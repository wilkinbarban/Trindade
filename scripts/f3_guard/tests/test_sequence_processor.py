"""Hermetic unit tests for scripts.f3_guard.sequence_processor module."""

from __future__ import annotations

import os
import sys
import unittest

_REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

from scripts.f3_guard.sequence_processor import ProbeSequenceProcessor


class TestProbeSequenceProcessor(unittest.TestCase):
    """Hermetic unit tests for ProbeSequenceProcessor."""

    def test_nonzero_retcode_passthrough_with_stderr_fallback_string(self) -> None:
        processor = ProbeSequenceProcessor([["cmd"]], 1024)
        # Nonzero retcode with explicit stderr
        done, res = processor.process_step(retcode=127, stdout="partial out", stderr="custom err")
        self.assertTrue(done)
        self.assertEqual(res, (127, "partial out", "custom err", False))

        # Nonzero retcode with empty stderr fallback
        processor.reset()
        done, res = processor.process_step(retcode=2, stdout="", stderr="")
        self.assertTrue(done)
        self.assertEqual(res, (2, "", "Command failed with code 2", False))

    def test_quota_failure_before_validation(self) -> None:
        # Command specifies processes dump, stdout is over quota AND lacks processes header
        processor = ProbeSequenceProcessor([["shell", "dumpsys", "activity", "processes"]], 20)
        over_quota_headerless = "X" * 30
        done, res = processor.process_step(retcode=0, stdout=over_quota_headerless, stderr="")
        self.assertTrue(done)
        # Must fail with quota error, NOT "processes dump missing required section header"
        self.assertEqual(
            res,
            (1, over_quota_headerless, "Probe output truncated exceeding maximum bounds", False),
        )

        # Multi-step quota failure includes accumulated stdout
        multi_proc = ProbeSequenceProcessor([["c1"], ["c2"]], 15)
        done, res = multi_proc.process_step(retcode=0, stdout="ABCDE", stderr="")
        self.assertFalse(done)
        self.assertIsNone(res)
        done, res = multi_proc.process_step(retcode=0, stdout="12345678901", stderr="")
        self.assertTrue(done)
        self.assertEqual(
            res,
            (1, "ABCDE\n12345678901", "Probe output truncated exceeding maximum bounds", False),
        )

    def test_processes_header_missing(self) -> None:
        processor = ProbeSequenceProcessor([["adb", "shell", "dumpsys", "activity", "processes"]], 1024)
        bad_out = "Random text without required section marker"
        done, res = processor.process_step(retcode=0, stdout=bad_out, stderr="")
        self.assertTrue(done)
        self.assertEqual(
            res,
            (1, bad_out, "processes dump missing required section header", False),
        )

    def test_last_anr_validation_failure_diagnostic(self) -> None:
        processor = ProbeSequenceProcessor([["shell", "dumpsys", "activity", "last-anr"]], 1024)
        bad_header = "invalid last anr output"
        done, res = processor.process_step(retcode=0, stdout=bad_header, stderr="")
        self.assertTrue(done)
        self.assertEqual(
            res,
            (
                1,
                bad_header,
                "last-anr dump validation failed: Missing or invalid ACTIVITY MANAGER LAST ANR header",
                False,
            ),
        )

        processor.reset()
        truncated_anr = "ACTIVITY MANAGER LAST ANR\nANR in\n"
        done, res = processor.process_step(retcode=0, stdout=truncated_anr, stderr="")
        self.assertTrue(done)
        self.assertEqual(
            res,
            (
                1,
                truncated_anr,
                "last-anr dump validation failed: Malformed or truncated ANR in header without valid package",
                False,
            ),
        )

    def test_successful_multi_command_progression(self) -> None:
        commands = [
            ["shell", "dumpsys", "activity", "processes"],
            ["shell", "dumpsys", "activity", "last-anr"],
        ]
        processor = ProbeSequenceProcessor(commands, 2048)

        # Initial state accessors
        self.assertEqual(processor.cmd_idx, 0)
        self.assertFalse(processor.is_last_step)
        self.assertEqual(processor.current_command, ["shell", "dumpsys", "activity", "processes"])

        # Step 1
        out1 = "ACTIVITY MANAGER RUNNING PROCESSES\nproc 123"
        done1, res1 = processor.process_step(retcode=0, stdout=out1, stderr="warn1")
        self.assertFalse(done1)
        self.assertIsNone(res1)

        # State after step 1
        self.assertEqual(processor.cmd_idx, 1)
        self.assertTrue(processor.is_last_step)
        self.assertEqual(processor.current_command, ["shell", "dumpsys", "activity", "last-anr"])

        # Step 2
        out2 = "ACTIVITY MANAGER LAST ANR\nno ANRs recorded."
        done2, res2 = processor.process_step(retcode=0, stdout=out2, stderr="warn2")
        self.assertTrue(done2)
        self.assertEqual(
            res2,
            (0, f"{out1}\n{out2}", "warn1\nwarn2", False),
        )
        self.assertEqual(processor.cmd_idx, 2)
        self.assertIsNone(processor.current_command)

    def test_cleanup_failed_poisoning_after_accumulation(self) -> None:
        processor = ProbeSequenceProcessor([["c1"], ["c2"]], 1024)
        done1, res1 = processor.process_step(retcode=0, stdout="first output", stderr="")
        self.assertFalse(done1)
        self.assertIsNone(res1)

        # Step 2 succeeds on command, but handle cleanup failed with explicit error
        done2, res2 = processor.process_step(
            retcode=0,
            stdout="second output",
            stderr="",
            cleanup_failed=True,
            cleanup_error="fd leak occurred",
        )
        self.assertTrue(done2)
        self.assertEqual(
            res2,
            (1, "first output\nsecond output", "Probe cleanup error: fd leak occurred", False),
        )

        # Cleanup failed with default fallback error
        processor.reset()
        done, res = processor.process_step(
            retcode=0,
            stdout="only output",
            stderr="",
            cleanup_failed=True,
            cleanup_error=None,
        )
        self.assertTrue(done)
        self.assertEqual(
            res,
            (1, "only output", "Probe cleanup error: Handle cleanup pending or failed", False),
        )

    def test_reset_clearing_buffers_and_index(self) -> None:
        processor = ProbeSequenceProcessor([["cmd1"], ["cmd2"]], 1024)
        processor.process_step(retcode=0, stdout="out1", stderr="err1")
        self.assertEqual(processor.cmd_idx, 1)
        self.assertEqual(processor.collected_stdout, ["out1"])
        self.assertEqual(processor.collected_stderr, ["err1"])

        processor.reset()
        self.assertEqual(processor.cmd_idx, 0)
        self.assertEqual(processor.collected_stdout, [])
        self.assertEqual(processor.collected_stderr, [])
        self.assertFalse(processor.is_last_step)
        self.assertEqual(processor.current_command, ["cmd1"])

    def test_stderr_empty_not_appended(self) -> None:
        commands = [["c1"], ["c2"], ["c3"]]
        processor = ProbeSequenceProcessor(commands, 1024)

        processor.process_step(retcode=0, stdout="o1", stderr="")
        processor.process_step(retcode=0, stdout="o2", stderr="err2")
        done, res = processor.process_step(retcode=0, stdout="o3", stderr="")
        self.assertTrue(done)
        self.assertEqual(res, (0, "o1\no2\no3", "err2", False))
        self.assertEqual(processor.collected_stderr, ["err2"])

    def test_single_command_sequence(self) -> None:
        processor = ProbeSequenceProcessor([["echo", "hi"]], 512)
        self.assertTrue(processor.is_last_step)
        done, res = processor.process_step(retcode=0, stdout="hi", stderr="")
        self.assertTrue(done)
        self.assertEqual(res, (0, "hi", "", False))


if __name__ == "__main__":
    unittest.main()
