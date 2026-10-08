"""Hermetic tests for emulator launcher preflight validation and argv generation."""

from __future__ import annotations

from contextlib import ExitStack
from dataclasses import FrozenInstanceError
import os
import signal
import unittest
from unittest.mock import Mock, patch

from scripts.f3_guard.launcher_preflight import (
    DEFAULT_EMULATOR_PATH,
    LAUNCH_RAM_MIN_KB,
    LAUNCH_SWAP_MIN_KB,
    TARGET_AVD,
    TARGET_PORT,
    TARGET_UID,
    LauncherPreflightHelper,
    LaunchResult,
)
from scripts.f3_guard.types import ProcessIdentity


class _DummyPidfdController:
    def __init__(self, use_pidfd: object = True, has_open: bool = True, has_signal: bool = True) -> None:
        self.use_pidfd = use_pidfd
        if has_open:
            self.pidfd_open = lambda pid, flags=0: 42
        if has_signal:
            self.pidfd_send_signal = lambda fd, sig: None


class TestLaunchResultContract(unittest.TestCase):
    """Contracts for LaunchResult frozen dataclass."""

    def test_dataclass_defaults_and_immutability(self) -> None:
        ident = ProcessIdentity(pid=10, start_ticks=20, uid=1000, avd="a", port=5556, binary_name="qemu")
        res = LaunchResult(success=True, identity=ident)
        self.assertTrue(res.success)
        self.assertIs(res.identity, ident)
        self.assertTrue(all(v is None for v in (res.pidfd, res.child_handle, res.error)))
        self.assertFalse(res.is_direct_child or res.pending_cleanup or res.transferred)
        with self.assertRaises(FrozenInstanceError):
            res.success = False  # type: ignore[misc]


