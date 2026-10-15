"""F3 Android Emulator Capacity and Safety Guard.

This module provides a bounded, fail-closed capacity supervisor and safety guard
for the Trindade F3 Android emulator verification run.

Design Contracts & Safety Invariants:
1. Memory Thresholds: Fail-closed stopping condition when:
   - MemAvailable < 1,048,576 kB (1.0 GiB)
   - SwapFree < 524,288 kB (512 MiB)
   Memory parse errors or missing /proc/meminfo metrics fail closed immediately.
   Strict finite non-negative integer kB required (NaN, Inf, floats rejected).
2. Cadence & Decoupling:
   - Memory sampling operates on a 5.0s cadence.
   - Asynchronous ANR/health probes run independently so slow ADB operations
     do not delay host RAM sampling. Stale or overdue probes (>15s) trigger a fail-closed stop.
   - Unrecognized or garbage probe formats fail closed to UNKNOWN / STOP.
3. Monotonic TTL:
   - Maximum lifetime is bounded to 1200.0s (20 minutes) using time.monotonic(),
     making the supervisor immune to wall-clock skew, NTP adjustments, or DST shifts.
4. Process Identity & Signal Discipline:
   - Target identity (PID, /proc start_ticks, UID 1000, AVD name, port, executable)
     is strictly verified immediately before issuing SIGTERM.
   - PID reuse is prevented: mismatched start_ticks, UID, or cmdline aborts signaling.
   - Safety against TOCTOU: pidfd/pidfd_send_signal supported with identity anchors.
   - SIGKILL is strictly prohibited. If a process does not exit during grace timeout,
     it is reported honestly as TERMINATION_FAILED, never assumed stopped.
   - Zombie states (Z) are classified as ZOMBIE_UNREAPED unless directly reaped via waitpid.
5. Readiness Evaluation:
   - Readiness requires: boot completed AND post-boot RAM/Swap above guard thresholds
     AND established ANR baseline AND clean probe status AND verified identity.
6. Supervisor Death Limitation:
   - On POSIX, abrupt parent death (e.g., SIGKILL of the supervisor itself) cannot
     guarantee child termination unless kernel mechanisms such as Linux PR_SET_PDEATHSIG
     are configured. Catchable exceptions and normal exits trigger safe cleanup in finally blocks.

Inert Import:
   - Importing this module executes no subprocesses, signals, or network calls.
"""

from __future__ import annotations

import argparse
import math
import os
import re
import signal
import subprocess
import sys
import time
from dataclasses import dataclass, replace
from enum import Enum
from typing import Any, Callable, Dict, List, Optional, Set, Tuple, Union


# --- Contract Constants ---
GUARD_RAM_MIN_KB: int = 1048576       # 1.0 GiB
GUARD_SWAP_MIN_KB: int = 524288       # 512 MiB
DEFAULT_CADENCE_SEC: float = 5.0
DEFAULT_TTL_SEC: float = 1200.0       # 20 minutes
DEFAULT_MAX_STALE_PROBE_SEC: float = 15.0
DEFAULT_GRACE_SEC: float = 20.0
TARGET_UID: int = 1000
TARGET_AVD: str = "Trindade_UX_v030"
TARGET_PORT: int = 5556
TARGET_BINARY: str = "qemu-system-x86_64"
DEFAULT_ADB_SERIAL: str = "emulator-5556"
MAX_PROBE_OUTPUT_BYTES: int = 1048576  # 1 MiB bounded probe output
LAUNCH_RAM_MIN_KB: int = 4718592       # 4.5 GiB
LAUNCH_SWAP_MIN_KB: int = 2097152      # 2.0 GiB
DEFAULT_EMULATOR_PATH: str = "/home/wilkin/Android/Sdk/emulator/emulator"


class MeminfoParseError(Exception):
    """Raised when /proc/meminfo cannot be read or lacks required numeric fields."""
    pass


class ProbeStatus(str, Enum):
    CLEAR = "clear"
    ANR_DETECTED = "anr_detected"
    PROBE_ERROR = "probe_error"
    UNKNOWN = "unknown"


class LifecycleState(str, Enum):
    INITIALIZING = "initializing"
    RUNNING = "running"
    STOPPED = "stopped"
    TERMINATION_PENDING = "termination_pending"
    TERMINATION_FAILED = "termination_failed"
    PID_MISMATCH = "pid_mismatch"
    ZOMBIE_UNREAPED = "zombie_unreaped"
    ZOMBIE_PENDING = "zombie_pending"
    UNKNOWN = "unknown"
    ERROR = "error"


@dataclass(frozen=True)
class ProcessIdentity:
    """Exact identity descriptor for target QEMU emulator process."""
    pid: int
    start_ticks: int
    uid: int
    avd: str
    port: int
    binary_name: str
    is_direct_child: bool = False


class TerminationClaimStatus(str, Enum):
    NOT_ATTEMPTED, CLAIMED, ALREADY_CLAIMED, MISMATCH, IN_PROGRESS = "not_attempted", "claimed", "already_claimed", "mismatch", "in_progress"
    @property
    def status(self): return self

@dataclass(frozen=True)
class TerminationOutcome:
    state: LifecycleState; detail: str = ""; primary_error: Optional[BaseException] = None; completed: bool = True
    @property
    def primary_exception(self) -> Optional[BaseException]: return self.primary_error

@dataclass(frozen=True)
class TerminationAttemptRecord:
    pid: int; start_ticks: int; uid: int; avd: str; controller: Any
    outcome: Optional[TerminationOutcome] = None; target: Optional[ProcessIdentity] = None
TerminationAttempt, TerminationRecord = TerminationAttemptRecord, TerminationOutcome


@dataclass(frozen=True)
class ANREvent:
    """Fingerprint for distinct ANR / crash events to distinguish baseline from new occurrences."""
    package: str
    pid: Optional[int] = None
    reason: str = ""
    occurrence_tag: str = ""


@dataclass(frozen=True)
class BootEvidence:
    """Strict preflight evidence of target process boot completion."""
    boot_completed: bool
    pid: int
    start_ticks: int
    timestamp_mono: float
    avd: Optional[str] = None
    uid: Optional[int] = None


# --- Clock Interfaces ---

class Clock:
    def monotonic(self) -> float:
        raise NotImplementedError

    def time(self) -> float:
        raise NotImplementedError


class SystemClock(Clock):
    def monotonic(self) -> float:
        return time.monotonic()

    def time(self) -> float:
        return time.time()


class FakeClock(Clock):
    """Injectable clock for hermetic unit testing of TTL and cadence."""
    def __init__(self, monotonic_start: float = 0.0, wall_start: float = 1700000000.0):
        self._mono: float = float(monotonic_start)
        self._wall: float = float(wall_start)

    def monotonic(self) -> float:
        return self._mono

    def time(self) -> float:
        return self._wall

    def advance_monotonic(self, delta: float) -> None:
        self._mono += float(delta)

    def advance_wall(self, delta: float) -> None:
        self._wall += float(delta)


# --- Monotonic TTL Tracker ---

class MonotonicTTLTracker:
    """Tracks elapsed runtime using monotonic time to resist wall-clock skew."""
    def __init__(self, ttl_seconds: float = DEFAULT_TTL_SEC, clock: Optional[Clock] = None):
        self._clock: Clock = clock or SystemClock()
        self._ttl_sec: float = float(ttl_seconds)
        self._start_mono: float = self._clock.monotonic()

    def elapsed_seconds(self) -> float:
        return max(0.0, self._clock.monotonic() - self._start_mono)

    def remaining_seconds(self) -> float:
        remaining = self._ttl_sec - self.elapsed_seconds()
        return max(0.0, remaining)

    def is_expired(self) -> bool:
        return self.elapsed_seconds() >= self._ttl_sec


# --- Memory Parsing and Verification ---

def parse_meminfo(content: str) -> Tuple[int, int]:
    """Parse MemAvailable and SwapFree in kB from /proc/meminfo text.

    Raises MeminfoParseError if either value is absent or non-numeric.
    """
    if not content or not content.strip():
        raise MeminfoParseError("Empty /proc/meminfo content")

    mem_avail: Optional[int] = None
    swap_free: Optional[int] = None

    for line in content.splitlines():
        parts = line.split()
        if len(parts) >= 2:
            key = parts[0]
            val_str = parts[1]
            if key == "MemAvailable:":
                try:
                    mem_avail = int(val_str)
                except ValueError:
                    raise MeminfoParseError(f"Malformed MemAvailable value: {val_str}")
            elif key == "SwapFree:":
                try:
                    swap_free = int(val_str)
                except ValueError:
                    raise MeminfoParseError(f"Malformed SwapFree value: {val_str}")

    if mem_avail is None:
        raise MeminfoParseError("Missing MemAvailable in /proc/meminfo")
    if swap_free is None:
        raise MeminfoParseError("Missing SwapFree in /proc/meminfo")

    return mem_avail, swap_free


class HostMeminfoReader:
    """Reads and parses host memory metrics (/proc/meminfo) with fail-closed semantics."""
    def __init__(
        self,
        path: str = "/proc/meminfo",
        file_reader: Optional[Callable[[str], str]] = None
    ) -> None:
        self._path = path
        self._file_reader = file_reader

    def __call__(self) -> Tuple[int, int]:
        try:
            if self._file_reader is not None:
                content = self._file_reader(self._path)
            else:
                with open(self._path, "r", encoding="utf-8") as f:
                    content = f.read()
        except Exception as e:
            raise MeminfoParseError(f"Host memory read failed for {self._path}: {e}") from e

        if not isinstance(content, str):
            raise MeminfoParseError(f"Meminfo content must be str, got {type(content).__name__}")

        return parse_meminfo(content)


def validate_memory_limits(mem_avail_kb: Any, swap_free_kb: Any) -> Tuple[bool, str]:
    """Validate that memory readings satisfy guard thresholds.

    Strictly requires finite non-negative integer kB.
    Rejects booleans, NaN, Inf, floats, fractions, and values below thresholds.
    """
    if isinstance(mem_avail_kb, bool) or isinstance(swap_free_kb, bool):
        return False, "Invalid boolean passed as memory metric"

    # Strictly require int (reject float, nan, inf, Decimal, etc.)
    if type(mem_avail_kb) is not int or type(swap_free_kb) is not int:
        return False, "Memory metric must be strict integer kB"

    if mem_avail_kb < 0 or swap_free_kb < 0:
        return False, "Invalid negative memory metric"

    if mem_avail_kb < GUARD_RAM_MIN_KB:
        return False, f"Available RAM {mem_avail_kb} kB below minimum {GUARD_RAM_MIN_KB} kB"

    if swap_free_kb < GUARD_SWAP_MIN_KB:
        return False, f"Free Swap {swap_free_kb} kB below minimum {GUARD_SWAP_MIN_KB} kB"

    return True, "Memory limits satisfied"


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


