/*  Title:      mcp/src/mcp_application.scala

Typed application boundary for one MCP connection.  This package owns ordinary
tool/resource dispatch and its per-connection state; JSON-RPC IDs, transport,
lifecycle, scheduling, and completion ownership stay on the connection side.
*/

package isabelle.mcp.application

import isabelle._
import isabelle.mcp.{MCP_Backend, MCP_Server, MCP_Session}


object McpApplication {
  sealed abstract class Readiness
  final case class Not_Ready(progress: String) extends Readiness
  final case class Ready(backend: MCP_Backend) extends Readiness
  final case class Failed(message: String) extends Readiness

  sealed trait Operation

  object Operation {
    case object ToolsList extends Operation
    final case class ToolsCall(name: String, arguments: JSON.Object.T) extends Operation
    case object ResourcesList extends Operation
    case object ResourceTemplatesList extends Operation
    final case class ResourcesRead(uri: String) extends Operation
  }

  sealed trait Outcome

  object Outcome {
    final case class Result(value: JSON.T) extends Outcome
    final case class InvalidParams(message: String) extends Outcome
  }

  /* The kernel supplies a connection-owned implementation.  Application code
     can observe the signal or register cooperative cleanup, but cannot trigger
     or complete it.  Registration is safe on either side of the cancellation
     race: a callback registered after cancellation runs immediately. */
  trait Cancellation {
    def isCancelled: Boolean
    def onCancel(callback: () => Unit): Unit
  }

  object Cancellation {
    val Never: Cancellation = new Cancellation {
      def isCancelled: Boolean = false
      def onCancel(callback: () => Unit): Unit = ()
    }
  }

  /* Deliberately returns only the port.  The concrete adapter remains an
     application implementation, not an object connection code can inspect or
     mutate. */
  private[mcp] def isabelle(
    readiness: () => Readiness,
    session_name: String,
    session_dirs: List[Path],
    theory: String
  ): McpApplication =
    new IsabelleMcpApplication(readiness, session_name, session_dirs, theory)
}


trait McpApplication {
  def execute(
    operation: McpApplication.Operation,
    cancellation: McpApplication.Cancellation
  ): McpApplication.Outcome
}


