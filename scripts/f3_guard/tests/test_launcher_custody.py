"""Hermetic tests for LauncherCustodyTracker adapter and normal-return snapshots."""

from __future__ import annotations

from contextlib import ExitStack
from dataclasses import FrozenInstanceError
import os
import signal
import unittest
from unittest.mock import Mock, patch

from scripts.f3_guard.launcher_custody import (
    CustodySnapshot,
    LauncherCustodySnapshot,
    LauncherCustodyTracker,
)
from scripts.f3_guard.launcher_pidfd import LauncherPidfdHelper, VerifiedPidfdContext
from scripts.f3_guard.launcher_preflight import (
    DEFAULT_EMULATOR_PATH, TARGET_AVD, TARGET_PORT, TARGET_UID,
    LaunchResult, LauncherPreflightHelper,
)
from scripts.f3_guard.launcher_spawn import LauncherSpawnHelper, SpawnedChildContext
from scripts.f3_guard.launcher_transfer import LauncherTransferHelper
from scripts.f3_guard.types import ProcessIdentity


class _DummyChild:
    def __init__(self, pid: int = 12345) -> None: self.pid = pid
    def poll(self) -> None: return None


class _BoolRaisesChild:
    def __init__(self, pid: int = 12345) -> None: self.pid = pid
    def __bool__(self) -> bool: raise RuntimeError("bool trap")
    def poll(self) -> None: return None


class _FalsyChild:
    def __init__(self, pid: int = 12345) -> None: self.pid = pid
    def __bool__(self) -> bool: return False
    def poll(self) -> None: return None


class _TrapHelper:
    def __init__(self, child: object = None, pidfd: object = None, res: object = None) -> None:
        self._c, self._f, self._r = child, pidfd, res

    def __getattr__(self, name: str) -> object:
        if name.startswith("_"): raise AssertionError(f"Private attribute accessed: {name}")
        raise AttributeError(name)

    @property
    def pending_child(self) -> object: return self._c
    @property
    def pending_pidfd(self) -> object: return self._f
    @property
    def last_result(self) -> object: return self._r


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


