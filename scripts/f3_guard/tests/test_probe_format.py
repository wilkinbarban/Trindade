"""Hermetic unit tests for probe formatting constants, patterns, and helpers.

Contracts target future scripts.f3_guard.probe_format module extracted from
scripts/f3_emulator_guard.py lines 295-374.
"""

from __future__ import annotations

import os
import re
import sys
import unittest

_REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

from scripts.f3_guard.probe_format import (
    APP_PACKAGE,
    RECOGNIZED_SECTION_HEADERS,
    SYSTEM_UI_PACKAGE,
    _ANR_HEADER_RE,
    _LAST_ANR_HEADER_RE,
    _NO_ANR_BODY_RE,
    _is_recognized_dumpsys_structure,
    _parse_health_bool,
    _validate_last_anr_content,
    check_aggregate_probe_quota,
    validate_dumpsys_section,
)


class TestProbeFormatConstantsAndRegex(unittest.TestCase):
    """Test package name constants and compiled regex patterns."""

    def test_package_constants(self) -> None:
        self.assertEqual(SYSTEM_UI_PACKAGE, "com.android.systemui")
        self.assertEqual(APP_PACKAGE, "com.trindade.app")

    def test_regex_compiled_patterns(self) -> None:
        self.assertIsInstance(_LAST_ANR_HEADER_RE, re.Pattern)
        self.assertIsInstance(_NO_ANR_BODY_RE, re.Pattern)
        self.assertIsInstance(_ANR_HEADER_RE, re.Pattern)
        self.assertEqual(len(RECOGNIZED_SECTION_HEADERS), 4)
        for pattern in RECOGNIZED_SECTION_HEADERS:
            self.assertIsInstance(pattern, re.Pattern)


class TestValidateLastAnrContent(unittest.TestCase):
    """Test _validate_last_anr_content tri-state return values and body parsing."""

    def test_empty_and_whitespace(self) -> None:
        for text in ("", "   ", "\n\t  \n"):
            with self.subTest(stdout=text):
                ok, reason = _validate_last_anr_content(text)
                self.assertFalse(ok)
                self.assertEqual(reason, "Empty last-anr dump")

    def test_invalid_header(self) -> None:
        garbage = "GARBAGE HEADER\nno ANRs recorded"
        ok, reason = _validate_last_anr_content(garbage)
        self.assertFalse(ok)
        self.assertEqual(reason, "Missing or invalid ACTIVITY MANAGER LAST ANR header")

    def test_header_only_no_body(self) -> None:
        for header in (
            "ACTIVITY MANAGER LAST ANR",
            "ACTIVITY MANAGER LAST ANR (dumpsys activity last-anr)",
            "ACTIVITY MANAGER LAST ANR\n",
            "activity manager last anr\n   \n",
        ):
            with self.subTest(header=header):
                ok, reason = _validate_last_anr_content(header)
                self.assertFalse(ok)
                self.assertEqual(reason, "last-anr header-only with no body")

    def test_no_anr_body_variants(self) -> None:
        bodies = [
            "no ANRs recorded", "no ANRs recorded.", "(no ANRs recorded)",
            "no ANR", "no ANR.", "no ANRs", "no ANRs.", "NO ANRS RECORDED",
        ]
        for body in bodies:
            stdout = f"ACTIVITY MANAGER LAST ANR\n{body}"
            with self.subTest(body=body):
                ok, reason = _validate_last_anr_content(stdout)
                self.assertTrue(ok)
                self.assertEqual(reason, "no_anr")

    def test_valid_anr_single_and_multiple(self) -> None:
        dumps = [
            "ACTIVITY MANAGER LAST ANR\nANR in com.android.systemui (com.android.systemui/.SystemUIService)\nPID: 123",
            "activity manager last anr\nANR in com.trindade.app\nReason: Input dispatching timed out",
            "ACTIVITY MANAGER LAST ANR\nANR in pkg.one\nANR in pkg.two\nsome trailing junk",
        ]
        for dump in dumps:
            with self.subTest(dump=dump):
                ok, reason = _validate_last_anr_content(dump)
                self.assertTrue(ok)
                self.assertEqual(reason, "has_anr")

    def test_truncated_anr_header(self) -> None:
        stdout = "ACTIVITY MANAGER LAST ANR\nanr in"
        ok, reason = _validate_last_anr_content(stdout)
        self.assertFalse(ok)
        self.assertEqual(reason, "Malformed or truncated ANR in header without valid package")

    def test_multiline_anr_header_with_pid_characterization(self) -> None:
        # Characterizes inherited regex acceptance where \s+ matches across newlines,
        # capturing PID as the package name, rather than new hardening.
        stdout = "ACTIVITY MANAGER LAST ANR\nanr in \nPID: 123"
        ok, reason = _validate_last_anr_content(stdout)
        self.assertTrue(ok)
        self.assertEqual(reason, "has_anr")

    def test_unknown_or_malformed_body(self) -> None:
        stdout = "ACTIVITY MANAGER LAST ANR\nsome unrecognized random log line\nanother line"
        ok, reason = _validate_last_anr_content(stdout)
        self.assertFalse(ok)
        self.assertEqual(reason, "Unrecognized or malformed last-anr body")


