"""Hermetic unit tests for scripts.f3_guard.proc_inspection module."""

from __future__ import annotations

import os
import sys
import unittest
from unittest.mock import MagicMock, mock_open, patch

_REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

from scripts.f3_guard.proc_inspection import (
    INSPECTION_ERROR,
    ProcInspectionHelper,
    _INSPECTION_ERROR,
)


class TestProcInspectionHelper(unittest.TestCase):
    """Hermetic unit tests for ProcInspectionHelper /proc inspection routines."""

    def setUp(self) -> None:
        self.helper = ProcInspectionHelper()

    def test_sentinel_identity(self) -> None:
        """INSPECTION_ERROR and _INSPECTION_ERROR point to the exact same sentinel object."""
        self.assertIs(INSPECTION_ERROR, _INSPECTION_ERROR)
        self.assertNotEqual(INSPECTION_ERROR, "INSPECTION_ERROR")

    def test_get_exe_path(self) -> None:
        """get_exe_path resolves /proc/{pid}/exe link or returns None on error."""
        with patch("os.readlink", return_value="/usr/bin/app") as mock_rl:
            self.assertEqual(self.helper.get_exe_path(42), "/usr/bin/app")
            mock_rl.assert_called_once_with("/proc/42/exe")

        with patch("os.readlink", side_effect=FileNotFoundError):
            self.assertIsNone(self.helper.get_exe_path(42))

        with patch("os.readlink", side_effect=PermissionError):
            self.assertIsNone(self.helper.get_exe_path(42))

    def test_get_ppid(self) -> None:
        """get_ppid parses PPID from /proc/{pid}/stat handling parentheses and errors."""
        normal_stat = "1234 (python3) S 1000 1234 1234 0 0 0 0 0\n"
        with patch("builtins.open", mock_open(read_data=normal_stat)):
            self.assertEqual(self.helper.get_ppid(1234), 1000)

        comm_parens_stat = "1234 (worker (sub) proc) S 5678 1234 1234 0 0\n"
        with patch("builtins.open", mock_open(read_data=comm_parens_stat)):
            self.assertEqual(self.helper.get_ppid(1234), 5678)

        for malformed in ["1234 malformed", "1234 (sh) S", "1234 (sh) S notanint"]:
            with self.subTest(stat=malformed):
                with patch("builtins.open", mock_open(read_data=malformed)):
                    self.assertIsNone(self.helper.get_ppid(1234))

        with patch("builtins.open", side_effect=FileNotFoundError):
            self.assertIsNone(self.helper.get_ppid(1234))

        m_read_err = mock_open()
        m_read_err.return_value.read.side_effect = OSError("Read failed")
        with patch("builtins.open", m_read_err):
            self.assertIsNone(self.helper.get_ppid(1234))

    def test_get_start_ticks(self) -> None:
        """get_start_ticks returns int ticks, None if absent, or sentinel on inspection failure."""
        tail = " ".join(str(i) for i in range(1, 19))
        stat_valid = f"1234 (bash) S {tail} 987654 0 0\n"
        with patch("builtins.open", mock_open(read_data=stat_valid)):
            self.assertEqual(self.helper.get_start_ticks(1234), 987654)

        stat_comm_parens = f"1234 (proc (with spaces)) S {tail} 112233 0 0\n"
        with patch("builtins.open", mock_open(read_data=stat_comm_parens)):
            self.assertEqual(self.helper.get_start_ticks(1234), 112233)

        for err in [FileNotFoundError(), ProcessLookupError()]:
            with self.subTest(err=type(err).__name__):
                with patch("builtins.open", side_effect=err):
                    self.assertIsNone(self.helper.get_start_ticks(1234))

        for bad in ["no_paren_here", f"1234 (x) S {tail}", f"1234 (x) S {tail} notanint 0"]:
            with self.subTest(bad=bad):
                with patch("builtins.open", mock_open(read_data=bad)):
                    self.assertIs(self.helper.get_start_ticks(1234), INSPECTION_ERROR)

        with patch("builtins.open", side_effect=PermissionError("EACCES")):
            self.assertIs(self.helper.get_start_ticks(1234), INSPECTION_ERROR)

        m_read_err = mock_open()
        m_read_err.return_value.read.side_effect = OSError("Read failed")
        with patch("builtins.open", m_read_err):
            self.assertIs(self.helper.get_start_ticks(1234), INSPECTION_ERROR)

    def test_get_uid(self) -> None:
        """get_uid retrieves UID from /proc/{pid} or None on error/absence."""
        stat_res = MagicMock()
        stat_res.st_uid = 1001
        with patch("os.stat", return_value=stat_res) as mock_st:
            self.assertEqual(self.helper.get_uid(42), 1001)
            mock_st.assert_called_once_with("/proc/42")

        with patch("os.stat", side_effect=FileNotFoundError):
            self.assertIsNone(self.helper.get_uid(42))

        with patch("os.stat", side_effect=ProcessLookupError):
            self.assertIsNone(self.helper.get_uid(42))

        with patch("os.stat", side_effect=PermissionError):
            self.assertIsNone(self.helper.get_uid(42))

    def test_get_cmdline(self) -> None:
        """get_cmdline decodes NUL-separated argv with utf-8 replacement or empty on error."""
        cmdline_bytes = b"/bin/app\x00--opt\x00arg with space\x00"
        with patch("builtins.open", mock_open(read_data=cmdline_bytes)):
            self.assertEqual(self.helper.get_cmdline(42), ["/bin/app", "--opt", "arg with space"])

        cmdline_trailing_nul = b"app\x00\x00\x00"
        with patch("builtins.open", mock_open(read_data=cmdline_trailing_nul)):
            self.assertEqual(self.helper.get_cmdline(42), ["app"])

        cmdline_replace = b"app\x00\xff\xfe\x00"
        with patch("builtins.open", mock_open(read_data=cmdline_replace)):
            self.assertEqual(self.helper.get_cmdline(42), ["app", "\ufffd\ufffd"])

        for err in [FileNotFoundError(), ProcessLookupError(), OSError("Corrupted")]:
            with self.subTest(err=type(err).__name__):
                with patch("builtins.open", side_effect=err):
                    self.assertEqual(self.helper.get_cmdline(42), [])

        m_read_err = mock_open()
        m_read_err.return_value.read.side_effect = OSError("Read failed")
        with patch("builtins.open", m_read_err):
            self.assertEqual(self.helper.get_cmdline(42), [])

    def test_get_fdinfo_pid_invalid_fd(self) -> None:
        """get_fdinfo_pid rejects non-integer, negative, or bool fd values immediately."""
        for invalid_fd in [-1, -100, True, False, "0", 3.14, None]:
            with self.subTest(fd=invalid_fd):
                self.assertIsNone(self.helper.get_fdinfo_pid(invalid_fd))  # type: ignore[arg-type]

    def test_get_fdinfo_pid_parsing(self) -> None:
        """get_fdinfo_pid strictly parses Pid field without real /proc reads."""
        valid_fdinfo = "pos:\t0\nflags:\t02000002\nmnt_id:\t15\nino:\t123\nPid:\t12345\n"
        m_valid = mock_open(read_data=valid_fdinfo)
        with patch("builtins.open", m_valid):
            self.assertEqual(self.helper.get_fdinfo_pid(77), 12345)
            m_valid().read.assert_called_once_with(8193)

        no_pid = "pos:\t0\nflags:\t02\n"
        with patch("builtins.open", mock_open(read_data=no_pid)):
            self.assertIsNone(self.helper.get_fdinfo_pid(77))

        bad_pid = "pos:\t0\nPid:\tnot_an_int\n"
        with patch("builtins.open", mock_open(read_data=bad_pid)):
            self.assertIsNone(self.helper.get_fdinfo_pid(77))

        with patch("builtins.open", side_effect=OSError("Permission denied")):
            self.assertIsNone(self.helper.get_fdinfo_pid(77))

        m_read_err = mock_open()
        m_read_err.return_value.read.side_effect = OSError("Read failed")
        with patch("builtins.open", m_read_err):
            self.assertIsNone(self.helper.get_fdinfo_pid(77))

    def test_get_fdinfo_pid_duplicates_and_malformed(self) -> None:
        """get_fdinfo_pid rejects duplicates, trailing junk, and malformed Pid lines."""
        dup_same = "pos:\t0\nPid:\t12345\nPid:\t12345\n"
        with patch("builtins.open", mock_open(read_data=dup_same)):
            self.assertIsNone(self.helper.get_fdinfo_pid(77))

        dup_diff = "pos:\t0\nPid:\t12345\nPid:\t999\n"
        with patch("builtins.open", mock_open(read_data=dup_diff)):
            self.assertIsNone(self.helper.get_fdinfo_pid(77))

        malformed_cases = [
            "pos:\t0\nPid:\t12345 junk\n",
            "pos:\t0\nPid:12345\n",
            "pos:\t0\nPid: 12345extra\n",
            "pos:\t0\nPid:\t-1\n",
            "pos:\t0\nPid:\t0\n",
        ]
        for bad_content in malformed_cases:
            with self.subTest(content=bad_content):
                with patch("builtins.open", mock_open(read_data=bad_content)):
                    self.assertIsNone(self.helper.get_fdinfo_pid(77))

    def test_get_fdinfo_pid_newline_and_oversize(self) -> None:
        """get_fdinfo_pid rejects missing newline termination and oversized (>8192) content."""
        exact_8192 = ("pos:\t0\nPid:\t12345\n" + "extra:\t0\n" * 1100)[:8191] + "\n"
        self.assertEqual(len(exact_8192), 8192)
        with patch("builtins.open", mock_open(read_data=exact_8192)):
            self.assertEqual(self.helper.get_fdinfo_pid(77), 12345)

        no_nl = "pos:\t0\nPid:\t12345"
        with patch("builtins.open", mock_open(read_data=no_nl)):
            self.assertIsNone(self.helper.get_fdinfo_pid(77))

        oversize = ("pos:\t0\nPid:\t12345\n" + "extra_info:\t0\n" * 700)[:8200] + "\n"
        self.assertGreater(len(oversize), 8192)
        with patch("builtins.open", mock_open(read_data=oversize)):
            self.assertIsNone(self.helper.get_fdinfo_pid(77))

    def test_get_process_state(self) -> None:
        """get_process_state returns state character, None if absent, or INSPECTION_ERROR."""
        for state_char in ["R", "S", "Z", "D", "T"]:
            with self.subTest(state=state_char):
                status_content = f"Name:\tproc\nState:\t{state_char} (info)\nTgid:\t100\n"
                with patch("builtins.open", mock_open(read_data=status_content)):
                    self.assertEqual(self.helper.get_process_state(42), state_char)

        for absent_err in [FileNotFoundError(), ProcessLookupError()]:
            with self.subTest(err=type(absent_err).__name__):
                with patch("builtins.open", side_effect=absent_err):
                    self.assertIsNone(self.helper.get_process_state(42))

        no_state = "Name:\tproc\nTgid:\t100\n"
        with patch("builtins.open", mock_open(read_data=no_state)):
            self.assertEqual(self.helper.get_process_state(42), "INSPECTION_ERROR")

        for malformed in ["State:\n", "State:   \n"]:
            with self.subTest(malformed=malformed):
                with patch("builtins.open", mock_open(read_data=malformed)):
                    self.assertEqual(self.helper.get_process_state(42), "INSPECTION_ERROR")

        with patch("builtins.open", side_effect=OSError("Read error")):
            self.assertEqual(self.helper.get_process_state(42), "INSPECTION_ERROR")

        m_iter_err = mock_open()
        m_iter_err.return_value.__iter__.side_effect = OSError("Iter error")
        with patch("builtins.open", m_iter_err):
            self.assertEqual(self.helper.get_process_state(42), "INSPECTION_ERROR")
