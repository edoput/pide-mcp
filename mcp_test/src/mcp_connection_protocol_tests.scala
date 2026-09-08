/*  Title:      mcp_test/src/mcp_connection_protocol_tests.scala

Focused contracts for the JSON-RPC/data-plane seam.
*/

package isabelle.mcp

import isabelle._
import isabelle.mcp.protocol.JsonRpc
import isabelle.mcp.transport.{DataPlane, InputMessageTooLargeException, ScriptedDataPlane, StdioDataPlane}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, IOException, OutputStream}
import java.nio.charset.MalformedInputException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference


class MCP_Connection_Protocol_Tests extends MCP_Suite {
  private val ping = JSON.Object("jsonrpc" -> "2.0", "id" -> 1, "method" -> "ping")
  private val notice = JSON.Object("jsonrpc" -> "2.0", "method" -> "notifications/initialized")

  private final class FailingOutputStream extends OutputStream {
    override def write(byte: Int): Unit = throw new IOException("deliberate output failure")
  }

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
    receive_contract(new StdioDataPlane(
      new ByteArrayInputStream(lines.mkString("\n").getBytes(StandardCharsets.UTF_8)), output, 1024))
  }

  test("stdio input is strict UTF-8") {
    val invalid_utf8 = Array[Byte]('{'.toByte, 0xC3.toByte, '}'.toByte, '\n'.toByte)
    val plane = new StdioDataPlane(new ByteArrayInputStream(invalid_utf8), new ByteArrayOutputStream, 1024)
    intercept[MalformedInputException](plane.receive())
  }

  spec_test("stdio bounds raw UTF-8 bytes and accepts exact LF CRLF EOF frames",
      covers = List("connection_kernel#T13")) {
    List("\n", "\r\n", "").foreach { terminator =>
      val plane = new StdioDataPlane(new ByteArrayInputStream(
        ("xxxx" + terminator).getBytes(StandardCharsets.UTF_8)), new ByteArrayOutputStream, 4)
      assertEquals(plane.receive(), Some(JsonRpc.Inbound.Malformed("xxxx")))
    }
    val unicode = new StdioDataPlane(new ByteArrayInputStream("λλ\n".getBytes(StandardCharsets.UTF_8)),
      new ByteArrayOutputStream, 3)
    intercept[InputMessageTooLargeException](unicode.receive())
    val blank = new StdioDataPlane(new ByteArrayInputStream("     \n{}\n".getBytes(StandardCharsets.UTF_8)),
      new ByteArrayOutputStream, 4)
    intercept[InputMessageTooLargeException](blank.receive())
  }

  test("oversized frame stops before parsing or later frames") {
    val plane = new StdioDataPlane(new ByteArrayInputStream("12345\n{}\n".getBytes(StandardCharsets.UTF_8)),
      new ByteArrayOutputStream, 4)
    intercept[InputMessageTooLargeException](plane.receive())
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
    val stdio = new StdioDataPlane(new ByteArrayInputStream(Array.emptyByteArray), output, 1024)
    stdio.send(single)
    stdio.send(batch)
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
    val stdio = new StdioDataPlane(new ByteArrayInputStream(Array.emptyByteArray), output, 1024)
    concurrent_sends(stdio, first, second)
    val frames = output.toString(StandardCharsets.UTF_8).linesIterator.toList
    assertEquals(frames.length, 2)
    assertEquals(frames.toSet, Set(JsonRpc.render(first), JsonRpc.render(second)))
  }

  test("stdio send reports an output failure") {
    val plane = new StdioDataPlane(
      new ByteArrayInputStream(Array.emptyByteArray), new FailingOutputStream, 1024)
    val outbound = JsonRpc.Outbound.Single(JSON.Object("jsonrpc" -> "2.0", "id" -> 1,
      "result" -> JSON.Object()))
    val error = intercept[IOException](plane.send(outbound))
    assertEquals(error.getMessage, "MCP stdio output failed")
  }
}
