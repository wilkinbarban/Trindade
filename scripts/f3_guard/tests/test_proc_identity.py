"""Hermetic unit tests for process identity verification and argv parsing."""

import unittest
from unittest.mock import MagicMock

from scripts.f3_guard.proc_identity import (
    TARGET_UID,
    _find_exact_argv,
    verify_process_identity,
)
from scripts.f3_guard.proc_inspection import INSPECTION_ERROR, _INSPECTION_ERROR
from scripts.f3_guard.types import ProcessIdentity


class TestProcessIdentityVerification(unittest.TestCase):
    """Complete verification of process identity, argv parsing, and inspection contracts."""

    def setUp(self):
        self.target = ProcessIdentity(
            pid=12345,
            start_ticks=987654,
            uid=1000,
            avd="Trindade_UX_v030",
            port=5556,
            binary_name="qemu-system-x86_64",
        )

    def test_python3_with_qemu_arg_rejected(self):
        """python3 process with 'qemu' as argument must NOT match."""
        mock_proc = MagicMock()
        mock_proc.get_start_ticks.return_value = 987654
        mock_proc.get_uid.return_value = 1000
        mock_proc.get_cmdline.return_value = [
            "/usr/bin/python3", "qemu-system-x86_64", "-avd", "Trindade_UX_v030", "-port", "5556"]

        ok, reason = verify_process_identity(self.target, mock_proc)
        self.assertFalse(ok)
        self.assertIn("executable", reason.lower())

    def test_qemu_bak_rejected(self):
        """qemu-system-x86_64.bak must NOT match."""
        mock_proc = MagicMock()
        mock_proc.get_start_ticks.return_value = 987654
        mock_proc.get_uid.return_value = 1000
        mock_proc.get_cmdline.return_value = [
            "qemu-system-x86_64.bak", "-avd", "Trindade_UX_v030", "-port", "5556"]

        ok, reason = verify_process_identity(self.target, mock_proc)
        self.assertFalse(ok)

    def test_wrong_avd_next_arg_rejected(self):
        """AVD_OTHER after -avd must NOT match Trindade_UX_v030."""
        mock_proc = MagicMock()
        mock_proc.get_start_ticks.return_value = 987654
        mock_proc.get_uid.return_value = 1000
        mock_proc.get_cmdline.return_value = [
            "qemu-system-x86_64", "-avd", "AVD_OTHER", "-port", "5556"]

        ok, reason = verify_process_identity(self.target, mock_proc)
        self.assertFalse(ok)
        self.assertIn("avd", reason.lower())

    def test_wrong_port_next_arg_rejected(self):
        """Port 15556 must NOT match target port 5556."""
        mock_proc = MagicMock()
        mock_proc.get_start_ticks.return_value = 987654
        mock_proc.get_uid.return_value = 1000
        mock_proc.get_cmdline.return_value = [
            "qemu-system-x86_64", "-avd", "Trindade_UX_v030", "-port", "15556"]

        ok, reason = verify_process_identity(self.target, mock_proc)
        self.assertFalse(ok)
        self.assertIn("port", reason.lower())

    def test_uid_0_rejected(self):
        """Target UID 0 (root) must NOT match required UID 1000."""
        mock_proc = MagicMock()
        mock_proc.get_start_ticks.return_value = 987654
        mock_proc.get_uid.return_value = 0
        mock_proc.get_cmdline.return_value = [
            "qemu-system-x86_64", "-avd", "Trindade_UX_v030", "-port", "5556"]

        ok, reason = verify_process_identity(self.target, mock_proc)
        self.assertFalse(ok)
        self.assertIn("uid", reason.lower())

    def test_exact_identity_passes(self):
        """Correct binary, -avd, -port, UID, ticks: passes."""
        mock_proc = MagicMock()
        mock_proc.get_start_ticks.return_value = 987654
        mock_proc.get_uid.return_value = 1000
        mock_proc.get_cmdline.return_value = [
            "/usr/lib/android-sdk/emulator/qemu/linux-x86_64/qemu-system-x86_64",
            "-avd", "Trindade_UX_v030", "-port", "5556", "-gpu", "swiftshader_indirect"]

        ok, reason = verify_process_identity(self.target, mock_proc)
        self.assertTrue(ok)
        self.assertEqual(reason, "Process identity verified")

    def test_inspection_error_on_ticks_rejects(self):
        """_INSPECTION_ERROR from get_start_ticks should conservatively reject."""
        mock_proc = MagicMock()
        mock_proc.get_start_ticks.return_value = _INSPECTION_ERROR
        mock_proc.get_uid.return_value = 1000
        mock_proc.get_cmdline.return_value = [
            "qemu-system-x86_64", "-avd", "Trindade_UX_v030", "-port", "5556"]

        ok, reason = verify_process_identity(self.target, mock_proc)
        self.assertFalse(ok)
        self.assertIn("inspection error", reason.lower())

    def test_target_uid_0_controller_uid_0_rejected(self):
        """Even if target.uid==0 and controller returns 0, must reject: UID must be 1000."""
        root_target = ProcessIdentity(
            pid=12345, start_ticks=987654, uid=0,
            avd="Trindade_UX_v030", port=5556,
            binary_name="qemu-system-x86_64"
        )
        mock_proc = MagicMock()
        mock_proc.get_start_ticks.return_value = 987654
        mock_proc.get_uid.return_value = 0
        mock_proc.get_cmdline.return_value = [
            "qemu-system-x86_64", "-avd", "Trindade_UX_v030", "-port", "5556"]

        ok, reason = verify_process_identity(root_target, mock_proc)
        self.assertFalse(ok)
        self.assertIn("uid", reason.lower())

    def test_target_uid_contract_and_missing_attributes(self):
        """TARGET_UID is 1000; missing target/pid/ticks or wrong target UID fail immediately."""
        self.assertEqual(TARGET_UID, 1000)
        mock_proc = MagicMock()

        for bad_target in (
            None,
            ProcessIdentity(pid=0, start_ticks=100, uid=1000, avd="avd", port=5554, binary_name="qemu"),
            ProcessIdentity(pid=42, start_ticks=0, uid=1000, avd="avd", port=5554, binary_name="qemu"),
        ):
            with self.subTest(bad_target=bad_target):
                ok, reason = verify_process_identity(bad_target, mock_proc)
                self.assertFalse(ok)
                self.assertEqual(reason, "Missing target PID or startTicks")
                mock_proc.get_start_ticks.assert_not_called()

        for wrong_uid in (0, 1001, -1):
            bad_uid_target = ProcessIdentity(
                pid=12345, start_ticks=987654, uid=wrong_uid, avd="avd", port=5556, binary_name="qemu"
            )
            with self.subTest(wrong_uid=wrong_uid):
                ok, reason = verify_process_identity(bad_uid_target, mock_proc)
                self.assertFalse(ok)
                self.assertEqual(reason, f"Target UID {wrong_uid} != required 1000")
                mock_proc.get_start_ticks.assert_not_called()

    def test_ticks_inspection_sentinel_and_mismatch_branches(self):
        """Ticks returning sentinel, None, or mismatched integer fail with exact reasons."""
        mock_proc = MagicMock()

        for sentinel in (INSPECTION_ERROR, _INSPECTION_ERROR):
            with self.subTest(sentinel=sentinel):
                mock_proc.get_start_ticks.return_value = sentinel
                ok, reason = verify_process_identity(self.target, mock_proc)
                self.assertFalse(ok)
                self.assertEqual(reason, f"Process start_ticks inspection error (pid={self.target.pid})")

        for current_ticks, expected_str in ((None, "None"), (999999, "999999")):
            with self.subTest(current_ticks=current_ticks):
                mock_proc.get_start_ticks.return_value = current_ticks
                ok, reason = verify_process_identity(self.target, mock_proc)
                self.assertFalse(ok)
                self.assertEqual(reason, f"Process startTicks mismatch ({expected_str} != {self.target.start_ticks})")

    def test_uid_and_cmdline_failure_branches(self):
        """Controller UID failure/mismatch and empty cmdline fail with exact reasons."""
        mock_proc = MagicMock()
        mock_proc.get_start_ticks.return_value = self.target.start_ticks

        for current_uid, expected_str in ((None, "None"), (1001, "1001")):
            with self.subTest(current_uid=current_uid):
                mock_proc.get_uid.return_value = current_uid
                ok, reason = verify_process_identity(self.target, mock_proc)
                self.assertFalse(ok)
                self.assertEqual(reason, f"Process UID mismatch ({expected_str} != {self.target.uid})")

        mock_proc.get_uid.return_value = self.target.uid
        for empty_cmdline in ([], None):
            with self.subTest(empty_cmdline=empty_cmdline):
                mock_proc.get_cmdline.return_value = empty_cmdline
                ok, reason = verify_process_identity(self.target, mock_proc)
                self.assertFalse(ok)
                self.assertEqual(reason, "Process cmdline is empty or unreadable")

    def test_binary_basename_and_path_matching(self):
        """Path basename matching succeeds; wrong basename fails with process executable reason."""
        mock_proc = MagicMock()
        mock_proc.get_start_ticks.return_value = self.target.start_ticks
        mock_proc.get_uid.return_value = self.target.uid

        mock_proc.get_cmdline.return_value = [
            "/custom/bin/qemu-system-x86_64", "-avd", "Trindade_UX_v030", "-port", "5556"
        ]
        ok, reason = verify_process_identity(self.target, mock_proc)
        self.assertTrue(ok)
        self.assertEqual(reason, "Process identity verified")

        for mismatch in ("qemu-system-x86_64.bak", "python3", "qemu"):
            with self.subTest(mismatch=mismatch):
                mock_proc.get_cmdline.return_value = [mismatch, "-avd", "Trindade_UX_v030", "-port", "5556"]
                ok, reason = verify_process_identity(self.target, mock_proc)
                self.assertFalse(ok)
                self.assertIn("Process executable", reason)

    def test_avd_and_port_flag_token_branches(self):
        """Missing flag, missing value, wrong value, or prefix token fails for avd and port."""
        mock_proc = MagicMock()
        mock_proc.get_start_ticks.return_value = self.target.start_ticks
        mock_proc.get_uid.return_value = self.target.uid

        for bad_cmdline in (
            ["qemu-system-x86_64", "-port", "5556"],
            ["qemu-system-x86_64", "-port", "5556", "-avd"],
            ["qemu-system-x86_64", "-avd_extra", "Trindade_UX_v030", "-port", "5556"],
            ["qemu-system-x86_64", "-avd", "WrongAVD", "-avd", "Trindade_UX_v030", "-port", "5556"],
        ):
            with self.subTest(bad_cmdline=bad_cmdline):
                mock_proc.get_cmdline.return_value = bad_cmdline
                ok, reason = verify_process_identity(self.target, mock_proc)
                self.assertFalse(ok)
                self.assertIn("missing exact -avd", reason)

        for bad_cmdline in (
            ["qemu-system-x86_64", "-avd", "Trindade_UX_v030"],
            ["qemu-system-x86_64", "-avd", "Trindade_UX_v030", "-port"],
            ["qemu-system-x86_64", "-avd", "Trindade_UX_v030", "-port_extra", "5556"],
            ["qemu-system-x86_64", "-avd", "Trindade_UX_v030", "-port", "9999", "-port", "5556"],
        ):
            with self.subTest(bad_cmdline=bad_cmdline):
                mock_proc.get_cmdline.return_value = bad_cmdline
                ok, reason = verify_process_identity(self.target, mock_proc)
                self.assertFalse(ok)
                self.assertIn("missing exact -port", reason)

    def test_find_exact_argv_contracts(self):
        """_find_exact_argv evaluates exact flag and subsequent value accurately."""
        self.assertFalse(_find_exact_argv([], "-flag", "val"))
        self.assertFalse(_find_exact_argv(["-other", "val"], "-flag", "val"))
        self.assertFalse(_find_exact_argv(["-flag"], "-flag", "val"))
        self.assertFalse(_find_exact_argv(["-flag", "wrong"], "-flag", "val"))
        self.assertTrue(_find_exact_argv(["-flag", "val"], "-flag", "val"))
        self.assertTrue(_find_exact_argv(["head", "-flag", "val", "tail"], "-flag", "val"))
        self.assertFalse(_find_exact_argv(["-flag", "wrong", "-flag", "val"], "-flag", "val"))
        self.assertTrue(_find_exact_argv(["-flag", "val", "-flag", "wrong"], "-flag", "val"))

    def test_call_ordering_short_circuit(self):
        """Early branch rejections exit immediately without subsequent inspection calls."""
        mock_proc = MagicMock()
        mock_proc.get_start_ticks.return_value = 999  # ticks mismatch
        ok, _ = verify_process_identity(self.target, mock_proc)
        self.assertFalse(ok)
        mock_proc.get_start_ticks.assert_called_once_with(self.target.pid)
        mock_proc.get_uid.assert_not_called()
        mock_proc.get_cmdline.assert_not_called()

        mock_proc.reset_mock()
        mock_proc.get_start_ticks.return_value = self.target.start_ticks
        mock_proc.get_uid.return_value = 999  # uid mismatch
        ok, _ = verify_process_identity(self.target, mock_proc)
        self.assertFalse(ok)
        mock_proc.get_uid.assert_called_once_with(self.target.pid)
        mock_proc.get_cmdline.assert_not_called()
