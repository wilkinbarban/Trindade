"""Hermetic unit tests for SupervisorScheduler pacing, caps, and durability."""

from __future__ import annotations

import time
import unittest
from typing import Callable, List, Optional, Tuple
from unittest.mock import MagicMock, patch

from scripts.f3_guard.clock import Clock, FakeClock
from scripts.f3_guard.readiness import _ProbeState
from scripts.f3_guard.scheduler import SupervisorScheduler
from scripts.f3_guard.types import LifecycleState


class FakeEngine:
    """Scripted duck-typed double satisfying SupervisorScheduler contracts."""

    def __init__(
        self, clock: Optional[Clock] = None, poll_interval: float = 0.5,
        steps: Optional[List[Tuple[bool, LifecycleState]]] = None,
        step_fn: Optional[Callable[[], Tuple[bool, LifecycleState]]] = None,
        ttl_rem: Optional[Callable[[], float]] = None,
        mem_cadence: Optional[float] = None, probe_deadline: Optional[float] = None,
        terminate_fn: Optional[Callable[[str], None]] = None,
    ):
        self._clock, self._poll_interval = clock, poll_interval
        self._steps = list(steps) if steps is not None else []
        self._step_fn, self._terminate_fn = step_fn, terminate_fn
        self.step_count = self.cleanup_count = 0
        self.state = LifecycleState.RUNNING
        self.termination_state = self.primary_error = self.secondary_error = None
        self.ttl_tracker = type("T", (), {"remaining_seconds": staticmethod(ttl_rem)})() if ttl_rem else None
        self._mem_reader = True if mem_cadence else None
        self._cadence_sec, self._last_mem_sample_mono = mem_cadence or 5.0, 0.0
        self._probe_state = _ProbeState.RUNNING if probe_deadline else _ProbeState.IDLE
        self._probe_deadline_sec, self._probe_start_mono = probe_deadline or 15.0, 0.0

    def step(self) -> Tuple[bool, LifecycleState]:
        self.step_count += 1
        res = self._step_fn() if self._step_fn else (self._steps.pop(0) if self._steps else (True, LifecycleState.STOPPED))
        self.state = res[1]
        if res[0]:
            self.termination_state = res[1]
        return res

    def _terminate(self, reason: str = "") -> None:
        self.cleanup_count += 1
        self.state = self.termination_state = LifecycleState.STOPPED
        if self._terminate_fn:
            self._terminate_fn(reason)


class SeqClock(Clock):
    def __init__(self, seq: List[object]):
        self._seq = list(seq)

    def monotonic(self) -> float:
        v = self._seq.pop(0) if self._seq else 0.0
        if isinstance(v, Exception):
            raise v
        return v  # type: ignore[return-value]

    def time(self) -> float:
        return 0.0


