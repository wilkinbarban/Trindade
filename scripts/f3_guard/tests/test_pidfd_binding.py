"""Hermetic unit tests for PidfdBindingHelper, BindingState, and PidfdBinding."""

from __future__ import annotations

import os
import unittest
from unittest.mock import MagicMock, patch

from scripts.f3_guard.pidfd_binding import (
    BindingState,
    PidfdBinding,
    PidfdBindingHelper,
)
from scripts.f3_guard.types import ProcessIdentity


class TestPidfdBindingTypes(unittest.TestCase):
    """Contracts for BindingState enum and PidfdBinding value object."""

    def test_binding_state_enum_values(self) -> None:
        expected = {"NEVER_BOUND", "BOUND", "CONSUMED", "IDENTITY_MISMATCH", "CLOSED", "ERROR"}
        self.assertEqual({s.value for s in BindingState}, expected)

    def test_pidfd_binding_immutability(self) -> None:
        binding = PidfdBinding(pid=12345, start_ticks=987654, fd=77)
        self.assertEqual((binding.pid, binding.start_ticks, binding.fd), (12345, 987654, 77))
        with self.assertRaises((AttributeError, TypeError)):
            binding.fd = 88  # type: ignore[misc]


class TestPidfdBindingLifecycle(unittest.TestCase):
    """Exclusive anchored binding, consumption, and descriptor cleanup."""

    def setUp(self) -> None:
        self.helper = PidfdBindingHelper()
        self.target = ProcessIdentity(12345, 987654, 1000, "AVD", 5556, "bin")

    def test_bind_anchored_pidfd_success_and_rejections(self) -> None:
        self.assertEqual(self.helper.binding_state, BindingState.NEVER_BOUND)
        self.assertIsNone(self.helper._bound_pidfd)
        self.assertTrue(self.helper.bind_anchored_pidfd(12345, 987654, 77))
        self.assertEqual(self.helper.binding_state, BindingState.BOUND)
        self.assertEqual(self.helper._bound_pidfd, PidfdBinding(12345, 987654, 77))

        # Rejects duplicate/overwrite
        self.assertFalse(self.helper.bind_anchored_pidfd(12345, 987654, 88))
        self.assertFalse(self.helper.bind_anchored_pidfd(54321, 111111, 88))

        # Rejects invalid input types / values
        bad_inputs = [
            (0, 987654, 77), (-1, 987654, 77), (True, 987654, 77),
            (12345, 0, 77), (12345, -1, 77), (12345, True, 77),
            (12345, 987654, -1), (12345, 987654, True), (12345, 987654, "77"),
        ]
        for pid, ticks, fd in bad_inputs:
            with self.subTest(pid=pid, ticks=ticks, fd=fd):
                self.assertFalse(PidfdBindingHelper().bind_anchored_pidfd(pid, ticks, fd))

    def test_consume_bound_pidfd_contracts(self) -> None:
        target_bad_pid = ProcessIdentity(54321, 987654, 1000, "AVD", 5556, "bin")
        target_bad_ticks = ProcessIdentity(12345, 111111, 1000, "AVD", 5556, "bin")

        self.assertIsNone(self.helper.consume_bound_pidfd(self.target))
        self.helper.bind_anchored_pidfd(12345, 987654, 77)
        self.assertIsNone(self.helper.consume_bound_pidfd(target_bad_pid))
        self.assertEqual(self.helper.binding_state, BindingState.IDENTITY_MISMATCH)
        self.assertIsNotNone(self.helper._bound_pidfd)

        self.assertIsNone(self.helper.consume_bound_pidfd(target_bad_ticks))
        self.assertEqual(self.helper.binding_state, BindingState.IDENTITY_MISMATCH)

        # Successful consume transitions to CONSUMED and clears binding
        self.assertEqual(self.helper.consume_bound_pidfd(self.target), 77)
        self.assertEqual(self.helper.binding_state, BindingState.CONSUMED)
        self.assertIsNone(self.helper._bound_pidfd)
        self.assertIsNone(self.helper.consume_bound_pidfd(self.target))

    def test_close_fd_invalidation_and_errors(self) -> None:
        self.helper.bind_anchored_pidfd(12345, 987654, 77)
        in_mock_checked = False

        def close_mock(fd: int) -> None:
            nonlocal in_mock_checked
            self.assertIsNone(self.helper._bound_pidfd)
            self.assertEqual(self.helper.binding_state, BindingState.CLOSED)
            in_mock_checked = True

        with patch("os.close", side_effect=close_mock) as mock_close:
            self.helper.close_fd(77)
            self.assertTrue(in_mock_checked)
            mock_close.assert_called_once_with(77)

        h2 = PidfdBindingHelper()
        h2.bind_anchored_pidfd(12345, 987654, 78)
        with patch("os.close", side_effect=OSError("EBADF")):
            with self.assertRaises(OSError):
                h2.close_fd(78)
            self.assertEqual(h2.binding_state, BindingState.ERROR)
            self.assertIsNone(h2._bound_pidfd)

        h3 = PidfdBindingHelper()
        h3.bind_anchored_pidfd(12345, 987654, 77)
        with patch("os.close") as mock_close:
            h3.close_fd(99)
            mock_close.assert_called_once_with(99)
            self.assertEqual(h3.binding_state, BindingState.BOUND)
            self.assertIsNotNone(h3._bound_pidfd)

    def test_cleanup_bound_pidfd_contracts(self) -> None:
        with patch("os.close") as mock_close:
            self.assertIsNone(self.helper.cleanup_bound_pidfd())
            mock_close.assert_not_called()

        self.helper.bind_anchored_pidfd(12345, 987654, 77)
        in_mock_checked = False

        def close_check(fd: int) -> None:
            nonlocal in_mock_checked
            self.assertIsNone(self.helper._bound_pidfd)
            self.assertEqual(self.helper.binding_state, BindingState.CLOSED)
            in_mock_checked = True

        with patch("os.close", side_effect=close_check) as mock_close:
            self.assertIsNone(self.helper.cleanup_bound_pidfd())
            self.assertTrue(in_mock_checked)
            self.assertIsNone(self.helper.cleanup_bound_pidfd())
            mock_close.assert_called_once_with(77)

        h2 = PidfdBindingHelper()
        h2.bind_anchored_pidfd(12345, 987654, 77)
        eio_err = OSError("EIO")
        with patch("os.close", side_effect=eio_err) as mock_close:
            self.assertIs(h2.cleanup_bound_pidfd(), eio_err)
            self.assertEqual(h2.binding_state, BindingState.ERROR)
            self.assertIs(h2.cleanup_bound_pidfd(), eio_err)
            mock_close.assert_called_once_with(77)

        h3 = PidfdBindingHelper()
        h3.bind_anchored_pidfd(12345, 987654, 77)
        kb_err = KeyboardInterrupt("ctrl-c")
        with patch("os.close", side_effect=kb_err) as mock_close:
            with self.assertRaises(KeyboardInterrupt):
                h3.cleanup_bound_pidfd()
            self.assertIs(h3.cleanup_bound_pidfd(), kb_err)
            mock_close.assert_called_once_with(77)

        h4 = PidfdBindingHelper()
        h4.bind_anchored_pidfd(12345, 987654, 77)
        sys_exit = SystemExit(2)
        with patch("os.close", side_effect=sys_exit) as mock_close:
            with self.assertRaises(SystemExit):
                h4.cleanup_bound_pidfd()
            self.assertIs(h4.cleanup_bound_pidfd(), sys_exit)
            mock_close.assert_called_once_with(77)

    def test_adopt_pidfd_contracts(self) -> None:
        # 1. Default getter fallback (lambda p: 1)
        h1 = PidfdBindingHelper()
        h1.adopt_pidfd(12345, 77)
        self.assertEqual(h1.binding_state, BindingState.BOUND)
        self.assertEqual(h1._bound_pidfd, PidfdBinding(12345, 1, 77))

        # 2. Custom valid getter
        h2 = PidfdBindingHelper()
        h2.get_start_ticks = lambda p: 987654
        h2.adopt_pidfd(12345, 88)
        self.assertEqual(h2.binding_state, BindingState.BOUND)
        self.assertEqual(h2._bound_pidfd, PidfdBinding(12345, 987654, 88))

        # 3. Getter returning 0 -> 0 or 1 evaluates to 1
        h3 = PidfdBindingHelper()
        h3.get_start_ticks = lambda p: 0
        h3.adopt_pidfd(12345, 99)
        self.assertEqual(h3.binding_state, BindingState.BOUND)
        self.assertEqual(h3._bound_pidfd, PidfdBinding(12345, 1, 99))

        # 4. Getter returning None -> None or 1 evaluates to 1
        h4 = PidfdBindingHelper()
        h4.get_start_ticks = lambda p: None
        h4.adopt_pidfd(12345, 100)
        self.assertEqual(h4.binding_state, BindingState.BOUND)
        self.assertEqual(h4._bound_pidfd, PidfdBinding(12345, 1, 100))

        # 5. Getter returning sentinel object -> fails isinstance int
        sentinel = object()
        h5 = PidfdBindingHelper()
        h5.get_start_ticks = lambda p: sentinel
        h5.adopt_pidfd(12345, 101)
        self.assertEqual(h5.binding_state, BindingState.NEVER_BOUND)
        self.assertIsNone(h5._bound_pidfd)

        # 6. Getter raising exception -> propagates
        h6 = PidfdBindingHelper()
        h6.get_start_ticks = MagicMock(side_effect=RuntimeError("proc error"))
        with self.assertRaises(RuntimeError):
            h6.adopt_pidfd(12345, 102)

        # 7. Regression: explicit own get_start_ticks = None raises TypeError
        h7 = PidfdBindingHelper()
        h7.get_start_ticks = None
        with self.assertRaises(TypeError):
            h7.adopt_pidfd(12345, 103)
