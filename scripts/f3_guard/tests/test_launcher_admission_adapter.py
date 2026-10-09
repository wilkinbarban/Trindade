"""Hermetic unit tests for pure admission custody adapter."""

import inspect
import unittest
from typing import Any, Optional

from scripts.f3_guard.launcher_admission_adapter import (
    LauncherAdmissionAdapter,
    LauncherAdmissionCustodyAdapter,
)
from scripts.f3_guard.launcher_custody import LauncherCustodySnapshot
from scripts.f3_guard.launcher_preflight import LaunchResult

_ACTIVE_ATTRS = (
    "pending_child", "child_handle", "pending_pidfd", "pidfd",
    "last_result", "snapshot", "take_snapshot",
)


class _ChildTrap:
    def __init__(self) -> None:
        self.bool_calls = self.invocations = 0

    def __bool__(self) -> bool:
        self.bool_calls += 1
        return False

    def __call__(self, *args: Any, **kwargs: Any) -> Any:
        self.invocations += 1
        raise AssertionError("Child trap invoked")


class _CustomBase(BaseException):
    pass


class _CountingSource:
    def __init__(
        self, child: Any = None, pidfd: Optional[int] = None,
        last: Optional[LaunchResult] = None, fault: Optional[BaseException] = None,
        fault_attr: str = "pending_pidfd",
    ) -> None:
        self.child, self.pidfd, self.last = child, pidfd, last
        self.fault, self.fault_attr = fault, fault_attr
        self.cb_calls = self.child_polls = self.pidfd_polls = self.last_polls = 0

    def __call__(self) -> Any:
        self.cb_calls += 1
        return self

    def _poll(self, attr: str, val: Any) -> Any:
        if self.fault is not None and self.fault_attr == attr:
            raise self.fault
        return val

    @property
    def pending_child(self) -> Any:
        self.child_polls += 1
        return self._poll("pending_child", self.child)

    @property
    def pending_pidfd(self) -> Optional[int]:
        self.pidfd_polls += 1
        return self._poll("pending_pidfd", self.pidfd)

    @property
    def last_result(self) -> Optional[LaunchResult]:
        self.last_polls += 1
        return self._poll("last_result", self.last)


def _access(a: LauncherAdmissionAdapter, n: str) -> Any:
    val = getattr(a, n)
    return val() if n in ("snapshot", "take_snapshot") else val


