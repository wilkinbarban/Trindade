"""Anchored-grid pacing scheduler driving SupervisorEngine to halt."""

from __future__ import annotations

import math
import time
from typing import Any, Callable, Optional

from scripts.f3_guard.clock import Clock
from scripts.f3_guard.readiness import _ProbeState
from scripts.f3_guard.types import LifecycleState

__all__ = [
    "SupervisorScheduler",
]


class SupervisorScheduler:
    """Anchored-grid pacing scheduler driving SupervisorEngine to halt."""

    def __init__(
        self,
        engine: Any,
        poll_interval: Optional[float] = None,
        clock: Optional[Clock] = None,
        sleeper: Optional[Callable[[float], None]] = None,
    ):
        pi = getattr(engine, "_poll_interval", 0.5) if poll_interval is None else poll_interval
        if isinstance(pi, bool) or not isinstance(pi, (int, float)) or not math.isfinite(pi) or pi <= 0:
            raise ValueError(f"poll_interval must be finite positive number: {pi!r}")
        if clock is not None and clock is not getattr(engine, "_clock", None):
            raise ValueError("Conflicting clock supplied to scheduler; must share engine clock")
        if sleeper is not None and not callable(sleeper):
            raise TypeError(f"sleeper must be callable or None: {sleeper!r}")
        self._engine = engine
        self._interval = float(pi)
        self._clock: Clock = getattr(engine, "_clock", None) if clock is None else clock
        self._sleeper: Callable[[float], None] = time.sleep if sleeper is None else sleeper
        self.state: LifecycleState = getattr(engine, "state", LifecycleState.RUNNING)
        self.termination_state: Optional[LifecycleState] = None
        self.primary_error: Optional[BaseException] = None
        self.secondary_error: Optional[BaseException] = None

    def _fail(self, msg: str, exc: Optional[BaseException] = None) -> LifecycleState:
        self.primary_error = getattr(self._engine, "primary_error", None) or exc
        self._engine.primary_error = self.primary_error
        self._engine.secondary_error = getattr(self._engine, "secondary_error", None)
        self._engine.state = self._engine.termination_state = self.state = self.termination_state = LifecycleState.ERROR
        try:
            self._engine._terminate(msg)
        except BaseException as ce:
            self.secondary_error = getattr(self._engine, "secondary_error", None) or ce
            self._engine.secondary_error = self.secondary_error
            if isinstance(ce, (KeyboardInterrupt, SystemExit)):
                raise
        finally:
            self._engine.state = self._engine.termination_state = self.state = self.termination_state = LifecycleState.ERROR
        if exc is not None:
            raise exc
        return LifecycleState.ERROR

    def _read_clock(self, prev: Optional[float] = None) -> float:
        val = self._clock.monotonic()
        if isinstance(val, bool) or not isinstance(val, (int, float)) or not math.isfinite(val) or (prev is not None and val < prev):
            raise RuntimeError(f"Invalid clock value: {val!r}")
        return float(val)

    def run(self) -> LifecycleState:
        try:
            t0 = self._read_clock()
        except BaseException as ce:
            return self._fail(f"Clock failure before first step: {ce}", ce)
        step_idx, last_wake = 0, t0
        while True:
            step_idx += 1
            try:
                halted, state = self._engine.step()
            except Exception as se:
                return self._fail(f"Step failure: {se}", se)
            self.state = state
            self.termination_state = getattr(self._engine, "termination_state", state)
            if halted:
                return state
            try:
                now = self._read_clock(last_wake)
            except BaseException as ce:
                return self._fail(f"Clock failure after step: {ce}", ce)
            target_wake = t0 + (step_idx * self._interval)
            while target_wake <= now:
                step_idx += 1
                target_wake = t0 + (step_idx * self._interval)
            caps = [target_wake]
            ttl_rem = getattr(self._engine.ttl_tracker, "remaining_seconds", lambda: 0.0)()
            if ttl_rem > 0:
                caps.append(now + ttl_rem)
            if getattr(self._engine, "_mem_reader", None) and (
                getattr(self._engine, "_last_mem_sample_mono", now) + getattr(self._engine, "_cadence_sec", 5.0)
            ) > now:
                caps.append(
                    getattr(self._engine, "_last_mem_sample_mono", now) + getattr(self._engine, "_cadence_sec", 5.0)
                )
            if getattr(self._engine, "_probe_state", None) == _ProbeState.RUNNING and (
                getattr(self._engine, "_probe_start_mono", now) + getattr(self._engine, "_probe_deadline_sec", 15.0)
            ) > now:
                caps.append(
                    getattr(self._engine, "_probe_start_mono", now) + getattr(self._engine, "_probe_deadline_sec", 15.0)
                )
            try:
                self._sleeper(min(caps) - now)
                post_sleep = self._read_clock(now)
                if post_sleep < now:
                    raise RuntimeError(f"Sleeper made no progress: {post_sleep} < {now}")
                last_wake = post_sleep
            except BaseException as se:
                return self._fail(f"Sleeper/pacing failure: {se}", se)
