"""Hermetic unit tests for scripts.f3_guard.probe_lifecycle module."""

from __future__ import annotations

import os
import sys
import unittest

_REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

from scripts.f3_guard.clock import FakeClock
from scripts.f3_guard.probe_lifecycle import ProbeLifecycle
from scripts.f3_guard.readiness import _ProbeState
from scripts.f3_guard.types import ANREvent, ProbeStatus


class TestProbeLifecycle(unittest.TestCase):
    """Full test matrix verifying mechanical extraction of ProbeLifecycle."""

    def setUp(self) -> None:
        self.clock = FakeClock(monotonic_start=100.0)

    def test_initial_state_defaults(self) -> None:
        lc = ProbeLifecycle(clock=self.clock)
        self.assertEqual(lc.probe_state, _ProbeState.IDLE)
        self.assertEqual(lc.last_probe_status, ProbeStatus.UNKNOWN)
        self.assertEqual(lc.last_probe_mono, 100.0)
        self.assertEqual(lc.probe_start_mono, 0.0)
        self.assertEqual(lc.probe_deadline_sec, 15.0)
        self.assertEqual(lc.stale_probe_sec, 15.0)
        self.assertFalse(lc.has_probe_cleanup_failure)
        self.assertIsNone(lc.probe_cleanup_error)

    def test_default_clock_is_system_clock(self) -> None:
        lc = ProbeLifecycle()
        self.assertGreater(lc.last_probe_mono, 0.0)

    def test_adapter_wiring_and_precedence(self) -> None:
        class DummyAdapter:
            def __init__(self):
                self.started = False
            def start_probe(self): self.started = True
            def poll_probe(self): return True, (0, "ACTIVITY MANAGER RUNNING PROCESSES", "", False)

        ad = DummyAdapter()
        lc = ProbeLifecycle(clock=self.clock, adapter=ad)
        lc.start_cycle()
        self.assertTrue(ad.started)
        res = lc.poll_cycle()
        self.assertIsNotNone(res)

    def test_explicit_callables_override_adapter(self) -> None:
        class DummyAdapter:
            def start_probe(self): pass
            def poll_probe(self): return True, None

        ad = DummyAdapter()
        custom_called = []
        lc = ProbeLifecycle(
            clock=self.clock,
            adapter=ad,
            start_probe=lambda: custom_called.append("start"),
            poll_probe=lambda: (True, (0, "out", "", False)),
        )
        lc.start_cycle()
        self.assertIn("start", custom_called)

    def test_nonblocking_start_and_poll_cycle(self) -> None:
        started = []
        completed = False
        def fake_start(): started.append(self.clock.monotonic())
        def fake_poll():
            if completed:
                return True, (0, "ACTIVITY MANAGER RUNNING PROCESSES\n  pid 100", "", False)
            return False, None

        lc = ProbeLifecycle(clock=self.clock, start_probe=fake_start, poll_probe=fake_poll)
        lc.start_cycle()
        self.assertEqual(lc.probe_state, _ProbeState.RUNNING)
        self.assertEqual(lc.probe_start_mono, 100.0)
        self.assertIsNone(lc.poll_cycle())

        completed = True
        res = lc.poll_cycle()
        self.assertEqual(lc.probe_state, _ProbeState.IDLE)
        self.assertEqual(res[0], 0)

    def test_legacy_probe_runner_success_and_exception_swallowed(self) -> None:
        lc = ProbeLifecycle(clock=self.clock, probe_runner=lambda: (0, "dump", "", False))
        lc.start_cycle()
        self.assertEqual(lc.probe_state, _ProbeState.COMPLETED)
        res = lc.poll_cycle()
        self.assertEqual(lc.probe_state, _ProbeState.IDLE)
        self.assertEqual(res, (0, "dump", "", False))

        def failing_runner(): raise RuntimeError("probe crashed")
        lc_fail = ProbeLifecycle(clock=self.clock, probe_runner=failing_runner)
        lc_fail.start_cycle()
        self.assertEqual(lc_fail.probe_state, _ProbeState.COMPLETED)
        res_fail = lc_fail.poll_cycle()
        self.assertIsNone(res_fail)
        self.assertEqual(lc_fail.probe_state, _ProbeState.IDLE)

    def test_step_probe_idle_starts_and_polls(self) -> None:
        payload = (
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
            "  ProcessRecord{1a2b3c 1234:com.android.systemui/u0a123}\n"
            "    notResponding=false\n"
            "    crashing=false\n"
        )
        lc = ProbeLifecycle(clock=self.clock, probe_runner=lambda: (0, payload, "", False))
        halted, reason = lc.step_probe()
        self.assertFalse(halted)
        self.assertIsNone(reason)
        self.assertEqual(lc.last_probe_status, ProbeStatus.CLEAR)
        self.assertEqual(lc.last_probe_mono, 100.0)

    def test_strict_deadline_boundary_overrun_and_late_completion(self) -> None:
        payload = (
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
            "  ProcessRecord{1a2b3c 1234:com.android.systemui/u0a123}\n"
            "    notResponding=false\n"
            "    crashing=false\n"
        )
        poll_done = False
        def fake_poll():
            if poll_done:
                return True, (0, payload, "", False)
            return False, None

        lc = ProbeLifecycle(clock=self.clock, start_probe=lambda: None, poll_probe=fake_poll, probe_deadline_sec=10.0)
        halted, reason = lc.step_probe()
        self.assertFalse(halted)
        self.assertEqual(lc.probe_state, _ProbeState.RUNNING)

        # Exact deadline boundary (delta == 10.0) -> not overrun (> 10.0)
        self.clock.advance_monotonic(10.0)
        halted, reason = lc.step_probe()
        self.assertFalse(halted)

        # Overrun (> 10.0) while still running
        self.clock.advance_monotonic(0.001)
        halted, reason = lc.step_probe()
        self.assertTrue(halted)
        self.assertIn("Health probe overrun (>10.0s without completion)", reason)

        # Late completion counts: was_running checked before poll, poll finishes, but overrun triggers
        # If overrun triggers in original code:
        # if was_running and (now_probe - orig_start) > self._probe_deadline_sec:
        #     return self._terminate(...)
        # So return happens BEFORE evaluating/recording result!
        lc2 = ProbeLifecycle(clock=self.clock, start_probe=lambda: None, poll_probe=fake_poll, probe_deadline_sec=10.0)
        lc2.step_probe()
        poll_done = True
        self.clock.advance_monotonic(10.001)
        halted, reason = lc2.step_probe()
        self.assertTrue(halted)
        self.assertIn("Health probe overrun (>10.0s without completion)", reason)
        # Result was not recorded because overrun terminated before result processing
        self.assertEqual(lc2.last_probe_status, ProbeStatus.UNKNOWN)
        # But probe_state reset to IDLE because poll was called and returned done
        self.assertEqual(lc2.probe_state, _ProbeState.IDLE)

    def test_legacy_sync_probe_runner_bypasses_deadline(self) -> None:
        payload = (
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
            "  ProcessRecord{1a2b3c 1234:com.android.systemui/u0a123}\n"
            "    notResponding=false\n"
            "    crashing=false\n"
        )
        def slow_runner():
            self.clock.advance_monotonic(20.0)
            return (0, payload, "", False)

        lc = ProbeLifecycle(clock=self.clock, probe_runner=slow_runner, probe_deadline_sec=10.0)
        halted, reason = lc.step_probe()
        self.assertFalse(halted)
        self.assertIsNone(reason)
        self.assertEqual(lc.last_probe_status, ProbeStatus.CLEAR)

    def test_step_probe_catches_exception_not_base_exception(self) -> None:
        def bad_poll(): raise ValueError("bad probe poll")
        lc = ProbeLifecycle(clock=self.clock, start_probe=lambda: None, poll_probe=bad_poll)
        lc.start_cycle()
        halted, reason = lc.step_probe()
        self.assertTrue(halted)
        self.assertEqual(reason, "Health probe execution failure: bad probe poll")

        def keyboard_int(): raise KeyboardInterrupt()
        lc_ki = ProbeLifecycle(clock=self.clock, start_probe=lambda: None, poll_probe=keyboard_int)
        lc_ki.start_cycle()
        with self.assertRaises(KeyboardInterrupt):
            lc_ki.step_probe()

    def test_baseline_events_forwarded_to_probe_eval(self) -> None:
        from unittest.mock import patch
        lc = ProbeLifecycle(
            clock=self.clock,
            probe_runner=lambda: (0, "dump", "", False),
            baseline_events={ANREvent(package="com.example")},
        )
        with patch("scripts.f3_guard.probe_lifecycle.evaluate_adb_probe_result") as mock_eval:
            mock_eval.return_value = (ProbeStatus.CLEAR, "ok", set())
            halted, reason = lc.step_probe()
            self.assertFalse(halted)
            mock_eval.assert_called_once()
            _, kwargs = mock_eval.call_args
            self.assertEqual(kwargs["baseline_events"], {ANREvent(package="com.example")})
