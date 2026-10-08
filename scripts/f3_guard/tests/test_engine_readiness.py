"""Hermetic unit tests for EngineReadinessHelper (8c3)."""

from __future__ import annotations

import unittest
from unittest.mock import MagicMock

from scripts.f3_guard.clock import FakeClock
from scripts.f3_guard.engine_readiness import EngineReadinessHelper
from scripts.f3_guard.memory import GUARD_RAM_MIN_KB, GUARD_SWAP_MIN_KB
from scripts.f3_guard.probe_lifecycle import ProbeLifecycle
from scripts.f3_guard.readiness import _ProbeState
from scripts.f3_guard.types import BootEvidence, ProbeStatus, ProcessIdentity


class TestEngineReadinessHelper(unittest.TestCase):
    """Hermetic tests covering readiness evaluation, caching, ordering, and errors."""

    def setUp(self) -> None:
        self.target = ProcessIdentity(
            pid=100,
            start_ticks=500,
            uid=1000,
            avd="test_avd",
            port=5554,
            binary_name="qemu-system-x86_64",
        )
        self.controller = MagicMock()
        self.controller.get_start_ticks.return_value = 500
        self.controller.get_uid.return_value = 1000
        self.controller.get_cmdline.return_value = [
            "/usr/bin/qemu-system-x86_64",
            "-avd",
            "test_avd",
            "-port",
            "5554",
        ]
        self.clock = FakeClock(monotonic_start=10.0)
        self.probe_lifecycle = ProbeLifecycle(clock=self.clock, stale_probe_sec=15.0)
        self.probe_lifecycle.record_probe_result(ProbeStatus.CLEAR, 10.0)
        self.valid_boot = BootEvidence(
            boot_completed=True,
            pid=100,
            start_ticks=500,
            timestamp_mono=10.0,
            avd="test_avd",
            uid=1000,
        )

    def _make_helper(self, **kwargs) -> EngineReadinessHelper:
        params = {
            "target": self.target,
            "controller": self.controller,
            "probe_lifecycle": self.probe_lifecycle,
            "clock": self.clock,
            "mem_reader": MagicMock(return_value=(2000000, 1000000)),
            "boot_evidence": self.valid_boot,
            "baseline_established": True,
        }
        params.update(kwargs)
        return EngineReadinessHelper(**params)

    def test_constructor_defaults_and_field_ownership(self) -> None:
        helper = EngineReadinessHelper(self.target, self.controller, self.probe_lifecycle)
        self.assertIs(helper.target, self.target)
        self.assertIs(helper.controller, self.controller)
        self.assertIs(helper.probe_lifecycle, self.probe_lifecycle)
        self.assertIsNotNone(helper.clock)
        self.assertIsNone(helper._mem_reader)
        self.assertIsNone(helper.boot_evidence)
        self.assertTrue(helper.baseline_established)
        self.assertFalse(helper.is_ready)
        self.assertEqual(helper.readiness_details, {})
        self.assertEqual(helper._last_mem_sample, (None, None))
        helper._last_mem_sample = (50, 100)
        self.assertEqual(helper._last_mem_sample, (50, 100))

    def test_identity_verification_first_order_and_exception_propagation(self) -> None:
        self.controller.get_start_ticks.side_effect = RuntimeError("ticks inquiry failed")
        mem_reader = MagicMock()
        boot_fn = MagicMock()
        helper = self._make_helper(mem_reader=mem_reader, boot_evidence=boot_fn)
        with self.assertRaises(RuntimeError) as ctx:
            helper.check_readiness()
        self.assertEqual(str(ctx.exception), "ticks inquiry failed")
        mem_reader.assert_not_called()
        boot_fn.assert_not_called()

    def test_identity_verification_false_flow(self) -> None:
        self.controller.get_start_ticks.return_value = 999
        helper = self._make_helper()
        ready, details = helper.check_readiness()
        self.assertFalse(ready)
        self.assertFalse(helper.is_ready)
        self.assertFalse(details["identity_verified"])
        self.assertEqual(details["status"], "not_ready")
        self.assertIn("identity", details["reason"].lower())

    def test_memory_cache_miss_and_subsequent_hit(self) -> None:
        reader = MagicMock(return_value=(2000000, 1000000))
        helper = self._make_helper(mem_reader=reader)
        ready1, details1 = helper.check_readiness()
        self.assertTrue(ready1)
        self.assertEqual(reader.call_count, 1)
        self.assertEqual(helper._last_mem_sample, (2000000, 1000000))
        ready2, details2 = helper.check_readiness()
        self.assertTrue(ready2)
        self.assertEqual(reader.call_count, 1)

    def test_memory_cache_hit_on_zero_and_false(self) -> None:
        reader = MagicMock()
        helper = self._make_helper(mem_reader=reader)
        helper._last_mem_sample = (0, 1000000)
        helper.check_readiness()
        reader.assert_not_called()
        helper._last_mem_sample = (False, 1000000)
        helper.check_readiness()
        reader.assert_not_called()

    def test_memory_reader_returns_none_first_retries_on_next_call(self) -> None:
        reader = MagicMock(side_effect=[(None, 500000), (2000000, 1000000)])
        helper = self._make_helper(mem_reader=reader)
        ready1, details1 = helper.check_readiness()
        self.assertFalse(ready1)
        self.assertEqual(reader.call_count, 1)
        self.assertEqual(helper._last_mem_sample, (None, 500000))
        ready2, details2 = helper.check_readiness()
        self.assertTrue(ready2)
        self.assertEqual(reader.call_count, 2)
        self.assertEqual(helper._last_mem_sample, (2000000, 1000000))

    def test_memory_reader_ordinary_exception_swallowed_cache_unchanged(self) -> None:
        reader = MagicMock(side_effect=RuntimeError("transient read error"))
        helper = self._make_helper(mem_reader=reader)
        ready, details = helper.check_readiness()
        self.assertFalse(ready)
        self.assertEqual(helper._last_mem_sample, (None, None))
        self.assertIsNone(details["mem_available_kb"])
        self.assertIn("memory capacity failure", details["reason"].lower())

    def test_memory_reader_malformed_unpack_swallowed_cache_unchanged(self) -> None:
        reader = MagicMock(return_value=(1, 2, 3))
        helper = self._make_helper(mem_reader=reader)
        ready, details = helper.check_readiness()
        self.assertFalse(ready)
        self.assertEqual(helper._last_mem_sample, (None, None))

    def test_memory_reader_interrupt_system_exit_propagate_state_unchanged(self) -> None:
        reader = MagicMock(side_effect=KeyboardInterrupt("abort"))
        helper = self._make_helper(mem_reader=reader)
        with self.assertRaises(KeyboardInterrupt):
            helper.check_readiness()
        self.assertEqual(helper._last_mem_sample, (None, None))
        self.assertFalse(helper.is_ready)
        self.assertEqual(helper.readiness_details, {})

        reader.side_effect = SystemExit(3)
        with self.assertRaises(SystemExit):
            helper.check_readiness()
        self.assertEqual(helper._last_mem_sample, (None, None))
        self.assertFalse(helper.is_ready)

    def test_boot_evidence_callable_invoked_zero_args_after_memory(self) -> None:
        calls = []

        def mem_fn():
            calls.append("mem")
            return (2000000, 1000000)

        def boot_fn():
            calls.append("boot")
            return self.valid_boot

        helper = self._make_helper(mem_reader=mem_fn, boot_evidence=boot_fn)
        ready, _ = helper.check_readiness()
        self.assertTrue(ready)
        self.assertEqual(calls, ["mem", "boot"])

    def test_boot_evidence_exception_propagates_cache_remains_prior_state_preserved(self) -> None:
        helper = self._make_helper(
            mem_reader=MagicMock(return_value=(2000000, 1000000)),
            boot_evidence=MagicMock(side_effect=RuntimeError("boot failure")),
        )
        helper.is_ready = False
        helper.readiness_details = {"preserved": True}
        with self.assertRaises(RuntimeError):
            helper.check_readiness()
        self.assertEqual(helper._last_mem_sample, (2000000, 1000000))
        self.assertFalse(helper.is_ready)
        self.assertEqual(helper.readiness_details, {"preserved": True})

    def test_real_evaluate_readiness_happy_path_ready(self) -> None:
        helper = self._make_helper()
        ready, details = helper.check_readiness()
        self.assertTrue(ready)
        self.assertEqual(details["status"], "ready")
        self.assertTrue(helper.is_ready)
        self.assertIs(details, helper.readiness_details)

    def test_real_evaluate_readiness_unhappy_scenarios(self) -> None:
        # 1. Stale probe
        clock1 = FakeClock(monotonic_start=20.0)
        probe1 = ProbeLifecycle(clock=clock1, stale_probe_sec=15.0)
        probe1.record_probe_result(ProbeStatus.CLEAR, 0.0)
        boot1 = BootEvidence(
            boot_completed=True, pid=100, start_ticks=500, timestamp_mono=20.0, avd="test_avd", uid=1000
        )
        helper1 = self._make_helper(clock=clock1, probe_lifecycle=probe1, boot_evidence=boot1)
        ready, details = helper1.check_readiness()
        self.assertFalse(ready)
        self.assertIn("stale", details["reason"].lower())

        # 2. Probe status UNKNOWN
        clock2 = FakeClock(monotonic_start=10.0)
        probe2 = ProbeLifecycle(clock=clock2, stale_probe_sec=15.0)
        probe2.record_probe_result(ProbeStatus.UNKNOWN, 10.0)
        boot2 = BootEvidence(
            boot_completed=True, pid=100, start_ticks=500, timestamp_mono=10.0, avd="test_avd", uid=1000
        )
        helper2 = self._make_helper(clock=clock2, probe_lifecycle=probe2, boot_evidence=boot2)
        ready, details = helper2.check_readiness()
        self.assertFalse(ready)
        self.assertIn("unhealthy", details["reason"].lower())

        # 3. Probe state RUNNING
        clock3 = FakeClock(monotonic_start=10.0)
        probe3 = ProbeLifecycle(clock=clock3, stale_probe_sec=15.0)
        probe3.record_probe_result(ProbeStatus.CLEAR, 10.0)
        probe3._probe_state = _ProbeState.RUNNING
        boot3 = BootEvidence(
            boot_completed=True, pid=100, start_ticks=500, timestamp_mono=10.0, avd="test_avd", uid=1000
        )
        helper3 = self._make_helper(clock=clock3, probe_lifecycle=probe3, boot_evidence=boot3)
        ready, details = helper3.check_readiness()
        self.assertFalse(ready)
        self.assertIn("pending", details["reason"].lower())

        # 4. ANR health baseline not established
        clock4 = FakeClock(monotonic_start=10.0)
        probe4 = ProbeLifecycle(clock=clock4, stale_probe_sec=15.0)
        probe4.record_probe_result(ProbeStatus.CLEAR, 10.0)
        boot4 = BootEvidence(
            boot_completed=True, pid=100, start_ticks=500, timestamp_mono=10.0, avd="test_avd", uid=1000
        )
        helper4 = self._make_helper(
            clock=clock4, probe_lifecycle=probe4, boot_evidence=boot4, baseline_established=False
        )
        ready, details = helper4.check_readiness()
        self.assertFalse(ready)
        self.assertIn("baseline", details["reason"].lower())

        # 5. Memory limits below minimum
        clock5 = FakeClock(monotonic_start=10.0)
        probe5 = ProbeLifecycle(clock=clock5, stale_probe_sec=15.0)
        probe5.record_probe_result(ProbeStatus.CLEAR, 10.0)
        boot5 = BootEvidence(
            boot_completed=True, pid=100, start_ticks=500, timestamp_mono=10.0, avd="test_avd", uid=1000
        )
        helper5 = self._make_helper(
            clock=clock5,
            probe_lifecycle=probe5,
            boot_evidence=boot5,
            mem_reader=MagicMock(return_value=(GUARD_RAM_MIN_KB - 1, GUARD_SWAP_MIN_KB)),
        )
        ready, details = helper5.check_readiness()
        self.assertFalse(ready)
        self.assertIn("memory capacity failure", details["reason"].lower())


if __name__ == "__main__":
    unittest.main()
