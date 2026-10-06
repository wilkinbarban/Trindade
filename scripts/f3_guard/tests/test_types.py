"""Hermetic unit tests for scripts.f3_guard.types module."""

from __future__ import annotations

from dataclasses import FrozenInstanceError
import json
import os
import sys
import unittest

_REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

from scripts.f3_guard.types import (
    ANREvent,
    BootEvidence,
    LifecycleState,
    ProbeStatus,
    ProcessIdentity,
    TerminationAttempt,
    TerminationAttemptRecord,
    TerminationClaimStatus,
    TerminationOutcome,
    TerminationRecord,
)


class TestEnumContracts(unittest.TestCase):
    """Unit tests for string enum values, JSON serialization, and state counts."""

    def test_probe_status_values_and_json(self) -> None:
        expected = {
            "CLEAR": "clear",
            "ANR_DETECTED": "anr_detected",
            "PROBE_ERROR": "probe_error",
            "UNKNOWN": "unknown",
        }
        self.assertEqual(len(ProbeStatus), 4)
        for name, value in expected.items():
            member = getattr(ProbeStatus, name)
            self.assertEqual(member, value)
            self.assertIsInstance(member, str)
        self.assertEqual(json.dumps({"status": ProbeStatus.ANR_DETECTED}), '{"status": "anr_detected"}')

    def test_lifecycle_state_ten_states_and_json(self) -> None:
        expected = {
            "INITIALIZING": "initializing",
            "RUNNING": "running",
            "STOPPED": "stopped",
            "TERMINATION_PENDING": "termination_pending",
            "TERMINATION_FAILED": "termination_failed",
            "PID_MISMATCH": "pid_mismatch",
            "ZOMBIE_UNREAPED": "zombie_unreaped",
            "ZOMBIE_PENDING": "zombie_pending",
            "UNKNOWN": "unknown",
            "ERROR": "error",
        }
        self.assertEqual(len(LifecycleState), 10)
        for name, value in expected.items():
            member = getattr(LifecycleState, name)
            self.assertEqual(member, value)
            self.assertIsInstance(member, str)
        self.assertEqual(json.dumps({"state": LifecycleState.STOPPED}), '{"state": "stopped"}')

    def test_termination_claim_status_five_states_and_status_identity(self) -> None:
        expected = {
            "NOT_ATTEMPTED": "not_attempted",
            "CLAIMED": "claimed",
            "ALREADY_CLAIMED": "already_claimed",
            "MISMATCH": "mismatch",
            "IN_PROGRESS": "in_progress",
        }
        self.assertEqual(len(TerminationClaimStatus), 5)
        for name, value in expected.items():
            member = getattr(TerminationClaimStatus, name)
            self.assertEqual(member, value)
            self.assertIsInstance(member, str)
            self.assertIs(member.status, member)
        self.assertEqual(json.dumps({"claim": TerminationClaimStatus.CLAIMED}), '{"claim": "claimed"}')


class TestProcessIdentity(unittest.TestCase):
    """Unit tests for ProcessIdentity fields, defaults, frozenness, and hashing."""

    def test_fields_defaults_and_override(self) -> None:
        proc = ProcessIdentity(1234, 5678, 1000, "test_avd", 5554, "qemu")
        self.assertEqual((proc.pid, proc.start_ticks, proc.uid), (1234, 5678, 1000))
        self.assertEqual((proc.avd, proc.port, proc.binary_name), ("test_avd", 5554, "qemu"))
        self.assertFalse(proc.is_direct_child)

        child = ProcessIdentity(1234, 5678, 1000, "test_avd", 5554, "qemu", is_direct_child=True)
        self.assertTrue(child.is_direct_child)

    def test_frozen_and_hash_distinctions(self) -> None:
        p1 = ProcessIdentity(10, 20, 1000, "avd", 5554, "qemu")
        p2 = ProcessIdentity(10, 20, 1000, "avd", 5554, "qemu")
        p_diff = ProcessIdentity(11, 20, 1000, "avd", 5554, "qemu")
        p_child = ProcessIdentity(10, 20, 1000, "avd", 5554, "qemu", is_direct_child=True)

        with self.assertRaises(FrozenInstanceError):
            p1.pid = 9999  # type: ignore[misc]

        self.assertEqual(p1, p2)
        self.assertEqual(hash(p1), hash(p2))
        self.assertNotEqual(p1, p_diff)
        self.assertNotEqual(p1, p_child)
        self.assertEqual(len({p1, p2, p_diff, p_child}), 3)