class TestIsRecognizedDumpsysStructure(unittest.TestCase):
    """Test _is_recognized_dumpsys_structure search and substring acceptance."""

    def test_recognized_headers_case_insensitive(self) -> None:
        headers = [
            "ACTIVITY MANAGER RUNNING PROCESSES", "activity manager running processes",
            "ACTIVITY MANAGER LAST ANR", "activity manager last anr",
            "ACTIVITY MANAGER ANR", "activity manager anr",
            "WINDOW MANAGER WINDOWS", "window manager windows",
        ]
        for header in headers:
            with self.subTest(header=header):
                self.assertTrue(_is_recognized_dumpsys_structure(header))

    def test_embedded_substring_accepted_due_to_search(self) -> None:
        content = "Prefix junk ... ACTIVITY MANAGER ANR ... suffix garbage"
        self.assertTrue(_is_recognized_dumpsys_structure(content))

    def test_lone_fragments_without_headers_return_false(self) -> None:
        fragments = [
            "ProcessRecord{4a1b2c3 1234:com.trindade.app/u0a123}",
            "mCurrentFocus=Window{5e6f7a8 u0 com.trindade.app}",
            "ProcessRecord{abc} and mCurrentFocus=Window{xyz}",
            "",
            "random unformatted log output",
        ]
        for fragment in fragments:
            with self.subTest(fragment=fragment):
                self.assertFalse(_is_recognized_dumpsys_structure(fragment))


class TestParseHealthBool(unittest.TestCase):
    """Test _parse_health_bool strict boolean token parsing."""

    def test_valid_lowercase_tokens(self) -> None:
        body_true = "hasAboveNormal=false\nanr=true\ncrashing=false"
        body_false = "anr=false"
        self.assertIs(_parse_health_bool(body_true, "anr"), True)
        self.assertIs(_parse_health_bool(body_false, "anr"), False)

    def test_word_boundaries_and_prefixes(self) -> None:
        body = "not_anr=true\nanr=false\nanr_extra=true"
        self.assertIs(_parse_health_bool(body, "anr"), False)
        leading_space = " anr=true "
        self.assertIs(_parse_health_bool(leading_space, "anr"), True)

    def test_malformed_case_empty_and_duplicates_return_none(self) -> None:
        cases = [
            ("anr=True", "uppercase True"), ("anr=FALSE", "uppercase FALSE"),
            ("anr=1", "numeric 1"), ("anr=0", "numeric 0"),
            ("anr=", "empty value"), ("anr=true_extra", "trailing junk"),
            ("anr=true\nanr=false", "duplicate occurrence"), ("other_key=true", "absent field"),
        ]
        for body, name in cases:
            with self.subTest(name=name):
                self.assertIsNone(_parse_health_bool(body, "anr"))


