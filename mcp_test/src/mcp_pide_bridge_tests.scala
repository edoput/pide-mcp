/*  Title:      mcp_test/src/mcp_pide_bridge_tests.scala

Deterministic contracts for the extracted PIDE bridge boundary.
*/

package isabelle.mcp.pide

import isabelle.{Bytes, Future}
import isabelle.mcp.MCP_Suite


class MCP_Pide_Bridge_Tests extends MCP_Suite {
  private object NeverCancelled extends BridgeCancellation {
    def isCancelled: Boolean = false
    def onCancel(callback: () => Unit): Unit = ()
  }

  private final class TestCancellation extends BridgeCancellation {
    private var cancelled = false
    private var callbacks = Vector.empty[() => Unit]

    def isCancelled: Boolean = synchronized { cancelled }
    def onCancel(callback: () => Unit): Unit = {
      val run = synchronized {
        if (cancelled) true else { callbacks :+= callback; false }
      }
      if (run) callback()
    }
    def cancel(): Unit = {
      val run = synchronized {
        if (cancelled) Vector.empty
        else { cancelled = true; val result = callbacks; callbacks = Vector.empty; result }
      }
      run.foreach(_())
    }
  }

  private final class ScriptedTransport extends PideTransport {
    private var receiver: Option[PideTransport.Inbound => Unit] = None
    private var terminated: Option[PideTransport.Termination => Unit] = None
    private var outbound = Vector.empty[PideTransport.Outbound]
    private var sendFailure: Option[RuntimeException] = None
    private var closed = false

    def start(receive: PideTransport.Inbound => Unit,
      onTerminated: PideTransport.Termination => Unit): Unit = synchronized {
      receiver = Some(receive)
      terminated = Some(onTerminated)
    }

    def send(message: PideTransport.Outbound): Unit = synchronized {
      sendFailure match {
        case Some(exn) => sendFailure = None; throw exn
        case None => outbound :+= message; notifyAll()
      }
    }

    def close(): Unit = {
      val notify = synchronized {
        if (closed) None else { closed = true; terminated }
      }
      notify.foreach(_(PideTransport.Closed))
    }

    def failNextSend(message: String): Unit = synchronized {
      sendFailure = Some(new RuntimeException(message))
    }

    def failTransport(message: String): Unit =
      terminated.foreach(_(PideTransport.Failed(message)))

    def sent: Vector[PideTransport.Outbound] = synchronized { outbound }

    def awaitSent(count: Int): Unit = synchronized {
      val deadline = System.nanoTime() + 5000000000L
      while (outbound.length < count && System.nanoTime() < deadline) wait(10L)
      if (outbound.length < count) fail("timed out waiting for " + count + " bridge sends")
    }

    def deliver(function: String, id: String, text: String,
      properties: List[(String, String)] = Nil): Unit = {
      val target = synchronized { receiver.getOrElse(fail("transport not started")) }
      target(PideTransport.Inbound(
        function, ("id" -> id) :: properties, Bytes(text), text))
    }
  }

  private def bridge(transport: ScriptedTransport, maxPending: Int = 4,
    diagnostics: String => Unit = _ => ()): PideBridge =
    new PideBridge(
      transport,
      policy(maxPending).maxPending,
      Map("first_result" -> "first", "second_result" -> "second", "op_result" -> "op"),
      id => PideTransport.Outbound("cancel", List(Bytes(id))),
      diagnostics)

  private def policy(maxPending: Int = 2): PideBridgePolicy =
    PideBridgePolicy.checked(
      maxPending = maxPending,
      callTimeoutSeconds = 5.0,
      drainTimeoutSeconds = 2.0,
      maxRequestBytes = 1024,
      maxReplyBytes = 4096).fold(errors => fail(errors.mkString(", ")), identity)

  private final case class TextOperation(name: String, request: String)
      extends BridgeOperation[String] {
    val resultFunction = name + "_result"
    def outbound(id: String): PideTransport.Outbound =
      PideTransport.Outbound(name, List(Bytes(id), Bytes(request)))
    def decodeReply(reply: PideTransport.Inbound): Either[String, String] =
      if (reply.text.startsWith("bad:")) Left(reply.text)
      else Right(reply.text)
  }

