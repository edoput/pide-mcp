"""Load and validate machine-readable and historical plan documents."""

from __future__ import annotations

from dataclasses import asdict, dataclass
from enum import Enum
import json
from pathlib import Path, PurePosixPath
import re
from typing import Any, Iterable, Mapping

from jsonschema import Draft202012Validator, FormatChecker
import yaml


PLAN_ID_RE = re.compile(r"^[a-z][a-z0-9_]*$")
CLAIM_ID_RE = re.compile(r"^([AITDQ])(\d+)$")
LEGACY_CLAIM_RE = re.compile(r"^ {0,4}([AITDQ]\d+)(?:[.:])?\s+(\S.*)$")
LEGACY_STATUS_RE = re.compile(r"^status:\s*(\S.*)$", re.IGNORECASE)
RULE_RE = re.compile(r"^[-=]{3,}\s*$")
ANY_TAG_RE = re.compile(r"\[([^\]]*)\]")
INLINE_TAG_RE = re.compile(r"^\[([^\]]*)\]\s*:?[ ]*")
FRONTMATTER_END = "\n---\n"
MAX_PLAN_BYTES = 1_000_000

KIND_BY_PREFIX = {
    "A": "assumption",
    "I": "infrastructure",
    "D": "design",
    "Q": "question",
    "T": "test",
}

LEGACY_LAYER_ALIASES = {
    "ml unit": "ml-unit",
    "ml-unit": "ml-unit",
    "scala unit": "scala-unit",
    "scala-unit": "scala-unit",
    "heap": "heap",
    "bridge": "bridge",
    "e2e": "e2e",
    "tooling unit": "tooling-unit",
    "tooling-unit": "tooling-unit",
}


class PlanFormat(str, Enum):
    V1 = "v1"
    BOOTSTRAP = "bootstrap-v0"
    LEGACY = "legacy-prose"


class DocumentError(ValueError):
    """A plan is unsafe, malformed, or inconsistent with its repository."""

    def __init__(self, path: Path, message: str, field: str | None = None):
        self.path = path
        self.field = field
        where = f"{path}:{field}" if field else str(path)
        super().__init__(f"{where}: {message}")


@dataclass(frozen=True)
class Claim:
    id: str
    kind: str
    statement: str
    state: str | None = None
    layers: tuple[str, ...] = ()
    category: str | None = None
    target: str | None = None
    rationale: str | None = None
    resolution: str | None = None
    resolved_on: str | None = None
    aliases: tuple[str, ...] = ()
    layer_origin: str = "none"


@dataclass(frozen=True)
class Invariant:
    id: str
    statement: str


@dataclass(frozen=True)
class SpecRefinement:
    id: str
    state: str
    statement: str
    spec_ids: tuple[str, ...]
    folded_on: str | None = None


@dataclass(frozen=True)
class PlanDocument:
    path: Path
    format: PlanFormat
    schema: str
    id: str
    title: str
    status: str
    depends_on: tuple[str, ...]
    allowed_modules: tuple[str, ...]
    invariants: tuple[Invariant, ...]
    claims: tuple[Claim, ...]
    spec_refinements: tuple[SpecRefinement, ...]
    done_command: tuple[str, ...]
    body: str

    @property
    def qualified_claim_ids(self) -> tuple[str, ...]:
        return tuple(f"{self.id}#{claim.id}" for claim in self.claims)

    def to_json(self, root: Path) -> dict[str, Any]:
        """Return stable JSON-compatible data without exposing absolute paths."""
        try:
            source = self.path.resolve().relative_to(root.resolve()).as_posix()
        except ValueError:
            source = self.path.as_posix()
        return {
            "source": source,
            "format": self.format.value,
            "schema": self.schema,
            "id": self.id,
            "title": self.title,
            "status": self.status,
            "depends_on": list(self.depends_on),
            "allowed_modules": list(self.allowed_modules),
            "invariants": [asdict(value) for value in self.invariants],
            "claims": [asdict(value) for value in self.claims],
            "spec_refinements": [asdict(value) for value in self.spec_refinements],
            "done_command": list(self.done_command),
        }


class _UniqueKeyLoader(yaml.SafeLoader):
    pass


