#!/usr/bin/env python3
"""Build the MCP-Tools/MCP-HOL sessions from a git worktree, keeping the
worktree's Isabelle user directory (heaps, session DBs) private to it.
See .claude/rules/theories-rebuild.md, "Building from a git worktree".

Also imported by .claude/hooks/isabelle-worktree, which calls setup()
and teardown() directly on EnterWorktree/ExitWorktree instead of
shelling out to this file -- keep those two importable with no
side effects at import time.

usage: tools/wt_isabelle_build.py <name> [setup|build|clean|teardown]

  <name>    worktree name, matching .claude/worktrees/<name>
  setup     create the scratch Isabelle user dir (idempotent, run once)
  build     run the session build (default if no action given)
  clean     force-rebuild MCP-HOL-Tests (isabelle build -c)
  teardown  rm -rf the scratch Isabelle user dir
"""

import re
import shutil
import subprocess
import sys
from pathlib import Path

REPO = Path("/home/edoput/repo/isabelle-mcp")
WORKTREES = REPO / ".claude" / "worktrees"
ISABELLE_HOME = Path.home() / ".isabelle"
REF_HEAPS = ISABELLE_HOME / "Isabelle2025-2" / "heaps" / "polyml-5.9.2_x86_64_32-linux"
COMPONENTS = ISABELLE_HOME / "Isabelle2025-2" / "etc" / "components"
ROOTS = ISABELLE_HOME / "Isabelle2025-2" / "ROOTS"
OWN_COMPONENT = re.compile(r"/isabelle-mcp/mcp(_test)?$")


def setup(name):
    scratch = ISABELLE_HOME / f"wt-{name}"
    heaps = scratch / "heaps" / "polyml-5.9.2_x86_64_32-linux"
    if (heaps / "HOL").exists():
        return  # idempotent: already set up
    if not (WORKTREES / name).is_dir():
        return  # worktree not actually there yet; nothing to do
    (scratch / "etc").mkdir(parents=True, exist_ok=True)
    (heaps / "log").mkdir(parents=True, exist_ok=True)
    (heaps / "HOL").symlink_to(REF_HEAPS / "HOL")
    (heaps / "Pure").symlink_to(REF_HEAPS / "Pure")
    shutil.copy(REF_HEAPS / "log" / "HOL.db", heaps / "log" / "HOL.db")
    shutil.copy(REF_HEAPS / "log" / "Pure.db", heaps / "log" / "Pure.db")
    kept = [line for line in COMPONENTS.read_text().splitlines(keepends=True)
            if not OWN_COMPONENT.search(line.rstrip("\n"))]
    (scratch / "etc" / "components").write_text("".join(kept))
    shutil.copy(ROOTS, scratch / "ROOTS")


def teardown(name):
    shutil.rmtree(ISABELLE_HOME / f"wt-{name}", ignore_errors=True)


def _isabelle(name, args):
    return subprocess.run([
        "flatpak", "run", f"--env=ISABELLE_IDENTIFIER=wt-{name}",
        "--command=isabelle", "de.tum.in.isabelle.Isabelle", *args,
    ])


def build(name):
    wt = WORKTREES / name
    if not wt.is_dir():
        print(f"no worktree at {wt}", file=sys.stderr)
        sys.exit(1)
    return _isabelle(name, [
        "build", "-d", str(wt / "mcp" / "Tools"), "-v",
        "MCP-Tools", "MCP-Tools-Tests", "MCP-HOL", "MCP-HOL-Tests",
    ])


def clean(name):
    wt = WORKTREES / name
    if not wt.is_dir():
        print(f"no worktree at {wt}", file=sys.stderr)
        sys.exit(1)
    return _isabelle(name, ["build", "-c", "-d", str(wt / "mcp" / "Tools"), "-v", "MCP-HOL-Tests"])


def main():
    if len(sys.argv) < 2:
        print("usage: wt_isabelle_build.py <name> [setup|build|clean|teardown]", file=sys.stderr)
        sys.exit(1)
    name = sys.argv[1]
    action = sys.argv[2] if len(sys.argv) > 2 else "build"

    if action == "setup":
        setup(name)
        print(f"wt-{name}: scratch Isabelle user dir ready at {ISABELLE_HOME / f'wt-{name}'}", file=sys.stderr)
    elif action == "build":
        setup(name)
        result = build(name)
        sys.exit(result.returncode)
    elif action == "clean":
        result = clean(name)
        sys.exit(result.returncode)
    elif action == "teardown":
        teardown(name)
        print(f"wt-{name}: removed {ISABELLE_HOME / f'wt-{name}'}", file=sys.stderr)
    else:
        print(f"unknown action: {action} (want setup|build|clean|teardown)", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
