"""Hermetic unit tests for AdmissionCustodyProtocol."""

from __future__ import annotations

import dataclasses
from typing import Any, Optional
import unittest

from scripts.f3_guard.launcher_custody import LauncherCustodySnapshot
from scripts.f3_guard.launcher_custody_protocol import (
    AdmissionCustodyProtocol,
    UNKNOWN,
)
from scripts.f3_guard.launcher_preflight import LaunchResult


class _MockHelper:
    """Mock helper recording calls and raising exceptions on access."""

    def __init__(
        self,
        child: Any = UNKNOWN,
        pidfd: Any = UNKNOWN,
        result: Any = UNKNOWN,
        snap: Optional[LauncherCustodySnapshot] = None,
    ) -> None:
        self.pending_child = child
        self.pending_pidfd = pidfd
        self.last_result = result
        self.last_snapshot = snap
        self.calls: list[str] = []

    def __bool__(self) -> bool:
        raise RuntimeError("trap: __bool__ called")

    def __eq__(self, other: object) -> bool:
        raise RuntimeError("trap: __eq__ called")

    def __getattribute__(self, name: str) -> Any:
        val = super().__getattribute__(name)
        if name in ("pending_child", "pending_pidfd", "last_result", "last_snapshot"):
            if isinstance(val, BaseException):
                raise val
        return val

    def __getattr__(self, name: str) -> Any:
        if "authorit" in name:
            raise AssertionError(f"obsolete authority API accessed: {name}")
        if name in ("close", "kill", "wait", "reap", "spawn"):
            return lambda: self.calls.append(name)
        raise AttributeError(name)


class _CounterTrap:
    """Trap child handle recording bool/call attempts with counters."""

    def __init__(self, exc_or_pid: BaseException | int | None = None) -> None:
        is_exc = isinstance(exc_or_pid, BaseException)
        self.exc = exc_or_pid if is_exc else None
        self.pid = 12345 if (is_exc or exc_or_pid is None) else exc_or_pid
        self.bool_calls = 0
        self.call_calls = 0
        self.calls: list[str] = []

    def __bool__(self) -> bool:
        self.bool_calls += 1
        if self.exc is not None:
            raise self.exc
        return False

    def __call__(self, *args: Any, **kwargs: Any) -> Any:
        self.call_calls += 1
        return True


class _InstrumentedHelper(_MockHelper):
    """Isolated fixture with true getter instrumentation and operation recording."""

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        super().__init__(*args, **kwargs)
        self.getter_counts: dict[str, int] = {
            "pending_child": 0,
            "pending_pidfd": 0,
            "last_result": 0,
            "last_snapshot": 0,
        }

    def __getattribute__(self, name: str) -> Any:
        if name in ("pending_child", "pending_pidfd", "last_result", "last_snapshot"):
            counts = super().__getattribute__("getter_counts")
            counts[name] = counts.get(name, 0) + 1
        return super().__getattribute__(name)


