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
Isabelle2025-2_linux/Isabelle2025-2/bin/isabelle scala_build -f
```

Do this before running or testing the MCP server, so the jars never go
stale relative to the sources.
