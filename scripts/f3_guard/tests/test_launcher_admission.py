"""Hermetic unit tests for single-use launcher admission helper."""

import inspect
import itertools
import unittest
from typing import Any, Optional
from unittest.mock import Mock, patch

from scripts.f3_guard.launcher_admission import LauncherAdmissionHelper
from scripts.f3_guard.launcher_admission_retry import (
    _ERR_PEND, _ERR_RESP, _ERR_XFER,
)
from scripts.f3_guard.launcher_custody import LauncherCustodySnapshot
from scripts.f3_guard.launcher_preflight import LaunchResult

_RM = "scripts.f3_guard.launcher_admission_resolver"
_FM = "scripts.f3_guard.launcher_admission_failure"


class _ChildTrap:
    def __init__(self) -> None:
        self.bool_calls = self.invocations = 0

    def __bool__(self) -> bool:
        self.bool_calls += 1
        return False

    def __call__(self, *args: Any, **kwargs: Any) -> Any:
        self.invocations += 1
        raise AssertionError("Trap")


class _MockSource:
    def __init__(
        self, child: Any = None, pidfd: Optional[int] = None,
        last: Optional[LaunchResult] = None,
        fault: Optional[BaseException] = None,
    ) -> None:
        self._child, self._pidfd = child, pidfd
        self._last, self._fault = last, fault
        self.cb_calls = self.child_polls = self.pidfd_polls = 0
        self.last_polls = 0

    def __call__(self) -> Any:
        self.cb_calls += 1
        return self

    @property
    def pending_child(self) -> Any:
        self.child_polls += 1
        return self._child

    @property
    def pending_pidfd(self) -> Optional[int]:
        self.pidfd_polls += 1
        if self._fault is not None:
            raise self._fault
        return self._pidfd

    @property
    def last_result(self) -> Optional[LaunchResult]:
        self.last_polls += 1
        return self._last


class _CustomBase(BaseException):
    pass


def _raise(exc: BaseException) -> Any:
    raise exc


