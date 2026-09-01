/*  Title:      mcp_test/src/mcp_pide_bridge_tests.scala

Deterministic contracts for the extracted PIDE bridge boundary.
*/

package isabelle.mcp.pide

import isabelle.{Bytes, Future, Markup, Properties, XML, YXML}
import isabelle.mcp.{MCP_Session, MCP_Suite, McpBridgeOperations, McpBridgeProfile}
import isabelle.mcp.control.ManualDeadlineScheduler


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

  /* Models RequestRegistry.prepareCancel(): the state is marked before its
     returned callback thunk gets a chance to acquire the bridge monitor. */
  private final class MarkedBeforeDispatchCancellation extends BridgeCancellation {
    private var registered = false

    def isCancelled: Boolean = synchronized { registered }
    def onCancel(callback: () => Unit): Unit = synchronized { registered = true }
  }

  private final class ScriptedTransport extends PideTransport {
    private var receiver: Option[PideTransport.Inbound => Unit] = None
    private var terminated: Option[PideTransport.Termination => Unit] = None
    private var outbound = Vector.empty[PideTransport.Outbound]
    private var sendFailure: Option[RuntimeException] = None
    private var nextSend: Option[PideTransport.Outbound => Unit] = None
    private var closed = false

    def start(receive: PideTransport.Inbound => Unit,
      onTerminated: PideTransport.Termination => Unit): Unit = synchronized {
      receiver = Some(receive)
      terminated = Some(onTerminated)
    }

    def send(message: PideTransport.Outbound): Unit = {
      val run = synchronized {
        sendFailure match {
          case Some(exn) => sendFailure = None; throw exn
          case None =>
            outbound :+= message
            val result = nextSend
            nextSend = None
            notifyAll()
            result
        }
      }
      run.foreach(_(message))
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

    def onNextSend(callback: PideTransport.Outbound => Unit): Unit = synchronized {
      nextSend = Some(callback)
    }

    def failTransport(message: String): Unit =
      terminated.foreach(_(PideTransport.Failed(message)))

    def sent: Vector[PideTransport.Outbound] = synchronized { outbound }

    /** Start a new observation window after control-plane setup. */
    def clearOutbound(): Unit = synchronized { outbound = Vector.empty }

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
    deadlines: ManualDeadlineScheduler = new ManualDeadlineScheduler,
    diagnostics: String => Unit = _ => ()): PideBridge = {
    val control = new PideBridge(
      transport,
      policy(maxPending).maxPending,
      policy(maxPending).timing.callTimeout,
      deadlines,
      "MCP_Tools",
      McpBridgeOperations.baseOperationNames ++ Set("first", "second", "op"),
      McpBridgeProfile.base,
      PideBridgeV1,
      diagnostics)
    transport.onNextSend(message =>
      deliverHello(transport, requestProperty(message, "id"),
        operations = (McpBridgeOperations.baseOperationNames ++
          Set("first", "second", "op")).toList.sorted))
    assertEquals(control.awaitReady(1.0), Right(()))
    transport.clearOutbound()
    control
  }

  private def startupBridge(transport: ScriptedTransport,
    bridgeProfile: McpBridgeProfile): PideBridge =
    new PideBridge(
      transport,
      policy().maxPending,
      policy().timing.callTimeout,
      new ManualDeadlineScheduler,
      "MCP_Tools",
      McpBridgeOperations.operationNames,
      bridgeProfile,
      PideBridgeV1)

  private def policy(maxPending: Int = 2): PideBridgePolicy =
    PideBridgePolicy.checked(
      maxPending = maxPending,
      callTimeoutSeconds = 5.0,
      drainTimeoutSeconds = 2.0,
      maxRequestBytes = 1024,
      maxReplyBytes = 4096).fold(errors => fail(errors.mkString(", ")), identity)

  private final case class TextOperation(name: String, request: String)
      extends BridgeOperation[String] {
    val requestPayload: XML.Body = XML.Encode.string(request)
    def decodeReply(payload: XML.Body): Either[String, String] = {
      val text = XML.Decode.string(payload)
      if (text.startsWith("bad:")) Left(text) else Right(text)
    }
  }

  private def reply(text: String): XML.Body = XML.Encode.string(text)

  private def requestProperties(message: PideTransport.Outbound): Properties.T =
    YXML.parse_body(YXML.Source(message.arguments.head.text)) match {
      case List(XML.Elem(markup, _)) if markup.name == "mcp_bridge" => markup.properties
      case body => fail("invalid bridge request envelope: " + body)
    }

  private def requestProperty(message: PideTransport.Outbound, name: String): String =
    Properties.get(requestProperties(message), name)
      .getOrElse(fail("bridge request has no " + name))

  private def deliverResult(transport: ScriptedTransport, id: String,
    operation: String, text: String): Unit = {
    val message = PideBridgeV1.result(
      id, operation, "ok", XML.Encode.string(text))
    transport.deliver(message.function, id, message.body.text, message.properties)
  }

  private def deliverHello(transport: ScriptedTransport, id: String,
    revision: String = PideBridgeV1.revision,
    operations: List[String] = McpBridgeOperations.baseOperationNames.toList.sorted): Unit = {
    val message = PideBridgeV1.result(id, "hello", "ok",
      XML.Encode.pair(XML.Encode.string, XML.Encode.list(XML.Encode.string))(
        (revision, operations)))
    transport.deliver(message.function, id, message.body.text, message.properties)
  }

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

    assertEquals(registry.complete(secondId, "second", reply("reply-2")),
      PendingRegistry.Completed)
    assertEquals(registry.complete(firstId, "first", reply("reply-1")),
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

    assertEquals(registry.complete(ownedId, "wrong", reply("reply")),
      PendingRegistry.Rejected)
    owned.result match {
      case Left(BridgeFailure.ProtocolError(message)) =>
        assert(message.contains("does not match"))
      case result => fail("expected ProtocolError, got " + result)
    }
    assertEquals(registry.complete(ownedId, "expected", reply("late")),
      PendingRegistry.Unowned)
    assertEquals(registry.complete(otherId, "expected", reply("unknown")),
      PendingRegistry.Unowned)
    assertEquals(recorded.toList,
      List(
        PendingRegistry.UnownedReply(ownedId, "expected"),
        PendingRegistry.UnownedReply(otherId, "expected")))

    val rejectedId = BridgeCallId.test("rejected")
    val rejected = registry.register(rejectedId, TextOperation("decoder", "request"))
      .fold(failure => fail(failure.message), identity)
    assertEquals(registry.complete(rejectedId, "decoder", reply("bad: malformed")),
      PendingRegistry.Rejected)
    rejected.result match {
      case Left(BridgeFailure.ProtocolError(message)) =>
        assert(message.contains("malformed"))
      case result => fail("expected returned ProtocolError, got " + result)
    }

    val throwingId = BridgeCallId.test("throwing")
    val throwing = registry.register(throwingId, new BridgeOperation[String] {
      val name = "throwing"
      val requestPayload: XML.Body = XML.Encode.unit(())
      def decodeReply(payload: XML.Body): Either[String, String] =
        throw new IllegalArgumentException("invalid body")
    }).fold(failure => fail(failure.message), identity)
    assertEquals(registry.complete(throwingId, "throwing", reply("body")),
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

  test("all seven domain operations use the single v1 command and result function") {
    assertEquals(McpBridgeOperations.operationNames,
      Set("tools", "theories", "run_tool", "check_context", "ir",
        "resources", "read_resource"))
    assertEquals(PideBridgeV1.Command, "MCP.bridge")
    assertEquals(PideBridgeV1.resultFunctions, Set("MCP.bridge_result"))

    val outbound = PideBridgeV1.call(
      "string-id", "MCP_Repl", "tools",
      McpBridgeOperations.tools("isabelle://context/theory/HOL.Main").requestPayload)
    assertEquals(outbound.command, PideBridgeV1.Command)
    assertEquals(requestProperty(outbound, "id"), "string-id")
    assertEquals(requestProperty(outbound, "operation"), "tools")
  }

  spec_test("base-profile hello gates ordinary calls and accepts extra operations",
      covers = List("pide_bridge#T10")) {
    val transport = new ScriptedTransport
    val control = startupBridge(transport, McpBridgeProfile.base)
    assertEquals(control.call(TextOperation("tools", "request"), NeverCancelled),
      Left(BridgeFailure.ProtocolError("PIDE bridge is not ready")))

    val ready = Future.fork(control.awaitReady(1.0))
    transport.awaitSent(1)
    val hello = transport.sent.head
    assertEquals(requestProperty(hello, "kind"), "hello")
    assertEquals(requestProperty(hello, "theory"), "MCP_Tools")
    deliverHello(transport, requestProperty(hello, "id"), operations =
      (McpBridgeOperations.baseOperationNames + "extension").toList.sorted)
    assertEquals(ready.join, Right(()))
    assertEquals(control.advertisedOperationNames,
      McpBridgeOperations.baseOperationNames + "extension")
    control.beginStop()
    control.sessionStopped()
  }

  test("bridge profiles explicitly select the base or HOL startup requirements") {
    assertEquals(McpBridgeProfile.base.name, "base")
    assertEquals(McpBridgeProfile.base.requiredOperationNames,
      McpBridgeOperations.baseOperationNames)
    assertEquals(McpBridgeProfile.hol.name, "hol")
    assertEquals(McpBridgeProfile.hol.requiredOperationNames,
      McpBridgeOperations.holOperationNames)

    val transport = new ScriptedTransport
    val control = startupBridge(transport, McpBridgeProfile.hol)
    val ready = Future.fork(control.awaitReady(1.0))
    transport.awaitSent(1)
    deliverHello(transport, requestProperty(transport.sent.head, "id"),
      operations = McpBridgeOperations.baseOperationNames.toList.sorted)
    assert(ready.join.left.exists(_.message.contains("ir")))
    control.sessionStopped()
  }

  test("an operation omitted from hello is rejected locally without a call envelope") {
    val transport = new ScriptedTransport
    val control = startupBridge(transport, McpBridgeProfile.base)
    val ready = Future.fork(control.awaitReady(1.0))
    transport.awaitSent(1)
    deliverHello(transport, requestProperty(transport.sent.head, "id"),
      operations = McpBridgeOperations.baseOperationNames.toList.sorted)
    assertEquals(ready.join, Right(()))

    assertEquals(control.call(TextOperation("ir", "request"), NeverCancelled),
      Left(BridgeFailure.ProtocolError("bridge operation was not advertised by ML: ir")))
    assertEquals(transport.sent.length, 1)
    control.beginStop()
    control.sessionStopped()
  }

  test("startup hello rejects a wrong revision, missing operation, and malformed reply") {
    def rejected(deliver: (ScriptedTransport, String) => Unit): BridgeResult[Unit] = {
      val transport = new ScriptedTransport
      val control = startupBridge(transport, McpBridgeProfile.base)
      val ready = Future.fork(control.awaitReady(1.0))
      transport.awaitSent(1)
      deliver(transport, requestProperty(transport.sent.head, "id"))
      val result = ready.join
      control.sessionStopped()
      result
    }

    assert(rejected((transport, id) => deliverHello(transport, id, revision = "wrong"))
      .left.exists(_.message.contains("unsupported bridge revision")))
    assert(rejected((transport, id) => deliverHello(transport, id,
      operations = List("tools"))).left.exists(_.message.contains("missing required")))
    assert(rejected((transport, id) => {
      val malformed = PideBridgeV1.result(id, "hello", "ok", XML.Encode.string("not a pair"))
      transport.deliver(malformed.function, id, malformed.body.text, malformed.properties)
    }).left.exists(_.message.contains("malformed hello reply")))
  }

  test("startup hello fails on transport failure and bounded no-reply timeout") {
    val sendFailureTransport = new ScriptedTransport
    sendFailureTransport.failNextSend("cannot send hello")
    val sendFailure = startupBridge(sendFailureTransport, McpBridgeProfile.base)
    assertEquals(sendFailure.awaitReady(1.0),
      Left(BridgeFailure.TransportFailed("cannot send hello")))
    sendFailure.sessionStopped()

    val failedTransport = new ScriptedTransport
    val failed = startupBridge(failedTransport, McpBridgeProfile.base)
    val failedReady = Future.fork(failed.awaitReady(1.0))
    failedTransport.awaitSent(1)
    failedTransport.failTransport("lost")
    assertEquals(failedReady.join, Left(BridgeFailure.TransportFailed("transport terminated")))
    failed.sessionStopped()

    val silentTransport = new ScriptedTransport
    val silent = startupBridge(silentTransport, McpBridgeProfile.base)
    assertEquals(silent.awaitReady(0.01), Left(BridgeFailure.TimedOut(0.01)))
    silent.sessionStopped()
  }

  test("a timed-out startup hello cannot reopen the bridge through a late reply") {
    val transport = new ScriptedTransport
    val control = startupBridge(transport, McpBridgeProfile.base)
    val waiting = Future.fork(control.awaitReady(0.01))
    transport.awaitSent(1)
    val helloId = requestProperty(transport.sent.head, "id")
    assertEquals(waiting.join, Left(BridgeFailure.TimedOut(0.01)))

    deliverHello(transport, helloId,
      operations = McpBridgeOperations.baseOperationNames.toList.sorted)
    assertEquals(control.call(TextOperation("tools", "request"), NeverCancelled),
      Left(BridgeFailure.SessionStopped))
    control.sessionStopped()
    assertEquals(control.call(TextOperation("tools", "request"), NeverCancelled),
      Left(BridgeFailure.SessionStopped))
  }

  spec_test("status operation payloads preserve structured PIDE markup as XML bodies",
      covers = List("pide_bridge#T8")) {
    val marked = List(XML.Elem(Markup("block", Nil), List(XML.Text("marked text"))))
    val payload =
      XML.Encode.pair(XML.Encode.string, XML.Encode.self)(("ok", marked))
    val operation = McpBridgeOperations.readResource(
      "isabelle://context/theory/HOL.Main", "marked")

    assertEquals(operation.decodeReply(payload), Right(MCP_Session.Ok("marked text")))
  }

  test("production transport recognizes only unknown correlated MCP results") {
    val known = Set("MCP.op_result")
    def properties(function: String, id: Option[String]) =
      List(Markup.FUNCTION -> function) ::: id.toList.map("id" -> _)

    assertEquals(
      SessionPideTransport.unknownResultFunction(
        known, properties("MCP.bogus_result", Some("call"))),
      Some("MCP.bogus_result"))
    assertEquals(
      SessionPideTransport.unknownResultFunction(
        known, properties("MCP.op_result", Some("call"))),
      None)
    assertEquals(
      SessionPideTransport.unknownResultFunction(
        known, properties("MCP.bogus_result", None)),
      None)
    assertEquals(
      SessionPideTransport.unknownResultFunction(
        known, properties("PIDE.bogus_result", Some("call"))),
      None)
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
    val firstSend = sends.find(message => requestProperty(message, "operation") == "first").get
    val secondSend = sends.find(message => requestProperty(message, "operation") == "second").get
    val firstId = requestProperty(firstSend, "id")
    val secondId = requestProperty(secondSend, "id")
    assertNotEquals(firstId, secondId)
    deliverResult(transport, secondId, "second", "reply-2")
    deliverResult(transport, firstId, "first", "reply-1")

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

    val markedTransport = new ScriptedTransport
    val markedBridge = bridge(markedTransport)
    assertEquals(
      markedBridge.call(
        TextOperation("op", "request"), new MarkedBeforeDispatchCancellation),
      Left(BridgeFailure.Cancelled))
    assertEquals(markedTransport.sent, Vector.empty)
    assertEquals(markedBridge.pendingCount, 0)
    markedBridge.beginStop()
    markedBridge.sessionStopped()

    val cancelTransport = new ScriptedTransport
    val cancelBridge = bridge(cancelTransport)
    val cancellation = new TestCancellation
    val cancelled = Future.fork(
      cancelBridge.call(TextOperation("op", "request"), cancellation))
    cancelTransport.awaitSent(1)
    val cancelId = requestProperty(cancelTransport.sent.head, "id")
    cancellation.cancel()
    cancelTransport.awaitSent(2)
    assertEquals(requestProperty(cancelTransport.sent(1), "kind"), "cancel")
    assertEquals(requestProperty(cancelTransport.sent(1), "id"), cancelId)
    assertEquals(cancelled.join, Left(BridgeFailure.Cancelled))
    assertEquals(cancelBridge.pendingCount, 0)

    val replyTransport = new ScriptedTransport
    val replyBridge = bridge(replyTransport)
    val replyCancellation = new TestCancellation
    val completed = Future.fork(
      replyBridge.call(TextOperation("op", "request"), replyCancellation))
    replyTransport.awaitSent(1)
    val replyId = requestProperty(replyTransport.sent.head, "id")
    deliverResult(replyTransport, replyId, "op", "reply")
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
    val sendDeadlines = new ManualDeadlineScheduler
    val sendTransport = new ScriptedTransport
    val sendBridge = bridge(sendTransport, deadlines = sendDeadlines)
    sendTransport.failNextSend("send boom")
    sendBridge.call(TextOperation("op", "request"), NeverCancelled) match {
      case Left(BridgeFailure.TransportFailed(message)) => assert(message.contains("send boom"))
      case result => fail("expected send TransportFailed, got " + result)
    }
    assertEquals(sendBridge.pendingCount, 0)
    assertEquals(sendBridge.deadlineCount, 0)
    assertEquals(sendDeadlines.pendingCount, 0)

    val terminationDeadlines = new ManualDeadlineScheduler
    val deadTransport = new ScriptedTransport
    val deadBridge = bridge(deadTransport, deadlines = terminationDeadlines)
    val waiting = Future.fork(deadBridge.call(TextOperation("op", "request"), NeverCancelled))
    deadTransport.awaitSent(1)
    deadTransport.failTransport("link lost")
    waiting.join match {
      case Left(BridgeFailure.TransportFailed(message)) =>
        assert(message.contains("transport terminated"))
      case result => fail("expected termination TransportFailed, got " + result)
    }
    assertEquals(deadBridge.pendingCount, 0)
    assertEquals(deadBridge.deadlineCount, 0)
    assertEquals(terminationDeadlines.pendingCount, 0)
    sendBridge.beginStop()
    sendBridge.sessionStopped()
    deadBridge.sessionStopped()
  }

  spec_test("ordinary bridge deadline races have one winner and recover capacity",
      covers = List("pide_bridge#T4")) {
    val diagnostics = collection.mutable.ListBuffer.empty[String]
    val deadlines = new ManualDeadlineScheduler
    val transport = new ScriptedTransport
    val control = bridge(transport, maxPending = 1, deadlines = deadlines,
      diagnostics = message => diagnostics.synchronized { diagnostics += message })

    val timedOut = Future.fork(control.call(TextOperation("op", "first"), NeverCancelled))
    transport.awaitSent(1)
    val firstId = requestProperty(transport.sent.head, "id")
    assertEquals(control.pendingCount, 1)
    assertEquals(control.deadlineCount, 1)
    assertEquals(deadlines.pendingCount, 1)
    assert(deadlines.fireNext())
    transport.awaitSent(2)
    assertEquals(requestProperty(transport.sent(1), "kind"), "cancel")
    assertEquals(requestProperty(transport.sent(1), "id"), firstId)
    assertEquals(timedOut.join, Left(BridgeFailure.TimedOut(5.0)))
    assertEquals(control.pendingCount, 0)
    assertEquals(control.deadlineCount, 0)
    assertEquals(deadlines.pendingCount, 0)

    /* A late result has no owner and cannot settle the subsequently admitted call. */
    deliverResult(transport, firstId, "op", "late")
    assert(diagnostics.synchronized {
      diagnostics.exists(_.contains("Unowned PIDE bridge reply"))
    })

    val replyWins = Future.fork(control.call(TextOperation("op", "second"), NeverCancelled))
    transport.awaitSent(3)
    val secondId = requestProperty(transport.sent(2), "id")
    deliverResult(transport, secondId, "op", "reply")
    assertEquals(replyWins.join, Right("reply"))
    assertEquals(control.pendingCount, 0)
    assertEquals(control.deadlineCount, 0)
    assertEquals(deadlines.pendingCount, 0)
    assert(!deadlines.fireNext())
    control.beginStop()
    assert(deadlines.isShutdown)
    control.sessionStopped()
  }

  spec_test("cancellation and stop remove ordinary bridge deadlines",
      covers = List("pide_bridge#T3", "pide_bridge#T4")) {
    val cancellationDeadlines = new ManualDeadlineScheduler
    val cancellationTransport = new ScriptedTransport
    val cancellationBridge = bridge(cancellationTransport, deadlines = cancellationDeadlines)
    val cancellation = new TestCancellation
    val cancelled = Future.fork(
      cancellationBridge.call(TextOperation("op", "request"), cancellation))
    cancellationTransport.awaitSent(1)
    assertEquals(cancellationDeadlines.pendingCount, 1)
    cancellation.cancel()
    assertEquals(cancelled.join, Left(BridgeFailure.Cancelled))
    assertEquals(cancellationBridge.pendingCount, 0)
    assertEquals(cancellationBridge.deadlineCount, 0)
    assertEquals(cancellationDeadlines.pendingCount, 0)
    cancellationBridge.beginStop()
    cancellationBridge.sessionStopped()

    val stopDeadlines = new ManualDeadlineScheduler
    val stopTransport = new ScriptedTransport
    val stopBridge = bridge(stopTransport, deadlines = stopDeadlines)
    val stopped = Future.fork(stopBridge.call(TextOperation("op", "request"), NeverCancelled))
    stopTransport.awaitSent(1)
    assertEquals(stopDeadlines.pendingCount, 1)
    stopBridge.beginStop()
    assertEquals(stopped.join, Left(BridgeFailure.SessionStopped))
    assertEquals(stopBridge.pendingCount, 0)
    assertEquals(stopBridge.deadlineCount, 0)
    assertEquals(stopDeadlines.pendingCount, 0)
    assert(stopDeadlines.isShutdown)
    stopBridge.sessionStopped()
  }

  test("synchronous decoder rejection cancels work already handed to the transport") {
    val transport = new ScriptedTransport
    val control = bridge(transport)
    transport.onNextSend { message =>
      val id = requestProperty(message, "id")
      deliverResult(transport, id, "op", "bad: malformed")
    }

    control.call(TextOperation("op", "request"), NeverCancelled) match {
      case Left(BridgeFailure.ProtocolError(message)) => assert(message.contains("malformed"))
      case result => fail("expected synchronous ProtocolError, got " + result)
    }
    assertEquals(transport.sent.length, 2)
    val id = requestProperty(transport.sent.head, "id")
    assertEquals(requestProperty(transport.sent(1), "kind"), "cancel")
    assertEquals(requestProperty(transport.sent(1), "id"), id)
    assertEquals(control.pendingCount, 0)
    control.beginStop()
    control.sessionStopped()
  }

  test("unknown result function fails its known owner and cancels the sent call") {
    val diagnostics = collection.mutable.ListBuffer.empty[String]
    val deadlines = new ManualDeadlineScheduler
    val transport = new ScriptedTransport
    val control = bridge(transport, deadlines = deadlines, diagnostics = message => diagnostics.synchronized {
      diagnostics += message
    })
    val waiting = Future.fork(control.call(TextOperation("op", "request"), NeverCancelled))
    transport.awaitSent(1)
    val id = requestProperty(transport.sent.head, "id")

    /* PideBridgeV1 decodes this correlated unknown function as
      * Malformed(Some(id), None, ...), rather than as a normal failure. */
    transport.deliver("MCP.bogus_result", id, "reply")
    waiting.join match {
      case Left(BridgeFailure.ProtocolError(message)) =>
        assert(message.contains("unknown PIDE bridge result function"))
      case result => fail("expected unknown-function ProtocolError, got " + result)
    }
    transport.awaitSent(2)
    assertEquals(requestProperty(transport.sent(1), "kind"), "cancel")
    assertEquals(requestProperty(transport.sent(1), "id"), id)
    assertEquals(control.pendingCount, 0)
    assertEquals(control.deadlineCount, 0)
    assertEquals(deadlines.pendingCount, 0)

    deliverResult(transport, id, "op", "late")
    assert(diagnostics.synchronized {
      diagnostics.exists(_.contains("Unowned PIDE bridge reply"))
    })
    control.beginStop()
    control.sessionStopped()
  }
}
