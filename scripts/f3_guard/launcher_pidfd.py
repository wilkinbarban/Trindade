"""Fail-closed emulator pidfd acquisition and post-open verification."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Optional, Tuple

from scripts.f3_guard.launcher_preflight import LaunchResult, TARGET_UID
from scripts.f3_guard.launcher_spawn import SpawnedChildContext


@dataclass(frozen=True)
class VerifiedPidfdContext:
    """Immutable verified descriptor retaining upstream context plus acquired pidfd."""
    spawn_context: SpawnedChildContext
    pidfd: int

    @property
    def context(self) -> SpawnedChildContext: return self.spawn_context
    @property
    def child_handle(self) -> Any: return self.spawn_context.child_handle
    @property
    def child_pid(self) -> int: return self.spawn_context.child_pid
    @property
    def start_ticks(self) -> int: return self.spawn_context.start_ticks
    @property
    def parent_ticks(self) -> int: return self.spawn_context.parent_ticks
    @property
    def owner_pid(self) -> int: return self.spawn_context.owner_pid


class LauncherPidfdHelper:
    """Fail-closed pidfd acquisition and immediate post-open invariant validation.

    Retains custody of _pending_child, _pending_pidfd, and _last_result without silent reset.
    Parent assembly owns single-use lifecycle and pending process/fd cleanup.
    """

    def __init__(self, controller: Optional[Any] = None, target_uid: int = TARGET_UID) -> None:
        self._controller = controller
        self._target_uid = target_uid
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

    def acquire_and_verify(
        self, context: SpawnedChildContext
    ) -> Tuple[Optional[VerifiedPidfdContext], Optional[LaunchResult]]:
        child, child_pid, start_ticks = context.child_handle, context.child_pid, context.start_ticks
        parent_ticks, owner_pid = context.parent_ticks, context.owner_pid
        self._pending_child = child

        def _fail(err: str, fd: Optional[int] = None) -> Tuple[None, LaunchResult]:
            self._last_result = LaunchResult(
                success=False, pidfd=fd, child_handle=self._pending_child,
                pending_cleanup=True, error=err
            )
            return None, self._last_result

        try:
            pidfd = self._controller.pidfd_open(child_pid)
            if not (isinstance(pidfd, int) and type(pidfd) is not bool and pidfd >= 0):
                return _fail(f"Post-spawn pidfd_open failed or invalid fd ({pidfd})")
            self._pending_pidfd = pidfd

            if not hasattr(self._controller, "get_fdinfo_pid"):
                return _fail("Controller missing get_fdinfo_pid reader", pidfd)
            try: fdinfo_pid = self._controller.get_fdinfo_pid(pidfd)
            except Exception as e: return _fail(f"fdinfo check error: {e}", pidfd)
            if not (isinstance(fdinfo_pid, int) and type(fdinfo_pid) is not bool and fdinfo_pid == child_pid):
                return _fail(f"pidfd fdinfo target PID mismatch or invalid ({fdinfo_pid} != {child_pid})", pidfd)

            if not (hasattr(self._controller, "is_pidfd_alive") and callable(self._controller.is_pidfd_alive)):
                return _fail("Controller missing mandatory is_pidfd_alive callable", pidfd)
            try: alive = self._controller.is_pidfd_alive(pidfd)
            except Exception as e: return _fail(f"pidfd liveness check error: {e}", pidfd)
            if alive is not True:
                return _fail(f"pidfd liveness verification failed (alive={alive!r})", pidfd)

            try: recheck_child_ticks = self._controller.get_start_ticks(child_pid)
            except Exception as e: return _fail(f"Child start_ticks recheck error: {e}", pidfd)
            if type(recheck_child_ticks) is not int or recheck_child_ticks <= 0 or recheck_child_ticks != start_ticks:
                return _fail("Child start_ticks shifted or invalid on recheck", pidfd)

            try: recheck_uid = self._controller.get_uid(child_pid) if hasattr(self._controller, "get_uid") else None
            except Exception as e: return _fail(f"Child UID recheck error: {e}", pidfd)
            if type(recheck_uid) is not int or recheck_uid != self._target_uid:
                return _fail("Child UID mismatch or invalid on recheck", pidfd)

            try: recheck_ppid = self._controller.get_ppid(child_pid) if hasattr(self._controller, "get_ppid") else None
            except Exception as e: return _fail(f"PPID recheck error: {e}", pidfd)
            if type(recheck_ppid) is not int or recheck_ppid <= 0 or recheck_ppid != owner_pid:
                return _fail("Child parent PID mismatch or invalid on recheck", pidfd)

            try: recheck_parent_ticks = self._controller.get_start_ticks(owner_pid)
            except Exception as e: return _fail(f"Parent start_ticks recheck error: {e}", pidfd)
            if type(recheck_parent_ticks) is not int or recheck_parent_ticks <= 0 or recheck_parent_ticks != parent_ticks:
                return _fail("Parent start_ticks shifted or invalid on recheck", pidfd)

            if hasattr(child, "poll"):
                try: poll_val = child.poll()
                except Exception as e: return _fail(f"Child poll error on recheck: {e}", pidfd)
                if poll_val is not None:
                    return _fail(f"Child exited during launch verification: {poll_val}", pidfd)

            return VerifiedPidfdContext(spawn_context=context, pidfd=pidfd), None
        except BaseException as ex:
            res = LaunchResult(
                success=False, pidfd=self._pending_pidfd, child_handle=self._pending_child,
                pending_cleanup=True, error=f"Post-spawn exception: {ex}"
            )
            self._last_result = res
            if isinstance(ex, (KeyboardInterrupt, SystemExit)):
                raise
            return None, res