class TestLauncherAdmissionHelper(unittest.TestCase):
    def _execute_catching(
        self, h: LauncherAdmissionHelper, exc: BaseException
    ) -> BaseException:
        caught = None
        try:
            h.execute(lambda: _raise(exc))
        except BaseException as ex:
            caught = ex
        self.assertIs(caught, exc)
        tb, frames = caught.__traceback__, []
        while tb:
            frames.append(tb.tb_frame.f_code.co_name)
            tb = tb.tb_next
        self.assertEqual((frames[1], frames[-1]), ("execute", "_raise"))
        return caught

    def _assert_counters(
        self, s: _MockSource, cb: int, c: int, fd: int, l: int
    ) -> None:
        self.assertEqual((s.cb_calls, s.child_polls), (cb, c))
        self.assertEqual((s.pidfd_polls, s.last_polls), (fd, l))

    def _run_matrix_case(
        self, p: BaseException, sec: Any, is_factory: bool
    ) -> None:
        c = _ChildTrap()
        lr = None if is_factory else LaunchResult(
            True, None, 0, c, transferred=True
        )
        src = _MockSource(c, 0, last=lr)
        h = LauncherAdmissionHelper(src)
        h.snapshot()
        cb, c_p = src.cb_calls, src.child_polls
        fd, l = src.pidfd_polls, src.last_polls
        if is_factory:
            spy = Mock(wraps=h.record_result)
            h.record_result = spy
            p1 = patch(f"{_RM}.LaunchResult", side_effect=sec)
            p2 = patch(f"{_RM}._CANONICAL_LAUNCH_RESULT", side_effect=sec)
            p3 = patch(f"{_FM}.LaunchResult", side_effect=sec)
            p4 = patch(f"{_FM}._CANONICAL_LAUNCH_RESULT", side_effect=sec)
            with p1, p2, p3, p4:
                self._execute_catching(h, p)
            self.assertEqual(spy.call_count, 0)
        else:
            with patch.object(h, "record_result", side_effect=sec):
                self._execute_catching(h, p)
            self.assertIs(h.known_last_result.transferred, True)
        self.assertEqual((h.known_pidfd, h.known_child), (0, c))
        kise = isinstance(p, (KeyboardInterrupt, SystemExit))
        d_cb, d_p = (0, 0) if kise else (3, 2)
        self._assert_counters(src, cb + d_cb, c_p + d_p, fd + d_p, l + d_cb)

    def test_flags_callback_execution_and_reentrancy(self) -> None:
        tc = self
        h = LauncherAdmissionHelper()
        self.assertIs(h.attempted, False)
        self.assertIs(h.is_attempted, False)

        class FalseBoolCB(_ChildTrap):
            def __call__(self, *a: Any, **kw: Any) -> LaunchResult:
                self.invocations += 1
                tc.assertIs(h.attempted, True)
                tc.assertIs(h.is_attempted, True)
                n = h.execute()
                tc.assertEqual((n.success, n.error), (False, _ERR_RESP))
                return LaunchResult(True)

        res = h.execute(cb := FalseBoolCB())
        self.assertEqual((cb.bool_calls, cb.invocations), (0, 1))
        self.assertIs(h.attempted, True)
        self.assertIs(h.is_attempted, True)
        self.assertIs(res.success, True)

    def test_init_injection_and_explicit_precedence(self) -> None:
        r1, r2 = LaunchResult(True, pidfd=1), LaunchResult(True, pidfd=2)
        h = LauncherAdmissionHelper(callback=lambda: r1)
        self.assertEqual((h.execute(), h.last_result), (r1, r1))
        h2 = LauncherAdmissionHelper(callback=lambda: r1)
        self.assertEqual((h2.execute(lambda: r2), h2.last_result), (r2, r2))

    def test_public_aliases_and_zero_private_protocol_attrs(self) -> None:
        h = LauncherAdmissionHelper()
        for a in ("known_child", "known_pidfd", "known_last_result"):
            self.assertIsNone(getattr(h, a))
        self.assertIsInstance(h.known_snapshot, LauncherCustodySnapshot)
        self.assertIs(h.is_attempted, h.attempted)
        self.assertEqual(h.take_snapshot, h.snapshot)
        self.assertIs(h.child_handle, h.pending_child)
        self.assertIs(h.pidfd, h.pending_pidfd)
        src = inspect.getsource(LauncherAdmissionHelper)
        for priv in ("._known", "._cache", "._known_helper"):
            self.assertNotIn(priv, src)

    def test_missing_noncallable_none_callback_consumes_attempt(self) -> None:
        child = _ChildTrap()
        src = _MockSource(child, 0)
        no_cb = "No admission callback provided for execution"
        none_cb = Mock(return_value=None)
        for bad_cb in (None, "not-callable", none_cb):
            with self.subTest(bad=type(bad_cb).__name__):
                h = LauncherAdmissionHelper(src, callback=bad_cb)
                self.assertEqual((h.attempted, h.is_attempted), (False, False))
                err = (
                    "Admission callback returned None"
                    if callable(bad_cb) else no_cb
                )
                r = h.execute()
                self.assertFalse(r.success)
                self.assertEqual((r.error, r.pidfd), (err, 0))
                self.assertTrue(r.pending_cleanup)
                self.assertIs(r.child_handle, child)
                self.assertEqual((child.bool_calls, child.invocations), (0, 0))
                self.assertIs(h.attempted, True)
                self.assertIs(h.is_attempted, True)
                retry_cb = Mock(return_value=LaunchResult(True))
                retry_r = h.execute(retry_cb)
                self.assertEqual(retry_cb.call_count, 0)
                self.assertFalse(retry_r.success)
                self.assertEqual(retry_r.error, _ERR_PEND)

        self.assertEqual(none_cb.call_count, 1)

    def test_retry_authority_branches_and_fresh_source(self) -> None:
        c_trap = _ChildTrap()
        cases = [
            (LaunchResult(True, transferred=True, child_handle="proc"),
             _ERR_XFER, True, False, "proc", None),
            (LaunchResult(False, pending_cleanup=True, pidfd=10),
             _ERR_PEND, False, True, None, 10),
            (LaunchResult(False), _ERR_RESP, False, False, None, None),
            (LaunchResult(True, None, 0, c_trap, pending_cleanup=True),
             _ERR_PEND, False, True, c_trap, 0),
        ]
        for init_res, err, xfer, pend, exp_c, exp_fd in cases:
            h = LauncherAdmissionHelper()
            h.execute(lambda: init_res)
            r = h.execute()
            self.assertEqual(
                (r.success, r.error, r.transferred, r.pending_cleanup),
                (False, err, xfer, pend),
            )
            self.assertEqual((r.child_handle, r.pidfd), (exp_c, exp_fd))

        real = _MockSource("c", 99)
        h_src = LauncherAdmissionHelper(real)
        local_res = h_src.execute(lambda: LaunchResult(True))
        real._last = LaunchResult(True, None, 99, "c", transferred=True)
        res_fresh = h_src.execute()
        self.assertIs(local_res.transferred, False)
        self.assertIs(real.last_result.transferred, True)
        self.assertEqual(
            (res_fresh.success, res_fresh.error), (False, _ERR_XFER)
        )
        self.assertEqual(
            (res_fresh.transferred, res_fresh.pending_cleanup), (True, False)
        )
        self.assertIsNone(res_fresh.pidfd)
        self.assertIs(res_fresh.child_handle, real.pending_child)

    def test_primary_kise_custody_zero_polls_and_recorder_fault(self) -> None:
        p_list = (KeyboardInterrupt("ki"), SystemExit(2), _CustomBase("cb"))
        s_list = (
            RuntimeError("rec"), KeyboardInterrupt("sec_ki"),
            SystemExit(3), _CustomBase("sec_cb"),
        )
        for p, s in itertools.product(p_list, s_list):
            with self.subTest(primary=type(p).__name__, sec=type(s).__name__):
                self._run_matrix_case(p, s, False)

    def test_ordinary_value_error_child_traps_fd0_and_secondary_fault(
        self,
    ) -> None:
        for p_exc in (ValueError("val"), _CustomBase("cb")):
            with self.subTest(primary=type(p_exc).__name__):
                child = _ChildTrap()
                src = _MockSource(fault=RuntimeError("sec"))
                h = LauncherAdmissionHelper(src)

                def cb() -> Any:
                    src._child, src._pidfd = child, 0
                    _raise(p_exc)

                res = h.execute(cb)
                self.assertEqual(
                    (src.pidfd_polls, res.success, res.pending_cleanup),
                    (2, False, True),
                )
                self.assertEqual(
                    res.error, f"Post-spawn exception: {type(p_exc).__name__}"
                )
                self.assertIs(res.child_handle, child)
                self.assertEqual((child.bool_calls, child.invocations), (0, 0))

    def test_post_transfer_failure_with_actual_secondary_kise(self) -> None:
        c_xfer = _ChildTrap()
        lr_x = LaunchResult(True, None, 0, c_xfer, transferred=True)
        src = _MockSource(c_xfer, 0, last=lr_x)
        h = LauncherAdmissionHelper(src)
        res = h.execute(lambda: _raise(ValueError("Fail after transfer")))
        self.assertEqual((res.transferred, res.pending_cleanup), (True, False))
        self.assertFalse(res.success)
        self.assertEqual((c_xfer.bool_calls, res.pidfd), (0, None))
        self.assertIs(res.child_handle, c_xfer)

        target = f"{_FM}.failure_result"
        for sec in (KeyboardInterrupt("Sec KI"), SystemExit(2)):
            with self.subTest(sec=type(sec).__name__):
                h_sec = LauncherAdmissionHelper(src)
                h_sec.snapshot()
                with patch(target, side_effect=sec):
                    self._execute_catching(h_sec, ValueError("p_err"))
                    self.assertEqual(
                        (h_sec.known_pidfd, h_sec.known_child), (0, c_xfer)
                    )
                    self.assertIs(h_sec.known_last_result.transferred, True)

    def test_double_factory_secondary_fault_policy(self) -> None:
        p_list = (ValueError("val"), _CustomBase("cb"))
        s_list = (
            TypeError("Ctor"), KeyboardInterrupt("Sec KI"),
            SystemExit(1), _CustomBase("Sec CB"),
        )
        for p, s in itertools.product(p_list, s_list):
            with self.subTest(primary=type(p).__name__, sec=type(s).__name__):
                self._run_matrix_case(p, s, True)


if __name__ == "__main__":
    unittest.main()
