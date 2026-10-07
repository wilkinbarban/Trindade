"""Hermetic integration tests for ProbeLifecycle and AdbProbeAdapter wiring."""

from __future__ import annotations

import unittest
from typing import List, Optional, Tuple

from scripts.f3_guard.adb_adapter import AdbProbeAdapter
from scripts.f3_guard.clock import FakeClock
from scripts.f3_guard.probe_handle import ProbeCommandHandle
from scripts.f3_guard.probe_lifecycle import ProbeLifecycle
from scripts.f3_guard.types import ProbeStatus


class FakeProbeHandle(ProbeCommandHandle):
    """Minimal fake handle implementing ProbeCommandHandle public methods."""

    def __init__(
        self,
        exit_code: Optional[int] = 0,
        stdout: str = "",
        stderr: str = "",
        reapable: bool = True,
        overflow: bool = False,
    ) -> None:
        self._exit_code = exit_code
        self._stdout = stdout
        self._stderr = stderr
        self._reapable = reapable
        self._overflow = overflow
        self.terminated: bool = False
        self.cleanup_count: int = 0

    def poll(self) -> Optional[int]:
        return self._exit_code

    def read_available(self, max_bytes: int) -> Tuple[str, str]:
        return self._stdout[:max_bytes], self._stderr[:max_bytes]

    def terminate(self) -> None:
        self.terminated = True

    def cleanup(self) -> bool:
        self.cleanup_count += 1
        return self._reapable


