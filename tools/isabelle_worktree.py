#!/usr/bin/env python3
"""Prepare private Isabelle state for an explicit checkout, using its launcher."""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from tools.isabelle_launcher import Launcher, LauncherError, resolve_launcher


class WorktreeError(RuntimeError):
    pass


# Executed by the selected installation, so /app and other container-local
# paths are interpreted there. No host paths are interpolated into shell code.
DISCOVER = r'''
set -eu
printf '%s\0' "$ISABELLE_HOME" "$ISABELLE_IDENTIFIER" "$ML_SYSTEM" \
  "$ISABELLE_HEAPS" "$ISABELLE_HEAPS_SYSTEM"
declare -A seen
shopt -s nullglob
for root in "$ISABELLE_HEAPS" "$ISABELLE_HEAPS_SYSTEM"; do
  for candidate in "$root"/"${ML_SYSTEM}_"*; do
    id=${candidate##*/}
    [[ -z ${seen[$id]:-} ]] || continue
    seen[$id]=1
    files=()
    for name in Pure log/Pure.db HOL log/HOL.db; do
      found=''
      for source in "$ISABELLE_HEAPS" "$ISABELLE_HEAPS_SYSTEM"; do
        if [[ -f $source/$id/$name ]]; then found=$source/$id/$name; break; fi
      done
      [[ -n $found ]] || break
      files+=("$found")
    done
    if [[ ${#files[@]} -eq 4 ]]; then
      platform=${id#"${ML_SYSTEM}_"}
      poly=$POLYML_HOME/$platform/poly
      [[ -f $poly ]] || continue
      printf '%s\0' "$id" "${files[@]}" "$poly"
    fi
  done
done
'''
HASH = r'''
set -eu
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum -- "$@"
elif command -v shasum >/dev/null 2>&1; then
  shasum -a 256 -- "$@"
else
  echo 'Isabelle environment needs sha256sum or shasum for seed validation' >&2
  exit 2
fi
'''


def real_dir(path: Path, label: str) -> Path:
    try:
        mode = path.lstat().st_mode
    except FileNotFoundError as ex:
        raise WorktreeError(f"{label} does not exist: {path}") from ex
    if stat.S_ISLNK(mode) or not stat.S_ISDIR(mode):
        raise WorktreeError(f"{label} must be a real directory: {path}")
    resolved = path.resolve()
    if any(char in str(resolved) for char in ("\n", "\r", "\0")):
        raise WorktreeError(f"{label} cannot contain line separators")
    return resolved


def checkout(path: Path) -> Path:
    path = real_dir(path, "worktree")
    result = subprocess.run(
        ["git", "-C", str(path), "rev-parse", "--show-toplevel"],
        text=True, capture_output=True, check=False,
    )
    if result.returncode or Path(result.stdout.strip()).resolve() != path:
        raise WorktreeError(f"--worktree must name a Git checkout root: {path}")
    return path


def invoke(launcher: Launcher, args: list[str], *, environment=None, cwd=None) -> str:
    result = subprocess.run(
        [*launcher.argv, *args], text=True, capture_output=True,
        env=environment, cwd=cwd, timeout=180, check=False,
    )
    if result.returncode:
        detail = (result.stdout + result.stderr).strip()
        raise WorktreeError(f"Isabelle {args[0]} failed (exit {result.returncode}): {detail}")
    return result.stdout


def selected_installation(launcher: Launcher, heap_id: str | None = None) -> dict:
    output = invoke(launcher, ["env", "bash", "-c", DISCOVER])
    fields = output.rstrip("\0").split("\0")
    if len(fields) < 5 or (len(fields) - 5) % 6:
        raise WorktreeError("selected Isabelle returned malformed heap settings")
    home, identifier, ml_system, user_heaps, system_heaps = fields[:5]
    candidates = [fields[i:i + 6] for i in range(5, len(fields), 6)]
    if heap_id is not None:
        candidates = [row for row in candidates if row[0] == heap_id]
    if len(candidates) != 1:
        choices = ", ".join(row[0] for row in candidates) or "none"
        raise WorktreeError(
            f"expected one complete Pure/HOL seed set, found {choices}; "
            "select --heap-id when ambiguous, or build base sessions explicitly "
            "with the selected installation first"
        )
    selected = candidates[0]
    hashes = invoke(launcher, ["env", "bash", "-c", HASH, "hash", *selected[1:]])
    digests = [line[:64] for line in hashes.splitlines()]
    if len(digests) != 5 or any(len(d) != 64 or any(c not in "0123456789abcdef" for c in d) for d in digests):
        raise WorktreeError("selected Isabelle returned malformed seed digests")
    return {
        "argv": list(launcher.argv), "home": home,
        "version": invoke(launcher, ["version"]).strip(),
        "identifier": identifier, "ml_system": ml_system,
        "user_heaps": user_heaps, "system_heaps": system_heaps,
        "heap_id": selected[0], "sources": selected[1:], "sha256": digests,
    }


def atomic_text(path: Path, content: str) -> None:
    fd, name = tempfile.mkstemp(prefix=".write-", dir=path.parent)
    try:
        with os.fdopen(fd, "w") as handle:
            handle.write(content)
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


