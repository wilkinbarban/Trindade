"""Hermetic unit tests for scripts.f3_guard.health_parse module."""

from __future__ import annotations

import os
import sys
import unittest

_REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

from scripts.f3_guard.health_parse import parse_system_health
from scripts.f3_guard.types import ANREvent, ProbeStatus


class TestSystemHealthStructureRecognition(unittest.TestCase):
    """Characterize empty and unrecognized dumpsys structures fail-closed."""

    def test_empty_or_whitespace_output_returns_unknown(self) -> None:
        for p_dump, w_dump in [("", ""), ("   ", "\n\t  \n")]:
            status, reason, pkgs = parse_system_health(p_dump, w_dump)
            self.assertEqual(status, ProbeStatus.UNKNOWN)
            self.assertEqual(reason, "Empty probe output")
            self.assertEqual(pkgs, set())

    def test_unrecognized_structure_fails_closed_unknown(self) -> None:
        raw = "random log line\nProcessRecord{ab 12:com.pkg/u0a1}\n"
        status, reason, pkgs = parse_system_health(raw, "")
        self.assertEqual(status, ProbeStatus.UNKNOWN)
        self.assertIn("Unrecognized probe output format", reason)
        self.assertEqual(pkgs, set())

    def test_recognized_header_with_no_records_returns_unknown(self) -> None:
        dumps = [
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n",
            "ACTIVITY MANAGER RUNNING PROCESSES\n  ProcessRecord{garbage}\n",
        ]
        for dump in dumps:
            status, reason, pkgs = parse_system_health(dump, "")
            self.assertEqual(status, ProbeStatus.UNKNOWN)
            self.assertEqual(reason, "Recognized section header but no well-formed process records parsed")
            self.assertEqual(pkgs, set())

    def test_window_focus_only_without_process_records_returns_unknown(self) -> None:
        w_dump = "WINDOW MANAGER WINDOWS\nmCurrentFocus=Window{abc u0 com.test/Main}\n"
        status, reason, pkgs = parse_system_health("", w_dump)
        self.assertEqual(status, ProbeStatus.UNKNOWN)
        self.assertEqual(reason, "Recognized section header but no well-formed process records parsed")
        self.assertEqual(pkgs, set())

    def test_empty_records_forgiven_anr_returns_unknown(self) -> None:
        dump = "ACTIVITY MANAGER LAST ANR\nANR in com.legacy.anr\n"
        status, reason, pkgs = parse_system_health(dump, "", baseline_anrs={"com.legacy.anr"})
        self.assertEqual(status, ProbeStatus.UNKNOWN)
        self.assertEqual(reason, "Recognized section header but no well-formed process records parsed")
        self.assertEqual(pkgs, set())


class TestProcessRecordHealthParsing(unittest.TestCase):
    """Characterize ProcessRecord boolean flags and health determination."""

    _HEADER = "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"

    def _make_record(self, pkg: str, pid: int, nr: str | None, cr: str | None) -> str:
        fields = []
        if nr is not None:
            fields.append(f"    notResponding={nr}")
        if cr is not None:
            fields.append(f"    crashing={cr}")
        return f"  ProcessRecord{{1a2b {pid}:{pkg}/u0a10}}\n" + "\n".join(fields) + "\n"

    def test_confirmed_healthy_records_returns_clear(self) -> None:
        dump = self._HEADER + self._make_record("com.test.app", 100, "false", "false")
        status, reason, pkgs = parse_system_health(dump, "")
        self.assertEqual(status, ProbeStatus.CLEAR)
        self.assertEqual(reason, "All monitored processes healthy")
        self.assertEqual(pkgs, set())

    def test_missing_or_invalid_health_flags_returns_unknown(self) -> None:
        cases = [
            self._make_record("com.test.app", 100, "false", None),
            self._make_record("com.test.app", 100, "invalid", "false"),
            self._make_record("com.test.app", 100, "False", "0"),
            self._make_record("com.a", 101, "false", "false") + self._make_record("com.b", 102, None, None),
        ]
        for body in cases:
            dump = self._HEADER + body
            status, reason, pkgs = parse_system_health(dump, "")
            self.assertEqual(status, ProbeStatus.UNKNOWN)
            self.assertEqual(reason, "Some process records lack explicit health fields; cannot confirm health")
            self.assertEqual(pkgs, set())

    def test_process_record_anr_and_crashing_detected(self) -> None:
        nr_dump = self._HEADER + self._make_record("com.nr.app", 201, "true", "false")
        s1, r1, p1 = parse_system_health(nr_dump, "")
        self.assertEqual(s1, ProbeStatus.ANR_DETECTED)
        self.assertIn("com.nr.app(pid=201, reason=notResponding)", r1)
        self.assertEqual(p1, {"com.nr.app"})

        cr_dump = self._HEADER + self._make_record("com.cr.app", 202, "false", "true")
        s2, r2, p2 = parse_system_health(cr_dump, "")
        self.assertEqual(s2, ProbeStatus.ANR_DETECTED)
        self.assertIn("com.cr.app(pid=202, reason=crashing)", r2)
        self.assertEqual(p2, {"com.cr.app"})

    def test_fail_closed_on_partial_record_with_true_flag(self) -> None:
        dump = self._HEADER + self._make_record("com.bad.app", 303, "true", "malformed")
        status, reason, pkgs = parse_system_health(dump, "")
        self.assertEqual(status, ProbeStatus.ANR_DETECTED)
        self.assertIn("com.bad.app(pid=303, reason=notResponding)", reason)
        self.assertEqual(pkgs, {"com.bad.app"})

    def test_healthy_colon_process_record_returns_clear(self) -> None:
        dump = self._HEADER + self._make_record("com.test.app:service", 100, "false", "false")
        status, reason, pkgs = parse_system_health(dump, "")
        self.assertEqual(status, ProbeStatus.CLEAR)
        self.assertEqual(reason, "All monitored processes healthy")
        self.assertEqual(pkgs, set())


