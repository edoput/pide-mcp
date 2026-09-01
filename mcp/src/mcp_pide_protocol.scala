/*  Title:      mcp/src/mcp_pide_protocol.scala

Versioned internal protocol for the Scala-to-Isabelle/ML bridge.
*/

package isabelle.mcp.pide

import isabelle.{Bytes, Markup, Properties, Symbol, XML, YXML}

import scala.util.control.NonFatal


sealed trait PideBridgeReply


object PideBridgeReply {
  final case class DrainAck(id: String) extends PideBridgeReply
  final case class DrainFailure(id: String, failure: BridgeFailure) extends PideBridgeReply
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
  def drain(id: String): PideTransport.Outbound
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

  def drain(id: String): PideTransport.Outbound =
    outbound(List("revision" -> revision, "kind" -> "drain", "id" -> id), Nil)

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
    def exactlyOnce(name: String): Boolean = properties.count(_._1 == name) == 1

    val decoded =
      for {
        actualRevision <- required("revision")
        _ <- Either.cond(actualRevision == revision, (),
          "unsupported bridge revision " + actualRevision)
        kind <- required("kind")
        callId <- required("id")
        status <- required("status")
      } yield (callId, kind, status)

    decoded match {
      case Left(detail) => malformed(detail)
      case Right((callId, "drain", "ok"))
          if List("revision", "kind", "id", "status").forall(exactlyOnce) &&
            properties.length == 4 && operation.isEmpty && payload.isEmpty => DrainAck(callId)
      case Right((_, "drain", "ok")) => malformed("invalid drain acknowledgement")
      case Right((callId, "drain", "protocol_error"))
          if List("revision", "kind", "id", "status").forall(exactlyOnce) &&
            properties.length == 4 && operation.isEmpty =>
        decodeDetail(payload).fold(
          detail => malformed("invalid drain protocol_error payload: " + detail),
          detail => DrainFailure(callId, ProtocolError(detail)))
      case Right((_, "drain", status)) => malformed("invalid drain acknowledgement status " + status)
      case Right((callId, "result", status)) =>
        operation match {
          case None => malformed("missing operation")
          case Some(op) => status match {
            case "ok" => Success(callId, op, payload)
            case "remote_error" => decodeDetail(payload).fold(
              detail => malformed("invalid remote_error payload: " + detail),
              detail => Failure(callId, op, RemoteError(detail)))
            case "protocol_error" => decodeDetail(payload).fold(
              detail => malformed("invalid protocol_error payload: " + detail),
              detail => Failure(callId, op, ProtocolError(detail)))
            case _ => malformed("invalid bridge result status " + status)
          }
        }
      case Right((_, kind, _)) => malformed("invalid bridge result kind " + kind)
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

  private[mcp] def drainResult(
    id: String,
    status: String = "ok",
    payload: XML.Body = Nil,
    properties: Properties.T = Nil,
    actualRevision: String = revision
  ): PideTransport.Inbound = {
    val body = List(XML.Elem(
      Markup(ResultElement,
        List(
          "revision" -> actualRevision,
          "kind" -> "drain",
          "id" -> id,
          "status" -> status) ::: properties),
      payload))
    PideTransport.Inbound(
      ResultFunction,
      List(Markup.FUNCTION -> ResultFunction, "id" -> id),
      Bytes(YXML.string_of_body(body)),
      "")
  }
}
