"""Hermetic unit tests for pure retry policy compute_retry_result."""

from __future__ import annotations

from typing import Any
import unittest
from unittest.mock import patch

from scripts.f3_guard.launcher_admission_retry import LaunchResult, compute_retry_result
import scripts.f3_guard.launcher_admission_retry as lar_mod


class _CounterTrap:
    def __init__(self, exc_or_pid: BaseException | int | None = None) -> None:
        is_exc = isinstance(exc_or_pid, BaseException)
        self.exc = exc_or_pid if is_exc else None
        self.pid = 12345 if (is_exc or exc_or_pid is None) else exc_or_pid
        self.bool_calls = self.call_calls = 0

    def __bool__(self) -> bool:
        self.bool_calls += 1
        if self.exc is not None:
            raise self.exc
        return False

    def __call__(self, *args: Any, **kwargs: Any) -> Any:
        self.call_calls += 1
        return True


class _CountingDescriptor:
    def __init__(self, val: Any = None, exc: BaseException | None = None) -> None:
        self.val, self.exc, self.calls = val, exc, 0

    def __get__(self, instance: Any, owner: Any) -> Any:
        self.calls += 1
        if self.exc is not None:
            raise self.exc
        return self.val


def _make_raising_result(prop_name: str, exc: BaseException) -> Any:
    return type("_Holder", (), {prop_name: _CountingDescriptor(exc=exc)})()


