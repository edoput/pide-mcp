/*  Title:      mcp/src/mcp_connection_policy.scala

Immutable, validated configuration selected once for one connection.
*/

package isabelle.mcp.connection

import isabelle.Options
import isabelle.mcp.control.{NonNegativeDuration, PositiveDuration}


sealed abstract class ProtocolRevision private (val value: String)

object ProtocolRevision {
  case object V2025_03_26 extends ProtocolRevision("2025-03-26")
}


final case class ConnectionPolicy(
  revision: ProtocolRevision,
  framing: ConnectionPolicy.FramingPolicy,
  admission: ConnectionPolicy.AdmissionPolicy,
  timing: ConnectionPolicy.TimingPolicy
)


object ConnectionPolicy {
  opaque type MaxInFlight = Int
  opaque type MaxInputMessageBytes = Int
  type RequestTimeout = PositiveDuration
  type ShutdownDrain = NonNegativeDuration

  object MaxInFlight {
    def checked(value: Int): Either[String, MaxInFlight] =
      if (value > 0) Right(value) else Left("maxInFlight must be positive")

    def value(value: MaxInFlight): Int = value
  }

  object MaxInputMessageBytes {
    def checked(value: Int): Either[String, MaxInputMessageBytes] =
      if (value > 0) Right(value) else Left("maxInputMessageBytes must be positive")

    def value(value: MaxInputMessageBytes): Int = value
  }

  object RequestTimeout {
    def checked(seconds: Double): Either[String, RequestTimeout] =
      PositiveDuration.checked("requestTimeout", seconds)

    def duration(value: RequestTimeout) = PositiveDuration.duration(value)
    def seconds(value: RequestTimeout): Double = PositiveDuration.seconds(value)
  }

  object ShutdownDrain {
    def checked(seconds: Double): Either[String, ShutdownDrain] =
      NonNegativeDuration.checked("shutdownDrain", seconds)

    def duration(value: ShutdownDrain) = NonNegativeDuration.duration(value)
    def seconds(value: ShutdownDrain): Double = NonNegativeDuration.seconds(value)
  }

  final case class FramingPolicy(maxInputMessageBytes: MaxInputMessageBytes)
  final case class AdmissionPolicy(maxInFlight: MaxInFlight)
  final case class TimingPolicy(requestTimeout: RequestTimeout, shutdownDrain: ShutdownDrain)

  def fromOptions(options: Options): Either[String, ConnectionPolicy] =
    for {
      maxInputMessageBytes <- MaxInputMessageBytes.checked(options.int("mcp_max_input_message_bytes"))
      maxInFlight <- MaxInFlight.checked(options.int("mcp_max_in_flight"))
      requestTimeout <- RequestTimeout.checked(options.real("mcp_request_timeout"))
      shutdownDrain <- ShutdownDrain.checked(options.real("mcp_shutdown_drain"))
    } yield ConnectionPolicy(
      revision = ProtocolRevision.V2025_03_26,
      framing = FramingPolicy(maxInputMessageBytes = maxInputMessageBytes),
      admission = AdmissionPolicy(maxInFlight = maxInFlight),
      timing = TimingPolicy(requestTimeout = requestTimeout, shutdownDrain = shutdownDrain))

}
