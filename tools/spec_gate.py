#!/usr/bin/env python3
"""Drift gate for the prose layer: spec, CHANGELOG, plans/.

The same shape as this project's existing gates -- it exits nonzero, so it can
be wired into CI or run beside `isabelle mcp_test`. Every check here exists
because the corresponding drift was MEASURED in this repository, not because it
seemed like a good idea:

  1. registry freshness   plans/ASSUMPTIONS must match plans/. Otherwise a
                          renamed assumption leaves stale exported references.
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
  5. citation coverage    Munit and Isabelle citations come from their full
                          structured manifests, never source-text matching.
                          Coverage is reported; --strict-tests enforces
                          declared T obligations.

usage: tools/spec_gate.py [--quiet] [--strict-tests]
                          --test-manifest munit-spec.json
                          --theory-manifest isabelle-spec.json
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
parser.add_argument("--theory-manifest", type=pathlib.Path, required=True)
args = parser.parse_args()

QUIET = args.quiet
STRICT_TESTS = args.strict_tests
TEST_MANIFEST = args.test_manifest
THEORY_MANIFEST = args.theory_manifest
notes: list[str] = []

ID_RE = re.compile(r"^(?:D-(?:\d{4}-\d{2}-\d{2}|undated)|S)-[a-z0-9-]+$")

# An assumption id is <plan>#<LABEL>. The plan part is a plans/ filename, so
# the charset must match what gen_assumptions.py will emit for one -- keep
# REG_ROW (reading the registry) and CITE (finding citations in sources) in
# step, or a plan whose name uses a character only one of them accepts gets
# ids that can be registered but never cited, and reports 0% forever.
PLAN = r"[\w-]+"
LABEL = r"[AITDQ]\d+"
# The LAYER column is one comma-joined field (`bridge,scala-unit`) for an
# obligation covered at more than one layer, so it stays a single \S+ token.
REG_ROW = re.compile(rf"^({PLAN}#{LABEL})\s+(\S+)\s")
CITE = re.compile(rf"\b({PLAN}#{LABEL})\b")
META = re.compile(r"^(id|supersedes|superseded_by|status):\s*(.*)$")
RULE = re.compile(r"^[-=]{3,}\s*$")
PLAN_LINK_RE = re.compile(r"^[A-Za-z0-9_.-]+#[AITDQ]\d+$")
LAYER_ROLE_RE = re.compile(r"^[a-z][a-z0-9_]*$")
LAYER_NAME_RE = re.compile(r"^[a-z][a-z0-9-]*$")

MUNIT_MANIFEST_SCHEMA_VERSION = 2
THEORY_MANIFEST_SCHEMA_VERSION = 1
MANIFEST_PRODUCER = "isabelle-mcp/munit"
THEORY_MANIFEST_PRODUCER = "isabelle-mcp/isabelle"
MUNIT_FRAMEWORK = "munit"
MUNIT_FRAMEWORK_VERSION = "1.1.1"
ISABELLE_FRAMEWORK = "isabelle"
ISABELLE_EXPORT_NAME = "mcp/spec-tests"
LINK_RELATIONS = frozenset({"verifies", "covers"})
MUNIT_TEST_CLASSES = frozenset({"functional", "performance"})

# The controlled vocabulary for a plan's status. "implemented" and "green" are
# spellings the tree already uses for done; they are accepted rather than
# churned, since the point is one AUTHORITATIVE field, not one wording.
DONE_WORDS = {"done", "implemented", "green", "shipped"}

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


def theory_manifest_require(condition, detail):
    if not condition:
        check("Isabelle manifest is valid", False, detail)


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
launcher = ROOT / "tools/planning-gate"
if launcher.exists():
    r = subprocess.run([str(launcher), "registry", "check"],
                       cwd=ROOT, capture_output=True, text=True)
    detail = (r.stdout + r.stderr).strip()
    check("plans/ASSUMPTIONS matches plans/", r.returncode == 0, detail)
else:
    check("tools/planning-gate present", False, "missing")

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
# Must agree with the document loader: a stray editor
# swap file beside a plan is not a plan, and reading one as text kills the gate.
plan_files = [p for p in sorted(PLANS.iterdir())
              if p.is_file() and p.name not in
              ("README", "ASSUMPTIONS", "legacy_unlinked.csv")
              and not p.name.startswith(".")
              and not p.name.endswith(("~", ".bak", ".orig", ".rej"))]
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


# ---- 5. citation coverage ---------------------------------------------------
say("\ncitation coverage")

cited_ids = set()

say("\nIsabelle manifest")
try:
    theory_manifest = json.loads(THEORY_MANIFEST.read_text())
except (OSError, json.JSONDecodeError) as ex:
    check("Isabelle manifest is readable JSON", False, str(ex))

theory_manifest_require(isinstance(theory_manifest, dict),
                        "document root is not an object")
theory_manifest_require(type(theory_manifest.get("schema_version")) is int and
                        theory_manifest["schema_version"] == THEORY_MANIFEST_SCHEMA_VERSION,
                        f"schema_version must be {THEORY_MANIFEST_SCHEMA_VERSION}")
theory_manifest_require(theory_manifest.get("producer") == THEORY_MANIFEST_PRODUCER,
                        f"producer must be {THEORY_MANIFEST_PRODUCER!r}")
theory_manifest_require(theory_manifest.get("framework") == ISABELLE_FRAMEWORK,
                        f"framework must be {ISABELLE_FRAMEWORK!r}")
theory_manifest_require(theory_manifest.get("export_name") == ISABELLE_EXPORT_NAME,
                        f"export_name must be {ISABELLE_EXPORT_NAME!r}")
theory_layers_digest = theory_manifest.get("test_layers_sha256")
theory_manifest_require(isinstance(theory_layers_digest, str),
                        "test_layers_sha256 is not a string")
theory_manifest_require(theory_layers_digest == TEST_LAYERS_SHA256,
                        f"manifest layer digest {theory_layers_digest!r}, "
                        f"registry {TEST_LAYERS_SHA256!r}")

theory_sessions = theory_manifest.get("sessions")
theory_manifest_require(isinstance(theory_sessions, list) and bool(theory_sessions),
                        "sessions is not a non-empty array")
theory_manifest_require(all(isinstance(session, str) and bool(session.strip())
                            for session in theory_sessions),
                        "sessions contains a non-string or empty name")
theory_manifest_require(len(theory_sessions) == len(set(theory_sessions)),
                        "sessions contains duplicate names")
theory_session_set = set(theory_sessions)

theories = theory_manifest.get("theories")
theory_manifest_require(isinstance(theories, list) and bool(theories),
                        "theories is not a non-empty array")
theory_sources = {}
for index, theory_record in enumerate(theories):
    where = f"theories[{index}]"
    theory_manifest_require(isinstance(theory_record, dict), f"{where} is not an object")
    session, theory = theory_record.get("session"), theory_record.get("theory")
    theory_manifest_require(isinstance(session, str) and session in theory_session_set,
                            f"{where}.session is not declared by sessions")
    theory_manifest_require(isinstance(theory, str) and bool(theory.strip()),
                            f"{where}.theory is not a non-empty string")
    identity = (session, theory)
    theory_manifest_require(identity not in theory_sources,
                            f"{where} duplicates {session}::{theory}")

    source = theory_record.get("source")
    theory_manifest_require(isinstance(source, dict), f"{where}.source is not an object")
    source_path, source_sha1 = source.get("path"), source.get("sha1")
    valid_source_path = (
        isinstance(source_path, str) and bool(source_path) and
        source_path.startswith("mcp/Tools/") and source_path.endswith(".thy") and
        not pathlib.PurePosixPath(source_path).is_absolute() and
        ".." not in pathlib.PurePosixPath(source_path).parts and
        "\\" not in source_path
    )
    theory_manifest_require(valid_source_path,
                            f"{where}.source.path is not a canonical theory path")
    theory_manifest_require(isinstance(source_sha1, str) and
                            bool(re.fullmatch(r"sha1:[0-9a-f]{40}", source_sha1)),
                            f"{where}.source.sha1 is malformed")
    source_file = ROOT / source_path
    try:
        source_bytes = source_file.read_bytes()
    except OSError as ex:
        check("Isabelle manifest theory source is readable", False,
              f"{source_path}: {ex}")
    actual_source_sha1 = "sha1:" + hashlib.sha1(source_bytes).hexdigest()
    theory_manifest_require(actual_source_sha1 == source_sha1,
                            f"{source_path} changed after its Isabelle export was built")
    theory_sources[identity] = (source_path, len(source_bytes.splitlines()))

theory_tests = theory_manifest.get("tests")
theory_manifest_require(isinstance(theory_tests, list) and bool(theory_tests),
                        "tests is not a non-empty array")
theory_ids = set()
theory_identities = set()
test_sessions = set()
for index, test in enumerate(theory_tests):
    where = f"tests[{index}]"
    theory_manifest_require(isinstance(test, dict), f"{where} is not an object")
    session, theory = test.get("session"), test.get("theory")
    name, layer = test.get("name"), test.get("layer")
    theory_manifest_require(isinstance(session, str) and session in theory_session_set,
                            f"{where}.session is not declared by sessions")
    theory_manifest_require(isinstance(theory, str) and bool(theory.strip()),
                            f"{where}.theory is not a non-empty string")
    theory_manifest_require((session, theory) in theory_sources,
                            f"{where} has no matching theories entry")
    theory_manifest_require(isinstance(name, str) and bool(name.strip()),
                            f"{where}.name is not a non-empty string")
    theory_manifest_require(isinstance(layer, str) and layer in TEST_LAYERS,
                            f"{where}.layer is not one of {sorted(TEST_LAYERS)}")
    identity = (session, theory, name)
    theory_manifest_require(identity not in theory_identities,
                            f"{where} duplicates {session}::{theory}::{name}")
    theory_identities.add(identity)
    test_sessions.add(session)

    location = test.get("location")
    theory_manifest_require(isinstance(location, dict), f"{where}.location is not an object")
    source_path, source_line = location.get("path"), location.get("line")
    expected_path, source_lines = theory_sources[(session, theory)]
    theory_manifest_require(source_path == expected_path,
                            f"{where}.location.path differs from its theory source")
    theory_manifest_require(type(source_line) is int and 0 < source_line <= source_lines,
                            f"{where}.location.line is outside its theory source")

    links = test.get("links")
    theory_manifest_require(isinstance(links, list) and bool(links),
                            f"{where}.links is not a non-empty array")
    seen_links = set()
    for link_index, link in enumerate(links):
        link_where = f"{where}.links[{link_index}]"
        theory_manifest_require(isinstance(link, dict), f"{link_where} is not an object")
        relation, ident = link.get("relation"), link.get("id")
        theory_manifest_require(relation in LINK_RELATIONS,
                                f"{link_where}.relation is not one of {sorted(LINK_RELATIONS)}")
        theory_manifest_require(isinstance(ident, str) and
                                bool(PLAN_LINK_RE.fullmatch(ident)),
                                f"{link_where}.id is not a qualified plan label")
        key = (relation, ident)
        theory_manifest_require(key not in seen_links,
                                f"{link_where} duplicates {relation}:{ident}")
        seen_links.add(key)
        theory_ids.add(ident)

        label = ident.rsplit("#", 1)[-1]
        relation_matches = (
            relation == "covers" and bool(re.fullmatch(r"T\d+", label))
        ) or (
            relation == "verifies" and bool(re.fullmatch(r"[AI]\d+", label))
        )
        theory_manifest_require(relation_matches,
                                f"{theory}::{name}: {relation} cannot target {ident}")
        theory_manifest_require(ident in known_ids,
                                f"{theory}::{name} cites unknown plan id {ident}")
        if relation == "covers":
            expected = id_layer.get(ident, "unstated")
            declared = set(expected.split(","))
            theory_manifest_require("unstated" in declared or layer in declared,
                                    f"{theory}::{name}: {ident} declares layer {expected}, "
                                    f"manifest says {layer}")

theory_manifest_require(test_sessions == theory_session_set,
                        "not every declared session contributes a spec_test")
check("Isabelle manifest is current, complete, and semantically valid", True)
cited_ids |= theory_ids

say("\nmunit manifest")
try:
    manifest = json.loads(TEST_MANIFEST.read_text())
except (OSError, json.JSONDecodeError) as ex:
    check("munit manifest is readable JSON", False, str(ex))

manifest_require(isinstance(manifest, dict), "document root is not an object")
manifest_require(type(manifest.get("schema_version")) is int and
                 manifest["schema_version"] == MUNIT_MANIFEST_SCHEMA_VERSION,
                 f"schema_version must be {MUNIT_MANIFEST_SCHEMA_VERSION}")
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
    test_class = test.get("test_class")
    manifest_require(test_class in MUNIT_TEST_CLASSES,
                     f"{where}.test_class is not one of {sorted(MUNIT_TEST_CLASSES)}")

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

        label = ident.rsplit("#", 1)[-1]
        relation_matches = (
            relation == "covers" and bool(re.fullmatch(r"T\d+", label))
        ) or (
            relation == "verifies" and bool(re.fullmatch(r"[AI]\d+", label))
        )
        manifest_require(relation_matches,
                         f"{suite}::{name}: {relation} cannot target {ident}")
        manifest_require(ident in known_ids,
                         f"{suite}::{name} cites unknown plan id {ident}")
        if relation == "covers":
            # An obligation may declare SEVERAL layers -- a plan's T-label can be
            # covered partly by a scala-unit test and partly by a bridge one.
            # The registry records them comma-joined, so the binding is
            # membership, not equality. This still refuses a link that claims a
            # layer the plan never named; what it does NOT check is that every
            # declared layer actually has a test (see CHANGELOG).
            expected = id_layer.get(ident, "unstated")
            declared = set(expected.split(","))
            manifest_require("unstated" in declared or layer in declared,
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
