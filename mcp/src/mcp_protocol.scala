/*  Title:      mcp/src/mcp_protocol.scala

Typed JSON-RPC transport values. This package owns JSON syntax and the
single-or-batch envelope shape only; MCP lifecycle and method validation belong
to the connection kernel introduced in later checkpoints.
*/

package isabelle.mcp.protocol

import isabelle.JSON


object JsonRpc {
  object ErrorCode {
    val ParseError = -32700
    val InvalidRequest = -32600
    val MethodNotFound = -32601
    val InvalidParams = -32602
    val InternalError = -32603
    /* JSON-RPC reserves -32000 through -32099 for implementation-specific
       server errors; these MCP server values are defined by this application. */
    val Rejected = -32000
    val Overloaded = -32001
    val RequestTimedOut = -32002
  }

  sealed trait Envelope

  object Envelope {
    final case class Single(value: JSON.T) extends Envelope
    final case class Batch(values: List[JSON.T]) extends Envelope
  }

  sealed trait Inbound

  object Inbound {
    final case class Decoded(envelope: Envelope) extends Inbound
    final case class Malformed(raw: String) extends Inbound
  }

  sealed trait Outbound

  object Outbound {
    final case class Single(value: JSON.T) extends Outbound
    final case class Batch(values: List[JSON.T]) extends Outbound
  }

  def decode(line: String): Inbound =
    JSON.Format.unapply(line) match {
      case Some(values: List[_]) =>
        Inbound.Decoded(Envelope.Batch(values.asInstanceOf[List[JSON.T]]))
      case Some(value) => Inbound.Decoded(Envelope.Single(value))
      case None => Inbound.Malformed(line)
    }

  def value(outbound: Outbound): JSON.T =
    outbound match {
      case Outbound.Single(value) => value
      case Outbound.Batch(values) => values
    }

  def render(outbound: Outbound): String = JSON.Format(value(outbound))
}