private[application] final class IsabelleMcpApplication(
  readiness: () => McpApplication.Readiness,
  session_name: String,
  session_dirs: List[Path],
  theory: String
) extends McpApplication {
  import McpApplication.{Cancellation, Operation, Outcome}

  private case class Scope(designation: String = "", bundles: List[String] = Nil)
  private val scope = Synchronized(Scope())

  private def not_ready_text(progress: String): String =
    "session " + session_name + " is not ready: " + progress + ". This tool needs " +
    "the prover; retry shortly. Read isabelle://session for status."

  private def failed_text(message: String): String =
    "session " + session_name + " failed to start: " + message + ". The server " +
    "cannot serve prover-backed tools; restart it after fixing the build."

  private def session_state_text(status: String): String =
    "session: " + session_name + "\n" +
    "dirs: " + session_dirs.map(_.implode).mkString(", ") + "\n" +
    "theory: " + theory + "\n" +
    "status: " + status

  private def text_outcome(result: MCP_Session.Result): Outcome =
    result match {
      case MCP_Session.Ok(text) => Outcome.Result(MCP_Server.text_result(text))
      case MCP_Session.Error(message) =>
        Outcome.Result(MCP_Server.text_result(message, is_error = true))
    }

  private val tool_scope_show_tool: MCP_Server.Builtin_Tool =
    MCP_Server.Builtin_Tool(
      name = "tool_scope_show",
      fname = "",
      description =
        "Show the current tool scope: which theory or repl context " +
        "the server reads user-registered tools from, the bundles " +
        "included in it, and the tools registered and active there. " +
        "Tools are context entities in Isabelle: a tool is visible " +
        "when the scope's context (transitively) imports its " +
        "registering theory and it has not been deactivated " +
        "(declare [[mcp_tools del: ...]] or a closed bundle).",
      input_schema = JSON.Object("type" -> "object"),
      annotations = MCP_Server.read_only_annotations,
      handler_fn = Some((backend, _, _) => {
        val sc = scope.value
        val bundles_text = if (sc.bundles.isEmpty) "none" else sc.bundles.mkString(", ")
        backend.check_designation(sc.designation, sc.bundles) match {
          case MCP_Session.Error(msg) =>
            MCP_Session.Ok(
              "Tool scope: " + MCP_Server.format_designation(sc.designation) + " (BROKEN: " + msg +
                ") -- use tool_scope_set to point it at a valid theory or repl\n" +
                "Included bundles: " + bundles_text)
          case MCP_Session.Ok(_) =>
            val rows = backend.ml_tools(sc.designation, sc.bundles).rows
            MCP_Session.Ok(
              "Tool scope: " + MCP_Server.format_designation(sc.designation) + "\n" +
                "Included bundles: " + bundles_text + "\n" +
                "Active tools (" + rows.length + "): " +
                (if (rows.isEmpty) "none" else rows.map(_.name).mkString(", ")))
        }
      }))

  private val tool_scope_set_tool: MCP_Server.Builtin_Tool =
    MCP_Server.Builtin_Tool(
      name = "tool_scope_set",
      fname = "",
      description =
        "Set the tool scope to a theory (by name, any known spelling) " +
        "or to a repl (by id). Repl scope serves the tools of the " +
        "repl's CURRENT state -- use this after registering a tool " +
        "via repl_step to call it without persisting the theory " +
        "first. Replaces the designation AND clears any bundles " +
        "included with tool_scope_include (fresh context, no " +
        "accumulated soup).",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" -> JSON.Object(
            "theory" -> JSON.Object("type" -> "string"),
            "repl" -> JSON.Object("type" -> "string")),
          "required" -> List()),
      annotations = MCP_Server.mutating_annotations,
      handler_fn = Some((backend, args, _) => {
        val theory = args.collectFirst({ case ("theory", v) => v })
        val repl = args.collectFirst({ case ("repl", v) => v })
        (theory, repl) match {
          case (Some(t), Some(r)) =>
            MCP_Session.Error(
              "tool_scope_set: theory and repl are mutually exclusive (got theory=" +
                quote(t) + ", repl=" + quote(r) + ")")
          case (None, None) =>
            MCP_Session.Error("tool_scope_set: exactly one of theory or repl is required")
          case (Some(t), None) =>
            backend.resolve_context_theory(t) match {
              case Right(canonical) =>
                scope.change(_ => Scope(canonical, Nil))
                MCP_Session.Ok("Tool scope set to theory " + quote(canonical))
              case Left(msg) => MCP_Session.Error(msg)
            }
          case (None, Some(r)) =>
            val candidate = "repl:" + r
            backend.check_designation(candidate) match {
              case MCP_Session.Ok(_) =>
                scope.change(_ => Scope(candidate, Nil))
                MCP_Session.Ok("Tool scope set to repl " + quote(r))
              case error @ MCP_Session.Error(_) => error
            }
        }
      }))

  private val tool_scope_include_tool: MCP_Server.Builtin_Tool =
    MCP_Server.Builtin_Tool(
      name = "tool_scope_include",
      fname = "",
      description =
        "Open bundles in the current tool scope (like Isar's `context " +
        "includes`): tools activated by those bundles become servable " +
        "until the scope changes (tool_scope_set). Bundle names " +
        "resolve in the scope's context.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" -> JSON.Object(
            "bundles" ->
              JSON.Object("type" -> "array", "items" -> JSON.Object("type" -> "string"))),
          "required" -> List("bundles")),
      annotations = MCP_Server.mutating_annotations,
      handler_fn = Some((backend, args, _) => {
        val bundles = args.collect({ case ("bundles", v) => v })
        val sc = scope.value
        val candidate = sc.bundles ++ bundles
        backend.check_designation(sc.designation, candidate) match {
          case MCP_Session.Ok(_) =>
            val applied =
              scope.change_result(cur =>
                if (cur == sc) (true, Scope(sc.designation, candidate)) else (false, cur))
            if (applied) MCP_Session.Ok("Included bundle(s): " + bundles.mkString(", "))
            else
              MCP_Session.Error(
                "tool_scope_include: the tool scope changed while this call was " +
                  "validating; re-issue it against the new scope")
          case error @ MCP_Session.Error(_) => error
        }
      }))

  private val tool_scope_builtins: List[MCP_Server.Builtin_Tool] =
    List(tool_scope_show_tool, tool_scope_set_tool, tool_scope_include_tool)

  private def all_builtins: List[MCP_Server.Builtin_Tool] =
    MCP_Server.builtins ++ tool_scope_builtins

  private def tools_list(): Outcome = {
    val builtins = all_builtins
    readiness() match {
      case McpApplication.Not_Ready(_) | McpApplication.Failed(_) =>
        val builtin_json = builtins.map(tool_json)
        Outcome.Result(JSON.Object("tools" -> builtin_json))
      case McpApplication.Ready(backend) =>
        val builtin_names = builtins.map(_.name).toSet
        val sc = scope.value
        val reply = backend.ml_tools(sc.designation, sc.bundles)
        val hidden = reply.builtin_activation.collect({ case (name, false) => name }).toSet
        val builtin_json = builtins.filterNot(tool => hidden(tool.name)).map(tool_json)
        val exposed = MCP_Server.exposure(reply.rows.map(_.name), builtin_names)
        val ml_json =
          reply.rows.flatMap(row =>
            exposed.get(row.name).map(name =>
              JSON.Object(
                "name" -> name,
                "description" -> row.description,
                "inputSchema" -> MCP_Server.ml_tool_schema(row.params)) ++
              JSON.Object.apply(
                MCP_Server.ml_tool_annotations(row.annotations).toList.map("annotations" -> _)*)))
        Outcome.Result(JSON.Object("tools" -> (builtin_json ++ ml_json)))
    }
  }

  private def tool_json(tool: MCP_Server.Builtin_Tool): JSON.Object.T =
    JSON.Object(
      "name" -> tool.name,
      "description" -> tool.description,
      "inputSchema" -> tool.input_schema,
      "annotations" -> tool.annotations)

  private def tools_call(
    name: String,
    arguments: JSON.Object.T,
    cancellation: Cancellation
  ): Outcome =
    readiness() match {
      case McpApplication.Not_Ready(progress) =>
        Outcome.Result(MCP_Server.text_result(not_ready_text(progress), is_error = true))
      case McpApplication.Failed(message) =>
        Outcome.Result(MCP_Server.text_result(failed_text(message), is_error = true))
      case McpApplication.Ready(backend) =>
        all_builtins.find(_.name == name) match {
          case Some(tool) =>
            text_outcome(tool.handler(backend, MCP_Server.json_args(arguments), cancellation))
          case None =>
            val sc = scope.value
            val exposed =
              MCP_Server.exposure(
                backend.ml_tools(sc.designation, sc.bundles).rows.map(_.name),
                all_builtins.map(_.name).toSet)
            val internal = exposed.collectFirst({ case (full, visible) if visible == name => full })
              .getOrElse(name)
            text_outcome(
              backend.ml_run_cancellable(
                internal, MCP_Server.json_args(arguments), sc.designation, sc.bundles,
                cancellation))
        }
    }

  private def resources_list(): Outcome =
    readiness() match {
      case McpApplication.Not_Ready(_) | McpApplication.Failed(_) =>
        Outcome.Result(JSON.Object("resources" -> List(
          JSON.Object("uri" -> "isabelle://session", "name" -> "session",
            "description" -> "current session name, dirs, loaded theories"))))
      case McpApplication.Ready(backend) =>
        val resources = backend.mcp_resources().map({ case (uri, name, description) =>
          JSON.Object("uri" -> uri, "name" -> name, "description" -> description)
        })
        Outcome.Result(JSON.Object("resources" -> resources))
    }

  private def resources_read(uri: String): Outcome =
    readiness() match {
      case McpApplication.Ready(backend) =>
        backend.mcp_resource_read(uri) match {
          case MCP_Session.Ok(text) =>
            Outcome.Result(MCP_Server.resource_contents(uri, text))
          case MCP_Session.Error(message) => Outcome.InvalidParams(message)
        }
      case McpApplication.Not_Ready(progress) =>
        if (uri == "isabelle://session")
          Outcome.Result(MCP_Server.resource_contents(uri, session_state_text("not ready (" + progress + ")")))
        else Outcome.InvalidParams(not_ready_text(progress))
      case McpApplication.Failed(message) =>
        if (uri == "isabelle://session")
          Outcome.Result(MCP_Server.resource_contents(uri, session_state_text("failed (" + message + ")")))
        else Outcome.InvalidParams(failed_text(message))
    }

  def execute(operation: Operation, cancellation: Cancellation): Outcome =
    operation match {
      case Operation.ToolsList => tools_list()
      case Operation.ToolsCall(name, arguments) => tools_call(name, arguments, cancellation)
      case Operation.ResourcesList => resources_list()
      case Operation.ResourceTemplatesList =>
        Outcome.Result(JSON.Object("resourceTemplates" -> MCP_Server.resource_templates))
      case Operation.ResourcesRead(uri) => resources_read(uri)
    }
}
