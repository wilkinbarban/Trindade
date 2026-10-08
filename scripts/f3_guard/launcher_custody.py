"""Normal-return launcher custody adapter and active-stage snapshot tracking."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Optional

from scripts.f3_guard.launcher_preflight import LaunchResult

__all__ = ["CustodySnapshot", "LauncherCustodySnapshot", "LauncherCustodyTracker"]

_DEFAULT = object()


@dataclass(frozen=True)
class LauncherCustodySnapshot:
    """Immutable descriptor of active launcher custody state and outcome."""
    pending_child: Optional[Any] = None
    pending_pidfd: Optional[int] = None
    last_result: Optional[LaunchResult] = None

    @property
    def child_handle(self) -> Optional[Any]: return self.pending_child
    @property
    def pidfd(self) -> Optional[int]: return self.pending_pidfd


CustodySnapshot = LauncherCustodySnapshot


class LauncherCustodyTracker:
    """Cohesive custody adapter tracking active helper resources and results.

    Tracks normal-return snapshots from public active helper properties.
    Not a resource owner: does not close, kill, wait, reap, spawn, or deliver
    process ownership. Does not promise exception custody; exception/recovery
    capture is excluded (unit 9c1b). Reads exclusively public helper properties
    with no fallback across helpers.
    """

    def __init__(self, active_helper: Optional[Any] = None) -> None:
        self._active_helper: Optional[Any] = active_helper
        self._last_snapshot: Optional[LauncherCustodySnapshot] = None

    @property
    def active_helper(self) -> Optional[Any]: return self._active_helper
    @property
    def last_snapshot(self) -> Optional[LauncherCustodySnapshot]: return self._last_snapshot
    @property
    def last_result(self) -> Optional[LaunchResult]:
        return self._last_snapshot.last_result if self._last_snapshot is not None else None
    @property
    def pending_child(self) -> Optional[Any]:
        return self._last_snapshot.pending_child if self._last_snapshot is not None else None
    @property
    def pending_pidfd(self) -> Optional[int]:
        return self._last_snapshot.pending_pidfd if self._last_snapshot is not None else None

    def set_active_helper(self, helper: Optional[Any]) -> None:
        """Explicitly select active helper without fallback to previous state."""
        self._active_helper = helper

    def snapshot(self, helper: Any = _DEFAULT) -> LauncherCustodySnapshot:
        """Capture normal-return custody snapshot from selected active helper.

        Propagates helper property faults directly. Preserves existing tracker
        state atomically if property inspection raises.
        """
        target = self._active_helper if helper is _DEFAULT else helper
        if target is None:
            snap = LauncherCustodySnapshot()
            self._active_helper = target
            self._last_snapshot = snap
            return snap

        child = getattr(target, "pending_child", None)
        raw_fd = getattr(target, "pending_pidfd", None)
        res = getattr(target, "last_result", None)

        snap = LauncherCustodySnapshot(
            pending_child=child,
            pending_pidfd=raw_fd,
            last_result=res,
        )
        self._active_helper = target
        self._last_snapshot = snap
        return snap

    take_snapshot = snapshot
