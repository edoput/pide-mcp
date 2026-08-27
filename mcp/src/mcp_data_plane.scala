/*  Title:      mcp/src/mcp_data_plane.scala

Replaceable byte transport for one MCP connection. It frames UTF-8 stdio input
and serializes complete JSON-RPC envelopes, but intentionally knows nothing
about MCP lifecycle, request scheduling, tool dispatch, or Isabelle backends.
*/

package isabelle.mcp.transport

import isabelle.mcp.protocol.JsonRpc

import java.io.{BufferedReader, IOException, InputStream, InputStreamReader, OutputStream, PrintStream}
import java.nio.charset.{CodingErrorAction, StandardCharsets}


trait DataPlane {
  /* One connection has one receive consumer.  send may be called by several
     connection-owned completion paths, and each call writes one whole
     newline-delimited envelope before another call can begin. */
  def receive(): Option[JsonRpc.Inbound]
  def send(outbound: JsonRpc.Outbound): Unit
}


final class StdioDataPlane(input: InputStream, output: OutputStream) extends DataPlane {
  private val utf8 =
    StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
  private val reader = new BufferedReader(new InputStreamReader(input, utf8))
  private val writer = new PrintStream(output, true, StandardCharsets.UTF_8)
  private val output_lock = new AnyRef

  def receive(): Option[JsonRpc.Inbound] = {
    var line = reader.readLine()
    while (line != null && line.isBlank) line = reader.readLine()
    Option(line).map(JsonRpc.decode)
  }

  def send(outbound: JsonRpc.Outbound): Unit =
    output_lock.synchronized {
      writer.println(JsonRpc.render(outbound))
      writer.flush()
      if (writer.checkError()) throw new IOException("MCP stdio output failed")
    }
}


/* Keeps the injectable reader/stream test seam on the same DataPlane contract
   as production stdio.  Blank lines remain transport whitespace, matching the
   historic serve loop rather than turning into JSON parse-error replies. */
final class BufferedDataPlane(input: BufferedReader, output: PrintStream) extends DataPlane {
  private val output_lock = new AnyRef

  def receive(): Option[JsonRpc.Inbound] = {
    var line = input.readLine()
    while (line != null && line.isBlank) line = input.readLine()
    Option(line).map(JsonRpc.decode)
  }

  def send(outbound: JsonRpc.Outbound): Unit =
    output_lock.synchronized {
      output.println(JsonRpc.render(outbound))
      output.flush()
      if (output.checkError()) throw new IOException("MCP stream output failed")
    }
}


object StdioDataPlane {
  def standard(): StdioDataPlane = new StdioDataPlane(System.in, System.out)
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
