/*  Title:      mcp/src/mcp_pide_protocol.scala

Versioned internal protocol for the Scala-to-Isabelle/ML bridge.
*/

package isabelle.mcp.pide

import isabelle.{Bytes, Markup, Properties, Symbol, XML, YXML}

import scala.util.control.NonFatal


sealed trait PideBridgeReply


object PideBridgeReply {
  final case class Success(id: String, operation: String, payload: XML.Body)
    extends PideBridgeReply
  final case class Failure(id: String, operation: String, failure: BridgeFailure)
    extends PideBridgeReply
  final case class Malformed(
    id: Option[String],
    operation: Option[String],
    detail: String
  ) extends PideBridgeReply
}


trait PideBridgeProtocol {
  def revision: String
  def resultFunctions: Set[String]
  def hello(id: String, theory: String): PideTransport.Outbound
  def call(id: String, theory: String, operation: String,
    payload: XML.Body): PideTransport.Outbound
  def cancel(id: String): PideTransport.Outbound
  def decode(reply: PideTransport.Inbound): PideBridgeReply
}


object PideBridgeV1 extends PideBridgeProtocol {
  import BridgeFailure._
  import PideBridgeReply._

  val revision = "1"
  val Command = "MCP.bridge"
  val ResultFunction = "MCP.bridge_result"
  val resultFunctions: Set[String] = Set(ResultFunction)

  private val RequestElement = "mcp_bridge"
  private val ResultElement = "mcp_bridge_result"

  def hello(id: String, theory: String): PideTransport.Outbound =
    outbound(List(
      "revision" -> revision,
      "kind" -> "hello",
      "id" -> id,
      "theory" -> theory,
      "operation" -> "hello"), Nil)

  def call(id: String, theory: String, operation: String,
    payload: XML.Body): PideTransport.Outbound =
    outbound(List(
      "revision" -> revision,
      "kind" -> "call",
      "id" -> id,
      "theory" -> theory,
      "operation" -> operation), payload)

  def cancel(id: String): PideTransport.Outbound =
    outbound(List("revision" -> revision, "kind" -> "cancel", "id" -> id), Nil)

  private def outbound(properties: Properties.T,
    payload: XML.Body): PideTransport.Outbound = {
    val body = List(XML.Elem(Markup(RequestElement, properties), payload))
    PideTransport.Outbound(
      Command,
      List(Bytes(YXML.string_of_body(body, recode = Symbol.encode))))
  }

  def decode(reply: PideTransport.Inbound): PideBridgeReply = {
    if (reply.function != ResultFunction)
      Malformed(reply.property("id"), None,
        "unknown PIDE bridge result function " + reply.function)
    else {
      try {
        YXML.parse_body(reply.body) match {
          case List(XML.Elem(Markup(ResultElement, properties), payload)) =>
            decodeResult(properties, payload)
          case _ => Malformed(None, None, "expected one " + ResultElement + " element")
        }
      }
      catch {
        case NonFatal(exn) =>
          Malformed(None, None,
            "cannot decode bridge result envelope: " +
              Option(exn.getMessage).getOrElse(exn.getClass.getName))
      }
    }
  }

  private def decodeResult(properties: Properties.T,
    payload: XML.Body): PideBridgeReply = {
    val id = Properties.get(properties, "id")
    val operation = Properties.get(properties, "operation")

    def malformed(detail: String): PideBridgeReply = Malformed(id, operation, detail)
    def required(name: String): Either[String, String] =
      Properties.get(properties, name).toRight("missing " + name)

    val decoded =
      for {
        actualRevision <- required("revision")
        _ <- Either.cond(actualRevision == revision, (),
          "unsupported bridge revision " + actualRevision)
        kind <- required("kind")
        _ <- Either.cond(kind == "result", (), "invalid result kind " + kind)
        callId <- required("id")
        op <- required("operation")
        status <- required("status")
      } yield (callId, op, status)

    decoded match {
      case Left(detail) => malformed(detail)
      case Right((callId, op, "ok")) => Success(callId, op, payload)
      case Right((callId, op, "remote_error")) =>
        decodeDetail(payload).fold(
          detail => malformed("invalid remote_error payload: " + detail),
          detail => Failure(callId, op, RemoteError(detail)))
      case Right((callId, op, "protocol_error")) =>
        decodeDetail(payload).fold(
          detail => malformed("invalid protocol_error payload: " + detail),
          detail => Failure(callId, op, ProtocolError(detail)))
      case Right((callId, op, status)) =>
        malformed("invalid bridge result status " + status)
    }
  }

  private def decodeDetail(payload: XML.Body): Either[String, String] =
    try Right(XML.Decode.string(payload))
    catch {
      case NonFatal(exn) =>
        Left(Option(exn.getMessage).getOrElse(exn.getClass.getName))
    }

  private[mcp] def result(
    id: String,
    operation: String,
    status: String,
    payload: XML.Body,
    actualRevision: String = revision
  ): PideTransport.Inbound = {
    val body = List(XML.Elem(
      Markup(ResultElement, List(
        "revision" -> actualRevision,
        "kind" -> "result",
        "id" -> id,
        "operation" -> operation,
        "status" -> status)),
      payload))
    PideTransport.Inbound(
      ResultFunction,
      List(Markup.FUNCTION -> ResultFunction),
      Bytes(YXML.string_of_body(body)),
      "")
  }
}
