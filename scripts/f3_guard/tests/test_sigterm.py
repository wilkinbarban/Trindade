"""Hermetic unit tests for post-signal process termination polling."""

from __future__ import annotations

import math
import os
import time
import unittest
from typing import List, Optional
from unittest.mock import MagicMock, patch

from scripts.f3_guard.controller import ProcessController
from scripts.f3_guard.pidfd_binding import BindingState, PidfdBinding
from scripts.f3_guard.proc_inspection import INSPECTION_ERROR, _INSPECTION_ERROR
from scripts.f3_guard.sigterm import (
    DEFAULT_GRACE_SEC,
    _acquire_target_pidfd,
    _close_pidfd,
    poll_process_termination,
    safe_sigterm,
)
from scripts.f3_guard.types import LifecycleState, ProcessIdentity


class TestPollProcessTermination(unittest.TestCase):
    """Complete hermetic verification for poll_process_termination contracts."""

    def setUp(self) -> None:
        self.fake_time = [1000.0]
        sp = patch.object(
            time,
            "sleep",
            side_effect=lambda s: self.fake_time.__setitem__(0, self.fake_time[0] + max(0.001, float(s))),
        )
        mp = patch.object(time, "monotonic", side_effect=lambda: self.fake_time[0])
        sp.start()
        mp.start()
        self.addCleanup(sp.stop)
        self.addCleanup(mp.stop)

        kp = patch("os.kill", side_effect=AssertionError("Unexpected real os.kill call"))
        kp.start()
        self.addCleanup(kp.stop)

        orig_open = open

        def _guarded_open(file, *args, **kwargs):
            try:
                path = os.fspath(file)
                if isinstance(path, str) and path.startswith("/proc"):
                    raise AssertionError(f"Unexpected real /proc open: {file}")
            except TypeError:
                pass
            return orig_open(file, *args, **kwargs)

        op = patch("builtins.open", side_effect=_guarded_open)
        op.start()
        self.addCleanup(op.stop)

        self.target = ProcessIdentity(
            pid=12345,
            start_ticks=987654,
            uid=1000,
            avd="Trindade_UX_v030",
            port=5556,
            binary_name="qemu-system-x86_64",
            is_direct_child=False,
        )
        self.controller = MagicMock(spec=ProcessController)
        self.controller.is_direct_child = False

    def test_default_grace_constant(self) -> None:
        self.assertEqual(DEFAULT_GRACE_SEC, 20.0)

    def test_clock_order_sleep_before_inspection(self) -> None:
        events: List[str] = []

        def mock_sleep_record(sec: float) -> None:
            events.append(f"sleep:{sec}")
            self.fake_time[0] += max(0.001, float(sec))

        def record_ticks(pid: int) -> None:
            events.append(f"ticks:{pid}")

        def record_state(pid: int) -> None:
            events.append(f"state:{pid}")

        with patch.object(time, "sleep", side_effect=mock_sleep_record):
            self.controller.get_start_ticks.side_effect = record_ticks
            self.controller.get_process_state.side_effect = record_state

            state, reason = poll_process_termination(
                self.target,
                self.controller,
                reason="order-check",
                grace_seconds=10.0,
                poll_interval=0.25,
            )

        self.assertEqual(state, LifecycleState.STOPPED)
        self.assertEqual(reason, "Process gracefully exited (order-check)")
        self.assertEqual(events, ["sleep:0.25", "ticks:12345", "state:12345"])

    def test_absence_non_child_returns_gracefully_exited(self) -> None:
        self.controller.is_direct_child = False
        self.controller.get_start_ticks.return_value = None
        self.controller.get_process_state.return_value = None

        state, reason = poll_process_termination(
            self.target,
            self.controller,
            reason="clean teardown",
            grace_seconds=5.0,
            poll_interval=0.5,
        )

        self.assertEqual(state, LifecycleState.STOPPED)
        self.assertEqual(reason, "Process gracefully exited (clean teardown)")
        self.controller.try_reap.assert_not_called()

    def _assert_reap_matrix(
        self,
        *,
        active_ticks: Optional[int],
        process_state: Optional[str],
        reason_label: str,
        success_msg: str,
        pending_msg: str,
        failed_msg: str,
    ) -> None:
        cases = [
            (True, LifecycleState.STOPPED, success_msg),
            (False, LifecycleState.ZOMBIE_PENDING, pending_msg),
            (None, LifecycleState.ZOMBIE_UNREAPED, failed_msg),
            (OSError("waitpid error"), LifecycleState.ZOMBIE_UNREAPED, failed_msg),
            (RuntimeError("reap error"), LifecycleState.ZOMBIE_UNREAPED, failed_msg),
        ]
        self.controller.is_direct_child = True
        self.controller.get_start_ticks.return_value = active_ticks
        self.controller.get_process_state.return_value = process_state

        for result_or_exc, expected_state, expected_msg in cases:
            with self.subTest(result=result_or_exc):
                self.controller.try_reap.reset_mock()
                if isinstance(result_or_exc, Exception):
                    self.controller.try_reap.side_effect = result_or_exc
                else:
                    self.controller.try_reap.side_effect = None
                    self.controller.try_reap.return_value = result_or_exc

                state, reason = poll_process_termination(
                    self.target,
                    self.controller,
                    reason=reason_label,
                    grace_seconds=5.0,
                    poll_interval=0.5,
                )
                self.assertEqual(state, expected_state)
                self.assertEqual(reason, expected_msg)
                self.controller.try_reap.assert_called_once_with(self.target.pid)

    def test_absence_direct_child_reap_matrix(self) -> None:
        self._assert_reap_matrix(
            active_ticks=None,
            process_state=None,
            reason_label="child exit",
            success_msg="Process exited and reaped (child exit)",
            pending_msg="Process absent but reap pending (child exit)",
            failed_msg="Process absent but reap failed (child exit)",
        )

    def test_inspection_error_ticks_continues_polling(self) -> None:
        for sentinel in (_INSPECTION_ERROR, INSPECTION_ERROR):
            with self.subTest(sentinel=sentinel):
                ticks_responses = [sentinel, sentinel, None]
                self.controller.get_start_ticks.side_effect = ticks_responses
                self.controller.get_process_state.return_value = "S"

                state, reason = poll_process_termination(
                    self.target,
                    self.controller,
                    reason="transient err",
                    grace_seconds=10.0,
                    poll_interval=0.5,
                )
                self.assertEqual(state, LifecycleState.STOPPED)
                self.assertEqual(reason, "Process gracefully exited (transient err)")

        with self.subTest(persistent=_INSPECTION_ERROR):
            self.fake_time[0] = 1000.0
            self.controller.get_start_ticks.side_effect = None
            self.controller.get_start_ticks.return_value = _INSPECTION_ERROR
            self.controller.get_process_state.return_value = "INSPECTION_ERROR"

            state, reason = poll_process_termination(
                self.target,
                self.controller,
                reason="persistent err",
                grace_seconds=2.0,
                poll_interval=0.5,
            )

            self.assertNotEqual(state, LifecycleState.STOPPED)
            self.assertEqual(state, LifecycleState.TERMINATION_FAILED)
            self.assertEqual(
                reason,
                "Process still alive after 2.0s grace period. SIGKILL prohibited by policy.",
            )

    def test_pid_recycled_returns_pid_mismatch_matrix(self) -> None:
        matrix = [
            (999999, "R"),
            (1, "R"),
            (0, "R"),
            (1000000, "R"),
            (999999, "INSPECTION_ERROR"),
        ]
        for recycled_ticks, proc_state in matrix:
            with self.subTest(recycled_ticks=recycled_ticks, state=proc_state):
                self.controller.get_start_ticks.return_value = recycled_ticks
                self.controller.get_process_state.return_value = proc_state

                state, reason = poll_process_termination(
                    self.target,
                    self.controller,
                    reason="recycle check",
                    grace_seconds=5.0,
                    poll_interval=0.5,
                )

                self.assertEqual(state, LifecycleState.PID_MISMATCH)
                self.assertEqual(reason, "Process PID recycled by OS during termination")

    def test_inspection_error_state_continues_polling(self) -> None:
        ticks_responses = [987654, 987654, None]
        state_responses = ["INSPECTION_ERROR", "INSPECTION_ERROR", None]
        self.controller.get_start_ticks.side_effect = ticks_responses
        self.controller.get_process_state.side_effect = state_responses

        state, reason = poll_process_termination(
            self.target,
            self.controller,
            reason="transient state err",
            grace_seconds=10.0,
            poll_interval=0.5,
        )

        self.assertEqual(state, LifecycleState.STOPPED)
        self.assertEqual(reason, "Process gracefully exited (transient state err)")
        self.assertEqual(self.controller.get_process_state.call_count, 3)

    def test_zombie_non_direct_child_unreaped_without_reap_call(self) -> None:
        self.controller.is_direct_child = False
        self.controller.get_start_ticks.return_value = 987654
        self.controller.get_process_state.return_value = "Z"

        state, reason = poll_process_termination(
            self.target,
            self.controller,
            reason="remote proc",
            grace_seconds=5.0,
            poll_interval=0.5,
        )

        self.assertEqual(state, LifecycleState.ZOMBIE_UNREAPED)
        self.assertEqual(
            reason,
            "Process entered zombie state (Z) but supervisor is not direct parent to reap it (remote proc)",
        )
        self.controller.try_reap.assert_not_called()

    def test_zombie_direct_child_reap_matrix(self) -> None:
        self._assert_reap_matrix(
            active_ticks=987654,
            process_state="Z",
            reason_label="zombie check",
            success_msg="Zombie child successfully reaped (zombie check)",
            pending_msg="Zombie child reap pending (zombie check)",
            failed_msg="Zombie child reap failed (zombie check)",
        )

    def test_timeout_prohibits_sigkill_and_respects_min_grace(self) -> None:
        self.controller.get_start_ticks.return_value = 987654
        self.controller.get_process_state.return_value = "S"

        for grace, expected_msg in [
            (2.0, "Process still alive after 2.0s grace period. SIGKILL prohibited by policy."),
            (-5.0, "Process still alive after -5.0s grace period. SIGKILL prohibited by policy."),
        ]:
            with self.subTest(grace=grace):
                state, reason = poll_process_termination(
                    self.target,
                    self.controller,
                    reason="timeout test",
                    grace_seconds=grace,
                    poll_interval=0.5,
                )
                self.assertEqual(state, LifecycleState.TERMINATION_FAILED)
                self.assertEqual(reason, expected_msg)

        below_boundary = math.nextafter(0.01, -math.inf)
        timing_cases = [
            ("min_negative_grace_discriminates_no_clamp", -1.0, [0.0, below_boundary, 0.01], 1),
            ("min_negative_grace_exact_deadline_zero_iterations", -1.0, [0.0, 0.01], 0),
            ("initial_expired_deadline_zero_iterations", 0.01, [1000.0, 1000.05], 0),
        ]
        for label, grace, clocks, expected_count in timing_cases:
            with self.subTest(timing=label):
                self.controller.get_start_ticks.reset_mock()
                self.controller.get_process_state.reset_mock()
                with patch.object(time, "monotonic", side_effect=clocks), patch.object(
                    time, "sleep"
                ) as mock_sleep:
                    state, reason = poll_process_termination(
                        self.target,
                        self.controller,
                        grace_seconds=grace,
                        poll_interval=0.001,
                    )
                self.assertEqual(state, LifecycleState.TERMINATION_FAILED)
                self.assertEqual(
                    reason,
                    f"Process still alive after {grace}s grace period. SIGKILL prohibited by policy.",
                )
                self.assertEqual(self.controller.get_start_ticks.call_count, expected_count)
                self.assertEqual(self.controller.get_process_state.call_count, expected_count)
                self.assertEqual(mock_sleep.call_count, expected_count)

        self.assertFalse(hasattr(self.controller, "kill"))
        self.controller.send_signal.assert_not_called()
        if hasattr(self.controller, "pidfd_send_signal"):
            self.controller.pidfd_send_signal.assert_not_called()

    def test_custom_poll_interval_passed_to_sleep(self) -> None:
        sleep_intervals: List[float] = []

        def mock_sleep(sec: float) -> None:
            sleep_intervals.append(sec)
            self.fake_time[0] += max(0.001, float(sec))

        with patch.object(time, "sleep", side_effect=mock_sleep):
            self.controller.get_start_ticks.return_value = None
            self.controller.get_process_state.return_value = None

            poll_process_termination(
                self.target,
                self.controller,
                grace_seconds=5.0,
                poll_interval=0.125,
            )

        self.assertEqual(sleep_intervals, [0.125])


