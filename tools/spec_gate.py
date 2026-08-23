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
                          operation, so the queue only ever grew. States were
                          added; the queue still only grew, because nothing made
                          closing one a condition of anything. Since 2026-08-23
                          a refinement is an INTERRUPT, not a queue entry:
                          [BLOCKING] fails outright, [DEFERRED <date>: <reason>]
                          needs a stated reason, and no `done` plan may own an
                          unresolved one (including the legacy [OPEN]).
  5. citation coverage    Munit citations come from its discovery manifest,
                          not test-name text. Isabelle citations retain their
                          checked antiquotation syntax. Coverage is reported;
                          --strict-tests enforces declared T obligations.

usage: tools/spec_gate.py [--quiet] [--strict-tests]
                          --test-manifest munit-spec.json
"""

import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SPEC = ROOT / "spec"
CHANGELOG = ROOT / "CHANGELOG"
PLANS = ROOT / "plans"
REGISTRY = PLANS / "ASSUMPTIONS"
TEST_LAYERS_FILE = ROOT / "mcp_test/etc/test_layers.json"

parser = argparse.ArgumentParser(description="check spec/plan/test linkage")
parser.add_argument("--quiet", action="store_true")
parser.add_argument("--strict-tests", action="store_true")
parser.add_argument("--test-manifest", type=pathlib.Path, required=True)
args = parser.parse_args()

QUIET = args.quiet
STRICT_TESTS = args.strict_tests
TEST_MANIFEST = args.test_manifest
notes: list[str] = []

ID_RE = re.compile(r"^(?:D-(?:\d{4}-\d{2}-\d{2}|undated)|S)-[a-z0-9-]+$")
META = re.compile(r"^(id|supersedes|superseded_by|status):\s*(.*)$")
RULE = re.compile(r"^[-=]{3,}\s*$")
PLAN_LINK_RE = re.compile(r"^[A-Za-z0-9_.-]+#[AITDQ]\d+$")
LAYER_ROLE_RE = re.compile(r"^[a-z][a-z0-9_]*$")
LAYER_NAME_RE = re.compile(r"^[a-z][a-z0-9-]*$")

MANIFEST_SCHEMA_VERSION = 1
MANIFEST_PRODUCER = "isabelle-mcp/munit"
MUNIT_FRAMEWORK = "munit"
MUNIT_FRAMEWORK_VERSION = "1.1.1"
LINK_RELATIONS = frozenset({"verifies", "discharges"})

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
        print(f"  FAIL  {name}  {detail}")
        print()
        print("FAILED")
        print(f"  - {name}: {detail}")
        raise SystemExit(1)


def manifest_require(condition, detail):
    if not condition:
        check("munit manifest is valid", False, detail)


def layer_registry_require(condition, detail):
    if not condition:
        check("test-layer registry is valid", False, detail)


def load_test_layers():
    try:
        raw = TEST_LAYERS_FILE.read_bytes()
        registry = json.loads(raw)
    except (OSError, json.JSONDecodeError) as ex:
        check("test-layer registry is readable JSON", False, str(ex))

    layer_registry_require(isinstance(registry, dict), "document root is not an object")
    layer_registry_require(type(registry.get("schema_version")) is int and
                           registry["schema_version"] == 1,
                           "schema_version must be 1")
    roles = registry.get("layers")
    layer_registry_require(isinstance(roles, dict) and bool(roles),
                           "layers must be a non-empty object")
    for role, name in roles.items():
        layer_registry_require(bool(LAYER_ROLE_RE.fullmatch(role)),
                               f"malformed role {role!r}")
        layer_registry_require(isinstance(name, str) and
                               bool(LAYER_NAME_RE.fullmatch(name)),
                               f"malformed layer name for role {role!r}")
    names = list(roles.values())
    layer_registry_require(len(names) == len(set(names)),
                           "a layer name is assigned to several roles")
    return frozenset(names), "sha256:" + hashlib.sha256(raw).hexdigest()


TEST_LAYERS, TEST_LAYERS_SHA256 = load_test_layers()


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
id_layer: dict[str, str] = {}
if REGISTRY.exists():
    for line in REGISTRY.read_text().splitlines():
        m = re.match(r"^(\S+#[AITDQ]\d+)\s+(\S+)\s", line)
        if m:
            known_ids.add(m.group(1))
            id_layer[m.group(1)] = m.group(2)


# ---- 2. one status per plan ------------------------------------------------
say("\nplan status")
readme = (PLANS / "README").read_text() if (PLANS / "README").exists() else ""
boxes = {n: (s == "x") for s, n in re.findall(r"- \[([ x])\]\s+(\S+)", readme)}

multi, disagree, missing = [], [], []
# name -> the plan's authoritative status word, reused by the refinement check
plan_status: dict[str, str] = {}
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
    plan_status[p.name] = word
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
# A refinement is an INTERRUPT, not a queue entry (plans/README, decision
# 2026-08-23). The three live states are:
#
#   [BLOCKING]                   raised; implementation is stopped, waiting on
#                                the user. It should not outlive the session, so
#                                any occurrence fails this gate.
#   [FOLDED <date>]              the correction is written into the spec.
#   [DEFERRED <date>: <reason>]  the user consciously chose to defer. The reason
#                                is required, so the debt stays readable.
#
# [OPEN] is the LEGACY state from the deferred-write era. It is still parsed and
# counted so the markers already in the tree stay visible, but nothing new
# should be written in it, and a `done` plan may not own one (check 4b).
say("\nrefinement queue")
state_re = re.compile(
    r"spec refinement\s*\["
    r"(BLOCKING"
    r"|OPEN"
    r"|FOLDED\s+\d{4}-\d{2}-\d{2}"
    r"|DEFERRED\s+\d{4}-\d{2}-\d{2}\s*:\s*[^\]]+)"
    r"\]", re.I)
# any marker at all, however written -- so a malformed state ([DEFERRED] with no
# date, a bare `spec refinement:`) is reported rather than silently ignored.
any_re = re.compile(r"spec refinement\s*[\[:]", re.I)
# `"spec refinement [OPEN]:"` in double quotes is prose ABOUT the convention --
# plans/README states the rule, plans/ml_builtin_migration cites it. A quoted
# marker is a mention, never an occurrence.
quoted_re = re.compile(r'"\s*spec refinement', re.I)


def mention_only(text: str, start: int) -> bool:
    return bool(quoted_re.search(text, max(0, start - 2), start + 16))


UNRESOLVED = ("BLOCKING", "DEFERRED", "OPEN")
malformed: list[str] = []
states = {"BLOCKING": 0, "OPEN": 0, "FOLDED": 0, "DEFERRED": 0}
blocking: list[str] = []
owned: dict[str, set[str]] = {}   # plan name -> unresolved states it owns
for f in plan_files + [SPEC, PLANS / "README"]:
    if not f.exists():
        continue
    text = f.read_text()
    for m in state_re.finditer(text):
        if mention_only(text, m.start()):
            continue
        word = m.group(1).upper().split()[0]
        states[word] += 1
        if word == "BLOCKING":
            blocking.append(f.name)
        if word in UNRESOLVED:
            owned.setdefault(f.name, set()).add(word)
    for m in any_re.finditer(text):
        if not state_re.match(text, m.start()) and not mention_only(text, m.start()):
            malformed.append(f"{f.name}:{text.count(chr(10), 0, m.start()) + 1}")
check("every 'spec refinement' carries BLOCKING, FOLDED <date> or DEFERRED <date>: <reason>",
      not malformed, f"{len(malformed)} malformed at {sorted(set(malformed))[:5]}")
say("  note  refinement queue: " +
    (", ".join(f"{n} {k.lower()}" for k, n in states.items() if n) or "empty"))

# A [BLOCKING] marker means implementation stopped for a spec conflict and the
# loop was never finished: either the user was never asked, or the answer was
# never written back. Both leave the tree mid-interrupt.
check("no refinement is left [BLOCKING]", not blocking,
      f"{len(blocking)} in {sorted(set(blocking))[:5]}")


# ---- 4b. no done plan owns an unresolved refinement -------------------------
# A plan marked done while owning a BLOCKING, DEFERRED or legacy OPEN refinement
# claims completion over a spec correction it wrote down and never folded. This
# is the check that turns the historical false-dones red.
false_done = sorted(
    f"{name} ({'/'.join(sorted(st)).lower()})"
    for name, st in owned.items()
    if plan_status.get(name, "") in DONE_WORDS)
check("no 'done' plan owns an unresolved refinement", not false_done,
      f"{len(false_done)}: {false_done}")


# ---- 5. citation coverage ---------------------------------------------------
say("\ncitation coverage")

# Isabelle citations are syntax checked by MCP_Assumption.thy when their
# theories build. Munit tests are dynamically registered values, so source
# regexes cannot reliably identify their metadata: consume the manifest emitted
# by `isabelle mcp_test -M FILE` instead.
cited_ids = set()
for f in ROOT.glob("mcp/Tools/**/*.thy"):
    if f.name in CITATION_SKIP:
        continue
    cited_ids |= set(re.findall(r"\b(\w+#[AITDQ]\d+)\b", f.read_text()))

say("\nmunit manifest")
try:
    manifest = json.loads(TEST_MANIFEST.read_text())
except (OSError, json.JSONDecodeError) as ex:
    check("munit manifest is readable JSON", False, str(ex))

manifest_require(isinstance(manifest, dict), "document root is not an object")
manifest_require(type(manifest.get("schema_version")) is int and
                 manifest["schema_version"] == MANIFEST_SCHEMA_VERSION,
                 f"schema_version must be {MANIFEST_SCHEMA_VERSION}")
manifest_require(manifest.get("producer") == MANIFEST_PRODUCER,
                 f"producer must be {MANIFEST_PRODUCER!r}")
manifest_require(manifest.get("framework") == MUNIT_FRAMEWORK,
                 f"framework must be {MUNIT_FRAMEWORK!r}")
manifest_require(manifest.get("framework_version") == MUNIT_FRAMEWORK_VERSION,
                 f"framework_version must be {MUNIT_FRAMEWORK_VERSION!r}")
manifest_layers_digest = manifest.get("test_layers_sha256")
manifest_require(isinstance(manifest_layers_digest, str),
                 "test_layers_sha256 is not a string")
check("manifest identifies the current test-layer registry",
      manifest_layers_digest == TEST_LAYERS_SHA256,
      f"manifest {manifest_layers_digest!r}, registry {TEST_LAYERS_SHA256!r}")

tests = manifest.get("tests")
manifest_require(isinstance(tests, list), "tests is not an array")
manifest_require(bool(tests), "tests is empty; the full suite was not exported")

test_jar = ROOT / "mcp_test/lib/mcp_test.jar"
try:
    actual_jar_digest = "sha256:" + hashlib.sha256(test_jar.read_bytes()).hexdigest()
except OSError as ex:
    check("compiled test jar is readable", False, str(ex))
manifest_jar_digest = manifest.get("test_jar_sha256")
manifest_require(isinstance(manifest_jar_digest, str),
                 "test_jar_sha256 is not a string")
check("manifest identifies the current compiled test jar",
      manifest_jar_digest == actual_jar_digest,
      f"manifest {manifest_jar_digest!r}, current jar {actual_jar_digest!r}")

manifest_ids = set()
identities = set()
for index, test in enumerate(tests):
    where = f"tests[{index}]"
    manifest_require(isinstance(test, dict), f"{where} is not an object")

    suite, name, layer = test.get("suite"), test.get("name"), test.get("layer")
    manifest_require(isinstance(suite, str) and bool(suite.strip()),
                     f"{where}.suite is not a non-empty string")
    manifest_require(isinstance(name, str) and bool(name.strip()),
                     f"{where}.name is not a non-empty string")
    manifest_require(isinstance(layer, str) and layer in TEST_LAYERS,
                     f"{where}.layer is not one of {sorted(TEST_LAYERS)}")

    identity = (suite, name)
    manifest_require(identity not in identities,
                     f"{where} duplicates test identity {suite}::{name}")
    identities.add(identity)

    location = test.get("location")
    manifest_require(isinstance(location, dict), f"{where}.location is not an object")
    source_path = location.get("path")
    source_line = location.get("line")
    valid_source_path = (
        isinstance(source_path, str) and bool(source_path) and
        not pathlib.PurePosixPath(source_path).is_absolute() and
        ".." not in pathlib.PurePosixPath(source_path).parts and
        "\\" not in source_path
    )
    manifest_require(valid_source_path, f"{where}.location.path is not canonical and relative")
    manifest_require(type(source_line) is int and source_line > 0,
                     f"{where}.location.line is not a positive integer")

    links = test.get("links")
    manifest_require(isinstance(links, list), f"{where}.links is not an array")
    seen_links = set()
    for link_index, link in enumerate(links):
        link_where = f"{where}.links[{link_index}]"
        manifest_require(isinstance(link, dict), f"{link_where} is not an object")
        relation, ident = link.get("relation"), link.get("id")
        manifest_require(relation in LINK_RELATIONS,
                         f"{link_where}.relation is not one of {sorted(LINK_RELATIONS)}")
        manifest_require(isinstance(ident, str) and bool(PLAN_LINK_RE.fullmatch(ident)),
                         f"{link_where}.id is not a qualified plan label")

        key = (relation, ident)
        manifest_require(key not in seen_links,
                         f"{link_where} duplicates {relation}:{ident}")
        seen_links.add(key)
        manifest_ids.add(ident)

        is_test_obligation = bool(re.fullmatch(r"T\d+", ident.rsplit("#", 1)[-1]))
        manifest_require((relation == "discharges") == is_test_obligation,
                         f"{suite}::{name}: {relation} cannot target {ident}")
        manifest_require(ident in known_ids,
                         f"{suite}::{name} cites unknown plan id {ident}")
        if relation == "discharges":
            expected = id_layer.get(ident, "unstated")
            manifest_require(expected == "unstated" or expected == layer,
                             f"{suite}::{name}: {ident} declares layer {expected}, "
                             f"manifest says {layer}")

check("munit manifest is structurally and semantically valid", True)
cited_ids |= manifest_ids

covered = cited_ids & known_ids
stray = sorted(cited_ids - known_ids)
if known_ids:
    pct = 100 * len(covered) // len(known_ids)
    say(f"  note  {len(covered)}/{len(known_ids)} assumptions cited by a test ({pct}%)")
check("no test cites an unknown assumption id", not stray, f"{stray[:5]}")


# ---- 6. declared test obligations ------------------------------------------
say("\ntest obligations")
test_ids = {ident for ident in known_ids if re.match(r"^\S+#T\d+$", ident)}
missing_tests = sorted(test_ids - cited_ids)
if test_ids:
    cited = len(test_ids) - len(missing_tests)
    pct = 100 * cited // len(test_ids)
    say(f"  note  {cited}/{len(test_ids)} test obligations are linked ({pct}%)")
by_layer: dict[str, list[str]] = {}
for ident in missing_tests:
    by_layer.setdefault(id_layer.get(ident, "?"), []).append(ident)
for layer in sorted(by_layer):
    ids = by_layer[layer]
    say(f"  note  {layer:<11} {len(ids):>3} unlinked, e.g. {', '.join(ids[:3])}")
if STRICT_TESTS:
    check("every test obligation is linked to a test", not missing_tests,
          f"{len(missing_tests)} unlinked: {missing_tests[:5]}")


# ---- verdict ---------------------------------------------------------------
print()
for n in notes:
    print(f"note: {n}")
print("spec gate: all checks pass")
