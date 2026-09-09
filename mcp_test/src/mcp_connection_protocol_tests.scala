/*  Title:      mcp_test/src/mcp_connection_protocol_tests.scala

Tests JSON-RPC framing and data-plane behavior: strict UTF-8 decoding,
input-byte limits, buffer release, complete concurrent writes, and output
failures.
*/

package isabelle.mcp

import isabelle._
import isabelle.mcp.protocol.JsonRpc
import isabelle.mcp.transport.{DataPlane, InputBufferLease, InputBufferProvider,
  InputMessageTooLargeException, McpInputPolicy, ScriptedDataPlane, StdioDataPlane}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, IOException, InputStream, OutputStream}
import java.nio.charset.MalformedInputException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}


class MCP_Connection_Protocol_Tests extends MCP_Suite {
  private val ping = JSON.Object("jsonrpc" -> "2.0", "id" -> 1, "method" -> "ping")
  private val notice = JSON.Object("jsonrpc" -> "2.0", "method" -> "notifications/initialized")

  private final class FailingOutputStream extends OutputStream {
    override def write(byte: Int): Unit = throw new IOException("deliberate output failure")
  }

  private final class CloseTrackingInput(bytes: Array[Byte]) extends ByteArrayInputStream(bytes) {
    val closes = new AtomicInteger(0)
    override def close(): Unit = { closes.incrementAndGet(); super.close() }
  }

  private final class CloseTrackingOutput extends ByteArrayOutputStream {
    val closes = new AtomicInteger(0)
    override def close(): Unit = { closes.incrementAndGet(); super.close() }
  }

  private final class RecordingBufferProvider(bufferBytes: Int) extends InputBufferProvider {
    val acquisitions = new AtomicInteger(0)
    val releases = new AtomicInteger(0)
    val requestedBytes = new AtomicInteger(0)

    def acquire(maximum: McpInputPolicy.MaxMessageBytes): InputBufferLease = {
      acquisitions.incrementAndGet()
      requestedBytes.set(McpInputPolicy.MaxMessageBytes.value(maximum))
      new InputBufferLease {
        val bytes = new Array[Byte](bufferBytes)
        def close(): Unit = releases.incrementAndGet()
      }
    }
  }

  private def inputPolicy(maxBytes: Int): McpInputPolicy =
    McpInputPolicy.checked(maxBytes).fold(message => fail(message), identity)

  private def stdio(input: InputStream, output: OutputStream,
      maxBytes: Int): StdioDataPlane =
    StdioDataPlane.open(input, output, inputPolicy(maxBytes))

  private def expect_decoded(
      plane: DataPlane, expected: JsonRpc.Envelope)(implicit loc: munit.Location): Unit =
    assertEquals(plane.receive(), Some(JsonRpc.Inbound.Decoded(expected)))

  private def receive_contract(plane: DataPlane)(implicit loc: munit.Location): Unit = {
    expect_decoded(plane, JsonRpc.Envelope.Single(ping))
    expect_decoded(plane, JsonRpc.Envelope.Batch(List(ping, notice)))
    expect_decoded(plane, JsonRpc.Envelope.Batch(Nil))
    assertEquals(plane.receive(), Some(JsonRpc.Inbound.Malformed("{not json")))
    assertEquals(plane.receive(), None)
  }

  private def concurrent_sends(
      plane: DataPlane, first: JsonRpc.Outbound, second: JsonRpc.Outbound): Unit = {
    val failure = new AtomicReference[Throwable](null)
    val threads = List(first, second).map(outbound =>
      new Thread(new Runnable {
        def run(): Unit =
          try plane.send(outbound)
          catch { case exn: Throwable => failure.compareAndSet(null, exn) }
      }))
    threads.foreach(_.start())
    threads.foreach(_.join())
    Option(failure.get()).foreach(throw _)
  }

  spec_test("typed JSON-RPC values preserve single, batch, empty-batch, and malformed framing",
      covers = List("connection_kernel#T1")) {
    val input = List("", " \t", JSON.Format(ping), JSON.Format(List(ping, notice)), "[]", "{not json")
    receive_contract(new ScriptedDataPlane(input))
  }

  spec_test("stdio and scripted data planes obey the same receive contract",
      covers = List("connection_kernel#T8")) {
    val lines = List("", " \t", JSON.Format(ping), JSON.Format(List(ping, notice)), "[]", "{not json")
    receive_contract(new ScriptedDataPlane(lines))
    val output = new ByteArrayOutputStream
    receive_contract(stdio(
      new ByteArrayInputStream(lines.mkString("\n").getBytes(StandardCharsets.UTF_8)), output, 1024))
  }

  test("stdio input is strict UTF-8") {
    val invalid_utf8 = Array[Byte]('{'.toByte, 0xC3.toByte, '}'.toByte, '\n'.toByte)
    val plane = stdio(
      new ByteArrayInputStream(invalid_utf8), new ByteArrayOutputStream, 1024)
    intercept[MalformedInputException](plane.receive())
  }

