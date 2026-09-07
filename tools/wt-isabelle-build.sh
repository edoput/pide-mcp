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

REPO="/home/edoput/repo/isabelle-mcp"
WORKTREES="$REPO/.claude/worktrees"
if [ -L "$WORKTREES" ] || [ ! -d "$WORKTREES" ]; then
  echo "invalid worktree root: $WORKTREES" >&2
  exit 1
fi
WORKTREES_REAL=$(realpath -e -- "$WORKTREES")
WT_PATH="$WORKTREES/$name"
if [ -L "$WT_PATH" ] || [ ! -d "$WT_PATH" ]; then
  echo "worktree must be a real directory: $WT_PATH" >&2
  exit 1
fi
WT=$(realpath -e -- "$WT_PATH")
if [ "${WT%/*}" != "$WORKTREES_REAL" ]; then
  echo "worktree escapes configured root: $WT" >&2
  exit 1
fi
GIT_ROOT=$(git -C "$WT" rev-parse --show-toplevel 2>/dev/null) || {
  echo "not a Git worktree: $WT" >&2
  exit 1
}
GIT_ROOT=$(realpath -e -- "$GIT_ROOT")
if [ "$GIT_ROOT" != "$WT" ]; then
  echo "not a Git worktree root: $WT" >&2
  exit 1
fi
REPO_GIT=$(git -C "$REPO" rev-parse --path-format=absolute --git-common-dir)
WT_GIT=$(git -C "$WT" rev-parse --path-format=absolute --git-common-dir)
if [ "$WT_GIT" != "$REPO_GIT" ]; then
  echo "worktree does not belong to repository: $WT" >&2
  exit 1
fi

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
  awk -v main_mcp="$REPO/mcp" -v main_test="$REPO/mcp_test" \
      -v worktree_mcp="$WT/mcp" -v worktree_test="$WT/mcp_test" \
      '$0 != main_mcp && $0 != main_test &&
       $0 != worktree_mcp && $0 != worktree_test { print }' \
      "$HOME/.isabelle/Isabelle2025-2/etc/components" > "$next"
  printf '%s\n%s\n' "$WT/mcp" "$WT/mcp_test" >> "$next"
  mv -f -- "$next" "$S/etc/components"
}

roots_worktree() {
  local next="$S/ROOTS.next.$$"
  ISABELLE_ROOT_HOME="$HOME" awk '
    substr($0, 1, 2) == "~/" {
      print ENVIRON["ISABELLE_ROOT_HOME"] "/" substr($0, 3)
      next
    }
    { print }
  ' "$HOME/.isabelle/Isabelle2025-2/ROOTS" > "$next"
  mv -f -- "$next" "$S/ROOTS"
}

safe_dir() {
  [ -d "$1" ] && [ ! -L "$1" ]
}

state_owned() {
  local recorded=""
  safe_dir "$STATE" && [ -f "$OWNER" ] && [ ! -L "$OWNER" ] &&
    IFS= read -r recorded < "$OWNER" && [ "$recorded" = "$name" ]
}

setup_complete() {
  [ -f "$READY" ] && [ ! -L "$READY" ] &&
    safe_dir "$USER_ROOT" && safe_dir "$USER_ROOT/.isabelle" && safe_dir "$S" &&
    safe_dir "$S/etc" && safe_dir "$S/heaps" && safe_dir "$H" && safe_dir "$H/log" &&
    [ -L "$H/HOL" ] && [ "$(readlink -- "$H/HOL")" = "$R/HOL" ] &&
    [ -L "$H/Pure" ] && [ "$(readlink -- "$H/Pure")" = "$R/Pure" ] &&
    [ -f "$H/log/HOL.db" ] && [ ! -L "$H/log/HOL.db" ] &&
    cmp -s "$R/log/HOL.db" "$H/log/HOL.db" &&
    [ -f "$H/log/Pure.db" ] && [ ! -L "$H/log/Pure.db" ] &&
    cmp -s "$R/log/Pure.db" "$H/log/Pure.db"
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
    roots_worktree
    echo "wt-$name: scratch Isabelle user dir already set up at $S" >&2
    return 0
  fi

  rm -rf -- "$USER_ROOT"
  mkdir -p "$S/etc" "$H/log"
  ln -s "$R/HOL" "$H/HOL"
  ln -s "$R/Pure" "$H/Pure"
  cp "$R/log/HOL.db" "$R/log/Pure.db" "$H/log/"
  components_worktree
  roots_worktree
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