def _construct_unique_mapping(
    loader: _UniqueKeyLoader, node: yaml.nodes.MappingNode, deep: bool = False
) -> dict[Any, Any]:
    loader.flatten_mapping(node)
    result: dict[Any, Any] = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        try:
            duplicate = key in result
        except TypeError as ex:
            raise yaml.constructor.ConstructorError(
                "while constructing a mapping",
                node.start_mark,
                "mapping keys must be scalar JSON values",
                key_node.start_mark,
            ) from ex
        if duplicate:
            raise yaml.constructor.ConstructorError(
                "while constructing a mapping",
                node.start_mark,
                f"duplicate key {key!r}",
                key_node.start_mark,
            )
        result[key] = loader.construct_object(value_node, deep=deep)
    return result


_UniqueKeyLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, _construct_unique_mapping
)


def _split_frontmatter(path: Path, text: str) -> tuple[Mapping[str, Any], str] | None:
    if not text.startswith("---\n"):
        return None
    end = text.find(FRONTMATTER_END, 4)
    if end < 0:
        raise DocumentError(path, "frontmatter has no closing '---' delimiter")
    raw = text[4:end]
    body = text[end + len(FRONTMATTER_END) :]
    try:
        value = yaml.load(raw, Loader=_UniqueKeyLoader)
    except yaml.YAMLError as ex:
        raise DocumentError(path, f"invalid or unsafe YAML: {ex}") from ex
    if not isinstance(value, dict):
        raise DocumentError(path, "frontmatter root must be a mapping")
    _require_json_values(path, value, "frontmatter")
    return value, body


def _require_json_values(path: Path, value: Any, field: str) -> None:
    """Reject YAML-only values before handing data to JSON Schema."""
    if value is None or isinstance(value, (str, bool, int, float)):
        return
    if isinstance(value, list):
        for index, item in enumerate(value):
            _require_json_values(path, item, f"{field}[{index}]")
        return
    if isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str):
                raise DocumentError(path, "mapping key is not a string", field)
            _require_json_values(path, item, f"{field}.{key}")
        return
    raise DocumentError(path, f"YAML value {type(value).__name__} is not JSON data", field)


def _load_schema(root: Path) -> Mapping[str, Any]:
    path = root / "plans/schema/plan-v1.schema.json"
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as ex:
        raise DocumentError(path, f"cannot load plan schema: {ex}") from ex
    try:
        Draft202012Validator.check_schema(value)
    except Exception as ex:
        raise DocumentError(path, f"invalid JSON Schema: {ex}") from ex
    return value


def _json_path(parts: Iterable[Any]) -> str:
    out = ""
    for part in parts:
        out += f"[{part}]" if isinstance(part, int) else ("." if out else "") + str(part)
    return out or "frontmatter"


def _validate_v1_schema(path: Path, root: Path, data: Mapping[str, Any]) -> None:
    validator = Draft202012Validator(
        _load_schema(root), format_checker=FormatChecker()
    )
    errors = sorted(validator.iter_errors(data), key=lambda error: _json_path(error.path))
    if errors:
        error = errors[0]
        raise DocumentError(path, error.message, _json_path(error.absolute_path))


def _validate_relative_path(path: Path, field: str, value: str) -> None:
    if "\\" in value:
        raise DocumentError(path, "repository paths must use '/' separators", field)
    candidate = PurePosixPath(value)
    if candidate.is_absolute() or not candidate.parts or any(
        part in ("", ".", "..") for part in candidate.parts
    ):
        raise DocumentError(path, "must be a normalized repository-relative path", field)


def _claim_from_v1(path: Path, raw: Mapping[str, Any], index: int) -> Claim:
    ident = raw["id"]
    match = CLAIM_ID_RE.fullmatch(ident)
    assert match is not None  # guaranteed by JSON Schema
    expected_kind = KIND_BY_PREFIX[match.group(1)]
    if raw["kind"] != expected_kind:
        raise DocumentError(
            path,
            f"claim {ident} requires kind {expected_kind!r}, got {raw['kind']!r}",
            f"claims[{index}].kind",
        )
    return Claim(
        id=ident,
        kind=raw["kind"],
        statement=raw["statement"],
        state=raw.get("state"),
        layers=tuple(raw.get("layers", ())),
        category=raw.get("category"),
        target=raw.get("target"),
        rationale=raw.get("rationale"),
        resolution=raw.get("resolution"),
        resolved_on=raw.get("resolved_on"),
        aliases=tuple(raw.get("aliases", ())),
        layer_origin="tag" if raw.get("layers") else "none",
    )


def _legacy_layer(tag: str) -> str | None:
    lowered = tag.lower()
    for alias, layer in LEGACY_LAYER_ALIASES.items():
        if alias in lowered:
            return layer
    return None


