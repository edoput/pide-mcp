"""Static verification-matrix producers, merge validation, and coverage."""

from __future__ import annotations

import ast
from dataclasses import asdict, dataclass
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
from typing import Any, Iterable, Mapping

from .document import Claim, PlanDocument, PlanFormat, load_repository


SCHEMA = "isabelle-mcp.verification-producer/v1"
MATRIX_SCHEMA = "isabelle-mcp.verification-matrix/v1"
PLAN_LINK_RE = re.compile(r"^[a-z][a-z0-9_]*#[AITDQ][0-9]+$")
PRODUCER_RE = re.compile(r"^[a-z0-9][a-z0-9./_-]*$")
LIFECYCLE_MARKER_RE = re.compile(r"\b(MOOT|SUPERSEDED)\b", re.IGNORECASE)
THEORY_MANIFEST_PRODUCER = "isabelle-mcp/isabelle"
THEORY_MATRIX_PRODUCER = "isabelle-mcp/isabelle-theory"
THEORY_FRAMEWORK = "isabelle"
THEORY_EXPORT_NAME = "mcp/spec-tests"
THEORY_SESSIONS = ("MCP-Tools-Tests", "MCP-HOL-Tests")
THEORY_SHA1_RE = re.compile(r"^sha1:[0-9a-f]{40}$")


class MatrixError(ValueError):
    pass


@dataclass(frozen=True)
class LayerRegistry:
    roles: Mapping[str, str]
    producers: Mapping[str, str]
    sha256: str

    @property
    def names(self) -> frozenset[str]:
        return frozenset(self.roles.values())


@dataclass(frozen=True)
class Link:
    relation: str
    id: str


@dataclass(frozen=True)
class TestRecord:
    identity: str
    name: str
    layer: str
    source: str
    line: int
    links: tuple[Link, ...]


@dataclass(frozen=True)
class ProducerManifest:
    producer: str
    artifact: Mapping[str, str]
    tests: tuple[TestRecord, ...]

    @staticmethod
    def _test_json(test: TestRecord) -> dict[str, Any]:
        return {
            "identity": test.identity,
            "name": test.name,
            "layer": test.layer,
            "location": {"path": test.source, "line": test.line},
            "verifies": sorted(
                link.id for link in test.links if link.relation == "verifies"
            ),
            "covers": sorted(
                link.id for link in test.links if link.relation == "covers"
            ),
        }

    def to_json(self) -> dict[str, Any]:
        return {
            "schema": SCHEMA,
            "producer": self.producer,
            "artifact": dict(sorted(self.artifact.items())),
            "tests": [self._test_json(test) for test in self.tests],
        }


@dataclass(frozen=True)
class MissingCoverage:
    id: str
    relation: str
    layer: str
    legacy: bool = False


@dataclass(frozen=True)
class MatrixResult:
    producers: tuple[ProducerManifest, ...]
    missing_producers: tuple[str, ...]
    missing_coverage: tuple[MissingCoverage, ...]

    def to_json(self) -> dict[str, Any]:
        tests = []
        for manifest in self.producers:
            for test in manifest.tests:
                record = {
                    "producer": manifest.producer,
                    **manifest._test_json(test),
                }
                tests.append(record)
        return {
            "schema": MATRIX_SCHEMA,
            "producers": [manifest.producer for manifest in self.producers],
            "missing_producers": list(self.missing_producers),
            "tests": sorted(tests, key=lambda value: (value["producer"], value["identity"])),
            "missing_coverage": [asdict(value) for value in self.missing_coverage],
        }


def _sha256(path: Path) -> str:
    return "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()


def load_layers(root: Path) -> LayerRegistry:
    path = root / "mcp_test/etc/test_layers.json"
    try:
        raw = path.read_bytes()
        value = json.loads(raw)
    except (OSError, json.JSONDecodeError) as ex:
        raise MatrixError(f"cannot load {path}: {ex}") from ex
    if not isinstance(value, dict) or set(value) != {"schema_version", "layers", "producers"}:
        raise MatrixError("layer registry requires exactly schema_version, layers, and producers")
    if value["schema_version"] != 1:
        raise MatrixError("layer registry schema_version must be 1")
    roles, producers = value["layers"], value["producers"]
    if not isinstance(roles, dict) or not roles:
        raise MatrixError("layer registry layers must be a non-empty object")
    if not isinstance(producers, dict):
        raise MatrixError("layer registry producers must be an object")
    if not all(isinstance(key, str) and isinstance(name, str) for key, name in roles.items()):
        raise MatrixError("layer roles and names must be strings")
    names = tuple(roles.values())
    if len(names) != len(set(names)):
        raise MatrixError("layer names must be unique")
    if set(producers) != set(names):
        raise MatrixError("every layer must have exactly one producer owner")
    if not all(isinstance(value, str) and PRODUCER_RE.fullmatch(value) for value in producers.values()):
        raise MatrixError("producer identifiers are malformed")
    return LayerRegistry(dict(roles), dict(producers), "sha256:" + hashlib.sha256(raw).hexdigest())


