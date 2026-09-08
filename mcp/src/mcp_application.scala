/*  Title:      mcp/src/mcp_application.scala

Typed application boundary for one MCP connection.  This package owns ordinary
tool/resource dispatch and its per-connection state; JSON-RPC IDs, transport,
lifecycle, scheduling, and completion ownership stay on the connection side.
*/

package isabelle.mcp.application

import isabelle._
import isabelle.mcp.{MCP_Backend, MCP_Server, MCP_Session}
import isabelle.mcp.pide.BridgeCancellation
import scala.concurrent.duration.FiniteDuration


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
    final case class TimedOut(delay: FiniteDuration) extends Outcome
  }

  /* The kernel supplies a connection-owned implementation.  Application code
     can observe the signal or register cooperative cleanup, but cannot trigger
     or complete it.  Registration is safe on either side of the cancellation
     race: a callback registered after cancellation runs immediately. */
  trait Cancellation extends BridgeCancellation

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
    theory: String,
    outputPolicy: McpOutputPolicy
  ): McpApplication =
    new IsabelleMcpApplication(readiness, session_name, session_dirs, theory, outputPolicy)
}


/** Output captured from arbitrary Isabelle/ML code is a distinct allocation
  * domain from a tool's typed return value. Zero is the secure default: only
  * tools that do not depend on captured output may execute. */
final case class McpOutputPolicy private (untrustedOutputBytes: Long) {
  def allowsUntrustedOutput: Boolean = untrustedOutputBytes > 0L
}


object McpOutputPolicy {
  val Disabled: McpOutputPolicy = new McpOutputPolicy(0L)
  /* Package-level composition tests historically execute output-backed tools
     without an Options value. Production always supplies the validated public
     option explicitly. */
  private[mcp] val TestDefault: McpOutputPolicy = new McpOutputPolicy(1048576L)