class TestLastAnrAndWindowParsing(unittest.TestCase):
    """Characterize dumpsys activity last-anr and window dumps."""

    def test_multiline_last_anr_extracts_pid_and_reason(self) -> None:
        dump = (
            "ACTIVITY MANAGER LAST ANR (dumpsys activity last-anr)\n"
            "ANR in com.android.systemui (com.android.systemui/.SystemUIService)\n"
            "PID: 1234\n"
            "Reason: Input dispatching timed out\n"
        )
        status, reason, pkgs = parse_system_health(dump, "")
        self.assertEqual(status, ProbeStatus.ANR_DETECTED)
        self.assertIn("com.android.systemui(pid=1234, reason=Input dispatching timed out)", reason)
        self.assertEqual(pkgs, {"com.android.systemui"})

    def test_last_anr_default_reason_and_missing_pid(self) -> None:
        dump = "ACTIVITY MANAGER LAST ANR\nANR in com.android.systemui\n"
        status, reason, pkgs = parse_system_health(dump, "")
        self.assertEqual(status, ProbeStatus.ANR_DETECTED)
        self.assertIn("com.android.systemui(pid=n/a, reason=unresponsive)", reason)
        self.assertEqual(pkgs, {"com.android.systemui"})

    def test_window_dump_application_not_responding(self) -> None:
        w_dump = "WINDOW MANAGER WINDOWS\nApplication Not Responding: com.hang.app\n"
        status, reason, pkgs = parse_system_health("", w_dump)
        self.assertEqual(status, ProbeStatus.ANR_DETECTED)
        self.assertIn("com.hang.app(pid=n/a, reason=window_anr)", reason)
        self.assertEqual(pkgs, {"com.hang.app"})


class TestBaselineFilteringAndSummary(unittest.TestCase):
    """Characterize baseline exemptions, non-exemptions, and summaries."""

    _HEALTHY = (
        "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
        "  ProcessRecord{11aa 500:com.main.app/u0a1}\n"
        "    notResponding=false\n"
        "    crashing=false\n"
    )

    def test_legacy_baseline_anrs_exemption_only_for_pid_none_and_unresponsive(self) -> None:
        exempt_dump = self._HEALTHY + "ACTIVITY MANAGER LAST ANR\nANR in com.legacy.anr\n"
        status, _, pkgs = parse_system_health(exempt_dump, "", baseline_anrs={"com.legacy.anr"})
        self.assertEqual(status, ProbeStatus.CLEAR)
        self.assertEqual(pkgs, set())

        not_exempt_pid = self._HEALTHY + "ACTIVITY MANAGER LAST ANR\nANR in com.legacy.anr\nPID: 99\n"
        s2, _, p2 = parse_system_health(not_exempt_pid, "", baseline_anrs={"com.legacy.anr"})
        self.assertEqual(s2, ProbeStatus.ANR_DETECTED)
        self.assertEqual(p2, {"com.legacy.anr"})

        not_exempt_reason = (
            self._HEALTHY + "ACTIVITY MANAGER LAST ANR\nANR in com.legacy.anr\nReason: custom freeze\n"
        )
        s3, _, p3 = parse_system_health(not_exempt_reason, "", baseline_anrs={"com.legacy.anr"})
        self.assertEqual(s3, ProbeStatus.ANR_DETECTED)
        self.assertEqual(p3, {"com.legacy.anr"})

    def test_baseline_events_never_exempts(self) -> None:
        event = ANREvent(package="com.main.app", pid=500, reason="notResponding")
        dump = (
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
            "  ProcessRecord{11aa 500:com.main.app/u0a1}\n"
            "    notResponding=true\n"
            "    crashing=false\n"
        )
        status, reason, pkgs = parse_system_health(dump, "", baseline_events={event})
        self.assertEqual(status, ProbeStatus.ANR_DETECTED)
        self.assertIn("com.main.app", reason)
        self.assertEqual(pkgs, {"com.main.app"})

    def test_healthy_record_legacy_anr_with_baseline_events_returns_clear(self) -> None:
        dump = self._HEALTHY + "ACTIVITY MANAGER LAST ANR\nANR in com.legacy.anr\n"
        status, _, pkgs = parse_system_health(dump, "", baseline_anrs={"com.legacy.anr"}, baseline_events=set())
        self.assertEqual(status, ProbeStatus.CLEAR)
        self.assertEqual(pkgs, set())

    def test_deterministic_sanitized_summary_ordering(self) -> None:
        dump = (
            "ACTIVITY MANAGER LAST ANR\n"
            "ANR in com.zebra\nPID: 10\n"
            "ANR in com.alpha\nPID: 20\n"
            "ANR in com.alpha\nPID: 5\n"
        )
        status, reason, pkgs = parse_system_health(dump, "")
        self.assertEqual(status, ProbeStatus.ANR_DETECTED)
        expected_summary = (
            "Detected active unresponsive process(es): "
            "com.alpha(pid=5, reason=unresponsive), "
            "com.alpha(pid=20, reason=unresponsive), "
            "com.zebra(pid=10, reason=unresponsive)"
        )
        self.assertEqual(reason, expected_summary)
        self.assertEqual(pkgs, {"com.zebra", "com.alpha"})


if __name__ == "__main__":
    unittest.main()
