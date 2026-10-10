"""Hermetic composition tests for the guarded runtime entry (W1)."""

from __future__ import annotations

import os
import select
import signal
import subprocess
import unittest
from contextlib import ExitStack
from typing import Any, Callable, Dict, List, Optional, Tuple
from unittest.mock import MagicMock, patch

import scripts.f3_guard.runtime as runtime
from scripts.f3_guard.adb_adapter import AdbProbeAdapter
from scripts.f3_guard.clock import Clock, FakeClock
from scripts.f3_guard.controller import ProcessController
from scripts.f3_guard.engine import SupervisorEngine
from scripts.f3_guard.probe_handle import ProbeCommandHandle
from scripts.f3_guard.types import LifecycleState, ProcessIdentity

HEALTHY_PROBE_OUTPUT = (
    "ACTIVITY MANAGER RUNNING PROCESSES\n"
    "  ProcessRecord{a1b2c3 42:com.example.app/1000}"
    " notResponding=false crashing=false"
)
_MISSING = object()


class _DuckEngine:
    """Duck-typed engine with an optional clock attribute."""

    def __init__(self, clock: Any = _MISSING) -> None:
        if clock is not _MISSING:
            self._clock = clock

    def step(self) -> Tuple[bool, LifecycleState]:
        return True, LifecycleState.STOPPED


class _FlakyClockEngine:
    """Duck-typed engine returning a new clock on each access."""

    def __init__(self, clocks: List[Clock]) -> None:
        self._clocks = list(clocks)

    @property
    def _clock(self) -> Clock:
        return self._clocks.pop(0)

    def step(self) -> Tuple[bool, LifecycleState]:
        return True, LifecycleState.STOPPED


