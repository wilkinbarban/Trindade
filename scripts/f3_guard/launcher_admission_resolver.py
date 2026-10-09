"""Authoritative custody descriptor and exception result resolver for emulator launcher."""

from __future__ import annotations

from typing import Any, Optional

from scripts.f3_guard.launcher_custody_protocol import AdmissionCustodyProtocol, UNKNOWN
from scripts.f3_guard.launcher_preflight import LaunchResult

__all__ = ["AdmissionCustodyResolver", "LauncherAdmissionResolver"]

_CANONICAL_LAUNCH_RESULT = LaunchResult


def _safe_result(
    success: bool,
    error: str,
    child: Optional[Any] = None,
    pidfd: Optional[int] = None,
    transferred: bool = False,
    pending_cleanup: Optional[bool] = None,
) -> LaunchResult:
    cleanup = (child is not None or pidfd is not None) if pending_cleanup is None else pending_cleanup
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


class AdmissionCustodyResolver(AdmissionCustodyProtocol):
    """Safe authoritative custody and exception result resolver."""

    safe_result = staticmethod(_safe_result)

    def set_custody_source(self, source: Optional[Any]) -> None:
        self.custody_source = source
        self._known_helper = None
        self._known.clear()
        self._cache = None

    def resolve_exception_result(
        self, exc: BaseException, rethrow_interrupt: bool = False
    ) -> LaunchResult:
        src, child, pidfd = self._resolve_source(), self.pending_child, self.pending_pidfd
        resolved: Optional[LaunchResult] = None

        if src is not None and src is not UNKNOWN:
            try:
                prior_snap = getattr(src, "last_snapshot", None)
                known_prior = {
                    id(r) for obj in (src, getattr(src, "active_helper", None), prior_snap)
                    if obj is not None and (r := getattr(obj, "last_result", None)) is not None
                }
                record_fn = getattr(src, "record_exception", None)
                if callable(record_fn):
                    snap = record_fn(exc)
                    has_proof = (
                        getattr(src, "last_exception", None) is exc
                        or getattr(snap, "last_exception", None) is exc
                    ) and (prior_snap is None or snap is not prior_snap)
                    if has_proof:
                        candidate = getattr(snap, "last_result", None)
                        if candidate is not None and id(candidate) not in known_prior:
                            expected_cleanup = bool(child is not None or pidfd is not None)
                            fd_match = (candidate.pidfd is None and pidfd is None) or (
                                type(candidate.pidfd) is int and type(pidfd) is int and candidate.pidfd == pidfd
                            )
                            if (
                                isinstance(candidate, LaunchResult)
                                and type(candidate.success) is bool and candidate.success is False
                                and candidate.child_handle is child and fd_match
                                and type(candidate.pending_cleanup) is bool and candidate.pending_cleanup is expected_cleanup
                                and type(candidate.transferred) is bool and candidate.transferred is False
                            ):
                                resolved = candidate
            except (KeyboardInterrupt, SystemExit):
                raise
            except BaseException:
                pass

        if resolved is None:
            try:
                resolved = _safe_result(
                    success=False,
                    error=f"Post-spawn exception: {type(exc).__name__}",
                    child=child,
                    pidfd=pidfd,
                )
            except (KeyboardInterrupt, SystemExit):
                raise
            except BaseException:
                resolved = None

        if resolved is not None:
            self.set_fallback_result(resolved)
        else:
            raise exc

        if rethrow_interrupt and isinstance(exc, (KeyboardInterrupt, SystemExit)):
            raise exc

        return resolved

    resolve_exception = resolve = resolve_exception_result


LauncherAdmissionResolver = AdmissionCustodyResolver
