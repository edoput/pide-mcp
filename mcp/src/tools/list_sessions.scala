/*  Built-in MCP tool: list_sessions. */

package isabelle.mcp.tools

import isabelle.JSON
import isabelle.mcp.{MCP_Backend, MCP_Server, MCP_Session, Window}
import isabelle.mcp.application.McpApplication

object ListSessions {
  val list_sessions_tool: MCP_Server.Builtin_Tool =
    MCP_Server.Builtin_Tool(
      name = "list_sessions",
      description =
        "List all Isabelle sessions known to the server, enumerated " +
        "from ROOT files on the configured session directories " +
        "(distribution, AFP if registered, etc.). Each entry shows " +
        "session name, chapter, whether a built heap exists, and " +
        "theory count. Mark the session the server is running as base " +
        "image. Sessions are coarse-grained units: theories in the base " +
        "image are queryable now; others require load_theory (slow) or " +
        "a heap rebuild + server restart (fast, coarse). Follow with " +
        "list_theories to see what is in a session. offset/limit window " +
        "the session rows for catalogs too large to return in full.",
      input_schema =
        JSON.Object("type" -> "object", "properties" -> MCP_Server.offset_limit_properties,
          "required" -> List()),
      annotations = JSON.Object("readOnlyHint" -> true, "idempotentHint" -> true, "openWorldHint" -> false),
      handler_fn = (backend, args, _) =>
        backend.list_sessions_info(
          MCP_Server.pass_int_arg(args, "offset", 0),
          MCP_Server.pass_int_arg(args, "limit", Window.default_limit)))


}
