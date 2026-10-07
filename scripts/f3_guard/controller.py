"""Process controller composing inspection, pidfd binding, and OS operations."""

from __future__ import annotations

from scripts.f3_guard.pidfd_binding import PidfdBindingHelper
from scripts.f3_guard.pidfd_ops import PidfdOpsHelper
from scripts.f3_guard.proc_inspection import ProcInspectionHelper


class ProcessController(ProcInspectionHelper, PidfdBindingHelper, PidfdOpsHelper):
    """Abstract/default process interaction provider for inspection and signaling."""

    is_direct_child: bool = False
    use_pidfd: bool = False
