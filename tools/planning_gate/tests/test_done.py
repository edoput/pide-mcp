from __future__ import annotations

import io
from pathlib import Path

import pytest

from tools.planning_gate.commands import CommandStep, StepResult, layer_step_ids
from tools.planning_gate.done import (
    DoneError,
    RepositorySnapshot,
    StaticReport,
    check_static_closure,
    run_done,
)
from tools.planning_gate.document import load_repository
from tools.planning_gate.labels import generate_audit
from tools.planning_gate.tests.test_document import repository, valid_plan, write_plan
from tools.planning_gate.tooling import spec_test


ROOT = Path(__file__).resolve().parents[3]
PREPARATION = ("scala-build", "munit-catalog", "theories", "theory-manifest")
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


@pytest.mark.parametrize("audit_state", ("missing", "stale"))
@spec_test(covers=("planning_gate#T9",))
def test_static_closure_rejects_a_missing_or_stale_legacy_label_audit(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, audit_state: str
) -> None:
    import tools.planning_gate.done as done_module

    root = repository(tmp_path)
    write_plan(root, "canonical", valid_plan("canonical"))
    legacy = write_plan(
        root,
        "legacy",
        "status: planned\n\nA1. A historical assumption requiring review.\n",
    )
    assert generate_audit(load_repository(root), root) == 1
    if audit_state == "missing":
        (root / "plans/migration/label-audit.json").unlink()
    else:
        legacy.write_text(
            legacy.read_text(encoding="utf-8")
            + "\nD1. A newly discovered historical design decision.\n",
            encoding="utf-8",
        )
    monkeypatch.setattr(done_module, "validate_labels", lambda *args, **kwargs: None)
    monkeypatch.setattr(done_module, "validate_refinements", lambda *args, **kwargs: None)

    with pytest.raises(DoneError, match="legacy label migration audit is missing or stale"):
        check_static_closure(root)


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


@spec_test(covers=("planning_gate#T8",))
def test_done_uses_the_shared_theory_catalog_composition(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    import tools.planning_gate.done as done_module

    seen: list[str] = []

    def composite(
        _: Path, *, steps: dict[str, CommandStep], runner: object
    ) -> tuple[StepResult, ...]:
        del steps, runner
        seen.append("theory-catalog")
        return (StepResult("theories", 0, 0), StepResult("theory-manifest", 0, 0))

    monkeypatch.setattr(done_module, "run_theory_catalog", composite)
    result = run_done(
        ROOT,
        steps=fixture_steps(),
        runner=lambda step, _: StepResult(step.id, 0, 0),
        snapshotter=lambda _: snapshot(),
        static_checker=static_ok,
        stream=io.StringIO(),
    )

    assert result.ok
    assert seen == ["theory-catalog"]


@spec_test(covers=("planning_gate#T3",))
def test_done_accounts_for_the_invoked_theory_step_not_an_injected_result_label() -> None:
    def runner(step: CommandStep, _: Path) -> StepResult:
        if step.id == "theories":
            return StepResult("spoofed", 9, 0)
        return StepResult(step.id, 0, 0)

    result = run_done(
        ROOT,
        steps=fixture_steps(),
        runner=runner,
        snapshotter=lambda _: snapshot(),
        static_checker=static_ok,
        stream=io.StringIO(),
    )

    assert result.failures == ("theories",)
    assert result.executed_steps == ("scala-build", "munit-catalog", "theories")


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
