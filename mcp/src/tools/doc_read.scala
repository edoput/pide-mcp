/*  Built-in MCP tool: doc_read. */

package isabelle.mcp.tools

import isabelle.JSON
import isabelle.mcp.{MCP_Backend, MCP_Server, MCP_Session, Window}
import isabelle.mcp.application.McpApplication

object DocRead {
  val doc_read_tool: MCP_Server.Builtin_Tool =
    MCP_Server.Builtin_Tool(
      name = "doc_read",
      description =
        "Read Isabelle documentation from its plain-text sources. `name` " +
        "is a doc_list entry (e.g. \"isar-ref\", \"system\", \"NEWS\"). " +
        "For manuals: without `section`, returns the table of contents -- " +
        "chapter and section headings with their source file and line; " +
        "with `section`, returns that section's source text (substring " +
        "match on headings; an ambiguous match lists the candidates). " +
        "Manual text is Isar theory source -- prose with antiquotations " +
        "-- not the rendered pdf. For plain-text entries (NEWS, examples), " +
        "returns file content; `lines` (e.g. \"120-180\") windows it. " +
        "Without `lines`, offset/limit window it instead (same convention " +
        "as list_sessions/list_theories/search_sources); narrow further " +
        "with a more specific `section` or use search_sources over the " +
        "manual's source session. `section` and `lines` are mutually " +
        "exclusive -- section addresses manuals, lines addresses plain " +
        "entries.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" ->
            (JSON.Object(
              "name" -> JSON.Object("type" -> "string"),
              "section" -> JSON.Object("type" -> "string"),
              "lines" -> JSON.Object("type" -> "string")) ++ MCP_Server.offset_limit_properties),
          "required" -> List("name")),
      annotations = MCP_Server.read_only_annotations,
      handler_fn = (backend, args, _) =>
        backend.doc_read(
          MCP_Server.pass_arg(args, "name"), MCP_Server.pass_arg(args, "section"),
          MCP_Server.pass_arg(args, "lines"),
          MCP_Server.pass_int_arg(args, "offset", 0),
          MCP_Server.pass_int_arg(args, "limit", Window.default_limit)))

}
