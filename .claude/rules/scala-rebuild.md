---
paths:
  - "mcp/src/**/*.scala"
  - "mcp/etc/build.props"
  - "mcp_test/src/**/*.scala"
  - "mcp_test/etc/build.props"
---

# Rebuild after Scala changes

This project's Scala sources live in two Isabelle components:

- `mcp/src/*.scala` → `mcp/lib/mcp.jar` (the production MCP server, no test code)
- `mcp_test/src/*.scala` → `mcp_test/lib/mcp_test.jar` (munit test suites +
  the `isabelle mcp_test` tool; vendored test libraries in `mcp_test/lib/ext/`)

each driven by its component's `etc/build.props`.

Whenever you edit a `.scala` file under `mcp/src/` or `mcp_test/src/` (or either
`etc/build.props`), rebuild the jars immediately afterwards by running:

```
flatpak run --command=isabelle de.tum.in.isabelle.Isabelle scala_build
```

Do this before running or testing the MCP server, so the jars never go
stale relative to the sources.

Never add `-f`. It forces a rebuild of Isabelle/Scala itself, and `isabelle.jar`
lives inside the read-only flatpak image, so the build dies with
`*** I/O error: /app/lib/classes/isabelle.jar: Read-only file system`.
Plain `scala_build` rebuilds both component jars, which is all this needs.

Always go through the flatpak, never `Isabelle2025-2_linux/Isabelle2025-2/bin/isabelle`.
The bundled tree is reference sources only (same packaged version). The two
installs ship different Poly/ML binaries and share `$ISABELLE_HOME_USER/heaps`,
and a root session's build digest is the SHA1 of the `poly` binary
(`src/Pure/Build/store.scala`, `make_shasum`), so alternating between them
invalidates Pure and forces a full Pure → HOL rebuild every time.
