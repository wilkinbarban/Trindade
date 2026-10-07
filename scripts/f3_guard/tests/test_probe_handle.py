"""Hermetic unit tests for scripts.f3_guard.probe_handle module."""

from __future__ import annotations

import os
import signal
import sys
import unittest
from unittest.mock import patch

_REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

from scripts.f3_guard.probe_handle import (
    MAX_PROBE_OUTPUT_BYTES,
    ProbeCommandHandle,
    SubprocessProbeHandle,
)


class FakeProc:
    def __init__(self, pid: int = 1234, returncode: int | None = None) -> None:
        self.pid = pid
        self.returncode = returncode
        self.signals: list[int] = []

    def poll(self) -> int | None:
        return self.returncode

    def send_signal(self, sig: int) -> None:
        self.signals.append(sig)


class FakeEnvironment:
    def __init__(
        self,
        proc: FakeProc | None = None,
        pipe_pairs: list[tuple[int, int]] | None = None,
        reads: dict[int, list[bytes | Exception]] | None = None,
        pidfd: int = 99,
    ) -> None:
        self.proc = proc or FakeProc()
        self.closed: list[int] = []
        self.blocking: list[tuple[int, bool]] = []
        self.signals: list[tuple[int, int]] = []
        self.popen_args: tuple | None = None
        self._pairs = list(pipe_pairs or [(10, 11), (20, 21)])
        self._reads = {k: list(v) for k, v in (reads or {}).items()}
        self._pidfd = pidfd

    def pipe(self) -> tuple[int, int]:
        return self._pairs.pop(0)

    def set_blocking(self, fd: int, blocking: bool) -> None:
        self.blocking.append((fd, blocking))

    def popen(self, cmd: list[str], **kw) -> FakeProc:
        self.popen_args = (cmd, kw)
        return self.proc

    def pidfd_open(self, pid: int, flags: int) -> int:
        return self._pidfd

    def pidfd_send_signal(self, fd: int, sig: int) -> None:
        self.signals.append((fd, sig))

    def read(self, fd: int, sz: int) -> bytes:
        q = self._reads.get(fd)
        if q:
            val = q.pop(0)
            if isinstance(val, Exception):
                raise val
            return val
        return b""

    def close(self, fd: int) -> None:
        self.closed.append(fd)


def _make_handle(
    cmd: list[str] | None = None,
    max_bytes: int = MAX_PROBE_OUTPUT_BYTES,
    env: FakeEnvironment | None = None,
    **overrides,
) -> tuple[SubprocessProbeHandle, FakeEnvironment]:
    env = env or FakeEnvironment()
    kwargs = {
        "pipe": env.pipe,
        "set_blocking": env.set_blocking,
        "popen": env.popen,
        "pidfd_open": env.pidfd_open,
        "pidfd_send_signal": env.pidfd_send_signal,
        "read": env.read,
        "close": env.close,
    }
    kwargs.update(overrides)
    handle = SubprocessProbeHandle(cmd or ["test"], max_bytes=max_bytes, **kwargs)
    return handle, env


