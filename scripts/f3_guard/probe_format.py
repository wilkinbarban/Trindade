"""Probe formatting constants, patterns, and parsing helpers for F3 emulator guard.

This module provides dumpsys diagnostic parsing rules and section validation
extracted from the F3 emulator guard architecture.
"""

from __future__ import annotations

import re
from typing import Optional, Sequence, Tuple

__all__ = [
    "APP_PACKAGE",
    "RECOGNIZED_SECTION_HEADERS",
    "SYSTEM_UI_PACKAGE",
    "_ANR_HEADER_RE",
    "_LAST_ANR_HEADER_RE",
    "_NO_ANR_BODY_RE",
    "_is_recognized_dumpsys_structure",
    "_parse_health_bool",
    "_validate_last_anr_content",
    "check_aggregate_probe_quota",
    "validate_dumpsys_section",
]

# --- ANR and Health Probe Parsing ---

SYSTEM_UI_PACKAGE: str = "com.android.systemui"
APP_PACKAGE: str = "com.trindade.app"

_LAST_ANR_HEADER_RE = re.compile(r"^ACTIVITY MANAGER LAST ANR(?:\s*\(dumpsys activity last-anr\))?$", re.IGNORECASE)
_NO_ANR_BODY_RE = re.compile(r"^(?:no ANRs recorded\.?|\(no ANRs recorded\)|no ANRs?\.?)$", re.IGNORECASE)
_ANR_HEADER_RE = re.compile(r"^ANR in\s+([a-zA-Z0-9_\.]+)", re.IGNORECASE | re.MULTILINE)


def _validate_last_anr_content(stdout: str) -> Tuple[bool, str]:
    """Validate dumpsys activity last-anr output strictly with tri-state rules.

    Accepts only explicit anchored no-ANR body or well-formed ANR events.
    Rejects empty, garbage, header-only, truncated ANR, unknown body, and mixed junk.
    """
    if not stdout or not stdout.strip():
        return False, "Empty last-anr dump"
    lines = [l.strip() for l in stdout.strip().splitlines() if l.strip()]
    if not lines or not _LAST_ANR_HEADER_RE.match(lines[0]):
        return False, "Missing or invalid ACTIVITY MANAGER LAST ANR header"
    if len(lines) < 2:
        return False, "last-anr header-only with no body"

    body_lines = lines[1:]
    body_text = "\n".join(body_lines).strip()

    if len(body_lines) == 1 and _NO_ANR_BODY_RE.match(body_lines[0]):
        return True, "no_anr"

    anr_matches = list(_ANR_HEADER_RE.finditer(body_text))
    if "anr in" in body_text.lower() and not anr_matches:
        return False, "Malformed or truncated ANR in header without valid package"

    if anr_matches:
        return True, "has_anr"

    return False, "Unrecognized or malformed last-anr body"

# Structural section headers that confirm complete diagnostic dump format.
# Individual markers like ProcessRecord{} or mCurrentFocus alone are insufficient;
# the dump must contain at least one authoritative section header.
RECOGNIZED_SECTION_HEADERS = [
    re.compile(r"ACTIVITY MANAGER RUNNING PROCESSES", re.IGNORECASE),
    re.compile(r"ACTIVITY MANAGER LAST ANR", re.IGNORECASE),
    re.compile(r"ACTIVITY MANAGER ANR", re.IGNORECASE),
    re.compile(r"WINDOW MANAGER WINDOWS", re.IGNORECASE),
]


def _is_recognized_dumpsys_structure(content: str) -> bool:
    """Confirm whether content exhibits a recognized dumpsys section header.

    Lone ProcessRecord{} fragments or mCurrentFocus lines without a section
    header are NOT recognized — they could be garbage or partial output and
    must fail closed to UNKNOWN.
    """
    for pattern in RECOGNIZED_SECTION_HEADERS:
        if pattern.search(content):
            return True
    return False


def _parse_health_bool(body: str, field_name: str) -> Optional[bool]:
    """Parse a boolean field from a ProcessRecord body with strict validation.

    Only exact lowercase 'true' or 'false' tokens are accepted. Missing, empty,
    malformed, case-mismatched, numeric, trailing garbage, or duplicate occurrences
    return None.
    """
    pattern = re.compile(r"(?:^|\s)" + re.escape(field_name) + r"=(\S*)", re.MULTILINE)
    matches = pattern.findall(body)
    if len(matches) != 1:
        return None
    val = matches[0]
    if val == "true":
        return True
    if val == "false":
        return False
    return None


def check_aggregate_probe_quota(
    collected_stdout: Sequence[str],
    stdout: str,
    collected_stderr: Sequence[str],
    stderr: str,
    max_output_bytes: int,
) -> Tuple[bool, str]:
    """Check whether aggregate probe output exceeds the maximum byte limit.

    Computes total UTF-8 bytes (using errors="replace") across previously collected
    and current stdout and stderr streams without counting joining separators.
    Rejects when total bytes >= max_output_bytes.

    Caller responsibility / ordering note:
        Future probe adapters (e.g., AdbProbeAdapter) must invoke quota checks before
        evaluating command-specific section formatting. This helper only evaluates
        the aggregate byte quota contract; it does not enforce caller execution
        sequence or claim runtime adapter status.

    Returns:
        (True, "") if under quota.
        (False, "Probe output truncated exceeding maximum bounds") if >= max_output_bytes.
    """
    tot_bytes = (
        sum(len(s.encode("utf-8", errors="replace")) for s in collected_stdout)
        + len(stdout.encode("utf-8", errors="replace"))
        + sum(len(s.encode("utf-8", errors="replace")) for s in collected_stderr)
        + len(stderr.encode("utf-8", errors="replace"))
    )
    if tot_bytes >= max_output_bytes:
        return False, "Probe output truncated exceeding maximum bounds"
    return True, ""


def validate_dumpsys_section(
    command_args: Sequence[str],
    stdout: str,
) -> Tuple[bool, str]:
    """Validate stdout structure for dumpsys commands.

    Inspects joined command arguments for targeted diagnostic subcommands:
    - If "processes" in joined command_args (case-sensitive):
        requires "ACTIVITY MANAGER RUNNING PROCESSES" (case-insensitive) anywhere in stdout.
    - If "last-anr" in joined command_args (case-sensitive):
        validates stdout via `_validate_last_anr_content(stdout)`.

    Both checks execute sequentially (processes check first, then last-anr check;
    not elif). Unknown commands pass validation without inspection.

    Caller responsibility / ordering note:
        Future adapters must run quota checks prior to section validation.
        This helper provides pure in-memory validation of command output
        and makes no runtime integration or adapter claims.

    Returns:
        (True, "") if validation passes or no checks apply.
        (False, error_message) on failure with exact diagnostic string:
            - "processes dump missing required section header"
            - "last-anr dump validation failed: <kind_or_err>"
    """
    cmd_str = " ".join(command_args)
    if "processes" in cmd_str:
        if not re.search(r"ACTIVITY MANAGER RUNNING PROCESSES", stdout, re.IGNORECASE):
            return False, "processes dump missing required section header"
    if "last-anr" in cmd_str:
        is_valid, kind_or_err = _validate_last_anr_content(stdout)
        if not is_valid:
            return False, f"last-anr dump validation failed: {kind_or_err}"
    return True, ""
