"""Body-free discovery and runtime registration for procedural e2e cases."""

from __future__ import annotations

import ast
from collections.abc import Callable, Iterable
from dataclasses import dataclass
import hashlib
import importlib
from pathlib import Path, PurePosixPath
import re
from typing import Any, TypeVar

from tools.planning_gate.matrix import ProducerManifest, manifest_from_json


PRODUCER = "isabelle-mcp/python-e2e"
LINK_RE = re.compile(r"^[a-z][a-z0-9_]*#[AIT][0-9]+$")
ENTRYPOINTS = frozenset(
    {
        "mcp/test/test_mcp.py",
        "mcp/test/repro_concurrent_serve.py",
        "mcp/test/repro_duplicate_session.py",
    }
)
F = TypeVar("F", bound=Callable[[], int | None])


class RegistryError(ValueError):
    pass


@dataclass(frozen=True)
class CaseDefinition:
    identity: str
    name: str
    module: str
    function: str
    source: str
    line: int
    verifies: tuple[str, ...]
    covers: tuple[str, ...]
    timeout_seconds: float
    entrypoint: str


def repository_root() -> Path:
    return Path(__file__).resolve().parents[3]


def _checked_links(relation: str, values: Iterable[str]) -> tuple[str, ...]:
    result = tuple(values)
    if len(result) != len(set(result)):
        raise RegistryError(f"duplicate {relation} links")
    for value in result:
        if not LINK_RE.fullmatch(value):
            raise RegistryError(f"malformed {relation} link {value!r}")
        label = value.rsplit("#", 1)[1]
        if relation == "verifies" and not label.startswith(("A", "I")):
            raise RegistryError(f"verifies cannot target {value!r}")
        if relation == "covers" and not label.startswith("T"):
            raise RegistryError(f"covers cannot target {value!r}")
    return tuple(sorted(result))


def _checked_entrypoint(value: str) -> str:
    path = PurePosixPath(value)
    if (
        not value
        or path.is_absolute()
        or "\\" in value
        or any(part in {"", ".", ".."} for part in path.parts)
    ):
        raise RegistryError(f"entrypoint must be a normalized relative path: {value!r}")
    return value


def e2e_test(
    *,
    name: str,
    entrypoint: str,
    verifies: Iterable[str] = (),
    covers: Iterable[str] = (),
    timeout_seconds: float = 1800,
) -> Callable[[F], F]:
    """Register runtime metadata; manifest discovery reads the source AST."""
    if not isinstance(name, str) or not name.strip():
        raise RegistryError("case name must not be empty")
    if not isinstance(timeout_seconds, (int, float)) or timeout_seconds <= 0:
        raise RegistryError("case timeout must be positive")
    checked_entrypoint = _checked_entrypoint(entrypoint)
    checked_verifies = _checked_links("verifies", verifies)
    checked_covers = _checked_links("covers", covers)

    def decorate(function: F) -> F:
        definition = CaseDefinition(
            identity=f"{function.__module__}::{function.__name__}",
            name=name,
            module=function.__module__,
            function=function.__name__,
            source=function.__code__.co_filename,
            line=function.__code__.co_firstlineno,
            verifies=checked_verifies,
            covers=checked_covers,
            timeout_seconds=float(timeout_seconds),
            entrypoint=checked_entrypoint,
        )
        setattr(function, "__e2e_definition__", definition)
        return function

    return decorate


def _literal(value: ast.expr, where: str) -> Any:
    try:
        return ast.literal_eval(value)
    except (ValueError, TypeError, SyntaxError) as ex:
        raise RegistryError(f"{where}: metadata values must be literals") from ex


