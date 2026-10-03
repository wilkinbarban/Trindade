"""Host memory parsing and safety threshold validation for F3 emulator guard.

This module provides fail-closed memory parsing (/proc/meminfo) and capacity
threshold validation extracted from the F3 emulator guard architecture.
"""

from __future__ import annotations

from typing import Any, Callable, Optional, Tuple

__all__ = [
    "GUARD_RAM_MIN_KB",
    "GUARD_SWAP_MIN_KB",
    "MeminfoParseError",
    "HostMeminfoReader",
    "parse_meminfo",
    "validate_memory_limits",
]

# --- Guard Memory Thresholds ---
GUARD_RAM_MIN_KB: int = 1048576       # 1.0 GiB
GUARD_SWAP_MIN_KB: int = 524288       # 512 MiB


class MeminfoParseError(Exception):
    """Raised when /proc/meminfo cannot be read or lacks required numeric fields."""
    pass


def parse_meminfo(content: str) -> Tuple[int, int]:
    """Parse MemAvailable and SwapFree in kB from /proc/meminfo text.

    Raises MeminfoParseError if either value is absent or non-numeric.
    """
    if not content or not content.strip():
        raise MeminfoParseError("Empty /proc/meminfo content")

    mem_avail: Optional[int] = None
    swap_free: Optional[int] = None

    for line in content.splitlines():
        parts = line.split()
        if len(parts) >= 2:
            key = parts[0]
            val_str = parts[1]
            if key == "MemAvailable:":
                try:
                    mem_avail = int(val_str)
                except ValueError:
                    raise MeminfoParseError(f"Malformed MemAvailable value: {val_str}")
            elif key == "SwapFree:":
                try:
                    swap_free = int(val_str)
                except ValueError:
                    raise MeminfoParseError(f"Malformed SwapFree value: {val_str}")

    if mem_avail is None:
        raise MeminfoParseError("Missing MemAvailable in /proc/meminfo")
    if swap_free is None:
        raise MeminfoParseError("Missing SwapFree in /proc/meminfo")

    return mem_avail, swap_free


class HostMeminfoReader:
    """Reads and parses host memory metrics (/proc/meminfo) with fail-closed semantics."""

    def __init__(
        self,
        path: str = "/proc/meminfo",
        file_reader: Optional[Callable[[str], str]] = None,
    ) -> None:
        self._path = path
        self._file_reader = file_reader

    def __call__(self) -> Tuple[int, int]:
        try:
            if self._file_reader is not None:
                content = self._file_reader(self._path)
            else:
                with open(self._path, "r", encoding="utf-8") as f:
                    content = f.read()
        except Exception as e:
            raise MeminfoParseError(f"Host memory read failed for {self._path}: {e}") from e

        if not isinstance(content, str):
            raise MeminfoParseError(f"Meminfo content must be str, got {type(content).__name__}")

        return parse_meminfo(content)


def validate_memory_limits(mem_avail_kb: Any, swap_free_kb: Any) -> Tuple[bool, str]:
    """Validate that memory readings satisfy guard thresholds.

    Strictly requires finite non-negative integer kB.
    Rejects booleans, NaN, Inf, floats, fractions, and values below thresholds.
    """
    if isinstance(mem_avail_kb, bool) or isinstance(swap_free_kb, bool):
        return False, "Invalid boolean passed as memory metric"

    # Strictly require int (reject float, nan, inf, Decimal, etc.)
    if type(mem_avail_kb) is not int or type(swap_free_kb) is not int:
        return False, "Memory metric must be strict integer kB"

    if mem_avail_kb < 0 or swap_free_kb < 0:
        return False, "Invalid negative memory metric"

    if mem_avail_kb < GUARD_RAM_MIN_KB:
        return False, f"Available RAM {mem_avail_kb} kB below minimum {GUARD_RAM_MIN_KB} kB"

    if swap_free_kb < GUARD_SWAP_MIN_KB:
        return False, f"Free Swap {swap_free_kb} kB below minimum {GUARD_SWAP_MIN_KB} kB"

    return True, "Memory limits satisfied"
