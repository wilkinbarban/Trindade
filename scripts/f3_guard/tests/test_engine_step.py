"""Hermetic unit tests for SupervisorEngine step contracts (8c4b)."""

from __future__ import annotations

from contextlib import ExitStack
import os
import signal
import subprocess
import time
import unittest
from unittest.mock import MagicMock, patch

from scripts.f3_guard.clock import FakeClock
from scripts.f3_guard.engine import SupervisorEngine
from scripts.f3_guard.readiness import _ProbeState
from scripts.f3_guard.types import LifecycleState, ProbeStatus, ProcessIdentity


class TestSupervisorEngineStep(unittest.TestCase):
    """Hermetic unit tests verifying SupervisorEngine.step discrete cycle contracts.

    Bounded hermetism guarantee: intercepts reachable proc paths, os.open, clock
    (time.monotonic/time.time), subprocesses, and pidfd/signals exercised by step
    logic. This is a bounded test guarantee, not a universal sandbox for all Python I/O.
    """

    def setUp(self) -> None:
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.proc_access_count = 0
        self._orig_open = open

        def _guarded_open(file: object, *args: object, **kwargs: object) -> object:
            raw = getattr(file, "__fspath__", lambda: file)()
            s = str(raw.decode("utf-8", "surrogateescape") if isinstance(raw, bytes) else raw)
            if s.startswith("/proc") or "/proc/" in s or s.startswith("proc/") or s.startswith("./proc"):
                self.proc_access_count += 1
                raise AssertionError(f"Unexpected real /proc open: {file}")
            return self._orig_open(file, *args, **kwargs)

        self.stack.enter_context(patch("builtins.open", side_effect=_guarded_open))
        self.stack.enter_context(patch("os.open", side_effect=AssertionError("Unexpected real os.open")))
        self.stack.enter_context(patch("time.monotonic", side_effect=AssertionError("Unexpected real time.monotonic")))
        self.stack.enter_context(patch("time.time", side_effect=AssertionError("Unexpected real time.time")))
        self.stack.enter_context(patch("time.sleep", side_effect=AssertionError("Unexpected real time.sleep")))
        self.stack.enter_context(patch("os.kill", side_effect=AssertionError("Unexpected real os.kill")))
        self.stack.enter_context(patch("os.waitpid", side_effect=AssertionError("Unexpected real os.waitpid")))
        self.stack.enter_context(patch("select.poll", side_effect=AssertionError("Unexpected real select.poll")))
        for sub in ("Popen", "run", "call", "check_call", "check_output"):
            self.stack.enter_context(patch(f"subprocess.{sub}", side_effect=AssertionError(f"Unexpected real subprocess.{sub}")))
        self.stack.enter_context(
            patch("scripts.f3_guard.engine_termination.safe_sigterm", return_value=(LifecycleState.STOPPED, 100))
        )
        if hasattr(os, "pidfd_open"):
            self.stack.enter_context(patch("os.pidfd_open", side_effect=AssertionError("Unexpected real os.pidfd_open")))
        if hasattr(signal, "pidfd_send_signal"):
            self.stack.enter_context(patch("signal.pidfd_send_signal", side_effect=AssertionError("Unexpected real signal.pidfd_send_signal")))

        self.target = ProcessIdentity(
            pid=100, start_ticks=500, uid=1000, avd="test_avd", port=5554, binary_name="qemu-system-x86_64",
        )
        self.controller = MagicMock()
        self.controller.get_start_ticks.return_value = 500
        self.controller.get_uid.return_value = 1000
        self.controller.get_cmdline.return_value = [
            "/usr/bin/qemu-system-x86_64", "-avd", "test_avd", "-port", "5554",
        ]
        self.controller.cleanup_bound_pidfd.return_value = None
        self.clock = FakeClock(monotonic_start=10.0)

    def tearDown(self) -> None:
        self.assertEqual(self.proc_access_count, 0)

    def _create_engine(self, **kwargs: object) -> SupervisorEngine:
        opts: dict[str, object] = {
            "clock": self.clock, "cadence_sec": 5.0, "ttl_sec": 100.0,
            "stale_probe_sec": 15.0, "probe_deadline_sec": 10.0,
        }
        opts.update(kwargs)
        return SupervisorEngine(self.target, self.controller, **opts)

    def test_ttl_pre_and_post_probe_exit(self) -> None:
        # 1. Pre-probe TTL check fires first
        e_pre = self._create_engine(ttl_sec=10.0)
        self.clock.advance_monotonic(15.0)
        halted, state = e_pre.step()
        self.assertTrue(halted)
        self.assertEqual(state, LifecycleState.STOPPED)
        self.assertEqual(e_pre.stop_reason, "Supervision TTL expired (10.0s monotonic)")
        self.controller.get_start_ticks.assert_not_called()

        # 2. Post-probe TTL check fires after probe execution
        self.clock = FakeClock(monotonic_start=10.0)
        def _advancing_probe() -> None:
            self.clock.advance_monotonic(35.0)
        e_post = self._create_engine(ttl_sec=25.0, probe_deadline_sec=100.0, start_probe=_advancing_probe)
        e_post.record_probe_result(ProbeStatus.CLEAR, 10.0)
        halted, state = e_post.step()
        self.assertTrue(halted)
        self.assertEqual(state, LifecycleState.STOPPED)
        self.assertEqual(e_post.stop_reason, "Supervision TTL expired after probe (25.0s monotonic)")

    def test_target_identity_early_exit(self) -> None:
        cases = [
            ("ticks", {"get_start_ticks.return_value": 999}),
            ("uid", {"get_uid.return_value": 2000}),
            ("cmdline_empty", {"get_cmdline.return_value": []}),
            ("binary", {"get_cmdline.return_value": ["/bin/wrong", "-avd", "test_avd", "-port", "5554"]}),
        ]
        for name, config in cases:
            with self.subTest(identity_case=name):
                mem_reader = MagicMock()
                self.controller.configure_mock(**config)
                eng = self._create_engine(mem_reader=mem_reader)
                halted, state = eng.step()
                self.assertTrue(halted)
                self.assertEqual(state, LifecycleState.STOPPED)
                self.assertTrue(str(eng.stop_reason).startswith("Target identity failure:"))
                mem_reader.assert_not_called()
                # Restore controller defaults
                self.controller.get_start_ticks.return_value = 500
                self.controller.get_uid.return_value = 1000
                self.controller.get_cmdline.return_value = [
                    "/usr/bin/qemu-system-x86_64", "-avd", "test_avd", "-port", "5554",
                ]

    def test_memory_first_cadence_boundary_and_threshold(self) -> None:
        reader = MagicMock(return_value=(2000000, 1000000))
        eng = self._create_engine(mem_reader=reader, cadence_sec=5.0)
        eng.record_probe_result(ProbeStatus.CLEAR, self.clock.monotonic())

        # First step: samples regardless of elapsed cadence
        self.assertTrue(eng._first_step)
        halted, _ = eng.step()
        self.assertFalse(halted)
        self.assertEqual(reader.call_count, 1)
        self.assertEqual(eng._last_mem_sample, (2000000, 1000000))
        self.assertEqual(eng._last_mem_sample_mono, 10.0)

        # Within cadence: does not sample
        self.clock.advance_monotonic(2.0)
        halted, _ = eng.step()
        self.assertFalse(halted)
        self.assertEqual(reader.call_count, 1)

        # Boundary (now - last == cadence): samples
        self.clock.advance_monotonic(3.0)
        halted, _ = eng.step()
        self.assertFalse(halted)
        self.assertEqual(reader.call_count, 2)
        self.assertEqual(eng._last_mem_sample_mono, 15.0)

        # Bad threshold matrix
        bad_samples = [
            ((500000, 1000000), "Available RAM 500000 kB below minimum 1048576 kB"),
            ((2000000, 100000), "Free Swap 100000 kB below minimum 524288 kB"),
            (("bad", 1000000), "Memory metric must be strict integer kB"),
        ]
        for sample, err_frag in bad_samples:
            with self.subTest(sample=sample):
                b_reader = MagicMock(return_value=sample)
                b_eng = self._create_engine(mem_reader=b_reader)
                halted, state = b_eng.step()
                self.assertTrue(halted)
                self.assertEqual(state, LifecycleState.STOPPED)
                self.assertEqual(b_eng.stop_reason, f"Memory threshold violation: {err_frag}")

    def test_memory_reader_exceptions_and_interrupts(self) -> None:
        # Ordinary exception terminated
        eng = self._create_engine(mem_reader=MagicMock(side_effect=RuntimeError("mem error")))
        halted, state = eng.step()
        self.assertTrue(halted)
        self.assertEqual(state, LifecycleState.STOPPED)
        self.assertIn("Memory read/parse failure: mem error", str(eng.stop_reason))

        # Unpack failure (TypeError) terminated
        eng_unpack = self._create_engine(mem_reader=MagicMock(return_value=12345))
        halted, state = eng_unpack.step()
        self.assertTrue(halted)
        self.assertEqual(state, LifecycleState.STOPPED)
        self.assertIn("Memory read/parse failure:", str(eng_unpack.stop_reason))

        # KeyboardInterrupt propagates without termination
        eng_ki = self._create_engine(mem_reader=MagicMock(side_effect=KeyboardInterrupt("mem interrupt")))
        with self.assertRaises(KeyboardInterrupt):
            eng_ki.step()
        self.assertEqual(eng_ki.state, LifecycleState.RUNNING)

        # SystemExit propagates without termination
        eng_se = self._create_engine(mem_reader=MagicMock(side_effect=SystemExit(2)))
        with self.assertRaises(SystemExit):
            eng_se.step()
        self.assertEqual(eng_se.state, LifecycleState.RUNNING)

    def test_probe_overrun_and_execution_failure(self) -> None:
        # Overrun
        eng_ov = self._create_engine(probe_deadline_sec=5.0, start_probe=MagicMock())
        eng_ov.record_probe_result(ProbeStatus.CLEAR, self.clock.monotonic())
        eng_ov._probe_lifecycle._probe_state = _ProbeState.RUNNING
        eng_ov._probe_lifecycle._probe_start_mono = 10.0
        self.clock.advance_monotonic(10.0)
        halted, state = eng_ov.step()
        self.assertTrue(halted)
        self.assertEqual(state, LifecycleState.STOPPED)
        self.assertEqual(eng_ov.stop_reason, "Health probe overrun (>5.0s without completion)")

        # Start probe execution failure
        eng_start = self._create_engine(start_probe=MagicMock(side_effect=RuntimeError("start err")))
        eng_start.record_probe_result(ProbeStatus.CLEAR, self.clock.monotonic())
        halted, state = eng_start.step()
        self.assertTrue(halted)
        self.assertEqual(state, LifecycleState.STOPPED)
        self.assertEqual(eng_start.stop_reason, "Health probe execution failure: start err")

        # Poll probe execution failure
        eng_poll = self._create_engine(
            start_probe=MagicMock(),
            poll_probe=MagicMock(side_effect=RuntimeError("poll err")),
        )
        eng_poll.record_probe_result(ProbeStatus.CLEAR, self.clock.monotonic())
        eng_poll._probe_lifecycle._probe_state = _ProbeState.RUNNING
        eng_poll._probe_lifecycle._probe_start_mono = 10.0
        halted, state = eng_poll.step()
        self.assertTrue(halted)
        self.assertEqual(state, LifecycleState.STOPPED)
        self.assertEqual(eng_poll.stop_reason, "Health probe execution failure: poll err")

    def test_probe_stale_and_unknown_running_policy(self) -> None:
        # Stale probe check (with CLEAR status)
        eng_stale = self._create_engine(stale_probe_sec=10.0)
        eng_stale.record_probe_result(ProbeStatus.CLEAR, 10.0)
        self.clock.advance_monotonic(15.0)
        halted, state = eng_stale.step()
        self.assertTrue(halted)
        self.assertEqual(state, LifecycleState.STOPPED)
        self.assertEqual(eng_stale.stop_reason, "Health probe stale (>10.0s without fresh update)")

        # Stale probe check with UNKNOWN status (approved R3-STALE-BYPASS fix)
        eng_stale_unk = self._create_engine(stale_probe_sec=10.0)
        eng_stale_unk._probe_state = _ProbeState.RUNNING
        eng_stale_unk.last_probe_mono = 10.0
        self.clock.advance_monotonic(15.0)
        halted, state = eng_stale_unk.step()
        self.assertTrue(halted)
        self.assertEqual(state, LifecycleState.STOPPED)
        self.assertEqual(eng_stale_unk.stop_reason, "Health probe stale (>10.0s without fresh update)")

        # UNKNOWN status allowed while running (non-stale)
        eng_run = self._create_engine(start_probe=MagicMock(), stale_probe_sec=15.0)
        self.assertEqual(eng_run.last_probe_status, ProbeStatus.UNKNOWN)
        halted, state = eng_run.step()
        self.assertEqual(eng_run._probe_state, _ProbeState.RUNNING)
        self.assertFalse(halted)
        self.assertEqual(state, LifecycleState.RUNNING)

        # UNKNOWN status idle fails
        eng_idle = self._create_engine(stale_probe_sec=15.0)
        self.assertEqual(eng_idle._probe_state, _ProbeState.IDLE)
        self.assertEqual(eng_idle.last_probe_status, ProbeStatus.UNKNOWN)
        halted, state = eng_idle.step()
        self.assertTrue(halted)
        self.assertEqual(state, LifecycleState.STOPPED)
        self.assertEqual(eng_idle.stop_reason, "Health probe failure: unknown")

    def test_probe_health_status_errors(self) -> None:
        for status in (ProbeStatus.ANR_DETECTED, ProbeStatus.PROBE_ERROR):
            with self.subTest(status=status):
                eng = self._create_engine()
                eng.record_probe_result(status, self.clock.monotonic())
                halted, state = eng.step()
                self.assertTrue(halted)
                self.assertEqual(state, LifecycleState.STOPPED)
                self.assertEqual(eng.stop_reason, f"Health probe failure: {status.value}")

    def test_readiness_evaluation_and_skip_when_ready(self) -> None:
        # Not ready: check_readiness called
        eng_unready = self._create_engine()
        eng_unready.record_probe_result(ProbeStatus.CLEAR, self.clock.monotonic())
        self.assertFalse(eng_unready.is_ready)
        with patch.object(eng_unready, "check_readiness", wraps=eng_unready.check_readiness) as mock_ready:
            halted, state = eng_unready.step()
            self.assertFalse(halted)
            mock_ready.assert_called_once()

        # Ready: check_readiness skipped
        eng_ready = self._create_engine()
        eng_ready.record_probe_result(ProbeStatus.CLEAR, self.clock.monotonic())
        eng_ready.is_ready = True
        with patch.object(eng_ready, "check_readiness", wraps=eng_ready.check_readiness) as mock_ready:
            halted, state = eng_ready.step()
            self.assertFalse(halted)
            mock_ready.assert_not_called()

    def test_first_step_flag_and_state_return_progression(self) -> None:
        eng = self._create_engine()
        eng.record_probe_result(ProbeStatus.CLEAR, self.clock.monotonic())
        self.assertTrue(eng._first_step)

        # Cycle 1
        halted, state = eng.step()
        self.assertFalse(halted)
        self.assertEqual(state, LifecycleState.RUNNING)
        self.assertFalse(eng._first_step)

        # Cycle 2
        halted2, state2 = eng.step()
        self.assertFalse(halted2)
        self.assertEqual(state2, LifecycleState.RUNNING)
        self.assertFalse(eng._first_step)


if __name__ == "__main__":
    unittest.main()