class TestLauncherCustodyTracker(unittest.TestCase):
    """Hermetic unit tests for LauncherCustodyTracker normal-return contract."""

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

    def test_forbidden_recovery_api_absent(self) -> None:
        tracker = LauncherCustodyTracker()
        self.assertIs(CustodySnapshot, LauncherCustodySnapshot)
        self.assertIs(LauncherCustodyTracker.take_snapshot, LauncherCustodyTracker.snapshot)
        for forbidden in ("record_exception", "catch_and_record", "last_exception"):
            self.assertFalse(hasattr(LauncherCustodyTracker, forbidden), f"{forbidden} on class")
            self.assertFalse(hasattr(tracker, forbidden), f"{forbidden} on instance")

    def test_active_stage_selection_and_no_fallback(self) -> None:
        child = _DummyChild()
        h1 = _TrapHelper(child=child, pidfd=7)
        h2 = _TrapHelper(child=None, pidfd=None)
        tracker = LauncherCustodyTracker(h1)
        s1 = tracker.snapshot()
        self.assertIs(s1.pending_child, child)
        self.assertEqual(s1.pending_pidfd, 7)
        s2 = tracker.snapshot(h2)
        self.assertIsNone(s2.pending_child)
        self.assertIsNone(s2.pending_pidfd)
        self.assertIs(h1.pending_child, child)
        self.assertEqual(h1.pending_pidfd, 7)
        h3 = _TrapHelper(child=child, pidfd=11)
        tracker.set_active_helper(h3)
        self.assertIs(tracker.active_helper, h3)
        s3 = tracker.take_snapshot()
        self.assertEqual(s3.pending_pidfd, 11)

    def test_public_only_trap_and_none_helper(self) -> None:
        trap = _TrapHelper(child=_DummyChild(), pidfd=9, res=LaunchResult(success=True))
        tracker = LauncherCustodyTracker()
        snap = tracker.snapshot(trap)
        self.assertIs(snap.pending_child, trap.pending_child)
        self.assertEqual(snap.pending_pidfd, 9)
        self.assertIs(snap.last_result, trap.last_result)
        s_none = tracker.snapshot(None)
        self.assertIsNone(s_none.pending_child)
        self.assertIsNone(s_none.pending_pidfd)
        self.assertIsNone(s_none.last_result)

    def test_falsey_and_bool_raises_child_no_evaluation(self) -> None:
        for ch in (_BoolRaisesChild(), _FalsyChild()):
            with self.subTest(child_type=type(ch).__name__):
                h = _TrapHelper(child=ch, pidfd=3)
                snap = LauncherCustodyTracker().snapshot(h)
                self.assertIs(snap.pending_child, ch)
                self.assertIs(snap.child_handle, ch)

    def test_zero_fd_preserved_and_opaque_value_identity(self) -> None:
        h0 = _TrapHelper(child=_DummyChild(), pidfd=0)
        s0 = LauncherCustodyTracker().snapshot(h0)
        self.assertIs(type(s0.pending_pidfd), int)
        self.assertEqual(s0.pending_pidfd, 0)
        self.assertEqual(s0.pidfd, 0)

        class _ComparatorTrap:
            def __ge__(self, other: object) -> bool:
                raise RuntimeError("comparator trap")

        trap_obj = _ComparatorTrap()
        for opaque in (True, False, -1, "5", 3.14, trap_obj):
            h_op = _TrapHelper(pidfd=opaque)
            s_op = LauncherCustodyTracker().snapshot(h_op)
            self.assertIs(s_op.pending_pidfd, opaque)
            self.assertIs(s_op.pidfd, opaque)

    def test_last_result_none_preserved_not_synthetic_failure(self) -> None:
        child = _DummyChild()
        h = _TrapHelper(child=child, pidfd=None, res=None)
        tracker = LauncherCustodyTracker(h)
        snap = tracker.snapshot()
        self.assertIs(snap.pending_child, child)
        self.assertIsNone(snap.last_result)
        self.assertIsNone(tracker.last_result)

    def test_snapshot_frozen_isolation_and_process_identity(self) -> None:
        ident = ProcessIdentity(12345, 200, TARGET_UID, TARGET_AVD, TARGET_PORT, "emulator", True)
        res = LaunchResult(success=True, identity=ident, transferred=True)
        h = _TrapHelper(child=_DummyChild(), pidfd=5, res=res)
        tracker = LauncherCustodyTracker(h)
        snap = tracker.snapshot()
        with self.assertRaises(FrozenInstanceError):
            snap.pending_child = None  # type: ignore
        self.assertIs(snap.last_result, res)
        self.assertIs(snap.last_result.identity, ident)
        self.assertEqual(snap.last_result.identity.pid, 12345)
        self.assertEqual(snap.last_result.identity.start_ticks, 200)
        self.assertEqual(snap.last_result.identity.uid, TARGET_UID)
        self.assertEqual(snap.last_result.identity.avd, TARGET_AVD)
        self.assertEqual(snap.last_result.identity.port, TARGET_PORT)
        self.assertEqual(snap.last_result.identity.binary_name, "emulator")
        self.assertTrue(snap.last_result.identity.is_direct_child)
        h._c = None
        self.assertIsNotNone(snap.pending_child)

    def test_transfer_helper_binder_absent_vs_accepted(self) -> None:
        h_abs = LauncherTransferHelper(controller=_DummyController(has_bind=False))
        h_abs.transfer(self._vctx(pidfd=8))
        s_abs = LauncherCustodyTracker().snapshot(h_abs)
        self.assertEqual(s_abs.pending_pidfd, 8)
        self.assertTrue(s_abs.last_result.transferred)
        self.assertIsNone(s_abs.last_result.pidfd)

        h_acc = LauncherTransferHelper(controller=_DummyController(bound_ok=True))
        h_acc.transfer(self._vctx(pidfd=8))
        s_acc = LauncherCustodyTracker().snapshot(h_acc)
        self.assertIsNone(s_acc.pending_pidfd)
        self.assertTrue(s_acc.last_result.transferred)

    def test_actual_spawn_and_pidfd_helper_contexts(self) -> None:
        child = _DummyChild()
        spawn_fail = LauncherSpawnHelper(spawner=None)
        ctx_none, res_fail = spawn_fail.spawn_and_verify(["dummy"])
        s_sf = LauncherCustodyTracker().snapshot(spawn_fail)
        self.assertIsNone(s_sf.pending_child)
        self.assertIsNone(s_sf.pending_pidfd)
        self.assertIs(s_sf.last_result, res_fail)

        spawn_ok = LauncherSpawnHelper(spawner=lambda argv: child, controller=_DummyController())
        ctx_ok, res_none = spawn_ok.spawn_and_verify(["dummy"])
        s_so = LauncherCustodyTracker().snapshot(spawn_ok)
        self.assertIs(s_so.pending_child, child)
        self.assertIsNone(s_so.pending_pidfd)
        self.assertIsNone(s_so.last_result)

        ctrl_pid_fail = _DummyController()
        ctrl_pid_fail.is_pidfd_alive = lambda fd: False
        pid_h = LauncherPidfdHelper(controller=ctrl_pid_fail)
        pid_ctx_none, pid_res_fail = pid_h.acquire_and_verify(ctx_ok)
        s_pf = LauncherCustodyTracker().snapshot(pid_h)
        self.assertIs(s_pf.pending_child, child)
        self.assertEqual(s_pf.pending_pidfd, 5)
        self.assertIs(s_pf.last_result, pid_res_fail)

    def test_getter_faults_propagate_and_preserve_atomic_state(self) -> None:
        class _FailingPropertyHelper:
            def __init__(self, fault_prop: str, exc: BaseException) -> None:
                self._prop, self._exc = fault_prop, exc
                self._c, self._f, self._r = _DummyChild(), 4, None

            @property
            def pending_child(self) -> object:
                if self._prop == "pending_child": raise self._exc
                return self._c

            @property
            def pending_pidfd(self) -> object:
                if self._prop == "pending_pidfd": raise self._exc
                return self._f

            @property
            def last_result(self) -> object:
                if self._prop == "last_result": raise self._exc
                return self._r

        good_h = _TrapHelper(child=_DummyChild(), pidfd=4)
        tracker = LauncherCustodyTracker(good_h)
        prior_snap = tracker.snapshot()
        self.assertEqual(prior_snap.pending_pidfd, 4)

        for prop in ("pending_child", "pending_pidfd", "last_result"):
            for exc in (RuntimeError("getter fault"), KeyboardInterrupt("abort"), SystemExit(2)):
                with self.subTest(prop=prop, exc_type=type(exc).__name__):
                    fail_h = _FailingPropertyHelper(prop, exc)
                    with self.assertRaises(type(exc)) as cm:
                        tracker.snapshot(fail_h)
                    self.assertIs(cm.exception, exc)
                    self.assertIs(tracker.last_snapshot, prior_snap)
                    self.assertIs(tracker.active_helper, good_h)
