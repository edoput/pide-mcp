"""Registered build and test commands with shell-free subprocess execution."""

from __future__ import annotations

from dataclasses import dataclass
import os
from pathlib import Path
import shlex
import signal
import subprocess
import sys
import time
from typing import Callable, Mapping, TextIO

from .matrix import load_layers


DEFAULT_ISABELLE = (
    "flatpak",
    "run",
    "--command=isabelle",
    "de.tum.in.isabelle.Isabelle",
)


class CommandError(ValueError):
    pass


@dataclass(frozen=True)
class CommandStep:
    id: str
    description: str
    argv: tuple[str, ...]
    timeout_seconds: float
    layers: tuple[str, ...] = ()


@dataclass(frozen=True)
class StepResult:
    step: str
    returncode: int | None
    duration_seconds: float
    timed_out: bool = False

    @property
    def ok(self) -> bool:
        return self.returncode == 0 and not self.timed_out


StepRunner = Callable[[CommandStep, Path], StepResult]


def run_theory_catalog(
    root: Path,
    *,
    steps: Mapping[str, CommandStep],
    runner: StepRunner | None = None,
) -> tuple[StepResult, ...]:
    """Build theory sessions, then export their metadata if the build passed.

    The registered steps remain independently injectable: this composition is
    deliberately in-process rather than a recursive planning-gate invocation.
    """
    required = ("theories", "theory-manifest")
    absent = [step_id for step_id in required if step_id not in steps]
    if absent:
        raise CommandError(f"theory catalog has no registered steps: {absent}")

    runner = run_step if runner is None else runner
    build = runner(steps["theories"], root)
    if not build.ok:
        return (build,)
    return (build, runner(steps["theory-manifest"], root))


def isabelle_command(environment: Mapping[str, str] | None = None) -> tuple[str, ...]:
    environment = os.environ if environment is None else environment
    configured = environment.get("ISABELLE")
    if configured is None:
        return DEFAULT_ISABELLE
    try:
        argv = tuple(shlex.split(configured))
    except ValueError as ex:
        raise CommandError(f"cannot parse ISABELLE as an argument vector: {ex}") from ex
    if not argv or any("\x00" in value for value in argv):
        raise CommandError("ISABELLE must name a non-empty command without NUL bytes")
    return argv


def registered_commands(
    root: Path,
    *,
    python: str | None = None,
    environment: Mapping[str, str] | None = None,
) -> dict[str, CommandStep]:
    root = root.resolve()
    python = python or sys.executable
    isabelle = isabelle_command(environment)
    registry = load_layers(root)
    required_roles = {
        "scala_unit_suites",
        "scala_performance_suites",
        "heap_suites",
        "pide_suites",
        "ml_unit_tests",
        "tooling_unit_tests",
        "end_to_end_tests",
    }
    missing_roles = sorted(required_roles - set(registry.roles))
    if missing_roles:
        raise CommandError(f"test-layer registry has no runners for roles: {missing_roles}")

    scala = registry.roles["scala_unit_suites"]
    performance = registry.roles["scala_performance_suites"]
    heap = registry.roles["heap_suites"]
    bridge = registry.roles["pide_suites"]
    ml = registry.roles["ml_unit_tests"]
    tooling = registry.roles["tooling_unit_tests"]
    e2e = registry.roles["end_to_end_tests"]
    theory_dir = "mcp/Tools"
    munit_manifest = "mcp_test/lib/munit-spec.json"
    theory_manifest = "mcp_test/lib/isabelle-spec.json"

    steps = {
        "scala-build": CommandStep(
            "scala-build", "compile production and test Scala components",
            isabelle + ("scala_build",), 3600,
        ),
        "munit-catalog": CommandStep(
            "munit-catalog", "write body-free MUnit metadata",
            isabelle + ("mcp_test", "-M", munit_manifest), 600,
        ),
        "theories": CommandStep(
            "theories", "build production and ML-unit theory sessions",
            isabelle
            + (
                "build", "-d", theory_dir,
                "MCP-Tools", "MCP-Tools-Tests", "MCP-HOL", "MCP-HOL-Tests",
            ),
            7200,
            (ml,),
        ),
        "theory-manifest": CommandStep(
            "theory-manifest", "materialize structured theory-test exports",
            isabelle + ("mcp_theory_metadata", "-M", theory_manifest), 600,
        ),
        "spec-gate": CommandStep(
            "spec-gate", "run compatibility spec and prose drift checks",
            (
                python, "tools/spec_gate.py",
                "--test-manifest", munit_manifest,
                "--theory-manifest", theory_manifest,
            ),
            600,
        ),
        tooling: CommandStep(
            tooling, "run planning-gate tooling tests",
            (python, "-m", "pytest", "-q", "tools/planning_gate/tests"),
            900,
            (tooling,),
        ),
        scala: CommandStep(
            scala, "run the complete Scala unit layer",
            isabelle + ("mcp_test", "-L", scala), 3600, (scala,),
        ),
        performance: CommandStep(
            performance, "run the explicit Scala performance budgets",
            isabelle + ("mcp_test", "-L", performance), 3600, (performance,),
        ),
        heap: CommandStep(
            heap, "run the complete fresh-heap layer",
            isabelle + ("mcp_test", "-L", heap, "-d", theory_dir), 3600, (heap,),
        ),
        bridge: CommandStep(
            bridge, "run the complete live PIDE bridge layer",
            isabelle + ("mcp_test", "-L", bridge, "-d", theory_dir), 7200, (bridge,),
        ),
        e2e: CommandStep(
            e2e, "run every registered procedural end-to-end case",
            (python, "-m", "mcp.test.e2e", "run", "--all"), 14400, (e2e,),
        ),
    }
    return steps


def layer_step_ids(root: Path) -> tuple[str, ...]:
    registry = load_layers(root)
    return tuple(
        registry.roles[role]
        for role in (
            "tooling_unit_tests",
            "scala_unit_suites",
            "scala_performance_suites",
            "heap_suites",
            "pide_suites",
            "end_to_end_tests",
        )
    )


def run_step(step: CommandStep, root: Path, *, stream: TextIO = sys.stdout) -> StepResult:
    print(f"\n==> {step.id}: {step.description}", file=stream, flush=True)
    print("    argv: " + shlex.join(step.argv), file=stream, flush=True)
    started = time.monotonic()
    process = subprocess.Popen(step.argv, cwd=root, start_new_session=True)

    def terminate_group() -> None:
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            return
        try:
            process.wait(timeout=2)
            return
        except subprocess.TimeoutExpired:
            pass
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait()

    try:
        returncode = process.wait(timeout=step.timeout_seconds)
        result = StepResult(step.id, returncode, time.monotonic() - started)
    except subprocess.TimeoutExpired:
        terminate_group()
        result = StepResult(step.id, None, time.monotonic() - started, timed_out=True)
    except KeyboardInterrupt:
        terminate_group()
        raise
    verdict = "PASS" if result.ok else "FAIL"
    detail = "timeout" if result.timed_out else f"exit {result.returncode}"
    print(
        f"<== {verdict} {step.id} ({detail}, {result.duration_seconds:.1f}s)",
        file=stream,
        flush=True,
    )
    return result
