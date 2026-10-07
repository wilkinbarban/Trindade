"""Hermetic unit tests for scripts.f3_guard.reap_coordinator module."""

from __future__ import annotations

import os
import signal
import sys
import unittest

_REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

from scripts.f3_guard.probe_handle import ProbeCommandHandle
from scripts.f3_guard.reap_coordinator import ProbeReapCoordinator


class FakeProbeHandle(ProbeCommandHandle):
    """Test fake for ProbeCommandHandle testing cleanup and termination semantics."""

    def __init__(
        self,
        exit_code: int | None = None,
        stdout: str = "",
        stderr: str = "",
        reapable: bool = True,
        raise_on_terminate: Exception | None = None,
        raise_on_cleanup: Exception | None = None,
        close_error: str | None = None,
    ) -> None:
        self._exit_code = exit_code
        self._stdout = stdout
        self._stderr = stderr
        self._reapable = reapable
        self._raise_on_terminate = raise_on_terminate
        self._raise_on_cleanup = raise_on_cleanup
        self._close_error = close_error

        self.terminated = False
        self.signal_received: int | None = None
        self.cleanup_count = 0
        self.event_order: list[str] = []

    def poll(self) -> int | None:
        return self._exit_code

    def read_available(self, max_bytes: int) -> tuple[str, str]:
        return self._stdout[:max_bytes], self._stderr[:max_bytes]

    def terminate(self) -> None:
        self.event_order.append("terminate")
        self.terminated = True
        self.signal_received = signal.SIGTERM
        if self._raise_on_terminate is not None:
            raise self._raise_on_terminate

    def cleanup(self) -> bool:
        self.event_order.append("cleanup")
        self.cleanup_count += 1
        if self._raise_on_cleanup is not None:
            raise self._raise_on_cleanup
        return self._reapable


