#!/usr/bin/env python3
"""Generate the assumption registry from plans/.

Every numbered assumption in a plan file gets a qualified, stable ID of the
form <plan>#<LABEL> -- repl_list#I3, readiness#A1. Those IDs are what tests
cite, so that "which assumption does this test check?" and "is this assumption
checked anywhere?" are answered by checked metadata rather than prose.

Two label shapes exist in plans/ and both are read here:

    I1  MCP_Session.ir exists and resolves the promise by id      (repl_list)
        test [bridge]: ...

    A1. initialize does not depend on the backend.                (readiness)
        T1 [scala unit]: drive Handler with a readiness thunk ...

OWNERSHIP: only the plan that DECLARES a label owns the ID. plans/repl_list
states I1..I5 once and later plans cite them by bare label; those citations are
not declarations and must not produce a second owner, or the IDs stop being
unique. A label line is a declaration; a bare mention in prose is not.

Outputs:
  plans/ASSUMPTIONS            the human/tooling-readable registry
  mcp/Tools/assumption_ids.ML  the same IDs for the Isabelle side

usage: tools/gen_assumptions.py [--check]
       --check exits 1 if the generated files are out of date
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
PLANS = ROOT / "plans"
REGISTRY = PLANS / "ASSUMPTIONS"
ML_OUT = ROOT / "mcp/Tools/assumption_ids.ML"

# widest LAYER value: a three-layer obligation like repl_init#T7
# renders "bridge,e2e,scala-unit"
LAYER_W = 24

# `I1  text`, `A1. text`, `T1 [scala unit]: text`, `D2: text`.
#
# The separator is one-or-more spaces, NOT two: labels are padded to a fixed
# column, so `T7  theory-context` has two spaces but `T10 goal-based` has one.
# Requiring two silently dropped every double-digit label.
#
# The label must open the line (<=4 spaces indent), which is what keeps prose
# out: "bridge). T7..T10 green" and "- T7..T10 green" do not match. A range at
# line start is rejected too -- after the label, `\s+` cannot match the "." of
# "T7..T10", so a summary line beginning with a range declares nothing.
LABEL = re.compile(r"^ {0,4}([AITDQ]\d+)(?:[.:])?\s+(\S.*)$")
RULE = re.compile(r"^[-=]{3,}\s*$")
INLINE_LAYER = re.compile(r"^\[([^\]]*)\]")
# Every bracketed tag, anywhere on a line -- an obligation may carry a second
# layer mid-line, which an anchored `^test [...]` pattern cannot see.
ANY_TAG = re.compile(r"\[([^\]]*)\]")

LAYERS = [("ml unit", "ml-unit"), ("ml-unit", "ml-unit"),
          ("scala unit", "scala-unit"), ("scala-unit", "scala-unit"),
          ("heap", "heap"), ("bridge", "bridge"), ("e2e", "e2e")]


def layer_of(text: str) -> str:
    t = text.lower()
    for needle, name in LAYERS:
        if needle in t:
            return name
    return "unstated"


def is_plan_file(path: pathlib.Path) -> bool:
    """A real plan: not a directory, an index, or an editor leftover.

    An open Vim buffer drops a binary `.plan.swp` beside the plan. read_text()
    then dies with UnicodeDecodeError and takes the whole spec gate down with
    it, because check 1 regenerates the registry and byte-compares. Keep this
    in step with the plan_files filter in tools/spec_gate.py.
    """
    return (path.is_file()
            and path.name not in ("README", "ASSUMPTIONS")
            and not path.name.startswith(".")
            and not path.name.endswith(("~", ".bak", ".orig", ".rej")))


def parse(path: pathlib.Path):
    """Yield (label, statement, layer) for every label DECLARED in this plan."""
    lines = path.read_text().splitlines()
    out, cur = [], None

    def close(c):
        if not c:
            return
        body = " ".join(c["lines"]).strip()
        # An obligation may name MORE THAN ONE layer, and 19 of them do:
        # plans/scope_show T2 is "test [scala unit] for patterns; [bridge] for
        # repl + load_theory", and ml_builtin_migration A5 carries its second
        # tag mid-line on a continuation. Reading only the first tag dropped the
        # rest silently, and because spec_gate.py layer-binds `discharges`, the
        # dropped layer's test became uncitable -- a real bridge test could not
        # be linked to the obligation it discharges.
        #
        # So: collect every tag that resolves to a layer, not just the first.
        # layer_of() stays substring-based on purpose. A stricter classifier was
        # tried and rejected: readiness#T9's tag is `[bridge or test_mcp.py]`,
        # which an exact-match rule rejects, falling through to the body scan
        # below -- where "cleaned heap" matches first and flips it to `heap`.
        found = []
        for ln in c["lines"]:
            for tag in ANY_TAG.findall(ln):
                name = layer_of(tag)
                if name != "unstated" and name not in found:
                    found.append(name)
        # sorted(), because this file is regenerated and byte-compared by
        # spec_gate.py's freshness check: discovery order must not leak in.
        # The fallback keeps obligations that carry no tag at all working.
        # `origin` records HOW the layer was decided, so the run can report how
        # much of the registry is declared versus guessed:
        #   "tag"   -- at least one bracketed [layer] tag; authoritative
        #   "prose" -- no tag anywhere, so layer_of() substring-scanned the
        #              whole statement and something matched. This is a GUESS:
        #              the word "bridge" in ordinary prose reads the same as a
        #              declaration, and repl_init_from_source T4/T5 were wrong
        #              in exactly this way until 2026-08-24.
        #   "none"  -- no tag and nothing matched; recorded as "unstated"
        if found:
            layer, origin = ",".join(sorted(found)), "tag"
        else:
            layer = layer_of(body)
            origin = "none" if layer == "unstated" else "prose"
        stmt = re.split(r"\btest\s*\[", body)[0].strip()
        # `T1 [scala unit]: drive Handler ...` -- the layer tag is metadata,
        # already captured above, so it does not belong in the statement.
        stmt = INLINE_LAYER.sub("", stmt).lstrip(": ").strip()
        out.append((c["label"], stmt or body, layer, origin))

    for ln in lines:
        m = LABEL.match(ln)
        if m:
            close(cur)
            cur = {"label": m.group(1), "lines": [m.group(2)]}
            continue
        if cur is None:
            continue
        # a rule, or flush-left prose, ends the block
        if RULE.match(ln) or (ln.strip() and not ln.startswith(" ")):
            close(cur)
            cur = None
            continue
        if ln.strip():
            cur["lines"].append(ln.strip())
    close(cur)
    return out


def collect():
    """Registry rows, plus where each row's layer came from (see `origin`)."""
    rows, origins = [], {}
    for path in sorted(PLANS.iterdir()):
        if not is_plan_file(path):
            continue
        seen = set()
        for label, stmt, layer, origin in parse(path):
            if label in seen:          # a plan restating its own label: keep first
                continue
            seen.add(label)
            ident = f"{path.name}#{label}"
            rows.append((ident, layer, stmt))
            origins[ident] = origin
    return rows, origins


