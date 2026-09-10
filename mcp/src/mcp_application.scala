/*  Title:      mcp/src/mcp_application.scala

Typed application boundary for one MCP connection.  This package owns ordinary
tool dispatch; JSON-RPC IDs, transport,
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

  private def not_ready_text(progress: String): String =
    "session " + session_name + " is not ready: " + progress + ". This tool needs " +
    "the prover; retry shortly."

  private def failed_text(message: String): String =
    "session " + session_name + " failed to start: " + message + ". The server " +
    "cannot serve prover-backed tools; restart it after fixing the build."

  private def text_outcome(result: MCP_Session.Result): Outcome =
    result match {
      case MCP_Session.Ok(text) => Outcome.Result(MCP_Server.text_result(text))
      case MCP_Session.Error(message) =>
        Outcome.Result(MCP_Server.text_result(message, is_error = true))
    }

  private def tools_list(cancellation: Cancellation): Outcome = {
    val builtins = MCP_Server.builtins
    readiness() match {
      case McpApplication.Not_Ready(_) | McpApplication.Failed(_) =>
        val builtin_json = builtins.map(tool_json)
        Outcome.Result(JSON.Object("tools" -> builtin_json))
      case McpApplication.Ready(backend) =>
        val builtin_names = builtins.map(_.name).toSet
        backend.root_context_cancellable(cancellation) match {
          case MCP_Session.Error(_) =>
            Outcome.Result(JSON.Object("tools" -> builtins.map(tool_json)))
          case MCP_Session.Ok(context) =>
            val reply = backend.ml_tools_cancellable(context, cancellation)
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
        MCP_Server.builtins.find(_.name == name) match {
          case Some(tool) =>
            text_outcome(tool.handler(backend, MCP_Server.json_args(arguments), cancellation))
          case None =>
            backend.root_context_cancellable(cancellation) match {
              case error @ MCP_Session.Error(_) => text_outcome(error)
              case MCP_Session.Ok(context) =>
                val exposed =
                  MCP_Server.exposure(
                    backend.ml_tools_cancellable(context, cancellation).rows.map(_.name),
                    MCP_Server.builtins.map(_.name).toSet)
                val internal = exposed.collectFirst({ case (full, visible) if visible == name => full })
                  .getOrElse(name)
                text_outcome(
                  backend.ml_run_cancellable(
                    internal, MCP_Server.json_args(arguments), context, cancellation))
            }
        }
    }

  def execute(operation: Operation, cancellation: Cancellation): Outcome =
    try {
      operation match {
        case Operation.ToolsList => tools_list(cancellation)
        case Operation.ToolsCall(name, arguments) => tools_call(name, arguments, cancellation)
      }
    }
    catch {
      case MCP_Session.BridgeTimedOut(delay) => Outcome.TimedOut(delay)
    }
}
