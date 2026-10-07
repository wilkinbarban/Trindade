"""Stream draining, bounded buffers, and output decoding for probe pipes."""

from __future__ import annotations

import os
from typing import Callable, Optional, Tuple

DEFAULT_MAX_STREAM_BYTES: int = 1048576  # 1 MiB bounded probe output
DEFAULT_CHUNK_SIZE: int = 16384
DEFAULT_MAX_CHUNKS_PER_POLL: int = 16


class StreamDrainBuffer:
    """Encapsulates non-blocking draining of stdout/stderr pipes with quota bounds.

    Preserves exact semantics:
    - Independent stdout and stderr byte buffers.
    - Up to max_chunks_per_poll reads per pipe per drain cycle.
    - Respects chunk_size per read call.
    - Terminates / invokes callback on combined output overflow past max_bytes.
    - Ignores BlockingIOError and InterruptedError on non-blocking reads.
    - Records first unexpected OSError / Exception and invokes termination callback.
    - Marks EOF per pipe on zero-byte read.
    - Decodes accumulated byte slices independently with UTF-8 errors='replace' (no incremental decoder or buffer consumption).
    """

    def __init__(
        self,
        max_bytes: int = DEFAULT_MAX_STREAM_BYTES,
        chunk_size: int = DEFAULT_CHUNK_SIZE,
        max_chunks_per_poll: int = DEFAULT_MAX_CHUNKS_PER_POLL,
        *,
        read_fn: Optional[Callable[[int, int], bytes]] = None,
        on_terminate: Optional[Callable[[], None]] = None,
    ) -> None:
        self.max_bytes = max_bytes
        self.chunk_size = chunk_size
        self.max_chunks_per_poll = max_chunks_per_poll
        self._read_fn = read_fn if read_fn is not None else os.read
        self._on_terminate = on_terminate

        self.out_buf = bytearray()
        self.err_buf = bytearray()
        self.eof_out = False
        self.eof_err = False
        self.overflow = False
        self.io_error: Optional[str] = None

    @property
    def all_eof(self) -> bool:
        """True if both pipes have observed EOF."""
        return self.eof_out and self.eof_err

    def drain(
        self,
        r_out: Optional[int],
        r_err: Optional[int],
        max_chunks: Optional[int] = None,
    ) -> None:
        """Drain available bytes from stdout and stderr descriptors up to max_chunks per pipe."""
        limit = max_chunks if max_chunks is not None else self.max_chunks_per_poll
        pipes = (
            (r_out, self.out_buf, "eof_out"),
            (r_err, self.err_buf, "eof_err"),
        )
        for fd, buf, eof_attr in pipes:
            if fd is None or getattr(self, eof_attr):
                continue
            for _ in range(limit):
                try:
                    chunk = self._read_fn(fd, self.chunk_size)
                    if not chunk:
                        setattr(self, eof_attr, True)
                        break
                    total_len = len(self.out_buf) + len(self.err_buf) + len(chunk)
                    if total_len > self.max_bytes:
                        self.overflow = True
                        remain = max(0, self.max_bytes - (len(self.out_buf) + len(self.err_buf)))
                        buf.extend(chunk[:remain])
                        if self._on_terminate is not None:
                            self._on_terminate()
                        break
                    buf.extend(chunk)
                except (BlockingIOError, InterruptedError):
                    break
                except Exception as e:
                    self.io_error = f"read error on fd {fd}: {e}"
                    if self._on_terminate is not None:
                        self._on_terminate()
                    break

    def read_available(self, max_bytes: int) -> Tuple[str, str]:
        """Return independent decoding of accumulated byte slices up to max_bytes with UTF-8 errors='replace'."""
        return (
            self.out_buf[:max_bytes].decode("utf-8", errors="replace"),
            self.err_buf[:max_bytes].decode("utf-8", errors="replace"),
        )
