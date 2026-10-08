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


class _StrTrapException(Exception):
    def __str__(self) -> str: raise AssertionError("str called")
    def __repr__(self) -> str: raise AssertionError("repr called")


class _StrTrapBaseException(BaseException):
    def __str__(self) -> str: raise AssertionError("str called")
    def __repr__(self) -> str: raise AssertionError("repr called")


class _ToggleHelper:
    def __init__(self, c: object = None, p: object = None, r: object = None) -> None:
        self.child, self.fd, self.res = c, p, r
        self.fault_c = self.fault_res = False

    @property
    def pending_child(self) -> object:
        if self.fault_c: raise RuntimeError("fault A")
        return self.child

    @property
    def pending_pidfd(self) -> object: return self.fd

    @property
    def last_result(self) -> object:
        if self.fault_res: raise RuntimeError("fault res")
        return self.res


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

    def test_recovery_api_contracts(self) -> None:
        tracker = LauncherCustodyTracker()
        self.assertIs(CustodySnapshot, LauncherCustodySnapshot)
        self.assertIs(LauncherCustodyTracker.take_snapshot, LauncherCustodyTracker.snapshot)
        for meth in ("record_exception", "catch_and_record", "last_exception"):
            self.assertTrue(hasattr(LauncherCustodyTracker, meth), f"{meth} on class")
            self.assertTrue(hasattr(tracker, meth), f"{meth} on instance")
        self.assertIsNone(tracker.last_exception)

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

    def test_primary_getter_secondary_matrix_and_assert_is(self) -> None:
        child = _DummyChild(999)
        primaries = (RuntimeError("p_run"), KeyboardInterrupt("p_ki"), SystemExit(3))
        props = ("pending_child", "pending_pidfd", "last_result")
        secondaries = (RuntimeError("s_run"), KeyboardInterrupt("s_ki"), SystemExit(4))

        for prim in primaries:
            for prop in props:
                for sec in secondaries:
                    with self.subTest(p=type(prim).__name__, prop=prop, s=type(sec).__name__):
                        class _MatrixHelper:
                            def __init__(self, p: str, s: BaseException) -> None:
                                self._p, self._s = p, s
                            @property
                            def pending_child(self) -> object:
                                if self._p == "pending_child": raise self._s
                                return child
                            @property
                            def pending_pidfd(self) -> object:
                                if self._p == "pending_pidfd": raise self._s
                                return 7
                            @property
                            def last_result(self) -> object:
                                if self._p == "last_result": raise self._s
                                return None

                        h = _MatrixHelper(prop, sec)
                        tr = LauncherCustodyTracker(h)
                        with self.assertRaises(type(prim)) as cm:
                            with tr.catch_and_record():
                                raise prim
                        self.assertIs(cm.exception, prim)
                        self.assertIs(tr.last_exception, prim)
                        self.assertFalse(tr.last_result.success)
                        self.assertIn(f"primary: {type(prim).__name__}", tr.last_result.error)
                        self.assertIn(f"secondary: {prop}: {type(sec).__name__}", tr.last_result.error)
                        if prop == "pending_child":
                            self.assertEqual(tr.pending_pidfd, 7)
                        elif prop == "pending_pidfd":
                            self.assertIs(tr.pending_child, child)
                        elif prop == "last_result":
                            self.assertIs(tr.pending_child, child)
                            self.assertEqual(tr.pending_pidfd, 7)

    def test_partial_fields_retained_on_later_getter_fault(self) -> None:
        child = _DummyChild()
        class _PartialHelper:
            @property
            def pending_child(self) -> object: return child
            @property
            def pending_pidfd(self) -> object: return 12
            @property
            def last_result(self) -> object: raise RuntimeError("last_result failed")

        tr = LauncherCustodyTracker()
        exc = ValueError("step fault")
        snap = tr.record_exception(exc, _PartialHelper())
        self.assertIs(snap.pending_child, child)
        self.assertEqual(snap.pending_pidfd, 12)
        self.assertFalse(snap.last_result.success)
        self.assertTrue(snap.last_result.pending_cleanup)

    def test_same_helper_prior_vs_none_authoritative_and_cross_helper(self) -> None:
        child = _DummyChild()
        h_a = _ToggleHelper(child, 6)
        tr = LauncherCustodyTracker(h_a)
        s_norm = tr.snapshot()
        self.assertIs(s_norm.pending_child, child)

        h_a.fault_c = True
        s_same = tr.record_exception(ValueError("v"), h_a)
        self.assertIs(s_same.pending_child, child)

        h_none = _TrapHelper(child=None, pidfd=None)
        s_none = tr.record_exception(ValueError("v2"), h_none)
        self.assertIsNone(s_none.pending_child)
        self.assertIsNone(s_none.pending_pidfd)

        h_a.fault_c = False
        s_fresh_a = tr.snapshot(h_a)
        self.assertIs(s_fresh_a.pending_child, child)
        self.assertEqual(s_fresh_a.pending_pidfd, 6)

        class _FaultHelperB:
            @property
            def pending_child(self) -> object: raise RuntimeError("fault B")
            @property
            def pending_pidfd(self) -> object: raise RuntimeError("fault B fd")
            @property
            def last_result(self) -> object: return None

        h_b = _FaultHelperB()
        tr.set_active_helper(h_b)
        s_b = tr.record_exception(ValueError("v3"), h_b)
        self.assertIsNone(s_b.pending_child)
        self.assertIsNone(s_b.pending_pidfd)

        s_no_h = tr.record_exception(ValueError("v4"), None)
        self.assertIsNone(s_no_h.pending_child)
        self.assertIsNone(s_no_h.pending_pidfd)

    def test_none_getter_authoritative_no_backfill_and_fault_fallback(self) -> None:
        old_child = _DummyChild(111)
        old_res = LaunchResult(success=False, child_handle=old_child, pidfd=99, error="old")
        h = _TrapHelper(child=None, pidfd=None, res=old_res)
        tr = LauncherCustodyTracker()
        snap = tr.record_exception(ValueError("err"), h)
        self.assertIsNone(snap.pending_child)
        self.assertIsNone(snap.pending_pidfd)
        self.assertIsNone(snap.last_result.child_handle)
        self.assertIsNone(snap.last_result.pidfd)
        self.assertFalse(snap.last_result.pending_cleanup)

        h_same = _ToggleHelper(None, None, old_res)
        tr2 = LauncherCustodyTracker(h_same)
        s_prev = tr2.snapshot()
        self.assertIs(s_prev.last_result, old_res)

        h_same.fault_res = True
        snap2 = tr2.record_exception(ValueError("err2"), h_same)
        self.assertIsNone(snap2.pending_child)
        self.assertIsNone(snap2.pending_pidfd)
        self.assertIsNone(snap2.last_result.child_handle)
        self.assertIsNone(snap2.last_result.pidfd)
        self.assertFalse(snap2.last_result.pending_cleanup)

    def test_mismatched_result_metadata_not_assigned_to_different_resources(self) -> None:
        ident_a = ProcessIdentity(12345, 200, TARGET_UID, TARGET_AVD, TARGET_PORT, "emulator", True)
        child_a = _DummyChild(12345)
        old_res_a = LaunchResult(
            success=False, identity=ident_a, child_handle=child_a, pidfd=55,
            is_direct_child=True, transferred=True,
        )
        child_b = _DummyChild(67890)
        h = _TrapHelper(child=child_b, pidfd=66, res=old_res_a)
        tr = LauncherCustodyTracker()
        snap = tr.record_exception(ValueError("err"), h)
        self.assertIs(snap.pending_child, child_b)
        self.assertEqual(snap.pending_pidfd, 66)
        self.assertIsNone(snap.last_result.identity)
        self.assertFalse(snap.last_result.is_direct_child)
        self.assertFalse(snap.last_result.transferred)

    def test_opaque_child_bool_trap_and_fd_zero(self) -> None:
        child = _BoolRaisesChild()
        h = _TrapHelper(child=child, pidfd=0)
        tr = LauncherCustodyTracker()
        snap = tr.record_exception(ValueError("trap"), h)
        self.assertIs(snap.pending_child, child)
        self.assertEqual(snap.pending_pidfd, 0)
        self.assertTrue(snap.last_result.pending_cleanup)

    def test_diagnostics_str_trap_not_called(self) -> None:
        prim = _StrTrapBaseException()
        sec = _StrTrapException()
        class _TrapPropHelper:
            @property
            def pending_child(self) -> object: raise sec
            @property
            def pending_pidfd(self) -> object: return None
            @property
            def last_result(self) -> object: return None

        tr = LauncherCustodyTracker()
        snap = tr.record_exception(prim, _TrapPropHelper())
        self.assertIn("primary: _StrTrapBaseException", snap.last_result.error)
        self.assertIn("secondary: pending_child: _StrTrapException", snap.last_result.error)

    def test_legit_failed_and_success_anomaly_identity(self) -> None:
        ident = ProcessIdentity(12345, 200, TARGET_UID, TARGET_AVD, TARGET_PORT, "emulator", True)
        child = _DummyChild()
        fail_res = LaunchResult(
            success=False, identity=ident, pidfd=4, child_handle=child,
            error="pre-existing fail; primary: ValueError", is_direct_child=True, pending_cleanup=True, transferred=True,
        )
        h_fail = _TrapHelper(child=child, pidfd=4, res=fail_res)
        tr = LauncherCustodyTracker()
        s_fail = tr.record_exception(ValueError("step err"), h_fail)
        self.assertFalse(s_fail.last_result.success)
        self.assertIs(s_fail.last_result, fail_res)
        self.assertIs(s_fail.last_result.identity, ident)

        fail_synth = LaunchResult(
            success=False, identity=ident, pidfd=4, child_handle=child,
            error="pre-existing fail", is_direct_child=True, pending_cleanup=True, transferred=True,
        )
        s_synth = tr.record_exception(ValueError("step err"), _TrapHelper(child=child, pidfd=4, res=fail_synth))
        self.assertIsNot(s_synth.last_result, fail_synth)
        self.assertIn("pre-existing fail; primary: ValueError", s_synth.last_result.error)

        succ_res = LaunchResult(
            success=True, identity=ident, pidfd=4, child_handle=child,
            is_direct_child=True, pending_cleanup=False, transferred=True,
        )
        h_succ = _TrapHelper(child=child, pidfd=4, res=succ_res)
        s_succ = tr.record_exception(ValueError("anomaly err"), h_succ)
        self.assertFalse(s_succ.last_result.success)
        self.assertIs(s_succ.last_result.identity, ident)
        self.assertTrue(s_succ.last_result.pending_cleanup)

        for res_obj in (s_fail.last_result, s_succ.last_result):
            self.assertEqual(res_obj.identity.pid, 12345)
            self.assertEqual(res_obj.identity.start_ticks, 200)
            self.assertEqual(res_obj.identity.uid, TARGET_UID)
            self.assertEqual(res_obj.identity.avd, TARGET_AVD)
            self.assertEqual(res_obj.identity.port, TARGET_PORT)
            self.assertEqual(res_obj.identity.binary_name, "emulator")
            self.assertTrue(res_obj.identity.is_direct_child)

        for c, f, expect_clean in ((child, None, True), (None, 4, True), (None, None, False)):
            with self.subTest(c=c is not None, f=f is not None):
                s_part = tr.record_exception(ValueError("p"), _TrapHelper(child=c, pidfd=f))
                self.assertEqual(s_part.last_result.pending_cleanup, expect_clean)

    def test_context_manager_normal_and_unexpected_internal_fault(self) -> None:
        tr = LauncherCustodyTracker()
        with tr.catch_and_record():
            pass
        self.assertIsNone(tr.last_exception)

        prim = RuntimeError("orig")
        tr.record_exception = Mock(side_effect=BaseException("catastrophic record failure"))
        with self.assertRaises(RuntimeError) as cm:
            with tr.catch_and_record():
                raise prim
        self.assertIs(cm.exception, prim)
