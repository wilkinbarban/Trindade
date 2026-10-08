"""SupervisorEngine assembly orchestrator for F3 emulator guard (8c4a)."""

from __future__ import annotations

from typing import Any, Callable, Dict, Optional, Set, Tuple, Union

from scripts.f3_guard.adb_adapter import AdbProbeAdapter
from scripts.f3_guard.clock import Clock, DEFAULT_TTL_SEC, MonotonicTTLTracker, SystemClock
from scripts.f3_guard.controller import ProcessController
from scripts.f3_guard.engine_claim import TerminationClaimTracker
from scripts.f3_guard.engine_readiness import EngineReadinessHelper
from scripts.f3_guard.engine_termination import EngineTerminationHelper
from scripts.f3_guard.probe_lifecycle import ProbeLifecycle
from scripts.f3_guard.readiness import DEFAULT_MAX_STALE_PROBE_SEC, _ProbeState
from scripts.f3_guard.sigterm import DEFAULT_GRACE_SEC
from scripts.f3_guard.types import (
    ANREvent,
    BootEvidence,
    LifecycleState,
    ProbeStatus,
    ProcessIdentity,
    TerminationAttemptRecord,
    TerminationClaimStatus,
    TerminationOutcome,
)

__all__ = ["DEFAULT_CADENCE_SEC", "SupervisorEngine"]

DEFAULT_CADENCE_SEC: float = 5.0


