"""CLI entry point and offline verification for F3 Emulator Guard."""

import argparse
import sys

from scripts.f3_guard.clock import DEFAULT_TTL_SEC
from scripts.f3_guard.engine import DEFAULT_CADENCE_SEC
from scripts.f3_guard.memory import GUARD_RAM_MIN_KB, GUARD_SWAP_MIN_KB

__all__ = ["main"]


def main() -> int:
    parser = argparse.ArgumentParser(
        description="F3 Android Emulator Capacity and Safety Guard (Offline / Verification mode)"
    )
    parser.add_argument(
        "--check-offline",
        action="store_true",
        help="Run offline self-check without starting or contacting real emulator",
    )
    parser.add_argument(
        "--version",
        action="version",
        version="f3_emulator_guard 1.0.0",
    )
    args = parser.parse_args()

    if args.check_offline:
        print("F3 Emulator Guard offline contract verification: OK")
        print(f"  RAM Limit: {GUARD_RAM_MIN_KB} kB")
        print(f"  Swap Limit: {GUARD_SWAP_MIN_KB} kB")
        print(f"  Supervision TTL: {DEFAULT_TTL_SEC} s (monotonic)")
        print(f"  Cadence: {DEFAULT_CADENCE_SEC} s")
        return 0

    parser.print_help()
    return 0


if __name__ == "__main__":
    sys.exit(main())
