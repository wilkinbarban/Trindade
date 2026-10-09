"""Hermetic tests for pure launcher admission failure factory."""

import itertools
import unittest
from typing import Any, Optional
from unittest.mock import Mock, patch

from scripts.f3_guard.launcher_admission_failure import (
    LauncherAdmissionFailureFactory,
    failure_result,
    safe_exception_failure,
    safe_result,
)
from scripts.f3_guard.launcher_preflight import LaunchResult


class _ChildTrap:
    """Diagnostic trap child tracking bool and invocation queries."""

    def __init__(self) -> None:
        self.bool_calls = 0
        self.invocations = 0

    def __bool__(self) -> bool:
        self.bool_calls += 1
        return False

    def __call__(self, *args: Any, **kwargs: Any) -> Any:
        self.invocations += 1
        raise AssertionError("Child trap invoked")


class _InvalidFlag:
    """Truthy invalid flag object instrumented with query counters."""

    def __init__(self) -> None:
        self.bool_calls = 0
        self.invocations = 0

    def __bool__(self) -> bool:
        self.bool_calls += 1
        return True

    def __call__(self, *args: Any, **kwargs: Any) -> Any:
        self.invocations += 1
        return True


class _CustomBase(BaseException):
    """Custom base exception."""


class _FaultyFormatStr(str):
    """String subclass whose __format__ raises."""

    def __new__(cls, val: str, exc: Optional[BaseException] = None) -> "_FaultyFormatStr":
        obj = super().__new__(cls, val)
        obj._exc = exc or RuntimeError("Diagnostic trap: __format__ failure")
        return obj

    def __format__(self, format_spec: str) -> str:
        raise self._exc


class _RaisingTransferredLast:
    """Last result whose transferred property getter raises."""

    def __init__(self, exc: BaseException) -> None:
        self._exc = exc

    @property
    def transferred(self) -> Any:
        raise self._exc


class _BrokenClassException(Exception):
    """Exception raising on __class__ access."""

    def __init__(self, exc: BaseException) -> None:
        super().__init__("broken")
        self._exc = exc

    @property
    def __class__(self) -> Any:
        raise self._exc


def _make_named_exc(name_val: Any) -> type[Exception]:
    """Create an exception class with custom __name__ attribute value or raising trap."""

    class _Meta(type):
        @property
        def __name__(cls) -> Any:
            if isinstance(name_val, BaseException):
                raise name_val
            return name_val

    class _Exc(Exception, metaclass=_Meta):
        pass

    return _Exc