class TestSupervisorScheduler(unittest.TestCase):
    """Hermetic unit tests for SupervisorScheduler pacing and contracts."""

    def setUp(self) -> None:
        fake_time = [1000.0]
        sp = patch.object(time, "sleep", side_effect=lambda s: fake_time.__setitem__(0, fake_time[0] + max(0.001, float(s))))
        mp = patch.object(time, "monotonic", side_effect=lambda: fake_time[0])
        sp.start(); mp.start()
        self.addCleanup(sp.stop); self.addCleanup(mp.stop)
        orig_open = open
        def _guarded_open(file, *args, **kwargs):
            if isinstance(file, str) and file.startswith("/proc"):
                raise AssertionError(f"Unexpected real /proc open: {file}")
            return orig_open(file, *args, **kwargs)
        op = patch("builtins.open", side_effect=_guarded_open)
        op.start()
        self.addCleanup(op.stop)

    def _assert_error_state(self, sched: SupervisorScheduler, engine: FakeEngine) -> None:
        for obj in (sched, engine):
            self.assertEqual(obj.state, LifecycleState.ERROR)
            self.assertEqual(obj.termination_state, LifecycleState.ERROR)

    def _run_pacing(
        self, engine: FakeEngine, interval: float, clock: FakeClock
    ) -> Tuple[SupervisorScheduler, LifecycleState, List[float]]:
        sleeps: List[float] = []
        sched = SupervisorScheduler(
            engine, poll_interval=interval, clock=clock,
            sleeper=lambda d: (sleeps.append(d), clock.advance_monotonic(d)),
        )
        return sched, sched.run(), sleeps

    def test_strict_interval_and_sleeper_defaults(self) -> None:
        engine = FakeEngine()
        for bad_pi in [False, True, 0, 0.0, -1.0, float("nan"), float("inf"), "0.5"]:
            with self.assertRaises(ValueError):
                SupervisorScheduler(engine, poll_interval=bad_pi)
        for bad_sl in ["not_callable", 123, False]:
            with self.assertRaises(TypeError):
                SupervisorScheduler(engine, sleeper=bad_sl)
        sched_def = SupervisorScheduler(engine, sleeper=None)
        self.assertIs(sched_def._sleeper, time.sleep)

    def test_different_clock_rejection_and_identity(self) -> None:
        clock_a, clock_b = FakeClock(monotonic_start=0.0), FakeClock(monotonic_start=0.0)
        engine = FakeEngine(clock=clock_a)
        with self.assertRaises(ValueError):
            SupervisorScheduler(engine, clock=clock_b)
        sched_a = SupervisorScheduler(engine, clock=clock_a)
        self.assertIs(sched_a._clock, clock_a)
        sched_none = SupervisorScheduler(engine, clock=None)
        self.assertIs(sched_none._clock, clock_a)

    def test_pacing_grid_work_duration_anchored(self) -> None:
        clock = FakeClock(monotonic_start=0.0)

        def step_fn() -> Tuple[bool, LifecycleState]:
            if engine.step_count == 1:
                clock.advance_monotonic(0.3)
                return False, LifecycleState.RUNNING
            if engine.step_count == 2:
                clock.advance_monotonic(0.4)
                return False, LifecycleState.RUNNING
            return True, LifecycleState.STOPPED

        engine = FakeEngine(clock=clock, step_fn=step_fn)
        _, res, sleeps = self._run_pacing(engine, 1.0, clock)
        self.assertEqual(res, LifecycleState.STOPPED)
        self.assertEqual([round(s, 2) for s in sleeps], [0.7, 0.6])
        self.assertEqual(clock.monotonic(), 2.0)

    def test_caps_ttl_memory_and_probe_deadline(self) -> None:
        # 1. Cap TTL
        clk_t = FakeClock(monotonic_start=0.0)
        eng_t = FakeEngine(clock=clk_t, poll_interval=1.0, ttl_rem=lambda: max(0.0, 2.5 - clk_t.monotonic()),
                           steps=[(False, LifecycleState.RUNNING)] * 3 + [(True, LifecycleState.STOPPED)])
        _, res_t, s_t = self._run_pacing(eng_t, 1.0, clk_t)
        self.assertEqual((res_t, [round(s, 2) for s in s_t]), (LifecycleState.STOPPED, [1.0, 1.0, 0.5]))

        # 2. Cap Memory Cadence
        clk_m = FakeClock(monotonic_start=0.0)
        eng_m = FakeEngine(clock=clk_m, poll_interval=3.0, mem_cadence=5.0,
                           steps=[(False, LifecycleState.RUNNING)] * 2 + [(True, LifecycleState.STOPPED)])
        _, res_m, s_m = self._run_pacing(eng_m, 3.0, clk_m)
        self.assertEqual((res_m, [round(s, 2) for s in s_m]), (LifecycleState.STOPPED, [3.0, 2.0]))

        # 3. Cap Probe Deadline
        clk_p = FakeClock(monotonic_start=0.0)
        eng_p = FakeEngine(clock=clk_p, poll_interval=3.0, probe_deadline=4.0,
                           steps=[(False, LifecycleState.RUNNING)] * 2 + [(True, LifecycleState.STOPPED)])
        _, _, s_p = self._run_pacing(eng_p, 3.0, clk_p)
        self.assertEqual([round(s, 2) for s in s_p[:2]], [3.0, 1.0])

    def test_exact_probe_equality_pending_progresses_no_hang(self) -> None:
        clock = FakeClock(monotonic_start=0.0)
        engine = FakeEngine(
            clock=clock, poll_interval=1.0, probe_deadline=4.0,
            steps=[(False, LifecycleState.RUNNING)] * 5 + [(True, LifecycleState.STOPPED)],
        )
        _, state, sleeps = self._run_pacing(engine, 1.0, clock)
        self.assertEqual(state, LifecycleState.STOPPED)
        self.assertEqual([round(s, 2) for s in sleeps], [1.0, 1.0, 1.0, 1.0, 1.0])
        self.assertEqual(clock.monotonic(), 5.0)

    def test_first_pending_no_ready_memory_at_5_ttl_anr_halt(self) -> None:
        clk0 = FakeClock(monotonic_start=0.0)
        sleep_mock = MagicMock(side_effect=lambda d: clk0.advance_monotonic(d))
        eng0 = FakeEngine(clock=clk0, steps=[(False, LifecycleState.RUNNING), (True, LifecycleState.STOPPED)])
        sched0 = SupervisorScheduler(eng0, poll_interval=1.0, clock=clk0, sleeper=sleep_mock)
        res0 = sched0.run()
        self.assertEqual((res0, sched0.state, sched0.termination_state), (LifecycleState.STOPPED, LifecycleState.STOPPED, LifecycleState.STOPPED))
        self.assertEqual((eng0.step_count, sleep_mock.call_count), (2, 1))

        clk_m = FakeClock(monotonic_start=0.0)
        eng_m = FakeEngine(clock=clk_m, poll_interval=2.0, mem_cadence=5.0,
                           ttl_rem=lambda: max(0.0, 6.0 - clk_m.monotonic()),
                           steps=[(False, LifecycleState.RUNNING)] * 4 + [(True, LifecycleState.STOPPED)])
        _, res_m, _ = self._run_pacing(eng_m, 2.0, clk_m)
        self.assertEqual((res_m, eng_m.step_count, clk_m.monotonic()), (LifecycleState.STOPPED, 5, 6.0))

        clk_a = FakeClock(monotonic_start=0.0)
        eng_a = FakeEngine(clock=clk_a, poll_interval=1.0, steps=[(True, LifecycleState.STOPPED)])
        _, res_a, _ = self._run_pacing(eng_a, 1.0, clk_a)
        self.assertEqual((res_a, eng_a.step_count), (LifecycleState.STOPPED, 1))

    def test_no_halt_extra_sleep(self) -> None:
        clock = FakeClock(monotonic_start=0.0)
        engine = FakeEngine(clock=clock, steps=[(True, LifecycleState.STOPPED)])
        sleep_mock = MagicMock()
        sched = SupervisorScheduler(engine, clock=clock, sleeper=sleep_mock)
        res = sched.run()
        self.assertEqual(res, LifecycleState.STOPPED)
        self.assertEqual(sleep_mock.call_count, 0)

        step_err = RuntimeError("step failure")
        eng_fail = FakeEngine(clock=clock, step_fn=MagicMock(side_effect=step_err))
        sched_fail = SupervisorScheduler(eng_fail, clock=clock, sleeper=sleep_mock)
        with self.assertRaises(RuntimeError) as ctx:
            sched_fail.run()
        self.assertIs(ctx.exception, step_err)
        self._assert_error_state(sched_fail, eng_fail)
        self.assertEqual((eng_fail.cleanup_count, sched_fail.primary_error), (1, step_err))

    def test_clock_nan_bool_regression_and_read_exception(self) -> None:
        for name, seq in [("nan", [0.0, float("nan")]), ("bool", [True]),
                          ("backwards", [10.0, 10.0, 9.0]), ("err", [0.0, 0.0, 0.0, RuntimeError("clk fail")])]:
            with self.subTest(case=name):
                clk = SeqClock(seq)
                eng = FakeEngine(clock=clk, steps=[(False, LifecycleState.RUNNING)] * 5)
                sched = SupervisorScheduler(eng, clock=clk, sleeper=lambda d: None)
                with self.assertRaises(RuntimeError):
                    sched.run()
                self._assert_error_state(sched, eng)

    def test_sleeper_keyboard_interrupt_and_system_exit_preserved(self) -> None:
        for exc in [KeyboardInterrupt("simulated ctrl-c"), SystemExit(77)]:
            with self.subTest(exc=type(exc).__name__):
                clk = FakeClock(monotonic_start=0.0)
                eng = FakeEngine(clock=clk, steps=[(False, LifecycleState.RUNNING)])
                sched = SupervisorScheduler(eng, clock=clk, sleeper=MagicMock(side_effect=exc))
                with self.assertRaises(type(exc)) as ctx:
                    sched.run()
                self.assertIs(ctx.exception, exc)
                self.assertEqual(eng.cleanup_count, 1)
                self.assertEqual(sched.state, LifecycleState.ERROR)
                self.assertEqual(eng.state, LifecycleState.ERROR)

    def test_no_progress_sleeper_fails_closed(self) -> None:
        clk_eq = FakeClock(monotonic_start=0.0)
        eng_eq = FakeEngine(clock=clk_eq, steps=[(False, LifecycleState.RUNNING), (True, LifecycleState.STOPPED)])
        sched_eq = SupervisorScheduler(eng_eq, clock=clk_eq, sleeper=lambda d: None)
        self.assertEqual(sched_eq.run(), LifecycleState.STOPPED)

        clk_b = SeqClock([10.0, 10.0, 9.0])
        eng_b = FakeEngine(clock=clk_b, steps=[(False, LifecycleState.RUNNING)])
        sched_b = SupervisorScheduler(eng_b, clock=clk_b, sleeper=lambda d: None)
        with self.assertRaises(RuntimeError):
            sched_b.run()
        self._assert_error_state(sched_b, eng_b)

    def test_exact_six_exception_cleanup_combinations_and_durability(self) -> None:
        """Test exact 6 combos: RuntimeError/KI/SE x cleanup success/failure."""
        for exc in [RuntimeError("primary failure"), KeyboardInterrupt("primary ctrl-c"), SystemExit(99)]:
            for c_mode, c_fail in [("success", False), ("failure", True)]:
                with self.subTest(exc=type(exc).__name__, cleanup=c_mode):
                    clk = FakeClock(monotonic_start=0.0)
                    def term_fail(reason: str) -> None:
                        raise RuntimeError("cleanup_terminate failure")
                    eng = FakeEngine(clock=clk, steps=[(False, LifecycleState.RUNNING)],
                                     terminate_fn=term_fail if c_fail else None)
                    sched = SupervisorScheduler(eng, clock=clk, sleeper=MagicMock(side_effect=exc))
                    with self.assertRaises(type(exc)) as ctx:
                        sched.run()

                    self.assertIs(ctx.exception, exc)
                    self._assert_error_state(sched, eng)
                    self.assertEqual((eng.cleanup_count, sched.primary_error, eng.primary_error), (1, exc, exc))
                    if c_fail:
                        self.assertIsInstance(sched.secondary_error, RuntimeError)
                        self.assertIsInstance(eng.secondary_error, RuntimeError)
                    else:
                        self.assertIsNone(sched.secondary_error)
                        self.assertIsNone(eng.secondary_error)

    def test_cleanup_secondary_interrupt_preserves_primary_and_durable_errors(self) -> None:
        for sec_exc in [KeyboardInterrupt("secondary cleanup interrupt"), SystemExit(42)]:
            with self.subTest(sec=type(sec_exc).__name__):
                clk = FakeClock(monotonic_start=0.0)
                eng = FakeEngine(clock=clk, steps=[(False, LifecycleState.RUNNING)], terminate_fn=MagicMock(side_effect=sec_exc))
                prim_re = RuntimeError("primary ordinary failure")
                sched = SupervisorScheduler(eng, clock=clk, sleeper=MagicMock(side_effect=prim_re))
                with self.assertRaises(type(sec_exc)) as ctx:
                    sched.run()
                self.assertIs(ctx.exception, sec_exc)
                self._assert_error_state(sched, eng)
                self.assertEqual((sched.primary_error, sched.secondary_error, eng.cleanup_count), (prim_re, sec_exc, 1))


if __name__ == "__main__":
    unittest.main()
