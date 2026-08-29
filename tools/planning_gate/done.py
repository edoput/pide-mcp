"""Fixed completion orchestration and working-tree integrity checks."""

from __future__ import annotations

from dataclasses import dataclass
import hashlib
import os
from pathlib import Path
import stat
import subprocess
import sys
from typing import Callable, Mapping, TextIO

from .commands import (
    CommandStep,
    StepResult,
    layer_step_ids,
    registered_commands,
    run_step,
)
from .document import PlanFormat, load_repository
from .labels import audit_is_fresh, validate_labels
from .legacy import compare, read_baseline, require_accepted
from .matrix import discover
from .refinements import validate_refinements
from .registry import stale_outputs


class DoneError(ValueError):
    pass


@dataclass(frozen=True)
class RepositorySnapshot:
    head: str
    tracked: Mapping[str, str]
    dirty_paths: tuple[str, ...]


@dataclass(frozen=True)
class StaticReport:
    plans: int
    tests: int
    legacy_gaps: int


@dataclass(frozen=True)
class DoneResult:
    failures: tuple[str, ...]
    executed_steps: tuple[str, ...]

    @property
    def ok(self) -> bool:
        return not self.failures


def _git(root: Path, *args: str) -> bytes:
    try:
        return subprocess.run(
            ("git", *args), cwd=root, check=True, capture_output=True
        ).stdout
    except subprocess.CalledProcessError as ex:
        detail = ex.stderr.decode("utf-8", "replace").strip()
        raise DoneError(f"git {' '.join(args)} failed: {detail}") from ex


def _decode_path(value: bytes) -> str:
    return value.decode("utf-8", "surrogateescape")


def _dirty_paths(root: Path) -> tuple[str, ...]:
    records = _git(root, "status", "--porcelain=v1", "-z", "--untracked-files=all").split(b"\0")
    paths: list[str] = []
    index = 0
    while index < len(records) - 1:
        record = records[index]
        index += 1
        if len(record) < 4:
            raise DoneError("git status emitted a malformed porcelain record")
        status_code = record[:2]
        paths.append(_decode_path(record[3:]))
        if b"R" in status_code or b"C" in status_code:
            if index >= len(records) - 1:
                raise DoneError("git status omitted the source of a rename/copy")
            paths.append(_decode_path(records[index]))
            index += 1
    return tuple(sorted(set(paths)))


def _tracked_digest(path: Path) -> str:
    try:
        info = path.lstat()
    except FileNotFoundError:
        return "missing"
    if stat.S_ISLNK(info.st_mode):
        payload = os.fsencode(os.readlink(path))
        kind = "symlink"
    elif stat.S_ISREG(info.st_mode):
        payload = path.read_bytes()
        kind = "file"
    elif stat.S_ISDIR(info.st_mode):
        payload = b""
        kind = "directory"
    else:
        payload = b""
        kind = f"mode-{stat.S_IFMT(info.st_mode):o}"
    executable = "x" if info.st_mode & 0o111 else "-"
    return f"{kind}:{executable}:sha256:{hashlib.sha256(payload).hexdigest()}"


def repository_snapshot(root: Path) -> RepositorySnapshot:
    root = root.resolve()
    head = _git(root, "rev-parse", "HEAD").decode("ascii").strip()
    names = [
        _decode_path(value)
        for value in _git(root, "ls-files", "-z").split(b"\0")
        if value
    ]
    tracked = {name: _tracked_digest(root / name) for name in names}
    return RepositorySnapshot(head, tracked, _dirty_paths(root))


def changed_tracked_paths(
    start: RepositorySnapshot, end: RepositorySnapshot
) -> tuple[str, ...]:
    return tuple(
        sorted(
            path
            for path in set(start.tracked) | set(end.tracked)
            if start.tracked.get(path) != end.tracked.get(path)
        )
    )