class TestRuntimeComposition(unittest.TestCase):
    """Hermetic composition tests with every kernel seam injected."""

    def setUp(self) -> None:
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.real_sleeps = 0
        self.real_clock_reads = 0
        real_open = open

        def _sleep(delay: float) -> None:
            self.real_sleeps += 1
            raise AssertionError("real time.sleep reached")

        def _monotonic() -> float:
            self.real_clock_reads += 1
            raise AssertionError("real clock reached")

        def _open(file: Any, *args: Any, **kwargs: Any) -> Any:
            raw = getattr(file, "__fspath__", lambda: file)()
            text = str(raw.decode("utf-8", "surrogateescape")
                       if isinstance(raw, bytes) else raw)
            if text.startswith("/proc") or "/proc/" in text:
                raise AssertionError(f"unexpected /proc access: {file}")
            return real_open(file, *args, **kwargs)

        seams: Dict[str, Any] = {
            "time.sleep": _sleep,
            "time.monotonic": _monotonic,
            "time.time": AssertionError("time.time"),
            "builtins.open": _open,
            "os.kill": AssertionError("os.kill"),
            "os.waitpid": AssertionError("waitpid"),
            "select.poll": AssertionError("select.poll"),
            "subprocess.Popen": AssertionError("Popen"),
            "subprocess.run": AssertionError("run"),
            "subprocess.call": AssertionError("call"),
            "subprocess.check_call": AssertionError("check_call"),
            "subprocess.check_output": AssertionError("check_output"),
        }
        if hasattr(os, "pidfd_open"):
            seams["os.pidfd_open"] = AssertionError("os.pidfd_open")
        if hasattr(signal, "pidfd_send_signal"):
            seams["signal.pidfd_send_signal"] = AssertionError("pidfd")
        for target, effect in seams.items():
            self.stack.enter_context(patch(target, side_effect=effect))

        self.target = ProcessIdentity(
            pid=100, start_ticks=500, uid=1000, avd="test_avd",
            port=5554, binary_name="qemu-system-x86_64")
        self.controller = MagicMock(spec=ProcessController)
        self.controller.cleanup_bound_pidfd.return_value = None
        self.controller.get_start_ticks.return_value = self.target.start_ticks
        self.controller.get_uid.return_value = self.target.uid
        self.controller.get_cmdline.return_value = [
            self.target.binary_name, "-avd", self.target.avd,
            "-port", str(self.target.port)]
        self.sink: Dict[str, Any] = {}

    def tearDown(self) -> None:
        self.assertEqual(self.real_sleeps, 0)
        self.assertEqual(self.real_clock_reads, 0)

    def _engine(self, clock: FakeClock, **kwargs: Any) -> SupervisorEngine:
        return SupervisorEngine(
            self.target, self.controller, clock=clock, **kwargs)

    def test_wiring_passes_scheduler_run_engine_and_sink(self) -> None:
        clock = FakeClock(monotonic_start=0.0)
        engine = self._engine(clock)
        sleeper = MagicMock()
        probe = MagicMock(return_value=True)
        with patch.object(runtime, "SupervisorScheduler") as sched_cls, \
                patch.object(runtime, "run_guarded_supervisor") as runner:
            sched = sched_cls.return_value
            runner.return_value = LifecycleState.STOPPED
            result = runtime.run_guard(
                engine, self.target, self.controller, self.sink,
                cleanup_probe=probe, grace_seconds=7.5,
                termination_poll_interval=0.75, pacing_interval=2.5,
                sleeper=sleeper)
            args, kwargs = runner.call_args
            self.assertIs(args[0], sched.run)
            self.assertIs(args[1], self.target)
            self.assertIs(args[2], self.controller)
            self.assertIs(args[3], self.sink)
            self.assertIs(kwargs["engine"], engine)
            self.assertIs(kwargs["cleanup_probe"], probe)
            self.assertEqual(kwargs["grace_seconds"], 7.5)
            self.assertEqual(kwargs["poll_interval"], 0.75)
            sched_cls.assert_called_once_with(
                engine, poll_interval=2.5, clock=clock, sleeper=sleeper)
        self.assertEqual(result, LifecycleState.STOPPED)

    def test_pacing_and_termination_intervals_never_conflated(self) -> None:
        clock = FakeClock(monotonic_start=0.0)
        engine = self._engine(clock, poll_interval=1.25)
        with patch.object(runtime, "SupervisorScheduler") as sched_cls, \
                patch.object(runtime, "run_guarded_supervisor") as runner:
            runner.return_value = LifecycleState.STOPPED
            runtime.run_guard(
                engine, self.target, self.controller, self.sink,
                pacing_interval=2.5, termination_poll_interval=0.75)
            pacing = sched_cls.call_args.kwargs["poll_interval"]
            termination = runner.call_args.kwargs["poll_interval"]
            self.assertEqual((pacing, termination), (2.5, 0.75))
            self.assertNotEqual(pacing, termination)
            sched_cls.reset_mock()
            runner.reset_mock()
            runtime.run_guard(
                engine, self.target, self.controller, self.sink)
            self.assertIsNone(sched_cls.call_args.kwargs["poll_interval"])
            self.assertEqual(runner.call_args.kwargs["poll_interval"], 0.5)
            self.assertNotEqual(engine._poll_interval, 0.5)

    def test_real_engine_halts_with_shared_clock_and_fake_transport(
        self,
    ) -> None:
        clock = FakeClock(monotonic_start=0.0)
        handles: List[ProbeCommandHandle] = []

        def transport(cmd: List[str]) -> ProbeCommandHandle:
            handle = MagicMock(spec=ProbeCommandHandle)
            handle.poll.return_value = 0
            handle.read_available.return_value = HEALTHY_PROBE_OUTPUT, ""
            handle.cleanup.return_value = True
            handles.append(handle)
            return handle

        adapter = AdbProbeAdapter(
            command=["adb", "shell", "dumpsys", "activity", "processes"],
            transport=transport, clock=clock)
        engine = self._engine(clock, ttl_sec=1.0, adapter=adapter)
        sleeper = MagicMock(side_effect=clock.advance_monotonic)
        with patch(
            "scripts.f3_guard.engine_termination.safe_sigterm",
            return_value=(LifecycleState.STOPPED, "hermetic sigterm"),
        ):
            result = runtime.run_guard(
                engine, self.target, self.controller, self.sink,
                pacing_interval=0.25, sleeper=sleeper)
        self.assertIsInstance(result, LifecycleState)
        self.assertEqual(result, LifecycleState.STOPPED)
        for key in ("state", "primary_state", "termination_state"):
            self.assertEqual(self.sink.get(key), LifecycleState.STOPPED)
        self.assertIn("TTL", str(self.sink.get("termination_detail")))
        self.assertGreater(clock.monotonic(), 0.0)
        self.assertGreaterEqual(sleeper.call_count, 1)
        self.assertGreaterEqual(len(handles), 2)
        self.assertTrue(all(h.cleanup.called for h in handles))

    def test_conflicting_clock_rejected_and_missing_clock_fails_closed(
        self,
    ) -> None:
        flaky = _FlakyClockEngine([FakeClock(), FakeClock()])
        with self.assertRaises(ValueError) as ctx:
            runtime.run_guard(
                flaky, self.target, self.controller, self.sink)
        self.assertIn("Conflicting clock", str(ctx.exception))
        self.assertEqual(self.sink, {})

        unusable = (
            _DuckEngine(), _DuckEngine(None), _DuckEngine(42),
        )
        for engine in unusable:
            with self.assertRaises(ValueError) as ctx:
                runtime.run_guard(
                    engine, self.target, self.controller, self.sink)
            self.assertIn("_clock", str(ctx.exception))
        self.assertEqual(self.sink, {})

    def test_cleanup_order_and_exception_precedence_survive_layer(
        self,
    ) -> None:
        clock = FakeClock(monotonic_start=0.0)
        calls: List[str] = []

        def _step() -> Tuple[bool, LifecycleState]:
            calls.append("body")
            return True, LifecycleState.STOPPED

        engine = self._engine(clock)
        engine.step = _step  # type: ignore[method-assign]
        self.controller.cleanup_bound_pidfd.side_effect = (
            lambda: calls.append("controller"))

        def _fake_sigterm(*args: Any, **kwargs: Any) -> Tuple[
            LifecycleState, str
        ]:
            calls.append("sigterm")
            return LifecycleState.STOPPED, "Clean exit"

        with patch(
            "scripts.f3_guard.runner.safe_sigterm",
            side_effect=_fake_sigterm,
        ), patch(
            "scripts.f3_guard.engine_termination.safe_sigterm",
            return_value=(LifecycleState.STOPPED, "hermetic"),
        ):
            result = runtime.run_guard(
                engine, self.target, self.controller, self.sink,
                cleanup_probe=lambda: calls.append("probe"),
                sleeper=MagicMock())
            self.assertEqual(result, LifecycleState.STOPPED)
            self.assertEqual(
                calls, ["body", "probe", "sigterm", "controller"])

            body_exc = ValueError("body crash")
            engine.step = MagicMock(side_effect=body_exc)
            self.sink = {}
            result = runtime.run_guard(
                engine, self.target, self.controller, self.sink,
                sleeper=MagicMock())
            self.assertEqual(result, LifecycleState.ERROR)
            self.assertIs(self.sink.get("body_exception"), body_exc)
            self.assertEqual(self.sink.get("state"), LifecycleState.ERROR)
            self.assertEqual(
                self.sink.get("termination_state"), LifecycleState.ERROR)
            self.assertIn(
                "Engine outcome invalid",
                str(self.sink.get("termination_detail")))

            for intr in (KeyboardInterrupt("stop"), SystemExit(42)):
                engine.step = MagicMock(side_effect=intr)
                sink: Dict[str, Any] = {}
                with self.assertRaises(type(intr)) as ctx:
                    runtime.run_guard(
                        engine, self.target, self.controller, sink,
                        sleeper=MagicMock())
                self.assertIs(ctx.exception, intr)
                self.assertIs(sink.get("body_exception"), intr)

    def test_kernel_seams_are_patched_and_untouched(self) -> None:
        probes: List[Callable[[], Any]] = [
            lambda: os.kill(1, 0),
            lambda: os.waitpid(1, 0),
            lambda: select.poll(),
            lambda: subprocess.Popen(["/bin/true"]),
            lambda: open("/proc/self/stat"),
        ]
        if hasattr(os, "pidfd_open"):
            probes.append(lambda: os.pidfd_open(1, 0))
        for probe in probes:
            with self.assertRaises(AssertionError):
                probe()


if __name__ == "__main__":
    unittest.main()
