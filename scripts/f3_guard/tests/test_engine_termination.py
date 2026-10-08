"""Hermetic unit tests for EngineTerminationHelper (8c2)."""

from __future__ import annotations

import dataclasses
import os
import time
import unittest
from unittest.mock import MagicMock, patch

from scripts.f3_guard.engine_claim import TerminationClaimTracker
from scripts.f3_guard.engine_termination import EngineTerminationHelper
from scripts.f3_guard.probe_lifecycle import ProbeLifecycle
from scripts.f3_guard.sigterm import DEFAULT_GRACE_SEC
from scripts.f3_guard.types import (
    LifecycleState,
    ProcessIdentity,
    TerminationClaimStatus,
)


class FalseyException(Exception):
    def __bool__(self) -> bool: return False


class TestEngineTerminationHelper(unittest.TestCase):
    """Hermetic unit test suite verifying EngineTerminationHelper contracts."""

    def setUp(self) -> None:
        self.fake_time = [1000.0]
        sp = patch.object(time, "sleep", side_effect=lambda s: self.fake_time.__setitem__(0, self.fake_time[0] + max(0.001, float(s))))
        mp = patch.object(time, "monotonic", side_effect=lambda: self.fake_time[0])
        kp = patch("os.kill", side_effect=AssertionError("Unexpected real os.kill call"))
        sp.start(); mp.start(); kp.start()
        self.addCleanup(sp.stop); self.addCleanup(mp.stop); self.addCleanup(kp.stop)

        self.proc_access_count = 0
        self._orig_open = open

        def _guarded_open(file: object, *args: object, **kwargs: object) -> object:
            if isinstance(getattr(file, "__fspath__", lambda: file)(), str) and str(file).startswith("/proc"):
                self.proc_access_count += 1
                raise AssertionError(f"Unexpected real /proc open: {file}")
            return self._orig_open(file, *args, **kwargs)

        op = patch("builtins.open", side_effect=_guarded_open)
        op.start()
        self.addCleanup(op.stop)

        self.target = ProcessIdentity(
            pid=12345, start_ticks=987654, uid=1000, avd="Trindade_UX_v030", port=5556, binary_name="qemu-system-x86_64",
        )
        self.controller = self._make_ctrl()
        self.probe_lifecycle = MagicMock(spec=ProbeLifecycle, has_probe_cleanup_failure=False, probe_cleanup_error=None)

    def tearDown(self) -> None:
        self.assertEqual(self.proc_access_count, 0)

    def _make_ctrl(self, ret=None, side_effect=None) -> MagicMock:
        c = MagicMock()
        if side_effect is not None: c.cleanup_bound_pidfd.side_effect = side_effect
        else: c.cleanup_bound_pidfd.return_value = ret
        return c

    def test_constructor_defaults_and_explicit_claim_tracker(self) -> None:
        h = EngineTerminationHelper(self.target, self.controller, self.probe_lifecycle)
        self.assertIs(h.target, self.target)
        self.assertIs(h.controller, self.controller)
        self.assertIs(h.probe_lifecycle, self.probe_lifecycle)
        self.assertIsInstance(h.claim_tracker, TerminationClaimTracker)
        self.assertEqual((h.grace_seconds, h.poll_interval, h.state), (DEFAULT_GRACE_SEC, 0.5, LifecycleState.RUNNING))
        self.assertIsNone(h.stop_reason)
        self.assertFalse(h.has_probe_cleanup_failure)
        self.assertIsNone(h.probe_cleanup_error)
        self.assertIsNone(h.attempt_record)

        tracker = TerminationClaimTracker(self.target, self.controller)
        h2 = EngineTerminationHelper(self.target, self.controller, self.probe_lifecycle, claim_tracker=tracker, grace_seconds=10.0, poll_interval=0.25)
        self.assertIs(h2.claim_tracker, tracker)
        self.assertEqual((h2.grace_seconds, h2.poll_interval), (10.0, 0.25))

    def test_clean_forward_exact_grace_poll_reason(self) -> None:
        h = EngineTerminationHelper(self.target, self.controller, self.probe_lifecycle, grace_seconds=15.0, poll_interval=0.2)
        with patch("scripts.f3_guard.engine_termination.safe_sigterm", return_value=(LifecycleState.STOPPED, "Process gracefully exited")) as mock_sig:
            halted, state = h.terminate("clean stop")
        self.assertEqual((halted, state), (True, LifecycleState.STOPPED))
        mock_sig.assert_called_once_with(self.target, self.controller, reason="clean stop", grace_seconds=15.0, poll_interval=0.2)
        self.controller.cleanup_bound_pidfd.assert_called_once()
        self.assertEqual((h.state, h.primary_state, h.termination_state, h.stop_reason), (LifecycleState.STOPPED, LifecycleState.STOPPED, LifecycleState.STOPPED, "clean stop"))
        self.assertIsNotNone(h.attempt_record)
        self.assertEqual((h.attempt_record.outcome.state, h.attempt_record.outcome.detail, h.attempt_record.outcome.completed), (LifecycleState.STOPPED, "clean stop", True))
        self.assertIsNone(h.attempt_record.outcome.primary_error)

    def test_real_probe_lifecycle_callback_runtime_error_diagnostic(self) -> None:
        real_pl = ProbeLifecycle(cleanup_probe=lambda: (_ for _ in ()).throw(RuntimeError("probe failure")))
        h = EngineTerminationHelper(self.target, self.controller, real_pl)
        with patch("scripts.f3_guard.engine_termination.safe_sigterm", return_value=(LifecycleState.STOPPED, "stopped")):
            halted, state = h.terminate("legacy stop")
        self.assertEqual((halted, state, h.has_probe_cleanup_failure), (True, LifecycleState.ERROR, True))
        self.assertIn("probe_cleanup exception: RuntimeError: probe failure", str(h.probe_cleanup_error))
        self.assertEqual(h.stop_reason, f"legacy stop (cleanup failed: {h.probe_cleanup_error})")
        self.assertEqual((h.attempt_record.outcome.state, h.attempt_record.outcome.completed), (LifecycleState.ERROR, True))
        self.assertIsNone(h.attempt_record.outcome.primary_error)
        self.controller.cleanup_bound_pidfd.assert_called_once()

    def test_injected_cleanup_teardown_raise_primary(self) -> None:
        exc = RuntimeError("teardown failure")
        self.probe_lifecycle.cleanup_teardown.side_effect = exc
        h = EngineTerminationHelper(self.target, self.controller, self.probe_lifecycle)
        with patch("scripts.f3_guard.engine_termination.safe_sigterm", return_value=(LifecycleState.STOPPED, "stopped")) as mock_sig:
            with self.assertRaises(RuntimeError) as ctx: h.terminate("aborted stop")
        self.assertIs(ctx.exception, exc)
        mock_sig.assert_called_once()
        self.controller.cleanup_bound_pidfd.assert_called_once()
        self.assertEqual((h.state, h.attempt_record.outcome.completed), (LifecycleState.ERROR, False))
        self.assertEqual((h.attempt_record.outcome.primary_error, h.attempt_record.outcome.primary_exception), (exc, exc))

    def test_cleanup_probe_false_and_pending_flags(self) -> None:
        real_pl = ProbeLifecycle(cleanup_probe=lambda: False)
        h = EngineTerminationHelper(self.target, self.controller, real_pl)
        with patch("scripts.f3_guard.engine_termination.safe_sigterm", return_value=(LifecycleState.STOPPED, "stopped")):
            halted, state = h.terminate("pending stop")
        self.assertEqual((halted, state, h.has_probe_cleanup_failure), (True, LifecycleState.ERROR, True))
        self.assertEqual(h.probe_cleanup_error, "cleanup_probe returned False (cleanup pending or failed)")
        self.assertEqual(h.stop_reason, "pending stop (cleanup failed: cleanup_probe returned False (cleanup pending or failed))")
        self.assertTrue(h.attempt_record.outcome.completed)

        h2 = EngineTerminationHelper(self.target, self.controller, self.probe_lifecycle)
        self.probe_lifecycle.has_probe_cleanup_failure = True
        self.probe_lifecycle.probe_cleanup_error = None
        with patch("scripts.f3_guard.engine_termination.safe_sigterm", return_value=(LifecycleState.STOPPED, "stopped")):
            h2.terminate("fallback stop")
        self.assertEqual(h2.probe_cleanup_error, "Probe teardown pending or un-reaped child handles remain")
        self.assertEqual(h2.stop_reason, "fallback stop (cleanup failed: Probe teardown pending or un-reaped child handles remain)")

    def test_controller_scenarios(self) -> None:
        h_no_bound = EngineTerminationHelper(self.target, MagicMock(spec=[]), self.probe_lifecycle)
        with patch("scripts.f3_guard.engine_termination.safe_sigterm", return_value=(LifecycleState.STOPPED, "stopped")):
            self.assertEqual(h_no_bound.terminate("no bound")[1], LifecycleState.STOPPED)

        scenarios = [
            ("ctrl_false", False, False, LifecycleState.ERROR, True, None, "Controller cleanup failed: False"),
            ("ctrl_oserr", OSError("io close err"), False, LifecycleState.ERROR, True, None, "Controller cleanup failed: io close err"),
            ("ctrl_runtime", RuntimeError("ctrl err"), True, LifecycleState.ERROR, True, None, "Controller cleanup exception: ctrl err"),
            ("ctrl_ki", KeyboardInterrupt("ctrl ki"), True, LifecycleState.ERROR, False, KeyboardInterrupt, None),
            ("ctrl_se", SystemExit(2), True, LifecycleState.ERROR, False, SystemExit, None),
        ]
        for name, ret_or_exc, is_raise, exp_state, exp_completed, exp_exc_cls, exp_err_str in scenarios:
            with self.subTest(scenario=name):
                ctrl = self._make_ctrl(side_effect=ret_or_exc) if is_raise else self._make_ctrl(ret=ret_or_exc)
                h = EngineTerminationHelper(self.target, ctrl, self.probe_lifecycle)
                with patch("scripts.f3_guard.engine_termination.safe_sigterm", return_value=(LifecycleState.STOPPED, "stopped")):
                    if exp_exc_cls is not None:
                        with self.assertRaises(exp_exc_cls) as ctx: h.terminate("ctrl stop")
                        self.assertIs(ctx.exception, ret_or_exc)
                        self.assertIs(h.attempt_record.outcome.primary_error, ret_or_exc)
                    else:
                        h.terminate("ctrl stop")
                        self.assertIsNone(h.attempt_record.outcome.primary_error)
                self.assertEqual((h.attempt_record.outcome.state, h.attempt_record.outcome.completed), (exp_state, exp_completed))
                if exp_err_str is not None: self.assertEqual(h.probe_cleanup_error, exp_err_str)

    def test_precedence_3_sources_matrix(self) -> None:
        t_ki, t_se, s_ki, s_se = KeyboardInterrupt("t ki"), SystemExit(1), KeyboardInterrupt("s ki"), SystemExit(2)
        c_ki, c_se, t_ord, s_ord = KeyboardInterrupt("c ki"), SystemExit(3), RuntimeError("t ord"), RuntimeError("s ord")
        f_t, f_s = FalseyException("f t"), FalseyException("f s")
        matrix = [
            ("ord_teardown_sig_ki_ctrl_ki", t_ord, s_ki, c_ki, s_ki),
            ("ord_teardown_ctrl_se", t_ord, None, c_se, c_se),
            ("falsey_teardown_ctrl_ki", f_t, None, c_ki, c_ki),
            ("falsey_sig_ctrl_se", None, f_s, c_se, c_se),
            ("interrupt_teardown_over_sig", t_se, s_ki, None, t_se),
            ("interrupt_sig_over_ctrl", None, s_ki, c_se, s_ki),
            ("ctrl_ki_alone", None, None, c_ki, c_ki),
            ("ctrl_runtime_diagnostic", None, None, RuntimeError("ctrl err"), None),
            ("ord_teardown_over_sig", t_ord, s_ord, None, t_ord),
            ("falsey_teardown_over_sig", f_t, s_ord, None, f_t),
            ("falsey_sig_alone", None, f_s, None, f_s),
        ]
        for name, te, se, ce, exp_raised in matrix:
            with self.subTest(case=name):
                ctrl = self._make_ctrl(side_effect=ce) if ce is not None else self._make_ctrl()
                pl = MagicMock(spec=ProbeLifecycle, has_probe_cleanup_failure=False, probe_cleanup_error=None)
                if te is not None: pl.cleanup_teardown.side_effect = te
                h = EngineTerminationHelper(self.target, ctrl, pl)
                with patch("scripts.f3_guard.engine_termination.safe_sigterm", side_effect=se if se is not None else lambda *a, **k: (LifecycleState.STOPPED, "ok")) as mock_sig:
                    if exp_raised is not None:
                        with self.assertRaises(type(exp_raised)) as ctx: h.terminate("matrix stop")
                        self.assertIs(ctx.exception, exp_raised)
                        self.assertIs(h.attempt_record.outcome.primary_error, exp_raised)
                        self.assertFalse(h.attempt_record.outcome.completed)
                    else:
                        h.terminate("matrix stop")
                        self.assertTrue(h.attempt_record.outcome.completed)
                    mock_sig.assert_called_once()
                    ctrl.cleanup_bound_pidfd.assert_called_once()
                self.assertEqual(h.attempt_record.outcome.state, LifecycleState.ERROR)

    def test_all_cleanup_calls_order_once(self) -> None:
        calls = []
        pl = MagicMock(spec=ProbeLifecycle, has_probe_cleanup_failure=False, probe_cleanup_error=None)
        pl.cleanup_teardown.side_effect = lambda: calls.append("teardown")
        ctrl = self._make_ctrl(side_effect=lambda: calls.append("controller"))
        h = EngineTerminationHelper(self.target, ctrl, pl)
        with patch("scripts.f3_guard.engine_termination.safe_sigterm", side_effect=lambda *a, **k: calls.append("sigterm") or (LifecycleState.STOPPED, "ok")):
            h.terminate("order stop")
        self.assertEqual(calls, ["teardown", "sigterm", "controller"])

    def test_sealed_abort_snapshot_in_progress_and_reentrant_terminate(self) -> None:
        ctrl = self._make_ctrl()
        mid_mismatch, mid_progress, h = None, None, None

        def hooked_teardown():
            nonlocal mid_mismatch, mid_progress
            mid_mismatch = h.claim_tracker.claim(dataclasses.replace(self.target, pid=99999), ctrl)
            mid_progress = h.claim_tracker.claim(self.target, ctrl)
            raise RuntimeError("abort during hook")

        pl = MagicMock(spec=ProbeLifecycle, has_probe_cleanup_failure=False, probe_cleanup_error=None)
        pl.cleanup_teardown.side_effect = hooked_teardown
        h = EngineTerminationHelper(self.target, ctrl, pl)

        with patch("scripts.f3_guard.engine_termination.safe_sigterm", return_value=(LifecycleState.STOPPED, "ok")) as mock_sig:
            with self.assertRaises(RuntimeError): h.terminate("aborted hook")
            self.assertEqual((mid_mismatch, mid_progress), (TerminationClaimStatus.MISMATCH, TerminationClaimStatus.IN_PROGRESS))
            self.assertIsNotNone(h.attempt_record)
            self.assertEqual((h.attempt_record.outcome.state, h.attempt_record.outcome.completed), (LifecycleState.ERROR, False))
            self.assertEqual(h.claim_tracker.claim(self.target, ctrl), TerminationClaimStatus.CLAIMED)
            self.assertEqual(h.claim_tracker.claim(self.target, ctrl), TerminationClaimStatus.ALREADY_CLAIMED)

            ret_ok, ret_state = h.terminate("reentrant stop")
            self.assertEqual((ret_ok, ret_state), (True, LifecycleState.ERROR))
            mock_sig.assert_called_once()
            ctrl.cleanup_bound_pidfd.assert_called_once()

    def test_reentrant_successful_terminate_no_resignal_recleanup(self) -> None:
        ctrl = self._make_ctrl()
        h = EngineTerminationHelper(self.target, ctrl, self.probe_lifecycle)
        with patch("scripts.f3_guard.engine_termination.safe_sigterm", return_value=(LifecycleState.STOPPED, "ok")) as mock_sig:
            ret1, state1 = h.terminate("first stop")
            ret2, state2 = h.terminate("repeat stop")
        self.assertEqual((ret1, state1, ret2, state2), (True, LifecycleState.STOPPED, True, LifecycleState.STOPPED))
        mock_sig.assert_called_once()
        ctrl.cleanup_bound_pidfd.assert_called_once()

    def test_integrated_probe_lifecycle_teardown_interrupts_and_reentrant_hook(self) -> None:
        for exc in (KeyboardInterrupt("probe ki"), SystemExit(2)):
            with self.subTest(exc=type(exc).__name__):
                ctrl = self._make_ctrl()
                hook_claim_res, reentry_res, h = None, None, None

                def hook():
                    nonlocal hook_claim_res, reentry_res
                    hook_claim_res = h.claim_tracker.claim(self.target, ctrl)
                    reentry_res = h.terminate("reentrant during hook")
                    raise exc

                h = EngineTerminationHelper(self.target, ctrl, ProbeLifecycle(cleanup_probe=hook))
                with patch("scripts.f3_guard.engine_termination.safe_sigterm", return_value=(LifecycleState.STOPPED, "ok")) as mock_sig:
                    with self.assertRaises(type(exc)) as ctx: h.terminate("outer stop")
                    self.assertIs(ctx.exception, exc)
                    mock_sig.assert_called_once()
                    ctrl.cleanup_bound_pidfd.assert_called_once()
                self.assertEqual((hook_claim_res, reentry_res), (TerminationClaimStatus.IN_PROGRESS, (True, LifecycleState.RUNNING)))
                self.assertEqual((h.state, h.attempt_record.outcome.state, h.attempt_record.outcome.completed), (LifecycleState.ERROR, LifecycleState.ERROR, False))
                self.assertIs(h.attempt_record.outcome.primary_error, exc)
                self.assertEqual(h.claim_tracker.claim(self.target, ctrl), TerminationClaimStatus.CLAIMED)
                self.assertEqual(h.claim_tracker.claim(self.target, ctrl), TerminationClaimStatus.ALREADY_CLAIMED)