class TestLauncherAdmissionFailureFactory(unittest.TestCase):
    """Hermetic unit tests for admission failure factory."""

    def _assert_child_uninvoked(self, child: Optional[Any]) -> None:
        self.assertEqual(getattr(child, "bool_calls", 0), 0)
        self.assertEqual(getattr(child, "invocations", 0), 0)

    def _assert_custody(
        self,
        res: LaunchResult,
        *,
        err: str,
        child: Optional[Any] = None,
        pidfd: Optional[int] = None,
        xfer: Any = False,
        cleanup: Any = False,
    ) -> None:
        self.assertFalse(res.success)
        self.assertEqual(res.error, err)
        self.assertIs(res.child_handle, child)
        self._assert_child_uninvoked(child)
        self.assertEqual(res.pidfd, pidfd)
        self.assertEqual(res.transferred, xfer)
        self.assertEqual(res.pending_cleanup, cleanup)

    def test_safe_result_resource_identity_and_falsy_child_traps(self) -> None:
        child = _ChildTrap()
        res = safe_result(False, "Fail error", child=child, pidfd=0)
        self._assert_custody(res, err="Fail error", child=child, pidfd=0, xfer=False, cleanup=True)

    def test_safe_result_cleanup_computation_matrix(self) -> None:
        child = _ChildTrap()
        for name, ch, fd, explicit, expected in [
            ("child_only", child, None, None, True),
            ("fd0", None, 0, None, True),
            ("fd10", None, 10, None, True),
            ("neither", None, None, None, False),
            ("override_f", child, 0, False, False),
            ("override_t", None, None, True, True),
        ]:
            with self.subTest(case=name):
                res = safe_result(False, "err", child=ch, pidfd=fd, pending_cleanup=explicit)
                self.assertEqual(res.pending_cleanup, expected)
                self._assert_child_uninvoked(ch)

    def test_failure_result_strict_transferred_vs_truthy_nonboolean(self) -> None:
        child = _ChildTrap()
        last_xfer = LaunchResult(success=True, transferred=True, child_handle="orig", pidfd=42)
        res_xfer = failure_result("xfer err", child=child, pidfd=0, last=last_xfer)
        self._assert_custody(res_xfer, err="xfer err", child=child, pidfd=None, xfer=True, cleanup=False)

        res_not_xfer = failure_result("not xfer", child=child, pidfd=0, last=LaunchResult(False, transferred=False))
        self._assert_custody(res_not_xfer, err="not xfer", child=child, pidfd=0, xfer=False, cleanup=True)

        flag = _InvalidFlag()
        mock_flag = Mock(spec=["transferred"])
        mock_flag.transferred = flag
        res_flag = failure_result("flag err", child=child, pidfd=0, last=mock_flag)
        self._assert_custody(res_flag, err="flag err", child=child, pidfd=0, xfer=False, cleanup=True)
        self.assertIs(res_flag.transferred, False)
        self.assertEqual(flag.bool_calls, 0)
        self.assertEqual(flag.invocations, 0)

        for truthy_val in (1, "True", [True], object()):
            with self.subTest(truthy=truthy_val):
                mock_last = Mock(spec=["transferred"])
                mock_last.transferred = truthy_val
                res_t = failure_result("truthy err", child=child, pidfd=0, last=mock_last)
                self._assert_custody(res_t, err="truthy err", child=child, pidfd=0, xfer=False, cleanup=True)
                self.assertIs(res_t.transferred, False)

        for raise_exc in (
            KeyboardInterrupt("getter KI"),
            SystemExit(2),
            _CustomBase("cb fault"),
            RuntimeError("getter fault"),
            KeyError("bad key"),
        ):
            with self.subTest(getter_raises=type(raise_exc).__name__):
                res_raise = failure_result("raise err", child=child, pidfd=0, last=_RaisingTransferredLast(raise_exc))
                self._assert_custody(res_raise, err="raise err", child=child, pidfd=0, xfer=False, cleanup=True)
                self.assertIs(res_raise.transferred, False)

        res_none = failure_result("no last", child=child, pidfd=0, last=None)
        self._assert_custody(res_none, err="no last", child=child, pidfd=0, xfer=False, cleanup=True)

    def test_constructor_fault_fallback_canonical_success(self) -> None:
        faults = (TypeError("Ctor fault"), KeyboardInterrupt("Ctor KI"), SystemExit(2), _CustomBase("Ctor cb"))
        for fault, has_ch, fd, xfer, clean in itertools.product(
            faults, (True, False), (0, None), (True, False), (True, False, None)
        ):
            name = f"{type(fault).__name__}-ch_{has_ch}-fd_{fd}-x_{xfer}-cln_{clean}"
            with self.subTest(scenario=name):
                ch = _ChildTrap() if has_ch else None
                exp_clean = (ch is not None or fd is not None) if clean is None else clean
                with patch("scripts.f3_guard.launcher_admission_failure.LaunchResult", side_effect=fault):
                    res = safe_result(False, "fallback err", child=ch, pidfd=fd, transferred=xfer, pending_cleanup=clean)
                self._assert_custody(res, err="fallback err", child=ch, pidfd=fd, xfer=xfer, cleanup=exp_clean)

        for fault in faults:
            with self.subTest(failure_result_fallback=type(fault).__name__):
                ch = _ChildTrap()
                last_xfer = LaunchResult(success=True, transferred=True)
                with patch("scripts.f3_guard.launcher_admission_failure.LaunchResult", side_effect=fault):
                    res_xfer = failure_result("fallback xfer", child=ch, pidfd=0, last=last_xfer)
                self._assert_custody(res_xfer, err="fallback xfer", child=ch, pidfd=None, xfer=True, cleanup=False)

    def test_double_constructor_failure_honestly_propagates(self) -> None:
        primary_faults = (TypeError("P-TypeError"), KeyboardInterrupt("P-KI"), SystemExit(2), _CustomBase("P-cb"))
        fallback_fault = RuntimeError("Ctor fallback fault")
        cases = itertools.product(primary_faults, (True, False), (0, None), (True, False), (True, False, None))

        for fault, has_ch, fd, xfer, clean in cases:
            with self.subTest(fault=type(fault).__name__, ch=has_ch, fd=fd, xfer=xfer, clean=clean):
                ch = _ChildTrap() if has_ch else None
                exp_clean = (ch is not None or fd is not None) if clean is None else clean
                captured: dict[str, Any] = {}

                def _fake_canonical(**kwargs: Any) -> Any:
                    captured.update(kwargs)
                    raise fallback_fault

                with patch(
                    "scripts.f3_guard.launcher_admission_failure.LaunchResult", side_effect=fault
                ), patch(
                    "scripts.f3_guard.launcher_admission_failure._CANONICAL_LAUNCH_RESULT", side_effect=_fake_canonical
                ):
                    with self.assertRaises(RuntimeError) as ctx:
                        safe_result(False, "double err", child=ch, pidfd=fd, transferred=xfer, pending_cleanup=clean)
                    self.assertIs(ctx.exception, fallback_fault)
                    self.assertIsNot(ctx.exception, fault)
                    self.assertIs(captured.get("success"), False)
                    self.assertEqual(captured.get("error"), "double err")
                    self.assertIs(captured.get("child_handle"), ch)
                    self.assertEqual(captured.get("pidfd"), fd)
                    self.assertEqual(captured.get("transferred"), xfer)
                    self.assertEqual(captured.get("pending_cleanup"), exp_clean)
                    self._assert_child_uninvoked(ch)

    def test_safe_exception_failure_and_formatting_secondary_traps(self) -> None:
        child = _ChildTrap()
        for exc, fd, exp in ((ValueError("standard"), 0, "ValueError"), (_CustomBase("custom"), 5, "_CustomBase")):
            res = safe_exception_failure(exc, child=child, pidfd=fd)
            self._assert_custody(res, err=f"Post-spawn exception: {exp}", child=child, pidfd=fd, xfer=False, cleanup=True)

        for non_str_val in (None, 123, [1]):
            with self.subTest(non_str=non_str_val):
                res_non_str = safe_exception_failure(_make_named_exc(non_str_val)(), child=child, pidfd=0)
                self._assert_custody(res_non_str, err="Post-spawn exception", child=child, pidfd=0, xfer=False, cleanup=True)

        diagnostic_faults = (
            KeyboardInterrupt("Diagnostic KI"),
            SystemExit(1),
            _CustomBase("Diagnostic custom base"),
            RuntimeError("Diagnostic ordinary"),
        )
        for fault in diagnostic_faults:
            with self.subTest(lookup_name=type(fault).__name__):
                res_trap = safe_exception_failure(_make_named_exc(fault)(), child=child, pidfd=0)
                self._assert_custody(res_trap, err="Post-spawn exception", child=child, pidfd=0, xfer=False, cleanup=True)

            with self.subTest(lookup_class=type(fault).__name__):
                res_trap_cls = safe_exception_failure(_BrokenClassException(fault), child=child, pidfd=0)
                self._assert_custody(res_trap_cls, err="Post-spawn exception", child=child, pidfd=0, xfer=False, cleanup=True)

            with self.subTest(format_fault=type(fault).__name__):
                exc_cls = _make_named_exc(_FaultyFormatStr("FormatFaulty", fault))
                res_fmt = safe_exception_failure(exc_cls(), child=child, pidfd=0)
                self._assert_custody(res_fmt, err="Post-spawn exception", child=child, pidfd=0, xfer=False, cleanup=True)

        last_xfer = LaunchResult(success=True, transferred=True)
        res_xfer = safe_exception_failure(RuntimeError("err"), child=child, pidfd=0, last=last_xfer)
        self._assert_custody(res_xfer, err="Post-spawn exception: RuntimeError", child=child, pidfd=None, xfer=True, cleanup=False)

    def test_pure_boundary_no_callbacks_no_poll_no_execute(self) -> None:
        import scripts.f3_guard.launcher_admission_failure as mod

        for name in ("execute", "poll", "callback", "snapshot", "reap", "attempted"):
            self.assertNotIn(name, dir(mod))

        self.assertIs(LauncherAdmissionFailureFactory.safe_result, mod.safe_result)
        self.assertIs(LauncherAdmissionFailureFactory.failure_result, mod.failure_result)
        self.assertIs(
            LauncherAdmissionFailureFactory.safe_exception_failure,
            mod.safe_exception_failure,
        )


if __name__ == "__main__":
    unittest.main()
