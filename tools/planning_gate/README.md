# Planning gate tooling

This package is the host-side implementation of the delivery plans in
`plans/plan_format` through `plans/planning_gate`.  Its modules are importable
and accept explicit repository roots.  Only `__main__.py` owns CLI parsing and
process exit behavior.

Current checkpoint commands:

```console
tools/planning-gate plan check
tools/planning-gate plan dump plans/plan_format
tools/planning-gate registry check
tools/planning-gate registry generate
tools/planning-gate labels check
tools/planning-gate labels audit check
tools/planning-gate matrix dump
tools/planning-gate matrix check
tools/planning-gate refinements check
tools/planning-gate commands
tools/planning-gate build
tools/planning-gate theories
tools/planning-gate theory-catalog
tools/planning-gate catalog all
tools/planning-gate static
tools/planning-gate test-layer scala-unit
tools/planning-gate test-layer heap
tools/planning-gate test-layer bridge
tools/planning-gate test-layer ml-unit
tools/planning-gate test-layer e2e
tools/planning-gate done
.venv/bin/python -m pytest -q tools/planning_gate/tests
```

`plan check` reads all three formats during bootstrap:

- `isabelle-mcp.plan/v1` is canonical, strictly schema-validated, and rejects
  claim declarations in the Markdown body;
- `isabelle-mcp.plan/bootstrap-v0` provides machine-readable ownership and
  dependency metadata while claims still live in compatibility prose; and
- historical prose plans are accepted read-only until they are modified or
  migrated.

Validation is deliberately split:

- JSON Schema checks local structure and rejects unknown fields;
- `document.py` rejects duplicate YAML keys, YAML-only values, unsafe tags,
  filename/ID drift, and paths which escape the repository; and
- repository validation resolves dependencies and rejects cycles.

`registry check` and `registry generate` derive the compatibility text registry
from those same loaded documents.  Isabelle theory links are checked through
their structured `spec_test` export rather than a generated ML ID mirror.  The
historical `tools/gen_assumptions.py` command remains only as a
launcher-forwarding wrapper; it has no second plan parser.

`labels check` enforces kind-specific lifecycle rules only on canonical v1
claims.  Legacy plans are not silently assigned states.  Instead, `labels audit
generate` writes `plans/migration/label-audit.json`, containing only claims
which need a human semantic decision before migration.

`matrix dump` performs body-free discovery and emits the common producer
catalog plus every missing verification relationship.  It remains diagnostic
and succeeds even when a producer or relationship is missing.  `matrix check`
additionally requires every registered producer and an exact match with the
reviewed `plans/legacy_unlinked.csv` ratchet.  The one-time baseline command is
`tools/planning-gate matrix legacy generate`; it refuses to run while any
producer is absent or after the CSV exists.

The final public command is `tools/planning-gate done`.  The checked-in launcher
resolves the repository root, selects `.venv/bin/python`, checks its required
imports, and then invokes the importable module.  It does not depend on the
caller's working directory or an activated environment, and it never installs
packages automatically.

`theory-catalog` builds every configured production and ML-unit theory session
before exporting `mcp_test/lib/isabelle-spec.json`; it short-circuits if that
build fails. `catalog theory` is a compatibility alias, and `catalog all` runs
the MUnit catalog followed by this complete theory catalog.

`done` accepts no filters or reduced layer set. It reports the starting HEAD
and exact dirty paths, prepares both structured manifests (using the same
`theory-catalog` orchestration), checks the static four-producer matrix and
compatibility spec gate, and executes tooling-unit,
scala-unit, heap, bridge, ml-unit, and e2e. A dirty tree is a visible local
warning; a change to HEAD, tracked content, or the set of dirty paths during
the invocation is a hard failure. At the end it emits one compact JSON
execution-evidence record: it contains the exact repository identities, each
required step result, and canonical before/after digests of the producer
catalog (including artifacts and test identities). It is accepted only when
every required step executed and passed, the static closure passed, and both
identities and catalog digests match. The named build, catalog, theory, and test-layer commands remain
independently callable for diagnosis, but their success alone is never
completion evidence.

The Scala runner uses explicit `-L scala-unit|heap|bridge|all` selection. Its
manifest and console output distinguish functional assertions from explicit
performance-budget tests. The compatibility `-b` option means `-L all`.
