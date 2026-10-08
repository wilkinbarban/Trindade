"""Cohesive engine termination helper for F3 emulator guard (8c2)."""

from __future__ import annotations

from typing import Any, Optional, Tuple

from scripts.f3_guard.engine_claim import TerminationClaimTracker
from scripts.f3_guard.probe_lifecycle import ProbeLifecycle
from scripts.f3_guard.sigterm import DEFAULT_GRACE_SEC, safe_sigterm
from scripts.f3_guard.types import (
    LifecycleState,
    TerminationAttemptRecord,
    TerminationOutcome,
)

__all__ = ["EngineTerminationHelper"]


class EngineTerminationHelper:
    """Orchestrates graceful emulator process termination, teardown, and outcome sealing."""

    def __init__(
        self,
        target: Any,
        controller: Any,
        probe_lifecycle: ProbeLifecycle,
        claim_tracker: Optional[TerminationClaimTracker] = None,
        grace_seconds: float = DEFAULT_GRACE_SEC,
        poll_interval: float = 0.5,
    ) -> None:
        self.target, self.controller, self.probe_lifecycle = target, controller, probe_lifecycle
        self.claim_tracker = claim_tracker or TerminationClaimTracker(target, controller)
        self.grace_seconds, self.poll_interval = float(grace_seconds), float(poll_interval)
        self.state, self.stop_reason = LifecycleState.RUNNING, None
        self.has_probe_cleanup_failure, self.probe_cleanup_error = False, None
        self.primary_state, self.termination_state, self.primary_error = None, None, None

    attempt_record = property(lambda self: self.claim_tracker.attempt_record)

    def cleanup_probe_teardown(self) -> None:
        """Central probe teardown delegating to ProbeLifecycle and syncing diagnostic state."""
        if self.probe_lifecycle is not None:
            try:
                self.probe_lifecycle.cleanup_teardown()
            finally:
                if getattr(self.probe_lifecycle, "has_probe_cleanup_failure", False):
                    self.has_probe_cleanup_failure = True
                    if self.probe_cleanup_error is None:
                        self.probe_cleanup_error = getattr(self.probe_lifecycle, "probe_cleanup_error", None)

    def terminate(self, reason: str) -> Tuple[bool, LifecycleState]:
        """Issue safe_sigterm, perform teardown, and record termination attempt."""
        if self.claim_tracker.attempt_record is not None:
            return True, self.state
        self.stop_reason = reason
        self.claim_tracker.record_attempt()
        term_state = LifecycleState.ERROR
        teardown_exc, sig_exc, ctrl_exc, abort_exc, completed = None, None, None, None, False
        try:
            try:
                self.cleanup_probe_teardown()
            except BaseException as te:
                teardown_exc = te
            finally:
                try:
                    term_state, _ = safe_sigterm(
                        self.target, self.controller, reason=reason,
                        grace_seconds=self.grace_seconds, poll_interval=self.poll_interval,
                    )
                except BaseException as se:
                    sig_exc = se
                finally:
                    if hasattr(self.controller, "cleanup_bound_pidfd"):
                        try:
                            c_err = self.controller.cleanup_bound_pidfd()
                            if c_err is False or isinstance(c_err, BaseException):
                                self.has_probe_cleanup_failure = True
                                self.probe_cleanup_error = f"Controller cleanup failed: {c_err}"
                        except BaseException as ce:
                            ctrl_exc = ce
                            self.has_probe_cleanup_failure = True
                            self.probe_cleanup_error = f"Controller cleanup exception: {ce}"

            _ints = (KeyboardInterrupt, SystemExit)
            propagate = (
                teardown_exc if isinstance(teardown_exc, _ints)
                else (sig_exc if isinstance(sig_exc, _ints)
                      else (ctrl_exc if isinstance(ctrl_exc, _ints)
                            else (teardown_exc if teardown_exc is not None
                                  else sig_exc)))
            )
            if propagate is not None: raise propagate

            self.primary_state = self.termination_state = term_state
            self.state = LifecycleState.ERROR if self.has_probe_cleanup_failure else term_state
            if self.has_probe_cleanup_failure:
                self.probe_cleanup_error = self.probe_cleanup_error or "Probe teardown pending or un-reaped child handles remain"
                self.stop_reason = f"{reason} (cleanup failed: {self.probe_cleanup_error})"
            completed = True
            return True, self.state
        except BaseException as be:
            abort_exc = be
            if self.primary_error is None: self.primary_error = be
            self.state = self.termination_state = LifecycleState.ERROR
            raise
        finally:
            if self.claim_tracker.attempt_record is not None and self.claim_tracker.attempt_record.outcome is None:
                self.claim_tracker.seal_outcome(TerminationOutcome(
                    state=self.state,
                    detail=self.stop_reason or (f"Aborted: {abort_exc}" if abort_exc else reason),
                    primary_error=self.primary_error if self.primary_error is not None else abort_exc,
                    completed=completed,
                ))