class TestAdmissionCustodyProtocol(unittest.TestCase):
    def _prime_proto(
        self, p_child: Any, p_fd: int, p_res: Any
    ) -> tuple[AdmissionCustodyProtocol, list[Any]]:
        current: list[Any] = [_InstrumentedHelper(child=p_child, pidfd=p_fd, result=p_res)]
        p = AdmissionCustodyProtocol(custody_source=lambda: current[0])
        snap = p.snapshot()
        self.assertIs(p.known_child, p_child)
        self.assertEqual(p.known_pidfd, p_fd)
        self.assertIs(p.known_last_result, p_res)
        self.assertIs(snap.pending_child, p_child)
        self.assertEqual(snap.pending_pidfd, p_fd)
        self.assertIs(snap.last_result, p_res)
        return p, current

    def _assert_custody(
        self, proto: Any, child: Any, pidfd: Any, result: Any
    ) -> None:
        for target in (proto, proto.snapshot()):
            self.assertIs(target.pending_child, child)
            self.assertIs(target.child_handle, child)
            self.assertEqual(target.pending_pidfd, pidfd)
            self.assertEqual(target.pidfd, pidfd)
            if pidfd is not None:
                self.assertIs(type(target.pending_pidfd), int)
                self.assertIsNot(target.pending_pidfd, False)
            self.assertIs(target.last_result, result)

    def _assert_known(
        self, proto: Any, child: Any, pidfd: Any, result: Any
    ) -> None:
        self.assertIs(proto.known_child, child)
        self.assertEqual(proto.known_pidfd, pidfd)
        if pidfd is not None:
            self.assertIs(type(proto.known_pidfd), int)
            self.assertIsNot(proto.known_pidfd, False)
        self.assertIs(proto.known_last_result, result)
        snap = proto.known_snapshot
        self.assertIsInstance(snap, LauncherCustodySnapshot)
        self.assertIs(snap.pending_child, child)
        self.assertEqual(snap.pending_pidfd, pidfd)
        if pidfd is not None:
            self.assertIs(type(snap.pending_pidfd), int)
            self.assertIsNot(snap.pending_pidfd, False)
        self.assertIs(snap.last_result, result)

    def test_default_construction_and_fallback_result(self) -> None:
        proto = AdmissionCustodyProtocol()
        self.assertIsNone(proto.custody_source)
        self.assertIsNone(proto.fallback_result)
        self._assert_custody(proto, None, None, None)

        child = _MockHelper()
        fallback = LaunchResult(
            success=False, child_handle=child, pidfd=42, pending_cleanup=True
        )
        proto.set_fallback_result(fallback)
        self.assertIs(proto.fallback_result, fallback)
        self._assert_custody(proto, child, 42, fallback)
        self.assertIs(proto.take_snapshot().last_result, fallback)

    def test_dynamic_source_replacement_and_clearing(self) -> None:
        c1, c2 = _MockHelper(), _MockHelper()
        r1 = LaunchResult(success=True)
        h1 = _MockHelper(child=c1, pidfd=10, result=r1)
        h2 = _MockHelper(child=c2, pidfd=20)
        current: Any = h1

        proto = AdmissionCustodyProtocol(custody_source=lambda: current)
        self._assert_custody(proto, c1, 10, r1)
        current = h2
        self._assert_custody(proto, c2, 20, None)
        current = None
        self._assert_custody(proto, None, None, None)

    def test_callback_unknown_or_fault_retains_cache(self) -> None:
        child = _MockHelper()
        h = _MockHelper(child=child, pidfd=0)
        mode = "ok"

        def source_cb() -> Any:
            if mode == "unknown":
                return UNKNOWN
            if mode == "fault":
                raise RuntimeError("transient callback error")
            return h

        proto = AdmissionCustodyProtocol(custody_source=source_cb)
        for next_mode in ("ok", "unknown", "fault"):
            mode = next_mode
            self._assert_custody(proto, child, 0, None)

    def test_snapshot_reads_dynamic_source_exactly_once(self) -> None:
        ca, cb = _MockHelper(), _MockHelper()
        ra, rb = LaunchResult(success=True), LaunchResult(success=False)
        ha = _MockHelper(child=ca, pidfd=11, result=ra)
        hb = _MockHelper(child=cb, pidfd=22, result=rb)
        helpers = iter([ha, hb, hb])
        call_count = 0

        def source_cb() -> Any:
            nonlocal call_count
            call_count += 1
            return next(helpers)

        proto = AdmissionCustodyProtocol(custody_source=source_cb)
        for exp_count, exp_c, exp_fd, exp_r in (
            (1, ca, 11, ra),
            (2, cb, 22, rb),
        ):
            s = proto.snapshot()
            self.assertEqual(call_count, exp_count)
            self.assertIs(s.pending_child, exp_c)
            self.assertEqual(s.pending_pidfd, exp_fd)
            self.assertIs(s.last_result, exp_r)

    def test_authority_interfaces_never_accessed(self) -> None:
        h = _MockHelper(child=None, pidfd=None, result=None)
        proto = AdmissionCustodyProtocol(custody_source=h)
        self._assert_custody(proto, None, None, None)

    def test_known_custody_precedes_snapshot_and_transitions(self) -> None:
        ca, cb, cc = _MockHelper(), _MockHelper(), _MockHelper()
        ra, rb = LaunchResult(success=True), LaunchResult(success=False)
        snap_b = LauncherCustodySnapshot(
            pending_child=cb, pending_pidfd=99, last_result=rb
        )
        h = _MockHelper(child=ca, pidfd=7, result=ra, snap=snap_b)

        proto = AdmissionCustodyProtocol(custody_source=h)
        self._assert_custody(proto, ca, 7, ra)

        h.pending_child = UNKNOWN
        h.pending_pidfd = UNKNOWN
        h.last_result = UNKNOWN
        self._assert_custody(proto, ca, 7, ra)

        transitions = [
            ("pending_child", None, None, 7, ra),
            ("pending_child", UNKNOWN, None, 7, ra),
            ("pending_child", cc, cc, 7, ra),
            ("pending_pidfd", None, cc, None, ra),
            ("pending_pidfd", UNKNOWN, cc, None, ra),
            ("pending_pidfd", 0, cc, 0, ra),
            ("last_result", None, cc, 0, None),
            ("last_result", UNKNOWN, cc, 0, None),
            ("last_result", ra, cc, 0, ra),
        ]
        for attr, val, exp_c, exp_fd, exp_r in transitions:
            setattr(h, attr, val)
            self._assert_custody(proto, exp_c, exp_fd, exp_r)

    def test_missing_getters_nonempty_snapshot_fallback(self) -> None:
        child = _MockHelper()
        res = LaunchResult(success=True)
        snap = LauncherCustodySnapshot(
            pending_child=child, pending_pidfd=99, last_result=res
        )
        h = _MockHelper(snap=snap)
        del h.pending_child, h.pending_pidfd, h.last_result
        proto = AdmissionCustodyProtocol(custody_source=h)
        self._assert_custody(proto, child, 99, res)

    def test_ordinary_getter_faults_retain_known_custody(self) -> None:
        child = _MockHelper()
        res = LaunchResult(success=True)
        h = _MockHelper(child=child, pidfd=7, result=res, snap=None)
        proto = AdmissionCustodyProtocol(custody_source=h)
        self._assert_custody(proto, child, 7, res)
        for attr in ("pending_child", "pending_pidfd", "last_result", "last_snapshot"):
            setattr(h, attr, RuntimeError(f"{attr} fault"))
        self._assert_custody(proto, child, 7, res)

    def test_interrupt_exceptions_propagate_same_object_and_traceback(self) -> None:
        cases = [
            lambda e: AdmissionCustodyProtocol(
                custody_source=lambda: (_ for _ in ()).throw(e)
            ),
            lambda e: AdmissionCustodyProtocol(custody_source=_MockHelper(child=e)),
            lambda e: AdmissionCustodyProtocol(custody_source=_MockHelper(snap=e)),
        ]
        for exc_factory in (lambda: KeyboardInterrupt("ki"), lambda: SystemExit(42)):
            for make_proto in cases:
                exc = exc_factory()
                proto = make_proto(exc)
                try:
                    proto.snapshot()
                    self.fail("exception not raised")
                except BaseException as caught:
                    self.assertIs(type(caught), type(exc))
                    self.assertIs(caught, exc)
                    self.assertIsNotNone(caught.__traceback__)

    def test_no_resource_ownership_close_kill_wait_reap_spawn(self) -> None:
        child = _MockHelper()
        h = _MockHelper(child=child, pidfd=12)
        proto = AdmissionCustodyProtocol(custody_source=h)
        self._assert_custody(proto, child, 12, None)
        self.assertEqual(child.calls, [])
        self.assertEqual(h.calls, [])

    def test_known_api_initial_empty_and_fallback(self) -> None:
        proto = AdmissionCustodyProtocol()
        self._assert_known(proto, None, None, None)

        child = _MockHelper()
        fallback = LaunchResult(
            success=False, child_handle=child, pidfd=42, pending_cleanup=True
        )
        proto_fb = AdmissionCustodyProtocol(fallback_result=fallback)
        self._assert_known(proto_fb, child, 42, fallback)

    def test_known_api_partial_child_only_and_fd0_only(self) -> None:
        child = _MockHelper()
        proto_child = AdmissionCustodyProtocol(custody_source=_MockHelper(child=child))
        proto_child.snapshot()
        self._assert_known(proto_child, child, None, None)

        proto_fd0 = AdmissionCustodyProtocol(
            custody_source=_MockHelper(child=UNKNOWN, pidfd=0, result=UNKNOWN)
        )
        proto_fd0.snapshot()
        self._assert_known(proto_fd0, None, 0, None)

    def test_known_after_successful_snapshot_no_additional_getter_callback_counts(self) -> None:
        child = _CounterTrap()
        res = LaunchResult(success=True)
        h = _InstrumentedHelper(child=child, pidfd=0, result=res)
        cb_count = 0

        def src_cb() -> Any:
            nonlocal cb_count
            cb_count += 1
            return h

        proto = AdmissionCustodyProtocol(custody_source=src_cb)
        proto.snapshot()
        self.assertEqual(cb_count, 1)
        self.assertEqual(h.getter_counts["pending_child"], 1)
        self.assertEqual(h.getter_counts["pending_pidfd"], 1)
        self.assertEqual(h.getter_counts["last_result"], 1)
        self.assertEqual(child.bool_calls, 0)
        self.assertEqual(child.call_calls, 0)

        props = [
            ("known_child", lambda: proto.known_child, child, True),
            ("known_pidfd", lambda: proto.known_pidfd, 0, False),
            ("known_last_result", lambda: proto.known_last_result, res, True),
            ("known_snapshot", lambda: proto.known_snapshot, None, False),
        ]

        for _ in range(3):
            for prop_name, reader, exp_val, is_id in props:
                with self.subTest(property=prop_name):
                    base_cb = cb_count
                    base_getters = dict(h.getter_counts)
                    base_ops = len(h.calls)
                    base_bool = child.bool_calls
                    base_calls = child.call_calls

                    val = reader()

                    self.assertEqual(cb_count, base_cb)
                    self.assertEqual(h.getter_counts, base_getters)
                    self.assertEqual(len(h.calls), base_ops)
                    self.assertEqual(child.bool_calls, 0)
                    self.assertEqual(child.call_calls, 0)

                    if is_id:
                        self.assertIs(val, exp_val)
                    elif prop_name == "known_pidfd":
                        self.assertEqual(val, 0)
                        self.assertIs(type(val), int)
                        self.assertIsNot(val, False)
                    elif prop_name == "known_snapshot":
                        self.assertIsInstance(val, LauncherCustodySnapshot)
                        self.assertIs(val.pending_child, child)
                        self.assertEqual(val.pending_pidfd, 0)
                        self.assertIs(type(val.pending_pidfd), int)
                        self.assertIsNot(val.pending_pidfd, False)
                        self.assertIs(val.last_result, res)

    def test_known_read_only_assignment_denied_and_snapshot_immutability(self) -> None:
        proto = AdmissionCustodyProtocol()
        for attr in ("known_child", "known_pidfd", "known_last_result", "known_snapshot"):
            with self.assertRaises(AttributeError):
                setattr(proto, attr, 123)

        snap = proto.known_snapshot
        for attr in ("pending_child", "pending_pidfd", "last_result"):
            with self.assertRaises((dataclasses.FrozenInstanceError, AttributeError)):
                setattr(snap, attr, 123)

    def test_known_child_truthiness_and_call_traps_counter_zero(self) -> None:
        child = _CounterTrap()
        h = _InstrumentedHelper(child=child, pidfd=0)
        proto = AdmissionCustodyProtocol(custody_source=h)
        proto.snapshot()

        self.assertIs(proto.known_child, child)
        self.assertEqual(proto.known_pidfd, 0)
        self.assertIs(type(proto.known_pidfd), int)
        self.assertIsNot(proto.known_pidfd, False)
        self.assertIsNone(proto.known_last_result)
        snap = proto.known_snapshot
        self.assertIs(snap.pending_child, child)
        self.assertEqual(snap.pending_pidfd, 0)
        self.assertEqual(child.bool_calls, 0)
        self.assertEqual(child.call_calls, 0)
        self.assertEqual(child.calls, [])
        self.assertEqual(h.calls, [])

        raising_child = _CounterTrap(RuntimeError("trap raised"))
        proto_raising = AdmissionCustodyProtocol(
            custody_source=_InstrumentedHelper(child=raising_child, pidfd=0)
        )
        proto_raising.snapshot()
        self.assertIs(proto_raising.known_child, raising_child)
        self.assertEqual(proto_raising.known_pidfd, 0)
        self.assertIs(proto_raising.known_snapshot.pending_child, raising_child)
        self.assertEqual(raising_child.bool_calls, 0)
        self.assertEqual(raising_child.call_calls, 0)

    def test_known_invalid_bool_pidfd_rejection_without_invocation(self) -> None:
        for bad_bool in (False, True):
            fb = LaunchResult(success=True, pidfd=bad_bool)
            proto_fb = AdmissionCustodyProtocol(fallback_result=fb)
            self.assertIsNone(proto_fb.known_pidfd)
            self.assertIsNone(proto_fb.known_snapshot.pending_pidfd)

            h = _MockHelper(pidfd=bad_bool)
            proto_h = AdmissionCustodyProtocol(custody_source=h)
            proto_h.snapshot()
            self.assertIsNone(proto_h.known_pidfd)
            self.assertIsNone(proto_h.known_snapshot.pending_pidfd)

        fb_zero = LaunchResult(success=True, pidfd=0)
        proto_zero = AdmissionCustodyProtocol(fallback_result=fb_zero)
        self.assertEqual(proto_zero.known_pidfd, 0)
        self.assertIs(type(proto_zero.known_pidfd), int)
        self.assertIsNot(proto_zero.known_pidfd, False)

    def test_known_partial_retention_interruption_kise_both_orders_and_ordinary_faults(self) -> None:
        for exc in (KeyboardInterrupt("ki"), SystemExit(2)):
            for order, new_c, new_fd, exp_fd in (
                ("pidfdinterrupt", _CounterTrap(102), exc, 100),
                ("resultinterrupt", _CounterTrap(202), 0, 0),
            ):
                with self.subTest(exc_type=type(exc).__name__, order=order):
                    old_c, old_fd, old_r = _CounterTrap(101), 100, LaunchResult(success=True)
                    p, cur = self._prime_proto(old_c, old_fd, old_r)
                    h = cur[0]
                    old_snap = p._cache
                    self.assertIsNotNone(old_snap)

                    h.pending_child = new_c
                    h.pending_pidfd = new_fd
                    h.last_result = exc if order == "resultinterrupt" else LaunchResult(False)

                    try:
                        p.snapshot()
                        self.fail("exception not raised")
                    except BaseException as caught:
                        self.assertIs(type(caught), type(exc))
                        self.assertIs(caught, exc)
                        self.assertIsNotNone(caught.__traceback__)

                    self.assertIs(cur[0], h)
                    self.assertIs(p._cache, old_snap)
                    self.assertIs(p._cache.pending_child, old_c)
                    self.assertEqual(p._cache.pending_pidfd, old_fd)
                    self.assertIs(p._cache.last_result, old_r)

                    self.assertIs(p.known_child, new_c)
                    self.assertIsNot(p.known_child, old_c)
                    self.assertEqual(p.known_pidfd, exp_fd)
                    if order == "resultinterrupt":
                        self.assertNotEqual(p.known_pidfd, old_fd)
                    self.assertIs(type(p.known_pidfd), int)
                    self.assertIsNot(p.known_pidfd, False)
                    self.assertIs(p.known_last_result, old_r)

                    snap = p.known_snapshot
                    self.assertIs(snap.pending_child, new_c)
                    self.assertIsNot(snap.pending_child, old_c)
                    self.assertEqual(snap.pending_pidfd, exp_fd)
                    self.assertIs(type(snap.pending_pidfd), int)
                    self.assertIsNot(snap.pending_pidfd, False)
                    self.assertIs(snap.last_result, old_r)

                    self.assertEqual(new_c.bool_calls, 0)
                    self.assertEqual(new_c.call_calls, 0)
                    self.assertEqual(old_c.bool_calls, 0)
                    self.assertEqual(old_c.call_calls, 0)

        child = _MockHelper()
        res = LaunchResult(success=True)
        h_faults = _MockHelper(child=child, pidfd=7, result=res)
        p_faults = AdmissionCustodyProtocol(custody_source=h_faults)
        p_faults.snapshot()
        self.assertIs(p_faults.known_child, child)
        self.assertEqual(p_faults.known_pidfd, 7)

        for attr in ("pending_child", "pending_pidfd", "last_result", "last_snapshot"):
            setattr(h_faults, attr, RuntimeError(f"{attr} fault"))
        p_faults.snapshot()
        self.assertIs(p_faults.known_child, child)
        self.assertEqual(p_faults.known_pidfd, 7)
        self.assertIs(p_faults.known_last_result, res)

    def test_known_stale_until_snapshot_semantics_and_no_active_source_mutation(self) -> None:
        child1 = _CounterTrap(10)
        res1 = LaunchResult(success=True)
        h = _InstrumentedHelper(child=child1, pidfd=9, result=res1)
        proto = AdmissionCustodyProtocol(custody_source=h)

        self.assertIsNone(proto.known_child)
        self.assertIsNone(proto.known_pidfd)
        self.assertIsNone(proto.known_last_result)
        self.assertIsNone(proto.known_snapshot.pending_child)
        self.assertEqual(h.calls, [])

        proto.snapshot()
        self._assert_known(proto, child1, 9, res1)

        snap1a, snap1b = proto.known_snapshot, proto.known_snapshot
        self.assertIsNot(snap1a, snap1b)
        self.assertIs(snap1a.pending_child, child1)
        self.assertEqual(snap1a.pending_pidfd, 9)
        self.assertIs(snap1a.last_result, res1)

        child2 = _CounterTrap(20)
        res2 = LaunchResult(success=False)
        h.pending_child, h.pending_pidfd, h.last_result = child2, 0, res2

        self._assert_known(proto, child1, 9, res1)
        snap_stale = proto.known_snapshot
        self.assertIsNot(snap_stale, snap1a)
        self.assertIs(snap_stale.pending_child, child1)

        proto.snapshot()
        self._assert_known(proto, child2, 0, res2)

        snap2a, snap2b = proto.known_snapshot, proto.known_snapshot
        self.assertIsNot(snap2a, snap2b)
        self.assertIsNot(snap2a, snap1a)
        self.assertIs(snap2a.pending_child, child2)
        self.assertEqual(snap2a.pending_pidfd, 0)
        self.assertIs(snap2a.last_result, res2)

        child3, res3 = _CounterTrap(30), LaunchResult(success=True)
        proto.set_custody_source(_InstrumentedHelper(child=child3, pidfd=77, result=res3))

        self._assert_known(proto, child2, 0, res2)

        proto.snapshot()
        self._assert_known(proto, child3, 77, res3)
        snap3 = proto.known_snapshot
        self.assertIsNot(snap3, snap2a)
        self.assertIs(snap3.pending_child, child3)
        self.assertEqual(snap3.pending_pidfd, 77)


if __name__ == "__main__":
    unittest.main()
