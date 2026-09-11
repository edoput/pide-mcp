/*  Built-in MCP tool: search_sources. */

package isabelle.mcp.tools

import isabelle.JSON
import isabelle.mcp.{MCP_Backend, MCP_Server, MCP_Session, Window}
import isabelle.mcp.application.McpApplication

object SearchSources {
  val search_sources_tool: MCP_Server.Builtin_Tool =
    MCP_Server.Builtin_Tool(
      name = "search_sources",
      description =
        "Search for theories by substring match. Scans all theories " +
        "across all sessions and returns long names that contain the " +
        "given pattern. Empty pattern returns no results (use " +
        "list_theories for a full enumeration of one session). " +
        "offset/limit window the match rows for broad patterns.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" ->
            (JSON.Object("pattern" -> JSON.Object("type" -> "string")) ++
              MCP_Server.offset_limit_properties),
          "required" -> List("pattern")),
      annotations = JSON.Object("readOnlyHint" -> true, "idempotentHint" -> true, "openWorldHint" -> false),
      handler_fn = (backend, args, _) =>
        backend.search_sources(MCP_Server.pass_arg(args, "pattern"),
          MCP_Server.pass_int_arg(args, "offset", 0),
          MCP_Server.pass_int_arg(args, "limit", Window.default_limit)))

  /* wave 5 (plans/doc_list, spec "documentation for the agent"): the
     Doc.contents() catalog (manuals, release notes, examples), joined per
     entry to the doc session doc_read will serve chapters from. */

}
