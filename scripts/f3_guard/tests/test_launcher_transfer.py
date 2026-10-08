"""Hermetic tests for launcher process transfer and post-spawn verification."""

from __future__ import annotations

from contextlib import ExitStack
import os
import signal
import sys
import unittest
from unittest.mock import Mock, patch

from scripts.f3_guard.launcher_pidfd import VerifiedPidfdContext
from scripts.f3_guard.launcher_preflight import (
    DEFAULT_EMULATOR_PATH, TARGET_AVD, TARGET_PORT, TARGET_UID,
    LauncherPreflightHelper,
)
from scripts.f3_guard.launcher_spawn import SpawnedChildContext
from scripts.f3_guard.launcher_transfer import LauncherTransferHelper
from scripts.f3_guard.proc_identity import verify_process_identity
from scripts.f3_guard.types import ProcessIdentity


class _DummyChild:
    def __init__(self, pid: int = 12345) -> None: self.pid = pid


class _StrReprTrapException(Exception):
    def __str__(self) -> str: raise AssertionError("__str__ trap executed")
    def __repr__(self) -> str: raise AssertionError("__repr__ trap executed")


class _StrReprTrapKI(KeyboardInterrupt):
    def __str__(self) -> str: raise AssertionError("__str__ trap executed")
    def __repr__(self) -> str: raise AssertionError("__repr__ trap executed")


class _StrReprTrapSE(SystemExit):
    def __str__(self) -> str: raise AssertionError("__str__ trap executed")
    def __repr__(self) -> str: raise AssertionError("__repr__ trap executed")


class _FaultyTransferHelper(LauncherTransferHelper):
    def __init__(self, *args: object, **kwargs: object) -> None:
        super().__init__(*args, **kwargs)
        self.arm_attr_fault = None
        self.fault_attr_name = "_pending_pidfd"

    def __setattr__(self, name: str, value: object) -> None:
        if getattr(self, "arm_attr_fault", None) is not None and name == getattr(self, "fault_attr_name", "_pending_pidfd"):
            exc = self.arm_attr_fault
            self.arm_attr_fault = None
            raise exc
        super().__setattr__(name, value)


_DEFAULT = object()


class _BadIter:
    def __init__(self, exc: BaseException) -> None: self.exc = exc
    def __bool__(self) -> bool: return True
    def __iter__(self) -> object: raise self.exc


class _DummyController:
    def __init__(
        self, cmdline: object = _DEFAULT, exe_path: object = DEFAULT_EMULATOR_PATH,
        has_exe: bool = True, has_bind: bool = True, bound_ok: object = True,
        child_ticks: object = 200, parent_ticks: object = 100, uid: object = TARGET_UID,
    ) -> None:
        raw_cmd = LauncherPreflightHelper().get_frozen_argv() if cmdline is _DEFAULT else cmdline
        self.get_cmdline = raw_cmd if callable(raw_cmd) else lambda p: raw_cmd
        if has_exe: self.get_exe_path = exe_path if callable(exe_path) else lambda p: exe_path
        if has_bind: self.bind_anchored_pidfd = bound_ok if callable(bound_ok) else lambda p, t, fd: bound_ok
        self.get_start_ticks = lambda p: child_ticks if p == 12345 else parent_ticks
        self.get_uid = lambda p: uid