  private def reply(operation: String, text: String): PideTransport.Inbound =
    PideTransport.Inbound(
      operation + "_result", List("id" -> "test"), Bytes(text), text)

  test("bridge policy validates all named resource bounds together") {
    val errors =
      PideBridgePolicy.checked(
        maxPending = 0,
        callTimeoutSeconds = Double.NaN,
        drainTimeoutSeconds = Double.PositiveInfinity,
        maxRequestBytes = 0,
        maxReplyBytes = -1).swap.getOrElse(fail("invalid policy was accepted"))

    assertEquals(errors,
      List(
        "maxPending must be positive",
        "callTimeout must be finite and positive",
        "drainTimeout must be finite and positive",
        "maxRequestBytes must be positive",
        "maxReplyBytes must be positive"))
  }

  test("bridge call ids come from an independent per-bridge string generator") {
    val clientId = "client-request-0"
    val generator = BridgeCallIdGenerator.test("bridge-instance")
    val first = BridgeCallId.value(generator.next())
    val second = BridgeCallId.value(generator.next())

    assertEquals(first, "bridge-instance:0")
    assertEquals(second, "bridge-instance:1")
    assertNotEquals(first, clientId)
    assertNotEquals(second, clientId)
  }

  spec_test("heterogeneous pending calls resolve reverse-order replies by internal id",
      covers = List("pide_bridge#T1", "connection_kernel#T11")) {
    val registry = new PendingRegistry(policy().maxPending)
    val firstId = BridgeCallId.test("internal-first")
    val secondId = BridgeCallId.test("internal-second")
    val first = registry.register(firstId, TextOperation("first", "request-1"))
      .fold(failure => fail(failure.message), identity)
    val second = registry.register(secondId, TextOperation("second", "request-2"))
      .fold(failure => fail(failure.message), identity)

    assertEquals(registry.complete(secondId, "second", reply("second", "reply-2")),
      PendingRegistry.Completed)
    assertEquals(registry.complete(firstId, "first", reply("first", "reply-1")),
      PendingRegistry.Completed)
    assertEquals(second.result, Right("reply-2"))
    assertEquals(first.result, Right("reply-1"))
    assertEquals(registry.size, 0)
  }

  test("known invalid replies fail only their owner and unowned replies diagnose") {
    val recorded = scala.collection.mutable.ListBuffer.empty[PendingRegistry.Diagnostic]
    val diagnostics = new PendingRegistry.Diagnostics {
      def report(diagnostic: PendingRegistry.Diagnostic): Unit = recorded += diagnostic
    }
    val registry = new PendingRegistry(policy().maxPending, diagnostics)
    val ownedId = BridgeCallId.test("owned")
    val otherId = BridgeCallId.test("other")
    val owned = registry.register(ownedId, TextOperation("expected", "request"))
      .fold(failure => fail(failure.message), identity)

    assertEquals(registry.complete(ownedId, "wrong", reply("wrong", "reply")),
      PendingRegistry.Rejected)
    owned.result match {
      case Left(BridgeFailure.ProtocolError(message)) =>
        assert(message.contains("does not match"))
      case result => fail("expected ProtocolError, got " + result)
    }
    assertEquals(registry.complete(ownedId, "expected", reply("expected", "late")),
      PendingRegistry.Unowned)
    assertEquals(registry.complete(otherId, "expected", reply("expected", "unknown")),
      PendingRegistry.Unowned)
    assertEquals(recorded.toList,
      List(
        PendingRegistry.UnownedReply(ownedId, "expected"),
        PendingRegistry.UnownedReply(otherId, "expected")))

    val rejectedId = BridgeCallId.test("rejected")
    val rejected = registry.register(rejectedId, TextOperation("decoder", "request"))
      .fold(failure => fail(failure.message), identity)
    assertEquals(registry.complete(rejectedId, "decoder", reply("decoder", "bad: malformed")),
      PendingRegistry.Rejected)
    rejected.result match {
      case Left(BridgeFailure.ProtocolError(message)) =>
        assert(message.contains("malformed"))
      case result => fail("expected returned ProtocolError, got " + result)
    }

    val throwingId = BridgeCallId.test("throwing")
    val throwing = registry.register(throwingId, new BridgeOperation[String] {
      val name = "throwing"
      val resultFunction = "throwing_result"
      def outbound(id: String): PideTransport.Outbound =
        PideTransport.Outbound("throwing", List(Bytes(id)))
      def decodeReply(reply: PideTransport.Inbound): Either[String, String] =
        throw new IllegalArgumentException("invalid body")
    }).fold(failure => fail(failure.message), identity)
    assertEquals(registry.complete(throwingId, "throwing", reply("throwing", "body")),
      PendingRegistry.Rejected)
    throwing.result match {
      case Left(BridgeFailure.ProtocolError(message)) =>
        assert(message.contains("invalid body"))
      case result => fail("expected decoder ProtocolError, got " + result)
    }
    assertEquals(registry.size, 0)
  }

