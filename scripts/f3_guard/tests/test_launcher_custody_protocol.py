"""Hermetic unit tests for AdmissionCustodyProtocol."""

from __future__ import annotations

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


class TestAdmissionCustodyProtocol(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
