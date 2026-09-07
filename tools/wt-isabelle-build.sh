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
STATE="$WT/.isabelle-worktree"
USER_ROOT="$STATE/user"
S="$USER_ROOT/.isabelle/wt-$name"
R="$HOME/.isabelle/Isabelle2025-2/heaps/polyml-5.9.2_x86_64_32-linux"
H="$S/heaps/polyml-5.9.2_x86_64_32-linux"
OWNER="$STATE/owner"
READY="$STATE/ready"

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

components_worktree() {
  local next="$S/etc/components.next.$$"
  grep -v '/isabelle-mcp/mcp\(_test\)\?$' \
    "$HOME/.isabelle/Isabelle2025-2/etc/components" > "$next"
  printf '%s\n%s\n' "$WT/mcp" "$WT/mcp_test" >> "$next"
  mv -f -- "$next" "$S/etc/components"
}

state_owned() {
  local recorded=""
  [ ! -L "$STATE" ] && [ -d "$STATE" ] && [ -f "$OWNER" ] &&
    IFS= read -r recorded < "$OWNER" && [ "$recorded" = "$name" ]
}

setup_complete() {
  [ -f "$READY" ] &&
    [ -e "$H/HOL" ] && [ -e "$H/Pure" ] &&
    [ -f "$H/log/HOL.db" ] && [ -f "$H/log/Pure.db" ] &&
    [ -f "$S/etc/components" ] && [ -f "$S/ROOTS" ]
}

setup() {
  local creating="$STATE.setup.$$"
  [ -d "$WT" ] || { echo "no worktree at $WT" >&2; exit 1; }
  if [ -L "$STATE" ]; then
    echo "refusing symlinked worktree state: $STATE" >&2
    exit 1
  fi
  if [ -e "$STATE" ] && ! state_owned; then
    echo "refusing unowned worktree state: $STATE" >&2
    exit 1
  fi
  if [ ! -e "$STATE" ]; then
    mkdir -m 700 -- "$creating"
    printf '%s\n' "$name" > "$creating/owner"
    if ! mv -T -- "$creating" "$STATE"; then
      rm -rf -- "$creating"
      echo "could not claim worktree state: $STATE" >&2
      exit 1
    fi
  fi
  if setup_complete; then
    components_worktree
    echo "wt-$name: scratch Isabelle user dir already set up at $S" >&2
    return 0
  fi

  rm -rf -- "$USER_ROOT"
  mkdir -p "$S/etc" "$H/log"
  ln -s "$R/HOL" "$H/HOL"
  ln -s "$R/Pure" "$H/Pure"
  cp "$R/log/HOL.db" "$R/log/Pure.db" "$H/log/"
  components_worktree
  sed "s|^~/|$HOME/|" "$HOME/.isabelle/Isabelle2025-2/ROOTS" > "$S/ROOTS"
  : > "$READY"
  echo "wt-$name: scratch Isabelle user dir ready at $S" >&2
}

build() {
  [ -d "$WT" ] || { echo "no worktree at $WT" >&2; exit 1; }
  components_worktree
  isabelle build -v \
    MCP-Tools MCP-Tools-Tests MCP-HOL MCP-HOL-Tests
}

clean() {
  [ -d "$WT" ] || { echo "no worktree at $WT" >&2; exit 1; }
  components_worktree
  isabelle build -c -v MCP-HOL-Tests
}

scala() {
  [ -d "$WT" ] || { echo "no worktree at $WT" >&2; exit 1; }
  components_worktree
  isabelle scala_build
}

test_() {
  [ -d "$WT" ] || { echo "no worktree at $WT" >&2; exit 1; }
  components_worktree
  isabelle mcp_test "$@"
}

teardown() {
  if [ ! -e "$STATE" ] && [ ! -L "$STATE" ]; then
    echo "wt-$name: no scratch Isabelle user dir at $STATE" >&2
    return 0
  fi
  if ! state_owned; then
    echo "refusing to remove unowned worktree state: $STATE" >&2
    exit 1
  fi
  rm -rf -- "$STATE"
  echo "wt-$name: removed $STATE" >&2
}

case "$action" in
  setup) setup ;;
  build) setup; build ;;
  clean) setup; clean ;;
  scala) setup; scala ;;
  test) setup; shift 2; test_ "$@" ;;
  teardown) teardown ;;
  *) echo "unknown action: $action (want setup|build|clean|scala|test|teardown)" >&2; exit 1 ;;
esac
