"""Lifecycle and target validation for plan-owned specification refinements."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import re
from typing import Iterable

from .document import PlanDocument, PlanFormat


SPEC_ID_RE = re.compile(
    r"^(?:D-(?:[0-9]{4}-[0-9]{2}-[0-9]{2}|undated)|S)-[a-z0-9-]+$"
)
SPEC_ID_LINE_RE = re.compile(r"^id:\s*(\S+)\s*$")


class RefinementError(ValueError):
    pass


@dataclass(frozen=True)
class RefinementReport:
    refinements: int
    open: tuple[str, ...]
    folded: int


def spec_ids(root: Path) -> frozenset[str]:
    path = root / "spec"
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as ex:
        raise RefinementError(f"cannot read {path}: {ex}") from ex
    result = [match.group(1) for line in lines if (match := SPEC_ID_LINE_RE.fullmatch(line))]
    malformed = sorted(value for value in result if not SPEC_ID_RE.fullmatch(value))
    if malformed:
        raise RefinementError(f"spec contains malformed section IDs: {malformed}")
    duplicates = sorted({value for value in result if result.count(value) > 1})
    if duplicates:
        raise RefinementError(f"spec contains duplicate section IDs: {duplicates}")
    return frozenset(result)


def validate_refinements(
    documents: Iterable[PlanDocument],
    root: Path,
    *,
    require_completion: bool = False,
) -> RefinementReport:
    documents = tuple(documents)
    known_spec_ids = spec_ids(root)
    open_refinements: list[str] = []
    folded = 0
    total = 0

    for document in documents:
        if document.format is not PlanFormat.V1:
            continue
        for refinement in document.spec_refinements:
            total += 1
            qualified = f"{document.id}#{refinement.id}"
            unknown = sorted(set(refinement.spec_ids) - known_spec_ids)
            if unknown:
                raise RefinementError(
                    f"{document.path}: {qualified} cites unknown spec IDs: {unknown}"
                )
            if refinement.state == "open":
                if refinement.folded_on is not None:
                    raise RefinementError(
                        f"{document.path}: open {qualified} cannot carry folded_on"
                    )
                open_refinements.append(qualified)
            elif refinement.state == "folded":
                if refinement.folded_on is None:
                    raise RefinementError(
                        f"{document.path}: folded {qualified} requires folded_on"
                    )
                folded += 1
            else:  # protected by the JSON schema, kept for direct callers
                raise RefinementError(
                    f"{document.path}: {qualified} has unknown state {refinement.state!r}"
                )

    blocking = [
        value
        for value in open_refinements
        if require_completion
        or next(document for document in documents if document.id == value.split("#", 1)[0]).status
        == "done"
    ]
    if blocking:
        raise RefinementError(
            "completion blocked by open spec refinements: " + ", ".join(blocking)
        )
    return RefinementReport(total, tuple(sorted(open_refinements)), folded)
