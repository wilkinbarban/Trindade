"""Hermetic tests for launcher pidfd acquisition and post-open verification."""

from __future__ import annotations

from contextlib import ExitStack
from dataclasses import FrozenInstanceError
import os
import signal
import unittest
from unittest.mock import Mock, patch

from scripts.f3_guard.launcher_pidfd import LauncherPidfdHelper, VerifiedPidfdContext
from scripts.f3_guard.launcher_preflight import TARGET_UID
from scripts.f3_guard.launcher_spawn import SpawnedChildContext


class _SubInt(int):
    pass


class _StrTrapException(Exception):
    def __str__(self) -> str:
        raise AssertionError("str called on _StrTrapException")

    def __repr__(self) -> str:
        raise AssertionError("repr called on _StrTrapException")


class _StrTrapBaseException(BaseException):
    def __str__(self) -> str:
        raise AssertionError("str called on _StrTrapBaseException")

    def __repr__(self) -> str:
        raise AssertionError("repr called on _StrTrapBaseException")


class _DummyChild:
    def __init__(self, pid: object = 12345, has_poll: bool = True, poll_val: object = None) -> None:
        self.pid = pid
        if has_poll: self.poll = lambda: poll_val


class _DummyController:
    def __init__(
        self, pidfd: object = 5, fdinfo_pid: object = 12345, alive: object = True,
        child_ticks: object = 200, uid: object = TARGET_UID, ppid: object = 10000,
        parent_ticks: object = 100, has_fdinfo: bool = True, has_alive: bool = True,
        has_uid: bool = True, has_ppid: bool = True,
    ) -> None:
        self.pidfd_open = lambda p: pidfd
        if has_fdinfo: self.get_fdinfo_pid = lambda fd: fdinfo_pid
        if has_alive: self.is_pidfd_alive = lambda fd: alive
        self.get_start_ticks = lambda p: child_ticks if p == 12345 else parent_ticks
        if has_uid: self.get_uid = lambda p: uid
        if has_ppid: self.get_ppid = lambda p: ppid


