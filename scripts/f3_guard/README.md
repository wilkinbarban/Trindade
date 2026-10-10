# F3 guard: compose a hermetic supervisor

This package provides a modular, callable supervision runtime. It is **not an
operational emulator launcher or a CLI-connected guard**. Hermetic operation
comes from the injected test dependencies, not from safe-by-default runtime
settings. Read the integration fixtures before composing a run.

## Quick path

From the repository root, the hermetic test entry points are:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest scripts.f3_guard.tests.test_runtime
PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest discover -s scripts/f3_guard/tests -p 'test_*.py'
```

W2 observed 7 focused / 539 regression tests passing. These are historical W2
results, **not tests rerun for W3 documentation**. Start with
[test_runtime.py](tests/test_runtime.py) for the composition recipe and its
boundary traps; no live-host example is provided here.

The existing [CLI](cli.py) supports only offline checking/version (plus help):

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -B -m scripts.f3_guard.cli --check-offline
PYTHONDONTWRITEBYTECODE=1 python3 -B -m scripts.f3_guard.cli --version
```

The offline check prints contract constants. It does not invoke `run_guard`,
launch an emulator, contact ADB, or validate a real supervision run. See
[test_cli.py](tests/test_cli.py). No runtime flags or new entry contract exist.

## Callable API

[Runtime source](runtime.py) exports this signature (declaration, not a recipe):

```python
run_guard(
    engine: SupervisorEngine,
    target: ProcessIdentity,
    controller: ProcessController,
    state_sink: Dict[str, Any],
    *,
    cleanup_probe: Optional[Callable[[], Any]] = None,
    grace_seconds: float = DEFAULT_GRACE_SEC,
    termination_poll_interval: float = 0.5,
    pacing_interval: Optional[float] = None,
    sleeper: Optional[Callable[[float], None]] = None,
) -> LifecycleState
```

`DEFAULT_GRACE_SEC` is 20.0 seconds in [sigterm.py](sigterm.py).
The caller supplies the engine, target, controller and mutable sink; there is
no runtime factory or implicit launcher. Use the engine's target/controller
objects for coherent termination claims; the claim tracker checks identity.

| Input | Meaning |
|-------|---------|
| `pacing_interval` | Scheduler grid spacing; `None` uses the engine's `_poll_interval` (fallback 0.5). |
| `termination_poll_interval` | Runner fallback termination polling cadence, not grid pacing. |
| `grace_seconds` | Runner fallback termination grace; does not reconfigure the engine. |
| `cleanup_probe` | Optional runner-finally cleanup hook; separate from engine probe teardown. |
| `sleeper` | Scheduler sleep callable; `None` selects real `time.sleep`. |

The [scheduler](scheduler.py) receives the exact engine `_clock` object. A
missing/unusable clock raises `ValueError`, with no real-clock fallback in
`run_guard`. A conflicting clock or non-finite/non-positive grid interval
raises `ValueError`; a non-callable sleeper raises `TypeError`. Construction
failures occur before the guarded runner and do not populate the sink.

Engine termination uses the engine's own `grace_sec` and `poll_interval`.
Changing runtime pacing or fallback polling does not change those settings.

## Return, diagnostics and exceptions

The [guarded runner](runner.py) ignores the body return and returns the final
`state_sink["state"]` as a `LifecycleState` (fallback `ERROR`). It writes
`primary_state`, `termination_state`, `state`, and `termination_detail`.
Cleanup failures can make `state` ERROR while the termination state remains
STOPPED. The sink is an in-memory diagnostic dictionary, not a serialized log
or an exit-code mapping; callers own its lifecycle.

A caught body exception is retained as `body_exception` with `stop_reason`.
Ordinary exceptions become ERROR rather than being re-raised by the runner.
Body `KeyboardInterrupt`/`SystemExit` are re-raised by identity after cleanup,
ahead of cleanup interrupts; absent a body interrupt, a cleanup interrupt
can propagate. Probe/controller failure diagnostics and secondary causes
remain in the sink. See [test_runner.py](tests/test_runner.py) for precedence.

## Composition, custody and cleanup

1. Caller composes [SupervisorEngine](engine.py) and its probe dependencies.
2. `run_guard` constructs the shared-clock scheduler and passes `scheduler.run`
   to the guarded runner with the same engine and sink.
3. During engine termination, [EngineTerminationHelper](engine_termination.py)
   records an attempt, tears down probes, invokes `safe_sigterm`, cleans the
   controller's binding, and seals the outcome.
4. Runner finally executes its optional probe hook, then claims the engine
   attempt. A completed, error-free STOPPED outcome is reused; NOT_ATTEMPTED
   permits fallback `safe_sigterm`; invalid/failed claims yield ERROR.
5. Runner finally also invokes controller binding cleanup.

Thus engine and runner can each call `cleanup_bound_pidfd()`; two calls are
not two closes. [PidfdBindingHelper](pidfd_binding.py) clears binding before
closing an unconsumed fd and makes repeated cleanup idempotent. Helper tests
[test_pidfd_binding.py](tests/test_pidfd_binding.py) use a mocked close;
W2's mocked controller proves calls, not real resource release.

The [launcher](launcher.py) is separate from `run_guard`. Its
[pipeline](launcher_pipeline.py) exposes custody; [transfer](launcher_transfer.py)
can bind an anchored pidfd to the controller and clear pending fd custody
while retaining the child handle. Parent assembly owns pending child/fd
cleanup and single-use lifecycle. W2 hands the successful identity and same
controller to the engine; retry preserves custody without a second spawn.
This is not an API for adopting an arbitrary already-running process.

## Hermetic fixtures versus actual defaults

[W1/W2 fixtures](tests/test_runtime.py) use `FakeClock`, an injected mocked
controller, fake adapter transport/handles, and a sleeper advancing fake time.
Engine/runner `safe_sigterm` boundaries are mocked as applicable; W2 traps
forbidden calls and asserts their counters remain zero.

Actual defaults are different: the engine selects `SystemClock` when no clock
is supplied; scheduler sleep is real; [AdbProbeAdapter](adb_adapter.py) defaults
to `SubprocessProbeHandle`; `ProcessController` operations touch procfs,
pidfds, signals and reaping. The launcher defaults to a real controller, but
has no implicit emulator spawner. Runtime does not enforce fake dependencies
or require real dependencies to be opt-in. Do not treat this API as a sandbox.

## Evidence and remaining work

W1 exercises a real engine/scheduler/runner TTL halt with fake dependencies.
W2 adds real launcher/admission/pipeline/transfer composition with a prebuilt
child supplied by a **fake spawn helper**, not native adoption or actual spawn.
Its TTL STOPPED path does not establish all health/readiness failure paths.
Neither unit validates real pidfd/waitpid, ADB, kernel behavior or an emulator.

[Active ledger](../../odd/tasks/guard-probe-eval.md) records W1 closure and W2
local commit `18e572bce0b4abd3d265052b1193b3d06b5e1277` (270 ALL), including
functional evidence and procedural deviations. Functional passing checks are
not a global verification PASS; the older quarantine remains unresolved.
W3 closes documentation only after the parent's local commit. The guard as a
whole remains incomplete; U (device/UX evidence) and C (consolidation/blockers)
remain pending in [main consolidation](../../odd/tasks/main-consolidation.md).
