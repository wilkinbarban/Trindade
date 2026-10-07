"""Filesystem /proc interaction helper for process inspection.

Provides ProcInspectionHelper extracting process attributes (executable link,
stat fields, UID, cmdline, fdinfo pid, process state) directly from Linux
/proc pseudo-filesystem with strict fail-closed error handling.
"""

from __future__ import annotations

import os
import re
from typing import List, Optional, Union

INSPECTION_ERROR = object()
_INSPECTION_ERROR = INSPECTION_ERROR

__all__ = [
    "INSPECTION_ERROR",
    "ProcInspectionHelper",
    "_INSPECTION_ERROR",
]


class ProcInspectionHelper:
    """Filesystem /proc inspection helper for process metadata and state."""

    def get_exe_path(self, pid: int) -> Optional[str]:
        """Read target process executable symlink destination."""
        try:
            return os.readlink(f"/proc/{pid}/exe")
        except Exception:
            return None

    def get_ppid(self, pid: int) -> Optional[int]:
        """Parse PPID (parent process ID) from /proc/{pid}/stat."""
        try:
            with open(f"/proc/{pid}/stat", "r") as f:
                content = f.read()
            rparen = content.rfind(")")
            return int(content[rparen + 2:].split()[1]) if rparen != -1 else None
        except Exception:
            return None

    def get_start_ticks(self, pid: int) -> Union[int, None, object]:
        """Return start_ticks (int), None if process confirmed absent, or
        _INSPECTION_ERROR if the reading failed for other reasons."""
        try:
            with open(f"/proc/{pid}/stat", "r") as f:
                content = f.read()
            rparen = content.rfind(")")
            if rparen == -1:
                return _INSPECTION_ERROR
            fields = content[rparen + 2:].split()
            return int(fields[19])
        except (FileNotFoundError, ProcessLookupError):
            return None
        except Exception:
            return _INSPECTION_ERROR

    def get_uid(self, pid: int) -> Optional[int]:
        """Read real UID owner from /proc/{pid} filesystem metadata."""
        try:
            return os.stat(f"/proc/{pid}").st_uid
        except (FileNotFoundError, ProcessLookupError):
            return None
        except Exception:
            return None

    def get_cmdline(self, pid: int) -> List[str]:
        """Read and decode NUL-delimited command-line arguments."""
        try:
            with open(f"/proc/{pid}/cmdline", "rb") as f:
                content = f.read()
            return [arg.decode("utf-8", errors="replace") for arg in content.split(b"\x00") if arg]
        except (FileNotFoundError, ProcessLookupError):
            return []
        except Exception:
            return []

    def get_fdinfo_pid(self, fd: int) -> Optional[int]:
        """Extract Pid from /proc/self/fdinfo/{fd} strictly and securely."""
        if not (isinstance(fd, int) and type(fd) is not bool and fd >= 0):
            return None
        try:
            with open(f"/proc/self/fdinfo/{fd}", "r") as f:
                content = f.read(8193)
            if len(content) > 8192 or not content.endswith("\n"):
                return None
            found_pid = None
            for line in content.splitlines():
                if line.startswith("Pid:"):
                    if found_pid is not None:
                        return None
                    m = re.fullmatch(r"Pid:[ \t]+([0-9]+)", line)
                    if not m:
                        return None
                    val = int(m.group(1))
                    if val <= 0:
                        return None
                    found_pid = val
            return found_pid
        except Exception:
            return None

    def get_process_state(self, pid: int) -> Optional[str]:
        """Returns process state character: 'R', 'S', 'Z' (zombie), 'INSPECTION_ERROR', or None if gone."""
        try:
            with open(f"/proc/{pid}/status", "r") as f:
                for line in f:
                    if line.startswith("State:"):
                        parts = line.split()
                        if len(parts) >= 2:
                            return parts[1]
            return "INSPECTION_ERROR"
        except (FileNotFoundError, ProcessLookupError):
            return None
        except Exception:
            return "INSPECTION_ERROR"
