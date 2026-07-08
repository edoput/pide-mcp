/*  Title:      mcp/src/mcp_server.scala

MCP server over stdio: newline-delimited JSON-RPC 2.0 on stdin/stdout.

Nothing but protocol replies may be written to stdout; all logging goes
through the given progress (Console_Progress(stderr = true) in the tool).
*/

package isabelle.mcp

import isabelle._

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets

object MCP_Server {
  val server_name = "isabelle-mcp"
  val server_version = "0.1.0"
  val default_protocol_version = "2025-03-26"

  val input_schema: JSON.Object.T =
    JSON.Object(
      "type" -> "object",
      "properties" -> JSON.Object("input" -> JSON.Object("type" -> "string")),
      "required" -> List("input"))


  /* json-rpc 2.0 */

  object RPC {
    val PARSE_ERROR = -32700
    val INVALID_REQUEST = -32600
    val METHOD_NOT_FOUND = -32601
    val INVALID_PARAMS = -32602

    def response(id: JSON.T, result: JSON.T): JSON.Object.T =
      JSON.Object("jsonrpc" -> "2.0", "id" -> id, "result" -> result)

    def error(id: JSON.T, code: Int, message: String): JSON.Object.T =
      JSON.Object("jsonrpc" -> "2.0", "id" -> id,
        "error" -> JSON.Object("code" -> code, "message" -> message))
  }

  def text_result(text: String, is_error: Boolean = false): JSON.Object.T = {
    val result = JSON.Object("content" -> List(JSON.Object("type" -> "text", "text" -> text)))
    if (is_error) result + ("isError" -> true) else result
  }


  /* server loop */

  def run(
    options: Options,
    session_name: String,
    session_dirs: List[Path],
    theory: String,
    progress: Progress = new Progress
  ): Unit = {
    val mcp_session =
      MCP_Session.start(options, session_name, session_dirs, theory, progress = progress)
    progress.echo("MCP server ready")

    def handle(json: JSON.T): Option[JSON.Object.T] = {
      val id = JSON.value(json, "id").orNull
      val has_id = JSON.value(json, "id").isDefined

      JSON.string(json, "method") match {
        case None =>
          if (has_id) Some(RPC.error(id, RPC.INVALID_REQUEST, "Missing method")) else None

        case Some("initialize") =>
          val protocol_version =
            JSON.value(json, "params").flatMap(JSON.string(_, "protocolVersion"))
              .getOrElse(default_protocol_version)
          Some(RPC.response(id,
            JSON.Object(
              "protocolVersion" -> protocol_version,
              "capabilities" -> JSON.Object("tools" -> JSON.Object()),
              "serverInfo" ->
                JSON.Object("name" -> server_name, "version" -> server_version))))

        case Some("notifications/initialized") => None

        case Some("ping") => Some(RPC.response(id, JSON.Object()))

        case Some("tools/list") =>
          val tools =
            mcp_session.ml_tools().map({ case (name, description) =>
              JSON.Object(
                "name" -> name,
                "description" -> description,
                "inputSchema" -> input_schema)
            })
          Some(RPC.response(id, JSON.Object("tools" -> tools)))

        case Some("tools/call") =>
          val params = JSON.value(json, "params").getOrElse(JSON.Object())
          JSON.string(params, "name") match {
            case None => Some(RPC.error(id, RPC.INVALID_PARAMS, "Missing tool name"))
            case Some(name) =>
              val arguments = JSON.value(params, "arguments").getOrElse(JSON.Object())
              JSON.string(arguments, "input") match {
                case None =>
                  Some(RPC.response(id,
                    text_result("Missing required argument: input", is_error = true)))
                case Some(input) =>
                  mcp_session.ml_run(name, input) match {
                    case MCP_Session.Ok(text) =>
                      Some(RPC.response(id, text_result(text)))
                    case MCP_Session.Error(message) =>
                      Some(RPC.response(id, text_result(message, is_error = true)))
                  }
              }
          }

        case Some(method) =>
          if (has_id) Some(RPC.error(id, RPC.METHOD_NOT_FOUND, "Method not found: " + method))
          else None
      }
    }

    def print_json(json: JSON.T): Unit = {
      System.out.println(JSON.Format(json))
      System.out.flush()
    }

    val stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
    try {
      var finished = false
      while (!finished) {
        stdin.readLine() match {
          case null => finished = true
          case line if line.isBlank =>
          case line =>
            val reply =
              JSON.Format.unapply(line) match {
                case None => Some(RPC.error(null, RPC.PARSE_ERROR, "Parse error"))
                case Some(json) => handle(json)
              }
            reply.foreach(print_json)
        }
      }
    }
    finally {
      progress.echo("Shutting down PIDE session ...")
      mcp_session.stop()
    }
  }
}
