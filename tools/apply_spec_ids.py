#!/usr/bin/env python3
"""Give every spec section a stable id.

WHY, demonstrated rather than argued: the explorer built last week had to
address spec sections by line number, because the file offers nothing else.
Those keys were minted against a 3761-line spec. The committed spec is 3546
lines, and all 13 probe line numbers land on unrelated content. The keys were
stale before they were a day old. A section needs a name that survives editing
above it.

THE SCHEME (decided by the project owner):

    D-<date>-<slug>   a section that records a DECISION
    S-<slug>          a section that is narrative or structure

<date> is the section's own date, which the headings already carry -- "(decided
2026-07-21)", "(measured 2026-07-30)", "(added 2026-07-07)". This extends how
the prose already refers to decisions rather than inventing a parallel scheme.
Where a decision section carries no date anywhere, the id is D-undated-<slug>
and the gate can find it.

The id is written as a metadata block directly under the heading rule, which
keeps the heading text untouched and gives supersedes/superseded_by somewhere
to live:

    the catalog is the long pole, not the build (measured 2026-07-30)
    -----------------------------------------------------------------
    id: D-2026-07-30-catalog-long-pole
    supersedes: D-2026-07-21-server-startup-readiness

usage: tools/apply_spec_ids.py [--apply]
"""

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SPEC = ROOT / "spec"
# already-extracted supersede links, keyed by heading text (NOT line number)
EXPLORER = pathlib.Path(
    "/home/edoput/repo/isabelle-mcp/.claude/worktrees/spec-explorer/explorer/data/dataset.json")
APPLY = "--apply" in sys.argv

RULE = re.compile(r"^[-=]{3,}\s*$")
DATE = re.compile(r"\b(\d{4}-\d{2}-\d{2})\b")
DATED_VERB = re.compile(
    r"\b(?:decided|measured|added|recorded|assessed|verified|updated|brainstormed)\s+(\d{4}-\d{2}-\d{2})",
    re.I)

STOP = {"the", "a", "an", "and", "or", "of", "to", "in", "for", "on", "as",
        "is", "are", "it", "its", "we", "this", "that", "with", "not", "no",
        "how", "what", "why", "at", "by", "from", "into", "one", "two"}

# headings that are structure, not decisions, however they are worded
NARRATIVE = {"goal", "directories", "status", "results", "components / deliverables",
             "implementation order", "testing", "acceptance criteria", "reuse",
             "do not reuse", "spike results", "skills impact"}
DECISION_HINT = re.compile(
    r"\b(decid\w*|decision\w*|measured|supersed\w*|policy|rule|pivot|instead|"
    r"contemplated|pending|migration|recoding|activation|flagship|bridge|"
    r"commands?|resources?|persisting|registries|language|extensibility)\b", re.I)

# "goal", "testing" and "acceptance criteria" each occur once per phase. A
# numeric suffix (S-goal-2) is a poor address -- nobody can tell which phase it
# names -- so duplicates are disambiguated by their enclosing phase instead.
PHASE_HEAD = re.compile(r"^phase\s+(\d\+?)", re.I)


def slugify(heading: str) -> str:
    h = re.sub(r"\([^)]*\)", " ", heading)          # drop parenthetical dates
    h = re.sub(r"[^a-zA-Z0-9]+", " ", h).lower()
    words = [w for w in h.split() if w and w not in STOP]
    return "-".join(words[:5]) or "section"


def sections(lines):
    out = []
    for i, line in enumerate(lines):
        if RULE.match(line) and i > 0 and lines[i - 1].strip():
            heading = lines[i - 1].strip()
            if heading.startswith("(") and i > 1 and lines[i - 2].strip():
                heading = lines[i - 2].strip() + " " + heading
            out.append((i, heading))
    return out


def body_of(lines, rule_idx, next_rule_idx):
    return "\n".join(lines[rule_idx + 1:next_rule_idx - 1 if next_rule_idx else len(lines)])