class SupervisorEngine:
    """Bounded, fail-closed supervisor orchestrating memory sampling, TTL, and health probes."""

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
        baseline_established: bool = True,
    ) -> None:
        self._target, self._controller = target, controller
        self._clock: Clock = clock or SystemClock()
        self._cadence_sec, self._ttl_sec = float(cadence_sec), float(ttl_sec)
        self._grace_sec, self._poll_interval = float(grace_sec), float(poll_interval)
        self._baseline_events, self._adapter = baseline_events or set(), adapter
        self.secondary_error: Optional[BaseException] = None
        self._first_step = True
        self.ttl_tracker = MonotonicTTLTracker(ttl_seconds=self._ttl_sec, clock=self._clock)
        self._last_mem_sample_mono = self._clock.monotonic()

        self._claim_tracker = TerminationClaimTracker(self._target, self._controller)
        self._probe_lifecycle = ProbeLifecycle(
            clock=self._clock,
            start_probe=start_probe, poll_probe=poll_probe, probe_runner=probe_runner,
            cleanup_probe=cleanup_probe, adapter=adapter, baseline_events=self._baseline_events,
            probe_deadline_sec=float(probe_deadline_sec), stale_probe_sec=float(stale_probe_sec),
        )

        self._readiness_helper = EngineReadinessHelper(
            target=self._target, controller=self._controller, probe_lifecycle=self._probe_lifecycle,
            clock=self._clock, mem_reader=mem_reader, boot_evidence=boot_evidence,
            baseline_established=baseline_established,
        )
        self._termination_helper = EngineTerminationHelper(
            target=self._target, controller=self._controller, probe_lifecycle=self._probe_lifecycle,
            claim_tracker=self._claim_tracker, grace_seconds=self._grace_sec, poll_interval=self._poll_interval,
        )

    # Readiness single source of truth delegation
    target = property(lambda self: self._target)
    controller = property(lambda self: self._controller)
    clock = property(lambda self: self._clock)

    is_ready = property(lambda self: self._readiness_helper.is_ready, lambda self, v: setattr(self._readiness_helper, "is_ready", v))
    readiness_details = property(lambda self: self._readiness_helper.readiness_details, lambda self, v: setattr(self._readiness_helper, "readiness_details", v))
    _last_mem_sample = property(lambda self: self._readiness_helper._last_mem_sample, lambda self, v: setattr(self._readiness_helper, "_last_mem_sample", v))
    boot_evidence = property(lambda self: self._readiness_helper.boot_evidence, lambda self, v: setattr(self._readiness_helper, "boot_evidence", v))
    baseline_established = property(lambda self: self._readiness_helper.baseline_established, lambda self, v: setattr(self._readiness_helper, "baseline_established", v))
    _mem_reader = property(lambda self: self._readiness_helper._mem_reader, lambda self, v: setattr(self._readiness_helper, "_mem_reader", v))

    def check_readiness(self) -> Tuple[bool, Dict[str, Any]]:
        return self._readiness_helper.check_readiness()

    # Termination single source of truth delegation
    state = property(lambda self: self._termination_helper.state, lambda self, v: setattr(self._termination_helper, "state", v))
    stop_reason = property(lambda self: self._termination_helper.stop_reason, lambda self, v: setattr(self._termination_helper, "stop_reason", v))
    has_probe_cleanup_failure = property(lambda self: self._termination_helper.has_probe_cleanup_failure, lambda self, v: setattr(self._termination_helper, "has_probe_cleanup_failure", v))
    probe_cleanup_error = property(lambda self: self._termination_helper.probe_cleanup_error, lambda self, v: setattr(self._termination_helper, "probe_cleanup_error", v))
    primary_state = property(lambda self: self._termination_helper.primary_state, lambda self, v: setattr(self._termination_helper, "primary_state", v))
    termination_state = property(lambda self: self._termination_helper.termination_state, lambda self, v: setattr(self._termination_helper, "termination_state", v))
    primary_error = property(lambda self: self._termination_helper.primary_error, lambda self, v: setattr(self._termination_helper, "primary_error", v))

    def _terminate(self, reason: str) -> Tuple[bool, LifecycleState]:
        return self._termination_helper.terminate(reason)

    def _cleanup_probe_teardown(self) -> None:
        self._termination_helper.cleanup_probe_teardown()

    # Probes single source of truth delegation
    _cleanup_probe = property(lambda self: self._probe_lifecycle._cleanup_probe, lambda self, v: setattr(self._probe_lifecycle, "_cleanup_probe", v))
    _start_probe = property(lambda self: self._probe_lifecycle._start_probe, lambda self, v: setattr(self._probe_lifecycle, "_start_probe", v))
    _poll_probe = property(lambda self: self._probe_lifecycle._poll_probe, lambda self, v: setattr(self._probe_lifecycle, "_poll_probe", v))
    _probe_runner = property(lambda self: self._probe_lifecycle._probe_runner, lambda self, v: setattr(self._probe_lifecycle, "_probe_runner", v))
    last_probe_status = property(lambda self: self._probe_lifecycle.last_probe_status, lambda self, v: setattr(self._probe_lifecycle, "last_probe_status", v))
    last_probe_mono = property(lambda self: self._probe_lifecycle.last_probe_mono, lambda self, v: setattr(self._probe_lifecycle, "last_probe_mono", v))
    _probe_state = property(lambda self: self._probe_lifecycle._probe_state, lambda self, v: setattr(self._probe_lifecycle, "_probe_state", v))
    _probe_start_mono = property(lambda self: self._probe_lifecycle._probe_start_mono, lambda self, v: setattr(self._probe_lifecycle, "_probe_start_mono", v))
    _probe_deadline_sec = property(lambda self: self._probe_lifecycle._probe_deadline_sec, lambda self, v: setattr(self._probe_lifecycle, "_probe_deadline_sec", v))
    _stale_probe_sec = property(lambda self: self._probe_lifecycle._stale_probe_sec, lambda self, v: setattr(self._probe_lifecycle, "_stale_probe_sec", v))

    def record_probe_result(self, status: ProbeStatus, monotonic_timestamp: float) -> None:
        self._probe_lifecycle.record_probe_result(status, monotonic_timestamp)

    # Claims API and forwarding
    attempt_record = property(lambda self: self._claim_tracker.attempt_record)
    termination_attempt = attempt_record
    termination_outcome = property(lambda self: self._claim_tracker.termination_outcome)
    _attempt_record = property(lambda self: self._claim_tracker._attempt_record)
    _claimed = property(lambda self: self._claim_tracker._claimed)

    def claim(self, target: Any, controller: Any) -> TerminationClaimStatus:
        return self._claim_tracker.claim(target, controller)

    claim_termination = claim

    def export_claim_snapshot(self) -> Optional[TerminationAttemptRecord]:
        return self._claim_tracker.export_claim_snapshot()
