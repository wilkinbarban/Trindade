"""Authoritative admission custody protocol for emulator launcher."""

from __future__ import annotations

from typing import Any, Optional

from scripts.f3_guard.launcher_custody import LauncherCustodySnapshot
from scripts.f3_guard.launcher_preflight import LaunchResult

__all__ = ["AdmissionCustodyProtocol", "UNKNOWN"]

UNKNOWN = object()


class AdmissionCustodyProtocol:
    """Standalone custody adapter resolving active custody across dynamic sources.

    - None confirms absence and clears custody.
    - UNKNOWN or reading failures retain known custody for that helper.
    - Dynamic sources are resolved once per snapshot.
    """

    def __init__(
        self,
        custody_source: Optional[Any] = None,
        fallback_result: Optional[LaunchResult] = None,
    ) -> None:
        self.custody_source: Optional[Any] = custody_source
        self.fallback_result: Optional[LaunchResult] = fallback_result
        self._known_helper: Optional[Any] = None
        self._known: dict[str, Any] = {}
        self._cache: Optional[LauncherCustodySnapshot] = None

    def set_custody_source(self, source: Optional[Any]) -> None:
        self.custody_source = source

    def set_fallback_result(self, result: Optional[LaunchResult]) -> None:
        self.fallback_result = result

    def _resolve_source(self) -> Any:
        src = self.custody_source
        if callable(src):
            try:
                return src()
            except (KeyboardInterrupt, SystemExit):
                raise
            except BaseException:
                return UNKNOWN
        return src

    def _read_snap(self, helper: Any) -> Optional[LauncherCustodySnapshot]:
        try:
            s = getattr(helper, "last_snapshot", None)
            return s if isinstance(s, LauncherCustodySnapshot) else None
        except (KeyboardInterrupt, SystemExit):
            raise
        except BaseException:
            return None

    def _read_field(
        self, helper: Any, attr: str, snap: Optional[LauncherCustodySnapshot]
    ) -> Any:
        try:
            val = getattr(helper, attr, UNKNOWN)
        except (KeyboardInterrupt, SystemExit):
            raise
        except BaseException:
            val = UNKNOWN

        if val is not UNKNOWN:
            self._known[attr] = val
            return val
        if attr in self._known:
            return self._known[attr]
        snap_val = getattr(snap, attr, None) if snap is not None else None
        if snap_val is not None:
            self._known[attr] = snap_val
            return snap_val
        return None

    def snapshot(self) -> LauncherCustodySnapshot:
        helper = self._resolve_source()
        if helper is UNKNOWN:
            return self._cache if self._cache is not None else LauncherCustodySnapshot()

        if helper is None or helper is not self._known_helper:
            self._known_helper = helper
            self._known.clear()
            self._cache = None

        if helper is None:
            fb = self.fallback_result if self.custody_source is None else None
            if fb is not None:
                return LauncherCustodySnapshot(fb.child_handle, fb.pidfd, fb)
            return LauncherCustodySnapshot()

        snap = self._read_snap(helper)
        self._cache = LauncherCustodySnapshot(
            self._read_field(helper, "pending_child", snap),
            self._read_field(helper, "pending_pidfd", snap),
            self._read_field(helper, "last_result", snap),
        )
        return self._cache

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
        return self.snapshot().last_result
