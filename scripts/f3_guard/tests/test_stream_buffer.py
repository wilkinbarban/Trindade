"""Hermetic unit tests for scripts.f3_guard.stream_buffer module."""

from __future__ import annotations

import os
import sys
import unittest

_REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

from scripts.f3_guard.stream_buffer import (
    DEFAULT_CHUNK_SIZE,
    DEFAULT_MAX_CHUNKS_PER_POLL,
    DEFAULT_MAX_STREAM_BYTES,
    StreamDrainBuffer,
)


class TestStreamDrainBuffer(unittest.TestCase):
    """Hermetic unit tests for StreamDrainBuffer draining, quota, and decoding."""

    def test_default_initialization_and_empty_drain(self) -> None:
        buf = StreamDrainBuffer()
        self.assertEqual(buf.max_bytes, DEFAULT_MAX_STREAM_BYTES)
        self.assertEqual(buf.chunk_size, DEFAULT_CHUNK_SIZE)
        self.assertEqual(buf.max_chunks_per_poll, DEFAULT_MAX_CHUNKS_PER_POLL)
        self.assertFalse(buf.all_eof)
        self.assertFalse(buf.overflow)
        self.assertIsNone(buf.io_error)

        out, err = buf.read_available(100)
        self.assertEqual(out, "")
        self.assertEqual(err, "")

        # Drain with None fds does nothing and does not mark EOF
        buf.drain(None, None)
        self.assertFalse(buf.all_eof)

    def test_bounded_chunks_per_poll_and_eof(self) -> None:
        calls = {10: 0, 20: 0}

        def fake_read(fd: int, sz: int) -> bytes:
            calls[fd] += 1
            if calls[fd] >= 20:
                return b""
            return b"x" * 10

        buf = StreamDrainBuffer(read_fn=fake_read)
        buf.drain(10, 20)
        # Bounded by max_chunks_per_poll (16) per pipe
        self.assertEqual(calls[10], 16)
        self.assertEqual(calls[20], 16)
        self.assertFalse(buf.all_eof)

        # Second drain exhausts remaining data and detects EOF on empty read
        buf.drain(10, 20)
        self.assertEqual(calls[10], 20)
        self.assertEqual(calls[20], 20)
        self.assertTrue(buf.eof_out)
        self.assertTrue(buf.eof_err)
        self.assertTrue(buf.all_eof)

        # Subsequent drain on EOF pipes does not issue reads
        buf.drain(10, 20)
        self.assertEqual(calls[10], 20)
        self.assertEqual(calls[20], 20)

    def test_combined_quota_overflow_and_callback(self) -> None:
        terminated = []
        data = {
            10: [b"A" * 600],
            20: [b"B" * 600],
        }

        def fake_read(fd: int, sz: int) -> bytes:
            q = data.get(fd, [])
            return q.pop(0) if q else b""

        buf = StreamDrainBuffer(
            max_bytes=1000,
            read_fn=fake_read,
            on_terminate=lambda: terminated.append(True),
        )
        buf.drain(10, 20)
        self.assertTrue(buf.overflow)
        self.assertEqual(len(terminated), 1)

        out, err = buf.read_available(2000)
        self.assertEqual(len(out.encode("utf-8")) + len(err.encode("utf-8")), 1000)
        self.assertEqual(len(buf.out_buf) + len(buf.err_buf), 1000)

    def test_isolated_transient_io_errors_ignored(self) -> None:
        for exc_cls in (BlockingIOError, InterruptedError):
            with self.subTest(exc=exc_cls.__name__):
                terminated: list[bool] = []

                def fake_read(fd: int, sz: int) -> bytes:
                    raise exc_cls()

                buf = StreamDrainBuffer(
                    read_fn=fake_read,
                    on_terminate=lambda: terminated.append(True),
                )
                buf.drain(10, 20)
                self.assertIsNone(buf.io_error)
                self.assertFalse(buf.overflow)
                self.assertEqual(terminated, [])
                self.assertEqual(len(buf.out_buf), 0)
                self.assertEqual(len(buf.err_buf), 0)
                self.assertFalse(buf.eof_out)
                self.assertFalse(buf.eof_err)

    def test_blocking_io_vs_unexpected_oserror(self) -> None:
        terminated = []
        data = {
            10: [BlockingIOError()],
            20: [OSError("bad file descriptor")],
        }

        def fake_read(fd: int, sz: int) -> bytes:
            q = data.get(fd, [])
            val = q.pop(0) if q else b""
            if isinstance(val, Exception):
                raise val
            return val

        buf = StreamDrainBuffer(
            read_fn=fake_read,
            on_terminate=lambda: terminated.append(True),
        )
        buf.drain(10, 20)
        self.assertIsNone(buf.eof_out or None)
        self.assertFalse(buf.eof_out)
        self.assertIn("read error on fd 20", str(buf.io_error))
        self.assertIn("bad file descriptor", str(buf.io_error))
        self.assertEqual(len(terminated), 1)

    def test_accumulated_utf8_errors_replace_decoding(self) -> None:
        data = {
            10: [b"valid ", b"\xff\xfe", b" more"],
            20: [b"\x80error"],
        }

        def fake_read(fd: int, sz: int) -> bytes:
            q = data.get(fd, [])
            return q.pop(0) if q else b""

        buf = StreamDrainBuffer(read_fn=fake_read)
        buf.drain(10, 20)
        out, err = buf.read_available(1024)
        self.assertIn("\ufffd", out)
        self.assertIn("valid ", out)
        self.assertIn(" more", out)
        self.assertIn("\ufffderror", err)
