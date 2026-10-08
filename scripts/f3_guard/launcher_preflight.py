"""Fail-closed emulator launcher preflight validation and argv generation."""

from __future__ import annotations

from dataclasses import dataclass
import os
from typing import Any, Callable, List, Optional, Tuple

from scripts.f3_guard.controller import ProcessController
from scripts.f3_guard.types import ProcessIdentity

TARGET_UID: int = 1000
TARGET_AVD: str = "Trindade_UX_v030"
TARGET_PORT: int = 5556
LAUNCH_RAM_MIN_KB: int = 4718592
LAUNCH_SWAP_MIN_KB: int = 2097152
DEFAULT_EMULATOR_PATH: str = "/home/wilkin/Android/Sdk/emulator/emulator"


@dataclass(frozen=True)
class LaunchResult:
    """Explicit descriptor of emulator launch attempt outcome."""
    success: bool
    identity: Optional[ProcessIdentity] = None
    pidfd: Optional[int] = None
    child_handle: Optional[Any] = None
    error: Optional[str] = None
    is_direct_child: bool = False
    pending_cleanup: bool = False
    transferred: bool = False


class LauncherPreflightHelper:
    """Bounded, fail-closed launcher preflight checks and argv generation."""

    def __init__(
        self,
        explicit_opt_in: bool = False,
        mem_reader: Optional[Callable[[], Tuple[int, int]]] = None,
        controller: Optional[ProcessController] = None,
        target_uid: int = TARGET_UID,
        avd_name: str = TARGET_AVD,
        port: int = TARGET_PORT,
        emulator_binary: str = DEFAULT_EMULATOR_PATH,
        binary_validator: Optional[Callable[[str], bool]] = None,
        availability_check: Optional[Callable[[str, int], bool]] = None,
    ) -> None:
        self._opt_in = explicit_opt_in
        self._mem_reader = mem_reader
        self._controller = controller or ProcessController()
        self._target_uid = target_uid
        self._avd_name = avd_name
        self._port = port
        self._binary = emulator_binary
        self._binary_validator = binary_validator
        self._availability_check = availability_check

    def get_frozen_argv(self) -> List[str]:
        return [
            self._binary, "-avd", self._avd_name, "-port", str(self._port),
            "-memory", "1536", "-cores", "2",
            "-no-window", "-no-audio", "-no-snapshot-load", "-no-snapshot-save",
        ]

    def validate(self) -> Optional[LaunchResult]:
        if self._opt_in is not True:
            return LaunchResult(success=False, error="Explicit opt-in required; launch refused")
        if self._avd_name != TARGET_AVD:
            return LaunchResult(success=False, error=f"AVD '{self._avd_name}' != immutable '{TARGET_AVD}'")
        if self._port != TARGET_PORT:
            return LaunchResult(success=False, error=f"Port {self._port} != immutable {TARGET_PORT}")
        if self._target_uid != TARGET_UID:
            return LaunchResult(success=False, error=f"Target UID {self._target_uid} != required {TARGET_UID}")

        if getattr(self._controller, "use_pidfd", False) is not True:
            return LaunchResult(success=False, error="Controller does not support or have pidfd enabled")
        if not (hasattr(self._controller, "pidfd_open") and hasattr(self._controller, "pidfd_send_signal")):
            return LaunchResult(success=False, error="Controller lacks required pidfd_open/pidfd_send_signal capabilities")

        if self._mem_reader is None:
            return LaunchResult(success=False, error="Missing memory reader for launch preflight")
        try:
            mem_avail, swap_free = self._mem_reader()
        except Exception as e:
            return LaunchResult(success=False, error=f"Memory preflight read failed: {e}")

        if type(mem_avail) is not int or type(swap_free) is not int or mem_avail < 0 or swap_free < 0:
            return LaunchResult(success=False, error="Memory metrics must be strict non-negative integer kB")
        if mem_avail < LAUNCH_RAM_MIN_KB:
            return LaunchResult(success=False, error=f"Available memory {mem_avail} kB below launch minimum {LAUNCH_RAM_MIN_KB} kB")
        if swap_free < LAUNCH_SWAP_MIN_KB:
            return LaunchResult(success=False, error=f"Free swap {swap_free} kB below launch minimum {LAUNCH_SWAP_MIN_KB} kB")

        if self._availability_check is None:
            return LaunchResult(success=False, error="Mandatory availability_check required but missing")
        try:
            if self._availability_check(self._avd_name, self._port) is not True:
                return LaunchResult(success=False, error=f"AVD '{self._avd_name}' or port {self._port} occupied or unverified")
        except Exception as e:
            return LaunchResult(success=False, error=f"Availability check exception: {e}")

        if not os.path.isabs(self._binary):
            return LaunchResult(success=False, error=f"Emulator binary must be absolute: {self._binary}")
        if os.path.normpath(self._binary) != self._binary:
            return LaunchResult(success=False, error=f"Emulator binary must be normalized without traversal: {self._binary}")
        try:
            if os.path.realpath(self._binary) != self._binary:
                return LaunchResult(success=False, error=f"Emulator binary must be canonical realpath without symlinks: {self._binary}")
        except Exception as e:
            return LaunchResult(success=False, error=f"Realpath resolution error: {e}")

        try:
            if not (os.path.isfile(self._binary) and os.access(self._binary, os.X_OK)):
                if self._binary_validator is None:
                    return LaunchResult(success=False, error=f"Emulator binary not executable regular file: {self._binary}")
        except Exception as e:
            return LaunchResult(success=False, error=f"File check error: {e}")

        if self._binary_validator is not None:
            try:
                if self._binary_validator(self._binary) is not True:
                    return LaunchResult(success=False, error=f"Binary validation failed for {self._binary}")
            except Exception as e:
                return LaunchResult(success=False, error=f"Binary validation error: {e}")

        return None
