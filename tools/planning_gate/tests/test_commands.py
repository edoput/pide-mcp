from __future__ import annotations

from pathlib import Path
import sys

import pytest

from tools.planning_gate.__main__ import _main, _parser
from tools.planning_gate.commands import (
    CommandStep,
    StepResult,
    registered_commands,
    run_step,
    run_theory_catalog,
)
from tools.planning_gate.tooling import spec_test


ROOT = Path(__file__).resolve().parents[3]


@spec_test(verifies=("planning_gate#I1",), covers=("planning_gate#T4",))
def test_every_layer_has_an_explicit_shell_free_diagnostic_command() -> None:
    steps = registered_commands(
        ROOT,
        python="/fixture/python",
        environment={"ISABELLE": "isabelle-fixture --flag value"},
    )

    assert steps["scala-unit"].argv == (
        "isabelle-fixture", "--flag", "value", "mcp_test", "-L", "scala-unit"
    )
    assert steps["scala-performance"].argv == (
        "isabelle-fixture", "--flag", "value", "mcp_test", "-L", "scala-performance"
    )
    assert steps["heap"].argv[-4:] == ("-L", "heap", "-d", "mcp/Tools")
    assert steps["bridge"].argv[-4:] == ("-L", "bridge", "-d", "mcp/Tools")
    assert steps["theories"].layers == ("ml-unit",)
    assert steps["tooling-unit"].argv[:3] == (
        "/fixture/python", "-m", "pytest"
    )
    assert steps["e2e"].argv[-2:] == ("run", "--all")
    assert all("sh" not in step.argv[:1] for step in steps.values())


@spec_test(covers=("planning_gate#T3",))
def test_command_runner_captures_failure_and_timeout(tmp_path: Path) -> None:
    failed = CommandStep(
        "failed", "fixture failure", (sys.executable, "-c", "raise SystemExit(7)"), 5
    )
    result = run_step(failed, tmp_path)
    assert result.returncode == 7
    assert not result.ok

    timed = CommandStep(
        "timed", "fixture timeout", (sys.executable, "-c", "import time; time.sleep(5)"), 0.05
    )
    result = run_step(timed, tmp_path)
    assert result.timed_out
    assert not result.ok


@spec_test(covers=("planning_gate#T4",))
def test_done_cli_rejects_filters_and_reduced_layer_arguments() -> None:
    with pytest.raises(SystemExit):
        _parser().parse_args(["done", "--filter", "one-test"])
    with pytest.raises(SystemExit):
        _parser().parse_args(["done", "--layer", "scala-unit"])


@spec_test(covers=("planning_gate#T3",))
def test_theory_catalog_builds_before_export_and_short_circuits() -> None:
    steps = {
        step_id: CommandStep(step_id, step_id, ("fixture", step_id), 1)
        for step_id in ("theories", "theory-manifest")
    }
    seen: list[str] = []

    def runner(step: CommandStep, _: Path) -> StepResult:
        seen.append(step.id)
        return StepResult(step.id, 0, 0)

    results = run_theory_catalog(ROOT, steps=steps, runner=runner)
    assert tuple(result.step for result in results) == ("theories", "theory-manifest")
    assert seen == ["theories", "theory-manifest"]

    results = run_theory_catalog(
        ROOT,
        steps=steps,
        runner=lambda step, _: StepResult(step.id, 9 if step.id == "theories" else 0, 0),
    )
    assert tuple(result.step for result in results) == ("theories",)
    assert not results[0].ok


@spec_test(covers=("planning_gate#T4",))
def test_theory_catalog_cli_and_catalog_alias_use_the_same_composition(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    import tools.planning_gate.__main__ as main_module

    steps = {
        step_id: CommandStep(step_id, step_id, ("fixture", step_id), 1)
        for step_id in ("theories", "theory-manifest", "munit-catalog")
    }
    calls: list[str] = []
    monkeypatch.setattr(main_module, "registered_commands", lambda _: steps)
    monkeypatch.setattr(
        main_module,
        "run_theory_catalog",
        lambda _, *, steps: calls.append("theory-catalog")
        or (StepResult("theories", 0, 0), StepResult("theory-manifest", 0, 0)),
    )
    monkeypatch.setattr(
        main_module, "run_step", lambda step, _: calls.append(step.id) or StepResult(step.id, 0, 0)
    )

    assert _main(["--root", str(ROOT), "theory-catalog"]) == 0
    assert calls == ["theory-catalog"]
    calls.clear()
    assert _main(["--root", str(ROOT), "catalog", "theory"]) == 0
    assert calls == ["theory-catalog"]
    calls.clear()
    assert _main(["--root", str(ROOT), "catalog", "all"]) == 0
    assert calls == ["munit-catalog", "theory-catalog"]
