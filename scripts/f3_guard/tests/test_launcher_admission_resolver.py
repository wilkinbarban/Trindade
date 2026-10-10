"""Hermetic unit tests for AdmissionCustodyResolver."""

from __future__ import annotations

from contextlib import nullcontext
from dataclasses import replace
from types import SimpleNamespace
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

    def _resolve_b2c(self, resolver, primary, rethrow, escapes):
        def original_raise():
            raise primary

        def resolve():
            try:
                original_raise()
            except BaseException as exc:
                return resolver.resolve_exception_result(
                    exc, rethrow_interrupt=rethrow,
                )

        if escapes:
            self._assert_escapes_with_frame(
                resolve, primary, "original_raise",
            )
            return None
        try:
            return resolve()
        except BaseException as escaped:
            self.assertIs(escaped, primary)
            self.fail("Valid failure must remain returnable")

    def _assert_b2c_failure(self, result, child, fd, diagnostic):
        self.assertIs(type(result), LaunchResult)
        self.assertIs(result.success, False)
        self.assertIs(result.transferred, False)
        self.assertIs(result.child_handle, child)
        self.assertIs(type(result.pidfd), type(fd))
        self.assertEqual(result.pidfd, fd)
        self.assertIs(
            result.pending_cleanup, child is not None or fd is not None,
        )
        self.assertEqual(result.error, diagnostic)

    def test_canonical_publication_faults_without_primary_formatting(
        self,
    ) -> None:
        secondary_types = (
            RuntimeError, KeyboardInterrupt, SystemExit, BaseException,
        )
        for first_type in secondary_types:
            for publication_type in secondary_types:
                for rethrow in (False, True):
                    with self.subTest(
                        first=first_type.__name__,
                        publication=publication_type.__name__,
                        rethrow=rethrow,
                    ):
                        primary = _StrReprTrap()
                        source = _FaultySource(child=self.child, pidfd=0)
                        resolver = AdmissionCustodyResolver(source)
                        with patch.object(
                            lar_mod, "LaunchResult",
                            side_effect=first_type("first"),
                        ) as first, patch.object(
                            lar_mod, "_CANONICAL_LAUNCH_RESULT",
                            wraps=LaunchResult,
                        ) as canonical, patch.object(
                            resolver, "set_fallback_result",
                            side_effect=publication_type("publication"),
                        ) as publication:
                            result = self._resolve_b2c(
                                resolver, primary, rethrow, False,
                            )
                            first.assert_called_once()
                            canonical.assert_called_once()
                            publication.assert_called_once_with(result)
                            self._assert_b2c_failure(
                                result, self.child, 0,
                                "Post-spawn exception: _StrReprTrap",
                            )
                            self.assertIsNone(resolver.fallback_result)

    def test_real_factory_secondary_matrix(self) -> None:
        secondary_types = (
            RuntimeError, KeyboardInterrupt, SystemExit, BaseException,
        )
        for child, fd in ((self.child, 0), (None, 0), (None, None)):
            for primary_type in (ValueError,) + secondary_types:
                for first_type in secondary_types:
                    for canonical_type in (None,) + secondary_types:
                        for rethrow in (False, True):
                            with self.subTest(
                                child=child is not None, fd=fd,
                                primary=primary_type.__name__,
                                first=first_type.__name__,
                                canonical=canonical_type,
                                rethrow=rethrow,
                            ):
                                primary = primary_type("primary")
                                source = _FaultySource(child=child, pidfd=fd)
                                resolver = AdmissionCustodyResolver(source)
                                history = LaunchResult(
                                    False, child_handle=_BoolTrapChild(),
                                    pidfd=99, pending_cleanup=True,
                                    error="obsolete",
                                )
                                resolver.set_fallback_result(history)
                                canonical_error = (
                                    canonical_type("canonical")
                                    if canonical_type is not None else None
                                )
                                with patch.object(
                                    lar_mod, "LaunchResult",
                                    side_effect=first_type("first"),
                                ) as first, patch.object(
                                    lar_mod, "_CANONICAL_LAUNCH_RESULT",
                                    side_effect=canonical_error,
                                    wraps=LaunchResult,
                                ) as canonical, patch.object(
                                    resolver, "set_fallback_result",
                                    wraps=resolver.set_fallback_result,
                                ) as publish:
                                    unavailable = canonical_type is not None
                                    interrupt = isinstance(
                                        primary,
                                        (KeyboardInterrupt, SystemExit),
                                    )
                                    escapes = unavailable or (
                                        rethrow and interrupt
                                    )
                                    result = self._resolve_b2c(
                                        resolver, primary, rethrow, escapes,
                                    )
                                    first.assert_called_once()
                                    canonical.assert_called_once()
                                    self.assertEqual(
                                        first.call_args, canonical.call_args,
                                    )
                                    self.assertIs(
                                        resolver.custody_source, source,
                                    )
                                    if unavailable:
                                        publish.assert_not_called()
                                        self.assertIs(
                                            resolver.fallback_result, history,
                                        )
                                        continue
                                    publish.assert_called_once()
                                    published = publish.call_args.args[0]
                                    if not escapes:
                                        self.assertIs(result, published)
                                    self.assertIsNot(published, history)
                                    self.assertIs(
                                        resolver.fallback_result, published,
                                    )
                                    self._assert_b2c_failure(
                                        published, child, fd,
                                        "Post-spawn exception: "
                                        f"{primary_type.__name__}",
                                    )

    def test_publication_secondary_matrix(self) -> None:
        secondary_types = (
            RuntimeError, KeyboardInterrupt, SystemExit, BaseException,
        )
        for recorded in (False, True):
            for mutate in (False, True):
                for primary_type in (ValueError,) + secondary_types:
                    for secondary_type in secondary_types:
                        for rethrow in (False, True):
                            with self.subTest(
                                recorded=recorded, mutate=mutate,
                                primary=primary_type.__name__,
                                secondary=secondary_type.__name__,
                                rethrow=rethrow,
                            ):
                                primary = primary_type("primary")
                                candidate = LaunchResult(
                                    False, child_handle=self.child, pidfd=0,
                                    pending_cleanup=True, error="fresh",
                                )
                                source = _FaultySource(
                                    child=self.child, pidfd=0,
                                    custom_snap=CustodySnapshot(
                                        last_result=candidate,
                                    ) if recorded else None,
                                    set_last_exc=recorded,
                                )
                                resolver = AdmissionCustodyResolver(source)
                                history = LaunchResult(False, error="old")
                                resolver.set_fallback_result(history)
                                setter = resolver.set_fallback_result
                                attempts = []
                                records = []
                                recorder = source.record_exception

                                def record(exc):
                                    records.append(exc)
                                    return recorder(exc)

                                def publish(result):
                                    attempts.append(result)
                                    if mutate:
                                        setter(result)
                                    raise secondary_type("publication")

                                with patch.object(
                                    resolver, "set_fallback_result",
                                    side_effect=publish,
                                ) as publication, patch.object(
                                    _FaultySource, "record_exception",
                                    property(lambda source: record),
                                ):
                                    escapes = rethrow and isinstance(
                                        primary,
                                        (KeyboardInterrupt, SystemExit),
                                    )
                                    result = self._resolve_b2c(
                                        resolver, primary, rethrow, escapes,
                                    )
                                    publication.assert_called_once()
                                    self.assertEqual(len(attempts), 1)
                                    self.assertEqual(len(records), 1)
                                    self.assertIs(records[0], primary)
                                    attempted = attempts[0]
                                    if not escapes:
                                        self.assertIs(result, attempted)
                                    if recorded:
                                        self.assertIs(attempted, candidate)
                                    else:
                                        self.assertIsNot(attempted, candidate)
                                    self._assert_b2c_failure(
                                        attempted, self.child, 0,
                                        "fresh" if recorded else
                                        "Post-spawn exception: "
                                        f"{primary_type.__name__}",
                                    )
                                    self.assertIsNot(attempted, history)
                                    self.assertIs(
                                        resolver.fallback_result,
                                        attempted if mutate else history,
                                    )
                                    self.assertIs(
                                        resolver.custody_source, source,
                                    )

    def test_recorder_interrupt_does_not_replace_ordinary_primary(
        self,
    ) -> None:
        source = _FaultySource(child=self.child, pidfd=0)
        secondary = KeyboardInterrupt("recorder")

        class RecorderTrap:
            def __getattr__(self, name):
                if name == "record_exception":
                    raise secondary
                return getattr(source, name)

        primary = ValueError("primary")
        resolver = AdmissionCustodyResolver(RecorderTrap())
        try:
            result = resolver.resolve_exception_result(primary)
        except BaseException as escaped:
            self.assertIs(escaped, primary)
            self.fail("Ordinary primary must return failure")
        self.assertIs(result.success, False)
        self.assertIs(result.child_handle, self.child)
        self.assertEqual(result.pidfd, 0)
        self.assertEqual(result.error, "Post-spawn exception: ValueError")

    def test_acquired_history_survives_changing_inventory(self) -> None:
        _, tracker, resolver = self._make_tracker_resolver(pidfd=0)
        primary = ValueError("fresh history")
        history = resolver.resolve_exception_result(primary)
        self.assertIs(history, tracker.last_result)
        source = _FaultySource(
            child=self.child, pidfd=0, stale_res=history,
            custom_snap=CustodySnapshot(last_result=history),
            set_last_exc=True,
        )
        reads = []

        class ChangingInventory:
            def __getattr__(self, name):
                if name == "last_result":
                    reads.append(name)
                    return history if len(reads) == 1 else None
                return getattr(source, name)

        result = AdmissionCustodyResolver(
            ChangingInventory(),
        ).resolve_exception_result(primary)
        self.assertIsNot(result, history)
        self.assertIs(result.success, False)
        self.assertIs(result.child_handle, self.child)
        self.assertEqual(result.pidfd, 0)
        self.assertIs(result.pending_cleanup, True)
        self.assertEqual(result.error, "Post-spawn exception: ValueError")
        self.assertEqual(len(reads), 2)

    def test_recorder_secondary_matrix_preserves_primary(self) -> None:
        stages = (
            "source.last_snapshot", "source.active_helper",
            "source.last_result", "active.last_result", "prior.last_result",
            "source.record_exception", "invoke", "source.last_exception",
            "snapshot.last_exception", "snapshot.last_result",
            "candidate.success", "candidate.pidfd", "candidate.child_handle",
            "candidate.pending_cleanup", "candidate.transferred",
        )
        secondary_types = (
            RuntimeError, KeyboardInterrupt, SystemExit, BaseException,
        )
        for stage in stages:
            for primary_type in (ValueError,) + secondary_types:
                for secondary_type in secondary_types:
                    for mode in ("return", "interrupt", "unavailable"):
                        with self.subTest(
                            stage=stage, primary=primary_type.__name__,
                            secondary=secondary_type.__name__, mode=mode,
                        ):
                            primary = primary_type("primary")
                            secondary = secondary_type("secondary")
                            enabled = False
                            hits = []
                            records = []

                            def trap(name):
                                if enabled and name == stage:
                                    hits.append(name)
                                    raise secondary

                            class View:
                                def __init__(self, label, **fields):
                                    self.label = label
                                    self.fields = fields

                                def __getattr__(self, name):
                                    trap(f"{self.label}.{name}")
                                    return self.fields.get(name)

                            class Candidate(LaunchResult):
                                def __getattribute__(self, name):
                                    trap(f"candidate.{name}")
                                    return super().__getattribute__(name)

                            candidate = Candidate(
                                False, child_handle=self.child, pidfd=0,
                                pending_cleanup=True, error="fresh",
                            )
                            prior = View("prior", last_result=None)
                            snap = View(
                                "snapshot", last_result=candidate,
                                last_exception=primary,
                            )
                            source = View(
                                "source", pending_child=self.child,
                                pending_pidfd=0, last_snapshot=prior,
                                active_helper=View("active"),
                            )

                            def record(exc):
                                records.append(exc)
                                trap("invoke")
                                source.fields["last_exception"] = (
                                    None if stage == "snapshot.last_exception"
                                    else exc
                                )
                                return snap

                            source.fields["record_exception"] = record
                            resolver = AdmissionCustodyResolver(source)
                            snapshot = resolver.snapshot

                            def acquire():
                                nonlocal enabled
                                custody = snapshot()
                                enabled = True
                                return custody

                            def original_raise():
                                raise primary

                            def resolve():
                                try:
                                    original_raise()
                                except BaseException as exc:
                                    return resolver.resolve_exception_result(
                                        exc,
                                        rethrow_interrupt=mode != "return",
                                    )

                            unavailable = (
                                patch.object(
                                    lar_mod, "_safe_result",
                                    side_effect=RuntimeError("unavailable"),
                                ) if mode == "unavailable" else nullcontext()
                            )
                            with patch.object(
                                resolver, "snapshot", side_effect=acquire,
                            ), unavailable:
                                interrupt = isinstance(
                                    primary, (KeyboardInterrupt, SystemExit),
                                )
                                escapes = mode == "unavailable" or (
                                    mode == "interrupt" and interrupt
                                )
                                if escapes:
                                    self._assert_escapes_with_frame(
                                        resolve, primary, "original_raise",
                                    )
                                    result = resolver.fallback_result
                                else:
                                    try:
                                        result = resolve()
                                    except BaseException as escaped:
                                        self.assertIs(escaped, primary)
                                        self.fail("Must return failure")
                            self.assertEqual(hits, [stage])
                            expected_records = (
                                [] if stage in stages[:6] else [primary]
                            )
                            self.assertEqual(
                                len(records), len(expected_records),
                            )
                            self.assertTrue(all(r is primary for r in records))
                            self.assertIs(resolver.custody_source, source)
                            if mode == "unavailable":
                                self.assertIsNone(result)
                                continue
                            self.assertIsNot(result, candidate)
                            self.assertIs(result.success, False)
                            self.assertIs(result.transferred, False)
                            self.assertIs(result.pending_cleanup, True)
                            self.assertIs(result.child_handle, self.child)
                            self.assertEqual(result.pidfd, 0)
                            self.assertEqual(
                                result.error,
                                "Post-spawn exception: "
                                f"{primary_type.__name__}",
                            )

    def test_fresh_candidate_proof_and_history_alternates(self) -> None:
        class IntSubclass(int):
            pass

        primary = ValueError("current")
        _, tracker, resolver = self._make_tracker_resolver(pidfd=0)
        history = resolver.resolve_exception_result(primary)
        self.assertIs(history, tracker.last_result)
        fresh = replace(history, error="fresh")
        cases = (
            ("source proof", fresh, True),
            ("snapshot proof", fresh, True),
            ("absent custody", LaunchResult(False, error="fresh"), True),
            ("source history", history, False),
            ("active history", history, False),
            ("prior history", history, False),
            ("no-op snapshot", fresh, False),
            ("missing proof", fresh, False),
            ("wrong proof", fresh, False),
            ("wrong result", SimpleNamespace(**vars(fresh)), False),
            ("success", replace(fresh, success=True), False),
            ("cleanup", replace(fresh, pending_cleanup=False), False),
            ("transfer", replace(fresh, transferred=True), False),
            ("bool fd", replace(fresh, pidfd=False), False),
            ("subclass fd", replace(fresh, pidfd=IntSubclass(0)), False),
            ("wrong child", replace(fresh, child_handle=object()), False),
        )
        for label, candidate, accepted in cases:
            with self.subTest(case=label):
                child = None if label == "absent custody" else self.child
                fd = None if label == "absent custody" else 0
                snapshot = SimpleNamespace(last_result=candidate)
                source = SimpleNamespace(
                    pending_child=child, pending_pidfd=fd,
                    last_result=None, last_snapshot=None, active_helper=None,
                )
                if label == "source history":
                    source.last_result = history
                elif label == "active history":
                    source.active_helper = SimpleNamespace(last_result=history)
                elif label == "prior history":
                    source.last_snapshot = CustodySnapshot(last_result=history)
                elif label == "no-op snapshot":
                    source.last_snapshot = snapshot

                def record(exc):
                    if label == "snapshot proof":
                        snapshot.last_exception = exc
                    elif label == "wrong proof":
                        source.last_exception = ValueError("current")
                    elif label != "missing proof":
                        source.last_exception = exc
                    return snapshot

                source.record_exception = record
                current = AdmissionCustodyResolver(source)
                result = current.resolve_exception_result(primary)
                if accepted:
                    self.assertIs(result, candidate)
                else:
                    self.assertIsNot(result, candidate)
                    self.assertIsNot(result, history)
                    self.assertEqual(
                        result.error, "Post-spawn exception: ValueError",
                    )
                self.assertIs(result.success, False)
                self.assertIs(result.transferred, False)
                self.assertIs(result.child_handle, child)
                self.assertEqual(result.pidfd, fd)
                self.assertIs(result.pending_cleanup, child is not None)
                self.assertIs(current.fallback_result, result)

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
