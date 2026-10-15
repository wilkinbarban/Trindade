"""Hermetic unit tests for F3 Emulator Capacity & Safety Guard.

Verifies fail-closed memory thresholds, decoupled ANR monitoring,
monotonic TTL, process identity verification before SIGTERM, zombie classification,
and readiness aggregation.
"""

import unittest
from unittest.mock import MagicMock
import dataclasses
import signal
import sys
import os
import time

# Ensure scripts dir is in sys.path for direct import
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Test imports from the guard module under test
import f3_emulator_guard as guard


def _enable_mock_sleep_progression(test_case, start_mono=1000.0):
    fake_time = [float(start_mono)]

    def _mock_sleep(seconds):
        fake_time[0] += max(0.001, float(seconds))

    def _mock_monotonic():
        return fake_time[0]

    sp = unittest.mock.patch.object(time, "sleep", side_effect=_mock_sleep)
    mp = unittest.mock.patch.object(time, "monotonic", side_effect=_mock_monotonic)
    sp.start()
    mp.start()

    def _restore():
        err = None
        try:
            mp.stop()
        except Exception as e:
            err = e
        try:
            sp.stop()
        except Exception as e:
            if err is None:
                err = e
        if err is not None:
            raise err

    test_case.addCleanup(_restore)


def _make_pidfd_mock(exit_on_signal=True):
    """Create a MagicMock ProcessController with pidfd mode enabled.

    All safe_sigterm tests require use_pidfd=True since the guard refuses
    PID-based signaling. If exit_on_signal is True, the mock simulates
    the process exiting after receiving SIGTERM via pidfd.
    """
    mock = MagicMock()
    mock.get_start_ticks.return_value = 987654
    mock.get_uid.return_value = 1000
    mock.get_cmdline.return_value = [
        "qemu-system-x86_64", "-avd", "Trindade_UX_v030", "-port", "5556"]
    mock.use_pidfd = True
    mock.pidfd_open.return_value = 99
    mock.close_fd = MagicMock()
    mock.is_direct_child = False
    mock.get_ppid = MagicMock(return_value=os.getpid())
    mock.get_fdinfo_pid = MagicMock(return_value=12345)
    mock.is_pidfd_alive = MagicMock(return_value=True)
    mock.binding_state = guard.BindingState.NEVER_BOUND
    mock.cleanup_bound_pidfd = MagicMock(return_value=None)
    if exit_on_signal:
        def on_pidfd_sig(fd, sig):
            mock.get_start_ticks.return_value = None
            mock.get_process_state.return_value = None
            return True
        mock.pidfd_send_signal.side_effect = on_pidfd_sig
    else:
        mock.pidfd_send_signal.return_value = True
    return mock


class TestMemoryParsingAndLimits(unittest.TestCase):
    """Verifies memory parse failures fail-closed and enforce threshold boundaries."""

    def test_parse_meminfo_success(self):
        sample = (
            "MemTotal:        7987200 kB\n"
            "MemFree:         1200000 kB\n"
            "MemAvailable:    3500000 kB\n"
            "SwapTotal:       4194300 kB\n"
            "SwapFree:        2100000 kB\n"
        )
        mem_avail, swap_free = guard.parse_meminfo(sample)
        self.assertEqual(mem_avail, 3500000)
        self.assertEqual(swap_free, 2100000)

    def test_parse_meminfo_missing_fields_fail_closed(self):
        sample_missing_swap = (
            "MemTotal:        7987200 kB\n"
            "MemAvailable:    3500000 kB\n"
        )
        with self.assertRaises(guard.MeminfoParseError):
            guard.parse_meminfo(sample_missing_swap)

        sample_missing_avail = (
            "MemTotal:        7987200 kB\n"
            "SwapFree:        2100000 kB\n"
        )
        with self.assertRaises(guard.MeminfoParseError):
            guard.parse_meminfo(sample_missing_avail)

        with self.assertRaises(guard.MeminfoParseError):
            guard.parse_meminfo("")

    def test_parse_meminfo_malformed_numeric(self):
        sample_corrupt = (
            "MemAvailable:    corrupted kB\n"
            "SwapFree:        2100000 kB\n"
        )
        with self.assertRaises(guard.MeminfoParseError):
            guard.parse_meminfo(sample_corrupt)

    def test_memory_negative_and_boolean_rejected(self):
        # Negative values must be rejected
        ok, reason = guard.validate_memory_limits(-1, 1000000)
        self.assertFalse(ok)
        self.assertIn("invalid", reason.lower())

        ok, reason = guard.validate_memory_limits(2000000, -50)
        self.assertFalse(ok)
        self.assertIn("invalid", reason.lower())

        # Boolean values (subclass of int in Python) must be strictly rejected
        ok, reason = guard.validate_memory_limits(True, 1000000)
        self.assertFalse(ok)
        self.assertIn("boolean", reason.lower())

        ok, reason = guard.validate_memory_limits(2000000, False)
        self.assertFalse(ok)
        self.assertIn("boolean", reason.lower())

    def test_thresholds_exact_boundary_equality(self):
        # Exact boundary: 1048576 kB RAM and 524288 kB Swap are acceptable (>=)
        ok, _ = guard.validate_memory_limits(
            guard.GUARD_RAM_MIN_KB, guard.GUARD_SWAP_MIN_KB
        )
        self.assertTrue(ok)

        # 1 kB below RAM threshold: 1048575 kB must FAIL
        ok, reason = guard.validate_memory_limits(
            guard.GUARD_RAM_MIN_KB - 1, guard.GUARD_SWAP_MIN_KB
        )
        self.assertFalse(ok)
        self.assertIn("ram", reason.lower())

        # 1 kB below Swap threshold: 524287 kB must FAIL
        ok, reason = guard.validate_memory_limits(
            guard.GUARD_RAM_MIN_KB, guard.GUARD_SWAP_MIN_KB - 1
        )
        self.assertFalse(ok)
        self.assertIn("swap", reason.lower())


class TestANRAndProbeFailClosed(unittest.TestCase):
    """Verifies ANR probes, fail-closed handling on error/timeout, and scope causality."""

    def test_probe_nonzero_exit_code_fail_closed(self):
        # ADB error returncode != 0 must NOT be interpreted as clear/healthy
        status, reason, _ = guard.evaluate_adb_probe_result(
            returncode=1,
            stdout="",
            stderr="error: device offline",
            is_timeout=False,
            baseline_anrs=set()
        )
        self.assertEqual(status, guard.ProbeStatus.PROBE_ERROR)
        self.assertIn("device offline", reason)

    def test_probe_timeout_fail_closed(self):
        # Timeout must NOT be treated as clear
        status, reason, _ = guard.evaluate_adb_probe_result(
            returncode=-1,
            stdout="",
            stderr="TIMED_OUT",
            is_timeout=True,
            baseline_anrs=set()
        )
        self.assertEqual(status, guard.ProbeStatus.PROBE_ERROR)
        self.assertIn("timeout", reason.lower())

    def test_probe_unknown_unparseable_output_fail_closed(self):
        # Output with garbage or unrecognized format must be UNKNOWN -> triggers stop
        status, reason, _ = guard.evaluate_adb_probe_result(
            returncode=0,
            stdout="?? UNKNOWN BUS PROTOCOL DUMP \x00\x01\x02",
            stderr="",
            is_timeout=False,
            baseline_anrs=set()
        )
        self.assertEqual(status, guard.ProbeStatus.UNKNOWN)

    def test_anr_systemui_detected_without_window_focus(self):
        # Window focus is normal/innocent, but dumpsys activity processes shows SystemUI notResponding
        process_dump = (
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
            "  ProcessRecord{1a2b3c 1234:com.android.systemui/u0a123}\n"
            "    notResponding=true\n"
            "    crashing=false\n"
        )
        window_dump = "mCurrentFocus=Window{4d5e6f u0 com.trindade.app/com.trindade.app.MainActivity}\n"
        status, reason, anrs = guard.parse_system_health(
            process_dump=process_dump,
            window_dump=window_dump,
            baseline_anrs=set()
        )
        self.assertEqual(status, guard.ProbeStatus.ANR_DETECTED)
        self.assertIn("com.android.systemui", reason)
        self.assertIn("com.android.systemui", anrs)

    def test_anr_causality_distinguishes_baseline(self):
        # Baseline forgiveness via baseline_events is disabled (no provable
        # identity matching). Same fingerprint still detects as ANR.
        baseline_event = guard.ANREvent(
            package="com.android.baseline.daemon",
            pid=555,
            reason="notResponding",
            occurrence_tag="baseline_20261001_001"
        )
        process_dump = (
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
            "  ProcessRecord{999a 555:com.android.baseline.daemon/u0a50}\n"
            "    notResponding=true\n"
            "    crashing=false\n"
        )
        # baseline_events forgiveness disabled: detected as ANR
        status, _, _ = guard.parse_system_health(
            process_dump=process_dump,
            window_dump="",
            baseline_events={baseline_event}
        )
        self.assertEqual(status, guard.ProbeStatus.ANR_DETECTED)

    def test_no_false_positive_on_innocent_anr_substring(self):
        # Window title mentioning "anr" innocently should not trigger
        # detection; requires proper section header and structure.
        window_dump = "WINDOW MANAGER WINDOWS\nmCurrentFocus=Window{777 u0 com.example.anr_viewer/MainActivity}\n"
        process_dump = (
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
            "  ProcessRecord{777a 888:com.example.anr_viewer/u0a99}\n"
            "    notResponding=false\n"
            "    crashing=false\n"
        )
        status, _, _ = guard.parse_system_health(
            process_dump=process_dump,
            window_dump=window_dump,
            baseline_anrs=set()
        )
        self.assertEqual(status, guard.ProbeStatus.CLEAR)


class TestMonotonicClockAndTTL(unittest.TestCase):
    """Verifies monotonic clock resistance to wall-clock jump and 1200s TTL."""

    def test_wallclock_jump_does_not_affect_ttl(self):
        # Simulated clock provider
        clock = guard.FakeClock(monotonic_start=100.0, wall_start=1700000000.0)
        tracker = guard.MonotonicTTLTracker(ttl_seconds=1200.0, clock=clock)

        self.assertFalse(tracker.is_expired())
        self.assertEqual(tracker.remaining_seconds(), 1200.0)

        # Simulate wall clock jump backwards or forwards by 2 hours (7200s)
        clock.advance_wall(7200.0)
        # But monotonic clock only advanced 10s
        clock.advance_monotonic(10.0)

        self.assertFalse(tracker.is_expired())
        self.assertEqual(tracker.remaining_seconds(), 1190.0)

    def test_monotonic_ttl_expires_at_1200s(self):
        clock = guard.FakeClock(monotonic_start=0.0, wall_start=1000.0)
        tracker = guard.MonotonicTTLTracker(ttl_seconds=1200.0, clock=clock)

        clock.advance_monotonic(1199.0)
        self.assertFalse(tracker.is_expired())

        clock.advance_monotonic(1.0)
        self.assertTrue(tracker.is_expired())
        self.assertEqual(tracker.remaining_seconds(), 0.0)


class TestDecoupledCadence(unittest.TestCase):
    """Verifies 5s memory loop cadence is independent of slow or stale probes."""

    def test_slow_probe_does_not_block_memory_cadence(self):
        # Probe takes 4.5 seconds to return, but memory checks run on 5s cadence
        clock = guard.FakeClock()
        mock_probe = MagicMock()
        mock_mem = MagicMock(return_value=(2000000, 1000000))

        loop = guard.DecoupledSupervisorLoop(
            cadence_sec=5.0,
            clock=clock,
            mem_reader=mock_mem,
            probe_runner=mock_probe,
            max_stale_probe_sec=15.0
        )

        # Trigger tick 1
        loop.tick()
        self.assertEqual(mock_mem.call_count, 1)

        # Advance 5 seconds (memory cadence)
        clock.advance_monotonic(5.0)
        loop.tick()
        self.assertEqual(mock_mem.call_count, 2)

    def test_stale_probe_triggers_stop(self):
        clock = guard.FakeClock()
        mock_mem = MagicMock(return_value=(2000000, 1000000))

        loop = guard.DecoupledSupervisorLoop(
            cadence_sec=5.0,
            clock=clock,
            mem_reader=mock_mem,
            probe_runner=MagicMock(),
            max_stale_probe_sec=15.0
        )
        loop.record_probe_result(guard.ProbeStatus.CLEAR, monotonic_timestamp=0.0)

        # Advance beyond 15 seconds stale threshold without new probe update
        clock.advance_monotonic(16.0)
        should_stop, reason = loop.check_health()
        self.assertTrue(should_stop)
        self.assertIn("stale", reason.lower())


class TestProcessIdentityAndTermination(unittest.TestCase):
    """Verifies PID reuse prevention, SIGKILL absence, and honest zombie classification."""

    def setUp(self):
        _enable_mock_sleep_progression(self)
        self.target = guard.ProcessIdentity(
            pid=12345,
            start_ticks=987654,
            uid=1000,
            avd="Trindade_UX_v030",
            port=5556,
            binary_name="qemu-system-x86_64"
        )

    def test_pid_reuse_start_ticks_mismatch_prevents_signal(self):
        mock_proc = _make_pidfd_mock()
        mock_proc.get_start_ticks.return_value = 111111

        result_state, reason = guard.safe_sigterm(
            target=self.target, controller=mock_proc, reason="test threshold stop"
        )
        mock_proc.pidfd_send_signal.assert_not_called()
        self.assertEqual(result_state, guard.LifecycleState.PID_MISMATCH)
        self.assertIn("mismatch", reason.lower())

    def test_uid_mismatch_prevents_signal(self):
        mock_proc = _make_pidfd_mock()
        mock_proc.get_start_ticks.return_value = 987654
        mock_proc.get_uid.return_value = 0

        result_state, _ = guard.safe_sigterm(
            target=self.target, controller=mock_proc, reason="test stop"
        )
        mock_proc.pidfd_send_signal.assert_not_called()
        self.assertEqual(result_state, guard.LifecycleState.PID_MISMATCH)

    def test_never_uses_sigkill_on_grace_timeout(self):
        mock_proc = _make_pidfd_mock(exit_on_signal=False)
        mock_proc.get_process_state.return_value = "S"

        result_state, reason = guard.safe_sigterm(
            target=self.target, controller=mock_proc,
            reason="ttl expired", grace_seconds=0.1, poll_interval=0.02
        )
        mock_proc.pidfd_send_signal.assert_called_with(99, signal.SIGTERM)
        for call in mock_proc.pidfd_send_signal.call_args_list:
            self.assertNotEqual(call[0][1], signal.SIGKILL)
            self.assertNotEqual(call[0][1], 9)
        self.assertEqual(result_state, guard.LifecycleState.TERMINATION_FAILED)
        self.assertIn("still alive", reason.lower())

    def test_zombie_state_honestly_classified(self):
        mock_proc = _make_pidfd_mock(exit_on_signal=False)
        mock_proc.is_direct_child = False
        mock_proc.get_process_state.return_value = "Z"

        result_state, reason = guard.safe_sigterm(
            target=self.target, controller=mock_proc,
            reason="test stop", grace_seconds=1.0, poll_interval=0.2
        )
        self.assertEqual(result_state, guard.LifecycleState.ZOMBIE_UNREAPED)
        self.assertIn("zombie", reason.lower())

    def test_direct_child_reaped_marked_stopped(self):
        mock_proc = _make_pidfd_mock(exit_on_signal=False)
        mock_proc.get_process_state.return_value = "Z"
        mock_proc.is_direct_child = True
        mock_proc.try_reap.return_value = True

        result_state, reason = guard.safe_sigterm(
            target=self.target, controller=mock_proc,
            reason="test stop", grace_seconds=1.0, poll_interval=0.2
        )
        self.assertEqual(result_state, guard.LifecycleState.STOPPED)
        self.assertIn("reaped", reason.lower())


class TestReadinessAndExceptionCleanup(unittest.TestCase):
    """Verifies readiness aggregation post-capacity/baselines and exception cleanup."""

    def setUp(self):
        _enable_mock_sleep_progression(self)

    def test_readiness_requires_post_capacity_and_baselines(self):
        # 1. Healthy ready state
        ready, details = guard.evaluate_readiness(
            boot_completed=True,
            mem_available_kb=2000000,
            swap_free_kb=1000000,
            probe_status=guard.ProbeStatus.CLEAR,
            identity_verified=True,
            baseline_established=True
        )
        self.assertTrue(ready)
        self.assertEqual(details["status"], "ready")

        # 2. Boot completed but memory fell below guard limit: MUST NOT be ready
        ready, details = guard.evaluate_readiness(
            boot_completed=True,
            mem_available_kb=guard.GUARD_RAM_MIN_KB - 100,
            swap_free_kb=1000000,
            probe_status=guard.ProbeStatus.CLEAR,
            identity_verified=True,
            baseline_established=True
        )
        self.assertFalse(ready)
        self.assertIn("ram", details["reason"].lower())

        # 3. Boot completed but ANR probe is UNKNOWN/ERROR: MUST NOT be ready
        ready, details = guard.evaluate_readiness(
            boot_completed=True,
            mem_available_kb=2000000,
            swap_free_kb=1000000,
            probe_status=guard.ProbeStatus.PROBE_ERROR,
            identity_verified=True,
            baseline_established=True
        )
        self.assertFalse(ready)
        self.assertIn("probe", details["reason"].lower())

    def test_exception_cleanup_terminates_target_in_finally(self):
        mock_proc = _make_pidfd_mock()
        target = guard.ProcessIdentity(
            pid=12345, start_ticks=987654, uid=1000,
            avd="Trindade_UX_v030", port=5556,
            binary_name="qemu-system-x86_64"
        )
        state_sink = {}
        def faulty_run():
            raise RuntimeError("Unexpected supervisor internal crash")
        guard.run_guarded_supervisor(
            runner=faulty_run, target=target, controller=mock_proc,
            state_sink=state_sink, grace_seconds=0.1, poll_interval=0.02
        )
        mock_proc.pidfd_send_signal.assert_called_with(99, signal.SIGTERM)
        self.assertEqual(state_sink.get("state"), guard.LifecycleState.ERROR)
        self.assertIn("Unexpected supervisor internal crash", state_sink.get("stop_reason"))


