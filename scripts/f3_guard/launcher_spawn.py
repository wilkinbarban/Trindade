"""Fail-closed emulator process spawning and post-spawn pre-pidfd verification."""

from __future__ import annotations

from dataclasses import dataclass
import os
from typing import Any, Callable, List, Optional, Tuple

from scripts.f3_guard.launcher_preflight import LaunchResult, TARGET_UID


@dataclass(frozen=True)
class SpawnedChildContext:
    """Immutable pre-pidfd descriptor of verified spawned emulator child."""
    child_handle: Any
    child_pid: int
    start_ticks: int
    parent_ticks: int
    owner_pid: int


class LauncherSpawnHelper:
    """Fail-closed spawn execution and immediate pre-pidfd invariant validation.

    Retains custody of _pending_child and _last_result without silent reset.
    Parent assembly owns single-use lifecycle and pending process cleanup.
    """

    def __init__(
        self, spawner: Optional[Callable[[List[str]], Any]] = None,
        controller: Optional[Any] = None, target_uid: int = TARGET_UID,
    ) -> None:
        self._spawner = spawner
        self._controller = controller
        self._target_uid = target_uid
        self._pending_child: Optional[Any] = None
        self._last_result: Optional[LaunchResult] = None

    @property
    def pending_child(self) -> Optional[Any]:
        return self._pending_child

    @property
    def last_result(self) -> Optional[LaunchResult]:
        return self._last_result

    def spawn_and_verify(self, argv: List[str]) -> Tuple[Optional[SpawnedChildContext], Optional[LaunchResult]]:
        if self._spawner is None:
            res = LaunchResult(success=False, error="Real emulator spawner not configured in offline mode")
            self._last_result = res
            return None, res

        try:
            child = self._spawner(argv)
        except (KeyboardInterrupt, SystemExit):
            raise
        except Exception as e:
            res = LaunchResult(success=False, error=f"Spawner execution failed: {type(e).__name__}")
            self._last_result = res
            return None, res

        self._pending_child = child

        def _fail(err: str, fd: Optional[int] = None) -> LaunchResult:
            self._last_result = LaunchResult(
                success=False, pidfd=fd, child_handle=child, pending_cleanup=True, error=err
            )
            return self._last_result

        child_pid = getattr(child, "pid", None)
        if not (isinstance(child_pid, int) and type(child_pid) is not bool and child_pid > 0):
            return None, _fail("Spawner did not provide valid positive child PID")

        if hasattr(child, "poll"):
            try:
                poll_val = child.poll()
                if poll_val is not None:
                    return None, _fail(f"Child already exited: {poll_val}")
            except (KeyboardInterrupt, SystemExit):
                raise
            except Exception as e:
                return None, _fail(f"Child poll error: {type(e).__name__}")

        owner_pid = os.getpid()
        if not (isinstance(owner_pid, int) and type(owner_pid) is not bool and owner_pid > 0):
            return None, _fail("Owner PID invalid")

        def _ticks(pid: int) -> Optional[int]:
            if not hasattr(self._controller, "get_start_ticks"):
                return None
            try:
                val = self._controller.get_start_ticks(pid)
                return val if isinstance(val, int) and type(val) is not bool and val > 0 else None
            except (KeyboardInterrupt, SystemExit):
                raise
            except Exception:
                return None

        parent_ticks = _ticks(owner_pid)
        if parent_ticks is None:
            return None, _fail("Could not read valid positive start_ticks for parent")

        if not hasattr(self._controller, "get_ppid"):
            return None, _fail("Controller missing get_ppid reader")
        try:
            ppid = self._controller.get_ppid(child_pid)
        except (KeyboardInterrupt, SystemExit):
            raise
        except Exception as e:
            return None, _fail(f"PPID check error: {type(e).__name__}")
        if not (isinstance(ppid, int) and type(ppid) is not bool and ppid == owner_pid):
            return None, _fail("Child parent PID mismatch or unknown")

        start_ticks = _ticks(child_pid)
        if start_ticks is None:
            return None, _fail("Could not read valid positive start_ticks for child before open")

        if not hasattr(self._controller, "get_uid"):
            return None, _fail("Controller missing get_uid reader")
        try:
            uid = self._controller.get_uid(child_pid)
        except (KeyboardInterrupt, SystemExit):
            raise
        except Exception as e:
            return None, _fail(f"Child UID read error: {type(e).__name__}")
        if type(uid) is not int or uid != self._target_uid:
            return None, _fail(f"Child UID {uid!r} != required {self._target_uid}")

        context = SpawnedChildContext(
            child_handle=child, child_pid=child_pid,
            start_ticks=start_ticks, parent_ticks=parent_ticks, owner_pid=owner_pid,
        )
        return context, None
