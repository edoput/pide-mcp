---
paths:
  - "mcp/Tools/**/*.thy"
  - "mcp/Tools/ROOT"
  - "ir/*.ML"
---

# Rebuild after theory/ML changes

`mcp/Tools/ROOT` declares three sessions built from this tree:
`MCP-Tools`, `MCP-Tools-Tests` (depends on `MCP-Tools`), and `MCP-HOL`
(depends on `MCP-Tools`; pulls in `ir/ir.ML` via the symlink at
`mcp/Tools/HOL/ir.ML`).

Unlike the Scala jar, sessions are **not** rebuilt implicitly — nothing
rebuilds them just by running `isabelle mcp_server`. Whenever you edit a
`.thy` file under `mcp/Tools/`, the `ROOT` file, or `ir/ir.ML`, rebuild
(and re-run the `\<^assert>`-based tests) with:

```
Isabelle2025-2_linux/Isabelle2025-2/bin/isabelle build -d mcp/Tools -v MCP-Tools MCP-Tools-Tests MCP-HOL
```

This fails iff a theory fails to load or an assertion in
`MCP_Tools_Tests`/`MCP_Repl` fails, so treat a clean build as the test
signal. Do this before relying on the tool registry or REPL bridge
behaving as expected.
