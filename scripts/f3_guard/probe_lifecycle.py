"""Probe lifecycle coordinator extracted from F3 emulator guard."""

from __future__ import annotations

from typing import Any, Callable, Dict, Optional, Set, Tuple

from scripts.f3_guard.clock import Clock, SystemClock
from scripts.f3_guard.probe_eval import evaluate_adb_probe_result
from scripts.f3_guard.readiness import DEFAULT_MAX_STALE_PROBE_SEC, _ProbeState
from scripts.f3_guard.types import ANREvent, ProbeStatus

__all__ = [
    "ProbeLifecycle",
]


class ProbeLifecycle:
    """Coordinator managing probe execution, timing, polling, and status transitions."""

    def __init__(
        self,
        clock: Optional[Clock] = None,
        start_probe: Optional[Callable[[], None]] = None,
        poll_probe: Optional[Callable[[], Tuple[bool, Optional[Tuple[int, str, str, bool]]]]] = None,
        probe_runner: Optional[Callable[[], Tuple[int, str, str, bool]]] = None,
        cleanup_probe: Optional[Callable[[], Optional[bool]]] = None,
        adapter: Optional[Any] = None,
        baseline_events: Optional[Set[ANREvent]] = None,
        probe_deadline_sec: float = DEFAULT_MAX_STALE_PROBE_SEC,
        stale_probe_sec: float = DEFAULT_MAX_STALE_PROBE_SEC,
    ):
        self._clock: Clock = clock or SystemClock()
        self._stale_probe_sec = float(stale_probe_sec)
        self._probe_deadline_sec = float(probe_deadline_sec)
        self._baseline_events = baseline_events or set()

        self._adapter = adapter
        if self._adapter is not None:
            if start_probe is None and hasattr(self._adapter, "start_probe"):
                start_probe = self._adapter.start_probe
            if poll_probe is None and hasattr(self._adapter, "poll_probe"):
                poll_probe = self._adapter.poll_probe
            if cleanup_probe is None and hasattr(self._adapter, "cancel"):
                cleanup_probe = self._adapter.cancel

        self._cleanup_probe = cleanup_probe
        self.probe_cleanup_error: Optional[str] = None
        self.has_probe_cleanup_failure: bool = False

        self._start_probe = start_probe
        self._poll_probe = poll_probe
        self._probe_runner = probe_runner

        self._probe_state: _ProbeState = _ProbeState.IDLE
        self._probe_start_mono: float = 0.0
        self._probe_sync_result: Optional[Tuple[int, str, str, bool]] = None

        self.last_probe_status: ProbeStatus = ProbeStatus.UNKNOWN
        self.last_probe_mono: float = self._clock.monotonic()

    @property
    def probe_state(self) -> _ProbeState:
        return self._probe_state

    @property
    def probe_start_mono(self) -> float:
        return self._probe_start_mono

    @property
    def probe_deadline_sec(self) -> float:
        return self._probe_deadline_sec

    @property
    def stale_probe_sec(self) -> float:
        return self._stale_probe_sec

    def record_probe_result(self, status: ProbeStatus, monotonic_timestamp: float) -> None:
        self.last_probe_status = status
        self.last_probe_mono = monotonic_timestamp

    def start_cycle(self) -> None:
        """Begin a new (non-blocking) probe cycle."""
        now = self._clock.monotonic()
        if self._start_probe is not None:
            self._start_probe()
            self._probe_state = _ProbeState.RUNNING
            self._probe_start_mono = now
        elif self._probe_runner is not None:
            try:
                self._probe_sync_result = self._probe_runner()
            except Exception:
                self._probe_sync_result = None
            self._probe_state = _ProbeState.COMPLETED
            self._probe_start_mono = now

    def poll_cycle(self) -> Optional[Tuple[int, str, str, bool]]:
        """Poll a running probe. Returns result tuple if done, None if still running."""
        if self._probe_state == _ProbeState.COMPLETED:
            self._probe_state = _ProbeState.IDLE
            result = self._probe_sync_result
            self._probe_sync_result = None
            return result
        if self._poll_probe is not None and self._probe_state == _ProbeState.RUNNING:
            done, result = self._poll_probe()
            if done:
                self._probe_state = _ProbeState.IDLE
                return result
        return None

    def step_probe(self) -> Tuple[bool, Optional[str]]:
        """Advance probe lifecycle: start if idle, poll if running, check deadline.

        Returns (halted, reason).
        """
        if self._start_probe is not None or self._probe_runner is not None:
            try:
                if self._probe_state == _ProbeState.IDLE:
                    self.start_cycle()

                if self._probe_state in (_ProbeState.RUNNING, _ProbeState.COMPLETED):
                    was_running = self._probe_state == _ProbeState.RUNNING
                    orig_start = self._probe_start_mono
                    result = self.poll_cycle()
                    now_probe = self._clock.monotonic()
                    if was_running and (now_probe - orig_start) > self._probe_deadline_sec:
                        return True, f"Health probe overrun (>{self._probe_deadline_sec}s without completion)"
                    if result is not None:
                        retcode, stdout, stderr, is_timeout = result
                        status, p_reason, _ = evaluate_adb_probe_result(
                            returncode=retcode,
                            stdout=stdout,
                            stderr=stderr,
                            is_timeout=is_timeout,
                            baseline_events=self._baseline_events,
                        )
                        self.record_probe_result(status, now_probe)
            except Exception as e:
                return True, f"Health probe execution failure: {e}"
        return False, None