def main():
    lines = SPEC.read_text().splitlines()
    secs = sections(lines)
    rule_idxs = [i for i, _ in secs]

    # heading -> supersede links, carried over from the explorer extraction
    links = {}
    if EXPLORER.exists():
        data = json.loads(EXPLORER.read_text())
        by_line = {d["id"]: d for d in data["decisions"]}
        for d in data["decisions"]:
            h = (d.get("heading") or "").strip()
            if not h:
                continue
            links[h] = {
                "supersedes": [by_line[r]["heading"].strip()
                               for r in (d.get("supersedes") or []) if r in by_line],
                "superseded_by": [by_line[r]["heading"].strip()
                                  for r in (d.get("superseded_by") or []) if r in by_line],
            }

    assigned, used, phase = {}, set(), None
    for n, (idx, heading) in enumerate(secs):
        pm = PHASE_HEAD.match(heading.strip())
        if pm:
            phase = f"phase-{pm.group(1).replace('+', 'plus')}"
        nxt = rule_idxs[n + 1] if n + 1 < len(rule_idxs) else None
        body = body_of(lines, idx, nxt)
        low = heading.lower().strip()

        is_decision = (low not in NARRATIVE
                       and not any(low.startswith(k) for k in ("phase ", "out of scope"))
                       and (DATED_VERB.search(heading) or DATE.search(heading)
                            or bool(DECISION_HINT.search(heading))
                            or bool(DATED_VERB.search(body[:600]))))

        date = None
        m = DATED_VERB.search(heading) or DATE.search(heading)
        if m:
            date = m.group(1)
        elif is_decision:
            # search the whole section, not a prefix: several sections state
            # their date well down the body ("argument encoding (decided
            # 2026-07-09)"), and the earliest dated verb in a section is that
            # section's own date. D-undated- is reserved for sections that
            # genuinely record no date anywhere, which the gate can then find.
            m = DATED_VERB.search(body)
            date = m.group(1) if m else None

        slug = slugify(heading)
        base = f"D-{date}-{slug}" if is_decision and date else (
            f"D-undated-{slug}" if is_decision else f"S-{slug}")
        ident = base
        if ident in used and phase:
            ident = f"{base}-{phase}"
        k = 2
        while ident in used:
            ident = f"{base}-{phase}-{k}" if phase else f"{base}-{k}"
            k += 1
        used.add(ident)
        assigned[idx] = (heading, ident, is_decision)

    heading_to_id = {h: i for _, (h, i, _) in assigned.items()}

    out, ins = [], 0
    for i, line in enumerate(lines):
        out.append(line)
        if i in assigned:
            heading, ident, _ = assigned[i]
            # already stamped?
            if i + 1 < len(lines) and lines[i + 1].startswith("id: "):
                continue
            block = [f"id: {ident}"]
            link = links.get(heading, {})
            for field in ("supersedes", "superseded_by"):
                refs = [heading_to_id[h] for h in link.get(field, []) if h in heading_to_id]
                if refs:
                    block.append(f"{field}: {' '.join(refs)}")
            out.extend(block)
            ins += 1

    dec = sum(1 for _, (_, _, d) in assigned.items() if d)
    undated = [i for _, (_, i, _) in assigned.items() if i.startswith("D-undated-")]
    linked = sum(1 for _, (h, _, _) in assigned.items()
                 if links.get(h, {}).get("supersedes") or links.get(h, {}).get("superseded_by"))

    print(f"sections      {len(secs)}")
    print(f"  decisions   {dec}   narrative {len(secs) - dec}")
    print(f"  undated     {len(undated)} {undated[:5]}")
    print(f"  with links  {linked}")
    print(f"blocks inserted {ins}")
    show = assigned.items() if "--list" in sys.argv else list(assigned.items())[:10]
    for _, (h, ident, d) in show:
        print(f"  {'D' if d else 'S'}  {ident:54s} {h[:52]}")

    if APPLY:
        SPEC.write_text("\n".join(out) + "\n")
        print("applied")
    else:
        print("(dry run -- pass --apply)")


if __name__ == "__main__":
    main()
