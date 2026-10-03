"""Hermetic unit tests for f3_guard.memory module."""

from __future__ import annotations

import builtins
import os
import sys
import unittest
from unittest.mock import mock_open, patch

# Add repository root to sys.path if not present for standalone execution
_REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

from scripts.f3_guard.memory import (
    GUARD_RAM_MIN_KB,
    GUARD_SWAP_MIN_KB,
    HostMeminfoReader,
    MeminfoParseError,
    parse_meminfo,
    validate_memory_limits,
)


class TestParseMeminfo(unittest.TestCase):
    """Unit tests for parse_meminfo text parsing and fail-closed error handling."""

    def test_parse_meminfo_success(self) -> None:
        sample = (
            "MemTotal:        7987200 kB\n"
            "MemFree:         1200000 kB\n"
            "MemAvailable:    3500000 kB\n"
            "SwapTotal:       4194300 kB\n"
            "SwapFree:        2100000 kB\n"
        )
        mem, swap = parse_meminfo(sample)
        self.assertEqual(mem, 3500000)
        self.assertEqual(swap, 2100000)

    def test_parse_meminfo_order_and_whitespace(self) -> None:
        sample = (
            "SwapTotal:\t4194300 kB\r\n"
            "SwapFree:   524288 kB\n"
            "\n"
            "MemAvailable:\t\t1048576 kB  \n"
            "MemTotal:   7987200 kB\n"
        )
        mem, swap = parse_meminfo(sample)
        self.assertEqual(mem, GUARD_RAM_MIN_KB)
        self.assertEqual(swap, GUARD_SWAP_MIN_KB)

    def test_parse_meminfo_empty_or_whitespace_fails_closed(self) -> None:
        for empty_input in ("", "   \n\t  \r\n  "):
            with self.subTest(sample=empty_input):
                with self.assertRaises(MeminfoParseError) as ctx:
                    parse_meminfo(empty_input)
                self.assertIn("Empty /proc/meminfo content", str(ctx.exception))

    def test_parse_meminfo_missing_fields_fail_closed(self) -> None:
        missing_swap = "MemTotal: 7987200 kB\nMemAvailable: 3500000 kB\n"
        with self.assertRaises(MeminfoParseError) as ctx:
            parse_meminfo(missing_swap)
        self.assertIn("Missing SwapFree in /proc/meminfo", str(ctx.exception))

        missing_avail = "MemTotal: 7987200 kB\nSwapFree: 2100000 kB\n"
        with self.assertRaises(MeminfoParseError) as ctx:
            parse_meminfo(missing_avail)
        self.assertIn("Missing MemAvailable in /proc/meminfo", str(ctx.exception))

    def test_parse_meminfo_truncated_lines_fail_closed(self) -> None:
        sample_key_only = "MemAvailable:\nSwapFree: 524288 kB\n"
        with self.assertRaises(MeminfoParseError) as ctx:
            parse_meminfo(sample_key_only)
        self.assertIn("Missing MemAvailable in /proc/meminfo", str(ctx.exception))

    def test_parse_meminfo_malformed_numeric_fails_closed(self) -> None:
        bad_ram = "MemAvailable: corrupt kB\nSwapFree: 524288 kB\n"
        with self.assertRaises(MeminfoParseError) as ctx:
            parse_meminfo(bad_ram)
        self.assertIn("Malformed MemAvailable value: corrupt", str(ctx.exception))

        bad_swap = "MemAvailable: 1048576 kB\nSwapFree: invalid kB\n"
        with self.assertRaises(MeminfoParseError) as ctx:
            parse_meminfo(bad_swap)
        self.assertIn("Malformed SwapFree value: invalid", str(ctx.exception))

        float_ram = "MemAvailable: 1048576.5 kB\nSwapFree: 524288 kB\n"
        with self.assertRaises(MeminfoParseError) as ctx:
            parse_meminfo(float_ram)
        self.assertIn("Malformed MemAvailable value: 1048576.5", str(ctx.exception))


