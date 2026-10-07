"""Linux pidfd OS operations and process reaping helper.

Provides PidfdOpsHelper for polling descriptor liveness, dispatching signals,
opening pidfds, and reaping child processes.
"""

from __future__ import annotations

import os
import select
import signal
from typing import Optional

__all__ = ["PidfdOpsHelper"]


class PidfdOpsHelper:
    """Helper managing direct Linux pidfd operations, signals, and process reaping."""

    def is_pidfd_alive(self, fd: int) -> bool:
        if not (isinstance(fd, int) and type(fd) is not bool and fd >= 0):
            return False
        try:
            import select
            p = select.poll()
            flags = select.POLLIN | select.POLLHUP | select.POLLERR | getattr(select, "POLLNVAL", 0)
            p.register(fd, flags)
            return len(p.poll(0)) == 0
        except Exception:
            return False

    def send_signal(self, pid: int, sig: int) -> None:
        os.kill(pid, sig)

    def pidfd_open(self, pid: int) -> Optional[int]:
        """Attempt to open Linux pidfd for race-free signaling if supported."""
        if hasattr(os, "pidfd_open"):
            try:
                return os.pidfd_open(pid, 0)
            except Exception:
                return None
        return None

    def pidfd_send_signal(self, pidfd: int, sig: int) -> bool:
        """Send signal via Linux pidfd."""
        if hasattr(signal, "pidfd_send_signal"):
            try:
                signal.pidfd_send_signal(pidfd, sig)
                return True
            except Exception:
                return False
        return False

    def try_reap(self, pid: int) -> Optional[bool]:
        """Attempt waitpid non-blocking if direct child.

        Returns True if successfully reaped, False if still pending (zombie
        not yet reapable), None if reap failed or pid is not a child.
        """
        if type(pid) is not int or pid <= 0:
            return None
        try:
            res_pid, _ = os.waitpid(pid, os.WNOHANG)
            if res_pid == pid:
                return True
            # res_pid == 0 means child exists but hasn't changed state yet
            return False
        except ChildProcessError:
            # "No child processes" — already reaped or not our child
            return None
        except Exception:
            return None
