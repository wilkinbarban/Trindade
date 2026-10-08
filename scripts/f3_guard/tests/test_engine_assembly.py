"""Hermetic unit tests for SupervisorEngine assembly (8c4a)."""

from __future__ import annotations

import unittest
from unittest.mock import MagicMock, patch

from scripts.f3_guard.clock import FakeClock
from scripts.f3_guard.engine import DEFAULT_CADENCE_SEC, SupervisorEngine
from scripts.f3_guard.readiness import _ProbeState
from scripts.f3_guard.sigterm import DEFAULT_GRACE_SEC
from scripts.f3_guard.types import (
    ANREvent,
    BootEvidence,
    LifecycleState,
    ProbeStatus,
    ProcessIdentity,
    TerminationClaimStatus,
)


class TestSupervisorEngineAssembly(unittest.TestCase):
    """Hermetic unit tests verifying SupervisorEngine assembly contracts."""

    def setUp(self) -> None:
        self.proc_access_count = 0
        self._orig_open = open

        def _guarded_open(file: object, *args: object, **kwargs: object) -> object:
            if isinstance(getattr(file, "__fspath__", lambda: file)(), str) and str(file).startswith("/proc"):
                self.proc_access_count += 1
                raise AssertionError(f"Unexpected real /proc open: {file}")
            return self._orig_open(file, *args, **kwargs)

        op = patch("builtins.open", side_effect=_guarded_open)
        kp = patch("os.kill", side_effect=AssertionError("Unexpected real os.kill call"))
        op.start()
        kp.start()
        self.addCleanup(op.stop)
        self.addCleanup(kp.stop)

        self.target = ProcessIdentity(pid=100, start_ticks=500, uid=1000, avd="test_avd", port=5554, binary_name="qemu-system-x86_64")
        self.controller = MagicMock()
        self.controller.get_start_ticks.return_value, self.controller.get_uid.return_value = 500, 1000
        self.controller.get_cmdline.return_value = ["/usr/bin/qemu-system-x86_64", "-avd", "test_avd", "-port", "5554"]
        self.controller.cleanup_bound_pidfd.return_value = None
        self.clock = FakeClock(monotonic_start=10.0)

    def tearDown(self) -> None:
        self.assertEqual(self.proc_access_count, 0)

    def test_constructor_defaults_and_custom_propagation(self) -> None:
        engine = SupervisorEngine(self.target, self.controller, clock=self.clock)
        self.assertIs(engine.target, self.target)
        self.assertIs(engine.controller, self.controller)
        for attr, expected in [
            ("_cadence_sec", DEFAULT_CADENCE_SEC), ("_ttl_sec", 1200.0),
            ("_stale_probe_sec", 15.0), ("_probe_deadline_sec", 15.0),
            ("_grace_sec", DEFAULT_GRACE_SEC), ("_poll_interval", 0.5),
        ]:
            self.assertEqual(getattr(engine, attr), expected)
        self.assertTrue(engine.baseline_established)
        self.assertEqual(engine.state, LifecycleState.RUNNING)
        self.assertFalse(engine.is_ready)
        self.assertEqual(engine.last_probe_status, ProbeStatus.UNKNOWN)
        self.assertFalse(engine.has_probe_cleanup_failure)
        self.assertIsNone(engine.probe_cleanup_error)
        self.assertIsNone(engine.secondary_error)
        # Shared ownership across helpers
        self.assertTrue(engine._clock is self.clock is engine._probe_lifecycle._clock is engine._readiness_helper.clock is engine.ttl_tracker._clock)
        self.assertIs(engine._claim_tracker, engine._termination_helper.claim_tracker)
        self.assertTrue(engine._probe_lifecycle is engine._readiness_helper.probe_lifecycle is engine._termination_helper.probe_lifecycle)
        # Dynamic transport ownership: buffer/handles not constructed on engine
        self.assertFalse(hasattr(engine, "_drain_buffer"))
        self.assertFalse(hasattr(engine, "_command_handle"))
        self.assertEqual(self.proc_access_count, 0)
        # Custom parameters propagation subtest
        anr = ANREvent(package="com.example.app", reason="anr")
        c_eng = SupervisorEngine(
            self.target, self.controller, clock=self.clock,
            cadence_sec=7.0, ttl_sec=500.0, stale_probe_sec=22.0, probe_deadline_sec=18.0,
            grace_sec=12.0, poll_interval=1.0, baseline_events={anr}, baseline_established=False,
        )
        for obj, attr, val in [
            (c_eng, "_cadence_sec", 7.0), (c_eng, "_ttl_sec", 500.0), (c_eng.ttl_tracker, "_ttl_sec", 500.0),
            (c_eng, "_stale_probe_sec", 22.0), (c_eng._probe_lifecycle, "stale_probe_sec", 22.0),
            (c_eng, "_probe_deadline_sec", 18.0), (c_eng._probe_lifecycle, "probe_deadline_sec", 18.0),
            (c_eng, "_grace_sec", 12.0), (c_eng._termination_helper, "grace_seconds", 12.0),
            (c_eng, "_poll_interval", 1.0), (c_eng._termination_helper, "poll_interval", 1.0),
        ]:
            self.assertEqual(getattr(obj, attr), val)
        self.assertEqual(c_eng._baseline_events, {anr})
        self.assertFalse(c_eng.baseline_established)
        self.assertFalse(c_eng._readiness_helper.baseline_established)

    def test_mutable_owned_properties_bidirectional_subtests(self) -> None:
        engine = SupervisorEngine(self.target, self.controller, clock=self.clock)
        cases = [
            ("is_ready", engine._readiness_helper, "is_ready", True, False),
            ("readiness_details", engine._readiness_helper, "readiness_details", {"a": 1}, {"b": 2}),
            ("_last_mem_sample", engine._readiness_helper, "_last_mem_sample", (100, 200), (300, 400)),
            ("boot_evidence", engine._readiness_helper, "boot_evidence", "boot1", "boot2"),
            ("baseline_established", engine._readiness_helper, "baseline_established", False, True),
            ("state", engine._termination_helper, "state", LifecycleState.STOPPED, LifecycleState.RUNNING),
            ("stop_reason", engine._termination_helper, "stop_reason", "reason1", "reason2"),
            ("has_probe_cleanup_failure", engine._termination_helper, "has_probe_cleanup_failure", True, False),
            ("probe_cleanup_error", engine._termination_helper, "probe_cleanup_error", "err1", "err2"),
            ("primary_state", engine._termination_helper, "primary_state", LifecycleState.ERROR, LifecycleState.STOPPED),
            ("termination_state", engine._termination_helper, "termination_state", LifecycleState.ERROR, LifecycleState.STOPPED),
            ("primary_error", engine._termination_helper, "primary_error", RuntimeError("e1"), RuntimeError("e2")),
            ("last_probe_status", engine._probe_lifecycle, "last_probe_status", ProbeStatus.CLEAR, ProbeStatus.PROBE_ERROR),
            ("last_probe_mono", engine._probe_lifecycle, "last_probe_mono", 123.0, 456.0),
            ("_probe_state", engine._probe_lifecycle, "_probe_state", _ProbeState.RUNNING, _ProbeState.IDLE),
            ("_probe_start_mono", engine._probe_lifecycle, "_probe_start_mono", 22.0, 33.0),
            ("_probe_deadline_sec", engine._probe_lifecycle, "_probe_deadline_sec", 45.0, 50.0),
            ("_cleanup_probe", engine._probe_lifecycle, "_cleanup_probe", lambda: 1, lambda: 2),
            ("_start_probe", engine._probe_lifecycle, "_start_probe", lambda: 3, lambda: 4),
            ("_poll_probe", engine._probe_lifecycle, "_poll_probe", lambda: 5, lambda: 6),
            ("_probe_runner", engine._probe_lifecycle, "_probe_runner", lambda: 7, lambda: 8),
        ]
        for name, helper, h_attr, val1, val2 in cases:
            with self.subTest(property=name):
                setattr(engine, name, val1)
                self.assertEqual(getattr(helper, h_attr), val1)
                self.assertEqual(getattr(engine, name), val1)
                setattr(helper, h_attr, val2)
                self.assertEqual(getattr(engine, name), val2)

    def test_claims_properties_api_forwarding_and_aliases(self) -> None:
        engine = SupervisorEngine(self.target, self.controller, clock=self.clock)
        self.assertTrue(engine.attempt_record is engine.termination_attempt is engine._claim_tracker.attempt_record)
        self.assertIs(engine.termination_outcome, engine._claim_tracker.termination_outcome)
        self.assertIs(engine.export_claim_snapshot(), engine._claim_tracker.export_claim_snapshot())
        self.assertTrue(engine._claimed is engine._claim_tracker._claimed and engine._attempt_record is engine._claim_tracker._attempt_record)
        self.assertIs(SupervisorEngine.claim_termination, SupervisorEngine.claim)
        # Pre-termination: attempt_record and outcome None, claim is NOT_ATTEMPTED
        self.assertIsNone(engine.attempt_record)
        self.assertIsNone(engine.termination_outcome)
        self.assertEqual(engine.claim(self.target, self.controller), TerminationClaimStatus.NOT_ATTEMPTED)
        # Post-termination: attempt_record non-None, sealed outcome, transitions to CLAIMED then ALREADY_CLAIMED
        with patch("scripts.f3_guard.engine_termination.safe_sigterm", return_value=(LifecycleState.STOPPED, 100)):
            engine._terminate("claim_test")
        self.assertIsNotNone(engine.attempt_record)
        self.assertIsNotNone(engine.termination_outcome)
        self.assertIs(engine.export_claim_snapshot(), engine.attempt_record)
        self.assertEqual(engine.claim(self.target, self.controller), TerminationClaimStatus.CLAIMED)
        self.assertTrue(engine._claimed)
        self.assertEqual(engine.claim(self.target, self.controller), TerminationClaimStatus.ALREADY_CLAIMED)

    def test_readiness_delegation_caching_and_exceptions(self) -> None:
        mem_reader = MagicMock(return_value=(2000000, 1000000))
        valid_boot = BootEvidence(boot_completed=True, pid=100, start_ticks=500, timestamp_mono=10.0, avd="test_avd", uid=1000)
        engine = SupervisorEngine(self.target, self.controller, clock=self.clock, mem_reader=mem_reader, boot_evidence=valid_boot)
        engine.record_probe_result(ProbeStatus.CLEAR, 10.0)
        # Missing sample gets cached
        ready, details = engine.check_readiness()
        self.assertTrue(ready)
        self.assertEqual(engine._last_mem_sample, (2000000, 1000000))
        self.assertEqual(mem_reader.call_count, 1)
        # Zero sample is preserved and not re-read
        engine._last_mem_sample = (0, 0)
        engine.check_readiness()
        self.assertEqual((mem_reader.call_count, engine._last_mem_sample), (1, (0, 0)))
        # Ordinary reader Exception swallowed, cached sample reset
        engine._last_mem_sample, mem_reader.side_effect = (None, None), RuntimeError("mem error")
        engine.check_readiness()
        self.assertEqual(engine._last_mem_sample, (None, None))
        # Interrupt in boot callable propagates without mutating owned cache
        engine._last_mem_sample = (5000, 2000)
        engine.boot_evidence = MagicMock(side_effect=KeyboardInterrupt("boot interrupt"))
        with self.assertRaises(KeyboardInterrupt):
            engine.check_readiness()
        self.assertEqual(engine._last_mem_sample, (5000, 2000))

    def test_probe_live_properties_and_adapter_precedence(self) -> None:
        engine = SupervisorEngine(self.target, self.controller, clock=self.clock)
        engine.record_probe_result(ProbeStatus.CLEAR, 99.0)
        self.assertEqual(engine.last_probe_status, ProbeStatus.CLEAR)
        self.assertEqual(engine.last_probe_mono, 99.0)
        # Adapter precedence and callback execution via owned ProbeLifecycle
        adapter = MagicMock()
        adapter.start_probe, adapter.poll_probe, adapter.cancel = MagicMock(), MagicMock(return_value=(True, (0, "", "", False))), MagicMock(return_value=True)
        e_adapt = SupervisorEngine(self.target, self.controller, adapter=adapter)
        self.assertIs(e_adapt._start_probe, adapter.start_probe)
        e_adapt._probe_lifecycle.start_cycle()
        adapter.start_probe.assert_called_once()
        self.assertEqual(e_adapt._probe_state, _ProbeState.RUNNING)
        e_adapt._probe_lifecycle.poll_cycle()
        adapter.poll_probe.assert_called_once()
        self.assertEqual(e_adapt._probe_state, _ProbeState.IDLE)
        e_adapt._cleanup_probe_teardown()
        adapter.cancel.assert_called_once()
        # Custom override precedence and execution
        custom_start = MagicMock()
        e_override = SupervisorEngine(self.target, self.controller, adapter=adapter, start_probe=custom_start)
        self.assertIs(e_override._start_probe, custom_start)
        e_override._probe_lifecycle.start_cycle()
        custom_start.assert_called_once()

    def test_cleanup_diagnostic_visibility(self) -> None:
        fail_cleanup = MagicMock(return_value=False)
        engine = SupervisorEngine(self.target, self.controller, cleanup_probe=fail_cleanup)
        engine._cleanup_probe_teardown()
        self.assertTrue(engine.has_probe_cleanup_failure)
        self.assertEqual(engine.probe_cleanup_error, "cleanup_probe returned False (cleanup pending or failed)")
        # Adapter pending cleanup visibility
        adapter = MagicMock(has_pending_cleanup=True, cleanup_error="pending leak")
        e_adapt = SupervisorEngine(self.target, self.controller, adapter=adapter)
        e_adapt._cleanup_probe_teardown()
        self.assertTrue(e_adapt.has_probe_cleanup_failure)
        self.assertEqual(e_adapt.probe_cleanup_error, "pending leak")

    def test_termination_mocked_safe_sigterm_idempotence_and_exception_priority(self) -> None:
        engine = SupervisorEngine(self.target, self.controller, clock=self.clock)
        with patch("scripts.f3_guard.engine_termination.safe_sigterm", return_value=(LifecycleState.STOPPED, 100)) as mock_sig:
            halted, state = engine._terminate("test_stop")
            self.assertTrue(halted)
            self.assertEqual(state, LifecycleState.STOPPED)
            self.assertEqual(engine.state, LifecycleState.STOPPED)
            self.assertEqual(engine.stop_reason, "test_stop")
            self.assertIsNotNone(engine.attempt_record)
            self.assertEqual(engine.attempt_record.outcome.state, LifecycleState.STOPPED)
            mock_sig.assert_called_once()
            # Idempotence: repeat does not issue second signal
            halted2, state2 = engine._terminate("repeat")
            self.assertTrue(halted2)
            self.assertEqual(state2, LifecycleState.STOPPED)
            mock_sig.assert_called_once()
        # Ordinary controller cleanup error sealed in diagnostics
        ctrl_fail = MagicMock()
        ctrl_fail.cleanup_bound_pidfd.return_value = False
        e_ctrl = SupervisorEngine(self.target, ctrl_fail, clock=self.clock)
        with patch("scripts.f3_guard.engine_termination.safe_sigterm", return_value=(LifecycleState.STOPPED, 100)):
            e_ctrl._terminate("ctrl_err")
            self.assertEqual(e_ctrl.state, LifecycleState.ERROR)
            self.assertTrue(e_ctrl.has_probe_cleanup_failure)
            self.assertIn("Controller cleanup failed", str(e_ctrl.probe_cleanup_error))
        # Exception / interrupt priority
        engine2 = SupervisorEngine(self.target, self.controller, clock=self.clock)
        with patch("scripts.f3_guard.engine_termination.safe_sigterm", side_effect=KeyboardInterrupt("sigterm interrupt")):
            with self.assertRaises(KeyboardInterrupt):
                engine2._terminate("abort")
            self.assertEqual(engine2.state, LifecycleState.ERROR)
            self.assertIsInstance(engine2.primary_error, KeyboardInterrupt)