def _path(value: Any, where: str) -> str:
    if not isinstance(value, str) or not value or "\\" in value:
        raise MatrixError(f"{where}: source path must be a non-empty POSIX string")
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in {"", ".", ".."} for part in path.parts):
        raise MatrixError(f"{where}: source path must be normalized and relative")
    return value


def _links(verifies: Any, covers: Any, where: str) -> tuple[Link, ...]:
    result: list[Link] = []
    for relation, values in (("verifies", verifies), ("covers", covers)):
        if not isinstance(values, list) or not all(isinstance(value, str) for value in values):
            raise MatrixError(f"{where}.{relation}: must be a string array")
        for value in values:
            if not PLAN_LINK_RE.fullmatch(value):
                raise MatrixError(f"{where}.{relation}: malformed plan link {value!r}")
            result.append(Link(relation, value))
    if len(result) != len(set(result)):
        raise MatrixError(f"{where}: duplicate plan link")
    return tuple(sorted(result, key=lambda link: (link.relation, link.id)))


def manifest_from_json(value: Any, where: str = "manifest") -> ProducerManifest:
    if not isinstance(value, dict) or set(value) != {"schema", "producer", "artifact", "tests"}:
        raise MatrixError(f"{where}: producer manifest has unknown or missing fields")
    if value["schema"] != SCHEMA:
        raise MatrixError(f"{where}: unsupported schema {value['schema']!r}")
    producer = value["producer"]
    if not isinstance(producer, str) or not PRODUCER_RE.fullmatch(producer):
        raise MatrixError(f"{where}: malformed producer")
    artifact = value["artifact"]
    if not isinstance(artifact, dict) or not all(
        isinstance(key, str) and isinstance(item, str) for key, item in artifact.items()
    ):
        raise MatrixError(f"{where}.artifact: must be a string map")
    raw_tests = value["tests"]
    if not isinstance(raw_tests, list):
        raise MatrixError(f"{where}.tests: must be an array")
    tests: list[TestRecord] = []
    for index, raw in enumerate(raw_tests):
        test_where = f"{where}.tests[{index}]"
        if not isinstance(raw, dict) or set(raw) != {
            "identity", "name", "layer", "location", "verifies", "covers"
        }:
            raise MatrixError(f"{test_where}: test record has unknown or missing fields")
        location = raw["location"]
        if not isinstance(location, dict) or set(location) != {"path", "line"}:
            raise MatrixError(f"{test_where}.location: requires path and line")
        if type(location["line"]) is not int or location["line"] <= 0:
            raise MatrixError(f"{test_where}.location.line: must be a positive integer")
        for name in ("identity", "name", "layer"):
            if not isinstance(raw[name], str) or not raw[name].strip():
                raise MatrixError(f"{test_where}.{name}: must be a non-empty string")
        tests.append(
            TestRecord(
                identity=raw["identity"],
                name=raw["name"],
                layer=raw["layer"],
                source=_path(location["path"], f"{test_where}.location.path"),
                line=location["line"],
                links=_links(raw["verifies"], raw["covers"], test_where),
            )
        )
    identities = [test.identity for test in tests]
    if len(identities) != len(set(identities)):
        raise MatrixError(f"{where}: duplicate test identities")
    return ProducerManifest(producer, dict(artifact), tuple(sorted(tests, key=lambda test: test.identity)))


def read_producer(path: Path) -> ProducerManifest:
    try:
        return manifest_from_json(json.loads(path.read_text(encoding="utf-8")), str(path))
    except (OSError, json.JSONDecodeError) as ex:
        raise MatrixError(f"cannot load producer manifest {path}: {ex}") from ex