def render_registry(rows):
    w = max(len(i) for i, _, _ in rows)
    body = [
        "assumption registry (GENERATED -- do not edit)",
        "=============================================",
        "",
        "Regenerate:  python3 tools/gen_assumptions.py",
        "Check:       python3 tools/spec_gate.py --test-manifest mcp_test/lib/munit-spec.json",
        "",
        "One row per assumption DECLARED in a plan file. The ID is the address a",
        "test cites: munit cases attach structured tags, Isabelle theories cite it",
        "with the checked \\<^assumption> antiquotation. An ID that no test cites is",
        "an unchecked assumption; the gate reports the count.",
        "",
        f"{'ID'.ljust(w)}  {'LAYER'.ljust(LAYER_W)}  STATEMENT",
        f"{'-' * w}  {'-' * LAYER_W}  ---------",
    ]
    for ident, layer, stmt in rows:
        body.append(f"{ident.ljust(w)}  {layer.ljust(LAYER_W)}  {stmt[:150]}")
    return "\n".join(body) + "\n"


def render_ml(rows):
    ids = "\n".join(f'  {chr(34)}{i}{chr(34)},' for i, _, _ in rows).rstrip(",")
    return (
        "(* GENERATED by tools/gen_assumptions.py -- do not edit.\n"
        "   Regenerate:  python3 tools/gen_assumptions.py\n"
        "   Check:       python3 tools/spec_gate.py --test-manifest mcp_test/lib/munit-spec.json\n\n"
        "   The IDs a theory may cite with \\<^assumption>. An unknown ID is a\n"
        "   build error with a position, so a renamed or deleted assumption cannot\n"
        "   leave a stale citation behind. *)\n\n"
        "structure MCP_Assumption_Ids =\n"
        "struct\n\n"
        "val ids =\n [\n" + ids + "\n ];\n\n"
        "end;\n"
    )


def main():
    rows, origins = collect()
    reg, ml = render_registry(rows), render_ml(rows)

    if "--check" in sys.argv:
        stale = []
        if not REGISTRY.exists() or REGISTRY.read_text() != reg:
            stale.append(str(REGISTRY.relative_to(ROOT)))
        if not ML_OUT.exists() or ML_OUT.read_text() != ml:
            stale.append(str(ML_OUT.relative_to(ROOT)))
        if stale:
            print("STALE (run tools/gen_assumptions.py): " + ", ".join(stale))
            return 1
        print(f"registry up to date ({len(rows)} assumptions)")
        return 0

    REGISTRY.write_text(reg)
    ML_OUT.parent.mkdir(parents=True, exist_ok=True)
    ML_OUT.write_text(ml)
    per = {}
    for ident, _, _ in rows:
        per[ident.split("#")[0]] = per.get(ident.split("#")[0], 0) + 1
    print(f"wrote {REGISTRY.relative_to(ROOT)} and {ML_OUT.relative_to(ROOT)}")
    print(f"{len(rows)} assumptions across {len(per)} plans")
    empty = [p.name for p in sorted(PLANS.iterdir())
             if is_plan_file(p) and p.name not in per]
    if empty:
        print(f"plans declaring NO labelled assumption ({len(empty)}): {', '.join(empty)}")

    # How much of the LAYER column is declared, and how much is inferred?
    # A "prose" layer is a substring match over the statement, so it is a guess
    # that reads exactly like a declaration in the registry. Those are the rows
    # worth auditing: repl_init_from_source T4/T5 sat wrong this way.
    by_origin = {}
    for ident in origins:
        by_origin.setdefault(origins[ident], []).append(ident)
    tag = len(by_origin.get("tag", []))
    guessed = sorted(by_origin.get("prose", []))
    none = len(by_origin.get("none", []))
    print(f"layer source: {tag} from an explicit [tag], "
          f"{len(guessed)} inferred from prose, {none} unstated")
    if guessed:
        print("  layers INFERRED from prose (a guess -- give these an explicit "
              f"[tag] to pin them):\n    {', '.join(guessed)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