class TestLauncherAdmissionAdapter(unittest.TestCase):
    def test_public_known_api_aliases_and_zero_private_protocol_attrs(self) -> None:
        adapter = LauncherAdmissionAdapter()
        self.assertEqual((adapter.known_child, adapter.known_pidfd, adapter.known_last_result), (None, None, None))
        self.assertIsInstance(adapter.known_snapshot, LauncherCustodySnapshot)
        self.assertEqual(
            (adapter.take_snapshot, adapter.child_handle, adapter.pidfd),
            (adapter.snapshot, adapter.pending_child, adapter.pending_pidfd),
        )
        self.assertIs(LauncherAdmissionCustodyAdapter, LauncherAdmissionAdapter)
        for no_attr in ("execute", "attempted", "is_attempted"):
            self.assertFalse(hasattr(adapter, no_attr))
        prod_source = inspect.getsource(LauncherAdmissionAdapter)
        for priv in ("._known", "._cache", "._known_helper"):
            self.assertNotIn(priv, prod_source)

    def test_active_getters_guarded_snapshot_and_reentrancy_single_poll(self) -> None:
        calls = 0
        adapter = LauncherAdmissionAdapter()

        def source_cb() -> Any:
            nonlocal calls
            calls += 1
            for getter in _ACTIVE_ATTRS:
                _access(adapter, getter)
            return None

        adapter.set_custody_source(source_cb)
        self.assertIsInstance(adapter.snapshot(), LauncherCustodySnapshot)
        self.assertEqual(calls, 1)

    def test_known_getters_zero_source_calls_with_actual_fixtures(self) -> None:
        child = _ChildTrap()
        src = _CountingSource(child=child, pidfd=99, last=LaunchResult(success=True))
        adapter = LauncherAdmissionAdapter(custody_source=src)
        self.assertEqual((adapter.known_child, adapter.known_pidfd, adapter.known_last_result), (None, None, None))
        self.assertIsInstance(adapter.known_snapshot, LauncherCustodySnapshot)
        self.assertEqual((src.child_polls, src.pidfd_polls, src.last_polls), (0, 0, 0))
        self.assertEqual((child.bool_calls, child.invocations), (0, 0))

    def test_all_active_getters_poll_once_kise_recovery_and_runtimeerror_absorbed(self) -> None:
        child, res = _ChildTrap(), LaunchResult(success=True, pidfd=42)
        for name in _ACTIVE_ATTRS:
            src = _CountingSource(child=child, pidfd=42, last=res)
            adapter = LauncherAdmissionAdapter(custody_source=src)
            _ = _access(adapter, name)
            self.assertEqual((src.child_polls, src.pidfd_polls, src.last_polls), (1, 1, 1))

            for i, fault in enumerate((KeyboardInterrupt("poll ki"), SystemExit(2)), 1):
                src.fault = fault
                with self.assertRaises(type(fault)) as ctx:
                    _access(adapter, name)
                self.assertIs(ctx.exception, fault)
                src.fault = None
                self.assertIsNotNone(_access(adapter, name))
                self.assertEqual((src.child_polls, src.pidfd_polls, src.last_polls), (2 * i + 1, 2 * i + 1, i + 1))

        err_src = _CountingSource(child=child, pidfd=42, last=res)
        adapter_err = LauncherAdmissionAdapter(custody_source=err_src)
        real_snap, fail = adapter_err._resolver.snapshot, True

        def snap_once() -> LauncherCustodySnapshot:
            nonlocal fail
            if fail:
                fail = False
                raise RuntimeError("escaping snapshot")
            return real_snap()

        adapter_err._resolver.snapshot = snap_once
        with self.assertRaises(RuntimeError):
            adapter_err.snapshot()
        self.assertEqual(adapter_err.snapshot().pending_pidfd, 42)
        self.assertEqual((err_src.child_polls, err_src.pidfd_polls, err_src.last_polls), (1, 1, 1))
        self.assertEqual((child.bool_calls, child.invocations), (0, 0))

    def test_current_resources_fallback_and_kise_propagation(self) -> None:
        child = _ChildTrap()
        last = LaunchResult(success=True, child_handle=child, pidfd=0)
        src = _CountingSource(child=child, pidfd=0, last=last)
        adapter = LauncherAdmissionAdapter(custody_source=src)

        def assert_cr(r: tuple[Any, Any, Any]) -> None:
            self.assertIs(r[0], child)
            self.assertEqual(r[1], 0)
            self.assertIs(r[2], last)

        assert_cr(adapter.current_resources())
        self.assertEqual((src.child_polls, src.pidfd_polls, src.last_polls), (1, 1, 1))

        calls, real_snap = 0, adapter.snapshot

        def snap_fault(f: BaseException) -> Any:
            nonlocal calls
            calls += 1
            if calls == 1:
                raise f
            return real_snap()

        for fault in (RuntimeError("cr fail"), KeyboardInterrupt("cr ki"), SystemExit(1)):
            calls = 0
            adapter.snapshot = lambda f=fault: snap_fault(f)
            if isinstance(fault, (KeyboardInterrupt, SystemExit)):
                with self.assertRaises(type(fault)) as ctx:
                    adapter.current_resources()
                self.assertIs(ctx.exception, fault)
            else:
                assert_cr(adapter.current_resources())
                self.assertEqual((src.child_polls, src.pidfd_polls, src.last_polls), (1, 1, 1))
            assert_cr(adapter.current_resources())

        self.assertEqual((child.bool_calls, child.invocations), (0, 0))

    def test_partial_custody_cache_coexistence_and_safe_failure_matrix(self) -> None:
        cases = (
            (False, ValueError("primary distinct")),
            (True, _CustomBase("primary custom")),
        )
        for transferred, primary_exc in cases:
            for sec_fault in (KeyboardInterrupt("sec ki"), SystemExit(3)):
                with self.subTest(transferred=transferred, fault=type(sec_fault).__name__):
                    old_child, pfd = object(), None if transferred else 10
                    src = _CountingSource(
                        child=old_child, pidfd=10,
                        last=LaunchResult(success=True, transferred=transferred, pidfd=pfd),
                    )
                    adapter = LauncherAdmissionAdapter(custody_source=src)

                    snap1 = adapter.snapshot()
                    self.assertEqual((src.cb_calls, src.child_polls, src.pidfd_polls, src.last_polls), (1, 1, 1, 1))

                    new_child = _ChildTrap()
                    src.child, src.pidfd = new_child, 0
                    src.fault, src.fault_attr = sec_fault, "last_result"

                    with self.assertRaises(type(sec_fault)) as ctx:
                        adapter.snapshot()
                    self.assertIs(ctx.exception, sec_fault)
                    self.assertEqual((src.cb_calls, src.child_polls, src.pidfd_polls, src.last_polls), (2, 2, 2, 2))

                    self.assertIs(adapter.known_child, new_child)
                    self.assertEqual(adapter.known_pidfd, 0)
                    self.assertIs(adapter.known_last_result, snap1.last_result)
                    self.assertEqual((src.cb_calls, src.child_polls, src.pidfd_polls, src.last_polls), (2, 2, 2, 2))

                    self.assertIsNot(primary_exc, sec_fault)
                    fail_res = adapter.safe_failure(primary_exc)
                    self.assertIsNotNone(fail_res)

                    self.assertEqual((src.cb_calls, src.child_polls, src.pidfd_polls, src.last_polls), (2, 2, 2, 2))
                    self.assertEqual((new_child.bool_calls, new_child.invocations), (0, 0))

                    self.assertIs(fail_res.child_handle, new_child)
                    expected = (0, True, False) if not transferred else (None, False, True)
                    self.assertEqual((fail_res.pidfd, fail_res.pending_cleanup, fail_res.transferred), expected)

    def test_safe_failure_known_transfer_not_degraded_and_precedence(self) -> None:
        child = _ChildTrap()
        adapter = LauncherAdmissionAdapter()
        adapter.record_result(LaunchResult(success=True, transferred=True, child_handle=child, pidfd=0))

        res = adapter.safe_failure(ValueError("Failure after transfer"))
        self.assertEqual((res.success, res.transferred, res.pending_cleanup, res.pidfd), (False, True, False, None))
        self.assertIs(res.child_handle, child)
        self.assertEqual((child.bool_calls, child.invocations), (0, 0))

        src = _CountingSource(
            child=child, pidfd=99,
            last=LaunchResult(success=True, transferred=True, child_handle=child, pidfd=99),
        )
        adapter2 = LauncherAdmissionAdapter(custody_source=src)
        adapter2.record_result(LaunchResult(success=True, transferred=False))
        adapter2.snapshot()
        res2 = adapter2.safe_failure(RuntimeError("Source authoritative"))
        self.assertIsNotNone(res2)
        self.assertEqual((res2.transferred, res2.pending_cleanup), (True, False))

    def test_freshest_source_authority_over_stale_local_fallback(self) -> None:
        child = _ChildTrap()
        src = _CountingSource(child=child, pidfd=12, last=LaunchResult(success=True, pidfd=12, child_handle=child))
        adapter = LauncherAdmissionAdapter(custody_source=src)
        adapter.record_result(LaunchResult(success=False, error="stale local"))
        self.assertIs(adapter.last_result, src.last_result)
        src.last = None
        adapter.record_result(LaunchResult(success=False, error="retained fallback"))
        self.assertEqual(adapter.last_result.error, "retained fallback")


if __name__ == "__main__":
    unittest.main()
