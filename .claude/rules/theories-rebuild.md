---
paths:
  - "mcp/Tools/**/*.thy"
  - "mcp/Tools/ROOT"
  - "ir/*.ML"
---

# Rebuild after theory/ML changes

`mcp/Tools/ROOT` declares four sessions built from this tree:
`MCP-Tools`, `MCP-Tools-Tests` (depends on `MCP-Tools`), `MCP-HOL`
(built on `HOL`, with an added session dependency on `MCP-Tools`;
pulls in `ir/ir.ML` via the symlink at `mcp/Tools/HOL/ir.ML`), and
`MCP-HOL-Tests` (depends on `MCP-HOL`).

Unlike the Scala jar, sessions are **not** rebuilt implicitly — nothing
rebuilds them just by running `isabelle mcp_server`. Whenever you edit
a `.thy` file under `mcp/Tools/`, the `ROOT` file, or `ir/ir.ML`,
rebuild (and re-run the `\<^assert>`-based tests) with:

```
flatpak run --command=isabelle de.tum.in.isabelle.Isabelle build \
  -d mcp/Tools -v MCP-Tools MCP-Tools-Tests MCP-HOL MCP-HOL-Tests
```

Never run the repo-bundled
`Isabelle2025-2_linux/Isabelle2025-2/bin/isabelle` binary. Both
installs share `$ISABELLE_HOME_USER/heaps`, and a root session's build
digest is keyed off the SHA1 of the Poly/ML executable itself. Since
the bundled and flatpak `poly` binaries differ, each install considers
the other's Pure stale, and heap lookup (`Store.input_dirs` in
`src/Pure/Build/store.scala`: user before system, first hit wins, no
fallback) means whichever install ran last shadows the other's heaps —
forcing a full Pure → HOL → MCP-HOL rebuild on every alternation. The
bundled tree is still fine to read as an Isabelle-source reference
(same packaged version) — just never execute it.

This fails iff a theory fails to load or an assertion in
`MCP_Tools_Tests`/`MCP_Repl`/`MCP_Repl_Tests` fails, so treat a clean
build as the test signal. Do this before relying on the tool registry
or REPL bridge behaving as expected.
