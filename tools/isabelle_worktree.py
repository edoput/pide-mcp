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
import shlex
import stat
import subprocess
import sys
import tempfile

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from tools.isabelle_launcher import Launcher, LauncherError, resolve_launcher


class WorktreeError(RuntimeError):
    pass


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
    def __init__(self, worktree: Path, launcher: Launcher, components=(), roots=(), base_heaps=None):
        self.worktree = worktree
        self.launcher = launcher
        self.base_heaps = base_heaps
        self.state = worktree / ".isabelle-worktree"
        self.components = [real_dir(p, "component") for p in (worktree / "mcp", worktree / "mcp_test", *components)]
        self.roots = [real_dir(p, "session root") for p in roots]
        if len(set(self.components)) != len(self.components):
            raise WorktreeError("duplicate component path")
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
        for path in (self.home / "etc" / "components", self.home / "ROOTS", self.home / "etc" / "preferences", self.home / "etc" / "settings"):
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
        (self.home / "etc").mkdir(parents=True, exist_ok=True)
        (self.home / "heaps").mkdir(exist_ok=True)
        self.catalogs()
        # A caller may nominate a base store in the installation namespace.
        # Isabelle still chooses its platform and validates every dependency.
        atomic_text(self.home / "etc" / "settings",
                    "" if self.base_heaps is None else
                    "ISABELLE_HEAPS_SYSTEM=" + shlex.quote(self.base_heaps) + "\n")
        # Keep all mutable build output in this checkout. Isabelle's Store
        # chooses compatible system heaps and validates their build metadata.
        atomic_text(self.home / "etc" / "preferences", "system_heaps = false\n")
        # Verify that the adapter propagated the state settings, before any
        # Scala/bootstrap command could use the wrong component catalog.
        reported = invoke(self.launcher, ["getenv", "-b", "ISABELLE_HOME_USER"], environment=self.environment()).strip()
        if reported != str(self.home):
            raise WorktreeError(f"launcher did not forward private state: expected {self.home}, got {reported}")
        print("checking base heaps without rebuilding them (Isabelle may compile configured Scala components)", file=sys.stderr)
        invoke(self.launcher, ["build", "-n", "-b", "Pure", "HOL"], environment=self.environment(), cwd=self.worktree)
        print(f"private Isabelle state ready: {self.home}", file=sys.stderr)

    def teardown(self) -> None:
        self.check_owner()
        shutil.rmtree(self.state)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--worktree", required=True, type=Path)
    parser.add_argument("--component", action="append", type=Path, default=[])
    parser.add_argument("--root", action="append", type=Path, default=[])
    parser.add_argument("--base-heaps", help="optional base heap input root, as visible to Isabelle")
    parser.add_argument("action", choices=("setup", "build", "clean", "scala", "test", "teardown"))
    parser.add_argument("arguments", nargs=argparse.REMAINDER)
    args = parser.parse_args(argv)
    try:
        state = State(checkout(args.worktree), resolve_launcher(), args.component, args.root, args.base_heaps)
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
