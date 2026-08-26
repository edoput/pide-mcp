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

`registry check` and `registry generate` derive the compatibility text and
Isabelle/ML ID registries from those same loaded documents.  The historical
`tools/gen_assumptions.py` command remains only as a launcher-forwarding
wrapper; it has no second plan parser.

The final public command is `tools/planning-gate done`.  The checked-in launcher
resolves the repository root, selects `.venv/bin/python`, checks its required
imports, and then invokes the importable module.  It does not depend on the
caller's working directory or an activated environment, and it never installs
packages automatically.
