#!/usr/bin/env bash
# Build the MCP-Tools/MCP-HOL sessions from a git worktree, keeping the
# worktree's Isabelle user directory (heaps, session DBs) private to it.
# See .claude/rules/theories-rebuild.md, "Building from a git worktree".
#
# Usage:
#   tools/wt-isabelle-build.sh <name> [setup|build|clean|teardown]
#
#   <name>    worktree name, matching .claude/worktrees/<name>
#   setup     create the scratch Isabelle user dir (idempotent, run once)
#   build     run the session build (default if no action given)
#   clean     force-rebuild MCP-HOL-Tests (isabelle build -c)
#   teardown  rm -rf the scratch Isabelle user dir
set -euo pipefail

name=${1:?usage: $0 <name> [setup|build|clean|teardown]}
action=${2:-build}

WT="/home/edoput/repo/isabelle-mcp/.claude/worktrees/$name"
S="$HOME/.isabelle/wt-$name"
R="$HOME/.isabelle/Isabelle2025-2/heaps/polyml-5.9.2_x86_64_32-linux"
H="$S/heaps/polyml-5.9.2_x86_64_32-linux"

isabelle() {
  flatpak run --env=ISABELLE_IDENTIFIER="wt-$name" \
    --command=isabelle de.tum.in.isabelle.Isabelle "$@"
}

setup() {
  if [ -e "$H/HOL" ]; then
    echo "wt-$name: scratch Isabelle user dir already set up at $S" >&2
    return 0
  fi
  [ -d "$WT" ] || { echo "no worktree at $WT" >&2; exit 1; }
  mkdir -p "$S/etc" "$H/log"
  ln -s "$R/HOL" "$H/HOL"
  ln -s "$R/Pure" "$H/Pure"
  cp "$R/log/HOL.db" "$R/log/Pure.db" "$H/log/"
  grep -v '/isabelle-mcp/mcp\(_test\)\?$' \
    "$HOME/.isabelle/Isabelle2025-2/etc/components" > "$S/etc/components"
  cp "$HOME/.isabelle/Isabelle2025-2/ROOTS" "$S/ROOTS"
  echo "wt-$name: scratch Isabelle user dir ready at $S" >&2
}

build() {
  [ -d "$WT" ] || { echo "no worktree at $WT" >&2; exit 1; }
  isabelle build -d "$WT/mcp/Tools" -v \
    MCP-Tools MCP-Tools-Tests MCP-HOL MCP-HOL-Tests
}

clean() {
  [ -d "$WT" ] || { echo "no worktree at $WT" >&2; exit 1; }
  isabelle build -c -d "$WT/mcp/Tools" -v MCP-HOL-Tests
}

teardown() {
  rm -rf "$S"
  echo "wt-$name: removed $S" >&2
}

case "$action" in
  setup) setup ;;
  build) setup; build ;;
  clean) clean ;;
  teardown) teardown ;;
  *) echo "unknown action: $action (want setup|build|clean|teardown)" >&2; exit 1 ;;
esac
