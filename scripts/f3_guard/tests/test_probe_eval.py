"""Hermetic unit tests for scripts.f3_guard.probe_eval module."""

from __future__ import annotations

import os
import sys
import unittest
from unittest.mock import patch

_REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

from scripts.f3_guard.probe_eval import evaluate_adb_probe_result
from scripts.f3_guard.types import ANREvent, ProbeStatus


class TestEvaluateAdbProbeResultPrecedenceAndFailClosed(unittest.TestCase):
    """Verifies timeout, nonzero return code, null bytes, and empty output handling."""

    def test_probe_timeout_fail_closed(self) -> None:
        status, reason, pkgs = evaluate_adb_probe_result(
            returncode=-1,
            stdout="",
            stderr="TIMED_OUT",
            is_timeout=True,
            baseline_anrs=set(),
        )
        self.assertEqual(status, ProbeStatus.PROBE_ERROR)
        self.assertEqual(reason, "ADB probe timeout expired")
        self.assertEqual(pkgs, set())

    def test_probe_timeout_takes_precedence_over_nonzero_and_corruption(self) -> None:
        status, reason, pkgs = evaluate_adb_probe_result(
            returncode=1,
            stdout="corrupted\x00data",
            stderr="fatal error",
            is_timeout=True,
            baseline_anrs=set(),
        )
        self.assertEqual(status, ProbeStatus.PROBE_ERROR)
        self.assertEqual(reason, "ADB probe timeout expired")
        self.assertEqual(pkgs, set())

    def test_probe_nonzero_exit_code_with_stderr_fail_closed(self) -> None:
        status, reason, pkgs = evaluate_adb_probe_result(
            returncode=1,
            stdout="",
            stderr="error: device offline",
            is_timeout=False,
            baseline_anrs=set(),
        )
        self.assertEqual(status, ProbeStatus.PROBE_ERROR)
        self.assertEqual(reason, "ADB probe failed: error: device offline")
        self.assertEqual(pkgs, set())

    def test_probe_nonzero_exit_code_strips_stderr_whitespace(self) -> None:
        status, reason, pkgs = evaluate_adb_probe_result(
            returncode=2,
            stdout="",
            stderr="  \n  device unauthorized  \t\n",
            is_timeout=False,
        )
        self.assertEqual(status, ProbeStatus.PROBE_ERROR)
        self.assertEqual(reason, "ADB probe failed: device unauthorized")
        self.assertEqual(pkgs, set())

    def test_probe_nonzero_exit_code_fallback_when_stderr_empty_or_whitespace(self) -> None:
        for empty_err in ("", "   ", "\n\t  \n"):
            with self.subTest(stderr=empty_err):
                status, reason, pkgs = evaluate_adb_probe_result(
                    returncode=127,
                    stdout="",
                    stderr=empty_err,
                    is_timeout=False,
                )
                self.assertEqual(status, ProbeStatus.PROBE_ERROR)
                self.assertEqual(reason, "ADB probe failed: ADB process exited with code 127")
                self.assertEqual(pkgs, set())

    def test_probe_nonzero_exit_code_takes_precedence_over_null_bytes(self) -> None:
        status, reason, pkgs = evaluate_adb_probe_result(
            returncode=1,
            stdout="raw\x00payload",
            stderr="device disconnected",
            is_timeout=False,
        )
        self.assertEqual(status, ProbeStatus.PROBE_ERROR)
        self.assertEqual(reason, "ADB probe failed: device disconnected")
        self.assertEqual(pkgs, set())

    def test_probe_corrupted_null_byte_fails_closed_unknown(self) -> None:
        status, reason, pkgs = evaluate_adb_probe_result(
            returncode=0,
            stdout="?? UNKNOWN BUS PROTOCOL DUMP \x00\x01\x02",
            stderr="",
            is_timeout=False,
            baseline_anrs=set(),
        )
        self.assertEqual(status, ProbeStatus.UNKNOWN)
        self.assertEqual(reason, "Unknown or corrupted probe output format")
        self.assertEqual(pkgs, set())

    def test_probe_empty_stdout_and_stderr_fails_closed_unknown(self) -> None:
        for out, err in [("", ""), ("   ", ""), ("", "  \t\n"), ("  ", "   ")]:
            with self.subTest(stdout=out, stderr=err):
                status, reason, pkgs = evaluate_adb_probe_result(
                    returncode=0,
                    stdout=out,
                    stderr=err,
                    is_timeout=False,
                )
                self.assertEqual(status, ProbeStatus.UNKNOWN)
                self.assertEqual(reason, "Unknown or corrupted probe output format")
                self.assertEqual(pkgs, set())


