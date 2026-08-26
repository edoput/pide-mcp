from __future__ import annotations

import json
from pathlib import Path

import pytest

from tools.planning_gate.document import load_repository
from tools.planning_gate.matrix import (
    LayerRegistry,
    MatrixError,
    assemble,
    manifest_from_json,
    theory_producer,
    tooling_producer,
)
from tools.planning_gate.tests.test_document import repository, write_plan
from tools.planning_gate.tests.test_labels import canonical_plan
from tools.planning_gate.tooling import spec_test


def layers(*names: str) -> LayerRegistry:
    roles = {f"role_{index}": name for index, name in enumerate(names)}
    producers = {name: "fixture/producer" for name in names}
    return LayerRegistry(roles, producers, "sha256:fixture")


def manifest(*tests: dict, producer: str = "fixture/producer"):
    return manifest_from_json(
        {
            "schema": "isabelle-mcp.verification-producer/v1",
            "producer": producer,
            "artifact": {"fixture": "true"},
            "tests": list(tests),
        }
    )


def fixture_record(
    identity: str,
    layer: str,
    *,
    verifies: list[str] | None = None,
    covers: list[str] | None = None,
) -> dict:
    return {
        "identity": identity,
        "name": identity,
        "layer": layer,
        "location": {"path": "tests/fixture.py", "line": 1},
        "verifies": verifies or [],
        "covers": covers or [],
    }


def claims_plan(ident: str = "example") -> str:
    claims = """  - id: A1
    kind: assumption
    state: active
    statement: Observable behavior.
    layers: [first]
  - id: T1
    kind: test
    state: active
    statement: Verify both layers.
    layers: [first, second]"""
    return canonical_plan(ident, claims)


@spec_test(
    verifies=("verification_matrix#I1",),
    covers=("verification_matrix#T1",),
)
def test_matrix_rejects_unknown_ids_relation_mismatches_and_duplicate_identity(
    tmp_path: Path,
) -> None:
    root = repository(tmp_path)
    write_plan(root, "example", claims_plan())
    documents = load_repository(root)
    registry = layers("first", "second")

    with pytest.raises(MatrixError, match="unknown plan claim"):
        assemble(
            documents,
            registry,
            [manifest(fixture_record("unknown", "first", covers=["missing#T1"]))],
        )
    with pytest.raises(MatrixError, match="covers cannot target assumption"):
        assemble(
            documents,
            registry,
            [manifest(fixture_record("wrong", "first", covers=["example#A1"]))],
        )
    duplicated = fixture_record("same", "first", verifies=["example#A1"])
    with pytest.raises(MatrixError, match="duplicate test identities"):
        manifest(duplicated, duplicated)
    with pytest.raises(MatrixError, match="duplicate plan link"):
        manifest(fixture_record("links", "first", verifies=["example#A1", "example#A1"]))
    with pytest.raises(MatrixError, match="cannot satisfy"):
        assemble(
            documents,
            registry,
            [manifest(fixture_record("wrong-layer", "second", verifies=["example#A1"]))],
        )


@spec_test(covers=("verification_matrix#T2",))
def test_active_assumption_missing_required_layer_is_reported(tmp_path: Path) -> None:
    root = repository(tmp_path)
    write_plan(root, "example", claims_plan())

    result = assemble(load_repository(root), layers("first", "second"), [manifest()])

    assert ("example#A1", "verifies", "first") in {
        (value.id, value.relation, value.layer) for value in result.missing_coverage
    }


@spec_test(covers=("verification_matrix#T3",))
def test_multilayer_requirement_needs_distinct_cases_in_each_layer(tmp_path: Path) -> None:
    root = repository(tmp_path)
    write_plan(root, "example", claims_plan())
    documents = load_repository(root)
    registry = layers("first", "second")

    partial = assemble(
        documents,
        registry,
        [manifest(fixture_record("first-case", "first", covers=["example#T1"]))],
    )
    assert ("example#T1", "second") in {
        (value.id, value.layer) for value in partial.missing_coverage
    }

    complete = assemble(
        documents,
        registry,
        [
            manifest(
                fixture_record("first-case", "first", covers=["example#T1"]),
                fixture_record("second-case", "second", covers=["example#T1"]),
            )
        ],
    )
    assert not [value for value in complete.missing_coverage if value.id == "example#T1"]


@spec_test(covers=("verification_matrix#T4",))
def test_matrix_output_is_stable_under_test_and_manifest_order(tmp_path: Path) -> None:
    root = repository(tmp_path)
    write_plan(root, "example", claims_plan())
    documents = load_repository(root)
    registry = LayerRegistry(
        {"one": "first", "two": "second"},
        {"first": "fixture/a", "second": "fixture/b"},
        "sha256:fixture",
    )
    first = manifest(
        fixture_record("a", "first", verifies=["example#A1"], covers=["example#T1"]),
        producer="fixture/a",
    )
    second = manifest(
        fixture_record("b", "second", covers=["example#T1"]), producer="fixture/b"
    )

    a = json.dumps(assemble(documents, registry, [first, second]).to_json(), sort_keys=True)
    b = json.dumps(assemble(documents, registry, [second, first]).to_json(), sort_keys=True)
    assert a == b


@spec_test(covers=("verification_matrix#T5",))
def test_missing_producer_and_unknown_layer_are_not_waived(tmp_path: Path) -> None:
    root = repository(tmp_path)
    write_plan(root, "example", claims_plan())
    documents = load_repository(root)
    registry = LayerRegistry(
        {"one": "first", "two": "second"},
        {"first": "fixture/producer", "second": "fixture/missing"},
        "sha256:fixture",
    )

    result = assemble(documents, registry, [manifest()])
    assert result.missing_producers == ("fixture/missing",)

    with pytest.raises(MatrixError, match="unknown layer"):
        assemble(
            documents,
            registry,
            [manifest(fixture_record("bad", "third", verifies=["example#A1"]))],
        )


@spec_test(
    verifies=("verification_matrix#A1", "verification_matrix#A2"),
    covers=("verification_matrix#T4",),
)
def test_tooling_discovery_is_body_free_and_deterministic(tmp_path: Path) -> None:
    root = tmp_path / "repository"
    tests = root / "tools/planning_gate/tests"
    tests.mkdir(parents=True)
    source = tests / "test_sentinel.py"
    source.write_text(
        """from tools.planning_gate.tooling import spec_test

raise RuntimeError("discovery executed this module")

@spec_test(covers=("example#T1",))
def test_sentinel():
    raise RuntimeError("discovery executed this body")
""",
        encoding="utf-8",
    )

    first = tooling_producer(root)
    second = tooling_producer(root)
    assert first == second
    assert first.tests[0].links[0].id == "example#T1"


@spec_test(verifies=("verification_matrix#A2", "verification_matrix#I1"))
def test_theory_discovery_uses_checked_citations_without_loading_theory(
    tmp_path: Path,
) -> None:
    root = tmp_path / "repository"
    theory = root / "mcp/Tools/HOL/Tests/Fixture.thy"
    theory.parent.mkdir(parents=True)
    theory.write_text(
        r"""theory Fixture imports Main begin

section \<open>\<^assumption>\<open>example#T1\<close> registered case\<close>
ML \<open>raise Fail \"discovery must not load this theory\"\<close>

end
""",
        encoding="utf-8",
    )

    producer = theory_producer(root)

    assert producer.tests[0].source == "mcp/Tools/HOL/Tests/Fixture.thy"
    assert producer.tests[0].line == 3
    assert producer.tests[0].links[0].relation == "covers"
    assert producer.tests[0].links[0].id == "example#T1"
