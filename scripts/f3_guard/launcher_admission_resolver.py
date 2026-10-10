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

    def _acquire_exception_custody(self) -> tuple[Any, Any]:
        """Capture the source selected by one public protocol snapshot.

        Restore configuration without rolling back retained state.
        Interrupted field reads leave only that helper's known custody.
        """
        source = self.custody_source
        selected = UNKNOWN

        def capture() -> Any:
            nonlocal selected
            try:
                selected = source() if callable(source) else source
            except BaseException:
                selected = UNKNOWN
            return selected

        try:
            if source is None:
                selected = None
            else:
                self.custody_source = capture
            try:
                custody = self.snapshot()
            except BaseException:
                custody = self.known_snapshot
                selected = UNKNOWN
        finally:
            self.custody_source = source
        return selected, custody

    def resolve_exception_result(
        self, exc: BaseException, rethrow_interrupt: bool = False
    ) -> LaunchResult:
        src, custody = self._acquire_exception_custody()
        child, pidfd = custody.pending_child, custody.pending_pidfd
        resolved: Optional[LaunchResult] = None

        if src is not None and src is not UNKNOWN:
            try:
                prior_snap = getattr(src, "last_snapshot", None)
                # Retain acquisition evidence even if later getters change.
                # Any incomplete inventory aborts candidate acceptance.
                known_prior = {id(custody.last_result)}
                active = getattr(src, "active_helper", None)
                for obj in (src, active, prior_snap):
                    if obj is not None:
                        previous = getattr(obj, "last_result", None)
                        if previous is not None:
                            known_prior.add(id(previous))
                record_fn = getattr(src, "record_exception", None)
                if callable(record_fn):
                    snap = record_fn(exc)
                    has_proof = (
                        getattr(src, "last_exception", None) is exc
                        or getattr(snap, "last_exception", None) is exc
                    ) and (prior_snap is None or snap is not prior_snap)
                    if has_proof:
                        candidate = getattr(snap, "last_result", None)
                        if (
                            isinstance(candidate, LaunchResult)
                            and id(candidate) not in known_prior
                        ):
                            success = candidate.success
                            candidate_fd = candidate.pidfd
                            candidate_child = candidate.child_handle
                            cleanup = candidate.pending_cleanup
                            transferred = candidate.transferred
                            expected_cleanup = (
                                child is not None or pidfd is not None
                            )
                            fd_match = (
                                candidate_fd is None and pidfd is None
                            ) or (
                                type(candidate_fd) is int
                                and type(pidfd) is int
                                and candidate_fd == pidfd
                            )
                            if (
                                success is False
                                and candidate_child is child and fd_match
                                and cleanup is expected_cleanup
                                and transferred is False
                            ):
                                resolved = candidate
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
            except BaseException:
                resolved = None

        if resolved is not None:
            try:
                self.set_fallback_result(resolved)
            except BaseException:
                # Keep the valid failure; publication may be incomplete.
                pass
        else:
            raise exc

        if rethrow_interrupt and isinstance(exc, (KeyboardInterrupt, SystemExit)):
            raise exc

        return resolved

    resolve_exception = resolve = resolve_exception_result


LauncherAdmissionResolver = AdmissionCustodyResolver