class TestProbeLifecycleWiring(unittest.TestCase):
    """Integration tests connecting real ProbeLifecycle and real AdbProbeAdapter."""

    def setUp(self) -> None:
        self.clock = FakeClock()

    def test_teardown_unreaped_handles_diagnostic_reports_accurate_count(self) -> None:
        """Teardown reports accurate count rather than misleading 0 when handles unreaped."""
        h = FakeProbeHandle(exit_code=0, reapable=False)
        adapter = AdbProbeAdapter(transport=lambda cmd: h, clock=self.clock)
        lc = ProbeLifecycle(adapter=adapter, clock=self.clock)

        lc.start_cycle()
        lc.cleanup_teardown()

        self.assertTrue(lc.has_probe_cleanup_failure)
        self.assertEqual(lc.probe_cleanup_error, "Pending un-reaped probe handles (1)")

    def test_teardown_without_cleanup_probe_reports_accurate_count(self) -> None:
        """Teardown with custom cleanup_probe queries adapter public contract, not misleading 0."""
        h = FakeProbeHandle(exit_code=0, reapable=False)
        adapter = AdbProbeAdapter(transport=lambda cmd: h, clock=self.clock)
        lc = ProbeLifecycle(adapter=adapter, clock=self.clock, cleanup_probe=lambda: None)

        lc.start_cycle()
        adapter.cancel()
        lc.cleanup_teardown()

        self.assertTrue(lc.has_probe_cleanup_failure)
        self.assertEqual(lc.probe_cleanup_error, "Pending un-reaped probe handles (1)")

    def test_multistep_progression_valid_dumpsys_to_healthy_status(self) -> None:
        """Multistep execution transitions from RUNNING to IDLE with HEALTHY status."""
        proc_dump = (
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
            "  ProcessRecord{abc1 1234:com.trindade.app/u0a1}\n"
            "    notResponding=false crashing=false\n"
        )
        anr_dump = (
            "ACTIVITY MANAGER LAST ANR (dumpsys activity last-anr)\n"
            "No ANRs recorded.\n"
        )
        cmds: List[List[str]] = []

        def transport(cmd: List[str]) -> FakeProbeHandle:
            cmds.append(cmd)
            if "last-anr" in " ".join(cmd):
                return FakeProbeHandle(exit_code=0, stdout=anr_dump)
            return FakeProbeHandle(exit_code=0, stdout=proc_dump)

        adapter = AdbProbeAdapter(transport=transport, clock=self.clock)
        lc = ProbeLifecycle(adapter=adapter, clock=self.clock)

        self.assertEqual(lc.last_probe_status, ProbeStatus.UNKNOWN)

        # Step 1: starts cycle, executes first command (processes), initiates second
        halted, reason = lc.step_probe()
        self.assertFalse(halted)
        self.assertIsNone(reason)
        self.assertEqual(len(cmds), 2)
        self.assertEqual(lc.last_probe_status, ProbeStatus.UNKNOWN)

        # Step 2: finishes second command (last-anr), evaluates health
        halted, reason = lc.step_probe()
        self.assertFalse(halted)
        self.assertIsNone(reason)
        self.assertEqual(lc.last_probe_status, ProbeStatus.CLEAR)

        halted, reason = lc.check_health()
        self.assertFalse(halted)
        self.assertIsNone(reason)

    def test_failure_transport_exit_code_evaluated_closed(self) -> None:
        """Command failure sets PROBE_ERROR status and check_health halts."""
        h = FakeProbeHandle(exit_code=1, stderr="adb: device offline")
        adapter = AdbProbeAdapter(transport=lambda cmd: h, clock=self.clock)
        lc = ProbeLifecycle(adapter=adapter, clock=self.clock)

        halted, reason = lc.step_probe()
        self.assertFalse(halted)
        self.assertEqual(lc.last_probe_status, ProbeStatus.PROBE_ERROR)

        halted, reason = lc.check_health()
        self.assertTrue(halted)
        self.assertEqual(reason, "Health probe failure: probe_error")

    def test_quota_overflow_handling(self) -> None:
        """Handle output overflow causes PROBE_ERROR status and halts health check."""
        h = FakeProbeHandle(exit_code=0, overflow=True)
        adapter = AdbProbeAdapter(transport=lambda cmd: h, clock=self.clock)
        lc = ProbeLifecycle(adapter=adapter, clock=self.clock)

        halted, reason = lc.step_probe()
        self.assertFalse(halted)
        self.assertEqual(lc.last_probe_status, ProbeStatus.PROBE_ERROR)

        halted, reason = lc.check_health()
        self.assertTrue(halted)

    def test_teardown_cleanup_error_precedence_over_pending_count(self) -> None:
        """Explicit cleanup_error takes precedence over pending handles count."""
        h = FakeProbeHandle(exit_code=0, reapable=False)
        adapter = AdbProbeAdapter(transport=lambda cmd: h, clock=self.clock)
        adapter.cleanup_error = "I/O error closing handle"
        lc = ProbeLifecycle(adapter=adapter, clock=self.clock)

        lc.start_cycle()
        lc.cleanup_teardown()

        self.assertTrue(lc.has_probe_cleanup_failure)
        self.assertEqual(lc.probe_cleanup_error, "I/O error closing handle")

    def test_duck_typed_adapter_without_count_uses_generic_diagnostic(self) -> None:
        """Duck-typed adapter without count contract produces honest generic diagnostic."""
        class MinimalDuckAdapter:
            has_pending_cleanup = True
            cleanup_error = None

        lc = ProbeLifecycle(adapter=MinimalDuckAdapter(), clock=self.clock)
        lc.cleanup_teardown()

        self.assertTrue(lc.has_probe_cleanup_failure)
        self.assertEqual(lc.probe_cleanup_error, "Pending un-reaped probe handles")

    def test_adversarial_adapter_trapping_private_lookups_produces_generic_diagnostic(self) -> None:
        """Adversarial adapter trapping private lookups produces honest generic diagnostic without exception."""
        class AdversarialTrapAdapter:
            has_pending_cleanup = True
            cleanup_error = None

            def __getattr__(self, name: str):
                if name in ("_pending_reap", "_coordinator") or name.startswith("_"):
                    raise AssertionError(f"Private adapter lookup forbidden: {name}")
                raise AttributeError(name)

        lc = ProbeLifecycle(adapter=AdversarialTrapAdapter(), clock=self.clock)
        lc.cleanup_teardown()

        self.assertTrue(lc.has_probe_cleanup_failure)
        self.assertEqual(lc.probe_cleanup_error, "Pending un-reaped probe handles")

    def test_adapter_pending_reap_count_contract(self) -> None:
        """AdbProbeAdapter exposes pending_reap_count as narrow read-only int."""
        h = FakeProbeHandle(exit_code=0, reapable=False)
        adapter = AdbProbeAdapter(transport=lambda cmd: h, clock=self.clock)

        self.assertEqual(adapter.pending_reap_count, 0)
        adapter.start_probe()
        adapter.cancel()
        self.assertEqual(adapter.pending_reap_count, 1)

    def test_clean_teardown_leaves_clean_lifecycle(self) -> None:
        """Teardown with fully reapable handle leaves no failure flags or error."""
        h = FakeProbeHandle(exit_code=0, reapable=True)
        adapter = AdbProbeAdapter(transport=lambda cmd: h, clock=self.clock)
        lc = ProbeLifecycle(adapter=adapter, clock=self.clock)

        lc.start_cycle()
        lc.cleanup_teardown()

        self.assertFalse(lc.has_probe_cleanup_failure)
        self.assertIsNone(lc.probe_cleanup_error)


if __name__ == "__main__":
    unittest.main()
