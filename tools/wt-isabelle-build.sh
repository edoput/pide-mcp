#!/usr/bin/env bash
# Build the MCP-Tools/MCP-HOL sessions from a git worktree, keeping the
# worktree's Isabelle user directory (heaps, session DBs) private to it.
# See .claude/rules/theories-rebuild.md, "Building from a git worktree".
#
# Usage:
#   tools/wt-isabelle-build.sh <name> [setup|build|clean|scala|test|teardown]
#
#   <name>    worktree name, matching .claude/worktrees/<name>
#   setup     create the scratch Isabelle user dir (idempotent, run once)
#   build     run the session build (default if no action given)
#   clean     force-rebuild MCP-HOL-Tests (isabelle build -c)
#   scala     build this worktree's mcp.jar and mcp_test.jar
#   test      run this worktree's mcp_test tool (remaining arguments are passed on)
#   teardown  remove the scratch Isabelle user dir
#
# Set ISABELLE_TOOL to an Isabelle launcher executable when Flatpak is not the
# desired launcher. Every invocation receives the same worktree-local
# USER_HOME and ISABELLE_IDENTIFIER.
set -euo pipefail

name=${1:?usage: $0 <name> [setup|build|clean|scala|test|teardown]}
action=${2:-build}

case "$name" in
  ""|"."|".."|*[!A-Za-z0-9._-]*)
    echo "invalid worktree name: $name" >&2
    exit 1
    ;;
esac

WT="/home/edoput/repo/isabelle-mcp/.claude/worktrees/$name"
STATE="/tmp/isabelle-mcp-worktrees/$name"
USER_ROOT="$STATE/user"
S="$USER_ROOT/.isabelle/wt-$name"
R="$HOME/.isabelle/Isabelle2025-2/heaps/polyml-5.9.2_x86_64_32-linux"
H="$S/heaps/polyml-5.9.2_x86_64_32-linux"

isabelle() {
  if [ -n "${ISABELLE_TOOL:-}" ]; then
    env USER_HOME="$USER_ROOT" ISABELLE_IDENTIFIER="wt-$name" \
      "$ISABELLE_TOOL" "$@"
  else
    flatpak run --env=USER_HOME="$USER_ROOT" \
      --env=ISABELLE_IDENTIFIER="wt-$name" \
      --command=isabelle de.tum.in.isabelle.Isabelle "$@"
  fi
}

components_theory_safe() {
  grep -v '/isabelle-mcp/mcp\(_test\)\?$' \
    "$HOME/.isabelle/Isabelle2025-2/etc/components" > "$S/etc/components"
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
  components_theory_safe
  sed "s|^~/|$HOME/|" "$HOME/.isabelle/Isabelle2025-2/ROOTS" > "$S/ROOTS"
  echo "wt-$name: scratch Isabelle user dir ready at $S" >&2
}

build() {
  [ -d "$WT" ] || { echo "no worktree at $WT" >&2; exit 1; }
  components_theory_safe
  isabelle build -d "$WT/mcp/Tools" -v \
    MCP-Tools MCP-Tools-Tests MCP-HOL MCP-HOL-Tests
}

clean() {
  [ -d "$WT" ] || { echo "no worktree at $WT" >&2; exit 1; }
  components_theory_safe
  isabelle build -c -d "$WT/mcp/Tools" -v MCP-HOL-Tests
}

with_mcp_components() {
  components_theory_safe
  {
    echo "$WT/mcp"
    echo "$WT/mcp_test"
  } >> "$S/etc/components"
  trap components_theory_safe EXIT

  local result
  if "$@"; then result=0; else result=$?; fi
  components_theory_safe
  trap - EXIT
  return "$result"
}

scala() {
  [ -d "$WT" ] || { echo "no worktree at $WT" >&2; exit 1; }
  with_mcp_components isabelle scala_build
}

test_() {
  [ -d "$WT" ] || { echo "no worktree at $WT" >&2; exit 1; }
  with_mcp_components isabelle mcp_test "$@"
}

teardown() {
  rm -rf -- "$STATE"
  echo "wt-$name: removed $STATE" >&2
}

case "$action" in
  setup) setup ;;
  build) setup; build ;;
  clean) clean ;;
  scala) setup; scala ;;
  test) setup; shift 2; test_ "$@" ;;
  teardown) teardown ;;
  *) echo "unknown action: $action (want setup|build|clean|scala|test|teardown)" >&2; exit 1 ;;
esac
