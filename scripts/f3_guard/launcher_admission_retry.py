"""Fail-closed pure retry policy for emulator launcher admission."""

from __future__ import annotations

import inspect
from typing import Any, Optional

from scripts.f3_guard.launcher_preflight import LaunchResult

__all__ = ["LaunchResult", "compute_retry_result"]

_CANONICAL_LAUNCH_RESULT = LaunchResult

_ERR_XFER = (
    "Single-use launch attempt already consumed; process ownership transferred to controller"
)
_ERR_PEND = (
    "Single-use launch attempt already consumed; refusing respawn (pending child retained)"
)
_ERR_RESP = "Single-use launch attempt already consumed; refusing respawn"


def _safe_result(
    success: bool,
    error: str,
    child: Optional[Any] = None,
    pidfd: Optional[int] = None,
    transferred: bool = False,
    pending_cleanup: bool = False,
) -> LaunchResult:
    kw = {
        "success": success,
        "identity": None,
        "pidfd": pidfd,
        "child_handle": child,
        "error": error,
        "is_direct_child": False,
        "pending_cleanup": pending_cleanup,
        "transferred": transferred,
    }
    try:
        return LaunchResult(**kw)
    except BaseException as primary_exc:
        try:
            return _CANONICAL_LAUNCH_RESULT(**kw)
        except BaseException:
            raise primary_exc


def _read_attr(obj: Any, name: str, as_bool: bool = False) -> tuple[Any, bool]:
    default = False if as_bool else None
    try:
        val = inspect.getattr_static(obj, name)
    except AttributeError:
        if hasattr(type(obj), "__getattr__"):
            return default, True
        return default, False
    except BaseException:
        return default, True

    if hasattr(type(val), "__get__"):
        if callable(val):
            return default, True
        try:
            val = getattr(obj, name)
        except BaseException:
            return default, True

    if as_bool:
        if type(val) is not bool:
            return False, True
        return val, False
    return val, False


def compute_retry_result(
    child: Optional[Any] = None,
    pidfd: Optional[int] = None,
    last_result: Optional[Any] = None,
) -> LaunchResult:
    """Compute fail-closed pure denial result on retry attempt.

    Precedence order:
      1. transferred -> controller owns process; child retained, pidfd cleared,
         pending_cleanup=False, transferred=True.
      2. pending_cleanup -> pending child or pidfd retained or uncertain custody;
         pending_cleanup=True, transferred=False.
      3. simple -> no resources or custody retained; pending_cleanup=False,
         transferred=False.

    Typed contract and fault policy:
      - Parameters child and pidfd represent explicit API authority; fallback to
        last_result child_handle occurs only when child is None, while custom result
        pidfd is unsupported (not a universal custody claim).
      - Flag attributes require exact boolean types (type(v) is bool); callables,
        methods, ints, strings, objects, and dynamic coercions fail closed.
      - Dynamic __getattr__-only flags/child are unsupported and fail closed.
      - Plain missing attributes on static objects default safely.
      - Static properties producing strict bool can remain if getter fault guarded.
      - Transferred authority short-circuits pending_cleanup inspection.
      - Explicit known child skips fallback child_handle inspection.
      - Any relevant descriptor fault triggers fail-closed uncertain custody:
        pending_cleanup is conservatively forced to True, transfer is never claimed,
        and explicit resources are preserved without losing custody.
      - Double factory failure fails closed by re-raising the primary exception
        without masking it with secondary failures.
    """
    last_transferred = False
    last_pending = False
    last_child = None
    uncertain_custody = False

    if last_result is not None:
        last_transferred, xfer_fault = _read_attr(last_result, "transferred", as_bool=True)
        if xfer_fault:
            uncertain_custody = True
        else:
            if not last_transferred:
                last_pending, pend_fault = _read_attr(last_result, "pending_cleanup", as_bool=True)
                if pend_fault:
                    uncertain_custody = True
            if child is None:
                last_child, child_fault = _read_attr(last_result, "child_handle")
                if child_fault and not last_transferred:
                    uncertain_custody = True

    resolved_child = child if child is not None else last_child

    if uncertain_custody or (
        not last_transferred
        and (resolved_child is not None or pidfd is not None or last_pending)
    ):
        return _safe_result(False, _ERR_PEND, resolved_child, pidfd, False, True)

    if last_transferred:
        return _safe_result(False, _ERR_XFER, resolved_child, None, True, False)

    return _safe_result(False, _ERR_RESP, None, None, False, False)