  spec_test("pending admission is bounded without a queue and recovers capacity",
      covers = List("pide_bridge#T7")) {
    val registry = new PendingRegistry(policy(maxPending = 1).maxPending)
    val firstId = BridgeCallId.test("first")
    val secondId = BridgeCallId.test("second")
    val first = registry.register(firstId, TextOperation("op", "request"))
      .fold(failure => fail(failure.message), identity)

    assertEquals(registry.register(secondId, TextOperation("op", "request")),
      Left(BridgeFailure.Overloaded(1)))
    assertEquals(registry.size, 1)
    assert(registry.fail(firstId, BridgeFailure.Cancelled))
    assertEquals(first.result, Left(BridgeFailure.Cancelled))
    assert(registry.register(secondId, TextOperation("op", "request")).isRight)
  }

  test("checkpoint-1 characterization names all legacy operations and change events") {
    assertEquals(
      LegacyWire.operations.map(operation =>
        (operation.name, operation.command, operation.resultFunction,
          operation.argumentNames, operation.replyShape)),
      List(
        ("tools", "MCP.tools", "MCP.tools_result",
          List("id", "designation", "bundles_yxml"), LegacyWire.ReplyShape.ToolsYxml),
        ("theories", "MCP.theories", "MCP.theories_result",
          List("id"), LegacyWire.ReplyShape.TheoriesYxml),
        ("run_tool", "MCP.run_tool", "MCP.run_tool_result",
          List("id", "designation", "bundles_yxml", "name", "args_yxml"),
          LegacyWire.ReplyShape.StatusText),
        ("check_designation", "MCP.check_designation", "MCP.check_designation_result",
          List("id", "designation", "bundles_yxml"), LegacyWire.ReplyShape.StatusText),
        ("ir", "MCP.ir", "MCP.ir_result",
          List("id", "fname", "args_yxml"), LegacyWire.ReplyShape.StatusYxml),
        ("resources", "MCP.resources", "MCP.resources_result",
          List("id", "designation"), LegacyWire.ReplyShape.ResourcesYxml),
        ("read_resource", "MCP.read_resource", "MCP.read_resource_result",
          List("id", "designation", "name"), LegacyWire.ReplyShape.StatusText)))
    assertEquals(LegacyWire.changes.map(change => (change.function, change.event)),
      List(("MCP.tools_changed", "tools"), ("MCP.resources_changed", "resources")))
    assertEquals(LegacyWire.operations.map(_.name).distinct.size, 7)

    assertEquals(
      LegacyWire.arguments(LegacyWire.Ir,
        "args_yxml" -> Bytes("args"), "id" -> Bytes("id"), "fname" -> Bytes("fn"))
        .map(_.text),
      List("id", "fn", "args"))
    intercept[IllegalArgumentException] {
      LegacyWire.arguments(LegacyWire.Ir, "id" -> Bytes("id"), "fname" -> Bytes("fn"))
    }
  }

