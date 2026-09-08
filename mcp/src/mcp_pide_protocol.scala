/*  Title:      mcp/src/mcp_pide_protocol.scala

Versioned internal protocol for the Scala-to-Isabelle/ML bridge.

This file owns the inner PideBridge request/result envelope.  Requests become
one serialized YXML byte argument of the raw `MCP.bridge` protocol command;
results arrive as one serialized YXML body chunk of a
`function=MCP.bridge_result` protocol message.  Isabelle's PIDE transport adds
and removes the outer comma-separated byte-length header.

The raw framing can be remembered as follows.  Here X is YXML.X_byte (0x05),
Y is YXML.Y_byte (0x06), N is the byte length of the YXML envelope, and `|` is
only a visual chunk boundary -- it is not present on the wire:

  Scala -> ML request

    10,N\n | MCP.bridge | X Y mcp_bridge
                            Y revision=1 Y kind=call Y id=7
                            Y theory=MCP_Tools Y operation=tools X
                            PAYLOAD
                            X Y X

  ML -> Scala result

    8,1,26,4,15,N\n | protocol | 3 | function=MCP.bridge_result
                              | id=7 | operation=tools
                              | X Y mcp_bridge_result
                                  Y revision=1 Y kind=result Y id=7
                                  Y operation=tools Y status=ok X
                                  PAYLOAD
                                  X Y X

After the header newline the chunks are concatenated with no delimiters: their
declared byte lengths are the only outer framing.  Within the final chunk,
`X Y name Y key=value ... X` opens a YXML element and `X Y X` closes it.
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
  /** Correlation recovered from outer PIDE properties only: its body was not parsed. */
  final case class Oversized(id: Option[String], operation: Option[String], detail: String)
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
  /** Size of the already-serialized request envelope that this protocol sends.
    * A protocol must not reconstruct or reserialize it for this check. */
  def requestBytes(outbound: PideTransport.Outbound): Either[BridgeFailure, Long]
  def oversized(reply: PideTransport.Inbound): PideBridgeReply
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

  def oversized(reply: PideTransport.Inbound): PideBridgeReply = {
    val ids = propertyValues(reply.properties, "id")
    val operations = propertyValues(reply.properties, "operation")
    val id = uniqueProperty(reply.properties, "id").filter(_.nonEmpty)
    val operation = uniqueProperty(reply.properties, "operation").filter(_.nonEmpty)
    val problem =
      if (reply.function != ResultFunction)
        Some("unknown PIDE bridge result function " + reply.function)
      else if (ids.lengthCompare(1) > 0) Some("duplicate outer id")
      else if (ids.contains("")) Some("empty outer id")
      else if (operations.lengthCompare(1) > 0) Some("duplicate outer operation")
      else if (operations.contains("")) Some("empty outer operation")
      else None
    problem match {
      case Some(detail) => Malformed(id, operation, detail)
      case None => Oversized(id, operation, "oversized PIDE bridge result")
    }
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

  def requestBytes(outbound: PideTransport.Outbound): Either[BridgeFailure, Long] =
    outbound match {
      case PideTransport.Outbound(Command, List(source)) => Right(source.size)
      case _ => Left(ProtocolError(
        "invalid PIDE bridge v1 outbound envelope: expected " + Command +
          " with exactly one serialized YXML argument"))
    }

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

    if (outerId.isEmpty) malformed("missing outer id")
    else correlationConflict match {
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
      case Right((_, "result", _)) if outerOperation.isEmpty =>
        malformed("missing outer operation")
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
            case "too_large" => decodeSizePayload(payload).fold(
              detail => malformed("invalid too_large payload: " + detail),
              { case (actual, limit) => Failure(callId, op,
                TooLarge(Direction.Reply, actual, limit)) })
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

  private def decodeSizePayload(payload: XML.Body): Either[String, (Long, Long)] = {
    def decimal(name: String, value: String): Either[String, Long] =
      if (value.nonEmpty && value.forall(_.isDigit))
        try Right(java.lang.Long.parseLong(value))
        catch { case _: NumberFormatException => Left("invalid " + name + " byte count") }
      else Left("invalid " + name + " byte count")

    try {
      val (actualText, limitText) = XML.Decode.pair(XML.Decode.string, XML.Decode.string)(payload)
      for {
        actual <- decimal("actual", actualText)
        limit <- decimal("limit", limitText)
        _ <- Either.cond(limit > 0L, (), "limit byte count must be positive")
        _ <- Either.cond(actual > limit, (), "actual byte count must exceed limit")
      } yield (actual, limit)
    }
    catch {
      case NonFatal(exn) => Left(Option(exn.getMessage).getOrElse(exn.getClass.getName))
    }
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
      List(Markup.FUNCTION -> ResultFunction, "id" -> id, "operation" -> operation),
      Bytes(YXML.string_of_body(body)))
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
      Bytes(YXML.string_of_body(body)))
  }
}
