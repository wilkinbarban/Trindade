"""Post-signal process termination polling loop."""

from __future__ import annotations

import time
from typing import Optional, Tuple

from scripts.f3_guard.controller import ProcessController
from scripts.f3_guard.pidfd_binding import BindingState, PidfdBinding
from scripts.f3_guard.proc_inspection import _INSPECTION_ERROR
from scripts.f3_guard.types import LifecycleState, ProcessIdentity

DEFAULT_GRACE_SEC: float = 20.0

__all__ = [
    "DEFAULT_GRACE_SEC",
    "poll_process_termination",
]


def poll_process_termination(
    target: ProcessIdentity,
    controller: ProcessController,
    reason: str = "",
    grace_seconds: float = DEFAULT_GRACE_SEC,
    poll_interval: float = 0.5,
) -> Tuple[LifecycleState, str]:
    """Poll process termination with grace period, reap direct children, and verify lifecycle."""
    deadline = time.monotonic() + max(0.01, grace_seconds)
    while time.monotonic() < deadline:
        time.sleep(poll_interval)

        active_ticks = controller.get_start_ticks(target.pid)
        state = controller.get_process_state(target.pid)

        # 1. Process confirmed absent from /proc (FileNotFoundError/ProcessLookupError)
        if active_ticks is None:
            if getattr(controller, "is_direct_child", False) is True:
                try:
                    reap_result = controller.try_reap(target.pid)
                except Exception:
                    reap_result = None
                if reap_result is True:
                    return LifecycleState.STOPPED, f"Process exited and reaped ({reason})"
                elif reap_result is False:
                    return LifecycleState.ZOMBIE_PENDING, f"Process absent but reap pending ({reason})"
                else:
                    return LifecycleState.ZOMBIE_UNREAPED, f"Process absent but reap failed ({reason})"
            return LifecycleState.STOPPED, f"Process gracefully exited ({reason})"

        # 1b. Inspection error: cannot read ticks but process not confirmed absent.
        #     Do NOT assume stopped; cross-check with known state.
        if active_ticks is _INSPECTION_ERROR:
            # Cannot confirm status, continue polling (conservative)
            continue

        # 2. Start ticks changed (PID recycled by OS!)
        if active_ticks != target.start_ticks:
            return LifecycleState.PID_MISMATCH, "Process PID recycled by OS during termination"

        # 3. State is inspection error (do NOT assume stopped)
        if state == "INSPECTION_ERROR":
            continue

        # 4. Process entered Zombie state
        if state == "Z":
            if getattr(controller, "is_direct_child", False) is True:
                try:
                    reaped = controller.try_reap(target.pid)
                except Exception:
                    reaped = None
                if reaped is True:
                    return LifecycleState.STOPPED, f"Zombie child successfully reaped ({reason})"
                elif reaped is False:
                    # Zombie exists but not yet reapable (pending)
                    return LifecycleState.ZOMBIE_PENDING, f"Zombie child reap pending ({reason})"
                else:
                    # reaped is None: reap failed (not our child or error)
                    return LifecycleState.ZOMBIE_UNREAPED, f"Zombie child reap failed ({reason})"
            return (
                LifecycleState.ZOMBIE_UNREAPED,
                f"Process entered zombie state (Z) but supervisor is not direct parent to reap it ({reason})"
            )

    # Process still alive after grace period
    return (
        LifecycleState.TERMINATION_FAILED,
        f"Process still alive after {grace_seconds}s grace period. SIGKILL prohibited by policy."
    )


def _close_pidfd(pidfd: Optional[int], controller: ProcessController) -> Optional[Exception]:
    """Close a pidfd via the controller if it's a valid integer fd, returning any close error."""
    if isinstance(pidfd, int) and type(pidfd) is not bool and pidfd >= 0 and hasattr(controller, "close_fd"):
        try:
            controller.close_fd(pidfd)
        except Exception as e:
            return e
    return None


def _acquire_target_pidfd(
    target: ProcessIdentity,
    controller: ProcessController,
) -> Tuple[Optional[int], Optional[Tuple[LifecycleState, str]]]:
    """Acquire or transfer target pidfd via controller binding or legacy open.

    Return (pidfd, None) on success, or (None, (LifecycleState, reason)) on failure.
    """
    pidfd = None
    pidfd_mode = getattr(controller, "use_pidfd", False) is True
    if not pidfd_mode:
        return None, (LifecycleState.ERROR, "pidfd mode required; refusing PID-based signal to prevent race")

    raw_bstate = getattr(controller, "binding_state", None)
    if isinstance(raw_bstate, BindingState):
        bstate = raw_bstate
    elif isinstance(getattr(controller, "_bound_pidfd", None), PidfdBinding):
        bstate = BindingState.BOUND
    elif type(controller) is not ProcessController and not isinstance(controller, ProcessController):
        bstate = BindingState.NEVER_BOUND
    else:
        bstate = raw_bstate

    if hasattr(controller, "consume_bound_pidfd") and bstate == BindingState.BOUND:
        try:
            val = controller.consume_bound_pidfd(target)
            if isinstance(val, int) and type(val) is not bool and val >= 0:
                pidfd = val
        except (KeyboardInterrupt, SystemExit):
            if hasattr(controller, "_binding_state"):
                controller._binding_state = BindingState.ERROR
            raise
        except Exception as e:
            if hasattr(controller, "_binding_state"):
                controller._binding_state = BindingState.ERROR
            return None, (LifecycleState.ERROR, f"consume_bound_pidfd failed: {e}")
        bstate = getattr(controller, "binding_state", None)

    if bstate != BindingState.NEVER_BOUND and pidfd is None:
        return None, (LifecycleState.ERROR, f"Bound pidfd transfer failed or invalid ({getattr(bstate, 'value', bstate)}); refusing reopen")

    if pidfd is None:
        if bstate is BindingState.NEVER_BOUND and hasattr(controller, "pidfd_open"):
            pidfd = controller.pidfd_open(target.pid)
        else:
            return None, (LifecycleState.ERROR, f"pidfd unavailable and legacy open not permitted for state ({getattr(bstate, 'value', bstate)})")

    if not isinstance(pidfd, int) or type(pidfd) is bool or pidfd < 0:
        return None, (LifecycleState.ERROR, "pidfd_open failed; refusing PID-based fallback to prevent signal race")

    return pidfd, None
