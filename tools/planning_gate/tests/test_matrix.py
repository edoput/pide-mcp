from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path

import pytest

from tools.planning_gate.document import load_repository
from tools.planning_gate.matrix import (
    LayerRegistry,
    MatrixError,
    assemble,
    manifest_from_json,
    munit_producer,
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


@spec_test(covers=("planning_gate#T7",))
def test_munit_adapter_keeps_functional_and_performance_classes_distinct(
    tmp_path: Path,
) -> None:
    root = tmp_path / "repository"
    path = root / "mcp_test/lib/munit-spec.json"
    path.parent.mkdir(parents=True)

    def write(test_class: str) -> None:
        path.write_text(
            json.dumps(
                {
                    "schema_version": 2,
                    "producer": "isabelle-mcp/munit",
                    "tests": [
                        {
                            "suite": "fixture.Suite",
                            "name": f"{test_class} case",
                            "layer": "scala-unit",
                            "test_class": test_class,
                            "location": {"path": "mcp_test/src/fixture.scala", "line": 1},
                            "links": [],
                        }
                    ],
                }
            ),
            encoding="utf-8",
        )

    for test_class in ("functional", "performance"):
        write(test_class)
        assert munit_producer(root, path).tests[0].name == f"{test_class} case"

    write("benchmark")
    with pytest.raises(MatrixError, match="test_class"):
        munit_producer(root, path)


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
    covers=("verification_matrix#T1", "planning_gate#T1"),
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


def write_theory_manifest(root: Path) -> tuple[Path, dict, tuple[Path, ...]]:
    layers_path = root / "mcp_test/etc/test_layers.json"
    layers_path.parent.mkdir(parents=True)
    layers_path.write_text(
        json.dumps(
            {
                "schema_version": 1,
                "layers": {"ml_unit_tests": "ml-unit"},
                "producers": {"ml-unit": "isabelle-mcp/isabelle-theory"},
            },
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )

    sources = (
        root / "mcp/Tools/Tests/Tool_Test.thy",
        root / "mcp/Tools/HOL/Tests/HOL_Test.thy",
    )
    for source in sources:
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text(
            "theory Fixture imports Main\nbegin\nML \\<open>val _ = ()\\<close>\nend\n",
            encoding="utf-8",
        )

    omitted = root / "mcp/Tools/HOL/Tests/Omitted_Test.thy"
    omitted.write_text(
        r"""theory Omitted_Test imports Main begin
section \<open>\<^assumption>\<open>example#T9\<close> citation only\<close>
spec_test \<open>omitted by aggregate import\<close> covers \<open>example#T9\<close>
end
""",
        encoding="utf-8",
    )

    sessions = ("MCP-Tools-Tests", "MCP-HOL-Tests")
    theories = ("MCP-Tools-Tests.Tool_Test", "MCP-HOL-Tests.HOL_Test")
    theory_records = []
    test_records = []
    for index, (session, theory, source) in enumerate(zip(sessions, theories, sources)):
        relative = source.relative_to(root).as_posix()
        digest = "sha1:" + hashlib.sha1(source.read_bytes()).hexdigest()
        theory_records.append(
            {
                "session": session,
                "theory": theory,
                "source": {"path": relative, "sha1": digest},
            }
        )
        test_records.append(
            {
                "session": session,
                "theory": theory,
                "name": f"registered {index}",
                "location": {"path": relative, "line": 3},
                "layer": "ml-unit",
                "links": [
                    {
                        "relation": "verifies" if index == 0 else "covers",
                        "id": "example#A1" if index == 0 else "example#T1",
                    }
                ],
            }
        )
    value = {
        "schema_version": 1,
        "producer": "isabelle-mcp/isabelle",
        "framework": "isabelle",
        "export_name": "mcp/spec-tests",
        "test_layers_sha256": "sha256:" + hashlib.sha256(layers_path.read_bytes()).hexdigest(),
        "sessions": list(sessions),
        "theories": theory_records,
        "tests": test_records,
    }
    manifest_path = root / "mcp_test/lib/isabelle-spec.json"
    manifest_path.parent.mkdir(parents=True)
    manifest_path.write_text(json.dumps(value), encoding="utf-8")
    return manifest_path, value, sources


@spec_test(
    verifies=("verification_matrix#A2", "verification_matrix#I1"),
    covers=("verification_matrix#T8",),
)
def test_theory_discovery_consumes_only_structured_manifest_records(
    tmp_path: Path,
) -> None:
    root = tmp_path / "repository"
    manifest_path, _, _ = write_theory_manifest(root)

    producer = theory_producer(root, manifest_path)

    assert [test.identity for test in producer.tests] == [
        "MCP-HOL-Tests::MCP-HOL-Tests.HOL_Test::registered 1",
        "MCP-Tools-Tests::MCP-Tools-Tests.Tool_Test::registered 0",
    ]
    assert {test.links[0].relation for test in producer.tests} == {"covers", "verifies"}
    assert not any("Omitted_Test" in test.source for test in producer.tests)
    assert "Omitted_Test" not in producer.artifact["theory_sources"]


@spec_test(covers=("verification_matrix#T8",))
def test_theory_manifest_rejects_incomplete_stale_and_mistyped_records(
    tmp_path: Path,
) -> None:
    root = tmp_path / "repository"
    manifest_path, original, sources = write_theory_manifest(root)

    missing_session = copy.deepcopy(original)
    missing_session["sessions"] = ["MCP-Tools-Tests"]
    manifest_path.write_text(json.dumps(missing_session), encoding="utf-8")
    with pytest.raises(MatrixError, match="configured sessions"):
        theory_producer(root, manifest_path)

    duplicate = copy.deepcopy(original)
    duplicate["tests"].append(copy.deepcopy(duplicate["tests"][0]))
    manifest_path.write_text(json.dumps(duplicate), encoding="utf-8")
    with pytest.raises(MatrixError, match="duplicates test identity"):
        theory_producer(root, manifest_path)

    mistyped = copy.deepcopy(original)
    mistyped["tests"][0]["links"][0] = {
        "relation": "verifies",
        "id": "example#D1",
    }
    manifest_path.write_text(json.dumps(mistyped), encoding="utf-8")
    with pytest.raises(MatrixError, match="verifies cannot target"):
        theory_producer(root, manifest_path)

    manifest_path.write_text(json.dumps(original), encoding="utf-8")
    sources[0].write_text("theory Changed imports Main begin end\n", encoding="utf-8")
    with pytest.raises(
        MatrixError,
        match="changed after its Isabelle export was built; run tools/planning-gate theory-catalog",
    ):
        theory_producer(root, manifest_path)
