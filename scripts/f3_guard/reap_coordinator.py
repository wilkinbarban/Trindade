"""Handle cleanup, reaping, and process termination tracking for F3 emulator guard.

This module extracts the process cleanup, termination, and non-blocking reap
coordination logic from AdbProbeAdapter.
"""

from __future__ import annotations

from typing import List, Optional

from scripts.f3_guard.probe_handle import ProbeCommandHandle

__all__ = ["ProbeReapCoordinator"]


class ProbeReapCoordinator:
    """Coordinates handle termination, non-blocking reaping, and cleanup error tracking."""

    def __init__(self) -> None:
        self._pending_reap: List[ProbeCommandHandle] = []
        self.cleanup_error: Optional[str] = None
        self._cleanup_failed: bool = False

    @property
    def pending_reap(self) -> List[ProbeCommandHandle]:
        """List of pending probe handles awaiting exit or reaping."""
        return self._pending_reap

    @property
    def cleanup_failed(self) -> bool:
        """True if any cleanup attempt failed or remains pending."""
        return self._cleanup_failed

    @cleanup_failed.setter
    def cleanup_failed(self, value: bool) -> None:
        self._cleanup_failed = bool(value)

    def mark_cleanup_failed(self, error: Optional[str] = None) -> None:
        """Mark cleanup as failed and optionally record the error message."""
        self._cleanup_failed = True
        if error is not None:
            self.cleanup_error = str(error)

    def cancel_handle(self, handle: ProbeCommandHandle) -> None:
        """Terminate and clean up a probe handle, capturing failure state."""
        try:
            handle.terminate()
        except Exception:
            pass
        self.cleanup_handle(handle)

    def cleanup_handle(self, handle: ProbeCommandHandle) -> bool:
        """Attempt non-blocking cleanup of a finished or cancelled handle without signaling.

        Returns True if cleanup succeeded without errors; False if failed or pending.
        """
        success = True
        try:
            if not handle.cleanup():
                self._pending_reap.append(handle)
                self._cleanup_failed = True
                success = False
            if getattr(handle, "_close_error", None):
                self.cleanup_error = handle._close_error
                self._cleanup_failed = True
                success = False
        except Exception as e:
            self.cleanup_error = f"Handle cleanup exception: {e}"
            self._pending_reap.append(handle)
            self._cleanup_failed = True
            success = False
        return success

    def reap_pending(self) -> int:
        """Attempt non-blocking reap of pending handles without busy-waiting."""
        remaining: List[ProbeCommandHandle] = []
        for h in self._pending_reap:
            try:
                if not h.cleanup():
                    remaining.append(h)
                if getattr(h, "_close_error", None):
                    self.cleanup_error = h._close_error
            except Exception as e:
                self.cleanup_error = f"Pending reap exception: {e}"
                remaining.append(h)
        self._pending_reap = remaining
        if not self._pending_reap and self.cleanup_error is None:
            self._cleanup_failed = False
        return len(self._pending_reap)

    def cancel(
        self,
        current_handle: Optional[ProbeCommandHandle] = None,
        start_error: Optional[str] = None,
    ) -> bool:
        """Cancel current handle if supplied and reap pending handles.

        Returns True if fully clean (no pending reap, errors, or failed state).
        Caller supplies optional start_error to reflect caller probe context.
        """
        if current_handle is not None:
            self.cancel_handle(current_handle)
        self.reap_pending()
        return not self.has_pending_cleanup(
            current_handle=current_handle, start_error=start_error
        )

    def has_active_child(
        self, current_handle: Optional[ProbeCommandHandle] = None
    ) -> bool:
        """Check whether current handle or any pending handle is still executing."""
        if current_handle is not None:
            if getattr(current_handle, "poll", lambda: 0)() is None:
                return True
        for h in self._pending_reap:
            if getattr(h, "poll", lambda: 0)() is None:
                return True
        return False

    def has_pending_cleanup(
        self,
        current_handle: Optional[ProbeCommandHandle] = None,
        start_error: Optional[str] = None,
    ) -> bool:
        """Check whether any handle cleanup, error, or pending reap exists."""
        has_handle_err = False
        if current_handle is not None:
            has_handle_err = getattr(current_handle, "_construction_error", None) is not None
        return (
            len(self._pending_reap) > 0
            or self.cleanup_error is not None
            or self._cleanup_failed
            or has_handle_err
            or start_error is not None
        )

    def reset_probe_state(self) -> None:
        """Reset transient probe failure state safely without discarding unreaped handles."""
        if not self._pending_reap and self.cleanup_error is None:
            self._cleanup_failed = False