class TestProbeHandle(unittest.TestCase):
    """Hermetic unit tests for ProbeCommandHandle and SubprocessProbeHandle."""

    def test_base_interface_raises_not_implemented(self) -> None:
        handle = ProbeCommandHandle()
        with self.assertRaises(NotImplementedError):
            handle.poll()
        with self.assertRaises(NotImplementedError):
            handle.read_available(10)
        with self.assertRaises(NotImplementedError):
            handle.terminate()
        with self.assertRaises(NotImplementedError):
            handle.cleanup()

    def test_platform_missing_pidfd_failclosed(self) -> None:
        with patch.object(os, "pidfd_open", None, create=True), \
             patch.object(signal, "pidfd_send_signal", None, create=True):
            with self.assertRaises(RuntimeError) as ctx:
                SubprocessProbeHandle(["test"])
            self.assertIn("pidfd signaling required", str(ctx.exception))

    def test_successful_spawn_and_anchoring(self) -> None:
        handle, env = _make_handle(["adb", "shell"])
        self.assertEqual(env.closed, [11, 21])
        self.assertEqual(env.blocking, [(10, False), (20, False)])
        self.assertEqual(handle._pidfd, 99)
        self.assertEqual(handle._r_out, 10)
        self.assertEqual(handle._r_err, 20)
        self.assertIsNone(handle._construction_error)

    def test_pidfd_open_failure_retains_child_and_cleans_read_fds(self) -> None:
        def bad_pidfd(pid: int, flags: int) -> int:
            raise OSError("pidfd denied")

        handle, env = _make_handle(pidfd_open=bad_pidfd)
        self.assertIsNotNone(handle._proc)
        self.assertIn("pidfd denied", str(handle._construction_error))
        self.assertIn(10, env.closed)
        self.assertIn(20, env.closed)
        self.assertIsNone(handle._pidfd)

    def test_popen_failure_cleans_pipe_fds_and_raises(self) -> None:
        def bad_popen(*a, **kw):
            raise OSError("exec failed")

        with self.assertRaises(OSError):
            _make_handle(popen=bad_popen)

    def test_drain_pipes_bounded_calls(self) -> None:
        calls = 0

        def infinite_read(fd: int, sz: int) -> bytes:
            nonlocal calls
            calls += 1
            return b"x" * 10

        handle, _ = _make_handle(read=infinite_read)
        self.assertIsNone(handle.poll())
        self.assertEqual(calls, 32)

    def test_combined_quota_overflow_sigterm(self) -> None:
        env = FakeEnvironment(reads={10: [b"A" * 600], 20: [b"B" * 600]})
        handle, _ = _make_handle(max_bytes=1000, env=env)
        handle.poll()
        self.assertTrue(handle._overflow)
        out, err = handle.read_available(2000)
        self.assertEqual(len(out.encode("utf-8")) + len(err.encode("utf-8")), 1000)
        self.assertEqual(env.signals, [(99, signal.SIGTERM)])

    def test_blocking_io_error_vs_unexpected_oserror(self) -> None:
        env_blocking = FakeEnvironment(reads={10: [BlockingIOError()]})
        handle, _ = _make_handle(env=env_blocking)
        self.assertIsNone(handle.poll())
        self.assertIsNone(handle._io_error)
        self.assertEqual(env_blocking.signals, [])

        env_err = FakeEnvironment(reads={10: [OSError("bad descriptor")]})
        handle_err, _ = _make_handle(env=env_err)
        handle_err.poll()
        self.assertIn("bad descriptor", str(handle_err._io_error))
        self.assertEqual(env_err.signals, [(99, signal.SIGTERM)])

    def test_descendant_pipe_open_after_child_exit(self) -> None:
        handle, _ = _make_handle(
            env=FakeEnvironment(proc=FakeProc(returncode=0)),
            read=lambda fd, sz: (_ for _ in ()).throw(BlockingIOError()),
        )
        self.assertEqual(handle.poll(), 0)
        self.assertTrue(handle.descendant_pipe_open)

    def test_incremental_utf8_replacement(self) -> None:
        env = FakeEnvironment(reads={10: [b"valid ", b"\xff\xfe", b" more"], 20: [b"\x80error"]})
        handle, _ = _make_handle(env=env)
        handle.poll()
        out, err = handle.read_available(1024)
        self.assertIn("\ufffd", out)
        self.assertIn("valid ", out)
        self.assertIn(" more", out)
        self.assertIn("\ufffderror", err)

    def test_only_pidfd_sigterm_no_sigkill_or_proc_send_signal(self) -> None:
        proc = FakeProc(returncode=None)
        env = FakeEnvironment(proc=proc)
        handle, _ = _make_handle(env=env)
        handle.terminate()
        self.assertEqual(env.signals, [(99, signal.SIGTERM)])
        self.assertEqual(proc.signals, [])

        proc.returncode = 0
        env.signals.clear()
        handle.terminate()
        self.assertEqual(env.signals, [])

    def test_cleanup_close_failures_and_repeat_cleanup(self) -> None:
        proc = FakeProc(returncode=None)
        env = FakeEnvironment(proc=proc)
        handle, _ = _make_handle(env=env)
        self.assertFalse(handle.cleanup())
        self.assertFalse(handle._close_failed)
        self.assertEqual(env.closed, [11, 21, 10, 20, 99])

        proc.returncode = 0
        self.assertTrue(handle.cleanup())
        self.assertEqual(len(env.closed), 5)

        def bad_close(fd: int) -> None:
            if fd == 10:
                raise OSError("close err")

        handle_fail, _ = _make_handle(close=bad_close)
        self.assertFalse(handle_fail.cleanup())
        self.assertTrue(handle_fail._close_failed)
        self.assertIn("close err", str(handle_fail._close_error))
