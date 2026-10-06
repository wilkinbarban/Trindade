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