class TestProbeReapCoordinator(unittest.TestCase):
    """Hermetic unit tests for ProbeReapCoordinator."""

    def test_initial_state(self) -> None:
        coord = ProbeReapCoordinator()
        self.assertEqual(coord.pending_reap, [])
        self.assertIsNone(coord.cleanup_error)
        self.assertFalse(coord.cleanup_failed)
        self.assertFalse(coord.has_pending_cleanup())
        self.assertFalse(coord.has_active_child())

    def test_cancel_handle_ordering_and_terminate_exception_isolation(self) -> None:
        coord = ProbeReapCoordinator()
        # Normal termination and cleanup ordering
        h1 = FakeProbeHandle(exit_code=0, reapable=True)
        coord.cancel_handle(h1)
        self.assertEqual(h1.event_order, ["terminate", "cleanup"])
        self.assertTrue(h1.terminated)
        self.assertEqual(h1.signal_received, signal.SIGTERM)
        self.assertFalse(coord.has_pending_cleanup())

        # Exception during terminate() is isolated; cleanup() still executes
        h2 = FakeProbeHandle(
            exit_code=None,
            reapable=True,
            raise_on_terminate=RuntimeError("terminate failed"),
        )
        coord.cancel_handle(h2)
        self.assertEqual(h2.event_order, ["terminate", "cleanup"])
        self.assertEqual(h2.cleanup_count, 1)

    def test_cleanup_handle_without_signal(self) -> None:
        coord = ProbeReapCoordinator()
        h = FakeProbeHandle(exit_code=0, reapable=True)
        ok = coord.cleanup_handle(h)
        self.assertTrue(ok)
        self.assertEqual(h.event_order, ["cleanup"])
        self.assertFalse(h.terminated)
        self.assertIsNone(h.signal_received)
        self.assertEqual(coord.pending_reap, [])

    def test_cleanup_handle_reap_false_and_close_error(self) -> None:
        coord = ProbeReapCoordinator()
        h_unreaped = FakeProbeHandle(exit_code=None, reapable=False)
        ok1 = coord.cleanup_handle(h_unreaped)
        self.assertFalse(ok1)
        self.assertIn(h_unreaped, coord.pending_reap)
        self.assertTrue(coord.cleanup_failed)
        self.assertTrue(coord.has_pending_cleanup())

        coord2 = ProbeReapCoordinator()
        h_close_err = FakeProbeHandle(exit_code=0, reapable=True, close_error="EBADF")
        ok2 = coord2.cleanup_handle(h_close_err)
        self.assertFalse(ok2)
        self.assertEqual(coord2.cleanup_error, "EBADF")
        self.assertTrue(coord2.cleanup_failed)
        self.assertTrue(coord2.has_pending_cleanup())

    def test_cleanup_exception_captured_and_queued(self) -> None:
        # Maps original monolith test_cleanup_exception_visible
        coord = ProbeReapCoordinator()
        h_broken = FakeProbeHandle(
            exit_code=0,
            raise_on_cleanup=OSError("Kernel FD close failed"),
        )
        ok = coord.cleanup_handle(h_broken)
        self.assertFalse(ok)
        self.assertTrue(coord.has_pending_cleanup())
        self.assertTrue(coord.cleanup_failed)
        self.assertTrue(str(coord.cleanup_error).startswith("Handle cleanup exception:"))
        self.assertIn("Kernel FD close failed", str(coord.cleanup_error))
        self.assertIn(h_broken, coord.pending_reap)

    def test_reap_pending_idempotence_and_recovery(self) -> None:
        # Maps original monolith test_honest_pending_reap_tracked
        coord = ProbeReapCoordinator()
        h = FakeProbeHandle(exit_code=None, reapable=False)
        coord.cancel_handle(h)
        self.assertTrue(coord.has_pending_cleanup())
        self.assertEqual(len(coord.pending_reap), 1)

        # First reap: still unreapable
        remaining = coord.reap_pending()
        self.assertEqual(remaining, 1)
        self.assertTrue(coord.has_pending_cleanup())

        # Now handle becomes reapable
        h._reapable = True
        remaining = coord.reap_pending()
        self.assertEqual(remaining, 0)
        self.assertFalse(coord.has_pending_cleanup())
        self.assertFalse(coord.cleanup_failed)

    def test_reap_pending_exception_retains_handle(self) -> None:
        coord = ProbeReapCoordinator()
        h = FakeProbeHandle(exit_code=None, reapable=False)
        coord.cancel_handle(h)
        h._raise_on_cleanup = OSError("reap failed")

        remaining = coord.reap_pending()
        self.assertEqual(remaining, 1)
        self.assertTrue(str(coord.cleanup_error).startswith("Pending reap exception:"))
        self.assertIn("reap failed", str(coord.cleanup_error))
        self.assertIn(h, coord.pending_reap)

    def test_reap_pending_discovers_close_error_and_retains_poison(self) -> None:
        coord = ProbeReapCoordinator()
        h = FakeProbeHandle(exit_code=None, reapable=False)
        coord.cancel_handle(h)
        self.assertIsNone(coord.cleanup_error)

        # Handle becomes reapable but reports _close_error during reap
        h._reapable = True
        h._close_error = "close failed on fd 10: EBADF"
        remaining = coord.reap_pending()
        self.assertEqual(remaining, 0)
        self.assertEqual(coord.cleanup_error, "close failed on fd 10: EBADF")
        self.assertTrue(coord.cleanup_failed)
        self.assertTrue(coord.has_pending_cleanup())

    def test_poison_persists_after_exception_recovery(self) -> None:
        coord = ProbeReapCoordinator()
        h = FakeProbeHandle(exit_code=None, reapable=False)
        coord.cancel_handle(h)
        # Handle is reapable before the induced reap failure; the exception is the only failure cause.
        h._reapable = True
        h._raise_on_cleanup = OSError("temporary reap failure")
        coord.reap_pending()
        self.assertTrue(str(coord.cleanup_error).startswith("Pending reap exception:"))
        self.assertTrue(coord.cleanup_failed)

        # Handle recovers from exception and reaps cleanly
        h._raise_on_cleanup = None
        remaining = coord.reap_pending()
        self.assertEqual(remaining, 0)
        # Poison persists because cleanup_error is not cleared
        self.assertTrue(coord.cleanup_failed)
        self.assertTrue(coord.has_pending_cleanup())

    def test_multiple_pending_handles_isolation(self) -> None:
        coord = ProbeReapCoordinator()
        h1 = FakeProbeHandle(exit_code=None, reapable=False)
        h2 = FakeProbeHandle(exit_code=None, reapable=False)
        coord.cancel_handle(h1)
        coord.cancel_handle(h2)
        self.assertEqual(len(coord.pending_reap), 2)

        # Only h2 becomes reapable; h1 remains stuck
        h2._reapable = True
        remaining = coord.reap_pending()
        self.assertEqual(remaining, 1)
        self.assertIn(h1, coord.pending_reap)
        self.assertNotIn(h2, coord.pending_reap)

    def test_cancel_convenience_method(self) -> None:
        coord = ProbeReapCoordinator()
        h = FakeProbeHandle(exit_code=None, reapable=True)
        clean = coord.cancel(current_handle=h)
        self.assertTrue(clean)
        self.assertTrue(h.terminated)
        self.assertFalse(coord.has_pending_cleanup())

        h_stuck = FakeProbeHandle(exit_code=None, reapable=False)
        clean2 = coord.cancel(current_handle=h_stuck)
        self.assertFalse(clean2)
        self.assertTrue(h_stuck.terminated)
        self.assertTrue(coord.has_pending_cleanup())

        # Cancel repeat after recovery succeeds
        h_stuck._reapable = True
        self.assertTrue(coord.cancel())
        self.assertFalse(coord.has_pending_cleanup())

        # Cancel with contextual start_error must return False
        h_clean2 = FakeProbeHandle(exit_code=0, reapable=True)
        clean3 = coord.cancel(current_handle=h_clean2, start_error="transport failed")
        self.assertFalse(clean3)

    def test_has_active_child_detection(self) -> None:
        coord = ProbeReapCoordinator()
        h_active = FakeProbeHandle(exit_code=None, reapable=False)
        h_done = FakeProbeHandle(exit_code=0)

        # Active current handle
        self.assertTrue(coord.has_active_child(current_handle=h_active))
        self.assertFalse(coord.has_active_child(current_handle=h_done))

        # Active pending handle
        coord.cancel_handle(h_active)
        self.assertTrue(coord.has_active_child())

        # Exited pending handle
        h_active._exit_code = 0
        self.assertFalse(coord.has_active_child())

    def test_has_pending_cleanup_surface_contract(self) -> None:
        # Maps original monolith test_repro_defect1 construction error check
        coord = ProbeReapCoordinator()
        h_normal = FakeProbeHandle(exit_code=0)
        h_constr = FakeProbeHandle(exit_code=None)
        h_constr._construction_error = "pidfd open failed"

        self.assertFalse(coord.has_pending_cleanup(current_handle=h_normal))
        self.assertTrue(coord.has_pending_cleanup(current_handle=h_constr))
        self.assertTrue(coord.has_pending_cleanup(start_error="spawn failed"))
        self.assertFalse(coord.cancel(current_handle=h_constr))

    def test_reset_probe_state_preserves_queue_and_poison(self) -> None:
        coord = ProbeReapCoordinator()
        coord.mark_cleanup_failed("initial error")
        self.assertTrue(coord.cleanup_failed)

        # With active error, reset preserves cleanup_failed
        coord.reset_probe_state()
        self.assertTrue(coord.cleanup_failed)

        # With unreaped handle in queue, reset preserves queue and poison
        h = FakeProbeHandle(exit_code=None, reapable=False)
        coord.cancel_handle(h)
        coord.cleanup_error = None
        coord.reset_probe_state()
        self.assertEqual(len(coord.pending_reap), 1)
        self.assertTrue(coord.cleanup_failed)

        # When queue is empty and cleanup_error is None, reset clears cleanup_failed
        coord._pending_reap.clear()
        coord.reset_probe_state()
        self.assertFalse(coord.cleanup_failed)


if __name__ == "__main__":
    unittest.main()