class TestEvaluateAdbProbeResultDelegationAndIdentity(unittest.TestCase):
    """Verifies hermetic argument forwarding and result identity delegation."""

    @patch("scripts.f3_guard.probe_eval.parse_system_health")
    def test_delegates_to_parse_system_health_with_exact_args(self, mock_parse) -> None:
        sentinel_result = (ProbeStatus.CLEAR, "All monitored processes healthy", set())
        mock_parse.return_value = sentinel_result

        test_events = {ANREvent(package="com.example.app", pid=100, reason="test")}
        test_anrs = {"com.example.app"}

        status, reason, pkgs = evaluate_adb_probe_result(
            returncode=0,
            stdout="ACTIVITY MANAGER RUNNING PROCESSES\n",
            stderr="",
            is_timeout=False,
            baseline_anrs=test_anrs,
            baseline_events=test_events,
        )

        mock_parse.assert_called_once_with(
            process_dump="ACTIVITY MANAGER RUNNING PROCESSES\n",
            window_dump="",
            baseline_anrs=test_anrs,
            baseline_events=test_events,
        )
        self.assertIs(status, sentinel_result[0])
        self.assertEqual(reason, sentinel_result[1])
        self.assertIs(pkgs, sentinel_result[2])


class TestEvaluateAdbProbeResultRealHealthIntegration(unittest.TestCase):
    """End-to-end integration tests exercising real parse_system_health outcomes."""

    def test_clean_monitored_processes_returns_clear(self) -> None:
        valid_dump = (
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
            "  ProcessRecord{1a2b3c 1234:com.android.systemui/u0a123}\n"
            "    notResponding=false\n"
            "    crashing=false\n"
        )
        status, reason, pkgs = evaluate_adb_probe_result(
            returncode=0,
            stdout=valid_dump,
            stderr="",
            is_timeout=False,
        )
        self.assertEqual(status, ProbeStatus.CLEAR)
        self.assertEqual(reason, "All monitored processes healthy")
        self.assertEqual(pkgs, set())

    def test_anr_process_detected_in_processes_dump(self) -> None:
        anr_dump = (
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
            "  ProcessRecord{1a2b3c 1234:com.android.systemui/u0a123}\n"
            "    notResponding=true\n"
            "    crashing=false\n"
        )
        status, reason, pkgs = evaluate_adb_probe_result(
            returncode=0,
            stdout=anr_dump,
            stderr="",
            is_timeout=False,
        )
        self.assertEqual(status, ProbeStatus.ANR_DETECTED)
        self.assertIn("com.android.systemui", reason)
        self.assertEqual(pkgs, {"com.android.systemui"})

    def test_dumpsys_last_anr_history_detected(self) -> None:
        last_anr_dump = (
            "ACTIVITY MANAGER LAST ANR (dumpsys activity last-anr)\n"
            "ANR in com.android.systemui (com.android.systemui/.SystemUIService)\n"
            "PID: 1234\n"
            "Reason: Input dispatching timed out\n"
        )
        status, reason, pkgs = evaluate_adb_probe_result(
            returncode=0,
            stdout=last_anr_dump,
            stderr="",
            is_timeout=False,
        )
        self.assertEqual(status, ProbeStatus.ANR_DETECTED)
        self.assertIn("com.android.systemui", reason)
        self.assertEqual(pkgs, {"com.android.systemui"})

    def test_unrecognized_probe_output_fails_closed_unknown(self) -> None:
        status, reason, pkgs = evaluate_adb_probe_result(
            returncode=0,
            stdout="some unrecognized garbage output without dumpsys activity structures",
            stderr="",
            is_timeout=False,
        )
        self.assertEqual(status, ProbeStatus.UNKNOWN)
        self.assertIn("Unrecognized probe output format without known dumpsys structures", reason)
        self.assertEqual(pkgs, set())

    def test_real_health_baseline_recurrence_not_forgiven(self) -> None:
        # A new process record with pid and notResponding=true is not forgiven by legacy baseline_anrs
        anr_dump = (
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
            "  ProcessRecord{1a2b 5678:com.android.systemui/u0a123}\n"
            "    notResponding=true\n"
            "    crashing=false\n"
        )
        status, reason, pkgs = evaluate_adb_probe_result(
            returncode=0,
            stdout=anr_dump,
            stderr="",
            is_timeout=False,
            baseline_anrs={"com.android.systemui"},
            baseline_events={ANREvent(package="com.android.systemui", pid=1234, reason="old")},
        )
        self.assertEqual(status, ProbeStatus.ANR_DETECTED)
        self.assertIn("com.android.systemui", reason)
        self.assertEqual(pkgs, {"com.android.systemui"})


if __name__ == "__main__":
    unittest.main()
