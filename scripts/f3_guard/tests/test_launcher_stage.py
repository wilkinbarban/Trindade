"""Hermetic tests for launcher stage handoff adapter and helper priming."""

from __future__ import annotations

from contextlib import ExitStack
import os
import signal
from typing import Any, Optional, Type
import unittest
from unittest.mock import Mock, patch

from scripts.f3_guard.launcher_custody import LauncherCustodyTracker
from scripts.f3_guard.launcher_pidfd import LauncherPidfdHelper, VerifiedPidfdContext
from scripts.f3_guard.launcher_preflight import (
    DEFAULT_EMULATOR_PATH, TARGET_UID, LaunchResult, LauncherPreflightHelper,
)
from scripts.f3_guard.launcher_spawn import LauncherSpawnHelper, SpawnedChildContext
from scripts.f3_guard.launcher_transfer import LauncherTransferHelper
from scripts.f3_guard.types import ProcessIdentity


class _StrTrapException(Exception):
    def __str__(self) -> str: raise AssertionError("str called")
    def __repr__(self) -> str: raise AssertionError("repr called")


class _TrapFd:
    def __ge__(self, other: object) -> bool: raise AssertionError("fd __ge__ called")
    def __bool__(self) -> bool: raise AssertionError("fd __bool__ called")
    def __str__(self) -> str: raise AssertionError("fd __str__ called")
    def __repr__(self) -> str: raise AssertionError("fd __repr__ called")


class _BoolRaisesChild:
    def __init__(self, pid: int = 12345) -> None: self.pid = pid
    def __bool__(self) -> bool: raise RuntimeError("bool trap")
    def poll(self) -> None: return None


class _FalsyChild:
    def __init__(self, pid: int = 12345) -> None: self.pid = pid
    def __bool__(self) -> bool: return False
    def poll(self) -> None: return None


class _DummyChild:
    def __init__(self, pid: int = 12345) -> None: self.pid = pid
    def poll(self) -> None: return None


class _DummyController:
    def __init__(
        self, has_bind: bool = True, bound_ok: bool = True,
        child_ticks: int = 200, parent_ticks: int = 100, uid: int = TARGET_UID,
    ) -> None:
        self.get_cmdline = lambda p: LauncherPreflightHelper().get_frozen_argv()
        self.get_exe_path = lambda p: DEFAULT_EMULATOR_PATH
        if has_bind: self.bind_anchored_pidfd = lambda p, t, fd: bound_ok
        self.get_start_ticks = lambda p: child_ticks if p == 12345 else parent_ticks
        self.get_uid = lambda p: uid
        self.get_ppid = lambda p: os.getpid()
        self.pidfd_open = lambda p: 5
        self.get_fdinfo_pid = lambda fd: 12345
        self.is_pidfd_alive = lambda fd: True


class _FaultyEarlyContext:
    def __init__(self, child: object, exc: BaseException, pidfd: Optional[int] = None) -> None:
        self.child_handle = child
        self.child_pid = 12345
        self._exc = exc
        self.pidfd = pidfd
        self.parent_ticks = 100
        self.owner_pid = os.getpid()

    @property
    def start_ticks(self) -> int:
        raise self._exc


def _make_failing_helper(base_cls: Type[Any], ctrl: object) -> Any:
    class _Failing(base_cls):  # type: ignore[valid-type, misc]
        def __init__(self) -> None:
            super().__init__(controller=ctrl)
            self.fault_prop, self.fault_exc = None, None

        @property
        def pending_child(self) -> object:
            if self.fault_prop == "pending_child" and self.fault_exc: raise self.fault_exc
            return super().pending_child

        @property
        def pending_pidfd(self) -> object:
            if self.fault_prop == "pending_pidfd" and self.fault_exc: raise self.fault_exc
            return super().pending_pidfd

        @property
        def last_result(self) -> object:
            if self.fault_prop == "last_result" and self.fault_exc: raise self.fault_exc
            return super().last_result

    return _Failing()


