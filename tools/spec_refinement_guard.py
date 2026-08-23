#!/usr/bin/env python3
"""PostToolUse guard: a spec refinement written instead of raised.

A refinement is an INTERRUPT, not a queue entry (plans/README, decision
2026-08-23). A model cannot be relied on to notice that the code contradicts
the spec -- only reading both does that. But the SYMPTOM of skipping the
interrupt is exactly detectable: a plan gains a marker in a state that means
"someone will deal with this later".

What is caught:

  [OPEN]  legacy. Nothing new is ever written in it.

What is deliberately NOT caught:

  [BLOCKING]  writing it is the correct move -- it means you stopped.
  [DEFERRED]  a deferral the user approved is equally correct, and this script
              cannot tell it from one assumed on their behalf. Firing on twelve
              legitimate edits during the triage unit is how a guard gets
              ignored, which is the failure mode the convention exists to
              remove. `.claude/rules/spec-refinement.md` carries that half.

Only text being ADDED is examined: `new_string` for Edit, `content` for Write,
the edits' `new_string` values for MultiEdit. Reading the whole payload would
drag in `old_string`, so converting an existing [OPEN] marker to [FOLDED] --
the triage unit's entire job -- would trip the guard on a correct edit.

usage (hook):    tools/spec_refinement_guard.py < payload.json
                 exit 0 = fine, exit 2 = feedback on stderr

usage (import):  from spec_refinement_guard import inspect_payload
                 verdict = inspect_payload(json.load(f))
                 verdict.blocked, verdict.reason, verdict.message
"""

from __future__ import annotations

import json
import re
import sys
from dataclasses import dataclass
from typing import Any

__all__ = ["Verdict", "added_text", "targets_plan", "writes_open_marker",
           "inspect_payload", "main"]

# `spec refinement [OPEN` in any case, with any spacing. Deliberately not
# anchored on the closing bracket: `[OPEN]`, `[ OPEN ]` and a malformed `[OPEN`
# are all the same mistake, and spec_gate.py reports the malformed spelling
# separately.
OPEN_MARKER = re.compile(r"spec\s+refinement\s*\[\s*OPEN", re.IGNORECASE)

FEEDBACK = """\
spec-refinement guard: this edit writes a new [OPEN] refinement marker.

[OPEN] is legacy. A refinement is an interrupt, not a queue entry
(.claude/rules/spec-refinement.md), so there is no state that means
"someone will deal with this later".

If the spec is false or contradicts what the implementation must do: write
[BLOCKING], stop implementing that thread, and ask the user. If the spec is
merely silent, that is a gap the plan may fill -- drop the marker entirely.
Only the user decides on [DEFERRED <date>: <reason>]; never assume one.\
"""

EXIT_OK = 0
EXIT_FEEDBACK = 2


@dataclass(frozen=True)
class Verdict:
    """The guard's decision about one PostToolUse payload."""

    blocked: bool
    reason: str
    message: str = ""

    @property
    def exit_code(self) -> int:
        return EXIT_FEEDBACK if self.blocked else EXIT_OK


def _str(value: Any) -> str:
    return value if isinstance(value, str) else ""


def added_text(tool_input: dict[str, Any]) -> str:
    """The text this tool call ADDS to the file, and nothing else.

    Edit carries new_string, Write carries content, MultiEdit carries a list of
    edits. Never returns old_string: see the module docstring.
    """
    for key in ("new_string", "content"):
        text = _str(tool_input.get(key))
        if text:
            return text
    edits = tool_input.get("edits")
    if isinstance(edits, list):
        return "\n".join(
            _str(e.get("new_string")) for e in edits if isinstance(e, dict))
    return ""


def targets_plan(file_path: str) -> bool:
    """Whether this path is a plan. Plans are the only place the marker lives."""
    if not file_path:
        return False
    parts = file_path.replace("\\", "/").split("/")
    return "plans" in parts[:-1]


def writes_open_marker(text: str) -> bool:
    return bool(OPEN_MARKER.search(text))


def inspect_payload(payload: dict[str, Any]) -> Verdict:
    tool_input = payload.get("tool_input")
    if not isinstance(tool_input, dict):
        return Verdict(False, "no tool_input")

    path = _str(tool_input.get("file_path"))
    if not targets_plan(path):
        return Verdict(False, "not a plan file")

    if not writes_open_marker(added_text(tool_input)):
        return Verdict(False, "no new [OPEN] marker")

    return Verdict(True, "new [OPEN] marker", FEEDBACK)


def main() -> int:
    raw = sys.stdin.read()
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError:
        # A guard that fails closed would block every edit the moment the hook
        # payload shape changes. Stay out of the way instead.
        return EXIT_OK
    if not isinstance(payload, dict):
        return EXIT_OK

    verdict = inspect_payload(payload)
    if verdict.blocked:
        print(verdict.message, file=sys.stderr)
    return verdict.exit_code


if __name__ == "__main__":
    raise SystemExit(main())
