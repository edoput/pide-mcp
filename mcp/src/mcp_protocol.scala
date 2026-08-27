/*  Title:      mcp/src/mcp_protocol.scala

Typed JSON-RPC transport values. This package owns JSON syntax and the
single-or-batch envelope shape only; MCP lifecycle and method validation belong
to the connection kernel introduced in later checkpoints.
*/

package isabelle.mcp.protocol

import isabelle.JSON


object JsonRpc {
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
