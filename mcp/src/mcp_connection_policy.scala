/*  Title:      mcp/src/mcp_connection_policy.scala

Immutable, validated configuration selected once for one connection.
*/

package isabelle.mcp.connection

import isabelle.Options


sealed abstract class ProtocolRevision private (val value: String)

object ProtocolRevision {
  case object V2025_03_26 extends ProtocolRevision("2025-03-26")
}


final case class ConnectionPolicy(
  revision: ProtocolRevision,
  admission: ConnectionPolicy.AdmissionPolicy,
  timing: ConnectionPolicy.TimingPolicy
)


object ConnectionPolicy {
  opaque type MaxInFlight = Int
  opaque type RequestTimeout = Double
  opaque type ShutdownDrain = Double

  object MaxInFlight {
    def checked(value: Int): Either[String, MaxInFlight] =
      if (value > 0) Right(value) else Left("maxInFlight must be positive")

    def value(value: MaxInFlight): Int = value
  }

  object RequestTimeout {
    def checked(seconds: Double): Either[String, RequestTimeout] =
      if (finite(seconds) && seconds > 0.0) Right(seconds)
      else Left("requestTimeout must be finite and positive")

    def seconds(value: RequestTimeout): Double = value
  }

  object ShutdownDrain {
    def checked(seconds: Double): Either[String, ShutdownDrain] =
      if (finite(seconds) && seconds >= 0.0) Right(seconds)
      else Left("shutdownDrain must be finite and non-negative")

    def seconds(value: ShutdownDrain): Double = value
  }

  final case class AdmissionPolicy(maxInFlight: MaxInFlight)
  final case class TimingPolicy(requestTimeout: RequestTimeout, shutdownDrain: ShutdownDrain)

  def fromOptions(options: Options): Either[String, ConnectionPolicy] =
    for {
      maxInFlight <- MaxInFlight.checked(options.int("mcp_max_in_flight"))
      requestTimeout <- RequestTimeout.checked(options.real("mcp_request_timeout"))
      shutdownDrain <- ShutdownDrain.checked(options.real("mcp_shutdown_drain"))
    } yield ConnectionPolicy(
      revision = ProtocolRevision.V2025_03_26,
      admission = AdmissionPolicy(maxInFlight = maxInFlight),
      timing = TimingPolicy(requestTimeout = requestTimeout, shutdownDrain = shutdownDrain))

  private def finite(value: Double): Boolean = !value.isNaN && !value.isInfinity
}
