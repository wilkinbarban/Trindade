"""Hermetic unit tests for scripts.f3_guard.probe_handle module."""

from __future__ import annotations

import os
import sys
import unittest

_REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

from scripts.f3_guard.probe_handle import (
    ProbeCommandHandle,
)


class TestProbeHandle(unittest.TestCase):
    """Hermetic unit tests for ProbeCommandHandle interface."""

    def test_base_interface_raises_not_implemented(self) -> None:
        handle = ProbeCommandHandle()
        with self.assertRaises(NotImplementedError):
            handle.poll()
        with self.assertRaises(NotImplementedError):
            handle.read_available(10)
        with self.assertRaises(NotImplementedError):
            handle.terminate()
        with self.assertRaises(NotImplementedError):
            handle.cleanup()
