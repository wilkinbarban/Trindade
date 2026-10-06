"""Domain types and lifecycle state enumerations for F3 emulator guard.

This module provides pure stdlib data contracts and immutable value objects
extracted from the F3 emulator guard architecture.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Any, Optional

__all__ = [
    "ANREvent",
    "BootEvidence",
    "LifecycleState",
    "ProbeStatus",
    "ProcessIdentity",
    "TerminationAttempt",
    "TerminationAttemptRecord",
    "TerminationClaimStatus",
    "TerminationOutcome",
    "TerminationRecord",
]


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
    NOT_ATTEMPTED = "not_attempted"
    CLAIMED = "claimed"
    ALREADY_CLAIMED = "already_claimed"
    MISMATCH = "mismatch"
    IN_PROGRESS = "in_progress"

    @property
    def status(self) -> TerminationClaimStatus:
        return self


@dataclass(frozen=True)
class TerminationOutcome:
    state: LifecycleState
    detail: str = ""
    primary_error: Optional[BaseException] = None
    completed: bool = True

    @property
    def primary_exception(self) -> Optional[BaseException]:
        return self.primary_error


@dataclass(frozen=True)
class TerminationAttemptRecord:
    pid: int
    start_ticks: int
    uid: int
    avd: str
    controller: Any
    outcome: Optional[TerminationOutcome] = None
    target: Optional[ProcessIdentity] = None


TerminationAttempt = TerminationAttemptRecord
TerminationRecord = TerminationOutcome


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