def munit_producer(root: Path, path: Path | None = None) -> ProducerManifest:
    path = path or root / "mcp_test/lib/munit-spec.json"
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as ex:
        raise MatrixError(f"cannot load MUnit manifest {path}: {ex}") from ex
    if not isinstance(value, dict) or value.get("producer") != "isabelle-mcp/munit":
        raise MatrixError("MUnit manifest has an unexpected producer")
    if value.get("schema_version") != 2:
        raise MatrixError("MUnit manifest schema_version must be 2")
    raw_tests = value.get("tests")
    if not isinstance(raw_tests, list):
        raise MatrixError("MUnit manifest tests is not an array")
    tests: list[dict[str, Any]] = []
    for index, raw in enumerate(raw_tests):
        if not isinstance(raw, dict):
            raise MatrixError(f"MUnit tests[{index}] is not an object")
        if raw.get("test_class") not in {"functional", "performance"}:
            raise MatrixError(
                f"MUnit tests[{index}].test_class must be 'functional' or 'performance'"
            )
        links = raw.get("links")
        if not isinstance(links, list):
            raise MatrixError(f"MUnit tests[{index}].links is not an array")
        verifies: list[str] = []
        covers: list[str] = []
        for link in links:
            if not isinstance(link, dict) or set(link) != {"relation", "id"}:
                raise MatrixError(f"MUnit tests[{index}] has malformed link")
            if link["relation"] == "verifies":
                verifies.append(link["id"])
            elif link["relation"] == "covers":
                covers.append(link["id"])
            else:
                raise MatrixError(
                    f"MUnit tests[{index}] uses removed or unknown relation {link['relation']!r}"
                )
        location = raw.get("location", {})
        tests.append(
            {
                "identity": f"{raw.get('suite')}::{raw.get('name')}",
                "name": raw.get("name"),
                "layer": raw.get("layer"),
                "location": location,
                "verifies": verifies,
                "covers": covers,
            }
        )
    return manifest_from_json(
        {
            "schema": SCHEMA,
            "producer": "isabelle-mcp/munit",
            "artifact": {
                "manifest": path.relative_to(root).as_posix(),
                "test_jar_sha256": str(value.get("test_jar_sha256", "")),
                "test_layers_sha256": str(value.get("test_layers_sha256", "")),
            },
            "tests": tests,
        },
        str(path),
    )


def _decorator_links(node: ast.FunctionDef, path: Path) -> tuple[list[str], list[str]]:
    verifies: list[str] = []
    covers: list[str] = []
    found = False
    for decorator in node.decorator_list:
        if not isinstance(decorator, ast.Call):
            continue
        name = decorator.func.id if isinstance(decorator.func, ast.Name) else None
        if name != "spec_test":
            continue
        if found or decorator.args:
            raise MatrixError(f"{path}:{node.lineno}: malformed or repeated spec_test decorator")
        found = True
        keywords = {keyword.arg: keyword.value for keyword in decorator.keywords}
        if set(keywords) - {"verifies", "covers"} or None in keywords:
            raise MatrixError(f"{path}:{node.lineno}: unknown spec_test argument")
        for relation, output in (("verifies", verifies), ("covers", covers)):
            try:
                values = ast.literal_eval(keywords.get(relation, ast.Tuple(elts=[])))
            except (ValueError, TypeError, SyntaxError) as ex:
                raise MatrixError(
                    f"{path}:{node.lineno}: {relation} links must be literal strings"
                ) from ex
            if not isinstance(values, (tuple, list)) or not all(
                isinstance(value, str) for value in values
            ):
                raise MatrixError(f"{path}:{node.lineno}: {relation} links must be literal strings")
            output.extend(values)
    return verifies, covers


def tooling_producer(root: Path) -> ProducerManifest:
    tests_dir = root / "tools/planning_gate/tests"
    tests: list[dict[str, Any]] = []
    artifacts: list[str] = []
    for path in sorted(tests_dir.glob("test_*.py")):
        relative = path.relative_to(root).as_posix()
        artifacts.append(f"{relative}={_sha256(path)}")
        try:
            tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        except (OSError, SyntaxError) as ex:
            raise MatrixError(f"cannot parse tooling tests {path}: {ex}") from ex
        for node in tree.body:
            if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) or not node.name.startswith(
                "test_"
            ):
                continue
            verifies, covers = _decorator_links(node, path)
            tests.append(
                {
                    "identity": f"{relative}::{node.name}",
                    "name": node.name,
                    "layer": "tooling-unit",
                    "location": {"path": relative, "line": node.lineno},
                    "verifies": verifies,
                    "covers": covers,
                }
            )
    return manifest_from_json(
        {
            "schema": SCHEMA,
            "producer": "isabelle-mcp/pytest",
            "artifact": {"sources": ";".join(artifacts)},
            "tests": tests,
        },
        "tooling discovery",
    )


