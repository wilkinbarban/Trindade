"""Backward-compatible decoupled supervisor loop wrapper (8e).

This module provides the legacy DecoupledSupervisorLoop wrapper retained for
backward compatibility. It is not used by SupervisorEngine and provides no
admission or runtime health guarantee.
"""

from __future__ import annotations

from typing import Any, Callable, Optional, Tuple

from scripts.f3_guard.clock import Clock, SystemClock
from scripts.f3_guard.engine import DEFAULT_CADENCE_SEC
from scripts.f3_guard.readiness import DEFAULT_MAX_STALE_PROBE_SEC
from scripts.f3_guard.types import ProbeStatus

__all__ = ["DecoupledSupervisorLoop"]


class DecoupledSupervisorLoop:
    """Manages independent 5s host memory checks decoupled from asynchronous probes.

    .. deprecated::
        This wrapper is retained for backward compatibility but is NOT used by
        SupervisorEngine and provides NO admission or runtime health guarantee.
        Use SupervisorEngine for supervised execution.
    """

    def __init__(
        self,
        cadence_sec: float = DEFAULT_CADENCE_SEC,
        clock: Optional[Clock] = None,
        mem_reader: Optional[Callable[[], Tuple[int, int]]] = None,
        probe_runner: Optional[Callable[[], Any]] = None,
        max_stale_probe_sec: float = DEFAULT_MAX_STALE_PROBE_SEC,
    ):
        self._cadence: float = cadence_sec
        self._clock: Clock = clock or SystemClock()
        self._mem_reader: Optional[Callable[[], Tuple[int, int]]] = mem_reader
        self._probe_runner: Optional[Callable[[], Any]] = probe_runner
        self._max_stale: float = max_stale_probe_sec

        self._last_probe_status: ProbeStatus = ProbeStatus.UNKNOWN
        self._last_probe_mono: float = self._clock.monotonic()

    def record_probe_result(self, status: ProbeStatus, monotonic_timestamp: float) -> None:
        self._last_probe_status = status
        self._last_probe_mono = monotonic_timestamp

    def tick(self) -> Tuple[int, int]:
        """Perform one memory sample tick."""
        if self._mem_reader:
            return self._mem_reader()
        return 0, 0

    def check_health(self) -> Tuple[bool, str]:
        """Verify whether supervisor must stop due to stale probes or errors."""
        now = self._clock.monotonic()
        if (now - self._last_probe_mono) > self._max_stale:
            return True, f"Health probe result stale (>{self._max_stale}s without fresh update)"

        if self._last_probe_status in (ProbeStatus.ANR_DETECTED, ProbeStatus.PROBE_ERROR, ProbeStatus.UNKNOWN):
            return True, f"Health probe failure: {self._last_probe_status.value}"

        return False, "Supervisor loop healthy"
