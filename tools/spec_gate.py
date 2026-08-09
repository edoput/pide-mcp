#!/usr/bin/env python3
"""Drift gate for the prose layer: spec, CHANGELOG, plans/.

The same shape as this project's existing gates -- it exits nonzero, so it can
be wired into CI or run beside `isabelle mcp_test`. Every check here exists
because the corresponding drift was MEASURED in this repository, not because it
seemed like a good idea:

  1. registry freshness   plans/ASSUMPTIONS and assumption_ids.ML must match
                          plans/. Otherwise a renamed assumption leaves stale
                          citations that still build.
  2. one status per plan  status was recorded in up to three places (README
                          checkbox, header line, a later in-file line) and 21
                          of 45 plans disagreed, the header being the stale one.
  3. spec section ids     every heading carries an id; ids are unique; every
                          supersedes/superseded_by resolves. Before this, the
                          only way to address a section was its line number,
                          which changes whenever anyone inserts a paragraph.
  4. refinement queue     `spec refinement:` had 14 occurrences and no close
                          operation, so the queue only ever grew. Each must now
                          carry OPEN or FOLDED <date>.
  5. citation coverage    reported, not enforced: how many assumption ids are
                          cited by at least one test. Demanding completeness
                          would invite wrong-but-checked citations, which are
                          worse than absent ones.

usage: tools/spec_gate.py [--quiet]
"""

import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SPEC = ROOT / "spec"
CHANGELOG = ROOT / "CHANGELOG"
PLANS = ROOT / "plans"
REGISTRY = PLANS / "ASSUMPTIONS"

QUIET = "--quiet" in sys.argv
failures: list[str] = []
notes: list[str] = []

ID_RE = re.compile(r"^(?:D-(?:\d{4}-\d{2}-\d{2}|undated)|S)-[a-z0-9-]+$")
META = re.compile(r"^(id|supersedes|superseded_by|status):\s*(.*)$")
RULE = re.compile(r"^[-=]{3,}\s*$")

# The controlled vocabulary for a plan's status. "implemented" and "green" are
# spellings the tree already uses for done; they are accepted rather than
# churned, since the point is one AUTHORITATIVE field, not one wording.
DONE_WORDS = {"done", "implemented", "green", "shipped"}

# MCP_Assumption.thy is the mechanism, not a test: its self-check deliberately
# names a nonexistent id to prove that unknown ids are rejected. Scanning it for
# citations would report that probe as drift.
CITATION_SKIP = {"MCP_Assumption.thy"}


def say(msg):
    if not QUIET:
        print(msg)


def check(name, ok, detail=""):
    if ok:
        say(f"  PASS  {name}")
    else:
        failures.append(f"{name}: {detail}")
        say(f"  FAIL  {name}  {detail}")


# ---- 1. registry freshness -------------------------------------------------
say("\nregistry")
gen = ROOT / "tools/gen_assumptions.py"
if gen.exists():
    r = subprocess.run([sys.executable, str(gen), "--check"],
                       cwd=ROOT, capture_output=True, text=True)
    check("plans/ASSUMPTIONS matches plans/", r.returncode == 0, r.stdout.strip())
else:
    check("tools/gen_assumptions.py present", False, "missing")

known_ids = set()
if REGISTRY.exists():
    for line in REGISTRY.read_text().splitlines():
        m = re.match(r"^(\S+#[AITDQ]\d+)\s", line)
        if m:
            known_ids.add(m.group(1))


# ---- 2. one status per plan ------------------------------------------------
say("\nplan status")
readme = (PLANS / "README").read_text() if (PLANS / "README").exists() else ""
boxes = {n: (s == "x") for s, n in re.findall(r"- \[([ x])\]\s+(\S+)", readme)}

multi, disagree, missing = [], [], []
plan_files = [p for p in sorted(PLANS.iterdir())
              if p.is_file() and p.name not in ("README", "ASSUMPTIONS")]
for p in plan_files:
    lines = p.read_text().splitlines()
    st = [(i + 1, l) for i, l in enumerate(lines) if re.match(r"status:", l, re.I)]
    if not st:
        missing.append(p.name)
        continue
    if len(st) > 1:
        multi.append(f"{p.name}({len(st)})")
    word = re.sub(r"status:\s*", "", st[0][1], flags=re.I).split()[0].rstrip(".,").lower()
    if p.name in boxes:
        done_box = boxes[p.name]
        if done_box != (word in DONE_WORDS):
            disagree.append(f"{p.name}: README {'[x]' if done_box else '[ ]'} vs header {word!r}")