def theory_producer(root: Path, path: Path | None = None) -> ProducerManifest:
    """Adapt PR 17's structured Isabelle export without scanning theory source."""
    path = path or root / "mcp_test/lib/isabelle-spec.json"
    try:
        raw_manifest = path.read_bytes()
        value = json.loads(raw_manifest)
    except (OSError, json.JSONDecodeError) as ex:
        raise MatrixError(f"cannot load Isabelle manifest {path}: {ex}") from ex
    expected_fields = {
        "schema_version",
        "producer",
        "framework",
        "export_name",
        "test_layers_sha256",
        "sessions",
        "theories",
        "tests",
    }
    if not isinstance(value, dict) or set(value) != expected_fields:
        raise MatrixError("Isabelle manifest has unknown or missing fields")
    if type(value["schema_version"]) is not int or value["schema_version"] != 1:
        raise MatrixError("Isabelle manifest schema_version must be 1")
    if value["producer"] != THEORY_MANIFEST_PRODUCER:
        raise MatrixError(f"Isabelle manifest producer must be {THEORY_MANIFEST_PRODUCER!r}")
    if value["framework"] != THEORY_FRAMEWORK:
        raise MatrixError(f"Isabelle manifest framework must be {THEORY_FRAMEWORK!r}")
    if value["export_name"] != THEORY_EXPORT_NAME:
        raise MatrixError(f"Isabelle manifest export_name must be {THEORY_EXPORT_NAME!r}")

    layer_digest = load_layers(root).sha256
    if value["test_layers_sha256"] != layer_digest:
        raise MatrixError(
            "Isabelle manifest test-layer registry is stale: "
            f"{value['test_layers_sha256']!r} != {layer_digest!r}"
        )
    sessions = value["sessions"]
    if not isinstance(sessions, list) or tuple(sessions) != THEORY_SESSIONS:
        raise MatrixError(
            "Isabelle manifest must declare configured sessions in order: "
            + ", ".join(THEORY_SESSIONS)
        )

    raw_theories = value["theories"]
    if not isinstance(raw_theories, list) or not raw_theories:
        raise MatrixError("Isabelle manifest theories must be a non-empty array")
    theory_sources: dict[tuple[str, str], tuple[str, int, str]] = {}
    for index, raw in enumerate(raw_theories):
        where = f"Isabelle manifest theories[{index}]"
        if not isinstance(raw, dict) or set(raw) != {"session", "theory", "source"}:
            raise MatrixError(f"{where}: unknown or missing fields")
        session, theory, source = raw["session"], raw["theory"], raw["source"]
        if session not in THEORY_SESSIONS:
            raise MatrixError(f"{where}.session is not configured")
        if not isinstance(theory, str) or not theory.strip():
            raise MatrixError(f"{where}.theory must be a non-empty string")
        if not isinstance(source, dict) or set(source) != {"path", "sha1"}:
            raise MatrixError(f"{where}.source requires path and sha1")
        source_path = _path(source["path"], f"{where}.source.path")
        if not source_path.startswith("mcp/Tools/") or not source_path.endswith(".thy"):
            raise MatrixError(f"{where}.source.path is not an Isabelle theory source")
        source_sha1 = source["sha1"]
        if not isinstance(source_sha1, str) or not THEORY_SHA1_RE.fullmatch(source_sha1):
            raise MatrixError(f"{where}.source.sha1 is malformed")
        identity = (session, theory)
        if identity in theory_sources:
            raise MatrixError(f"{where} duplicates theory identity {session}::{theory}")
        source_file = root / source_path
        try:
            source_bytes = source_file.read_bytes()
        except OSError as ex:
            raise MatrixError(f"{where}.source is unreadable: {ex}") from ex
        actual_sha1 = "sha1:" + hashlib.sha1(source_bytes).hexdigest()
        if actual_sha1 != source_sha1:
            raise MatrixError(f"{source_path} changed after its Isabelle export was built")
        theory_sources[identity] = (
            source_path,
            len(source_bytes.splitlines()),
            source_sha1,
        )

    raw_tests = value["tests"]
    if not isinstance(raw_tests, list) or not raw_tests:
        raise MatrixError("Isabelle manifest tests must be a non-empty array")
    tests: list[dict[str, Any]] = []
    identities: set[tuple[str, str, str]] = set()
    test_theories: set[tuple[str, str]] = set()
    test_sessions: set[str] = set()
    for index, raw in enumerate(raw_tests):
        where = f"Isabelle manifest tests[{index}]"
        if not isinstance(raw, dict) or set(raw) != {
            "session",
            "theory",
            "name",
            "location",
            "layer",
            "links",
        }:
            raise MatrixError(f"{where}: unknown or missing fields")
        session, theory, name = raw["session"], raw["theory"], raw["name"]
        if not isinstance(session, str) or session not in THEORY_SESSIONS:
            raise MatrixError(f"{where}.session is not configured")
        if not isinstance(theory, str) or not theory.strip():
            raise MatrixError(f"{where}.theory must be a non-empty string")
        source = theory_sources.get((session, theory))
        if source is None:
            raise MatrixError(f"{where} has no matching theory source")
        if not isinstance(name, str) or not name.strip():
            raise MatrixError(f"{where}.name must be a non-empty string")
        identity = (session, theory, name)
        if identity in identities:
            raise MatrixError(f"{where} duplicates test identity {'::'.join(identity)}")
        identities.add(identity)
        test_theories.add((session, theory))
        test_sessions.add(session)
        if raw["layer"] != "ml-unit":
            raise MatrixError(f"{where}.layer must be 'ml-unit'")
        location = raw["location"]
        if not isinstance(location, dict) or set(location) != {"path", "line"}:
            raise MatrixError(f"{where}.location requires path and line")
        source_path, source_lines, _ = source
        if location["path"] != source_path:
            raise MatrixError(f"{where}.location.path differs from its theory source")
        if (
            type(location["line"]) is not int
            or location["line"] <= 0
            or location["line"] > source_lines
        ):
            raise MatrixError(f"{where}.location.line is outside its theory source")

        links = raw["links"]
        if not isinstance(links, list) or not links:
            raise MatrixError(f"{where}.links must be a non-empty array")
        verifies: list[str] = []
        covers: list[str] = []
        seen_links: set[tuple[str, str]] = set()
        for link_index, link in enumerate(links):
            link_where = f"{where}.links[{link_index}]"
            if not isinstance(link, dict) or set(link) != {"relation", "id"}:
                raise MatrixError(f"{link_where}: requires relation and id")
            relation, ident = link["relation"], link["id"]
            if not isinstance(relation, str) or relation not in {"verifies", "covers"}:
                raise MatrixError(f"{link_where}.relation is unknown or removed")
            if not isinstance(ident, str) or not PLAN_LINK_RE.fullmatch(ident):
                raise MatrixError(f"{link_where}.id is not a qualified plan label")
            label = ident.rsplit("#", 1)[1]
            if relation == "verifies" and label[0] not in "AI":
                raise MatrixError(f"{link_where}: verifies cannot target {ident}")
            if relation == "covers" and label[0] != "T":
                raise MatrixError(f"{link_where}: covers cannot target {ident}")
            key = (relation, ident)
            if key in seen_links:
                raise MatrixError(f"{link_where} duplicates {relation}:{ident}")
            seen_links.add(key)
            (verifies if relation == "verifies" else covers).append(ident)
        tests.append(
            {
                "identity": "::".join(identity),
                "name": name,
                "layer": "ml-unit",
                "location": {"path": source_path, "line": location["line"]},
                "verifies": verifies,
                "covers": covers,
            }
        )

    if test_sessions != set(THEORY_SESSIONS):
        raise MatrixError("not every configured Isabelle session contributes a spec_test")
    if test_theories != set(theory_sources):
        raise MatrixError("every declared Isabelle theory must contribute a spec_test")
    try:
        manifest_path = path.relative_to(root).as_posix()
    except ValueError as ex:
        raise MatrixError("Isabelle manifest path must be inside the repository") from ex
    source_artifact = ";".join(
        f"{source_path}={source_sha1}"
        for source_path, _, source_sha1 in sorted(theory_sources.values())
    )
    return manifest_from_json(
        {
            "schema": SCHEMA,
            "producer": THEORY_MATRIX_PRODUCER,
            "artifact": {
                "manifest": manifest_path,
                "manifest_sha256": "sha256:" + hashlib.sha256(raw_manifest).hexdigest(),
                "test_layers_sha256": layer_digest,
                "theory_sources": source_artifact,
            },
            "tests": tests,
        },
        str(path),
    )


