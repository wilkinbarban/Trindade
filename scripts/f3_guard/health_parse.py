"""Diagnostic health parsing and system probe evaluation for F3 emulator guard.

This module provides dumpsys diagnostic parsing rules and health evaluation
extracted from the F3 emulator guard architecture.
"""

from __future__ import annotations

import re
from typing import Optional, Set, Tuple

from scripts.f3_guard.probe_format import _is_recognized_dumpsys_structure, _parse_health_bool
from scripts.f3_guard.types import ANREvent, ProbeStatus

__all__ = [
    "parse_system_health",
]


def parse_system_health(
    process_dump: str,
    window_dump: str = "",
    baseline_anrs: Optional[Set[str]] = None,
    baseline_events: Optional[Set[ANREvent]] = None,
) -> Tuple[ProbeStatus, str, Set[str]]:
    """Parse process dump, last-anr dump, and window dump to evaluate system health.

    Verifies structure recognition, detects notResponding/crashing/ANR history,
    distinguishes baseline fingerprints from new occurrences, and sanitizes output.
    """
    full_text = f"{process_dump}\n{window_dump}".strip()
    if not full_text:
        return ProbeStatus.UNKNOWN, "Empty probe output", set()

    if not _is_recognized_dumpsys_structure(full_text):
        return ProbeStatus.UNKNOWN, "Unrecognized probe output format without known dumpsys structures", set()

    detected_events: Set[ANREvent] = set()
    affected_packages: Set[str] = set()
    parsed_valid_records: int = 0  # Well-formed process records matched
    health_confirmed_records: int = 0  # Records with explicit health fields

    # 1. Parse ActivityManager process records: ProcessRecord{hash pid:package/uid}
    # Require well-formed structure: hex hash, numeric PID, valid package, UID suffix.
    # Garbage like ProcessRecord{garbage} without proper fields → no match (fail closed).
    proc_pattern = re.compile(
        r"ProcessRecord\{[0-9a-fA-F]+\s+(\d+):([a-zA-Z0-9_\.:]+)/[a-zA-Z0-9_]+\}\s*(.*?)(?=\n\s*ProcessRecord|\Z)",
        re.DOTALL,
    )
    for match in proc_pattern.finditer(process_dump):
        pid_str = match.group(1)
        pkg = match.group(2)
        body = match.group(3)
        parsed_valid_records += 1

        # Parse health boolean fields with strict exact-token validation.
        # Only 'true' and 'false' are valid; anything else is malformed.
        nr_val = _parse_health_bool(body, "notResponding")
        cr_val = _parse_health_bool(body, "crashing")

        if nr_val is None or cr_val is None:
            # Missing or malformed field: this record cannot confirm health.
            # If a valid 'true' was parsed for one field, treat as ANR
            # (fail-closed), otherwise the record stays unconfirmed.
            if nr_val is True or cr_val is True:
                evt = ANREvent(
                    package=pkg,
                    pid=int(pid_str),
                    reason="notResponding" if nr_val is True else "crashing",
                )
                detected_events.add(evt)
                affected_packages.add(pkg)
            continue

        health_confirmed_records += 1

        if nr_val is True or cr_val is True:
            reason = "notResponding" if nr_val is True else "crashing"
            evt = ANREvent(package=pkg, pid=int(pid_str), reason=reason)
            detected_events.add(evt)
            affected_packages.add(pkg)

    # 2. Parse dumpsys activity last-anr / ANR history sections.
    # Use separate non-optional patterns to reliably capture PID and Reason
    # when present (the previous optional-group regex silently dropped them).
    anr_header_pattern = re.compile(
        r"ANR in\s+([a-zA-Z0-9_\.]+)",
        re.IGNORECASE,
    )
    pid_pattern = re.compile(r"PID:\s*(\d+)")
    reason_pattern = re.compile(r"Reason:\s*([^\n]+)")

    # Process each ANR block: find the header, then search within its
    # neighborhood (up to 500 chars or next ANR header) for PID/Reason.
    for a_match in anr_header_pattern.finditer(full_text):
        pkg = a_match.group(1)
        block_start = a_match.end()
        # Find the next ANR header or end of text as block boundary
        next_anr = anr_header_pattern.search(full_text, block_start)
        block_end = next_anr.start() if next_anr else min(block_start + 500, len(full_text))
        block = full_text[block_start:block_end]

        pid_m = pid_pattern.search(block)
        reason_m = reason_pattern.search(block)
        pid_val = int(pid_m.group(1)) if pid_m else None
        reason_str = reason_m.group(1).strip() if reason_m else "unresponsive"
        evt = ANREvent(package=pkg, pid=pid_val, reason=reason_str)
        detected_events.add(evt)
        affected_packages.add(pkg)

    # 3. Parse Window dump specific error patterns (excluding innocent word boundaries)
    window_anr_pattern = re.compile(
        r"Application Not Responding:\s*([a-zA-Z0-9_\.]+)",
        re.IGNORECASE,
    )
    for w_match in window_anr_pattern.finditer(window_dump):
        pkg = w_match.group(1)
        if pkg:
            evt = ANREvent(package=pkg, reason="window_anr")
            detected_events.add(evt)
            affected_packages.add(pkg)

    # 4. Filter against baselines using full event fingerprint.
    # Never collapse to package-name-only forgiveness: same package with
    # different PID or reason is a NEW recurrence that must be detected.
    # Same pkg/pid/reason with no occurrence_tag cannot reliably distinguish
    # recurrence from historical baseline; conservatively do NOT forgive.
    new_events: Set[ANREvent] = set()
    for evt in detected_events:
        if baseline_events is not None:
            # Baseline forgiveness is DISABLED: without parsing the current
            # event's occurrence timestamp/ID from the dump, we cannot prove
            # a detected event IS the exact baseline occurrence rather than
            # a recurrence. Conservative: never forgive via baseline_events.
            # When host adapter implements timestamp parsing, re-enable with
            # proven exact identity matching.
            pass
        if baseline_anrs is not None:
            # Legacy package-name set: only forgive if the event has no
            # distinguishing fields (pid/reason) beyond the package name.
            if evt.package in baseline_anrs and evt.pid is None and evt.reason in ("", "unresponsive"):
                continue
        new_events.add(evt)

    if not new_events:
        # CLEAR requires at least one well-formed process record with
        # explicit health fields. Header-only, garbage, or truncated
        # records (missing notResponding/crashing) cannot confirm health.
        if parsed_valid_records == 0:
            return ProbeStatus.UNKNOWN, "Recognized section header but no well-formed process records parsed", set()
        if health_confirmed_records < parsed_valid_records:
            return ProbeStatus.UNKNOWN, "Some process records lack explicit health fields; cannot confirm health", set()
        return ProbeStatus.CLEAR, "All monitored processes healthy", set()

    new_affected_pkgs = {e.package for e in new_events}
    # Sanitized structured summary: no raw user dumps, no stack dumps
    summary_items = [
        f"{e.package}(pid={e.pid or 'n/a'}, reason={e.reason})"
        for e in sorted(list(new_events), key=lambda x: (x.package, x.pid or 0))
    ]
    reason = f"Detected active unresponsive process(es): {', '.join(summary_items)}"
    return ProbeStatus.ANR_DETECTED, reason, new_affected_pkgs