check("every plan carries a status: line", not missing, f"{len(missing)} without: {missing[:6]}")
check("exactly one status: line per plan", not multi, f"{len(multi)} with several: {multi[:6]}")
check("plans/README agrees with each plan header", not disagree,
      f"{len(disagree)} disagree, e.g. {disagree[:3]}")


# ---- 3. spec section ids ---------------------------------------------------
say("\nspec ids")
spec_lines = SPEC.read_text().splitlines() if SPEC.exists() else []
sections, seen_ids, dup = [], {}, []
i = 0
while i < len(spec_lines):
    if RULE.match(spec_lines[i]) and i > 0 and spec_lines[i - 1].strip():
        heading, meta = spec_lines[i - 1].strip(), {}
        j = i + 1
        while j < len(spec_lines):
            m = META.match(spec_lines[j].strip())
            if not m:
                break
            meta[m.group(1)] = m.group(2).strip()
            j += 1
        sections.append((i, heading, meta))
        ident = meta.get("id")
        if ident:
            if ident in seen_ids:
                dup.append(ident)
            seen_ids[ident] = i
    i += 1

if not sections:
    notes.append("no spec sections parsed -- has the id scheme been applied yet?")
    say("  SKIP  spec has no sections with metadata blocks yet")
else:
    no_id = [h for _, h, m in sections if not m.get("id")]
    bad = [m["id"] for _, _, m in sections if m.get("id") and not ID_RE.match(m["id"])]
    check("every spec section carries an id", not no_id,
          f"{len(no_id)} without: {[h[:40] for h in no_id[:4]]}")
    check("ids are well formed", not bad, f"{bad[:4]}")
    check("ids are unique", not dup, f"{dup[:4]}")

    dangling = []
    for _, h, m in sections:
        for field in ("supersedes", "superseded_by"):
            for ref in re.split(r"[,\s]+", m.get(field, "")):
                ref = ref.strip()
                if ref and ref not in ("-", "—") and ref not in seen_ids:
                    dangling.append(f"{m.get('id', h[:24])}.{field} -> {ref}")
    check("supersedes/superseded_by resolve", not dangling,
          f"{len(dangling)} dangling: {dangling[:3]}")

    # every spec id cited elsewhere must exist
    cited = set()
    for f in [CHANGELOG] + plan_files:
        if f.exists():
            cited |= set(re.findall(r"\b(?:D-(?:\d{4}-\d{2}-\d{2}|undated)|S)-[a-z0-9-]+", f.read_text()))
    unknown = sorted(cited - set(seen_ids))
    check("ids cited by CHANGELOG/plans exist in spec", not unknown,
          f"{len(unknown)} unknown: {unknown[:4]}")


# ---- 4. refinement queue ---------------------------------------------------
say("\nrefinement queue")
open_re = re.compile(r"spec refinement\s*\[(OPEN|FOLDED\s+\d{4}-\d{2}-\d{2})\]", re.I)
bare_re = re.compile(r"spec refinement\s*:")
bare, states = [], {"OPEN": 0, "FOLDED": 0}
for f in plan_files + [SPEC, PLANS / "README"]:
    if not f.exists():
        continue
    text = f.read_text()
    for m in open_re.finditer(text):
        states["FOLDED" if m.group(1).upper().startswith("FOLDED") else "OPEN"] += 1
    # a bare marker not immediately followed by a state
    for m in bare_re.finditer(text):
        if not open_re.match(text, m.start()):
            bare.append(f.name)
check("every 'spec refinement' carries OPEN or FOLDED <date>", not bare,
      f"{len(bare)} bare in {sorted(set(bare))[:5]}")
if states["OPEN"] or states["FOLDED"]:
    say(f"  note  refinement queue: {states['OPEN']} open, {states['FOLDED']} folded")


# ---- 5. citation coverage (reported, never enforced) -----------------------
say("\ncitation coverage")
cited_ids = set()
for pat in ("mcp/Tools/**/*.thy", "mcp_test/src/*.scala"):
    for f in ROOT.glob(pat):
        if f.name in CITATION_SKIP:
            continue
        cited_ids |= set(re.findall(r"\b(\w+#[AITDQ]\d+)\b", f.read_text()))
covered = cited_ids & known_ids
stray = sorted(cited_ids - known_ids)
if known_ids:
    pct = 100 * len(covered) // len(known_ids)
    say(f"  note  {len(covered)}/{len(known_ids)} assumptions cited by a test ({pct}%)")
check("no test cites an unknown assumption id", not stray, f"{stray[:5]}")


# ---- verdict ---------------------------------------------------------------
print()
for n in notes:
    print(f"note: {n}")
if failures:
    print(f"FAILED ({len(failures)})")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)
print("spec gate: all checks pass")