def _active(claim: Claim, document: PlanDocument) -> bool:
    if claim.kind not in {"assumption", "infrastructure", "test"}:
        return False
    if document.format is PlanFormat.V1:
        return claim.state == "active"
    return not (
        claim.kind in {"assumption", "infrastructure"}
        and LIFECYCLE_MARKER_RE.search(claim.statement)
    )


def assemble(
    documents: Iterable[PlanDocument],
    layers: LayerRegistry,
    manifests: Iterable[ProducerManifest],
) -> MatrixResult:
    documents = tuple(documents)
    manifests = tuple(sorted(manifests, key=lambda manifest: manifest.producer))
    producers = [manifest.producer for manifest in manifests]
    if len(producers) != len(set(producers)):
        raise MatrixError("a producer manifest appears more than once")
    required_producers = set(layers.producers.values())
    unknown_producers = set(producers) - required_producers
    if unknown_producers:
        raise MatrixError(f"unregistered producers: {sorted(unknown_producers)}")

    claims = {
        f"{document.id}#{claim.id}": (document, claim)
        for document in documents
        for claim in document.claims
    }
    tests: dict[tuple[str, str], TestRecord] = {}
    linked: dict[tuple[str, str, str], set[tuple[str, str]]] = {}
    for manifest in manifests:
        for test in manifest.tests:
            key = (manifest.producer, test.identity)
            if key in tests:
                raise MatrixError(f"duplicate test identity {key}")
            tests[key] = test
            if test.layer not in layers.names:
                raise MatrixError(f"{key}: unknown layer {test.layer!r}")
            owner = layers.producers[test.layer]
            if owner != manifest.producer:
                raise MatrixError(
                    f"{key}: layer {test.layer!r} is owned by {owner!r}, not {manifest.producer!r}"
                )
            for link in test.links:
                target = claims.get(link.id)
                if not target:
                    raise MatrixError(f"{key}: unknown plan claim {link.id!r}")
                document, claim = target
                expected = "covers" if claim.kind == "test" else (
                    "verifies" if claim.kind in {"assumption", "infrastructure"} else None
                )
                if link.relation != expected:
                    raise MatrixError(
                        f"{key}: {link.relation} cannot target {claim.kind} claim {link.id}"
                    )
                if not _active(claim, document):
                    raise MatrixError(f"{key}: link targets inactive claim {link.id}")
                if claim.layers and test.layer not in claim.layers:
                    raise MatrixError(
                        f"{key}: {test.layer} test cannot satisfy {link.id} layers {claim.layers}"
                    )
                linked.setdefault((link.id, link.relation, test.layer), set()).add(key)

    missing: list[MissingCoverage] = []
    for ident, (document, claim) in claims.items():
        if not _active(claim, document):
            continue
        relation = "covers" if claim.kind == "test" else "verifies"
        required_layers = claim.layers or ("unstated",)
        for layer in required_layers:
            if layer == "unstated" or not linked.get((ident, relation, layer)):
                missing.append(
                    MissingCoverage(
                        ident,
                        relation,
                        layer,
                        legacy=document.format is not PlanFormat.V1,
                    )
                )
    return MatrixResult(
        manifests,
        tuple(sorted(required_producers - set(producers))),
        tuple(sorted(missing, key=lambda value: (value.id, value.relation, value.layer))),
    )


def discover(
    root: Path, additional: Iterable[ProducerManifest] = ()
) -> MatrixResult:
    """Discover the currently implemented producers without executing tests."""
    from mcp.test.e2e.registry import producer as e2e_producer

    theory_manifest = root / "mcp_test/lib/isabelle-spec.json"
    manifests = [
        munit_producer(root),
        tooling_producer(root),
        e2e_producer(root),
        *additional,
    ]
    if theory_manifest.is_file():
        manifests.append(theory_producer(root, theory_manifest))
    return assemble(load_repository(root), load_layers(root), manifests)
