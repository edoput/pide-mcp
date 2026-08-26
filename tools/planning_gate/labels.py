"""Kind-specific plan-claim semantics and bounded legacy migration audit."""

from __future__ import annotations

from dataclasses import asdict, dataclass
import json
from pathlib import Path
import re
from typing import Iterable

from .document import Claim, DocumentError, PlanDocument, PlanFormat
from .files import write_atomic_text


QUALIFIED_ID_RE = re.compile(r"^([a-z][a-z0-9_]*)#([AITDQ][0-9]+)$")
ADR_ID_RE = re.compile(r"^ADR-[A-Za-z0-9][A-Za-z0-9._-]*$")
LIFECYCLE_MARKER_RE = re.compile(r"\b(MOOT|SUPERSEDED)\b", re.IGNORECASE)


@dataclass(frozen=True)
class CompletionBlocker:
    plan: str
    claim: str
    reason: str


@dataclass(frozen=True)
class LabelReport:
    v1_plans: int
    legacy_plans: int
    claims: int
    blockers: tuple[CompletionBlocker, ...]


@dataclass(frozen=True)
class AuditRecord:
    id: str
    kind: str
    source: str
    line: int
    statement: str
    layers: tuple[str, ...]
    layer_origin: str
    decisions: tuple[str, ...]


def _error(document: PlanDocument, claim: Claim, message: str, field: str) -> None:
    raise DocumentError(document.path, message, f"claims.{claim.id}.{field}")


def _forbid(
    document: PlanDocument, claim: Claim, names: Iterable[str]
) -> None:
    for name in names:
        value = getattr(claim, name)
        if value not in (None, (), ""):
            _error(document, claim, f"{claim.kind} claim cannot set {name}", name)


def _validate_assumption(document: PlanDocument, claim: Claim) -> None:
    if claim.state not in {"active", "moot", "superseded"}:
        _error(document, claim, "A/I state must be active, moot, or superseded", "state")
    _forbid(document, claim, ("category", "resolution", "resolved_on"))
    if claim.state == "active":
        if not claim.layers:
            _error(document, claim, "ACTIVE A/I claim requires at least one layer", "layers")
        _forbid(document, claim, ("target", "rationale"))
    elif claim.state == "moot":
        if not claim.rationale:
            _error(document, claim, "MOOT A/I claim requires a rationale", "rationale")
        if claim.layers:
            _error(document, claim, "MOOT A/I claim cannot require test layers", "layers")
        _forbid(document, claim, ("target",))
    else:
        if not claim.target:
            _error(document, claim, "SUPERSEDED A/I claim requires a target", "target")
        if claim.layers:
            _error(document, claim, "SUPERSEDED A/I claim cannot require test layers", "layers")


def _validate_design(document: PlanDocument, claim: Claim) -> None:
    if claim.category not in {"design", "detail", "dependency"}:
        _error(document, claim, "D claim requires design, detail, or dependency category", "category")
    _forbid(document, claim, ("state", "layers", "rationale", "resolution", "resolved_on"))
    if claim.category == "dependency" and not claim.target:
        _error(document, claim, "DEPENDENCY D claim requires a target", "target")


def _validate_question(document: PlanDocument, claim: Claim) -> None:
    if claim.state not in {"open", "resolved"}:
        _error(document, claim, "Q state must be open or resolved", "state")
    _forbid(document, claim, ("layers", "category", "rationale"))
    if claim.state == "open":
        _forbid(document, claim, ("target", "resolution", "resolved_on"))
    else:
        if not claim.resolved_on:
            _error(document, claim, "RESOLVED Q requires resolved_on", "resolved_on")
        if not (claim.resolution or claim.target):
            _error(document, claim, "RESOLVED Q requires resolution or target", "resolution")


def _validate_test(document: PlanDocument, claim: Claim) -> None:
    if claim.state != "active":
        _error(document, claim, "T state must be active", "state")
    if not claim.layers:
        _error(document, claim, "ACTIVE T claim requires at least one layer", "layers")
    _forbid(
        document,
        claim,
        ("category", "target", "rationale", "resolution", "resolved_on"),
    )