class TestLauncherPidfd(unittest.TestCase):
    """Hermetic unit tests for LauncherPidfdHelper and VerifiedPidfdContext."""

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
        for fn in ("time.monotonic", "time.time", "time.sleep", "os.kill", "os.waitpid", "os.system",
                   *(f"subprocess.{s}" for s in ("Popen", "run", "call", "check_call", "check_output")),
                   *(["os.pidfd_open"] if hasattr(os, "pidfd_open") else []),
                   *(["signal.pidfd_send_signal"] if hasattr(signal, "pidfd_send_signal") else [])):
            self.stack.enter_context(patch(fn, side_effect=AssertionError(fn)))

    def tearDown(self) -> None:
        self.assertEqual(self.proc_count, 0)

    def _ctx(self, child: object = None) -> SpawnedChildContext:
        return SpawnedChildContext(
            child_handle=child or _DummyChild(), child_pid=12345,
            start_ticks=200, parent_ticks=100, owner_pid=10000,
        )

    def test_verified_pidfd_context_contract(self) -> None:
        spawn_ctx = self._ctx()
        vctx = VerifiedPidfdContext(spawn_context=spawn_ctx, pidfd=5)
        self.assertIs(vctx.spawn_context, spawn_ctx)
        self.assertIs(vctx.context, spawn_ctx)
        self.assertEqual(vctx.pidfd, 5)
        self.assertIs(vctx.child_handle, spawn_ctx.child_handle)
        self.assertEqual((vctx.child_pid, vctx.start_ticks, vctx.parent_ticks, vctx.owner_pid), (12345, 200, 100, 10000))
        with self.assertRaises(FrozenInstanceError): vctx.pidfd = 6  # type: ignore[misc]

    def test_successful_acquire_and_verify(self) -> None:
        spawn_ctx = self._ctx()
        h = LauncherPidfdHelper(controller=_DummyController(pidfd=5))
        vctx, res = h.acquire_and_verify(spawn_ctx)
        self.assertEqual((res, vctx.pidfd, h.pending_pidfd, h.last_result), (None, 5, 5, None))
        self.assertIs(vctx.child_handle, spawn_ctx.child_handle)
        self.assertIs(h.pending_child, spawn_ctx.child_handle)

    def test_pidfd_open_validation_table(self) -> None:
        spawn_ctx = self._ctx()
        for bad_fd in (None, False, True, -1, "5", 5.0):
            with self.subTest(bad_fd=bad_fd):
                h = LauncherPidfdHelper(controller=_DummyController(pidfd=bad_fd))
                vctx, res = h.acquire_and_verify(spawn_ctx)
                self.assertIsNone(vctx)
                self.assertIn("Post-spawn pidfd_open failed or invalid fd", res.error)
                self.assertEqual((res.pidfd, res.child_handle, res.pending_cleanup, h.pending_pidfd, h.last_result), (None, spawn_ctx.child_handle, True, None, res))

        for ok_fd in (0, _SubInt(5)):
            with self.subTest(ok_fd=ok_fd):
                vctx, res = LauncherPidfdHelper(controller=_DummyController(pidfd=ok_fd)).acquire_and_verify(spawn_ctx)
                self.assertIsNone(res)
                self.assertEqual(vctx.pidfd, int(ok_fd))

        ctrl_exc = _DummyController()
        ctrl_exc.pidfd_open = Mock(side_effect=OSError("open failed"))
        vctx, res = LauncherPidfdHelper(controller=ctrl_exc).acquire_and_verify(spawn_ctx)
        self.assertIsNone(vctx)
        self.assertIn("Post-spawn exception: OSError", res.error)
        self.assertEqual((res.pidfd, res.child_handle, res.pending_cleanup), (None, spawn_ctx.child_handle, True))

    def test_fdinfo_validation_table(self) -> None:
        spawn_ctx = self._ctx()
        self.assertIn("missing get_fdinfo_pid reader", LauncherPidfdHelper(controller=_DummyController(has_fdinfo=False)).acquire_and_verify(spawn_ctx)[1].error)

        ctrl_err = _DummyController()
        ctrl_err.get_fdinfo_pid = Mock(side_effect=RuntimeError("fdinfo crash"))
        self.assertIn("fdinfo check error: RuntimeError", LauncherPidfdHelper(controller=ctrl_err).acquire_and_verify(spawn_ctx)[1].error)

        for bad_pid in (None, False, True, 99999, "12345"):
            with self.subTest(bad_pid=bad_pid):
                h = LauncherPidfdHelper(controller=_DummyController(fdinfo_pid=bad_pid))
                vctx, res = h.acquire_and_verify(spawn_ctx)
                self.assertEqual((vctx, res.pidfd, h.pending_pidfd), (None, 5, 5))
                self.assertIn("pidfd fdinfo target PID mismatch or invalid", res.error)

        vctx, res = LauncherPidfdHelper(controller=_DummyController(fdinfo_pid=_SubInt(12345))).acquire_and_verify(spawn_ctx)
        self.assertIsNone(res)
        self.assertIsNotNone(vctx)

    def test_is_pidfd_alive_validation_table(self) -> None:
        spawn_ctx = self._ctx()
        self.assertIn("missing mandatory is_pidfd_alive callable", LauncherPidfdHelper(controller=_DummyController(has_alive=False)).acquire_and_verify(spawn_ctx)[1].error)
        ctrl_noncall = _DummyController()
        ctrl_noncall.is_pidfd_alive = True
        self.assertIn("missing mandatory is_pidfd_alive callable", LauncherPidfdHelper(controller=ctrl_noncall).acquire_and_verify(spawn_ctx)[1].error)

        ctrl_err = _DummyController()
        ctrl_err.is_pidfd_alive = Mock(side_effect=RuntimeError("alive crash"))
        self.assertIn("pidfd liveness check error: RuntimeError", LauncherPidfdHelper(controller=ctrl_err).acquire_and_verify(spawn_ctx)[1].error)

        for bad_alive in (False, None, 1, 0, "True"):
            with self.subTest(bad_alive=bad_alive):
                res = LauncherPidfdHelper(controller=_DummyController(alive=bad_alive)).acquire_and_verify(spawn_ctx)[1]
                self.assertIn("pidfd liveness verification failed", res.error)
                self.assertEqual(res.pidfd, 5)

    def test_recheck_drift_and_subclass_rejection_table(self) -> None:
        spawn_ctx = self._ctx()
        checks = (
            *[({"child_ticks": v}, "Child start_ticks shifted or invalid on recheck") for v in (None, False, True, 0, 201, _SubInt(200))],
            *[({"uid": v}, "Child UID mismatch or invalid on recheck") for v in (None, False, True, 1001, _SubInt(TARGET_UID))],
            ({"has_uid": False}, "Child UID mismatch or invalid on recheck"),
            *[({"ppid": v}, "Child parent PID mismatch or invalid on recheck") for v in (None, False, True, 0, 9999, _SubInt(10000))],
            ({"has_ppid": False}, "Child parent PID mismatch or invalid on recheck"),
            *[({"parent_ticks": v}, "Parent start_ticks shifted or invalid on recheck") for v in (None, False, True, 0, 101, _SubInt(100))],
        )
        for kw, msg in checks:
            with self.subTest(kw=kw, msg=msg):
                res = LauncherPidfdHelper(controller=_DummyController(**kw)).acquire_and_verify(spawn_ctx)[1]
                self.assertIn(msg, res.error)
                self.assertEqual(res.pidfd, 5)

        for fn_name, err_prefix, call_p in (
            ("get_start_ticks", "Child start_ticks recheck error", 12345),
            ("get_uid", "Child UID recheck error", 12345),
            ("get_ppid", "PPID recheck error", 12345),
            ("get_start_ticks", "Parent start_ticks recheck error", 10000),
        ):
            ctrl = _DummyController()
            orig = getattr(ctrl, fn_name)
            setattr(ctrl, fn_name, lambda p, o=orig, cp=call_p: (_ for _ in ()).throw(RuntimeError("recheck fail")) if p == cp else o(p))
            self.assertIn(err_prefix, LauncherPidfdHelper(controller=ctrl).acquire_and_verify(spawn_ctx)[1].error)

    def test_child_poll_matrix(self) -> None:
        for has_poll, val, ok, err_sub in (
            (False, None, True, None), (True, None, True, None),
            (True, 0, False, "Child exited during launch verification: 0"),
            (True, 1, False, "Child exited during launch verification: 1"),
            (True, -9, False, "Child exited during launch verification: -9"),
        ):
            with self.subTest(has_poll=has_poll, val=val):
                vctx, res = LauncherPidfdHelper(controller=_DummyController()).acquire_and_verify(
                    self._ctx(_DummyChild(has_poll=has_poll, poll_val=val))
                )
                if ok:
                    self.assertIsNone(res)
                    self.assertIsNotNone(vctx)
                else:
                    self.assertIn(err_sub, res.error)
                    self.assertEqual(res.pidfd, 5)

        child_err = _DummyChild()
        child_err.poll = Mock(side_effect=RuntimeError("poll fail"))
        self.assertIn("Child poll error on recheck: RuntimeError", LauncherPidfdHelper(controller=_DummyController()).acquire_and_verify(self._ctx(child_err))[1].error)

    def test_callback_order_and_early_no_call_assertions_outside(self) -> None:
        spawn_ctx = self._ctx()
        ctrl = Mock(pidfd_open=Mock(return_value=-1))
        vctx, res = LauncherPidfdHelper(controller=ctrl).acquire_and_verify(spawn_ctx)
        self.assertIsNone(vctx)
        ctrl.pidfd_open.assert_called_once_with(12345)
        ctrl.get_fdinfo_pid.assert_not_called()
        ctrl.is_pidfd_alive.assert_not_called()
        ctrl.get_start_ticks.assert_not_called()

    def test_ki_se_custody_and_other_base_exception(self) -> None:
        for exc in (KeyboardInterrupt, SystemExit):
            stages = (
                ("pidfd_open", lambda c: setattr(c, "pidfd_open", Mock(side_effect=exc))),
                ("get_fdinfo_pid", lambda c: setattr(c, "get_fdinfo_pid", Mock(side_effect=exc))),
                ("is_pidfd_alive", lambda c: setattr(c, "is_pidfd_alive", Mock(side_effect=exc))),
                ("child_ticks", lambda c: setattr(c, "get_start_ticks", lambda p, e=exc: (_ for _ in ()).throw(e) if p == 12345 else 100)),
                ("uid", lambda c: setattr(c, "get_uid", Mock(side_effect=exc))),
                ("ppid", lambda c: setattr(c, "get_ppid", Mock(side_effect=exc))),
                ("parent_ticks", lambda c: setattr(c, "get_start_ticks", lambda p, e=exc: (_ for _ in ()).throw(e) if p == 10000 else 200)),
                ("poll", None),
            )
            for stage, setup_fn in stages:
                with self.subTest(exc=exc.__name__, stage=stage):
                    child = _DummyChild()
                    if stage == "poll": child.poll = Mock(side_effect=exc)
                    ctrl = _DummyController()
                    if setup_fn is not None: setup_fn(ctrl)
                    h = LauncherPidfdHelper(controller=ctrl)
                    with self.assertRaises(exc):
                        h.acquire_and_verify(self._ctx(child))
                    self.assertIs(h.pending_child, child)
                    exp_fd = None if stage == "pidfd_open" else 5
                    self.assertEqual((h.pending_pidfd, h.last_result.pidfd, h.last_result.child_handle), (exp_fd, exp_fd, child))
                    self.assertTrue(h.last_result.pending_cleanup)

        ctrl_custom = _DummyController()
        ctrl_custom.pidfd_open = Mock(side_effect=type("_CustomBase", (BaseException,), {})("custom error"))
        h_custom = LauncherPidfdHelper(controller=ctrl_custom)
        vctx, res = h_custom.acquire_and_verify(self._ctx())
        self.assertEqual((vctx, h_custom.last_result), (None, res))
        self.assertIn("Post-spawn exception: _CustomBase", res.error)

    def test_str_trap_regression_pidfd_open_and_secondary_callbacks(self) -> None:
        spawn_ctx = self._ctx()

        ctrl_exc = _DummyController()
        ctrl_exc.pidfd_open = Mock(side_effect=_StrTrapException())
        h_open = LauncherPidfdHelper(controller=ctrl_exc)
        vctx, res = h_open.acquire_and_verify(spawn_ctx)
        self.assertIsNone(vctx)
        self.assertEqual(res.error, "Post-spawn exception: _StrTrapException")
        self.assertIsNone(h_open.pending_pidfd)
        self.assertIs(h_open.pending_child, spawn_ctx.child_handle)
        self.assertTrue(res.pending_cleanup)

        ctrl_base = _DummyController()
        ctrl_base.pidfd_open = Mock(side_effect=_StrTrapBaseException())
        h_base = LauncherPidfdHelper(controller=ctrl_base)
        vctx, res = h_base.acquire_and_verify(spawn_ctx)
        self.assertIsNone(vctx)
        self.assertEqual(res.error, "Post-spawn exception: _StrTrapBaseException")
        self.assertIsNone(h_base.pending_pidfd)
        self.assertIs(h_base.pending_child, spawn_ctx.child_handle)
        self.assertTrue(res.pending_cleanup)

        stages = (
            ("get_fdinfo_pid", "fdinfo check error: _StrTrapException", lambda c: setattr(c, "get_fdinfo_pid", Mock(side_effect=_StrTrapException()))),
            ("is_pidfd_alive", "pidfd liveness check error: _StrTrapException", lambda c: setattr(c, "is_pidfd_alive", Mock(side_effect=_StrTrapException()))),
            ("child_ticks", "Child start_ticks recheck error: _StrTrapException", lambda c: setattr(c, "get_start_ticks", lambda p: (_ for _ in ()).throw(_StrTrapException()) if p == 12345 else 100)),
            ("uid", "Child UID recheck error: _StrTrapException", lambda c: setattr(c, "get_uid", Mock(side_effect=_StrTrapException()))),
            ("ppid", "PPID recheck error: _StrTrapException", lambda c: setattr(c, "get_ppid", Mock(side_effect=_StrTrapException()))),
            ("parent_ticks", "Parent start_ticks recheck error: _StrTrapException", lambda c: setattr(c, "get_start_ticks", lambda p: (_ for _ in ()).throw(_StrTrapException()) if p == 10000 else 200)),
        )
        for name, expected_err, setup_fn in stages:
            with self.subTest(stage=name):
                ctrl = _DummyController()
                setup_fn(ctrl)
                h = LauncherPidfdHelper(controller=ctrl)
                vctx, res = h.acquire_and_verify(spawn_ctx)
                self.assertIsNone(vctx)
                self.assertEqual(res.error, expected_err)
                self.assertEqual(h.pending_pidfd, 5)
                self.assertIs(h.pending_child, spawn_ctx.child_handle)
                self.assertTrue(res.pending_cleanup)

        child_poll = _DummyChild()
        child_poll.poll = Mock(side_effect=_StrTrapException())
        h_poll = LauncherPidfdHelper(controller=_DummyController())
        vctx, res = h_poll.acquire_and_verify(self._ctx(child_poll))
        self.assertIsNone(vctx)
        self.assertEqual(res.error, "Child poll error on recheck: _StrTrapException")
        self.assertEqual(h_poll.pending_pidfd, 5)
        self.assertIs(h_poll.pending_child, child_poll)
        self.assertTrue(res.pending_cleanup)


if __name__ == "__main__":
    unittest.main()
