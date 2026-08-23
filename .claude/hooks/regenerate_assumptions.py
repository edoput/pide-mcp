#!/usr/bin/env python3
"""Regenerate assumption registries after Claude edits a plan file."""

from __future__ import annotations

import json
import os
from pathlib import Path
import sys


EXCLUDED_PLAN_FILES = {"README", "ASSUMPTIONS"}


def project_root() -> Path:
    configured = os.environ.get("CLAUDE_PROJECT_DIR")
    if configured:
        return Path(configured).resolve()
    return Path(__file__).resolve().parents[2]


def changed_plan(event: object, root: Path) -> Path | None:
    if not isinstance(event, dict):
        return None
    tool_input = event.get("tool_input")
    if not isinstance(tool_input, dict):
        return None
    file_path = tool_input.get("file_path")
    if not isinstance(file_path, str) or not file_path:
        return None

    changed = Path(file_path)
    if not changed.is_absolute():
        changed = root / changed
    try:
        relative = changed.resolve().relative_to((root / "plans").resolve())
    except ValueError:
        return None

    if len(relative.parts) != 1 or relative.name in EXCLUDED_PLAN_FILES:
        return None
    return relative


def regenerate(root: Path) -> None:
    tools_dir = root / "tools"
    sys.path.insert(0, str(tools_dir))
    try:
        import gen_assumptions
    finally:
        sys.path.pop(0)

    imported = Path(gen_assumptions.__file__).resolve()
    expected = (tools_dir / "gen_assumptions.py").resolve()
    if imported != expected:
        raise ImportError(f"imported {imported}, expected {expected}")
    gen_assumptions.main()


def main() -> int:
    try:
        event = json.load(sys.stdin)
    except json.JSONDecodeError as ex:
        print(f"invalid Claude hook input: {ex}", file=sys.stderr)
        return 2

    root = project_root()
    if changed_plan(event, root) is None:
        return 0

    regenerate(root)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