def _definition(node: ast.FunctionDef, path: Path, root: Path) -> CaseDefinition | None:
    decorators = [
        value
        for value in node.decorator_list
        if isinstance(value, ast.Call)
        and isinstance(value.func, ast.Name)
        and value.func.id == "e2e_test"
    ]
    if not decorators:
        if node.name.startswith("case_"):
            raise RegistryError(f"{path}:{node.lineno}: e2e case lacks @e2e_test")
        return None
    if len(decorators) != 1 or decorators[0].args:
        raise RegistryError(f"{path}:{node.lineno}: malformed or repeated @e2e_test")
    decorator = decorators[0]
    keywords: dict[str, ast.expr] = {}
    for keyword in decorator.keywords:
        if keyword.arg is None or keyword.arg in keywords:
            raise RegistryError(f"{path}:{node.lineno}: duplicate or expanded argument")
        keywords[keyword.arg] = keyword.value
    allowed = {"name", "entrypoint", "verifies", "covers", "timeout_seconds"}
    if set(keywords) - allowed or not {"name", "entrypoint"} <= set(keywords):
        raise RegistryError(f"{path}:{node.lineno}: unknown or missing @e2e_test argument")

    name = _literal(keywords["name"], f"{path}:{node.lineno}")
    entrypoint = _literal(keywords["entrypoint"], f"{path}:{node.lineno}")
    verifies = _literal(keywords.get("verifies", ast.Tuple(elts=[])), str(path))
    covers = _literal(keywords.get("covers", ast.Tuple(elts=[])), str(path))
    timeout = _literal(keywords.get("timeout_seconds", ast.Constant(1800)), str(path))
    if not isinstance(name, str) or not name.strip():
        raise RegistryError(f"{path}:{node.lineno}: case name must be a non-empty string")
    if not isinstance(entrypoint, str):
        raise RegistryError(f"{path}:{node.lineno}: entrypoint must be a string")
    if not isinstance(verifies, (tuple, list)) or not all(
        isinstance(value, str) for value in verifies
    ):
        raise RegistryError(f"{path}:{node.lineno}: verifies must be a string sequence")
    if not isinstance(covers, (tuple, list)) or not all(
        isinstance(value, str) for value in covers
    ):
        raise RegistryError(f"{path}:{node.lineno}: covers must be a string sequence")
    if not isinstance(timeout, (int, float)) or isinstance(timeout, bool) or timeout <= 0:
        raise RegistryError(f"{path}:{node.lineno}: timeout_seconds must be positive")
    relative = path.relative_to(root).as_posix()
    module = relative.removesuffix(".py").replace("/", ".")
    return CaseDefinition(
        identity=f"{module}::{node.name}",
        name=name,
        module=module,
        function=node.name,
        source=relative,
        line=node.lineno,
        verifies=_checked_links("verifies", verifies),
        covers=_checked_links("covers", covers),
        timeout_seconds=float(timeout),
        entrypoint=_checked_entrypoint(entrypoint),
    )


def discover_cases(
    root: Path,
    cases_dir: Path | None = None,
    required_entrypoints: Iterable[str] = ENTRYPOINTS,
) -> tuple[CaseDefinition, ...]:
    cases_dir = cases_dir or root / "mcp/test/e2e/cases"
    cases: list[CaseDefinition] = []
    for path in sorted(cases_dir.glob("case_*.py")):
        try:
            tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        except (OSError, SyntaxError) as ex:
            raise RegistryError(f"cannot parse e2e cases {path}: {ex}") from ex
        for node in tree.body:
            if isinstance(node, ast.FunctionDef):
                definition = _definition(node, path, root)
                if definition:
                    cases.append(definition)
    identities = [case.identity for case in cases]
    names = [case.name for case in cases]
    if len(identities) != len(set(identities)):
        raise RegistryError("duplicate e2e case identity")
    if len(names) != len(set(names)):
        raise RegistryError("duplicate e2e case name")
    entrypoints = {case.entrypoint for case in cases}
    required = set(required_entrypoints)
    if entrypoints != required:
        missing = sorted(required - entrypoints)
        unexpected = sorted(entrypoints - required)
        raise RegistryError(
            f"e2e entrypoint inventory mismatch; missing={missing}, unexpected={unexpected}"
        )
    for entrypoint in sorted(entrypoints):
        if not (root / entrypoint).is_file():
            raise RegistryError(f"registered e2e entrypoint does not exist: {entrypoint}")
    return tuple(sorted(cases, key=lambda case: case.identity))


def producer(root: Path) -> ProducerManifest:
    cases = discover_cases(root)
    sources = sorted({case.source for case in cases})
    artifacts = ";".join(
        f"{source}=sha256:{hashlib.sha256((root / source).read_bytes()).hexdigest()}"
        for source in sources
    )
    return manifest_from_json(
        {
            "schema": "isabelle-mcp.verification-producer/v1",
            "producer": PRODUCER,
            "artifact": {"sources": artifacts},
            "tests": [
                {
                    "identity": case.identity,
                    "name": case.name,
                    "layer": "e2e",
                    "location": {"path": case.source, "line": case.line},
                    "verifies": list(case.verifies),
                    "covers": list(case.covers),
                }
                for case in cases
            ],
        },
        "Python e2e discovery",
    )


def load_runtime_case(identity: str) -> tuple[CaseDefinition, Callable[[], int | None]]:
    try:
        module_name, function_name = identity.split("::", 1)
    except ValueError as ex:
        raise RegistryError(f"malformed e2e identity {identity!r}") from ex
    module = importlib.import_module(module_name)
    function = getattr(module, function_name, None)
    definition = getattr(function, "__e2e_definition__", None)
    if not callable(function) or not isinstance(definition, CaseDefinition):
        raise RegistryError(f"e2e registration not found at {identity}")
    if definition.identity != identity:
        raise RegistryError(f"runtime e2e identity drift for {identity}")
    return definition, function
