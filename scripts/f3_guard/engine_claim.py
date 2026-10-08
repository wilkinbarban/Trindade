"""Standalone termination claim tracking for F3 emulator guard.

Manages process termination claim lifecycle, recording attempt metadata,
sealing completion outcomes, and enforcing strict identity claim invariants.
"""

from __future__ import annotations

from dataclasses import replace
from typing import Any, Optional

from scripts.f3_guard.types import (
    TerminationAttemptRecord,
    TerminationClaimStatus,
    TerminationOutcome,
)

__all__ = ["TerminationClaimTracker"]


class TerminationClaimTracker:
    """Tracks termination attempts, sealed outcomes, and identity claims."""

    def __init__(self, target: Any, controller: Any) -> None:
        self._target = target
        self._controller = controller
        self._attempt_record: Optional[TerminationAttemptRecord] = None
        self._claimed: bool = False

    target = property(lambda self: self._target)
    controller = property(lambda self: self._controller)
    attempt_record = property(lambda self: self._attempt_record)
    termination_attempt = attempt_record
    export_claim_snapshot = lambda self: self._attempt_record
    termination_outcome = property(
        lambda self: self._attempt_record.outcome if self._attempt_record is not None else None
    )

    def record_attempt(self) -> TerminationAttemptRecord:
        """Idempotently record termination attempt metadata (no reset on repeat)."""
        if self._attempt_record is None:
            self._attempt_record = TerminationAttemptRecord(
                pid=getattr(self._target, "pid", None),
                start_ticks=getattr(self._target, "start_ticks", None),
                uid=getattr(self._target, "uid", None),
                avd=getattr(self._target, "avd", None),
                controller=self._controller,
                target=self._target,
            )
        return self._attempt_record

    def seal_outcome(self, outcome: TerminationOutcome) -> TerminationAttemptRecord:
        """Seal outcome of recorded attempt; raises ValueError if unrecorded (new invariant)."""
        if self._attempt_record is None:
            raise ValueError("Cannot seal outcome before recording an attempt")
        if self._attempt_record.outcome is None:
            self._attempt_record = replace(self._attempt_record, outcome=outcome)
        return self._attempt_record

    def claim(self, target: Any, controller: Any) -> TerminationClaimStatus:
        """Evaluate and consume termination claim token for target process."""
        _si = lambda v: isinstance(v, int) and type(v) is not bool
        if not (
            _si(getattr(target, "pid", None))
            and _si(getattr(target, "start_ticks", None))
            and _si(getattr(target, "uid", None))
        ):
            return TerminationClaimStatus.MISMATCH

        rt, rc = (
            (self._attempt_record, self._attempt_record.controller)
            if self._attempt_record is not None
            else (self._target, self._controller)
        )
        if (
            getattr(target, "pid", None) != getattr(rt, "pid", None)
            or getattr(target, "start_ticks", None) != getattr(rt, "start_ticks", None)
            or getattr(target, "uid", None) != getattr(rt, "uid", None)
            or getattr(target, "avd", None) != getattr(rt, "avd", None)
            or controller is not rc
        ):
            return TerminationClaimStatus.MISMATCH

        if self._attempt_record is None:
            return TerminationClaimStatus.NOT_ATTEMPTED
        if self._attempt_record.outcome is None:
            return TerminationClaimStatus.IN_PROGRESS
        if self._claimed:
            return TerminationClaimStatus.ALREADY_CLAIMED

        self._claimed = True
        return TerminationClaimStatus.CLAIMED

    claim_termination = claim
