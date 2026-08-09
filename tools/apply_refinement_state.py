#!/usr/bin/env python3
"""Give `spec refinement:` a close operation.

A plan writes `spec refinement:` when it discovers the spec is wrong or coarse
in some detail, with the intent that the correction is folded back. There are
14 such markers and 11 occurrences of the word "folded" in the whole tree, and
nothing records whether any given one ever WAS folded. It is a queue with no
close operation, which is why it only grows.

This adds the state, defaulting every existing marker to OPEN:

    spec refinement [OPEN]: the spec's annotation blanket puts all ...
    spec refinement [FOLDED 2026-08-09]: ...

Closing one is then a one-word edit at the site, and tools/spec_gate.py fails
on any marker carrying neither state -- so a new refinement cannot be written
without declaring one.

Prose mentions of the phrase ("see spec refinements", a section heading) are
left alone: only a marker followed by a colon is a queue entry.

usage: tools/apply_refinement_state.py [--apply]
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
APPLY = "--apply" in sys.argv

# a queue entry is the phrase followed directly by a colon; `spec refinements
# (fold back ...)` as a heading, and `see spec refinements`, are prose.
ENTRY = re.compile(r"spec refinement(?!s)\s*:")
ALREADY = re.compile(r"spec refinement\s*\[(?:OPEN|FOLDED[^\]]*)\]\s*:")

targets = [ROOT / "spec"] + sorted(p for p in (ROOT / "plans").iterdir() if p.is_file())
total, per_file = 0, []

for path in targets:
    if not path.exists():
        continue
    text = path.read_text()
    if not ENTRY.search(text):
        continue
    new, n = ENTRY.subn("spec refinement [OPEN]:", text)
    # do not double-stamp anything already carrying a state
    new = re.sub(r"spec refinement \[OPEN\]\s*:\s*\[(OPEN|FOLDED[^\]]*)\]",
                 r"spec refinement [\1]:", new)
    if n:
        per_file.append(f"  {path.relative_to(ROOT)}: {n}")
        total += n
        if APPLY:
            path.write_text(new)

print("\n".join(per_file))
print(f"\n{total} markers stamped OPEN across {len(per_file)} files")
print("applied" if APPLY else "(dry run -- pass --apply)")
