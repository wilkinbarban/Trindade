"""Pidfd binding and process lifecycle operation helper.

Provides PidfdBindingHelper managing anchored Linux pidfd lifecycle, exclusive
binding states, and descriptor cleanup.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
import os
from typing import Any, Optional

__all__ = ["BindingState", "PidfdBinding", "PidfdBindingHelper"]


class BindingState(Enum):
    NEVER_BOUND = "NEVER_BOUND"
    BOUND = "BOUND"
    CONSUMED = "CONSUMED"
    IDENTITY_MISMATCH = "IDENTITY_MISMATCH"
    CLOSED = "CLOSED"
    ERROR = "ERROR"


@dataclass(frozen=True)
class PidfdBinding:
    pid: int
    start_ticks: int
    fd: int


class PidfdBindingHelper:
    """Helper managing exclusive pidfd binding lifecycle and descriptor closing."""

    _bound_pidfd: Optional[PidfdBinding] = None
    _binding_state: BindingState = BindingState.NEVER_BOUND
    _cleanup_error: Optional[BaseException] = None

    @property
    def binding_state(self) -> BindingState:
        return self._binding_state

    def close_fd(self, fd: int) -> None:
        """Close a file descriptor, invalidating any matching bound fd BEFORE os.close."""
        binding = getattr(self, "_bound_pidfd", None)
        if binding is not None and binding.fd == fd:
            self._bound_pidfd = None
            self._binding_state = BindingState.CLOSED
        try:
            os.close(fd)
        except BaseException as e:
            self._binding_state = BindingState.ERROR
            self._cleanup_error = e
            raise

    def bind_anchored_pidfd(self, pid: int, start_ticks: int, fd: int) -> bool:
        """Exclusive, one-time binding tied to exact PID and start_ticks. Rejects duplicates/overwrites."""
        if not (isinstance(pid, int) and type(pid) is not bool and pid > 0):
            return False
        if not (isinstance(start_ticks, int) and type(start_ticks) is not bool and start_ticks > 0):
            return False
        if not (isinstance(fd, int) and type(fd) is not bool and fd >= 0):
            return False
        if getattr(self, "_bound_pidfd", None) is not None:
            return False
        self._bound_pidfd = PidfdBinding(pid=pid, start_ticks=start_ticks, fd=fd)
        self._binding_state = BindingState.BOUND
        return True

    def consume_bound_pidfd(self, target: Any) -> Optional[int]:
        """Extract bound pidfd using current target identity and start_ticks. Clears binding before handoff."""
        binding = getattr(self, "_bound_pidfd", None)
        if binding is None:
            return None
        if target.pid != binding.pid or target.start_ticks != binding.start_ticks:
            self._binding_state = BindingState.IDENTITY_MISMATCH
            return None
        self._bound_pidfd = None
        self._binding_state = BindingState.CONSUMED
        return binding.fd

    def cleanup_bound_pidfd(self) -> Optional[Exception]:
        """Idempotent, non-signaling close of any unconsumed bound pidfd."""
        if getattr(self, "_cleanup_error", None) is not None:
            return self._cleanup_error
        binding = getattr(self, "_bound_pidfd", None)
        if binding is None:
            return None
        fd = binding.fd
        self._bound_pidfd = None
        self._binding_state = BindingState.CLOSED
        try:
            os.close(fd)
            return None
        except BaseException as e:
            self._binding_state = BindingState.ERROR
            self._cleanup_error = e
            if isinstance(e, (KeyboardInterrupt, SystemExit)):
                raise
            return e

    def adopt_pidfd(self, pid: int, pidfd: int) -> None:
        """Backward-compatible adoption wrapping exclusive binding."""
        self.bind_anchored_pidfd(pid, getattr(self, "get_start_ticks", lambda p: 1)(pid) or 1, pidfd)
