"""Fail-closed 3-stage emulator launcher pipeline executor."""

from __future__ import annotations

from typing import Any, Callable, List, Optional, Tuple

from scripts.f3_guard.launcher_custody import LauncherCustodySnapshot, LauncherCustodyTracker
from scripts.f3_guard.launcher_pidfd import LauncherPidfdHelper
from scripts.f3_guard.launcher_preflight import (
    DEFAULT_EMULATOR_PATH, TARGET_AVD, TARGET_PORT, TARGET_UID, LaunchResult,
)
from scripts.f3_guard.launcher_spawn import LauncherSpawnHelper
from scripts.f3_guard.launcher_transfer import LauncherTransferHelper
from scripts.f3_guard.types import ProcessIdentity

__all__ = ["LauncherPipelineExecutor"]

_CANONICAL_LAUNCH_RESULT = LaunchResult


class LauncherPipelineExecutor:
    """Fail-closed 3-stage pipeline executor across spawn, pidfd, and transfer stages."""

    def __init__(
        self, spawner: Optional[Callable[[List[str]], Any]] = None,
        controller: Optional[Any] = None, target_uid: int = TARGET_UID,
        avd_name: str = TARGET_AVD, port: int = TARGET_PORT,
        emulator_binary: str = DEFAULT_EMULATOR_PATH,
        identity_verifier: Optional[Callable[[ProcessIdentity, Any], Tuple[bool, str]]] = None,
        spawn_helper: Optional[Any] = None, pidfd_helper: Optional[Any] = None,
        transfer_helper: Optional[Any] = None,
        custody_tracker: Optional[LauncherCustodyTracker] = None,
    ) -> None:
        self.spawner, self.controller = spawner, controller
        self.target_uid, self.avd_name = target_uid, avd_name
        self.port, self.emulator_binary = port, emulator_binary
        self.identity_verifier = identity_verifier
        self.spawn_helper, self.pidfd_helper = spawn_helper, pidfd_helper
        self.transfer_helper = transfer_helper
        self._tracker = custody_tracker if custody_tracker is not None else LauncherCustodyTracker()

    @property
    def custody_tracker(self) -> LauncherCustodyTracker: return self._tracker
    @property
    def pending_child(self) -> Optional[Any]: return self._tracker.pending_child
    @property
    def pending_pidfd(self) -> Optional[int]: return self._tracker.pending_pidfd
    @property
    def last_result(self) -> Optional[LaunchResult]: return self._tracker.last_result
    @property
    def active_helper(self) -> Optional[Any]: return self._tracker.active_helper
    @property
    def last_snapshot(self) -> Optional[LauncherCustodySnapshot]: return self._tracker.last_snapshot
    @property
    def last_exception(self) -> Optional[BaseException]: return self._tracker.last_exception

    @staticmethod
    def _safe_result(
        success: bool, error: str, child: Optional[Any] = None, pidfd: Optional[int] = None,
    ) -> LaunchResult:
        kw = dict(
            success=success, child_handle=child, pidfd=pidfd,
            pending_cleanup=child is not None or pidfd is not None, error=error,
        )
        try: return LaunchResult(**kw)
        except BaseException: return _CANONICAL_LAUNCH_RESULT(**kw)

    def execute(self, argv: List[str]) -> LaunchResult:
        s_h = self.spawn_helper if self.spawn_helper is not None else LauncherSpawnHelper(
            spawner=self.spawner, controller=self.controller, target_uid=self.target_uid)
        p_h = self.pidfd_helper if self.pidfd_helper is not None else LauncherPidfdHelper(
            controller=self.controller, target_uid=self.target_uid)
        t_h = self.transfer_helper if self.transfer_helper is not None else LauncherTransferHelper(
            controller=self.controller, target_uid=self.target_uid, avd_name=self.avd_name,
            port=self.port, emulator_binary=self.emulator_binary, identity_verifier=self.identity_verifier)

        tr = self._tracker
        tr.set_active_helper(s_h)

        try:
            s_ctx, s_res = s_h.spawn_and_verify(argv)
            if s_ctx is None:
                tr.snapshot()
                c = getattr(s_h, "pending_child", None)
                return s_res if s_res is not None else self._safe_result(
                    False, "Stage 1 spawn returned invalid context", c)

            p_h.prime(s_ctx.child_handle, None)
            tr.set_active_helper(p_h)
            tr.snapshot()

            p_ctx, p_res = p_h.acquire_and_verify(s_ctx)
            if p_ctx is None:
                tr.snapshot()
                c, f = getattr(p_h, "pending_child", None), getattr(p_h, "pending_pidfd", None)
                return p_res if p_res is not None else self._safe_result(
                    False, "Stage 2 pidfd acquisition returned invalid context", c, f,
                )

            t_h.prime(p_ctx.child_handle, p_ctx.pidfd)
            tr.set_active_helper(t_h)
            tr.snapshot()

            t_res = t_h.transfer(p_ctx)
            tr.snapshot()
            if t_res is not None:
                return t_res
            c, f = getattr(t_h, "pending_child", None), getattr(t_h, "pending_pidfd", None)
            return self._safe_result(False, "Stage 3 transfer returned invalid result", c, f)
        except BaseException as exc:
            try: tr.record_exception(exc)
            except BaseException: pass
            if isinstance(exc, (KeyboardInterrupt, SystemExit)): raise
            res = self.last_result
            if res is None:
                try:
                    res = self._safe_result(
                        False, f"primary: {type(exc).__name__}", self.pending_child, self.pending_pidfd,
                    )
                except BaseException: res = None
            if res is None: raise
            return res
