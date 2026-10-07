"""Hermetic unit tests for PidfdOpsHelper direct OS operations and process reaping."""

from __future__ import annotations

import errno
import os
import select
import signal
import unittest
from unittest.mock import MagicMock, patch

from scripts.f3_guard.pidfd_ops import PidfdOpsHelper


class TestPidfdOperationsAndReap(unittest.TestCase):
    """Platform pidfd open, signaling, event poll, and process reaping."""

    def setUp(self) -> None:
        self.helper = PidfdOpsHelper()

    def test_is_pidfd_alive_events_and_errors(self) -> None:
        mock_p = MagicMock()
        expected_flags = select.POLLIN | select.POLLHUP | select.POLLERR | getattr(select, "POLLNVAL", 0)

        with patch("select.poll", return_value=mock_p):
            mock_p.poll.return_value = []
            self.assertTrue(self.helper.is_pidfd_alive(77))
            mock_p.register.assert_called_with(77, expected_flags)
            mock_p.poll.assert_called_with(0)

            for flag in [select.POLLIN, select.POLLHUP, select.POLLERR, getattr(select, "POLLNVAL", 32)]:
                with self.subTest(flag=flag):
                    mock_p.poll.return_value = [(77, flag)]
                    self.assertFalse(self.helper.is_pidfd_alive(77))

            for unknown_flag in [0, 0x1000]:
                with self.subTest(unknown_flag=unknown_flag):
                    mock_p.poll.return_value = [(77, unknown_flag)]
                    self.assertFalse(self.helper.is_pidfd_alive(77))

            # Multiple events, mixed masks, and unknown flags demonstrate any event -> False
            multi_event_cases = [
                [(77, select.POLLIN), (77, select.POLLHUP)],
                [(77, select.POLLIN | select.POLLHUP)],
                [(77, select.POLLERR | 0x1000)],
                [(77, select.POLLIN), (88, select.POLLERR)],
            ]
            for events in multi_event_cases:
                with self.subTest(events=events):
                    mock_p.poll.return_value = events
                    self.assertFalse(self.helper.is_pidfd_alive(77))
                    mock_p.register.assert_called_with(77, expected_flags)
                    mock_p.poll.assert_called_with(0)

            mock_p.poll.side_effect = OSError("EBADF")
            self.assertFalse(self.helper.is_pidfd_alive(77))
            mock_p.poll.side_effect = None

            mock_p.register.side_effect = OSError("register failed")
            self.assertFalse(self.helper.is_pidfd_alive(77))

        with patch("select.poll", side_effect=OSError("poll init failed")):
            self.assertFalse(self.helper.is_pidfd_alive(77))

        with patch("select.poll") as mock_poll:
            for bad_fd in [-1, True, False, "77", 3.14]:
                with self.subTest(bad_fd=bad_fd):
                    self.assertFalse(self.helper.is_pidfd_alive(bad_fd))
            mock_poll.assert_not_called()

    def test_pidfd_open_contracts(self) -> None:
        with patch("os.pidfd_open", create=True, return_value=88) as mock_open:
            self.assertEqual(self.helper.pidfd_open(12345), 88)
            mock_open.assert_called_once_with(12345, 0)

        with patch("os.pidfd_open", create=True, side_effect=OSError(errno.ESRCH, "No process")):
            self.assertIsNone(self.helper.pidfd_open(12345))

        for err in [
            OSError(errno.EPERM, "Operation not permitted"),
            OSError(errno.EMFILE, "Too many open files"),
            OSError(errno.EINVAL, "Invalid argument"),
        ]:
            with self.subTest(err=err):
                with patch("os.pidfd_open", create=True, side_effect=err):
                    self.assertIsNone(self.helper.pidfd_open(12345))

        with patch("scripts.f3_guard.pidfd_ops.hasattr", side_effect=lambda o, a: False if a == "pidfd_open" else hasattr(o, a)):
            self.assertIsNone(self.helper.pidfd_open(12345))

    def test_pidfd_send_signal_contracts(self) -> None:
        with patch("signal.pidfd_send_signal", create=True) as mock_sig:
            self.assertTrue(self.helper.pidfd_send_signal(88, signal.SIGTERM))
            mock_sig.assert_called_once_with(88, signal.SIGTERM)

        with patch("signal.pidfd_send_signal", create=True, side_effect=OSError(errno.ESRCH, "No process")):
            self.assertFalse(self.helper.pidfd_send_signal(88, signal.SIGTERM))

        for err in [OSError(errno.EPERM, "Permission denied"), OSError(errno.EINVAL, "Invalid argument")]:
            with self.subTest(err=err):
                with patch("signal.pidfd_send_signal", create=True, side_effect=err):
                    self.assertFalse(self.helper.pidfd_send_signal(88, signal.SIGTERM))

        with patch("scripts.f3_guard.pidfd_ops.hasattr", side_effect=lambda o, a: False if a == "pidfd_send_signal" else hasattr(o, a)):
            self.assertFalse(self.helper.pidfd_send_signal(88, signal.SIGTERM))

    def test_send_signal_calls_os_kill(self) -> None:
        with patch("os.kill") as mock_kill:
            self.helper.send_signal(12345, signal.SIGKILL)
            mock_kill.assert_called_once_with(12345, signal.SIGKILL)

        with patch("os.kill", side_effect=OSError(errno.ESRCH, "No such process")):
            with self.assertRaises(OSError):
                self.helper.send_signal(12345, signal.SIGTERM)

    def test_try_reap_tri_state(self) -> None:
        with patch("os.waitpid", return_value=(12345, 0)) as mock_wait:
            self.assertTrue(self.helper.try_reap(12345))
            mock_wait.assert_called_once_with(12345, os.WNOHANG)

        with patch("os.waitpid", return_value=(0, 0)):
            self.assertFalse(self.helper.try_reap(12345))

        with patch("os.waitpid", return_value=(54321, 0)):
            self.assertFalse(self.helper.try_reap(12345))

        with patch("os.waitpid", side_effect=ChildProcessError(errno.ECHILD, "No child")):
            self.assertIsNone(self.helper.try_reap(12345))

        for err in [
            OSError(errno.EINTR, "Interrupted"),
            OSError(errno.ESRCH, "No process"),
            OSError(errno.EINVAL, "Invalid argument"),
            TypeError("an integer is required"),
        ]:
            with self.subTest(err=err):
                with patch("os.waitpid", side_effect=err):
                    self.assertIsNone(self.helper.try_reap(12345))

        # R3-001 authorized replacement: invalid PID inputs no longer forward to waitpid;
        # verified in test_try_reap_invalid_pid_matrix_rejects_without_waitpid below.

    def test_try_reap_invalid_pid_matrix_rejects_without_waitpid(self) -> None:
        """R3-001: strict int > 0 PID check returns None without invoking os.waitpid."""
        class IntSubclass(int):
            pass

        invalid_pids = [
            0,
            -1,
            -99,
            True,
            False,
            3.14,
            float("nan"),
            "12345",
            "",
            None,
            IntSubclass(10),
        ]
        with patch("os.waitpid") as mock_wait:
            for bad_pid in invalid_pids:
                with self.subTest(bad_pid=bad_pid):
                    self.assertIsNone(self.helper.try_reap(bad_pid))  # type: ignore[arg-type]
                    mock_wait.assert_not_called()