class TestLauncherAdmissionRetry(unittest.TestCase):
    ERR_XFER = (
        "Single-use launch attempt already consumed; process ownership transferred to controller"
    )
    ERR_PEND = (
        "Single-use launch attempt already consumed; refusing respawn (pending child retained)"
    )
    ERR_RESP = "Single-use launch attempt already consumed; refusing respawn"

    def _assert_result(
        self, res: LaunchResult, exp_err: str, exp_xf: bool, exp_cl: bool,
        exp_c: Any = None, exp_fd: int | None = None,
    ) -> None:
        self.assertFalse(res.success)
        self.assertIs(type(res.success), bool)
        self.assertEqual(res.error, exp_err)
        self.assertIs(type(res.transferred), bool)
        self.assertEqual(res.transferred, exp_xf)
        self.assertIs(type(res.pending_cleanup), bool)
        self.assertEqual(res.pending_cleanup, exp_cl)
        self.assertIs(res.is_direct_child, False)
        self.assertIsNone(res.identity)
        self.assertIs(res.child_handle, exp_c)
        self.assertEqual(res.pidfd, exp_fd)

    def _factory_branches(self, child: Any) -> list[tuple[str, dict[str, Any], str, bool, bool, Any, Any]]:
        return [
            ("simple", {}, self.ERR_RESP, False, False, None, None),
            ("pending", dict(child=child, pidfd=0), self.ERR_PEND, False, True, child, 0),
            ("transferred", dict(child=child, last_result=LaunchResult(True, transferred=True)),
             self.ERR_XFER, True, False, child, None),
        ]

    def test_precedence_strings_and_exact_types(self) -> None:
        child = _CounterTrap()
        cases = [
            ("simple_no_result", None, None, None, self.ERR_RESP, False, False, None, None),
            ("simple_empty_result", None, None, LaunchResult(False, error="prior"), self.ERR_RESP, False, False, None, None),
            ("simple_custom_noattr", None, None, object(), self.ERR_RESP, False, False, None, None),
            ("transferred_precedence_over_pending", child, 42,
             LaunchResult(True, child_handle=_CounterTrap(99), transferred=True, pending_cleanup=True),
             self.ERR_XFER, True, False, child, None),
            ("pending_cleanup_with_child", child, None, None, self.ERR_PEND, False, True, child, None),
            ("pending_cleanup_with_pidfd", None, 77, None, self.ERR_PEND, False, True, None, 77),
            ("pending_cleanup_without_resource", None, None, LaunchResult(False, pending_cleanup=True),
             self.ERR_PEND, False, True, None, None),
        ]
        for name, c_in, fd_in, lr_in, exp_err, exp_xf, exp_cl, exp_c, exp_fd in cases:
            with self.subTest(scenario=name):
                res = compute_retry_result(child=c_in, pidfd=fd_in, last_result=lr_in)
                self._assert_result(res, exp_err, exp_xf, exp_cl, exp_c, exp_fd)

    def test_resource_identity_and_falsy_traps(self) -> None:
        res_fd0 = compute_retry_result(pidfd=0)
        self._assert_result(res_fd0, self.ERR_PEND, False, True, None, 0)
        self.assertIs(type(res_fd0.pidfd), int)

        child_fd0 = _CounterTrap(555)
        res_child_fd0 = compute_retry_result(child=child_fd0, pidfd=0)
        self._assert_result(res_child_fd0, self.ERR_PEND, False, True, child_fd0, 0)
        self.assertIs(type(res_child_fd0.pidfd), int)

        falsy_child = _CounterTrap(666)
        res_falsy = compute_retry_result(child=falsy_child)
        self._assert_result(res_falsy, self.ERR_PEND, False, True, falsy_child, None)

        for exc in (RuntimeError("bool boom"), KeyboardInterrupt(), SystemExit(2)):
            with self.subTest(exc_type=type(exc).__name__):
                throw_child = _CounterTrap(exc)
                res_throw = compute_retry_result(child=throw_child)
                self._assert_result(res_throw, self.ERR_PEND, False, True, throw_child, None)

        fallback_child = _CounterTrap(777)
        last_res = LaunchResult(False, child_handle=fallback_child)
        res_fallback = compute_retry_result(child=None, last_result=last_res)
        self._assert_result(res_fallback, self.ERR_PEND, False, True, fallback_child, None)

        explicit_child = _CounterTrap(888)
        res_override = compute_retry_result(child=explicit_child, last_result=last_res)
        self._assert_result(res_override, self.ERR_PEND, False, True, explicit_child, None)
        self.assertEqual(explicit_child.bool_calls, 0)
        self.assertEqual(explicit_child.call_calls, 0)
        self.assertEqual(fallback_child.bool_calls, 0)
        self.assertEqual(fallback_child.call_calls, 0)

    def test_descriptor_exceptions_fail_closed(self) -> None:
        child = _CounterTrap(111)
        excs = (RuntimeError("desc err"), KeyboardInterrupt(), SystemExit(1), AttributeError("desc err"))
        for attr in ("transferred", "pending_cleanup", "child_handle"):
            for exc in excs:
                with self.subTest(attr=attr, exc_type=type(exc).__name__):
                    faulty_last = _make_raising_result(attr, exc)
                    res_exp = compute_retry_result(child=child, pidfd=0, last_result=faulty_last)
                    self._assert_result(res_exp, self.ERR_PEND, False, True, child, 0)
                    res_none = compute_retry_result(child=None, pidfd=None, last_result=faulty_last)
                    self._assert_result(res_none, self.ERR_PEND, False, True, None, None)

    def test_transferred_authority_shortcircuits_and_skips_unused_getters(self) -> None:
        child = _CounterTrap(222)
        excs = (RuntimeError("err"), KeyboardInterrupt(), SystemExit(1), AttributeError("err"))
        for exc in excs:
            with self.subTest(scenario="transferred_shortcircuits_pending", exc=type(exc).__name__):
                pend_desc = _CountingDescriptor(exc=exc)
                child_desc = _CountingDescriptor(exc=exc)
                holder = type("_XferHolder", (), {"transferred": True, "pending_cleanup": pend_desc, "child_handle": child_desc})()
                res = compute_retry_result(child=child, pidfd=0, last_result=holder)
                self.assertEqual(pend_desc.calls, 0)
                self.assertEqual(child_desc.calls, 0)
                self._assert_result(res, self.ERR_XFER, True, False, child, None)

            with self.subTest(scenario="explicit_child_skips_fallback_child", exc=type(exc).__name__):
                child_desc = _CountingDescriptor(exc=exc)
                holder = type("_ChildHolder", (), {"transferred": False, "pending_cleanup": False, "child_handle": child_desc})()
                res = compute_retry_result(child=child, pidfd=0, last_result=holder)
                self.assertEqual(child_desc.calls, 0)
                self._assert_result(res, self.ERR_PEND, False, True, child, 0)

    def test_normal_factory_exceptions_fallback_to_canonical(self) -> None:
        child = _CounterTrap(333)
        branches = self._factory_branches(child)
        for b_name, kw, exp_err, exp_xf, exp_cl, exp_c, exp_fd in branches:
            for exc in (RuntimeError("fact rt"), KeyboardInterrupt(), SystemExit(3)):
                with self.subTest(branch=b_name, exc=type(exc).__name__):
                    with patch.object(lar_mod, "LaunchResult", side_effect=exc):
                        res = compute_retry_result(**kw)
                        self.assertIsInstance(res, LaunchResult)
                        self._assert_result(res, exp_err, exp_xf, exp_cl, exp_c, exp_fd)

    def test_double_factory_failure_raises_primary_without_masking(self) -> None:
        prims = (RuntimeError("prim rt"), KeyboardInterrupt("prim ki"), SystemExit(4))
        secs = (RuntimeError("sec rt"), KeyboardInterrupt("sec ki"), SystemExit(5))
        branches = self._factory_branches(_CounterTrap())
        for prim, sec in [(p, s) for p in prims for s in secs]:
            for b_name, kw, *_ in branches:
                with self.subTest(prim=type(prim).__name__, sec=type(sec).__name__, branch=b_name):
                    with patch.object(lar_mod, "LaunchResult", side_effect=prim), \
                         patch.object(lar_mod, "_CANONICAL_LAUNCH_RESULT", side_effect=sec):
                        with self.assertRaises(BaseException) as cm:
                            compute_retry_result(**kw)
                        self.assertIs(cm.exception, prim)

    def test_strict_flags_dynamic_results_and_nonbool_fail_closed(self) -> None:
        child = _CounterTrap(999)

        class _DynamicResult:
            def __init__(self, transferred: Any = None) -> None:
                self.calls = 0
                if transferred is not None:
                    self.transferred = transferred
            def __getattr__(self, name: str) -> Any:
                self.calls += 1
                return True

        dyn = _DynamicResult()
        res_dyn = compute_retry_result(last_result=dyn)
        self._assert_result(res_dyn, self.ERR_PEND, False, True, None, None)
        self.assertEqual(dyn.calls, 0)

        dyn_pend = _DynamicResult(transferred=False)
        res_dyn_pend = compute_retry_result(child=child, pidfd=0, last_result=dyn_pend)
        self._assert_result(res_dyn_pend, self.ERR_PEND, False, True, child, 0)
        self.assertEqual(dyn_pend.calls, 0)

        for attr in ("transferred", "pending_cleanup"):
            trap = _CounterTrap()
            m_calls, l_calls = [0], [0]
            cases = [
                ("int_0", 0), ("int_1", 1), ("string", "true"), ("object", object()),
                ("callable", lambda: (l_calls.__setitem__(0, l_calls[0] + 1) or True)),
                ("method", type("_M", (), {"m": lambda self: (m_calls.__setitem__(0, m_calls[0] + 1) or True)})().m),
                ("trap", trap),
            ]
            for label, val in cases:
                with self.subTest(attr=attr, val=label):
                    holder = type("_Bad", (), {attr: val})()
                    res = compute_retry_result(child=child, pidfd=0, last_result=holder)
                    self._assert_result(res, self.ERR_PEND, False, True, child, 0)
            self.assertEqual(trap.bool_calls, 0)
            self.assertEqual(trap.call_calls, 0)
            self.assertEqual(m_calls[0], 0)
            self.assertEqual(l_calls[0], 0)

    def test_valid_static_properties_bool(self) -> None:
        for val in (True, False):
            with self.subTest(prop_val=val):
                res_xf = compute_retry_result(last_result=type("_Xf", (), {"transferred": property(lambda _, v=val: v)})())
                self._assert_result(res_xf, self.ERR_XFER if val else self.ERR_RESP, val, False, None, None)
                res_cl = compute_retry_result(last_result=type("_Cl", (), {"pending_cleanup": property(lambda _, v=val: v)})())
                self._assert_result(res_cl, self.ERR_PEND if val else self.ERR_RESP, False, val, None, None)

    def test_canonical_metadata_and_failure_strings(self) -> None:
        self.assertEqual(lar_mod._ERR_XFER, self.ERR_XFER)
        self.assertEqual(lar_mod._ERR_PEND, self.ERR_PEND)
        self.assertEqual(lar_mod._ERR_RESP, self.ERR_RESP)
        self.assertEqual(lar_mod.__all__, ["LaunchResult", "compute_retry_result"])
        self.assertIs(lar_mod._CANONICAL_LAUNCH_RESULT, LaunchResult)

    def test_no_external_process_spawn(self) -> None:
        with patch("subprocess.Popen") as mock_popen, patch("os.system") as mock_sys:
            compute_retry_result(child=_CounterTrap(), pidfd=0)
            compute_retry_result(child=None, pidfd=None)
            compute_retry_result(last_result=LaunchResult(True, transferred=True))
            mock_popen.assert_not_called()
            mock_sys.assert_not_called()


if __name__ == "__main__":
    unittest.main()
