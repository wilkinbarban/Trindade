"""Hermetic unit tests for launcher pipeline executor."""

from __future__ import annotations

from contextlib import ExitStack
import os
import traceback
from typing import Any, Optional
import unittest
from unittest.mock import Mock, patch

from scripts.f3_guard.launcher_custody import LauncherCustodyTracker
import scripts.f3_guard.launcher_custody as lc_mod
from scripts.f3_guard.launcher_pidfd import LauncherPidfdHelper
from scripts.f3_guard.launcher_pipeline import LauncherPipelineExecutor
import scripts.f3_guard.launcher_pipeline as lp_mod
from scripts.f3_guard.launcher_preflight import (
    DEFAULT_EMULATOR_PATH, TARGET_AVD, TARGET_PORT, TARGET_UID, LaunchResult, LauncherPreflightHelper,
)
from scripts.f3_guard.launcher_spawn import LauncherSpawnHelper
from scripts.f3_guard.launcher_transfer import LauncherTransferHelper
from scripts.f3_guard.tests.test_launcher_custody import _StrTrapBaseException
from scripts.f3_guard.tests.test_launcher_stage import (
    _BoolRaisesChild, _DummyChild, _DummyController, _StrTrapException,
)
from scripts.f3_guard.types import ProcessIdentity


class _FailPrimeHelper:
    def __init__(self, base: Any, exc: BaseException) -> None:
        self._base, self._exc = base, exc
        self._pending_child, self._pending_pidfd = None, None

    def prime(self, c: Any, f: Any) -> None:
        self._pending_child = c
        raise self._exc

    def __getattr__(self, name: str) -> Any: return getattr(self._base, name)


class _InvalidStageHelper:
    def __init__(self, child: Any = None, pidfd: Optional[int] = None) -> None:
        self.pending_child, self.pending_pidfd = child, pidfd

    def prime(self, c: Any, f: Any = None) -> None: pass
    def spawn_and_verify(self, argv: Any) -> Any: return None, None
    def acquire_and_verify(self, ctx: Any) -> Any: return None, None
    def transfer(self, ctx: Any) -> Any: return None


class _FalsySpawnHelper:
    def __bool__(self) -> bool: return False

    def spawn_and_verify(self, argv: list[str]) -> Any:
        return None, LaunchResult(success=False, error="falsy helper used")


class _BoolTrapTracker(LauncherCustodyTracker):
    def __bool__(self) -> bool: raise RuntimeError("tracker bool trap")


