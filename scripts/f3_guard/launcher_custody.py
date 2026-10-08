"""Normal-return and exception recovery launcher custody adapter."""

from __future__ import annotations

from contextlib import contextmanager
from dataclasses import dataclass
from typing import Any, Iterator, Optional

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
        self._snapshot_helper: Optional[Any] = None
        self._last_exception: Optional[BaseException] = None

    @property
    def active_helper(self) -> Optional[Any]: return self._active_helper
    @property
    def last_snapshot(self) -> Optional[LauncherCustodySnapshot]: return self._last_snapshot
    @property
    def last_exception(self) -> Optional[BaseException]: return self._last_exception
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
            self._snapshot_helper = target
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
        self._snapshot_helper = target
        return snap

    take_snapshot = snapshot

    def record_exception(
        self, exc: BaseException, helper: Any = _DEFAULT
    ) -> LauncherCustodySnapshot:
        """Progressively record exception and snapshot active helper with fail-closed recovery."""
        self._last_exception = exc
        target = self._active_helper if helper is _DEFAULT else helper
        same_helper = (
            target is not None
            and target is self._snapshot_helper
            and self._last_snapshot is not None
        )

        child = None
        raw_fd = None
        res = None
        sec_errs: list[str] = []

        if target is not None:
            try:
                child = getattr(target, "pending_child", None)
            except BaseException as g_exc:
                sec_errs.append(f"pending_child: {type(g_exc).__name__}")
                if same_helper:
                    child = self._last_snapshot.pending_child

            try:
                raw_fd = getattr(target, "pending_pidfd", None)
            except BaseException as g_exc:
                sec_errs.append(f"pending_pidfd: {type(g_exc).__name__}")
                if same_helper:
                    raw_fd = self._last_snapshot.pending_pidfd

            try:
                res = getattr(target, "last_result", None)
            except BaseException as g_exc:
                sec_errs.append(f"last_result: {type(g_exc).__name__}")
                if same_helper:
                    res = self._last_snapshot.last_result

        diag = f"primary: {type(exc).__name__}"
        if sec_errs:
            diag = f"{diag}; secondary: {', '.join(sec_errs)}"

        out_child = child
        out_fd = raw_fd
        needs_cleanup = out_child is not None or out_fd is not None

        ident = None
        direct = False
        trans = False
        err_msg = diag

        binding_matches = (
            isinstance(res, LaunchResult)
            and (out_child is not None or out_fd is not None)
            and res.child_handle is out_child
            and res.pidfd is out_fd
        )

        if binding_matches:
            ident = res.identity
            direct = res.is_direct_child
            trans = res.transferred

        if (
            isinstance(res, LaunchResult)
            and not res.success
            and binding_matches
            and res.pending_cleanup == needs_cleanup
            and not sec_errs
            and isinstance(res.error, str)
            and diag in res.error
        ):
            final_res = res
        else:
            if isinstance(res, LaunchResult) and isinstance(res.error, str) and res.error:
                err_msg = f"{res.error}; {diag}"
            final_res = LaunchResult(
                success=False,
                identity=ident,
                pidfd=out_fd,
                child_handle=out_child,
                error=err_msg,
                is_direct_child=direct,
                pending_cleanup=needs_cleanup,
                transferred=trans,
            )

        snap = LauncherCustodySnapshot(
            pending_child=out_child,
            pending_pidfd=out_fd,
            last_result=final_res,
        )
        self._active_helper = target
        self._last_snapshot = snap
        self._snapshot_helper = target
        return snap

    @contextmanager
    def catch_and_record(self, helper: Any = _DEFAULT) -> Iterator[None]:
        """Record custody snapshot on exception and rethrow exact same instance."""
        try:
            yield
        except BaseException as exc:
            try:
                self.record_exception(exc, helper=helper)
            except BaseException:
                # Catch internal BaseException to guarantee original exception identity and traceback are re-raised
                pass
            raise
