"""Hermetic tests for launcher process spawning and pre-pidfd verification."""

from __future__ import annotations

from contextlib import ExitStack
from dataclasses import FrozenInstanceError
import os
import signal
import unittest
from unittest.mock import Mock, patch

from scripts.f3_guard.launcher_preflight import LaunchResult, TARGET_UID
from scripts.f3_guard.launcher_spawn import LauncherSpawnHelper, SpawnedChildContext


class _SubInt(int):
    pass


class _DummyChild:
    def __init__(self, pid: object = 12345, has_poll: bool = True, poll_val: object = None) -> None:
        self.pid = pid
        if has_poll:
            self.poll = lambda: poll_val


class _DummyController:
    def __init__(
        self, parent_ticks: object = 100, child_ticks: object = 200,
        ppid: object = 10000, uid: object = TARGET_UID,
        has_ticks: bool = True, has_ppid: bool = True, has_uid: bool = True,
    ) -> None:
        if has_ticks:
            self.get_start_ticks = lambda pid: parent_ticks if pid == 10000 else child_ticks
        if has_ppid:
            self.get_ppid = lambda pid: ppid
        if has_uid:
            self.get_uid = lambda pid: uid


class TestLauncherSpawn(unittest.TestCase):
    """Hermetic unit tests for LauncherSpawnHelper and SpawnedChildContext."""

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
        self.stack.enter_context(patch("os.getpid", return_value=10000))
        for fn in ("time.monotonic", "time.time", "time.sleep", "os.kill", "os.waitpid", "os.system",
                   *(f"subprocess.{s}" for s in ("Popen", "run", "call", "check_call", "check_output")),
                   *(["os.pidfd_open"] if hasattr(os, "pidfd_open") else []),
                   *(["signal.pidfd_send_signal"] if hasattr(signal, "pidfd_send_signal") else [])):
            self.stack.enter_context(patch(fn, side_effect=AssertionError(fn)))

    def tearDown(self) -> None:
        self.assertEqual(self.proc_count, 0)

    def test_spawned_child_context_contract(self) -> None:
        child = _DummyChild()
        ctx = SpawnedChildContext(child_handle=child, child_pid=12345, start_ticks=200, parent_ticks=100, owner_pid=10000)
        self.assertIs(ctx.child_handle, child)
        self.assertEqual((ctx.child_pid, ctx.start_ticks, ctx.parent_ticks, ctx.owner_pid), (12345, 200, 100, 10000))
        with self.assertRaises(FrozenInstanceError):
            ctx.child_pid = 999  # type: ignore[misc]

    def test_spawner_none_and_exceptions(self) -> None:
        h_none = LauncherSpawnHelper(spawner=None, controller=_DummyController())
        ctx, res = h_none.spawn_and_verify(["test"])
        self.assertEqual((ctx, h_none.pending_child, h_none.last_result), (None, None, res))
        self.assertIn("not configured", res.error)

        h_err = LauncherSpawnHelper(spawner=Mock(side_effect=RuntimeError("spawner crash")), controller=_DummyController())
        ctx, res = h_err.spawn_and_verify(["test"])
        self.assertEqual((ctx, h_err.pending_child), (None, None))
        self.assertIn("Spawner execution failed: spawner crash", res.error)

        for exc in (KeyboardInterrupt, SystemExit):
            h_ki = LauncherSpawnHelper(spawner=Mock(side_effect=exc), controller=_DummyController())
            with self.assertRaises(exc):
                h_ki.spawn_and_verify(["test"])
            self.assertIsNone(h_ki.pending_child)

    def test_child_pid_validation_table(self) -> None:
        for bad_pid in (None, -1, 0, "123", True, False, 1.5):
            with self.subTest(bad_pid=bad_pid):
                child = _DummyChild(pid=bad_pid)
                h = LauncherSpawnHelper(spawner=lambda a: child, controller=_DummyController())
                ctx, res = h.spawn_and_verify(["test"])
                self.assertIsNone(ctx)
                self.assertIn("valid positive child PID", res.error)
                self.assertEqual((res.child_handle, res.pending_cleanup, res.pidfd), (child, True, None))
                self.assertEqual((h.pending_child, h.last_result), (child, res))

        child_sub = _DummyChild(pid=_SubInt(12345))
        ctx, res = LauncherSpawnHelper(spawner=lambda argv: child_sub, controller=_DummyController()).spawn_and_verify(["test"])
        self.assertIsNone(res)
        self.assertIsNotNone(ctx)

    def test_child_poll_matrix(self) -> None:
        child_no_poll = _DummyChild(has_poll=False)
        ctx, res = LauncherSpawnHelper(spawner=lambda a: child_no_poll, controller=_DummyController()).spawn_and_verify(["t"])
        self.assertIsNone(res)
        self.assertIsNotNone(ctx)

        for exit_val in (0, 1, -9):
            with self.subTest(exit_val=exit_val):
                child = _DummyChild(poll_val=exit_val)
                h = LauncherSpawnHelper(spawner=lambda a: child, controller=_DummyController())
                ctx, res = h.spawn_and_verify(["t"])
                self.assertIsNone(ctx)
                self.assertIn(f"Child already exited: {exit_val}", res.error)
                self.assertIs(h.pending_child, child)

        child_err = _DummyChild()
        child_err.poll = Mock(side_effect=RuntimeError("poll failed"))
        self.assertIn("Child poll error: poll failed", LauncherSpawnHelper(spawner=lambda a: child_err, controller=_DummyController()).spawn_and_verify(["t"])[1].error)

    def test_owner_pid_validation(self) -> None:
        child = _DummyChild()
        for bad_owner in (0, -1, True, False, "10000"):
            with self.subTest(bad_owner=bad_owner), patch("os.getpid", return_value=bad_owner):
                h = LauncherSpawnHelper(spawner=lambda a: child, controller=_DummyController())
                ctx, res = h.spawn_and_verify(["t"])
                self.assertIsNone(ctx)
                self.assertIn("Owner PID invalid", res.error)
                self.assertEqual((res.child_handle, res.pending_cleanup), (child, True))

        with patch("os.getpid", return_value=_SubInt(10000)):
            ctx, res = LauncherSpawnHelper(spawner=lambda a: child, controller=_DummyController()).spawn_and_verify(["t"])
            self.assertIsNone(res)
            self.assertIsNotNone(ctx)

    def test_ticks_validation(self) -> None:
        child = _DummyChild()
        h_no_ticks = LauncherSpawnHelper(spawner=lambda a: child, controller=_DummyController(has_ticks=False))
        self.assertIn("start_ticks for parent", h_no_ticks.spawn_and_verify(["t"])[1].error)

        for bad_ticks in (None, 0, -1, True, False, 1.5):
            with self.subTest(bad_ticks=bad_ticks):
                h = LauncherSpawnHelper(spawner=lambda a: child, controller=_DummyController(parent_ticks=bad_ticks))
                self.assertIn("start_ticks for parent", h.spawn_and_verify(["t"])[1].error)

        ctrl_exc = _DummyController()
        ctrl_exc.get_start_ticks = Mock(side_effect=RuntimeError("ticks crash"))
        self.assertIn("start_ticks for parent", LauncherSpawnHelper(spawner=lambda a: child, controller=ctrl_exc).spawn_and_verify(["t"])[1].error)

        h_child_ticks = LauncherSpawnHelper(spawner=lambda a: child, controller=_DummyController(child_ticks=None))
        self.assertIn("start_ticks for child before open", h_child_ticks.spawn_and_verify(["t"])[1].error)

        h_sub_ticks = LauncherSpawnHelper(spawner=lambda a: child, controller=_DummyController(parent_ticks=_SubInt(100), child_ticks=_SubInt(200)))
        ctx, res = h_sub_ticks.spawn_and_verify(["t"])
        self.assertIsNone(res)
        self.assertIsNotNone(ctx)

    def test_ppid_validation(self) -> None:
        child = _DummyChild()
        h_no_ppid = LauncherSpawnHelper(spawner=lambda a: child, controller=_DummyController(has_ppid=False))
        self.assertIn("missing get_ppid reader", h_no_ppid.spawn_and_verify(["t"])[1].error)

        ctrl_err = _DummyController()
        ctrl_err.get_ppid = Mock(side_effect=RuntimeError("ppid error"))
        self.assertIn("PPID check error: ppid error", LauncherSpawnHelper(spawner=lambda a: child, controller=ctrl_err).spawn_and_verify(["t"])[1].error)

        for bad_ppid in (True, False, 9999):
            with self.subTest(bad_ppid=bad_ppid):
                h = LauncherSpawnHelper(spawner=lambda a: child, controller=_DummyController(ppid=bad_ppid))
                self.assertIn("Child parent PID mismatch or unknown", h.spawn_and_verify(["t"])[1].error)

    def test_uid_validation(self) -> None:
        child = _DummyChild()
        h_no_uid = LauncherSpawnHelper(spawner=lambda a: child, controller=_DummyController(has_uid=False))
        self.assertIn("missing get_uid reader", h_no_uid.spawn_and_verify(["t"])[1].error)

        ctrl_err = _DummyController()
        ctrl_err.get_uid = Mock(side_effect=RuntimeError("uid error"))
        self.assertIn("Child UID read error: uid error", LauncherSpawnHelper(spawner=lambda a: child, controller=ctrl_err).spawn_and_verify(["t"])[1].error)

        for bad_uid in (_SubInt(TARGET_UID), True, False, 1001, "1000"):
            with self.subTest(bad_uid=bad_uid):
                h = LauncherSpawnHelper(spawner=lambda a: child, controller=_DummyController(uid=bad_uid))
                self.assertIn(f"Child UID {bad_uid!r} != required {TARGET_UID}", h.spawn_and_verify(["t"])[1].error)

    def test_successful_spawn_and_verify_context(self) -> None:
        child = _DummyChild(pid=12345)
        ctrl = _DummyController(parent_ticks=100, child_ticks=200, ppid=10000, uid=TARGET_UID)
        h = LauncherSpawnHelper(spawner=lambda argv: child, controller=ctrl)
        ctx, res = h.spawn_and_verify(["/test/bin"])
        self.assertIsNone(res)
        self.assertIsNotNone(ctx)
        self.assertIs(ctx.child_handle, child)
        self.assertEqual((ctx.child_pid, ctx.start_ticks, ctx.parent_ticks, ctx.owner_pid), (12345, 200, 100, 10000))
        self.assertIs(h.pending_child, child)
        self.assertIsNone(h.last_result)

    def test_keyboard_interrupt_system_exit_and_custody_boundaries(self) -> None:
        for exc in (KeyboardInterrupt, SystemExit):
            child_poll_ki = _DummyChild()
            child_poll_ki.poll = Mock(side_effect=exc)
            h_poll = LauncherSpawnHelper(spawner=lambda a: child_poll_ki, controller=_DummyController())
            with self.assertRaises(exc):
                h_poll.spawn_and_verify(["t"])
            self.assertIs(h_poll.pending_child, child_poll_ki)

            child = _DummyChild()
            for method, kw in (("get_start_ticks", {"parent_ticks": 100}), ("get_ppid", {"ppid": 10000}), ("get_uid", {"uid": TARGET_UID})):
                ctrl_ki = _DummyController(**kw)
                setattr(ctrl_ki, method, Mock(side_effect=exc))
                h = LauncherSpawnHelper(spawner=lambda a: child, controller=ctrl_ki)
                with self.assertRaises(exc):
                    h.spawn_and_verify(["t"])
                self.assertIs(h.pending_child, child)

    def test_downstream_isolation_assertions_outside(self) -> None:
        child_bad_pid = _DummyChild(pid=-1)
        spy_ticks, spy_ppid, spy_uid = Mock(), Mock(), Mock()
        ctrl = Mock(get_start_ticks=spy_ticks, get_ppid=spy_ppid, get_uid=spy_uid)
        h = LauncherSpawnHelper(spawner=lambda a: child_bad_pid, controller=ctrl)
        ctx, res = h.spawn_and_verify(["t"])
        self.assertIsNotNone(res)
        spy_ticks.assert_not_called()
        spy_ppid.assert_not_called()
        spy_uid.assert_not_called()


if __name__ == "__main__":
    unittest.main()
