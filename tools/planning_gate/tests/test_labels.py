from __future__ import annotations

import json
from pathlib import Path

import pytest

from tools.planning_gate.document import DocumentError, load_repository
from tools.planning_gate.labels import (
    audit_is_fresh,
    generate_audit,
    validate_labels,
)
from tools.planning_gate.tests.test_document import repository, write_plan
from tools.planning_gate.tooling import spec_test


def canonical_plan(ident: str, claims: str, *, status: str = "planned") -> str:
    return f"""---
schema: isabelle-mcp.plan/v1
id: {ident}
title: Label fixture
status: {status}
depends_on: []
allowed_modules: [tools/planning_gate]
invariants: []
claims:
{claims}
spec_refinements: []
done_command: [tools/planning-gate, done]
---

Fixture body.
"""


ACTIVE_A = """  - id: A1
    kind: assumption
    state: active
    statement: Active behavior.
    layers: [tooling-unit]"""


def validate_one(tmp_path: Path, claims: str, *, status: str = "planned"):
    root = repository(tmp_path)
    write_plan(root, "example", canonical_plan("example", claims, status=status))
    return validate_labels(load_repository(root))


def test_current_canonical_labels_are_semantically_valid() -> None:
    root = Path(__file__).resolve().parents[3]
    report = validate_labels(load_repository(root))

    assert report.v1_plans == 5
    assert report.claims == 57
    assert report.blockers == ()


@spec_test(verifies=("plan_label_schema#A1",), covers=("plan_label_schema#T4",))
def test_active_assumption_requires_a_layer(tmp_path: Path) -> None:
    claim = ACTIVE_A.replace("    layers: [tooling-unit]", "")

    with pytest.raises(DocumentError, match="ACTIVE A/I claim requires at least one layer"):
        validate_one(tmp_path, claim)


@spec_test(verifies=("plan_label_schema#A2",), covers=("plan_label_schema#T1",))
def test_moot_assumption_requires_rationale_and_forbids_layers(tmp_path: Path) -> None:
    missing_rationale = ACTIVE_A.replace("state: active", "state: moot").replace(
        "    layers: [tooling-unit]", ""
    )
    with pytest.raises(DocumentError, match="MOOT A/I claim requires a rationale"):
        validate_one(tmp_path / "missing", missing_rationale)

    false_layer = missing_rationale + "\n    rationale: Historical behavior is no longer required.\n    layers: [tooling-unit]"
    with pytest.raises(DocumentError, match="MOOT A/I claim cannot require test layers"):
        validate_one(tmp_path / "layer", false_layer)

    valid = missing_rationale + "\n    rationale: Historical behavior is no longer required."
    assert validate_one(tmp_path / "valid", valid).claims == 1


@spec_test(covers=("plan_label_schema#T2", "planning_gate#T1"))
def test_dependency_design_requires_a_resolvable_target(tmp_path: Path) -> None:
    dependency = """  - id: D1
    kind: design
    statement: Reuse shared infrastructure.
    category: dependency
    target: missing"""
    with pytest.raises(DocumentError, match="unknown dependency target 'missing'"):
        validate_one(tmp_path / "missing", dependency)

    root = repository(tmp_path / "valid")
    write_plan(root, "owner", canonical_plan("owner", ACTIVE_A))
    write_plan(
        root,
        "dependent",
        canonical_plan("dependent", dependency.replace("target: missing", "target: owner")),
    )
    assert validate_labels(load_repository(root)).claims == 2


@spec_test(covers=("plan_label_schema#T3", "planning_gate#T1"))
def test_open_question_is_a_completion_blocker_not_malformed_metadata(
    tmp_path: Path,
) -> None:
    question = """  - id: Q1
    kind: question
    state: open
    statement: Which implementation should own this behavior?"""
    root = repository(tmp_path)
    write_plan(root, "example", canonical_plan("example", question))
    documents = load_repository(root)

    report = validate_labels(documents)
    assert [(value.claim, value.reason) for value in report.blockers] == [
        ("Q1", "question is open")
    ]
    with pytest.raises(DocumentError, match="completion blocked by Q1"):
        validate_labels(documents, require_completion=True)

    write_plan(root, "example", canonical_plan("example", question, status="done"))
    with pytest.raises(DocumentError, match="completion blocked by Q1"):
        validate_labels(load_repository(root))


