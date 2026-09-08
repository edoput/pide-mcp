/*  Title:      mcp/src/mcp_data_plane.scala

Replaceable byte transport for one MCP connection. It frames UTF-8 stdio input
and serializes complete JSON-RPC envelopes, but intentionally knows nothing
about MCP lifecycle, request scheduling, tool dispatch, or Isabelle backends.
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
}


final class InputMessageTooLargeException(val limit: Int)
  extends IOException("MCP input message exceeds " + limit + " bytes")


final class StdioDataPlane(input: InputStream, output: OutputStream, maxInputMessageBytes: Int)
  extends DataPlane {
  require(maxInputMessageBytes > 0, "maxInputMessageBytes must be positive")

  private val reader = new BufferedInputStream(input, 8192)
  /* A connection has exactly one receiver and retains precisely one fixed
     message buffer.  An oversized frame throws at its first excess byte;
     it is neither drained nor copied into an unbounded intermediate. */
  private val bytes = new Array[Byte](maxInputMessageBytes)
  private val writer = new PrintStream(output, true, StandardCharsets.UTF_8)
  private val output_lock = new AnyRef

  private def decode(length: Int): String = {
    val decoder = StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    try decoder.decode(ByteBuffer.wrap(bytes, 0, length)).toString
    catch { case exn: CharacterCodingException => throw exn }
  }

  private def readLine(): Option[String] = {
    var length = 0
    var complete = false
    var pendingCR = false
    while (!complete) {
      val next = reader.read()
      if (next < 0) {
        if (pendingCR) {
          if (length == maxInputMessageBytes)
            throw new InputMessageTooLargeException(maxInputMessageBytes)
          bytes(length) = '\r'
          length += 1
        }
        if (length == 0) return None
        complete = true
      }
      else if (pendingCR && next == '\n') complete = true
      else {
        if (pendingCR) {
          if (length == maxInputMessageBytes)
            throw new InputMessageTooLargeException(maxInputMessageBytes)
          bytes(length) = '\r'
          length += 1
          pendingCR = false
        }
        if (next == '\n') complete = true
        else if (next == '\r') pendingCR = true
        else {
          if (length == maxInputMessageBytes)
            throw new InputMessageTooLargeException(maxInputMessageBytes)
          bytes(length) = next.toByte
          length += 1
        }
      }
    }
    Some(decode(length))
  }

  def receive(): Option[JsonRpc.Inbound] = {
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
}


object StdioDataPlane {
  def standard(maxInputMessageBytes: Int): StdioDataPlane =
    new StdioDataPlane(System.in, System.out, maxInputMessageBytes)
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
