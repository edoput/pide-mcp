/*  Built-in MCP tool: check_theory. */

package isabelle.mcp.tools

import isabelle.JSON
import isabelle.mcp.{MCP_Backend, MCP_Server, MCP_Session, Window}
import isabelle.mcp.application.McpApplication

object CheckTheory {
  val check_theory_tool: MCP_Server.Builtin_Tool =
    MCP_Server.Builtin_Tool(
      name = "check_theory",
      description =
        "Re-read a theory file from disk and check it, then report " +
        "its diagnostics (errors and warnings with positions). Use " +
        "this after editing the file to verify it as it now stands. " +
        "Equivalent to unload_theory followed by " +
        "load_theory. A clean reply means the theory checks; errors " +
        "carry line positions for the next edit round. By default only " +
        "errors and warnings are reported; set include_output to also " +
        "see writeln/tracing/information proof output (can be large). " +
        "offset/limit window the diagnostic lines; the leading " +
        "(N errors, N warnings, ...) summary always counts the true " +
        "total, even when windowed.",
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
        backend.check_theory(MCP_Server.pass_arg(args, "name"), MCP_Server.pass_arg(args, "master_dir"),
          MCP_Server.pass_bool_arg(args, "include_output", false),
          MCP_Server.pass_int_arg(args, "offset", 0),
          MCP_Server.pass_int_arg(args, "limit", Window.default_limit)))


}
