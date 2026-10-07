"""Fail-closed ADB probe result evaluation for F3 emulator guard.

Evaluates raw process execution outcomes, transport timeouts, non-zero exit
codes, corrupted/null probe payloads, and delegates structured health output
parsing to parse_system_health.
"""

from __future__ import annotations

from typing import Optional, Set, Tuple

from scripts.f3_guard.health_parse import parse_system_health
from scripts.f3_guard.types import ANREvent, ProbeStatus

__all__ = [
    "evaluate_adb_probe_result",
]


def evaluate_adb_probe_result(
    returncode: int,
    stdout: str,
    stderr: str,
    is_timeout: bool,
    baseline_anrs: Optional[Set[str]] = None,
    baseline_events: Optional[Set[ANREvent]] = None,
) -> Tuple[ProbeStatus, str, Set[str]]:
    """Evaluate raw ADB output fail-closed."""
    if is_timeout:
        return ProbeStatus.PROBE_ERROR, "ADB probe timeout expired", set()

    if returncode != 0:
        err_msg = stderr.strip() or f"ADB process exited with code {returncode}"
        return ProbeStatus.PROBE_ERROR, f"ADB probe failed: {err_msg}", set()

    if "\x00" in stdout or (not stdout.strip() and not stderr.strip()):
        return ProbeStatus.UNKNOWN, "Unknown or corrupted probe output format", set()

    return parse_system_health(
        process_dump=stdout,
        window_dump="",
        baseline_anrs=baseline_anrs,
        baseline_events=baseline_events,
    )
