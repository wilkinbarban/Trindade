"""Hermetic composition of preflight, pipeline and admission helpers."""

from typing import Any, Callable, List, Optional, Tuple

from scripts.f3_guard.controller import ProcessController
from scripts.f3_guard.launcher_admission import LauncherAdmissionHelper
from scripts.f3_guard.launcher_pipeline import LauncherPipelineExecutor
from scripts.f3_guard.launcher_preflight import (
    DEFAULT_EMULATOR_PATH, TARGET_AVD, TARGET_PORT, TARGET_UID,
    LaunchResult, LauncherPreflightHelper,
)
from scripts.f3_guard.types import ProcessIdentity

__all__ = ["EmulatorLauncher"]


class _PreflightController:
    """Forward capabilities without testing controller truthiness."""

    def __init__(self, controller: Any) -> None:
        self.controller = controller

    def __getattr__(self, name: str) -> Any:
        return getattr(self.controller, name)


class EmulatorLauncher:
    """Single-use composition without an implicit emulator spawner."""

    def __init__(
        self,
        explicit_opt_in: bool = False,
        mem_reader: Optional[Callable[[], Tuple[int, int]]] = None,
        controller: Optional[ProcessController] = None,
        target_uid: int = TARGET_UID,
        avd_name: str = TARGET_AVD,
        port: int = TARGET_PORT,
        emulator_binary: str = DEFAULT_EMULATOR_PATH,
        binary_validator: Optional[Callable[[str], bool]] = None,
        availability_check: Optional[Callable[[str, int], bool]] = None,
        spawner: Optional[Callable[[List[str]], Any]] = None,
        *,
        identity_verifier: Optional[
            Callable[[ProcessIdentity, Any], Tuple[bool, str]]
        ] = None,
        preflight_helper: Optional[LauncherPreflightHelper] = None,
        pipeline_executor: Optional[LauncherPipelineExecutor] = None,
    ) -> None:
        selected = ProcessController() if controller is None else controller
        self._preflight = (
            LauncherPreflightHelper(
                explicit_opt_in, mem_reader, _PreflightController(selected),
                target_uid, avd_name, port, emulator_binary,
                binary_validator, availability_check,
            ) if preflight_helper is None else preflight_helper
        )
        self._pipeline = (
            LauncherPipelineExecutor(
                spawner=spawner, controller=selected, target_uid=target_uid,
                avd_name=avd_name, port=port, emulator_binary=emulator_binary,
                identity_verifier=identity_verifier,
            ) if pipeline_executor is None else pipeline_executor
        )
        self._admission = LauncherAdmissionHelper(
            custody_source=self._pipeline.custody_tracker,
        )
        self._pipeline_started = False

    @property
    def admission_helper(self) -> LauncherAdmissionHelper:
        return self._admission

    @property
    def attempted(self) -> bool:
        return self._admission.attempted

    is_attempted = attempted

    @property
    def pending_child(self) -> Optional[Any]:
        return self._pipeline.pending_child

    child_handle = pending_child

    @property
    def pending_pidfd(self) -> Optional[int]:
        return self._pipeline.pending_pidfd

    pidfd = pending_pidfd

    @property
    def last_result(self) -> Optional[LaunchResult]:
        if self._pipeline_started:
            return self._pipeline.last_result
        return self._admission.last_result

    def get_frozen_argv(self) -> List[str]:
        return list(self._preflight.get_frozen_argv())

    def _execute(self) -> LaunchResult:
        rejected = self._preflight.validate()
        if rejected is not None:
            return rejected
        self._pipeline_started = True
        try:
            return self._pipeline.execute(self.get_frozen_argv())
        except (KeyboardInterrupt, SystemExit):
            # Pipeline recorded custody. Seed the public fallback before
            # admission's known-only interrupt handler, without polling again.
            result = self._pipeline.last_result
            if result is not None:
                self._admission.record_result(result)
                self._admission.set_custody_source(None)
            raise

    def launch(self) -> LaunchResult:
        source = self._admission.custody_source
        try:
            return self._admission.execute(self._execute)
        finally:
            if self._admission.custody_source is not source:
                self._admission.set_custody_source(source)