class TestClosePidfdHelper(unittest.TestCase):
    """Hermetic unit tests for private _close_pidfd helper contracts."""

    def setUp(self) -> None:
        self.patcher_close = patch("os.close", side_effect=AssertionError("Direct os.close forbidden"))
        self.patcher_close.start()
        self.addCleanup(self.patcher_close.stop)
        self.controller = ProcessController()

    def test_close_valid_descriptors(self) -> None:
        for fd in (77, 0):
            with self.subTest(fd=fd):
                mock_close = MagicMock()
                self.controller.close_fd = mock_close
                err = _close_pidfd(fd, self.controller)
                self.assertIsNone(err)
                mock_close.assert_called_once_with(fd)

    def test_close_invalid_descriptors_ignored(self) -> None:
        mock_close = MagicMock()
        self.controller.close_fd = mock_close
        for invalid_fd in (None, True, False, -1, -99, 3.14, "77", [77]):
            with self.subTest(invalid_fd=invalid_fd):
                mock_close.reset_mock()
                err = _close_pidfd(invalid_fd, self.controller)
                self.assertIsNone(err)
                mock_close.assert_not_called()

    def test_close_missing_close_method_ignored(self) -> None:
        class NoCloseController:
            pass

        err = _close_pidfd(77, NoCloseController())
        self.assertIsNone(err)

    def test_close_returns_exact_exception(self) -> None:
        exc = OSError("EBADF")
        self.controller.close_fd = MagicMock(side_effect=exc)
        res = _close_pidfd(77, self.controller)
        self.assertIs(res, exc)

    def test_close_does_not_catch_interrupts(self) -> None:
        for exc_cls in (KeyboardInterrupt, SystemExit):
            with self.subTest(exc=exc_cls):
                self.controller.close_fd = MagicMock(side_effect=exc_cls())
                with self.assertRaises(exc_cls):
                    _close_pidfd(77, self.controller)


