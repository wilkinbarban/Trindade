"""Hermetic unit tests for F3 Emulator Guard CLI and offline verification (8f)."""

from __future__ import annotations

from contextlib import ExitStack, contextmanager
import inspect
import io
import os
import runpy
import select
import signal
import subprocess
import sys
import time
import unittest
from unittest.mock import patch

from scripts.f3_guard.cli import main
from scripts.f3_guard.clock import DEFAULT_TTL_SEC
from scripts.f3_guard.engine import DEFAULT_CADENCE_SEC
from scripts.f3_guard.memory import GUARD_RAM_MIN_KB, GUARD_SWAP_MIN_KB


@contextmanager
def _reimport_cli_context():
    """Isolate CLI reimport, restoring sys.modules and package attribute state."""
    pkg = sys.modules.get("scripts.f3_guard")
    had_pkg_attr = hasattr(pkg, "cli") if pkg is not None else False
    orig_pkg_attr = getattr(pkg, "cli", None) if pkg is not None else None
    saved_modules = dict(sys.modules)
    try:
        sys.modules.pop("scripts.f3_guard.cli", None)
        yield
    finally:
        sys.modules.clear()
        sys.modules.update(saved_modules)
        pkg_now = sys.modules.get("scripts.f3_guard", pkg)
        if pkg_now is not None:
            if had_pkg_attr:
                setattr(pkg_now, "cli", orig_pkg_attr)
            elif hasattr(pkg_now, "cli"):
                delattr(pkg_now, "cli")


