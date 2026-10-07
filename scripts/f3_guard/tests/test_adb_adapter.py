"""Hermetic unit tests for scripts.f3_guard.adb_adapter module."""

from __future__ import annotations

import os
import signal
import sys
import unittest

_REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

from scripts.f3_guard.adb_adapter import (
    DEFAULT_ADB_SERIAL,
    AdbProbeAdapter,
)
from scripts.f3_guard.clock import FakeClock, SystemClock
from scripts.f3_guard.probe_handle import (
    MAX_PROBE_OUTPUT_BYTES,
    ProbeCommandHandle,
    SubprocessProbeHandle,
)
from scripts.f3_guard.readiness import DEFAULT_MAX_STALE_PROBE_SEC


class FakeProbeHandle(ProbeCommandHandle):
    """Test fake for ProbeCommandHandle with configurable responses."""

    def __init__(
        self,
        exit_code: int | None = None,
        stdout: str = "",
        stderr: str = "",
        reapable: bool = True,
    ) -> None:
        self._exit_code = exit_code
        self._stdout = stdout
        self._stderr = stderr
        self._reapable = reapable
        self.terminated = False
        self.signal_received: int | None = None
        self.cleanup_count = 0

    def poll(self) -> int | None:
        return self._exit_code

    def read_available(self, max_bytes: int) -> tuple[str, str]:
        return self._stdout[:max_bytes], self._stderr[:max_bytes]

    def terminate(self) -> None:
        self.terminated = True
        self.signal_received = signal.SIGTERM

    def cleanup(self) -> bool:
        self.cleanup_count += 1
        return self._reapable


class TestAdbProbeAdapter(unittest.TestCase):
    """Hermetic unit tests for AdbProbeAdapter."""

    def test_constructor_defaults(self) -> None:
        adapter = AdbProbeAdapter()
        self.assertEqual(adapter._serial, DEFAULT_ADB_SERIAL)
        self.assertEqual(adapter._serial, "emulator-5556")
        self.assertEqual(adapter._timeout_sec, DEFAULT_MAX_STALE_PROBE_SEC)
        self.assertEqual(adapter._max_output_bytes, MAX_PROBE_OUTPUT_BYTES)
        self.assertIs(adapter._transport, SubprocessProbeHandle)
        self.assertIsInstance(adapter._clock, SystemClock)
        self.assertEqual(len(adapter._commands), 2)
        self.assertIn("processes", " ".join(adapter._commands[0]))
        self.assertIn("last-anr", " ".join(adapter._commands[1]))
        self.assertFalse(adapter.has_pending_cleanup)

    def test_constructor_custom_options(self) -> None:
        clock = FakeClock()
        custom_handle = FakeProbeHandle()
        adapter_single = AdbProbeAdapter(
            serial="custom-serial",
            command=["adb", "version"],
            transport=lambda cmd: custom_handle,
            clock=clock,
            timeout_sec=5.0,
            max_output_bytes=2048,
        )
        self.assertEqual(adapter_single._serial, "custom-serial")
        self.assertEqual(adapter_single._commands, [["adb", "version"]])
        self.assertEqual(adapter_single._timeout_sec, 5.0)
        self.assertEqual(adapter_single._max_output_bytes, 2048)
        self.assertIs(adapter_single._clock, clock)

        adapter_multi = AdbProbeAdapter(
            commands=[["cmd1", "arg1"], ["cmd2"]],
            timeout_sec=999.0,
        )
        self.assertEqual(adapter_multi._commands, [["cmd1", "arg1"], ["cmd2"]])
        self.assertEqual(adapter_multi._timeout_sec, DEFAULT_MAX_STALE_PROBE_SEC)

    def test_multi_command_progression_success(self) -> None:
        clock = FakeClock()
        valid_processes = (
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
            "  ProcessRecord{abc1 1234:com.trindade.app/u0a1}\n"
        )
        valid_last_anr = (
            "ACTIVITY MANAGER LAST ANR (dumpsys activity last-anr)\n"
            "No ANRs recorded.\n"
        )
        spawned: list[list[str]] = []

        def fake_transport(cmd: list[str]) -> FakeProbeHandle:
            spawned.append(cmd)
            if "last-anr" in " ".join(cmd):
                return FakeProbeHandle(exit_code=0, stdout=valid_last_anr)
            return FakeProbeHandle(exit_code=0, stdout=valid_processes)

        adapter = AdbProbeAdapter(transport=fake_transport, clock=clock)
        adapter.start_probe()
        done1, res1 = adapter.poll_probe()
        self.assertFalse(done1)
        self.assertIsNone(res1)
        self.assertEqual(len(spawned), 2)

        done2, res2 = adapter.poll_probe()
        self.assertTrue(done2)
        self.assertIsNotNone(res2)
        retcode, stdout, stderr, is_timeout = res2
        self.assertEqual(retcode, 0)
        self.assertIn("RUNNING PROCESSES", stdout)
        self.assertIn("No ANRs recorded", stdout)
        self.assertEqual(stderr, "")
        self.assertFalse(is_timeout)

    def test_step_command_failures(self) -> None:
        h0 = FakeProbeHandle(exit_code=1, stderr="adb: device offline")
        adapter0 = AdbProbeAdapter(transport=lambda cmd: h0)
        adapter0.start_probe()
        done, res = adapter0.poll_probe()
        self.assertTrue(done)
        self.assertEqual(res, (1, "", "adb: device offline", False))

        spawn_idx = 0

        def fake_transport(cmd: list[str]) -> FakeProbeHandle:
            nonlocal spawn_idx
            spawn_idx += 1
            if spawn_idx == 1:
                return FakeProbeHandle(
                    exit_code=0,
                    stdout="ACTIVITY MANAGER RUNNING PROCESSES\n",
                )
            return FakeProbeHandle(exit_code=2, stderr="dumpsys last-anr failed")

        adapter1 = AdbProbeAdapter(transport=fake_transport)
        adapter1.start_probe()
        done1, _ = adapter1.poll_probe()
        self.assertFalse(done1)
        done2, res2 = adapter1.poll_probe()
        self.assertTrue(done2)
        retcode, stdout, stderr, is_timeout = res2
        self.assertEqual(retcode, 2)
        self.assertEqual(stderr, "dumpsys last-anr failed")
        self.assertFalse(is_timeout)

    def test_prior_close_error_does_not_poison_next_probe(self) -> None:
        h1 = FakeProbeHandle(exit_code=0, stdout="p1\n")
        h1._close_error = "pipe close error"
        h2 = FakeProbeHandle(exit_code=0, stdout="p2\n")
        handles = [h1, h2]
        adapter = AdbProbeAdapter(command=["cmd"], transport=lambda cmd: handles.pop(0))
        adapter.start_probe()
        done1, res1 = adapter.poll_probe()
        self.assertTrue(done1)
        self.assertEqual(res1, (1, "p1\n", "Probe cleanup error: pipe close error", False))
        self.assertEqual(adapter.cleanup_error, "pipe close error")
        self.assertTrue(adapter.has_pending_cleanup)
        adapter.start_probe()
        done2, res2 = adapter.poll_probe()
        self.assertTrue(done2)
        self.assertEqual(res2, (0, "p2\n", "", False))
        self.assertEqual(adapter.cleanup_error, "pipe close error")
        self.assertTrue(adapter.has_pending_cleanup)


if __name__ == "__main__":
    unittest.main()
