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

  private def propertyValues(properties: Properties.T, name: String): List[String] =
    properties.collect { case (`name`, value) => value }

  private def uniqueProperty(properties: Properties.T, name: String): Option[String] =
    propertyValues(properties, name) match {
      case List(value) => Some(value)
      case _ => None
    }

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
    val outerIdValues = propertyValues(reply.properties, "id")
    val outerOperationValues = propertyValues(reply.properties, "operation")
    val outerId = uniqueProperty(reply.properties, "id").filter(_.nonEmpty)
    val outerOperation = uniqueProperty(reply.properties, "operation").filter(_.nonEmpty)
    val outerProblem =
      if (outerIdValues.lengthCompare(1) > 0) Some("duplicate outer id")
      else if (outerIdValues.contains("")) Some("empty outer id")
      else if (outerOperationValues.lengthCompare(1) > 0) Some("duplicate outer operation")
      else if (outerOperationValues.contains("")) Some("empty outer operation")
      else None
    def malformed(detail: String): PideBridgeReply =
      Malformed(outerId, outerOperation, detail)

    if (reply.function != ResultFunction)
      malformed("unknown PIDE bridge result function " + reply.function)
    else if (outerProblem.isDefined)
      malformed(outerProblem.get)
    else {
      try {
        YXML.parse_body(reply.body) match {
          case List(XML.Elem(Markup(ResultElement, properties), payload)) =>
            decodeResult(properties, payload, outerId, outerOperation)
          case _ => malformed("expected one " + ResultElement + " element")
        }
      }
      catch {
        case NonFatal(exn) =>
          malformed("cannot decode bridge result envelope: " +
            Option(exn.getMessage).getOrElse(exn.getClass.getName))
      }
    }
  }

  private def decodeResult(properties: Properties.T,
    payload: XML.Body,
    outerId: Option[String],
    outerOperation: Option[String]): PideBridgeReply = {
    val innerId = uniqueProperty(properties, "id").filter(_.nonEmpty)
    val innerOperation = uniqueProperty(properties, "operation").filter(_.nonEmpty)
    val id = outerId.orElse(innerId)
    val operation = outerOperation.orElse(innerOperation)

    def malformed(detail: String): PideBridgeReply = Malformed(id, operation, detail)
    def required(name: String): Either[String, String] =
      properties.filter(_._1 == name) match {
        case List((_, value)) if value.nonEmpty => Right(value)
        case List(_) => Left("empty " + name)
        case Nil => Left("missing " + name)
        case _ => Left("duplicate " + name)
      }
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

    val correlationConflict =
      (outerId, innerId) match {
        case (Some(outer), Some(inner)) if outer != inner =>
          Some("inner id " + inner + " does not match outer id " + outer)
        case _ =>
          (outerOperation, innerOperation) match {
            case (Some(outer), Some(inner)) if outer != inner =>
              Some("inner operation " + inner + " does not match outer operation " + outer)
            case _ => None
          }
      }

    correlationConflict match {
      case Some(detail) => malformed(detail)
      case None => decoded match {
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
      case Right((callId, "result", status))
          if List("revision", "kind", "id", "operation", "status").forall(exactlyOnce) &&
            properties.length == 5 =>
        required("operation") match {
          case Left(detail) => malformed(detail)
          case Right(op) => status match {
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
      case Right((_, "result", _)) => malformed("invalid ordinary result properties")
      case Right((_, kind, _)) => malformed("invalid bridge result kind " + kind)
      }
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
    actualRevision: String = revision,
    properties: Properties.T = Nil
  ): PideTransport.Inbound = {
    val body = List(XML.Elem(
      Markup(ResultElement, List(
        "revision" -> actualRevision,
        "kind" -> "result",
        "id" -> id,
        "operation" -> operation,
        "status" -> status) ::: properties),
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
