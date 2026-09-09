---
paths:
  - "mcp/Tools/**/*.thy"
  - "mcp/Tools/ROOT"
  - "ir/*.ML"
  - "tools/wt-isabelle-build.sh"
  - "tools/isabelle_worktree.py"
---

# Rebuild after theory/ML changes

Use the shared launcher selected by `ISABELLE`, otherwise `isabelle` on PATH.
Do not discover or switch installations to recover a failed command.
See `tools/ISABELLE.md` for the configuration contract.

Build and verify changes in an explicit worktree with private state:

```sh
tools/wt-isabelle-build.sh --worktree /absolute/checkout build
```

This builds MCP-Tools, MCP-Tools-Tests, MCP-HOL, and MCP-HOL-Tests using that
checkout's components. `setup`, `scala`, `test -L scala-unit`, `clean`, and
`teardown` use the same explicit path. Additional components and session roots
must be requested with `--component` and `--root`.

Do not share mutable heaps or session databases between checkouts. Setup
copies validated base seeds and checks them with the selected runtime; if
base sessions are missing, report the requirement. Do not substitute another
installation or silently rebuild Pure/HOL. Changing the runtime requires
explicit teardown of the old private state.

For an already configured user environment, the ordinary command is:

```sh
tools/isabelle build -d mcp/Tools MCP-Tools MCP-Tools-Tests MCP-HOL MCP-HOL-Tests
```

A successful settings probe or Scala compilation is not theory-test evidence.
Run the session build after changing theories or ML, and report the actual
result. The complete acceptance command remains `tools/planning-gate done`.
