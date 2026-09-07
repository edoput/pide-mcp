"""Ephemeral, canonical evidence for a complete planning-gate execution."""

from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
from typing import Any, Iterable, Mapping, Protocol

from .commands import StepResult
from .matrix import discover


SCHEMA = "isabelle-mcp.planning-gate-execution/v1"


class Snapshot(Protocol):
    head: str
    tracked: Mapping[str, str]
    dirty_paths: tuple[str, ...]


def _canonical(value: object) -> bytes:
    return json.dumps(
        value, sort_keys=True, ensure_ascii=False, separators=(",", ":")
    ).encode("utf-8")


def _digest(value: object) -> str:
    return "sha256:" + hashlib.sha256(_canonical(value)).hexdigest()


def repository_identity(snapshot: Snapshot) -> dict[str, object]:
    """Return the compact repository identity used to bracket one execution."""
    tracked = dict(sorted(snapshot.tracked.items()))
    return {
        "head": snapshot.head,
        "tracked_tree_sha256": _digest(tracked),
        "dirty_paths": sorted(snapshot.dirty_paths),
    }


def producer_catalog_digest(root: Path) -> str:
    """Digest producer artifacts and every discovered test identity canonically."""
    matrix = discover(root)
    manifests = []
    for manifest in matrix.producers:
        value = manifest.to_json()
        value["tests"] = sorted(value["tests"], key=lambda test: test["identity"])
        manifests.append(value)
    manifests.sort(key=lambda manifest: manifest["producer"])
    return _digest({"producers": manifests})


@dataclass(frozen=True)
class ExecutionEvidence:
    required_steps: tuple[str, ...]
    results: tuple[StepResult, ...]
    static_closure_passed: bool
    start: Mapping[str, object]
    end: Mapping[str, object]
    producer_digest_before: str | None
    producer_digest_after: str | None
    failures: tuple[str, ...]

    @property
    def accepted(self) -> bool:
        if not self.static_closure_passed:
            return False
        if tuple(result.step for result in self.results) != self.required_steps:
            return False
        if any(not result.ok for result in self.results):
            return False
        if self.start != self.end:
            return False
        if self.producer_digest_before is None:
            return False
        if self.producer_digest_before != self.producer_digest_after:
            return False
        return not self.failures

    def to_json(self) -> dict[str, Any]:
        return {
            "schema": SCHEMA,
            "accepted": self.accepted,
            "required_steps": list(self.required_steps),
            "steps": [
                {
                    "id": result.step,
                    "returncode": result.returncode,
                    "duration_seconds": result.duration_seconds,
                    "timed_out": result.timed_out,
                    "passed": result.ok,
                }
                for result in self.results
            ],
            "static_closure_passed": self.static_closure_passed,
            "repository": {"start": dict(self.start), "end": dict(self.end)},
            "producer_catalog": {
                "before_sha256": self.producer_digest_before,
                "after_sha256": self.producer_digest_after,
            },
            "failures": list(self.failures),
        }


def render(evidence: ExecutionEvidence) -> str:
    """Render one compact canonical JSON record suitable for command output."""
    return _canonical(evidence.to_json()).decode("utf-8")
