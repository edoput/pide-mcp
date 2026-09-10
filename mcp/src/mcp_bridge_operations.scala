/*  Title:      mcp/src/mcp_bridge_operations.scala

Typed application payload codecs for the internal PIDE bridge.
*/

package isabelle.mcp

import isabelle.XML
import isabelle.mcp.pide.BridgeOperation

import scala.util.control.NonFatal


/** Reviewed bridge surface selected by the composition root.
  *
  * The startup hello may advertise additional extension operations, but it
  * must include every operation required by the selected application surface.
  */
private[mcp] final case class McpBridgeProfile private (
  name: String,
  requiredOperationNames: Set[String]
)

private[mcp] object McpBridgeProfile {
  val base: McpBridgeProfile =
    McpBridgeProfile("base", McpBridgeOperations.baseOperationNames)
}


private[mcp] object McpBridgeOperations {
  private abstract class Operation[A](
    val name: String,
    val requestPayload: XML.Body
  ) extends BridgeOperation[A] {
    protected def decode(payload: XML.Body): A

    final def decodeReply(payload: XML.Body): Either[String, A] =
      try Right(decode(payload))
      catch {
        case NonFatal(exn) =>
          val detail = Option(exn.getMessage).filter(_.nonEmpty)
            .getOrElse(exn.getClass.getName)
          Left(detail + "; payload=" + payload)
      }
  }

  private val encodeArgs =
    XML.Encode.list(XML.Encode.pair(XML.Encode.string, XML.Encode.string))
  private val decodeStatusBody =
    XML.Decode.pair(XML.Decode.string, XML.Decode.self)

  def tools(context: String): BridgeOperation[MCP_Session.Tools_Reply] =
    new Operation[MCP_Session.Tools_Reply]("tools", XML.Encode.string(context)) {
      protected def decode(payload: XML.Body): MCP_Session.Tools_Reply =
        MCP_Session.decode_tools_reply(payload)
    }

  def runTool(context: String, name: String,
    args: List[(String, String)]): BridgeOperation[MCP_Session.Result] =
    statusText(
      "run_tool",
      XML.Encode.pair(XML.Encode.string,
        XML.Encode.pair(XML.Encode.string, encodeArgs))((context, (name, args))))

  /** None asks ML for the canonical registry-root context. */
  def checkContext(context: Option[String]): BridgeOperation[MCP_Session.Result] =
    statusText("check_context", XML.Encode.option(XML.Encode.string)(context))

  private def statusText(operation: String,
    request: XML.Body): BridgeOperation[MCP_Session.Result] =
    new Operation[MCP_Session.Result](operation, request) {
      protected def decode(payload: XML.Body): MCP_Session.Result = {
        val (status, body) = decodeStatusBody(payload)
        statusResult(status, XML.content(body))
      }
    }

  private def statusResult(status: String, text: String): MCP_Session.Result =
    if (status == "ok") MCP_Session.Ok(text) else MCP_Session.Error(text)

  val operationNames: Set[String] = Set("tools", "run_tool", "check_context")
  val baseOperationNames: Set[String] = operationNames
}