def parse_system_health(
    process_dump: str,
    window_dump: str = "",
    baseline_anrs: Optional[Set[str]] = None,
    baseline_events: Optional[Set[ANREvent]] = None
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
    parsed_valid_records: int = 0     # Well-formed process records matched
    health_confirmed_records: int = 0  # Records with explicit health fields

    # 1. Parse ActivityManager process records: ProcessRecord{hash pid:package/uid}
    # Require well-formed structure: hex hash, numeric PID, valid package, UID suffix.
    # Garbage like ProcessRecord{garbage} without proper fields → no match (fail closed).
    proc_pattern = re.compile(
        r"ProcessRecord\{[0-9a-fA-F]+\s+(\d+):([a-zA-Z0-9_\.]+)/[a-zA-Z0-9_]+\}\s*(.*?)(?=\n\s*ProcessRecord|\Z)",
        re.DOTALL
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
                evt = ANREvent(package=pkg, pid=int(pid_str),
                               reason="notResponding" if nr_val is True else "crashing")
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
        re.IGNORECASE
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
        re.IGNORECASE
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
        elif baseline_anrs is not None:
            # Legacy package-name set: only forgive if the event has no
            # distinguishing fields (pid/reason) beyond the package name.
            if evt.package in baseline_anrs and evt.pid is None and evt.reason in ("", "unresponsive"):
                continue
        new_events.add(evt)

    if not new_events:
        # CLEAR requires at least one well-formed process record with
        # explicit health fields. Header-only, garbage, or truncated
        # records (missing notResponding/crashing) cannot confirm health.
        # Zero valid records is UNKNOWN on its own: a detected event that the
        # legacy baseline forgave must not be what decides that a probe which
        # parsed nothing is allowed to call the system healthy.
        if parsed_valid_records == 0:
            return ProbeStatus.UNKNOWN, "Recognized section header but no well-formed process records parsed", set()
        if health_confirmed_records < parsed_valid_records:
            return ProbeStatus.UNKNOWN, "Some process records lack explicit health fields; cannot confirm health", set()
        return ProbeStatus.CLEAR, "All monitored processes healthy", set()

    new_affected_pkgs = {e.package for e in new_events}
    # Sanitized structured summary: no raw user dumps, no stack dumps
    summary_items = [f"{e.package}(pid={e.pid or 'n/a'}, reason={e.reason})" for e in sorted(list(new_events), key=lambda x: (x.package, x.pid or 0, x.reason))]
    reason = f"Detected active unresponsive process(es): {', '.join(summary_items)}"
    return ProbeStatus.ANR_DETECTED, reason, new_affected_pkgs


def evaluate_adb_probe_result(
    returncode: int,
    stdout: str,
    stderr: str,
    is_timeout: bool,
    baseline_anrs: Optional[Set[str]] = None,
    baseline_events: Optional[Set[ANREvent]] = None
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
        baseline_events=baseline_events
    )


# --- Process Controller & Identity Verification ---

# Sentinel distinguishing "process confirmed absent" from "inspection failed"
# (permissions, I/O error, corrupted /proc). Only ProcessLookupError and
# FileNotFoundError confirm absence; other errors are ambiguous.
_INSPECTION_ERROR = object()


class BindingState(Enum):
    NEVER_BOUND = "NEVER_BOUND"
    BOUND = "BOUND"
    CONSUMED = "CONSUMED"
    IDENTITY_MISMATCH = "IDENTITY_MISMATCH"
    CLOSED = "CLOSED"
    ERROR = "ERROR"


@dataclass(frozen=True)
class PidfdBinding:
    pid: int
    start_ticks: int
    fd: int


class ProcessController:
    """Abstract/default process interaction provider for inspection and signaling."""
    is_direct_child: bool = False
    use_pidfd: bool = False
    _bound_pidfd: Optional[PidfdBinding] = None
    _binding_state: BindingState = BindingState.NEVER_BOUND
    _cleanup_error: Optional[BaseException] = None

    @property
    def binding_state(self) -> BindingState:
        return self._binding_state

    def get_exe_path(self, pid: int) -> Optional[str]:
        try:
            return os.readlink(f"/proc/{pid}/exe")
        except Exception:
            return None

    def get_ppid(self, pid: int) -> Optional[int]:
        try:
            with open(f"/proc/{pid}/stat", "r") as f:
                content = f.read()
            rparen = content.rfind(")")
            return int(content[rparen + 2:].split()[1]) if rparen != -1 else None
        except Exception:
            return None

    def get_start_ticks(self, pid: int) -> Union[int, None, object]:
        """Return start_ticks (int), None if process confirmed absent, or
        _INSPECTION_ERROR if the reading failed for other reasons."""
        try:
            with open(f"/proc/{pid}/stat", "r") as f:
                content = f.read()
            rparen = content.rfind(")")
            if rparen == -1:
                return _INSPECTION_ERROR
            fields = content[rparen + 2:].split()
            return int(fields[19])
        except (FileNotFoundError, ProcessLookupError):
            return None
        except Exception:
            return _INSPECTION_ERROR

    def get_uid(self, pid: int) -> Optional[int]:
        try:
            return os.stat(f"/proc/{pid}").st_uid
        except (FileNotFoundError, ProcessLookupError):
            return None
        except Exception:
            return None

    def get_cmdline(self, pid: int) -> List[str]:
        try:
            with open(f"/proc/{pid}/cmdline", "rb") as f:
                content = f.read()
            return [arg.decode("utf-8", errors="replace") for arg in content.split(b"\x00") if arg]
        except (FileNotFoundError, ProcessLookupError):
            return []
        except Exception:
            return []

    def get_fdinfo_pid(self, fd: int) -> Optional[int]:
        if not (isinstance(fd, int) and type(fd) is not bool and fd >= 0):
            return None
        try:
            with open(f"/proc/self/fdinfo/{fd}", "r") as f:
                content = f.read(8193)
            if len(content) > 8192 or not content.endswith("\n"):
                return None
            found_pid = None
            for line in content.splitlines():
                if line.startswith("Pid:"):
                    if found_pid is not None:
                        return None
                    m = re.fullmatch(r"Pid:[ \t]+([0-9]+)", line)
                    if not m:
                        return None
                    val = int(m.group(1))
                    if val <= 0:
                        return None
                    found_pid = val
            return found_pid
        except Exception:
            return None

    def is_pidfd_alive(self, fd: int) -> bool:
        if not (isinstance(fd, int) and type(fd) is not bool and fd >= 0):
            return False
        try:
            import select
            p = select.poll()
            flags = select.POLLIN | select.POLLHUP | select.POLLERR | getattr(select, "POLLNVAL", 0)
            p.register(fd, flags)
            return len(p.poll(0)) == 0
        except Exception:
            return False

    def get_process_state(self, pid: int) -> Optional[str]:
        """Returns process state character: 'R', 'S', 'Z' (zombie), 'INSPECTION_ERROR', or None if gone."""
        try:
            with open(f"/proc/{pid}/status", "r") as f:
                for line in f:
                    if line.startswith("State:"):
                        parts = line.split()
                        if len(parts) >= 2:
                            return parts[1]
            return "INSPECTION_ERROR"
        except (FileNotFoundError, ProcessLookupError):
            return None
        except Exception:
            return "INSPECTION_ERROR"

    def close_fd(self, fd: int) -> None:
        """Close a file descriptor, invalidating any matching bound fd BEFORE os.close."""
        binding = getattr(self, "_bound_pidfd", None)
        if binding is not None and binding.fd == fd:
            self._bound_pidfd = None
            self._binding_state = BindingState.CLOSED
        try:
            os.close(fd)
        except BaseException as e:
            self._binding_state = BindingState.ERROR
            self._cleanup_error = e
            raise

    def send_signal(self, pid: int, sig: int) -> None:
        os.kill(pid, sig)

    def bind_anchored_pidfd(self, pid: int, start_ticks: int, fd: int) -> bool:
        """Exclusive, one-time binding tied to exact PID and start_ticks. Rejects duplicates/overwrites."""
        if not (isinstance(pid, int) and type(pid) is not bool and pid > 0):
            return False
        if not (isinstance(start_ticks, int) and type(start_ticks) is not bool and start_ticks > 0):
            return False
        if not (isinstance(fd, int) and type(fd) is not bool and fd >= 0):
            return False
        if getattr(self, "_bound_pidfd", None) is not None:
            return False
        self._bound_pidfd = PidfdBinding(pid=pid, start_ticks=start_ticks, fd=fd)
        self._binding_state = BindingState.BOUND
        return True

    def consume_bound_pidfd(self, target: ProcessIdentity) -> Optional[int]:
        """Extract bound pidfd using current target identity and start_ticks. Clears binding before handoff."""
        binding = getattr(self, "_bound_pidfd", None)
        if binding is None:
            return None
        if target.pid != binding.pid or target.start_ticks != binding.start_ticks:
            self._binding_state = BindingState.IDENTITY_MISMATCH
            return None
        self._bound_pidfd = None
        self._binding_state = BindingState.CONSUMED
        return binding.fd

    def cleanup_bound_pidfd(self) -> Optional[Exception]:
        """Idempotent, non-signaling close of any unconsumed bound pidfd."""
        if getattr(self, "_cleanup_error", None) is not None:
            return self._cleanup_error
        binding = getattr(self, "_bound_pidfd", None)
        if binding is None:
            return None
        fd = binding.fd
        self._bound_pidfd = None
        self._binding_state = BindingState.CLOSED
        try:
            os.close(fd)
            return None
        except BaseException as e:
            self._binding_state = BindingState.ERROR
            self._cleanup_error = e
            if isinstance(e, (KeyboardInterrupt, SystemExit)):
                raise
            return e

    def adopt_pidfd(self, pid: int, pidfd: int) -> None:
        """Backward-compatible adoption wrapping exclusive binding."""
        self.bind_anchored_pidfd(pid, getattr(self, "get_start_ticks", lambda p: 1)(pid) or 1, pidfd)

    def pidfd_open(self, pid: int) -> Optional[int]:
        """Attempt to open Linux pidfd for race-free signaling if supported."""
        if hasattr(os, "pidfd_open"):
            try:
                return os.pidfd_open(pid, 0)
            except Exception:
                return None
        return None

    def pidfd_send_signal(self, pidfd: int, sig: int) -> bool:
        """Send signal via Linux pidfd."""
        if hasattr(signal, "pidfd_send_signal"):
            try:
                signal.pidfd_send_signal(pidfd, sig)
                return True
            except Exception:
                return False
        return False

    def try_reap(self, pid: int) -> Optional[bool]:
        """Attempt waitpid non-blocking if direct child.

        Returns True if successfully reaped, False if still pending (zombie
        not yet reapable), None if reap failed or pid is not a child.
        """
        try:
            res_pid, _ = os.waitpid(pid, os.WNOHANG)
            if res_pid == pid:
                return True
            # res_pid == 0 means child exists but hasn't changed state yet
            return False
        except ChildProcessError:
            # "No child processes" — already reaped or not our child
            return None
        except Exception:
            return None


def _find_exact_argv(cmdline: List[str], flag: str, expected: str) -> bool:
    """Return True if flag is in cmdline and the NEXT argument equals expected exactly."""
    try:
        idx = cmdline.index(flag)
        return idx + 1 < len(cmdline) and cmdline[idx + 1] == expected
    except ValueError:
        return False


def verify_process_identity(target: ProcessIdentity, controller: ProcessController) -> Tuple[bool, str]:
    """Strictly verify target PID, start_ticks, UID, and cmdline before signaling.

    Identity checks:
    - start_ticks must match exactly (integer comparison). _INSPECTION_ERROR
      is treated as ambiguous and rejected conservatively.
    - UID must equal the target UID (contract: 1000 for emulator user).
    - Executable (argv[0] basename or /proc/exe) must equal the known binary name
      exactly — not a substring match. A path ending in the binary is accepted.
    - AVD name: the exact next argument after ‘-avd’ must equal target.avd.
    - Port: the exact next argument after a documented port flag must equal
      str(target.port).
    """
    if not target or not target.pid or not target.start_ticks:
        return False, "Missing target PID or startTicks"

    # Contract: target UID must be the emulator user (1000), never root.
    if target.uid != TARGET_UID:
        return False, f"Target UID {target.uid} != required {TARGET_UID}"

    current_ticks = controller.get_start_ticks(target.pid)
    # _INSPECTION_ERROR → ambiguous, reject conservatively
    if current_ticks is _INSPECTION_ERROR:
        return False, f"Process start_ticks inspection error (pid={target.pid})"
    if current_ticks is None or current_ticks != target.start_ticks:
        return False, f"Process startTicks mismatch ({current_ticks} != {target.start_ticks})"

    current_uid = controller.get_uid(target.pid)
    if current_uid is None or current_uid != target.uid:
        return False, f"Process UID mismatch ({current_uid} != {target.uid})"

    cmdline = controller.get_cmdline(target.pid)
    if not cmdline:
        return False, "Process cmdline is empty or unreadable"

    cmdline_str = " ".join(cmdline)

    # 1. Verify executable: argv[0] basename or full path must END with the
    #    exact binary name (/usr/bin/qemu-system-x86_64 is OK, qemu.bak is not,
    #    python3-with-qemu-arg is not).
    exe_arg = cmdline[0]
    exe_basename = os.path.basename(exe_arg)
    binary_ok = exe_basename == target.binary_name
    if not binary_ok:
        return False, f"Process executable '{exe_basename}' != expected '{target.binary_name}': {cmdline_str}"

    # 2. Verify AVD name: exact next argument after '-avd'
    avd_ok = _find_exact_argv(cmdline, "-avd", target.avd)
    if not avd_ok:
        return False, f"Process cmdline missing exact -avd '{target.avd}': {cmdline_str}"

    # 3. Verify port: exact next argument after '-port'
    port_ok = _find_exact_argv(cmdline, "-port", str(target.port))
    if not port_ok:
        return False, f"Process cmdline missing exact -port '{target.port}': {cmdline_str}"

    return True, "Process identity verified"


def _close_pidfd(pidfd: Optional[int], controller: ProcessController) -> Optional[Exception]:
    """Close a pidfd via the controller if it's a valid integer fd, returning any close error."""
    if isinstance(pidfd, int) and type(pidfd) is not bool and pidfd >= 0 and hasattr(controller, 'close_fd'):
        try:
            controller.close_fd(pidfd)
        except Exception as e:
            return e
    return None


def safe_sigterm(
    target: ProcessIdentity,
    controller: ProcessController,
    reason: str = "",
    grace_seconds: float = DEFAULT_GRACE_SEC,
    poll_interval: float = 0.5
) -> Tuple[LifecycleState, str]:
    """Gracefully terminate verified target with SIGTERM.

    Invariants:
    - Verifies exact PID, start_ticks, UID, and cmdline before signaling.
    - Safety against TOCTOU via pidfd where available, with tick anchors.
    - pidfd is opened BEFORE identity revalidation and signals ONLY the
      verified pidfd; if pidfd_open or pidfd_send_signal fails, returns
      honest failure — NEVER falls back to os.kill (PID-based race).
    - NEVER sends SIGKILL (forbidden by contract).
    - Accurately checks zombie state and honors waitpid reap result.
    - Distinguishes inspection errors from actual process exit.
    - All pidfd closes go through controller.close_fd, never raw os.close.
    """
    identity_ok, identity_msg = verify_process_identity(target, controller)
    if not identity_ok:
        return LifecycleState.PID_MISMATCH, f"Identity verification failed: {identity_msg}"

    # Anchor via pidfd BEFORE revalidation (pidfd locks the kernel task)
    # pidfd is REQUIRED to prevent PID-based signal races. If not supported
    # or not enabled, fail closed rather than silently using os.kill.
    pidfd = None
    pidfd_mode = getattr(controller, "use_pidfd", False) is True
    if not pidfd_mode:
        return LifecycleState.ERROR, "pidfd mode required; refusing PID-based signal to prevent race"

    raw_bstate = getattr(controller, "binding_state", None)
    if isinstance(raw_bstate, BindingState):
        bstate = raw_bstate
    elif isinstance(getattr(controller, "_bound_pidfd", None), PidfdBinding):
        bstate = BindingState.BOUND
    elif type(controller) is not ProcessController and not isinstance(controller, ProcessController):
        bstate = BindingState.NEVER_BOUND
    else:
        bstate = raw_bstate

    if hasattr(controller, "consume_bound_pidfd") and bstate == BindingState.BOUND:
        try:
            val = controller.consume_bound_pidfd(target)
            if isinstance(val, int) and type(val) is not bool and val >= 0:
                pidfd = val
        except (KeyboardInterrupt, SystemExit):
            if hasattr(controller, "_binding_state"):
                controller._binding_state = BindingState.ERROR
            raise
        except Exception as e:
            if hasattr(controller, "_binding_state"):
                controller._binding_state = BindingState.ERROR
            return LifecycleState.ERROR, f"consume_bound_pidfd failed: {e}"
        bstate = getattr(controller, "binding_state", None)

    if bstate != BindingState.NEVER_BOUND and pidfd is None:
        return LifecycleState.ERROR, f"Bound pidfd transfer failed or invalid ({getattr(bstate, 'value', bstate)}); refusing reopen"

    if pidfd is None:
        if bstate is BindingState.NEVER_BOUND and hasattr(controller, "pidfd_open"):
            pidfd = controller.pidfd_open(target.pid)
        else:
            return LifecycleState.ERROR, f"pidfd unavailable and legacy open not permitted for state ({getattr(bstate, 'value', bstate)})"

    if not isinstance(pidfd, int) or type(pidfd) is bool or pidfd < 0:
        return LifecycleState.ERROR, "pidfd_open failed; refusing PID-based fallback to prevent signal race"

    # Re-verify start ticks immediately prior to delivering signal
    ticks_before_signal = controller.get_start_ticks(target.pid)
    # Accept only exact integer match; _INSPECTION_ERROR or None both abort
    if not isinstance(ticks_before_signal, int) or ticks_before_signal != target.start_ticks:
        close_err = _close_pidfd(pidfd, controller)
        if close_err is not None:
            return LifecycleState.ERROR, f"Process startTicks shifted and pidfd close failed: {close_err}"
        return LifecycleState.PID_MISMATCH, "Process startTicks shifted prior to signal delivery (PID reused)"

    # Deliver SIGTERM only — pidfd is closed exclusively in the
    # outer finally block to prevent double-close.
    signal_result: Optional[Tuple[LifecycleState, str]] = None
    close_err: Optional[Exception] = None
    try:
        signaled = False
        if isinstance(pidfd, int) and hasattr(controller, "pidfd_send_signal"):
            signaled = controller.pidfd_send_signal(pidfd, signal.SIGTERM)
        if not signaled:
            if pidfd_mode:
                signal_result = (LifecycleState.ERROR, "pidfd_send_signal failed; refusing PID-based fallback")
            else:
                controller.send_signal(target.pid, signal.SIGTERM)
    except ProcessLookupError:
        signal_result = (LifecycleState.STOPPED, "Process already vanished prior to signal delivery")
    except Exception as e:
        signal_result = (LifecycleState.ERROR, f"Failed to send SIGTERM: {e}")
    finally:
        close_err = _close_pidfd(pidfd, controller)

    if signal_result is not None:
        if signal_result[0] != LifecycleState.STOPPED:
            return signal_result
        if close_err is not None:
            return LifecycleState.ERROR, f"Process vanished but pidfd close failed: {close_err}"
        return signal_result

    if close_err is not None:
        return LifecycleState.ERROR, f"SIGTERM delivered but pidfd close failed: {close_err}"

    # Bounded polling loop during grace period
    deadline = time.monotonic() + max(0.01, grace_seconds)
    while time.monotonic() < deadline:
        time.sleep(poll_interval)

        active_ticks = controller.get_start_ticks(target.pid)
        state = controller.get_process_state(target.pid)

        # 1. Process confirmed absent from /proc (FileNotFoundError/ProcessLookupError)
        if active_ticks is None:
            if getattr(controller, "is_direct_child", False) is True:
                try:
                    reap_result = controller.try_reap(target.pid)
                except Exception:
                    reap_result = None
                if reap_result is True:
                    return LifecycleState.STOPPED, f"Process exited and reaped ({reason})"
                elif reap_result is False:
                    return LifecycleState.ZOMBIE_PENDING, f"Process absent but reap pending ({reason})"
                else:
                    return LifecycleState.ZOMBIE_UNREAPED, f"Process absent but reap failed ({reason})"
            return LifecycleState.STOPPED, f"Process gracefully exited ({reason})"

        # 1b. Inspection error: cannot read ticks but process not confirmed absent.
        #     Do NOT assume stopped; cross-check with known state.
        if active_ticks is _INSPECTION_ERROR:
            # Cannot confirm status, continue polling (conservative)
            continue

        # 2. Start ticks changed (PID recycled by OS!)
        if active_ticks != target.start_ticks:
            return LifecycleState.PID_MISMATCH, "Process PID recycled by OS during termination"

        # 3. State is inspection error (do NOT assume stopped)
        if state == "INSPECTION_ERROR":
            continue

        # 4. Process entered Zombie state
        if state == "Z":
            if getattr(controller, "is_direct_child", False) is True:
                try:
                    reaped = controller.try_reap(target.pid)
                except Exception:
                    reaped = None
                if reaped is True:
                    return LifecycleState.STOPPED, f"Zombie child successfully reaped ({reason})"
                elif reaped is False:
                    # Zombie exists but not yet reapable (pending)
                    return LifecycleState.ZOMBIE_PENDING, f"Zombie child reap pending ({reason})"
                else:
                    # reaped is None: reap failed (not our child or error)
                    return LifecycleState.ZOMBIE_UNREAPED, f"Zombie child reap failed ({reason})"
            return (
                LifecycleState.ZOMBIE_UNREAPED,
                f"Process entered zombie state (Z) but supervisor is not direct parent to reap it ({reason})"
            )

    # Process still alive after grace period
    return (
        LifecycleState.TERMINATION_FAILED,
        f"Process still alive after {grace_seconds}s grace period. SIGKILL prohibited by policy."
    )


# --- Readiness Evaluation ---

def _is_finite_num(v: Any) -> bool:
    return type(v) in (int, float) and math.isfinite(v)


def evaluate_readiness(
    boot_completed: Any, mem_available_kb: Any, swap_free_kb: Any,
    probe_status: ProbeStatus, identity_verified: bool, baseline_established: bool,
    target: Optional[ProcessIdentity] = None, clock: Optional[Clock] = None,
    max_stale_sec: float = DEFAULT_MAX_STALE_PROBE_SEC,
    last_probe_mono: Optional[float] = None, probe_state: Optional[_ProbeState] = None
) -> Tuple[bool, Dict[str, Any]]:
    """Aggregate readiness across boot completion, memory thresholds, probes, and identity."""
    raw_boot = getattr(boot_completed, "boot_completed", boot_completed)
    details: Dict[str, Any] = {
        "boot_completed": raw_boot,
        "mem_available_kb": mem_available_kb,
        "swap_free_kb": swap_free_kb,
        "probe_status": probe_status.value if hasattr(probe_status, "value") else str(probe_status),
        "identity_verified": identity_verified,
        "baseline_established": baseline_established
    }
    if type(raw_boot) is not bool or not raw_boot:
        details.update({"status": "not_ready", "reason": "Boot sequence incomplete or non-boolean"})
        return False, details
    if target is not None:
        if not isinstance(boot_completed, BootEvidence):
            details.update({"status": "not_ready", "reason": "Boot evidence missing target process identity"})
            return False, details
        if (type(boot_completed.pid) is not int or type(boot_completed.start_ticks) is not int
                or (boot_completed.uid is not None and type(boot_completed.uid) is not int)
                or boot_completed.pid != target.pid or boot_completed.start_ticks != target.start_ticks
                or (boot_completed.avd is not None and (type(boot_completed.avd) is not str or boot_completed.avd != target.avd))
                or (boot_completed.uid is not None and boot_completed.uid != target.uid)):
            details.update({"status": "not_ready", "reason": "Boot evidence target mismatch"})
            return False, details
    if clock is not None and isinstance(boot_completed, BootEvidence):
        now = clock.monotonic()
        b_mono = boot_completed.timestamp_mono
        if (not _is_finite_num(now) or not _is_finite_num(b_mono)
                or b_mono > now or (now - b_mono) > max_stale_sec or (now - b_mono) < 0.0):
            details.update({"status": "not_ready", "reason": "Boot evidence stale"})
            return False, details
    if not identity_verified:
        details.update({"status": "not_ready", "reason": "Process identity verification pending or mismatched"})
        return False, details
    if not baseline_established:
        details.update({"status": "not_ready", "reason": "ANR health baseline not established"})
        return False, details
    mem_ok, mem_reason = validate_memory_limits(mem_available_kb, swap_free_kb)
    if not mem_ok:
        details.update({"status": "not_ready", "reason": f"Memory capacity failure: {mem_reason}"})
        return False, details
    if probe_state == _ProbeState.RUNNING:
        details.update({"status": "not_ready", "reason": "System probe pending"})
        return False, details
    if clock is not None and last_probe_mono is not None:
        now = clock.monotonic()
        if not _is_finite_num(now) or not _is_finite_num(last_probe_mono):
            details.update({"status": "not_ready", "reason": "Health probe stale"})
            return False, details
        p_elapsed = now - last_probe_mono
        if p_elapsed < 0.0 or p_elapsed > max_stale_sec:
            details.update({"status": "not_ready", "reason": "Health probe stale"})
            return False, details
    if probe_status != ProbeStatus.CLEAR:
        details.update({"status": "not_ready", "reason": f"System probe unhealthy: {probe_status.value}"})
        return False, details
    details.update({"status": "ready", "reason": "All preflight and capacity checks satisfied"})
    return True, details


# --- Integrated Supervisor Engine ---

# Probe lifecycle states for non-blocking probe management
class _ProbeState(str, Enum):
    IDLE = "idle"
    RUNNING = "running"
    COMPLETED = "completed"


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
    def __init__(self, cmd: List[str], max_bytes: int = MAX_PROBE_OUTPUT_BYTES):
        if not (hasattr(os, "pidfd_open") and hasattr(signal, "pidfd_send_signal")):
            raise RuntimeError("pidfd signaling required but not supported on platform")

        self._max_bytes = max_bytes
        # Each pipe (stdout, stderr) is drained up to 16 chunks per pass; across
        # two pipes and up to two passes per poll, at most 64 non-blocking read
        # calls can occur per poll cycle.
        self._max_chunks_per_poll = 16
        self._chunk_size = 16384
        self._out_buf = bytearray()
        self._err_buf = bytearray()
        self._eof_out = False
        self._eof_err = False
        self._overflow = False
        self._io_error: Optional[str] = None
        self.descendant_pipe_open = False
        self._construction_error: Optional[str] = None
        self._close_failed: bool = False
        self._close_error: Optional[str] = None

        self._proc: Optional[subprocess.Popen] = None
        self._pidfd: Optional[int] = None
        self._r_out: Optional[int] = None
        self._r_err: Optional[int] = None

        r_out = w_out = r_err = w_err = pidfd = None
        proc = None
        try:
            r_out, w_out = os.pipe()
            r_err, w_err = os.pipe()
            os.set_blocking(r_out, False)
            os.set_blocking(r_err, False)
            proc = subprocess.Popen(
                cmd,
                stdout=w_out,
                stderr=w_err,
                shell=False,
                close_fds=True
            )
            os.close(w_out)
            w_out = None
            os.close(w_err)
            w_err = None
            self._proc = proc
            try:
                pidfd = os.pidfd_open(proc.pid, 0)
                self._pidfd = pidfd
                self._r_out = r_out
                self._r_err = r_err
            except Exception as e:
                # Close read ends and retain child ownership via construction-error-carrying handle
                for fd in (r_out, r_err):
                    if isinstance(fd, int):
                        try:
                            os.close(fd)
                        except Exception:
                            pass
                self._construction_error = f"pidfd_open failed: {e}"
        except Exception:
            for fd in (w_out, w_err, r_out, r_err, pidfd):
                if isinstance(fd, int):
                    try:
                        os.close(fd)
                    except Exception:
                        pass
            if proc is not None:
                self._proc = proc
                self._construction_error = "subprocess spawned but setup failed"
                return
            raise

    def _drain_pipes(self, max_chunks: int = 16) -> None:
        for fd_attr, buf_attr, eof_attr in (("_r_out", "_out_buf", "_eof_out"), ("_r_err", "_err_buf", "_eof_err")):
            fd = getattr(self, fd_attr)
            if fd is None or getattr(self, eof_attr):
                continue
            buf = getattr(self, buf_attr)
            for _ in range(max_chunks):
                try:
                    chunk = os.read(fd, self._chunk_size)
                    if not chunk:
                        setattr(self, eof_attr, True)
                        break
                    if len(self._out_buf) + len(self._err_buf) + len(chunk) > self._max_bytes:
                        self._overflow = True
                        remain = max(0, self._max_bytes - (len(self._out_buf) + len(self._err_buf)))
                        buf.extend(chunk[:remain])
                        self.terminate()
                        break
                    buf.extend(chunk)
                except (BlockingIOError, InterruptedError):
                    break
                except Exception as e:
                    self._io_error = f"read error on fd {fd}: {e}"
                    self.terminate()
                    break

    def poll(self) -> Optional[int]:
        if self._proc is None:
            return None
        self._drain_pipes(max_chunks=self._max_chunks_per_poll)
        rc = self._proc.poll()
        if rc is not None:
            self._drain_pipes(max_chunks=self._max_chunks_per_poll)
            if not (self._eof_out and self._eof_err):
                self.descendant_pipe_open = True
        return rc

    def read_available(self, max_bytes: int) -> Tuple[str, str]:
        return (
            self._out_buf[:max_bytes].decode("utf-8", errors="replace"),
            self._err_buf[:max_bytes].decode("utf-8", errors="replace")
        )

    def terminate(self) -> None:
        if self._pidfd is not None and self._proc is not None:
            if self._proc.poll() is None:
                try:
                    signal.pidfd_send_signal(self._pidfd, signal.SIGTERM)
                except Exception:
                    pass

    def cleanup(self) -> bool:
        for attr in ("_r_out", "_r_err", "_pidfd"):
            fd = getattr(self, attr, None)
            if isinstance(fd, int):
                setattr(self, attr, None)
                try:
                    os.close(fd)
                except Exception as e:
                    self._close_failed = True
                    self._close_error = f"close failed on fd {fd}: {e}"
        if self._close_failed:
            return False
        if self._proc is not None:
            return self._proc.poll() is not None
        return True


class AdbProbeAdapter:
    """Non-blocking ADB health probe adapter for Android emulator supervision."""
    def __init__(
        self,
        serial: str = DEFAULT_ADB_SERIAL,
        command: Optional[List[str]] = None,
        commands: Optional[List[List[str]]] = None,
        transport: Optional[Callable[[List[str]], ProbeCommandHandle]] = None,
        clock: Optional[Clock] = None,
        timeout_sec: float = DEFAULT_MAX_STALE_PROBE_SEC,
        max_output_bytes: int = MAX_PROBE_OUTPUT_BYTES,
    ):
        self._serial = serial
        if command is not None:
            self._commands = [list(command)]
        elif commands is not None:
            self._commands = [list(c) for c in commands]
        else:
            self._commands = [
                ["adb", "-s", self._serial, "shell", "dumpsys", "activity", "processes"],
                ["adb", "-s", self._serial, "shell", "dumpsys", "activity", "last-anr"]
            ]
        self._transport = transport or SubprocessProbeHandle
        self._clock = clock or SystemClock()
        self._timeout_sec = min(float(timeout_sec), DEFAULT_MAX_STALE_PROBE_SEC)
        self._max_output_bytes = int(max_output_bytes)

        self._current_handle: Optional[ProbeCommandHandle] = None
        self._start_mono: float = 0.0
        self._start_error: Optional[str] = None
        self._pending_reap: List[ProbeCommandHandle] = []
        self.cleanup_error: Optional[str] = None
        self._cmd_idx: int = 0
        self._collected_stdout: List[str] = []
        self._collected_stderr: List[str] = []
        self._cleanup_failed: bool = False

    @property
    def has_pending_cleanup(self) -> bool:
        has_handle_err = False
        if self._current_handle is not None:
            has_handle_err = getattr(self._current_handle, "_construction_error", None) is not None
        return (
            len(self._pending_reap) > 0
            or self.cleanup_error is not None
            or self._cleanup_failed
            or has_handle_err
            or self._start_error is not None
        )

    def start_probe(self) -> None:
        """Start non-blocking probe execution."""
        if self._current_handle is not None:
            if getattr(self._current_handle, "poll", lambda: 0)() is None:
                self._start_error = "Previous child process still pending exit/reap"
                return
            self._cancel_handle(self._current_handle)
            self._current_handle = None

        if self._pending_reap:
            self.reap_pending()
            for h in self._pending_reap:
                if getattr(h, "poll", lambda: 0)() is None:
                    self._start_error = "Previous child process still pending exit/reap"
                    return

        self._start_error = None
        self._start_mono = self._clock.monotonic()
        self._cmd_idx = 0
        self._collected_stdout = []
        self._collected_stderr = []
        self._cleanup_failed = False
        try:
            self._current_handle = self._transport(list(self._commands[0]))
        except Exception as e:
            self._start_error = f"Probe transport start failed: {e}"
            self._current_handle = None

    def _cancel_handle(self, handle: ProbeCommandHandle) -> None:
        try:
            handle.terminate()
        except Exception:
            pass
        try:
            if not handle.cleanup():
                self._pending_reap.append(handle)
                self._cleanup_failed = True
            if getattr(handle, "_close_error", None):
                self.cleanup_error = handle._close_error
                self._cleanup_failed = True
        except Exception as e:
            self.cleanup_error = f"Handle cleanup exception: {e}"
            self._pending_reap.append(handle)
            self._cleanup_failed = True

    def reap_pending(self) -> int:
        """Attempt non-blocking reap of pending handles without busy-waiting."""
        remaining: List[ProbeCommandHandle] = []
        for h in self._pending_reap:
            try:
                if not h.cleanup():
                    remaining.append(h)
                if getattr(h, "_close_error", None):
                    self.cleanup_error = h._close_error
            except Exception as e:
                self.cleanup_error = f"Pending reap exception: {e}"
                remaining.append(h)
        self._pending_reap = remaining
        if not self._pending_reap and self.cleanup_error is None:
            self._cleanup_failed = False
        return len(self._pending_reap)

    def cancel(self) -> bool:
        """Cancel current probe and reap pending handles honestly. Returns True if fully clean."""
        if self._current_handle is not None:
            self._cancel_handle(self._current_handle)
            self._current_handle = None
        self.reap_pending()
        return not self.has_pending_cleanup

    def poll_probe(self) -> Tuple[bool, Optional[Tuple[int, str, str, bool]]]:
        """Poll in-flight probe non-blockingly.

        Returns (done: bool, Optional[(retcode, stdout, stderr, is_timeout)]).
        """
        if self._pending_reap:
            self.reap_pending()

        if self._start_error is not None:
            err = self._start_error
            self._start_error = None
            return True, (1, "", err, False)

        if self._current_handle is None:
            return False, None

        now = self._clock.monotonic()
        elapsed = now - self._start_mono

        # Check deadline across entire probe cycle
        if elapsed > self._timeout_sec:
            handle = self._current_handle
            self._current_handle = None
            self._cancel_handle(handle)
            return True, (1, "", f"ADB probe deadline exceeded ({elapsed:.1f}s > {self._timeout_sec}s)", True)

        try:
            retcode = self._current_handle.poll()
        except Exception as e:
            handle = self._current_handle
            self._current_handle = None
            self._cancel_handle(handle)
            return True, (1, "", f"Probe poll failed: {e}", False)

        # Immediate overflow surface without waiting
        if getattr(self._current_handle, "_overflow", False):
            handle = self._current_handle
            self._current_handle = None
            self._cancel_handle(handle)
            return True, (1, "", "Probe output truncated exceeding maximum bounds", False)

        # Immediate I/O error surface without waiting
        if getattr(self._current_handle, "_io_error", None):
            err = getattr(self._current_handle, "_io_error")
            handle = self._current_handle
            self._current_handle = None
            self._cancel_handle(handle)
            return True, (1, "", f"Probe I/O error: {err}", False)

        # Check construction error on handle: surface immediately even if child pending
        if getattr(self._current_handle, "_construction_error", None):
            err = self._current_handle._construction_error
            handle = self._current_handle
            self._current_handle = None
            self._cancel_handle(handle)
            return True, (1, "", f"Probe construction failure: {err}", False)

        if retcode is None:
            return False, None

        # Handle finished! Check descendant pipe open
        if getattr(self._current_handle, "descendant_pipe_open", False):
            handle = self._current_handle
            self._current_handle = None
            self._cancel_handle(handle)
            return True, (1, "", "Probe descendant pipe held open without EOF", False)

        handle = self._current_handle
        self._current_handle = None
        try:
            stdout, stderr = handle.read_available(self._max_output_bytes)
        except Exception as e:
            self._cancel_handle(handle)
            return True, (1, "", f"Probe output read failed: {e}", False)

        try:
            if not handle.cleanup():
                self._pending_reap.append(handle)
                self._cleanup_failed = True
            if getattr(handle, "_close_error", None):
                self.cleanup_error = handle._close_error
                self._cleanup_failed = True
        except Exception as e:
            self.cleanup_error = f"Handle cleanup exception: {e}"
            self._pending_reap.append(handle)
            self._cleanup_failed = True

        if retcode != 0:
            return True, (retcode, stdout, stderr or f"Command failed with code {retcode}", False)

        # Check aggregate buffer limits across commands before section header checks
        tot_bytes = sum(len(s.encode("utf-8", errors="replace")) for s in self._collected_stdout) + \
                    len(stdout.encode("utf-8", errors="replace")) + \
                    sum(len(s.encode("utf-8", errors="replace")) for s in self._collected_stderr) + \
                    len(stderr.encode("utf-8", errors="replace"))
        if tot_bytes >= self._max_output_bytes:
            return True, (1, "\n".join(self._collected_stdout + [stdout]), "Probe output truncated exceeding maximum bounds", False)

        # Validate dump contract before concat
        cmd_args = self._commands[self._cmd_idx]
        cmd_str = " ".join(cmd_args)
        if "processes" in cmd_str:
            if not re.search(r"ACTIVITY MANAGER RUNNING PROCESSES", stdout, re.IGNORECASE):
                return True, (1, stdout, "processes dump missing required section header", False)
        if "last-anr" in cmd_str:
            is_valid, kind_or_err = _validate_last_anr_content(stdout)
            if not is_valid:
                return True, (1, stdout, f"last-anr dump validation failed: {kind_or_err}", False)

        self._collected_stdout.append(stdout)
        if stderr:
            self._collected_stderr.append(stderr)

        # Check if cleanup failure poisoned healthy result
        if self._cleanup_failed:
            err_msg = self.cleanup_error or "Handle cleanup pending or failed"
            return True, (1, "\n".join(self._collected_stdout), f"Probe cleanup error: {err_msg}", False)

        # More commands in sequence?
        self._cmd_idx += 1
        if self._cmd_idx < len(self._commands):
            try:
                self._current_handle = self._transport(list(self._commands[self._cmd_idx]))
            except Exception as e:
                self._cleanup_failed = True
                return True, (1, "", f"Probe transport start failed for step {self._cmd_idx}: {e}", False)
            return False, None

        full_stdout = "\n".join(self._collected_stdout)
        full_stderr = "\n".join(self._collected_stderr)
        return True, (0, full_stdout, full_stderr, False)


class SupervisorEngine:
    """Bounded, fail-closed supervisor orchestrating memory sampling, TTL, and health probes.

    Design:
    - Memory sampling runs on the injected cadence (default 5s) independent of
      any in-flight probe. A blocked/slow probe never delays RAM/Swap checks.
    - Probes use a non-blocking start/poll/deadline model:
      * start_probe(): injected callable that returns immediately (fires probe)
      * poll_probe():  injected callable that returns (done: bool, result | None)
      * probe_deadline_sec: maximum wall-time a single probe may run before
        the engine declares it overdue and stops.
    - Initial probe status is UNKNOWN (not CLEAR): freshness must be earned by
      at least one successful probe result before the engine trusts probe state.
    - TTL is rechecked after every probe operation so long probes cannot extend
      the lifetime past the monotonic deadline.
    - Integration adapter limitation: start_probe/poll_probe are stubs in the
      offline test harness. Full ADB subprocess integration requires a runtime
      adapter not included in this offline-only correction.
    """
    def __init__(
        self,
        target: ProcessIdentity,
        controller: ProcessController,
        clock: Optional[Clock] = None,
        mem_reader: Optional[Callable[[], Tuple[int, int]]] = None,
        probe_runner: Optional[Callable[[], Tuple[int, str, str, bool]]] = None,
        start_probe: Optional[Callable[[], None]] = None,
        poll_probe: Optional[Callable[[], Tuple[bool, Optional[Tuple[int, str, str, bool]]]]] = None,
        cadence_sec: float = DEFAULT_CADENCE_SEC,
        ttl_sec: float = DEFAULT_TTL_SEC,
        stale_probe_sec: float = DEFAULT_MAX_STALE_PROBE_SEC,
        probe_deadline_sec: float = DEFAULT_MAX_STALE_PROBE_SEC,
        grace_sec: float = DEFAULT_GRACE_SEC,
        poll_interval: float = 0.5,
        baseline_events: Optional[Set[ANREvent]] = None,
        cleanup_probe: Optional[Callable[[], None]] = None,
        adapter: Optional[AdbProbeAdapter] = None,
        boot_evidence: Optional[Union[BootEvidence, Callable[[], Optional[BootEvidence]]]] = None,
        baseline_established: bool = True
    ):
        self._target = target
        self._controller = controller
        self._clock = clock or SystemClock()
        self._mem_reader = mem_reader
        self._cadence_sec = float(cadence_sec)
        self._ttl_sec = float(ttl_sec)
        self._stale_probe_sec = float(stale_probe_sec)
        self._probe_deadline_sec = float(probe_deadline_sec)
        self._grace_sec = float(grace_sec)
        self._poll_interval = float(poll_interval)
        self._baseline_events = baseline_events or set()
        self.boot_evidence = boot_evidence
        self.baseline_established: bool = baseline_established
        self.is_ready: bool = False
        self.readiness_details: Dict[str, Any] = {}
        self._last_mem_sample: Tuple[Optional[int], Optional[int]] = (None, None)

        self._adapter = adapter
        if self._adapter is not None:
            if start_probe is None:
                start_probe = self._adapter.start_probe
            if poll_probe is None:
                poll_probe = self._adapter.poll_probe
            if cleanup_probe is None:
                cleanup_probe = self._adapter.cancel

        self._cleanup_probe = cleanup_probe
        self.probe_cleanup_error: Optional[str] = None
        self.has_probe_cleanup_failure: bool = False

        # Non-blocking probe callables (preferred over legacy probe_runner)
        self._start_probe = start_probe
        self._poll_probe = poll_probe
        # Legacy synchronous probe_runner: wrapped into start/poll if no
        # non-blocking callables are provided
        self._probe_runner = probe_runner
        self._probe_state: _ProbeState = _ProbeState.IDLE
        self._probe_start_mono: float = 0.0
        self._probe_sync_result: Optional[Tuple[int, str, str, bool]] = None

        self.ttl_tracker = MonotonicTTLTracker(ttl_seconds=self._ttl_sec, clock=self._clock)
        self.state: LifecycleState = LifecycleState.RUNNING
        self.stop_reason: Optional[str] = None
        self._attempt_record: Optional[TerminationAttemptRecord] = None; self._claimed: bool = False
        # Initial probe status UNKNOWN: freshness must be earned
        self.last_probe_status: ProbeStatus = ProbeStatus.UNKNOWN
        self.last_probe_mono: float = self._clock.monotonic()
        self._last_mem_sample_mono: float = self._clock.monotonic()
        self._first_step: bool = True

    def record_probe_result(self, status: ProbeStatus, monotonic_timestamp: float) -> None:
        self.last_probe_status = status
        self.last_probe_mono = monotonic_timestamp

    def claim(self, target: Any, controller: Any) -> TerminationClaimStatus:
        _si = lambda v: isinstance(v, int) and type(v) is not bool
        if not (_si(getattr(target, "pid", None)) and _si(getattr(target, "start_ticks", None)) and _si(getattr(target, "uid", None))):
            return TerminationClaimStatus.MISMATCH
        rt, rc = (self._attempt_record, self._attempt_record.controller) if self._attempt_record else (self._target, self._controller)
        if (target.pid != rt.pid or target.start_ticks != rt.start_ticks or target.uid != rt.uid or getattr(target, "avd", None) != rt.avd or controller is not rc):
            return TerminationClaimStatus.MISMATCH
        if self._attempt_record is None: return TerminationClaimStatus.NOT_ATTEMPTED
        if self._attempt_record.outcome is None: return TerminationClaimStatus.IN_PROGRESS
        if self._claimed: return TerminationClaimStatus.ALREADY_CLAIMED
        self._claimed = True; return TerminationClaimStatus.CLAIMED

    claim_termination, attempt_record = claim, property(lambda self: self._attempt_record)
    termination_attempt, export_claim_snapshot = attempt_record, lambda self: self._attempt_record
    termination_outcome = property(lambda self: self._attempt_record.outcome if self._attempt_record else None)

    def check_readiness(self) -> Tuple[bool, Dict[str, Any]]:
        id_ok, _ = verify_process_identity(self._target, self._controller)
        mem_avail, swap_free = self._last_mem_sample
        if mem_avail is None and self._mem_reader:
            try:
                mem_avail, swap_free = self._mem_reader()
                self._last_mem_sample = (mem_avail, swap_free)
            except Exception:
                mem_avail, swap_free = None, None
        boot_ev = self.boot_evidence() if callable(self.boot_evidence) else self.boot_evidence
        ready, details = evaluate_readiness(
            boot_completed=boot_ev, mem_available_kb=mem_avail, swap_free_kb=swap_free,
            probe_status=self.last_probe_status, identity_verified=id_ok,
            baseline_established=self.baseline_established, target=self._target, clock=self._clock,
            max_stale_sec=self._stale_probe_sec, last_probe_mono=self.last_probe_mono,
            probe_state=self._probe_state
        )
        self.is_ready = ready
        self.readiness_details = details
        return ready, details

    def _cleanup_probe_teardown(self) -> None:
        """Central teardown for active/pending probes across all halt paths."""
        if self._cleanup_probe is not None:
            try:
                res = self._cleanup_probe()
                if res is False:
                    self.has_probe_cleanup_failure = True
                    if self.probe_cleanup_error is None:
                        self.probe_cleanup_error = "cleanup_probe returned False (cleanup pending or failed)"
            except BaseException as e:  # diagnostic error; only interrupts escape
                self.probe_cleanup_error = f"probe_cleanup exception: {type(e).__name__}{': ' + str(e) if str(e) else ''}"
                self.has_probe_cleanup_failure = True
                if isinstance(e, (KeyboardInterrupt, SystemExit)):
                    raise
        if self._adapter is not None:
            if getattr(self._adapter, "has_pending_cleanup", False):
                self.has_probe_cleanup_failure = True
                if getattr(self._adapter, "cleanup_error", None):
                    self.probe_cleanup_error = self._adapter.cleanup_error
                elif self.probe_cleanup_error is None:
                    self.probe_cleanup_error = f"Pending un-reaped probe handles ({len(getattr(self._adapter, '_pending_reap', []))})"

    def _terminate(self, reason: str) -> Tuple[bool, LifecycleState]:
        """Issue safe_sigterm and record the stop."""
        if self._attempt_record is not None: return True, self.state
        self.stop_reason = reason
        self._attempt_record = TerminationAttemptRecord(self._target.pid, self._target.start_ticks, self._target.uid, self._target.avd, self._controller, target=self._target)
        term_state, teardown_exc, sig_exc, ctrl_exc, abort_exc, completed = LifecycleState.ERROR, None, None, None, None, False
        try:
            try:
                self._cleanup_probe_teardown()
            except BaseException as te:
                teardown_exc = te
            finally:
                try:
                    term_state, _ = safe_sigterm(
                        self._target, self._controller, reason=reason,
                        grace_seconds=self._grace_sec, poll_interval=self._poll_interval
                    )
                except BaseException as se: sig_exc = se
                finally:
                    if hasattr(self._controller, "cleanup_bound_pidfd"):
                        try:
                            c_err = self._controller.cleanup_bound_pidfd()
                            if c_err is not None and (c_err is False or isinstance(c_err, BaseException)):
                                self.has_probe_cleanup_failure = True
                                self.probe_cleanup_error = f"Controller cleanup failed: {c_err}"
                        except BaseException as ce:
                            ctrl_exc = ce
                            self.has_probe_cleanup_failure = True
                            self.probe_cleanup_error = f"Controller cleanup exception: {ce}"

            propagate = teardown_exc if teardown_exc is not None else (sig_exc if sig_exc is not None else (
                ctrl_exc if ctrl_exc is not None and isinstance(ctrl_exc, (KeyboardInterrupt, SystemExit)) else None
            ))
            if propagate is not None: raise propagate

            self.primary_state = term_state
            self.termination_state = term_state
            if self.has_probe_cleanup_failure:
                self.state = LifecycleState.ERROR
                if self.probe_cleanup_error is None:
                    self.probe_cleanup_error = "Probe teardown pending or un-reaped child handles remain"
                self.stop_reason = f"{reason} (cleanup failed: {self.probe_cleanup_error})"
            else:
                self.state = term_state

            completed = True; return True, self.state
        except BaseException as be:
            abort_exc = be
            if getattr(self, "primary_error", None) is None: self.primary_error = be
            self.state = self.termination_state = LifecycleState.ERROR; raise
        finally:
            if self._attempt_record is not None and self._attempt_record.outcome is None:
                self._attempt_record = replace(self._attempt_record, outcome=TerminationOutcome(
                    state=self.state, detail=self.stop_reason or (f"Aborted: {abort_exc}" if abort_exc else reason),
                    primary_error=getattr(self, "primary_error", None) or abort_exc, completed=completed
                ))

    def _start_probe_cycle(self) -> None:
        """Begin a new (non-blocking) probe cycle."""
        now = self._clock.monotonic()
        if self._start_probe is not None:
            self._start_probe()
            self._probe_state = _ProbeState.RUNNING
            self._probe_start_mono = now
        elif self._probe_runner is not None:
            # Legacy synchronous: run immediately, store result
            try:
                self._probe_sync_result = self._probe_runner()
            except Exception:
                self._probe_sync_result = None
            self._probe_state = _ProbeState.COMPLETED
            self._probe_start_mono = now

    def _poll_probe_cycle(self) -> Optional[Tuple[int, str, str, bool]]:
        """Poll a running probe. Returns result tuple if done, None if still running."""
        if self._probe_state == _ProbeState.COMPLETED:
            self._probe_state = _ProbeState.IDLE
            result = self._probe_sync_result
            self._probe_sync_result = None
            return result
        if self._poll_probe is not None and self._probe_state == _ProbeState.RUNNING:
            done, result = self._poll_probe()
            if done:
                self._probe_state = _ProbeState.IDLE
                return result
        return None

    def step(self) -> Tuple[bool, LifecycleState]:
        """Perform one discrete supervisor step.

        Returns (halted: bool, state: LifecycleState).
        Memory sampling and probe polling are decoupled: memory always runs
        on cadence, probes are started/polled independently.
        """
        now = self._clock.monotonic()

        # 1. TTL check (monotonic) — always first
        if self.ttl_tracker.is_expired():
            return self._terminate(f"Supervision TTL expired ({self._ttl_sec}s monotonic)")

        # 2. Target identity check
        id_ok, id_msg = verify_process_identity(self._target, self._controller)
        if not id_ok:
            return self._terminate(f"Target identity failure: {id_msg}")

        # 3. Memory sampling on cadence (independent of probe state)
        if self._mem_reader:
            mem_due = (now - self._last_mem_sample_mono) >= self._cadence_sec
            # Also sample on first step to establish baseline reading
            if mem_due or self._first_step:
                try:
                    mem_avail, swap_free = self._mem_reader()
                    self._last_mem_sample = (mem_avail, swap_free)
                except Exception as e:
                    return self._terminate(f"Memory read/parse failure: {e}")
                self._last_mem_sample_mono = now

                mem_ok, mem_msg = validate_memory_limits(mem_avail, swap_free)
                if not mem_ok:
                    return self._terminate(f"Memory threshold violation: {mem_msg}")

        # 4. Probe lifecycle: start if idle, poll if running, check deadline
        if self._start_probe is not None or self._probe_runner is not None:
            try:
                if self._probe_state == _ProbeState.IDLE:
                    self._start_probe_cycle()

                if self._probe_state in (_ProbeState.RUNNING, _ProbeState.COMPLETED):
                    was_running = self._probe_state == _ProbeState.RUNNING
                    orig_start = self._probe_start_mono
                    result = self._poll_probe_cycle()
                    now_probe = self._clock.monotonic()
                    if was_running and (now_probe - orig_start) > self._probe_deadline_sec:
                        return self._terminate(
                            f"Health probe overrun (>{self._probe_deadline_sec}s without completion)")
                    if result is not None:
                        retcode, stdout, stderr, is_timeout = result
                        status, p_reason, _ = evaluate_adb_probe_result(
                            returncode=retcode,
                            stdout=stdout,
                            stderr=stderr,
                            is_timeout=is_timeout,
                            baseline_events=self._baseline_events
                        )
                        self.record_probe_result(status, now_probe)
            except Exception as e:
                return self._terminate(f"Health probe execution failure: {e}")

        # 4b. Recheck TTL after probe operations (probe may have consumed time)
        if self.ttl_tracker.is_expired():
            return self._terminate(f"Supervision TTL expired after probe ({self._ttl_sec}s monotonic)")

        # 5. Stale probe check (freshness window)
        now_after = self._clock.monotonic()
        if self.last_probe_status != ProbeStatus.UNKNOWN and (now_after - self.last_probe_mono) > self._stale_probe_sec:
            return self._terminate(
                f"Health probe stale (>{self._stale_probe_sec}s without fresh update)")

        # 6. Probe failure check (ANR, probe error, or unknown)
        if self.last_probe_status in (ProbeStatus.ANR_DETECTED, ProbeStatus.PROBE_ERROR):
            return self._terminate(f"Health probe failure: {self.last_probe_status.value}")
        if self.last_probe_status == ProbeStatus.UNKNOWN and self._probe_state != _ProbeState.RUNNING:
            return self._terminate(f"Health probe failure: {self.last_probe_status.value}")

        if not self.is_ready:
            self.check_readiness()

        self._first_step = False
        return False, self.state


class SupervisorScheduler:
    """Anchored-grid pacing scheduler driving SupervisorEngine to halt."""
    def __init__(self, engine: SupervisorEngine, poll_interval: Optional[float] = None, clock: Optional[Clock] = None, sleeper: Optional[Callable[[float], None]] = None):
        pi = getattr(engine, "_poll_interval", 0.5) if poll_interval is None else poll_interval
        if isinstance(pi, bool) or not isinstance(pi, (int, float)) or not math.isfinite(pi) or pi <= 0:
            raise ValueError(f"poll_interval must be finite positive number: {pi!r}")
        if clock is not None and clock is not getattr(engine, "_clock", None):
            raise ValueError("Conflicting clock supplied to scheduler; must share engine clock")
        if sleeper is not None and not callable(sleeper): raise TypeError(f"sleeper must be callable or None: {sleeper!r}")
        self._engine, self._interval = engine, float(pi)
        self._clock: Clock = getattr(engine, "_clock", None) if clock is None else clock
        self._sleeper: Callable[[float], None] = time.sleep if sleeper is None else sleeper
        self.state, self.termination_state = getattr(engine, "state", LifecycleState.RUNNING), None
        self.primary_error, self.secondary_error = None, None

    def _fail(self, msg: str, exc: Optional[BaseException] = None) -> LifecycleState:
        self.primary_error = getattr(self._engine, "primary_error", None) or exc
        self._engine.primary_error = self.primary_error
        self._engine.secondary_error = getattr(self._engine, "secondary_error", None)
        self._engine.state = self._engine.termination_state = self.state = self.termination_state = LifecycleState.ERROR
        try:
            self._engine._terminate(msg)
        except BaseException as ce:
            self.secondary_error = getattr(self._engine, "secondary_error", None) or ce
            self._engine.secondary_error = self.secondary_error
        finally:
            self._engine.state = self._engine.termination_state = self.state = self.termination_state = LifecycleState.ERROR
        if exc is not None: raise exc
        return LifecycleState.ERROR

    def _read_clock(self, prev: Optional[float] = None) -> float:
        val = self._clock.monotonic()
        if isinstance(val, bool) or not isinstance(val, (int, float)) or not math.isfinite(val) or (prev is not None and val < prev):
            raise RuntimeError(f"Invalid clock value: {val!r}")
        return float(val)

    def run(self) -> LifecycleState:
        try:
            t0 = self._read_clock()
        except BaseException as ce:
            return self._fail(f"Clock failure before first step: {ce}", ce)
        step_idx, last_wake = 0, t0
        while True:
            step_idx += 1
            halted, state = self._engine.step()
            self.state, self.termination_state = state, getattr(self._engine, "termination_state", state)
            if halted: return state
            try:
                now = self._read_clock(last_wake)
            except BaseException as ce:
                return self._fail(f"Clock failure after step: {ce}", ce)
            target_wake = t0 + (step_idx * self._interval)
            while target_wake <= now: step_idx += 1; target_wake = t0 + (step_idx * self._interval)
            caps = [target_wake]
            ttl_rem = getattr(self._engine.ttl_tracker, "remaining_seconds", lambda: 0.0)()
            if ttl_rem > 0: caps.append(now + ttl_rem)
            if getattr(self._engine, "_mem_reader", None) and (getattr(self._engine, "_last_mem_sample_mono", now) + getattr(self._engine, "_cadence_sec", 5.0)) > now:
                caps.append(getattr(self._engine, "_last_mem_sample_mono", now) + getattr(self._engine, "_cadence_sec", 5.0))
            if getattr(self._engine, "_probe_state", None) == _ProbeState.RUNNING and (getattr(self._engine, "_probe_start_mono", now) + getattr(self._engine, "_probe_deadline_sec", 15.0)) > now:
                caps.append(getattr(self._engine, "_probe_start_mono", now) + getattr(self._engine, "_probe_deadline_sec", 15.0))
            try:
                self._sleeper(min(caps) - now)
                post_sleep = self._read_clock(now)
                if post_sleep <= now: raise RuntimeError(f"Sleeper made no progress: {post_sleep} <= {now}")
                last_wake = post_sleep
            except BaseException as se:
                return self._fail(f"Sleeper/pacing failure: {se}", se)


# --- Backward-Compatible Decoupled Loop Wrapper ---

class DecoupledSupervisorLoop:
    """Manages independent 5s host memory checks decoupled from asynchronous probes.

    .. deprecated::
        This wrapper is retained for backward compatibility but is NOT used by
        SupervisorEngine and provides NO admission or runtime health guarantee.
        Use SupervisorEngine for supervised execution.
    """
    def __init__(
        self,
        cadence_sec: float = DEFAULT_CADENCE_SEC,
        clock: Optional[Clock] = None,
        mem_reader: Optional[Callable[[], Tuple[int, int]]] = None,
        probe_runner: Optional[Callable[[], Any]] = None,
        max_stale_probe_sec: float = DEFAULT_MAX_STALE_PROBE_SEC
    ):
        self._cadence: float = cadence_sec
        self._clock: Clock = clock or SystemClock()
        self._mem_reader: Optional[Callable[[], Tuple[int, int]]] = mem_reader
        self._probe_runner: Optional[Callable[[], Any]] = probe_runner
        self._max_stale: float = max_stale_probe_sec

        self._last_probe_status: ProbeStatus = ProbeStatus.UNKNOWN
        self._last_probe_mono: float = self._clock.monotonic()

    def record_probe_result(self, status: ProbeStatus, monotonic_timestamp: float) -> None:
        self._last_probe_status = status
        self._last_probe_mono = monotonic_timestamp

    def tick(self) -> Tuple[int, int]:
        """Perform one memory sample tick."""
        if self._mem_reader:
            return self._mem_reader()
        return 0, 0

    def check_health(self) -> Tuple[bool, str]:
        """Verify whether supervisor must stop due to stale probes or errors."""
        now = self._clock.monotonic()
        if (now - self._last_probe_mono) > self._max_stale:
            return True, f"Health probe result stale (>{self._max_stale}s without fresh update)"

        if self._last_probe_status in (ProbeStatus.ANR_DETECTED, ProbeStatus.PROBE_ERROR, ProbeStatus.UNKNOWN):
            return True, f"Health probe failure: {self._last_probe_status.value}"

        return False, "Supervisor loop healthy"


# --- Emulator Launcher Preparation (I2) ---

@dataclass(frozen=True)
class LaunchResult:
    """Explicit descriptor of emulator launch attempt outcome."""
    success: bool
    identity: Optional[ProcessIdentity] = None
    pidfd: Optional[int] = None
    child_handle: Optional[Any] = None
    error: Optional[str] = None
    is_direct_child: bool = False
    pending_cleanup: bool = False
    transferred: bool = False


class EmulatorLauncher:
    """Bounded, fail-closed launcher preparation for Android emulator verification."""
    def __init__(
        self,
        explicit_opt_in: bool = False,
        mem_reader: Optional[Callable[[], Tuple[int, int]]] = None,
        controller: Optional[ProcessController] = None,
        target_uid: int = TARGET_UID,
        avd_name: str = TARGET_AVD,
        port: int = TARGET_PORT,
        emulator_binary: str = DEFAULT_EMULATOR_PATH,
        binary_validator: Optional[Callable[[str], bool]] = None,
        availability_check: Optional[Callable[[str, int], bool]] = None,
        spawner: Optional[Callable[[List[str]], Any]] = None,
    ):
        self._opt_in = explicit_opt_in
        self._mem_reader = mem_reader
        self._controller = controller or ProcessController()
        self._target_uid = target_uid
        self._avd_name = avd_name
        self._port = port
        self._binary = emulator_binary
        self._binary_validator = binary_validator
        self._availability_check = availability_check
        self._spawner = spawner
        self._attempted = False
        self._pending_child: Optional[Any] = None
        self._pending_pidfd: Optional[int] = None
        self._last_result: Optional[LaunchResult] = None

    def get_frozen_argv(self) -> List[str]:
        return [
            self._binary, "-avd", self._avd_name, "-port", str(self._port),
            "-memory", "1536", "-cores", "2",
            "-no-window", "-no-audio", "-no-snapshot-load", "-no-snapshot-save"
        ]

    def launch(self) -> LaunchResult:
        if self._attempted:
            if self._last_result is not None and getattr(self._last_result, "transferred", False):
                return LaunchResult(
                    success=False, child_handle=self._last_result.child_handle,
                    transferred=True, pending_cleanup=False,
                    error="Single-use launch attempt already consumed; process ownership transferred to controller"
                )
            if self._last_result is not None and self._last_result.pending_cleanup:
                return LaunchResult(
                    success=False, pidfd=self._last_result.pidfd,
                    child_handle=self._last_result.child_handle, pending_cleanup=True,
                    error="Single-use launch attempt already consumed; refusing respawn (pending child retained)"
                )
            return LaunchResult(success=False, error="Single-use launch attempt already consumed; refusing respawn")
        self._attempted = True

        if self._opt_in is not True:
            return LaunchResult(success=False, error="Explicit opt-in required; launch refused")
        if self._avd_name != TARGET_AVD:
            return LaunchResult(success=False, error=f"AVD '{self._avd_name}' != immutable '{TARGET_AVD}'")
        if self._port != TARGET_PORT:
            return LaunchResult(success=False, error=f"Port {self._port} != immutable {TARGET_PORT}")
        if self._target_uid != TARGET_UID:
            return LaunchResult(success=False, error=f"Target UID {self._target_uid} != required {TARGET_UID}")

        if getattr(self._controller, "use_pidfd", False) is not True:
            return LaunchResult(success=False, error="Controller does not support or have pidfd enabled")
        if not (hasattr(self._controller, "pidfd_open") and hasattr(self._controller, "pidfd_send_signal")):
            return LaunchResult(success=False, error="Controller lacks required pidfd_open/pidfd_send_signal capabilities")

        if self._mem_reader is None:
            return LaunchResult(success=False, error="Missing memory reader for launch preflight")
        try:
            mem_avail, swap_free = self._mem_reader()
        except Exception as e:
            return LaunchResult(success=False, error=f"Memory preflight read failed: {e}")

        if type(mem_avail) is not int or type(swap_free) is not int or mem_avail < 0 or swap_free < 0:
            return LaunchResult(success=False, error="Memory metrics must be strict non-negative integer kB")
        if mem_avail < LAUNCH_RAM_MIN_KB:
            return LaunchResult(success=False, error=f"Available memory {mem_avail} kB below launch minimum {LAUNCH_RAM_MIN_KB} kB")
        if swap_free < LAUNCH_SWAP_MIN_KB:
            return LaunchResult(success=False, error=f"Free swap {swap_free} kB below launch minimum {LAUNCH_SWAP_MIN_KB} kB")

        if self._availability_check is None:
            return LaunchResult(success=False, error="Mandatory availability_check required but missing")
        try:
            if self._availability_check(self._avd_name, self._port) is not True:
                return LaunchResult(success=False, error=f"AVD '{self._avd_name}' or port {self._port} occupied or unverified")
        except Exception as e:
            return LaunchResult(success=False, error=f"Availability check exception: {e}")

        if not os.path.isabs(self._binary):
            return LaunchResult(success=False, error=f"Emulator binary must be absolute: {self._binary}")
        if os.path.normpath(self._binary) != self._binary:
            return LaunchResult(success=False, error=f"Emulator binary must be normalized without traversal: {self._binary}")
        try:
            if os.path.realpath(self._binary) != self._binary:
                return LaunchResult(success=False, error=f"Emulator binary must be canonical realpath without symlinks: {self._binary}")
        except Exception as e:
            return LaunchResult(success=False, error=f"Realpath resolution error: {e}")

        try:
            if not (os.path.isfile(self._binary) and os.access(self._binary, os.X_OK)):
                if self._binary_validator is None:
                    return LaunchResult(success=False, error=f"Emulator binary not executable regular file: {self._binary}")
        except Exception as e:
            return LaunchResult(success=False, error=f"File check error: {e}")

        if self._binary_validator is not None:
            try:
                if self._binary_validator(self._binary) is not True:
                    return LaunchResult(success=False, error=f"Binary validation failed for {self._binary}")
            except Exception as e:
                return LaunchResult(success=False, error=f"Binary validation error: {e}")

        argv = self.get_frozen_argv()
        if self._spawner is None:
            return LaunchResult(success=False, error="Real emulator spawner not configured in offline mode")

        try:
            child = self._spawner(argv)
        except Exception as e:
            return LaunchResult(success=False, error=f"Spawner execution failed: {e}")

        self._pending_child = child

        def _fail(err: str, fd: Optional[int] = None) -> LaunchResult:
            self._last_result = LaunchResult(
                success=False, pidfd=fd, child_handle=child,
                pending_cleanup=True, error=err
            )
            return self._last_result

        child_pid = getattr(child, "pid", None)
        if not (isinstance(child_pid, int) and type(child_pid) is not bool and child_pid > 0):
            return _fail("Spawner did not provide valid positive child PID")

        if hasattr(child, "poll"):
            try:
                poll_val = child.poll()
                if poll_val is not None:
                    return _fail(f"Child already exited: {poll_val}")
            except Exception as e:
                return _fail(f"Child poll error: {e}")

        owner_pid = os.getpid()
        if not (isinstance(owner_pid, int) and type(owner_pid) is not bool and owner_pid > 0):
            return _fail("Owner PID invalid")

        def _ticks(pid: int) -> Optional[int]:
            if not hasattr(self._controller, "get_start_ticks"):
                return None
            try:
                val = self._controller.get_start_ticks(pid)
                return val if isinstance(val, int) and type(val) is not bool and val > 0 else None
            except Exception:
                return None

        parent_ticks = _ticks(owner_pid)
        if parent_ticks is None:
            return _fail("Could not read valid positive start_ticks for parent")

        if not hasattr(self._controller, "get_ppid"):
            return _fail("Controller missing get_ppid reader")
        try:
            ppid = self._controller.get_ppid(child_pid)
        except Exception as e:
            return _fail(f"PPID check error: {e}")
        if not (isinstance(ppid, int) and type(ppid) is not bool and ppid == owner_pid):
            return _fail("Child parent PID mismatch or unknown")

        start_ticks = _ticks(child_pid)
        if start_ticks is None:
            return _fail("Could not read valid positive start_ticks for child before open")

        if not hasattr(self._controller, "get_uid"):
            return _fail("Controller missing get_uid reader")
        try:
            uid = self._controller.get_uid(child_pid)
        except Exception as e:
            return _fail(f"Child UID read error: {e}")
        if type(uid) is not int or uid != self._target_uid:
            return _fail(f"Child UID {uid!r} != required {self._target_uid}")

        try:
            pidfd = self._controller.pidfd_open(child_pid)
            if not (isinstance(pidfd, int) and type(pidfd) is not bool and pidfd >= 0):
                return _fail(f"Post-spawn pidfd_open failed or invalid fd ({pidfd})")

            self._pending_pidfd = pidfd

            if not hasattr(self._controller, "get_fdinfo_pid"):
                return _fail("Controller missing get_fdinfo_pid reader", pidfd)
            try:
                fdinfo_pid = self._controller.get_fdinfo_pid(pidfd)
            except Exception as e:
                return _fail(f"fdinfo check error: {e}", pidfd)
            if not (isinstance(fdinfo_pid, int) and type(fdinfo_pid) is not bool and fdinfo_pid == child_pid):
                return _fail(f"pidfd fdinfo target PID mismatch or invalid ({fdinfo_pid} != {child_pid})", pidfd)

            if not (hasattr(self._controller, "is_pidfd_alive") and callable(self._controller.is_pidfd_alive)):
                return _fail("Controller missing mandatory is_pidfd_alive callable", pidfd)
            try:
                alive = self._controller.is_pidfd_alive(pidfd)
            except (KeyboardInterrupt, SystemExit):
                raise
            except Exception as e:
                return _fail(f"pidfd liveness check error: {e}", pidfd)
            if alive is not True:
                return _fail(f"pidfd liveness verification failed (alive={alive!r})", pidfd)

            try:
                recheck_child_ticks = self._controller.get_start_ticks(child_pid)
            except Exception as e:
                return _fail(f"Child start_ticks recheck error: {e}", pidfd)
            if type(recheck_child_ticks) is not int or recheck_child_ticks <= 0 or recheck_child_ticks != start_ticks:
                return _fail("Child start_ticks shifted or invalid on recheck", pidfd)

            try:
                recheck_uid = self._controller.get_uid(child_pid) if hasattr(self._controller, "get_uid") else None
            except Exception as e:
                return _fail(f"Child UID recheck error: {e}", pidfd)
            if type(recheck_uid) is not int or recheck_uid != self._target_uid:
                return _fail("Child UID mismatch or invalid on recheck", pidfd)

            try:
                recheck_ppid = self._controller.get_ppid(child_pid) if hasattr(self._controller, "get_ppid") else None
            except Exception as e:
                return _fail(f"PPID recheck error: {e}", pidfd)
            if type(recheck_ppid) is not int or recheck_ppid <= 0 or recheck_ppid != owner_pid:
                return _fail("Child parent PID mismatch or invalid on recheck", pidfd)

            try:
                recheck_parent_ticks = self._controller.get_start_ticks(owner_pid)
            except Exception as e:
                return _fail(f"Parent start_ticks recheck error: {e}", pidfd)
            if type(recheck_parent_ticks) is not int or recheck_parent_ticks <= 0 or recheck_parent_ticks != parent_ticks:
                return _fail("Parent start_ticks shifted or invalid on recheck", pidfd)

            if hasattr(child, "poll"):
                try:
                    poll_val = child.poll()
                    if poll_val is not None:
                        return _fail(f"Child exited during launch verification: {poll_val}", pidfd)
                except Exception as e:
                    return _fail(f"Child poll error on recheck: {e}", pidfd)

            cmdline = self._controller.get_cmdline(child_pid)
            if not cmdline:
                return _fail("Child cmdline empty or unreadable", pidfd)

            if not hasattr(self._controller, "get_exe_path"):
                return _fail("Controller missing get_exe_path reader", pidfd)
            try:
                exe_path = self._controller.get_exe_path(child_pid)
            except Exception as e:
                return _fail(f"Post-spawn exe inspection failed: {e}", pidfd)
            if not isinstance(exe_path, str) or not exe_path or exe_path != self._binary:
                return _fail(f"Post-spawn identity failure: Child actual exe '{exe_path}' mismatch", pidfd)

            if tuple(cmdline) != tuple(self.get_frozen_argv()):
                return _fail("Post-spawn identity verification failure: Child cmdline has extra, missing, or mismatched arguments", pidfd)

            candidate_identity = ProcessIdentity(
                pid=child_pid, start_ticks=start_ticks, uid=self._target_uid,
                avd=self._avd_name, port=self._port, binary_name=os.path.basename(self._binary),
                is_direct_child=True
            )

            id_ok, id_msg = verify_process_identity(candidate_identity, self._controller)
            if not id_ok:
                res = LaunchResult(success=False, identity=candidate_identity, pidfd=pidfd, child_handle=child, pending_cleanup=True, error=f"Post-spawn identity verification failed: {id_msg}")
                self._last_result = res
                return res

            if hasattr(self._controller, "bind_anchored_pidfd"):
                bound_ok = self._controller.bind_anchored_pidfd(child_pid, start_ticks, pidfd)
                if not bound_ok:
                    return _fail("Controller bind_anchored_pidfd rejected duplicate/conflict", pidfd)
                self._pending_pidfd = None

            res = LaunchResult(
                success=True, identity=candidate_identity, pidfd=None,
                child_handle=child, is_direct_child=True, pending_cleanup=False,
                transferred=True
            )
            self._last_result = res
            return res
        except BaseException as ex:
            res = LaunchResult(
                success=False, pidfd=self._pending_pidfd, child_handle=self._pending_child,
                pending_cleanup=True, error=f"Post-spawn exception: {ex}"
            )
            self._last_result = res
            if isinstance(ex, (KeyboardInterrupt, SystemExit)):
                raise
            return res


# --- Guarded Runner with Exception and Exit Cleanup ---

def run_guarded_supervisor(
    runner: Callable[[], None],
    target: ProcessIdentity,
    controller: ProcessController,
    state_sink: Dict[str, Any],
    cleanup_probe: Optional[Callable[[], Any]] = None,
    grace_seconds: float = DEFAULT_GRACE_SEC,
    poll_interval: float = 0.5,
    engine: Optional[SupervisorEngine] = None
) -> LifecycleState:
    """Execute supervisor runner and guarantee fail-closed cleanup in finally block.

    Handles normal return, Exception, KeyboardInterrupt, and SystemExit.
    Preserves exact termination lifecycle state in state_sink.
    """
    exc_caught: Optional[BaseException] = None
    cleanup_exc: Optional[BaseException] = None
    cleanup_interrupt: Optional[BaseException] = None
    try:
        runner()
    except BaseException as e:
        exc_caught = e
        state_sink["body_exception"] = e
        state_sink["stop_reason"] = f"Supervisor terminated by: {e}"
    finally:
        reason = state_sink.get("stop_reason", "Guarded supervisor normal exit")
        try:
            if cleanup_probe is not None:
                res = cleanup_probe()
                if res is False:
                    state_sink["probe_cleanup_failed"] = True
                    state_sink["probe_cleanup_error"] = "cleanup_probe returned False (cleanup pending or failed)"
                elif res is None:
                    adapter_obj = getattr(cleanup_probe, "__self__", None)
                    if getattr(adapter_obj, "has_pending_cleanup", False):
                        state_sink["probe_cleanup_failed"] = True
                        state_sink["probe_cleanup_error"] = (
                            getattr(adapter_obj, "cleanup_error", None)
                            or f"Pending un-reaped probe handles ({len(getattr(adapter_obj, '_pending_reap', []))})"
                        )
                    elif getattr(adapter_obj, "has_probe_cleanup_failure", False):
                        state_sink["probe_cleanup_failed"] = True
                        state_sink["probe_cleanup_error"] = (
                            getattr(adapter_obj, "probe_cleanup_error", None) or "Pending probe cleanup failure"
                        )
        except BaseException as ce:
            cleanup_exc = ce
            if isinstance(ce, (KeyboardInterrupt, SystemExit)) and cleanup_interrupt is None:
                cleanup_interrupt = ce
            state_sink["probe_cleanup_failed"] = True
            state_sink["probe_cleanup_error"] = f"probe_cleanup exception: {type(ce).__name__}{': ' + str(ce) if str(ce) else ''}"
            state_sink["secondary_cleanup_cause"] = type(ce).__name__
        finally:
            term_state, term_msg = LifecycleState.ERROR, ""
            try:
                if engine is None:
                    term_state, term_msg = safe_sigterm(
                        target, controller, reason=reason, grace_seconds=grace_seconds, poll_interval=poll_interval
                    )
                elif not isinstance(engine, SupervisorEngine):
                    term_state, term_msg = LifecycleState.ERROR, "Invalid engine instance"
                else:
                    claim_st = SupervisorEngine.claim(engine, target, controller)
                    if claim_st == TerminationClaimStatus.CLAIMED:
                        rec = getattr(engine, "attempt_record", None)
                        out = getattr(rec, "outcome", None) if rec else None
                        if out and out.completed is True and out.state == LifecycleState.STOPPED and out.primary_error is None:
                            term_state, term_msg = out.state, (out.detail or "Engine claimed termination completed")
                        else:
                            term_state, term_msg = LifecycleState.ERROR, f"Engine outcome invalid or failed: {out}"
                    elif claim_st == TerminationClaimStatus.NOT_ATTEMPTED:
                        term_state, term_msg = safe_sigterm(
                            target, controller, reason=reason, grace_seconds=grace_seconds, poll_interval=poll_interval
                        )
                    else:
                        term_state, term_msg = LifecycleState.ERROR, f"Engine claim status: {claim_st}"
            except BaseException as te:
                if isinstance(te, (KeyboardInterrupt, SystemExit)) and cleanup_interrupt is None:
                    cleanup_interrupt = te
                term_state = LifecycleState.ERROR
                term_msg = f"Target termination exception: {te}"
                state_sink["target_termination_error"] = str(te)
            finally:
                if hasattr(controller, "cleanup_bound_pidfd"):
                    try:
                        c_err = controller.cleanup_bound_pidfd()
                        if c_err is not None and (c_err is False or isinstance(c_err, BaseException)):
                            state_sink["controller_cleanup_failed"] = True
                            state_sink["controller_cleanup_error"] = str(c_err)
                    except BaseException as cbe:
                        state_sink["controller_cleanup_failed"] = True
                        state_sink["controller_cleanup_error"] = str(cbe)
                        if isinstance(cbe, (KeyboardInterrupt, SystemExit)) and cleanup_interrupt is None:
                            cleanup_interrupt = cbe

            state_sink["primary_state"] = term_state
            state_sink["termination_state"] = term_state
            state_sink["state"] = term_state
            state_sink["termination_detail"] = term_msg

            if state_sink.get("probe_cleanup_failed") or state_sink.get("controller_cleanup_failed"):
                state_sink["state"] = LifecycleState.ERROR

            if exc_caught is not None and term_state == LifecycleState.STOPPED:
                state_sink["state"] = LifecycleState.ERROR

        if isinstance(exc_caught, (KeyboardInterrupt, SystemExit)):
            raise exc_caught
        if cleanup_interrupt is not None:
            if exc_caught is not None:
                raise exc_caught
            raise cleanup_interrupt

    return state_sink.get("state", LifecycleState.ERROR)


# --- CLI / Offline Verification ---

def main() -> int:
    parser = argparse.ArgumentParser(
        description="F3 Android Emulator Capacity and Safety Guard (Offline / Verification mode)"
    )
    parser.add_argument(
        "--check-offline",
        action="store_true",
        help="Run offline self-check without starting or contacting real emulator"
    )
    parser.add_argument(
        "--version",
        action="version",
        version="f3_emulator_guard 1.0.0"
    )
    args = parser.parse_args()

    if args.check_offline:
        print("F3 Emulator Guard offline contract verification: OK")
        print(f"  RAM Limit: {GUARD_RAM_MIN_KB} kB")
        print(f"  Swap Limit: {GUARD_SWAP_MIN_KB} kB")
        print(f"  Supervision TTL: {DEFAULT_TTL_SEC} s (monotonic)")
        print(f"  Cadence: {DEFAULT_CADENCE_SEC} s")
        return 0

    parser.print_help()
    return 0


if __name__ == "__main__":
    sys.exit(main())
