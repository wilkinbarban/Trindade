"""Hermetic unit tests for post-signal process termination polling."""

from __future__ import annotations

import math
import os
import time
import unittest
from typing import List, Optional
from unittest.mock import MagicMock, patch

from scripts.f3_guard.controller import ProcessController
from scripts.f3_guard.proc_inspection import INSPECTION_ERROR, _INSPECTION_ERROR
from scripts.f3_guard.sigterm import DEFAULT_GRACE_SEC, poll_process_termination
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


if __name__ == "__main__":
    unittest.main()