class State:
    def __init__(self, worktree: Path, launcher: Launcher, components=(), roots=(), heap_id=None):
        self.worktree = worktree
        self.launcher = launcher
        self.state = worktree / ".isabelle-worktree"
        self.components = [real_dir(p, "component") for p in (worktree / "mcp", worktree / "mcp_test", *components)]
        self.roots = [real_dir(p, "session root") for p in roots]
        if len(set(self.components)) != len(self.components):
            raise WorktreeError("duplicate component path")
        self.heap_id = heap_id
        self.identifier = "mcp-wt-" + hashlib.sha256(str(worktree).encode()).hexdigest()[:16]
        self.user = self.state / "user"
        self.home = self.user / ".isabelle" / self.identifier
        self.owner = {"schema": 1, "worktree": str(worktree), "uid": os.getuid()}

    def check_owner(self) -> None:
        real_dir(self.state, "private state")
        owner = self.state / "owner.json"
        if self.state.stat().st_uid != os.getuid() or owner.is_symlink() or not owner.is_file():
            raise WorktreeError(f"refusing unowned state: {self.state}")
        if json.loads(owner.read_text()) != self.owner:
            raise WorktreeError(f"refusing mismatched state: {self.state}")

    @contextmanager
    def locked(self):
        if not self.state.exists() and not self.state.is_symlink():
            self.state.mkdir(mode=0o700)
            atomic_text(self.state / "owner.json", json.dumps(self.owner) + "\n")
        self.check_owner()
        lock = self.state / "lock"
        fd = os.open(lock, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        try:
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as ex:
                raise WorktreeError(f"private state is in use: {self.state}") from ex
            yield
        finally:
            os.close(fd)

    def check_tree(self) -> None:
        # Never follow a replaced ancestor when refreshing configuration.
        for path in (self.user, self.user / ".isabelle", self.home, self.home / "etc", self.home / "heaps"):
            if path.exists() or path.is_symlink():
                real_dir(path, "private state directory")
        for path in (self.home / "etc" / "components", self.home / "ROOTS", self.state / "installation.json"):
            if path.is_symlink():
                raise WorktreeError(f"refusing symlinked state file: {path}")

    def environment(self) -> dict[str, str]:
        environment = self.launcher.child_environment()
        environment.update(USER_HOME=str(self.user), ISABELLE_IDENTIFIER=self.identifier)
        return environment

    def catalogs(self) -> None:
        atomic_text(self.home / "etc" / "components", "".join(str(p) + "\n" for p in self.components))
        atomic_text(self.home / "ROOTS", "".join(str(p) + "\n" for p in self.roots))

    def setup(self) -> None:
        self.check_tree()
        installation = selected_installation(self.launcher, self.heap_id)
        record = self.state / "installation.json"
        if record.exists() and json.loads(record.read_text()) != installation:
            raise WorktreeError("selected installation or base seeds changed; explicitly teardown this private state before recreating it")
        heaps = self.home / "heaps" / installation["heap_id"]
        for path in (heaps, heaps / "log"):
            if path.exists() or path.is_symlink():
                real_dir(path, "private heap directory")
        (self.home / "etc").mkdir(parents=True, exist_ok=True)
        (heaps / "log").mkdir(parents=True, exist_ok=True)
        self.catalogs()
        names = ("Pure", "log/Pure.db", "HOL", "log/HOL.db")
        targets = [heaps / name for name in names]
        for target in targets:
            if target.is_symlink():
                raise WorktreeError(f"refusing symlinked private seed: {target}")
        if not record.exists() or not all(p.is_file() for p in targets):
            for source, target in zip(installation["sources"][:4], targets):
                # This also verifies the destination is visible from Isabelle.
                invoke(self.launcher, ["env", "cp", "--", source, str(target)])
                invoke(self.launcher, ["env", "chmod", "u+w", str(target)])
        # Verify that the adapter propagated the state settings, before any
        # Scala/bootstrap command could use the wrong component catalog.
        reported = invoke(self.launcher, ["getenv", "-b", "ISABELLE_HOME_USER"], environment=self.environment()).strip()
        if reported != str(self.home):
            raise WorktreeError(f"launcher did not forward private state: expected {self.home}, got {reported}")
        print("checking base heaps without rebuilding them (Isabelle may compile configured Scala components)", file=sys.stderr)
        invoke(self.launcher, ["build", "-n", "-b", "Pure", "HOL"], environment=self.environment(), cwd=self.worktree)
        atomic_text(record, json.dumps(installation, sort_keys=True) + "\n")
        print(f"private Isabelle state ready: {self.home}", file=sys.stderr)

    def teardown(self) -> None:
        self.check_owner()
        shutil.rmtree(self.state)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--worktree", required=True, type=Path)
    parser.add_argument("--component", action="append", type=Path, default=[])
    parser.add_argument("--root", action="append", type=Path, default=[])
    parser.add_argument("--heap-id", help="select a seed platform when the installation has several")
    parser.add_argument("action", choices=("setup", "build", "clean", "scala", "test", "teardown"))
    parser.add_argument("arguments", nargs=argparse.REMAINDER)
    args = parser.parse_args(argv)
    try:
        state = State(checkout(args.worktree), resolve_launcher(), args.component, args.root, args.heap_id)
        if args.arguments and args.action != "test":
            raise WorktreeError("extra arguments are only supported for test")
        if args.action == "teardown" and not state.state.exists() and not state.state.is_symlink():
            return 0
        with state.locked():
            if args.action == "teardown":
                state.teardown()
                return 0
            state.setup()
            commands = {
                "build": ["build", "-v", "MCP-Tools", "MCP-Tools-Tests", "MCP-HOL", "MCP-HOL-Tests"],
                "clean": ["build", "-c", "-v", "MCP-HOL-Tests"],
                "scala": ["scala_build"], "test": ["mcp_test", *args.arguments],
            }
            if args.action == "setup":
                return 0
            return subprocess.run([*state.launcher.argv, *commands[args.action]], cwd=state.worktree, env=state.environment()).returncode
    except (LauncherError, OSError, WorktreeError, json.JSONDecodeError, subprocess.TimeoutExpired) as ex:
        print(f"isabelle worktree: {ex}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