class TestAcquireTargetPidfdHelper(unittest.TestCase):
    """Hermetic unit tests for private _acquire_target_pidfd helper contracts."""

    def setUp(self) -> None:
        self.target = ProcessIdentity(
            pid=12345,
            start_ticks=987654,
            uid=1000,
            avd="Trindade_UX_v030",
            port=5556,
            binary_name="emulator",
            is_direct_child=True,
        )
        self.controller = ProcessController()
        self.controller.use_pidfd = True

        p_close = patch("os.close", side_effect=AssertionError("os.close forbidden"))
        p_kill = patch("os.kill", side_effect=AssertionError("os.kill forbidden"))
        p_open = patch("os.pidfd_open", side_effect=AssertionError("os.pidfd_open forbidden"))
        p_send = patch("signal.pidfd_send_signal", side_effect=AssertionError("signal forbidden"))
        for p in (p_close, p_kill, p_open, p_send):
            p.start()
            self.addCleanup(p.stop)

    def test_disabled_pidfd_mode_fails_closed(self) -> None:
        for val in (False, None, 0, "true"):
            with self.subTest(use_pidfd=val):
                ctrl = ProcessController()
                ctrl.use_pidfd = val  # type: ignore[assignment]
                ctrl.pidfd_open = MagicMock()
                ctrl.consume_bound_pidfd = MagicMock()

                fd, err = _acquire_target_pidfd(self.target, ctrl)
                self.assertIsNone(fd)
                self.assertEqual(
                    err,
                    (LifecycleState.ERROR, "pidfd mode required; refusing PID-based signal to prevent race"),
                )
                ctrl.pidfd_open.assert_not_called()
                ctrl.consume_bound_pidfd.assert_not_called()

    def test_binding_state_resolution_variants(self) -> None:
        self.controller.bind_anchored_pidfd(12345, 987654, 77)
        self.assertEqual(self.controller.binding_state, BindingState.BOUND)
        fd, err = _acquire_target_pidfd(self.target, self.controller)
        self.assertEqual(fd, 77)
        self.assertIsNone(err)

        ctrl2 = ProcessController()
        ctrl2.use_pidfd = True
        ctrl2._bound_pidfd = PidfdBinding(12345, 987654, 88)
        ctrl2._binding_state = None  # type: ignore[assignment]
        ctrl2.consume_bound_pidfd = MagicMock(return_value=88)
        fd2, err2 = _acquire_target_pidfd(self.target, ctrl2)
        self.assertEqual(fd2, 88)
        self.assertIsNone(err2)
        ctrl2.consume_bound_pidfd.assert_called_once_with(self.target)

        class DuckController:
            use_pidfd = True

            def pidfd_open(self, pid: int) -> int:
                return 99

        duck = DuckController()
        fd3, err3 = _acquire_target_pidfd(self.target, duck)  # type: ignore[arg-type]
        self.assertEqual(fd3, 99)
        self.assertIsNone(err3)

        ctrl4 = ProcessController()
        ctrl4.use_pidfd = True
        ctrl4._binding_state = "UNKNOWN_STATE"  # type: ignore[assignment]
        fd4, err4 = _acquire_target_pidfd(self.target, ctrl4)
        self.assertIsNone(fd4)
        self.assertEqual(
            err4,
            (LifecycleState.ERROR, "Bound pidfd transfer failed or invalid (UNKNOWN_STATE); refusing reopen"),
        )

        # 5. BOUND state but missing consume_bound_pidfd method
        class NoConsumeBoundCtrl:
            use_pidfd = True
            binding_state = BindingState.BOUND

        fd5, err5 = _acquire_target_pidfd(self.target, NoConsumeBoundCtrl())  # type: ignore[arg-type]
        self.assertIsNone(fd5)
        self.assertEqual(
            err5,
            (LifecycleState.ERROR, "Bound pidfd transfer failed or invalid (BOUND); refusing reopen"),
        )

    def test_bound_successful_transfer_no_reopen(self) -> None:
        self.controller.bind_anchored_pidfd(12345, 987654, 77)
        self.controller.pidfd_open = MagicMock()
        fd, err = _acquire_target_pidfd(self.target, self.controller)
        self.assertEqual(fd, 77)
        self.assertIsNone(err)
        self.controller.pidfd_open.assert_not_called()

    def test_bound_invalid_transfer_refuses_reopen(self) -> None:
        for invalid_val in (None, True, False, -1, "invalid", 3.14):
            with self.subTest(val=invalid_val):
                ctrl = ProcessController()
                ctrl.use_pidfd = True
                ctrl.bind_anchored_pidfd(12345, 987654, 77)
                ctrl.consume_bound_pidfd = MagicMock(return_value=invalid_val)
                ctrl.pidfd_open = MagicMock()

                fd, err = _acquire_target_pidfd(self.target, ctrl)
                self.assertIsNone(fd)
                self.assertIsNotNone(err)
                state, msg = err  # type: ignore[misc]
                self.assertEqual(state, LifecycleState.ERROR)
                self.assertIn("Bound pidfd transfer failed or invalid", msg)
                self.assertIn("refusing reopen", msg)
                ctrl.pidfd_open.assert_not_called()

    def test_consume_keyboard_interrupt_tags_error_and_reraises(self) -> None:
        self.controller.bind_anchored_pidfd(12345, 987654, 77)
        self.controller.consume_bound_pidfd = MagicMock(side_effect=KeyboardInterrupt("Ctrl-C"))
        with self.assertRaises(KeyboardInterrupt):
            _acquire_target_pidfd(self.target, self.controller)
        self.assertEqual(self.controller.binding_state, BindingState.ERROR)

    def test_consume_system_exit_tags_error_and_reraises(self) -> None:
        self.controller.bind_anchored_pidfd(12345, 987654, 77)
        self.controller.consume_bound_pidfd = MagicMock(side_effect=SystemExit(2))
        with self.assertRaises(SystemExit):
            _acquire_target_pidfd(self.target, self.controller)
        self.assertEqual(self.controller.binding_state, BindingState.ERROR)

    def test_consume_ordinary_exception_tags_error_and_returns_error(self) -> None:
        self.controller.bind_anchored_pidfd(12345, 987654, 77)
        self.controller.consume_bound_pidfd = MagicMock(side_effect=OSError("Consume I/O failure"))
        self.controller.pidfd_open = MagicMock()

        fd, err = _acquire_target_pidfd(self.target, self.controller)
        self.assertIsNone(fd)
        self.assertEqual(
            err,
            (LifecycleState.ERROR, "consume_bound_pidfd failed: Consume I/O failure"),
        )
        self.assertEqual(self.controller.binding_state, BindingState.ERROR)
        self.controller.pidfd_open.assert_not_called()

        # Duck-typed controller without _binding_state attribute
        class DuckNoBindingAttr:
            use_pidfd = True
            binding_state = BindingState.BOUND

            def consume_bound_pidfd(self, target: Any) -> Any:
                raise OSError("error without _binding_state")

        duck = DuckNoBindingAttr()
        fd2, err2 = _acquire_target_pidfd(self.target, duck)  # type: ignore[arg-type]
        self.assertIsNone(fd2)
        self.assertEqual(
            err2,
            (LifecycleState.ERROR, "consume_bound_pidfd failed: error without _binding_state"),
        )

    def test_closed_and_error_states_refuse_reopen(self) -> None:
        for state in (BindingState.CLOSED, BindingState.ERROR):
            with self.subTest(state=state):
                ctrl = ProcessController()
                ctrl.use_pidfd = True
                ctrl._binding_state = state
                ctrl.pidfd_open = MagicMock()
                ctrl.consume_bound_pidfd = MagicMock()

                fd, err = _acquire_target_pidfd(self.target, ctrl)
                self.assertIsNone(fd)
                self.assertEqual(
                    err,
                    (LifecycleState.ERROR, f"Bound pidfd transfer failed or invalid ({state.value}); refusing reopen"),
                )
                ctrl.pidfd_open.assert_not_called()
                ctrl.consume_bound_pidfd.assert_not_called()

    def test_never_bound_legacy_open_success(self) -> None:
        self.controller.pidfd_open = MagicMock(return_value=88)
        fd, err = _acquire_target_pidfd(self.target, self.controller)
        self.assertEqual(fd, 88)
        self.assertIsNone(err)
        self.controller.pidfd_open.assert_called_once_with(self.target.pid)

    def test_never_bound_legacy_open_invalid_descriptor(self) -> None:
        for invalid_fd in (None, True, False, -1, -5, "88", 3.14):
            with self.subTest(invalid_fd=invalid_fd):
                ctrl = ProcessController()
                ctrl.use_pidfd = True
                ctrl.pidfd_open = MagicMock(return_value=invalid_fd)

                fd, err = _acquire_target_pidfd(self.target, ctrl)
                self.assertIsNone(fd)
                self.assertEqual(
                    err,
                    (LifecycleState.ERROR, "pidfd_open failed; refusing PID-based fallback to prevent signal race"),
                )

    def test_never_bound_missing_open_method(self) -> None:
        class NoOpenNeverBound:
            use_pidfd = True
            binding_state = BindingState.NEVER_BOUND

        fd, err = _acquire_target_pidfd(self.target, NoOpenNeverBound())  # type: ignore[arg-type]
        self.assertIsNone(fd)
        self.assertEqual(
            err,
            (LifecycleState.ERROR, "pidfd unavailable and legacy open not permitted for state (NEVER_BOUND)"),
        )

    def test_never_bound_open_raised_exception_propagates(self) -> None:
        self.controller.pidfd_open = MagicMock(side_effect=OSError("EPERM pidfd_open not supported"))
        with self.assertRaises(OSError) as ctx:
            _acquire_target_pidfd(self.target, self.controller)
        self.assertIn("EPERM", str(ctx.exception))


