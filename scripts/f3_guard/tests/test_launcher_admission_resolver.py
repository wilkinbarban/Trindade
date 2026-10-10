"""Hermetic unit tests for AdmissionCustodyResolver."""

from __future__ import annotations

from contextlib import nullcontext
import traceback
from typing import Any, Callable, Optional
import unittest
from unittest.mock import patch

from scripts.f3_guard.launcher_admission import LauncherAdmissionHelper
from scripts.f3_guard.launcher_admission_resolver import AdmissionCustodyResolver
import scripts.f3_guard.launcher_admission_resolver as lar_mod
from scripts.f3_guard.launcher_custody import CustodySnapshot, LauncherCustodyTracker
from scripts.f3_guard.launcher_preflight import LaunchResult
from scripts.f3_guard.launcher_custody_protocol import UNKNOWN


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

    def test_resolution_captures_one_rotating_source(self) -> None:
        children = [self.child, _BoolTrapChild(), _BoolTrapChild()]
        sources = [
            _FaultySource(child=child, pidfd=fd)
            for child, fd in zip(children, (0, 11, 22))
        ]
        polls = []
        records = []
        candidates = []
        primary = ValueError("rotating")
        for source in sources:
            candidate = LaunchResult(
                False, child_handle=source.pending_child,
                pidfd=source.pending_pidfd, pending_cleanup=True,
                error="fresh diagnostic",
            )
            candidates.append(candidate)

            def record(exc, source=source, candidate=candidate):
                records.append((source, exc))
                source.last_exception = exc
                return CustodySnapshot(last_result=candidate)

            source.record = record

        class RecordingSource:
            def __init__(self, source):
                self.source = source

            def __getattr__(self, name):
                if name == "record_exception":
                    return self.source.record
                return getattr(self.source, name)

        wrappers = [RecordingSource(source) for source in sources]

        def rotate():
            source = wrappers[len(polls) % len(wrappers)]
            polls.append(source)
            return source

        resolver = AdmissionCustodyResolver(custody_source=rotate)
        for index in range(3):
            result = resolver.resolve_exception_result(primary)
            self.assertIs(result, candidates[index])
            self.assertIs(result.child_handle, children[index])
            self.assertEqual(result.pidfd, (0, 11, 22)[index])
            self.assertIs(resolver.custody_source, rotate)
            self.assertEqual(len(polls), index + 1)
            self.assertEqual(records[-1], (sources[index], primary))
        self.assertEqual(len(records), 3)

    def test_acquisition_secondary_matrix_preserves_primary(self) -> None:
        stages = (
            "source", "last_snapshot", "pending_child", "pending_pidfd",
            "last_result", "snapshot",
        )
        secondary_types = (
            RuntimeError, KeyboardInterrupt, SystemExit, BaseException,
        )
        primary_types = (ValueError,) + secondary_types
        cases = [(stage, same) for stage in stages for same in (False, True)]
        for stage, same_helper in cases:
            for primary_type in primary_types:
                for secondary_type in secondary_types:
                    with self.subTest(
                        stage=stage, same=same_helper,
                        primary=primary_type.__name__,
                        secondary=secondary_type.__name__,
                    ):
                        source = _FaultySource(child=self.child, pidfd=0)
                        primary = primary_type("primary")
                        secondary = secondary_type("secondary")
                        enabled = False
                        polls = []

                        class Fault:
                            def __getattr__(self, name):
                                if enabled and name == stage:
                                    raise secondary
                                return getattr(source, name)

                        faulty = Fault()
                        initial = faulty if same_helper else source
                        resolver = AdmissionCustodyResolver(initial)
                        resolver.snapshot()
                        enabled = True

                        def acquire():
                            polls.append(stage)
                            if stage == "source":
                                raise secondary
                            return faulty

                        resolver.custody_source = acquire

                        def original_raise():
                            raise primary

                        def resolve():
                            try:
                                original_raise()
                            except BaseException as exc:
                                return resolver.resolve_exception_result(
                                    exc, rethrow_interrupt=True,
                                )

                        def check():
                            if isinstance(
                                primary, (KeyboardInterrupt, SystemExit)
                            ):
                                self._assert_escapes_with_frame(
                                    resolve, primary, "original_raise",
                                )
                                return resolver.fallback_result
                            try:
                                return resolve()
                            except BaseException as escaped:
                                self.assertIs(escaped, primary)
                                self.fail(
                                    "Ordinary primary must return failure",
                                )

                        snapshot_patch = (
                            patch.object(
                                resolver, "snapshot", side_effect=secondary,
                            ) if stage == "snapshot" else nullcontext()
                        )
                        with snapshot_patch as snapshot_mock:
                            result = check()
                            self.assertIsNotNone(result)
                            self.assertIs(result.success, False)
                            self.assertEqual(
                                result.error,
                                "Post-spawn exception: "
                                f"{primary_type.__name__}",
                            )
                            self.assertIs(resolver.custody_source, acquire)
                            self.assertEqual(
                                len(polls), 0 if stage == "snapshot" else 1,
                            )
                            child, fd = self.child, 0
                            interrupted = issubclass(
                                secondary_type,
                                (KeyboardInterrupt, SystemExit),
                            )
                            if not same_helper:
                                if stage == "pending_child":
                                    child = None
                                    if interrupted:
                                        fd = None
                                elif stage == "pending_pidfd":
                                    fd = None
                                elif stage == "last_snapshot" and interrupted:
                                    child = fd = None
                            self.assertIs(result.child_handle, child)
                            self.assertEqual(result.pidfd, fd)
                            self.assertIs(
                                result.pending_cleanup,
                                child is not None or fd is not None,
                            )
                            if stage == "snapshot":
                                self.assertEqual(snapshot_mock.call_count, 1)
                            with patch.object(
                                lar_mod, "LaunchResult",
                                side_effect=RuntimeError("factory"),
                            ) as factory, patch.object(
                                lar_mod, "_CANONICAL_LAUNCH_RESULT",
                                side_effect=RuntimeError("canonical"),
                            ) as canonical:
                                self._assert_escapes_with_frame(
                                    resolve, primary, "original_raise",
                                )
                                factory.assert_called_once()
                                canonical.assert_called_once()
                                if stage == "snapshot":
                                    self.assertEqual(
                                        snapshot_mock.call_count, 2,
                                    )
                                    self.assertIs(
                                        snapshot_mock.side_effect, secondary,
                                    )

    def test_resolution_authority_and_same_helper_retention(self) -> None:
        source = _FaultySource(child=self.child, pidfd=0)
        selected = source
        resolver = AdmissionCustodyResolver(lambda: selected)
        resolver.snapshot()
        old = LaunchResult(
            False, child_handle=_BoolTrapChild(), pidfd=99,
            pending_cleanup=True, error="obsolete",
        )
        resolver.set_fallback_result(old)
        cases = (
            (source, self.child, 0),
            (UNKNOWN, self.child, 0),
            (_FaultySource(child=UNKNOWN, pidfd=UNKNOWN), None, None),
            (UNKNOWN, None, None),
            (source, self.child, 0),
            (None, None, None),
            (UNKNOWN, None, None),
        )
        for selected, child, fd in cases:
            result = resolver.resolve_exception_result(ValueError("new"))
            self.assertIs(result.child_handle, child)
            self.assertEqual(result.pidfd, fd)
            self.assertIsNot(result, old)
            self.assertEqual(result.error, "Post-spawn exception: ValueError")
        selected = source
        resolver.snapshot()
        source.pending_child = source.pending_pidfd = UNKNOWN
        retained = resolver.resolve_exception_result(ValueError("unknown"))
        self.assertIs(retained.child_handle, self.child)
        self.assertEqual(retained.pidfd, 0)
        source.pending_child = source.pending_pidfd = None
        cleared = resolver.resolve_exception_result(ValueError("clear"))
        self.assertIsNone(cleared.child_handle)
        self.assertIsNone(cleared.pidfd)
        self.assertFalse(cleared.pending_cleanup)

    def test_ordinary_exception_acquires_new_callback_custody(self) -> None:
        for primary_type in (ValueError, RuntimeError, BaseException):
            with self.subTest(primary=primary_type.__name__):
                source = _FaultySource(child=_BoolTrapChild(), pidfd=77)
                polls = []

                def acquire():
                    polls.append(source)
                    return source

                helper = LauncherAdmissionHelper(acquire)
                helper.snapshot()
                primary = primary_type("new resources")

                def callback():
                    source.pending_child = self.child
                    source.pending_pidfd = 0
                    raise primary

                result = helper.execute(callback)
                self.assertIs(result.child_handle, self.child)
                self.assertEqual(result.pidfd, 0)
                self.assertIs(result.success, False)
                self.assertIs(result.pending_cleanup, True)
                self.assertIs(result.transferred, False)
                self.assertEqual(
                    result.error,
                    f"Post-spawn exception: {primary_type.__name__}",
                )
                self.assertEqual(len(polls), 2)

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
