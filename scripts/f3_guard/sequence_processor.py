"""Probe sequence processor for multi-command dumpsys health probes.

Extracted from AdbProbeAdapter.poll_probe in scripts/f3_emulator_guard.py
lines 1480-1534. Coordinates sequential output accumulation, UTF-8 aggregate
quota enforcement, section header validation, and cleanup poisoning.
"""

from __future__ import annotations

from typing import List, Optional, Sequence, Tuple

from scripts.f3_guard.probe_format import (
    check_aggregate_probe_quota,
    validate_dumpsys_section,
)

__all__ = ["ProbeSequenceProcessor"]


class ProbeSequenceProcessor:
    """Processes sequential probe steps, enforcing limits, validation, and poisoning."""

    def __init__(
        self,
        commands: Sequence[Sequence[str]],
        max_output_bytes: int,
    ) -> None:
        self._commands: List[List[str]] = [list(c) for c in commands]
        self._max_output_bytes: int = int(max_output_bytes)
        self._cmd_idx: int = 0
        self._collected_stdout: List[str] = []
        self._collected_stderr: List[str] = []

    @property
    def cmd_idx(self) -> int:
        """Current zero-based index in the command sequence."""
        return self._cmd_idx

    @property
    def current_command(self) -> Optional[List[str]]:
        """Arguments of the current command, or None if the sequence is complete."""
        if 0 <= self._cmd_idx < len(self._commands):
            return list(self._commands[self._cmd_idx])
        return None

    @property
    def is_last_step(self) -> bool:
        """True if the current step is the final command in the sequence."""
        return bool(self._commands and self._cmd_idx >= len(self._commands) - 1)

    @property
    def collected_stdout(self) -> List[str]:
        """Accumulated stdout strings across successfully processed steps."""
        return list(self._collected_stdout)

    @property
    def collected_stderr(self) -> List[str]:
        """Accumulated stderr strings across successfully processed steps."""
        return list(self._collected_stderr)

    @property
    def max_output_bytes(self) -> int:
        """Configured maximum total output limit in bytes."""
        return self._max_output_bytes

    def reset(self) -> None:
        """Reset sequence index and accumulated output buffers."""
        self._cmd_idx = 0
        self._collected_stdout = []
        self._collected_stderr = []

    def process_step(
        self,
        retcode: int,
        stdout: str,
        stderr: str,
        cleanup_failed: bool = False,
        cleanup_error: Optional[str] = None,
    ) -> Tuple[bool, Optional[Tuple[int, str, str, bool]]]:
        """Process a single command step in the probe sequence.

        Returns:
            (True, (retcode, stdout, stderr, False)) when the step finishes the probe
            either via failure or sequence completion.
            (False, None) when the step succeeded and more commands remain.
        """
        if retcode != 0:
            return True, (retcode, stdout, stderr or f"Command failed with code {retcode}", False)

        under_quota, _ = check_aggregate_probe_quota(
            self._collected_stdout,
            stdout,
            self._collected_stderr,
            stderr,
            self._max_output_bytes,
        )
        if not under_quota:
            return True, (
                1,
                "\n".join(self._collected_stdout + [stdout]),
                "Probe output truncated exceeding maximum bounds",
                False,
            )

        is_valid, diag = validate_dumpsys_section(self.current_command or [], stdout)
        if not is_valid:
            return True, (1, stdout, diag, False)

        self._collected_stdout.append(stdout)
        if stderr:
            self._collected_stderr.append(stderr)

        if cleanup_failed:
            err_msg = cleanup_error or "Handle cleanup pending or failed"
            return True, (1, "\n".join(self._collected_stdout), f"Probe cleanup error: {err_msg}", False)

        self._cmd_idx += 1
        if self._cmd_idx < len(self._commands):
            return False, None

        full_stdout = "\n".join(self._collected_stdout)
        full_stderr = "\n".join(self._collected_stderr)
        return True, (0, full_stdout, full_stderr, False)
