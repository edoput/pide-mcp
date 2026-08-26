#!/usr/bin/env python3
"""Compatibility wrapper for the planning-gate registry command.

Prefer:

    tools/planning-gate registry generate
    tools/planning-gate registry check

The wrapper remains while historical documentation and external callers move
to the repository launcher.
"""

from __future__ import annotations

from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parent.parent
LAUNCHER = ROOT / "tools/planning-gate"


def main() -> int:
    command = "check" if "--check" in sys.argv[1:] else "generate"
    unknown = [argument for argument in sys.argv[1:] if argument != "--check"]
    if unknown:
        print(f"unknown arguments: {' '.join(unknown)}", file=sys.stderr)
        return 2
    return subprocess.run(
        [str(LAUNCHER), "registry", command], cwd=ROOT, check=False
    ).returncode


if __name__ == "__main__":
    raise SystemExit(main())
