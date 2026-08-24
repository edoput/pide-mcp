# Planning gate tooling

This package is the host-side implementation of the delivery plans in
`plans/plan_format` through `plans/planning_gate`.  Its modules are importable
and accept explicit repository roots.  Only `__main__.py` owns CLI parsing and
process exit behavior.

Current checkpoint commands:

```console
.venv/bin/python -m tools.planning_gate plan check
.venv/bin/python -m tools.planning_gate plan dump plans/plan_format
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

The final public command remains `python3 -m tools.planning_gate done`.  During
bootstrap the required Python packages are available only in `.venv`, so the
checkpoint commands name that interpreter explicitly.  Interpreter selection
must be resolved before enabling the final command; it is not hidden inside
the document loader.
