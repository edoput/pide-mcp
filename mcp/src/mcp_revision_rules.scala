/*  Title:      mcp/src/mcp_revision_rules.scala

MCP 2025-03-26 message classification.  This is protocol validation only:
admission/lifecycle decisions are owned by ConnectionKernel.
*/

package isabelle.mcp.connection

import isabelle.JSON
import isabelle.mcp.application.McpApplication
import isabelle.mcp.protocol.JsonRpc


trait RevisionRules {
  def revision: ProtocolRevision
  def classify(inbound: JsonRpc.Inbound): RevisionRules.Message
}


object RevisionRules {
  val ParseError = -32700
  val InvalidRequest = -32600
  val MethodNotFound = -32601
  val InvalidParams = -32602

  sealed trait Message
  sealed trait ReplyTarget
  case object NoReply extends ReplyTarget
  case object NullReply extends ReplyTarget
  final case class ReplyId(value: RequestId) extends ReplyTarget

  final case class Initialize(id: RequestId, requestedVersion: String) extends Message
  case object Initialized extends Message
  final case class Ping(id: RequestId) extends Message
  final case class Application(operation: McpApplication.Operation, id: RequestId) extends Message
  final case class Cancelled(id: RequestId, reason: Option[String]) extends Message
  final case class Invalid(reply: ReplyTarget, code: Int, message: String) extends Message
  case object Ignored extends Message
  final case class Batch(elements: List[JSON.T]) extends Message

  def error(id: JSON.T, code: Int, message: String): JSON.Object.T =
    JSON.Object("jsonrpc" -> "2.0", "id" -> id,
      "error" -> JSON.Object("code" -> code, "message" -> message))
}


final class Mcp2025RevisionRules extends RevisionRules {
  import RevisionRules._

  val revision: ProtocolRevision = ProtocolRevision.V2025_03_26

  def classify(inbound: JsonRpc.Inbound): Message =
    inbound match {
      case JsonRpc.Inbound.Malformed(_) => Invalid(NullReply, ParseError, "Parse error")
      case JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Batch(Nil)) =>
        Invalid(NullReply, InvalidRequest, "Empty batch is invalid")
      case JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Batch(values)) if values.exists(isInitialize) =>
        Invalid(NullReply, InvalidRequest, "initialize must be a standalone request")
      case JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Batch(values)) => Batch(values)
      case JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(json)) => classifyObject(json)
    }

  private def classifyObject(json: JSON.T): Message =
    json match {
      case objectValue: JSON.Object.T @unchecked =>
        requestId(objectValue) match {
          case Left(message) => Invalid(NullReply, InvalidRequest, message)
          case Right(requestId) =>
            if (JSON.string(objectValue, "jsonrpc") != Some("2.0"))
              invalid(requestId, InvalidRequest, "jsonrpc must be 2.0")
            else
              JSON.string(objectValue, "method") match {
                case None => invalid(requestId, InvalidRequest, "Missing method")
                case Some("initialize") => initialize(objectValue, requestId)
                case Some("notifications/initialized") => initialized(objectValue, requestId)
                case Some("notifications/cancelled") => cancelled(objectValue, requestId)
                case Some("ping") => request(requestId, id => Ping(id))
                case Some("tools/list") =>
                  request(requestId, id => Application(McpApplication.Operation.ToolsList, id))
                case Some("tools/call") => toolsCall(objectValue, requestId)
                case Some(method) =>
                  request(requestId, id => Invalid(ReplyId(id), MethodNotFound,
                    "Method not found: " + method))
              }
        }
      case _ => Invalid(NullReply, InvalidRequest, "Request must be an object")
    }

  private def initialize(json: JSON.Object.T, requestId: Option[RequestId]): Message =
    request(requestId, id =>
      JSON.value(json, "params").flatMap(JSON.string(_, "protocolVersion")) match {
        case Some(version) => Initialize(id, version)
        case None => Invalid(ReplyId(id), InvalidParams, "Missing protocolVersion")
      })

  private def initialized(json: JSON.Object.T, requestId: Option[RequestId]): Message =
    if (requestId.isEmpty) Initialized
    else Invalid(ReplyId(requestId.get), InvalidRequest,
      "notifications/initialized must be a notification")

  private def cancelled(json: JSON.Object.T, requestId: Option[RequestId]): Message =
    if (requestId.nonEmpty) Ignored
    else
      JSON.value(json, "params") match {
        case Some(params: JSON.Object.T @unchecked) =>
          JSON.value(params, "requestId").flatMap(RequestId.fromJson(_).toOption) match {
            case Some(id) =>
              JSON.value(params, "reason") match {
                case None => Cancelled(id, None)
                case Some(_: String) => Cancelled(id, JSON.string(params, "reason"))
                case _ => Ignored
              }
            case None => Ignored
          }
        case _ => Ignored
      }

  private def toolsCall(json: JSON.Object.T, requestId: Option[RequestId]): Message =
    request(requestId, id => {
      val params = JSON.value(json, "params").getOrElse(JSON.Object())
      JSON.string(params, "name") match {
        case None => Invalid(ReplyId(id), InvalidParams, "Missing tool name")
        case Some(name) =>
          val arguments =
            JSON.value(params, "arguments") match {
              case Some(value: JSON.Object.T @unchecked) => value
              case _ => JSON.Object()
            }
          Application(McpApplication.Operation.ToolsCall(name, arguments), id)
      }
    })

  private def request(requestId: Option[RequestId], message: RequestId => Message): Message =
    requestId match {
      case Some(id) => message(id)
      case None => Ignored
    }

  private def requestId(json: JSON.Object.T): Either[String, Option[RequestId]] =
    JSON.value(json, "id") match {
      case None => Right(None)
      case Some(value) => RequestId.fromJson(value).map(Some(_))
    }

  private def invalid(requestId: Option[RequestId], code: Int, message: String): Invalid =
    Invalid(requestId.map(ReplyId.apply).getOrElse(NoReply), code, message)

  private def isInitialize(json: JSON.T): Boolean =
    json match {
      case objectValue: JSON.Object.T @unchecked =>
        JSON.string(objectValue, "method").contains("initialize")
      case _ => false
    }
}