class TestLauncherPreflightHelper(unittest.TestCase):
    """Hermetic unit tests for LauncherPreflightHelper."""

    def setUp(self) -> None:
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.proc_count = 0
        self._orig_open = open
        self._orig_os_open = os.open

        def _check_proc(p: object) -> None:
            raw = getattr(p, "__fspath__", lambda: p)()
            s = str(raw.decode("utf-8", "surrogateescape") if isinstance(raw, bytes) else raw)
            if "/proc" in s:
                self.proc_count += 1
                raise AssertionError(f"Unexpected /proc: {p}")

        self.stack.enter_context(patch("builtins.open", side_effect=lambda f, *a, **kw: _check_proc(f) or self._orig_open(f, *a, **kw)))
        self.stack.enter_context(patch("os.open", side_effect=lambda p, *a, **kw: _check_proc(p) or self._orig_os_open(p, *a, **kw)))
        for p, rv in [("os.path.realpath", lambda p: str(p)), ("os.path.isfile", True), ("os.access", True)]:
            self.stack.enter_context(patch(p, side_effect=rv if callable(rv) else None, return_value=None if callable(rv) else rv))
        for fn in ("time.monotonic", "time.time", "time.sleep", "os.kill", "os.waitpid", "os.system",
                   *(f"subprocess.{s}" for s in ("Popen", "run", "call", "check_call", "check_output")),
                   *(["os.pidfd_open"] if hasattr(os, "pidfd_open") else []),
                   *(["signal.pidfd_send_signal"] if hasattr(signal, "pidfd_send_signal") else [])):
            self.stack.enter_context(patch(fn, side_effect=AssertionError(fn)))

        self.valid_mem = (5_000_000, 2_500_000)
        self.ctrl = _DummyPidfdController()
        self.valid_binary = "/opt/android/emulator"

    def tearDown(self) -> None:
        self.assertEqual(self.proc_count, 0)

    def _make_helper(self, **kwargs: object) -> LauncherPreflightHelper:
        params = {
            "explicit_opt_in": True, "mem_reader": lambda: self.valid_mem,
            "controller": self.ctrl, "target_uid": TARGET_UID, "avd_name": TARGET_AVD,
            "port": TARGET_PORT, "emulator_binary": self.valid_binary,
            "binary_validator": lambda p: True, "availability_check": lambda a, p: True,
        }
        params.update(kwargs)
        return LauncherPreflightHelper(**params)  # type: ignore[arg-type]

    def test_helper_defaults(self) -> None:
        h = LauncherPreflightHelper()
        self.assertFalse(h._opt_in)
        self.assertEqual((h._binary, h._port, h._avd_name, h._target_uid),
                         (DEFAULT_EMULATOR_PATH, TARGET_PORT, TARGET_AVD, TARGET_UID))

    def test_get_frozen_argv_structure_and_freshness(self) -> None:
        argv1, argv2 = self._make_helper().get_frozen_argv(), self._make_helper().get_frozen_argv()
        self.assertIsNot(argv1, argv2)
        self.assertEqual(len(argv1), 13)
        self.assertTrue(all(isinstance(t, str) for t in argv1))
        expected = [
            self.valid_binary, "-avd", TARGET_AVD, "-port", str(TARGET_PORT),
            "-memory", "1536", "-cores", "2",
            "-no-window", "-no-audio", "-no-snapshot-load", "-no-snapshot-save",
        ]
        self.assertEqual(argv1, expected)

    def test_preflight_accepted_returns_none(self) -> None:
        self.assertIsNone(self._make_helper().validate())
        self.assertIsNone(self._make_helper(binary_validator=None).validate())

    def test_filesystem_spy_and_outer_guard_isolation(self) -> None:
        def raising_spy(*args: object, **kwargs: object) -> bool:
            raise AssertionError("Unexpected filesystem access")

        with patch("os.path.isfile", side_effect=raising_spy), patch("os.access", side_effect=raising_spy):
            res_opt = self._make_helper(explicit_opt_in=False).validate()
            self.assertIsNotNone(res_opt)
            self.assertIn("opt-in", res_opt.error.lower())

        with patch("os.path.isfile", side_effect=PermissionError("fs denied")):
            res_err = self._make_helper(binary_validator=lambda p: True).validate()
            self.assertIn("file check error", res_err.error.lower())

    def test_strict_opt_in_rejection_and_callback_not_called(self) -> None:
        called = {"mem": False, "avail": False, "val": False}
        for bad_opt in (False, "true", "True", 1, 0, None):
            with self.subTest(opt=bad_opt):
                called = {"mem": False, "avail": False, "val": False}
                res = self._make_helper(
                    explicit_opt_in=bad_opt,
                    mem_reader=lambda: called.update(mem=True) or self.valid_mem,
                    availability_check=lambda a, p: called.update(avail=True) or True,
                    binary_validator=lambda p: called.update(val=True) or True,
                ).validate()
                self.assertIsNotNone(res)
                self.assertIn("opt-in", res.error.lower())
                self.assertFalse(any(called.values()))

    def test_rejection_order_precedence(self) -> None:
        bad_ctrl = _DummyPidfdController(use_pidfd=False)
        order_cases = [
            ({"explicit_opt_in": False, "avd_name": "wrong"}, "opt-in"),
            ({"avd_name": "wrong", "port": 1234}, "Trindade_UX_v030"),
            ({"port": 1234, "target_uid": 0}, "1234"),
            ({"target_uid": 0, "controller": bad_ctrl}, "Target UID"),
            ({"controller": bad_ctrl, "mem_reader": None}, "pidfd"),
            ({"mem_reader": None, "availability_check": None}, "Missing memory"),
            ({"availability_check": None, "emulator_binary": "rel"}, "Mandatory availability"),
            ({"emulator_binary": "rel"}, "must be absolute"),
            ({"emulator_binary": "/opt/../opt/emulator"}, "normalized"),
        ]
        for kwargs, expected_substr in order_cases:
            with self.subTest(kwargs=kwargs):
                res = self._make_helper(**kwargs).validate()
                self.assertIsNotNone(res)
                self.assertIn(expected_substr.lower(), res.error.lower())

    def test_binary_path_canonical_and_error(self) -> None:
        with patch("os.path.realpath", return_value="/other/real"):
            self.assertIn("canonical realpath", self._make_helper().validate().error)
        with patch("os.path.realpath", side_effect=OSError("realpath error")):
            self.assertIn("realpath resolution error", self._make_helper().validate().error.lower())

    def test_controller_pidfd_capabilities(self) -> None:
        for bad_val in (False, 1, 0, "true", "True", "1", None):
            with self.subTest(val=bad_val):
                res = self._make_helper(controller=_DummyPidfdController(use_pidfd=bad_val)).validate()
                self.assertIn("pidfd", res.error.lower())
        self.assertIn("capabilities", self._make_helper(controller=_DummyPidfdController(has_open=False)).validate().error.lower())
        self.assertIn("capabilities", self._make_helper(controller=_DummyPidfdController(has_signal=False)).validate().error.lower())

    def test_memory_preflight_checks(self) -> None:
        self.assertIn("missing", self._make_helper(mem_reader=None).validate().error.lower())
        self.assertIn("boom", self._make_helper(mem_reader=lambda: (_ for _ in ()).throw(ValueError("boom"))).validate().error)
        self.assertIn("preflight read failed", self._make_helper(mem_reader=lambda: (10,)).validate().error.lower())  # type: ignore[return-value]

        bad_types = [(True, 2_500_000), (5_000_000, True), (5.0, 2_500_000), (5_000_000, 2.5), (-1, 2_500_000), (5_000_000, -5)]
        for pair in bad_types:
            with self.subTest(pair=pair):
                self.assertIn("strict non-negative integer", self._make_helper(mem_reader=lambda: pair).validate().error)

        self.assertIn("below launch minimum", self._make_helper(mem_reader=lambda: (LAUNCH_RAM_MIN_KB - 1, LAUNCH_SWAP_MIN_KB)).validate().error)
        self.assertIn("below launch minimum", self._make_helper(mem_reader=lambda: (LAUNCH_RAM_MIN_KB, LAUNCH_SWAP_MIN_KB - 1)).validate().error)
        self.assertIsNone(self._make_helper(mem_reader=lambda: (LAUNCH_RAM_MIN_KB, LAUNCH_SWAP_MIN_KB)).validate())

        # Downstream availability not called when memory check fails
        cb_avail = Mock()
        res_mem = self._make_helper(mem_reader=lambda: (1, 1), availability_check=cb_avail).validate()
        self.assertIn("below launch minimum", res_mem.error)
        cb_avail.assert_not_called()

    def test_availability_checks(self) -> None:
        self.assertIn("mandatory", self._make_helper(availability_check=None).validate().error.lower())
        for bad_check in [lambda a, p: False, lambda a, p: "yes", lambda a, p: 1, lambda a, p: None]:
            with self.subTest(bad=bad_check):
                self.assertIn("occupied or unverified", self._make_helper(availability_check=bad_check).validate().error)
        self.assertIn("port down", self._make_helper(availability_check=lambda a, p: (_ for _ in ()).throw(RuntimeError("port down"))).validate().error)
        # Downstream validator not called on availability failure
        cb_val = Mock()
        res_avail = self._make_helper(availability_check=lambda a, p: False, binary_validator=cb_val).validate()
        self.assertIn("occupied or unverified", res_avail.error)
        cb_val.assert_not_called()

    def test_file_checks_and_binary_validator_fallback(self) -> None:
        matrix = [
            (False, False, None, False, "not executable regular file"),
            (True, False, None, False, "not executable regular file"),
            (False, True, None, False, "not executable regular file"),
            (True, False, lambda p: True, True, None),
            (False, False, lambda p: True, True, None),
            (False, False, lambda p: False, False, "validation failed"),
            (False, False, lambda p: "truthy", False, "validation failed"),
            (False, False, lambda p: (_ for _ in ()).throw(ValueError("val err")), False, "validation error"),
        ]
        for is_f, acc, val, exp_ok, exp_sub in matrix:
            with self.subTest(is_f=is_f, acc=acc, val=val, exp_ok=exp_ok):
                with patch("os.path.isfile", return_value=is_f), patch("os.access", return_value=acc):
                    res = self._make_helper(binary_validator=val).validate()
                    if exp_ok:
                        self.assertIsNone(res)
                    else:
                        self.assertIsNotNone(res)
                        self.assertIn(exp_sub.lower(), res.error.lower())

        for target in ("os.access", "os.path.isfile"):
            with self.subTest(target=target), patch(target, side_effect=PermissionError(f"{target} denied")):
                res = self._make_helper(binary_validator=lambda p: True).validate()
                self.assertIn("file check error", res.error.lower())

    def test_keyboard_interrupt_and_system_exit_propagate(self) -> None:
        for exc in (KeyboardInterrupt, SystemExit):
            for kw in (
                {"mem_reader": lambda: (_ for _ in ()).throw(exc())},
                {"availability_check": lambda a, p: (_ for _ in ()).throw(exc())},
                {"binary_validator": lambda p: (_ for _ in ()).throw(exc())},
            ):
                with self.subTest(kw=kw, exc=exc), self.assertRaises(exc):
                    self._make_helper(**kw).validate()


if __name__ == "__main__":
    unittest.main()
