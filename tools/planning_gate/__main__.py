"""Command-line interface for the planning gate package."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import sys
import tempfile

from .document import DocumentError, PlanFormat, load_plan, load_repository
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
    return parser


def _write_atomic(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    mode = path.stat().st_mode & 0o777 if path.exists() else 0o644
    descriptor, name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = Path(name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            os.fchmod(stream.fileno(), mode)
            stream.write(text)
            stream.flush()
            os.fsync(stream.fileno())
        temporary.replace(path)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise


def _main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    root = args.root.resolve()
    try:
        if args.command == "registry":
            if args.registry_command == "generate":
                report = generate(root)
                print("wrote plans/ASSUMPTIONS and mcp/Tools/assumption_ids.ML")
                print(format_report(report))
                return 0
            report, stale = stale_outputs(root)
            if stale:
                names = ", ".join(path.relative_to(root).as_posix() for path in stale)
                print(f"registry check: FAIL: stale outputs: {names}", file=sys.stderr)
                return 1
            print(f"registry check: PASS ({len(report.rows)} claims)")
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
            _write_atomic(output, rendered)
        else:
            sys.stdout.write(rendered)
        return 0
    except (DocumentError, OSError) as ex:
        print(f"plan gate: FAIL: {ex}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(_main())