class TestLauncherPipelineExecutor(unittest.TestCase):
    """Hermetic unit tests for fail-closed 3-stage pipeline executor."""

    def setUp(self) -> None:
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self._orig_open, self._orig_os_open = open, os.open

        def _check_proc(p: object) -> None:
            raw = getattr(p, "__fspath__", lambda: p)()
            s = str(raw.decode("utf-8", "surrogateescape") if isinstance(raw, bytes) else raw)
            if "/proc" in s: raise AssertionError(f"Unexpected /proc: {p}")

        self.stack.enter_context(patch("builtins.open", side_effect=lambda f, *a, **kw: _check_proc(f) or self._orig_open(f, *a, **kw)))
        self.stack.enter_context(patch("os.open", side_effect=lambda p, *a, **kw: _check_proc(p) or self._orig_os_open(p, *a, **kw)))
        for fn in ("subprocess.Popen", "os.kill", "os.waitpid"):
            self.stack.enter_context(patch(fn, side_effect=AssertionError(f"{fn} forbidden")))

    def _assert_escapes_with_frame(self, ex: LauncherPipelineExecutor, exc: BaseException, fn: str) -> None:
        caught = None
        try: ex.execute(["t"])
        except BaseException as err: caught = err
        self.assertIs(caught, exc)
        self.assertIsNotNone(caught.__traceback__)
        frames = traceback.extract_tb(caught.__traceback__)
        self.assertTrue(len(frames) >= 2 and any(f.name == fn for f in frames))

    def test_happy_path_real_helpers_and_transferred_ownership(self) -> None:
        ctrl, child = _DummyController(bound_ok=True), _DummyChild(12345)
        bt = _BoolTrapTracker()
        executor = LauncherPipelineExecutor(spawner=lambda argv: child, controller=ctrl, custody_tracker=bt)
        res = executor.execute(LauncherPreflightHelper().get_frozen_argv())
        self.assertTrue(res.success and res.transferred and not res.pending_cleanup and res.pidfd is None)
        self.assertIsInstance(res.identity, ProcessIdentity)
        self.assertIs(executor.last_result, res)
        self.assertIs(executor.pending_child, child)
        self.assertTrue(executor.pending_pidfd is None and executor.last_exception is None and executor.last_snapshot is not None)
        self.assertIs(executor.custody_tracker, bt)
        self.assertIs(executor.active_helper, bt.active_helper)

        ex_def = LauncherPipelineExecutor()
        self.assertIsNotNone(ex_def.custody_tracker)
        self.assertTrue(ex_def.active_helper is None and ex_def.pending_child is None and ex_def.pending_pidfd is None)
        self.assertTrue(ex_def.last_result is None and ex_def.last_exception is None and ex_def.last_snapshot is None)
        self.assertEqual((ex_def.spawner, ex_def.controller, ex_def.identity_verifier), (None, None, None))
        self.assertEqual((ex_def.spawn_helper, ex_def.pidfd_helper, ex_def.transfer_helper), (None, None, None))
        self.assertEqual((ex_def.target_uid, ex_def.avd_name, ex_def.port, ex_def.emulator_binary), (TARGET_UID, TARGET_AVD, TARGET_PORT, DEFAULT_EMULATOR_PATH))
        res_def = ex_def.execute(["t"])
        self.assertTrue(not res_def.success and res_def.error == "Real emulator spawner not configured in offline mode")
        self.assertIs(ex_def.last_result, res_def)
        self.assertIsInstance(ex_def.active_helper, LauncherSpawnHelper)
        self.assertTrue(ex_def.pending_child is None and ex_def.pending_pidfd is None and ex_def.last_exception is None)

    def test_dependency_callback_failure_matrix_across_all_stages(self) -> None:
        child = _DummyChild(12345)
        for stage in (1, 2, 3):
            primaries = [
                RuntimeError(f"s{stage}_err"), GeneratorExit(),
                KeyboardInterrupt(f"s{stage}_ki"), SystemExit(stage),
                _StrTrapBaseException() if stage == 1 else _StrTrapException(),
            ]
            for exc in primaries:
                with self.subTest(stage=stage, exc_type=type(exc).__name__):
                    ctrl = _DummyController(bound_ok=True)
                    def _fail_cb(*_a: Any, **_kw: Any) -> Any: raise exc
                    kw: dict[str, Any] = {"controller": ctrl, "spawner": lambda a: child}
                    if stage == 1: kw["spawner"] = _fail_cb
                    elif stage == 2: ctrl.pidfd_open = _fail_cb
                    else: kw["identity_verifier"] = _fail_cb

                    ex = LauncherPipelineExecutor(**kw)
                    if isinstance(exc, (KeyboardInterrupt, SystemExit)):
                        self._assert_escapes_with_frame(ex, exc, "_fail_cb")
                        self.assertIs(ex.last_exception, exc)
                        self.assertIsNotNone(ex.last_result)
                    else:
                        res = ex.execute(["t"])
                        self.assertFalse(res.success)
                        self.assertIs(ex.last_result, res)
                        if stage == 1 and isinstance(exc, (GeneratorExit, _StrTrapBaseException)):
                            self.assertIs(ex.last_exception, exc)
                        else:
                            self.assertIsNone(ex.last_exception)
                        self.assertTrue(
                            res.error and (
                                type(exc).__name__ in res.error
                                or "Spawner execution failed" in res.error
                                or "Post-spawn exception" in res.error
                            )
                        )

    def test_handoff_prime_upstream_authoritative_and_secondary_diagnostics(self) -> None:
        ctrl, child = _DummyController(), _DummyChild(12345)
        for handoff in ("spawn_to_pidfd", "pidfd_to_transfer"):
            for exc in (RuntimeError("p_err"), KeyboardInterrupt("p_ki"), SystemExit(8)):
                with self.subTest(h=handoff, exc_type=type(exc).__name__):
                    s_h, p_h, t_h = LauncherSpawnHelper(spawner=lambda a: child, controller=ctrl), LauncherPidfdHelper(controller=ctrl), LauncherTransferHelper(controller=ctrl)
                    if handoff == "spawn_to_pidfd": p_h = _FailPrimeHelper(p_h, exc)  # type: ignore[assignment]
                    else: t_h = _FailPrimeHelper(t_h, exc)  # type: ignore[assignment]

                    ex = LauncherPipelineExecutor(controller=ctrl, spawn_helper=s_h, pidfd_helper=p_h, transfer_helper=t_h)
                    if isinstance(exc, (KeyboardInterrupt, SystemExit)):
                        self._assert_escapes_with_frame(ex, exc, "prime")
                    else:
                        self.assertFalse(ex.execute(["t"]).success)

                    if handoff == "spawn_to_pidfd":
                        self.assertIs(ex.active_helper, s_h)
                        self.assertIs(ex.pending_child, child)
                    else:
                        self.assertIs(ex.active_helper, p_h)
                        self.assertTrue(ex.pending_child is child and ex.pending_pidfd == 5)

        class _FailingGetterPidfdHelper(LauncherPidfdHelper):
            _fault = False
            def acquire_and_verify(self, ctx: Any) -> Any:
                self._fault = True
                raise TypeError("primary stage fault")
            @property
            def pending_pidfd(self) -> Any:
                if self._fault: raise RuntimeError("getter fault")
                return self._pending_pidfd

        p_fail = _FailingGetterPidfdHelper(controller=ctrl)
        ex_diag = LauncherPipelineExecutor(
            controller=ctrl, spawn_helper=LauncherSpawnHelper(spawner=lambda a: child, controller=ctrl),
            pidfd_helper=p_fail,
        )
        res_diag = ex_diag.execute(["t"])
        self.assertFalse(res_diag.success)
        self.assertTrue("primary: TypeError" in res_diag.error and "secondary: pending_pidfd: RuntimeError" in res_diag.error)
        self.assertTrue(res_diag.child_handle is child and res_diag.pidfd is None and res_diag.pending_cleanup)
        self.assertTrue(ex_diag.active_helper is p_fail and ex_diag.pending_child is child and ex_diag.pending_pidfd is None)
        self.assertIs(ex_diag.last_result, res_diag)
        self.assertIsInstance(ex_diag.last_exception, TypeError)

    def test_early_failure_stops_downstream(self) -> None:
        ctrl, child = _DummyController(), _DummyChild(12345)
        p_mock, t_mock = Mock(), Mock()
        self.assertFalse(LauncherPipelineExecutor(spawner=Mock(return_value=None), controller=ctrl, pidfd_helper=p_mock).execute(["t"]).success)
        p_mock.acquire_and_verify.assert_not_called()

        ctrl_fail = _DummyController()
        ctrl_fail.pidfd_open = Mock(return_value=-1)
        self.assertFalse(LauncherPipelineExecutor(spawner=lambda a: child, controller=ctrl_fail, transfer_helper=t_mock).execute(["t"]).success)
        t_mock.transfer.assert_not_called()

    def test_invalid_context_and_falsy_comparators(self) -> None:
        ctrl, child = _DummyController(), _BoolRaisesChild(12345)
        ex1 = LauncherPipelineExecutor(controller=ctrl, spawn_helper=_InvalidStageHelper(child))
        res1 = ex1.execute(["t"])
        self.assertTrue(not res1.success and "Stage 1 spawn returned invalid context" in res1.error and res1.pending_cleanup and res1.child_handle is child and res1.pidfd is None)
        self.assertTrue(res1.identity is None and not res1.transferred and not res1.is_direct_child and ex1.pending_child is child and ex1.pending_pidfd is None and ex1.last_exception is None and ex1.active_helper is ex1.spawn_helper)

        ex2 = LauncherPipelineExecutor(controller=ctrl, spawn_helper=LauncherSpawnHelper(spawner=lambda a: child, controller=ctrl), pidfd_helper=_InvalidStageHelper(child, 5))
        res2 = ex2.execute(["t"])
        self.assertTrue(not res2.success and "Stage 2 pidfd acquisition returned invalid context" in res2.error and res2.pending_cleanup and res2.child_handle is child and res2.pidfd == 5)
        self.assertTrue(res2.identity is None and not res2.transferred and not res2.is_direct_child and ex2.pending_child is child and ex2.pending_pidfd == 5 and ex2.last_exception is None and ex2.active_helper is ex2.pidfd_helper)

        ex3 = LauncherPipelineExecutor(controller=ctrl, spawner=lambda a: child, transfer_helper=_InvalidStageHelper(child, 5))
        res3 = ex3.execute(LauncherPreflightHelper().get_frozen_argv())
        self.assertTrue(not res3.success and "Stage 3 transfer returned invalid result" in res3.error and res3.pending_cleanup and res3.child_handle is child and res3.pidfd == 5)
        self.assertTrue(res3.identity is None and not res3.transferred and not res3.is_direct_child and ex3.pending_child is child and ex3.pending_pidfd == 5 and ex3.last_exception is None and ex3.active_helper is ex3.transfer_helper)

        falsy_sh = _FalsySpawnHelper()
        ex_falsy = LauncherPipelineExecutor(controller=ctrl, spawn_helper=falsy_sh)
        self.assertIs(ex_falsy.spawn_helper, falsy_sh)
        self.assertEqual(ex_falsy.execute(["t"]).error, "falsy helper used")

    def test_postbind_fault_and_transferred_state(self) -> None:
        ctrl, child = _DummyController(bound_ok=True), _DummyChild(12345)
        for fault_attr in ("_last_result", "_pending_pidfd"):
            with self.subTest(fault_attr=fault_attr):
                class _PostBindFaultTransferHelper(LauncherTransferHelper):
                    def __setattr__(self, name: str, value: Any) -> None:
                        if name == fault_attr and (
                            (name == "_last_result" and isinstance(value, LaunchResult) and value.success)
                            or (name == "_pending_pidfd" and value is None and getattr(self, "_pending_pidfd", None) is not None)
                        ):
                            raise RuntimeError(f"post-bind {name} fault")
                        super().__setattr__(name, value)

                ex = LauncherPipelineExecutor(spawner=lambda a: child, controller=ctrl, transfer_helper=_PostBindFaultTransferHelper(controller=ctrl))
                res = ex.execute(LauncherPreflightHelper().get_frozen_argv())
                self.assertTrue(not res.success and res.transferred and res.identity is not None)
                self.assertTrue(res.pidfd is None and res.pending_cleanup and res.child_handle is child)
                self.assertIs(ex.last_result, res)
                self.assertTrue(ex.pending_pidfd is None and ex.pending_child is child and ex.last_exception is None)

    def test_resultfactory_doublefailure_primarytraceback(self) -> None:
        ctrl, child = _DummyController(), _DummyChild(12345)
        sec = RuntimeError("secondary constructor error")

        with patch.object(lc_mod, "LaunchResult", side_effect=sec), patch.object(
            lp_mod, "LaunchResult", side_effect=RuntimeError("first factory fail")
        ):
            res_canon = LauncherPipelineExecutor(
                controller=ctrl, spawner=lambda a: child, pidfd_helper=_FailPrimeHelper(None, RuntimeError("pri err")),
            ).execute(["t"])
            self.assertFalse(res_canon.success)
            self.assertIn("primary: RuntimeError", res_canon.error)

        for pri_exc in (RuntimeError("pri err"), GeneratorExit(), KeyboardInterrupt("pri ki"), SystemExit(9)):
            with self.subTest(exc_type=type(pri_exc).__name__):
                with patch.object(lc_mod, "LaunchResult", side_effect=sec), patch.object(
                    lp_mod, "LaunchResult", side_effect=sec
                ), patch.object(lp_mod, "_CANONICAL_LAUNCH_RESULT", side_effect=sec):
                    ex_df = LauncherPipelineExecutor(
                        controller=ctrl, spawner=lambda a: child, pidfd_helper=_FailPrimeHelper(None, pri_exc),
                    )
                    self.assertIsInstance(ex_df.custody_tracker, LauncherCustodyTracker)
                    self._assert_escapes_with_frame(ex_df, pri_exc, "prime")
                    self.assertFalse(isinstance(pri_exc, UnboundLocalError))
