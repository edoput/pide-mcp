"""Command-line interface for the planning gate package."""

from __future__ import annotations

import argparse
from dataclasses import replace
import json
from pathlib import Path
import subprocess
import sys

from .document import DocumentError, PlanFormat, load_plan, load_repository
from .commands import CommandError, registered_commands, run_step
from .done import DoneError, check_static_closure, run_done
from .files import write_atomic_text
from .legacy import (
    LegacyDebtError,
    compare,
    generate_baseline,
    read_baseline,
    require_accepted,
)
from .labels import audit_is_fresh, generate_audit, validate_labels
from .matrix import MatrixError, discover
from .refinements import RefinementError, validate_refinements
from .registry import format_report, generate, stale_outputs


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="tools/planning-gate")
    parser.add_argument(
        "--root", type=Path, default=Path.cwd(), help="repository root (default: cwd)"
    )
    commands = parser.add_subparsers(dest="command", required=True)
    plan = commands.add_parser("plan", help="inspect plan documents")
    plan_commands = plan.add_subparsers(dest="plan_command", required=True)

    check = plan_commands.add_parser("check", help="validate plan documents")
    check.add_argument(
        "--require-v1", action="store_true", help="reject bootstrap and prose plans"
    )

    dump = plan_commands.add_parser("dump", help="emit canonical plan metadata as JSON")
    dump.add_argument("paths", type=Path, nargs="*", help="plans to dump; default: all")
    dump.add_argument("--output", type=Path, help="write JSON atomically to this path")

    registry = commands.add_parser("registry", help="manage generated claim registries")
    registry_commands = registry.add_subparsers(dest="registry_command", required=True)
    registry_commands.add_parser("generate", help="regenerate compatibility registries")
    registry_commands.add_parser("check", help="check compatibility registry freshness")

    labels = commands.add_parser("labels", help="validate claim semantics and migration")
    label_commands = labels.add_subparsers(dest="label_command", required=True)
    label_check = label_commands.add_parser("check", help="validate canonical claim semantics")
    label_check.add_argument(
        "--completion", action="store_true", help="reject every completion blocker"
    )
    audit = label_commands.add_parser("audit", help="manage the legacy migration audit")
    audit_commands = audit.add_subparsers(dest="audit_command", required=True)
    audit_commands.add_parser("generate", help="regenerate the migration audit")
    audit_commands.add_parser("check", help="check migration-audit freshness")

    matrix = commands.add_parser("matrix", help="discover static verification coverage")
    matrix_commands = matrix.add_subparsers(dest="matrix_command", required=True)
    matrix_dump = matrix_commands.add_parser("dump", help="emit the static matrix as JSON")
    matrix_dump.add_argument("--output", type=Path, help="write JSON atomically")
    matrix_commands.add_parser("check", help="check producers, links, and legacy debt")
    legacy = matrix_commands.add_parser("legacy", help="manage the one-time debt baseline")
    legacy_commands = legacy.add_subparsers(dest="legacy_command", required=True)
    legacy_commands.add_parser("generate", help="create plans/legacy_unlinked.csv once")
    legacy_commands.add_parser("check", help="check the reviewed legacy-debt ratchet")

    refinements = commands.add_parser(
        "refinements", help="validate plan-owned specification refinements"
    )
    refinement_commands = refinements.add_subparsers(
        dest="refinement_command", required=True
    )
    refinement_check = refinement_commands.add_parser("check")
    refinement_check.add_argument(
        "--completion", action="store_true", help="reject every open refinement"
    )

    commands.add_parser("commands", help="list registered build and test steps")
    commands.add_parser("build", help="compile production and test Scala artifacts")
    catalog = commands.add_parser("catalog", help="materialize static test catalogs")
    catalog.add_argument("producer", choices=("munit", "theory", "all"))
    commands.add_parser("static", help="run static closure and compatibility spec checks")
    test_layer = commands.add_parser("test-layer", help="run one complete test layer")
    test_layer.add_argument(
        "layer",
        choices=("tooling-unit", "scala-unit", "heap", "bridge", "ml-unit", "e2e"),
    )
    commands.add_parser("theories", help="build all registered theory sessions")
    e2e = commands.add_parser("e2e", help="run procedural end-to-end cases")
    e2e_selection = e2e.add_mutually_exclusive_group(required=True)
    e2e_selection.add_argument("--all", action="store_true")
    e2e_selection.add_argument("--filter", help="diagnostic name or plan-ID substring")
    commands.add_parser("done", help="run the fixed repository completion pipeline")
    return parser


