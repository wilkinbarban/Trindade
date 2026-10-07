"""Strict process identity verification and argv token checking."""

from __future__ import annotations

import os
from typing import List, Tuple

from scripts.f3_guard.controller import ProcessController
from scripts.f3_guard.proc_inspection import _INSPECTION_ERROR
from scripts.f3_guard.types import ProcessIdentity

TARGET_UID: int = 1000

__all__ = [
    "TARGET_UID",
    "_find_exact_argv",
    "verify_process_identity",
]


def _find_exact_argv(cmdline: List[str], flag: str, expected: str) -> bool:
    """Return True if flag is in cmdline and the NEXT argument equals expected exactly."""
    try:
        idx = cmdline.index(flag)
        return idx + 1 < len(cmdline) and cmdline[idx + 1] == expected
    except ValueError:
        return False


def verify_process_identity(target: ProcessIdentity, controller: ProcessController) -> Tuple[bool, str]:
    """Strictly verify target PID, start_ticks, UID, and cmdline before signaling.

    Identity checks:
    - start_ticks must match exactly (integer comparison). _INSPECTION_ERROR
      is treated as ambiguous and rejected conservatively.
    - UID must equal the target UID (contract: 1000 for emulator user).
    - Executable (argv[0] basename or /proc/exe) must equal the known binary name
      exactly — not a substring match. A path ending in the binary is accepted.
    - AVD name: the exact next argument after ‘-avd’ must equal target.avd.
    - Port: the exact next argument after a documented port flag must equal
      str(target.port).
    """
    if not target or not target.pid or not target.start_ticks:
        return False, "Missing target PID or startTicks"

    # Contract: target UID must be the emulator user (1000), never root.
    if target.uid != TARGET_UID:
        return False, f"Target UID {target.uid} != required {TARGET_UID}"

    current_ticks = controller.get_start_ticks(target.pid)
    # _INSPECTION_ERROR → ambiguous, reject conservatively
    if current_ticks is _INSPECTION_ERROR:
        return False, f"Process start_ticks inspection error (pid={target.pid})"
    if current_ticks is None or current_ticks != target.start_ticks:
        return False, f"Process startTicks mismatch ({current_ticks} != {target.start_ticks})"

    current_uid = controller.get_uid(target.pid)
    if current_uid is None or current_uid != target.uid:
        return False, f"Process UID mismatch ({current_uid} != {target.uid})"

    cmdline = controller.get_cmdline(target.pid)
    if not cmdline:
        return False, "Process cmdline is empty or unreadable"

    cmdline_str = " ".join(cmdline)

    # 1. Verify executable: argv[0] basename or full path must END with the
    #    exact binary name (/usr/bin/qemu-system-x86_64 is OK, qemu.bak is not,
    #    python3-with-qemu-arg is not).
    exe_arg = cmdline[0]
    exe_basename = os.path.basename(exe_arg)
    binary_ok = exe_basename == target.binary_name
    if not binary_ok:
        return False, f"Process executable '{exe_basename}' != expected '{target.binary_name}': {cmdline_str}"

    # 2. Verify AVD name: exact next argument after '-avd'
    avd_ok = _find_exact_argv(cmdline, "-avd", target.avd)
    if not avd_ok:
        return False, f"Process cmdline missing exact -avd '{target.avd}': {cmdline_str}"

    # 3. Verify port: exact next argument after '-port'
    port_ok = _find_exact_argv(cmdline, "-port", str(target.port))
    if not port_ok:
        return False, f"Process cmdline missing exact -port '{target.port}': {cmdline_str}"

    return True, "Process identity verified"
