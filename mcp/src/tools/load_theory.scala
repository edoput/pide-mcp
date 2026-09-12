/*  Built-in MCP tool: load_theory. */

package isabelle.mcp.tools

import isabelle.JSON
import isabelle.mcp.{MCP_Backend, MCP_Server, MCP_Session, Window}
import isabelle.mcp.application.McpApplication

object LoadTheory {
  val load_theory_tool: MCP_Server.Builtin_Tool =
    MCP_Server.Builtin_Tool(
      name = "load_theory",
      description =
        "Load and check a theory from disk (with its transitive " +
        "dependencies) into the running session, by session-qualified " +
        "long name (\"HOL-Library.Multiset\") or by path via " +
        "master_dir. Replies with per-theory " +
        "ok/error status; errors carry positions. Loading is the " +
        "expensive promotion -- a deep import chain outside the base " +
        "image can take minutes; see list_theories for what is " +
        "already available. By default only errors and warnings are " +
        "reported; set include_output to also see writeln/tracing/" +
        "information proof output (can be large). offset/limit window " +
        "the diagnostic lines; the leading (N errors, N warnings, ...) " +
        "summary always counts the true total, even when windowed.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" ->
            (JSON.Object(
              "name" -> JSON.Object("type" -> "string"),
              "master_dir" -> JSON.Object("type" -> "string"),
              "include_output" -> JSON.Object("type" -> "boolean",
                "description" -> "Also include writeln/tracing/information proof output " +
                  "alongside errors and warnings. Default false.",
                "default" -> false)) ++ MCP_Server.offset_limit_properties),
          "required" -> List("name")),
      annotations = MCP_Server.idempotent_mutating_annotations,
      handler_fn = (backend, args, _) =>
        backend.load_theory(MCP_Server.pass_arg(args, "name"), MCP_Server.pass_arg(args, "master_dir"),
          MCP_Server.pass_bool_arg(args, "include_output", false),
          MCP_Server.pass_int_arg(args, "offset", 0),
          MCP_Server.pass_int_arg(args, "limit", Window.default_limit)))


}