class TestValidateDumpsysSection(unittest.TestCase):
    """Test validate_dumpsys_section for processes and last-anr commands."""

    def test_processes_valid_header(self) -> None:
        stdout = "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\nProcessRecord{...}"
        ok, err = validate_dumpsys_section(["dumpsys", "activity", "processes"], stdout)
        self.assertTrue(ok)
        self.assertEqual(err, "")

    def test_processes_case_insensitive_header(self) -> None:
        stdout = "prefix activity manager running processes suffix"
        ok, err = validate_dumpsys_section(["adb", "shell", "dumpsys activity processes"], stdout)
        self.assertTrue(ok)
        self.assertEqual(err, "")

    def test_processes_missing_header_fails(self) -> None:
        stdout = "ProcessRecord{abc1 1234:com.trindade.app/u0a1}\nnotResponding=false\n"
        ok, err = validate_dumpsys_section(["dumpsys", "activity", "processes"], stdout)
        self.assertFalse(ok)
        self.assertEqual(err, "processes dump missing required section header")

    def test_processes_command_match_is_case_sensitive(self) -> None:
        # Command match checks "processes" in joined argv (case-sensitive)
        stdout = "random stdout"
        ok, err = validate_dumpsys_section(["dumpsys", "PROCESSES"], stdout)
        self.assertTrue(ok)
        self.assertEqual(err, "")

    def test_unknown_command_passes_validation(self) -> None:
        stdout = "anything at all"
        ok, err = validate_dumpsys_section(["dumpsys", "window", "windows"], stdout)
        self.assertTrue(ok)
        self.assertEqual(err, "")
        ok_empty, err_empty = validate_dumpsys_section([], stdout)
        self.assertTrue(ok_empty)
        self.assertEqual(err_empty, "")

    def test_both_keywords_checks_processes_then_last_anr_when_processes_fails(self) -> None:
        # If command has both "processes" and "last-anr", processes check fails first if header missing
        cmd = ["dumpsys", "processes", "last-anr"]
        stdout = "ACTIVITY MANAGER LAST ANR\nNo ANRs recorded.\n"
        ok, err = validate_dumpsys_section(cmd, stdout)
        self.assertFalse(ok)
        self.assertEqual(err, "processes dump missing required section header")

    def test_both_keywords_checks_last_anr_if_processes_passes(self) -> None:
        cmd = ["dumpsys", "processes", "last-anr"]
        # Passes processes header (found anywhere in stdout), but fails last-anr body validation
        stdout = "ACTIVITY MANAGER LAST ANR\ngarbage\nACTIVITY MANAGER RUNNING PROCESSES\n"
        ok, err = validate_dumpsys_section(cmd, stdout)
        self.assertFalse(ok)
        self.assertEqual(err, "last-anr dump validation failed: Unrecognized or malformed last-anr body")

    def test_both_keywords_passes_when_both_valid(self) -> None:
        cmd = ["dumpsys", "processes", "last-anr"]
        # Passes processes header (found anywhere) and valid last-anr ANR event
        stdout = (
            "ACTIVITY MANAGER LAST ANR (dumpsys activity last-anr)\n"
            "ANR in com.trindade.app\n"
            "PID: 1234\n"
            "ACTIVITY MANAGER RUNNING PROCESSES\n"
        )
        ok, err = validate_dumpsys_section(cmd, stdout)
        self.assertTrue(ok)
        self.assertEqual(err, "")

    def test_last_anr_matrix_10_cases(self) -> None:
        valid_no_anr = (
            "ACTIVITY MANAGER LAST ANR (dumpsys activity last-anr)\n"
            "No ANRs recorded.\n"
        )
        anr_dump = (
            "ACTIVITY MANAGER LAST ANR (dumpsys activity last-anr)\n"
            "ANR in com.trindade.app\n"
            "PID: 1234\n"
            "Reason: input timeout\n"
        )
        matrix = [
            ("validheadergarbage", "ACTIVITY MANAGER LAST ANR (dumpsys activity last-anr)\ngarbage\n", False, "last-anr dump validation failed: Unrecognized or malformed last-anr body"),
            ("truncatedANR", "ACTIVITY MANAGER LAST ANR (dumpsys activity last-anr)\nANR in\n", False, "last-anr dump validation failed: Malformed or truncated ANR in header without valid package"),
            ("unknownbody", "ACTIVITY MANAGER LAST ANR (dumpsys activity last-anr)\nsome unknown body\n", False, "last-anr dump validation failed: Unrecognized or malformed last-anr body"),
            ("blank", "", False, "last-anr dump validation failed: Empty last-anr dump"),
            ("wrongheader", "WINDOW MANAGER WINDOWS\nWindow #1\n", False, "last-anr dump validation failed: Missing or invalid ACTIVITY MANAGER LAST ANR header"),
            ("headeronly", "ACTIVITY MANAGER LAST ANR (dumpsys activity last-anr)\n", False, "last-anr dump validation failed: last-anr header-only with no body"),
            ("explicitnoANRexact", valid_no_anr, True, ""),
            ("completeANR", anr_dump, True, ""),
            ("mixednoANR_junk", "ACTIVITY MANAGER LAST ANR (dumpsys activity last-anr)\nNo ANRs recorded.\ngarbage\n", False, "last-anr dump validation failed: Unrecognized or malformed last-anr body"),
            ("mixednoANR_ANR", "ACTIVITY MANAGER LAST ANR (dumpsys activity last-anr)\nNo ANRs recorded.\nANR in com.trindade.app\nPID: 1234\nReason: timeout\n", True, ""),
        ]
        cmd = ["dumpsys", "activity", "last-anr"]
        for label, stdout, expected_ok, expected_err in matrix:
            with self.subTest(label=label):
                ok, err = validate_dumpsys_section(cmd, stdout)
                self.assertEqual(ok, expected_ok)
                self.assertEqual(err, expected_err)


