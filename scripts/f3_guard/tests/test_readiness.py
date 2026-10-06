"""Hermetic unit tests for scripts.f3_guard.readiness module."""

from __future__ import annotations

import math, os, sys, unittest

_REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

from scripts.f3_guard.clock import FakeClock
from scripts.f3_guard.memory import GUARD_RAM_MIN_KB, GUARD_SWAP_MIN_KB
from scripts.f3_guard.readiness import (
    DEFAULT_MAX_STALE_PROBE_SEC,
    _ProbeState,
    evaluate_readiness,
)
from scripts.f3_guard.types import BootEvidence, ProbeStatus, ProcessIdentity


class TestReadinessEvaluation(unittest.TestCase):
    """Hermetic unit tests for evaluate_readiness."""

    def setUp(self) -> None:
        self.target = ProcessIdentity(1234, 5678, 1000, "test_avd", 5554, "qemu")
        self.valid_ev = BootEvidence(True, 1234, 5678, 100.0, "test_avd", 1000)
        self.clock = FakeClock(monotonic_start=100.0)

    def _ev(self, mono: float = 100.0) -> BootEvidence:
        return BootEvidence(True, 1234, 5678, mono, "test_avd", 1000)

    def _call(self, **overrides):
        kwargs = {
            "boot_completed": self.valid_ev,
            "mem_available_kb": GUARD_RAM_MIN_KB,
            "swap_free_kb": GUARD_SWAP_MIN_KB,
            "probe_status": ProbeStatus.CLEAR,
            "identity_verified": True,
            "baseline_established": True,
            "target": self.target,
            "clock": self.clock,
            "last_probe_mono": 100.0,
        }
        kwargs.update(overrides)
        return evaluate_readiness(**kwargs)

    def test_constants_and_success_details(self) -> None:
        self.assertEqual(DEFAULT_MAX_STALE_PROBE_SEC, 15.0)
        self.assertEqual(_ProbeState.RUNNING.value, "running")
        # Direct call truly omitting optional arguments
        ok_dir, d_dir = evaluate_readiness(
            True, GUARD_RAM_MIN_KB, GUARD_SWAP_MIN_KB, ProbeStatus.CLEAR, True, True
        )
        self.assertTrue(ok_dir)
        self.assertEqual(d_dir["status"], "ready")

        ok, d = self._call(mem_available_kb=GUARD_RAM_MIN_KB + 1024)
        self.assertTrue(ok)
        self.assertEqual(d["status"], "ready")
        self.assertEqual(d["reason"], "All preflight and capacity checks satisfied")
        self.assertEqual(d["boot_completed"], True)
        self.assertEqual(d["mem_available_kb"], GUARD_RAM_MIN_KB + 1024)
        self.assertEqual(d["swap_free_kb"], GUARD_SWAP_MIN_KB)
        self.assertEqual(d["probe_status"], "clear")
        self.assertTrue(d["identity_verified"] and d["baseline_established"])

    def test_boot_strict_boolean_and_types(self) -> None:
        for bad in (False, "true", 1, 0, None, [True]):
            with self.subTest(bad=bad):
                ok, d = self._call(boot_completed=bad, target=None, clock=None)
                self.assertFalse(ok)
                self.assertEqual(d["status"], "not_ready")
                self.assertEqual(d["reason"], "Boot sequence incomplete or non-boolean")

        # Non-boolean BootEvidence.boot_completed rejected via subtests
        for bad_raw in ("true", 1, 0, None, [True], False):
            with self.subTest(bad_raw=bad_raw):
                ev = BootEvidence(bad_raw, 1234, 5678, 100.0, "test_avd", 1000)  # type: ignore
                ok, d = self._call(boot_completed=ev)
                self.assertFalse(ok)
                self.assertEqual(d["status"], "not_ready")
                self.assertEqual(d["reason"], "Boot sequence incomplete or non-boolean")

    def test_target_presence_and_evidence_mismatches(self) -> None:
        # Bare bool rejected when target is provided
        ok, d = self._call(boot_completed=True, target=self.target)
        self.assertFalse(ok)
        self.assertEqual(d["reason"], "Boot evidence missing target process identity")

        # Bare bool accepted when target is None
        ok, d = self._call(boot_completed=True, target=None, clock=None)
        self.assertTrue(ok)
        self.assertEqual(d["status"], "ready")

        # Optional avd/uid None accepted
        ev_none = BootEvidence(True, 1234, 5678, 100.0, None, None)
        ok, _ = self._call(boot_completed=ev_none)
        self.assertTrue(ok)

        # Mismatches, invalid start_ticks, bool pid/uid or bad types fail
        cases = [
            BootEvidence(True, 9999, 5678, 100.0, "test_avd", 1000),
            BootEvidence(True, 1234, 9999, 100.0, "test_avd", 1000),
            BootEvidence(True, 1234, 5678, 100.0, "other_avd", 1000),
            BootEvidence(True, 1234, 5678, 100.0, "test_avd", 2000),
            BootEvidence(True, "1234", 5678, 100.0, "test_avd", 1000),  # type: ignore
            BootEvidence(True, 1234, 5678, 100.0, 123, 1000),  # type: ignore
            BootEvidence(True, 1234, 5678, 100.0, "test_avd", "1000"),  # type: ignore
            BootEvidence(True, True, 5678, 100.0, "test_avd", 1000),  # type: ignore (bool pid)
            BootEvidence(True, 1234, 5678, 100.0, "test_avd", True),  # type: ignore (bool uid)
            BootEvidence(True, 1234, True, 100.0, "test_avd", 1000),  # type: ignore (bool ticks)
            BootEvidence(True, 1234, "5678", 100.0, "test_avd", 1000),  # type: ignore (str ticks)
            BootEvidence(True, 1234, 5678.0, 100.0, "test_avd", 1000),  # type: ignore (float ticks)
            BootEvidence(True, 1234, None, 100.0, "test_avd", 1000),  # type: ignore (None ticks)
        ]
        for ev in cases:
            with self.subTest(ev=ev):
                ok, d = self._call(boot_completed=ev)
                self.assertFalse(ok)
                self.assertEqual(d["reason"], "Boot evidence target mismatch")

    def test_boot_evidence_freshness_boundaries_and_nonfinite(self) -> None:
        c = FakeClock(monotonic_start=115.0)
        ok, _ = self._call(boot_completed=self.valid_ev, clock=c)
        self.assertTrue(ok)

        c.advance_monotonic(0.001)
        ok, d = self._call(boot_completed=self.valid_ev, clock=c)
        self.assertFalse(ok)
        self.assertEqual(d["reason"], "Boot evidence stale")

        # Custom max stale boundary
        c_cust = FakeClock(monotonic_start=105.0)
        ok, _ = self._call(boot_completed=self.valid_ev, clock=c_cust, max_stale_sec=5.0)
        self.assertTrue(ok)
        c_cust.advance_monotonic(0.001)
        ok, d = self._call(boot_completed=self.valid_ev, clock=c_cust, max_stale_sec=5.0)
        self.assertFalse(ok)
        self.assertEqual(d["reason"], "Boot evidence stale")

        # Clock None with stale timestamp preserves omission
        stale_ev = BootEvidence(True, 1234, 5678, 0.0, "test_avd", 1000)
        ok, _ = self._call(boot_completed=stale_ev, clock=None)
        self.assertTrue(ok)

        for b_time, c_time in [(120.0, 100.0), (float("nan"), 100.0), (100.0, float("inf"))]:
            ev = BootEvidence(True, 1234, 5678, b_time)
            ok, d = self._call(boot_completed=ev, clock=FakeClock(c_time))
            self.assertFalse(ok)
            self.assertEqual(d["reason"], "Boot evidence stale")

    def test_identity_and_baseline_shortcircuit_priority(self) -> None:
        # Boot priority over identity
        ok, d = self._call(boot_completed=False, identity_verified=False)
        self.assertFalse(ok)
        self.assertEqual(d["reason"], "Boot sequence incomplete or non-boolean")

        # Identity priority over baseline
        ok, d = self._call(identity_verified=False, baseline_established=False)
        self.assertFalse(ok)
        self.assertEqual(d["reason"], "Process identity verification pending or mismatched")

        # Baseline priority over memory
        ok, d = self._call(baseline_established=False, mem_available_kb=0)
        self.assertFalse(ok)
        self.assertEqual(d["reason"], "ANR health baseline not established")

        # Memory priority over RUNNING probe state
        ok, d = self._call(mem_available_kb=0, probe_state=_ProbeState.RUNNING)
        self.assertFalse(ok)
        self.assertIn("Memory capacity failure", d["reason"])

        # Stale probe priority over unhealthy probe status
        c = FakeClock(monotonic_start=120.0)
        ok, d = self._call(
            boot_completed=self._ev(120.0), clock=c, last_probe_mono=100.0,
            probe_status=ProbeStatus.ANR_DETECTED,
        )
        self.assertFalse(ok)
        self.assertEqual(d["reason"], "Health probe stale")

    def test_memory_invalid_and_boundaries(self) -> None:
        ok, d = self._call(mem_available_kb=GUARD_RAM_MIN_KB - 1)
        self.assertFalse(ok)
        self.assertIn("Available RAM", d["reason"])

        ok, d = self._call(swap_free_kb=GUARD_SWAP_MIN_KB - 1)
        self.assertFalse(ok)
        self.assertIn("Free Swap", d["reason"])

        ok, d = self._call(mem_available_kb="invalid")
        self.assertFalse(ok)
        self.assertIn("Memory metric must be strict integer", d["reason"])

    def test_probe_state_running_and_enum_statuses(self) -> None:
        ok, d = self._call(probe_state=_ProbeState.RUNNING)
        self.assertFalse(ok)
        self.assertEqual(d["reason"], "System probe pending")

        for st in (ProbeStatus.ANR_DETECTED, ProbeStatus.PROBE_ERROR, ProbeStatus.UNKNOWN):
            with self.subTest(st=st):
                ok, d = self._call(probe_status=st)
                self.assertFalse(ok)
                self.assertEqual(d["reason"], f"System probe unhealthy: {st.value}")

    def test_probe_freshness_boundaries_and_omission(self) -> None:
        c = FakeClock(monotonic_start=115.0)
        ok, _ = self._call(boot_completed=self._ev(115.0), clock=c, last_probe_mono=100.0)
        self.assertTrue(ok)

        c.advance_monotonic(0.001)
        ok, d = self._call(boot_completed=self._ev(115.0), clock=c, last_probe_mono=100.0)
        self.assertFalse(ok)
        self.assertEqual(d["reason"], "Health probe stale")

        # Custom max stale boundary
        c_cust = FakeClock(monotonic_start=105.0)
        ev_c = self._ev(105.0)
        ok, _ = self._call(
            boot_completed=ev_c, clock=c_cust, last_probe_mono=100.0, max_stale_sec=5.0
        )
        self.assertTrue(ok)
        c_cust.advance_monotonic(0.001)
        ok, d = self._call(
            boot_completed=ev_c, clock=c_cust, last_probe_mono=100.0, max_stale_sec=5.0
        )
        self.assertFalse(ok)
        self.assertEqual(d["reason"], "Health probe stale")

        for p_mono, c_mono in [(120.0, 100.0), (float("nan"), 100.0), (100.0, float("inf"))]:
            boot = self._ev(c_mono) if math.isfinite(c_mono) else True
            target = self.target if math.isfinite(c_mono) else None
            ok, d = self._call(
                boot_completed=boot, target=target, clock=FakeClock(c_mono), last_probe_mono=p_mono
            )
            self.assertFalse(ok)
            self.assertEqual(d["reason"], "Health probe stale")

        # Clock or last_probe_mono None omits freshness check (preserves omission with stale probe)
        ok, _ = self._call(clock=None, last_probe_mono=0.0)
        self.assertTrue(ok)
        ok, _ = self._call(clock=None, last_probe_mono=None)
        self.assertTrue(ok)
        ok, _ = self._call(clock=self.clock, last_probe_mono=None)
        self.assertTrue(ok)

    def test_unhealthy_probe_status_without_value(self) -> None:
        for st in ("anr_detected", "probe_error", "unknown", None):
            with self.subTest(st=st):
                ok, d = self._call(probe_status=st)
                self.assertFalse(ok)
                self.assertEqual(d["status"], "not_ready")
                self.assertEqual(d["probe_status"], str(st))
                self.assertEqual(d["reason"], f"System probe unhealthy: {st}")


if __name__ == "__main__":
    unittest.main()