class TestValidateMemoryLimits(unittest.TestCase):
    """Unit tests for validate_memory_limits boundaries, types, and error strings."""

    def test_exact_threshold_boundary_satisfied(self) -> None:
        ok, reason = validate_memory_limits(GUARD_RAM_MIN_KB, GUARD_SWAP_MIN_KB)
        self.assertTrue(ok)
        self.assertEqual(reason, "Memory limits satisfied")

        ok, reason = validate_memory_limits(GUARD_RAM_MIN_KB + 1024, GUARD_SWAP_MIN_KB + 1024)
        self.assertTrue(ok)
        self.assertEqual(reason, "Memory limits satisfied")

    def test_below_threshold_boundaries_fail_closed(self) -> None:
        ok, reason = validate_memory_limits(GUARD_RAM_MIN_KB - 1, GUARD_SWAP_MIN_KB)
        self.assertFalse(ok)
        self.assertIn(f"Available RAM {GUARD_RAM_MIN_KB - 1} kB below minimum", reason)

        ok, reason = validate_memory_limits(GUARD_RAM_MIN_KB, GUARD_SWAP_MIN_KB - 1)
        self.assertFalse(ok)
        self.assertIn(f"Free Swap {GUARD_SWAP_MIN_KB - 1} kB below minimum", reason)

        ok, reason = validate_memory_limits(GUARD_RAM_MIN_KB - 1, GUARD_SWAP_MIN_KB - 1)
        self.assertFalse(ok)
        self.assertIn("Available RAM", reason)

    def test_negative_values_rejected(self) -> None:
        for ram, swap in [(-1, 1000000), (2000000, -50), (-1, -1)]:
            with self.subTest(ram=ram, swap=swap):
                ok, reason = validate_memory_limits(ram, swap)
                self.assertFalse(ok)
                self.assertEqual(reason, "Invalid negative memory metric")

    def test_booleans_strictly_rejected(self) -> None:
        for ram, swap in [(True, 1000000), (2000000, False), (True, False), (False, True)]:
            with self.subTest(ram=ram, swap=swap):
                ok, reason = validate_memory_limits(ram, swap)
                self.assertFalse(ok)
                self.assertEqual(reason, "Invalid boolean passed as memory metric")

    def test_floats_nan_inf_and_non_int_rejected(self) -> None:
        cases = [
            (float("nan"), float("nan")),
            (float("inf"), 1000000),
            (2000000, float("-inf")),
            (1048576.0, 524288),
            (1048576, 524288.0),
            ("1048576", 524288),
            (None, 524288),
            (1048576, None),
        ]
        for ram, swap in cases:
            with self.subTest(ram=ram, swap=swap):
                ok, reason = validate_memory_limits(ram, swap)
                self.assertFalse(ok)
                self.assertEqual(reason, "Memory metric must be strict integer kB")


class TestHostMeminfoReader(unittest.TestCase):
    """Hermetic unit tests for HostMeminfoReader with zero actual /proc read."""

    def test_injected_file_reader_success(self) -> None:
        sample = "MemAvailable: 2097152 kB\nSwapFree: 1048576 kB\n"
        captured_path = []

        def custom_reader(path: str) -> str:
            captured_path.append(path)
            return sample

        reader = HostMeminfoReader(path="/custom/proc/meminfo", file_reader=custom_reader)
        mem, swap = reader()
        self.assertEqual(mem, 2097152)
        self.assertEqual(swap, 1048576)
        self.assertEqual(captured_path, ["/custom/proc/meminfo"])

    def test_injected_file_reader_oserror_fails_closed(self) -> None:
        def failing_reader(path: str) -> str:
            raise OSError("Permission denied")

        reader = HostMeminfoReader(path="/proc/meminfo", file_reader=failing_reader)
        with self.assertRaises(MeminfoParseError) as ctx:
            reader()
        self.assertIn("Host memory read failed for /proc/meminfo: Permission denied", str(ctx.exception))
        self.assertIsInstance(ctx.exception.__cause__, OSError)

    def test_injected_file_reader_empty_or_truncated(self) -> None:
        reader_empty = HostMeminfoReader(file_reader=lambda p: "")
        with self.assertRaises(MeminfoParseError) as ctx:
            reader_empty()
        self.assertIn("Empty /proc/meminfo content", str(ctx.exception))

        reader_missing_swap = HostMeminfoReader(file_reader=lambda p: "MemAvailable: 2097152 kB\n")
        with self.assertRaises(MeminfoParseError) as ctx:
            reader_missing_swap()
        self.assertIn("Missing SwapFree in /proc/meminfo", str(ctx.exception))

    def test_injected_file_reader_non_string_fails_closed(self) -> None:
        for bad_val in (12345, None, b"bytes", ["line"]):
            with self.subTest(bad_val=bad_val):
                reader = HostMeminfoReader(file_reader=lambda p, v=bad_val: v)  # type: ignore
                with self.assertRaises(MeminfoParseError) as ctx:
                    reader()
                self.assertIn(f"Meminfo content must be str, got {type(bad_val).__name__}", str(ctx.exception))

    def test_default_operation_mocked_open_with_owner_restore(self) -> None:
        sample = "MemAvailable: 2097152 kB\nSwapFree: 1048576 kB\n"
        original_open = builtins.open

        # Scoped mock for success path
        with patch("builtins.open", mock_open(read_data=sample)) as mocked_open:
            reader = HostMeminfoReader()
            mem, swap = reader()
            self.assertEqual(mem, 2097152)
            self.assertEqual(swap, 1048576)
            mocked_open.assert_called_once_with("/proc/meminfo", "r", encoding="utf-8")

        # Owner restore check
        self.assertIs(builtins.open, original_open)

        # Scoped mock for open error path
        with patch("builtins.open", side_effect=FileNotFoundError("No such file")) as mocked_open_err:
            reader = HostMeminfoReader()
            with self.assertRaises(MeminfoParseError) as ctx:
                reader()
            self.assertIn("Host memory read failed for /proc/meminfo: No such file", str(ctx.exception))
            self.assertIsInstance(ctx.exception.__cause__, FileNotFoundError)
            mocked_open_err.assert_called_once_with("/proc/meminfo", "r", encoding="utf-8")

        # Owner restore check after error
        self.assertIs(builtins.open, original_open)


if __name__ == "__main__":
    unittest.main()
