"""Command line for body-free discovery and isolated e2e execution."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

from tools.planning_gate.files import write_atomic_text

from .registry import RegistryError, discover_cases, producer, repository_root
from .runner import execute_worker, run_cases, select_cases


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(prog="python -m mcp.test.e2e")
    commands = result.add_subparsers(dest="command", required=True)
    manifest = commands.add_parser("manifest", help="discover metadata without running cases")
    manifest.add_argument("--output", type=Path)
    run = commands.add_parser("run", help="run registered process cases")
    selection = run.add_mutually_exclusive_group(required=True)
    selection.add_argument("--all", action="store_true", help="run the completion suite")
    selection.add_argument("--filter", help="diagnostic substring or plan-ID filter")
    worker = commands.add_parser("_worker", help=argparse.SUPPRESS)
    worker.add_argument("identity")
    return result


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    root = repository_root()
    try:
        if args.command == "_worker":
            return execute_worker(args.identity)
        if args.command == "manifest":
            rendered = json.dumps(
                producer(root).to_json(), indent=2, sort_keys=True, ensure_ascii=False
            ) + "\n"
            if args.output:
                output = args.output if args.output.is_absolute() else root / args.output
                write_atomic_text(output, rendered)
                print(f"wrote {output}")
            else:
                sys.stdout.write(rendered)
            return 0
        cases = discover_cases(root)
        selected = select_cases(cases, args.filter)
        return run_cases(root, selected, filtered=not args.all)
    except (OSError, RegistryError) as ex:
        print(f"e2e: FAIL: {ex}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
