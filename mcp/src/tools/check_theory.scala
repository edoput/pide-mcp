/*  Built-in MCP tool: check_theory. */

package isabelle.mcp.tools

import isabelle.JSON
import isabelle.mcp.{MCP_Backend, MCP_Server, MCP_Session}
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
        "carry line positions for the next edit round.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" ->
            JSON.Object(
              "name" -> JSON.Object("type" -> "string"),
              "master_dir" -> JSON.Object("type" -> "string")),
          "required" -> List("name")),
      annotations = MCP_Server.idempotent_mutating_annotations,
      handler_fn = (backend, args, _) =>
        backend.check_theory(MCP_Server.pass_arg(args, "name"), MCP_Server.pass_arg(args, "master_dir")))


}
