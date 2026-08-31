/*  Title:      mcp/src/mcp_legacy_operations.scala

Application adapters for the characterized legacy Scala-to-ML wire.
*/

package isabelle.mcp

import isabelle.{Bytes, XML, YXML}
import isabelle.mcp.pide.{BridgeOperation, LegacyWire}
import isabelle.mcp.pide.PideTransport.{Inbound, Outbound}


/** Typed domain adapters kept outside the generic bridge package. */
private[mcp] object LegacyOperations {
  private abstract class Operation[A](wire: LegacyWire.Operation,
    expectedReply: LegacyWire.ReplyShape)
      extends BridgeOperation[A] {
    val name: String = wire.name
    val resultFunction: String = wire.resultFunction
    protected def arguments(id: String): List[(String, Bytes)]
    protected def decode(reply: Inbound): A

    final def outbound(id: String): Outbound =
      Outbound(wire.command, LegacyWire.arguments(wire, arguments(id)*))

    final def decodeReply(reply: Inbound): Either[String, A] = {
      LegacyWire.expectReply(wire, expectedReply)
      Right(decode(reply))
    }
  }

  def tools(designation: String, bundles: List[String]): BridgeOperation[MCP_Session.Tools_Reply] =
    new Operation[MCP_Session.Tools_Reply](LegacyWire.Tools, LegacyWire.ReplyShape.ToolsYxml) {
      protected def arguments(id: String): List[(String, Bytes)] =
        List(
          "id" -> Bytes(id),
          "designation" -> Bytes(designation),
          "bundles_yxml" -> Bytes(MCP_Session.encode_names(bundles)))
      protected def decode(reply: Inbound): MCP_Session.Tools_Reply =
        MCP_Session.decode_tools_reply(YXML.parse_body(reply.body))
    }

  def theories: BridgeOperation[List[String]] =
    new Operation[List[String]](LegacyWire.Theories, LegacyWire.ReplyShape.TheoriesYxml) {
      protected def arguments(id: String): List[(String, Bytes)] = List("id" -> Bytes(id))
      protected def decode(reply: Inbound): List[String] =
        MCP_Session.decode_theories(YXML.parse_body(reply.body))
    }

  def runTool(designation: String, bundles: List[String], name: String,
    args: List[(String, String)]): BridgeOperation[MCP_Session.Result] =
    statusText(LegacyWire.RunTool,
      id => List(
        "id" -> Bytes(id),
        "designation" -> Bytes(designation),
        "bundles_yxml" -> Bytes(MCP_Session.encode_names(bundles)),
        "name" -> Bytes(name),
        "args_yxml" -> Bytes(MCP_Session.encode_args(args))))

  def checkDesignation(designation: String,
    bundles: List[String]): BridgeOperation[MCP_Session.Result] =
    statusText(LegacyWire.CheckDesignation,
      id => List(
        "id" -> Bytes(id),
        "designation" -> Bytes(designation),
        "bundles_yxml" -> Bytes(MCP_Session.encode_names(bundles))))

  def ir(fname: String, args: List[(String, String)]): BridgeOperation[MCP_Session.Result] =
    new Operation[MCP_Session.Result](LegacyWire.Ir, LegacyWire.ReplyShape.StatusYxml) {
      protected def arguments(id: String): List[(String, Bytes)] =
        List(
          "id" -> Bytes(id),
          "fname" -> Bytes(fname),
          "args_yxml" -> Bytes(MCP_Session.encode_args(args)))
      protected def decode(reply: Inbound): MCP_Session.Result = {
        val text = XML.content(YXML.parse_body(YXML.Source(reply.text)))
        statusResult(reply, text)
      }
    }

  def resources(designation: String): BridgeOperation[List[(String, String)]] =
    new Operation[List[(String, String)]](
      LegacyWire.Resources, LegacyWire.ReplyShape.ResourcesYxml) {
      protected def arguments(id: String): List[(String, Bytes)] =
        List("id" -> Bytes(id), "designation" -> Bytes(designation))
      protected def decode(reply: Inbound): List[(String, String)] =
        MCP_Session.decode_resources(YXML.parse_body(reply.body))
    }

  def readResource(name: String, designation: String): BridgeOperation[MCP_Session.Result] =
    statusText(LegacyWire.ReadResource,
      id => List(
        "id" -> Bytes(id),
        "designation" -> Bytes(designation),
        "name" -> Bytes(name)))

  private def statusText(wire: LegacyWire.Operation,
    request: String => List[(String, Bytes)]): BridgeOperation[MCP_Session.Result] =
    new Operation[MCP_Session.Result](wire, LegacyWire.ReplyShape.StatusText) {
      protected def arguments(id: String): List[(String, Bytes)] = request(id)
      protected def decode(reply: Inbound): MCP_Session.Result = statusResult(reply, reply.text)
    }

  private def statusResult(reply: Inbound, text: String): MCP_Session.Result =
    if (reply.property("status") == Some("ok")) MCP_Session.Ok(text)
    else MCP_Session.Error(text)

  val resultOperations: Map[String, String] =
    LegacyWire.operations.map(operation => operation.resultFunction -> operation.name).toMap

  def cancel(id: String): Outbound = Outbound("MCP.cancel", List(Bytes(id)))
}
