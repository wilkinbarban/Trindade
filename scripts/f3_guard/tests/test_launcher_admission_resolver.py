"""Hermetic unit tests for AdmissionCustodyResolver."""

from __future__ import annotations

import traceback
from typing import Any, Callable, Optional
import unittest
from unittest.mock import patch

from scripts.f3_guard.launcher_admission_resolver import AdmissionCustodyResolver
import scripts.f3_guard.launcher_admission_resolver as lar_mod
from scripts.f3_guard.launcher_custody import CustodySnapshot, LauncherCustodyTracker
from scripts.f3_guard.launcher_preflight import LaunchResult


class _StrReprTrap(Exception):
    def __str__(self) -> str: raise RuntimeError("str trap")
    def __repr__(self) -> str: raise RuntimeError("repr trap")


class _BoolTrapChild:
    def __init__(self, pid: int = 12345) -> None: self.pid = pid
    def __bool__(self) -> bool: return False


class _FaultySource:
    def __init__(
        self, child: Any = None, pidfd: Optional[int] = None, fail_rec: bool = False,
        fail_last: bool = False, stale_res: Optional[LaunchResult] = None,
        rec_invoke_fail: bool = False, custom_snap: Optional[CustodySnapshot] = None, set_last_exc: bool = False,
    ) -> None:
        self.pending_child, self.pending_pidfd, self.last_exception = child, pidfd, None
        self._fail_rec, self._fail_last, self._stale_res = fail_rec, fail_last, stale_res
        self._rec_invoke_fail, self._custom_snap, self._set_last_exc = rec_invoke_fail, custom_snap, set_last_exc

    @property
    def record_exception(self) -> Any:
        if self._fail_rec: raise RuntimeError("faulty rec lookup")
        if self._rec_invoke_fail: return lambda exc: (_ for _ in ()).throw(RuntimeError("bad rec"))
        return lambda exc: (setattr(self, "last_exception", exc) if self._set_last_exc else None) or self._custom_snap

    @property
    def last_result(self) -> Optional[LaunchResult]:
        if self._fail_last: raise RuntimeError("faulty last lookup")
        return self._stale_res


