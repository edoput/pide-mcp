/*  Built-in MCP tool: load_theory. */

package isabelle.mcp.tools

import isabelle.JSON
import isabelle.mcp.{MCP_Backend, MCP_Server, MCP_Session}
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
        "already available.",
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
        backend.load_theory(MCP_Server.pass_arg(args, "name"), MCP_Server.pass_arg(args, "master_dir")))


}
