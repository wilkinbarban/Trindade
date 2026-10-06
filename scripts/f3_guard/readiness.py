"""Readiness evaluation for F3 emulator guard.

This module provides aggregation of host memory, process identity, boot evidence,
and non-blocking health probe state into a composite readiness verdict.
"""

from __future__ import annotations

from enum import Enum
import math
from typing import Any, Dict, Optional, Tuple

from .clock import Clock
from .memory import validate_memory_limits
from .types import BootEvidence, ProbeStatus, ProcessIdentity

__all__ = [
    "DEFAULT_MAX_STALE_PROBE_SEC",
    "_ProbeState",
    "_is_finite_num",
    "evaluate_readiness",
]

# --- Contract Constants ---
DEFAULT_MAX_STALE_PROBE_SEC: float = 15.0


class _ProbeState(str, Enum):
    IDLE = "idle"
    RUNNING = "running"
    COMPLETED = "completed"


def _is_finite_num(v: Any) -> bool:
    return type(v) in (int, float) and math.isfinite(v)


def evaluate_readiness(
    boot_completed: Any,
    mem_available_kb: Any,
    swap_free_kb: Any,
    probe_status: ProbeStatus,
    identity_verified: bool,
    baseline_established: bool,
    target: Optional[ProcessIdentity] = None,
    clock: Optional[Clock] = None,
    max_stale_sec: float = DEFAULT_MAX_STALE_PROBE_SEC,
    last_probe_mono: Optional[float] = None,
    probe_state: Optional[_ProbeState] = None,
) -> Tuple[bool, Dict[str, Any]]:
    """Aggregate readiness across boot completion, memory thresholds, probes, and identity."""
    raw_boot = getattr(boot_completed, "boot_completed", boot_completed)
    details: Dict[str, Any] = {
        "boot_completed": raw_boot,
        "mem_available_kb": mem_available_kb,
        "swap_free_kb": swap_free_kb,
        "probe_status": probe_status.value if hasattr(probe_status, "value") else str(probe_status),
        "identity_verified": identity_verified,
        "baseline_established": baseline_established,
    }
    if type(raw_boot) is not bool or not raw_boot:
        details.update({"status": "not_ready", "reason": "Boot sequence incomplete or non-boolean"})
        return False, details
    if target is not None:
        if not isinstance(boot_completed, BootEvidence):
            details.update({"status": "not_ready", "reason": "Boot evidence missing target process identity"})
            return False, details
        if (
            type(boot_completed.pid) is not int
            or type(boot_completed.start_ticks) is not int
            or (boot_completed.uid is not None and type(boot_completed.uid) is not int)
            or boot_completed.pid != target.pid
            or boot_completed.start_ticks != target.start_ticks
            or (boot_completed.avd is not None and (type(boot_completed.avd) is not str or boot_completed.avd != target.avd))
            or (boot_completed.uid is not None and boot_completed.uid != target.uid)
        ):
            details.update({"status": "not_ready", "reason": "Boot evidence target mismatch"})
            return False, details
    if clock is not None and isinstance(boot_completed, BootEvidence):
        now = clock.monotonic()
        b_mono = boot_completed.timestamp_mono
        if (
            not _is_finite_num(now)
            or not _is_finite_num(b_mono)
            or b_mono > now
            or (now - b_mono) > max_stale_sec
            or (now - b_mono) < 0.0
        ):
            details.update({"status": "not_ready", "reason": "Boot evidence stale"})
            return False, details
    if not identity_verified:
        details.update({"status": "not_ready", "reason": "Process identity verification pending or mismatched"})
        return False, details
    if not baseline_established:
        details.update({"status": "not_ready", "reason": "ANR health baseline not established"})
        return False, details
    mem_ok, mem_reason = validate_memory_limits(mem_available_kb, swap_free_kb)
    if not mem_ok:
        details.update({"status": "not_ready", "reason": f"Memory capacity failure: {mem_reason}"})
        return False, details
    if probe_state == _ProbeState.RUNNING:
        details.update({"status": "not_ready", "reason": "System probe pending"})
        return False, details
    if clock is not None and last_probe_mono is not None:
        now = clock.monotonic()
        if not _is_finite_num(now) or not _is_finite_num(last_probe_mono):
            details.update({"status": "not_ready", "reason": "Health probe stale"})
            return False, details
        p_elapsed = now - last_probe_mono
        if p_elapsed < 0.0 or p_elapsed > max_stale_sec:
            details.update({"status": "not_ready", "reason": "Health probe stale"})
            return False, details
    if probe_status != ProbeStatus.CLEAR:
        details.update({"status": "not_ready", "reason": f"System probe unhealthy: {details['probe_status']}"})
        return False, details
    details.update({"status": "ready", "reason": "All preflight and capacity checks satisfied"})
    return True, details
