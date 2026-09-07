from __future__ import annotations

from dataclasses import dataclass
import json
from typing import Mapping

from tools.planning_gate.commands import StepResult
from tools.planning_gate.evidence import ExecutionEvidence, render, repository_identity
from tools.planning_gate.tooling import spec_test


@dataclass(frozen=True)
class FixtureSnapshot:
    head: str = "a" * 40
    tracked: Mapping[str, str] | None = None
    dirty_paths: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if self.tracked is None:
            object.__setattr__(self, "tracked", {"tracked.txt": "sha256:same"})


REQUIRED = ("scala-build", "scala-unit")


def evidence(
    results: tuple[StepResult, ...] = (),
    *,
    start: FixtureSnapshot = FixtureSnapshot(),
    end: FixtureSnapshot = FixtureSnapshot(),
    before: str | None = "sha256:catalog",
    after: str | None = "sha256:catalog",
    static: bool = True,
) -> ExecutionEvidence:
    return ExecutionEvidence(
        REQUIRED,
        results,
        static,
        repository_identity(start),
        repository_identity(end),
        before,
        after,
        (),
    )


@spec_test(covers=("planning_gate#T2",))
def test_static_catalog_without_executions_is_not_execution_evidence() -> None:
    assert not evidence().accepted


@spec_test(covers=("planning_gate#T8",))
def test_missing_mandatory_step_is_rejected() -> None:
    assert not evidence((StepResult("scala-build", 0, 1),)).accepted


@spec_test(covers=("planning_gate#T3",))
def test_failed_or_timed_out_layer_is_rejected() -> None:
    failed = (StepResult("scala-build", 0, 1), StepResult("scala-unit", 2, 1))
    timed_out = (StepResult("scala-build", 0, 1), StepResult("scala-unit", None, 1, True))
    assert not evidence(failed).accepted
    assert not evidence(timed_out).accepted


@spec_test(covers=("planning_gate#T5",))
def test_repository_and_producer_digest_mismatches_are_rejected() -> None:
    complete = (StepResult("scala-build", 0, 1), StepResult("scala-unit", 0, 1))
    assert not evidence(complete, end=FixtureSnapshot(head="b" * 40)).accepted
    assert not evidence(complete, end=FixtureSnapshot(tracked={"tracked.txt": "sha256:changed"})).accepted
    assert not evidence(complete, after="sha256:changed").accepted


@spec_test(covers=("planning_gate#T5",))
def test_dirty_but_stable_complete_record_is_accepted_and_canonical() -> None:
    dirty = FixtureSnapshot(dirty_paths=("plans/planning_gate", "new file.txt"))
    record = evidence(
        (StepResult("scala-build", 0, 1), StepResult("scala-unit", 0, 2)),
        start=dirty,
        end=dirty,
    )
    assert record.accepted
    rendered = render(record)
    assert json.loads(rendered)["repository"]["start"]["dirty_paths"] == [
        "new file.txt", "plans/planning_gate"
    ]
    assert rendered == render(record)
    assert "\n" not in rendered
