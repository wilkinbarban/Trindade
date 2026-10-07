"""Non-blocking process handle transport contract for F3 guard."""

from __future__ import annotations

from typing import Optional, Tuple


class ProbeCommandHandle:
    """Documented transport contract for non-blocking probe execution.

    Invariants:
    1. poll() must be strictly non-blocking: returns exit code (int) if finished,
       or None if running. Never blocks on descendant processes or pipe EOF.
    2. read_available(max_bytes) returns (stdout, stderr) up to max_bytes without blocking.
    3. terminate() sends SIGTERM exclusively to the owned exact handle. Never SIGKILL, no PID fallback.
    4. cleanup() closes owned resources and attempts non-blocking reap. Returns True if reaped,
       False if pending. Never performs busy blocking waits.
    """

    def poll(self) -> Optional[int]:
        raise NotImplementedError

    def read_available(self, max_bytes: int) -> Tuple[str, str]:
        raise NotImplementedError

    def terminate(self) -> None:
        raise NotImplementedError

    def cleanup(self) -> bool:
        raise NotImplementedError
