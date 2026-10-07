"""Non-blocking process handle transport contract for F3 guard."""

from __future__ import annotations

import os
import signal
import subprocess
from typing import Any, Callable, List, Optional, Tuple

from scripts.f3_guard.stream_buffer import StreamDrainBuffer

MAX_PROBE_OUTPUT_BYTES: int = 1048576  # 1 MiB bounded probe output


class ProbeCommandHandle:
    """Documented transport contract for non-blocking probe execution.

    Invariants:
    1. poll() must be strictly non-blocking: returns exit code (int) if finished,
       or None if running. Never blocks on descendant processes or pipe EOF.
    2. read_available(max_bytes) returns (stdout, stderr) up to max_bytes without blocking.
    3. terminate() sends SIGTERM exclusively to the owned exact handle. Never SIGKILL, no PID fallback.
    4. cleanup() closes owned resources and attempts non-blocking reap. Returns True if reaped,
       False if pending. Never performs busy blocking waits.
    """

    def poll(self) -> Optional[int]:
        raise NotImplementedError

    def read_available(self, max_bytes: int) -> Tuple[str, str]:
        raise NotImplementedError

    def terminate(self) -> None:
        raise NotImplementedError

    def cleanup(self) -> bool:
        raise NotImplementedError


class SubprocessProbeHandle(ProbeCommandHandle):
    """Concrete subprocess probe handle using nonblocking pipes and pidfd signaling."""

    def __init__(
        self,
        cmd: List[str],
        max_bytes: int = MAX_PROBE_OUTPUT_BYTES,
        *,
        pipe: Optional[Callable[[], Tuple[int, int]]] = None,
        set_blocking: Optional[Callable[[int, bool], None]] = None,
        popen: Optional[Callable[..., Any]] = None,
        pidfd_open: Optional[Callable[[int, int], int]] = None,
        pidfd_send_signal: Optional[Callable[[int, int], None]] = None,
        read: Optional[Callable[[int, int], bytes]] = None,
        close: Optional[Callable[[int], None]] = None,
    ):
        self._pipe = pipe if pipe is not None else os.pipe
        self._set_blocking = set_blocking if set_blocking is not None else os.set_blocking
        self._popen = popen if popen is not None else subprocess.Popen
        self._pidfd_open = pidfd_open if pidfd_open is not None else getattr(os, "pidfd_open", None)
        self._pidfd_send_signal = (
            pidfd_send_signal if pidfd_send_signal is not None else getattr(signal, "pidfd_send_signal", None)
        )
        self._close = close if close is not None else os.close

        if self._pidfd_open is None or self._pidfd_send_signal is None:
            raise RuntimeError("pidfd signaling required but not supported on platform")

        self.descendant_pipe_open = False
        self._construction_error: Optional[str] = None
        self._close_failed: bool = False
        self._close_error: Optional[str] = None

        self._proc: Optional[subprocess.Popen] = None
        self._pidfd: Optional[int] = None
        self._r_out: Optional[int] = None
        self._r_err: Optional[int] = None

        self._buffer = StreamDrainBuffer(
            max_bytes=max_bytes,
            read_fn=read,
            on_terminate=self.terminate,
        )

        r_out = w_out = r_err = w_err = pidfd = proc = None
        try:
            r_out, w_out = self._pipe()
            r_err, w_err = self._pipe()
            self._set_blocking(r_out, False)
            self._set_blocking(r_err, False)
            proc = self._popen(
                cmd,
                stdout=w_out,
                stderr=w_err,
                shell=False,
                close_fds=True,
            )
            self._close(w_out)
            w_out = None
            self._close(w_err)
            w_err = None
            self._proc = proc
            try:
                pidfd = self._pidfd_open(proc.pid, 0)
                self._pidfd = pidfd
                self._r_out = r_out
                self._r_err = r_err
            except Exception as e:
                # Close read ends and retain child ownership via construction-error-carrying handle
                for fd in (r_out, r_err):
                    if isinstance(fd, int):
                        try:
                            self._close(fd)
                        except Exception:
                            pass
                self._construction_error = f"pidfd_open failed: {e}"
        except Exception:
            for fd in (w_out, w_err, r_out, r_err, pidfd):
                if isinstance(fd, int):
                    try:
                        self._close(fd)
                    except Exception:
                        pass
            if proc is not None:
                self._proc = proc
                self._construction_error = "subprocess spawned but setup failed"
                return
            raise

    @property
    def _overflow(self) -> bool:
        return self._buffer.overflow

    @property
    def _io_error(self) -> Optional[str]:
        return self._buffer.io_error

    def poll(self) -> Optional[int]:
        if self._proc is None:
            return None
        self._buffer.drain(self._r_out, self._r_err)
        rc = self._proc.poll()
        if rc is not None:
            self._buffer.drain(self._r_out, self._r_err)
            if not self._buffer.all_eof:
                self.descendant_pipe_open = True
        return rc

    def read_available(self, max_bytes: int) -> Tuple[str, str]:
        return self._buffer.read_available(max_bytes)

    def terminate(self) -> None:
        if self._pidfd is not None and self._proc is not None:
            if self._proc.poll() is None:
                try:
                    self._pidfd_send_signal(self._pidfd, signal.SIGTERM)
                except Exception:
                    pass

    def cleanup(self) -> bool:
        for attr in ("_r_out", "_r_err", "_pidfd"):
            fd = getattr(self, attr, None)
            if isinstance(fd, int):
                setattr(self, attr, None)
                try:
                    self._close(fd)
                except Exception as e:
                    self._close_failed = True
                    self._close_error = f"close failed on fd {fd}: {e}"
        if self._close_failed:
            return False
        if self._proc is not None:
            return self._proc.poll() is not None
        return True