class TestCLI(unittest.TestCase):
    """Hermetic unit tests verifying CLI contracts, parser behavior, and isolation."""

    def setUp(self) -> None:
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.proc_access_count = 0
        self._orig_open = open
        self._orig_os_open = os.open

        def _guarded_open(file: object, *args: object, **kwargs: object) -> object:
            raw = getattr(file, "__fspath__", lambda: file)()
            s = str(raw.decode("utf-8", "surrogateescape") if isinstance(raw, bytes) else raw)
            if s.startswith("/proc") or "/proc/" in s:
                self.proc_access_count += 1
                raise AssertionError(f"Unexpected real /proc open: {file}")
            return self._orig_open(file, *args, **kwargs)

        def _guarded_os_open(path: object, *args: object, **kwargs: object) -> object:
            s = str(path.decode("utf-8", "surrogateescape") if isinstance(path, bytes) else path)
            if s.startswith("/proc") or "/proc/" in s:
                self.proc_access_count += 1
                raise AssertionError(f"Unexpected real /proc os.open: {path}")
            return self._orig_os_open(path, *args, **kwargs)

        self.stack.enter_context(patch("builtins.open", side_effect=_guarded_open))
        self.stack.enter_context(patch("os.open", side_effect=_guarded_os_open))
        self.stack.enter_context(patch("time.monotonic", side_effect=AssertionError("monotonic")))
        self.stack.enter_context(patch("time.time", side_effect=AssertionError("time")))
        self.stack.enter_context(patch("time.sleep", side_effect=AssertionError("sleep")))
        self.stack.enter_context(patch("os.kill", side_effect=AssertionError("os.kill")))
        self.stack.enter_context(patch("os.waitpid", side_effect=AssertionError("waitpid")))
        self.stack.enter_context(patch("os.system", side_effect=AssertionError("os.system")))
        self.stack.enter_context(patch("select.poll", side_effect=AssertionError("poll")))
        for sub in ("Popen", "run", "call", "check_call", "check_output"):
            self.stack.enter_context(patch(f"subprocess.{sub}", side_effect=AssertionError(sub)))
        if hasattr(os, "pidfd_open"):
            self.stack.enter_context(patch("os.pidfd_open", side_effect=AssertionError("pidfd_open")))
        if hasattr(signal, "pidfd_send_signal"):
            self.stack.enter_context(patch("signal.pidfd_send_signal", side_effect=AssertionError("pidfd_send")))

    def tearDown(self) -> None:
        self.assertEqual(self.proc_access_count, 0)

    def test_check_offline_contract_output_and_return_code(self) -> None:
        with patch.object(sys, "argv", ["scripts.f3_guard.cli", "--check-offline"]):
            with patch("sys.stdout", new=io.StringIO()) as fake_stdout:
                ret = main()
        self.assertEqual(ret, 0)
        expected_lines = [
            "F3 Emulator Guard offline contract verification: OK",
            f"  RAM Limit: {GUARD_RAM_MIN_KB} kB",
            f"  Swap Limit: {GUARD_SWAP_MIN_KB} kB",
            f"  Supervision TTL: {DEFAULT_TTL_SEC} s (monotonic)",
            f"  Cadence: {DEFAULT_CADENCE_SEC} s",
        ]
        self.assertEqual(fake_stdout.getvalue().splitlines(), expected_lines)

    def test_version_flag_exits_zero_with_version_text(self) -> None:
        with patch.object(sys, "argv", ["scripts.f3_guard.cli", "--version"]):
            with patch("sys.stdout", new=io.StringIO()) as fake_stdout, \
                 patch("sys.stderr", new=io.StringIO()) as fake_stderr:
                with self.assertRaises(SystemExit) as cm:
                    main()
        self.assertEqual(cm.exception.code, 0)
        output = fake_stdout.getvalue() + fake_stderr.getvalue()
        self.assertIn("f3_emulator_guard 1.0.0", output)

    def test_no_args_prints_help_and_returns_zero(self) -> None:
        with patch.object(sys, "argv", ["scripts.f3_guard.cli"]):
            with patch("sys.stdout", new=io.StringIO()) as fake_stdout:
                ret = main()
        self.assertEqual(ret, 0)
        stdout_val = fake_stdout.getvalue()
        self.assertIn("F3 Android Emulator Capacity and Safety Guard", stdout_val)
        self.assertIn("--check-offline", stdout_val)
        self.assertIn("--version", stdout_val)

    def test_help_flag_exits_zero_with_help_text(self) -> None:
        for flag in ("-h", "--help"):
            with patch.object(sys, "argv", ["scripts.f3_guard.cli", flag]):
                with patch("sys.stdout", new=io.StringIO()) as fake_stdout:
                    with self.assertRaises(SystemExit) as cm:
                        main()
            self.assertEqual(cm.exception.code, 0)
            self.assertIn("usage:", fake_stdout.getvalue().lower())

    def test_invalid_flag_exits_code_two_with_stderr(self) -> None:
        with patch.object(sys, "argv", ["scripts.f3_guard.cli", "--unrecognized-option"]):
            with patch("sys.stderr", new=io.StringIO()) as fake_stderr:
                with self.assertRaises(SystemExit) as cm:
                    main()
        self.assertEqual(cm.exception.code, 2)
        self.assertIn("unrecognized arguments", fake_stderr.getvalue())

    def test_flag_combination_parser_priority(self) -> None:
        with patch.object(sys, "argv", ["scripts.f3_guard.cli", "--version", "--check-offline"]):
            with patch("sys.stdout", new=io.StringIO()) as fake_stdout, \
                 patch("sys.stderr", new=io.StringIO()) as fake_stderr:
                with self.assertRaises(SystemExit) as cm:
                    main()
        self.assertEqual(cm.exception.code, 0)
        self.assertIn("f3_emulator_guard 1.0.0", fake_stdout.getvalue() + fake_stderr.getvalue())

        with patch.object(sys, "argv", ["scripts.f3_guard.cli", "--check-offline", "--version"]):
            with patch("sys.stdout", new=io.StringIO()) as fake_stdout, \
                 patch("sys.stderr", new=io.StringIO()) as fake_stderr:
                with self.assertRaises(SystemExit) as cm:
                    main()
        self.assertEqual(cm.exception.code, 0)
        self.assertIn("f3_emulator_guard 1.0.0", fake_stdout.getvalue() + fake_stderr.getvalue())

    def test_pure_import_is_silent_and_does_not_consume_argv(self) -> None:
        with patch.object(sys, "argv", ["scripts.f3_guard.cli", "--invalid-nonexistent-flag-xyz"]):
            with _reimport_cli_context():
                with patch("sys.stdout", new=io.StringIO()) as fake_stdout, \
                     patch("sys.stderr", new=io.StringIO()) as fake_stderr:
                    mod = __import__("scripts.f3_guard.cli", fromlist=["main"])
                    self.assertTrue(callable(getattr(mod, "main", None)))
                    self.assertEqual(fake_stdout.getvalue(), "")
                    self.assertEqual(fake_stderr.getvalue(), "")

    def test_module_entry_in_process_runpy_run_module(self) -> None:
        with _reimport_cli_context():
            with patch.object(sys, "argv", ["scripts.f3_guard.cli", "--check-offline"]):
                with patch("sys.stdout", new=io.StringIO()) as fake_stdout:
                    with self.assertRaises(SystemExit) as cm:
                        runpy.run_module("scripts.f3_guard.cli", run_name="__main__", alter_sys=False)
            self.assertEqual(cm.exception.code, 0)
            self.assertEqual(
                fake_stdout.getvalue().splitlines(),
                [
                    "F3 Emulator Guard offline contract verification: OK",
                    f"  RAM Limit: {GUARD_RAM_MIN_KB} kB",
                    f"  Swap Limit: {GUARD_SWAP_MIN_KB} kB",
                    f"  Supervision TTL: {DEFAULT_TTL_SEC} s (monotonic)",
                    f"  Cadence: {DEFAULT_CADENCE_SEC} s",
                ],
            )

    def test_reimport_context_restores_sys_modules_and_package_attribute(self) -> None:
        import scripts.f3_guard
        import scripts.f3_guard.cli as initial_cli
        initial_attr = getattr(scripts.f3_guard, "cli", None)
        self.assertIs(initial_cli, initial_attr)

        with _reimport_cli_context():
            reimported = __import__("scripts.f3_guard.cli", fromlist=["main"])
            self.assertIsNot(reimported, initial_cli)
            self.assertIs(getattr(scripts.f3_guard, "cli", None), reimported)

        self.assertIs(sys.modules.get("scripts.f3_guard.cli"), initial_cli)
        self.assertIs(getattr(scripts.f3_guard, "cli", None), initial_attr)

    def test_reimport_context_restores_absent_package_cli_attribute(self) -> None:
        import scripts.f3_guard
        initial_attr = getattr(scripts.f3_guard, "cli", None)
        initial_mod = sys.modules.get("scripts.f3_guard.cli")
        try:
            if hasattr(scripts.f3_guard, "cli"):
                delattr(scripts.f3_guard, "cli")
            self.assertFalse(hasattr(scripts.f3_guard, "cli"))

            with _reimport_cli_context():
                reimported = __import__("scripts.f3_guard.cli", fromlist=["main"])
                self.assertTrue(hasattr(scripts.f3_guard, "cli"))
                self.assertIs(getattr(scripts.f3_guard, "cli"), reimported)

            self.assertFalse(hasattr(scripts.f3_guard, "cli"))
        finally:
            if initial_attr is not None:
                setattr(scripts.f3_guard, "cli", initial_attr)
            if initial_mod is not None:
                sys.modules["scripts.f3_guard.cli"] = initial_mod

    def test_main_signature_has_no_argv_parameter(self) -> None:
        sig = inspect.signature(main)
        self.assertEqual(len(sig.parameters), 0)
