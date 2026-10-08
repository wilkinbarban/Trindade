"""Guarded supervisor runner with fail-closed cleanup and exception precedence (8d)."""

from __future__ import annotations

from typing import Any, Callable, Dict, Optional

from .controller import ProcessController
from .engine import SupervisorEngine
from .sigterm import DEFAULT_GRACE_SEC, safe_sigterm
from .types import LifecycleState, ProcessIdentity, TerminationClaimStatus

__all__ = ["run_guarded_supervisor", "run_supervisor"]


def run_guarded_supervisor(
    runner: Callable[[], None],
    target: ProcessIdentity,
    controller: ProcessController,
    state_sink: Dict[str, Any],
    cleanup_probe: Optional[Callable[[], Any]] = None,
    grace_seconds: float = DEFAULT_GRACE_SEC,
    poll_interval: float = 0.5,
    engine: Optional[SupervisorEngine] = None,
) -> LifecycleState:
    """Execute supervisor runner and guarantee fail-closed cleanup in finally block.

    Handles normal return, Exception, KeyboardInterrupt, and SystemExit.
    Preserves exact termination lifecycle state in state_sink.
    """
    exc_caught: Optional[BaseException] = None
    cleanup_exc: Optional[BaseException] = None
    cleanup_interrupt: Optional[BaseException] = None
    try:
        runner()
    except BaseException as e:
        exc_caught = e
        state_sink["body_exception"] = e
        state_sink["stop_reason"] = f"Supervisor terminated by: {e}"
    finally:
        reason = state_sink.get("stop_reason", "Guarded supervisor normal exit")
        try:
            if cleanup_probe is not None:
                res = cleanup_probe()
                if res is False:
                    state_sink["probe_cleanup_failed"] = True
                    state_sink["probe_cleanup_error"] = "cleanup_probe returned False (cleanup pending or failed)"
                elif res is None:
                    adapter_obj = getattr(cleanup_probe, "__self__", None)
                    if getattr(adapter_obj, "has_pending_cleanup", False):
                        state_sink["probe_cleanup_failed"] = True
                        state_sink["probe_cleanup_error"] = (
                            getattr(adapter_obj, "cleanup_error", None)
                            or f"Pending un-reaped probe handles ({len(getattr(adapter_obj, '_pending_reap', []))})"
                        )
                    elif getattr(adapter_obj, "has_probe_cleanup_failure", False):
                        state_sink["probe_cleanup_failed"] = True
                        state_sink["probe_cleanup_error"] = (
                            getattr(adapter_obj, "probe_cleanup_error", None) or "Pending probe cleanup failure"
                        )
        except BaseException as ce:
            cleanup_exc = ce
            if isinstance(ce, (KeyboardInterrupt, SystemExit)) and cleanup_interrupt is None:
                cleanup_interrupt = ce
            state_sink["probe_cleanup_failed"] = True
            state_sink["probe_cleanup_error"] = f"probe_cleanup exception: {type(ce).__name__}{': ' + str(ce) if str(ce) else ''}"
            state_sink["secondary_cleanup_cause"] = type(ce).__name__
        finally:
            term_state, term_msg = LifecycleState.ERROR, ""
            try:
                if engine is None:
                    term_state, term_msg = safe_sigterm(
                        target, controller, reason=reason, grace_seconds=grace_seconds, poll_interval=poll_interval
                    )
                elif not isinstance(engine, SupervisorEngine):
                    term_state, term_msg = LifecycleState.ERROR, "Invalid engine instance"
                else:
                    claim_st = SupervisorEngine.claim(engine, target, controller)
                    if claim_st == TerminationClaimStatus.CLAIMED:
                        rec = getattr(engine, "attempt_record", None)
                        out = getattr(rec, "outcome", None) if rec else None
                        if out and out.completed is True and out.state == LifecycleState.STOPPED and out.primary_error is None:
                            term_state, term_msg = out.state, (out.detail or "Engine claimed termination completed")
                        else:
                            term_state, term_msg = LifecycleState.ERROR, f"Engine outcome invalid or failed: {out}"
                    elif claim_st == TerminationClaimStatus.NOT_ATTEMPTED:
                        term_state, term_msg = safe_sigterm(
                            target, controller, reason=reason, grace_seconds=grace_seconds, poll_interval=poll_interval
                        )
                    else:
                        term_state, term_msg = LifecycleState.ERROR, f"Engine claim status: {claim_st}"
            except BaseException as te:
                if isinstance(te, (KeyboardInterrupt, SystemExit)) and cleanup_interrupt is None:
                    cleanup_interrupt = te
                term_state = LifecycleState.ERROR
                term_msg = f"Target termination exception: {te}"
                state_sink["target_termination_error"] = str(te)
            finally:
                if hasattr(controller, "cleanup_bound_pidfd"):
                    try:
                        c_err = controller.cleanup_bound_pidfd()
                        if c_err is not None and (c_err is False or isinstance(c_err, BaseException)):
                            state_sink["controller_cleanup_failed"] = True
                            state_sink["controller_cleanup_error"] = str(c_err)
                    except BaseException as cbe:
                        state_sink["controller_cleanup_failed"] = True
                        state_sink["controller_cleanup_error"] = str(cbe)
                        if isinstance(cbe, (KeyboardInterrupt, SystemExit)) and cleanup_interrupt is None:
                            cleanup_interrupt = cbe

            state_sink["primary_state"] = term_state
            state_sink["termination_state"] = term_state
            state_sink["state"] = term_state
            state_sink["termination_detail"] = term_msg

            if state_sink.get("probe_cleanup_failed") or state_sink.get("controller_cleanup_failed"):
                state_sink["state"] = LifecycleState.ERROR

            if exc_caught is not None and term_state == LifecycleState.STOPPED:
                state_sink["state"] = LifecycleState.ERROR

        if isinstance(exc_caught, (KeyboardInterrupt, SystemExit)):
            raise exc_caught
        if cleanup_interrupt is not None:
            raise cleanup_interrupt

    return state_sink.get("state", LifecycleState.ERROR)


run_supervisor = run_guarded_supervisor
