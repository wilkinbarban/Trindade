"""Hermetic unit tests for guarded supervisor runner contracts (8d)."""

from __future__ import annotations

from contextlib import ExitStack
import os
import select
import signal
import subprocess
import time
import unittest
from unittest.mock import MagicMock, patch

from scripts.f3_guard.clock import FakeClock
from scripts.f3_guard.controller import ProcessController
from scripts.f3_guard.engine import SupervisorEngine
from scripts.f3_guard.runner import run_guarded_supervisor, run_supervisor
from scripts.f3_guard.types import (
    LifecycleState,
    ProcessIdentity,
    TerminationOutcome,
)


class TestGuardedRunner(unittest.TestCase):
    """Hermetic unit tests for run_guarded_supervisor lifecycle and cleanup."""

    def setUp(self) -> None:
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.proc_access_count = 0
        self._orig_open = open

        def _guarded_open(file: object, *args: object, **kwargs: object) -> object:
            raw = getattr(file, "__fspath__", lambda: file)()
            s = str(raw.decode("utf-8", "surrogateescape") if isinstance(raw, bytes) else raw)
            if s.startswith("/proc") or "/proc/" in s:
                self.proc_access_count += 1
                raise AssertionError(f"Unexpected real /proc open: {file}")
            return self._orig_open(file, *args, **kwargs)

        self.stack.enter_context(patch("builtins.open", side_effect=_guarded_open))
        self.stack.enter_context(patch("os.open", side_effect=AssertionError("os.open")))
        self.stack.enter_context(patch("time.monotonic", side_effect=AssertionError("monotonic")))
        self.stack.enter_context(patch("time.time", side_effect=AssertionError("time")))
        self.stack.enter_context(patch("time.sleep", side_effect=AssertionError("sleep")))
        self.stack.enter_context(patch("os.kill", side_effect=AssertionError("os.kill")))
        self.stack.enter_context(patch("os.waitpid", side_effect=AssertionError("waitpid")))
        self.stack.enter_context(patch("select.poll", side_effect=AssertionError("poll")))
        for sub in ("Popen", "run", "call", "check_call", "check_output"):
            self.stack.enter_context(patch(f"subprocess.{sub}", side_effect=AssertionError(sub)))
        if hasattr(os, "pidfd_open"):
            self.stack.enter_context(patch("os.pidfd_open", side_effect=AssertionError("pidfd_open")))
        if hasattr(signal, "pidfd_send_signal"):
            self.stack.enter_context(patch("signal.pidfd_send_signal", side_effect=AssertionError("pidfd_send")))

        self.target = ProcessIdentity(pid=100, start_ticks=500, uid=1000, avd="test_avd", port=5554, binary_name="qemu")
        self.controller = MagicMock(spec=ProcessController)
        self.controller.cleanup_bound_pidfd.return_value = None
        self.sink: dict[str, object] = {}
        self.calls: list[str] = []
        self.mock_sigterm = self.stack.enter_context(
            patch("scripts.f3_guard.runner.safe_sigterm", side_effect=self._default_sigterm)
        )

    def tearDown(self) -> None:
        self.assertEqual(self.proc_access_count, 0)

    def _default_sigterm(self, *args: object, **kwargs: object) -> tuple[LifecycleState, str]:
        self.calls.append("sigterm")
        return LifecycleState.STOPPED, "Clean exit"

    def _make_engine(self, target: object = None, controller: object = None) -> SupervisorEngine:
        t = self.target if target is None else target
        c = self.controller if controller is None else controller
        return SupervisorEngine(t, c, clock=FakeClock(monotonic_start=10.0), cadence_sec=5.0, ttl_sec=100.0)

    def test_normal_run_and_alias(self) -> None:
        self.assertIs(run_supervisor, run_guarded_supervisor)
        st = run_guarded_supervisor(lambda: self.calls.append("runner"), self.target, self.controller, self.sink)
        self.assertEqual(st, LifecycleState.STOPPED)
        self.assertEqual(self.sink.get("state"), LifecycleState.STOPPED)
        self.assertEqual(self.sink.get("primary_state"), LifecycleState.STOPPED)
        self.assertEqual(self.sink.get("termination_detail"), "Clean exit")
        self.controller.cleanup_bound_pidfd.assert_called_once()
        self.assertEqual(self.calls, ["runner", "sigterm"])

    def test_runner_body_exceptions_and_interrupt_precedence(self) -> None:
        val_exc = ValueError("body crash")
        st = run_guarded_supervisor(MagicMock(side_effect=val_exc), self.target, self.controller, self.sink)
        self.assertEqual(st, LifecycleState.ERROR)
        self.assertIs(self.sink.get("body_exception"), val_exc)
        self.assertIn("body crash", str(self.sink.get("stop_reason")))
        self.assertEqual(self.sink.get("state"), LifecycleState.ERROR)

        for intr in (KeyboardInterrupt("stop"), SystemExit(42)):
            sink: dict[str, object] = {}
            with self.assertRaises(type(intr)) as cm:
                run_guarded_supervisor(MagicMock(side_effect=intr), self.target, self.controller, sink)
            self.assertIs(cm.exception, intr)
            self.assertIs(sink.get("body_exception"), intr)

    def test_probe_cleanup_outcomes(self) -> None:
        sink: dict[str, object] = {}
        st = run_guarded_supervisor(lambda: None, self.target, self.controller, sink, cleanup_probe=lambda: False)
        self.assertEqual(st, LifecycleState.ERROR)
        self.assertTrue(sink.get("probe_cleanup_failed"))

        adapter = MagicMock(has_pending_cleanup=True, cleanup_error="pending probes")
        adapter.cleanup.return_value = None
        adapter.cleanup.__self__ = adapter
        sink = {}
        st = run_guarded_supervisor(lambda: None, self.target, self.controller, sink, cleanup_probe=adapter.cleanup)
        self.assertEqual(st, LifecycleState.ERROR)
        self.assertIn("pending probes", str(sink.get("probe_cleanup_error")))

        adapter2 = MagicMock(has_pending_cleanup=False, has_probe_cleanup_failure=True, probe_cleanup_error="probe err")
        adapter2.cleanup.return_value = None
        adapter2.cleanup.__self__ = adapter2
        sink = {}
        run_guarded_supervisor(lambda: None, self.target, self.controller, sink, cleanup_probe=adapter2.cleanup)
        self.assertIn("probe err", str(sink.get("probe_cleanup_error")))

        sink = {}
        st = run_guarded_supervisor(
            lambda: None, self.target, self.controller, sink, cleanup_probe=MagicMock(side_effect=RuntimeError("probe err"))
        )
        self.assertEqual(st, LifecycleState.ERROR)
        self.assertEqual(sink.get("secondary_cleanup_cause"), "RuntimeError")

        ki = KeyboardInterrupt("probe ki")
        with self.assertRaises(KeyboardInterrupt) as cm:
            run_guarded_supervisor(lambda: None, self.target, self.controller, {}, cleanup_probe=MagicMock(side_effect=ki))
        self.assertIs(cm.exception, ki)

    def test_controller_cleanup_outcomes(self) -> None:
        for bad in (False, RuntimeError("pidfd failed")):
            sink: dict[str, object] = {}
            self.controller.cleanup_bound_pidfd.return_value = bad
            st = run_guarded_supervisor(lambda: None, self.target, self.controller, sink)
            self.assertEqual(st, LifecycleState.ERROR)
            self.assertTrue(sink.get("controller_cleanup_failed"))

        sink = {}
        self.controller.cleanup_bound_pidfd.side_effect = RuntimeError("pidfd raise")
        st = run_guarded_supervisor(lambda: None, self.target, self.controller, sink)
        self.assertEqual(st, LifecycleState.ERROR)
        self.assertIn("pidfd raise", str(sink.get("controller_cleanup_error")))

        ki_ctrl = KeyboardInterrupt("pidfd ki")
        self.controller.cleanup_bound_pidfd.side_effect = ki_ctrl
        with self.assertRaises(KeyboardInterrupt) as cm:
            run_guarded_supervisor(lambda: None, self.target, self.controller, {})
        self.assertIs(cm.exception, ki_ctrl)

    def test_cleanup_chronology_and_precedence_matrix(self) -> None:
        self.controller.cleanup_bound_pidfd.side_effect = lambda: self.calls.append("controller")
        probe = lambda: self.calls.append("probe")
        run_guarded_supervisor(lambda: self.calls.append("runner"), self.target, self.controller, self.sink, cleanup_probe=probe)
        self.assertEqual(self.calls, ["runner", "probe", "sigterm", "controller"])

        ki, se = KeyboardInterrupt("probe_ki"), SystemExit(9)
        self.controller.cleanup_bound_pidfd.side_effect = se
        with self.assertRaises(KeyboardInterrupt) as cm:
            run_guarded_supervisor(lambda: None, self.target, self.controller, {}, cleanup_probe=MagicMock(side_effect=ki))
        self.assertIs(cm.exception, ki)

        body_val = ValueError("body_val")
        with self.assertRaises(KeyboardInterrupt) as cm:
            run_guarded_supervisor(MagicMock(side_effect=body_val), self.target, self.controller, self.sink, cleanup_probe=MagicMock(side_effect=ki))
        self.assertIs(cm.exception, ki)

    def test_safe_sigterm_results_and_exceptions(self) -> None:
        self.mock_sigterm.side_effect = None
        self.mock_sigterm.return_value = (LifecycleState.ERROR, "kill failed")
        st = run_guarded_supervisor(lambda: None, self.target, self.controller, self.sink)
        self.assertEqual(st, LifecycleState.ERROR)
        self.assertEqual(self.sink.get("termination_detail"), "kill failed")

        self.mock_sigterm.side_effect = RuntimeError("sigterm crash")
        st = run_guarded_supervisor(lambda: None, self.target, self.controller, self.sink)
        self.assertEqual(st, LifecycleState.ERROR)
        self.assertIn("sigterm crash", str(self.sink.get("target_termination_error")))

        ki = KeyboardInterrupt("sigterm ki")
        self.mock_sigterm.side_effect = ki
        with self.assertRaises(KeyboardInterrupt) as cm:
            run_guarded_supervisor(lambda: None, self.target, self.controller, {})
        self.assertIs(cm.exception, ki)

    def test_engine_claims_and_duck_rejection(self) -> None:
        fake = MagicMock()
        st = run_guarded_supervisor(lambda: None, self.target, self.controller, self.sink, engine=fake)
        self.assertEqual(st, LifecycleState.ERROR)
        self.assertEqual(self.sink.get("termination_detail"), "Invalid engine instance")
        self.mock_sigterm.assert_not_called()

        eng_claimed = self._make_engine()
        eng_claimed._claim_tracker.record_attempt()
        eng_claimed._claim_tracker.seal_outcome(TerminationOutcome(completed=True, state=LifecycleState.STOPPED, detail="Engine ok"))
        sink: dict[str, object] = {}
        st = run_guarded_supervisor(lambda: None, self.target, self.controller, sink, engine=eng_claimed)
        self.assertEqual(st, LifecycleState.STOPPED)
        self.assertEqual(sink.get("termination_detail"), "Engine ok")
        self.mock_sigterm.assert_not_called()

        eng_bad = self._make_engine()
        eng_bad._claim_tracker.record_attempt()
        eng_bad._claim_tracker.seal_outcome(TerminationOutcome(completed=False, state=LifecycleState.ERROR, primary_error="err"))
        sink = {}
        st = run_guarded_supervisor(lambda: None, self.target, self.controller, sink, engine=eng_bad)
        self.assertEqual(st, LifecycleState.ERROR)
        self.assertIn("Engine outcome invalid", str(sink.get("termination_detail")))

        eng_unatt = self._make_engine()
        sink = {}
        st = run_guarded_supervisor(lambda: None, self.target, self.controller, sink, engine=eng_unatt)
        self.assertEqual(st, LifecycleState.STOPPED)
        self.mock_sigterm.assert_called_once()

        eng_mismatch = self._make_engine()
        other = ProcessIdentity(pid=999, start_ticks=1, uid=1, avd="other", port=5556, binary_name="q")
        sink = {}
        st = run_guarded_supervisor(lambda: None, other, self.controller, sink, engine=eng_mismatch)
        self.assertEqual(st, LifecycleState.ERROR)
        self.assertIn("Engine claim status", str(sink.get("termination_detail")))

        eng_raise = self._make_engine()
        sink = {}
        with patch.object(SupervisorEngine, "claim", side_effect=RuntimeError("claim broke")):
            st = run_guarded_supervisor(lambda: None, self.target, self.controller, sink, engine=eng_raise)
        self.assertEqual(st, LifecycleState.ERROR)
        self.assertIn("Target termination exception: claim broke", str(sink.get("termination_detail")))

    def test_pidfd_cleanup_and_signal_count_invariants(self) -> None:
        self.controller.cleanup_bound_pidfd.return_value = None
        run_guarded_supervisor(lambda: None, self.target, self.controller, self.sink)
        self.controller.cleanup_bound_pidfd.assert_called_once()
        self.assertEqual(self.mock_sigterm.call_count, 1)
        self.assertEqual(self.sink.get("primary_state"), LifecycleState.STOPPED)
        self.assertEqual(self.sink.get("state"), LifecycleState.STOPPED)