class TestCheckAggregateProbeQuota(unittest.TestCase):
    """Test check_aggregate_probe_quota UTF-8 byte accounting and threshold boundaries."""

    def test_under_quota_passes(self) -> None:
        ok, err = check_aggregate_probe_quota(
            collected_stdout=["abc"], stdout="def",
            collected_stderr=["g"], stderr="h",
            max_output_bytes=10
        )
        self.assertTrue(ok)
        self.assertEqual(err, "")

    def test_exact_quota_rejects(self) -> None:
        # 3 + 3 + 1 + 1 = 8 bytes; max_output_bytes=8 is rejected (>= boundary)
        ok, err = check_aggregate_probe_quota(
            collected_stdout=["abc"], stdout="def",
            collected_stderr=["g"], stderr="h",
            max_output_bytes=8
        )
        self.assertFalse(ok)
        self.assertEqual(err, "Probe output truncated exceeding maximum bounds")

    def test_over_quota_rejects(self) -> None:
        ok, err = check_aggregate_probe_quota(
            collected_stdout=["abc"], stdout="def",
            collected_stderr=["g"], stderr="h",
            max_output_bytes=7
        )
        self.assertFalse(ok)
        self.assertEqual(err, "Probe output truncated exceeding maximum bounds")

    def test_empty_inputs_with_positive_quota_passes(self) -> None:
        ok, err = check_aggregate_probe_quota([], "", [], "", max_output_bytes=1)
        self.assertTrue(ok)
        self.assertEqual(err, "")

    def test_empty_inputs_with_zero_quota_rejects(self) -> None:
        # 0 >= 0 is rejected
        ok, err = check_aggregate_probe_quota([], "", [], "", max_output_bytes=0)
        self.assertFalse(ok)
        self.assertEqual(err, "Probe output truncated exceeding maximum bounds")

    def test_multibyte_utf8_counted_correctly(self) -> None:
        # "ñ" is 2 bytes in UTF-8, "🎉" is 4 bytes
        # stdout = "ñ" (2 bytes), stderr = "🎉" (4 bytes) => total 6 bytes
        ok, err = check_aggregate_probe_quota([], "ñ", [], "🎉", max_output_bytes=6)
        self.assertFalse(ok)
        self.assertEqual(err, "Probe output truncated exceeding maximum bounds")

        ok2, err2 = check_aggregate_probe_quota([], "ñ", [], "🎉", max_output_bytes=7)
        self.assertTrue(ok2)
        self.assertEqual(err2, "")

    def test_utf8_replacement_encoding_semantics(self) -> None:
        # surrogate characters like '\ud800' cannot be encoded in UTF-8 without replace
        # encode('utf-8', errors='replace') turns \ud800 into '?' (1 byte, b'\x3f')
        surrogate_str = "\ud800"
        ok, err = check_aggregate_probe_quota([surrogate_str], "", [], "", max_output_bytes=1)
        self.assertFalse(ok)
        self.assertEqual(err, "Probe output truncated exceeding maximum bounds")

    def test_separator_is_not_counted_in_quota(self) -> None:
        # In original code, tot_bytes is sum(len(s.encode(...))) for components.
        # It does NOT count newline separators that would join them later.
        # collected_stdout=["a", "b"], stdout="c" -> 1 + 1 + 1 = 3 bytes (not 5 with "\n")
        ok, err = check_aggregate_probe_quota(["a", "b"], "c", [], "", max_output_bytes=4)
        self.assertTrue(ok)
        self.assertEqual(err, "")
