"""Hermetic unit tests for standalone TerminationClaimTracker (8c1)."""

from __future__ import annotations

import dataclasses
import unittest
from unittest.mock import patch

from scripts.f3_guard.engine_claim import TerminationClaimTracker
from scripts.f3_guard.types import (
    LifecycleState,
    ProcessIdentity,
    TerminationAttemptRecord,
    TerminationClaimStatus,
    TerminationOutcome,
)


class _EqualityController:
    """Mock controller whose __eq__ returns True, isolating identity semantics."""

    def __init__(self, token: str) -> None:
        self.token = token

    def __eq__(self, other: object) -> bool:
        return True


class TerminationClaimTrackerTests(unittest.TestCase):
    """Hermetic unit tests for TerminationClaimTracker lifecycle and invariants."""

    def setUp(self) -> None:
        self.target = ProcessIdentity(
            pid=12345,
            start_ticks=987654,
            uid=1000,
            avd="Trindade_UX_v030",
            port=5556,
            binary_name="qemu-system-x86_64",
        )
        self.controller = _EqualityController("primary")
        self.proc_access_count = 0
        self._orig_open = open

        def _guarded_open(file: object, *args: object, **kwargs: object) -> object:
            if isinstance(file, str) and file.startswith("/proc"):
                self.proc_access_count += 1
                raise AssertionError(f"Unexpected real /proc open: {file}")
            return self._orig_open(file, *args, **kwargs)

        self._patcher = patch("builtins.open", side_effect=_guarded_open)
        self._patcher.start()
        self.addCleanup(self._patcher.stop)

    def tearDown(self) -> None:
        self.assertEqual(self.proc_access_count, 0)

    def test_initial_state_and_not_attempted(self) -> None:
        tracker = TerminationClaimTracker(self.target, self.controller)
        self.assertIs(tracker.target, self.target)
        self.assertIs(tracker.controller, self.controller)
        self.assertIsNone(tracker.attempt_record)
        self.assertIsNone(tracker.termination_attempt)
        self.assertIsNone(tracker.termination_outcome)
        self.assertIsNone(tracker.export_claim_snapshot())
        res = tracker.claim(self.target, self.controller)
        self.assertEqual(res, TerminationClaimStatus.NOT_ATTEMPTED)

    def test_claim_lifecycle_progression_and_single_consumption(self) -> None:
        tracker = TerminationClaimTracker(self.target, self.controller)
        rec = tracker.record_attempt()
        self.assertIsInstance(rec, TerminationAttemptRecord)
        self.assertEqual(tracker.claim(self.target, self.controller), TerminationClaimStatus.IN_PROGRESS)

        outcome = TerminationOutcome(state=LifecycleState.STOPPED, detail="clean stop", completed=True)
        tracker.seal_outcome(outcome)

        self.assertEqual(tracker.claim(self.target, self.controller), TerminationClaimStatus.CLAIMED)
        self.assertEqual(tracker.claim(self.target, self.controller), TerminationClaimStatus.ALREADY_CLAIMED)
        self.assertEqual(tracker.claim(self.target, self.controller), TerminationClaimStatus.ALREADY_CLAIMED)

    def test_controller_equality_vs_identity(self) -> None:
        ctrl_other = _EqualityController("secondary")
        self.assertTrue(self.controller == ctrl_other)
        self.assertIsNot(self.controller, ctrl_other)

        tracker = TerminationClaimTracker(self.target, self.controller)
        self.assertEqual(tracker.claim(self.target, ctrl_other), TerminationClaimStatus.MISMATCH)

        tracker.record_attempt()
        self.assertEqual(tracker.claim(self.target, ctrl_other), TerminationClaimStatus.MISMATCH)

        tracker.seal_outcome(TerminationOutcome(state=LifecycleState.STOPPED, completed=True))
        self.assertEqual(tracker.claim(self.target, ctrl_other), TerminationClaimStatus.MISMATCH)
        # Identity match still claims
        self.assertEqual(tracker.claim(self.target, self.controller), TerminationClaimStatus.CLAIMED)

    def test_target_mismatches_before_and_after_attempt(self) -> None:
        tracker = TerminationClaimTracker(self.target, self.controller)
        bad_pid = dataclasses.replace(self.target, pid=99999)
        bad_ticks = dataclasses.replace(self.target, start_ticks=11111)
        bad_uid = dataclasses.replace(self.target, uid=0)
        bad_avd = dataclasses.replace(self.target, avd="Other_AVD")

        for bad in (bad_pid, bad_ticks, bad_uid, bad_avd):
            self.assertEqual(tracker.claim(bad, self.controller), TerminationClaimStatus.MISMATCH)
        self.assertEqual(tracker.claim(self.target, self.controller), TerminationClaimStatus.NOT_ATTEMPTED)

        tracker.record_attempt()
        tracker.seal_outcome(TerminationOutcome(state=LifecycleState.STOPPED, completed=True))

        for bad in (bad_pid, bad_ticks, bad_uid, bad_avd):
            self.assertEqual(tracker.claim(bad, self.controller), TerminationClaimStatus.MISMATCH)
        self.assertEqual(tracker.claim(self.target, self.controller), TerminationClaimStatus.CLAIMED)

    def test_strict_int_matrix_rejects_bool_float_none_str(self) -> None:
        tracker = TerminationClaimTracker(self.target, self.controller)
        tracker.record_attempt()
        tracker.seal_outcome(TerminationOutcome(state=LifecycleState.STOPPED, completed=True))

        invalid_vals = (True, False, 12345.0, None, "12345")
        for val in invalid_vals:
            for field_name in ("pid", "start_ticks", "uid"):
                bad = dataclasses.replace(self.target, **{field_name: val})
                self.assertEqual(
                    tracker.claim(bad, self.controller),
                    TerminationClaimStatus.MISMATCH,
                    f"Expected MISMATCH for {field_name}={val!r}",
                )

        # Mismatches did not consume claim
        self.assertEqual(tracker.claim(self.target, self.controller), TerminationClaimStatus.CLAIMED)

    def test_mismatch_preserves_claim_state(self) -> None:
        tracker = TerminationClaimTracker(self.target, self.controller)
        bad = dataclasses.replace(self.target, pid=99999)

        # Not attempted state preserved
        self.assertEqual(tracker.claim(bad, self.controller), TerminationClaimStatus.MISMATCH)
        self.assertEqual(tracker.claim(self.target, self.controller), TerminationClaimStatus.NOT_ATTEMPTED)

        # In progress state preserved
        tracker.record_attempt()
        self.assertEqual(tracker.claim(bad, self.controller), TerminationClaimStatus.MISMATCH)
        self.assertEqual(tracker.claim(self.target, self.controller), TerminationClaimStatus.IN_PROGRESS)

        # Post-seal unconsumed state preserved
        tracker.seal_outcome(TerminationOutcome(state=LifecycleState.STOPPED, completed=True))
        self.assertEqual(tracker.claim(bad, self.controller), TerminationClaimStatus.MISMATCH)
        self.assertEqual(tracker.claim(self.target, self.controller), TerminationClaimStatus.CLAIMED)

        # Already claimed state preserved
        self.assertEqual(tracker.claim(bad, self.controller), TerminationClaimStatus.MISMATCH)
        self.assertEqual(tracker.claim(self.target, self.controller), TerminationClaimStatus.ALREADY_CLAIMED)

    def test_snapshot_immutability_and_replace_isolation(self) -> None:
        tracker = TerminationClaimTracker(self.target, self.controller)
        self.assertIsNone(tracker.export_claim_snapshot())

        tracker.record_attempt()
        snap_initial = tracker.export_claim_snapshot()
        self.assertIsNotNone(snap_initial)
        self.assertIsNone(snap_initial.outcome)

        with self.assertRaises((dataclasses.FrozenInstanceError, AttributeError)):
            snap_initial.pid = 99999  # type: ignore[misc]

        outcome = TerminationOutcome(state=LifecycleState.STOPPED, completed=True)
        tracker.seal_outcome(outcome)
        snap_sealed = tracker.export_claim_snapshot()

        self.assertIsNot(snap_initial, snap_sealed)
        self.assertIsNone(snap_initial.outcome)
        self.assertIs(snap_sealed.outcome, outcome)
        self.assertIs(tracker.attempt_record, snap_sealed)

        with self.assertRaises((dataclasses.FrozenInstanceError, AttributeError)):
            snap_sealed.outcome.state = LifecycleState.ERROR  # type: ignore[misc]

    def test_aliases_consistency(self) -> None:
        tracker = TerminationClaimTracker(self.target, self.controller)
        self.assertIsNone(tracker.termination_outcome)
        self.assertEqual(tracker.claim_termination(self.target, self.controller), TerminationClaimStatus.NOT_ATTEMPTED)

        tracker.record_attempt()
        self.assertIs(tracker.attempt_record, tracker.termination_attempt)
        self.assertIs(tracker.attempt_record, tracker.export_claim_snapshot())

        outcome = TerminationOutcome(state=LifecycleState.STOPPED, completed=True)
        tracker.seal_outcome(outcome)
        self.assertIs(tracker.termination_outcome, outcome)
        self.assertEqual(tracker.claim_termination(self.target, self.controller), TerminationClaimStatus.CLAIMED)

    def test_idempotent_record_and_reseal_no_overwrite(self) -> None:
        tracker = TerminationClaimTracker(self.target, self.controller)
        rec1 = tracker.record_attempt()
        rec2 = tracker.record_attempt()
        self.assertIs(rec1, rec2)

        outcome1 = TerminationOutcome(state=LifecycleState.STOPPED, detail="first", completed=True)
        outcome2 = TerminationOutcome(state=LifecycleState.ERROR, detail="second", completed=False)
        tracker.seal_outcome(outcome1)
        # Reseal must not overwrite
        tracker.seal_outcome(outcome2)
        self.assertIs(tracker.termination_outcome, outcome1)
        self.assertEqual(tracker.termination_outcome.detail, "first")

    def test_invalid_seal_before_record_raises_valueerror(self) -> None:
        tracker = TerminationClaimTracker(self.target, self.controller)
        outcome = TerminationOutcome(state=LifecycleState.STOPPED, completed=True)
        with self.assertRaises(ValueError) as ctx:
            tracker.seal_outcome(outcome)
        self.assertIn("before recording", str(ctx.exception).lower())
        self.assertIsNone(tracker.attempt_record)
        self.assertEqual(tracker.claim(self.target, self.controller), TerminationClaimStatus.NOT_ATTEMPTED)


if __name__ == "__main__":
    unittest.main()
