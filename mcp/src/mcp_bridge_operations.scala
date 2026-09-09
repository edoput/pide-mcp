/*  Title:      mcp/src/mcp_bridge_operations.scala

Typed application operations and payload codecs for the internal PIDE bridge.
Provides base and HOL profiles specifying the operations that startup
negotiation must confirm before the selected application can use the bridge.
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
  val hol: McpBridgeProfile =
    McpBridgeProfile("hol", McpBridgeOperations.holOperationNames)
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

  def theories: BridgeOperation[List[String]] =
    new Operation[List[String]]("theories", XML.Encode.unit(())) {
      protected def decode(payload: XML.Body): List[String] =
        MCP_Session.decode_theories(payload)
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

  def ir(fname: String, args: List[(String, String)]): BridgeOperation[MCP_Session.Result] =
    new Operation[MCP_Session.Result](
      "ir", XML.Encode.pair(XML.Encode.string, encodeArgs)((fname, args))) {
      protected def decode(payload: XML.Body): MCP_Session.Result = {
        val (status, body) =
          XML.Decode.pair(XML.Decode.string, XML.Decode.self)(payload)
        statusResult(status, XML.content(body))
      }
    }

  def resources(context: String): BridgeOperation[List[(String, String)]] =
    new Operation[List[(String, String)]]("resources", XML.Encode.string(context)) {
      protected def decode(payload: XML.Body): List[(String, String)] =
        MCP_Session.decode_resources(payload)
    }

  def readResource(context: String, name: String): BridgeOperation[MCP_Session.Result] =
    statusText(
      "read_resource",
      XML.Encode.pair(XML.Encode.string, XML.Encode.string)((context, name)))

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

  val operationNames: Set[String] =
    Set("tools", "theories", "run_tool", "check_context", "ir",
      "resources", "read_resource")

  /* Startup requirements are bridge-operation names, not public MCP tool
     names.  The ML registry is extensible, so a hello may advertise more. */
  val baseOperationNames: Set[String] =
    Set("tools", "theories", "run_tool", "check_context", "resources", "read_resource")
  val holOperationNames: Set[String] = baseOperationNames + "ir"
}
