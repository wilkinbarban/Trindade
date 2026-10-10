"""Hermetic behavior tests for real launcher composition."""

from contextlib import ExitStack
import inspect
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from scripts.f3_guard.controller import ProcessController
from scripts.f3_guard.launcher import EmulatorLauncher
from scripts.f3_guard.launcher_admission_failure import safe_exception_failure
from scripts.f3_guard.launcher_pipeline import LauncherPipelineExecutor
from scripts.f3_guard.launcher_preflight import (
    DEFAULT_EMULATOR_PATH, TARGET_AVD, TARGET_PORT, TARGET_UID,
    LaunchResult, LauncherPreflightHelper,
)
from scripts.f3_guard.tests.test_launcher_stage import (
    _DummyController, _FalsyChild,
)


class FalsyController(_DummyController, ProcessController):
    use_pidfd = True
    bool_calls = 0

    def __bool__(self):
        self.bool_calls += 1
        return False

    def pidfd_send_signal(self, fd, sig):
        raise AssertionError("unexpected signal")


class FalsyCallback:
    def __init__(self, callback):
        self.callback = callback

    def __bool__(self):
        raise AssertionError("callback bool")

    def __call__(self, *args):
        return self.callback(*args)


class FalsyPreflight(LauncherPreflightHelper):
    def __bool__(self):
        raise AssertionError("preflight bool")


class FalsyPipeline(LauncherPipelineExecutor):
    def __bool__(self):
        raise AssertionError("pipeline bool")


