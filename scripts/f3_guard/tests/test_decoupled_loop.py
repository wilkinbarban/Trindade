"""Hermetic unit tests for DecoupledSupervisorLoop backward-compatibility wrapper (8e)."""

from __future__ import annotations

from contextlib import ExitStack
import os
import select
import signal
import subprocess
import time
import unittest
from unittest.mock import MagicMock, patch

from scripts.f3_guard.clock import FakeClock, SystemClock
from scripts.f3_guard.decoupled_loop import DecoupledSupervisorLoop
from scripts.f3_guard.engine import DEFAULT_CADENCE_SEC
from scripts.f3_guard.readiness import DEFAULT_MAX_STALE_PROBE_SEC
from scripts.f3_guard.types import ProbeStatus


class TestDecoupledSupervisorLoop(unittest.TestCase):
    """Hermetic unit tests verifying DecoupledSupervisorLoop contracts and isolation."""

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

    def tearDown(self) -> None:
        self.assertEqual(self.proc_access_count, 0)

    def test_defaults_and_mocked_system_clock(self) -> None:
        mock_clock_inst = MagicMock(spec=SystemClock)
        mock_clock_inst.monotonic.return_value = 100.0
        with patch("scripts.f3_guard.decoupled_loop.SystemClock", return_value=mock_clock_inst):
            loop = DecoupledSupervisorLoop()
            self.assertEqual(loop._cadence, DEFAULT_CADENCE_SEC)
            self.assertIs(loop._clock, mock_clock_inst)
            self.assertIsNone(loop._mem_reader)
            self.assertIsNone(loop._probe_runner)
            self.assertEqual(loop._max_stale, DEFAULT_MAX_STALE_PROBE_SEC)
            self.assertEqual(loop._last_probe_status, ProbeStatus.UNKNOWN)
            self.assertEqual(loop._last_probe_mono, 100.0)

    def test_injected_fake_clock(self) -> None:
        clock = FakeClock(monotonic_start=50.0)
        loop = DecoupledSupervisorLoop(clock=clock)
        self.assertIs(loop._clock, clock)
        self.assertEqual(loop._last_probe_mono, 50.0)

    def test_initial_probe_status_unknown_health_failure(self) -> None:
        clock = FakeClock(monotonic_start=10.0)
        loop = DecoupledSupervisorLoop(clock=clock)
        should_stop, reason = loop.check_health()
        self.assertTrue(should_stop)
        self.assertEqual(reason, f"Health probe failure: {ProbeStatus.UNKNOWN.value}")

    def test_tick_mem_reader_exact_tuple_and_fallback(self) -> None:
        loop = DecoupledSupervisorLoop(clock=FakeClock())
        self.assertEqual(loop.tick(), (0, 0))

        sample = (2048000, 1024000)
        reader = MagicMock(return_value=sample)
        loop_with_reader = DecoupledSupervisorLoop(clock=FakeClock(), mem_reader=reader)
        res = loop_with_reader.tick()
        self.assertEqual(res, sample)
        self.assertIs(res, sample)
        reader.assert_called_once_with()

    def test_tick_deliberately_falsy_mem_reader_fallback_not_called(self) -> None:
        falsy_reader = MagicMock()
        falsy_reader.__bool__.return_value = False
        loop = DecoupledSupervisorLoop(clock=FakeClock(), mem_reader=falsy_reader)
        self.assertEqual(loop.tick(), (0, 0))
        falsy_reader.assert_not_called()

    def test_tick_mem_reader_exceptions_propagate_exact_identity(self) -> None:
        for exc in (RuntimeError("simulated runtime"), KeyboardInterrupt(), SystemExit(2)):
            reader = MagicMock(side_effect=exc)
            loop = DecoupledSupervisorLoop(clock=FakeClock(), mem_reader=reader)
            with self.assertRaises(BaseException) as cm:
                loop.tick()
            self.assertIs(cm.exception, exc)

    def test_record_probe_result_clear_and_healthy(self) -> None:
        clock = FakeClock(monotonic_start=10.0)
        loop = DecoupledSupervisorLoop(clock=clock)
        loop.record_probe_result(ProbeStatus.CLEAR, 12.0)
        self.assertEqual(loop._last_probe_status, ProbeStatus.CLEAR)
        self.assertEqual(loop._last_probe_mono, 12.0)
        clock.advance_monotonic(5.0)
        should_stop, reason = loop.check_health()
        self.assertFalse(should_stop)
        self.assertEqual(reason, "Supervisor loop healthy")

    def test_stale_boundary_exact_limit_and_exceeded(self) -> None:
        clock = FakeClock(monotonic_start=10.0)
        loop = DecoupledSupervisorLoop(clock=clock, max_stale_probe_sec=15.0)
        loop.record_probe_result(ProbeStatus.CLEAR, 10.0)

        clock.advance_monotonic(15.0)
        should_stop, reason = loop.check_health()
        self.assertFalse(should_stop)
        self.assertEqual(reason, "Supervisor loop healthy")

        clock.advance_monotonic(0.001)
        should_stop, reason = loop.check_health()
        self.assertTrue(should_stop)
        self.assertEqual(reason, "Health probe result stale (>15.0s without fresh update)")

    def test_stale_priority_over_all_failure_statuses(self) -> None:
        for status in (ProbeStatus.ANR_DETECTED, ProbeStatus.PROBE_ERROR, ProbeStatus.UNKNOWN):
            clock = FakeClock(monotonic_start=10.0)
            loop = DecoupledSupervisorLoop(clock=clock, max_stale_probe_sec=15.0)
            loop.record_probe_result(status, 10.0)
            clock.advance_monotonic(15.1)
            should_stop, reason = loop.check_health()
            self.assertTrue(should_stop)
            self.assertEqual(reason, "Health probe result stale (>15.0s without fresh update)")

    def test_health_failure_statuses_when_fresh(self) -> None:
        for status in (ProbeStatus.ANR_DETECTED, ProbeStatus.PROBE_ERROR, ProbeStatus.UNKNOWN):
            clock = FakeClock(monotonic_start=10.0)
            loop = DecoupledSupervisorLoop(clock=clock, max_stale_probe_sec=15.0)
            loop.record_probe_result(status, 10.0)
            clock.advance_monotonic(5.0)
            should_stop, reason = loop.check_health()
            self.assertTrue(should_stop)
            self.assertEqual(reason, f"Health probe failure: {status.value}")

    def test_custom_max_stale_probe_sec(self) -> None:
        clock = FakeClock(monotonic_start=100.0)
        loop = DecoupledSupervisorLoop(clock=clock, max_stale_probe_sec=30.0)
        loop.record_probe_result(ProbeStatus.CLEAR, 100.0)
        clock.advance_monotonic(30.0)
        self.assertEqual(loop.check_health(), (False, "Supervisor loop healthy"))
        clock.advance_monotonic(0.1)
        should_stop, reason = loop.check_health()
        self.assertTrue(should_stop)
        self.assertEqual(reason, "Health probe result stale (>30.0s without fresh update)")

    def test_probe_runner_never_invoked(self) -> None:
        clock = FakeClock(monotonic_start=10.0)
        probe_runner = MagicMock()
        loop = DecoupledSupervisorLoop(clock=clock, probe_runner=probe_runner)
        self.assertIs(loop._probe_runner, probe_runner)

        loop.tick()
        probe_runner.assert_not_called()

        loop.check_health()
        probe_runner.assert_not_called()

        loop.record_probe_result(ProbeStatus.CLEAR, 10.0)
        loop.check_health()
        probe_runner.assert_not_called()

        clock.advance_monotonic(20.0)
        loop.check_health()
        probe_runner.assert_not_called()
