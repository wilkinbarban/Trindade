"""Fail-closed single-use admission helper for emulator launcher."""

from __future__ import annotations

from typing import Any, Callable, Optional

from scripts.f3_guard.launcher_admission_adapter import (
    LauncherAdmissionAdapter,
)
from scripts.f3_guard.launcher_admission_failure import (
    failure_result, safe_result,
)
from scripts.f3_guard.launcher_admission_retry import compute_retry_result
from scripts.f3_guard.launcher_preflight import LaunchResult

__all__ = ["LauncherAdmissionHelper"]


class LauncherAdmissionHelper(LauncherAdmissionAdapter):
    """Bounded single-use launcher admission state and execution."""

    safe_result = staticmethod(safe_result)

    def __init__(
        self, custody_source: Optional[Any] = None,
        callback: Optional[Callable[[], LaunchResult]] = None,
    ) -> None:
        super().__init__(custody_source=custody_source)
        self._callback = callback
        self._attempted: bool = False

    @property
    def attempted(self) -> bool:
        return self._attempted

    is_attempted = attempted

    def _canonical_failure(self, error: str) -> LaunchResult:
        c, f, last = self.current_resources()
        return self.record_result(
            failure_result(error, child=c, pidfd=f, last=last)
        )

    def execute(
        self, callback: Optional[Callable[[], LaunchResult]] = None
    ) -> LaunchResult:
        if self._attempted or self._polling:
            c, f, last = self.current_resources()
            return compute_retry_result(child=c, pidfd=f, last_result=last)
        self._attempted = True

        cb = callback if callback is not None else self._callback
        if cb is None or not callable(cb):
            return self._canonical_failure(
                "No admission callback provided for execution"
            )

        try:
            res = cb()
            if res is None:
                return self._canonical_failure(
                    "Admission callback returned None"
                )
            return self.record_result(res)
        except (KeyboardInterrupt, SystemExit) as ex:
            try:
                if (res := self.safe_failure(ex)) is not None:
                    self.record_result(res)
            except BaseException:
                pass
            raise
        except BaseException as ex:
            res = None
            try:
                res = self._resolver.resolve_exception_result(ex)
            except BaseException:
                pass
            fallback = self.safe_failure(ex)
            auth = self.known_last_result
            last = auth if auth is not None else self._last_result
            if getattr(last, "transferred", None) is True or res is None:
                res = fallback
            if res is not None:
                try:
                    return self.record_result(res)
                except BaseException:
                    pass
            raise
