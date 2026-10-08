"""Cohesive engine readiness helper for F3 emulator guard (8c3)."""

from __future__ import annotations

from typing import Any, Callable, Dict, Optional, Tuple, Union

from scripts.f3_guard.clock import Clock, SystemClock
from scripts.f3_guard.controller import ProcessController
from scripts.f3_guard.probe_lifecycle import ProbeLifecycle
from scripts.f3_guard.proc_identity import verify_process_identity
from scripts.f3_guard.readiness import evaluate_readiness
from scripts.f3_guard.types import BootEvidence, ProcessIdentity

__all__ = ["EngineReadinessHelper"]


class EngineReadinessHelper:
    """Evaluates host memory, target identity, probes, and boot evidence readiness."""

    def __init__(
        self,
        target: ProcessIdentity,
        controller: ProcessController,
        probe_lifecycle: ProbeLifecycle,
        clock: Optional[Clock] = None,
        mem_reader: Optional[Callable[[], Tuple[Optional[int], Optional[int]]]] = None,
        boot_evidence: Optional[Union[BootEvidence, Callable[[], Optional[BootEvidence]]]] = None,
        baseline_established: bool = True,
    ) -> None:
        self.target = target
        self.controller = controller
        self.probe_lifecycle = probe_lifecycle
        self.clock: Clock = clock or SystemClock()
        self._mem_reader = mem_reader
        self.boot_evidence = boot_evidence
        self.baseline_established: bool = baseline_established
        self.is_ready: bool = False
        self.readiness_details: Dict[str, Any] = {}
        self._last_mem_sample: Tuple[Optional[int], Optional[int]] = (None, None)

    def check_readiness(self) -> Tuple[bool, Dict[str, Any]]:
        """Evaluate process identity, memory capacity, probe health, and boot completion."""
        id_ok, _ = verify_process_identity(self.target, self.controller)
        mem_avail, swap_free = self._last_mem_sample
        if mem_avail is None and self._mem_reader:
            try:
                mem_avail, swap_free = self._mem_reader()
                self._last_mem_sample = (mem_avail, swap_free)
            except Exception:
                mem_avail, swap_free = None, None
        boot_ev = self.boot_evidence() if callable(self.boot_evidence) else self.boot_evidence
        ready, details = evaluate_readiness(
            boot_completed=boot_ev,
            mem_available_kb=mem_avail,
            swap_free_kb=swap_free,
            probe_status=self.probe_lifecycle.last_probe_status,
            identity_verified=id_ok,
            baseline_established=self.baseline_established,
            target=self.target,
            clock=self.clock,
            max_stale_sec=self.probe_lifecycle.stale_probe_sec,
            last_probe_mono=self.probe_lifecycle.last_probe_mono,
            probe_state=self.probe_lifecycle.probe_state,
        )
        self.is_ready = ready
        self.readiness_details = details
        return ready, details
