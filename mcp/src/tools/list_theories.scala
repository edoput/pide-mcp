/*  Built-in MCP tool: list_theories. */

package isabelle.mcp.tools

import isabelle.JSON
import isabelle.mcp.{MCP_Backend, MCP_Server, MCP_Session}
import isabelle.mcp.application.McpApplication

object ListTheories {
  val list_theories_tool: MCP_Server.Builtin_Tool =
    MCP_Server.Builtin_Tool(
      name = "list_theories",
      description =
        "List all theories in a given Isabelle session (by name, as " +
        "shown by list_sessions). Each entry is a long theory name; " +
        "use load_theory to load one, search_sources for a name search.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" -> JSON.Object("session" -> JSON.Object("type" -> "string")),
          "required" -> List("session")),
      annotations = JSON.Object("readOnlyHint" -> true, "idempotentHint" -> true, "openWorldHint" -> false),
      handler_fn = (backend, args, _) =>
        backend.list_theories_info(MCP_Server.pass_arg(args, "session")))


}
