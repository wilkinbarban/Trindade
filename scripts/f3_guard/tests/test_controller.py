"""Hermetic unit tests for ProcessController composite class."""

from __future__ import annotations

import os
import select
import signal
import sys
import unittest
from unittest.mock import MagicMock, mock_open, patch

from scripts.f3_guard.pidfd_binding import (
    BindingState,
    PidfdBinding,
    PidfdBindingHelper,
)
from scripts.f3_guard.pidfd_ops import PidfdOpsHelper
from scripts.f3_guard.proc_inspection import (
    INSPECTION_ERROR,
    ProcInspectionHelper,
    _INSPECTION_ERROR,
)
from scripts.f3_guard.types import ProcessIdentity

try:
    from scripts.f3_guard.controller import ProcessController
except ImportError:
    ProcessController = None  # type: ignore[assignment,misc]


class TestProcessController(unittest.TestCase):
    """Behavioral unit test suite verifying ProcessController composition and contracts."""

    def setUp(self) -> None:
        if ProcessController is None:
            self.fail("ProcessController could not be imported")
        self.controller = ProcessController()

    def test_inheritance_and_defaults(self) -> None:
        """Each helper class is inherited directly, and default attributes are preserved."""
        self.assertTrue(issubclass(ProcessController, ProcInspectionHelper))
        self.assertTrue(issubclass(ProcessController, PidfdBindingHelper))
        self.assertTrue(issubclass(ProcessController, PidfdOpsHelper))

        self.assertFalse(self.controller.is_direct_child)
        self.assertFalse(self.controller.use_pidfd)
        self.assertEqual(self.controller.binding_state, BindingState.NEVER_BOUND)
        self.assertIsNone(self.controller._bound_pidfd)
        self.assertIsNone(self.controller._cleanup_error)

    def test_public_api_method_bindings(self) -> None:
        """All 17 public methods and binding_state property match helper implementations."""
        proc_methods = [
            "get_exe_path", "get_ppid", "get_start_ticks", "get_uid",
            "get_cmdline", "get_fdinfo_pid", "get_process_state",
        ]
        for name in proc_methods:
            self.assertIs(getattr(ProcessController, name), getattr(ProcInspectionHelper, name))

        binding_methods = [
            "close_fd", "bind_anchored_pidfd", "consume_bound_pidfd",
            "cleanup_bound_pidfd", "adopt_pidfd",
        ]
        for name in binding_methods:
            self.assertIs(getattr(ProcessController, name), getattr(PidfdBindingHelper, name))

        ops_methods = [
            "is_pidfd_alive", "send_signal", "pidfd_open",
            "pidfd_send_signal", "try_reap",
        ]
        for name in ops_methods:
            self.assertIs(getattr(ProcessController, name), getattr(PidfdOpsHelper, name))

        self.assertIs(ProcessController.binding_state, PidfdBindingHelper.binding_state)

    def test_adopt_pidfd_start_ticks_dispatch_and_overrides(self) -> None:
        """adopt_pidfd dispatches to get_start_ticks with tick reads, fallback, overrides, and sentinel."""
        valid_stat = "100 (proc) S " + " ".join(["0"] * 18) + " 54321 0\n"
        with patch("builtins.open", mock_open(read_data=valid_stat)):
            c1 = ProcessController()
            c1.adopt_pidfd(100, 55)
            self.assertEqual(c1.binding_state, BindingState.BOUND)
            self.assertEqual(c1._bound_pidfd, PidfdBinding(100, 54321, 55))

        with patch("builtins.open", side_effect=FileNotFoundError):
            c2 = ProcessController()
            c2.adopt_pidfd(101, 56)
            self.assertEqual(c2.binding_state, BindingState.BOUND)
            self.assertEqual(c2._bound_pidfd, PidfdBinding(101, 1, 56))

        with patch("builtins.open", mock_open(read_data="corrupt")):
            c3 = ProcessController()
            c3.adopt_pidfd(102, 57)
            self.assertEqual(c3.binding_state, BindingState.NEVER_BOUND)
            self.assertIsNone(c3._bound_pidfd)

        class CustomSubclass(ProcessController):
            def get_start_ticks(self, pid: int) -> int:
                return 88888

        c4 = CustomSubclass()
        c4.adopt_pidfd(103, 58)
        self.assertEqual(c4._bound_pidfd, PidfdBinding(103, 88888, 58))

        c5 = ProcessController()
        c5.get_start_ticks = lambda p: 99999  # type: ignore[assignment]
        c5.adopt_pidfd(104, 59)
        self.assertEqual(c5._bound_pidfd, PidfdBinding(104, 99999, 59))

        c6 = ProcessController()
        c6.get_start_ticks = None  # type: ignore[assignment]
        with self.assertRaises(TypeError):
            c6.adopt_pidfd(105, 60)

    def test_two_instances_state_isolation(self) -> None:
        """Distinct ProcessController instances maintain strictly isolated binding states."""
        c1 = ProcessController()
        c2 = ProcessController()
        self.assertTrue(c1.bind_anchored_pidfd(200, 1000, 77))
        self.assertEqual(c1.binding_state, BindingState.BOUND)
        self.assertEqual(c1._bound_pidfd, PidfdBinding(200, 1000, 77))

        self.assertEqual(c2.binding_state, BindingState.NEVER_BOUND)
        self.assertIsNone(c2._bound_pidfd)
        self.assertIsNone(c2._cleanup_error)

    def test_binding_lifecycle_close_and_cleanup_contracts(self) -> None:
        """Lifecycle methods handle consumption, descriptor closing, and cleanup errors."""
        target = ProcessIdentity(200, 1000, 1000, "avd", 5554, "qemu")
        mismatch_target = ProcessIdentity(200, 2000, 1000, "avd", 5554, "qemu")

        c1 = ProcessController()
        c1.bind_anchored_pidfd(200, 1000, 77)
        self.assertIsNone(c1.consume_bound_pidfd(mismatch_target))
        self.assertEqual(c1.binding_state, BindingState.IDENTITY_MISMATCH)
        self.assertEqual(c1.consume_bound_pidfd(target), 77)
        self.assertEqual(c1.binding_state, BindingState.CONSUMED)

        c2 = ProcessController()
        c2.bind_anchored_pidfd(200, 1000, 78)
        close_verified = False

        def close_hook(fd: int) -> None:
            nonlocal close_verified
            close_verified = (c2._bound_pidfd is None and c2.binding_state == BindingState.CLOSED)

        with patch("os.close", side_effect=close_hook) as mock_close:
            c2.close_fd(78)
            self.assertTrue(close_verified)
            mock_close.assert_called_once_with(78)

        c3 = ProcessController()
        c3.bind_anchored_pidfd(200, 1000, 79)
        err = OSError("EBADF")
        with patch("os.close", side_effect=err):
            self.assertIs(c3.cleanup_bound_pidfd(), err)
            self.assertEqual(c3.binding_state, BindingState.ERROR)
            self.assertIs(c3._cleanup_error, err)
            with patch("os.close") as mock_close_cached:
                self.assertIs(c3.cleanup_bound_pidfd(), err)
                mock_close_cached.assert_not_called()

    def test_os_dispatch_signals_and_pidfd(self) -> None:
        """ProcessController dispatches signals and pidfd operations to system interfaces."""
        with patch("os.kill") as mock_kill:
            self.controller.send_signal(300, signal.SIGTERM)
            mock_kill.assert_called_once_with(300, signal.SIGTERM)

        mock_p = MagicMock()
        mock_p.poll.return_value = []
        expected_flags = select.POLLIN | select.POLLHUP | select.POLLERR | getattr(select, "POLLNVAL", 0)
        with patch("select.poll", return_value=mock_p):
            self.assertTrue(self.controller.is_pidfd_alive(10))
            mock_p.register.assert_called_once_with(10, expected_flags)
            mock_p.poll.assert_called_once_with(0)

        with patch("os.pidfd_open", create=True, return_value=88) as mock_open:
            self.assertEqual(self.controller.pidfd_open(300), 88)
            mock_open.assert_called_once_with(300, 0)

        with patch("signal.pidfd_send_signal", create=True, return_value=None) as mock_sig:
            self.assertTrue(self.controller.pidfd_send_signal(88, signal.SIGKILL))
            mock_sig.assert_called_once_with(88, signal.SIGKILL)

    def test_try_reap_positive_and_strict_invalid_guards(self) -> None:
        """try_reap implements positive PID tri-state and strict type/value validation guard."""
        with patch("os.waitpid", return_value=(300, 0)) as mock_w:
            self.assertTrue(self.controller.try_reap(300))
            mock_w.assert_called_once_with(300, os.WNOHANG)

        with patch("os.waitpid", return_value=(0, 0)):
            self.assertFalse(self.controller.try_reap(300))

        with patch("os.waitpid", side_effect=ChildProcessError):
            self.assertIsNone(self.controller.try_reap(300))

        invalid_inputs = [0, -1, -100, True, False, None, "300", 300.0, [300]]
        for val in invalid_inputs:
            with self.subTest(val=val):
                with patch("os.waitpid") as mock_w_invalid:
                    self.assertIsNone(self.controller.try_reap(val))  # type: ignore[arg-type]
                    mock_w_invalid.assert_not_called()

    def test_inspection_proc_methods(self) -> None:
        """All 7 inspection methods are reachable through ProcessController on mocked fixtures."""
        with patch("os.readlink", return_value="/bin/app") as mock_rl:
            self.assertEqual(self.controller.get_exe_path(400), "/bin/app")
            mock_rl.assert_called_once_with("/proc/400/exe")

        with patch("builtins.open", mock_open(read_data="400 (proc) S 123 0 0\n")):
            self.assertEqual(self.controller.get_ppid(400), 123)

        ticks_stat = "400 (proc) S " + " ".join(["0"] * 18) + " 7777 0\n"
        with patch("builtins.open", mock_open(read_data=ticks_stat)):
            self.assertEqual(self.controller.get_start_ticks(400), 7777)

        mock_st = MagicMock()
        mock_st.st_uid = 1000
        with patch("os.stat", return_value=mock_st) as mock_stat:
            self.assertEqual(self.controller.get_uid(400), 1000)
            mock_stat.assert_called_once_with("/proc/400")

        with patch("builtins.open", mock_open(read_data=b"arg1\x00arg2\x00")):
            self.assertEqual(self.controller.get_cmdline(400), ["arg1", "arg2"])

        with patch("builtins.open", mock_open(read_data="Pid:\t400\n")):
            self.assertEqual(self.controller.get_fdinfo_pid(5), 400)

        with patch("builtins.open", mock_open(read_data="State:\tS (sleeping)\n")):
            self.assertEqual(self.controller.get_process_state(400), "S")