class TestSafeSigterm(unittest.TestCase):
    """Hermetic unit tests for safe_sigterm composition."""

    def setUp(self) -> None:
        self.fake_time = [1000.0]
        sp = patch.object(
            time,
            "sleep",
            side_effect=lambda s: self.fake_time.__setitem__(0, self.fake_time[0] + max(0.001, float(s))),
        )
        mp = patch.object(time, "monotonic", side_effect=lambda: self.fake_time[0])
        sp.start()
        mp.start()
        self.addCleanup(sp.stop)
        self.addCleanup(mp.stop)

        kp = patch("os.kill", side_effect=AssertionError("Unexpected real os.kill call"))
        kp.start()
        self.addCleanup(kp.stop)

        orig_open = open

        def _guarded_open(file, *args, **kwargs):
            try:
                path = os.fspath(file)
                if isinstance(path, str) and path.startswith("/proc"):
                    raise AssertionError(f"Unexpected real /proc open: {file}")
            except TypeError:
                pass
            return orig_open(file, *args, **kwargs)

        op = patch("builtins.open", side_effect=_guarded_open)
        op.start()
        self.addCleanup(op.stop)

        self.target = ProcessIdentity(
            pid=12345,
            start_ticks=987654,
            uid=1000,
            avd="Trindade_UX_v030",
            port=5556,
            binary_name="qemu-system-x86_64",
            is_direct_child=False,
        )
        self.controller = MagicMock(spec=ProcessController)
        self.controller.use_pidfd = True
        self.controller.binding_state = BindingState.NEVER_BOUND
        self.controller.get_start_ticks.return_value = 987654
        self.controller.get_uid.return_value = 1000
        self.controller.get_cmdline.return_value = [
            "/path/qemu-system-x86_64", "-avd", "Trindade_UX_v030", "-port", "5556"
        ]
        self.controller.pidfd_open.return_value = 77
        self.controller.pidfd_send_signal.return_value = True
        self.controller.close_fd.return_value = None

    def test_identity_fails_zero_acquire_or_signal(self) -> None:
        self.controller.get_uid.return_value = 0
        state, msg = safe_sigterm(self.target, self.controller)
        self.assertEqual(state, LifecycleState.PID_MISMATCH)
        self.assertIn("Identity verification failed:", msg)
        self.controller.pidfd_open.assert_not_called()
        self.controller.pidfd_send_signal.assert_not_called()
        self.controller.close_fd.assert_not_called()

    def test_acquisition_error_forwarded_directly(self) -> None:
        self.controller.use_pidfd = False
        state, msg = safe_sigterm(self.target, self.controller)
        self.assertEqual(state, LifecycleState.ERROR)
        self.assertEqual(msg, "pidfd mode required; refusing PID-based signal to prevent race")
        self.controller.pidfd_send_signal.assert_not_called()
        self.controller.close_fd.assert_not_called()

    def test_ticks_recheck_mismatch_closes_once_and_error_precedence(self) -> None:
        # None ticks -> PID_MISMATCH, close called once
        self.controller.get_start_ticks.side_effect = [987654, None]
        state, msg = safe_sigterm(self.target, self.controller)
        self.assertEqual(state, LifecycleState.PID_MISMATCH)
        self.assertIn("Process startTicks shifted prior to signal delivery (PID reused)", msg)
        self.controller.close_fd.assert_called_once_with(77)
        self.controller.pidfd_send_signal.assert_not_called()

        # _INSPECTION_ERROR ticks -> PID_MISMATCH, close called once
        self.controller.close_fd.reset_mock()
        self.controller.get_start_ticks.side_effect = [987654, _INSPECTION_ERROR]
        state, msg = safe_sigterm(self.target, self.controller)
        self.assertEqual(state, LifecycleState.PID_MISMATCH)
        self.controller.close_fd.assert_called_once_with(77)

        # Mismatch with close error -> ERROR dominates
        self.controller.close_fd.reset_mock()
        self.controller.close_fd.side_effect = OSError("close fail")
        self.controller.get_start_ticks.side_effect = [987654, 987655]
        state, msg = safe_sigterm(self.target, self.controller)
        self.assertEqual(state, LifecycleState.ERROR)
        self.assertIn("Process startTicks shifted and pidfd close failed: close fail", msg)
        self.controller.close_fd.assert_called_once_with(77)

        # pre-signal ticks raise guarantees pidfd close once and propagates exact object
        for prim in (RuntimeError("proc read failure"), KeyboardInterrupt("sig-abort"), SystemExit(2)):
            for close_eff in (None, OSError("close fail"), KeyboardInterrupt("close abort"), SystemExit(3)):
                with self.subTest(prim=prim, close_eff=close_eff):
                    self.controller.close_fd.reset_mock()
                    self.controller.close_fd.side_effect = close_eff
                    self.controller.get_start_ticks.side_effect = [987654, prim]
                    with self.assertRaises(type(prim)) as ctx:
                        safe_sigterm(self.target, self.controller)
                    self.assertIs(ctx.exception, prim)
                    self.controller.close_fd.assert_called_once_with(77)
                    self.controller.pidfd_send_signal.assert_not_called()

    def test_signal_false_or_missing_fails_closed_and_dominates_close_error(self) -> None:
        # pidfd_send_signal returns False
        self.controller.pidfd_send_signal.return_value = False
        state, msg = safe_sigterm(self.target, self.controller)
        self.assertEqual(state, LifecycleState.ERROR)
        self.assertIn("pidfd_send_signal failed; refusing PID-based fallback", msg)
        self.controller.close_fd.assert_called_once_with(77)

        # missing pidfd_send_signal method
        del self.controller.pidfd_send_signal
        self.controller.close_fd.reset_mock()
        state, msg = safe_sigterm(self.target, self.controller)
        self.assertEqual(state, LifecycleState.ERROR)
        self.assertIn("pidfd_send_signal failed; refusing PID-based fallback", msg)
        self.controller.close_fd.assert_called_once_with(77)

        # pidfd_send_signal returns False AND close fails -> signal error dominates
        self.controller.pidfd_send_signal = MagicMock(return_value=False)
        self.controller.close_fd.reset_mock()
        self.controller.close_fd.side_effect = OSError("close fail")
        state, msg = safe_sigterm(self.target, self.controller)
        self.assertEqual(state, LifecycleState.ERROR)
        self.assertEqual(msg, "pidfd_send_signal failed; refusing PID-based fallback")
        self.controller.close_fd.assert_called_once_with(77)

    def test_process_lookup_error_stopped_unless_close_error(self) -> None:
        # close ok -> STOPPED
        self.controller.pidfd_send_signal.side_effect = ProcessLookupError("No such process")
        state, msg = safe_sigterm(self.target, self.controller)
        self.assertEqual(state, LifecycleState.STOPPED)
        self.assertIn("Process already vanished prior to signal delivery", msg)
        self.controller.close_fd.assert_called_once_with(77)

        # close error -> ERROR dominates STOPPED
        self.controller.close_fd.reset_mock()
        self.controller.close_fd.side_effect = OSError("close fail")
        state, msg = safe_sigterm(self.target, self.controller)
        self.assertEqual(state, LifecycleState.ERROR)
        self.assertIn("Process vanished but pidfd close failed: close fail", msg)
        self.controller.close_fd.assert_called_once_with(77)

    def test_signal_ordinary_exception_dominates_close_error(self) -> None:
        # signal raises exception, close ok
        self.controller.pidfd_send_signal.side_effect = RuntimeError("signal crash")
        state, msg = safe_sigterm(self.target, self.controller)
        self.assertEqual(state, LifecycleState.ERROR)
        self.assertEqual(msg, "Failed to send SIGTERM: signal crash")
        self.controller.close_fd.assert_called_once_with(77)

        # signal raises exception, close fails -> signal error dominates
        self.controller.close_fd.reset_mock()
        self.controller.close_fd.side_effect = OSError("close fail")
        state, msg = safe_sigterm(self.target, self.controller)
        self.assertEqual(state, LifecycleState.ERROR)
        self.assertEqual(msg, "Failed to send SIGTERM: signal crash")
        self.controller.close_fd.assert_called_once_with(77)

    def test_successful_signal_close_error_returns_error(self) -> None:
        self.controller.close_fd.side_effect = OSError("close fail")
        with patch("scripts.f3_guard.sigterm.poll_process_termination") as mock_poll:
            state, msg = safe_sigterm(self.target, self.controller)
            self.assertEqual(state, LifecycleState.ERROR)
            self.assertIn("SIGTERM delivered but pidfd close failed: close fail", msg)
            mock_poll.assert_not_called()

    def test_keyboard_interrupt_and_system_exit_reraised_closing_once(self) -> None:
        self.controller.pidfd_send_signal.side_effect = KeyboardInterrupt("Ctrl-C")
        with self.assertRaises(KeyboardInterrupt):
            safe_sigterm(self.target, self.controller)
        self.controller.close_fd.assert_called_once_with(77)

        self.controller.close_fd.reset_mock()
        self.controller.pidfd_send_signal.side_effect = SystemExit(1)
        with self.assertRaises(SystemExit):
            safe_sigterm(self.target, self.controller)
        self.controller.close_fd.assert_called_once_with(77)

    def test_success_poll_forwarding_args_and_defaults(self) -> None:
        with patch("scripts.f3_guard.sigterm.poll_process_termination") as mock_poll:
            mock_poll.return_value = (LifecycleState.STOPPED, "Process gracefully exited (reboot)")
            res = safe_sigterm(
                self.target,
                self.controller,
                reason="reboot",
                grace_seconds=15.0,
                poll_interval=0.25,
            )
            self.assertEqual(res, (LifecycleState.STOPPED, "Process gracefully exited (reboot)"))
            mock_poll.assert_called_once_with(
                self.target,
                self.controller,
                reason="reboot",
                grace_seconds=15.0,
                poll_interval=0.25,
            )

            mock_poll.reset_mock()
            safe_sigterm(self.target, self.controller)
            mock_poll.assert_called_once_with(
                self.target,
                self.controller,
                reason="",
                grace_seconds=20.0,
                poll_interval=0.5,
            )

    def test_hermetic_full_composition_with_real_helpers(self) -> None:
        """Hermetic composition test using REAL modular identity, acquire, close, and poll helpers."""
        events: List[str] = []

        class FakeProcessController:
            use_pidfd = True
            binding_state = BindingState.NEVER_BOUND
            is_direct_child = True

            def get_start_ticks(self, pid: int) -> Optional[int]:
                events.append(f"get_start_ticks:{pid}")
                call_count = sum(1 for e in events if e.startswith("get_start_ticks"))
                return 987654 if call_count <= 2 else None

            def get_uid(self, pid: int) -> int:
                events.append(f"get_uid:{pid}")
                return 1000

            def get_cmdline(self, pid: int) -> List[str]:
                events.append(f"get_cmdline:{pid}")
                return ["/path/qemu-system-x86_64", "-avd", "Trindade_UX_v030", "-port", "5556"]

            def pidfd_open(self, pid: int) -> int:
                events.append(f"pidfd_open:{pid}")
                return 99

            def pidfd_send_signal(self, pidfd: int, sig: int) -> bool:
                events.append(f"pidfd_send_signal:{pidfd}:{sig}")
                return True

            def close_fd(self, fd: int) -> None:
                events.append(f"close_fd:{fd}")

            def get_process_state(self, pid: int) -> str:
                events.append(f"get_process_state:{pid}")
                return "S"

            def try_reap(self, pid: int) -> bool:
                events.append(f"try_reap:{pid}")
                return True

        fake_ctrl = FakeProcessController()
        state, msg = safe_sigterm(
            self.target,
            fake_ctrl,  # type: ignore[arg-type]
            reason="hermetic-test",
            grace_seconds=5.0,
            poll_interval=0.1,
        )

        self.assertEqual(state, LifecycleState.STOPPED)
        self.assertEqual(msg, "Process exited and reaped (hermetic-test)")
        self.assertIn("get_uid:12345", events)
        self.assertIn("pidfd_open:12345", events)
        self.assertTrue(any(e.startswith("pidfd_send_signal:99:") for e in events))
        self.assertIn("close_fd:99", events)
        self.assertIn("try_reap:12345", events)


if __name__ == "__main__":
    unittest.main()
