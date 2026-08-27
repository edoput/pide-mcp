from __future__ import annotations

from pathlib import Path

import pytest

from tools.planning_gate.document import load_repository
from tools.planning_gate.refinements import RefinementError, validate_refinements
from tools.planning_gate.tests.test_document import repository, write_plan
from tools.planning_gate.tests.test_labels import ACTIVE_A, canonical_plan
from tools.planning_gate.tooling import spec_test


def refinement_repository(tmp_path: Path, refinement: str, *, status: str = "planned") -> Path:
    root = repository(tmp_path)
    (root / "spec").write_text("heading\n=======\nid: S-fixture\n", encoding="utf-8")
    plan = canonical_plan("example", ACTIVE_A, status=status).replace(
        "spec_refinements: []", "spec_refinements:\n" + refinement
    )
    write_plan(root, "example", plan)
    return root


@spec_test(covers=("planning_gate#T1",))
def test_open_refinement_blocks_completion_but_remains_visible_during_planning(
    tmp_path: Path,
) -> None:
    value = """  - id: R1
    state: open
    statement: The spec needs a clarified behavior.
    spec_ids: [S-fixture]"""
    root = refinement_repository(tmp_path, value)
    documents = load_repository(root)

    assert validate_refinements(documents, root).open == ("example#R1",)
    with pytest.raises(RefinementError, match="completion blocked.*example#R1"):
        validate_refinements(documents, root, require_completion=True)

    done_root = refinement_repository(tmp_path / "done", value, status="done")
    with pytest.raises(RefinementError, match="completion blocked.*example#R1"):
        validate_refinements(load_repository(done_root), done_root)


@spec_test(covers=("planning_gate#T1",))
def test_folded_refinement_requires_date_and_every_target_must_resolve(
    tmp_path: Path,
) -> None:
    missing_date = """  - id: R1
    state: folded
    statement: The behavior was incorporated.
    spec_ids: [S-fixture]"""
    root = refinement_repository(tmp_path / "date", missing_date)
    with pytest.raises(RefinementError, match="requires folded_on"):
        validate_refinements(load_repository(root), root)

    unknown = missing_date.replace("S-fixture", "S-missing") + "\n    folded_on: '2026-08-26'"
    root = refinement_repository(tmp_path / "target", unknown)
    with pytest.raises(RefinementError, match="unknown spec IDs"):
        validate_refinements(load_repository(root), root)

    complete = missing_date + "\n    folded_on: '2026-08-26'"
    root = refinement_repository(tmp_path / "complete", complete)
    report = validate_refinements(load_repository(root), root, require_completion=True)
    assert report.folded == 1
    assert report.open == ()
