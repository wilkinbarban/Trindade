"""Hermetic unit tests for scripts.f3_guard.clock module."""

from __future__ import annotations

import os
import sys
import unittest
from unittest.mock import patch

# Add repository root to sys.path if not present for standalone execution
_REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

from scripts.f3_guard.clock import (
    DEFAULT_TTL_SEC,
    Clock,
    FakeClock,
    MonotonicTTLTracker,
    SystemClock,
)


class TestClockInterface(unittest.TestCase):
    """Unit tests for Clock base class contract and abstract method semantics."""

    def test_clock_base_raises_not_implemented(self) -> None:
        clock = Clock()
        with self.assertRaises(NotImplementedError):
            clock.monotonic()
        with self.assertRaises(NotImplementedError):
            clock.time()


class TestSystemClock(unittest.TestCase):
    """Hermetic unit tests for SystemClock delegating to time functions via mocks."""

    @patch("time.monotonic", return_value=1234.5)
    def test_system_clock_monotonic_delegation(self, mock_mono) -> None:
        clock = SystemClock()
        self.assertEqual(clock.monotonic(), 1234.5)
        mock_mono.assert_called_once_with()

    @patch("time.time", return_value=1700000000.0)
    def test_system_clock_time_delegation(self, mock_time) -> None:
        clock = SystemClock()
        self.assertEqual(clock.time(), 1700000000.0)
        mock_time.assert_called_once_with()


class TestFakeClock(unittest.TestCase):
    """Unit tests for FakeClock deterministic advancement and separation of clocks."""

    def test_fake_clock_defaults(self) -> None:
        clock = FakeClock()
        self.assertEqual(clock.monotonic(), 0.0)
        self.assertEqual(clock.time(), 1700000000.0)

    def test_fake_clock_custom_initial_values(self) -> None:
        clock = FakeClock(monotonic_start=42.5, wall_start=1650000000.0)
        self.assertEqual(clock.monotonic(), 42.5)
        self.assertEqual(clock.time(), 1650000000.0)

    def test_fake_clock_advancements_independent(self) -> None:
        clock = FakeClock(monotonic_start=10.0, wall_start=100.0)
        clock.advance_monotonic(5.5)
        self.assertEqual(clock.monotonic(), 15.5)
        self.assertEqual(clock.time(), 100.0)

        clock.advance_wall(20.25)
        self.assertEqual(clock.monotonic(), 15.5)
        self.assertEqual(clock.time(), 120.25)

    def test_fake_clock_negative_or_zero_advance(self) -> None:
        clock = FakeClock(monotonic_start=100.0, wall_start=500.0)
        clock.advance_monotonic(0.0)
        clock.advance_wall(0.0)
        self.assertEqual(clock.monotonic(), 100.0)
        self.assertEqual(clock.time(), 500.0)

        clock.advance_monotonic(-10.0)
        clock.advance_wall(-50.0)
        self.assertEqual(clock.monotonic(), 90.0)
        self.assertEqual(clock.time(), 450.0)


class TestMonotonicTTLTracker(unittest.TestCase):
    """Hermetic unit tests for MonotonicTTLTracker duration, boundaries, and skew immunity."""

    def test_default_ttl_constant_and_initialization(self) -> None:
        clock = FakeClock(monotonic_start=50.0)
        tracker = MonotonicTTLTracker(clock=clock)
        self.assertEqual(tracker._ttl_sec, DEFAULT_TTL_SEC)
        self.assertEqual(tracker._ttl_sec, 1200.0)
        self.assertEqual(tracker.elapsed_seconds(), 0.0)
        self.assertEqual(tracker.remaining_seconds(), 1200.0)
        self.assertFalse(tracker.is_expired())

    def test_default_clock_instantiates_system_clock(self) -> None:
        with patch("time.monotonic", return_value=100.0):
            tracker = MonotonicTTLTracker()
            self.assertIsInstance(tracker._clock, SystemClock)
            self.assertEqual(tracker._start_mono, 100.0)

    def test_elapsed_and_remaining_progression(self) -> None:
        clock = FakeClock(monotonic_start=100.0)
        tracker = MonotonicTTLTracker(ttl_seconds=50.0, clock=clock)

        clock.advance_monotonic(20.0)
        self.assertEqual(tracker.elapsed_seconds(), 20.0)
        self.assertEqual(tracker.remaining_seconds(), 30.0)
        self.assertFalse(tracker.is_expired())

        clock.advance_monotonic(29.9)
        self.assertAlmostEqual(tracker.elapsed_seconds(), 49.9)
        self.assertAlmostEqual(tracker.remaining_seconds(), 0.1)
        self.assertFalse(tracker.is_expired())

    def test_boundary_exact_expiry(self) -> None:
        clock = FakeClock(monotonic_start=0.0)
        tracker = MonotonicTTLTracker(ttl_seconds=1200.0, clock=clock)

        clock.advance_monotonic(1200.0)
        self.assertEqual(tracker.elapsed_seconds(), 1200.0)
        self.assertEqual(tracker.remaining_seconds(), 0.0)
        self.assertTrue(tracker.is_expired())

    def test_post_expiry_clamped_to_zero(self) -> None:
        clock = FakeClock(monotonic_start=0.0)
        tracker = MonotonicTTLTracker(ttl_seconds=100.0, clock=clock)

        clock.advance_monotonic(250.0)
        self.assertEqual(tracker.elapsed_seconds(), 250.0)
        self.assertEqual(tracker.remaining_seconds(), 0.0)
        self.assertTrue(tracker.is_expired())

    def test_monotonic_backward_step_clamped_to_zero_elapsed(self) -> None:
        clock = FakeClock(monotonic_start=100.0)
        tracker = MonotonicTTLTracker(ttl_seconds=60.0, clock=clock)

        clock.advance_monotonic(-20.0)
        self.assertEqual(tracker.elapsed_seconds(), 0.0)
        self.assertEqual(tracker.remaining_seconds(), 60.0)
        self.assertFalse(tracker.is_expired())

    def test_wallclock_jump_does_not_affect_ttl(self) -> None:
        clock = FakeClock(monotonic_start=100.0, wall_start=1700000000.0)
        tracker = MonotonicTTLTracker(ttl_seconds=1200.0, clock=clock)

        self.assertFalse(tracker.is_expired())
        self.assertEqual(tracker.remaining_seconds(), 1200.0)

        # Simulate large wall-clock skew (+/- hours)
        clock.advance_wall(7200.0)
        self.assertFalse(tracker.is_expired())
        self.assertEqual(tracker.remaining_seconds(), 1200.0)

        clock.advance_wall(-14400.0)
        self.assertFalse(tracker.is_expired())
        self.assertEqual(tracker.remaining_seconds(), 1200.0)

        # Only monotonic advance increments elapsed and reduces remaining
        clock.advance_monotonic(10.0)
        self.assertFalse(tracker.is_expired())
        self.assertEqual(tracker.remaining_seconds(), 1190.0)


if __name__ == "__main__":
    unittest.main()