  def checked(value: Long): Either[String, McpOutputPolicy] =
    if (value < 0L) Left("mcp_untrusted_output_bytes must be non-negative")
    else Right(new McpOutputPolicy(value))
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
  theory: String,
  outputPolicy: McpOutputPolicy
) extends McpApplication {
  import McpApplication.{Cancellation, Operation, Outcome}

  /* None lasts only until the prover is ready. ML supplies the canonical
     registry-root locator; Scala never manufactures or parses locator URLs. */
  private val scope = Synchronized(Option.empty[String])

  private def current_context(
    backend: MCP_Backend,
    cancellation: Cancellation
  ): MCP_Session.Result =
    scope.value match {
      case Some(context) => MCP_Session.Ok(context)
      case None =>
        backend.root_context_cancellable(cancellation) match {
          case MCP_Session.Ok(root) =>
            val selected = scope.change_result {
              case Some(current) => (current, Some(current))
              case None => (root, Some(root))
            }
            MCP_Session.Ok(selected)
          case error @ MCP_Session.Error(_) => error
        }
    }

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
        "Show the current context locator and the tools registered and " +
        "active in the resolved Isabelle proof context. " +
        "Tools are context entities in Isabelle: a tool is visible " +
        "when the scope's context (transitively) imports its " +
        "registering theory and it has not been deactivated.",
      input_schema = JSON.Object("type" -> "object"),
      annotations = MCP_Server.read_only_annotations,
      handler_fn = Some((backend, _, cancellation) => {
        current_context(backend, cancellation) match {
          case MCP_Session.Error(msg) =>
            MCP_Session.Error("Cannot resolve the tool scope: " + msg)
          case MCP_Session.Ok(context) =>
            backend.check_context_cancellable(context, cancellation) match {
              case MCP_Session.Error(msg) =>
                MCP_Session.Ok(
                  "Context: " + context + " (BROKEN: " + msg +
                    ") -- use tool_scope_set with a valid context locator")
              case MCP_Session.Ok(canonical) =>
                val rows = backend.ml_tools_cancellable(canonical, cancellation).rows
                MCP_Session.Ok(
                  "Context: " + canonical + "\n" +
                    "Active tools (" + rows.length + "): " +
                    (if (rows.isEmpty) "none" else rows.map(_.name).mkString(", ")))
            }
        }
      }))

  private val tool_scope_set_tool: MCP_Server.Builtin_Tool =
    MCP_Server.Builtin_Tool(
      name = "tool_scope_set",
      fname = "",
      description =
        "Set the tool scope to an Isabelle context locator. Theory locators " +
        "select a global proof context; repl locators resolve the repl's " +
        "current evolving proof context each time an operation executes.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" -> JSON.Object(
            "context" -> JSON.Object("type" -> "string")),
          "required" -> List("context")),
      annotations = MCP_Server.mutating_annotations,
      handler_fn = Some((backend, args, cancellation) => {
        args.collectFirst({ case ("context", value) => value }) match {
          case None => MCP_Session.Error("tool_scope_set: context is required")
          case Some(candidate) =>
            backend.check_context_cancellable(candidate, cancellation) match {
              case MCP_Session.Ok(canonical) =>
                scope.change(_ => Some(canonical))
                MCP_Session.Ok("Tool scope set to " + canonical)
              case error @ MCP_Session.Error(_) => error
            }
        }
      }))

  private val tool_scope_builtins: List[MCP_Server.Builtin_Tool] =
    List(tool_scope_show_tool, tool_scope_set_tool)

  private def all_builtins: List[MCP_Server.Builtin_Tool] =
    MCP_Server.builtins ++ tool_scope_builtins

  private def outputDependentForm(form: String): Boolean =
    Set("diag_wrap", "method_wrap", "capture")(form)

  private def outputDisabled(name: String): MCP_Session.Error =
    MCP_Session.Error(
      "Tool " + quote(name) + " requires captured Isabelle output, but " +
        "mcp_untrusted_output_bytes is 0; restart with " +
        "-o mcp_untrusted_output_bytes=BYTES to enable bounded output")

  private def listedBuiltins: List[MCP_Server.Builtin_Tool] =
    if (outputPolicy.allowsUntrustedOutput) all_builtins
    else all_builtins.filterNot(_.requires_untrusted_output)

  private def tools_list(cancellation: Cancellation): Outcome = {
    val builtins = listedBuiltins
    readiness() match {
      case McpApplication.Not_Ready(_) | McpApplication.Failed(_) =>
        val builtin_json = builtins.map(tool_json)
        Outcome.Result(JSON.Object("tools" -> builtin_json))
      case McpApplication.Ready(backend) =>
        /* Disabled builtins remain reserved: an ML collision must not be
           advertised under a name whose dispatch is owned by a gated builtin. */
        val builtin_names = all_builtins.map(_.name).toSet
        current_context(backend, cancellation) match {
          case MCP_Session.Error(_) =>
            Outcome.Result(JSON.Object("tools" -> builtins.map(tool_json)))
          case MCP_Session.Ok(context) =>
            val reply = backend.ml_tools_cancellable(context, cancellation)
            val hidden = reply.builtin_activation.collect({ case (name, false) => name }).toSet
            val builtin_json = builtins.filterNot(tool => hidden(tool.name)).map(tool_json)
            val exposed = MCP_Server.exposure(reply.rows.map(_.name), builtin_names)
            val rows =
              if (outputPolicy.allowsUntrustedOutput) reply.rows
              else reply.rows.filterNot(row => outputDependentForm(row.form))
            val ml_json =
              rows.flatMap(row =>
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
          case Some(tool) if tool.requires_untrusted_output &&
              !outputPolicy.allowsUntrustedOutput =>
            text_outcome(outputDisabled(name))
          case Some(tool) =>
            text_outcome(tool.handler(backend, MCP_Server.json_args(arguments), cancellation))
          case None =>
            current_context(backend, cancellation) match {
              case error @ MCP_Session.Error(_) => text_outcome(error)
              case MCP_Session.Ok(context) =>
                val rows = backend.ml_tools_cancellable(context, cancellation).rows
                val exposed = MCP_Server.exposure(rows.map(_.name), all_builtins.map(_.name).toSet)
                val selected =
                  rows.find(row => exposed.get(row.name).contains(name) || row.name == name)
                selected match {
                  case Some(row) if outputDependentForm(row.form) &&
                      !outputPolicy.allowsUntrustedOutput =>
                    text_outcome(outputDisabled(name))
                  case Some(row) =>
                    text_outcome(backend.ml_run_cancellable(
                      row.name, MCP_Server.json_args(arguments), context, cancellation))
                  case None =>
                    text_outcome(backend.ml_run_cancellable(
                      name, MCP_Server.json_args(arguments), context, cancellation))
                }
            }
        }
    }

  private def resources_list(cancellation: Cancellation): Outcome =
    readiness() match {
      case McpApplication.Not_Ready(_) | McpApplication.Failed(_) =>
        Outcome.Result(JSON.Object("resources" -> List(
          JSON.Object("uri" -> "isabelle://session", "name" -> "session",
            "description" -> "current session name, dirs, loaded theories"))))
      case McpApplication.Ready(backend) =>
        val resources = backend.mcp_resources_cancellable(cancellation).map({ case (uri, name, description) =>
          JSON.Object("uri" -> uri, "name" -> name, "description" -> description)
        })
        Outcome.Result(JSON.Object("resources" -> resources))
    }

  private def resources_read(uri: String, cancellation: Cancellation): Outcome =
    readiness() match {
      case McpApplication.Ready(backend) =>
        backend.mcp_resource_read_cancellable(uri, cancellation) match {
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
    try {
      operation match {
        case Operation.ToolsList => tools_list(cancellation)
        case Operation.ToolsCall(name, arguments) => tools_call(name, arguments, cancellation)
        case Operation.ResourcesList => resources_list(cancellation)
        case Operation.ResourceTemplatesList =>
          Outcome.Result(JSON.Object("resourceTemplates" -> MCP_Server.resource_templates))
        case Operation.ResourcesRead(uri) => resources_read(uri, cancellation)
      }
    }
    catch {
      case MCP_Session.BridgeTimedOut(delay) => Outcome.TimedOut(delay)
    }
}