  spec_test("one PideBridge over a scripted transport correlates reverse replies",
      verifies = List("pide_bridge#A1", "pide_bridge#I1"),
      covers = List("pide_bridge#T1")) {
    val transport = new ScriptedTransport
    val control = bridge(transport)
    val first = Future.fork(control.call(TextOperation("first", "request-1"), NeverCancelled))
    val second = Future.fork(control.call(TextOperation("second", "request-2"), NeverCancelled))
    transport.awaitSent(2)

    val sends = transport.sent
    val firstId = sends.find(_.command == "first").get.arguments.head.text
    val secondId = sends.find(_.command == "second").get.arguments.head.text
    assertNotEquals(firstId, secondId)
    transport.deliver("second_result", secondId, "reply-2")
    transport.deliver("first_result", firstId, "reply-1")

    assertEquals(second.join, Right("reply-2"))
    assertEquals(first.join, Right("reply-1"))
    assertEquals(control.pendingCount, 0)
    control.beginStop()
    control.sessionStopped()
  }

  spec_test("cancellation is ordered around dispatch and loses cleanly to a reply",
      covers = List("pide_bridge#T3")) {
    val preTransport = new ScriptedTransport
    val pre = bridge(preTransport)
    val already = new TestCancellation
    already.cancel()
    assertEquals(pre.call(TextOperation("op", "request"), already),
      Left(BridgeFailure.Cancelled))
    assertEquals(preTransport.sent, Vector.empty)
    pre.beginStop()
    pre.sessionStopped()

    val cancelTransport = new ScriptedTransport
    val cancelBridge = bridge(cancelTransport)
    val cancellation = new TestCancellation
    val cancelled = Future.fork(
      cancelBridge.call(TextOperation("op", "request"), cancellation))
    cancelTransport.awaitSent(1)
    val cancelId = cancelTransport.sent.head.arguments.head.text
    cancellation.cancel()
    cancelTransport.awaitSent(2)
    assertEquals(cancelTransport.sent(1),
      PideTransport.Outbound("cancel", List(Bytes(cancelId))))
    assertEquals(cancelled.join, Left(BridgeFailure.Cancelled))
    assertEquals(cancelBridge.pendingCount, 0)

    val replyTransport = new ScriptedTransport
    val replyBridge = bridge(replyTransport)
    val replyCancellation = new TestCancellation
    val completed = Future.fork(
      replyBridge.call(TextOperation("op", "request"), replyCancellation))
    replyTransport.awaitSent(1)
    val replyId = replyTransport.sent.head.arguments.head.text
    replyTransport.deliver("op_result", replyId, "reply")
    replyCancellation.cancel()
    assertEquals(completed.join, Right("reply"))
    assertEquals(replyTransport.sent.length, 1)
    assertEquals(replyBridge.pendingCount, 0)
    cancelBridge.beginStop()
    cancelBridge.sessionStopped()
    replyBridge.beginStop()
    replyBridge.sessionStopped()
  }

  spec_test("send failure and transport termination fail owned calls without leaks",
      covers = List("pide_bridge#T5")) {
    val sendTransport = new ScriptedTransport
    sendTransport.failNextSend("send boom")
    val sendBridge = bridge(sendTransport)
    sendBridge.call(TextOperation("op", "request"), NeverCancelled) match {
      case Left(BridgeFailure.TransportFailed(message)) => assert(message.contains("send boom"))
      case result => fail("expected send TransportFailed, got " + result)
    }
    assertEquals(sendBridge.pendingCount, 0)

    val deadTransport = new ScriptedTransport
    val deadBridge = bridge(deadTransport)
    val waiting = Future.fork(deadBridge.call(TextOperation("op", "request"), NeverCancelled))
    deadTransport.awaitSent(1)
    deadTransport.failTransport("link lost")
    waiting.join match {
      case Left(BridgeFailure.TransportFailed(message)) =>
        assert(message.contains("transport terminated"))
      case result => fail("expected termination TransportFailed, got " + result)
    }
    assertEquals(deadBridge.pendingCount, 0)
    sendBridge.beginStop()
    sendBridge.sessionStopped()
    deadBridge.sessionStopped()
  }
}