  spec_test("stdio bounds raw UTF-8 bytes and accepts exact LF CRLF EOF frames",
      covers = List("connection_kernel#T13")) {
    List("\n", "\r\n", "").foreach { terminator =>
      val plane = stdio(new ByteArrayInputStream(
        ("xxxx" + terminator).getBytes(StandardCharsets.UTF_8)), new ByteArrayOutputStream, 4)
      assertEquals(plane.receive(), Some(JsonRpc.Inbound.Malformed("xxxx")))
    }
    val unicode = stdio(new ByteArrayInputStream("λλ\n".getBytes(StandardCharsets.UTF_8)),
      new ByteArrayOutputStream, 3)
    intercept[InputMessageTooLargeException](unicode.receive())
    val blank = stdio(new ByteArrayInputStream("     \n{}\n".getBytes(StandardCharsets.UTF_8)),
      new ByteArrayOutputStream, 4)
    intercept[InputMessageTooLargeException](blank.receive())
  }

  test("oversized frame stops before parsing or later frames") {
    val plane = stdio(new ByteArrayInputStream("12345\n{}\n".getBytes(StandardCharsets.UTF_8)),
      new ByteArrayOutputStream, 4)
    intercept[InputMessageTooLargeException](plane.receive())
  }

  test("input-buffer resources preserve the policy bound and release exactly once") {
    val provider = new RecordingBufferProvider(bufferBytes = 8)
    val input = new CloseTrackingInput("12345\n".getBytes(StandardCharsets.UTF_8))
    val output = new CloseTrackingOutput
    val plane = StdioDataPlane.open(input, output, inputPolicy(4),
      StdioDataPlane.Resources(provider))

    assertEquals(provider.acquisitions.get(), 1)
    assertEquals(provider.requestedBytes.get(), 4)
    val failure = intercept[InputMessageTooLargeException](plane.receive())
    assertEquals(failure.limit, 4, "a larger supplied buffer weakened the policy limit")

    plane.close()
    plane.close()
    assertEquals(provider.releases.get(), 1)
    assertEquals(input.closes.get(), 0, "data-plane close closed its caller-owned input")
    assertEquals(output.closes.get(), 0, "data-plane close closed its caller-owned output")
  }

  test("an undersized input-buffer lease is rejected and released") {
    val provider = new RecordingBufferProvider(bufferBytes = 3)
    val failure = intercept[IllegalArgumentException] {
      StdioDataPlane.open(new ByteArrayInputStream(Array.emptyByteArray),
        new ByteArrayOutputStream, inputPolicy(4), StdioDataPlane.Resources(provider))
    }

    assert(failure.getMessage.contains("policy requires 4"), failure.getMessage)
    assertEquals(provider.acquisitions.get(), 1)
    assertEquals(provider.requestedBytes.get(), 4)
    assertEquals(provider.releases.get(), 1)
  }

  test("data planes serialize complete UTF-8 single and batch envelopes") {
    val single = JsonRpc.Outbound.Single(JSON.Object("jsonrpc" -> "2.0", "id" -> "λ",
      "result" -> JSON.Object("text" -> "λ")))
    val batch = JsonRpc.Outbound.Batch(List(
      JSON.Object("jsonrpc" -> "2.0", "id" -> 2, "result" -> JSON.Object())))

    val scripted = new ScriptedDataPlane(Nil)
    scripted.send(single)
    scripted.send(batch)
    assertEquals(scripted.written, List(JsonRpc.render(single), JsonRpc.render(batch)))

    val output = new ByteArrayOutputStream
    val stdioPlane = stdio(new ByteArrayInputStream(Array.emptyByteArray), output, 1024)
    stdioPlane.send(single)
    stdioPlane.send(batch)
    val lines = output.toString(StandardCharsets.UTF_8).linesIterator.toList
    assertEquals(lines, List(JsonRpc.render(single), JsonRpc.render(batch)))
    assert(lines.forall(JSON.Format.unapply(_).isDefined), "stdio emitted invalid JSON")
  }

  test("data-plane sends are complete and lossless under concurrent callers") {
    val first = JsonRpc.Outbound.Single(JSON.Object("jsonrpc" -> "2.0", "id" -> 1,
      "result" -> JSON.Object("text" -> "first")))
    val second = JsonRpc.Outbound.Single(JSON.Object("jsonrpc" -> "2.0", "id" -> 2,
      "result" -> JSON.Object("text" -> "second")))
    val plane = new ScriptedDataPlane(Nil)
    concurrent_sends(plane, first, second)

    assertEquals(plane.written.length, 2)
    assertEquals(plane.written.toSet, Set(JsonRpc.render(first), JsonRpc.render(second)))

    val output = new ByteArrayOutputStream
    val stdioPlane = stdio(new ByteArrayInputStream(Array.emptyByteArray), output, 1024)
    concurrent_sends(stdioPlane, first, second)
    val frames = output.toString(StandardCharsets.UTF_8).linesIterator.toList
    assertEquals(frames.length, 2)
    assertEquals(frames.toSet, Set(JsonRpc.render(first), JsonRpc.render(second)))
  }

  test("stdio send reports an output failure") {
    val plane = stdio(
      new ByteArrayInputStream(Array.emptyByteArray), new FailingOutputStream, 1024)
    val outbound = JsonRpc.Outbound.Single(JSON.Object("jsonrpc" -> "2.0", "id" -> 1,
      "result" -> JSON.Object()))
    val error = intercept[IOException](plane.send(outbound))
    assertEquals(error.getMessage, "MCP stdio output failed")
  }
}