class TestAdmissionCustodyResolver(unittest.TestCase):
    def setUp(self) -> None:
        self.child = _BoolTrapChild(pid=12345)

    def _assert_escapes_with_frame(self, fn: Callable[[], Any], exc: BaseException, name: str) -> None:
        caught = None
        try: fn()
        except BaseException as err: caught = err
        self.assertIs(caught, exc)
        self.assertIsNotNone(caught.__traceback__)
        frames = traceback.extract_tb(caught.__traceback__)
        self.assertTrue(any(f.name == name for f in frames))

    def _make_tracker_resolver(self, pidfd: int = 77, cls: Any = LauncherCustodyTracker) -> tuple[Any, Any, AdmissionCustodyResolver]:
        active_h = _FaultySource(child=self.child, pidfd=pidfd)
        tracker = cls(active_helper=active_h)
        tracker.snapshot()
        return active_h, tracker, AdmissionCustodyResolver(custody_source=tracker)

    def test_authoritative_properties_and_source_none_vs_absent_vs_none_resources(self) -> None:
        resolver = AdmissionCustodyResolver()
        for p in (resolver.custody_source, resolver.pending_child, resolver.pending_pidfd, resolver.pidfd, resolver.last_result):
            self.assertIsNone(p)

        fallback = LaunchResult(success=False, child_handle=self.child, pidfd=42, pending_cleanup=True)
        resolver.set_fallback_result(fallback)
        self.assertIs(resolver.fallback_result, fallback)
        self.assertIs(resolver.pending_child, self.child)
        self.assertEqual(resolver.pending_pidfd, 42)
        self.assertIs(resolver.last_result, fallback)

        for src, exp_c, exp_f in (
            (_FaultySource(child=None, pidfd=None, stale_res=None), None, None),
            (object(), None, None),
            (_FaultySource(child=self.child, pidfd=0), self.child, 0),
            (lambda: _FaultySource(child=self.child, pidfd=0), self.child, 0),
            (lambda: (_ for _ in ()).throw(RuntimeError("getter boom")), None, None),
        ):
            resolver.set_custody_source(src)
            self.assertIs(resolver.pending_child, exp_c)
            self.assertEqual(resolver.pending_pidfd, exp_f)
            if exp_c is not None:
                self.assertEqual(resolver.pidfd, exp_f)
            else:
                self.assertIsNone(resolver.last_result)

    def test_real_tracker_integration_resolves_current_exception(self) -> None:
        _, tracker, resolver = self._make_tracker_resolver()
        for err, exp_err in (
            (ValueError("real tracker fault"), "primary: ValueError"),
            (KeyboardInterrupt("escape"), "primary: KeyboardInterrupt"),
            (SystemExit("escape"), "primary: SystemExit"),
        ):
            with self.subTest(exc=type(err).__name__):
                res = resolver.resolve_exception_result(err)
                self.assertFalse(res.success)
                self.assertIs(res.child_handle, self.child)
                self.assertEqual(res.pidfd, 77)
                self.assertTrue(res.pending_cleanup)
                self.assertFalse(res.transferred)
                self.assertEqual(res.error, exp_err)
                self.assertIs(res, tracker.last_result)

    def test_stale_result_and_snapshot_rejected_absent_current_exception_proof(self) -> None:
        old_c = _BoolTrapChild(pid=9999)
        snap = CustodySnapshot(pending_child=self.child, pending_pidfd=0, last_result=LaunchResult(success=False, child_handle=self.child, pidfd=0, error="SNAP"))
        cases = (
            _FaultySource(child=self.child, pidfd=88, stale_res=LaunchResult(success=True, child_handle=self.child, pidfd=88)),
            _FaultySource(child=self.child, pidfd=0, stale_res=LaunchResult(success=False, child_handle=old_c, pidfd=81, error="OLD")),
            _FaultySource(child=self.child, pidfd=0, stale_res=LaunchResult(success=False, child_handle=old_c, pidfd=81, error="Post-spawn exception: ValueError")),
            _FaultySource(child=self.child, pidfd=0, stale_res=LaunchResult(success=False, child_handle=self.child, pidfd=0, error="Post-spawn exception: ValueError")),
            _FaultySource(child=self.child, pidfd=0, custom_snap=snap),
        )
        for src in cases:
            with self.subTest(stale=src._stale_res):
                res = AdmissionCustodyResolver(custody_source=src).resolve_exception_result(ValueError("new error"))
                self.assertFalse(res.success)
                self.assertEqual(res.error, "Post-spawn exception: ValueError")
                self.assertIs(res.child_handle, self.child)
                self.assertEqual(res.pidfd, src.pending_pidfd)
                self.assertTrue(res.pending_cleanup)
                self.assertIsNot(res, src.last_result)

    def test_legitimate_custom_diagnostic_result_accepted_by_identity(self) -> None:
        c_res = LaunchResult(success=False, child_handle=self.child, pidfd=0, error="Custom diag", pending_cleanup=True)
        snap = CustodySnapshot(pending_child=self.child, pending_pidfd=0, last_result=c_res)
        src = _FaultySource(child=self.child, pidfd=0, custom_snap=snap, set_last_exc=True)
        res = AdmissionCustodyResolver(custody_source=src).resolve_exception_result(ValueError("boom"))
        self.assertFalse(res.success)
        self.assertIs(res, c_res)
        self.assertEqual(res.error, "Custom diag")
        self.assertIs(res.child_handle, self.child)
        self.assertEqual(res.pidfd, 0)

    def test_str_repr_trap_and_type_diagnostic(self) -> None:
        res = AdmissionCustodyResolver().resolve_exception_result(_StrReprTrap())
        self.assertFalse(res.success)
        self.assertEqual(res.error, "Post-spawn exception: _StrReprTrap")

    def test_getter_and_recorder_lookup_and_invocation_faults_fail_closed(self) -> None:
        for f_rec, f_last, inv_f in ((True, False, False), (False, True, False), (True, True, False), (False, False, True)):
            with self.subTest(rec=f_rec, last=f_last, inv=inv_f):
                src = _FaultySource(child=self.child, pidfd=33, fail_rec=f_rec, fail_last=f_last, rec_invoke_fail=inv_f)
                res = AdmissionCustodyResolver(custody_source=src).resolve_exception_result(RuntimeError("fail"))
                self.assertFalse(res.success)
                self.assertEqual(res.error, "Post-spawn exception: RuntimeError")
                self.assertIs(res.child_handle, self.child)
                self.assertEqual(res.pidfd, 33)
                self.assertTrue(res.pending_cleanup)

    def test_double_factory_failure_raises_primary_exception_with_traceback(self) -> None:
        resolver = AdmissionCustodyResolver()
        for exc_cls in (ValueError, KeyboardInterrupt, SystemExit):
            with self.subTest(exc=exc_cls.__name__):
                pri_err = exc_cls("primary failure")
                def cb_fail() -> None: raise pri_err
                with patch.object(lar_mod, "LaunchResult", side_effect=RuntimeError("prim fail")), \
                     patch.object(lar_mod, "_CANONICAL_LAUNCH_RESULT", side_effect=RuntimeError("canon fail")):
                    def test_call() -> None:
                        try: cb_fail()
                        except BaseException as ex: resolver.resolve_exception_result(ex)
                    self._assert_escapes_with_frame(test_call, pri_err, "cb_fail")

    def test_real_tracker_with_old_failure_rejects_historical_result(self) -> None:
        active_h, tracker, resolver = self._make_tracker_resolver()
        old_res = resolver.resolve_exception_result(ValueError("old failure"))
        self.assertEqual(old_res.error, "primary: ValueError")
        self.assertIs(old_res, tracker.last_result)
        active_h._stale_res = old_res

        new_res = resolver.resolve_exception_result(ValueError("new failure"))
        self.assertIsNot(new_res, old_res)
        self.assertEqual(new_res.error, "Post-spawn exception: ValueError")
        self.assertIs(new_res.child_handle, self.child)
        self.assertEqual(new_res.pidfd, 77)
        self.assertTrue(new_res.pending_cleanup)

    def test_real_tracker_previously_recorded_same_exception_recorder_noop_rejects_old_snapshot(self) -> None:
        _, tracker, resolver = self._make_tracker_resolver()
        same_exc = ValueError("repeat failure")
        old_snap = tracker.record_exception(same_exc)
        old_res = tracker.last_result
        self.assertIsNotNone(old_res)

        tracker.record_exception = lambda exc: old_snap
        res = resolver.resolve_exception_result(same_exc)
        self.assertIsNot(res, old_res)
        self.assertEqual(res.error, "Post-spawn exception: ValueError")
        self.assertIs(res.child_handle, self.child)
        self.assertEqual(res.pidfd, 77)

    def test_authoritative_fd0_cand_false_cleanup1_transferred0_rejects_wrong_exact_types(self) -> None:
        for bad_kw in (dict(pidfd=False), dict(pending_cleanup=1), dict(transferred=0), dict(success=0)):
            with self.subTest(bad=bad_kw):
                kw: dict[str, Any] = dict(
                    success=False, child_handle=self.child, pidfd=0,
                    pending_cleanup=True, transferred=False, error="wrong types",
                )
                kw.update(bad_kw)
                snap = CustodySnapshot(pending_child=self.child, pending_pidfd=0, last_result=LaunchResult(**kw))
                src = _FaultySource(child=self.child, pidfd=0, custom_snap=snap, set_last_exc=True)
                res = AdmissionCustodyResolver(custody_source=src).resolve_exception_result(ValueError("type boom"))
                self.assertIsNot(res, snap.last_result)
                self.assertEqual(res.error, "Post-spawn exception: ValueError")
                self.assertIs(res.child_handle, self.child)
                self.assertEqual(res.pidfd, 0)
                self.assertTrue(res.pending_cleanup)

    def test_real_tracker_subclass_getters_both_fault_retains_snapshot_resources(self) -> None:
        class FaultyTracker(LauncherCustodyTracker):
            @property
            def pending_child(self) -> Any: raise RuntimeError("child getter boom")
            @property
            def pending_pidfd(self) -> Any: raise RuntimeError("pidfd getter boom")

        _, tracker, resolver = self._make_tracker_resolver(cls=FaultyTracker)
        self.assertIs(tracker.last_snapshot.pending_child, self.child)
        self.assertEqual(tracker.last_snapshot.pending_pidfd, 77)
        self.assertIs(resolver.pending_child, self.child)
        self.assertEqual(resolver.pending_pidfd, 77)

        res = resolver.resolve_exception_result(ValueError("faulty tracker"))
        self.assertFalse(res.success)
        self.assertIs(res.child_handle, self.child)
        self.assertEqual(res.pidfd, 77)
        self.assertTrue(res.pending_cleanup)
        self.assertEqual(res.error, "primary: ValueError")

        tracker.record_exception = lambda exc: (_ for _ in ()).throw(RuntimeError("rec fault"))
        res_fail = resolver.resolve_exception_result(ValueError("double fault"))
        self.assertFalse(res_fail.success)
        self.assertIs(res_fail.child_handle, self.child)
        self.assertEqual(res_fail.pidfd, 77)
        self.assertTrue(res_fail.pending_cleanup)
        self.assertEqual(res_fail.error, "Post-spawn exception: ValueError")

    def test_kise_callback_frames_under_secondary_faults_and_double_factory(self) -> None:
        class FaultyTracker(LauncherCustodyTracker):
            @property
            def pending_child(self) -> Any: raise RuntimeError("child getter boom")
            def record_exception(self, exc: BaseException, helper: Any = None) -> Any: raise RuntimeError("recorder boom")

        _, tracker, resolver = self._make_tracker_resolver(cls=FaultyTracker)
        for exc_cls in (KeyboardInterrupt, SystemExit):
            with self.subTest(exc=exc_cls.__name__):
                pri_err = exc_cls("primary interrupt")
                def cb_fail() -> None: raise pri_err

                def test_rethrow() -> None:
                    try: cb_fail()
                    except BaseException as ex: resolver.resolve_exception_result(ex, rethrow_interrupt=True)
                self._assert_escapes_with_frame(test_rethrow, pri_err, "cb_fail")

                with patch.object(lar_mod, "LaunchResult", side_effect=RuntimeError("prim fail")), \
                     patch.object(lar_mod, "_CANONICAL_LAUNCH_RESULT", side_effect=RuntimeError("canon fail")):
                    def test_df() -> None:
                        try: cb_fail()
                        except BaseException as ex: resolver.resolve_exception_result(ex)
                    self._assert_escapes_with_frame(test_df, pri_err, "cb_fail")


if __name__ == "__main__":
    unittest.main()
