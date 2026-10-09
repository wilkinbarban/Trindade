"""Authoritative custody adapter delegating to resolver with guarded snapshot."""

from __future__ import annotations

from typing import Any, Optional

from scripts.f3_guard.launcher_admission_failure import safe_exception_failure
from scripts.f3_guard.launcher_admission_resolver import AdmissionCustodyResolver
from scripts.f3_guard.launcher_custody import LauncherCustodySnapshot
from scripts.f3_guard.launcher_preflight import LaunchResult

__all__ = ["LauncherAdmissionAdapter", "LauncherAdmissionCustodyAdapter"]


class LauncherAdmissionAdapter:
    """Pure custody adapter delegating to public resolver API with guarded snapshot."""

    def __init__(
        self,
        custody_source: Optional[Any] = None,
        fallback_result: Optional[LaunchResult] = None,
    ) -> None:
        self._resolver = AdmissionCustodyResolver(
            custody_source=custody_source, fallback_result=fallback_result
        )
        self._last_result: Optional[LaunchResult] = fallback_result
        self._polling: bool = False

    @property
    def custody_source(self) -> Optional[Any]:
        return self._resolver.custody_source

    def set_custody_source(self, source: Optional[Any]) -> None:
        self._resolver.set_custody_source(source)

    def record_result(self, res: LaunchResult) -> LaunchResult:
        self._last_result = res
        self._resolver.set_fallback_result(res)
        return res

    _record_result = record_result

    def snapshot(self) -> LauncherCustodySnapshot:
        if self._polling:
            return self.known_snapshot
        self._polling = True
        try:
            self._resolver.set_fallback_result(self._last_result)
            return self._resolver.snapshot()
        finally:
            self._polling = False

    take_snapshot = snapshot

    @property
    def pending_child(self) -> Optional[Any]:
        return self.snapshot().pending_child

    child_handle = pending_child

    @property
    def pending_pidfd(self) -> Optional[int]:
        return self.snapshot().pending_pidfd

    pidfd = pending_pidfd

    @property
    def last_result(self) -> Optional[LaunchResult]:
        res = self.snapshot().last_result
        return res if res is not None else self._last_result

    @property
    def known_child(self) -> Optional[Any]:
        return self._resolver.known_child

    @property
    def known_pidfd(self) -> Optional[int]:
        return self._resolver.known_pidfd

    @property
    def known_last_result(self) -> Optional[LaunchResult]:
        res = self._resolver.known_last_result
        return res if res is not None else self._last_result

    @property
    def known_snapshot(self) -> LauncherCustodySnapshot:
        return LauncherCustodySnapshot(
            self.known_child, self.known_pidfd, self.known_last_result
        )

    def current_resources(self) -> tuple[Optional[Any], Optional[int], Optional[LaunchResult]]:
        try:
            snap = self.snapshot()
            auth = snap.last_result
            return (
                snap.pending_child,
                snap.pending_pidfd,
                auth if auth is not None else self._last_result,
            )
        except (KeyboardInterrupt, SystemExit):
            raise
        except BaseException:
            return self.known_child, self.known_pidfd, self.known_last_result

    _current_resources = current_resources

    def safe_failure(self, exc: BaseException) -> Optional[LaunchResult]:
        try:
            auth = self.known_last_result
            last = auth if auth is not None else self._last_result
            return safe_exception_failure(
                exc, child=self.known_child, pidfd=self.known_pidfd, last=last
            )
        except BaseException:
            return None

    _safe_failure = safe_failure


LauncherAdmissionCustodyAdapter = LauncherAdmissionAdapter