def validate_labels(
    documents: Iterable[PlanDocument], *, require_completion: bool = False
) -> LabelReport:
    documents = tuple(documents)
    v1 = tuple(document for document in documents if document.format is PlanFormat.V1)
    legacy = tuple(document for document in documents if document.format is not PlanFormat.V1)
    canonical = {
        f"{document.id}#{claim.id}": (document, claim)
        for document in v1
        for claim in document.claims
    }
    aliases: dict[str, str] = {}
    blockers: list[CompletionBlocker] = []

    for qualified, (document, claim) in canonical.items():
        if claim.kind in {"assumption", "infrastructure"}:
            _validate_assumption(document, claim)
        elif claim.kind == "design":
            _validate_design(document, claim)
        elif claim.kind == "question":
            _validate_question(document, claim)
            if claim.state == "open":
                blockers.append(CompletionBlocker(document.id, claim.id, "question is open"))
        elif claim.kind == "test":
            _validate_test(document, claim)

        for alias in claim.aliases:
            if not QUALIFIED_ID_RE.fullmatch(alias):
                _error(document, claim, f"malformed qualified alias {alias!r}", "aliases")
            if alias in canonical:
                _error(document, claim, f"alias {alias!r} collides with a canonical claim", "aliases")
            previous = aliases.get(alias)
            if previous:
                _error(document, claim, f"alias {alias!r} is already owned by {previous}", "aliases")
            aliases[alias] = qualified

    for qualified, (document, claim) in canonical.items():
        if not claim.target:
            continue
        if claim.kind in {"assumption", "infrastructure"}:
            target = canonical.get(claim.target)
            if not target:
                _error(document, claim, f"unknown supersession target {claim.target!r}", "target")
            if target[1].kind != claim.kind:
                _error(document, claim, "supersession target must have the same kind", "target")
        elif claim.kind == "design" and claim.category == "dependency":
            if claim.target not in {value.id for value in documents} and claim.target not in canonical:
                _error(document, claim, f"unknown dependency target {claim.target!r}", "target")
        elif claim.kind == "question":
            if ADR_ID_RE.fullmatch(claim.target):
                continue
            target = canonical.get(claim.target)
            if not target or target[1].kind not in {"assumption", "design", "test"}:
                _error(document, claim, f"unknown or invalid resolution target {claim.target!r}", "target")

    supersession = {
        qualified: claim.target
        for qualified, (_, claim) in canonical.items()
        if claim.state == "superseded" and claim.target
    }
    for start in supersession:
        seen: list[str] = []
        current: str | None = start
        while current in supersession:
            if current in seen:
                cycle = " -> ".join(seen[seen.index(current) :] + [current])
                document, claim = canonical[start]
                _error(document, claim, f"supersession cycle: {cycle}", "target")
            seen.append(current)
            current = supersession[current]

    done_with_blockers = {
        blocker.plan for blocker in blockers
        if next(document for document in v1 if document.id == blocker.plan).status == "done"
    }
    if done_with_blockers or (require_completion and blockers):
        blocker = next(
            value for value in blockers
            if require_completion or value.plan in done_with_blockers
        )
        document = next(value for value in v1 if value.id == blocker.plan)
        raise DocumentError(
            document.path,
            f"completion blocked by {blocker.claim}: {blocker.reason}",
            "status",
        )

    return LabelReport(
        v1_plans=len(v1),
        legacy_plans=len(legacy),
        claims=sum(len(document.claims) for document in v1),
        blockers=tuple(blockers),
    )


def _source_line(document: PlanDocument, claim: Claim) -> int:
    pattern = re.compile(rf"^ {{0,4}}{re.escape(claim.id)}(?:[.:])?\s+")
    for line, text in enumerate(document.body.splitlines(), 1):
        if pattern.match(text):
            return line
    return 0


def audit_records(documents: Iterable[PlanDocument], root: Path) -> tuple[AuditRecord, ...]:
    records: list[AuditRecord] = []
    for document in documents:
        if document.format is PlanFormat.V1:
            continue
        for claim in document.claims:
            decisions: list[str] = []
            if claim.kind == "design":
                decisions.append("classify-design-detail-or-dependency")
            if claim.kind == "question":
                decisions.append("record-open-or-resolved-question")
            if claim.kind in {"design", "question"} and claim.layers:
                decisions.append("remove-test-layers-or-convert-to-A-or-T")
            if claim.kind in {"assumption", "infrastructure"} and LIFECYCLE_MARKER_RE.search(
                claim.statement
            ):
                decisions.append("record-moot-or-superseded-lifecycle")
            if claim.kind in {"assumption", "infrastructure", "test"} and claim.layer_origin != "tag":
                decisions.append("declare-required-layers-or-non-active-lifecycle")
            if not decisions:
                continue
            records.append(
                AuditRecord(
                    id=f"{document.id}#{claim.id}",
                    kind=claim.kind,
                    source=document.path.relative_to(root).as_posix(),
                    line=_source_line(document, claim),
                    statement=claim.statement,
                    layers=claim.layers,
                    layer_origin=claim.layer_origin,
                    decisions=tuple(decisions),
                )
            )
    return tuple(records)


def render_audit(documents: Iterable[PlanDocument], root: Path) -> str:
    records = audit_records(documents, root)
    payload = {
        "schema": "isabelle-mcp.label-migration-audit/v1",
        "purpose": "bounded semantic decisions required before migrating legacy claims",
        "records": [asdict(record) for record in records],
    }
    return json.dumps(payload, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def audit_path(root: Path) -> Path:
    return root / "plans/migration/label-audit.json"


def generate_audit(documents: Iterable[PlanDocument], root: Path) -> int:
    rendered = render_audit(documents, root)
    write_atomic_text(audit_path(root), rendered)
    return len(json.loads(rendered)["records"])


def audit_is_fresh(documents: Iterable[PlanDocument], root: Path) -> tuple[bool, int]:
    rendered = render_audit(documents, root)
    path = audit_path(root)
    return path.exists() and path.read_text(encoding="utf-8") == rendered, len(
        json.loads(rendered)["records"]
    )
