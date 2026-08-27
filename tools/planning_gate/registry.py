"""Generate the compatibility claim registry from canonical plan documents."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

from .document import PlanDocument, load_repository
from .files import write_atomic_text


LAYER_WIDTH = 24


@dataclass(frozen=True)
class RegistryRow:
    id: str
    layer: str
    statement: str
    layer_origin: str


@dataclass(frozen=True)
class RegistryReport:
    rows: tuple[RegistryRow, ...]
    plans_without_claims: tuple[str, ...]

    @property
    def inferred_ids(self) -> tuple[str, ...]:
        return tuple(row.id for row in self.rows if row.layer_origin == "prose")

    def count_origin(self, origin: str) -> int:
        return sum(row.layer_origin == origin for row in self.rows)


def collect(root: Path) -> RegistryReport:
    documents = load_repository(root)
    rows: list[RegistryRow] = []
    empty: list[str] = []
    for document in documents:
        if not document.claims:
            empty.append(document.id)
        for claim in document.claims:
            rows.append(
                RegistryRow(
                    id=f"{document.id}#{claim.id}",
                    layer=",".join(sorted(claim.layers)) or "unstated",
                    statement=claim.statement,
                    layer_origin=claim.layer_origin,
                )
            )
    return RegistryReport(tuple(rows), tuple(empty))


def render_text(rows: Iterable[RegistryRow]) -> str:
    rows = tuple(rows)
    width = max((len(row.id) for row in rows), default=len("ID"))
    body = [
        "assumption registry (GENERATED -- do not edit)",
        "=============================================",
        "",
        "Regenerate:  tools/planning-gate registry generate",
        "Check:       tools/planning-gate registry check",
        "",
        "One row per assumption DECLARED in a plan file. The ID is the address a",
        "test cites: MUnit cases attach structured tags, and Isabelle theories",
        "declare spec_test metadata. An ID that no test cites is",
        "an unchecked assumption; the gate reports the count.",
        "",
        f"{'ID'.ljust(width)}  {'LAYER'.ljust(LAYER_WIDTH)}  STATEMENT",
        f"{'-' * width}  {'-' * LAYER_WIDTH}  ---------",
    ]
    for row in rows:
        body.append(
            f"{row.id.ljust(width)}  {row.layer.ljust(LAYER_WIDTH)}  "
            f"{row.statement[:150].rstrip()}"
        )
    return "\n".join(body) + "\n"


def expected_outputs(root: Path) -> tuple[RegistryReport, dict[Path, str]]:
    report = collect(root)
    outputs = {
        root / "plans/ASSUMPTIONS": render_text(report.rows),
    }
    return report, outputs


def stale_outputs(root: Path) -> tuple[RegistryReport, tuple[Path, ...]]:
    report, expected = expected_outputs(root)
    stale = tuple(
        path for path, value in expected.items()
        if not path.exists() or path.read_text(encoding="utf-8") != value
    )
    return report, stale


def generate(root: Path) -> RegistryReport:
    report, outputs = expected_outputs(root)
    for path, value in outputs.items():
        write_atomic_text(path, value)
    return report


def format_report(report: RegistryReport) -> str:
    lines = [
        f"{len(report.rows)} claims across "
        f"{len({row.id.split('#', 1)[0] for row in report.rows})} plans"
    ]
    if report.plans_without_claims:
        lines.append(
            f"plans declaring NO labelled claim ({len(report.plans_without_claims)}): "
            + ", ".join(report.plans_without_claims)
        )
    lines.append(
        "layer source: "
        f"{report.count_origin('tag')} from an explicit [tag], "
        f"{report.count_origin('prose')} inferred from prose, "
        f"{report.count_origin('none')} unstated"
    )
    if report.inferred_ids:
        lines.append(
            "  layers INFERRED from prose (a guess -- give these an explicit "
            "[tag] to pin them):\n    " + ", ".join(report.inferred_ids)
        )
    return "\n".join(lines)