@spec_test(covers=("plan_label_schema#T3",))
def test_resolved_question_requires_date_and_resolution_or_target(tmp_path: Path) -> None:
    incomplete = """  - id: Q1
    kind: question
    state: resolved
    statement: Which implementation should own this behavior?"""
    with pytest.raises(DocumentError, match="RESOLVED Q requires resolved_on"):
        validate_one(tmp_path / "date", incomplete)

    complete = incomplete + "\n    resolved_on: '2026-08-26'\n    resolution: The document loader owns it."
    assert validate_one(tmp_path / "complete", complete).claims == 1


@spec_test(covers=("plan_label_schema#T5",))
def test_supersession_requires_same_kind_and_rejects_cycles(tmp_path: Path) -> None:
    claims = """  - id: A1
    kind: assumption
    state: superseded
    statement: Old behavior one.
    target: example#A2
  - id: A2
    kind: assumption
    state: superseded
    statement: Old behavior two.
    target: example#A1"""
    with pytest.raises(DocumentError, match="supersession cycle"):
        validate_one(tmp_path, claims)


@spec_test(covers=("plan_label_schema#T4",))
def test_test_claim_requires_active_state_and_layers(tmp_path: Path) -> None:
    claim = """  - id: T1
    kind: test
    state: active
    statement: Execute the check."""
    with pytest.raises(DocumentError, match="ACTIVE T claim requires at least one layer"):
        validate_one(tmp_path, claim)


@spec_test(covers=("plan_label_schema#T6",))
def test_legacy_audit_contains_only_semantic_decisions(tmp_path: Path) -> None:
    root = repository(tmp_path)
    write_plan(
        root,
        "legacy",
        """status: planned

A1. MOOT because the behavior was removed.

D1. The implementation uses a shared parser.

Q1. Which parser owns validation?

T1 [scala-unit]: explicit test requirement.
""",
    )
    documents = load_repository(root)

    assert audit_is_fresh(documents, root) == (False, 3)
    assert generate_audit(documents, root) == 3
    assert audit_is_fresh(documents, root) == (True, 3)

    payload = json.loads((root / "plans/migration/label-audit.json").read_text())
    by_id = {record["id"]: record for record in payload["records"]}
    assert set(by_id) == {"legacy#A1", "legacy#D1", "legacy#Q1"}
    assert by_id["legacy#A1"]["decisions"] == [
        "record-moot-or-superseded-lifecycle",
        "declare-required-layers-or-non-active-lifecycle",
    ]
    assert by_id["legacy#D1"]["line"] > 0


@spec_test(covers=("plan_label_schema#T2", "plan_label_schema#T6"))
def test_design_layer_requires_removing_metadata_or_converting_kind(
    tmp_path: Path,
) -> None:
    design = """  - id: D1
    kind: design
    statement: A design record is structural.
    category: design
    layers: [scala-unit]"""
    with pytest.raises(DocumentError, match="design claim cannot set layers"):
        validate_one(tmp_path, design)


@spec_test(verifies=("plan_label_schema#I1",), covers=("plan_label_schema#T5",))
def test_alias_has_one_owner_and_cannot_shadow_a_canonical_id(tmp_path: Path) -> None:
    claims = """  - id: A1
    kind: assumption
    state: active
    statement: First behavior.
    layers: [tooling-unit]
    aliases: [legacy#D1]
  - id: A2
    kind: assumption
    state: active
    statement: Second behavior.
    layers: [tooling-unit]
    aliases: [legacy#D1]"""
    with pytest.raises(DocumentError, match="alias 'legacy#D1' is already owned"):
        validate_one(tmp_path, claims)
