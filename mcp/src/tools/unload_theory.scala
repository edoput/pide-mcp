/*  Built-in MCP tool: unload_theory. */

package isabelle.mcp.tools

import isabelle.JSON
import isabelle.mcp.{MCP_Backend, MCP_Server, MCP_Session}
import isabelle.mcp.application.McpApplication

object UnloadTheory {
  val unload_theory_tool: MCP_Server.Builtin_Tool =
    MCP_Server.Builtin_Tool(
      name = "unload_theory",
      description =
        "Unload a theory that was loaded with load_theory: removes " +
        "its PIDE document and purges the snapshot. " +
        "Cannot unload theories baked into the base image.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" -> JSON.Object("name" -> JSON.Object("type" -> "string")),
          "required" -> List("name")),
      annotations = MCP_Server.mutating_annotations,
      handler_fn = (backend, args, _) => backend.unload_theory(MCP_Server.pass_arg(args, "name")))


}
