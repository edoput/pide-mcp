from __future__ import annotations

import json
from pathlib import Path

from tools.planning_gate.registry import (
    RegistryRow,
    collect,
    generate,
    render_text,
    stale_outputs,
)
from tools.planning_gate.tests.test_document import repository, valid_plan, write_plan


def test_generate_is_deterministic_and_freshness_detects_statement_drift(
    tmp_path: Path,
) -> None:
    root = repository(tmp_path)
    path = write_plan(root, "example", valid_plan())

    _, initially_stale = stale_outputs(root)
    assert {value.relative_to(root).as_posix() for value in initially_stale} == {
        "plans/ASSUMPTIONS",
    }

    first = generate(root)
    first_text = (root / "plans/ASSUMPTIONS").read_bytes()
    second = generate(root)
    assert first == second
    assert (root / "plans/ASSUMPTIONS").read_bytes() == first_text
    assert stale_outputs(root)[1] == ()

    path.write_text(
        valid_plan().replace("A valid plan parses.", "A changed statement parses."),
        encoding="utf-8",
    )
    _, changed = stale_outputs(root)
    assert tuple(value.relative_to(root).as_posix() for value in changed) == (
        "plans/ASSUMPTIONS",
    )


def test_legacy_to_v1_migration_preserves_registry_rows(tmp_path: Path) -> None:
    legacy_root = repository(tmp_path / "legacy")
    write_plan(
        legacy_root,
        "example",
        """status: planned

A1 [tooling-unit]: A valid plan parses.

T1 [tooling-unit]: Invalid values are rejected.
""",
    )
    legacy_rows = collect(legacy_root).rows

    v1_root = repository(tmp_path / "v1")
    write_plan(v1_root, "example", valid_plan())
    v1_rows = tuple(
        row for row in collect(v1_root).rows if row.id in {"example#A1", "example#T1"}
    )

    assert tuple((row.id, row.layer, row.statement) for row in legacy_rows) == tuple(
        (row.id, row.layer, row.statement) for row in v1_rows
    )


def test_rendered_registry_has_no_trailing_whitespace_after_truncation() -> None:
    row = RegistryRow(
        id="example#A1",
        layer="tooling-unit",
        statement="x" * 149 + " " + "truncated suffix",
        layer_origin="tag",
    )

    assert all(line == line.rstrip() for line in render_text((row,)).splitlines())


def test_current_catalog_keeps_prose_inference_explicit() -> None:
    root = Path(__file__).resolve().parents[3]
    report = collect(root)

    assert len(report.rows) == 322
    assert report.inferred_ids == (
        "find_definition#T6",
        "repl_remove#T5",
        "session_dirs_errors#A6",
    )


def test_connection_kernel_checkpoint_1_fixture_is_staged_noncoverage() -> None:
    root = Path(__file__).resolve().parents[3]
    path = root / "mcp/test/fixtures/connection_kernel_checkpoint_1.json"
    fixture = json.loads(path.read_text(encoding="utf-8"))

    assert fixture["schema"] == "isabelle-mcp.connection-kernel-characterization/v1"
    assert fixture["status"] == "non-coverage"
    assert fixture["checkpoint"] == 1
    assert [case["id"] for case in fixture["cases"]] == [
        "CK1-LIFECYCLE-PREINIT",
        "CK1-FRAMING-BATCH",
        "CK1-IDS-CORRELATION",
        "CK1-CANCELLATION-IGNORED",
        "CK1-CONCURRENCY-UNBOUNDED",
        "CK1-EOF-OWNERSHIP",
    ]

    def plan_links(value: object) -> list[str]:
        if isinstance(value, dict):
            own = [key for key in value if key in {"verifies", "covers"}]
            nested = [link for child in value.values() for link in plan_links(child)]
            return own + nested
        if isinstance(value, list):
            return [link for nested in value for link in plan_links(nested)]
        return []

    assert plan_links(fixture) == []
