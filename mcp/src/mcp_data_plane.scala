/*  Title:      mcp/src/mcp_data_plane.scala

Replaceable byte transport for one MCP connection. It frames UTF-8 stdio input
and serializes complete JSON-RPC envelopes, but intentionally knows nothing
about MCP lifecycle, request scheduling, tool dispatch, or Isabelle backends.

Trust assumption: stdio input comes from a trusted client. McpInputPolicy
currently bounds raw input bytes and the message buffer only; it does not
make this transport suitable for untrusted input.

JsonRpc.decode still delegates to Isabelle's recursive
JSON parser without a nesting-depth limit, so deeply nested JSON within the
byte limit can exhaust the parser stack. That separate parser problem is known
and deliberately deferred; future structural parsing limits belong in
McpInputPolicy.
*/

package isabelle.mcp.transport

import isabelle.mcp.protocol.JsonRpc

import java.io.{BufferedInputStream, IOException, InputStream, OutputStream, PrintStream}
import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}


trait DataPlane {
  /* One connection has one receive consumer.  send may be called by several
     connection-owned completion paths, and each call writes one whole
     newline-delimited envelope before another call can begin. */
  def receive(): Option[JsonRpc.Inbound]
  def send(outbound: JsonRpc.Outbound): Unit
  /* Releases data-plane-owned resources only.  Implementations must not close
     caller-owned input or output streams. */
  def close(): Unit = ()
}


/** Immutable limits for one MCP input data plane.  New parsing limits belong
  * here instead of becoming more constructor arguments on each adapter.
  */
final case class McpInputPolicy private (maxMessageBytes: McpInputPolicy.MaxMessageBytes)

object McpInputPolicy {
  opaque type MaxMessageBytes = Int

  object MaxMessageBytes {
    def checked(value: Int): Either[String, MaxMessageBytes] =
      if (value > 0) Right(value) else Left("maxInputMessageBytes must be positive")

    def value(value: MaxMessageBytes): Int = value
  }

  def checked(maxInputMessageBytes: Int): Either[String, McpInputPolicy] =
    MaxMessageBytes.checked(maxInputMessageBytes).map(McpInputPolicy(_))
}


/** Exclusive ownership of one reusable MCP input-message buffer. */
trait InputBufferLease extends AutoCloseable {
  def bytes: Array[Byte]
  def close(): Unit
}


/** Allocation boundary for a future bounded pool.  A provider may return a
  * larger buffer, but never a smaller one; framing still uses the policy size.
  */
trait InputBufferProvider {
  def acquire(maximum: McpInputPolicy.MaxMessageBytes): InputBufferLease
}


object InputBufferProvider {
  /* The default provider allocates one exact-sized Array[Byte] when a data
     plane opens, retains it for that connection, and cannot reuse it after
     close. A future bounded pool can replace only this provider, recycle
     released leases, and bound aggregate leased storage; McpInputPolicy and
     StdioDataPlane signatures need not change. */
  val unpooled: InputBufferProvider = new InputBufferProvider {
    def acquire(maximum: McpInputPolicy.MaxMessageBytes): InputBufferLease =
      new InputBufferLease {
        val bytes = new Array[Byte](McpInputPolicy.MaxMessageBytes.value(maximum))
        def close(): Unit = ()
      }
  }
}


final class InputMessageTooLargeException(val limit: Int)
  extends IOException("MCP input message exceeds " + limit + " bytes")


final class StdioDataPlane private (
  input: InputStream,
  output: OutputStream,
  policy: McpInputPolicy,
  bytes: Array[Byte],
  lease: InputBufferLease
)
  extends DataPlane {
  private val maxInputMessageBytes = McpInputPolicy.MaxMessageBytes.value(policy.maxMessageBytes)

  private val reader = new BufferedInputStream(input, 8192)
  /* A connection has exactly one receiver and retains precisely one fixed
     message buffer.  An oversized frame throws at its first excess byte;
     it is neither drained nor copied into an unbounded intermediate. */
  private val writer = new PrintStream(output, true, StandardCharsets.UTF_8)
  private val input_lock = new AnyRef
  private val output_lock = new AnyRef
  private var lease_open = true

  private def decode(length: Int): String = {
    val decoder = StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    try decoder.decode(ByteBuffer.wrap(bytes, 0, length)).toString
    catch { case exn: CharacterCodingException => throw exn }
  }

  private def readLine(): Option[String] = {
    var length = 0
    var pendingCR = false

    /* If the JSON parser cannot enforce structural limits, check structure
       here before parsing (e.g. bounded nesting and matching delimiters,
       respecting JSON strings and escapes), with limits from McpInputPolicy. */
    def append(value: Int): Unit = {
      if (length == maxInputMessageBytes)
        throw new InputMessageTooLargeException(maxInputMessageBytes)
      bytes(length) = value.toByte
      length += 1
    }

    var next = reader.read()
    while (next != '\n') {
      if (pendingCR) append('\r')

      if (next < 0)
        return if (length == 0) None else Some(decode(length))

      pendingCR = next == '\r'
      if (!pendingCR) append(next)
      next = reader.read()
    }
    Some(decode(length))
  }

  def receive(): Option[JsonRpc.Inbound] =
    input_lock.synchronized {
      if (!lease_open) throw new IOException("MCP stdio data plane is closed")
      var line = readLine()
      while (line.exists(_.isBlank)) line = readLine()
      line.map(JsonRpc.decode)
    }

  def send(outbound: JsonRpc.Outbound): Unit =
    output_lock.synchronized {
      writer.println(JsonRpc.render(outbound))
      writer.flush()
      if (writer.checkError()) throw new IOException("MCP stdio output failed")
    }

  override def close(): Unit =
    input_lock.synchronized {
      if (lease_open) {
        lease_open = false
        lease.close()
      }
    }
}


object StdioDataPlane {
  final case class Resources(inputBuffers: InputBufferProvider = InputBufferProvider.unpooled)

  object Resources {
    val default: Resources = Resources()
  }

  def open(input: InputStream, output: OutputStream, policy: McpInputPolicy,
      resources: Resources = Resources.default): StdioDataPlane = {
    val lease = resources.inputBuffers.acquire(policy.maxMessageBytes)
    try {
      val bytes = lease.bytes
      val required = McpInputPolicy.MaxMessageBytes.value(policy.maxMessageBytes)
      require(bytes.length >= required,
        "input buffer has " + bytes.length + " bytes; policy requires " + required)
      new StdioDataPlane(input, output, policy, bytes, lease)
    }
    catch {
      case exn: Throwable =>
        try lease.close()
        catch { case closeExn: Throwable => exn.addSuppressed(closeExn) }
        throw exn
    }
  }

  def standard(policy: McpInputPolicy,
      resources: Resources = Resources.default): StdioDataPlane =
    open(System.in, System.out, policy, resources)
}


final class ScriptedDataPlane(lines: List[String]) extends DataPlane {
  private var remaining = lines
  private var emitted = Vector.empty[String]
  private val output_lock = new AnyRef

  def receive(): Option[JsonRpc.Inbound] = {
    while (remaining.headOption.exists(_.isBlank)) remaining = remaining.tail
    remaining match {
      case Nil => None
      case line :: more =>
        remaining = more
        Some(JsonRpc.decode(line))
    }
  }

  def send(outbound: JsonRpc.Outbound): Unit =
    output_lock.synchronized {
      emitted = emitted :+ JsonRpc.render(outbound)
    }

  def written: List[String] = output_lock.synchronized(emitted.toList)
}
