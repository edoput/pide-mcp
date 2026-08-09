#!/usr/bin/env python3
"""Turn a test theory's prose section headings into CHECKED assumption citations.

MCP_Repl_Tests.thy already records which assumptions each section discharges --
`section <open>repl_init (plans/repl_init): T1..T6<close>` -- but as prose. Nothing
checks it, and the first four sections are unqualified (`T2:`), resolving only
via the theory header naming plans/repl_list.

This adds a `text <open>covers ...<close>` line under each section, citing the same
assumptions through the \\<^assumption> antiquotation, which fails the build on
an unknown id. The heading prose is left exactly as it was: it reads well and
the citation line is what tooling and the build consume.

usage: tools/cite_assumptions.py FILE [--default-plan repl_list] [--apply]
       without --apply it prints the diff it would make
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
REGISTRY = ROOT / "plans/ASSUMPTIONS"

OPEN, CLOSE = "\\<open>", "\\<close>"
AQ = "\\<^assumption>"

SECTION = re.compile(r"^section\s+" + re.escape(OPEN) + r"(.*)$")
# `(plans/repl_init)` or `plans/repl_init`
PLAN = re.compile(r"plans/(\w+)")
# T1..T6 | T1, T3 | T1/T3 | T2
RANGE = re.compile(r"\b([AITDQ])(\d+)\s*\.\.\s*[AITDQ]?(\d+)")
SINGLE = re.compile(r"\b([AITDQ]\d+)\b")


def known_ids():
    ids = set()
    for line in REGISTRY.read_text().splitlines():
        m = re.match(r"^(\S+#[AITDQ]\d+)\s", line)
        if m:
            ids.add(m.group(1))
    return ids


def labels_in(text: str):
    """Every label a heading mentions, ranges expanded, in order, deduped."""
    out, seen = [], set()
    spans = []
    for m in RANGE.finditer(text):
        kind, lo, hi = m.group(1), int(m.group(2)), int(m.group(3))
        spans.append(m.span())
        for n in range(lo, hi + 1):
            lab = f"{kind}{n}"
            if lab not in seen:
                seen.add(lab)
                out.append(lab)
    masked = list(text)
    for a, b in spans:
        for i in range(a, b):
            masked[i] = " "
    for m in SINGLE.finditer("".join(masked)):
        if m.group(1) not in seen:
            seen.add(m.group(1))
            out.append(m.group(1))
    return out


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    apply = "--apply" in sys.argv
    default_plan = "repl_list"
    if "--default-plan" in sys.argv:
        default_plan = sys.argv[sys.argv.index("--default-plan") + 1]
        args = [a for a in args if a != default_plan]

    path = pathlib.Path(args[0])
    ids = known_ids()
    lines = path.read_text().splitlines(keepends=True)

    out, added, skipped, unknown = [], 0, [], []
    i = 0
    while i < len(lines):
        line = lines[i]
        out.append(line)
        m = SECTION.match(line)
        i += 1
        if not m:
            continue

        # a heading may wrap; gather until the closing bracket
        head = m.group(1)
        while CLOSE not in head and i < len(lines):
            head += " " + lines[i].strip()
            out.append(lines[i])
            i += 1
        head_text = head.split(CLOSE)[0]

        # already cited?
        if i < len(lines) and AQ in lines[i]:
            continue

        plan_m = PLAN.search(head_text)
        plan = plan_m.group(1) if plan_m else default_plan
        labs = labels_in(head_text)
        if not labs:
            skipped.append(head_text.strip()[:60])
            continue

        cites, bad = [], []
        for lab in labs:
            ident = f"{plan}#{lab}"
            (cites if ident in ids else bad).append(ident)
        unknown.extend(bad)
        if not cites:
            skipped.append(head_text.strip()[:60])
            continue

        body = " ".join(f"{AQ}{OPEN}{c}{CLOSE}" for c in cites)
        # keep lines civil: wrap at ~78 columns
        wrapped, cur = [], "text " + OPEN + "covers"
        for tok in body.split(" "):
            if len(cur) + len(tok) + 1 > 78:
                wrapped.append(cur)
                cur = "  " + tok
            else:
                cur += " " + tok
        wrapped.append(cur + CLOSE)
        out.append("\n".join(wrapped) + "\n")
        added += 1

    print(f"{path}: {added} sections cited, {len(skipped)} skipped")
    for s in skipped:
        print(f"    skipped (no label in heading): {s}")
    if unknown:
        print(f"  !! {len(unknown)} ids not in the registry: {sorted(set(unknown))}")

    if apply:
        path.write_text("".join(out))
        print(f"  applied to {path}")
    else:
        print("  (dry run -- pass --apply to write)")
    return 1 if unknown else 0


if __name__ == "__main__":
    sys.exit(main())