def _legacy_claims(body: str) -> tuple[Claim, ...]:
    """Read owned declarations and continuations without interpreting lifecycle."""
    claims: list[Claim] = []
    seen: set[str] = set()
    current: dict[str, Any] | None = None

    def close() -> None:
        nonlocal current
        if current is None:
            return
        ident = current["id"]
        if ident not in seen:
            raw_lines = current["lines"]
            body_text = " ".join(raw_lines).strip()
            layers: list[str] = []
            for raw_line in raw_lines:
                for tag in ANY_TAG_RE.findall(raw_line):
                    layer = _legacy_layer(tag)
                    if layer and layer not in layers:
                        layers.append(layer)
            if layers:
                layer_origin = "tag"
            else:
                inferred = _legacy_layer(body_text)
                if inferred:
                    layers.append(inferred)
                    layer_origin = "prose"
                else:
                    layer_origin = "none"
            statement = re.split(r"\btest\s*\[", body_text)[0].strip()
            statement = INLINE_TAG_RE.sub("", statement).strip()
            claims.append(
                Claim(
                    id=ident,
                    kind=KIND_BY_PREFIX[ident[0]],
                    statement=statement or body_text,
                    layers=tuple(sorted(layers)),
                    layer_origin=layer_origin,
                )
            )
            seen.add(ident)
        current = None

    for line in body.splitlines():
        match = LEGACY_CLAIM_RE.match(line)
        if match:
            close()
            current = {"id": match.group(1), "lines": [match.group(2)]}
            continue
        if current is None:
            continue
        if RULE_RE.match(line) or (line.strip() and not line.startswith(" ")):
            close()
        elif line.strip():
            current["lines"].append(line.strip())
    close()
    return tuple(claims)


def _v1_document(path: Path, root: Path, data: Mapping[str, Any], body: str) -> PlanDocument:
    _validate_v1_schema(path, root, data)
    if data["id"] != path.name:
        raise DocumentError(path, f"plan id {data['id']!r} must equal filename {path.name!r}", "id")
    for index, value in enumerate(data["allowed_modules"]):
        _validate_relative_path(path, f"allowed_modules[{index}]", value)

    body_declarations = _legacy_claims(body)
    if body_declarations:
        raise DocumentError(
            path,
            f"v1 body redeclares canonical claim {body_declarations[0].id}",
            "body",
        )

    claims = tuple(_claim_from_v1(path, value, i) for i, value in enumerate(data["claims"]))
    for field, values in (
        ("claims", [value.id for value in claims]),
        ("invariants", [value["id"] for value in data["invariants"]]),
        ("spec_refinements", [value["id"] for value in data["spec_refinements"]]),
    ):
        duplicate = next((value for value in values if values.count(value) > 1), None)
        if duplicate:
            raise DocumentError(path, f"duplicate {field} id {duplicate!r}", field)

    return PlanDocument(
        path=path,
        format=PlanFormat.V1,
        schema=data["schema"],
        id=data["id"],
        title=data["title"],
        status=data["status"],
        depends_on=tuple(data["depends_on"]),
        allowed_modules=tuple(data["allowed_modules"]),
        invariants=tuple(Invariant(**value) for value in data["invariants"]),
        claims=claims,
        spec_refinements=tuple(
            SpecRefinement(
                id=value["id"],
                state=value["state"],
                statement=value["statement"],
                spec_ids=tuple(value["spec_ids"]),
                folded_on=value.get("folded_on"),
            )
            for value in data["spec_refinements"]
        ),
        done_command=tuple(data["done_command"]),
        body=body,
    )


def _bootstrap_document(path: Path, data: Mapping[str, Any], body: str) -> PlanDocument:
    expected = {
        "schema", "id", "title", "status", "depends_on", "allowed_modules", "done_command"
    }
    unknown = set(data) - expected
    missing = expected - set(data)
    if unknown or missing:
        detail = f"unknown fields {sorted(unknown)}" if unknown else f"missing fields {sorted(missing)}"
        raise DocumentError(path, detail, "frontmatter")
    if data["schema"] != "isabelle-mcp.plan/bootstrap-v0":
        raise DocumentError(path, f"unsupported schema {data['schema']!r}", "schema")
    if data["id"] != path.name:
        raise DocumentError(path, "bootstrap id must equal filename", "id")
    for name in ("depends_on", "allowed_modules", "done_command"):
        if not isinstance(data[name], list) or not all(isinstance(value, str) for value in data[name]):
            raise DocumentError(path, "must be a list of strings", name)
    for index, value in enumerate(data["allowed_modules"]):
        _validate_relative_path(path, f"allowed_modules[{index}]", value)
    return PlanDocument(
        path=path,
        format=PlanFormat.BOOTSTRAP,
        schema=data["schema"],
        id=data["id"],
        title=str(data["title"]),
        status=str(data["status"]),
        depends_on=tuple(data["depends_on"]),
        allowed_modules=tuple(data["allowed_modules"]),
        invariants=(),
        claims=_legacy_claims(body),
        spec_refinements=(),
        done_command=tuple(data["done_command"]),
        body=body,
    )