class TestLauncherStageHandoff(unittest.TestCase):
    """Hermetic unit tests for helper priming and stage handoff custody."""

    def setUp(self) -> None:
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.proc_count = 0
        self._orig_open, self._orig_os_open = open, os.open

        def _check_proc(p: object) -> None:
            raw = getattr(p, "__fspath__", lambda: p)()
            s = str(raw.decode("utf-8", "surrogateescape") if isinstance(raw, bytes) else raw)
            if "/proc" in s:
                self.proc_count += 1
                raise AssertionError(f"Unexpected /proc: {p}")

        self.stack.enter_context(patch("builtins.open", side_effect=lambda f, *a, **kw: _check_proc(f) or self._orig_open(f, *a, **kw)))
        self.stack.enter_context(patch("os.open", side_effect=lambda p, *a, **kw: _check_proc(p) or self._orig_os_open(p, *a, **kw)))
        self.guard_mocks = {}
        for fn in ("time.monotonic", "time.time", "time.sleep", "os.kill", "os.waitpid", "os.system",
                   *(f"subprocess.{s}" for s in ("Popen", "run", "call", "check_call", "check_output")),
                   *(["os.pidfd_open"] if hasattr(os, "pidfd_open") else []),
                   *(["signal.pidfd_send_signal"] if hasattr(signal, "pidfd_send_signal") else [])):
            m = Mock(side_effect=AssertionError(fn))
            self.guard_mocks[fn] = m
            self.stack.enter_context(patch(fn, m))

    def tearDown(self) -> None:
        self.assertEqual(self.proc_count, 0)
        for m in self.guard_mocks.values():
            m.assert_not_called()

    def _vctx(self, pidfd: int = 5, child: object = None) -> VerifiedPidfdContext:
        return VerifiedPidfdContext(
            spawn_context=SpawnedChildContext(
                child_handle=child or _DummyChild(), child_pid=12345,
                start_ticks=200, parent_ticks=100, owner_pid=os.getpid(),
            ),
            pidfd=pidfd,
        )

    def test_prime_api_and_no_coercion_or_eval(self) -> None:
        ctrl = _DummyController()
        h_pidfd = LauncherPidfdHelper(controller=ctrl)
        h_xfer = LauncherTransferHelper(controller=ctrl)

        for h in (h_pidfd, h_xfer):
            self.assertTrue(hasattr(h, "prime"), f"prime missing on {type(h).__name__}")
            for ch in (_BoolRaisesChild(), _FalsyChild(), _StrTrapException()):
                for raw_fd in (-1, 0, True, _TrapFd(), object(), None):
                    h.prime(ch, raw_fd)
                    self.assertIs(h.pending_child, ch)
                    self.assertIs(h.pending_pidfd, raw_fd)
                    self.assertIsNone(h.last_result)

            res = LaunchResult(success=True)
            h._last_result = res
            h.prime(_DummyChild(), 99)
            self.assertIs(h.last_result, res)

    def test_partial_prime_interruption_matrix(self) -> None:
        ctrl = _DummyController()
        upstream_child = _DummyChild(100)
        primaries = (RuntimeError("part_run"), KeyboardInterrupt("part_ki"), SystemExit(11))

        for helper_cls in (LauncherPidfdHelper, LauncherTransferHelper):
            for exc in primaries:
                with self.subTest(h=helper_cls.__name__, exc=type(exc).__name__):
                    class _PartialPrimeHelper(helper_cls):  # type: ignore[valid-type, misc]
                        def __init__(self) -> None:
                            super().__init__(controller=ctrl)
                            self._fail_on_fd = False

                        def __setattr__(self, name: str, value: object) -> None:
                            if getattr(self, "_fail_on_fd", False) and name == "_pending_pidfd":
                                raise exc
                            super().__setattr__(name, value)

                    if helper_cls is LauncherPidfdHelper:
                        upstream: Any = LauncherSpawnHelper(controller=ctrl)
                        upstream._pending_child = upstream_child
                    else:
                        upstream = LauncherPidfdHelper(controller=ctrl)
                        upstream._pending_child = upstream_child
                        upstream._pending_pidfd = 50

                    tracker = LauncherCustodyTracker(upstream)
                    tracker.snapshot()

                    downstream = _PartialPrimeHelper()
                    downstream._fail_on_fd = True
                    downstream_child = _DummyChild(200)

                    with self.assertRaises(type(exc)) as cm:
                        with tracker.catch_and_record():
                            downstream.prime(downstream_child, pidfd=88)

                    self.assertIs(cm.exception, exc)
                    self.assertIs(tracker.last_exception, exc)
                    self.assertIs(tracker.active_helper, upstream)
                    self.assertIs(tracker.last_snapshot.pending_child, upstream_child)
                    if helper_cls is LauncherTransferHelper:
                        self.assertEqual(tracker.last_snapshot.pending_pidfd, 50)
                    self.assertIs(downstream.pending_child, downstream_child)
                    self.assertIsNone(downstream.pending_pidfd)

    def test_primed_downstream_early_entry_interruption_matrix(self) -> None:
        ctrl = _DummyController()
        primaries = (RuntimeError("early_run"), KeyboardInterrupt("early_ki"), SystemExit(12))

        for helper_cls in (LauncherPidfdHelper, LauncherTransferHelper):
            for exc in primaries:
                with self.subTest(h=helper_cls.__name__, exc=type(exc).__name__):
                    helper = helper_cls(controller=ctrl)
                    child = _DummyChild(54321)
                    fd = 19 if helper_cls is LauncherTransferHelper else None

                    helper.prime(child, fd)
                    tracker = LauncherCustodyTracker(helper)
                    tracker.snapshot()

                    fault_ctx = _FaultyEarlyContext(child=child, exc=exc, pidfd=fd)

                    with self.assertRaises(type(exc)) as cm:
                        with tracker.catch_and_record():
                            if helper_cls is LauncherPidfdHelper:
                                helper.acquire_and_verify(fault_ctx)
                            else:
                                helper.transfer(fault_ctx)

                    self.assertIs(cm.exception, exc)
                    self.assertIs(tracker.last_exception, exc)
                    snap = tracker.last_snapshot
                    self.assertIs(snap.pending_child, child)
                    self.assertIs(snap.pending_pidfd, fd)
                    self.assertFalse(snap.last_result.success)
                    self.assertTrue(snap.last_result.pending_cleanup)

    def test_secondary_getter_faults_and_prior_snapshot_matrix(self) -> None:
        ctrl = _DummyController()
        child = _DummyChild(999)
        fd = 10
        primaries = (RuntimeError("p_run"), KeyboardInterrupt("p_ki"), SystemExit(3))
        secondaries = (RuntimeError("s_run"), KeyboardInterrupt("s_ki"), SystemExit(4))
        props = ("pending_child", "pending_pidfd", "last_result")

        for helper_cls in (LauncherPidfdHelper, LauncherTransferHelper):
            for prim in primaries:
                for prop in props:
                    for sec in secondaries:
                        with self.subTest(
                            h=helper_cls.__name__, p=type(prim).__name__,
                            prop=prop, s=type(sec).__name__,
                        ):
                            # Case A: WITH prior same-helper snapshot
                            h_s = _make_failing_helper(helper_cls, ctrl)
                            h_s.prime(child, fd)
                            tr_s = LauncherCustodyTracker(h_s)
                            tr_s.snapshot()

                            h_s.fault_prop, h_s.fault_exc = prop, sec
                            with self.assertRaises(type(prim)) as cm_s:
                                with tr_s.catch_and_record():
                                    raise prim

                            self.assertIs(cm_s.exception, prim)
                            self.assertIs(tr_s.last_exception, prim)
                            self.assertIs(tr_s.last_snapshot.pending_child, child)
                            self.assertEqual(tr_s.last_snapshot.pending_pidfd, fd)
                            self.assertIn(
                                f"secondary: {prop}: {type(sec).__name__}",
                                tr_s.last_snapshot.last_result.error,
                            )

                            # Case B: WITHOUT prior same-helper snapshot
                            h_u = _make_failing_helper(helper_cls, ctrl)
                            h_u.prime(child, fd)
                            tr_u = LauncherCustodyTracker(h_u)

                            h_u.fault_prop, h_u.fault_exc = prop, sec
                            with self.assertRaises(type(prim)) as cm_u:
                                with tr_u.catch_and_record():
                                    raise prim

                            self.assertIs(cm_u.exception, prim)
                            self.assertIs(tr_u.last_exception, prim)
                            if prop == "pending_child":
                                self.assertIsNone(tr_u.last_snapshot.pending_child)
                                self.assertEqual(tr_u.last_snapshot.pending_pidfd, fd)
                            elif prop == "pending_pidfd":
                                self.assertIs(tr_u.last_snapshot.pending_child, child)
                                self.assertIsNone(tr_u.last_snapshot.pending_pidfd)
                            elif prop == "last_result":
                                self.assertIs(tr_u.last_snapshot.pending_child, child)
                                self.assertEqual(tr_u.last_snapshot.pending_pidfd, fd)
                            self.assertIn(
                                f"secondary: {prop}: {type(sec).__name__}",
                                tr_u.last_snapshot.last_result.error,
                            )

    def test_real_helper_transfer_success_bind_none_and_recovery_metadata(self) -> None:
        ctrl = _DummyController(has_bind=True, bound_ok=True)
        xfer = LauncherTransferHelper(controller=ctrl)
        child = _DummyChild(12345)
        pidfd = 5

        xfer.prime(child, pidfd)
        self.assertIs(xfer.pending_child, child)
        self.assertEqual(xfer.pending_pidfd, pidfd)

        tracker = LauncherCustodyTracker(xfer)
        snap_primed = tracker.snapshot()
        self.assertEqual(snap_primed.pending_pidfd, pidfd)
        self.assertIs(snap_primed.pending_child, child)

        vctx = self._vctx(pidfd=pidfd, child=child)
        res = xfer.transfer(vctx)
        self.assertTrue(res.success)
        self.assertTrue(res.transferred)
        self.assertIsNone(res.pidfd)
        self.assertFalse(res.pending_cleanup)
        self.assertIs(res.child_handle, child)
        self.assertIsNotNone(res.identity)
        ident = res.identity

        self.assertIsNone(xfer.pending_pidfd)
        snap_success = tracker.snapshot()
        self.assertIsNone(snap_success.pending_pidfd)
        self.assertIs(snap_success.last_result, res)
        self.assertIs(snap_success.last_result.identity, ident)
        self.assertFalse(snap_success.last_result.pending_cleanup)

        post_exc = RuntimeError("post transfer crash")
        with self.assertRaises(RuntimeError) as cm:
            with tracker.catch_and_record():
                raise post_exc

        self.assertIs(cm.exception, post_exc)
        self.assertIs(tracker.last_exception, post_exc)
        snap_rec = tracker.last_snapshot
        self.assertIsNone(snap_rec.pending_pidfd)
        self.assertIs(snap_rec.pending_child, child)
        self.assertFalse(snap_rec.last_result.success)
        self.assertTrue(snap_rec.last_result.pending_cleanup)
        self.assertTrue(snap_rec.last_result.transferred)
        self.assertIsNone(snap_rec.last_result.pidfd)
        self.assertIs(snap_rec.last_result.identity, ident)
        self.assertIn("primary: RuntimeError", snap_rec.last_result.error)

    def test_metadata_mismatched_identity_discard(self) -> None:
        ctrl = _DummyController()
        xfer = LauncherTransferHelper(controller=ctrl)
        child_a = _DummyChild(111)
        child_b = _DummyChild(222)
        ident = ProcessIdentity(
            pid=111, start_ticks=200, uid=TARGET_UID,
            avd="test", port=5554, binary_name="emulator",
        )

        xfer.prime(child_a, 5)
        mismatched_res = LaunchResult(
            success=False, identity=ident, child_handle=child_b, pidfd=5, error="mismatch"
        )
        xfer._last_result = mismatched_res

        tracker = LauncherCustodyTracker(xfer)
        snap = tracker.record_exception(RuntimeError("mismatch test"))
        self.assertIsNone(snap.last_result.identity)