def _main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    root = args.root.resolve()
    try:
        if args.command == "done":
            return 0 if run_done(root).ok else 1

        if args.command in {
            "commands",
            "build",
            "catalog",
            "static",
            "test-layer",
            "theories",
            "e2e",
        }:
            steps = registered_commands(root)
            if args.command == "commands":
                for step in steps.values():
                    layers = ",".join(step.layers) if step.layers else "preparation"
                    print(f"{step.id:<16} {layers:<20} {step.description}")
                return 0
            if args.command == "build":
                return 0 if run_step(steps["scala-build"], root).ok else 1
            if args.command == "catalog":
                selected = {
                    "munit": ("munit-catalog",),
                    "theory": ("theory-catalog",),
                    "all": ("munit-catalog", "theory-catalog"),
                }[args.producer]
                return 0 if all(run_step(steps[value], root).ok for value in selected) else 1
            if args.command == "static":
                report = check_static_closure(root)
                print(
                    "static closure: PASS "
                    f"({report.plans} plans; {report.tests} tests; "
                    f"{report.legacy_gaps} reviewed legacy gaps)"
                )
                return 0 if run_step(steps["spec-gate"], root).ok else 1
            if args.command == "theories":
                return 0 if run_step(steps["theories"], root).ok else 1
            if args.command == "test-layer":
                step_id = "theories" if args.layer == "ml-unit" else args.layer
                return 0 if run_step(steps[step_id], root).ok else 1
            if args.command == "e2e":
                step = steps["e2e"]
                if args.filter is not None:
                    step = replace(
                        step,
                        description="run a diagnostic filtered end-to-end selection",
                        argv=step.argv[:-1] + ("--filter", args.filter),
                    )
                return 0 if run_step(step, root).ok else 1

        if args.command == "refinements":
            documents = load_repository(root)
            report = validate_refinements(
                documents, root, require_completion=args.completion
            )
            print(
                "refinements check: PASS "
                f"({report.refinements} records; {len(report.open)} open; "
                f"{report.folded} folded)"
            )
            return 0

        if args.command == "matrix":
            result = discover(root)
            if args.matrix_command == "dump":
                rendered = json.dumps(
                    result.to_json(), indent=2, sort_keys=True, ensure_ascii=False
                ) + "\n"
                if args.output:
                    output = args.output if args.output.is_absolute() else root / args.output
                    write_atomic_text(output, rendered)
                    try:
                        display = output.relative_to(root)
                    except ValueError:
                        display = output
                    print(f"wrote {display}")
                else:
                    sys.stdout.write(rendered)
                return 0

            baseline_path = root / "plans/legacy_unlinked.csv"
            if args.matrix_command == "legacy" and args.legacy_command == "generate":
                revision = subprocess.run(
                    ["git", "rev-parse", "HEAD"],
                    cwd=root,
                    check=True,
                    capture_output=True,
                    text=True,
                ).stdout.strip()
                count = generate_baseline(baseline_path, result, revision)
                print(f"wrote plans/legacy_unlinked.csv ({count} reviewed gaps)")
                return 0

            if result.missing_producers:
                names = ", ".join(result.missing_producers)
                raise MatrixError(f"missing verification producers: {names}")

            baseline = read_baseline(baseline_path)
            ratchet = compare(result, baseline)
            require_accepted(ratchet)
            print(
                "matrix check: PASS "
                f"({sum(len(value.tests) for value in result.producers)} tests; "
                f"{len(result.missing_coverage)} reviewed legacy gaps)"
            )
            return 0

        if args.command == "registry":
            if args.registry_command == "generate":
                report = generate(root)
                print("wrote plans/ASSUMPTIONS")
                print(format_report(report))
                return 0
            report, stale = stale_outputs(root)
            if stale:
                names = ", ".join(path.relative_to(root).as_posix() for path in stale)
                print(f"registry check: FAIL: stale outputs: {names}", file=sys.stderr)
                return 1
            print(f"registry check: PASS ({len(report.rows)} claims)")
            return 0

        if args.command == "labels":
            documents = load_repository(root)
            if args.label_command == "check":
                report = validate_labels(documents, require_completion=args.completion)
                print(
                    "labels check: PASS "
                    f"({report.claims} canonical claims in {report.v1_plans} v1 plans; "
                    f"{report.legacy_plans} legacy plans; {len(report.blockers)} blockers)"
                )
                return 0
            if args.audit_command == "generate":
                count = generate_audit(documents, root)
                print(f"wrote plans/migration/label-audit.json ({count} records)")
                return 0
            fresh, count = audit_is_fresh(documents, root)
            if not fresh:
                print(
                    "labels audit: FAIL: stale plans/migration/label-audit.json",
                    file=sys.stderr,
                )
                return 1
            print(f"labels audit: PASS ({count} records)")
            return 0

        if args.plan_command == "check":
            documents = load_repository(root, allow_legacy=not args.require_v1)
            counts = {value: 0 for value in PlanFormat}
            for document in documents:
                counts[document.format] += 1
            summary = ", ".join(
                f"{counts[value]} {value.value}" for value in PlanFormat if counts[value]
            )
            print(f"plan check: PASS ({len(documents)} documents: {summary})")
            return 0

        if args.paths:
            documents = tuple(
                load_plan(path if path.is_absolute() else root / path, root)
                for path in args.paths
            )
        else:
            documents = load_repository(root)
        payload = {
            "schema": "isabelle-mcp.plan-catalog/v1",
            "plans": [document.to_json(root) for document in documents],
        }
        rendered = json.dumps(payload, indent=2, sort_keys=True, ensure_ascii=False) + "\n"
        if args.output:
            output = args.output if args.output.is_absolute() else root / args.output
            write_atomic_text(output, rendered)
        else:
            sys.stdout.write(rendered)
        return 0
    except (
        DocumentError,
        CommandError,
        DoneError,
        LegacyDebtError,
        MatrixError,
        RefinementError,
        OSError,
        subprocess.SubprocessError,
    ) as ex:
        print(f"planning gate: FAIL: {ex}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(_main())