def check_static_closure(root: Path) -> StaticReport:
    documents = load_repository(root)
    validate_labels(documents, require_completion=True)
    validate_refinements(documents, root, require_completion=True)

    audit_fresh, _ = audit_is_fresh(documents, root)
    if not audit_fresh:
        raise DoneError(
            "legacy label migration audit is missing or stale: "
            "run tools/planning-gate labels audit generate"
        )

    wrong_done_commands = [
        document.id
        for document in documents
        if document.format is PlanFormat.V1
        and document.done_command != ("tools/planning-gate", "done")
    ]
    if wrong_done_commands:
        raise DoneError(
            "canonical plans do not use tools/planning-gate done: "
            + ", ".join(wrong_done_commands)
        )

    _, stale = stale_outputs(root)
    if stale:
        names = ", ".join(path.relative_to(root).as_posix() for path in stale)
        raise DoneError(f"generated registries are stale: {names}")

    matrix = discover(root)
    if matrix.missing_producers:
        raise DoneError(
            "missing verification producers: " + ", ".join(matrix.missing_producers)
        )
    baseline = read_baseline(root / "plans/legacy_unlinked.csv")
    ratchet = compare(matrix, baseline)
    require_accepted(ratchet)
    return StaticReport(
        len(documents),
        sum(len(producer.tests) for producer in matrix.producers),
        len(matrix.missing_coverage),
    )


StepRunner = Callable[[CommandStep, Path], StepResult]
Snapshotter = Callable[[Path], RepositorySnapshot]
StaticChecker = Callable[[Path], StaticReport]


def run_done(
    root: Path,
    *,
    steps: Mapping[str, CommandStep] | None = None,
    runner: StepRunner | None = None,
    snapshotter: Snapshotter = repository_snapshot,
    static_checker: StaticChecker = check_static_closure,
    stream: TextIO = sys.stdout,
) -> DoneResult:
    root = root.resolve()
    steps = registered_commands(root) if steps is None else dict(steps)
    runner = (lambda step, cwd: run_step(step, cwd, stream=stream)) if runner is None else runner
    preparation = ("scala-build", "munit-catalog", "theories", "theory-catalog")
    execution = layer_step_ids(root)
    required = preparation + ("spec-gate",) + execution
    absent = [step for step in required if step not in steps]
    if absent:
        raise DoneError(f"completion pipeline has no registered steps: {absent}")

    start = snapshotter(root)
    print(f"planning gate start HEAD: {start.head}", file=stream)
    if start.dirty_paths:
        print("WARNING: dirty working tree; human review required:", file=stream)
        for path in start.dirty_paths:
            print(f"  {path}", file=stream)
    else:
        print("working tree: clean", file=stream)

    failures: list[str] = []
    executed: list[str] = []

    def execute(step_id: str) -> bool:
        executed.append(step_id)
        result = runner(steps[step_id], root)
        if not result.ok:
            failures.append(step_id)
        return result.ok

    preparation_ok = True
    for step_id in preparation:
        if not execute(step_id):
            preparation_ok = False
            break

    if preparation_ok:
        try:
            report = static_checker(root)
            print(
                "static closure: PASS "
                f"({report.plans} plans; {report.tests} tests; "
                f"{report.legacy_gaps} reviewed legacy gaps)",
                file=stream,
            )
        except Exception as ex:
            print(f"static closure: FAIL: {ex}", file=stream)
            failures.append("static-closure")
        else:
            if execute("spec-gate"):
                for step_id in execution:
                    execute(step_id)

    end = snapshotter(root)
    if end.head != start.head:
        print(f"repository integrity: FAIL: HEAD changed to {end.head}", file=stream)
        failures.append("head-changed")
    changed = changed_tracked_paths(start, end)
    if changed:
        print("repository integrity: FAIL: tracked files changed:", file=stream)
        for path in changed:
            print(f"  {path}", file=stream)
        failures.append("tracked-content-changed")
    if end.head == start.head and not changed:
        print("repository integrity: PASS", file=stream)

    unique_failures = tuple(dict.fromkeys(failures))
    print(
        "planning gate done: " + ("PASS" if not unique_failures else "FAIL: ")
        + ("" if not unique_failures else ", ".join(unique_failures)),
        file=stream,
    )
    return DoneResult(unique_failures, tuple(executed))