class TestFindingsRegressions(unittest.TestCase):
    """Regression test cases reproducing confirmed verifier findings 1 through 6."""

    def setUp(self):
        _enable_mock_sleep_progression(self)
        self.target = guard.ProcessIdentity(
            pid=12345,
            start_ticks=987654,
            uid=1000,
            avd="Trindade_UX_v030",
            port=5556,
            binary_name="qemu-system-x86_64"
        )

    # --- Finding 6: Strict integer memory validation (rejection of NaN, Inf, floats) ---
    def test_nan_inf_and_float_rejected_strictly(self):
        # NaN must fail closed
        ok, reason = guard.validate_memory_limits(float('nan'), float('nan'))
        self.assertFalse(ok)
        self.assertIn("integer", reason.lower())

        # Inf must fail closed
        ok, _ = guard.validate_memory_limits(float('inf'), 1000000)
        self.assertFalse(ok)

        # Float representation must fail closed (strict integer kB required)
        ok, _ = guard.validate_memory_limits(1048576.0, 524288)
        self.assertFalse(ok)

        # Readiness evaluation must also fail closed on NaN
        ready, details = guard.evaluate_readiness(
            boot_completed=True,
            mem_available_kb=float('nan'),
            swap_free_kb=float('nan'),
            probe_status=guard.ProbeStatus.CLEAR,
            identity_verified=True,
            baseline_established=True
        )
        self.assertFalse(ready)

    # --- Finding 2: Unrecognized probe output UNKNOWN and Last ANR history with fingerprint ---
    def test_unrecognized_probe_output_fail_closed_unknown(self):
        # Plain unstructured text or unrecognized format must be UNKNOWN, not CLEAR
        status, reason, _ = guard.evaluate_adb_probe_result(
            returncode=0,
            stdout="some unrecognized garbage output without dumpsys activity structures",
            stderr="",
            is_timeout=False,
            baseline_anrs=set()
        )
        self.assertEqual(status, guard.ProbeStatus.UNKNOWN)

    def test_dumpsys_last_anr_history_detected(self):
        # Last ANR history dump must be parsed and detected
        anr_dump = (
            "ACTIVITY MANAGER LAST ANR (dumpsys activity last-anr)\n"
            "ANR in com.android.systemui (com.android.systemui/.SystemUIService)\n"
            "PID: 1234\n"
            "Reason: Input dispatching timed out\n"
        )
        status, reason, anrs = guard.evaluate_adb_probe_result(
            returncode=0,
            stdout=anr_dump,
            stderr="",
            is_timeout=False,
            baseline_anrs=set()
        )
        self.assertEqual(status, guard.ProbeStatus.ANR_DETECTED)
        self.assertIn("com.android.systemui", reason)

    def test_anr_baseline_recurrence_fingerprint(self):
        # Baseline must track event fingerprint (e.g. pid or event identity), not just package name
        baseline_event = guard.ANREvent(
            package="com.android.systemui",
            pid=1234,
            reason="old baseline freeze"
        )
        # New ANR event for SAME package with different PID/reason
        new_dump = (
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
            "  ProcessRecord{1a2b 5678:com.android.systemui/u0a123}\n"
            "    notResponding=true\n"
            "    crashing=false\n"
        )
        status, reason, _ = guard.parse_system_health(
            process_dump=new_dump,
            window_dump="",
            baseline_events={baseline_event}
        )
        self.assertEqual(status, guard.ProbeStatus.ANR_DETECTED)

    # --- Finding 3: Exact cmdline check (binary, avd, port) and TOCTOU protection ---
    def test_cmdline_mismatch_prevents_signal(self):
        mock_proc = _make_pidfd_mock()
        mock_proc.get_cmdline.return_value = ["/usr/bin/python3", "unrelated_worker.py"]

        state, reason = guard.safe_sigterm(
            target=self.target, controller=mock_proc, reason="test stop"
        )
        mock_proc.pidfd_send_signal.assert_not_called()
        self.assertEqual(state, guard.LifecycleState.PID_MISMATCH)
        self.assertIn("executable", reason.lower())

    # --- Finding 4: Inspection error handling and direct child reap honoring ---
    def test_inspection_error_during_termination_does_not_claim_stopped(self):
        mock_proc = _make_pidfd_mock(exit_on_signal=False)
        mock_proc.get_process_state.return_value = "INSPECTION_ERROR"

        state, reason = guard.safe_sigterm(
            target=self.target, controller=mock_proc,
            reason="test stop", grace_seconds=0.2, poll_interval=0.1
        )
        self.assertNotEqual(state, guard.LifecycleState.STOPPED)

    def test_direct_child_reap_failure_reported_as_zombie(self):
        mock_proc = _make_pidfd_mock(exit_on_signal=False)
        mock_proc.is_direct_child = True
        mock_proc.get_process_state.return_value = "Z"
        mock_proc.try_reap.return_value = None

        state, reason = guard.safe_sigterm(
            target=self.target, controller=mock_proc,
            reason="test stop", grace_seconds=0.2, poll_interval=0.1
        )
        self.assertEqual(state, guard.LifecycleState.ZOMBIE_UNREAPED)

    # --- Finding 5: run_guarded_supervisor catches SystemExit, preserves termination failure ---
    def test_guarded_supervisor_preserves_termination_failure_in_state(self):
        mock_proc = _make_pidfd_mock(exit_on_signal=False)
        mock_proc.get_process_state.return_value = "S"
        state_sink = {}
        def normal_or_exit_runner():
            raise SystemExit("Forced process exit")
        with self.assertRaises(SystemExit):
            guard.run_guarded_supervisor(
                runner=normal_or_exit_runner, target=self.target,
                controller=mock_proc, state_sink=state_sink,
                grace_seconds=0.2, poll_interval=0.1
            )
        self.assertEqual(state_sink.get("state"), guard.LifecycleState.TERMINATION_FAILED)

    # --- Finding 1: Genuine integrated supervisor loop with scheduling & fail-closed stops ---
    def _make_supervisor_mock(self, clock=None):
        """Create a mock ProcessController with pidfd for supervisor tests."""
        mock_proc = _make_pidfd_mock()
        return mock_proc

    def _clear_probe_dump(self):
        """Return a probe runner result producing CLEAR status."""
        clear_dump = (
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
            "  ProcessRecord{ab12 100:com.test.app/u0a1}\n"
            "    notResponding=false\n"
            "    crashing=false\n"
        )
        return (0, clear_dump, "", False)

    def test_integrated_supervisor_loop_stops_on_ram_threshold(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        mock_proc = self._make_supervisor_mock(clock)

        # First step uses first reading (OK), second step uses second reading (below threshold)
        mem_readings = [
            (2000000, 1000000),
            (guard.GUARD_RAM_MIN_KB - 500, 1000000)
        ]
        mock_mem = MagicMock(side_effect=mem_readings)

        # Probe returns CLEAR to earn initial freshness
        clear_probes = [self._clear_probe_dump(), self._clear_probe_dump()]
        mock_probe = MagicMock(side_effect=clear_probes)

        supervisor = guard.SupervisorEngine(
            target=self.target,
            controller=mock_proc,
            clock=clock,
            mem_reader=mock_mem,
            probe_runner=mock_probe,
            cadence_sec=5.0,
            ttl_sec=1200.0,
            stale_probe_sec=15.0,
            grace_sec=0.1,
            poll_interval=0.02
        )

        # Step 1: at 0.0s (probe returns CLEAR, memory healthy)
        halted, state = supervisor.step()
        self.assertFalse(halted)

        # Step 2: advance clock 5s (cadence), memory drops
        clock.advance_monotonic(5.0)
        halted, state = supervisor.step()
        self.assertTrue(halted)
        mock_proc.pidfd_send_signal.assert_called_with(99, signal.SIGTERM)


    # --- Finding 1 additional cases: TTL expiry, stale probe, probe ANR in supervisor ---
    def test_integrated_supervisor_loop_stops_on_ttl_expiry(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        mock_proc = self._make_supervisor_mock(clock)

        supervisor = guard.SupervisorEngine(
            target=self.target,
            controller=mock_proc,
            clock=clock,
            mem_reader=MagicMock(return_value=(2000000, 1000000)),
            probe_runner=MagicMock(return_value=self._clear_probe_dump()),
            cadence_sec=5.0,
            ttl_sec=1200.0,
            grace_sec=0.1,
            poll_interval=0.02
        )
        clock.advance_monotonic(1201.0)
        halted, state = supervisor.step()
        self.assertTrue(halted)
        self.assertIn("TTL expired", supervisor.stop_reason)

    def test_integrated_supervisor_loop_stops_on_stale_probe(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        mock_proc = self._make_supervisor_mock(clock)

        # No probe_runner: probe stays at initial UNKNOWN; will trigger UNKNOWN stop
        # first. To test stale specifically, supply a CLEAR probe initially,
        # then advance past staleness without new probes.
        probe_results = [self._clear_probe_dump()]  # only one result
        mock_probe = MagicMock(side_effect=probe_results)

        supervisor = guard.SupervisorEngine(
            target=self.target,
            controller=mock_proc,
            clock=clock,
            mem_reader=MagicMock(return_value=(2000000, 1000000)),
            probe_runner=mock_probe,
            cadence_sec=5.0,
            stale_probe_sec=15.0,
            grace_sec=0.1,
            poll_interval=0.02
        )
        # Step 1: earns CLEAR probe
        halted, _ = supervisor.step()
        self.assertFalse(halted)

        # Advance beyond 15s without fresh probe (runner exhausted)
        clock.advance_monotonic(16.0)
        # The probe_runner will raise StopIteration on next call, caught as PROBE_ERROR
        halted, state = supervisor.step()
        self.assertTrue(halted)

    def test_integrated_supervisor_loop_stops_on_probe_anr(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        mock_proc = self._make_supervisor_mock(clock)

        anr_dump = (
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
            "  ProcessRecord{111a 222:com.android.systemui/u0a10}\n"
            "    notResponding=true\n"
        )
        mock_probe = MagicMock(return_value=(0, anr_dump, "", False))

        supervisor = guard.SupervisorEngine(
            target=self.target,
            controller=mock_proc,
            clock=clock,
            mem_reader=MagicMock(return_value=(2000000, 1000000)),
            probe_runner=mock_probe,
            cadence_sec=5.0,
            grace_sec=0.1,
            poll_interval=0.02
        )
        halted, state = supervisor.step()
        self.assertTrue(halted)
        self.assertIn("anr_detected", supervisor.stop_reason.lower())

    # --- Finding 3 additional case: pidfd signaling when supported ---
    def test_pidfd_signaling_when_supported(self):
        mock_proc = MagicMock()
        mock_proc.get_start_ticks.return_value = 987654
        mock_proc.get_uid.return_value = 1000
        mock_proc.get_cmdline.return_value = ["qemu-system-x86_64", "-avd", "Trindade_UX_v030", "-port", "5556"]
        mock_proc.use_pidfd = True
        mock_proc.pidfd_open.return_value = 42
        mock_proc.close_fd = MagicMock()
        def on_pidfd_sig(fd, sig):
            mock_proc.get_start_ticks.return_value = None
            mock_proc.get_process_state.return_value = None
            return True
        mock_proc.pidfd_send_signal.side_effect = on_pidfd_sig

        state, reason = guard.safe_sigterm(
            target=self.target,
            controller=mock_proc,
            reason="test pidfd",
            grace_seconds=0.1,
            poll_interval=0.02
        )
        mock_proc.pidfd_send_signal.assert_called_with(42, signal.SIGTERM)
        # close_fd must be called (not raw os.close)
        mock_proc.close_fd.assert_called_with(42)
        self.assertEqual(state, guard.LifecycleState.STOPPED)

    # --- Finding 5 additional case: normal return cleanup ---
    def test_guarded_supervisor_cleans_up_on_normal_return(self):
        mock_proc = _make_pidfd_mock()
        state_sink = {}
        def normal_run():
            pass
        final_state = guard.run_guarded_supervisor(
            runner=normal_run, target=self.target, controller=mock_proc,
            state_sink=state_sink, grace_seconds=0.1, poll_interval=0.02
        )
        mock_proc.pidfd_send_signal.assert_called_with(99, signal.SIGTERM)
        self.assertEqual(final_state, guard.LifecycleState.STOPPED)
        self.assertEqual(state_sink.get("state"), guard.LifecycleState.STOPPED)


class TestAdditionalTriangulation(unittest.TestCase):
    """Triangulation tests covering CLI offline check, crashing processes, and meminfo robustness."""

    def test_parse_meminfo_mixed_order_and_whitespace(self):
        sample = (
            "SwapTotal:       4194300 kB\n"
            "SwapFree:        524288 kB\n"
            "MemTotal:        7987200 kB\n"
            "MemAvailable:    1048576 kB\n"
        )
        mem_avail, swap_free = guard.parse_meminfo(sample)
        self.assertEqual(mem_avail, guard.GUARD_RAM_MIN_KB)
        self.assertEqual(swap_free, guard.GUARD_SWAP_MIN_KB)

    def test_crashing_process_triggers_anr_status(self):
        process_dump = (
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
            "  ProcessRecord{abc 2345:com.trindade.app/u0a200}\n"
            "    notResponding=false\n"
            "    crashing=true\n"
        )
        status, reason, anrs = guard.parse_system_health(
            process_dump=process_dump,
            window_dump="",
            baseline_anrs=set()
        )
        self.assertEqual(status, guard.ProbeStatus.ANR_DETECTED)
        self.assertIn("com.trindade.app", anrs)
        self.assertIn("crashing", reason.lower())

    def test_cli_offline_check_executes_safely(self):
        # inert import and safe offline argument parser
        orig_argv = sys.argv
        try:
            sys.argv = ["f3_emulator_guard.py", "--check-offline"]
            with unittest.mock.patch("sys.stdout"):
                exit_code = guard.main()
            self.assertEqual(exit_code, 0)
        finally:
            sys.argv = orig_argv

    def test_ttl_remaining_clamped_to_zero_after_expiry(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        tracker = guard.MonotonicTTLTracker(ttl_seconds=100.0, clock=clock)
        clock.advance_monotonic(200.0)
        self.assertTrue(tracker.is_expired())
        self.assertEqual(tracker.remaining_seconds(), 0.0)


class TestDefect1UnsafeFDMock(unittest.TestCase):
    """Defect 1: Ensure pidfd close goes through controller.close_fd, never raw os.close.

    Previous test_pidfd_signaling_when_supported mocked pidfd_open=>42 but
    production guard called real os.close(42). Fixed: all pidfd closes now go
    through controller.close_fd.
    """

    def setUp(self):
        _enable_mock_sleep_progression(self)
        self.target = guard.ProcessIdentity(
            pid=12345, start_ticks=987654, uid=1000,
            avd="Trindade_UX_v030", port=5556,
            binary_name="qemu-system-x86_64"
        )

    def test_pidfd_close_uses_controller_not_os_close(self):
        """Verify close_fd is called and os.close is NOT called on pidfd."""
        mock_proc = MagicMock()
        mock_proc.get_start_ticks.return_value = 987654
        mock_proc.get_uid.return_value = 1000
        mock_proc.get_cmdline.return_value = [
            "qemu-system-x86_64", "-avd", "Trindade_UX_v030", "-port", "5556"]
        mock_proc.use_pidfd = True
        mock_proc.pidfd_open.return_value = 42
        mock_proc.close_fd = MagicMock()
        def on_pidfd_sig(fd, sig):
            mock_proc.get_start_ticks.return_value = None
            mock_proc.get_process_state.return_value = None
            return True
        mock_proc.pidfd_send_signal.side_effect = on_pidfd_sig

        from unittest.mock import patch
        with patch('os.close') as mock_os_close:
            state, _ = guard.safe_sigterm(
                target=self.target, controller=mock_proc,
                reason="test fd safety", grace_seconds=0.1, poll_interval=0.02
            )
            # os.close must NOT have been called with 42
            for call in mock_os_close.call_args_list:
                self.assertNotEqual(call[0][0], 42,
                    "os.close(42) was called; production must use controller.close_fd")

        # controller.close_fd MUST have been called with 42
        mock_proc.close_fd.assert_called_with(42)
        self.assertEqual(state, guard.LifecycleState.STOPPED)

    def test_pidfd_open_failure_returns_error_no_pid_fallback(self):
        """If pidfd mode is on but pidfd_open fails, must return ERROR, not fallback to os.kill."""
        mock_proc = MagicMock()
        mock_proc.get_start_ticks.return_value = 987654
        mock_proc.get_uid.return_value = 1000
        mock_proc.get_cmdline.return_value = [
            "qemu-system-x86_64", "-avd", "Trindade_UX_v030", "-port", "5556"]
        mock_proc.use_pidfd = True
        mock_proc.pidfd_open.return_value = None  # open failed

        state, reason = guard.safe_sigterm(
            target=self.target, controller=mock_proc,
            reason="test pidfd fail", grace_seconds=0.1, poll_interval=0.02
        )
        self.assertEqual(state, guard.LifecycleState.ERROR)
        self.assertIn("pidfd_open failed", reason)
        mock_proc.send_signal.assert_not_called()

    def test_pidfd_send_failure_returns_error_no_pid_fallback(self):
        """If pidfd_send_signal fails, must not fall back to os.kill."""
        mock_proc = MagicMock()
        mock_proc.get_start_ticks.return_value = 987654
        mock_proc.get_uid.return_value = 1000
        mock_proc.get_cmdline.return_value = [
            "qemu-system-x86_64", "-avd", "Trindade_UX_v030", "-port", "5556"]
        mock_proc.use_pidfd = True
        mock_proc.pidfd_open.return_value = 42
        mock_proc.pidfd_send_signal.return_value = False  # send failed
        mock_proc.close_fd = MagicMock()

        state, reason = guard.safe_sigterm(
            target=self.target, controller=mock_proc,
            reason="test send fail", grace_seconds=0.1, poll_interval=0.02
        )
        self.assertEqual(state, guard.LifecycleState.ERROR)
        self.assertIn("pidfd_send_signal failed", reason)
        mock_proc.send_signal.assert_not_called()
        mock_proc.close_fd.assert_called_with(42)


class TestDefect2SupervisorCadenceProbe(unittest.TestCase):
    """Defect 2: Probe non-blocking, cadence used, initial UNKNOWN, TTL recheck after probe."""

    def setUp(self):
        _enable_mock_sleep_progression(self)
        self.target = guard.ProcessIdentity(
            pid=12345, start_ticks=987654, uid=1000,
            avd="Trindade_UX_v030", port=5556,
            binary_name="qemu-system-x86_64"
        )

    def _make_mock(self):
        mock_proc = _make_pidfd_mock()
        return mock_proc

    def _clear_probe(self):
        return (0, "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
                "  ProcessRecord{ab12 100:com.test.app/u0a1}\n"
                "    notResponding=false\n    crashing=false\n", "", False)

    def test_initial_probe_status_unknown_not_clear(self):
        """Initial probe status must be UNKNOWN, halting on first step if no probe earns freshness."""
        clock = guard.FakeClock(monotonic_start=0.0)
        mock_proc = self._make_mock()

        supervisor = guard.SupervisorEngine(
            target=self.target, controller=mock_proc, clock=clock,
            mem_reader=MagicMock(return_value=(2000000, 1000000)),
            cadence_sec=5.0, ttl_sec=1200.0, stale_probe_sec=15.0,
            grace_sec=0.1, poll_interval=0.02
        )
        self.assertEqual(supervisor.last_probe_status, guard.ProbeStatus.UNKNOWN)
        halted, _ = supervisor.step()
        # Should halt due to UNKNOWN probe status (fail-closed)
        self.assertTrue(halted)
        self.assertIn("unknown", supervisor.stop_reason.lower())

    def test_memory_cadence_enforced(self):
        """Memory is only sampled when cadence interval has elapsed."""
        clock = guard.FakeClock(monotonic_start=0.0)
        mock_proc = self._make_mock()
        mock_mem = MagicMock(return_value=(2000000, 1000000))

        supervisor = guard.SupervisorEngine(
            target=self.target, controller=mock_proc, clock=clock,
            mem_reader=mock_mem,
            probe_runner=MagicMock(return_value=self._clear_probe()),
            cadence_sec=5.0, ttl_sec=1200.0, stale_probe_sec=15.0,
            grace_sec=0.1, poll_interval=0.02
        )
        # Step 1 at t=0: samples memory (first step)
        supervisor.step()
        count1 = mock_mem.call_count

        # Step 2 at t=2: should NOT re-sample (cadence not met)
        clock.advance_monotonic(2.0)
        supervisor.step()
        count2 = mock_mem.call_count
        self.assertEqual(count2, count1, "Memory sampled before cadence interval elapsed")

        # Step 3 at t=5: should sample (cadence met)
        clock.advance_monotonic(3.0)
        supervisor.step()
        count3 = mock_mem.call_count
        self.assertEqual(count3, count1 + 1)

    def test_blocked_probe_does_not_block_memory_sampling(self):
        """Memory poll runs even when probe is pending (non-blocking)."""
        clock = guard.FakeClock(monotonic_start=0.0)
        mock_proc = self._make_mock()
        mock_mem = MagicMock(return_value=(2000000, 1000000))

        # Use start/poll probe model: probe starts but never completes
        start_probe = MagicMock()
        poll_probe = MagicMock(return_value=(False, None))  # always pending

        supervisor = guard.SupervisorEngine(
            target=self.target, controller=mock_proc, clock=clock,
            mem_reader=mock_mem,
            start_probe=start_probe, poll_probe=poll_probe,
            cadence_sec=5.0, ttl_sec=1200.0, stale_probe_sec=100.0,
            probe_deadline_sec=100.0,
            grace_sec=0.1, poll_interval=0.02
        )
        # Manually set probe status to CLEAR to avoid immediate UNKNOWN halt
        supervisor.record_probe_result(guard.ProbeStatus.CLEAR, 0.0)

        supervisor.step()  # t=0: memory sampled + probe started
        mem_count_1 = mock_mem.call_count
        self.assertGreaterEqual(mem_count_1, 1)
        start_probe.assert_called_once()

        # Advance 5s: probe still pending, but memory should sample again
        clock.advance_monotonic(5.0)
        supervisor.step()
        self.assertEqual(mock_mem.call_count, mem_count_1 + 1)

    def test_ttl_rechecked_after_probe_operations(self):
        """TTL must be rechecked after probe processing."""
        clock = guard.FakeClock(monotonic_start=0.0)
        mock_proc = self._make_mock()

        # Probe result advances clock past TTL during processing
        def sneaky_probe():
            # Simulate probe taking a long time
            clock.advance_monotonic(1201.0)  # push past TTL
            return self._clear_probe()

        supervisor = guard.SupervisorEngine(
            target=self.target, controller=mock_proc, clock=clock,
            mem_reader=MagicMock(return_value=(2000000, 1000000)),
            probe_runner=MagicMock(side_effect=sneaky_probe),
            cadence_sec=5.0, ttl_sec=1200.0, stale_probe_sec=1500.0,
            grace_sec=0.1, poll_interval=0.02
        )
        halted, _ = supervisor.step()
        self.assertTrue(halted)
        self.assertIn("TTL expired", supervisor.stop_reason)

    def test_probe_overrun_triggers_stop(self):
        """Probe running longer than deadline triggers stop."""
        clock = guard.FakeClock(monotonic_start=0.0)
        mock_proc = self._make_mock()

        start_probe = MagicMock()
        poll_probe = MagicMock(return_value=(False, None))  # never finishes

        supervisor = guard.SupervisorEngine(
            target=self.target, controller=mock_proc, clock=clock,
            mem_reader=MagicMock(return_value=(2000000, 1000000)),
            start_probe=start_probe, poll_probe=poll_probe,
            cadence_sec=5.0, ttl_sec=1200.0, stale_probe_sec=100.0,
            probe_deadline_sec=10.0,
            grace_sec=0.1, poll_interval=0.02
        )
        # Earn initial CLEAR
        supervisor.record_probe_result(guard.ProbeStatus.CLEAR, 0.0)

        supervisor.step()  # starts probe

        # Advance past probe deadline
        clock.advance_monotonic(11.0)
        halted, _ = supervisor.step()
        self.assertTrue(halted)
        self.assertIn("overrun", supervisor.stop_reason.lower())

    def test_scheduled_memory_samples_during_pending_probe(self):
        """Two steps at t=0 (sample=[RAM_ok,0]) and t=5 (sample=[RAM_ok,0])
        with probe still pending verifies memory runs on schedule."""
        clock = guard.FakeClock(monotonic_start=0.0)
        mock_proc = self._make_mock()
        mem_samples = [
            (2000000, 1000000),
            (2000000, 1000000),
        ]
        mock_mem = MagicMock(side_effect=mem_samples)
        start_probe = MagicMock()
        poll_probe = MagicMock(return_value=(False, None))

        supervisor = guard.SupervisorEngine(
            target=self.target, controller=mock_proc, clock=clock,
            mem_reader=mock_mem,
            start_probe=start_probe, poll_probe=poll_probe,
            cadence_sec=5.0, ttl_sec=1200.0, stale_probe_sec=100.0,
            probe_deadline_sec=100.0,
            grace_sec=0.1, poll_interval=0.02
        )
        supervisor.record_probe_result(guard.ProbeStatus.CLEAR, 0.0)

        supervisor.step()  # t=0: mem sample 1
        self.assertEqual(mock_mem.call_count, 1)

        clock.advance_monotonic(5.0)
        supervisor.step()  # t=5: mem sample 2
        self.assertEqual(mock_mem.call_count, 2)


class TestDefect3ANRParsing(unittest.TestCase):
    """Defect 3: Garbage ProcessRecord, focus-only CLEAR, last_anr PID/Reason, recurrence."""

    def test_garbage_process_record_not_clear(self):
        """ProcessRecord{garbage} without proper structure: UNKNOWN not CLEAR."""
        process_dump = (
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
            "  ProcessRecord{garbage}\n"
        )
        status, reason, _ = guard.parse_system_health(
            process_dump=process_dump, window_dump="", baseline_anrs=set()
        )
        # Header present but no well-formed records parsed → UNKNOWN
        self.assertEqual(status, guard.ProbeStatus.UNKNOWN)

    def test_lone_focus_window_without_section_header_unknown(self):
        """mCurrentFocus alone (no section header) must be UNKNOWN, not CLEAR."""
        content = "mCurrentFocus=Window{abc123 u0 com.innocent.app/MainActivity}\n"
        status, reason, _ = guard.parse_system_health(
            process_dump=content, window_dump="", baseline_anrs=set()
        )
        self.assertEqual(status, guard.ProbeStatus.UNKNOWN)
        self.assertIn("unrecognized", reason.lower())

    def test_lone_process_record_without_section_header_unknown(self):
        """ProcessRecord{} without section header must be UNKNOWN."""
        content = "ProcessRecord{abc1 123:com.test.app/u0a50}\n notResponding=true\n"
        status, reason, _ = guard.parse_system_health(
            process_dump=content, window_dump="", baseline_anrs=set()
        )
        self.assertEqual(status, guard.ProbeStatus.UNKNOWN)

    def test_last_anr_captures_pid_and_reason(self):
        """ANR in ... PID: ... Reason: ... must capture all three fields."""
        anr_dump = (
            "ACTIVITY MANAGER LAST ANR (dumpsys activity last-anr)\n"
            "ANR in com.android.systemui (com.android.systemui/.SystemUIService)\n"
            "PID: 123\n"
            "Reason: Input dispatching timed out\n"
        )
        status, reason, anrs = guard.parse_system_health(
            process_dump=anr_dump, window_dump="", baseline_anrs=set()
        )
        self.assertEqual(status, guard.ProbeStatus.ANR_DETECTED)
        self.assertIn("123", reason)  # PID captured
        self.assertIn("Input dispatching", reason)  # Reason captured

    def test_baseline_same_package_new_pid_detected(self):
        """Same package with different PID/reason is a NEW recurrence."""
        baseline = {
            guard.ANREvent(package="com.android.systemui", pid=100, reason="old")
        }
        dump = (
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
            "  ProcessRecord{ff01 456:com.android.systemui/u0a123}\n"
            "    notResponding=true\n"
        )
        status, _, _ = guard.parse_system_health(
            process_dump=dump, window_dump="",
            baseline_events=baseline
        )
        # Different pid (456 vs 100): must detect as new
        self.assertEqual(status, guard.ProbeStatus.ANR_DETECTED)

    def test_unidentifiable_history_unknown_fail_closed(self):
        """Output without ANY recognized section header: UNKNOWN fail-closed."""
        content = (
            "Some random debug output\n"
            "Process 1234 running\n"
            "Memory: OK\n"
        )
        status, reason, _ = guard.parse_system_health(
            process_dump=content, window_dump="", baseline_anrs=set()
        )
        self.assertEqual(status, guard.ProbeStatus.UNKNOWN)


class TestDefect4IdentityVerification(unittest.TestCase):
    """Defect 4: Exact token argv, UID enforcement, pidfd before revalidation."""

    def setUp(self):
        self.target = guard.ProcessIdentity(
            pid=12345, start_ticks=987654, uid=1000,
            avd="Trindade_UX_v030", port=5556,
            binary_name="qemu-system-x86_64"
        )

    def test_python3_with_qemu_arg_rejected(self):
        """python3 process with 'qemu' as argument must NOT match."""
        mock_proc = MagicMock()
        mock_proc.get_start_ticks.return_value = 987654
        mock_proc.get_uid.return_value = 1000
        mock_proc.get_cmdline.return_value = [
            "/usr/bin/python3", "qemu-system-x86_64", "-avd", "Trindade_UX_v030", "-port", "5556"]

        ok, reason = guard.verify_process_identity(self.target, mock_proc)
        self.assertFalse(ok)
        self.assertIn("executable", reason.lower())

    def test_qemu_bak_rejected(self):
        """qemu-system-x86_64.bak must NOT match."""
        mock_proc = MagicMock()
        mock_proc.get_start_ticks.return_value = 987654
        mock_proc.get_uid.return_value = 1000
        mock_proc.get_cmdline.return_value = [
            "qemu-system-x86_64.bak", "-avd", "Trindade_UX_v030", "-port", "5556"]

        ok, reason = guard.verify_process_identity(self.target, mock_proc)
        self.assertFalse(ok)

    def test_wrong_avd_next_arg_rejected(self):
        """AVD_OTHER after -avd must NOT match Trindade_UX_v030."""
        mock_proc = MagicMock()
        mock_proc.get_start_ticks.return_value = 987654
        mock_proc.get_uid.return_value = 1000
        mock_proc.get_cmdline.return_value = [
            "qemu-system-x86_64", "-avd", "AVD_OTHER", "-port", "5556"]

        ok, reason = guard.verify_process_identity(self.target, mock_proc)
        self.assertFalse(ok)
        self.assertIn("avd", reason.lower())

    def test_wrong_port_next_arg_rejected(self):
        """Port 15556 must NOT match target port 5556."""
        mock_proc = MagicMock()
        mock_proc.get_start_ticks.return_value = 987654
        mock_proc.get_uid.return_value = 1000
        mock_proc.get_cmdline.return_value = [
            "qemu-system-x86_64", "-avd", "Trindade_UX_v030", "-port", "15556"]

        ok, reason = guard.verify_process_identity(self.target, mock_proc)
        self.assertFalse(ok)
        self.assertIn("port", reason.lower())

    def test_uid_0_rejected(self):
        """Target UID 0 (root) must NOT match required UID 1000."""
        mock_proc = MagicMock()
        mock_proc.get_start_ticks.return_value = 987654
        mock_proc.get_uid.return_value = 0
        mock_proc.get_cmdline.return_value = [
            "qemu-system-x86_64", "-avd", "Trindade_UX_v030", "-port", "5556"]

        ok, reason = guard.verify_process_identity(self.target, mock_proc)
        self.assertFalse(ok)
        self.assertIn("uid", reason.lower())

    def test_exact_identity_passes(self):
        """Correct binary, -avd, -port, UID, ticks: passes."""
        mock_proc = MagicMock()
        mock_proc.get_start_ticks.return_value = 987654
        mock_proc.get_uid.return_value = 1000
        mock_proc.get_cmdline.return_value = [
            "/usr/lib/android-sdk/emulator/qemu/linux-x86_64/qemu-system-x86_64",
            "-avd", "Trindade_UX_v030", "-port", "5556", "-gpu", "swiftshader_indirect"]

        ok, reason = guard.verify_process_identity(self.target, mock_proc)
        self.assertTrue(ok)

    def test_inspection_error_on_ticks_rejects(self):
        """_INSPECTION_ERROR from get_start_ticks should conservatively reject."""
        mock_proc = MagicMock()
        mock_proc.get_start_ticks.return_value = guard._INSPECTION_ERROR
        mock_proc.get_uid.return_value = 1000
        mock_proc.get_cmdline.return_value = [
            "qemu-system-x86_64", "-avd", "Trindade_UX_v030", "-port", "5556"]

        ok, reason = guard.verify_process_identity(self.target, mock_proc)
        self.assertFalse(ok)
        self.assertIn("inspection error", reason.lower())


class TestDefect5TicksReapSemantics(unittest.TestCase):
    """Defect 5: get_start_ticks None vs _INSPECTION_ERROR, try_reap tri-state."""

    def setUp(self):
        _enable_mock_sleep_progression(self)
        self.target = guard.ProcessIdentity(
            pid=12345, start_ticks=987654, uid=1000,
            avd="Trindade_UX_v030", port=5556,
            binary_name="qemu-system-x86_64"
        )

    def test_confirmed_absence_returns_stopped(self):
        """get_start_ticks returning None (ProcessLookupError) → STOPPED."""
        mock_proc = _make_pidfd_mock()
        state, reason = guard.safe_sigterm(
            target=self.target, controller=mock_proc,
            reason="test exit", grace_seconds=0.1, poll_interval=0.02
        )
        self.assertEqual(state, guard.LifecycleState.STOPPED)

    def test_inspection_error_during_poll_does_not_claim_stopped(self):
        """_INSPECTION_ERROR from get_start_ticks during poll → continue, not STOPPED."""
        mock_proc = _make_pidfd_mock(exit_on_signal=False)
        def on_sig(fd, sig):
            mock_proc.get_start_ticks.return_value = guard._INSPECTION_ERROR
            mock_proc.get_process_state.return_value = "INSPECTION_ERROR"
            return True
        mock_proc.pidfd_send_signal.side_effect = on_sig

        state, reason = guard.safe_sigterm(
            target=self.target, controller=mock_proc,
            reason="test inspection err", grace_seconds=0.2, poll_interval=0.1
        )
        self.assertNotEqual(state, guard.LifecycleState.STOPPED)
        self.assertEqual(state, guard.LifecycleState.TERMINATION_FAILED)

    def test_reap_true_returns_stopped(self):
        """try_reap returning True (successfully reaped) → STOPPED."""
        mock_proc = _make_pidfd_mock(exit_on_signal=False)
        mock_proc.is_direct_child = True
        mock_proc.get_process_state.return_value = "Z"
        mock_proc.try_reap.return_value = True

        state, reason = guard.safe_sigterm(
            target=self.target, controller=mock_proc,
            reason="test reap", grace_seconds=0.2, poll_interval=0.1
        )
        self.assertEqual(state, guard.LifecycleState.STOPPED)
        self.assertIn("reaped", reason.lower())

    def test_reap_false_returns_zombie_pending(self):
        """try_reap returning False (zombie not yet reapable) → ZOMBIE_PENDING."""
        mock_proc = _make_pidfd_mock(exit_on_signal=False)
        mock_proc.is_direct_child = True
        mock_proc.get_process_state.return_value = "Z"
        mock_proc.try_reap.return_value = False

        state, reason = guard.safe_sigterm(
            target=self.target, controller=mock_proc,
            reason="test pending", grace_seconds=0.2, poll_interval=0.1
        )
        self.assertEqual(state, guard.LifecycleState.ZOMBIE_PENDING)
        self.assertIn("pending", reason.lower())

    def test_reap_none_returns_zombie_unreaped(self):
        """try_reap returning None (not our child or error) → ZOMBIE_UNREAPED."""
        mock_proc = _make_pidfd_mock(exit_on_signal=False)
        mock_proc.is_direct_child = True
        mock_proc.get_process_state.return_value = "Z"
        mock_proc.try_reap.return_value = None

        state, reason = guard.safe_sigterm(
            target=self.target, controller=mock_proc,
            reason="test reap none", grace_seconds=0.2, poll_interval=0.1
        )
        self.assertEqual(state, guard.LifecycleState.ZOMBIE_UNREAPED)
        self.assertIn("reap failed", reason.lower())

    def test_non_direct_child_zombie_not_reaped(self):
        """Non-direct-child zombie → ZOMBIE_UNREAPED without reap attempt."""
        mock_proc = _make_pidfd_mock(exit_on_signal=False)
        mock_proc.is_direct_child = False
        mock_proc.get_process_state.return_value = "Z"

        state, reason = guard.safe_sigterm(
            target=self.target, controller=mock_proc,
            reason="test non child", grace_seconds=0.2, poll_interval=0.1
        )
        self.assertEqual(state, guard.LifecycleState.ZOMBIE_UNREAPED)
        mock_proc.try_reap.assert_not_called()


class TestDefectA_PidfdDoubleClose(unittest.TestCase):
    """Defect A: pidfd must be closed exactly ONCE via finally owner.

    When pidfd_send_signal returns False, the early-return branch calls
    _close_pidfd AND the finally block calls it again — double close.
    Same for exception branches. close_fd must be called exactly once
    and close errors must be honestly reported not swallowed.
    """

    def setUp(self):
        _enable_mock_sleep_progression(self)
        self.target = guard.ProcessIdentity(
            pid=12345, start_ticks=987654, uid=1000,
            avd="Trindade_UX_v030", port=5556,
            binary_name="qemu-system-x86_64"
        )

    def _make_pidfd_mock(self):
        mock_proc = MagicMock()
        mock_proc.get_start_ticks.return_value = 987654
        mock_proc.get_uid.return_value = 1000
        mock_proc.get_cmdline.return_value = [
            "qemu-system-x86_64", "-avd", "Trindade_UX_v030", "-port", "5556"]
        mock_proc.use_pidfd = True
        mock_proc.pidfd_open.return_value = 42
        mock_proc.close_fd = MagicMock()
        mock_proc.binding_state = guard.BindingState.NEVER_BOUND
        mock_proc.cleanup_bound_pidfd = MagicMock(return_value=None)
        return mock_proc

    def test_pidfd_closed_exactly_once_on_send_false(self):
        """pidfd_send_signal returns False: close_fd called exactly once."""
        mock_proc = self._make_pidfd_mock()
        mock_proc.pidfd_send_signal.return_value = False

        state, reason = guard.safe_sigterm(
            target=self.target, controller=mock_proc,
            reason="test", grace_seconds=0.1, poll_interval=0.02
        )
        close_calls = [c for c in mock_proc.close_fd.call_args_list if c[0] == (42,)]
        self.assertEqual(len(close_calls), 1,
            f"close_fd(42) called {len(close_calls)} times, expected exactly 1")

    def test_pidfd_closed_exactly_once_on_send_exception(self):
        """pidfd_send_signal raises: close_fd called exactly once."""
        mock_proc = self._make_pidfd_mock()
        mock_proc.pidfd_send_signal.side_effect = OSError("signal error")

        state, reason = guard.safe_sigterm(
            target=self.target, controller=mock_proc,
            reason="test", grace_seconds=0.1, poll_interval=0.02
        )
        close_calls = [c for c in mock_proc.close_fd.call_args_list if c[0] == (42,)]
        self.assertEqual(len(close_calls), 1,
            f"close_fd(42) called {len(close_calls)} times, expected exactly 1")

    def test_pidfd_closed_exactly_once_on_success(self):
        """pidfd_send_signal succeeds, process exits: close_fd exactly once."""
        mock_proc = self._make_pidfd_mock()
        def on_pidfd_sig(fd, sig):
            mock_proc.get_start_ticks.return_value = None
            mock_proc.get_process_state.return_value = None
            return True
        mock_proc.pidfd_send_signal.side_effect = on_pidfd_sig

        state, reason = guard.safe_sigterm(
            target=self.target, controller=mock_proc,
            reason="test", grace_seconds=0.1, poll_interval=0.02
        )
        close_calls = [c for c in mock_proc.close_fd.call_args_list if c[0] == (42,)]
        self.assertEqual(len(close_calls), 1,
            f"close_fd(42) called {len(close_calls)} times, expected exactly 1")
        self.assertEqual(state, guard.LifecycleState.STOPPED)


class TestDefectB_ParseStructure(unittest.TestCase):
    """Defect B: Header-only or garbage ProcessRecord must NOT yield CLEAR.

    CLEAR requires: recognized section header AND at least one well-formed
    process record parsed without ANR flags. Header alone or garbage records
    mean we cannot confirm health — must be UNKNOWN.
    Baseline forgiveness with same pkg/pid/reason but missing occurrence_tag
    must not forgive when timestamp/event identity is ambiguous.
    """

    def test_header_only_no_process_records_is_unknown(self):
        """Section header with zero parseable process records: UNKNOWN not CLEAR."""
        process_dump = (
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
            "  (nothing here)\n"
        )
        status, _, _ = guard.parse_system_health(
            process_dump=process_dump, window_dump="", baseline_anrs=set()
        )
        # Currently returns CLEAR because no ANR detected. Should be UNKNOWN
        # since we parsed no valid process records to confirm health.
        self.assertEqual(status, guard.ProbeStatus.UNKNOWN)

    def test_header_plus_garbage_record_is_unknown(self):
        """Section header + ProcessRecord{garbage}: UNKNOWN not CLEAR."""
        process_dump = (
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
            "  ProcessRecord{garbage}\n"
        )
        status, _, _ = guard.parse_system_health(
            process_dump=process_dump, window_dump="", baseline_anrs=set()
        )
        self.assertEqual(status, guard.ProbeStatus.UNKNOWN)

    def test_window_header_plus_focus_only_is_unknown(self):
        """WINDOW MANAGER WINDOWS + mCurrentFocus alone: UNKNOWN, no health from focus."""
        status, _, _ = guard.parse_system_health(
            process_dump="",
            window_dump="WINDOW MANAGER WINDOWS\nmCurrentFocus=Window{abc u0 com.test/Main}\n",
            baseline_anrs=set()
        )
        self.assertEqual(status, guard.ProbeStatus.UNKNOWN)

    def test_valid_healthy_process_record_returns_clear(self):
        """Section header + well-formed healthy record: CLEAR is correct."""
        process_dump = (
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
            "  ProcessRecord{ab12 1234:com.android.systemui/u0a10}\n"
            "    notResponding=false\n"
            "    crashing=false\n"
        )
        status, _, _ = guard.parse_system_health(
            process_dump=process_dump, window_dump="", baseline_anrs=set()
        )
        self.assertEqual(status, guard.ProbeStatus.CLEAR)

    def test_same_pkg_pid_reason_no_occurrence_tag_not_forgiven(self):
        """Baseline event with identical pkg/pid/reason but no occurrence_tag:
        cannot reliably distinguish recurrence, conservatively detect as ANR."""
        baseline_event = guard.ANREvent(
            package="com.android.systemui", pid=1234,
            reason="Input dispatching timed out"
        )
        anr_dump = (
            "ACTIVITY MANAGER LAST ANR (dumpsys activity last-anr)\n"
            "ANR in com.android.systemui\n"
            "PID: 1234\n"
            "Reason: Input dispatching timed out\n"
        )
        status, _, _ = guard.parse_system_health(
            process_dump=anr_dump, window_dump="",
            baseline_events={baseline_event}
        )
        # Same fingerprint fields but no occurrence_tag means we cannot
        # confirm it IS the baseline occurrence. Conservative: detect.
        self.assertEqual(status, guard.ProbeStatus.ANR_DETECTED)


class TestDefectC_UIDAndPidfdRequirement(unittest.TestCase):
    """Defect C: Target UID must be exactly 1000; use_pidfd=False must NOT
    silently allow PID-based signal race.
    """

    def setUp(self):
        self.target = guard.ProcessIdentity(
            pid=12345, start_ticks=987654, uid=1000,
            avd="Trindade_UX_v030", port=5556,
            binary_name="qemu-system-x86_64"
        )

    def test_target_uid_0_controller_uid_0_rejected(self):
        """Even if target.uid==0 and controller returns 0, must reject: UID must be 1000."""
        root_target = guard.ProcessIdentity(
            pid=12345, start_ticks=987654, uid=0,
            avd="Trindade_UX_v030", port=5556,
            binary_name="qemu-system-x86_64"
        )
        mock_proc = MagicMock()
        mock_proc.get_start_ticks.return_value = 987654
        mock_proc.get_uid.return_value = 0
        mock_proc.get_cmdline.return_value = [
            "qemu-system-x86_64", "-avd", "Trindade_UX_v030", "-port", "5556"]

        ok, reason = guard.verify_process_identity(root_target, mock_proc)
        # Must reject: contract requires UID 1000
        self.assertFalse(ok)
        self.assertIn("uid", reason.lower())

    def test_use_pidfd_false_no_signal_sent(self):
        """use_pidfd=False (default) must NOT send signal via PID — fail closed."""
        mock_proc = MagicMock()
        mock_proc.get_start_ticks.return_value = 987654
        mock_proc.get_uid.return_value = 1000
        mock_proc.get_cmdline.return_value = [
            "qemu-system-x86_64", "-avd", "Trindade_UX_v030", "-port", "5556"]
        mock_proc.use_pidfd = False

        state, reason = guard.safe_sigterm(
            target=self.target, controller=mock_proc,
            reason="test", grace_seconds=0.1, poll_interval=0.02
        )
        # With use_pidfd=False, guard should refuse to signal by PID alone
        mock_proc.send_signal.assert_not_called()
        self.assertNotEqual(state, guard.LifecycleState.STOPPED)


class TestDefectD_AbsenceReapDistinction(unittest.TestCase):
    """Defect D: When ticks=None (confirmed absent) and is_direct_child,
    try_reap result must NOT be ignored. STOPPED only if reap succeeds or
    process is confirmed gone; False/None must distinguish states.
    """

    def setUp(self):
        _enable_mock_sleep_progression(self)
        self.target = guard.ProcessIdentity(
            pid=12345, start_ticks=987654, uid=1000,
            avd="Trindade_UX_v030", port=5556,
            binary_name="qemu-system-x86_64"
        )

    def test_absence_direct_child_reap_false_not_stopped(self):
        """Ticks=None + direct child + try_reap=False: NOT STOPPED."""
        mock_proc = MagicMock()
        mock_proc.get_start_ticks.return_value = 987654
        mock_proc.get_uid.return_value = 1000
        mock_proc.get_cmdline.return_value = [
            "qemu-system-x86_64", "-avd", "Trindade_UX_v030", "-port", "5556"]
        mock_proc.use_pidfd = True
        mock_proc.pidfd_open.return_value = 99
        mock_proc.close_fd = MagicMock()
        mock_proc.is_direct_child = True
        mock_proc.try_reap.return_value = False
        def on_sig(fd, sig):
            mock_proc.get_start_ticks.return_value = None
            mock_proc.get_process_state.return_value = None
            return True
        mock_proc.pidfd_send_signal.side_effect = on_sig

        state, reason = guard.safe_sigterm(
            target=self.target, controller=mock_proc,
            reason="test", grace_seconds=0.1, poll_interval=0.02
        )
        # Reap returned False (pending), should NOT claim STOPPED
        self.assertNotEqual(state, guard.LifecycleState.STOPPED)

    def test_absence_direct_child_reap_none_not_stopped(self):
        """Ticks=None + direct child + try_reap=None: NOT STOPPED."""
        mock_proc = MagicMock()
        mock_proc.get_start_ticks.return_value = 987654
        mock_proc.get_uid.return_value = 1000
        mock_proc.get_cmdline.return_value = [
            "qemu-system-x86_64", "-avd", "Trindade_UX_v030", "-port", "5556"]
        mock_proc.use_pidfd = True
        mock_proc.pidfd_open.return_value = 99
        mock_proc.close_fd = MagicMock()
        mock_proc.is_direct_child = True
        mock_proc.try_reap.return_value = None
        def on_sig(fd, sig):
            mock_proc.get_start_ticks.return_value = None
            mock_proc.get_process_state.return_value = None
            return True
        mock_proc.pidfd_send_signal.side_effect = on_sig

        state, reason = guard.safe_sigterm(
            target=self.target, controller=mock_proc,
            reason="test", grace_seconds=0.1, poll_interval=0.02
        )
        self.assertNotEqual(state, guard.LifecycleState.STOPPED)


class TestDefectE_EngineClockRecapture(unittest.TestCase):
    """Defect E: Engine must recapture clock after every potentially blocking
    hook so deadline/overrun/stale checks use fresh timestamps.
    Legacy sync probe_runner is unbounded and must be rejected or documented.
    """

    def setUp(self):
        _enable_mock_sleep_progression(self)
        self.target = guard.ProcessIdentity(
            pid=12345, start_ticks=987654, uid=1000,
            avd="Trindade_UX_v030", port=5556,
            binary_name="qemu-system-x86_64"
        )

    def _make_mock(self):
        mock_proc = _make_pidfd_mock()
        return mock_proc

    def _clear_probe(self):
        return (0, "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n" +
                "  ProcessRecord{ab12 100:com.test.app/u0a1}\n" +
                "    notResponding=false\n    crashing=false\n", "", False)

    def test_stale_check_uses_fresh_timestamp_not_old_now(self):
        """After a blocking probe that advances time, the stale check must use
        fresh clock, not the `now` captured before the probe ran."""
        clock = guard.FakeClock(monotonic_start=0.0)
        mock_proc = self._make_mock()

        call_count = [0]
        def slow_probe():
            call_count[0] += 1
            if call_count[0] == 1:
                # First probe takes 14s of fake time
                clock.advance_monotonic(14.0)
            return self._clear_probe()

        supervisor = guard.SupervisorEngine(
            target=self.target, controller=mock_proc, clock=clock,
            mem_reader=MagicMock(return_value=(2000000, 1000000)),
            probe_runner=MagicMock(side_effect=slow_probe),
            cadence_sec=5.0, ttl_sec=1200.0, stale_probe_sec=15.0,
            grace_sec=0.1, poll_interval=0.02
        )
        # Step at t=0: probe takes 14s, finishes at t=14, records result at t=14.
        # Stale check: fresh_now(14) - last_probe_mono(14) = 0 < 15 → not stale.
        # If stale check wrongly used old `now`(0): 0 - 14 would be negative (OK)
        # but if probe_mono was set to old now(0) then 14 - 0 = 14 < 15 → OK too.
        # Real bug: if deadline/stale used old `now` for comparison.
        halted, _ = supervisor.step()
        # The probe completed and earned CLEAR, stale should NOT trigger.
        # If halted for wrong reason, the old-now bug is present.
        if halted and supervisor.stop_reason and "stale" in supervisor.stop_reason.lower():
            self.fail("Stale check used old timestamp instead of fresh clock")

    def test_overrun_uses_fresh_timestamp(self):
        """Overrun deadline check must use fresh clock after poll_probe runs."""
        clock = guard.FakeClock(monotonic_start=0.0)
        mock_proc = self._make_mock()

        start_calls = [0]
        def fake_start():
            start_calls[0] += 1
        poll_calls = [0]
        def fake_poll():
            poll_calls[0] += 1
            # First poll advances time past deadline
            if poll_calls[0] == 1:
                clock.advance_monotonic(20.0)
            return (False, None)  # still running

        supervisor = guard.SupervisorEngine(
            target=self.target, controller=mock_proc, clock=clock,
            mem_reader=MagicMock(return_value=(2000000, 1000000)),
            start_probe=MagicMock(side_effect=fake_start),
            poll_probe=MagicMock(side_effect=fake_poll),
            cadence_sec=5.0, ttl_sec=1200.0, stale_probe_sec=100.0,
            probe_deadline_sec=10.0,
            grace_sec=0.1, poll_interval=0.02
        )
        supervisor.record_probe_result(guard.ProbeStatus.CLEAR, 0.0)

        # Step 1: starts probe (t=0), polls (advances to t=20)
        # Overrun check at old now(0): 0 - 0 = 0 < 10 → miss!
        # Overrun check at fresh now(20): 20 - 0 = 20 > 10 → catch!
        halted, _ = supervisor.step()
        self.assertTrue(halted, "Overrun not detected — old timestamp used")
        self.assertIn("overrun", supervisor.stop_reason.lower())


class TestB1_IncompleteHealthRecord(unittest.TestCase):
    """B1: ProcessRecord parsed but missing explicit health fields
    (notResponding=false, crashing=false) cannot confirm health → UNKNOWN."""

    def test_record_without_health_fields_is_unknown(self):
        """ProcessRecord with no notResponding/crashing lines: UNKNOWN."""
        dump = (
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
            "  ProcessRecord{ab12 123:com.android.systemui/u0a1}\n"
        )
        status, _, _ = guard.parse_system_health(
            process_dump=dump, window_dump="", baseline_anrs=set())
        self.assertEqual(status, guard.ProbeStatus.UNKNOWN)

    def test_complete_healthy_record_is_clear(self):
        """ProcessRecord with explicit notResponding=false crashing=false: CLEAR."""
        dump = (
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
            "  ProcessRecord{ab12 123:com.android.systemui/u0a1}\n"
            "    notResponding=false\n"
            "    crashing=false\n"
        )
        status, _, _ = guard.parse_system_health(
            process_dump=dump, window_dump="", baseline_anrs=set())
        self.assertEqual(status, guard.ProbeStatus.CLEAR)

    def test_one_healthy_one_truncated_is_unknown(self):
        """One complete healthy + one truncated (no flags): UNKNOWN."""
        dump = (
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
            "  ProcessRecord{ab12 123:com.android.systemui/u0a1}\n"
            "    notResponding=false\n"
            "    crashing=false\n"
            "  ProcessRecord{cd34 456:com.trindade.app/u0a200}\n"
        )
        status, _, _ = guard.parse_system_health(
            process_dump=dump, window_dump="", baseline_anrs=set())
        self.assertEqual(status, guard.ProbeStatus.UNKNOWN)


class TestB2_BaselineForgiveness(unittest.TestCase):
    """B2: Baseline forgiveness must NOT clear events without provable
    identity matching. Until timestamp/occurrence parsing is implemented,
    all baseline_events filtering is disabled."""

    def test_same_pkg_pid_reason_different_context_not_forgiven(self):
        """Baseline with tag + current SAME pkg/PID/reason: NOT forgiven
        because current event has no proven identity link to baseline."""
        baseline = {guard.ANREvent(
            package="com.android.systemui", pid=123,
            reason="notResponding", occurrence_tag="old_timestamp")}
        dump = (
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
            "  ProcessRecord{ab12 123:com.android.systemui/u0a1}\n"
            "    notResponding=true\n"
            "    crashing=false\n"
        )
        status, _, _ = guard.parse_system_health(
            process_dump=dump, window_dump="",
            baseline_events=baseline)
        self.assertEqual(status, guard.ProbeStatus.ANR_DETECTED)

    def test_baseline_with_empty_tag_not_forgiven(self):
        """Baseline event with empty occurrence_tag: NOT forgiven."""
        baseline = {guard.ANREvent(
            package="com.android.systemui", pid=123,
            reason="notResponding", occurrence_tag="")}
        dump = (
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
            "  ProcessRecord{ab12 123:com.android.systemui/u0a1}\n"
            "    notResponding=true\n"
        )
        status, _, _ = guard.parse_system_health(
            process_dump=dump, window_dump="",
            baseline_events=baseline)
        self.assertEqual(status, guard.ProbeStatus.ANR_DETECTED)


class TestContainment_ReapOSError(unittest.TestCase):
    """try_reap OSError during grace poll must not escape cleanup."""

    def setUp(self):
        _enable_mock_sleep_progression(self)
        self.target = guard.ProcessIdentity(
            pid=12345, start_ticks=987654, uid=1000,
            avd="Trindade_UX_v030", port=5556,
            binary_name="qemu-system-x86_64")

    def test_try_reap_oserror_does_not_crash(self):
        mock_proc = _make_pidfd_mock(exit_on_signal=False)
        mock_proc.is_direct_child = True
        mock_proc.get_process_state.return_value = "Z"
        mock_proc.try_reap.side_effect = OSError("unexpected reap error")
        # Must not raise — should return a zombie/unknown state
        state, _ = guard.safe_sigterm(
            target=self.target, controller=mock_proc,
            reason="test", grace_seconds=0.2, poll_interval=0.1)
        self.assertIn(state, (
            guard.LifecycleState.ZOMBIE_UNREAPED,
            guard.LifecycleState.ZOMBIE_PENDING,
            guard.LifecycleState.TERMINATION_FAILED,
            guard.LifecycleState.UNKNOWN))


class TestContainment_DecoupledLoopInitial(unittest.TestCase):
    """DecoupledSupervisorLoop initial CLEAR is misleading; should be UNKNOWN."""

    def test_initial_probe_status_is_unknown(self):
        clock = guard.FakeClock()
        loop = guard.DecoupledSupervisorLoop(
            cadence_sec=5.0, clock=clock,
            mem_reader=MagicMock(return_value=(2000000, 1000000)),
            max_stale_probe_sec=15.0)
        # should_stop should be True immediately because probe is unknown
        should_stop, reason = loop.check_health()
        self.assertTrue(should_stop)


class TestB1_StrictBooleanHealthParsing(unittest.TestCase):
    """B1 strict: health boolean fields must be exact 'true' or 'false' tokens.
    Garbage, empty, partial, coerced, or trailing-junk values are malformed.
    """

    HEADER = "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"

    def _record(self, nr_val, cr_val):
        """Build a single ProcessRecord body with given field values."""
        lines = []
        if nr_val is not None:
            lines.append(f"    notResponding={nr_val}")
        if cr_val is not None:
            lines.append(f"    crashing={cr_val}")
        body = "\n".join(lines)
        return f"  ProcessRecord{{ab12 100:com.test.app/u0a1}}\n{body}\n"

    def _parse(self, *records):
        dump = self.HEADER + "".join(records)
        return guard.parse_system_health(
            process_dump=dump, window_dump="", baseline_anrs=set())

    # --- Positive: exact valid booleans ---
    def test_false_false_clear(self):
        s, _, _ = self._parse(self._record("false", "false"))
        self.assertEqual(s, guard.ProbeStatus.CLEAR)

    def test_true_false_anr(self):
        s, _, _ = self._parse(self._record("true", "false"))
        self.assertEqual(s, guard.ProbeStatus.ANR_DETECTED)

    def test_false_true_anr(self):
        s, _, _ = self._parse(self._record("false", "true"))
        self.assertEqual(s, guard.ProbeStatus.ANR_DETECTED)

    # --- Garbage/malformed values: must NOT yield CLEAR ---
    def test_garbage_values_unknown(self):
        s, _, _ = self._parse(self._record("garbage", "garbage"))
        self.assertEqual(s, guard.ProbeStatus.UNKNOWN)

    def test_empty_values_unknown(self):
        s, _, _ = self._parse(self._record("", ""))
        self.assertEqual(s, guard.ProbeStatus.UNKNOWN)

    def test_falsejunk_unknown(self):
        """'falseXXX' is not 'false'."""
        s, _, _ = self._parse(self._record("falseXXX", "false"))
        self.assertEqual(s, guard.ProbeStatus.UNKNOWN)

    def test_truejunk_unknown_or_anr(self):
        """'truegarbage' is not 'true' — must not silently yield CLEAR."""
        s, _, _ = self._parse(self._record("truegarbage", "false"))
        self.assertNotEqual(s, guard.ProbeStatus.CLEAR)

    def test_capitalized_True_unknown(self):
        """Python-style 'True' is not dumpsys 'true'."""
        s, _, _ = self._parse(self._record("True", "False"))
        self.assertEqual(s, guard.ProbeStatus.UNKNOWN)

    def test_numeric_0_unknown(self):
        s, _, _ = self._parse(self._record("0", "0"))
        self.assertEqual(s, guard.ProbeStatus.UNKNOWN)

    # --- Missing one field ---
    def test_missing_crashing_field_unknown(self):
        s, _, _ = self._parse(self._record("false", None))
        self.assertEqual(s, guard.ProbeStatus.UNKNOWN)

    # --- Mixed valid + malformed records: malformed poisons ---
    def test_valid_plus_malformed_unknown(self):
        """One good record + one with garbage fields: UNKNOWN not CLEAR."""
        good = self._record("false", "false")
        bad = "  ProcessRecord{cd34 200:com.other/u0a2}\n    notResponding=maybe\n    crashing=false\n"
        s, _, _ = self._parse(good, bad)
        self.assertEqual(s, guard.ProbeStatus.UNKNOWN)

    # --- Duplicate/conflicting fields ---
    def test_duplicate_conflicting_fields_unknown(self):
        """Conflicting notResponding=false then notResponding=true: ambiguous."""
        rec = (
            "  ProcessRecord{ab12 100:com.test.app/u0a1}\n"
            "    notResponding=false\n"
            "    notResponding=true\n"
            "    crashing=false\n"
        )
        s, _, _ = self._parse(rec)
        # Must NOT be CLEAR (ambiguous); ANR_DETECTED or UNKNOWN acceptable
        self.assertNotEqual(s, guard.ProbeStatus.CLEAR)

    # --- Misleading field name ---
    def test_similar_field_name_not_accepted(self):
        """'notRespondingX=false' is not 'notResponding=false'."""
        rec = (
            "  ProcessRecord{ab12 100:com.test.app/u0a1}\n"
            "    notRespondingX=false\n"
            "    crashing=false\n"
        )
        s, _, _ = self._parse(rec)
        self.assertEqual(s, guard.ProbeStatus.UNKNOWN)


class TestHostMeminfoReader(unittest.TestCase):
    """Hermetic unit tests for HostMeminfoReader."""

    def test_valid_meminfo_reading(self):
        sample = "MemAvailable:    2097152 kB\nSwapFree:        1048576 kB\n"
        reader = guard.HostMeminfoReader(file_reader=lambda path: sample)
        mem, swap = reader()
        self.assertEqual(mem, 2097152)
        self.assertEqual(swap, 1048576)

    def test_oserror_fails_closed(self):
        def bad_reader(path):
            raise OSError("Permission denied or file not found")
        reader = guard.HostMeminfoReader(file_reader=bad_reader)
        with self.assertRaises(guard.MeminfoParseError):
            reader()

    def test_empty_content_fails_closed(self):
        reader = guard.HostMeminfoReader(file_reader=lambda path: "")
        with self.assertRaises(guard.MeminfoParseError):
            reader()

    def test_truncated_meminfo_fails_closed(self):
        # MemAvailable present, SwapFree missing
        sample = "MemAvailable:    2097152 kB\n"
        reader = guard.HostMeminfoReader(file_reader=lambda path: sample)
        with self.assertRaises(guard.MeminfoParseError):
            reader()

    def test_malformed_numeric_fails_closed(self):
        sample = "MemAvailable:    invalid kB\nSwapFree:        1048576 kB\n"
        reader = guard.HostMeminfoReader(file_reader=lambda path: sample)
        with self.assertRaises(guard.MeminfoParseError):
            reader()

    def test_non_string_content_fails_closed(self):
        reader = guard.HostMeminfoReader(file_reader=lambda path: 12345)  # type: ignore
        with self.assertRaises(guard.MeminfoParseError):
            reader()


class FakeProbeHandle(guard.ProbeCommandHandle if hasattr(guard, "ProbeCommandHandle") else object):
    def __init__(self, exit_code=None, stdout="", stderr="", reapable=True):
        self._exit_code = exit_code
        self._stdout = stdout
        self._stderr = stderr
        self._reapable = reapable
        self.terminated = False
        self.signal_received = None
        self.cleaned_up = False
        self.cleanup_call_count = 0

    def poll(self):
        return self._exit_code

    def read_available(self, max_bytes: int):
        return self._stdout[:max_bytes], self._stderr[:max_bytes]

    def terminate(self):
        self.terminated = True
        self.signal_received = signal.SIGTERM

    def cleanup(self):
        self.cleanup_call_count += 1
        if self._reapable:
            self.cleaned_up = True
            return True
        return False


class TestAdbProbeAdapter(unittest.TestCase):
    """Hermetic unit tests for AdbProbeAdapter."""

    def test_explicit_serial_and_command(self):
        captured_cmd = []
        def fake_transport(cmd):
            captured_cmd.extend(cmd)
            return FakeProbeHandle(exit_code=0)

        adapter = guard.AdbProbeAdapter(
            serial="emulator-5556",
            transport=fake_transport
        )
        adapter.start_probe()
        self.assertIn("emulator-5556", captured_cmd)
        self.assertIn("-s", captured_cmd)

    def test_nonblocking_start_and_poll_while_running(self):
        clock = guard.FakeClock()
        handle = FakeProbeHandle(exit_code=None)  # Still running
        adapter = guard.AdbProbeAdapter(
            transport=lambda cmd: handle,
            clock=clock
        )
        adapter.start_probe()
        done, result = adapter.poll_probe()
        self.assertFalse(done)
        self.assertIsNone(result)

    def test_successful_probe_poll(self):
        clock = guard.FakeClock()
        valid_dumpsys = (
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
            "  ProcessRecord{abc1 1234:com.trindade.app/u0a1}\n"
            "    notResponding=false\n"
            "    crashing=false\n"
        )
        def fake_transport(cmd):
            if "last-anr" in cmd:
                return FakeProbeHandle(exit_code=0, stdout="ACTIVITY MANAGER LAST ANR (dumpsys activity last-anr)\nNo ANRs recorded.\n")
            return FakeProbeHandle(exit_code=0, stdout=valid_dumpsys)

        adapter = guard.AdbProbeAdapter(
            transport=fake_transport,
            clock=clock
        )
        adapter.start_probe()
        done, _ = adapter.poll_probe()
        if not done:
            done, result = adapter.poll_probe()
        self.assertTrue(done)
        self.assertIsNotNone(result)
        retcode, stdout, stderr, is_timeout = result
        self.assertEqual(retcode, 0)
        self.assertIn(valid_dumpsys, stdout)
        self.assertFalse(is_timeout)

    def test_failclosed_nonzero_exitcode(self):
        clock = guard.FakeClock()
        handle = FakeProbeHandle(exit_code=1, stderr="adb: device offline")
        adapter = guard.AdbProbeAdapter(
            transport=lambda cmd: handle,
            clock=clock
        )
        adapter.start_probe()
        done, result = adapter.poll_probe()
        self.assertTrue(done)
        retcode, stdout, stderr, is_timeout = result
        self.assertEqual(retcode, 1)
        self.assertEqual(stderr, "adb: device offline")
        self.assertFalse(is_timeout)

    def test_failclosed_output_exceeding_bound(self):
        clock = guard.FakeClock()
        oversized = "A" * 2000
        handle = FakeProbeHandle(exit_code=0, stdout=oversized)
        adapter = guard.AdbProbeAdapter(
            transport=lambda cmd: handle,
            clock=clock,
            max_output_bytes=1000
        )
        adapter.start_probe()
        done, result = adapter.poll_probe()
        self.assertTrue(done)
        retcode, stdout, stderr, is_timeout = result
        self.assertNotEqual(retcode, 0)
        self.assertIn("truncated", stderr)

    def test_probe_deadline_timeout_cancellation(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        handle = FakeProbeHandle(exit_code=None)  # Hangs forever
        adapter = guard.AdbProbeAdapter(
            transport=lambda cmd: handle,
            clock=clock,
            timeout_sec=15.0
        )
        adapter.start_probe()
        clock.advance_monotonic(15.1)
        done, result = adapter.poll_probe()
        self.assertTrue(done)
        retcode, stdout, stderr, is_timeout = result
        self.assertTrue(is_timeout)
        self.assertEqual(retcode, 1)
        # Verify SIGTERM sent to owned handle
        self.assertTrue(handle.terminated)
        self.assertEqual(handle.signal_received, signal.SIGTERM)

    def test_cancellation_sigterm_only_via_owned_handle(self):
        handle = FakeProbeHandle(exit_code=None)
        adapter = guard.AdbProbeAdapter(
            transport=lambda cmd: handle
        )
        adapter.start_probe()
        adapter.cancel()
        self.assertTrue(handle.terminated)
        self.assertEqual(handle.signal_received, signal.SIGTERM)

    def test_honest_pending_reap_tracked(self):
        # Handle cannot be reaped yet (reapable=False)
        handle = FakeProbeHandle(exit_code=None, reapable=False)
        adapter = guard.AdbProbeAdapter(
            transport=lambda cmd: handle
        )
        adapter.start_probe()
        adapter.cancel()
        self.assertTrue(adapter.has_pending_cleanup)
        # Now make handle reapable and reap
        handle._reapable = True
        remaining = adapter.reap_pending()
        self.assertEqual(remaining, 0)
        self.assertFalse(adapter.has_pending_cleanup)

    def test_cleanup_exception_visible(self):
        class BrokenHandle(FakeProbeHandle):
            def cleanup(self):
                raise OSError("Kernel FD close failed")

        handle = BrokenHandle(exit_code=0)
        adapter = guard.AdbProbeAdapter(
            transport=lambda cmd: handle
        )
        adapter.start_probe()
        adapter.poll_probe()
        self.assertTrue(adapter.has_pending_cleanup)
        self.assertIn("Kernel FD close failed", adapter.cleanup_error)

    def test_subprocess_probe_handle_hermetic_mock(self):
        """Hermetically verifies SubprocessProbeHandle nonblocking pipe transport."""
        mock_proc = MagicMock()
        mock_proc.poll.return_value = None

        with unittest.mock.patch("os.pipe", side_effect=[(10, 11), (20, 21)]), \
             unittest.mock.patch("os.set_blocking") as mock_set_block, \
             unittest.mock.patch("os.close") as mock_close, \
             unittest.mock.patch("os.read", side_effect=[b"test stdout", b"", b"test stderr", b""]), \
             unittest.mock.patch("os.pidfd_open", return_value=99) as mock_pidfd_open, \
             unittest.mock.patch("signal.pidfd_send_signal") as mock_pidfd_sig, \
             unittest.mock.patch("subprocess.Popen", return_value=mock_proc) as mock_popen:
            handle = guard.SubprocessProbeHandle(["adb", "version"])
            mock_popen.assert_called_once_with(
                ["adb", "version"],
                stdout=11,
                stderr=21,
                shell=False,
                close_fds=True
            )
            # Write ends closed in parent immediately
            mock_close.assert_any_call(11)
            mock_close.assert_any_call(21)
            # Read ends set non-blocking
            mock_set_block.assert_any_call(10, False)
            mock_set_block.assert_any_call(20, False)

            # Non-blocking poll
            self.assertIsNone(handle.poll())

            # Non-blocking read_available
            out, err = handle.read_available(1024)
            self.assertEqual(out, "test stdout")
            self.assertEqual(err, "test stderr")

            # Terminate sends SIGTERM via pidfd only
            handle.terminate()
            mock_pidfd_sig.assert_called_once_with(99, signal.SIGTERM)
            mock_proc.send_signal.assert_not_called()

            # Cleanup closes read fds and pidfd, and reports reap state
            mock_proc.poll.return_value = 0
            reaped = handle.cleanup()
            self.assertTrue(reaped)
            mock_close.assert_any_call(10)
            mock_close.assert_any_call(20)
            mock_close.assert_any_call(99)


class TestSupervisorIntegrationI1(unittest.TestCase):
    """Protocol-compatible engine test using fake pending probe while memory checks still advance 5s."""

    def setUp(self):
        _enable_mock_sleep_progression(self)

    def test_engine_fakependingprobe_memory_advances_and_cleanup_failures_visible(self):
        clock = guard.FakeClock(monotonic_start=100.0)
        target = guard.ProcessIdentity(
            pid=12345,
            start_ticks=987654,
            uid=1000,
            avd="Trindade_UX_v030",
            port=5556,
            binary_name="qemu-system-x86_64"
        )
        controller = _make_pidfd_mock(exit_on_signal=True)

        mem_readings = [
            (2097152, 1048576),  # step 1 (t=100): ok
            (2097152, 1048576),  # step 2 (t=105): ok
            (2097152, 1048576),  # step 3 (t=110): ok
        ]
        mem_call_count = 0
        def fake_mem_reader():
            nonlocal mem_call_count
            val = mem_readings[min(mem_call_count, len(mem_readings) - 1)]
            mem_call_count += 1
            return val

        # Probe that stays pending (exit_code=None) and fails cleanup with an exception
        class PendingFailingCleanupHandle(FakeProbeHandle):
            def cleanup(self):
                raise OSError("Mock close failure on pipe FD")

        handle = PendingFailingCleanupHandle(exit_code=None)
        adapter = guard.AdbProbeAdapter(
            clock=clock,
            transport=lambda cmd: handle,
            timeout_sec=15.0
        )

        engine = guard.SupervisorEngine(
            target=target,
            controller=controller,
            clock=clock,
            mem_reader=fake_mem_reader,
            start_probe=adapter.start_probe,
            poll_probe=adapter.poll_probe,
            cleanup_probe=adapter.cancel,
            cadence_sec=5.0,
            ttl_sec=60.0,
            probe_deadline_sec=15.0
        )
        # Establish initial clear probe state at t=100 so freshness is established
        engine.record_probe_result(guard.ProbeStatus.CLEAR, 100.0)

        # Step 1 at t=100: Initial step. Starts probe, checks memory.
        halted, state = engine.step()
        self.assertFalse(halted)
        self.assertEqual(state, guard.LifecycleState.RUNNING)
        self.assertEqual(mem_call_count, 1)

        # Advance clock by 5s to t=105. Probe is still pending!
        clock.advance_monotonic(5.0)
        halted, state = engine.step()
        self.assertFalse(halted)
        self.assertEqual(state, guard.LifecycleState.RUNNING)
        # Memory check MUST have advanced on its 5s cadence despite probe pending!
        self.assertEqual(mem_call_count, 2)

        # Advance clock another 5s to t=110. Probe is still pending!
        clock.advance_monotonic(5.0)
        halted, state = engine.step()
        self.assertFalse(halted)
        self.assertEqual(state, guard.LifecycleState.RUNNING)
        self.assertEqual(mem_call_count, 3)

        # Now advance past probe deadline (t=116, >15s elapsed on probe)
        clock.advance_monotonic(6.0)
        halted, state = engine.step()
        self.assertTrue(halted)
        # Cleanup failure from PendingFailingCleanupHandle is visible!
        self.assertTrue(adapter.has_pending_cleanup)
        self.assertIn("Mock close failure on pipe FD", adapter.cleanup_error)
        self.assertTrue(handle.terminated)


class TestI1aTransportCorrective(unittest.TestCase):
    """Focused TDD tests for I1a corrective non-blocking transport invariants."""

    def setUp(self):
        _enable_mock_sleep_progression(self)

    def test_regression_aggregate_buffer_cap_and_stderr_overflow(self):
        """Aggregate buffer (stdout + stderr) capped; overflow fails closed."""
        clock = guard.FakeClock()
        # 600 bytes stdout + 600 bytes stderr = 1200 > 1000 limit
        handle = FakeProbeHandle(exit_code=0, stdout="A" * 600, stderr="B" * 600)
        adapter = guard.AdbProbeAdapter(
            transport=lambda cmd: handle,
            clock=clock,
            max_output_bytes=1000
        )
        adapter.start_probe()
        done, result = adapter.poll_probe()
        # Should complete and fail closed due to aggregate buffer overflow
        self.assertTrue(done)
        retcode, stdout, stderr, is_timeout = result
        self.assertNotEqual(retcode, 0)
        self.assertIn("truncated", stderr.lower())

    def test_continuous_writer_bounded_poll(self):
        """Continuous writer does not hang poll loop; reads are capped per poll."""
        read_calls = 0
        def fake_read(fd, n):
            nonlocal read_calls
            read_calls += 1
            return b"continuous output stream data\n"

        with unittest.mock.patch("os.pipe", side_effect=[(10, 11), (20, 21)]), \
             unittest.mock.patch("os.set_blocking"), \
             unittest.mock.patch("os.close"), \
             unittest.mock.patch("os.read", side_effect=fake_read), \
             unittest.mock.patch("os.pidfd_open", return_value=99), \
             unittest.mock.patch("subprocess.Popen") as mock_popen:
            mock_proc = MagicMock()
            mock_proc.poll.return_value = None
            mock_popen.return_value = mock_proc

            handle = guard.SubprocessProbeHandle(["adb", "shell"])
            # Polling while child is running should drain bounded chunks and return None
            status = handle.poll()
            self.assertIsNone(status)
            # Must be bounded (not infinite loop)
            self.assertLessEqual(read_calls, 64)

    def test_descendant_pipe_open_fails_closed(self):
        """Child done but descendant holds pipe (no EOF): bounded drain then PROBE_ERROR."""
        class DescendantOpenHandle(guard.ProbeCommandHandle):
            def __init__(self):
                self.descendant_pipe_open = True
            def poll(self):
                return 0  # Child exited 0
            def read_available(self, max_bytes: int):
                return "some output", ""
            def terminate(self):
                pass
            def cleanup(self):
                return True

        adapter = guard.AdbProbeAdapter(
            transport=lambda cmd: DescendantOpenHandle()
        )
        adapter.start_probe()
        done, result = adapter.poll_probe()
        self.assertTrue(done)
        retcode, stdout, stderr, is_timeout = result
        self.assertNotEqual(retcode, 0)
        self.assertIn("descendant", stderr.lower())

    def test_constructor_failure_cleans_fds_and_retains_child(self):
        """Constructor failure after Popen closes owned FDs exactly once and retains child ownership."""
        closed_fds = []
        def fake_close(fd):
            closed_fds.append(fd)

        with unittest.mock.patch("os.pipe", side_effect=[(10, 11), (20, 21)]), \
             unittest.mock.patch("os.set_blocking"), \
             unittest.mock.patch("os.close", side_effect=fake_close), \
             unittest.mock.patch("os.pidfd_open", side_effect=OSError("pidfd open failed")), \
             unittest.mock.patch("subprocess.Popen") as mock_popen:
            mock_proc = MagicMock()
            mock_popen.return_value = mock_proc

            handle = guard.SubprocessProbeHandle(["adb", "shell"])
            self.assertIsNotNone(handle._proc)
            self.assertIsNotNone(handle._construction_error)

            # Verify write ends and read ends closed
            self.assertIn(11, closed_fds)
            self.assertIn(21, closed_fds)
            self.assertIn(10, closed_fds)
            self.assertIn(20, closed_fds)
            # FDs closed exactly once
            self.assertEqual(len(closed_fds), len(set(closed_fds)))

    def test_pidfd_cancel_only_no_pid_fallback(self):
        """Cancellation uses pidfd_send_signal only; no proc.send_signal or os.kill."""
        with unittest.mock.patch("os.pipe", side_effect=[(10, 11), (20, 21)]), \
             unittest.mock.patch("os.set_blocking"), \
             unittest.mock.patch("os.close"), \
             unittest.mock.patch("os.pidfd_open", return_value=88), \
             unittest.mock.patch("signal.pidfd_send_signal") as mock_pidfd_sig, \
             unittest.mock.patch("subprocess.Popen") as mock_popen:
            mock_proc = MagicMock()
            mock_proc.poll.return_value = None
            mock_popen.return_value = mock_proc

            handle = guard.SubprocessProbeHandle(["adb", "shell"])
            handle.terminate()

            mock_pidfd_sig.assert_called_once_with(88, signal.SIGTERM)
            mock_proc.send_signal.assert_not_called()

    def test_cleanup_failure_or_pending_poisons_clear(self):
        """Healthy dumpsys output cannot produce CLEAR if cleanup is pending or failed."""
        valid_dumpsys = (
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
            "  ProcessRecord{abc1 1234:com.trindade.app/u0a1}\n"
            "    notResponding=false\n"
            "    crashing=false\n"
        )
        # Handle cleanup returns False (pending reap)
        handle = FakeProbeHandle(exit_code=0, stdout=valid_dumpsys, reapable=False)
        adapter = guard.AdbProbeAdapter(
            command=["adb", "shell"],
            transport=lambda cmd: handle
        )
        adapter.start_probe()
        done, result = adapter.poll_probe()
        self.assertTrue(done)
        retcode, stdout, stderr, is_timeout = result
        # Must fail closed: retcode != 0 so evaluate_adb_probe_result yields PROBE_ERROR
        self.assertNotEqual(retcode, 0)
        self.assertIn("cleanup", stderr.lower())

    def test_default_probe_runs_both_commands_and_second_failure_fails(self):
        """Default probe must run both 'processes' and 'last-anr'; failure of either fails probe."""
        spawned_cmds = []
        def fake_transport(cmd):
            spawned_cmds.append(list(cmd))
            if "last-anr" in cmd:
                return FakeProbeHandle(exit_code=1, stderr="dumpsys last-anr failed")
            return FakeProbeHandle(exit_code=0, stdout="ACTIVITY MANAGER RUNNING PROCESSES\n")

        adapter = guard.AdbProbeAdapter(
            serial="emulator-5556",
            transport=fake_transport
        )
        adapter.start_probe()
        # First poll: processes finishes, starts last-anr
        done1, _ = adapter.poll_probe()
        self.assertFalse(done1)
        # Second poll: last-anr runs and fails
        done2, result2 = adapter.poll_probe()
        self.assertTrue(done2)
        retcode, stdout, stderr, is_timeout = result2
        self.assertNotEqual(retcode, 0)
        self.assertIn("last-anr", stderr.lower())
        # Both commands must have been spawned
        self.assertEqual(len(spawned_cmds), 2)
        self.assertIn("processes", " ".join(spawned_cmds[0]))
        self.assertIn("last-anr", " ".join(spawned_cmds[1]))

    def test_repro_defect1_pidfd_open_failure_retains_child_ownership(self):
        """Popen succeeds but pidfd_open raises OSError: handle must carry construction error,
        first poll must produce done=True nonzero(PROBE_ERROR) IMMEDIATELY even while child is pending,
        adapter retains owned child in pending cleanup, rejects second spawn, never PID-signals,
        reaps nonblocking later, and engine with previous CLEAR cannot remain RUNNING on known error."""
        mock_proc = MagicMock()
        mock_proc.poll.return_value = None  # Child still running
        mock_proc.pid = 4321

        with unittest.mock.patch("os.pipe", side_effect=[(10, 11), (20, 21), (30, 31), (40, 41)]), \
             unittest.mock.patch("os.set_blocking"), \
             unittest.mock.patch("os.close"), \
             unittest.mock.patch("os.pidfd_open", side_effect=OSError("EBADF on pidfd")), \
             unittest.mock.patch("subprocess.Popen", return_value=mock_proc):
            adapter = guard.AdbProbeAdapter(serial="emulator-5556")
            adapter.start_probe()

            # First poll must produce done=True and nonzero (PROBE_ERROR) IMMEDIATELY even while child is pending
            done, result = adapter.poll_probe()
            self.assertTrue(done, "known construction error must produce done=True immediately even child pending")
            retcode, stdout, stderr, is_timeout = result
            self.assertNotEqual(retcode, 0)
            self.assertIn("pidfd", stderr.lower())

            # Adapter must retain owned handle in pending cleanup
            self.assertTrue(adapter.has_pending_cleanup)
            self.assertEqual(len(adapter._pending_reap), 1)

            # Second spawn while child running must be rejected
            adapter.start_probe()
            self.assertTrue(adapter.has_pending_cleanup)
            self.assertIn("pending", adapter._start_error.lower())

            # Never sent PID signal
            mock_proc.send_signal.assert_not_called()

            # When child finally exits, later reap succeeds
            mock_proc.poll.return_value = 0
            adapter._pending_reap[0]._close_failed = False
            adapter._pending_reap[0]._close_error = None
            adapter.cleanup_error = None
            remaining = adapter.reap_pending()
            self.assertEqual(remaining, 0)

            # Engine with previous CLEAR cannot remain RUNNING on known construction error
            clock = guard.FakeClock(monotonic_start=100.0)
            target = guard.ProcessIdentity(
                pid=12345, start_ticks=987654, uid=1000,
                avd="Trindade_UX_v030", port=5556, binary_name="qemu-system-x86_64"
            )
            controller = _make_pidfd_mock(exit_on_signal=True)
            mock_proc_eng = MagicMock()
            mock_proc_eng.poll.return_value = None
            mock_proc_eng.pid = 9999

            adapter_eng = guard.AdbProbeAdapter(serial="emulator-5556")
            engine = guard.SupervisorEngine(
                target=target, controller=controller, clock=clock,
                mem_reader=lambda: (2097152, 1048576),
                start_probe=adapter_eng.start_probe,
                poll_probe=adapter_eng.poll_probe,
                cadence_sec=5.0, ttl_sec=60.0
            )
            # Previous state was CLEAR
            engine.record_probe_result(guard.ProbeStatus.CLEAR, 100.0)

            # Step triggers start_probe and poll_probe; with construction error, cannot remain RUNNING
            halted, state = engine.step()
            self.assertTrue(halted, "Engine cannot remain RUNNING when probe fails with known construction error")
            self.assertNotEqual(state, guard.LifecycleState.RUNNING)

    def test_repro_defect2_dump_contract_validation_before_concat(self):
        """Processes healthy + lastANR garbage/empty/wrongheader/headeronly must fail closed;
        valid nohistoricalANR permits CLEAR; actual ANR detects ANR."""
        valid_processes = (
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
            "  ProcessRecord{abc1 1234:com.trindade.app/u0a1}\n"
            "    notResponding=false\n"
            "    crashing=false\n"
        )
        valid_no_anr = (
            "ACTIVITY MANAGER LAST ANR (dumpsys activity last-anr)\n"
            "No ANRs recorded.\n"
        )
        anr_dump = (
            "ACTIVITY MANAGER LAST ANR (dumpsys activity last-anr)\n"
            "ANR in com.trindade.app\n"
            "PID: 1234\n"
            "Reason: input timeout\n"
        )

        test_cases = [
            ("validheadergarbage", "ACTIVITY MANAGER LAST ANR (dumpsys activity last-anr)\ngarbage\n", guard.ProbeStatus.PROBE_ERROR),
            ("truncatedANR", "ACTIVITY MANAGER LAST ANR (dumpsys activity last-anr)\nANR in\n", guard.ProbeStatus.PROBE_ERROR),
            ("unknownbody", "ACTIVITY MANAGER LAST ANR (dumpsys activity last-anr)\nsome unknown body\n", guard.ProbeStatus.PROBE_ERROR),
            ("blank", "", guard.ProbeStatus.PROBE_ERROR),
            ("wrongheader", "WINDOW MANAGER WINDOWS\nWindow #1\n", guard.ProbeStatus.PROBE_ERROR),
            ("headeronly", "ACTIVITY MANAGER LAST ANR (dumpsys activity last-anr)\n", guard.ProbeStatus.PROBE_ERROR),
            ("explicitnoANRexact", valid_no_anr, guard.ProbeStatus.CLEAR),
            ("completeANR", anr_dump, guard.ProbeStatus.ANR_DETECTED),
            ("mixednoANR_junk", "ACTIVITY MANAGER LAST ANR (dumpsys activity last-anr)\nNo ANRs recorded.\ngarbage\n", guard.ProbeStatus.PROBE_ERROR),
            ("mixednoANR_ANR", "ACTIVITY MANAGER LAST ANR (dumpsys activity last-anr)\nNo ANRs recorded.\nANR in com.trindade.app\nPID: 1234\nReason: timeout\n", guard.ProbeStatus.ANR_DETECTED),
        ]

        for label, last_anr_out, expected_status in test_cases:
            with self.subTest(label=label):
                def fake_transport(cmd):
                    if "last-anr" in cmd:
                        return FakeProbeHandle(exit_code=0, stdout=last_anr_out)
                    return FakeProbeHandle(exit_code=0, stdout=valid_processes)

                adapter = guard.AdbProbeAdapter(serial="emulator-5556", transport=fake_transport)
                adapter.start_probe()
                done, _ = adapter.poll_probe()
                self.assertFalse(done)
                done, result = adapter.poll_probe()
                self.assertTrue(done)
                retcode, stdout, stderr, is_timeout = result

                status, _, _ = guard.evaluate_adb_probe_result(
                    returncode=retcode, stdout=stdout, stderr=stderr, is_timeout=is_timeout
                )
                self.assertEqual(status, expected_status, f"Failed for case {label}: got {status}")

    def test_repro_defect3_close_failure_durable_and_immediate_overflow(self):
        """Close failure on pipe or pidfd poisons cleanup (never True) and poisons probe result;
        immediate overflow/IO error surfaces without waiting 15s."""
        mock_proc = MagicMock()
        mock_proc.poll.return_value = 0

        # Subtest 3a: Pipe close failure in concrete SubprocessProbeHandle
        with unittest.mock.patch("os.pipe", side_effect=[(10, 11), (20, 21)]), \
             unittest.mock.patch("os.set_blocking"), \
             unittest.mock.patch("os.close", side_effect=[None, None, OSError("close r_out failed"), None, None]), \
             unittest.mock.patch("os.pidfd_open", return_value=99), \
             unittest.mock.patch("subprocess.Popen", return_value=mock_proc):
            handle = guard.SubprocessProbeHandle(["adb", "shell"])
            reaped = handle.cleanup()
            self.assertFalse(reaped, "cleanup must NEVER return True healthy with error")
            self.assertTrue(handle._close_failed)
            self.assertIn("close r_out failed", handle._close_error)

        # Subtest 3b: pidfd close failure in concrete SubprocessProbeHandle
        with unittest.mock.patch("os.pipe", side_effect=[(10, 11), (20, 21)]), \
             unittest.mock.patch("os.set_blocking"), \
             unittest.mock.patch("os.close", side_effect=[None, None, None, None, OSError("close pidfd failed")]), \
             unittest.mock.patch("os.pidfd_open", return_value=99), \
             unittest.mock.patch("subprocess.Popen", return_value=mock_proc):
            handle2 = guard.SubprocessProbeHandle(["adb", "shell"])
            reaped2 = handle2.cleanup()
            self.assertFalse(reaped2)
            self.assertTrue(handle2._close_failed)
            self.assertIn("close pidfd failed", handle2._close_error)

        # Subtest 3c: Immediate overflow surface and cancellation without waiting 15s
        overflow_handle = FakeProbeHandle(exit_code=None)  # child still running
        overflow_handle._overflow = True
        adapter = guard.AdbProbeAdapter(
            transport=lambda cmd: overflow_handle,
            timeout_sec=15.0
        )
        adapter.start_probe()
        done, result = adapter.poll_probe()
        self.assertTrue(done, "Overflow must surface immediately without waiting 15s")
        retcode, stdout, stderr, is_timeout = result
        self.assertNotEqual(retcode, 0)
        self.assertFalse(is_timeout)
        self.assertTrue(overflow_handle.terminated)


class TestI1bCentralizedTeardown(unittest.TestCase):
    """Hermetic unit tests for I1b centralized probe cleanup and terminal teardown."""

    def setUp(self):
        _enable_mock_sleep_progression(self)

    def test_pid_mismatch_cleans_up_probes_without_signaling_target(self):
        """On PID_MISMATCH, active/pending probes are cleaned up, but mismatched target is never signaled."""
        clock = guard.FakeClock()
        target = guard.ProcessIdentity(
            pid=12345, start_ticks=987654, uid=1000,
            avd="Trindade_UX_v030", port=5556, binary_name="qemu-system-x86_64"
        )
        controller = _make_pidfd_mock()
        # Simulate PID reuse: start ticks do not match
        controller.get_start_ticks.return_value = 111111

        cleanup_called = False
        def fake_cleanup():
            nonlocal cleanup_called
            cleanup_called = True

        engine = guard.SupervisorEngine(
            target=target, controller=controller, clock=clock,
            cleanup_probe=fake_cleanup,
            cadence_sec=5.0, ttl_sec=60.0
        )
        halted, state = engine.step()
        self.assertTrue(halted)
        self.assertEqual(state, guard.LifecycleState.PID_MISMATCH)
        # Probe cleanup must be executed
        self.assertTrue(cleanup_called, "Probe cleanup must run on PID_MISMATCH")
        # Target must NEVER be signaled on PID_MISMATCH
        controller.pidfd_send_signal.assert_not_called()

    def test_start_or_poll_exception_triggers_cleanup_and_target_termination(self):
        """Exceptions in start_probe or poll_probe trigger cleanup and safe_sigterm to target."""
        clock = guard.FakeClock()
        target = guard.ProcessIdentity(
            pid=12345, start_ticks=987654, uid=1000,
            avd="Trindade_UX_v030", port=5556, binary_name="qemu-system-x86_64"
        )
        controller = _make_pidfd_mock(exit_on_signal=True)

        cleanup_called = False
        def fake_cleanup():
            nonlocal cleanup_called
            cleanup_called = True

        def failing_start():
            raise RuntimeError("Corrupted probe start transport")

        engine = guard.SupervisorEngine(
            target=target, controller=controller, clock=clock,
            start_probe=failing_start,
            cleanup_probe=fake_cleanup,
            cadence_sec=5.0, ttl_sec=60.0
        )
        halted, state = engine.step()
        self.assertTrue(halted)
        self.assertTrue(cleanup_called)
        self.assertEqual(state, guard.LifecycleState.STOPPED)
        controller.pidfd_send_signal.assert_called_once_with(99, signal.SIGTERM)

    def test_run_guarded_supervisor_finally_cleans_probes_on_exception_and_exits(self):
        """run_guarded_supervisor cleans probes and terminates target across Exception, SystemExit, and KeyboardInterrupt."""
        target = guard.ProcessIdentity(
            pid=12345, start_ticks=987654, uid=1000,
            avd="Trindade_UX_v030", port=5556, binary_name="qemu-system-x86_64"
        )

        for exc in [RuntimeError("Test crash"), SystemExit(2), KeyboardInterrupt()]:
            with self.subTest(exc=type(exc).__name__):
                controller = _make_pidfd_mock(exit_on_signal=True)
                cleanup_called = False
                def fake_cleanup():
                    nonlocal cleanup_called
                    cleanup_called = True

                state_sink = {}
                def crashing_runner():
                    raise exc

                if isinstance(exc, (KeyboardInterrupt, SystemExit)):
                    with self.assertRaises(type(exc)):
                        guard.run_guarded_supervisor(
                            runner=crashing_runner,
                            target=target,
                            controller=controller,
                            state_sink=state_sink,
                            cleanup_probe=fake_cleanup,
                            grace_seconds=0.1,
                            poll_interval=0.02
                        )
                else:
                    guard.run_guarded_supervisor(
                        runner=crashing_runner,
                        target=target,
                        controller=controller,
                        state_sink=state_sink,
                        cleanup_probe=fake_cleanup,
                        grace_seconds=0.1,
                        poll_interval=0.02
                    )
                self.assertTrue(cleanup_called, f"cleanup_probe must be called in finally for {type(exc).__name__}")
                controller.pidfd_send_signal.assert_called_once_with(99, signal.SIGTERM)
                self.assertIn("Supervisor terminated by:", state_sink.get("stop_reason", ""))

    def test_cleanup_oserror_does_not_skip_target_termination_and_is_observable(self):
        """If probe cleanup raises OSError, target termination is guaranteed and failure is observable."""
        target = guard.ProcessIdentity(
            pid=12345, start_ticks=987654, uid=1000,
            avd="Trindade_UX_v030", port=5556, binary_name="qemu-system-x86_64"
        )
        controller = _make_pidfd_mock(exit_on_signal=True)

        def failing_cleanup():
            raise OSError("Probe FD release EIO")

        state_sink = {}
        def normal_run():
            pass

        final_state = guard.run_guarded_supervisor(
            runner=normal_run,
            target=target,
            controller=controller,
            state_sink=state_sink,
            cleanup_probe=failing_cleanup,
            grace_seconds=0.1,
            poll_interval=0.02
        )
        # Target termination must NOT be skipped despite probe cleanup raising OSError
        controller.pidfd_send_signal.assert_called_once_with(99, signal.SIGTERM)
        # Cleanup failure must be observable in state_sink and prevent clean STOPPED state
        self.assertTrue(state_sink.get("probe_cleanup_failed"))
        self.assertIn("Probe FD release EIO", state_sink.get("probe_cleanup_error", ""))
        self.assertEqual(final_state, guard.LifecycleState.ERROR)
        self.assertEqual(state_sink.get("state"), guard.LifecycleState.ERROR)

    def test_engine_adapter_binding_and_durable_cleanup_failure_observation(self):
        """SupervisorEngine binds adapter directly and records durable probe cleanup failure."""
        clock = guard.FakeClock()
        target = guard.ProcessIdentity(
            pid=12345, start_ticks=987654, uid=1000,
            avd="Trindade_UX_v030", port=5556, binary_name="qemu-system-x86_64"
        )
        controller = _make_pidfd_mock(exit_on_signal=True)

        class FailingCleanupHandle(guard.ProbeCommandHandle):
            def poll(self): return 0
            def read_available(self, max_bytes): return "ACTIVITY MANAGER RUNNING PROCESSES\n", ""
            def terminate(self): pass
            def cleanup(self):
                raise OSError("Kernel FD release failed")

        adapter = guard.AdbProbeAdapter(
            transport=lambda cmd: FailingCleanupHandle()
        )
        engine = guard.SupervisorEngine(
            target=target, controller=controller, clock=clock,
            adapter=adapter,
            cadence_sec=5.0, ttl_sec=60.0
        )
        # Step triggers probe cycle
        halted, state = engine.step()
        self.assertTrue(halted)
        # Engine durable cleanup error observation
        self.assertTrue(engine.has_probe_cleanup_failure)
        self.assertIn("Kernel FD release failed", engine.probe_cleanup_error)

    def test_repro_i1b_1_concrete_adapter_pending_runner_sink_error(self):
        """Concrete SubprocessProbeHandle with mock Popen still running: adapter.cancel leaves pending=1,
        runner recognizes pending/error even though cancel returns None/bool, state is ERROR, not STOPPED."""
        target = guard.ProcessIdentity(
            pid=12345, start_ticks=987654, uid=1000,
            avd="Trindade_UX_v030", port=5556, binary_name="qemu-system-x86_64"
        )
        controller = _make_pidfd_mock(exit_on_signal=True)
        mock_proc = MagicMock()
        mock_proc.poll.return_value = None  # Child is still running!
        mock_proc.pid = 7777

        with unittest.mock.patch("os.pipe", side_effect=[(10, 11), (20, 21)]), \
             unittest.mock.patch("os.set_blocking"), \
             unittest.mock.patch("os.close"), \
             unittest.mock.patch("os.pidfd_open", return_value=88), \
             unittest.mock.patch("signal.pidfd_send_signal"), \
             unittest.mock.patch("subprocess.Popen", return_value=mock_proc):
            adapter = guard.AdbProbeAdapter(serial="emulator-5556")
            adapter.start_probe()

            state_sink = {}
            def normal_run():
                pass

            final_state = guard.run_guarded_supervisor(
                runner=normal_run,
                target=target,
                controller=controller,
                state_sink=state_sink,
                cleanup_probe=adapter.cancel,
                grace_seconds=0.1,
                poll_interval=0.02
            )
            # Must be ERROR, never clean STOPPED when adapter has un-reaped child
            self.assertEqual(final_state, guard.LifecycleState.ERROR)
            self.assertEqual(state_sink.get("state"), guard.LifecycleState.ERROR)
            self.assertTrue(state_sink.get("probe_cleanup_failed"))
            self.assertIn("pending", state_sink.get("probe_cleanup_error", "").lower())
            self.assertEqual(len(adapter._pending_reap), 1)

    def test_repro_i1b_2_cleanup_probe_returns_false_runner_errors(self):
        """cleanup_probe returning False causes runner to report ERROR, probe_cleanup_failed=True,
        and target safe_sigterm is still called once."""
        target = guard.ProcessIdentity(
            pid=12345, start_ticks=987654, uid=1000,
            avd="Trindade_UX_v030", port=5556, binary_name="qemu-system-x86_64"
        )
        controller = _make_pidfd_mock(exit_on_signal=True)
        state_sink = {}
        def normal_run():
            pass

        final_state = guard.run_guarded_supervisor(
            runner=normal_run,
            target=target,
            controller=controller,
            state_sink=state_sink,
            cleanup_probe=lambda: False,
            grace_seconds=0.1,
            poll_interval=0.02
        )
        self.assertEqual(final_state, guard.LifecycleState.ERROR)
        self.assertEqual(state_sink.get("state"), guard.LifecycleState.ERROR)
        self.assertTrue(state_sink.get("probe_cleanup_failed"))
        self.assertIn("false", state_sink.get("probe_cleanup_error", "").lower())
        controller.pidfd_send_signal.assert_called_once_with(99, signal.SIGTERM)

    def test_repro_i1b_3_engine_bound_adapter_pending_terminates_error(self):
        """Engine bound to adapter with pending child terminates as ERROR (not clean STOPPED);
        on PID_MISMATCH, PID_MISMATCH is preserved as primary cause without target signaling."""
        clock = guard.FakeClock(monotonic_start=0.0)
        target = guard.ProcessIdentity(
            pid=12345, start_ticks=987654, uid=1000,
            avd="Trindade_UX_v030", port=5556, binary_name="qemu-system-x86_64"
        )
        controller = _make_pidfd_mock(exit_on_signal=True)
        mock_proc = MagicMock()
        mock_proc.poll.return_value = None  # Child still running
        mock_proc.pid = 5555

        with unittest.mock.patch("os.pipe", side_effect=[(10, 11), (20, 21)]), \
             unittest.mock.patch("os.set_blocking"), \
             unittest.mock.patch("os.close"), \
             unittest.mock.patch("os.read", side_effect=BlockingIOError), \
             unittest.mock.patch("os.pidfd_open", return_value=88), \
             unittest.mock.patch("signal.pidfd_send_signal"), \
             unittest.mock.patch("subprocess.Popen", return_value=mock_proc):
            adapter = guard.AdbProbeAdapter(serial="emulator-5556")
            engine = guard.SupervisorEngine(
                target=target, controller=controller, clock=clock,
                adapter=adapter,
                cadence_sec=5.0, ttl_sec=10.0,
                grace_sec=0.1, poll_interval=0.02
            )
            # Record initial clear status to establish freshness at t=0
            engine.record_probe_result(guard.ProbeStatus.CLEAR, 0.0)
            # Step 1: starts probe, probe is running
            halted1, state1 = engine.step()
            self.assertFalse(halted1)
            self.assertEqual(state1, guard.LifecycleState.RUNNING)
            # Advance clock past TTL (t=15s > 10s)
            clock.advance_monotonic(15.0)
            halted, state = engine.step()
            self.assertTrue(halted)
            # Must be ERROR, not clean STOPPED, because adapter still has pending child
            self.assertEqual(state, guard.LifecycleState.ERROR)
            self.assertTrue(engine.has_probe_cleanup_failure)
            self.assertIn("pending", engine.stop_reason.lower())

        # Subtest 3b: PID_MISMATCH preserves PID_MISMATCH primary state without signaling target
        mismatch_controller = _make_pidfd_mock()
        mismatch_controller.get_start_ticks.return_value = 999999  # Mismatch!
        mock_proc2 = MagicMock()
        mock_proc2.poll.return_value = None
        with unittest.mock.patch("os.pipe", side_effect=[(10, 11), (20, 21)]), \
             unittest.mock.patch("os.set_blocking"), \
             unittest.mock.patch("os.close"), \
             unittest.mock.patch("os.read", side_effect=BlockingIOError), \
             unittest.mock.patch("os.pidfd_open", return_value=88), \
             unittest.mock.patch("signal.pidfd_send_signal"), \
             unittest.mock.patch("subprocess.Popen", return_value=mock_proc2):
            adapter2 = guard.AdbProbeAdapter(serial="emulator-5556")
            adapter2.start_probe()
            engine_mismatch = guard.SupervisorEngine(
                target=target, controller=mismatch_controller, clock=clock,
                adapter=adapter2,
                cadence_sec=5.0, ttl_sec=100.0,
                grace_sec=0.1, poll_interval=0.02
            )
            halted, state_mismatch = engine_mismatch.step()
            self.assertTrue(halted)
            self.assertEqual(state_mismatch, guard.LifecycleState.ERROR)
            self.assertEqual(engine_mismatch.primary_state, guard.LifecycleState.PID_MISMATCH)
            self.assertEqual(engine_mismatch.termination_state, guard.LifecycleState.PID_MISMATCH)
            self.assertTrue(engine_mismatch.has_probe_cleanup_failure)
            mismatch_controller.pidfd_send_signal.assert_not_called()

    def test_repro_i1b_4_cleanup_interrupt_preserves_sink_and_does_not_mask_original(self):
        """When cleanup raises KeyboardInterrupt/SystemExit:
        - If original runner exception exists: original exception is rethrown (not masked by cleanup interrupt).
        - If no original exception: cleanup interrupt is surfaced after persisting state_sink.
        - In both cases, target safe_sigterm is executed once and state_sink is persisted."""
        target = guard.ProcessIdentity(
            pid=12345, start_ticks=987654, uid=1000,
            avd="Trindade_UX_v030", port=5556, binary_name="qemu-system-x86_64"
        )

        # 4a: Original exception present + cleanup raises KeyboardInterrupt
        controller = _make_pidfd_mock(exit_on_signal=True)
        state_sink = {}
        def crashing_runner():
            raise RuntimeError("Original runner failure")
        def interrupt_cleanup():
            raise KeyboardInterrupt()

        with self.assertRaises(RuntimeError) as cm:
            guard.run_guarded_supervisor(
                runner=crashing_runner,
                target=target,
                controller=controller,
                state_sink=state_sink,
                cleanup_probe=interrupt_cleanup,
                grace_seconds=0.1,
                poll_interval=0.02
            )
        self.assertIn("Original runner failure", str(cm.exception))
        # Target termination was guaranteed in finally
        controller.pidfd_send_signal.assert_called_once_with(99, signal.SIGTERM)
        self.assertTrue(state_sink.get("probe_cleanup_failed"))
        self.assertIn("KeyboardInterrupt", state_sink.get("probe_cleanup_error", ""))
        self.assertEqual(state_sink.get("state"), guard.LifecycleState.ERROR)

        # 4b: No original exception + cleanup raises KeyboardInterrupt -> surfaces cleanup interrupt
        controller2 = _make_pidfd_mock(exit_on_signal=True)
        state_sink2 = {}
        def clean_runner():
            pass

        with self.assertRaises(KeyboardInterrupt):
            guard.run_guarded_supervisor(
                runner=clean_runner,
                target=target,
                controller=controller2,
                state_sink=state_sink2,
                cleanup_probe=interrupt_cleanup,
                grace_seconds=0.1,
                poll_interval=0.02
            )
        # Target termination was guaranteed in finally
        controller2.pidfd_send_signal.assert_called_once_with(99, signal.SIGTERM)
        self.assertTrue(state_sink2.get("probe_cleanup_failed"))
        self.assertEqual(state_sink2.get("state"), guard.LifecycleState.ERROR)


class TestI2EmulatorLauncher(unittest.TestCase):
    """Hermetic unit tests for I2 local emulator launcher preparation."""

    def setUp(self):
        self.valid_mem = (5000000, 2500000)  # > 4718592 kB RAM and > 2097152 kB Swap
        self.controller = _make_pidfd_mock()
        self.controller.is_direct_child = True
        self.controller.get_ppid = MagicMock(return_value=os.getpid())
        # Default validator for hermetic offline test environment where real SDK is not on disk
        self.default_validator = lambda p: True

    def test_refuses_spawn_without_explicit_opt_in(self):
        spawner = MagicMock()
        launcher = guard.EmulatorLauncher(
            explicit_opt_in=False,
            mem_reader=lambda: self.valid_mem,
            controller=self.controller,
            spawner=spawner
        )
        res = launcher.launch()
        self.assertFalse(res.success)
        self.assertIn("opt-in", res.error.lower())
        spawner.assert_not_called()

    def test_refuses_spawn_on_low_or_unknown_memory_capacity(self):
        spawner = MagicMock()
        # RAM below 4718592 kB
        launcher_low_ram = guard.EmulatorLauncher(
            explicit_opt_in=True,
            mem_reader=lambda: (4000000, 2500000),
            controller=self.controller,
            spawner=spawner
        )
        res = launcher_low_ram.launch()
        self.assertFalse(res.success)
        self.assertIn("memory", res.error.lower())
        spawner.assert_not_called()

        # Swap below 2097152 kB
        launcher_low_swap = guard.EmulatorLauncher(
            explicit_opt_in=True,
            mem_reader=lambda: (5000000, 1000000),
            controller=self.controller,
            spawner=spawner
        )
        res2 = launcher_low_swap.launch()
        self.assertFalse(res2.success)
        self.assertIn("swap", res2.error.lower())
        spawner.assert_not_called()

        # Corrupted / exception mem reader
        launcher_err = guard.EmulatorLauncher(
            explicit_opt_in=True,
            mem_reader=lambda: (float("nan"), 2500000),
            controller=self.controller,
            spawner=spawner
        )
        res3 = launcher_err.launch()
        self.assertFalse(res3.success)
        spawner.assert_not_called()

    def test_refuses_spawn_when_port_or_avd_occupied(self):
        spawner = MagicMock()
        launcher = guard.EmulatorLauncher(
            explicit_opt_in=True,
            mem_reader=lambda: self.valid_mem,
            controller=self.controller,
            availability_check=lambda avd, port: False,
            spawner=spawner
        )
        res = launcher.launch()
        self.assertFalse(res.success)
        self.assertIn("occupied", res.error.lower())
        spawner.assert_not_called()

    def test_refuses_spawn_when_uid_not_1000_or_no_pidfd(self):
        spawner = MagicMock()
        launcher_uid = guard.EmulatorLauncher(
            explicit_opt_in=True,
            mem_reader=lambda: self.valid_mem,
            controller=self.controller,
            target_uid=0,  # root rejected
            spawner=spawner
        )
        res = launcher_uid.launch()
        self.assertFalse(res.success)
        self.assertIn("uid", res.error.lower())
        spawner.assert_not_called()

        bad_ctrl = MagicMock()
        bad_ctrl.use_pidfd = False
        launcher_pidfd = guard.EmulatorLauncher(
            explicit_opt_in=True,
            mem_reader=lambda: self.valid_mem,
            controller=bad_ctrl,
            spawner=spawner
        )
        res2 = launcher_pidfd.launch()
        self.assertFalse(res2.success)
        self.assertIn("pidfd", res2.error.lower())
        spawner.assert_not_called()

    def test_exact_frozen_argv_on_successful_launch(self):
        captured_argv = []
        mock_proc = MagicMock()
        mock_proc.pid = 12345
        mock_proc.poll.return_value = None
        def fake_spawner(argv):
            captured_argv.extend(argv)
            return mock_proc

        expected_argv = [
            "/test/bin/emulator",
            "-avd", "Trindade_UX_v030",
            "-port", "5556",
            "-memory", "1536",
            "-cores", "2",
            "-no-window",
            "-no-audio",
            "-no-snapshot-load",
            "-no-snapshot-save"
        ]
        self.controller.is_direct_child = True
        self.controller.get_ppid = MagicMock(return_value=os.getpid())
        self.controller.get_cmdline.return_value = list(expected_argv)
        self.controller.get_exe_path = MagicMock(return_value="/test/bin/emulator")

        with unittest.mock.patch("os.path.realpath", return_value="/test/bin/emulator"), \
             unittest.mock.patch("os.path.isfile", return_value=True), \
             unittest.mock.patch("os.access", return_value=True):
            launcher = guard.EmulatorLauncher(
                explicit_opt_in=True,
                mem_reader=lambda: self.valid_mem,
                controller=self.controller,
                availability_check=lambda avd, port: True,
                spawner=fake_spawner,
                emulator_binary="/test/bin/emulator"
            )
            res = launcher.launch()
            self.assertTrue(res.success)
            self.assertEqual(captured_argv, expected_argv)
            self.assertEqual(res.identity.pid, 12345)
            self.assertEqual(res.identity.start_ticks, 987654)
            self.assertEqual(res.identity.uid, 1000)
            self.assertEqual(res.identity.avd, "Trindade_UX_v030")
            self.assertEqual(res.identity.port, 5556)
            self.assertTrue(res.is_direct_child)
            self.assertIsNone(res.pidfd)
            self.assertTrue(res.transferred)

    def test_single_use_consumed_before_spawner_no_respawn(self):
        spawner = MagicMock()
        spawner.side_effect = RuntimeError("Spawn crash")
        launcher = guard.EmulatorLauncher(
            explicit_opt_in=True,
            mem_reader=lambda: self.valid_mem,
            controller=self.controller,
            availability_check=lambda avd, port: True,
            binary_validator=self.default_validator,
            spawner=spawner
        )
        res1 = launcher.launch()
        self.assertFalse(res1.success)
        self.assertEqual(spawner.call_count, 1)

        res2 = launcher.launch()
        self.assertFalse(res2.success)
        self.assertIn("single-use", res2.error.lower())
        self.assertEqual(spawner.call_count, 1)

    def test_post_spawn_identity_or_wrapper_mismatch_retains_ownership_no_blind_signal(self):
        mock_proc = MagicMock()
        mock_proc.pid = 12345
        mock_proc.poll.return_value = None

        mismatch_ctrl = _make_pidfd_mock()
        mismatch_ctrl.is_direct_child = True
        mismatch_ctrl.get_ppid = MagicMock(return_value=os.getpid())
        mismatch_ctrl.get_cmdline.return_value = ["/bin/bash", "emulator_wrapper.sh"]

        launcher = guard.EmulatorLauncher(
            explicit_opt_in=True,
            mem_reader=lambda: self.valid_mem,
            controller=mismatch_ctrl,
            availability_check=lambda avd, port: True,
            binary_validator=self.default_validator,
            spawner=lambda argv: mock_proc
        )
        res = launcher.launch()
        self.assertFalse(res.success)
        self.assertIn("identity", res.error.lower())
        self.assertEqual(res.child_handle, mock_proc)
        self.assertTrue(res.pending_cleanup)
        mismatch_ctrl.pidfd_send_signal.assert_not_called()

    # --- New I2 Safety Defect Repro Tests ---

    def test_repro_i2_optin_string_false_0_1_none_refuses(self):
        """Only explicit_opt_in is True permits launch; 'false', 0, 1, None refuse zero spawns."""
        for invalid_optin in ["false", 0, 1, None, "True", "1"]:
            with self.subTest(optin=invalid_optin):
                spawner = MagicMock()
                launcher = guard.EmulatorLauncher(
                    explicit_opt_in=invalid_optin,
                    mem_reader=lambda: self.valid_mem,
                    controller=self.controller,
                    availability_check=lambda avd, port: True,
                    spawner=spawner
                )
                res = launcher.launch()
                self.assertFalse(res.success)
                self.assertIn("opt-in", res.error.lower())
                spawner.assert_not_called()

    def test_repro_i2_availability_missing_none_unknown_refuses(self):
        """availability_check is mandatory and must return strictly True; None, missing, or truthy refuse."""
        for bad_check in [None, lambda avd, port: "yes", lambda avd, port: None, lambda avd, port: 1]:
            with self.subTest(checker=bad_check):
                spawner = MagicMock()
                launcher = guard.EmulatorLauncher(
                    explicit_opt_in=True,
                    mem_reader=lambda: self.valid_mem,
                    controller=self.controller,
                    availability_check=bad_check,
                    spawner=spawner
                )
                res = launcher.launch()
                self.assertFalse(res.success)
                spawner.assert_not_called()

    def test_repro_i2_relative_binary_or_changed_port_avd_refuses(self):
        """Relative/non-absolute binary, binary validation failure, or deviation from immutable port/AVD refuse."""
        spawner = MagicMock()
        # Relative binary path
        launcher_rel = guard.EmulatorLauncher(
            explicit_opt_in=True,
            mem_reader=lambda: self.valid_mem,
            controller=self.controller,
            availability_check=lambda avd, port: True,
            emulator_binary="emulator",  # relative!
            spawner=spawner
        )
        res_rel = launcher_rel.launch()
        self.assertFalse(res_rel.success)
        spawner.assert_not_called()

        # Deviated port 5554 (must not touch Studio/5554)
        launcher_port = guard.EmulatorLauncher(
            explicit_opt_in=True,
            mem_reader=lambda: self.valid_mem,
            controller=self.controller,
            availability_check=lambda avd, port: True,
            port=5554,
            spawner=spawner
        )
        res_port = launcher_port.launch()
        self.assertFalse(res_port.success)
        spawner.assert_not_called()

        # Deviated AVD name
        launcher_avd = guard.EmulatorLauncher(
            explicit_opt_in=True,
            mem_reader=lambda: self.valid_mem,
            controller=self.controller,
            availability_check=lambda avd, port: True,
            avd_name="Other_AVD",
            spawner=spawner
        )
        res_avd = launcher_avd.launch()
        self.assertFalse(res_avd.success)
        spawner.assert_not_called()

    def test_repro_i2_pidfd_missing_capability_negative_or_bool_refuses(self):
        """Controller missing pidfd_send_signal capability, or pidfd_open returning -1/bool refuses."""
        # Missing pidfd_send_signal capability
        no_send_ctrl = _make_pidfd_mock()
        del no_send_ctrl.pidfd_send_signal
        spawner = MagicMock()
        launcher_no_send = guard.EmulatorLauncher(
            explicit_opt_in=True,
            mem_reader=lambda: self.valid_mem,
            controller=no_send_ctrl,
            availability_check=lambda avd, port: True,
            binary_validator=self.default_validator,
            spawner=spawner
        )
        res1 = launcher_no_send.launch()
        self.assertFalse(res1.success)
        spawner.assert_not_called()

        # pidfd_open returns -1
        bad_fd_ctrl = _make_pidfd_mock()
        bad_fd_ctrl.is_direct_child = True
        bad_fd_ctrl.get_ppid = MagicMock(return_value=os.getpid())
        bad_fd_ctrl.pidfd_open.return_value = -1
        mock_proc = MagicMock()
        mock_proc.pid = 12345
        mock_proc.poll.return_value = None
        launcher_bad_fd = guard.EmulatorLauncher(
            explicit_opt_in=True,
            mem_reader=lambda: self.valid_mem,
            controller=bad_fd_ctrl,
            availability_check=lambda avd, port: True,
            binary_validator=self.default_validator,
            spawner=lambda argv: mock_proc
        )
        res2 = launcher_bad_fd.launch()
        self.assertFalse(res2.success)
        self.assertIn("pidfd", res2.error.lower())
        self.assertEqual(res2.child_handle, mock_proc)
        self.assertTrue(res2.pending_cleanup)

    def test_repro_i2_exited_child_post_spawn_refuses(self):
        """If child has already exited post-spawn (poll() is not None), refuse success with pending cleanup."""
        mock_proc = MagicMock()
        mock_proc.pid = 12345
        mock_proc.poll.return_value = 0  # Child exited immediately!

        launcher = guard.EmulatorLauncher(
            explicit_opt_in=True,
            mem_reader=lambda: self.valid_mem,
            controller=self.controller,
            availability_check=lambda avd, port: True,
            binary_validator=self.default_validator,
            spawner=lambda argv: mock_proc
        )
        res = launcher.launch()
        self.assertFalse(res.success)
        self.assertIn("exited", res.error.lower())
        self.assertEqual(res.child_handle, mock_proc)
        self.assertTrue(res.pending_cleanup)

    def test_repro_i2_post_spawn_ticks_error_retains_child_and_pidfd(self):
        """If get_start_ticks raises OSError post-spawn, child and opened pidfd are retained with pending cleanup."""
        mock_proc = MagicMock()
        mock_proc.pid = 12345
        mock_proc.poll.return_value = None

        ctrl = _make_pidfd_mock()
        ctrl.is_direct_child = True
        ctrl.get_ppid = MagicMock(return_value=os.getpid())
        ctrl.pidfd_open.return_value = 77
        ctrl.get_start_ticks.side_effect = [987654, 987654, OSError("Corrupted /proc/stat")]

        launcher = guard.EmulatorLauncher(
            explicit_opt_in=True,
            mem_reader=lambda: self.valid_mem,
            controller=ctrl,
            availability_check=lambda avd, port: True,
            binary_validator=self.default_validator,
            spawner=lambda argv: mock_proc
        )
        res = launcher.launch()
        self.assertFalse(res.success)
        self.assertEqual(res.child_handle, mock_proc)
        self.assertEqual(res.pidfd, 77)
        self.assertTrue(res.pending_cleanup)

    def test_repro_i2_repeated_attempt_retains_descriptor(self):
        """Repeated launch attempt after post-spawn failure preserves pending child descriptor."""
        mock_proc = MagicMock()
        mock_proc.pid = 12345
        mock_proc.poll.return_value = None

        ctrl = _make_pidfd_mock()
        ctrl.is_direct_child = True
        ctrl.get_ppid = MagicMock(return_value=os.getpid())
        ctrl.pidfd_open.return_value = -1  # Fails anchor

        launcher = guard.EmulatorLauncher(
            explicit_opt_in=True,
            mem_reader=lambda: self.valid_mem,
            controller=ctrl,
            availability_check=lambda avd, port: True,
            binary_validator=self.default_validator,
            spawner=lambda argv: mock_proc
        )
        res1 = launcher.launch()
        self.assertFalse(res1.success)
        self.assertEqual(res1.child_handle, mock_proc)

        # Second attempt must retain child descriptor and pending cleanup
        res2 = launcher.launch()
        self.assertFalse(res2.success)
        self.assertEqual(res2.child_handle, mock_proc)
        self.assertTrue(res2.pending_cleanup)

    def test_repro_i2_launcher_controller_handoff_no_reopen_leak(self):
        """Successful LaunchResult anchored pidfd is adopted by controller without opening new unverified fd."""
        mock_proc = MagicMock()
        mock_proc.pid = 12345
        mock_proc.poll.return_value = None

        expected_flags = [
            "-avd", "Trindade_UX_v030", "-port", "5556", "-memory", "1536",
            "-cores", "2", "-no-window", "-no-audio", "-no-snapshot-load", "-no-snapshot-save"
        ]
        self.controller.is_direct_child = True
        self.controller.get_ppid = MagicMock(return_value=os.getpid())
        self.controller.get_cmdline.return_value = ["/valid/absolute/bin/emulator"] + expected_flags
        self.controller.get_exe_path = MagicMock(return_value="/valid/absolute/bin/emulator")

        with unittest.mock.patch("os.path.realpath", return_value="/valid/absolute/bin/emulator"), \
             unittest.mock.patch("os.path.isfile", return_value=True), \
             unittest.mock.patch("os.access", return_value=True):
            launcher = guard.EmulatorLauncher(
                explicit_opt_in=True,
                mem_reader=lambda: self.valid_mem,
                controller=self.controller,
                availability_check=lambda avd, port: True,
                spawner=lambda argv: mock_proc,
                emulator_binary="/valid/absolute/bin/emulator"
            )
            res = launcher.launch()
            self.assertTrue(res.success)
            self.assertIsNone(res.pidfd)
            self.assertTrue(res.transferred)

            # Exclusive consumable binding handoff to real controller
            real_ctrl = guard.ProcessController()
            real_ctrl.use_pidfd = True
            real_ctrl.bind_anchored_pidfd(res.identity.pid, res.identity.start_ticks, 99)
            consumed_fd = real_ctrl.consume_bound_pidfd(res.identity)
            self.assertEqual(consumed_fd, 99)
            # Cannot consume again or return stale fd
            self.assertIsNone(real_ctrl.consume_bound_pidfd(res.identity))


class TestI2ResidualScoutFixes(unittest.TestCase):
    """Regression tests for the 4 residual defects from scout design."""

    def setUp(self):
        self.valid_mem = (5000000, 2500000)
        self.controller = _make_pidfd_mock()
        self.controller.is_direct_child = True

    def test_residual_1_binary_canonical_checks_reject_symlink_traversal_nonexistent(self):
        """Binary must be absolute, normpath == path, realpath == path, regular executable."""
        spawner = MagicMock()
        # Traversal / non-normpath: /bin/../bin/emulator
        launcher_traversal = guard.EmulatorLauncher(
            explicit_opt_in=True, mem_reader=lambda: self.valid_mem,
            controller=self.controller, availability_check=lambda avd, port: True,
            emulator_binary="/bin/../bin/emulator", spawner=spawner
        )
        res1 = launcher_traversal.launch()
        self.assertFalse(res1.success)
        spawner.assert_not_called()

        # Symlink: realpath != path
        with unittest.mock.patch("os.path.realpath", return_value="/different/real/path"):
            launcher_symlink = guard.EmulatorLauncher(
                explicit_opt_in=True, mem_reader=lambda: self.valid_mem,
                controller=self.controller, availability_check=lambda avd, port: True,
                emulator_binary="/canonical/path/emulator", spawner=spawner
            )
            res2 = launcher_symlink.launch()
            self.assertFalse(res2.success)
            spawner.assert_not_called()

    def test_residual_2_exact_exe_and_cmdline_rejects_extras_or_wrapper(self):
        """cmdline must equal exact frozen argv tuple (no -wipe-data, -gpu host); get_exe_path must equal binary."""
        mock_proc = MagicMock()
        mock_proc.pid = 12345
        mock_proc.poll.return_value = None

        ctrl = _make_pidfd_mock()
        ctrl.is_direct_child = True
        # cmdline has extra flags: -wipe-data and -gpu host
        extra_cmdline = [
            "qemu-system-x86_64", "-avd", "Trindade_UX_v030", "-port", "5556",
            "-memory", "1536", "-cores", "2", "-no-window", "-no-audio",
            "-no-snapshot-load", "-no-snapshot-save", "-wipe-data", "-gpu", "host"
        ]
        ctrl.get_cmdline.return_value = extra_cmdline
        ctrl.get_exe_path = MagicMock(return_value="/canonical/bin/emulator")

        with unittest.mock.patch("os.path.realpath", return_value="/canonical/bin/emulator"), \
             unittest.mock.patch("os.path.isfile", return_value=True), \
             unittest.mock.patch("os.access", return_value=True):
            launcher = guard.EmulatorLauncher(
                explicit_opt_in=True, mem_reader=lambda: self.valid_mem,
                controller=ctrl, availability_check=lambda avd, port: True,
                emulator_binary="/canonical/bin/emulator", spawner=lambda argv: mock_proc
            )
            res = launcher.launch()
            self.assertFalse(res.success)
            self.assertTrue(res.pending_cleanup)

    def test_residual_3_poll_oserror_and_parent_ticks_proof(self):
        """poll raising OSError fails closed pending; parent PID mismatch or ticks shift fails closed pending."""
        # 3a: poll raises OSError
        mock_proc = MagicMock()
        mock_proc.pid = 12345
        mock_proc.poll.side_effect = OSError("poll status read error")

        ctrl = _make_pidfd_mock()
        with unittest.mock.patch("os.path.realpath", return_value="/canonical/bin/emulator"), \
             unittest.mock.patch("os.path.isfile", return_value=True), \
             unittest.mock.patch("os.access", return_value=True):
            launcher = guard.EmulatorLauncher(
                explicit_opt_in=True, mem_reader=lambda: self.valid_mem,
                controller=ctrl, availability_check=lambda avd, port: True,
                emulator_binary="/canonical/bin/emulator", spawner=lambda argv: mock_proc
            )
            res = launcher.launch()
            self.assertFalse(res.success)
            self.assertTrue(res.pending_cleanup)

        # 3b: ppid is not current owner
        mock_proc2 = MagicMock()
        mock_proc2.pid = 12345
        mock_proc2.poll.return_value = None
        ctrl2 = _make_pidfd_mock()
        ctrl2.get_ppid = MagicMock(return_value=999999)  # Not our PID!

        with unittest.mock.patch("os.path.realpath", return_value="/canonical/bin/emulator"), \
             unittest.mock.patch("os.path.isfile", return_value=True), \
             unittest.mock.patch("os.access", return_value=True):
            launcher2 = guard.EmulatorLauncher(
                explicit_opt_in=True, mem_reader=lambda: self.valid_mem,
                controller=ctrl2, availability_check=lambda avd, port: True,
                emulator_binary="/canonical/bin/emulator", spawner=lambda argv: mock_proc2
            )
            res2 = launcher2.launch()
            self.assertFalse(res2.success)
            self.assertTrue(res2.pending_cleanup)

    def test_residual_4_identity_bound_exclusive_consumable_pidfd_binding(self):
        """ProcessController binding is exclusive to (pid, start_ticks), consumed once, and close invalidates before os.close."""
        ctrl = guard.ProcessController()
        # Bind PID 12345 with start_ticks 987654 to fd 77
        ok = ctrl.bind_anchored_pidfd(12345, 987654, 77)
        self.assertTrue(ok)

        # Duplicate/overwrite binding rejected
        dup_ok = ctrl.bind_anchored_pidfd(12345, 987654, 88)
        self.assertFalse(dup_ok)
        alias_ok = ctrl.bind_anchored_pidfd(54321, 111111, 88)
        self.assertFalse(alias_ok)

        # Consume with mismatched ticks returns None
        target_bad_ticks = guard.ProcessIdentity(12345, 999999, 1000, "AVD", 5556, "bin")
        self.assertIsNone(ctrl.consume_bound_pidfd(target_bad_ticks))

        # Close invalidates before os.close even if os.close fails
        with unittest.mock.patch("os.close", side_effect=OSError("EBADF")):
            with self.assertRaises(OSError):
                ctrl.close_fd(77)
        # After close, cannot return stale fd 77
        target_good = guard.ProcessIdentity(12345, 987654, 1000, "AVD", 5556, "bin")
        self.assertIsNone(ctrl.consume_bound_pidfd(target_good))

        # Re-bind and test consumable once: first consumes fd 78, second returns None
        ctrl.bind_anchored_pidfd(12345, 987654, 78)
        consumed = ctrl.consume_bound_pidfd(target_good)
        self.assertEqual(consumed, 78)
        second = ctrl.consume_bound_pidfd(target_good)
        self.assertIsNone(second, "Must be consumed once; second request cannot return stale binding")


class TestI2ExactIdentityReproductionAndEnforcement(unittest.TestCase):
    """Reproduction of identity vulnerabilities and strict enforcement of exact identity."""

    def setUp(self):
        self.valid_mem = (5000000, 2500000)
        self.canonical_bin = "/canonical/bin/emulator"
        self.expected_argv = [
            self.canonical_bin,
            "-avd", "Trindade_UX_v030",
            "-port", "5556",
            "-memory", "1536",
            "-cores", "2",
            "-no-window",
            "-no-audio",
            "-no-snapshot-load",
            "-no-snapshot-save",
        ]
        self.mock_proc = MagicMock()
        self.mock_proc.pid = 12345
        self.mock_proc.poll.return_value = None

    def _create_launcher(self, ctrl):
        return guard.EmulatorLauncher(
            explicit_opt_in=True,
            mem_reader=lambda: self.valid_mem,
            controller=ctrl,
            availability_check=lambda avd, port: True,
            emulator_binary=self.canonical_bin,
            spawner=lambda argv: self.mock_proc,
        )

    def test_repro_configured_canonical_and_unrelated_qemu_actual_exe_refuses(self):
        """Vulnerability repro: Configured /canonical/bin/emulator with actual exe /unrelated/qemu-system-x86_64 and argv0 /other/qemu-system-x86_64 must be refused."""
        ctrl = _make_pidfd_mock()
        ctrl.is_direct_child = True
        ctrl.get_ppid = MagicMock(return_value=os.getpid())
        ctrl.pidfd_open.return_value = 77
        ctrl.get_exe_path = MagicMock(return_value="/unrelated/qemu-system-x86_64")
        ctrl.get_cmdline.return_value = ["/other/qemu-system-x86_64"] + self.expected_argv[1:]

        with unittest.mock.patch("os.path.realpath", return_value=self.canonical_bin), \
             unittest.mock.patch("os.path.isfile", return_value=True), \
             unittest.mock.patch("os.access", return_value=True):
            launcher = self._create_launcher(ctrl)
            res = launcher.launch()
            self.assertFalse(res.success)
            self.assertTrue(res.pending_cleanup)
            self.assertEqual(res.child_handle, self.mock_proc)
            self.assertEqual(res.pidfd, 77)
            ctrl.pidfd_send_signal.assert_not_called()

    def test_repro_get_exe_path_none_refuses(self):
        """Vulnerability repro: get_exe_path returning None currently succeeds when cmdline has qemu argv0; must fail closed pending descriptor."""
        ctrl = _make_pidfd_mock()
        ctrl.is_direct_child = True
        ctrl.get_ppid = MagicMock(return_value=os.getpid())
        ctrl.pidfd_open.return_value = 77
        ctrl.get_exe_path = MagicMock(return_value=None)
        ctrl.get_cmdline.return_value = ["qemu-system-x86_64"] + self.expected_argv[1:]

        with unittest.mock.patch("os.path.realpath", return_value=self.canonical_bin), \
             unittest.mock.patch("os.path.isfile", return_value=True), \
             unittest.mock.patch("os.access", return_value=True):
            launcher = self._create_launcher(ctrl)
            res = launcher.launch()
            self.assertFalse(res.success)
            self.assertTrue(res.pending_cleanup)
            self.assertEqual(res.child_handle, self.mock_proc)
            self.assertEqual(res.pidfd, 77)
            ctrl.pidfd_send_signal.assert_not_called()

    def test_exact_identity_wrong_argv0_with_correct_exe_refuses(self):
        """Wrong argv0 in cmdline with correct actual exe must fail closed (both other path and qemu name)."""
        for bad_argv0 in ["/other/emulator", "qemu-system-x86_64", "/usr/bin/qemu-system-x86_64"]:
            with self.subTest(bad_argv0=bad_argv0):
                ctrl = _make_pidfd_mock()
                ctrl.is_direct_child = True
                ctrl.get_ppid = MagicMock(return_value=os.getpid())
                ctrl.pidfd_open.return_value = 77
                ctrl.get_exe_path = MagicMock(return_value=self.canonical_bin)
                ctrl.get_cmdline.return_value = [bad_argv0] + self.expected_argv[1:]

                with unittest.mock.patch("os.path.realpath", return_value=self.canonical_bin), \
                     unittest.mock.patch("os.path.isfile", return_value=True), \
                     unittest.mock.patch("os.access", return_value=True):
                    launcher = self._create_launcher(ctrl)
                    res = launcher.launch()
                    self.assertFalse(res.success)
                    self.assertTrue(res.pending_cleanup)
                    self.assertEqual(res.child_handle, self.mock_proc)
                    self.assertEqual(res.pidfd, 77)
                    ctrl.pidfd_send_signal.assert_not_called()

    def test_exact_identity_correct_argv0_wrong_exe_refuses(self):
        """Correct argv0 in cmdline with wrong actual exe must fail closed."""
        ctrl = _make_pidfd_mock()
        ctrl.is_direct_child = True
        ctrl.get_ppid = MagicMock(return_value=os.getpid())
        ctrl.pidfd_open.return_value = 77
        ctrl.get_exe_path = MagicMock(return_value="/other/bin/emulator")
        ctrl.get_cmdline.return_value = list(self.expected_argv)

        with unittest.mock.patch("os.path.realpath", return_value=self.canonical_bin), \
             unittest.mock.patch("os.path.isfile", return_value=True), \
             unittest.mock.patch("os.access", return_value=True):
            launcher = self._create_launcher(ctrl)
            res = launcher.launch()
            self.assertFalse(res.success)
            self.assertTrue(res.pending_cleanup)
            self.assertEqual(res.child_handle, self.mock_proc)
            self.assertEqual(res.pidfd, 77)
            ctrl.pidfd_send_signal.assert_not_called()

    def test_exact_identity_reader_error_or_missing_refuses(self):
        """Actual exe reader OSError or missing reader must fail closed pending descriptor."""
        # 1. get_exe_path raises OSError
        ctrl_err = _make_pidfd_mock()
        ctrl_err.is_direct_child = True
        ctrl_err.get_ppid = MagicMock(return_value=os.getpid())
        ctrl_err.pidfd_open.return_value = 77
        ctrl_err.get_exe_path = MagicMock(side_effect=OSError("Read error"))
        ctrl_err.get_cmdline.return_value = list(self.expected_argv)

        with unittest.mock.patch("os.path.realpath", return_value=self.canonical_bin), \
             unittest.mock.patch("os.path.isfile", return_value=True), \
             unittest.mock.patch("os.access", return_value=True):
            launcher = self._create_launcher(ctrl_err)
            res = launcher.launch()
            self.assertFalse(res.success)
            self.assertTrue(res.pending_cleanup)
            self.assertEqual(res.child_handle, self.mock_proc)
            self.assertEqual(res.pidfd, 77)
            ctrl_err.pidfd_send_signal.assert_not_called()

        # 2. Missing get_exe_path reader
        ctrl_missing = _make_pidfd_mock()
        ctrl_missing.is_direct_child = True
        ctrl_missing.get_ppid = MagicMock(return_value=os.getpid())
        ctrl_missing.pidfd_open.return_value = 77
        del ctrl_missing.get_exe_path
        ctrl_missing.get_cmdline.return_value = list(self.expected_argv)

        with unittest.mock.patch("os.path.realpath", return_value=self.canonical_bin), \
             unittest.mock.patch("os.path.isfile", return_value=True), \
             unittest.mock.patch("os.access", return_value=True):
            launcher2 = self._create_launcher(ctrl_missing)
            res2 = launcher2.launch()
            self.assertFalse(res2.success)
            self.assertTrue(res2.pending_cleanup)
            self.assertEqual(res2.child_handle, self.mock_proc)
            self.assertEqual(res2.pidfd, 77)
            ctrl_missing.pidfd_send_signal.assert_not_called()

    def test_exact_identity_cmdline_extra_arguments_refuses(self):
        """cmdline with extra arguments beyond frozen argv must fail closed."""
        ctrl = _make_pidfd_mock()
        ctrl.is_direct_child = True
        ctrl.get_ppid = MagicMock(return_value=os.getpid())
        ctrl.pidfd_open.return_value = 77
        ctrl.get_exe_path = MagicMock(return_value=self.canonical_bin)
        ctrl.get_cmdline.return_value = list(self.expected_argv) + ["-extra-flag"]

        with unittest.mock.patch("os.path.realpath", return_value=self.canonical_bin), \
             unittest.mock.patch("os.path.isfile", return_value=True), \
             unittest.mock.patch("os.access", return_value=True):
            launcher = self._create_launcher(ctrl)
            res = launcher.launch()
            self.assertFalse(res.success)
            self.assertTrue(res.pending_cleanup)
            self.assertEqual(res.child_handle, self.mock_proc)
            self.assertEqual(res.pidfd, 77)
            ctrl.pidfd_send_signal.assert_not_called()

    def test_exact_identity_cmdline_subset_missing_arguments_refuses(self):
        """cmdline with subset/missing arguments compared to frozen argv must fail closed."""
        ctrl = _make_pidfd_mock()
        ctrl.is_direct_child = True
        ctrl.get_ppid = MagicMock(return_value=os.getpid())
        ctrl.pidfd_open.return_value = 77
        ctrl.get_exe_path = MagicMock(return_value=self.canonical_bin)
        ctrl.get_cmdline.return_value = self.expected_argv[:-1]

        with unittest.mock.patch("os.path.realpath", return_value=self.canonical_bin), \
             unittest.mock.patch("os.path.isfile", return_value=True), \
             unittest.mock.patch("os.access", return_value=True):
            launcher = self._create_launcher(ctrl)
            res = launcher.launch()
            self.assertFalse(res.success)
            self.assertTrue(res.pending_cleanup)
            self.assertEqual(res.child_handle, self.mock_proc)
            self.assertEqual(res.pidfd, 77)
            ctrl.pidfd_send_signal.assert_not_called()

    def test_exact_identity_exact_success_fixture(self):
        """Exact canonical configured binary and exact frozen argv succeeds and anchors pidfd without signals."""
        ctrl = _make_pidfd_mock()
        ctrl.is_direct_child = True
        ctrl.get_ppid = MagicMock(return_value=os.getpid())
        ctrl.pidfd_open.return_value = 77
        ctrl.get_exe_path = MagicMock(return_value=self.canonical_bin)
        ctrl.get_cmdline.return_value = list(self.expected_argv)

        with unittest.mock.patch("os.path.realpath", return_value=self.canonical_bin), \
             unittest.mock.patch("os.path.isfile", return_value=True), \
             unittest.mock.patch("os.access", return_value=True):
            launcher = self._create_launcher(ctrl)
            res = launcher.launch()
            self.assertTrue(res.success)
            self.assertFalse(res.pending_cleanup)
            self.assertIsNone(res.pidfd)
            self.assertTrue(res.transferred)
            self.assertEqual(res.child_handle, self.mock_proc)
            self.assertEqual(res.identity.pid, 12345)
            self.assertEqual(res.identity.start_ticks, 987654)
            self.assertEqual(res.identity.uid, 1000)
            self.assertEqual(res.identity.avd, "Trindade_UX_v030")
            self.assertEqual(res.identity.port, 5556)
            self.assertEqual(res.identity.binary_name, "emulator")
            ctrl.pidfd_send_signal.assert_not_called()


class TestI2bParentAndAnchorProof(unittest.TestCase):
    """Hermetic unit tests for I2b parentage and anchor proof."""

    def setUp(self):
        self.valid_mem = (5000000, 2500000)
        self.canonical_bin = "/canonical/bin/emulator"
        self.expected_argv = [
            self.canonical_bin,
            "-avd", "Trindade_UX_v030",
            "-port", "5556",
            "-memory", "1536",
            "-cores", "2",
            "-no-window",
            "-no-audio",
            "-no-snapshot-load",
            "-no-snapshot-save",
        ]
        self.mock_proc = MagicMock()
        self.mock_proc.pid = 12345
        self.mock_proc.poll.return_value = None

    def _create_launcher(self, ctrl, mock_proc=None):
        proc = mock_proc or self.mock_proc
        return guard.EmulatorLauncher(
            explicit_opt_in=True,
            mem_reader=lambda: self.valid_mem,
            controller=ctrl,
            availability_check=lambda avd, port: True,
            emulator_binary=self.canonical_bin,
            spawner=lambda argv: proc,
        )

    def test_repro_fallback_static_is_direct_child_true_without_get_ppid_refuses(self):
        """Vulnerability repro: Static is_direct_child=True without get_ppid reader must fail closed."""
        ctrl = _make_pidfd_mock()
        ctrl.is_direct_child = True
        del ctrl.get_ppid
        ctrl.get_exe_path = MagicMock(return_value=self.canonical_bin)
        ctrl.get_cmdline.return_value = list(self.expected_argv)

        with unittest.mock.patch("os.path.realpath", return_value=self.canonical_bin), \
             unittest.mock.patch("os.path.isfile", return_value=True), \
             unittest.mock.patch("os.access", return_value=True):
            launcher = self._create_launcher(ctrl)
            res = launcher.launch()
            self.assertFalse(res.success)
            self.assertTrue(res.pending_cleanup)
            self.assertEqual(res.child_handle, self.mock_proc)
            ctrl.pidfd_send_signal.assert_not_called()

    def test_repro_parent_ticks_unknown_or_missing_refuses(self):
        """Parent start ticks unknown (None, missing, or error) must fail closed pending."""
        ctrl = _make_pidfd_mock()
        ctrl.is_direct_child = True
        owner_pid = os.getpid()
        ctrl.get_start_ticks = MagicMock(side_effect=lambda p: 987654 if p != owner_pid else None)
        ctrl.get_exe_path = MagicMock(return_value=self.canonical_bin)
        ctrl.get_cmdline.return_value = list(self.expected_argv)

        with unittest.mock.patch("os.path.realpath", return_value=self.canonical_bin), \
             unittest.mock.patch("os.path.isfile", return_value=True), \
             unittest.mock.patch("os.access", return_value=True):
            launcher = self._create_launcher(ctrl)
            res = launcher.launch()
            self.assertFalse(res.success)
            self.assertTrue(res.pending_cleanup)
            ctrl.pidfd_send_signal.assert_not_called()

    def test_repro_parent_ticks_change_across_launch_refuses(self):
        """Parent start ticks shifting across launch recheck must fail closed pending."""
        ctrl = _make_pidfd_mock()
        ctrl.is_direct_child = True
        owner_pid = os.getpid()
        parent_tick_vals = iter([987654, 999999])
        def fake_ticks(p):
            if p == owner_pid:
                return next(parent_tick_vals)
            return 987654
        ctrl.get_start_ticks = MagicMock(side_effect=fake_ticks)
        ctrl.get_exe_path = MagicMock(return_value=self.canonical_bin)
        ctrl.get_cmdline.return_value = list(self.expected_argv)

        with unittest.mock.patch("os.path.realpath", return_value=self.canonical_bin), \
             unittest.mock.patch("os.path.isfile", return_value=True), \
             unittest.mock.patch("os.access", return_value=True):
            launcher = self._create_launcher(ctrl)
            res = launcher.launch()
            self.assertFalse(res.success)
            self.assertTrue(res.pending_cleanup)
            ctrl.pidfd_send_signal.assert_not_called()

    def test_repro_child_ticks_change_across_pidfd_open_refuses(self):
        """Child start ticks shifting across pidfd_open must fail closed pending."""
        ctrl = _make_pidfd_mock()
        ctrl.is_direct_child = True
        owner_pid = os.getpid()
        child_tick_vals = iter([987654, 111111])
        def fake_ticks(p):
            if p == owner_pid:
                return 555555
            return next(child_tick_vals)
        ctrl.get_start_ticks = MagicMock(side_effect=fake_ticks)
        ctrl.get_exe_path = MagicMock(return_value=self.canonical_bin)
        ctrl.get_cmdline.return_value = list(self.expected_argv)

        with unittest.mock.patch("os.path.realpath", return_value=self.canonical_bin), \
             unittest.mock.patch("os.path.isfile", return_value=True), \
             unittest.mock.patch("os.access", return_value=True):
            launcher = self._create_launcher(ctrl)
            res = launcher.launch()
            self.assertFalse(res.success)
            self.assertTrue(res.pending_cleanup)
            ctrl.pidfd_send_signal.assert_not_called()

    def test_repro_fdinfo_mismatched_pid_refuses(self):
        """pidfd fdinfo Pid != child PID must fail closed pending."""
        ctrl = _make_pidfd_mock()
        ctrl.is_direct_child = True
        ctrl.get_fdinfo_pid = MagicMock(return_value=99999)
        ctrl.get_exe_path = MagicMock(return_value=self.canonical_bin)
        ctrl.get_cmdline.return_value = list(self.expected_argv)

        with unittest.mock.patch("os.path.realpath", return_value=self.canonical_bin), \
             unittest.mock.patch("os.path.isfile", return_value=True), \
             unittest.mock.patch("os.access", return_value=True):
            launcher = self._create_launcher(ctrl)
            res = launcher.launch()
            self.assertFalse(res.success)
            self.assertTrue(res.pending_cleanup)
            self.assertEqual(res.pidfd, 99)
            ctrl.pidfd_send_signal.assert_not_called()

    def test_repro_fdinfo_unknown_or_malformed_refuses(self):
        """pidfd fdinfo missing reader, returning None, or raising error must fail closed pending."""
        for bad_fdinfo in [None, "malformed", -1, 0, OSError("fdinfo unreadable")]:
            with self.subTest(bad=bad_fdinfo):
                ctrl = _make_pidfd_mock()
                ctrl.is_direct_child = True
                if isinstance(bad_fdinfo, Exception):
                    ctrl.get_fdinfo_pid = MagicMock(side_effect=bad_fdinfo)
                else:
                    ctrl.get_fdinfo_pid = MagicMock(return_value=bad_fdinfo)
                ctrl.get_exe_path = MagicMock(return_value=self.canonical_bin)
                ctrl.get_cmdline.return_value = list(self.expected_argv)

                with unittest.mock.patch("os.path.realpath", return_value=self.canonical_bin), \
                     unittest.mock.patch("os.path.isfile", return_value=True), \
                     unittest.mock.patch("os.access", return_value=True):
                    launcher = self._create_launcher(ctrl)
                    res = launcher.launch()
                    self.assertFalse(res.success)
                    self.assertTrue(res.pending_cleanup)
                    ctrl.pidfd_send_signal.assert_not_called()

    def test_repro_get_ppid_unknown_or_error_refuses(self):
        """get_ppid returning None or raising error must fail closed pending."""
        for bad_ppid in [None, OSError("PPID stat read error"), 999999]:
            with self.subTest(bad=bad_ppid):
                ctrl = _make_pidfd_mock()
                ctrl.is_direct_child = True
                if isinstance(bad_ppid, Exception):
                    ctrl.get_ppid = MagicMock(side_effect=bad_ppid)
                else:
                    ctrl.get_ppid = MagicMock(return_value=bad_ppid)
                ctrl.get_exe_path = MagicMock(return_value=self.canonical_bin)
                ctrl.get_cmdline.return_value = list(self.expected_argv)

                with unittest.mock.patch("os.path.realpath", return_value=self.canonical_bin), \
                     unittest.mock.patch("os.path.isfile", return_value=True), \
                     unittest.mock.patch("os.access", return_value=True):
                    launcher = self._create_launcher(ctrl)
                    res = launcher.launch()
                    self.assertFalse(res.success)
                    self.assertTrue(res.pending_cleanup)
                    ctrl.pidfd_send_signal.assert_not_called()

    def test_repro_exited_poll_later_or_error_refuses(self):
        """Child exiting after open (poll() returns exit code) must fail closed pending."""
        proc = MagicMock()
        proc.pid = 12345
        proc.poll = MagicMock(side_effect=[None, 137])

        ctrl = _make_pidfd_mock()
        ctrl.is_direct_child = True
        ctrl.get_exe_path = MagicMock(return_value=self.canonical_bin)
        ctrl.get_cmdline.return_value = list(self.expected_argv)

        with unittest.mock.patch("os.path.realpath", return_value=self.canonical_bin), \
             unittest.mock.patch("os.path.isfile", return_value=True), \
             unittest.mock.patch("os.access", return_value=True):
            launcher = self._create_launcher(ctrl, mock_proc=proc)
            res = launcher.launch()
            self.assertFalse(res.success)
            self.assertTrue(res.pending_cleanup)
            ctrl.pidfd_send_signal.assert_not_called()

    def test_repro_strict_pid_or_ticks_bool_refuses(self):
        """child.pid=True or ticks=True (bool instead of int) must fail closed pending."""
        proc = MagicMock()
        proc.pid = True
        proc.poll.return_value = None

        ctrl = _make_pidfd_mock()
        ctrl.is_direct_child = True
        ctrl.get_exe_path = MagicMock(return_value=self.canonical_bin)
        ctrl.get_cmdline.return_value = list(self.expected_argv)

        with unittest.mock.patch("os.path.realpath", return_value=self.canonical_bin), \
             unittest.mock.patch("os.path.isfile", return_value=True), \
             unittest.mock.patch("os.access", return_value=True):
            launcher = self._create_launcher(ctrl, mock_proc=proc)
            res = launcher.launch()
            self.assertFalse(res.success)
            self.assertTrue(res.pending_cleanup)
            ctrl.pidfd_send_signal.assert_not_called()

    def test_valid_exact_mock_proof_success(self):
        """Complete valid proof establishes ProcessIdentity.is_direct_child=True and LaunchResult.is_direct_child=True."""
        ctrl = _make_pidfd_mock()
        ctrl.is_direct_child = True
        ctrl.get_exe_path = MagicMock(return_value=self.canonical_bin)
        ctrl.get_cmdline.return_value = list(self.expected_argv)

        with unittest.mock.patch("os.path.realpath", return_value=self.canonical_bin), \
             unittest.mock.patch("os.path.isfile", return_value=True), \
             unittest.mock.patch("os.access", return_value=True):
            launcher = self._create_launcher(ctrl)
            res = launcher.launch()
            self.assertTrue(res.success)
            self.assertTrue(res.is_direct_child)
            self.assertTrue(res.identity.is_direct_child)
            self.assertIsNone(res.pidfd)
            self.assertTrue(res.transferred)
            self.assertFalse(res.pending_cleanup)
            ctrl.pidfd_send_signal.assert_not_called()

    def test_concrete_controller_fdinfo_pid_parsing(self):
        """ProcessController.get_fdinfo_pid strictly parses Pid field without real /proc reads."""
        real_ctrl = guard.ProcessController()
        mock_data = "pos:\t0\nflags:\t02000002\nmnt_id:\t15\nino:\t123\nPid:\t12345\n"
        with unittest.mock.patch("builtins.open", unittest.mock.mock_open(read_data=mock_data)):
            pid = real_ctrl.get_fdinfo_pid(77)
            self.assertEqual(pid, 12345)

        mock_data_no_pid = "pos:\t0\nflags:\t02\n"
        with unittest.mock.patch("builtins.open", unittest.mock.mock_open(read_data=mock_data_no_pid)):
            pid = real_ctrl.get_fdinfo_pid(77)
            self.assertIsNone(pid)

        mock_data_bad = "pos:\t0\nPid:\tnot_an_int\n"
        with unittest.mock.patch("builtins.open", unittest.mock.mock_open(read_data=mock_data_bad)):
            pid = real_ctrl.get_fdinfo_pid(77)
            self.assertIsNone(pid)

        with unittest.mock.patch("builtins.open", side_effect=OSError("Permission denied")):
            pid = real_ctrl.get_fdinfo_pid(77)
            self.assertIsNone(pid)

    def test_concrete_controller_is_pidfd_alive(self):
        """ProcessController.is_pidfd_alive uses poll without real /proc/readFD execution."""
        real_ctrl = guard.ProcessController()
        mock_poll_obj = MagicMock()

        mock_poll_obj.poll.return_value = []
        with unittest.mock.patch("select.poll", return_value=mock_poll_obj):
            self.assertTrue(real_ctrl.is_pidfd_alive(77))

        mock_poll_obj.poll.return_value = [(77, 1)]
        with unittest.mock.patch("select.poll", return_value=mock_poll_obj):
            self.assertFalse(real_ctrl.is_pidfd_alive(77))

        mock_poll_obj.poll.side_effect = OSError("EBADF")
        with unittest.mock.patch("select.poll", return_value=mock_poll_obj):
            self.assertFalse(real_ctrl.is_pidfd_alive(77))


class TestI2bResidualFixes(unittest.TestCase):
    """Hermetic unit tests for the three I2b residuals."""

    def setUp(self):
        self.valid_mem = (5000000, 2500000)
        self.canonical_bin = "/canonical/bin/emulator"
        self.expected_argv = [
            self.canonical_bin,
            "-avd", "Trindade_UX_v030",
            "-port", "5556",
            "-memory", "1536",
            "-cores", "2",
            "-no-window",
            "-no-audio",
            "-no-snapshot-load",
            "-no-snapshot-save",
        ]
        self.mock_proc = MagicMock()
        self.mock_proc.pid = 12345
        self.mock_proc.poll.return_value = None

    def _create_launcher(self, ctrl, mock_proc=None):
        proc = mock_proc or self.mock_proc
        return guard.EmulatorLauncher(
            explicit_opt_in=True,
            mem_reader=lambda: self.valid_mem,
            controller=ctrl,
            availability_check=lambda avd, port: True,
            emulator_binary=self.canonical_bin,
            spawner=lambda argv: proc,
        )

    # --- Residual 1: Strict get_fdinfo_pid rejection ---

    def test_residual_1_fdinfo_duplicate_pid_rejects(self):
        """fdinfo with duplicate Pid lines (identical or conflicting) must return None."""
        ctrl = guard.ProcessController()
        dup_same = "pos:\t0\nPid:\t12345\nPid:\t12345\n"
        with unittest.mock.patch("builtins.open", unittest.mock.mock_open(read_data=dup_same)):
            self.assertIsNone(ctrl.get_fdinfo_pid(77))

        dup_diff = "pos:\t0\nPid:\t12345\nPid:\t999\n"
        with unittest.mock.patch("builtins.open", unittest.mock.mock_open(read_data=dup_diff)):
            self.assertIsNone(ctrl.get_fdinfo_pid(77))

    def test_residual_1_fdinfo_trailing_junk_and_malformed_rejects(self):
        """fdinfo Pid with trailing tokens, bad delimiters, or no space must return None."""
        ctrl = guard.ProcessController()
        cases = [
            "pos:\t0\nPid:\t12345 junk\n",
            "pos:\t0\nPid:12345\n",
            "pos:\t0\nPid: 12345extra\n",
            "pos:\t0\nPid:\t-1\n",
            "pos:\t0\nPid:\t0\n",
        ]
        for bad_content in cases:
            with self.subTest(content=bad_content):
                with unittest.mock.patch("builtins.open", unittest.mock.mock_open(read_data=bad_content)):
                    self.assertIsNone(ctrl.get_fdinfo_pid(77))

    def test_residual_1_fdinfo_no_newline_or_oversize_rejects(self):
        """fdinfo without newline termination or > 8192 bytes must return None."""
        ctrl = guard.ProcessController()
        no_nl = "pos:\t0\nPid:\t12345"
        with unittest.mock.patch("builtins.open", unittest.mock.mock_open(read_data=no_nl)):
            self.assertIsNone(ctrl.get_fdinfo_pid(77))

        oversize = ("pos:\t0\nPid:\t12345\n" + "extra_info:\t0\n" * 700)[:8200] + "\n"
        self.assertGreater(len(oversize), 8192)
        with unittest.mock.patch("builtins.open", unittest.mock.mock_open(read_data=oversize)):
            self.assertIsNone(ctrl.get_fdinfo_pid(77))

    # --- Residual 2: Mandatory liveness callable output is True ONLY ---

    def test_residual_2_liveness_missing_or_non_callable_refuses(self):
        """Missing or non-callable is_pidfd_alive reader must fail closed pending."""
        for missing_or_bad in ["missing", "not_callable"]:
            with self.subTest(mode=missing_or_bad):
                ctrl = _make_pidfd_mock()
                ctrl.is_direct_child = True
                if missing_or_bad == "missing":
                    del ctrl.is_pidfd_alive
                else:
                    ctrl.is_pidfd_alive = "not_callable"
                ctrl.get_exe_path = MagicMock(return_value=self.canonical_bin)
                ctrl.get_cmdline.return_value = list(self.expected_argv)

                with unittest.mock.patch("os.path.realpath", return_value=self.canonical_bin), \
                     unittest.mock.patch("os.path.isfile", return_value=True), \
                     unittest.mock.patch("os.access", return_value=True):
                    launcher = self._create_launcher(ctrl)
                    res = launcher.launch()
                    self.assertFalse(res.success)
                    self.assertTrue(res.pending_cleanup)
                    self.assertEqual(res.pidfd, 99)
                    ctrl.pidfd_send_signal.assert_not_called()

    def test_residual_2_liveness_truthy_non_true_or_false_refuses(self):
        """is_pidfd_alive returning non-True ('unknown', 1, 0, None, False) must fail closed pending."""
        for non_true_val in ["unknown", 1, 0, None, False]:
            with self.subTest(val=non_true_val):
                ctrl = _make_pidfd_mock()
                ctrl.is_direct_child = True
                ctrl.is_pidfd_alive = MagicMock(return_value=non_true_val)
                ctrl.get_exe_path = MagicMock(return_value=self.canonical_bin)
                ctrl.get_cmdline.return_value = list(self.expected_argv)

                with unittest.mock.patch("os.path.realpath", return_value=self.canonical_bin), \
                     unittest.mock.patch("os.path.isfile", return_value=True), \
                     unittest.mock.patch("os.access", return_value=True):
                    launcher = self._create_launcher(ctrl)
                    res = launcher.launch()
                    self.assertFalse(res.success)
                    self.assertTrue(res.pending_cleanup)
                    self.assertEqual(res.pidfd, 99)
                    ctrl.pidfd_send_signal.assert_not_called()

    def test_residual_2_concrete_poll_events_fail_closed(self):
        """Concrete poll reporting POLLHUP, POLLERR, POLLNVAL or raising error must return False."""
        real_ctrl = guard.ProcessController()
        mock_p = MagicMock()

        for flag in [16, 8, 32]:
            with self.subTest(flag=flag):
                mock_p.poll.return_value = [(77, flag)]
                with unittest.mock.patch("select.poll", return_value=mock_p):
                    self.assertFalse(real_ctrl.is_pidfd_alive(77))

    # --- Residual 3: Strict int type (reject float/bool) for initial UID and post-open rechecks ---

    def test_residual_3_initial_uid_float_or_bool_refuses(self):
        """Initial UID returning float 1000.0 or bool True must fail closed."""
        for bad_uid in [1000.0, True, False]:
            with self.subTest(uid=bad_uid):
                ctrl = _make_pidfd_mock()
                ctrl.is_direct_child = True
                ctrl.get_uid = MagicMock(return_value=bad_uid)
                ctrl.get_exe_path = MagicMock(return_value=self.canonical_bin)
                ctrl.get_cmdline.return_value = list(self.expected_argv)

                with unittest.mock.patch("os.path.realpath", return_value=self.canonical_bin), \
                     unittest.mock.patch("os.path.isfile", return_value=True), \
                     unittest.mock.patch("os.access", return_value=True):
                    launcher = self._create_launcher(ctrl)
                    res = launcher.launch()
                    self.assertFalse(res.success)
                    self.assertTrue(res.pending_cleanup)
                    ctrl.pidfd_send_signal.assert_not_called()

    def test_residual_3_recheck_float_and_bool_matrix_refuses(self):
        """Post-open recheck returning float or bool for childticks, PPID, parentticks, or UID must fail closed."""
        owner_pid = os.getpid()
        matrix = [
            ("childticks", 987654.0),
            ("childticks", True),
            ("ppid", float(owner_pid)),
            ("ppid", True),
            ("parentticks", 987654.0),
            ("parentticks", True),
            ("uid", 1000.0),
            ("uid", True),
        ]
        for field, bad_val in matrix:
            with self.subTest(field=field, val=bad_val):
                ctrl = _make_pidfd_mock()
                ctrl.is_direct_child = True
                ctrl.get_exe_path = MagicMock(return_value=self.canonical_bin)
                ctrl.get_cmdline.return_value = list(self.expected_argv)

                if field == "childticks":
                    ctrl.get_start_ticks = MagicMock(side_effect=[987654, 987654, bad_val, 987654])
                elif field == "ppid":
                    ctrl.get_ppid = MagicMock(side_effect=[owner_pid, bad_val])
                elif field == "parentticks":
                    ctrl.get_start_ticks = MagicMock(side_effect=[987654, 987654, 987654, bad_val])
                elif field == "uid":
                    ctrl.get_uid = MagicMock(side_effect=[1000, bad_val])

                with unittest.mock.patch("os.path.realpath", return_value=self.canonical_bin), \
                     unittest.mock.patch("os.path.isfile", return_value=True), \
                     unittest.mock.patch("os.access", return_value=True):
                    launcher = self._create_launcher(ctrl)
                    res = launcher.launch()
                    self.assertFalse(res.success)
                    self.assertTrue(res.pending_cleanup)
                    self.assertEqual(res.pidfd, 99)
                    ctrl.pidfd_send_signal.assert_not_called()


class TestI2cFailedHandoffAndAliases(unittest.TestCase):
    """Concrete ProcessController integration tests for I2c failed handoff, reopen prevention, and alias safety."""

    def setUp(self):
        _enable_mock_sleep_progression(self)
        self.valid_mem = (5000000, 2500000)
        self.canonical_bin = "/canonical/bin/emulator"
        self.expected_argv = [
            self.canonical_bin,
            "-avd", "Trindade_UX_v030",
            "-port", "5556",
            "-memory", "1536",
            "-cores", "2",
            "-no-window",
            "-no-audio",
            "-no-snapshot-load",
            "-no-snapshot-save",
        ]

        # Scoped guard prohibiting accidental real /proc access in concrete tests
        self._orig_open = open
        def _guarded_open(file, *args, **kwargs):
            if isinstance(file, str) and file.startswith("/proc"):
                raise AssertionError(f"Unmocked real /proc open attempted: {file}")
            return self._orig_open(file, *args, **kwargs)
        self._open_patcher = unittest.mock.patch("builtins.open", side_effect=_guarded_open)
        self._open_patcher.start()
        self.addCleanup(self._open_patcher.stop)

        self._orig_readlink = os.readlink
        def _guarded_readlink(path, *args, **kwargs):
            if isinstance(path, str) and path.startswith("/proc"):
                raise AssertionError(f"Unmocked real /proc readlink attempted: {path}")
            return self._orig_readlink(path, *args, **kwargs)
        self._readlink_patcher = unittest.mock.patch("os.readlink", side_effect=_guarded_readlink)
        self._readlink_patcher.start()
        self.addCleanup(self._readlink_patcher.stop)

        self._orig_stat = os.stat
        def _guarded_stat(path, *args, **kwargs):
            if isinstance(path, str) and path.startswith("/proc"):
                raise AssertionError(f"Unmocked real /proc stat attempted: {path}")
            return self._orig_stat(path, *args, **kwargs)
        self._stat_patcher = unittest.mock.patch("os.stat", side_effect=_guarded_stat)
        self._stat_patcher.start()
        self.addCleanup(self._stat_patcher.stop)

    def test_explicit_proc_read_prohibition(self):
        """Prove that the scoped guard strictly prohibits unmocked /proc access."""
        with self.assertRaises(AssertionError) as ctx:
            open("/proc/self/stat", "r")
        self.assertIn("Unmocked real /proc open attempted", str(ctx.exception))

    def _setup_concrete_controller(self, pid=12345, start_ticks=987654):
        ctrl = guard.ProcessController()
        ctrl.use_pidfd = True
        ctrl.is_direct_child = True
        owner_pid = os.getpid()

        ctrl.get_start_ticks = MagicMock(side_effect=lambda p: start_ticks if p == pid else (999999 if p == owner_pid else None))
        ctrl.get_uid = MagicMock(return_value=1000)
        ctrl.get_ppid = MagicMock(return_value=owner_pid)
        ctrl.get_cmdline = MagicMock(return_value=list(self.expected_argv))
        ctrl.get_exe_path = MagicMock(return_value=self.canonical_bin)
        ctrl.get_fdinfo_pid = MagicMock(return_value=pid)
        ctrl.is_pidfd_alive = MagicMock(return_value=True)
        ctrl.try_reap = MagicMock(return_value=True)
        return ctrl

    def test_integrated_launcher_to_concrete_binding_to_safe_sigterm(self):
        """Essential flow: launcher -> concrete binding -> safe_sigterm consumes exact fd 77, signals 77 once, closes 77 once, no pidfd_open(88); later call no open/no signal."""
        mock_proc = MagicMock()
        mock_proc.pid = 12345
        mock_proc.poll.return_value = None

        ctrl = self._setup_concrete_controller(pid=12345, start_ticks=987654)
        ctrl.pidfd_open = MagicMock(return_value=77)

        with unittest.mock.patch("os.path.realpath", return_value=self.canonical_bin), \
             unittest.mock.patch("os.path.isfile", return_value=True), \
             unittest.mock.patch("os.access", return_value=True):
            launcher = guard.EmulatorLauncher(
                explicit_opt_in=True,
                mem_reader=lambda: self.valid_mem,
                controller=ctrl,
                availability_check=lambda avd, port: True,
                emulator_binary=self.canonical_bin,
                spawner=lambda argv: mock_proc,
            )
            res = launcher.launch()
            self.assertTrue(res.success)
            self.assertIsNone(res.pidfd, "Successful launcher must return pidfd=None to prevent raw FD alias")
            self.assertTrue(res.transferred, "Must indicate ownership transferred to controller")
            self.assertEqual(ctrl.binding_state, guard.BindingState.BOUND)

        def on_send(fd, sig):
            ctrl.get_start_ticks = MagicMock(return_value=None)
            return True

        with unittest.mock.patch("signal.pidfd_send_signal", side_effect=on_send) as mock_send, \
             unittest.mock.patch("os.close") as mock_close, \
             unittest.mock.patch("os.pidfd_open", return_value=88) as mock_kernel_open:
            state, msg = guard.safe_sigterm(res.identity, ctrl)
            self.assertEqual(state, guard.LifecycleState.STOPPED)
            mock_send.assert_called_once_with(77, signal.SIGTERM)
            mock_close.assert_called_once_with(77)
            mock_kernel_open.assert_not_called()
            self.assertEqual(ctrl.binding_state, guard.BindingState.CONSUMED)

            mock_send.reset_mock()
            mock_close.reset_mock()
            ctrl.get_start_ticks = MagicMock(return_value=987654)
            state2, msg2 = guard.safe_sigterm(res.identity, ctrl)
            self.assertEqual(state2, guard.LifecycleState.ERROR)
            mock_send.assert_not_called()
            mock_close.assert_not_called()
            mock_kernel_open.assert_not_called()

    def test_changed_ticks_binding_target_rejection_zero_opens_signals_retains_fd(self):
        """Binding PID 12345 ticks 111111 fd 77 vs target PID 12345 ticks 987654: abort zero opens, zero signals, retains fd 77."""
        ctrl = guard.ProcessController()
        ctrl.use_pidfd = True
        ctrl.bind_anchored_pidfd(12345, 111111, 77)

        target = guard.ProcessIdentity(12345, 987654, 1000, "Trindade_UX_v030", 5556, "emulator", is_direct_child=True)

        ctrl.get_start_ticks = MagicMock(return_value=987654)
        ctrl.get_uid = MagicMock(return_value=1000)
        ctrl.get_cmdline = MagicMock(return_value=list(self.expected_argv))

        with unittest.mock.patch("signal.pidfd_send_signal") as mock_send, \
             unittest.mock.patch("os.close") as mock_close, \
             unittest.mock.patch("os.pidfd_open", return_value=88) as mock_open:
            state, msg = guard.safe_sigterm(target, ctrl)
            self.assertEqual(state, guard.LifecycleState.ERROR)
            mock_send.assert_not_called()
            mock_close.assert_not_called()
            mock_open.assert_not_called()
            self.assertEqual(ctrl.binding_state, guard.BindingState.IDENTITY_MISMATCH)
            self.assertIsNotNone(ctrl._bound_pidfd)
            self.assertEqual(ctrl._bound_pidfd.fd, 77)

    def test_target_pid_mismatch_signals_zero(self):
        """Target PID mismatch aborts with zero opens and zero signals without real /proc access."""
        ctrl = guard.ProcessController()
        ctrl.use_pidfd = True
        ctrl.bind_anchored_pidfd(12345, 987654, 77)

        target = guard.ProcessIdentity(54321, 987654, 1000, "Trindade_UX_v030", 5556, "emulator", is_direct_child=True)
        # Fully mock getters for hermetic execution (no real /proc/54321 reads)
        ctrl.get_start_ticks = MagicMock(return_value=987654)
        ctrl.get_uid = MagicMock(return_value=1000)
        ctrl.get_cmdline = MagicMock(return_value=list(self.expected_argv))
        ctrl.get_exe_path = MagicMock(return_value=self.canonical_bin)
        ctrl.get_ppid = MagicMock(return_value=os.getpid())

        with unittest.mock.patch("signal.pidfd_send_signal") as mock_send, \
             unittest.mock.patch("os.pidfd_open", return_value=88) as mock_open:
            state, msg = guard.safe_sigterm(target, ctrl)
            mock_send.assert_not_called()
            mock_open.assert_not_called()
            self.assertEqual(state, guard.LifecycleState.ERROR)
            self.assertEqual(ctrl.binding_state, guard.BindingState.IDENTITY_MISMATCH)

    def test_close_before_consume_and_close_error_refuse_reopen(self):
        """Closed binding or close error transitions state and refuses reopen."""
        ctrl = guard.ProcessController()
        ctrl.use_pidfd = True
        ctrl.bind_anchored_pidfd(12345, 987654, 77)

        target = guard.ProcessIdentity(12345, 987654, 1000, "Trindade_UX_v030", 5556, "emulator", is_direct_child=True)
        ctrl.get_start_ticks = MagicMock(return_value=987654)
        ctrl.get_uid = MagicMock(return_value=1000)
        ctrl.get_cmdline = MagicMock(return_value=list(self.expected_argv))
        ctrl.get_exe_path = MagicMock(return_value=self.canonical_bin)
        ctrl.get_ppid = MagicMock(return_value=os.getpid())

        with unittest.mock.patch("os.close"):
            ctrl.close_fd(77)
        self.assertEqual(ctrl.binding_state, guard.BindingState.CLOSED)

        with unittest.mock.patch("signal.pidfd_send_signal") as mock_send, \
             unittest.mock.patch("os.pidfd_open", return_value=88) as mock_open:
            state, msg = guard.safe_sigterm(target, ctrl)
            self.assertEqual(state, guard.LifecycleState.ERROR)
            mock_send.assert_not_called()
            mock_open.assert_not_called()

        ctrl2 = guard.ProcessController()
        ctrl2.use_pidfd = True
        ctrl2.bind_anchored_pidfd(12345, 987654, 77)
        ctrl2.get_start_ticks = MagicMock(return_value=987654)
        ctrl2.get_uid = MagicMock(return_value=1000)
        ctrl2.get_cmdline = MagicMock(return_value=list(self.expected_argv))
        ctrl2.get_exe_path = MagicMock(return_value=self.canonical_bin)
        ctrl2.get_ppid = MagicMock(return_value=os.getpid())
        with unittest.mock.patch("os.close", side_effect=OSError("EBADF")):
            with self.assertRaises(OSError):
                ctrl2.close_fd(77)
        self.assertEqual(ctrl2.binding_state, guard.BindingState.ERROR)

        with unittest.mock.patch("signal.pidfd_send_signal") as mock_send, \
             unittest.mock.patch("os.pidfd_open", return_value=88) as mock_open:
            state2, msg2 = guard.safe_sigterm(target, ctrl2)
            self.assertEqual(state2, guard.LifecycleState.ERROR)
            mock_send.assert_not_called()
            mock_open.assert_not_called()

    def test_second_launch_after_transfer_no_respawn_no_alias(self):
        """Second launch after successful transfer refuses respawn, exposes transferred=True, pidfd=None."""
        mock_proc = MagicMock()
        mock_proc.pid = 12345
        mock_proc.poll.return_value = None

        ctrl = self._setup_concrete_controller(pid=12345, start_ticks=987654)
        ctrl.pidfd_open = MagicMock(return_value=77)

        with unittest.mock.patch("os.path.realpath", return_value=self.canonical_bin), \
             unittest.mock.patch("os.path.isfile", return_value=True), \
             unittest.mock.patch("os.access", return_value=True):
            launcher = guard.EmulatorLauncher(
                explicit_opt_in=True,
                mem_reader=lambda: self.valid_mem,
                controller=ctrl,
                availability_check=lambda avd, port: True,
                emulator_binary=self.canonical_bin,
                spawner=lambda argv: mock_proc,
            )
            res1 = launcher.launch()
            self.assertTrue(res1.success)
            self.assertIsNone(res1.pidfd)
            self.assertTrue(res1.transferred)

            res2 = launcher.launch()
            self.assertFalse(res2.success)
            self.assertIsNone(res2.pidfd)
            self.assertTrue(res2.transferred)
            self.assertFalse(res2.pending_cleanup)

    def test_existing_unbound_normal_probe_fd_opens_preserved(self):
        """Legacy controller without binding (NEVER_BOUND) still allows normal pidfd_open."""
        ctrl = guard.ProcessController()
        ctrl.use_pidfd = True
        self.assertEqual(ctrl.binding_state, guard.BindingState.NEVER_BOUND)

        target = guard.ProcessIdentity(12345, 987654, 1000, "Trindade_UX_v030", 5556, "emulator", is_direct_child=True)
        ctrl.get_start_ticks = MagicMock(return_value=987654)
        ctrl.get_uid = MagicMock(return_value=1000)
        ctrl.get_cmdline = MagicMock(return_value=list(self.expected_argv))
        ctrl.get_exe_path = MagicMock(return_value=self.canonical_bin)
        ctrl.get_ppid = MagicMock(return_value=os.getpid())

        def on_send2(fd, sig):
            ctrl.get_start_ticks = MagicMock(return_value=None)
            return True

        with unittest.mock.patch("os.pidfd_open", return_value=88) as mock_open, \
             unittest.mock.patch("signal.pidfd_send_signal", side_effect=on_send2) as mock_send, \
             unittest.mock.patch("os.close") as mock_close:
            state, msg = guard.safe_sigterm(target, ctrl)
            self.assertEqual(state, guard.LifecycleState.STOPPED)
            mock_open.assert_called_once_with(12345, 0)
            mock_send.assert_called_once_with(88, signal.SIGTERM)
            mock_close.assert_called_once_with(88)

    # --- Repro 1 & 2 Tests ---

    def test_repro_1_unknown_binding_state_refuses_legacy_open(self):
        """Unknown or missing binding state must refuse legacy pidfd_open with ERROR and zero opens/signals."""
        ctrl = guard.ProcessController()
        ctrl.use_pidfd = True
        ctrl._binding_state = "UNKNOWN_OR_MALFORMED"

        target = guard.ProcessIdentity(12345, 987654, 1000, "Trindade_UX_v030", 5556, "emulator", is_direct_child=True)
        ctrl.get_start_ticks = MagicMock(return_value=987654)
        ctrl.get_uid = MagicMock(return_value=1000)
        ctrl.get_cmdline = MagicMock(return_value=list(self.expected_argv))
        ctrl.get_exe_path = MagicMock(return_value=self.canonical_bin)
        ctrl.get_ppid = MagicMock(return_value=os.getpid())

        with unittest.mock.patch("os.pidfd_open", return_value=88) as mock_open, \
             unittest.mock.patch("signal.pidfd_send_signal") as mock_send:
            state, msg = guard.safe_sigterm(target, ctrl)
            self.assertEqual(state, guard.LifecycleState.ERROR)
            mock_open.assert_not_called()
            mock_send.assert_not_called()

    def test_repro_1_consume_oserror_returns_error_zero_opens_signals(self):
        """consume_bound_pidfd raising OSError must return ERROR, zero opens, zero signals, and retain binding."""
        ctrl = guard.ProcessController()
        ctrl.use_pidfd = True
        ctrl.bind_anchored_pidfd(12345, 987654, 77)

        target = guard.ProcessIdentity(12345, 987654, 1000, "Trindade_UX_v030", 5556, "emulator", is_direct_child=True)
        ctrl.get_start_ticks = MagicMock(return_value=987654)
        ctrl.get_uid = MagicMock(return_value=1000)
        ctrl.get_cmdline = MagicMock(return_value=list(self.expected_argv))
        ctrl.get_exe_path = MagicMock(return_value=self.canonical_bin)
        ctrl.get_ppid = MagicMock(return_value=os.getpid())
        ctrl.consume_bound_pidfd = MagicMock(side_effect=OSError("Consume I/O error"))

        with unittest.mock.patch("os.pidfd_open", return_value=88) as mock_open, \
             unittest.mock.patch("signal.pidfd_send_signal") as mock_send:
            state, msg = guard.safe_sigterm(target, ctrl)
            self.assertEqual(state, guard.LifecycleState.ERROR)
            mock_open.assert_not_called()
            mock_send.assert_not_called()
            self.assertIsNotNone(ctrl._bound_pidfd)
            self.assertEqual(ctrl._bound_pidfd.fd, 77)

    def test_repro_2_close_pidfd_error_returns_honest_error_not_stopped(self):
        """_close_pidfd error must return honest ERROR, never falsely report STOPPED."""
        ctrl = guard.ProcessController()
        ctrl.use_pidfd = True
        target = guard.ProcessIdentity(12345, 987654, 1000, "Trindade_UX_v030", 5556, "emulator", is_direct_child=True)
        ctrl.get_start_ticks = MagicMock(return_value=987654)
        ctrl.get_uid = MagicMock(return_value=1000)
        ctrl.get_cmdline = MagicMock(return_value=list(self.expected_argv))
        ctrl.get_exe_path = MagicMock(return_value=self.canonical_bin)
        ctrl.get_ppid = MagicMock(return_value=os.getpid())
        ctrl.pidfd_open = MagicMock(return_value=77)

        def on_send(fd, sig):
            ctrl.get_start_ticks = MagicMock(return_value=None)
            return True

        with unittest.mock.patch("signal.pidfd_send_signal", side_effect=on_send), \
             unittest.mock.patch.object(ctrl, "close_fd", side_effect=OSError("close EIO")):
            state, msg = guard.safe_sigterm(target, ctrl)
            self.assertEqual(state, guard.LifecycleState.ERROR)
            self.assertIn("close", msg.lower())

    def test_repro_2_close_error_does_not_mask_primary_signal_failure(self):
        """close failure must not mask primary signal failure reason."""
        ctrl = guard.ProcessController()
        ctrl.use_pidfd = True
        target = guard.ProcessIdentity(12345, 987654, 1000, "Trindade_UX_v030", 5556, "emulator", is_direct_child=True)
        ctrl.get_start_ticks = MagicMock(return_value=987654)
        ctrl.get_uid = MagicMock(return_value=1000)
        ctrl.get_cmdline = MagicMock(return_value=list(self.expected_argv))
        ctrl.get_exe_path = MagicMock(return_value=self.canonical_bin)
        ctrl.get_ppid = MagicMock(return_value=os.getpid())
        ctrl.pidfd_open = MagicMock(return_value=77)
        ctrl.pidfd_send_signal = MagicMock(return_value=False)

        with unittest.mock.patch.object(ctrl, "close_fd", side_effect=OSError("close EIO")):
            state, msg = guard.safe_sigterm(target, ctrl)
            self.assertEqual(state, guard.LifecycleState.ERROR)
            self.assertIn("pidfd_send_signal failed", msg)


class TestI2dUnconsumedBoundFDCleanup(unittest.TestCase):
    """Hermetic unit tests for I2d cleanup of unconsumed bound FDs across engine and runner paths."""

    def setUp(self):
        _enable_mock_sleep_progression(self)
        self.canonical_bin = "/canonical/bin/emulator"
        self.expected_argv = [
            self.canonical_bin,
            "-avd", "Trindade_UX_v030",
            "-port", "5556",
            "-memory", "1536",
            "-cores", "2",
            "-no-window",
            "-no-audio",
            "-no-snapshot-load",
            "-no-snapshot-save",
        ]

        # Non-effect guard with attempt counter: assert ZERO unexpected /proc accesses
        self.proc_access_count = 0
        self._orig_open = open
        def _guarded_open(file, *args, **kwargs):
            if isinstance(file, str) and file.startswith("/proc"):
                self.proc_access_count += 1
                raise AssertionError(f"Unexpected real /proc open: {file}")
            return self._orig_open(file, *args, **kwargs)
        self._open_patcher = unittest.mock.patch("builtins.open", side_effect=_guarded_open)
        self._open_patcher.start()
        self.addCleanup(self._open_patcher.stop)

    def _setup_controller(self, pid=12345, start_ticks=987654):
        ctrl = guard.ProcessController()
        ctrl.use_pidfd = True
        ctrl.is_direct_child = True
        owner_pid = os.getpid()

        ctrl.get_start_ticks = MagicMock(side_effect=lambda p: start_ticks if p == pid else (999999 if p == owner_pid else None))
        ctrl.get_uid = MagicMock(return_value=1000)
        ctrl.get_ppid = MagicMock(return_value=owner_pid)
        ctrl.get_cmdline = MagicMock(return_value=list(self.expected_argv))
        ctrl.get_exe_path = MagicMock(return_value=self.canonical_bin)
        ctrl.get_fdinfo_pid = MagicMock(return_value=pid)
        ctrl.is_pidfd_alive = MagicMock(return_value=True)
        ctrl.try_reap = MagicMock(return_value=True)
        ctrl.get_process_state = MagicMock(return_value=None)
        return ctrl

    def test_cleanup_bound_pidfd_idempotent_closes_once(self):
        """cleanup_bound_pidfd invalidates binding before os.close and is strictly idempotent."""
        ctrl = guard.ProcessController()
        ctrl.bind_anchored_pidfd(12345, 987654, 77)
        self.assertEqual(ctrl.binding_state, guard.BindingState.BOUND)

        with unittest.mock.patch("os.close") as mock_close:
            err = ctrl.cleanup_bound_pidfd()
            self.assertIsNone(err)
            mock_close.assert_called_once_with(77)
            self.assertEqual(ctrl.binding_state, guard.BindingState.CLOSED)
            self.assertIsNone(ctrl._bound_pidfd)

            # Idempotent second call: no duplicate close or reuse
            err2 = ctrl.cleanup_bound_pidfd()
            self.assertIsNone(err2)
            mock_close.assert_called_once_with(77)
        self.assertEqual(self.proc_access_count, 0)

    def test_cleanup_bound_pidfd_oserror_durable_error_no_retry(self):
        """cleanup_bound_pidfd records durable ERROR on close failure and prevents retry."""
        ctrl = guard.ProcessController()
        ctrl.bind_anchored_pidfd(12345, 987654, 77)

        with unittest.mock.patch("os.close", side_effect=OSError("EBADF")):
            err = ctrl.cleanup_bound_pidfd()
            self.assertIsInstance(err, OSError)
            self.assertEqual(ctrl.binding_state, guard.BindingState.ERROR)
            self.assertIsNone(ctrl._bound_pidfd)

            err2 = ctrl.cleanup_bound_pidfd()
            self.assertIs(err2, err)
        self.assertEqual(self.proc_access_count, 0)

    def test_runner_target_identity_mismatch_cleans_up_bound_fd_zero_signals(self):
        """Runner with target identity mismatch sends zero signals, zero opens, but closes bound fd once."""
        ctrl = self._setup_controller(12345, 111111)
        ctrl.bind_anchored_pidfd(12345, 111111, 77)

        target = guard.ProcessIdentity(12345, 987654, 1000, "Trindade_UX_v030", 5556, "emulator", is_direct_child=True)
        state_sink = {}
        probe_closed = False
        def fake_probe_cleanup():
            nonlocal probe_closed
            probe_closed = True
            return True

        with unittest.mock.patch("signal.pidfd_send_signal") as mock_sig, \
             unittest.mock.patch("os.pidfd_open") as mock_open, \
             unittest.mock.patch("os.close") as mock_close:
            guard.run_guarded_supervisor(
                runner=lambda: None,
                target=target,
                controller=ctrl,
                state_sink=state_sink,
                cleanup_probe=fake_probe_cleanup
            )
            mock_sig.assert_not_called()
            mock_open.assert_not_called()
            mock_close.assert_called_once_with(77)
            self.assertTrue(probe_closed)
            self.assertNotEqual(state_sink.get("state"), guard.LifecycleState.STOPPED)
            self.assertEqual(ctrl.binding_state, guard.BindingState.CLOSED)
        self.assertEqual(self.proc_access_count, 0)

    def test_runner_body_exception_cleans_up_unconsumed_bound_fd(self):
        """Runner body exception preserves original cause, runs probe cleanup and closes unconsumed bound fd."""
        ctrl = self._setup_controller(12345, 111111)
        ctrl.bind_anchored_pidfd(12345, 111111, 77)

        target = guard.ProcessIdentity(12345, 987654, 1000, "Trindade_UX_v030", 5556, "emulator", is_direct_child=True)
        state_sink = {}
        probe_closed = False
        def fake_probe_cleanup():
            nonlocal probe_closed
            probe_closed = True
            return True

        crash_err = RuntimeError("Engine crashed during operation")
        def failing_runner():
            raise crash_err

        with unittest.mock.patch("signal.pidfd_send_signal") as mock_sig, \
             unittest.mock.patch("os.pidfd_open") as mock_open, \
             unittest.mock.patch("os.close") as mock_close:
            guard.run_guarded_supervisor(
                runner=failing_runner,
                target=target,
                controller=ctrl,
                state_sink=state_sink,
                cleanup_probe=fake_probe_cleanup
            )
            self.assertEqual(state_sink.get("body_exception"), crash_err)
            self.assertIn("Engine crashed", state_sink.get("stop_reason"))
            mock_sig.assert_not_called()
            mock_open.assert_not_called()
            mock_close.assert_called_once_with(77)
            self.assertTrue(probe_closed)
            self.assertIn(state_sink.get("state"), (guard.LifecycleState.ERROR, guard.LifecycleState.PID_MISMATCH))
            self.assertNotEqual(state_sink.get("state"), guard.LifecycleState.STOPPED)
            self.assertEqual(ctrl.binding_state, guard.BindingState.CLOSED)
        self.assertEqual(self.proc_access_count, 0)

    def test_runner_body_interrupt_cleans_up_bound_fd_re_raises(self):
        """Runner body KeyboardInterrupt triggers unconsumed fd cleanup and re-raises original interrupt."""
        ctrl = self._setup_controller(12345, 111111)
        ctrl.bind_anchored_pidfd(12345, 111111, 77)

        target = guard.ProcessIdentity(12345, 987654, 1000, "Trindade_UX_v030", 5556, "emulator", is_direct_child=True)
        state_sink = {}

        kb_err = KeyboardInterrupt()
        def interrupted_runner():
            raise kb_err

        with unittest.mock.patch("os.close") as mock_close:
            with self.assertRaises(KeyboardInterrupt) as ctx:
                guard.run_guarded_supervisor(
                    runner=interrupted_runner,
                    target=target,
                    controller=ctrl,
                    state_sink=state_sink
                )
            self.assertIs(ctx.exception, kb_err)
            mock_close.assert_called_once_with(77)
            self.assertEqual(state_sink.get("body_exception"), kb_err)
            self.assertIn(state_sink.get("state"), (guard.LifecycleState.ERROR, guard.LifecycleState.PID_MISMATCH))
            self.assertNotEqual(state_sink.get("state"), guard.LifecycleState.STOPPED)
            self.assertEqual(ctrl.binding_state, guard.BindingState.CLOSED)
        self.assertEqual(self.proc_access_count, 0)

    def test_runner_cleanup_keyboard_interrupt_re_raises(self):
        """Controller cleanup KeyboardInterrupt re-raises interrupt out of supervisor."""
        ctrl = self._setup_controller(12345, 111111)
        ctrl.bind_anchored_pidfd(12345, 111111, 77)

        target = guard.ProcessIdentity(12345, 987654, 1000, "Trindade_UX_v030", 5556, "emulator", is_direct_child=True)
        state_sink = {}

        with unittest.mock.patch("os.close", side_effect=KeyboardInterrupt):
            with self.assertRaises(KeyboardInterrupt):
                guard.run_guarded_supervisor(
                    runner=lambda: None,
                    target=target,
                    controller=ctrl,
                    state_sink=state_sink
                )
            self.assertTrue(state_sink.get("controller_cleanup_failed"))
            self.assertEqual(state_sink.get("state"), guard.LifecycleState.ERROR)
            self.assertEqual(state_sink.get("primary_state"), guard.LifecycleState.PID_MISMATCH)
            self.assertEqual(state_sink.get("termination_state"), guard.LifecycleState.PID_MISMATCH)
        self.assertEqual(self.proc_access_count, 0)

    def test_runner_cleanup_oserror_sets_honest_error_in_sink(self):
        """Controller cleanup failure sets honest ERROR and controller_cleanup_failed in state_sink."""
        ctrl = self._setup_controller(12345, 111111)
        ctrl.bind_anchored_pidfd(12345, 111111, 77)

        target = guard.ProcessIdentity(12345, 987654, 1000, "Trindade_UX_v030", 5556, "emulator", is_direct_child=True)
        state_sink = {}

        with unittest.mock.patch("os.close", side_effect=OSError("EBADF")):
            guard.run_guarded_supervisor(
                runner=lambda: None,
                target=target,
                controller=ctrl,
                state_sink=state_sink
            )
            self.assertTrue(state_sink.get("controller_cleanup_failed"))
            self.assertEqual(state_sink.get("state"), guard.LifecycleState.ERROR)
            self.assertEqual(state_sink.get("primary_state"), guard.LifecycleState.PID_MISMATCH)
            self.assertEqual(state_sink.get("termination_state"), guard.LifecycleState.PID_MISMATCH)
            self.assertEqual(ctrl.binding_state, guard.BindingState.ERROR)
        self.assertEqual(self.proc_access_count, 0)

    def test_successful_consumed_termination_zero_additional_close(self):
        """When bound fd is consumed and closed by safe_sigterm, controller cleanup performs zero additional closes."""
        ctrl = self._setup_controller(12345, 987654)
        ctrl.bind_anchored_pidfd(12345, 987654, 77)

        target = guard.ProcessIdentity(12345, 987654, 1000, "Trindade_UX_v030", 5556, "emulator", is_direct_child=True)
        state_sink = {}

        def on_send(fd, sig):
            ctrl.get_start_ticks = MagicMock(return_value=None)
            return True

        with unittest.mock.patch("signal.pidfd_send_signal", side_effect=on_send) as mock_sig, \
             unittest.mock.patch("os.close") as mock_close:
            guard.run_guarded_supervisor(
                runner=lambda: None,
                target=target,
                controller=ctrl,
                state_sink=state_sink
            )
            mock_sig.assert_called_once_with(77, signal.SIGTERM)
            # Must be closed exactly once by safe_sigterm; cleanup_bound_pidfd in finally must not double-close!
            mock_close.assert_called_once_with(77)
            self.assertEqual(state_sink.get("state"), guard.LifecycleState.STOPPED)
            self.assertEqual(ctrl.binding_state, guard.BindingState.CONSUMED)
            self.assertIsNone(ctrl._bound_pidfd)
        self.assertEqual(self.proc_access_count, 0)

    def test_engine_ttl_stop_cleans_up_unconsumed_bound_fd(self):
        """SupervisorEngine stop on TTL cleans up unconsumed bound fd without signals."""
        clock = guard.FakeClock(monotonic_start=100.0)
        ctrl = self._setup_controller(12345, 111111)
        ctrl.bind_anchored_pidfd(12345, 111111, 77)

        target = guard.ProcessIdentity(12345, 987654, 1000, "Trindade_UX_v030", 5556, "emulator", is_direct_child=True)
        engine = guard.SupervisorEngine(
            target=target,
            controller=ctrl,
            ttl_sec=5.0,
            clock=clock
        )

        clock.advance_monotonic(6.0)
        with unittest.mock.patch("signal.pidfd_send_signal") as mock_sig, \
             unittest.mock.patch("os.close") as mock_close:
            stopped, state = engine.step()
            self.assertTrue(stopped)
            mock_sig.assert_not_called()
            mock_close.assert_called_once_with(77)
            self.assertEqual(ctrl.binding_state, guard.BindingState.CLOSED)
        self.assertEqual(self.proc_access_count, 0)

    # --- Five Confirmed Repro Tests for I2d Residuals ---

    def test_repro_1_engine_step_identity_mismatch_closes_bound_fd_without_signals(self):
        """Repro 1: engine.step direct PID_MISMATCH must close unconsumed bound fd and send zero signals."""
        clock = guard.FakeClock(monotonic_start=100.0)
        ctrl = self._setup_controller(12345, 111111)
        ctrl.bind_anchored_pidfd(12345, 111111, 77)

        target = guard.ProcessIdentity(12345, 987654, 1000, "Trindade_UX_v030", 5556, "emulator", is_direct_child=True)
        probe_cleaned = False
        def fake_probe_cleanup():
            nonlocal probe_cleaned
            probe_cleaned = True
            return True

        engine = guard.SupervisorEngine(
            target=target,
            controller=ctrl,
            ttl_sec=5.0,
            clock=clock,
            cleanup_probe=fake_probe_cleanup
        )

        with unittest.mock.patch("signal.pidfd_send_signal") as mock_sig, \
             unittest.mock.patch("os.close") as mock_close:
            halted, state = engine.step()
            self.assertTrue(halted)
            mock_sig.assert_not_called()
            mock_close.assert_called_once_with(77)
            self.assertTrue(probe_cleaned)
            self.assertEqual(state, guard.LifecycleState.PID_MISMATCH)
            self.assertEqual(ctrl.binding_state, guard.BindingState.CLOSED)
        self.assertEqual(self.proc_access_count, 0)

    def test_repro_2_terminate_controller_cleanup_interrupt_sets_state_error(self):
        """Repro 2: _terminate must persist state=ERROR before propagating KeyboardInterrupt / OSError."""
        clock = guard.FakeClock(monotonic_start=100.0)
        ctrl = self._setup_controller(12345, 987654)
        target = guard.ProcessIdentity(12345, 987654, 1000, "Trindade_UX_v030", 5556, "emulator", is_direct_child=True)
        ctrl.cleanup_bound_pidfd = MagicMock(side_effect=KeyboardInterrupt("interrupt in close"))

        probe_attempted = False
        def probe_cleanup():
            nonlocal probe_attempted
            probe_attempted = True
            return True

        engine = guard.SupervisorEngine(
            target=target, controller=ctrl, ttl_sec=5.0, clock=clock, cleanup_probe=probe_cleanup
        )

        with self.assertRaises(KeyboardInterrupt):
            engine._terminate("test stop")
        self.assertEqual(engine.state, guard.LifecycleState.ERROR)
        self.assertTrue(probe_attempted)
        self.assertEqual(self.proc_access_count, 0)

    def test_repro_3_cleanup_false_or_pending_marks_failure(self):
        """Repro 3: cleanup returning False or pending must mark failure, not only BaseException."""
        clock = guard.FakeClock(monotonic_start=100.0)
        ctrl = self._setup_controller(12345, 987654)
        target = guard.ProcessIdentity(12345, 987654, 1000, "Trindade_UX_v030", 5556, "emulator", is_direct_child=True)
        ctrl.cleanup_bound_pidfd = MagicMock(return_value=False)

        engine = guard.SupervisorEngine(target=target, controller=ctrl, ttl_sec=5.0, clock=clock)
        halted, state = engine._terminate("test stop")
        self.assertTrue(engine.has_probe_cleanup_failure)
        self.assertEqual(state, guard.LifecycleState.ERROR)
        self.assertEqual(self.proc_access_count, 0)

    def test_repro_4_cleanup_bound_pidfd_persists_exact_error_no_retry(self):
        """Repro 4: cleanup_bound_pidfd after close OSError must persist exact error object and not retry os.close."""
        ctrl = guard.ProcessController()
        ctrl.bind_anchored_pidfd(12345, 987654, 77)

        eio_err = OSError("EIO")
        with unittest.mock.patch("os.close", side_effect=eio_err) as mock_close:
            err1 = ctrl.cleanup_bound_pidfd()
            self.assertIs(err1, eio_err)
            mock_close.assert_called_once_with(77)

            # Second call: must return the exact same error object and NOT call os.close again
            err2 = ctrl.cleanup_bound_pidfd()
            self.assertIs(err2, eio_err)
            mock_close.assert_called_once_with(77)
        self.assertEqual(self.proc_access_count, 0)

    def test_repro_5_runner_state_sink_has_both_final_error_and_primary_pid_mismatch(self):
        """Repro 5: On cleanup failure, state_sink must have final ERROR and primary_state PID_MISMATCH."""
        ctrl = self._setup_controller(12345, 111111)
        ctrl.bind_anchored_pidfd(12345, 111111, 77)

        target = guard.ProcessIdentity(12345, 987654, 1000, "Trindade_UX_v030", 5556, "emulator", is_direct_child=True)
        state_sink = {}

        with unittest.mock.patch("os.close", side_effect=OSError("EBADF")):
            guard.run_guarded_supervisor(
                runner=lambda: None,
                target=target,
                controller=ctrl,
                state_sink=state_sink
            )
            self.assertEqual(state_sink.get("state"), guard.LifecycleState.ERROR)
            self.assertEqual(state_sink.get("primary_state"), guard.LifecycleState.PID_MISMATCH)
            self.assertEqual(state_sink.get("termination_state"), guard.LifecycleState.PID_MISMATCH)
        self.assertEqual(self.proc_access_count, 0)

    # --- Residual Interrupt Priority Tests ---

    def test_repro_body_runtime_error_probe_oserror_controller_keyboard_interrupt(self):
        """Body RuntimeError + probe OSError + controller KeyboardInterrupt propagates original body exception."""
        ctrl = self._setup_controller(12345, 987654)
        target = guard.ProcessIdentity(12345, 987654, 1000, "Trindade_UX_v030", 5556, "emulator", is_direct_child=True)
        state_sink = {}

        body_err = RuntimeError("original body failure")
        ctrl.cleanup_bound_pidfd = MagicMock(side_effect=KeyboardInterrupt("controller interrupt"))

        def probe_oserror():
            raise OSError("probe cleanup failure")

        with self.assertRaises(RuntimeError) as cm:
            guard.run_guarded_supervisor(
                runner=lambda: (_ for _ in ()).throw(body_err),
                target=target,
                controller=ctrl,
                state_sink=state_sink,
                cleanup_probe=probe_oserror
            )
        self.assertIs(cm.exception, body_err)
        self.assertEqual(state_sink.get("state"), guard.LifecycleState.ERROR)
        self.assertIs(state_sink.get("body_exception"), body_err)
        self.assertTrue(state_sink.get("probe_cleanup_failed"))
        self.assertTrue(state_sink.get("controller_cleanup_failed"))
        self.assertEqual(self.proc_access_count, 0)

    def test_repro_body_system_exit_probe_oserror_controller_keyboard_interrupt(self):
        """Body SystemExit + probe OSError + controller KeyboardInterrupt propagates original body SystemExit."""
        ctrl = self._setup_controller(12345, 987654)
        target = guard.ProcessIdentity(12345, 987654, 1000, "Trindade_UX_v030", 5556, "emulator", is_direct_child=True)
        state_sink = {}

        body_exit = SystemExit(42)
        ctrl.cleanup_bound_pidfd = MagicMock(side_effect=KeyboardInterrupt("controller interrupt"))

        def probe_oserror():
            raise OSError("probe cleanup failure")

        with self.assertRaises(SystemExit) as cm:
            guard.run_guarded_supervisor(
                runner=lambda: (_ for _ in ()).throw(body_exit),
                target=target,
                controller=ctrl,
                state_sink=state_sink,
                cleanup_probe=probe_oserror
            )
        self.assertIs(cm.exception, body_exit)
        self.assertEqual(state_sink.get("state"), guard.LifecycleState.ERROR)
        self.assertIs(state_sink.get("body_exception"), body_exit)
        self.assertTrue(state_sink.get("probe_cleanup_failed"))
        self.assertTrue(state_sink.get("controller_cleanup_failed"))
        self.assertEqual(self.proc_access_count, 0)

    def test_repro_no_body_probe_oserror_controller_keyboard_interrupt(self):
        """No body exception + probe OSError + controller KeyboardInterrupt propagates controller interrupt."""
        ctrl = self._setup_controller(12345, 987654)
        target = guard.ProcessIdentity(12345, 987654, 1000, "Trindade_UX_v030", 5556, "emulator", is_direct_child=True)
        state_sink = {}

        ctrl_kb = KeyboardInterrupt("controller interrupt")
        ctrl.cleanup_bound_pidfd = MagicMock(side_effect=ctrl_kb)

        def probe_oserror():
            raise OSError("probe cleanup failure")

        with self.assertRaises(KeyboardInterrupt) as cm:
            guard.run_guarded_supervisor(
                runner=lambda: None,
                target=target,
                controller=ctrl,
                state_sink=state_sink,
                cleanup_probe=probe_oserror
            )
        self.assertIs(cm.exception, ctrl_kb)
        self.assertEqual(state_sink.get("state"), guard.LifecycleState.ERROR)
        self.assertTrue(state_sink.get("probe_cleanup_failed"))
        self.assertTrue(state_sink.get("controller_cleanup_failed"))
        self.assertEqual(self.proc_access_count, 0)

    def test_repro_controller_system_exit_variants(self):
        """Controller SystemExit propagates original body if present, or surfaces controller SystemExit if no body."""
        ctrl = self._setup_controller(12345, 987654)
        target = guard.ProcessIdentity(12345, 987654, 1000, "Trindade_UX_v030", 5556, "emulator", is_direct_child=True)
        ctrl_exit = SystemExit(99)
        ctrl.cleanup_bound_pidfd = MagicMock(side_effect=ctrl_exit)

        def probe_oserror():
            raise OSError("probe cleanup failure")

        # Variant 1: body RuntimeError present -> propagates body RuntimeError
        body_err = RuntimeError("original body")
        state_sink1 = {}
        with self.assertRaises(RuntimeError) as cm1:
            guard.run_guarded_supervisor(
                runner=lambda: (_ for _ in ()).throw(body_err),
                target=target,
                controller=ctrl,
                state_sink=state_sink1,
                cleanup_probe=probe_oserror
            )
        self.assertIs(cm1.exception, body_err)
        self.assertEqual(state_sink1.get("state"), guard.LifecycleState.ERROR)

        # Variant 2: no body -> surfaces controller SystemExit
        state_sink2 = {}
        with self.assertRaises(SystemExit) as cm2:
            guard.run_guarded_supervisor(
                runner=lambda: None,
                target=target,
                controller=ctrl,
                state_sink=state_sink2,
                cleanup_probe=probe_oserror
            )
        self.assertIs(cm2.exception, ctrl_exit)
        self.assertEqual(state_sink2.get("state"), guard.LifecycleState.ERROR)
        self.assertEqual(self.proc_access_count, 0)


class TestFreshReadinessGateI3a(unittest.TestCase):
    """Hermetic unit tests for I3a fresh readiness admission and pending probe deadline."""

    def setUp(self):
        _enable_mock_sleep_progression(self)
        self.target = guard.ProcessIdentity(
            pid=12345,
            start_ticks=987654,
            uid=1000,
            avd="Trindade_UX_v030",
            port=5556,
            binary_name="qemu-system-x86_64"
        )
        self.proc_access_count = 0
        self._orig_open = open

        def _guarded_open(file, *args, **kwargs):
            if isinstance(file, str) and file.startswith("/proc"):
                self.proc_access_count += 1
                raise AssertionError(f"Unexpected real /proc open: {file}")
            return self._orig_open(file, *args, **kwargs)

        self._open_patcher = unittest.mock.patch("builtins.open", side_effect=_guarded_open)
        self._open_patcher.start()
        self.addCleanup(self._open_patcher.stop)

    def _setup_controller(self, clock=None):
        return _make_pidfd_mock()

    def _clear_probe(self):
        clear_dump = (
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
            "  ProcessRecord{ab12 100:com.test.app/u0a1}\n"
            "    notResponding=false\n"
            "    crashing=false\n"
        )
        return (0, clear_dump, "", False)

    def _anr_probe(self):
        anr_dump = (
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
            "  ProcessRecord{ab12 100:com.test.app/u0a1}\n"
            "    notResponding=true\n"
            "    crashing=false\n"
        )
        return (0, anr_dump, "", False)

    def test_boot_false_or_nonboolean_or_absent_identity_not_ready(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        ctrl = self._setup_controller(clock)

        # 1. boot_completed is False in BootEvidence
        ev_false = guard.BootEvidence(
            boot_completed=False, pid=12345, start_ticks=987654, timestamp_mono=0.0
        )
        eng = guard.SupervisorEngine(
            target=self.target, controller=ctrl, clock=clock,
            boot_evidence=ev_false, mem_reader=MagicMock(return_value=(2000000, 1000000))
        )
        ready, details = eng.check_readiness()
        self.assertFalse(ready)
        self.assertEqual(details["status"], "not_ready")
        self.assertIn("incomplete", details["reason"].lower())

        # 2. Truthy string boot evidence rejected
        eng.boot_evidence = "true"
        ready, details = eng.check_readiness()
        self.assertFalse(ready)
        self.assertEqual(details["status"], "not_ready")

        # 3. Truthy integer boot evidence rejected
        eng.boot_evidence = 1
        ready, details = eng.check_readiness()
        self.assertFalse(ready)
        self.assertEqual(details["status"], "not_ready")

        # 4. Bare boolean True without target identity rejected
        eng.boot_evidence = True
        ready, details = eng.check_readiness()
        self.assertFalse(ready)
        self.assertEqual(details["status"], "not_ready")
        self.assertIn("identity", details["reason"].lower())
        self.assertEqual(self.proc_access_count, 0)

    def test_mismatched_target_or_changed_start_ticks_rejects(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        ctrl = self._setup_controller(clock)

        # PID mismatch
        ev_pid_mismatch = guard.BootEvidence(
            boot_completed=True, pid=54321, start_ticks=987654, timestamp_mono=0.0
        )
        eng = guard.SupervisorEngine(
            target=self.target, controller=ctrl, clock=clock,
            boot_evidence=ev_pid_mismatch, mem_reader=MagicMock(return_value=(2000000, 1000000))
        )
        ready, details = eng.check_readiness()
        self.assertFalse(ready)
        self.assertIn("mismatch", details["reason"].lower())

        # Start ticks changed (PID reuse / different instance)
        ev_ticks_mismatch = guard.BootEvidence(
            boot_completed=True, pid=12345, start_ticks=111111, timestamp_mono=0.0
        )
        eng.boot_evidence = ev_ticks_mismatch
        ready, details = eng.check_readiness()
        self.assertFalse(ready)
        self.assertIn("mismatch", details["reason"].lower())

        # AVD mismatch
        ev_avd_mismatch = guard.BootEvidence(
            boot_completed=True, pid=12345, start_ticks=987654, timestamp_mono=0.0,
            avd="Different_AVD"
        )
        eng.boot_evidence = ev_avd_mismatch
        ready, details = eng.check_readiness()
        self.assertFalse(ready)
        self.assertIn("mismatch", details["reason"].lower())

        # UID mismatch
        ev_uid_mismatch = guard.BootEvidence(
            boot_completed=True, pid=12345, start_ticks=987654, timestamp_mono=0.0,
            uid=0
        )
        eng.boot_evidence = ev_uid_mismatch
        ready, details = eng.check_readiness()
        self.assertFalse(ready)
        self.assertIn("mismatch", details["reason"].lower())
        self.assertEqual(self.proc_access_count, 0)

    def test_stale_boot_evidence_and_stale_probe_rejects(self):
        clock = guard.FakeClock(monotonic_start=20.0)
        ctrl = self._setup_controller(clock)

        # Boot timestamp at 0.0s when clock is at 20.0s (stale_probe_sec=15.0)
        ev_stale = guard.BootEvidence(
            boot_completed=True, pid=12345, start_ticks=987654, timestamp_mono=0.0
        )
        eng = guard.SupervisorEngine(
            target=self.target, controller=ctrl, clock=clock,
            boot_evidence=ev_stale, mem_reader=MagicMock(return_value=(2000000, 1000000)),
            stale_probe_sec=15.0
        )
        ready, details = eng.check_readiness()
        self.assertFalse(ready)
        self.assertIn("stale", details["reason"].lower())

        # Boot timestamp in the future (30.0s > 20.0s)
        ev_future = guard.BootEvidence(
            boot_completed=True, pid=12345, start_ticks=987654, timestamp_mono=30.0
        )
        eng.boot_evidence = ev_future
        ready, details = eng.check_readiness()
        self.assertFalse(ready)
        self.assertIn("stale", details["reason"].lower())

        # Boot evidence fresh (20.0s), but probe timestamp is stale (0.0s)
        ev_fresh = guard.BootEvidence(
            boot_completed=True, pid=12345, start_ticks=987654, timestamp_mono=20.0
        )
        eng.boot_evidence = ev_fresh
        eng.last_probe_status = guard.ProbeStatus.CLEAR
        eng.last_probe_mono = 0.0  # 20s ago
        ready, details = eng.check_readiness()
        self.assertFalse(ready)
        self.assertIn("stale", details["reason"].lower())
        self.assertEqual(self.proc_access_count, 0)

    def test_first_pending_probe_waits_but_deadline_fails(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        ctrl = self._setup_controller(clock)
        start_probe = MagicMock()
        poll_probe = MagicMock(return_value=(False, None))  # pending

        ev = guard.BootEvidence(
            boot_completed=True, pid=12345, start_ticks=987654, timestamp_mono=0.0
        )
        eng = guard.SupervisorEngine(
            target=self.target, controller=ctrl, clock=clock,
            mem_reader=MagicMock(return_value=(2000000, 1000000)),
            start_probe=start_probe, poll_probe=poll_probe,
            cadence_sec=5.0, ttl_sec=1200.0, probe_deadline_sec=10.0,
            boot_evidence=ev
        )
        # Step 1: probe starts, is RUNNING and pending. Must NOT abort on UNKNOWN!
        halted, state = eng.step()
        self.assertFalse(halted)
        self.assertEqual(state, guard.LifecycleState.RUNNING)
        self.assertFalse(eng.is_ready)
        start_probe.assert_called_once()

        # Step 2: advance clock past deadline (11.0s > 10.0s). Must fail-closed on overrun!
        clock.advance_monotonic(11.0)
        halted, state = eng.step()
        self.assertTrue(halted)
        self.assertIn("overrun", eng.stop_reason.lower())
        ctrl.pidfd_send_signal.assert_called_with(99, signal.SIGTERM)
        self.assertEqual(self.proc_access_count, 0)

    def test_fresh_positive_readiness_lifecycle(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        ctrl = self._setup_controller(clock)
        start_probe = MagicMock()
        # Step 1 pending, Step 2 completes with fresh CLEAR probe
        probe_cycles = [
            (False, None),
            (True, self._clear_probe())
        ]
        poll_probe = MagicMock(side_effect=probe_cycles)

        ev = guard.BootEvidence(
            boot_completed=True, pid=12345, start_ticks=987654, timestamp_mono=0.0
        )
        eng = guard.SupervisorEngine(
            target=self.target, controller=ctrl, clock=clock,
            mem_reader=MagicMock(return_value=(2000000, 1000000)),
            start_probe=start_probe, poll_probe=poll_probe,
            cadence_sec=5.0, ttl_sec=1200.0, probe_deadline_sec=15.0,
            boot_evidence=ev
        )
        # Step 1: probe pending -> running, not ready
        halted, state = eng.step()
        self.assertFalse(halted)
        self.assertEqual(state, guard.LifecycleState.RUNNING)
        self.assertFalse(eng.is_ready)

        # Step 2: probe finishes CLEAR -> ready!
        clock.advance_monotonic(1.0)
        halted, state = eng.step()
        self.assertFalse(halted)
        self.assertEqual(state, guard.LifecycleState.RUNNING)
        self.assertTrue(eng.is_ready)
        self.assertEqual(eng.readiness_details["status"], "ready")
        self.assertEqual(self.proc_access_count, 0)

    def test_memory_and_ttl_and_anr_during_pending_probe_terminate(self):
        # Case A: Memory violation during pending probe terminates
        clock = guard.FakeClock(monotonic_start=0.0)
        ctrl = self._setup_controller(clock)
        mem_samples = [(2000000, 1000000), (guard.GUARD_RAM_MIN_KB - 100, 1000000)]
        eng_mem = guard.SupervisorEngine(
            target=self.target, controller=ctrl, clock=clock,
            mem_reader=MagicMock(side_effect=mem_samples),
            start_probe=MagicMock(), poll_probe=MagicMock(return_value=(False, None)),
            cadence_sec=5.0, ttl_sec=1200.0, probe_deadline_sec=30.0
        )
        halted, _ = eng_mem.step()  # t=0 OK
        self.assertFalse(halted)
        clock.advance_monotonic(5.0)
        halted, _ = eng_mem.step()  # t=5 memory drops below limit
        self.assertTrue(halted)
        self.assertIn("Memory threshold violation", eng_mem.stop_reason)

        # Case B: TTL expiry during pending probe terminates
        clock_ttl = guard.FakeClock(monotonic_start=0.0)
        ctrl_ttl = self._setup_controller(clock_ttl)
        eng_ttl = guard.SupervisorEngine(
            target=self.target, controller=ctrl_ttl, clock=clock_ttl,
            mem_reader=MagicMock(return_value=(2000000, 1000000)),
            start_probe=MagicMock(), poll_probe=MagicMock(return_value=(False, None)),
            cadence_sec=5.0, ttl_sec=1200.0, probe_deadline_sec=2000.0
        )
        halted, _ = eng_ttl.step()  # t=0 OK
        self.assertFalse(halted)
        clock_ttl.advance_monotonic(1201.0)
        halted, _ = eng_ttl.step()  # t=1201 TTL expired
        self.assertTrue(halted)
        self.assertIn("TTL expired", eng_ttl.stop_reason)

        # Case C: ANR detected terminates even during initial probe
        clock_anr = guard.FakeClock(monotonic_start=0.0)
        ctrl_anr = self._setup_controller(clock_anr)
        eng_anr = guard.SupervisorEngine(
            target=self.target, controller=ctrl_anr, clock=clock_anr,
            mem_reader=MagicMock(return_value=(2000000, 1000000)),
            start_probe=MagicMock(), poll_probe=MagicMock(return_value=(True, self._anr_probe())),
            cadence_sec=5.0, ttl_sec=1200.0, probe_deadline_sec=15.0
        )
        halted, _ = eng_anr.step()
        self.assertTrue(halted)
        self.assertIn("anr_detected", eng_anr.stop_reason.lower())
        self.assertEqual(self.proc_access_count, 0)

    def test_no_baseline_forgiveness_for_historical_anr(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        ctrl = self._setup_controller(clock)
        base_event = guard.ANREvent(package="com.test.app", pid=100, reason="notResponding")
        eng = guard.SupervisorEngine(
            target=self.target, controller=ctrl, clock=clock,
            mem_reader=MagicMock(return_value=(2000000, 1000000)),
            start_probe=MagicMock(), poll_probe=MagicMock(return_value=(True, self._anr_probe())),
            baseline_events={base_event}
        )
        # Historical ANR present in baseline_events MUST NOT be forgiven to CLEAR
        halted, _ = eng.step()
        self.assertTrue(halted)
        self.assertEqual(eng.last_probe_status, guard.ProbeStatus.ANR_DETECTED)
        self.assertFalse(eng.is_ready)
        self.assertEqual(self.proc_access_count, 0)

    def test_cleanup_durable_on_readiness_failure_or_overrun(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        ctrl = self._setup_controller(clock)
        cleanup_called = False

        def mock_cleanup():
            nonlocal cleanup_called
            cleanup_called = True
            return True

        eng = guard.SupervisorEngine(
            target=self.target, controller=ctrl, clock=clock,
            mem_reader=MagicMock(return_value=(2000000, 1000000)),
            start_probe=MagicMock(), poll_probe=MagicMock(return_value=(False, None)),
            probe_deadline_sec=5.0, cleanup_probe=mock_cleanup
        )
        eng.step()  # starts probe
        clock.advance_monotonic(6.0)
        halted, state = eng.step()  # overruns deadline
        self.assertTrue(halted)
        self.assertTrue(cleanup_called)
        ctrl.pidfd_send_signal.assert_called_with(99, signal.SIGTERM)
        ctrl.cleanup_bound_pidfd.assert_called()
        self.assertEqual(self.proc_access_count, 0)

    def test_completed_unknown_probe_fails_closed(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        ctrl = self._setup_controller(clock)
        unknown_probe = (0, "unrecognized garbage dumpsys output", "", False)
        eng = guard.SupervisorEngine(
            target=self.target, controller=ctrl, clock=clock,
            mem_reader=MagicMock(return_value=(2000000, 1000000)),
            start_probe=MagicMock(), poll_probe=MagicMock(return_value=(True, unknown_probe)),
            probe_deadline_sec=15.0
        )
        halted, _ = eng.step()
        self.assertTrue(halted)
        self.assertIn("unknown", eng.stop_reason.lower())
        self.assertEqual(self.proc_access_count, 0)

    def test_boot_and_probe_nan_inf_bool_rejected(self):
        clock = guard.FakeClock(monotonic_start=20.0)
        ctrl = self._setup_controller(clock)

        ev_nan = guard.BootEvidence(
            boot_completed=True, pid=12345, start_ticks=987654, timestamp_mono=float('nan')
        )
        eng = guard.SupervisorEngine(
            target=self.target, controller=ctrl, clock=clock,
            boot_evidence=ev_nan, mem_reader=MagicMock(return_value=(2000000, 1000000))
        )
        ready, details = eng.check_readiness()
        self.assertFalse(ready)
        self.assertEqual(details["status"], "not_ready")

        ev_inf = guard.BootEvidence(
            boot_completed=True, pid=12345, start_ticks=987654, timestamp_mono=float('inf')
        )
        eng.boot_evidence = ev_inf
        ready, details = eng.check_readiness()
        self.assertFalse(ready)
        self.assertEqual(details["status"], "not_ready")

        ev_bool = guard.BootEvidence(
            boot_completed=True, pid=12345, start_ticks=987654, timestamp_mono=True
        )
        eng.boot_evidence = ev_bool
        ready, details = eng.check_readiness()
        self.assertFalse(ready)
        self.assertEqual(details["status"], "not_ready")

        ev_valid = guard.BootEvidence(
            boot_completed=True, pid=12345, start_ticks=987654, timestamp_mono=20.0
        )
        eng.boot_evidence = ev_valid
        eng.last_probe_status = guard.ProbeStatus.CLEAR
        eng.last_probe_mono = float('nan')
        ready, details = eng.check_readiness()
        self.assertFalse(ready)
        self.assertEqual(details["status"], "not_ready")

        eng.last_probe_mono = float('inf')
        ready, details = eng.check_readiness()
        self.assertFalse(ready)
        self.assertEqual(details["status"], "not_ready")

        eng.last_probe_mono = True
        ready, details = eng.check_readiness()
        self.assertFalse(ready)
        self.assertEqual(details["status"], "not_ready")
        self.assertEqual(self.proc_access_count, 0)

    def test_evidence_pid_ticks_uid_float_or_bool_rejected(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        ctrl = self._setup_controller(clock)

        ev_float_pid = guard.BootEvidence(
            boot_completed=True, pid=12345.0, start_ticks=987654, timestamp_mono=0.0
        )
        eng = guard.SupervisorEngine(
            target=self.target, controller=ctrl, clock=clock,
            boot_evidence=ev_float_pid, mem_reader=MagicMock(return_value=(2000000, 1000000))
        )
        ready, details = eng.check_readiness()
        self.assertFalse(ready)
        self.assertIn("mismatch", details["reason"].lower())

        ev_float_ticks = guard.BootEvidence(
            boot_completed=True, pid=12345, start_ticks=987654.0, timestamp_mono=0.0
        )
        eng.boot_evidence = ev_float_ticks
        ready, details = eng.check_readiness()
        self.assertFalse(ready)
        self.assertIn("mismatch", details["reason"].lower())

        ev_float_uid = guard.BootEvidence(
            boot_completed=True, pid=12345, start_ticks=987654, timestamp_mono=0.0, uid=1000.0
        )
        eng.boot_evidence = ev_float_uid
        ready, details = eng.check_readiness()
        self.assertFalse(ready)
        self.assertIn("mismatch", details["reason"].lower())

        ev_bool_uid = guard.BootEvidence(
            boot_completed=True, pid=12345, start_ticks=987654, timestamp_mono=0.0, uid=True
        )
        eng.boot_evidence = ev_bool_uid
        ready, details = eng.check_readiness()
        self.assertFalse(ready)
        self.assertIn("mismatch", details["reason"].lower())
        self.assertEqual(self.proc_access_count, 0)

    def test_late_completion_poll_overrun_boundary(self):
        clock_a = guard.FakeClock(monotonic_start=0.0)
        ctrl_a = self._setup_controller(clock_a)
        start_probe_a = MagicMock()

        def on_poll_in_time():
            clock_a.advance_monotonic(10.0)
            return (True, self._clear_probe())

        ev_a = guard.BootEvidence(boot_completed=True, pid=12345, start_ticks=987654, timestamp_mono=10.0)
        eng_a = guard.SupervisorEngine(
            target=self.target, controller=ctrl_a, clock=clock_a,
            mem_reader=MagicMock(return_value=(2000000, 1000000)),
            start_probe=start_probe_a, poll_probe=on_poll_in_time,
            probe_deadline_sec=10.0, boot_evidence=ev_a
        )
        halted, state = eng_a.step()
        self.assertFalse(halted)
        self.assertTrue(eng_a.is_ready)

        clock_b = guard.FakeClock(monotonic_start=0.0)
        ctrl_b = self._setup_controller(clock_b)
        start_probe_b = MagicMock()

        def on_poll_late():
            clock_b.advance_monotonic(11.0)
            return (True, self._clear_probe())

        ev_b = guard.BootEvidence(boot_completed=True, pid=12345, start_ticks=987654, timestamp_mono=11.0)
        eng_b = guard.SupervisorEngine(
            target=self.target, controller=ctrl_b, clock=clock_b,
            mem_reader=MagicMock(return_value=(2000000, 1000000)),
            start_probe=start_probe_b, poll_probe=on_poll_late,
            probe_deadline_sec=10.0, boot_evidence=ev_b
        )
        halted, state = eng_b.step()
        self.assertTrue(halted)
        self.assertIn("overrun", eng_b.stop_reason.lower())
        self.assertFalse(eng_b.is_ready)
        self.assertEqual(self.proc_access_count, 0)


class TestHarnessTimeIsolationRegression(unittest.TestCase):
    """Deterministic isolation regressions for harness time/sleep scoping."""

    def test_module_import_preserves_time_sleep_and_monotonic_identities(self):
        # Module import preserves active time.sleep and time.monotonic identities without forced assignment
        prior_sleep = time.sleep
        prior_mono = time.monotonic
        import f3_emulator_guard  # noqa: F401
        self.assertIs(time.sleep, prior_sleep)
        self.assertIs(time.monotonic, prior_mono)

    def test_case_cleanup_restores_genuine_originals(self):
        prior_sleep = time.sleep
        prior_mono = time.monotonic
        fake_test = unittest.TestCase()
        _enable_mock_sleep_progression(fake_test, start_mono=1000.0)
        self.assertIsNot(time.sleep, prior_sleep)
        time.sleep(0.0)
        self.assertEqual(time.monotonic(), 1000.001)
        fake_test.doCleanups()
        self.assertIs(time.sleep, prior_sleep)
        self.assertIs(time.monotonic, prior_mono)

    def test_next_case_fresh_clock_reset_and_no_real_sleeps(self):
        prior_sleep = time.sleep
        prior_mono = time.monotonic
        t1 = unittest.TestCase()
        _enable_mock_sleep_progression(t1, start_mono=500.0)
        time.sleep(10.0)
        self.assertEqual(time.monotonic(), 510.0)
        t1.doCleanups()

        t2 = unittest.TestCase()
        _enable_mock_sleep_progression(t2, start_mono=500.0)
        self.assertEqual(time.monotonic(), 500.0)
        t2.doCleanups()
        self.assertIs(time.sleep, prior_sleep)
        self.assertIs(time.monotonic, prior_mono)

    def test_outer_barrier_preserved_after_case_cleanup(self):
        prior_sleep = MagicMock()
        prior_mono = MagicMock(return_value=999.0)
        with unittest.mock.patch.object(time, "sleep", prior_sleep), \
             unittest.mock.patch.object(time, "monotonic", prior_mono):
            self.assertIs(time.sleep, prior_sleep)
            self.assertIs(time.monotonic, prior_mono)
            fake_test = unittest.TestCase()
            _enable_mock_sleep_progression(fake_test, start_mono=500.0)
            self.assertIsNot(time.sleep, prior_sleep)
            time.sleep(5.0)
            self.assertEqual(time.monotonic(), 505.0)
            fake_test.doCleanups()
            self.assertIs(time.sleep, prior_sleep)
            self.assertIs(time.monotonic, prior_mono)

    def test_outer_barrier_preserved_even_on_case_exception(self):
        prior_sleep = MagicMock()
        prior_mono = MagicMock(return_value=999.0)
        with unittest.mock.patch.object(time, "sleep", prior_sleep), \
             unittest.mock.patch.object(time, "monotonic", prior_mono):
            fake_test = unittest.TestCase()
            _enable_mock_sleep_progression(fake_test, start_mono=500.0)
            try:
                raise RuntimeError("simulated case body failure")
            except RuntimeError:
                pass
            finally:
                fake_test.doCleanups()
            self.assertIs(time.sleep, prior_sleep)
            self.assertIs(time.monotonic, prior_mono)


class TestSupervisorSchedulerI3b1(unittest.TestCase):
    """Hermetic unit tests for I3b.1 SupervisorScheduler pacing and contracts."""

    def setUp(self):
        _enable_mock_sleep_progression(self)
        self.target = guard.ProcessIdentity(
            pid=12345,
            start_ticks=987654,
            uid=1000,
            avd="Trindade_UX_v030",
            port=5556,
            binary_name="qemu-system-x86_64"
        )
        self.proc_access_count = 0
        self._orig_open = open

        def _guarded_open(file, *args, **kwargs):
            if isinstance(file, str) and file.startswith("/proc"):
                self.proc_access_count += 1
                raise AssertionError(f"Unexpected real /proc open: {file}")
            return self._orig_open(file, *args, **kwargs)

        self._open_patcher = unittest.mock.patch("builtins.open", side_effect=_guarded_open)
        self._open_patcher.start()
        self.addCleanup(self._open_patcher.stop)

    def _setup_controller(self, clock=None):
        return _make_pidfd_mock()

    def _clear_probe(self):
        clear_dump = (
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
            "  ProcessRecord{ab12 100:com.test.app/u0a1}\n"
            "    notResponding=false\n"
            "    crashing=false\n"
        )
        return (0, clear_dump, "", False)

    def _anr_probe(self):
        anr_dump = (
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
            "  ProcessRecord{ab12 100:com.test.app/u0a1}\n"
            "    notResponding=true\n"
            "    crashing=false\n"
        )
        return (0, anr_dump, "", False)

    def test_strict_interval_and_sleeper_defaults(self):
        ctrl = self._setup_controller()
        engine = guard.SupervisorEngine(target=self.target, controller=ctrl)
        for bad_pi in [False, True, 0, 0.0, -1.0, float("nan"), float("inf"), "0.5"]:
            with self.assertRaises(ValueError):
                guard.SupervisorScheduler(engine, poll_interval=bad_pi)
        for bad_sl in ["not_callable", 123, False]:
            with self.assertRaises(TypeError):
                guard.SupervisorScheduler(engine, sleeper=bad_sl)
        sched_def = guard.SupervisorScheduler(engine, sleeper=None)
        self.assertIs(sched_def._sleeper, time.sleep)

    def test_different_clock_rejection_and_identity(self):
        clock_a = guard.FakeClock(monotonic_start=0.0)
        clock_b = guard.FakeClock(monotonic_start=0.0)
        ctrl = self._setup_controller(clock_a)
        engine = guard.SupervisorEngine(target=self.target, controller=ctrl, clock=clock_a)
        with self.assertRaises(ValueError):
            guard.SupervisorScheduler(engine, clock=clock_b)
        sched_a = guard.SupervisorScheduler(engine, clock=clock_a)
        self.assertIs(sched_a._clock, clock_a)
        sched_none = guard.SupervisorScheduler(engine, clock=None)
        self.assertIs(sched_none._clock, clock_a)

    def test_pacing_grid_work_duration_anchored(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        ctrl = self._setup_controller(clock)
        step_count = [0]
        sleeps = []
        def sleeper(dur):
            sleeps.append(dur)
            clock.advance_monotonic(dur)
        def on_poll():
            step_count[0] += 1
            if step_count[0] == 1:
                clock.advance_monotonic(0.3)
                return (False, None)
            elif step_count[0] == 2:
                clock.advance_monotonic(0.4)
                return (False, None)
            return (True, self._clear_probe())
        engine = guard.SupervisorEngine(
            target=self.target, controller=ctrl, clock=clock,
            poll_probe=on_poll, start_probe=MagicMock(),
            ttl_sec=2.0, probe_deadline_sec=50.0
        )
        sched = guard.SupervisorScheduler(engine, poll_interval=1.0, clock=clock, sleeper=sleeper)
        res = sched.run()
        self.assertEqual(res, guard.LifecycleState.STOPPED)
        self.assertEqual([round(s, 2) for s in sleeps], [0.7, 0.6])
        self.assertEqual(clock.monotonic(), 2.0)

    def test_caps_ttl_memory_and_probe_deadline(self):
        # 1. Cap TTL
        clock_t = guard.FakeClock(monotonic_start=0.0)
        ctrl_t = self._setup_controller(clock_t)
        engine_t = guard.SupervisorEngine(
            target=self.target, controller=ctrl_t, clock=clock_t,
            start_probe=MagicMock(), poll_probe=lambda: (False, None),
            ttl_sec=2.5, poll_interval=1.0
        )
        sleeps_t = []
        sched_t = guard.SupervisorScheduler(engine_t, poll_interval=1.0, clock=clock_t, sleeper=lambda d: (sleeps_t.append(d), clock_t.advance_monotonic(d)))
        res_t = sched_t.run()
        self.assertEqual(res_t, guard.LifecycleState.STOPPED)
        self.assertEqual([round(s, 2) for s in sleeps_t], [1.0, 1.0, 0.5])

        # 2. Cap Memory Cadence
        clock_m = guard.FakeClock(monotonic_start=0.0)
        ctrl_m = self._setup_controller(clock_m)
        mem_mock = MagicMock(return_value=(2000000, 1000000))
        engine_m = guard.SupervisorEngine(
            target=self.target, controller=ctrl_m, clock=clock_m,
            start_probe=MagicMock(), poll_probe=lambda: (False, None),
            cadence_sec=5.0, mem_reader=mem_mock, ttl_sec=5.0, poll_interval=3.0
        )
        sleeps_m = []
        sched_m = guard.SupervisorScheduler(engine_m, poll_interval=3.0, clock=clock_m, sleeper=lambda d: (sleeps_m.append(d), clock_m.advance_monotonic(d)))
        res_m = sched_m.run()
        self.assertEqual(res_m, guard.LifecycleState.STOPPED)
        self.assertEqual([round(s, 2) for s in sleeps_m], [3.0, 2.0])

        # 3. Cap Probe Deadline
        clock_p = guard.FakeClock(monotonic_start=0.0)
        ctrl_p = self._setup_controller(clock_p)
        engine_p = guard.SupervisorEngine(
            target=self.target, controller=ctrl_p, clock=clock_p,
            start_probe=MagicMock(), poll_probe=lambda: (False, None),
            probe_deadline_sec=4.0, poll_interval=3.0, ttl_sec=100.0
        )
        sleeps_p = []
        sched_p = guard.SupervisorScheduler(engine_p, poll_interval=3.0, clock=clock_p, sleeper=lambda d: (sleeps_p.append(d), clock_p.advance_monotonic(d)))
        sched_p.run()
        # Step 0 (t=0) sleeps 3.0 -> t=3.0. Step 1 (t=3.0) next grid is 6.0, probe deadline capped at 4.0!
        self.assertEqual([round(s, 2) for s in sleeps_p[:2]], [3.0, 1.0])

    def test_exact_probe_equality_pending_progresses_no_hang(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        ctrl = self._setup_controller(clock)
        engine = guard.SupervisorEngine(
            target=self.target, controller=ctrl, clock=clock,
            start_probe=MagicMock(), poll_probe=lambda: (False, None),
            probe_deadline_sec=4.0, poll_interval=1.0, ttl_sec=100.0
        )
        sleeps = []
        sched = guard.SupervisorScheduler(engine, poll_interval=1.0, clock=clock, sleeper=lambda d: (sleeps.append(d), clock.advance_monotonic(d)))
        state = sched.run()
        self.assertEqual(state, guard.LifecycleState.STOPPED)
        self.assertIn("overrun", engine.stop_reason.lower())
        # Sleeps at 0.0, 1.0, 2.0, 3.0 (each 1.0) waking at 4.0 (equality, pending), then sleeps 1.0 waking at 5.0 (overrun detected)
        self.assertEqual([round(s, 2) for s in sleeps], [1.0, 1.0, 1.0, 1.0, 1.0])
        self.assertEqual(clock.monotonic(), 5.0)

    def test_first_pending_no_ready_memory_at_5_ttl_anr_halt(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        ctrl = self._setup_controller(clock)
        engine = guard.SupervisorEngine(
            target=self.target, controller=ctrl, clock=clock,
            start_probe=MagicMock(), poll_probe=lambda: (False, None),
            mem_reader=MagicMock(return_value=(2000000, 1000000)),
            ttl_sec=5.0, cadence_sec=5.0
        )
        halted, _ = engine.step()
        self.assertFalse(halted)
        self.assertFalse(engine.is_ready)

        # Drive to TTL halt with memory sampled at 0s and 5s
        mem_mock = MagicMock(return_value=(2000000, 1000000))
        clock_mem = guard.FakeClock(monotonic_start=0.0)
        ctrl_mem = self._setup_controller(clock_mem)
        engine_mem = guard.SupervisorEngine(
            target=self.target, controller=ctrl_mem, clock=clock_mem,
            start_probe=MagicMock(), poll_probe=lambda: (False, None),
            mem_reader=mem_mock, cadence_sec=5.0, ttl_sec=6.0, poll_interval=2.0
        )
        sched_mem = guard.SupervisorScheduler(engine_mem, poll_interval=2.0, clock=clock_mem, sleeper=lambda d: clock_mem.advance_monotonic(d))
        res_mem = sched_mem.run()
        self.assertEqual(res_mem, guard.LifecycleState.STOPPED)
        self.assertEqual(mem_mock.call_count, 2)
        self.assertIn("ttl expired", engine_mem.stop_reason.lower())

        # ANR halt
        clock_anr = guard.FakeClock(monotonic_start=0.0)
        ctrl_anr = self._setup_controller(clock_anr)
        engine_anr = guard.SupervisorEngine(
            target=self.target, controller=ctrl_anr, clock=clock_anr,
            start_probe=MagicMock(), poll_probe=lambda: (True, self._anr_probe()),
            ttl_sec=100.0, poll_interval=1.0
        )
        sched_anr = guard.SupervisorScheduler(engine_anr, poll_interval=1.0, clock=clock_anr, sleeper=lambda d: clock_anr.advance_monotonic(d))
        res_anr = sched_anr.run()
        self.assertEqual(res_anr, guard.LifecycleState.STOPPED)
        self.assertIn("anr", engine_anr.stop_reason.lower())

    def test_clock_nan_bool_regression_and_read_exception(self):
        # 1. NaN clock
        class NanClock(guard.Clock):
            def __init__(self): self.c = 0
            def monotonic(self):
                self.c += 1
                return 0.0 if self.c == 1 else float("nan")
            def time(self): return 0.0
        ctrl1 = self._setup_controller()
        clk1 = NanClock()
        eng1 = guard.SupervisorEngine(target=self.target, controller=ctrl1, clock=clk1)
        sched1 = guard.SupervisorScheduler(eng1, clock=clk1, sleeper=lambda d: None)
        with self.assertRaises(RuntimeError):
            sched1.run()
        self.assertEqual(eng1.state, guard.LifecycleState.ERROR)
        self.assertEqual(eng1.termination_state, guard.LifecycleState.ERROR)
        self.assertEqual(sched1.state, guard.LifecycleState.ERROR)
        self.assertEqual(sched1.termination_state, guard.LifecycleState.ERROR)

        # 2. Bool clock
        class BoolClock(guard.Clock):
            def monotonic(self): return True
            def time(self): return 0.0
        ctrl2 = self._setup_controller()
        clk2 = BoolClock()
        eng2 = guard.SupervisorEngine(target=self.target, controller=ctrl2, clock=clk2)
        sched2 = guard.SupervisorScheduler(eng2, clock=clk2, sleeper=lambda d: None)
        with self.assertRaises(RuntimeError):
            sched2.run()
        self.assertEqual(eng2.state, guard.LifecycleState.ERROR)
        self.assertEqual(eng2.termination_state, guard.LifecycleState.ERROR)
        self.assertEqual(sched2.state, guard.LifecycleState.ERROR)
        self.assertEqual(sched2.termination_state, guard.LifecycleState.ERROR)

        # 3. Regression (backwards clock)
        class BackClock(guard.Clock):
            def __init__(self): self.seq = [10.0, 10.0, 9.0]
            def monotonic(self): return self.seq.pop(0) if self.seq else 8.0
            def time(self): return 0.0
        ctrl3 = self._setup_controller()
        clk3 = BackClock()
        eng3 = guard.SupervisorEngine(
            target=self.target, controller=ctrl3, clock=clk3,
            start_probe=MagicMock(), poll_probe=lambda: (False, None)
        )
        sched3 = guard.SupervisorScheduler(eng3, clock=clk3, sleeper=lambda d: None)
        with self.assertRaises(RuntimeError):
            sched3.run()
        self.assertEqual(eng3.state, guard.LifecycleState.ERROR)
        self.assertEqual(eng3.termination_state, guard.LifecycleState.ERROR)
        self.assertEqual(sched3.state, guard.LifecycleState.ERROR)
        self.assertEqual(sched3.termination_state, guard.LifecycleState.ERROR)

        # 4. Clock read exception
        class ErrClock(guard.Clock):
            def __init__(self): self.c = 0
            def monotonic(self):
                self.c += 1
                if self.c > 3:
                    raise RuntimeError("hardware clock read failed")
                return 0.0
            def time(self): return 0.0
        ctrl4 = self._setup_controller()
        clk4 = ErrClock()
        eng4 = guard.SupervisorEngine(target=self.target, controller=ctrl4, clock=clk4)
        sched4 = guard.SupervisorScheduler(eng4, clock=clk4, sleeper=lambda d: None)
        with self.assertRaises(RuntimeError):
            sched4.run()
        self.assertEqual(eng4.state, guard.LifecycleState.ERROR)
        self.assertEqual(eng4.termination_state, guard.LifecycleState.ERROR)
        self.assertEqual(sched4.state, guard.LifecycleState.ERROR)
        self.assertEqual(sched4.termination_state, guard.LifecycleState.ERROR)

    def test_sleeper_keyboard_interrupt_and_system_exit_preserved(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        ctrl = self._setup_controller(clock)
        engine = guard.SupervisorEngine(
            target=self.target, controller=ctrl, clock=clock, ttl_sec=100.0,
            start_probe=MagicMock(), poll_probe=lambda: (False, None)
        )
        ki = KeyboardInterrupt("simulated ctrl-c")
        sched = guard.SupervisorScheduler(engine, clock=clock, sleeper=MagicMock(side_effect=ki))
        with self.assertRaises(KeyboardInterrupt) as ctx:
            sched.run()
        self.assertIs(ctx.exception, ki)
        self.assertTrue(ctrl.pidfd_send_signal.called)

        ctrl2 = self._setup_controller(clock)
        engine2 = guard.SupervisorEngine(
            target=self.target, controller=ctrl2, clock=clock, ttl_sec=100.0,
            start_probe=MagicMock(), poll_probe=lambda: (False, None)
        )
        se = SystemExit(77)
        sched2 = guard.SupervisorScheduler(engine2, clock=clock, sleeper=MagicMock(side_effect=se))
        with self.assertRaises(SystemExit) as ctx2:
            sched2.run()
        self.assertIs(ctx2.exception, se)
        self.assertTrue(ctrl2.pidfd_send_signal.called)

    def test_no_progress_sleeper_fails_closed(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        ctrl = self._setup_controller(clock)
        engine = guard.SupervisorEngine(
            target=self.target, controller=ctrl, clock=clock, ttl_sec=100.0,
            start_probe=MagicMock(), poll_probe=lambda: (False, None)
        )
        sched = guard.SupervisorScheduler(engine, clock=clock, sleeper=lambda d: None)
        with self.assertRaises(RuntimeError):
            sched.run()
        self.assertEqual(engine.state, guard.LifecycleState.ERROR)
        self.assertEqual(sched.state, guard.LifecycleState.ERROR)
        self.assertEqual(engine.termination_state, guard.LifecycleState.ERROR)
        self.assertEqual(sched.termination_state, guard.LifecycleState.ERROR)

    def test_exact_six_exception_cleanup_combinations_and_durability(self):
        """Test exact 6 combos: RuntimeError/KI/SE x cleanup success/failure."""
        exceptions = [
            RuntimeError("primary runtime failure"),
            KeyboardInterrupt("primary keyboard interrupt"),
            SystemExit(99),
        ]
        cleanup_modes = [
            ("success", False),
            ("failure", True),
        ]

        for exc in exceptions:
            for c_mode, c_fail in cleanup_modes:
                with self.subTest(exc=type(exc).__name__, cleanup=c_mode):
                    clock = guard.FakeClock(monotonic_start=0.0)
                    ctrl = self._setup_controller(clock)
                    cleanup_count = [0]

                    engine = guard.SupervisorEngine(
                        target=self.target, controller=ctrl, clock=clock, ttl_sec=100.0,
                        start_probe=MagicMock(), poll_probe=lambda: (False, None),
                    )
                    orig_terminate = engine._terminate
                    if c_fail:
                        def term_fail(reason):
                            cleanup_count[0] += 1
                            raise RuntimeError("cleanup_terminate failure")
                        engine._terminate = term_fail
                    else:
                        def term_ok(reason):
                            cleanup_count[0] += 1
                            return orig_terminate(reason)
                        engine._terminate = term_ok

                    sched = guard.SupervisorScheduler(engine, clock=clock, sleeper=MagicMock(side_effect=exc))
                    with self.assertRaises(type(exc)) as ctx:
                        sched.run()

                    self.assertIs(ctx.exception, exc)
                    self.assertEqual(engine.state, guard.LifecycleState.ERROR)
                    self.assertEqual(sched.state, guard.LifecycleState.ERROR)
                    self.assertEqual(engine.termination_state, guard.LifecycleState.ERROR)
                    self.assertEqual(sched.termination_state, guard.LifecycleState.ERROR)
                    self.assertEqual(cleanup_count[0], 1)
                    self.assertIs(sched.primary_error, exc)
                    self.assertIs(engine.primary_error, exc)
                    if c_fail:
                        self.assertIsInstance(sched.secondary_error, RuntimeError)
                        self.assertIsInstance(engine.secondary_error, RuntimeError)
                    else:
                        self.assertIsNone(sched.secondary_error)
                        self.assertIsNone(engine.secondary_error)

    def test_cleanup_secondary_interrupt_preserves_primary_and_durable_errors(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        ctrl = self._setup_controller(clock)
        cleanup_count = [0]
        sec_ki = KeyboardInterrupt("secondary cleanup interrupt")

        engine = guard.SupervisorEngine(
            target=self.target, controller=ctrl, clock=clock, ttl_sec=100.0,
            start_probe=MagicMock(), poll_probe=lambda: (False, None),
        )
        def term_ki(reason):
            cleanup_count[0] += 1
            raise sec_ki
        engine._terminate = term_ki

        prim_re = RuntimeError("primary ordinary failure")
        sched = guard.SupervisorScheduler(engine, clock=clock, sleeper=MagicMock(side_effect=prim_re))
        with self.assertRaises(RuntimeError) as ctx:
            sched.run()
        self.assertIs(ctx.exception, prim_re)
        self.assertEqual(engine.state, guard.LifecycleState.ERROR)
        self.assertEqual(sched.state, guard.LifecycleState.ERROR)
        self.assertEqual(engine.termination_state, guard.LifecycleState.ERROR)
        self.assertEqual(sched.termination_state, guard.LifecycleState.ERROR)
        self.assertIs(sched.primary_error, prim_re)
        self.assertIs(sched.secondary_error, sec_ki)
        self.assertEqual(cleanup_count[0], 1)

    def test_no_halt_extra_sleep(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        ctrl = self._setup_controller(clock)
        engine = guard.SupervisorEngine(target=self.target, controller=ctrl, clock=clock, ttl_sec=0.0)
        sleep_mock = MagicMock()
        sched = guard.SupervisorScheduler(engine, clock=clock, sleeper=sleep_mock)
        res = sched.run()
        self.assertEqual(res, guard.LifecycleState.STOPPED)
        self.assertEqual(sleep_mock.call_count, 0)


class TestSupervisorEngineClaimI3b2a(unittest.TestCase):
    """Hermetic unit tests for I3b.2a engine-owned attempt bookkeeping and typed claim."""

    def setUp(self):
        _enable_mock_sleep_progression(self)
        self.target = guard.ProcessIdentity(
            pid=12345,
            start_ticks=987654,
            uid=1000,
            avd="Trindade_UX_v030",
            port=5556,
            binary_name="qemu-system-x86_64"
        )
        self.proc_access_count = 0
        self._orig_open = open

        def _guarded_open(file, *args, **kwargs):
            if isinstance(file, str) and file.startswith("/proc"):
                self.proc_access_count += 1
                raise AssertionError(f"Unexpected real /proc open: {file}")
            return self._orig_open(file, *args, **kwargs)

        self._open_patcher = unittest.mock.patch("builtins.open", side_effect=_guarded_open)
        self._open_patcher.start()
        self.addCleanup(self._open_patcher.stop)

    def tearDown(self):
        self.assertEqual(self.proc_access_count, 0)

    def _setup_controller(self):
        return _make_pidfd_mock()

    def test_claim_not_attempted(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        ctrl = self._setup_controller()
        engine = guard.SupervisorEngine(target=self.target, controller=ctrl, clock=clock)
        self.assertIsNone(engine.attempt_record)
        res = engine.claim(self.target, ctrl)
        self.assertEqual(res, guard.TerminationClaimStatus.NOT_ATTEMPTED)
        self.assertIsNone(engine.attempt_record)

    def test_claim_exact_binding_success(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        ctrl = self._setup_controller()
        engine = guard.SupervisorEngine(target=self.target, controller=ctrl, clock=clock)
        engine._terminate("normal stop")
        self.assertIsNotNone(engine.attempt_record)
        self.assertIsNotNone(engine.attempt_record.outcome)
        self.assertTrue(engine.attempt_record.outcome.completed)
        self.assertEqual(engine.attempt_record.outcome.state, guard.LifecycleState.STOPPED)
        res = engine.claim(self.target, ctrl)
        self.assertEqual(res, guard.TerminationClaimStatus.CLAIMED)

    def test_sealed_immutable(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        ctrl = self._setup_controller()
        engine = guard.SupervisorEngine(target=self.target, controller=ctrl, clock=clock)
        engine._terminate("normal stop")
        rec = engine.attempt_record
        snap = engine.export_claim_snapshot()
        self.assertIs(rec, snap)
        with self.assertRaises((dataclasses.FrozenInstanceError, AttributeError)):
            rec.pid = 99999
        with self.assertRaises((dataclasses.FrozenInstanceError, AttributeError)):
            rec.outcome = None
        with self.assertRaises((dataclasses.FrozenInstanceError, AttributeError)):
            rec.outcome.state = guard.LifecycleState.ERROR

    def test_repeated_claim(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        ctrl = self._setup_controller()
        engine = guard.SupervisorEngine(target=self.target, controller=ctrl, clock=clock)
        engine._terminate("normal stop")
        res1 = engine.claim(self.target, ctrl)
        self.assertEqual(res1, guard.TerminationClaimStatus.CLAIMED)
        res2 = engine.claim(self.target, ctrl)
        self.assertEqual(res2, guard.TerminationClaimStatus.ALREADY_CLAIMED)
        res3 = engine.claim(self.target, ctrl)
        self.assertEqual(res3, guard.TerminationClaimStatus.ALREADY_CLAIMED)

    def test_mismatch_pid_ticks_uid_avd_controller_preserves_record(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        ctrl = self._setup_controller()
        ctrl_other = self._setup_controller()
        engine = guard.SupervisorEngine(target=self.target, controller=ctrl, clock=clock)

        # Before terminate: mismatch returns MISMATCH (not NOT_ATTEMPTED)
        bad_pid = dataclasses.replace(self.target, pid=99999)
        self.assertEqual(engine.claim(bad_pid, ctrl), guard.TerminationClaimStatus.MISMATCH)
        self.assertEqual(engine.claim(self.target, ctrl_other), guard.TerminationClaimStatus.MISMATCH)
        self.assertEqual(engine.claim(self.target, ctrl), guard.TerminationClaimStatus.NOT_ATTEMPTED)

        # After terminate: mismatches return MISMATCH and preserve valid record
        engine._terminate("normal stop")
        bad_ticks = dataclasses.replace(self.target, start_ticks=11111)
        bad_uid = dataclasses.replace(self.target, uid=0)
        bad_avd = dataclasses.replace(self.target, avd="Other_AVD")

        self.assertEqual(engine.claim(bad_pid, ctrl), guard.TerminationClaimStatus.MISMATCH)
        self.assertEqual(engine.claim(bad_ticks, ctrl), guard.TerminationClaimStatus.MISMATCH)
        self.assertEqual(engine.claim(bad_uid, ctrl), guard.TerminationClaimStatus.MISMATCH)
        self.assertEqual(engine.claim(bad_avd, ctrl), guard.TerminationClaimStatus.MISMATCH)
        self.assertEqual(engine.claim(self.target, ctrl_other), guard.TerminationClaimStatus.MISMATCH)

        # Preserved record: valid claim consumes once and transitions to CLAIMED
        self.assertEqual(engine.claim(self.target, ctrl), guard.TerminationClaimStatus.CLAIMED)

    def test_claim_numeric_bool_float_preserves_record(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        ctrl = self._setup_controller()
        engine = guard.SupervisorEngine(target=self.target, controller=ctrl, clock=clock)
        engine._terminate("normal stop")

        for bad_t in [
            dataclasses.replace(self.target, pid=True),
            dataclasses.replace(self.target, pid=12345.0),
            dataclasses.replace(self.target, start_ticks=True),
            dataclasses.replace(self.target, start_ticks=987654.0),
            dataclasses.replace(self.target, uid=True),
            dataclasses.replace(self.target, uid=1000.0),
        ]:
            self.assertEqual(engine.claim(bad_t, ctrl), guard.TerminationClaimStatus.MISMATCH)

        # Record preserved
        self.assertEqual(engine.claim(self.target, ctrl), guard.TerminationClaimStatus.CLAIMED)

    def test_before_cleanup_throw_record_exists(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        ctrl = self._setup_controller()
        engine = guard.SupervisorEngine(target=self.target, controller=ctrl, clock=clock)
        cleanup_err = RuntimeError("teardown exploded")
        engine._cleanup_probe_teardown = MagicMock(side_effect=cleanup_err)

        with self.assertRaises(RuntimeError) as ctx:
            engine._terminate("stop")
        self.assertIs(ctx.exception, cleanup_err)

        self.assertIsNotNone(engine.attempt_record)
        self.assertEqual(engine.attempt_record.pid, 12345)
        self.assertEqual(engine.attempt_record.start_ticks, 987654)
        self.assertEqual(engine.attempt_record.uid, 1000)
        self.assertEqual(engine.attempt_record.avd, "Trindade_UX_v030")
        self.assertIs(engine.attempt_record.controller, ctrl)
        self.assertIsNotNone(engine.attempt_record.outcome)
        self.assertEqual(engine.attempt_record.outcome.state, guard.LifecycleState.ERROR)
        self.assertFalse(engine.attempt_record.outcome.completed)
        self.assertIs(engine.attempt_record.outcome.primary_error, cleanup_err)
        self.assertIs(engine.attempt_record.outcome.primary_exception, cleanup_err)
        # Best effort once
        ctrl.pidfd_send_signal.assert_called_once()
        ctrl.cleanup_bound_pidfd.assert_called_once()

        # Repeat call does no second attempt
        ret_ok, ret_state = engine._terminate("repeat stop")
        self.assertTrue(ret_ok)
        self.assertEqual(ret_state, guard.LifecycleState.ERROR)
        ctrl.pidfd_send_signal.assert_called_once()
        ctrl.cleanup_bound_pidfd.assert_called_once()

    def test_reentrant_claim_success_mismatch_and_postseal_consumption(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        ctrl = self._setup_controller()
        mid_res_bad = None
        mid_res_ok = None
        engine = None

        def probe_cleanup():
            nonlocal mid_res_bad, mid_res_ok
            bad_t = dataclasses.replace(self.target, pid=99999)
            mid_res_bad = engine.claim(bad_t, ctrl)
            mid_res_ok = engine.claim(self.target, ctrl)
            return True

        engine = guard.SupervisorEngine(
            target=self.target, controller=ctrl, clock=clock, cleanup_probe=probe_cleanup
        )
        engine._terminate("normal stop")

        self.assertEqual(mid_res_bad, guard.TerminationClaimStatus.MISMATCH)
        self.assertEqual(mid_res_ok, guard.TerminationClaimStatus.IN_PROGRESS)
        self.assertEqual(engine.claim(self.target, ctrl), guard.TerminationClaimStatus.CLAIMED)
        self.assertEqual(engine.claim(self.target, ctrl), guard.TerminationClaimStatus.ALREADY_CLAIMED)

    def test_reentrant_claim_aborted_attempts_runtimeerror_ki_se(self):
        for exc in [
            RuntimeError("probe failure"),
            KeyboardInterrupt("probe interrupt"),
            SystemExit(2)
        ]:
            clock = guard.FakeClock(monotonic_start=0.0)
            ctrl = self._setup_controller()
            mid_mismatch = None
            mid_progress = None
            engine = None

            def hooked_teardown():
                nonlocal mid_mismatch, mid_progress
                bad_ctrl = self._setup_controller()
                mid_mismatch = engine.claim(self.target, bad_ctrl)
                mid_progress = engine.claim(self.target, ctrl)
                raise exc

            engine = guard.SupervisorEngine(
                target=self.target, controller=ctrl, clock=clock
            )
            engine._cleanup_probe_teardown = hooked_teardown
            with self.assertRaises(type(exc)) as ctx:
                engine._terminate("aborted stop")
            self.assertIs(ctx.exception, exc)

            self.assertEqual(mid_mismatch, guard.TerminationClaimStatus.MISMATCH)
            self.assertEqual(mid_progress, guard.TerminationClaimStatus.IN_PROGRESS)
            self.assertIsNotNone(engine.attempt_record)
            self.assertIsNotNone(engine.attempt_record.outcome)
            self.assertEqual(engine.attempt_record.outcome.state, guard.LifecycleState.ERROR)
            self.assertFalse(engine.attempt_record.outcome.completed)
            self.assertIs(engine.attempt_record.outcome.primary_error, exc)

            self.assertEqual(engine.claim(self.target, ctrl), guard.TerminationClaimStatus.CLAIMED)
            self.assertEqual(engine.claim(self.target, ctrl), guard.TerminationClaimStatus.ALREADY_CLAIMED)

    def test_matrix_first_teardown_precedence_over_secondary_errors(self):
        first_exceptions = [
            RuntimeError("teardown runtime err"),
            KeyboardInterrupt("teardown ki"),
            SystemExit(5),
        ]
        secondary_scenarios = [
            ("sigterm_runtime", RuntimeError("sigterm err"), None),
            ("sigterm_ki", KeyboardInterrupt("sigterm ki"), None),
            ("ctrl_runtime", None, RuntimeError("ctrl err")),
            ("ctrl_ki", None, KeyboardInterrupt("ctrl ki")),
            ("both_errors", RuntimeError("sigterm err"), RuntimeError("ctrl err")),
        ]
        for first_exc in first_exceptions:
            for sc_name, sig_exc, ctrl_exc in secondary_scenarios:
                with self.subTest(first=type(first_exc).__name__, scenario=sc_name):
                    clock = guard.FakeClock(monotonic_start=0.0)
                    ctrl = self._setup_controller()
                    if ctrl_exc is not None:
                        ctrl.cleanup_bound_pidfd = MagicMock(side_effect=ctrl_exc)

                    engine = guard.SupervisorEngine(target=self.target, controller=ctrl, clock=clock)
                    engine._cleanup_probe_teardown = MagicMock(side_effect=first_exc)

                    with unittest.mock.patch.object(
                        guard,
                        "safe_sigterm",
                        side_effect=sig_exc if sig_exc is not None else lambda *a, **kw: (guard.LifecycleState.STOPPED, True)
                    ) as mock_sig:
                        with self.assertRaises(type(first_exc)) as ctx:
                            engine._terminate("matrix stop")
                        self.assertIs(ctx.exception, first_exc)
                        mock_sig.assert_called_once()
                        ctrl.cleanup_bound_pidfd.assert_called_once()

                    self.assertIsNotNone(engine.attempt_record)
                    self.assertIsNotNone(engine.attempt_record.outcome)
                    self.assertEqual(engine.attempt_record.outcome.state, guard.LifecycleState.ERROR)
                    self.assertFalse(engine.attempt_record.outcome.completed)
                    self.assertIs(engine.attempt_record.outcome.primary_error, first_exc)
                    self.assertIs(engine.attempt_record.outcome.primary_exception, first_exc)

    def test_legacy_callback_runtime_and_os_error_remains_diagnostic_error_completed_true(self):
        for legacy_err in [RuntimeError("callback failed"), OSError("io error")]:
            with self.subTest(err=type(legacy_err).__name__):
                clock = guard.FakeClock(monotonic_start=0.0)
                ctrl = self._setup_controller()
                def probe_cleanup():
                    raise legacy_err

                engine = guard.SupervisorEngine(
                    target=self.target, controller=ctrl, clock=clock, cleanup_probe=probe_cleanup
                )
                halted, state = engine._terminate("legacy stop")
                self.assertTrue(halted)
                self.assertEqual(state, guard.LifecycleState.ERROR)
                self.assertTrue(engine.has_probe_cleanup_failure)
                self.assertIsNotNone(engine.attempt_record)
                self.assertIsNotNone(engine.attempt_record.outcome)
                self.assertEqual(engine.attempt_record.outcome.state, guard.LifecycleState.ERROR)
                self.assertTrue(engine.attempt_record.outcome.completed)
                self.assertIsNone(engine.attempt_record.outcome.primary_error)
                ctrl.pidfd_send_signal.assert_called_once()
                ctrl.cleanup_bound_pidfd.assert_called_once()

    def test_safe_sigterm_throw_abort_retained_and_original_object_propagated(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        ctrl = self._setup_controller()
        engine = guard.SupervisorEngine(target=self.target, controller=ctrl, clock=clock)
        sigterm_err = RuntimeError("safe_sigterm failure")

        with unittest.mock.patch.object(guard, "safe_sigterm", side_effect=sigterm_err):
            with self.assertRaises(RuntimeError) as ctx:
                engine._terminate("stop with err")
            self.assertIs(ctx.exception, sigterm_err)

        self.assertIsNotNone(engine.attempt_record)
        self.assertIsNotNone(engine.attempt_record.outcome)
        self.assertFalse(engine.attempt_record.outcome.completed)
        self.assertEqual(engine.attempt_record.outcome.state, guard.LifecycleState.ERROR)
        self.assertIs(engine.attempt_record.outcome.primary_error, sigterm_err)
        self.assertIs(engine.attempt_record.outcome.primary_exception, sigterm_err)

    def test_repeat_terminate_no_second_attempt_and_no_reset_consumption(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        ctrl = self._setup_controller()
        engine = guard.SupervisorEngine(target=self.target, controller=ctrl, clock=clock)
        engine._terminate("first stop")
        first_record = engine.attempt_record
        self.assertEqual(ctrl.pidfd_send_signal.call_count, 1)

        res1 = engine.claim(self.target, ctrl)
        self.assertEqual(res1, guard.TerminationClaimStatus.CLAIMED)

        # Second _terminate call
        engine._terminate("second stop")
        self.assertIs(engine.attempt_record, first_record)
        self.assertEqual(ctrl.pidfd_send_signal.call_count, 1)

        # Claim consumption preserved (not reset)
        res2 = engine.claim(self.target, ctrl)
        self.assertEqual(res2, guard.TerminationClaimStatus.ALREADY_CLAIMED)


class TestSupervisorWrapperI3b2b(unittest.TestCase):
    """Hermetic unit tests for I3b.2b run_guarded_supervisor engine integration and interrupt handling."""

    def setUp(self):
        _enable_mock_sleep_progression(self)
        self.target = guard.ProcessIdentity(
            pid=12345,
            start_ticks=987654,
            uid=1000,
            avd="Trindade_UX_v030",
            port=5556,
            binary_name="qemu-system-x86_64"
        )
        self.proc_access_count = 0
        self._orig_open = open

        def _guarded_open(file, *args, **kwargs):
            if isinstance(file, str) and file.startswith("/proc"):
                self.proc_access_count += 1
                raise AssertionError(f"Unexpected real /proc open: {file}")
            return self._orig_open(file, *args, **kwargs)

        self._open_patcher = unittest.mock.patch("builtins.open", side_effect=_guarded_open)
        self._open_patcher.start()
        self.addCleanup(self._open_patcher.stop)

    def tearDown(self):
        self.assertEqual(self.proc_access_count, 0)

    def _setup_controller(self):
        return _make_pidfd_mock()

    def test_engine_none_unchanged_legacy(self):
        ctrl = self._setup_controller()
        state_sink = {}
        st = guard.run_guarded_supervisor(
            runner=lambda: None,
            target=self.target,
            controller=ctrl,
            state_sink=state_sink,
            engine=None
        )
        self.assertEqual(st, guard.LifecycleState.STOPPED)
        self.assertEqual(state_sink.get("state"), guard.LifecycleState.STOPPED)
        ctrl.pidfd_send_signal.assert_called_once_with(99, signal.SIGTERM)

    def test_idle_exact_not_attempted_permits_legacy_safe_sigterm(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        ctrl = self._setup_controller()
        engine = guard.SupervisorEngine(target=self.target, controller=ctrl, clock=clock)
        state_sink = {}
        st = guard.run_guarded_supervisor(
            runner=lambda: None,
            target=self.target,
            controller=ctrl,
            state_sink=state_sink,
            engine=engine
        )
        self.assertEqual(st, guard.LifecycleState.STOPPED)
        self.assertEqual(state_sink.get("state"), guard.LifecycleState.STOPPED)
        ctrl.pidfd_send_signal.assert_called_once_with(99, signal.SIGTERM)

    def test_engine_run_termination_stopped_single_safe_sigterm_no_reopening(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        ctrl = self._setup_controller()
        engine = guard.SupervisorEngine(target=self.target, controller=ctrl, clock=clock)
        state_sink = {}
        def runner():
            halted, st = engine._terminate("normal halt")
            self.assertTrue(halted)
            self.assertEqual(st, guard.LifecycleState.STOPPED)
        st = guard.run_guarded_supervisor(
            runner=runner,
            target=self.target,
            controller=ctrl,
            state_sink=state_sink,
            engine=engine
        )
        self.assertEqual(st, guard.LifecycleState.STOPPED)
        self.assertEqual(state_sink.get("state"), guard.LifecycleState.STOPPED)
        self.assertEqual(ctrl.pidfd_send_signal.call_count, 1)

    def test_completed_failed_or_aborted_no_retry(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        # Subtest 1: aborted termination (completed=False, primary_error)
        ctrl1 = self._setup_controller()
        engine1 = guard.SupervisorEngine(target=self.target, controller=ctrl1, clock=clock)
        engine1._cleanup_probe_teardown = MagicMock(side_effect=RuntimeError("teardown exploded"))
        state_sink1 = {}
        def runner1():
            try:
                engine1._terminate("abort test")
            except RuntimeError:
                pass
        st1 = guard.run_guarded_supervisor(
            runner=runner1,
            target=self.target,
            controller=ctrl1,
            state_sink=state_sink1,
            engine=engine1
        )
        self.assertEqual(st1, guard.LifecycleState.ERROR)
        self.assertEqual(state_sink1.get("state"), guard.LifecycleState.ERROR)
        self.assertEqual(ctrl1.pidfd_send_signal.call_count, 1)

        # Subtest 2: outcome state is ERROR with completed=True
        ctrl2 = self._setup_controller()
        def failing_probe():
            raise OSError("disk error")
        engine2 = guard.SupervisorEngine(target=self.target, controller=ctrl2, clock=clock, cleanup_probe=failing_probe)
        state_sink2 = {}
        def runner2():
            engine2._terminate("failed stop")
        st2 = guard.run_guarded_supervisor(
            runner=runner2,
            target=self.target,
            controller=ctrl2,
            state_sink=state_sink2,
            engine=engine2
        )
        self.assertEqual(st2, guard.LifecycleState.ERROR)
        self.assertEqual(state_sink2.get("state"), guard.LifecycleState.ERROR)
        self.assertEqual(ctrl2.pidfd_send_signal.call_count, 1)

    def test_midclaim_repeated_mismatch_duckengine_no_signal(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        # 1. Midclaim / in progress (outcome is None)
        ctrl_mid = self._setup_controller()
        engine_mid = guard.SupervisorEngine(target=self.target, controller=ctrl_mid, clock=clock)
        engine_mid._attempt_record = guard.TerminationAttemptRecord(
            self.target.pid, self.target.start_ticks, self.target.uid, self.target.avd, ctrl_mid, outcome=None
        )
        sink_mid = {}
        st_mid = guard.run_guarded_supervisor(
            runner=lambda: None, target=self.target, controller=ctrl_mid, state_sink=sink_mid, engine=engine_mid
        )
        self.assertEqual(st_mid, guard.LifecycleState.ERROR)
        ctrl_mid.pidfd_send_signal.assert_not_called()

        # 2. Repeated claim (already claimed)
        ctrl_rep = self._setup_controller()
        engine_rep = guard.SupervisorEngine(target=self.target, controller=ctrl_rep, clock=clock)
        engine_rep._terminate("stop")
        res = engine_rep.claim(self.target, ctrl_rep)
        self.assertEqual(res, guard.TerminationClaimStatus.CLAIMED)
        ctrl_rep.pidfd_send_signal.reset_mock()
        sink_rep = {}
        st_rep = guard.run_guarded_supervisor(
            runner=lambda: None, target=self.target, controller=ctrl_rep, state_sink=sink_rep, engine=engine_rep
        )
        self.assertEqual(st_rep, guard.LifecycleState.ERROR)
        ctrl_rep.pidfd_send_signal.assert_not_called()

        # 3. Mismatch on all 5 fields
        ctrl_base = self._setup_controller()
        engine_base = guard.SupervisorEngine(target=self.target, controller=ctrl_base, clock=clock)
        ctrl_other = self._setup_controller()
        mismatches = [
            (dataclasses.replace(self.target, pid=99999), ctrl_base),
            (dataclasses.replace(self.target, start_ticks=11111), ctrl_base),
            (dataclasses.replace(self.target, uid=0), ctrl_base),
            (dataclasses.replace(self.target, avd="Other_AVD"), ctrl_base),
            (self.target, ctrl_other),
        ]
        for tgt, c in mismatches:
            c.pidfd_send_signal.reset_mock()
            sink_m = {}
            st_m = guard.run_guarded_supervisor(
                runner=lambda: None, target=tgt, controller=c, state_sink=sink_m, engine=engine_base
            )
            self.assertEqual(st_m, guard.LifecycleState.ERROR)
            c.pidfd_send_signal.assert_not_called()

        # 4. Numeric types (bool, float)
        numeric_targets = [
            dataclasses.replace(self.target, pid=True),
            dataclasses.replace(self.target, pid=12345.0),
            dataclasses.replace(self.target, start_ticks=True),
            dataclasses.replace(self.target, start_ticks=987654.0),
            dataclasses.replace(self.target, uid=True),
            dataclasses.replace(self.target, uid=1000.0),
        ]
        for bad_t in numeric_targets:
            ctrl_base.pidfd_send_signal.reset_mock()
            sink_n = {}
            st_n = guard.run_guarded_supervisor(
                runner=lambda: None, target=bad_t, controller=ctrl_base, state_sink=sink_n, engine=engine_base
            )
            self.assertEqual(st_n, guard.LifecycleState.ERROR)
            ctrl_base.pidfd_send_signal.assert_not_called()

        # 5. Duck engine
        class DuckEngine:
            def claim(self, t, c):
                return guard.TerminationClaimStatus.CLAIMED
        duck = DuckEngine()
        sink_duck = {}
        ctrl_duck = self._setup_controller()
        st_duck = guard.run_guarded_supervisor(
            runner=lambda: None, target=self.target, controller=ctrl_duck, state_sink=sink_duck, engine=duck
        )
        self.assertEqual(st_duck, guard.LifecycleState.ERROR)
        ctrl_duck.pidfd_send_signal.assert_not_called()

        # 6. Claim dispatch exception
        ctrl_throw = self._setup_controller()
        engine_throw = guard.SupervisorEngine(target=self.target, controller=ctrl_throw, clock=clock)
        with unittest.mock.patch.object(guard.SupervisorEngine, "claim", side_effect=RuntimeError("claim dispatch failed")):
            sink_throw = {}
            st_throw = guard.run_guarded_supervisor(
                runner=lambda: None, target=self.target, controller=ctrl_throw, state_sink=sink_throw, engine=engine_throw
            )
            self.assertEqual(st_throw, guard.LifecycleState.ERROR)
            ctrl_throw.pidfd_send_signal.assert_not_called()
            ctrl_throw.cleanup_bound_pidfd.assert_called_once()

    def test_cleanup_failure_records_error_despite_prior_claimed_stopped(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        # Probe cleanup failure
        ctrl1 = self._setup_controller()
        engine1 = guard.SupervisorEngine(target=self.target, controller=ctrl1, clock=clock)
        engine1._terminate("stop")
        sink1 = {}
        st1 = guard.run_guarded_supervisor(
            runner=lambda: None, target=self.target, controller=ctrl1, state_sink=sink1,
            engine=engine1, cleanup_probe=lambda: False
        )
        self.assertEqual(st1, guard.LifecycleState.ERROR)
        self.assertEqual(sink1.get("state"), guard.LifecycleState.ERROR)
        self.assertTrue(sink1.get("probe_cleanup_failed"))

        # Controller cleanup failure
        ctrl2 = self._setup_controller()
        ctrl2.cleanup_bound_pidfd = MagicMock(return_value=False)
        engine2 = guard.SupervisorEngine(target=self.target, controller=ctrl2, clock=clock)
        engine2._terminate("stop")
        sink2 = {}
        st2 = guard.run_guarded_supervisor(
            runner=lambda: None, target=self.target, controller=ctrl2, state_sink=sink2,
            engine=engine2
        )
        self.assertEqual(st2, guard.LifecycleState.ERROR)
        self.assertEqual(sink2.get("state"), guard.LifecycleState.ERROR)
        self.assertTrue(sink2.get("controller_cleanup_failed"))

    def test_stale_primary_state_stopped_cannot_dedup(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        ctrl = self._setup_controller()
        engine = guard.SupervisorEngine(target=self.target, controller=ctrl, clock=clock)
        sink = {"primary_state": guard.LifecycleState.STOPPED, "state": guard.LifecycleState.STOPPED}
        st = guard.run_guarded_supervisor(
            runner=lambda: None,
            target=self.target,
            controller=ctrl,
            state_sink=sink,
            engine=engine
        )
        self.assertEqual(st, guard.LifecycleState.STOPPED)
        self.assertEqual(sink.get("state"), guard.LifecycleState.STOPPED)
        ctrl.pidfd_send_signal.assert_called_once_with(99, signal.SIGTERM)

    def test_runner_runtime_error_returns_error_without_reraise(self):
        ctrl = self._setup_controller()
        sink = {}
        body_err = RuntimeError("runner failed")
        def crashing_runner():
            raise body_err
        st = guard.run_guarded_supervisor(
            runner=crashing_runner,
            target=self.target,
            controller=ctrl,
            state_sink=sink
        )
        self.assertEqual(st, guard.LifecycleState.ERROR)
        self.assertEqual(sink.get("state"), guard.LifecycleState.ERROR)
        self.assertIs(sink.get("body_exception"), body_err)

    def test_runner_ki_se_matrix_secondary_failures_and_sink_durable_error(self):
        clock = guard.FakeClock(monotonic_start=0.0)
        interrupts = [KeyboardInterrupt("body ki"), SystemExit(3)]
        for exc in interrupts:
            # 1. Clean cleanups -> exact original exc re-raised, sink durable ERROR
            with self.subTest(exc=type(exc).__name__, case="clean"):
                ctrl = self._setup_controller()
                engine = guard.SupervisorEngine(target=self.target, controller=ctrl, clock=clock)
                sink = {}
                def run_interrupt():
                    raise exc
                with self.assertRaises(type(exc)) as ctx:
                    guard.run_guarded_supervisor(
                        runner=run_interrupt, target=self.target, controller=ctrl,
                        state_sink=sink, engine=engine
                    )
                self.assertIs(ctx.exception, exc)
                self.assertEqual(sink.get("state"), guard.LifecycleState.ERROR)
                self.assertIs(sink.get("body_exception"), exc)

            # 2. Probe cleanup fails with OSError -> exact original exc re-raised
            with self.subTest(exc=type(exc).__name__, case="probe_oserror"):
                ctrl = self._setup_controller()
                sink = {}
                def probe_fail():
                    raise OSError("probe error")
                with self.assertRaises(type(exc)) as ctx:
                    guard.run_guarded_supervisor(
                        runner=run_interrupt, target=self.target, controller=ctrl,
                        state_sink=sink, cleanup_probe=probe_fail
                    )
                self.assertIs(ctx.exception, exc)
                self.assertEqual(sink.get("state"), guard.LifecycleState.ERROR)
                self.assertTrue(sink.get("probe_cleanup_failed"))

            # 3. Controller cleanup fails with RuntimeError -> exact original exc re-raised
            with self.subTest(exc=type(exc).__name__, case="ctrl_runtime"):
                ctrl = self._setup_controller()
                ctrl.cleanup_bound_pidfd = MagicMock(side_effect=RuntimeError("ctrl cleanup err"))
                sink = {}
                with self.assertRaises(type(exc)) as ctx:
                    guard.run_guarded_supervisor(
                        runner=run_interrupt, target=self.target, controller=ctrl,
                        state_sink=sink
                    )
                self.assertIs(ctx.exception, exc)
                self.assertEqual(sink.get("state"), guard.LifecycleState.ERROR)
                self.assertTrue(sink.get("controller_cleanup_failed"))

            # 4. Probe cleanup raises KeyboardInterrupt -> runner body exc wins
            with self.subTest(exc=type(exc).__name__, case="probe_ki"):
                ctrl = self._setup_controller()
                sink = {}
                def probe_ki():
                    raise KeyboardInterrupt("probe ki")
                with self.assertRaises(type(exc)) as ctx:
                    guard.run_guarded_supervisor(
                        runner=run_interrupt, target=self.target, controller=ctrl,
                        state_sink=sink, cleanup_probe=probe_ki
                    )
                self.assertIs(ctx.exception, exc)
                self.assertEqual(sink.get("state"), guard.LifecycleState.ERROR)
                self.assertTrue(sink.get("probe_cleanup_failed"))

            # 5. Controller cleanup raises KeyboardInterrupt -> runner body exc wins
            with self.subTest(exc=type(exc).__name__, case="ctrl_ki"):
                ctrl = self._setup_controller()
                ctrl.cleanup_bound_pidfd = MagicMock(side_effect=KeyboardInterrupt("ctrl ki"))
                sink = {}
                with self.assertRaises(type(exc)) as ctx:
                    guard.run_guarded_supervisor(
                        runner=run_interrupt, target=self.target, controller=ctrl,
                        state_sink=sink
                    )
                self.assertIs(ctx.exception, exc)
                self.assertEqual(sink.get("state"), guard.LifecycleState.ERROR)
                self.assertTrue(sink.get("controller_cleanup_failed"))

    def _assert_termination_interrupt_case(self, mode: str, term_exc: BaseException, second: str):
        clock = guard.FakeClock(monotonic_start=0.0)
        ctrl = self._setup_controller()
        state_sink = {}
        probe_fn = None
        probe_exc = None
        ctrl_exc = None

        if second == "probe":
            probe_exc = SystemExit(101) if isinstance(term_exc, KeyboardInterrupt) else KeyboardInterrupt("probe_ki")
            def failing_probe():
                raise probe_exc
            probe_fn = failing_probe
        elif second == "controller":
            ctrl_exc = SystemExit(202) if isinstance(term_exc, KeyboardInterrupt) else KeyboardInterrupt("ctrl_ki")
            ctrl.cleanup_bound_pidfd = MagicMock(side_effect=ctrl_exc)

        engine = None
        if mode == "claim":
            engine = guard.SupervisorEngine(target=self.target, controller=ctrl, clock=clock)

        if second == "probe":
            expected_exc = probe_exc
        else:
            expected_exc = term_exc

        patch_target = guard.SupervisorEngine if mode == "claim" else guard
        patch_method = "claim" if mode == "claim" else "safe_sigterm"

        with unittest.mock.patch.object(patch_target, patch_method, side_effect=term_exc):
            with self.assertRaises(BaseException) as ctx:
                guard.run_guarded_supervisor(
                    runner=lambda: None,
                    target=self.target,
                    controller=ctrl,
                    state_sink=state_sink,
                    cleanup_probe=probe_fn,
                    engine=engine
                )

        self.assertIs(ctx.exception, expected_exc)
        self.assertEqual(state_sink.get("state"), guard.LifecycleState.ERROR)
        ctrl.cleanup_bound_pidfd.assert_called_once()
        if mode == "claim":
            ctrl.pidfd_send_signal.assert_not_called()

    # --- Confirmed I3b.2b 12 Matrix Reproduction Tests ---

    def test_repro_matrix_01_sigterm_ki_second_none(self):
        self._assert_termination_interrupt_case("sigterm", KeyboardInterrupt("sigterm_ki"), "none")

    def test_repro_matrix_02_sigterm_ki_second_probe(self):
        self._assert_termination_interrupt_case("sigterm", KeyboardInterrupt("sigterm_ki"), "probe")

    def test_repro_matrix_03_sigterm_ki_second_controller(self):
        self._assert_termination_interrupt_case("sigterm", KeyboardInterrupt("sigterm_ki"), "controller")

    def test_repro_matrix_04_sigterm_se_second_none(self):
        self._assert_termination_interrupt_case("sigterm", SystemExit(1), "none")

    def test_repro_matrix_05_sigterm_se_second_probe(self):
        self._assert_termination_interrupt_case("sigterm", SystemExit(1), "probe")

    def test_repro_matrix_06_sigterm_se_second_controller(self):
        self._assert_termination_interrupt_case("sigterm", SystemExit(1), "controller")

    def test_repro_matrix_07_claim_ki_second_none(self):
        self._assert_termination_interrupt_case("claim", KeyboardInterrupt("claim_ki"), "none")

    def test_repro_matrix_08_claim_ki_second_probe(self):
        self._assert_termination_interrupt_case("claim", KeyboardInterrupt("claim_ki"), "probe")

    def test_repro_matrix_09_claim_ki_second_controller(self):
        self._assert_termination_interrupt_case("claim", KeyboardInterrupt("claim_ki"), "controller")

    def test_repro_matrix_10_claim_se_second_none(self):
        self._assert_termination_interrupt_case("claim", SystemExit(2), "none")

    def test_repro_matrix_11_claim_se_second_probe(self):
        self._assert_termination_interrupt_case("claim", SystemExit(2), "probe")

    def test_repro_matrix_12_claim_se_second_controller(self):
        self._assert_termination_interrupt_case("claim", SystemExit(2), "controller")

    # --- Body and Ordinary Exceptions Interruption Invariants ---

    def test_body_runtime_error_with_termination_interrupt_body_wins(self):
        """Runner RuntimeError wins over subsequent termination KeyboardInterrupt or SystemExit."""
        clock = guard.FakeClock(monotonic_start=0.0)
        for mode in ("sigterm", "claim"):
            for exc in (KeyboardInterrupt("term_ki"), SystemExit(5)):
                with self.subTest(mode=mode, exc=type(exc).__name__):
                    ctrl = self._setup_controller()
                    sink = {}
                    body_err = RuntimeError("runner exploded")
                    engine = guard.SupervisorEngine(target=self.target, controller=ctrl, clock=clock) if mode == "claim" else None
                    patch_target = guard.SupervisorEngine if mode == "claim" else guard
                    patch_method = "claim" if mode == "claim" else "safe_sigterm"
                    with unittest.mock.patch.object(patch_target, patch_method, side_effect=exc):
                        with self.assertRaises(BaseException) as ctx:
                            guard.run_guarded_supervisor(
                                runner=lambda: (_ for _ in ()).throw(body_err),
                                target=self.target,
                                controller=ctrl,
                                state_sink=sink,
                                engine=engine
                            )
                    self.assertIs(ctx.exception, body_err)
                    self.assertEqual(sink.get("state"), guard.LifecycleState.ERROR)
                    self.assertIs(sink.get("body_exception"), body_err)
                    ctrl.cleanup_bound_pidfd.assert_called_once()
                    if mode == "claim":
                        ctrl.pidfd_send_signal.assert_not_called()

    def test_body_ki_se_with_termination_interrupt_body_wins(self):
        """Runner KeyboardInterrupt or SystemExit exact object wins over termination interrupt."""
        clock = guard.FakeClock(monotonic_start=0.0)
        for body_exc in (KeyboardInterrupt("runner_ki"), SystemExit(7)):
            for mode in ("sigterm", "claim"):
                term_exc = SystemExit(8) if isinstance(body_exc, KeyboardInterrupt) else KeyboardInterrupt("term_ki")
                with self.subTest(body=type(body_exc).__name__, mode=mode):
                    ctrl = self._setup_controller()
                    sink = {}
                    engine = guard.SupervisorEngine(target=self.target, controller=ctrl, clock=clock) if mode == "claim" else None
                    patch_target = guard.SupervisorEngine if mode == "claim" else guard
                    patch_method = "claim" if mode == "claim" else "safe_sigterm"
                    with unittest.mock.patch.object(patch_target, patch_method, side_effect=term_exc):
                        with self.assertRaises(BaseException) as ctx:
                            guard.run_guarded_supervisor(
                                runner=lambda: (_ for _ in ()).throw(body_exc),
                                target=self.target,
                                controller=ctrl,
                                state_sink=sink,
                                engine=engine
                            )
                    self.assertIs(ctx.exception, body_exc)
                    self.assertEqual(sink.get("state"), guard.LifecycleState.ERROR)
                    self.assertIs(sink.get("body_exception"), body_exc)
                    ctrl.cleanup_bound_pidfd.assert_called_once()
                    if mode == "claim":
                        ctrl.pidfd_send_signal.assert_not_called()

    def test_ordinary_termination_exception_returns_error_legacy(self):
        """Ordinary RuntimeError in safe_sigterm or claim returns ERROR without reraise when no interrupt."""
        clock = guard.FakeClock(monotonic_start=0.0)
        for mode in ("sigterm", "claim"):
            with self.subTest(mode=mode):
                ctrl = self._setup_controller()
                sink = {}
                engine = guard.SupervisorEngine(target=self.target, controller=ctrl, clock=clock) if mode == "claim" else None
                patch_target = guard.SupervisorEngine if mode == "claim" else guard
                patch_method = "claim" if mode == "claim" else "safe_sigterm"
                with unittest.mock.patch.object(patch_target, patch_method, side_effect=RuntimeError("ordinary termination failure")):
                    st = guard.run_guarded_supervisor(
                        runner=lambda: None,
                        target=self.target,
                        controller=ctrl,
                        state_sink=sink,
                        engine=engine
                    )
                self.assertEqual(st, guard.LifecycleState.ERROR)
                self.assertEqual(sink.get("state"), guard.LifecycleState.ERROR)
                self.assertIn("ordinary termination failure", sink.get("target_termination_error", ""))
                ctrl.cleanup_bound_pidfd.assert_called_once()
                if mode == "claim":
                    ctrl.pidfd_send_signal.assert_not_called()


class TestT12_FalseClearWithoutValidRecords(unittest.TestCase):
    """A probe that parsed zero well-formed process records must not report
    CLEAR, even when every detected event was forgiven by the legacy baseline.

    The docstring of the CLEAR branch states the invariant this guards: health
    cannot be confirmed without at least one well-formed process record. An
    event forgiven by baseline used to make the empty-record case skip the
    UNKNOWN return and fall through to CLEAR.
    """

    def test_zero_valid_records_with_forgiven_event_is_unknown(self):
        process_dump = "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
        window_dump = "ANR in com.example.app\n"

        status, reason, packages = guard.parse_system_health(
            process_dump=process_dump,
            window_dump=window_dump,
            baseline_anrs={"com.example.app"},
        )

        self.assertEqual(status, guard.ProbeStatus.UNKNOWN)
        self.assertEqual(packages, set())
        self.assertIn("no well-formed process records", reason)

    def test_forgiven_event_still_detects_when_records_are_valid(self):
        """The same forgiveness must not mask a real event once records parse."""
        process_dump = (
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
            "  ProcessRecord{ab12 999:com.other.app/u0a1}\n"
            "    notResponding=false\n"
            "    crashing=false\n"
        )
        status, _, _ = guard.parse_system_health(
            process_dump=process_dump,
            window_dump="",
            baseline_anrs={"com.other.app"},
        )
        self.assertEqual(status, guard.ProbeStatus.CLEAR)


class TestT12_DeterministicEventSummary(unittest.TestCase):
    """Two events that share package and PID must still produce one stable summary.

    Sorting by (package, pid) alone leaves ties ordered by the iteration order of
    a set, which follows the interpreter's hash seed, so the operator-facing
    summary could differ between identical runs.
    """

    def test_summary_order_is_total_when_package_and_pid_tie(self):
        reasons = ["zeta", "alpha", "mu", "delta", "beta", "gamma"]
        dump = "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n" + "".join(
            f"ANR in com.example.app\nPID: 4242\nReason: {reason}\n" for reason in reasons
        )

        status, reason, _ = guard.parse_system_health(process_dump=dump)

        self.assertEqual(status, guard.ProbeStatus.ANR_DETECTED)
        # Six events sharing package and PID. A key that stops at the PID leaves these ties to the
        # iteration order of a set, which follows the interpreter's hash seed, so the summary could
        # differ between identical runs; with six tied events a lucky order is about one in seven
        # hundred, which turns a flaky assertion into a reliable one.
        ordered = ", ".join(
            f"com.example.app(pid=4242, reason={reason})" for reason in sorted(reasons)
        )
        self.assertEqual(reason, f"Detected active unresponsive process(es): {ordered}")


if __name__ == "__main__":
    unittest.main()