class TestANREvent(unittest.TestCase):
    """Unit tests for ANREvent fields, defaults, frozenness, and fingerprints."""

    def test_fields_defaults_and_immutability(self) -> None:
        event = ANREvent(package="com.example.app")
        self.assertEqual(event.package, "com.example.app")
        self.assertIsNone(event.pid)
        self.assertEqual(event.reason, "")
        self.assertEqual(event.occurrence_tag, "")
        with self.assertRaises(FrozenInstanceError):
            event.package = "other"  # type: ignore[misc]

    def test_equality_hash_fingerprint_distinctions(self) -> None:
        e1 = ANREvent("com.app", pid=100, reason="dispatch timeout", occurrence_tag="tag1")
        e2 = ANREvent("com.app", pid=100, reason="dispatch timeout", occurrence_tag="tag1")
        e_diff_tag = ANREvent("com.app", pid=100, reason="dispatch timeout", occurrence_tag="tag2")
        e_diff_pkg = ANREvent("com.other", pid=100, reason="dispatch timeout", occurrence_tag="tag1")

        self.assertEqual(e1, e2)
        self.assertEqual(hash(e1), hash(e2))
        self.assertNotEqual(e1, e_diff_tag)
        self.assertNotEqual(e1, e_diff_pkg)
        self.assertEqual(len({e1, e2, e_diff_tag, e_diff_pkg}), 3)


class TestTerminationTypes(unittest.TestCase):
    """Unit tests for TerminationOutcome, AttemptRecord, aliases, and defaults."""

    def test_outcome_fields_defaults_and_exception_identity(self) -> None:
        outcome = TerminationOutcome(state=LifecycleState.STOPPED)
        self.assertEqual(outcome.state, LifecycleState.STOPPED)
        self.assertEqual(outcome.detail, "")
        self.assertIsNone(outcome.primary_error)
        self.assertTrue(outcome.completed)
        self.assertIsNone(outcome.primary_exception)

        err = RuntimeError("sigterm failure")
        outcome_err = TerminationOutcome(LifecycleState.TERMINATION_FAILED, "failed signal", err, completed=False)
        self.assertFalse(outcome_err.completed)
        self.assertIs(outcome_err.primary_error, err)
        self.assertIs(outcome_err.primary_exception, err)
        self.assertIs(outcome_err.primary_exception, outcome_err.primary_error)

        with self.assertRaises(FrozenInstanceError):
            outcome.detail = "mutated"  # type: ignore[misc]

    def test_attempt_record_controller_any_defaults_and_explicit(self) -> None:
        ctrl = object()
        rec = TerminationAttemptRecord(123, 456, 1000, "test_avd", controller=ctrl)
        self.assertEqual((rec.pid, rec.start_ticks, rec.uid, rec.avd), (123, 456, 1000, "test_avd"))
        self.assertIs(rec.controller, ctrl)
        self.assertIsNone(rec.outcome)
        self.assertIsNone(rec.target)

        target = ProcessIdentity(123, 456, 1000, "test_avd", 5554, "qemu")
        outcome = TerminationOutcome(LifecycleState.STOPPED)
        rec_full = TerminationAttemptRecord(123, 456, 1000, "test_avd", None, outcome=outcome, target=target)
        self.assertIs(rec_full.outcome, outcome)
        self.assertIs(rec_full.target, target)

        with self.assertRaises(FrozenInstanceError):
            rec.pid = 999  # type: ignore[misc]

    def test_aliases_identity(self) -> None:
        self.assertIs(TerminationAttempt, TerminationAttemptRecord)
        self.assertIs(TerminationRecord, TerminationOutcome)


class TestBootEvidence(unittest.TestCase):
    """Unit tests for BootEvidence fields, defaults, and frozenness."""

    def test_fields_defaults_and_immutability(self) -> None:
        ev = BootEvidence(True, 1234, 5678, 42.125)
        self.assertTrue(ev.boot_completed)
        self.assertEqual((ev.pid, ev.start_ticks, ev.timestamp_mono), (1234, 5678, 42.125))
        self.assertIsNone(ev.avd)
        self.assertIsNone(ev.uid)

        ev_full = BootEvidence(False, 1234, 5678, 42.125, avd="custom_avd", uid=1001)
        self.assertFalse(ev_full.boot_completed)
        self.assertEqual(ev_full.avd, "custom_avd")
        self.assertEqual(ev_full.uid, 1001)

        with self.assertRaises(FrozenInstanceError):
            ev.boot_completed = False  # type: ignore[misc]


if __name__ == "__main__":
    unittest.main()
