"""Clock abstraction and monotonic TTL tracking for F3 emulator guard.

This module provides pure stdlib clock abstractions (Clock, SystemClock, FakeClock)
and skew-immune monotonic TTL tracking extracted from the F3 emulator guard.
"""

from __future__ import annotations

import time
from typing import Optional

__all__ = [
    "DEFAULT_TTL_SEC",
    "Clock",
    "SystemClock",
    "FakeClock",
    "MonotonicTTLTracker",
]

# --- Contract Constants ---
DEFAULT_TTL_SEC: float = 1200.0  # 20 minutes


class Clock:
    def monotonic(self) -> float:
        raise NotImplementedError

    def time(self) -> float:
        raise NotImplementedError


class SystemClock(Clock):
    def monotonic(self) -> float:
        return time.monotonic()

    def time(self) -> float:
        return time.time()


class FakeClock(Clock):
    """Injectable clock for hermetic unit testing of TTL and cadence."""

    def __init__(self, monotonic_start: float = 0.0, wall_start: float = 1700000000.0):
        self._mono: float = float(monotonic_start)
        self._wall: float = float(wall_start)

    def monotonic(self) -> float:
        return self._mono

    def time(self) -> float:
        return self._wall

    def advance_monotonic(self, delta: float) -> None:
        self._mono += float(delta)

    def advance_wall(self, delta: float) -> None:
        self._wall += float(delta)


# --- Monotonic TTL Tracker ---

class MonotonicTTLTracker:
    """Tracks elapsed runtime using monotonic time to resist wall-clock skew."""

    def __init__(self, ttl_seconds: float = DEFAULT_TTL_SEC, clock: Optional[Clock] = None):
        self._clock: Clock = clock or SystemClock()
        self._ttl_sec: float = float(ttl_seconds)
        self._start_mono: float = self._clock.monotonic()

    def elapsed_seconds(self) -> float:
        return max(0.0, self._clock.monotonic() - self._start_mono)

    def remaining_seconds(self) -> float:
        remaining = self._ttl_sec - self.elapsed_seconds()
        return max(0.0, remaining)

    def is_expired(self) -> bool:
        return self.elapsed_seconds() >= self._ttl_sec