class TestLauncherTransfer(unittest.TestCase):
    """Hermetic unit tests for LauncherTransferHelper."""

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

    def _ctx(self, pidfd: int = 5, child: object = None, start_ticks: int = 200) -> VerifiedPidfdContext:
        return VerifiedPidfdContext(
            spawn_context=SpawnedChildContext(
                child_handle=child or _DummyChild(), child_pid=12345,
                start_ticks=start_ticks, parent_ticks=100, owner_pid=10000,
            ),
            pidfd=pidfd,
        )

    def test_transfer_success_and_binding_oddity(self) -> None:
        ctx = self._ctx(pidfd=5)
        for bound_val in (True, 1):
            with self.subTest(bound_val=bound_val):
                h = LauncherTransferHelper(controller=_DummyController(bound_ok=bound_val))
                res = h.transfer(ctx)
                self.assertTrue(res.success and res.transferred and res.is_direct_child)
                self.assertFalse(res.pending_cleanup)
                self.assertEqual((res.pidfd, h.pending_pidfd, h.last_result), (None, None, res))
                self.assertEqual((res.child_handle, h.pending_child), (ctx.child_handle, ctx.child_handle))
                self.assertEqual(res.identity, ProcessIdentity(12345, 200, TARGET_UID, TARGET_AVD, TARGET_PORT, "emulator", True))

        # Absent binding preserves source oddity: res.pidfd=None, but h.pending_pidfd retained
        h_abs = LauncherTransferHelper(controller=_DummyController(has_bind=False))
        res_abs = h_abs.transfer(ctx)
        self.assertTrue(res_abs.success and res_abs.transferred)
        self.assertEqual((res_abs.pidfd, h_abs.pending_pidfd), (None, 5))

        # pidfd == 0 retained on absent binding, cleared on present binding
        ctx0 = self._ctx(pidfd=0)
        h0_abs = LauncherTransferHelper(controller=_DummyController(has_bind=False))
        self.assertEqual((h0_abs.transfer(ctx0).pidfd, h0_abs.pending_pidfd), (None, 0))
        h0_bnd = LauncherTransferHelper(controller=_DummyController(bound_ok=True))
        self.assertEqual((h0_bnd.transfer(ctx0).pidfd, h0_bnd.pending_pidfd), (None, None))

    def test_cmdline_validation_table(self) -> None:
        ctx = self._ctx()
        for bad_cmd in ([], "", (), None):
            with self.subTest(bad_cmd=bad_cmd):
                h = LauncherTransferHelper(controller=_DummyController(cmdline=bad_cmd))
                res = h.transfer(ctx)
                self.assertEqual((res.success, res.pidfd, res.pending_cleanup, h.last_result), (False, 5, True, res))
                self.assertIn("Child cmdline empty or unreadable", res.error)
                self.assertIsNone(res.identity)

        res_exc = LauncherTransferHelper(controller=_DummyController(cmdline=Mock(side_effect=RuntimeError("cmdline error")))).transfer(ctx)
        self.assertEqual((res_exc.success, res_exc.pidfd), (False, 5))
        self.assertIn("Post-spawn exception: RuntimeError", res_exc.error)

        res_noniter = LauncherTransferHelper(controller=_DummyController(cmdline=12345)).transfer(ctx)
        self.assertEqual((res_noniter.success, res_noniter.pidfd), (False, 5))
        self.assertIn("Post-spawn exception:", res_noniter.error)

    def test_exe_path_validation_table(self) -> None:
        ctx = self._ctx()
        res_abs = LauncherTransferHelper(controller=_DummyController(has_exe=False)).transfer(ctx)
        self.assertEqual((res_abs.pidfd, res_abs.error), (5, "Controller missing get_exe_path reader"))

        ctrl_nc = _DummyController()
        ctrl_nc.get_exe_path = "/not/callable"
        self.assertIn("Post-spawn exe inspection failed: ", LauncherTransferHelper(controller=ctrl_nc).transfer(ctx).error)

        res_err = LauncherTransferHelper(controller=_DummyController(exe_path=Mock(side_effect=RuntimeError("exe crash")))).transfer(ctx)
        self.assertEqual(res_err.error, "Post-spawn exe inspection failed: RuntimeError")

        for bad_exe in (None, 123, "", "/bin/bash", "/home/wilkin/Android/Sdk/emulator/other"):
            with self.subTest(bad_exe=bad_exe):
                res_bad = LauncherTransferHelper(controller=_DummyController(exe_path=bad_exe)).transfer(ctx)
                self.assertEqual(res_bad.pidfd, 5)
                self.assertIn(f"Post-spawn identity failure: Child actual exe '{bad_exe}' mismatch", res_bad.error)

    def test_argv_verification_table(self) -> None:
        ctx = self._ctx()
        valid = LauncherPreflightHelper().get_frozen_argv()
        for bad_argv in (valid[:-1], [*valid, "-extra"], [valid[0], "-wrong", *valid[2:]]):
            with self.subTest(bad_argv=bad_argv):
                res = LauncherTransferHelper(controller=_DummyController(cmdline=bad_argv)).transfer(ctx)
                self.assertEqual((res.pidfd, res.error), (5, "Post-spawn identity verification failure: Child cmdline has extra, missing, or mismatched arguments"))

    def test_verifier_identity_preservation_and_di(self) -> None:
        ctx = self._ctx()
        # Default real verify_process_identity with controller mismatch
        res_df = LauncherTransferHelper(controller=_DummyController(child_ticks=999)).transfer(ctx)
        self.assertEqual((res_df.success, res_df.pidfd, res_df.pending_cleanup), (False, 5, True))
        self.assertIn("Post-spawn identity verification failed: Process startTicks mismatch", res_df.error)
        self.assertEqual(res_df.identity, ProcessIdentity(12345, 200, TARGET_UID, TARGET_AVD, TARGET_PORT, "emulator", True))

        # Injected verifier rejecting -> preserves candidate identity
        captured: list[ProcessIdentity] = []
        def reject_verifier(ident: ProcessIdentity, ctrl: object) -> tuple[bool, str]:
            captured.append(ident)
            return False, "injected reject reason"

        h_inj = LauncherTransferHelper(controller=_DummyController(), identity_verifier=reject_verifier)
        res_inj = h_inj.transfer(ctx)
        self.assertEqual((res_inj.success, res_inj.pidfd, res_inj.error), (False, 5, "Post-spawn identity verification failed: injected reject reason"))
        self.assertIs(res_inj.identity, captured[0])
        self.assertEqual(captured[0], ProcessIdentity(12345, 200, TARGET_UID, TARGET_AVD, TARGET_PORT, "emulator", True))

        # Injected verifier truthy
        self.assertTrue(LauncherTransferHelper(controller=_DummyController(), identity_verifier=lambda i, c: (True, "ok")).transfer(ctx).success)

        # Injected verifier Exception
        h_inj_err = LauncherTransferHelper(controller=_DummyController(), identity_verifier=Mock(side_effect=RuntimeError("ver crash")))
        res_ierr = h_inj_err.transfer(ctx)
        self.assertEqual((res_ierr.success, res_ierr.pidfd), (False, 5))
        self.assertIn("Post-spawn exception: RuntimeError", res_ierr.error)

    def test_binding_validation_table(self) -> None:
        ctx = self._ctx(pidfd=0)
        for falsy_val in (False, None, 0):
            with self.subTest(falsy_val=falsy_val):
                h = LauncherTransferHelper(controller=_DummyController(bound_ok=falsy_val))
                res = h.transfer(ctx)
                self.assertEqual((res.success, res.pidfd, h.pending_pidfd, res.pending_cleanup), (False, 0, 0, True))
                self.assertEqual(res.error, "Controller bind_anchored_pidfd rejected duplicate/conflict")

        ctrl_nc = _DummyController()
        ctrl_nc.bind_anchored_pidfd = True
        self.assertIn("Post-spawn exception:", LauncherTransferHelper(controller=ctrl_nc).transfer(ctx).error)

        res_err = LauncherTransferHelper(controller=_DummyController(bound_ok=Mock(side_effect=RuntimeError("bind crash")))).transfer(ctx)
        self.assertEqual(res_err.error, "Post-spawn exception: RuntimeError")

    def test_custody_observed_inside_callbacks_and_early_no_call(self) -> None:
        ctx = self._ctx(pidfd=7)
        h = LauncherTransferHelper()

        def _check_custody(val: object) -> object:
            self.assertIs(h.pending_child, ctx.child_handle)
            self.assertEqual(h.pending_pidfd, 7)
            return val

        ctrl = Mock(
            get_cmdline=lambda p: _check_custody(LauncherPreflightHelper().get_frozen_argv()),
            get_exe_path=lambda p: _check_custody(DEFAULT_EMULATOR_PATH),
            bind_anchored_pidfd=lambda p, t, fd: _check_custody(True),
            get_start_ticks=lambda p: 200,
            get_uid=lambda p: TARGET_UID,
        )
        h._controller = ctrl
        self.assertTrue(h.transfer(ctx).success)

        ctrl_early = Mock(get_cmdline=Mock(return_value=[]))
        res_early = LauncherTransferHelper(controller=ctrl_early).transfer(ctx)
        self.assertFalse(res_early.success)
        ctrl_early.get_cmdline.assert_called_once_with(12345)
        ctrl_early.get_exe_path.assert_not_called()
        ctrl_early.bind_anchored_pidfd.assert_not_called()

    def test_ki_se_custody_and_other_base_exception(self) -> None:
        for exc_cls in (KeyboardInterrupt, SystemExit):
            exc = exc_cls()
            stages = (
                ("cmdline", lambda c: setattr(c, "get_cmdline", Mock(side_effect=exc))),
                ("exe", lambda c: setattr(c, "get_exe_path", Mock(side_effect=exc))),
                ("argv_iter", lambda c: setattr(c, "get_cmdline", lambda p: _BadIter(exc))),
                ("verifier", None),
                ("binder", lambda c: setattr(c, "bind_anchored_pidfd", Mock(side_effect=exc))),
            )
            for stage, setup_fn in stages:
                with self.subTest(exc=exc_cls.__name__, stage=stage):
                    ctx = self._ctx(pidfd=5)
                    ctrl = _DummyController()
                    if setup_fn is not None: setup_fn(ctrl)
                    kw = {"identity_verifier": Mock(side_effect=exc)} if stage == "verifier" else {}
                    h = LauncherTransferHelper(controller=ctrl, **kw)
                    with self.assertRaises(exc_cls) as cm:
                        h.transfer(ctx)
                    self.assertIs(cm.exception, exc)
                    self.assertEqual((h.pending_child, h.pending_pidfd), (ctx.child_handle, 5))
                    self.assertEqual((h.last_result.pidfd, h.last_result.child_handle, h.last_result.pending_cleanup), (5, ctx.child_handle, True))

        custom_base = type("_CustomBase", (BaseException,), {})("custom error")
        h_custom = LauncherTransferHelper(controller=_DummyController(cmdline=Mock(side_effect=custom_base)))
        res_custom = h_custom.transfer(self._ctx(pidfd=5))
        self.assertEqual((res_custom.success, res_custom.pidfd, res_custom.pending_cleanup), (False, 5, True))
        self.assertIn("Post-spawn exception: _CustomBase", res_custom.error)

    def test_dependency_callback_fault_matrix(self) -> None:
        ctx = self._ctx(pidfd=5)
        exceptions = [
            RuntimeError("runtime"),
            _StrReprTrapException(),
            _StrReprTrapKI(),
            _StrReprTrapSE(),
            GeneratorExit(),
        ]
        callbacks = ("cmdline", "exe_path", "identity_verifier", "bind_anchored_pidfd")
        for cb_name in callbacks:
            for exc in exceptions:
                with self.subTest(callback=cb_name, exc_type=type(exc).__name__):
                    ctrl = _DummyController()
                    kw: dict[str, object] = {}
                    if cb_name == "cmdline":
                        ctrl.get_cmdline = Mock(side_effect=exc)
                    elif cb_name == "exe_path":
                        ctrl.get_exe_path = Mock(side_effect=exc)
                    elif cb_name == "identity_verifier":
                        kw["identity_verifier"] = Mock(side_effect=exc)
                    elif cb_name == "bind_anchored_pidfd":
                        ctrl.bind_anchored_pidfd = Mock(side_effect=exc)

                    h = LauncherTransferHelper(controller=ctrl, **kw)
                    if isinstance(exc, (KeyboardInterrupt, SystemExit)):
                        with self.assertRaises(type(exc)) as cm:
                            h.transfer(ctx)
                        self.assertIs(cm.exception, exc)
                        res = h.last_result
                    else:
                        res = h.transfer(ctx)
                        self.assertIs(h.last_result, res)
                        self.assertFalse(res.success)

                    self.assertIsNotNone(res)
                    self.assertFalse(res.transferred)
                    self.assertEqual(res.pidfd, 5)
                    self.assertEqual(h.pending_pidfd, 5)
                    self.assertIs(res.child_handle, ctx.child_handle)
                    self.assertTrue(res.pending_cleanup)
                    if cb_name == "exe_path" and isinstance(exc, Exception):
                        self.assertEqual(res.error, f"Post-spawn exe inspection failed: {type(exc).__name__}")
                    else:
                        self.assertEqual(res.error, f"Post-spawn exception: {type(exc).__name__}")
                    self.assertIsNone(res.identity)

    def test_postbind_setattr_fault_injection(self) -> None:
        ctx = self._ctx(pidfd=5)
        exceptions = [
            RuntimeError("attr fault"),
            GeneratorExit(),
            _StrReprTrapKI(),
            _StrReprTrapSE(),
        ]
        for attr_target in ("_pending_pidfd", "_last_result"):
            for exc in exceptions:
                with self.subTest(attr=attr_target, exc_type=type(exc).__name__):
                    captured_id: list[ProcessIdentity] = []
                    def verifier(ident: ProcessIdentity, c: object) -> tuple[bool, str]:
                        captured_id.append(ident)
                        return True, "ok"

                    h = _FaultyTransferHelper(identity_verifier=verifier)
                    h.fault_attr_name = attr_target
                    def _bind_and_arm(p: int, t: int, fd: int) -> bool:
                        h.arm_attr_fault = exc
                        return True

                    ctrl = _DummyController(bound_ok=_bind_and_arm)
                    h._controller = ctrl
                    if isinstance(exc, (KeyboardInterrupt, SystemExit)):
                        with self.assertRaises(type(exc)) as cm:
                            h.transfer(ctx)
                        self.assertIs(cm.exception, exc)
                        res = h.last_result
                    else:
                        res = h.transfer(ctx)
                        self.assertIs(h.last_result, res)

                    self.assertIsNotNone(res)
                    self.assertFalse(res.success)
                    self.assertTrue(res.transferred)
                    self.assertIsNone(res.pidfd)
                    self.assertIsNone(h.pending_pidfd)
                    self.assertTrue(res.pending_cleanup)
                    self.assertIs(res.identity, captured_id[0])
                    self.assertEqual(res.error, f"Post-spawn exception: {type(exc).__name__}")
                    self.assertIs(res.child_handle, ctx.child_handle)

    def test_postbind_constructor_fault_injection(self) -> None:
        ctx = self._ctx(pidfd=5)
        import scripts.f3_guard.launcher_transfer as lt_mod
        orig_lr = lt_mod.LaunchResult

        # Recovery when only first call raises
        for exc in (RuntimeError("ctor err"), GeneratorExit(), _StrReprTrapKI(), _StrReprTrapSE()):
            with self.subTest(sub="recover_first_call", exc_type=type(exc).__name__):
                calls = 0
                captured_id: list[ProcessIdentity] = []
                def verifier(ident: ProcessIdentity, c: object) -> tuple[bool, str]:
                    captured_id.append(ident)
                    return True, "ok"

                def faulty_lr(*a: object, **kw: object) -> LaunchResult:
                    nonlocal calls
                    calls += 1
                    if calls == 1:
                        raise exc
                    return orig_lr(*a, **kw)

                with patch.object(lt_mod, "LaunchResult", side_effect=faulty_lr):
                    h = LauncherTransferHelper(controller=_DummyController(bound_ok=True), identity_verifier=verifier)
                    if isinstance(exc, (KeyboardInterrupt, SystemExit)):
                        with self.assertRaises(type(exc)) as cm:
                            h.transfer(ctx)
                        self.assertIs(cm.exception, exc)
                        res = h.last_result
                    else:
                        res = h.transfer(ctx)
                        self.assertIs(h.last_result, res)

                    self.assertIsNotNone(res)
                    self.assertFalse(res.success)
                    self.assertTrue(res.transferred)
                    self.assertIsNone(res.pidfd)
                    self.assertIsNone(h.pending_pidfd)
                    self.assertTrue(res.pending_cleanup)
                    self.assertIs(res.identity, captured_id[0])
                    self.assertEqual(res.error, f"Post-spawn exception: {type(exc).__name__}")

        # Secondary repeat fault preserves primary KI/SE, and absorbs RuntimeError/GeneratorExit
        for pri_exc in (_StrReprTrapKI("pri ki"), _StrReprTrapSE("pri se"), RuntimeError("pri err"), GeneratorExit()):
            with self.subTest(sub="repeat_fault", exc_type=type(pri_exc).__name__):
                calls2 = 0
                captured_id2: list[ProcessIdentity] = []
                orig_frame = None
                def verifier2(ident: ProcessIdentity, c: object) -> tuple[bool, str]:
                    captured_id2.append(ident)
                    return True, "ok"

                def repeat_fault_lr(*a: object, **kw: object) -> LaunchResult:
                    nonlocal calls2, orig_frame
                    calls2 += 1
                    if calls2 == 1:
                        orig_frame = sys._getframe(0)
                        raise pri_exc
                    raise RuntimeError("secondary constructor error")

                with patch.object(lt_mod, "LaunchResult", side_effect=repeat_fault_lr):
                    h2 = LauncherTransferHelper(controller=_DummyController(bound_ok=True), identity_verifier=verifier2)
                    if isinstance(pri_exc, (KeyboardInterrupt, SystemExit)):
                        caught_exc = None
                        try:
                            h2.transfer(ctx)
                        except type(pri_exc) as err:
                            caught_exc = err

                        self.assertIs(caught_exc, pri_exc)
                        tb = caught_exc.__traceback__
                        self.assertIsNotNone(tb)
                        tb_frames = []
                        curr_tb = tb
                        while curr_tb is not None:
                            tb_frames.append(curr_tb.tb_frame)
                            curr_tb = curr_tb.tb_next
                        self.assertIn(orig_frame, tb_frames)
                        self.assertIs(tb_frames[-1], orig_frame)
                        self.assertIs(orig_frame.f_code, repeat_fault_lr.__code__)

                        res_ki = h2.last_result
                        self.assertIsInstance(res_ki, orig_lr)
                        self.assertIs(type(res_ki), orig_lr)
                        self.assertFalse(res_ki.success)
                        self.assertTrue(res_ki.transferred)
                        self.assertIsNone(res_ki.pidfd)
                        self.assertIsNone(h2.pending_pidfd)
                        self.assertIs(res_ki.identity, captured_id2[0])
                        self.assertIs(res_ki.child_handle, ctx.child_handle)
                        self.assertIs(h2.pending_child, ctx.child_handle)
                        self.assertTrue(res_ki.pending_cleanup)
                        self.assertEqual(res_ki.error, f"Post-spawn exception: {type(pri_exc).__name__}")
                    else:
                        res2 = h2.transfer(ctx)
                        self.assertIs(h2.last_result, res2)
                        self.assertFalse(res2.success)
                        self.assertTrue(res2.transferred)
                        self.assertIsNone(res2.pidfd)
                        self.assertIsNone(h2.pending_pidfd)
                        self.assertTrue(res2.pending_cleanup)
                        self.assertIs(res2.identity, captured_id2[0])
                        self.assertEqual(res2.error, f"Post-spawn exception: {type(pri_exc).__name__}")

    def test_binding_opaque_fd_and_no_close(self) -> None:
        class _OpaqueFd(int): pass
        ctx = self._ctx(pidfd=_OpaqueFd(42))
        with patch("os.close", Mock(side_effect=AssertionError("os.close called"))) as mock_close:
            h = LauncherTransferHelper(controller=_DummyController(bound_ok=True))
            res = h.transfer(ctx)
            self.assertTrue(res.success and res.transferred)
            self.assertIsNone(res.pidfd)
            self.assertIsNone(h.pending_pidfd)
            self.assertFalse(res.pending_cleanup)

            h_rej = LauncherTransferHelper(controller=_DummyController(bound_ok=False))
            res_rej = h_rej.transfer(ctx)
            self.assertFalse(res_rej.success)
            self.assertEqual(res_rej.pidfd, 42)
            self.assertEqual(h_rej.pending_pidfd, 42)
            mock_close.assert_not_called()

        # Binder returning object whose __bool__ raises
        class _ThrowBool:
            def __bool__(self) -> bool: raise RuntimeError("bool evaluation failed")

        h_bool_throw = LauncherTransferHelper(controller=_DummyController(bound_ok=lambda p, t, fd: _ThrowBool()))
        res_bt = h_bool_throw.transfer(self._ctx(pidfd=5))
        self.assertFalse(res_bt.success)
        self.assertFalse(res_bt.transferred)
        self.assertEqual(res_bt.pidfd, 5)
        self.assertEqual(h_bool_throw.pending_pidfd, 5)
        self.assertTrue(res_bt.pending_cleanup)
        self.assertIsNone(res_bt.identity)
        self.assertEqual(res_bt.error, "Post-spawn exception: RuntimeError")

    def test_postbind_both_factories_failing_reraises_primary(self) -> None:
        ctx = self._ctx(pidfd=5)
        import scripts.f3_guard.launcher_transfer as lt_mod
        sec = RuntimeError("secondary constructor error")
        cases = (
            RuntimeError("pri err"),
            GeneratorExit(),
            _StrReprTrapKI("pri ki"),
            _StrReprTrapSE("pri se"),
        )
        for pri_exc in cases:
            with self.subTest(exc_type=type(pri_exc).__name__):
                with patch.object(lt_mod, "LaunchResult", side_effect=[pri_exc, sec]), \
                     patch.object(lt_mod, "_CANONICAL_LAUNCH_RESULT", side_effect=sec):
                    h = LauncherTransferHelper(controller=_DummyController(bound_ok=True))
                    caught = None
                    try:
                        h.transfer(ctx)
                    except BaseException as err:
                        caught = err
                    self.assertIs(caught, pri_exc)
                    self.assertIsNotNone(caught.__traceback__)
                    self.assertFalse(isinstance(caught, UnboundLocalError))
                    self.assertIsNone(h.pending_pidfd)
                    self.assertIs(h.pending_child, ctx.child_handle)

    def test_get_frozen_argv_and_defaults(self) -> None:
        h = LauncherTransferHelper()
        self.assertEqual(h.get_frozen_argv(), LauncherPreflightHelper().get_frozen_argv())
        self.assertEqual((h.pending_child, h.pending_pidfd, h.last_result), (None, None, None))


if __name__ == "__main__":
    unittest.main()
