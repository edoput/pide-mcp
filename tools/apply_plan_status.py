#!/usr/bin/env python3
"""Normalise each plan to ONE authoritative status field.

Status was recorded in up to three disagreeing places: the plans/README
checkbox, the plan's header line, and a later in-file `status:` line written
when the work actually landed. The header was reliably the stale one -- 21 of
45 plans still said `planned` for tools shipped weeks earlier, plans/repl_list
(the pilot every other plan was copied from) among them.

After this there is exactly one `status:` line per plan, in the header, and it
is correct. The later lines keep every word they had but are relabelled
`landed:` so they no longer compete for the name -- they carry real detail
(dates, which assumptions went green) that would be lost by deleting them.

usage: tools/apply_plan_status.py [--apply]
"""

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
PLANS = ROOT / "plans"
DATA = ROOT / "tools/refdata/plan-status.json"
APPLY = "--apply" in sys.argv

resolved = {p["plan"]: p for p in json.loads(DATA.read_text())["plans"]}
DONE = {"done", "implemented", "green", "shipped"}
STATUS_RE = re.compile(r"^(\s*)status:(\s*)(.*)$", re.I)

changed_files, header_fixed, relabelled, box_fixed = 0, 0, 0, 0
report = []

for path in sorted(PLANS.iterdir()):
    if not path.is_file() or path.name in ("README", "ASSUMPTIONS"):
        continue
    info = resolved.get(path.name)
    if not info:
        report.append(f"  !! no resolved status for {path.name}")
        continue

    lines = path.read_text().splitlines(keepends=True)
    hits = [i for i, l in enumerate(lines) if STATUS_RE.match(l)]
    if not hits:
        report.append(f"  !! {path.name}: no status: line at all")
        continue

    true_status = info["status"]

    # Only carry a date the evidence actually supports. plans/repl_list is
    # `done` per its README box while BOTH its status lines still say
    # `planned (2026-07-09)` -- so 2026-07-09 is when it was PLANNED and the
    # done date was never recorded. Reusing it would assert a fact the tree
    # does not contain, which is the class of error this whole exercise is
    # about. A date is corroborated only when it came from a line asserting
    # the resolved status.
    def norm(w):
        w = (w or "").strip().lower()
        return "done" if w in DONE else w

    corroborated = (norm(info.get("later_says")) == true_status
                    or norm(info.get("header_says")) == true_status)
    date = info.get("date") if corroborated else None
    suffix = f" ({date})" if date else " (date not recorded)"
    new_header = f"status: {true_status}{suffix}\n"

    touched = False
    if lines[hits[0]] != new_header:
        old = lines[hits[0]].strip()
        lines[hits[0]] = new_header
        header_fixed += 1
        touched = True
        report.append(f"  {path.name:26s} {old!r} -> {new_header.strip()!r}")

    # later status: lines keep their text, lose the competing name
    for i in hits[1:]:
        m = STATUS_RE.match(lines[i])
        lines[i] = f"{m.group(1)}landed:{m.group(2)}{m.group(3)}\n"
        relabelled += 1
        touched = True

    if touched:
        changed_files += 1
        if APPLY:
            path.write_text("".join(lines))

# plans/README checkboxes follow the resolved status
readme_path = PLANS / "README"
readme = readme_path.read_text()


def fix_box(m):
    global box_fixed
    mark, name = m.group(1), m.group(2)
    info = resolved.get(name)
    if not info:
        return m.group(0)
    want = "x" if info["status"] in DONE else " "
    if want != mark:
        box_fixed += 1
        return f"- [{want}] {name}"
    return m.group(0)


readme_new = re.sub(r"- \[([ x])\]\s+(\S+)", fix_box, readme)
if APPLY and readme_new != readme:
    readme_path.write_text(readme_new)

print("\n".join(report[:40]))
if len(report) > 40:
    print(f"  ... and {len(report) - 40} more")
print(f"\nfiles changed      {changed_files}")
print(f"headers corrected  {header_fixed}")
print(f"later status: -> landed:  {relabelled}")
print(f"README checkboxes corrected  {box_fixed}")
print("applied" if APPLY else "(dry run -- pass --apply)")
