/*  Built-in MCP tool: doc_list. */

package isabelle.mcp.tools

import isabelle.JSON
import isabelle.mcp.{MCP_Backend, MCP_Server, MCP_Session}
import isabelle.mcp.application.McpApplication

object DocList {
  val doc_list_tool: MCP_Server.Builtin_Tool =
    MCP_Server.Builtin_Tool(
      name = "doc_list",
      description =
        "List the Isabelle documentation catalog: the manuals, release " +
        "notes, and examples shipped with the distribution (what " +
        "`isabelle doc` shows). Each entry reports name, title, its " +
        "catalog section, and how it is readable: manuals name the " +
        "source session whose theory files doc_read serves (chapter-" +
        "level plain text -- never the pdf); plain-text entries (NEWS, " +
        "examples) are read directly. Find theory names with " +
        "search_sources. Glob `pattern` " +
        "filters entry names.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" -> JSON.Object("pattern" -> JSON.Object("type" -> "string")),
          "required" -> List()),
      annotations = MCP_Server.read_only_annotations,
      handler_fn = (backend, args, _) => backend.doc_list(MCP_Server.pass_arg(args, "pattern")))

  /* wave 5 (plans/doc_read, spec "documentation for the agent"): reads a
     doc_list entry from its plain-text source -- manuals resolve through
     the catalog to their src/Doc session's chapter .thy files (toc
     without `section`, that section's source text with it); NEWS/examples
     are plain files (`lines` windows them). Never the pdf. */

}
