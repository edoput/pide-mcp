from __future__ import annotations

import io
from pathlib import Path

import pytest

from tools.planning_gate.commands import CommandStep, StepResult, layer_step_ids
from tools.planning_gate.done import (
    RepositorySnapshot,
    StaticReport,
    run_done,
)
from tools.planning_gate.tooling import spec_test


ROOT = Path(__file__).resolve().parents[3]
PREPARATION = ("scala-build", "munit-catalog", "theories", "theory-catalog")
EXECUTION = layer_step_ids(ROOT)
ALL_STEPS = PREPARATION + ("spec-gate",) + EXECUTION


def fixture_steps() -> dict[str, CommandStep]:
    return {
        ident: CommandStep(ident, ident, ("fixture", ident), 1)
        for ident in ALL_STEPS
    }


def snapshot(
    *, head: str = "a" * 40, tracked: dict[str, str] | None = None,
    dirty: tuple[str, ...] = (),
) -> RepositorySnapshot:
    return RepositorySnapshot(head, tracked or {"tracked.txt": "same"}, dirty)


def static_ok(_: Path) -> StaticReport:
    return StaticReport(5, 400, 100)


@spec_test(
    verifies=("planning_gate#A1", "planning_gate#I1"),
    covers=("planning_gate#T2", "planning_gate#T8"),
)
def test_done_requires_every_fixed_execution_step_after_static_discovery() -> None:
    seen: list[str] = []

    def runner(step: CommandStep, _: Path) -> StepResult:
        seen.append(step.id)
        return StepResult(step.id, 0, 0)

    result = run_done(
        ROOT,
        steps=fixture_steps(),
        runner=runner,
        snapshotter=lambda _: snapshot(),
        static_checker=static_ok,
        stream=io.StringIO(),
    )

    assert result.ok
    assert tuple(seen) == ALL_STEPS
    assert result.executed_steps == ALL_STEPS


@pytest.mark.parametrize("failed_step", ALL_STEPS)
@spec_test(covers=("planning_gate#T3",))
def test_each_registered_step_failure_makes_done_fail_and_names_the_step(
    failed_step: str,
) -> None:
    def runner(step: CommandStep, _: Path) -> StepResult:
        return StepResult(step.id, 9 if step.id == failed_step else 0, 0)

    output = io.StringIO()
    result = run_done(
        ROOT,
        steps=fixture_steps(),
        runner=runner,
        snapshotter=lambda _: snapshot(),
        static_checker=static_ok,
        stream=output,
    )

    assert not result.ok
    assert failed_step in result.failures
    assert failed_step in output.getvalue()


@spec_test(verifies=("planning_gate#A2",), covers=("planning_gate#T5",))
def test_dirty_paths_are_reported_and_tracked_mutation_invalidates_completion() -> None:
    snapshots = iter(
        (
            snapshot(dirty=("plans/planning_gate", "new file.txt")),
            snapshot(tracked={"tracked.txt": "changed"}, dirty=("plans/planning_gate",)),
        )
    )
    output = io.StringIO()
    result = run_done(
        ROOT,
        steps=fixture_steps(),
        runner=lambda step, _: StepResult(step.id, 0, 0),
        snapshotter=lambda _: next(snapshots),
        static_checker=static_ok,
        stream=output,
    )

    rendered = output.getvalue()
    assert "WARNING: dirty working tree" in rendered
    assert "plans/planning_gate" in rendered
    assert "new file.txt" in rendered
    assert "tracked.txt" in rendered
    assert "tracked-content-changed" in result.failures


@spec_test(covers=("planning_gate#T5",))
def test_head_change_invalidates_completion_even_when_files_match() -> None:
    snapshots = iter((snapshot(), snapshot(head="b" * 40)))
    result = run_done(
        ROOT,
        steps=fixture_steps(),
        runner=lambda step, _: StepResult(step.id, 0, 0),
        snapshotter=lambda _: next(snapshots),
        static_checker=static_ok,
        stream=io.StringIO(),
    )
    assert "head-changed" in result.failures