def _legacy_document(path: Path, body: str) -> PlanDocument:
    statuses = [match.group(1) for line in body.splitlines() if (match := LEGACY_STATUS_RE.match(line))]
    if len(statuses) != 1:
        raise DocumentError(path, f"legacy plan requires exactly one status line, found {len(statuses)}")
    return PlanDocument(
        path=path,
        format=PlanFormat.LEGACY,
        schema="isabelle-mcp.plan/legacy-prose",
        id=path.name,
        title=path.name.replace("_", " "),
        status=statuses[0],
        depends_on=(),
        allowed_modules=(),
        invariants=(),
        claims=_legacy_claims(body),
        spec_refinements=(),
        done_command=(),
        body=body,
    )


def load_plan(path: Path, root: Path, *, allow_legacy: bool = True) -> PlanDocument:
    """Load one plan without consulting process cwd or module globals."""
    path = path.resolve()
    root = root.resolve()
    try:
        path.relative_to(root)
    except ValueError as ex:
        raise DocumentError(path, f"plan lies outside repository root {root}") from ex
    try:
        raw = path.read_bytes()
    except OSError as ex:
        raise DocumentError(path, f"cannot read plan: {ex}") from ex
    if len(raw) > MAX_PLAN_BYTES:
        raise DocumentError(path, f"plan exceeds {MAX_PLAN_BYTES} byte safety limit")
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as ex:
        raise DocumentError(path, "plan is not UTF-8") from ex

    split = _split_frontmatter(path, text)
    if split is None:
        if not allow_legacy:
            raise DocumentError(path, "v1 YAML frontmatter is required")
        return _legacy_document(path, text)
    data, body = split
    schema = data.get("schema")
    if schema == "isabelle-mcp.plan/v1":
        return _v1_document(path, root, data, body)
    if allow_legacy and schema == "isabelle-mcp.plan/bootstrap-v0":
        return _bootstrap_document(path, data, body)
    raise DocumentError(path, f"unsupported schema {schema!r}", "schema")


def _is_plan_file(path: Path) -> bool:
    return (
        path.is_file()
        and path.name not in {"README", "ASSUMPTIONS"}
        and not path.name.startswith(".")
        and not path.name.endswith(("~", ".bak", ".orig", ".rej"))
    )


def load_repository(root: Path, *, allow_legacy: bool = True) -> tuple[PlanDocument, ...]:
    """Load all plans, then enforce facts which require repository context."""
    root = root.resolve()
    plans_dir = root / "plans"
    documents = tuple(
        load_plan(path, root, allow_legacy=allow_legacy)
        for path in sorted(plans_dir.iterdir())
        if _is_plan_file(path)
    )
    by_id: dict[str, PlanDocument] = {}
    for document in documents:
        if not PLAN_ID_RE.fullmatch(document.id):
            raise DocumentError(document.path, f"malformed plan id {document.id!r}", "id")
        if document.id in by_id:
            raise DocumentError(document.path, f"duplicate plan id {document.id!r}", "id")
        by_id[document.id] = document
    for document in documents:
        for index, dependency in enumerate(document.depends_on):
            if dependency not in by_id:
                raise DocumentError(
                    document.path,
                    f"unknown plan dependency {dependency!r}",
                    f"depends_on[{index}]",
                )

    visiting: list[str] = []
    visited: set[str] = set()

    def visit(ident: str) -> None:
        if ident in visiting:
            cycle = " -> ".join(visiting[visiting.index(ident) :] + [ident])
            raise DocumentError(by_id[ident].path, f"dependency cycle: {cycle}", "depends_on")
        if ident in visited:
            return
        visiting.append(ident)
        for dependency in by_id[ident].depends_on:
            visit(dependency)
        visiting.pop()
        visited.add(ident)

    for document in documents:
        visit(document.id)
    return documents
