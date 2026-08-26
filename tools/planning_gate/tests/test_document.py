from __future__ import annotations

import json
from pathlib import Path
import shutil
import subprocess

import pytest

from tools.planning_gate.document import (
    DocumentError,
    PlanFormat,
    load_plan,
    load_repository,
)


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
SCHEMA = REPOSITORY_ROOT / "plans/schema/plan-v1.schema.json"


def repository(tmp_path: Path) -> Path:
    root = tmp_path / "repository"
    (root / "plans/schema").mkdir(parents=True)
    shutil.copyfile(SCHEMA, root / "plans/schema/plan-v1.schema.json")
    return root


def valid_plan(ident: str = "example", **replacements: str) -> str:
    values = {
        "id": ident,
        "depends_on": "[]",
        "allowed_modules": "[tools/planning_gate]",
        "done_command": "[tools/planning-gate, done]",
        "extra": "",
        "body": "Narrative body without canonical claim declarations.\n",
    }
    values.update(replacements)
    return """---
schema: isabelle-mcp.plan/v1
id: {id}
title: Example plan
status: planned
depends_on: {depends_on}
allowed_modules: {allowed_modules}
invariants:
  - id: INV1
    statement: Parsing has no process side effects.
claims:
  - id: A1
    kind: assumption
    state: active
    statement: A valid plan parses.
    layers: [tooling-unit]
  - id: D1
    kind: design
    statement: YAML is the interchange format.
    category: design
  - id: T1
    kind: test
    statement: Invalid values are rejected.
    layers: [tooling-unit]
spec_refinements: []
done_command: {done_command}
{extra}---
{body}""".format(**values)


def write_plan(root: Path, ident: str, text: str) -> Path:
    path = root / "plans" / ident
    path.write_text(text, encoding="utf-8")
    return path


def test_valid_v1_plan_becomes_immutable_typed_document(tmp_path: Path) -> None:
    root = repository(tmp_path)
    path = write_plan(root, "example", valid_plan())

    document = load_plan(path, root, allow_legacy=False)

    assert document.format is PlanFormat.V1
    assert document.qualified_claim_ids == (
        "example#A1",
        "example#D1",
        "example#T1",
    )
    assert document.claims[0].layers == ("tooling-unit",)
    with pytest.raises(AttributeError):
        document.status = "done"  # type: ignore[misc]


@pytest.mark.parametrize(
    ("name", "text", "message"),
    [
        (
            "missing_frontmatter",
            "status: planned\n",
            "v1 YAML frontmatter is required",
        ),
        (
            "duplicate_key",
            valid_plan().replace("status: planned", "status: planned\nstatus: done"),
            "duplicate key 'status'",
        ),
        (
            "unknown_field",
            valid_plan(extra="surprise: true\n"),
            "Additional properties are not allowed",
        ),
        (
            "unsafe_yaml",
            valid_plan().replace(
                "title: Example plan", "title: !!python/object/apply:os.system ['false']"
            ),
            "invalid or unsafe YAML",
        ),
        (
            "shell_command",
            valid_plan(done_command="python3 -m tools.planning_gate done"),
            "is not of type 'array'",
        ),
        (
            "outside_path",
            valid_plan(allowed_modules="[../outside]"),
            "normalized repository-relative path",
        ),
        (
            "body_redeclaration",
            valid_plan(body="T1. This conflicts with frontmatter.\n"),
            "body redeclares canonical claim T1",
        ),
        (
            "kind_mismatch",
            valid_plan().replace("kind: assumption", "kind: test", 1),
            "claim A1 requires kind 'assumption'",
        ),
    ],
)
def test_invalid_v1_documents_are_rejected(
    tmp_path: Path, name: str, text: str, message: str
) -> None:
    root = repository(tmp_path)
    path = write_plan(root, name, text.replace("id: example", f"id: {name}", 1))

    with pytest.raises(DocumentError, match=message):
        load_plan(path, root, allow_legacy=False)


def test_repository_rejects_unknown_dependency(tmp_path: Path) -> None:
    root = repository(tmp_path)
    write_plan(root, "example", valid_plan(depends_on="[missing]"))

    with pytest.raises(DocumentError, match="unknown plan dependency 'missing'"):
        load_repository(root, allow_legacy=False)


def test_repository_rejects_dependency_cycle(tmp_path: Path) -> None:
    root = repository(tmp_path)
    write_plan(root, "first", valid_plan("first", depends_on="[second]"))
    write_plan(root, "second", valid_plan("second", depends_on="[first]"))

    with pytest.raises(DocumentError, match="first -> second -> first"):
        load_repository(root, allow_legacy=False)


def test_legacy_migration_preserves_claim_ids_and_statements(tmp_path: Path) -> None:
    root = repository(tmp_path)
    legacy = write_plan(
        root,
        "legacy",
        """status: planned

A1 [tooling-unit]: parsing preserves a statement
    across continuation lines.

T1 [tooling-unit]: malformed input is rejected.
""",
    )
    old = load_plan(legacy, root)
    legacy.unlink()
    migrated = write_plan(
        root,
        "legacy",
        valid_plan("legacy").replace(
            "A valid plan parses.", "parsing preserves a statement across continuation lines."
        ).replace("Invalid values are rejected.", "malformed input is rejected."),
    )
    new = load_plan(migrated, root, allow_legacy=False)

    assert old.qualified_claim_ids == ("legacy#A1", "legacy#T1")
    assert tuple(claim.statement for claim in old.claims) == (
        "parsing preserves a statement across continuation lines.",
        "malformed input is rejected.",
    )
    assert [claim.statement for claim in new.claims if claim.id in {"A1", "T1"}] == [
        claim.statement for claim in old.claims
    ]


def test_dump_payload_uses_relative_source_and_json_values(tmp_path: Path) -> None:
    root = repository(tmp_path)
    path = write_plan(root, "example", valid_plan())
    document = load_plan(path, root, allow_legacy=False)

    encoded = json.dumps(document.to_json(root), sort_keys=True)

    assert '"source": "plans/example"' in encoded
    assert str(root) not in encoded


def test_current_delivery_plans_load_without_side_effects() -> None:
    documents = load_repository(REPOSITORY_ROOT)
    formats = {document.id: document.format for document in documents}

    delivery_formats = {
        formats[name]
        for name in (
            "plan_format",
            "plan_label_schema",
            "verification_matrix",
            "python_e2e",
            "planning_gate",
        )
    }
    assert len(delivery_formats) == 1
    assert delivery_formats <= {PlanFormat.BOOTSTRAP, PlanFormat.V1}


def test_launcher_resolves_repository_outside_working_directory(tmp_path: Path) -> None:
    result = subprocess.run(
        [str(REPOSITORY_ROOT / "tools/planning-gate"), "plan", "check"],
        cwd=tmp_path,
        text=True,
        capture_output=True,
        check=False,
    )

    assert result.returncode == 0, result.stderr
    assert result.stdout.startswith("plan check: PASS")
