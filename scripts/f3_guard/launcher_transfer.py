"""Fail-closed emulator process transfer and anchored pidfd binding."""

from __future__ import annotations

import os
from typing import Any, Callable, List, Optional, Tuple

from scripts.f3_guard.launcher_pidfd import VerifiedPidfdContext
from scripts.f3_guard.launcher_preflight import (
    DEFAULT_EMULATOR_PATH, LaunchResult, LauncherPreflightHelper,
    TARGET_AVD, TARGET_PORT, TARGET_UID,
)
from scripts.f3_guard.proc_identity import verify_process_identity
from scripts.f3_guard.types import ProcessIdentity

_CANONICAL_LAUNCH_RESULT = LaunchResult


class LauncherTransferHelper:
    """Fail-closed emulator process transfer and post-spawn identity verification.

    Retains custody of _pending_child, _pending_pidfd, and _last_result without silent reset.
    Parent assembly owns single-use lifecycle and pending process/fd cleanup.
    """

    def __init__(
        self, controller: Optional[Any] = None, target_uid: int = TARGET_UID,
        avd_name: str = TARGET_AVD, port: int = TARGET_PORT,
        emulator_binary: str = DEFAULT_EMULATOR_PATH,
        identity_verifier: Optional[Callable[[ProcessIdentity, Any], Tuple[bool, str]]] = None,
    ) -> None:
        self._controller = controller
        self._target_uid = target_uid
        self._avd_name = avd_name
        self._port = port
        self._binary = emulator_binary
        self._identity_verifier = verify_process_identity if identity_verifier is None else identity_verifier
        self._pending_child: Optional[Any] = None
        self._pending_pidfd: Optional[int] = None
        self._last_result: Optional[LaunchResult] = None

    @property
    def pending_child(self) -> Optional[Any]: return self._pending_child
    @property
    def pending_pidfd(self) -> Optional[int]: return self._pending_pidfd
    @property
    def last_result(self) -> Optional[LaunchResult]: return self._last_result

    def prime(self, child: Any, pidfd: Optional[int] = None) -> None:
        """Pre-execution seed pending resources for custody tracking prior to stage execution."""
        self._pending_child = child
        self._pending_pidfd = pidfd

    def get_frozen_argv(self) -> List[str]:
        return LauncherPreflightHelper(
            avd_name=self._avd_name, port=self._port, emulator_binary=self._binary
        ).get_frozen_argv()

    def transfer(self, context: VerifiedPidfdContext) -> LaunchResult:
        child = context.child_handle
        child_pid = context.child_pid
        start_ticks = context.start_ticks
        pidfd = context.pidfd
        self._pending_child = child
        self._pending_pidfd = pidfd
        candidate_identity: Optional[ProcessIdentity] = None
        bound_accepted = False

        def _fail(err: str, fd: Optional[int] = None) -> LaunchResult:
            self._last_result = LaunchResult(
                success=False, pidfd=fd, child_handle=child,
                pending_cleanup=True, error=err
            )
            return self._last_result

        try:
            cmdline = self._controller.get_cmdline(child_pid)
            if not cmdline:
                return _fail("Child cmdline empty or unreadable", pidfd)

            if not hasattr(self._controller, "get_exe_path"):
                return _fail("Controller missing get_exe_path reader", pidfd)
            try:
                exe_path = self._controller.get_exe_path(child_pid)
            except Exception as e:
                return _fail(f"Post-spawn exe inspection failed: {type(e).__name__}", pidfd)
            if not isinstance(exe_path, str) or not exe_path or exe_path != self._binary:
                return _fail(f"Post-spawn identity failure: Child actual exe '{exe_path}' mismatch", pidfd)

            if tuple(cmdline) != tuple(self.get_frozen_argv()):
                return _fail("Post-spawn identity verification failure: Child cmdline has extra, missing, or mismatched arguments", pidfd)

            candidate_identity = ProcessIdentity(
                pid=child_pid, start_ticks=start_ticks, uid=self._target_uid,
                avd=self._avd_name, port=self._port, binary_name=os.path.basename(self._binary),
                is_direct_child=True
            )

            id_ok, id_msg = self._identity_verifier(candidate_identity, self._controller)
            if not id_ok:
                res = LaunchResult(
                    success=False, identity=candidate_identity, pidfd=pidfd,
                    child_handle=child, pending_cleanup=True,
                    error=f"Post-spawn identity verification failed: {id_msg}"
                )
                self._last_result = res
                return res

            if hasattr(self._controller, "bind_anchored_pidfd"):
                bound_ok = self._controller.bind_anchored_pidfd(child_pid, start_ticks, pidfd)
                if not bound_ok:
                    return _fail("Controller bind_anchored_pidfd rejected duplicate/conflict", pidfd)
                bound_accepted = True
                self._pending_pidfd = None

            res = LaunchResult(
                success=True, identity=candidate_identity, pidfd=None,
                child_handle=child, is_direct_child=True, pending_cleanup=False,
                transferred=True
            )
            self._last_result = res
            return res
        except BaseException as ex:
            if bound_accepted:
                try:
                    self._pending_pidfd = None
                except BaseException:
                    object.__setattr__(self, "_pending_pidfd", None)
            res = None
            try:
                err_msg = f"Post-spawn exception: {type(ex).__name__}"
                if bound_accepted:
                    res_kwargs = dict(
                        success=False, identity=candidate_identity, pidfd=None,
                        child_handle=self._pending_child, pending_cleanup=True,
                        transferred=True, error=err_msg
                    )
                else:
                    res_kwargs = dict(
                        success=False, pidfd=self._pending_pidfd,
                        child_handle=self._pending_child, pending_cleanup=True,
                        error=err_msg
                    )
                try:
                    res = LaunchResult(**res_kwargs)
                except BaseException:
                    res = _CANONICAL_LAUNCH_RESULT(**res_kwargs)
                try:
                    self._last_result = res
                except BaseException:
                    object.__setattr__(self, "_last_result", res)
            except BaseException:
                pass
            if res is None:
                raise
            if isinstance(ex, (KeyboardInterrupt, SystemExit)):
                raise
            return res
