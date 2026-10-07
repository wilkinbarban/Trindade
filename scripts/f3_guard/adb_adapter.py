"""Non-blocking ADB health probe adapter for Android emulator supervision."""

from __future__ import annotations

from typing import Callable, List, Optional, Sequence, Tuple

from scripts.f3_guard.clock import Clock, SystemClock
from scripts.f3_guard.probe_handle import (
    MAX_PROBE_OUTPUT_BYTES,
    ProbeCommandHandle,
    SubprocessProbeHandle,
)
from scripts.f3_guard.readiness import DEFAULT_MAX_STALE_PROBE_SEC
from scripts.f3_guard.reap_coordinator import ProbeReapCoordinator
from scripts.f3_guard.sequence_processor import ProbeSequenceProcessor

__all__ = ["DEFAULT_ADB_SERIAL", "AdbProbeAdapter"]

DEFAULT_ADB_SERIAL: str = "emulator-5556"


class AdbProbeAdapter:
    """Non-blocking ADB health probe adapter for Android emulator supervision."""

    def __init__(
        self,
        serial: str = DEFAULT_ADB_SERIAL,
        command: Optional[Sequence[str]] = None,
        commands: Optional[Sequence[Sequence[str]]] = None,
        transport: Optional[Callable[[List[str]], ProbeCommandHandle]] = None,
        clock: Optional[Clock] = None,
        timeout_sec: float = DEFAULT_MAX_STALE_PROBE_SEC,
        max_output_bytes: int = MAX_PROBE_OUTPUT_BYTES,
    ) -> None:
        self._serial = serial
        if command is not None:
            self._commands = [list(command)]
        elif commands is not None:
            self._commands = [list(c) for c in commands]
        else:
            self._commands = [
                ["adb", "-s", self._serial, "shell", "dumpsys", "activity", "processes"],
                ["adb", "-s", self._serial, "shell", "dumpsys", "activity", "last-anr"],
            ]
        self._transport = transport or SubprocessProbeHandle
        self._clock = clock or SystemClock()
        self._timeout_sec = min(float(timeout_sec), DEFAULT_MAX_STALE_PROBE_SEC)
        self._max_output_bytes = int(max_output_bytes)
        self._coordinator = ProbeReapCoordinator()
        self._processor = ProbeSequenceProcessor(self._commands, self._max_output_bytes)
        self._current_handle: Optional[ProbeCommandHandle] = None
        self._start_mono: float = 0.0
        self._start_error: Optional[str] = None

    @property
    def has_pending_cleanup(self) -> bool:
        return self._coordinator.has_pending_cleanup(
            current_handle=self._current_handle,
            start_error=self._start_error,
        )

    @property
    def pending_reap_count(self) -> int:
        return len(self._coordinator.pending_reap)

    @property
    def cleanup_error(self) -> Optional[str]:
        return self._coordinator.cleanup_error

    @cleanup_error.setter
    def cleanup_error(self, value: Optional[str]) -> None:
        self._coordinator.cleanup_error = value

    def start_probe(self) -> None:
        """Start non-blocking probe execution."""
        if self._current_handle is not None:
            if getattr(self._current_handle, "poll", lambda: 0)() is None:
                self._start_error = "Previous child process still pending exit/reap"
                return
            self._coordinator.cancel_handle(self._current_handle)
            self._current_handle = None

        if self._coordinator.pending_reap:
            self._coordinator.reap_pending()
            if self._coordinator.has_active_child():
                self._start_error = "Previous child process still pending exit/reap"
                return

        self._start_error = None
        self._start_mono = self._clock.monotonic()
        self._processor.reset()
        self._coordinator.cleanup_failed = False
        try:
            self._current_handle = self._transport(list(self._commands[0]))
        except Exception as e:
            self._start_error = f"Probe transport start failed: {e}"
            self._current_handle = None

    def _cancel_handle(self, handle: ProbeCommandHandle) -> None:
        self._coordinator.cancel_handle(handle)

    def reap_pending(self) -> int:
        """Attempt non-blocking reap of pending handles without busy-waiting."""
        return self._coordinator.reap_pending()

    def cancel(self) -> bool:
        """Cancel current probe and reap pending handles honestly. Returns True if fully clean."""
        handle = self._current_handle
        self._current_handle = None
        return self._coordinator.cancel(
            current_handle=handle, start_error=self._start_error
        )

    def poll_probe(self) -> Tuple[bool, Optional[Tuple[int, str, str, bool]]]:
        """Poll in-flight probe non-blockingly.

        Returns (done: bool, Optional[(retcode, stdout, stderr, is_timeout)]).
        """
        if self._coordinator.pending_reap:
            self._coordinator.reap_pending()

        if self._start_error is not None:
            err = self._start_error
            self._start_error = None
            return True, (1, "", err, False)

        if self._current_handle is None:
            return False, None

        now = self._clock.monotonic()
        elapsed = now - self._start_mono

        if elapsed > self._timeout_sec:
            handle = self._current_handle
            self._current_handle = None
            self._coordinator.cancel_handle(handle)
            return True, (1, "", f"ADB probe deadline exceeded ({elapsed:.1f}s > {self._timeout_sec}s)", True)

        try:
            retcode = self._current_handle.poll()
        except Exception as e:
            handle = self._current_handle
            self._current_handle = None
            self._coordinator.cancel_handle(handle)
            return True, (1, "", f"Probe poll failed: {e}", False)

        if getattr(self._current_handle, "_overflow", False):
            handle = self._current_handle
            self._current_handle = None
            self._coordinator.cancel_handle(handle)
            return True, (1, "", "Probe output truncated exceeding maximum bounds", False)

        if getattr(self._current_handle, "_io_error", None):
            err = getattr(self._current_handle, "_io_error")
            handle = self._current_handle
            self._current_handle = None
            self._coordinator.cancel_handle(handle)
            return True, (1, "", f"Probe I/O error: {err}", False)

        if getattr(self._current_handle, "_construction_error", None):
            err = self._current_handle._construction_error
            handle = self._current_handle
            self._current_handle = None
            self._coordinator.cancel_handle(handle)
            return True, (1, "", f"Probe construction failure: {err}", False)

        if retcode is None:
            return False, None

        if getattr(self._current_handle, "descendant_pipe_open", False):
            handle = self._current_handle
            self._current_handle = None
            self._coordinator.cancel_handle(handle)
            return True, (1, "", "Probe descendant pipe held open without EOF", False)

        handle = self._current_handle
        self._current_handle = None
        try:
            stdout, stderr = handle.read_available(self._max_output_bytes)
        except Exception as e:
            self._coordinator.cancel_handle(handle)
            return True, (1, "", f"Probe output read failed: {e}", False)

        self._coordinator.cleanup_handle(handle)

        done, res = self._processor.process_step(
            retcode,
            stdout,
            stderr,
            cleanup_failed=self._coordinator.cleanup_failed,
            cleanup_error=self._coordinator.cleanup_error,
        )
        if done:
            return True, res

        step_idx = self._processor.cmd_idx
        try:
            self._current_handle = self._transport(list(self._processor.current_command or []))
        except Exception as e:
            self._coordinator.cleanup_failed = True
            return True, (1, "", f"Probe transport start failed for step {step_idx}: {e}", False)
        return False, None
