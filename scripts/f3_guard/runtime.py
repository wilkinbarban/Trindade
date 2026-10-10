"""Hermetic runtime composition for the F3 emulator guard (W1)."""

from __future__ import annotations

from typing import Any, Callable, Dict, Optional

from scripts.f3_guard.clock import Clock
from scripts.f3_guard.controller import ProcessController
from scripts.f3_guard.engine import SupervisorEngine
from scripts.f3_guard.runner import run_guarded_supervisor
from scripts.f3_guard.scheduler import SupervisorScheduler
from scripts.f3_guard.sigterm import DEFAULT_GRACE_SEC
from scripts.f3_guard.types import LifecycleState, ProcessIdentity

__all__ = ["run_guard"]


def _resolve_engine_clock(engine: Any) -> Clock:
    """Return the engine's own clock, failing closed when unusable.

    The scheduler must share this exact object. A missing or unusable
    clock never falls back to a real clock.
    """
    clock = getattr(engine, "_clock", None)
    if clock is None or not callable(getattr(clock, "monotonic", None)):
        raise ValueError(
            "engine must expose a usable _clock for pacing; refusing to "
            "fall back to a real clock"
        )
    return clock


def run_guard(
    engine: SupervisorEngine,
    target: ProcessIdentity,
    controller: ProcessController,
    state_sink: Dict[str, Any],
    *,
    cleanup_probe: Optional[Callable[[], Any]] = None,
    grace_seconds: float = DEFAULT_GRACE_SEC,
    termination_poll_interval: float = 0.5,
    pacing_interval: Optional[float] = None,
    sleeper: Optional[Callable[[float], None]] = None,
) -> LifecycleState:
    """Compose engine, scheduler, and guarded runner into one run.

    ``pacing_interval`` is the scheduler grid interval. The distinct
    ``termination_poll_interval`` is the termination polling cadence the
    guarded runner uses. They are never conflated.
    """
    clock = _resolve_engine_clock(engine)
    scheduler = SupervisorScheduler(
        engine, poll_interval=pacing_interval, clock=clock, sleeper=sleeper
    )
    return run_guarded_supervisor(
        scheduler.run,
        target,
        controller,
        state_sink,
        cleanup_probe=cleanup_probe,
        grace_seconds=grace_seconds,
        poll_interval=termination_poll_interval,
        engine=engine,
    )
