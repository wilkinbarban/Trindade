"""Pure admission failure result factory with canonical fallback and diagnostic traps."""

from __future__ import annotations

from typing import Any, Optional

from scripts.f3_guard.launcher_preflight import LaunchResult

__all__ = [
    "LaunchResult",
    "LauncherAdmissionFailureFactory",
    "failure_result",
    "safe_exception_failure",
    "safe_result",
]

_CANONICAL_LAUNCH_RESULT = LaunchResult
_POST_SPAWN_FALLBACK_ERROR = "Post-spawn exception"


def safe_result(
    success: bool,
    error: Optional[str] = None,
    child: Optional[Any] = None,
    pidfd: Optional[int] = None,
    transferred: bool = False,
    pending_cleanup: Optional[bool] = None,
) -> LaunchResult:
    """Safely construct a LaunchResult with canonical fallback on constructor failure."""
    cleanup = (
        (child is not None or pidfd is not None)
        if pending_cleanup is None
        else pending_cleanup
    )
    kw = {
        "success": success,
        "pidfd": pidfd,
        "child_handle": child,
        "error": error,
        "pending_cleanup": cleanup,
        "transferred": transferred,
    }
    try:
        return LaunchResult(**kw)
    except BaseException:
        return _CANONICAL_LAUNCH_RESULT(**kw)


def failure_result(
    error: str,
    child: Optional[Any] = None,
    pidfd: Optional[int] = None,
    last: Optional[LaunchResult] = None,
) -> LaunchResult:
    """Construct an admission failure result enforcing transferred custody invariants."""
    try:
        xfer = getattr(last, "transferred", False) is True
    except BaseException:
        xfer = False
    return safe_result(
        False,
        error=error,
        child=child,
        pidfd=None if xfer else pidfd,
        transferred=xfer,
        pending_cleanup=False if xfer else None,
    )


def safe_exception_failure(
    exc: BaseException,
    child: Optional[Any] = None,
    pidfd: Optional[int] = None,
    last: Optional[LaunchResult] = None,
) -> LaunchResult:
    """Format post-spawn exception diagnostics trapping inspection errors safely."""
    err = _POST_SPAWN_FALLBACK_ERROR
    try:
        cls = getattr(exc, "__class__", type(exc))
        name = getattr(cls, "__name__", None)
        if isinstance(name, str) and name:
            err = f"Post-spawn exception: {name}"
    except BaseException:
        err = _POST_SPAWN_FALLBACK_ERROR
    return failure_result(err, child=child, pidfd=pidfd, last=last)


class LauncherAdmissionFailureFactory:
    """Cohesive pure factory class exposing static failure result builders."""

    safe_result = staticmethod(safe_result)
    failure_result = staticmethod(failure_result)
    safe_exception_failure = staticmethod(safe_exception_failure)
