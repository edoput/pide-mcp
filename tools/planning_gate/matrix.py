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
THEORY_LINK_RE = re.compile(r"\\<\^assumption>\\<open>([a-z][a-z0-9_]*#[AITDQ][0-9]+)\\<close>")
LIFECYCLE_MARKER_RE = re.compile(r"\b(MOOT|SUPERSEDED)\b", re.IGNORECASE)


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
    raw_tests = value.get("tests")
    if not isinstance(raw_tests, list):
        raise MatrixError("MUnit manifest tests is not an array")
    tests: list[dict[str, Any]] = []
    for index, raw in enumerate(raw_tests):
        if not isinstance(raw, dict):
            raise MatrixError(f"MUnit tests[{index}] is not an object")
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


def theory_producer(root: Path) -> ProducerManifest:
    tests: list[dict[str, Any]] = []
    artifacts: list[str] = []
    paths = sorted((root / "mcp/Tools").glob("**/Tests/*.thy"))
    for path in paths:
        relative = path.relative_to(root).as_posix()
        artifacts.append(f"{relative}={_sha256(path)}")
        for line, text in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            for occurrence, ident in enumerate(THEORY_LINK_RE.findall(text), 1):
                label = ident.rsplit("#", 1)[1]
                relation = "covers" if label.startswith("T") else "verifies"
                tests.append(
                    {
                        "identity": f"{relative}:{line}:{occurrence}",
                        "name": f"{path.stem} line {line}",
                        "layer": "ml-unit",
                        "location": {"path": relative, "line": line},
                        "verifies": [ident] if relation == "verifies" else [],
                        "covers": [ident] if relation == "covers" else [],
                    }
                )
    return manifest_from_json(
        {
            "schema": SCHEMA,
            "producer": "isabelle-mcp/isabelle-theory",
            "artifact": {"sources": ";".join(artifacts)},
            "tests": tests,
        },
        "Isabelle theory discovery",
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
                missing.append(MissingCoverage(ident, relation, layer))
    return MatrixResult(
        manifests,
        tuple(sorted(required_producers - set(producers))),
        tuple(sorted(missing, key=lambda value: (value.id, value.relation, value.layer))),
    )


def discover(
    root: Path, additional: Iterable[ProducerManifest] = ()
) -> MatrixResult:
    """Discover the currently implemented producers without executing tests."""
    manifests = [
        munit_producer(root),
        theory_producer(root),
        tooling_producer(root),
        *additional,
    ]
    return assemble(load_repository(root), load_layers(root), manifests)
