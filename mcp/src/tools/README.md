# Built-in tools

This directory is the home for Scala implementations of the server's built-in
MCP tools.

Use one source file per tool implementation. Keep the shared tool descriptor,
registry, and dispatch wiring in the surrounding `isabelle.mcp` application
layer; a tool file should contain the metadata and handler for one tool.

The files in this directory use the `isabelle.mcp.tools` package. Keep this
package focused on tool metadata and handlers; transport, protocol, and
connection lifecycle code belongs in the surrounding packages.
