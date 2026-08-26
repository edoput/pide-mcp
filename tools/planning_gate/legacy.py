"""Reviewed legacy-debt baseline for missing verification-matrix links."""

from __future__ import annotations

import csv
from dataclasses import dataclass
import io
from pathlib import Path
import re
from typing import Iterable

from .files import write_atomic_text
from .matrix import MatrixResult, MissingCoverage


HEADER = ("plan", "id", "relation", "missing_layer", "baseline_revision")
REVISION_RE = re.compile(r"^[0-9a-f]{40}$")
LABEL_RE = re.compile(r"^[AIT][0-9]+$")


class LegacyDebtError(ValueError):
    pass


@dataclass(frozen=True, order=True)
class DebtKey:
    plan: str
    id: str
    relation: str
    missing_layer: str

    @classmethod
    def from_missing(cls, value: MissingCoverage) -> "DebtKey":
        plan, label = value.id.split("#", 1)
        return cls(plan, label, value.relation, value.layer)


@dataclass(frozen=True)
class DebtRow:
    key: DebtKey
    baseline_revision: str


@dataclass(frozen=True)
class RatchetResult:
    current_work: tuple[DebtKey, ...]
    unexpected: tuple[DebtKey, ...]
    resolved: tuple[DebtKey, ...]

    @property
    def accepted(self) -> bool:
        return not self.current_work and not self.unexpected and not self.resolved


def _validate_key(key: DebtKey, where: str) -> None:
    if not re.fullmatch(r"[a-z][a-z0-9_]*", key.plan):
        raise LegacyDebtError(f"{where}: malformed plan identifier {key.plan!r}")
    if not LABEL_RE.fullmatch(key.id):
        raise LegacyDebtError(f"{where}: only A, I, and T claims may be legacy debt")
    expected = "covers" if key.id.startswith("T") else "verifies"
    if key.relation != expected:
        raise LegacyDebtError(
            f"{where}: {key.id} requires relation {expected!r}, not {key.relation!r}"
        )
    if not key.missing_layer or any(character.isspace() for character in key.missing_layer):
        raise LegacyDebtError(f"{where}: malformed missing layer {key.missing_layer!r}")


def parse_baseline(text: str, where: str = "legacy baseline") -> tuple[DebtRow, ...]:
    try:
        reader = csv.DictReader(io.StringIO(text), strict=True)
        if tuple(reader.fieldnames or ()) != HEADER:
            raise LegacyDebtError(f"{where}: expected CSV header {','.join(HEADER)}")
        rows: list[DebtRow] = []
        for line, raw in enumerate(reader, 2):
            if None in raw or any(value is None for value in raw.values()):
                raise LegacyDebtError(f"{where}:{line}: malformed CSV row")
            key = DebtKey(
                raw["plan"], raw["id"], raw["relation"], raw["missing_layer"]
            )
            _validate_key(key, f"{where}:{line}")
            revision = raw["baseline_revision"]
            if not REVISION_RE.fullmatch(revision):
                raise LegacyDebtError(
                    f"{where}:{line}: baseline_revision must be a full Git object ID"
                )
            rows.append(DebtRow(key, revision))
    except csv.Error as ex:
        raise LegacyDebtError(f"{where}: malformed CSV: {ex}") from ex
    keys = [row.key for row in rows]
    if len(keys) != len(set(keys)):
        raise LegacyDebtError(f"{where}: duplicate legacy-debt row")
    if keys != sorted(keys):
        raise LegacyDebtError(f"{where}: rows must be sorted")
    return tuple(rows)


def read_baseline(path: Path) -> tuple[DebtRow, ...]:
    try:
        return parse_baseline(path.read_text(encoding="utf-8"), str(path))
    except OSError as ex:
        raise LegacyDebtError(f"cannot load {path}: {ex}") from ex


def render_baseline(missing: Iterable[MissingCoverage], revision: str) -> str:
    if not REVISION_RE.fullmatch(revision):
        raise LegacyDebtError("baseline revision must be a full Git object ID")
    keys = sorted(DebtKey.from_missing(value) for value in missing)
    if len(keys) != len(set(keys)):
        raise LegacyDebtError("matrix contains duplicate missing-coverage rows")
    stream = io.StringIO(newline="")
    writer = csv.writer(stream, lineterminator="\n")
    writer.writerow(HEADER)
    for key in keys:
        _validate_key(key, "matrix")
        writer.writerow(
            (key.plan, key.id, key.relation, key.missing_layer, revision)
        )
    return stream.getvalue()


def generate_baseline(path: Path, matrix: MatrixResult, revision: str) -> int:
    if path.exists():
        raise LegacyDebtError(f"{path} already exists; the legacy baseline is generated once")
    if matrix.missing_producers:
        names = ", ".join(matrix.missing_producers)
        raise LegacyDebtError(f"cannot baseline while producers are missing: {names}")
    legacy_missing = tuple(value for value in matrix.missing_coverage if value.legacy)
    rendered = render_baseline(legacy_missing, revision)
    write_atomic_text(path, rendered)
    return len(legacy_missing)


def compare(matrix: MatrixResult, baseline: Iterable[DebtRow]) -> RatchetResult:
    if matrix.missing_producers:
        names = ", ".join(matrix.missing_producers)
        raise LegacyDebtError(f"cannot check debt while producers are missing: {names}")
    current_work = {
        DebtKey.from_missing(value) for value in matrix.missing_coverage if not value.legacy
    }
    current_legacy = {
        DebtKey.from_missing(value) for value in matrix.missing_coverage if value.legacy
    }
    reviewed = {row.key for row in baseline}
    return RatchetResult(
        current_work=tuple(sorted(current_work)),
        unexpected=tuple(sorted(current_legacy - reviewed)),
        resolved=tuple(sorted(reviewed - current_legacy)),
    )


def require_accepted(result: RatchetResult) -> None:
    if result.current_work:
        values = ", ".join(
            f"{row.plan}#{row.id}:{row.relation}:{row.missing_layer}"
            for row in result.current_work
        )
        raise LegacyDebtError(f"current-plan coverage is missing: {values}")
    if result.unexpected:
        values = ", ".join(
            f"{row.plan}#{row.id}:{row.relation}:{row.missing_layer}"
            for row in result.unexpected
        )
        raise LegacyDebtError(f"new unreviewed legacy debt: {values}")
    if result.resolved:
        values = ", ".join(
            f"{row.plan}#{row.id}:{row.relation}:{row.missing_layer}"
            for row in result.resolved
        )
        raise LegacyDebtError(f"remove resolved legacy-debt rows: {values}")