class TestEmulatorLauncher(unittest.TestCase):
    def setUp(self):
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        for path, value in (("os.path.realpath", DEFAULT_EMULATOR_PATH),
                            ("os.path.isfile", True), ("os.access", True)):
            self.stack.enter_context(patch(path, return_value=value))
        for path in ("os.kill", "os.waitpid", "os.open", "builtins.open",
                     "subprocess.Popen", "subprocess.run", "time.sleep"):
            self.stack.enter_context(patch(path, side_effect=AssertionError))
        self.ctrl = FalsyController()
        self.ctrl.pidfd_open = lambda pid: 0
        self.child = _FalsyChild()
        self.spawn = Mock(return_value=self.child)

    def make_launcher(self, **overrides):
        params = dict(
            explicit_opt_in=True, mem_reader=lambda: (5_000_000, 2_500_000),
            controller=self.ctrl, availability_check=lambda avd, port: True,
            binary_validator=lambda path: True, spawner=self.spawn,
            identity_verifier=lambda identity, controller: (True, ""),
        )
        params.update(overrides)
        return EmulatorLauncher(**params)

    def test_original_signature_defaults_positional_and_fresh_argv(self):
        signature = inspect.signature(EmulatorLauncher)
        names = list(signature.parameters)[:10]
        self.assertEqual(names, [
            "explicit_opt_in", "mem_reader", "controller", "target_uid",
            "avd_name", "port", "emulator_binary", "binary_validator",
            "availability_check", "spawner",
        ])
        defaults = [signature.parameters[name].default for name in names]
        self.assertEqual(defaults, [
            False, None, None, TARGET_UID, TARGET_AVD, TARGET_PORT,
            DEFAULT_EMULATOR_PATH, None, None, None,
        ])
        launcher = EmulatorLauncher(*defaults)
        argv = launcher.get_frozen_argv()
        self.assertEqual(argv, LauncherPreflightHelper().get_frozen_argv())
        argv.clear()
        self.assertEqual(len(launcher.get_frozen_argv()), 13)
        self.assertIn("opt-in", launcher.launch().error)
        self.assertFalse(EmulatorLauncher(True).launch().success)
        self.spawn.assert_not_called()

    def test_falsy_callbacks_use_original_controller_and_real_helpers(self):
        def verifier(identity, controller):
            self.assertIs(controller, self.ctrl)
            return True, ""

        launcher = self.make_launcher(
            mem_reader=FalsyCallback(lambda: (5_000_000, 2_500_000)),
            binary_validator=FalsyCallback(lambda path: True),
            availability_check=FalsyCallback(lambda avd, port: True),
            spawner=FalsyCallback(self.spawn),
            identity_verifier=FalsyCallback(verifier),
        )
        self.assertFalse(launcher.attempted)
        self.assertTrue(launcher.launch().success)
        self.assertTrue(launcher.is_attempted)
        self.spawn.assert_called_once_with(launcher.get_frozen_argv())

    def test_transfer_and_three_canonical_retry_branches(self):
        for branch in ("transferred", "pending", "simple"):
            with self.subTest(branch=branch):
                self.spawn.reset_mock()
                self.ctrl.bind_anchored_pidfd = lambda pid, ticks, fd: (
                    branch == "transferred"
                )
                launcher = self.make_launcher(
                    explicit_opt_in=branch != "simple",
                )
                first = launcher.launch()
                retry = launcher.launch()
                self.assertIs(launcher.last_result, first)
                self.assertIs(type(retry), LaunchResult)
                self.assertIs(retry.success, False)
                self.assertIs(retry.transferred, branch == "transferred")
                self.assertIs(retry.pending_cleanup, branch == "pending")
                self.assertIs(retry.child_handle,
                              None if branch == "simple" else self.child)
                self.assertEqual(
                    retry.pidfd, 0 if branch == "pending" else None,
                )
                self.assertEqual(self.spawn.call_count,
                                 0 if branch == "simple" else 1)
                self.assertEqual(self.ctrl.bool_calls, 0)

    def test_real_preflight_reentry_and_rejection_do_not_respawn(self):
        nested = []

        def availability(avd, port):
            nested.append(launcher.launch())
            return False

        launcher = self.make_launcher(availability_check=availability)
        result = launcher.launch()
        self.assertFalse(result.success)
        self.assertIn("consumed", nested[0].error)
        self.assertIs(launcher.last_result, result)
        self.assertFalse(launcher.launch().success)
        self.spawn.assert_not_called()

    def test_falsy_injected_helpers_and_authoritative_none_erase(self):
        # The injected preflight is untouched, including its controller choice.
        pipeline = FalsyPipeline(
            spawner=self.spawn, controller=self.ctrl,
            identity_verifier=lambda identity, controller: (True, ""),
        )
        # Supply the capability only through the fixture's public interface.
        preflight_controller = _DummyController()
        preflight_controller.use_pidfd = True
        preflight_controller.pidfd_send_signal = lambda fd, sig: None
        preflight = FalsyPreflight(
            True, lambda: (5_000_000, 2_500_000), preflight_controller,
            binary_validator=lambda path: True,
            availability_check=lambda avd, port: True,
        )
        launcher = self.make_launcher(
            preflight_helper=preflight, pipeline_executor=pipeline,
        )
        self.assertTrue(launcher.launch().success)
        state = SimpleNamespace(pending_child=self.child, pending_pidfd=0,
                                last_result=None)
        pipeline.custody_tracker.set_active_helper(state)
        pipeline.custody_tracker.snapshot()
        self.assertIs(launcher.pending_child, self.child)
        self.assertEqual(launcher.pending_pidfd, 0)
        self.assertIsNone(launcher.last_result)
        pipeline.custody_tracker.set_active_helper(None)
        pipeline.custody_tracker.snapshot()
        self.assertIsNone(launcher.pending_child)
        self.assertIsNone(launcher.pending_pidfd)
        self.assertIsNone(launcher.last_result)

    def test_interrupt_phases_preserve_primary_custody_frames_and_retry(self):
        for phase in ("spawn", "pidfd", "transfer"):
            for exc_type in (KeyboardInterrupt, SystemExit):
                for secondary in (False, True):
                    with self.subTest(phase=phase, exc=exc_type,
                                      secondary=secondary):
                        self.ctrl = FalsyController()
                        self.ctrl.pidfd_open = lambda pid: 0
                        self.spawn.reset_mock()
                        primary = exc_type("primary")

                        def original_raise(*args):
                            raise primary

                        params = {}
                        if phase == "spawn":
                            self.ctrl.get_uid = original_raise
                        elif phase == "pidfd":
                            self.ctrl.get_fdinfo_pid = original_raise
                        else:
                            params["identity_verifier"] = original_raise
                        launcher = self.make_launcher(**params)
                        source = launcher.admission_helper.custody_source
                        with ExitStack() as stack:
                            fault = stack.enter_context(patch(
                                "scripts.f3_guard.launcher_admission_adapter."
                                "safe_exception_failure",
                                wraps=safe_exception_failure,
                                side_effect=(SystemExit("secondary")
                                             if secondary else None),
                            ))
                            try:
                                launcher.launch()
                            except BaseException as caught:
                                self.assertIs(caught, primary)
                                frames = []
                                tb = caught.__traceback__
                                while tb is not None:
                                    frames.append(tb.tb_frame.f_code.co_name)
                                    tb = tb.tb_next
                                self.assertIn("original_raise", frames)
                            else:
                                self.fail("interrupt did not escape")
                            self.assertEqual(fault.call_count, 1)
                            self.assertIs(fault.call_args.args[0], primary)
                            self.assertIs(fault.call_args.kwargs["child"],
                                          self.child)
                            self.assertEqual(fault.call_args.kwargs["pidfd"],
                                             None if phase == "spawn" else 0)
                        self.assertIs(
                            launcher.admission_helper.custody_source, source,
                        )
                        self.assertIs(launcher.pending_child, self.child)
                        expected_fd = None if phase == "spawn" else 0
                        self.assertEqual(launcher.pending_pidfd, expected_fd)
                        self.assertIn(
                            exc_type.__name__, launcher.last_result.error,
                        )
                        retry = launcher.launch()
                        self.assertIs(retry.child_handle, self.child)
                        self.assertEqual(retry.pidfd, expected_fd)
                        self.assertIs(retry.pending_cleanup, True)
                        self.assertIs(retry.transferred, False)
                        self.assertEqual(self.spawn.call_count, 1)
