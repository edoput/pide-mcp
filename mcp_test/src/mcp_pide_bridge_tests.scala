/*  Title:      mcp_test/src/mcp_pide_bridge_tests.scala

Deterministic PIDE bridge contracts using a scripted transport and manual
deadlines, without starting Isabelle. Tests startup negotiation, reply
correlation, envelope validation, request/reply size limits, bounded
admission, cancellation, transport failures, and shutdown drain.

Controlled reply ordering and callbacks exercise competing terminal outcomes
and capacity recovery. Payload-corpus checks verify operation coverage,
deterministic measurements, derived defaults, and stale-artifact detection.
*/

package isabelle.mcp.pide

import isabelle.{Bytes, Future, Markup, Path, Properties, XML, YXML}
import isabelle.mcp.{MCP_Pide_Payload_Measure, MCP_Session, MCP_Suite, McpBridgeOperations, McpBridgeProfile}
import isabelle.mcp.control.ManualDeadlineScheduler

import java.nio.charset.StandardCharsets
import java.nio.file.Files


class MCP_Pide_Bridge_Tests extends MCP_Suite {
  private def positiveDuration(seconds: Double): PideBridgePolicy.PositiveDuration =
    PideBridgePolicy.PositiveDuration.checked("test timeout", seconds)
      .fold(message => fail(message), identity)

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
    def isClosed: Boolean = synchronized { closed }

    /** Start a new observation window after control-plane setup. */
    def clearOutbound(): Unit = synchronized { outbound = Vector.empty }

    def awaitSent(count: Int): Unit = synchronized {
      val deadline = System.nanoTime() + 5000000000L
      while (outbound.length < count && System.nanoTime() < deadline) wait(10L)
      if (outbound.length < count) fail("timed out waiting for " + count + " bridge sends")
    }

    def deliver(function: String, id: String, text: String,
      properties: List[(String, String)] = Nil): Unit = {
      deliverInbound(PideTransport.Inbound(
        function, ("id" -> id) :: properties, Bytes(text)))
    }

    def deliverInbound(message: PideTransport.Inbound): Unit = {
      val target = synchronized { receiver.getOrElse(fail("transport not started")) }
      target(message)
    }
  }

  private def bridge(transport: ScriptedTransport, maxPending: Int = 4,
    deadlines: ManualDeadlineScheduler = new ManualDeadlineScheduler,
    drainTimeoutSeconds: Double = 0.0,
    maxRequestBytes: Long = 1024,
    maxReplyBytes: Long = 4096,
    protocol: PideBridgeProtocol = PideBridgeV1,
    diagnostics: String => Unit = _ => ()): PideBridge = {
    val configured = policy(maxPending, drainTimeoutSeconds, maxRequestBytes, maxReplyBytes)
    val control = new PideBridge(
      transport,
      configured.maxPending,
      configured.envelopes.maxRequestBytes,
      configured.envelopes.maxReplyBytes,
      configured.timing.callTimeout,
      configured.timing.drainTimeout,
      deadlines,
      "MCP_Tools",
      McpBridgeOperations.baseOperationNames ++ Set("first", "second", "op"),
      McpBridgeProfile.base,
      protocol,
      diagnostics)
    transport.onNextSend(message =>
      deliverHello(transport, requestProperty(message, "id"),
        operations = (McpBridgeOperations.baseOperationNames ++
          Set("first", "second", "op")).toList.sorted))
    assertEquals(control.awaitReady(positiveDuration(1.0)), Right(()))
    transport.clearOutbound()
    control
  }

  private def startupBridge(transport: ScriptedTransport,
    bridgeProfile: McpBridgeProfile,
    maxRequestBytes: Long = 1024,
    diagnostics: String => Unit = _ => ()): PideBridge =
    new PideBridge(
      transport,
      policy().maxPending,
      policy(maxRequestBytes = maxRequestBytes).envelopes.maxRequestBytes,
      policy().envelopes.maxReplyBytes,
      policy().timing.callTimeout,
      policy().timing.drainTimeout,
      new ManualDeadlineScheduler,
      "MCP_Tools",
      McpBridgeOperations.operationNames,
      bridgeProfile,
      PideBridgeV1,
      diagnostics)

  private def policy(maxPending: Int = 2, drainTimeoutSeconds: Double = 0.0,
    maxRequestBytes: Long = 1024,
    maxReplyBytes: Long = 4096): PideBridgePolicy =
    PideBridgePolicy.checked(
      maxPending = maxPending,
      callTimeoutSeconds = 5.0,
      drainTimeoutSeconds = drainTimeoutSeconds,
      maxRequestBytes = maxRequestBytes,
      maxReplyBytes = maxReplyBytes).fold(errors => fail(errors.mkString(", ")), identity)

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
    transport.deliver(message.function, id, message.body.text,
      message.properties.filterNot(_._1 == "id"))
  }

  private def deliverHello(transport: ScriptedTransport, id: String,
    revision: String = PideBridgeV1.revision,
    operations: List[String] = McpBridgeOperations.baseOperationNames.toList.sorted): Unit = {
    val message = PideBridgeV1.result(id, "hello", "ok",
      XML.Encode.pair(XML.Encode.string, XML.Encode.list(XML.Encode.string))(
        (revision, operations)))
    transport.deliver(message.function, id, message.body.text,
      message.properties.filterNot(_._1 == "id"))
  }

  private def deliverDrain(transport: ScriptedTransport, id: String,
    status: String = "ok", payload: XML.Body = Nil,
    properties: Properties.T = Nil): Unit = {
    val message = PideBridgeV1.drainResult(id, status, payload, properties)
    transport.deliver(message.function, id, message.body.text,
      message.properties.filterNot(_._1 == "id"))
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
        "drainTimeout must be finite and non-negative",
        "maxRequestBytes must be positive",
        "maxReplyBytes must be at least " + PideBridgePolicy.MinimumReplyBytes))
  }

  test("reply policy rejects limits too small for the correlated size failure") {
    val minimum = PideBridgePolicy.MinimumReplyBytes
    assert(PideBridgePolicy.checkedReplyBytes(minimum - 1).isLeft)
    assert(PideBridgePolicy.checkedReplyBytes(minimum).isRight)
  }

  spec_test("request size admits the exact serialized envelope and rejects one byte over",
      covers = List("pide_bridge#T11")) {
    val payload = "x" * 64
    /* UUID ids have 36 characters; :1 and :2 have the same request size. */
    val sampleId = "x" * 36 + ":1"
    val exactBytes = PideBridgeV1.requestBytes(PideBridgeV1.call(
      sampleId, "MCP_Tools", "op", XML.Encode.string(payload))).fold(
        failure => fail(failure.message), identity)
    val transport = new ScriptedTransport
    val control = bridge(transport, maxRequestBytes = exactBytes)
    val exact = Future.fork(control.call(TextOperation("op", payload), NeverCancelled))
    transport.awaitSent(1)
    deliverResult(transport, requestProperty(transport.sent.head, "id"), "op", "ok")
    assertEquals(exact.join, Right("ok"))
    assertEquals(control.call(TextOperation("op", payload + "x"), NeverCancelled),
      Left(BridgeFailure.TooLarge(BridgeFailure.Direction.Request, exactBytes + 1L, exactBytes)))
    assertEquals(transport.sent.length, 1)
    assertEquals(control.pendingCount, 0)
    assertEquals(control.deadlineCount, 0)
    val small = Future.fork(control.call(TextOperation("op", "small"), NeverCancelled))
    transport.awaitSent(2)
    deliverResult(transport, requestProperty(transport.sent(1), "id"), "op", "small")
    assertEquals(small.join, Right("small"))
    assert(PideBridgeV1.requestBytes(PideTransport.Outbound(PideBridgeV1.Command, Nil)).isLeft)
  }

  spec_test("tiny request limit settles startup hello with typed TooLarge",
      covers = List("pide_bridge#T11")) {
    val transport = new ScriptedTransport
    val control = startupBridge(transport, McpBridgeProfile.base, maxRequestBytes = 1)
    control.awaitReady(positiveDuration(1.0)) match {
      case Left(BridgeFailure.TooLarge(BridgeFailure.Direction.Request, actual, 1L)) =>
        assert(actual > 1L)
      case other => fail("expected request TooLarge for hello, got " + other)
    }
    assertEquals(transport.sent, Vector.empty)
  }

  private object PaddedDrainProtocol extends PideBridgeProtocol {
    def revision: String = PideBridgeV1.revision
    def resultFunctions: Set[String] = PideBridgeV1.resultFunctions
    def hello(id: String, theory: String): PideTransport.Outbound = PideBridgeV1.hello(id, theory)
    def call(id: String, theory: String, operation: String,
      payload: XML.Body): PideTransport.Outbound = PideBridgeV1.call(id, theory, operation, payload)
    def cancel(id: String): PideTransport.Outbound = PideBridgeV1.cancel(id)
    def drain(id: String): PideTransport.Outbound = {
      val outbound = PideBridgeV1.drain(id)
      outbound.copy(arguments = List(Bytes(outbound.arguments.head.text + ("x" * 2048))))
    }
    def requestBytes(outbound: PideTransport.Outbound): Either[BridgeFailure, Long] =
      PideBridgeV1.requestBytes(outbound)
    def oversized(reply: PideTransport.Inbound): PideBridgeReply = PideBridgeV1.oversized(reply)
    def decode(reply: PideTransport.Inbound): PideBridgeReply = PideBridgeV1.decode(reply)
  }

  spec_test("tiny request limit settles drain with typed TooLarge",
      covers = List("pide_bridge#T11")) {
    val transport = new ScriptedTransport
    val control = bridge(transport, maxRequestBytes = 1024, protocol = PaddedDrainProtocol)
    control.beginStop() match {
      case BridgeDrainOutcome.Failed(
          BridgeFailure.TooLarge(BridgeFailure.Direction.Request, actual, 1024L)) =>
        assert(actual > 1024L)
      case other => fail("expected request TooLarge for drain, got " + other)
    }
    assertEquals(transport.sent, Vector.empty)
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

    val drain = PideBridgeV1.drain("drain-id")
    assertEquals(requestProperty(drain, "kind"), "drain")
    assertEquals(requestProperty(drain, "id"), "drain-id")
    assert(!requestProperties(drain).exists(_._1 == "operation"))
  }

  test("drain acknowledgements have an exact control-envelope shape") {
    assertEquals(PideBridgeV1.decode(PideBridgeV1.drainResult("drain-id")),
      PideBridgeReply.DrainAck("drain-id"))
    assertEquals(
      PideBridgeV1.decode(PideBridgeV1.drainResult(
        "drain-id", status = "protocol_error", payload = XML.Encode.string("bad drain"))),
      PideBridgeReply.DrainFailure("drain-id", BridgeFailure.ProtocolError("bad drain")))

    def malformed(message: PideTransport.Inbound): String =
      PideBridgeV1.decode(message) match {
        case PideBridgeReply.Malformed(_, _, detail) => detail
        case other => fail("expected malformed drain reply, got " + other)
      }

    assert(malformed(PideBridgeV1.drainResult(
      "drain-id", properties = List("extra" -> "value"))).contains("invalid drain"))
    assert(malformed(PideBridgeV1.drainResult(
      "drain-id", properties = List("operation" -> "drain"))).contains("invalid drain"))
    assert(malformed(PideBridgeV1.drainResult(
      "drain-id", payload = XML.Encode.string("unexpected"))).contains("invalid drain"))
    assert(malformed(PideBridgeV1.drainResult(
      "drain-id", status = "remote_error")).contains("invalid drain acknowledgement status"))
    assert(malformed(PideBridgeV1.drainResult(
      "drain-id", properties = List("id" -> "duplicate"))).contains("duplicate id"))
  }

  spec_test("ordinary result envelopes reject every duplicate and extra property",
      covers = List("pide_bridge#T2")) {
    val invalidProperties = List(
      List("revision" -> PideBridgeV1.revision),
      List("kind" -> "result"),
      List("id" -> "other-id"),
      List("operation" -> "other-operation"),
      List("status" -> "remote_error"),
      List("extra" -> "value"))

    invalidProperties.foreach { extra =>
      val message = PideBridgeV1.result(
        "owned-id", "op", "ok", XML.Encode.string("result"), properties = extra)
      val decoded = PideBridgeV1.decode(message.copy(properties =
        List(Markup.FUNCTION -> PideBridgeV1.ResultFunction,
          "id" -> "owned-id", "operation" -> "op")))
      decoded match {
        case PideBridgeReply.Malformed(Some("owned-id"), Some("op"), _) => ()
        case other => fail("expected a correlated malformed ordinary result for " + extra +
          ", got " + other)
      }
    }

    val conflicting = PideBridgeV1.result(
      "inner-id", "inner-operation", "ok", XML.Encode.string("result"))
    PideBridgeV1.decode(conflicting.copy(properties =
      List(Markup.FUNCTION -> PideBridgeV1.ResultFunction,
        "id" -> "outer-id", "operation" -> "outer-operation"))) match {
      case PideBridgeReply.Malformed(Some("outer-id"), Some("outer-operation"), detail) =>
        assert(detail.contains("does not match"))
      case other => fail("expected conflicting correlation to retain the outer owner, got " + other)
    }

    val valid = PideBridgeV1.result(
      "owned-id", "op", "ok", XML.Encode.string("result"))
    List("id", "operation").foreach { missing =>
      PideBridgeV1.decode(valid.copy(
        properties = valid.properties.filterNot(_._1 == missing))) match {
        case PideBridgeReply.Malformed(Some("owned-id"), Some("op"), detail) =>
          assert(detail.contains("missing outer " + missing))
        case other => fail("expected missing outer " + missing + " to reject the result, got " + other)
      }
    }
  }

  spec_test("reply size protocol decodes structured too_large and rejects malformed counts",
      covers = List("pide_bridge#T12")) {
    val payload = XML.Encode.pair(XML.Encode.string, XML.Encode.string)("1048577", "1048576")
    assertEquals(PideBridgeV1.decode(PideBridgeV1.result(
      "owned-id", "op", "too_large", payload)),
      PideBridgeReply.Failure("owned-id", "op",
        BridgeFailure.TooLarge(BridgeFailure.Direction.Reply, 1048577L, 1048576L)))

    List(("-1", "1048576"), ("1", "0"), ("1048576", "1048576")).foreach {
      case (actual, limit) =>
        PideBridgeV1.decode(PideBridgeV1.result(
          "owned-id", "op", "too_large",
          XML.Encode.pair(XML.Encode.string, XML.Encode.string)(actual, limit))) match {
          case PideBridgeReply.Malformed(Some("owned-id"), Some("op"), detail) =>
            assert(detail.contains("invalid too_large payload"))
          case other => fail("expected malformed too_large payload, got " + other)
        }
    }
  }

  spec_test("reply size limit admits exact envelopes and rejects raw oversized replies before decoding",
      covers = List("pide_bridge#T12")) {
    val limit = 4096L
    val empty = PideBridgeV1.result("x" * 38, "op", "ok", reply(""))
    val accepted = "x" * (limit - empty.body.size).toInt
    val exactTransport = new ScriptedTransport
    val decoded = collection.mutable.ListBuffer.empty[String]
    val exact = bridge(exactTransport, maxReplyBytes = limit)
    val exactCall = Future.fork(exact.call(new BridgeOperation[String] {
      val name = "op"
      val requestPayload = reply("request")
      def decodeReply(payload: XML.Body): Either[String, String] = {
        val value = XML.Decode.string(payload); decoded += value; Right(value)
      }
    }, NeverCancelled))
    exactTransport.awaitSent(1)
    val exactId = requestProperty(exactTransport.sent.head, "id")
    val exactReply = PideBridgeV1.result(exactId, "op", "ok", reply(accepted))
    assertEquals(exactReply.body.size.toLong, limit)
    exactTransport.deliverInbound(exactReply)
    assertEquals(exactCall.join, Right(accepted))
    assertEquals(decoded.toList, List(accepted))
    exact.beginStop(); exact.sessionStopped()

    val deadlines = new ManualDeadlineScheduler
    val transport = new ScriptedTransport
    val control = bridge(transport, maxPending = 2, deadlines = deadlines, maxReplyBytes = 4096)
    var oversizedDecoderCalled = false
    val first = Future.fork(control.call(new BridgeOperation[String] {
      val name = "op"
      val requestPayload = reply("first")
      def decodeReply(payload: XML.Body): Either[String, String] = {
        oversizedDecoderCalled = true; Right(XML.Decode.string(payload))
      }
    }, NeverCancelled))
    val second = Future.fork(control.call(TextOperation("op", "second"), NeverCancelled))
    transport.awaitSent(2)
    val firstId = requestProperty(transport.sent(0), "id")
    val secondId = requestProperty(transport.sent(1), "id")
    val oversizedEmpty = PideBridgeV1.result("x" * 38, "op", "ok", reply(""))
    val oversizedPayload = "x" * (4097L - oversizedEmpty.body.size).toInt
    val oversizedReply = PideBridgeV1.result(firstId, "op", "ok", reply(oversizedPayload))
    assertEquals(oversizedReply.body.size, 4097L)
    transport.deliverInbound(oversizedReply)
    assertEquals(first.join, Left(BridgeFailure.TooLarge(BridgeFailure.Direction.Reply, 4097L, 4096L)))
    assert(!oversizedDecoderCalled, "oversized reply reached its operation decoder")
    assert(!second.is_finished, "oversized first reply completed another owner")
    assertEquals(control.pendingCount, 1)
    assertEquals(control.deadlineCount, 1)
    deliverResult(transport, secondId, "op", "second")
    assertEquals(second.join, Right("second"))
    assertEquals(control.pendingCount, 0)
    assertEquals(control.deadlineCount, 0)
    control.beginStop(); control.sessionStopped()
  }

  spec_test("reply size oversized replies without a safe outer id only diagnose",
      covers = List("pide_bridge#T12")) {
    val diagnostics = collection.mutable.ListBuffer.empty[String]
    val transport = new ScriptedTransport
    val control = bridge(transport, maxReplyBytes = 4096,
      diagnostics = message => diagnostics.synchronized { diagnostics += message })
    val call = Future.fork(control.call(TextOperation("op", "request"), NeverCancelled))
    transport.awaitSent(1)
    val id = requestProperty(transport.sent.head, "id")
    transport.deliverInbound(PideTransport.Inbound(PideBridgeV1.ResultFunction,
      List(Markup.FUNCTION -> PideBridgeV1.ResultFunction, "operation" -> "op"),
      Bytes("x" * 4097)))
    assert(!call.is_finished, "uncorrelated oversized reply completed an owner")
    assert(diagnostics.synchronized {
      diagnostics.exists(_.contains("Uncorrelated oversized"))
    })
    deliverResult(transport, id, "op", "reply")
    assertEquals(call.join, Right("reply"))
    control.beginStop(); control.sessionStopped()
  }

  spec_test("reply size routes oversized control replies and malformed outer functions",
      covers = List("pide_bridge#T12")) {
    def oversized(id: String, operation: Option[String] = None,
      function: String = PideBridgeV1.ResultFunction): PideTransport.Inbound =
      PideTransport.Inbound(function,
        List(Markup.FUNCTION -> function, "id" -> id) ++ operation.map("operation" -> _),
        Bytes("x" * 4097))

    val helloTransport = new ScriptedTransport
    val hello = startupBridge(helloTransport, McpBridgeProfile.base)
    val ready = Future.fork(hello.awaitReady(positiveDuration(1.0)))
    helloTransport.awaitSent(1)
    helloTransport.deliverInbound(oversized(requestProperty(helloTransport.sent.head, "id"), Some("hello")))
    assertEquals(ready.join,
      Left(BridgeFailure.TooLarge(BridgeFailure.Direction.Reply, 4097L, 4096L)))
    assert(!hello.startupHelloPending)
    hello.sessionStopped()

    val drainTransport = new ScriptedTransport
    val drain = bridge(drainTransport, drainTimeoutSeconds = 1.0)
    val stopping = Future.fork(drain.beginStop())
    drainTransport.awaitSent(1)
    drainTransport.deliverInbound(oversized(requestProperty(drainTransport.sent.head, "id")))
    assertEquals(stopping.join,
      BridgeDrainOutcome.Failed(BridgeFailure.TooLarge(BridgeFailure.Direction.Reply, 4097L, 4096L)))
    drain.sessionStopped()

    var decoderCalled = false
    val ordinaryTransport = new ScriptedTransport
    val ordinary = bridge(ordinaryTransport)
    val call = Future.fork(ordinary.call(new BridgeOperation[String] {
      val name = "op"
      val requestPayload = reply("request")
      def decodeReply(payload: XML.Body): Either[String, String] = {
        decoderCalled = true; Right(XML.Decode.string(payload))
      }
    }, NeverCancelled))
    ordinaryTransport.awaitSent(1)
    ordinaryTransport.deliverInbound(oversized(
      requestProperty(ordinaryTransport.sent.head, "id"), Some("op"), "MCP.bogus_result"))
    call.join match {
      case Left(BridgeFailure.ProtocolError(detail)) =>
        assert(detail.contains("unknown PIDE bridge result function"))
      case other => fail("expected malformed outer function ProtocolError, got " + other)
    }
    assert(!decoderCalled)
    ordinary.sessionStopped()

    val diagnostics = collection.mutable.ListBuffer.empty[String]
    val unownedTransport = new ScriptedTransport
    val unowned = bridge(unownedTransport,
      diagnostics = message => diagnostics.synchronized { diagnostics += message })
    unownedTransport.deliverInbound(oversized("unowned-size-id"))
    assert(diagnostics.synchronized {
      diagnostics.exists(_.contains("Unowned PIDE bridge reply for oversized result id unowned-size-id"))
    })
    unowned.sessionStopped()
  }

  spec_test("missing outer result identity immediately rejects the inner owner",
      covers = List("pide_bridge#T2")) {
    List("id", "operation").foreach { missing =>
      val deadlines = new ManualDeadlineScheduler
      val transport = new ScriptedTransport
      val control = bridge(transport, deadlines = deadlines)
      val call = Future.fork(control.call(TextOperation("op", "request"), NeverCancelled))
      transport.awaitSent(1)
      val id = requestProperty(transport.sent.head, "id")
      val result = PideBridgeV1.result(id, "op", "ok", XML.Encode.string("wrong"))
      transport.deliverInbound(result.copy(
        properties = result.properties.filterNot(_._1 == missing)))

      call.join match {
        case Left(BridgeFailure.ProtocolError(message)) =>
          assert(message.contains("missing outer " + missing))
        case other => fail("expected missing outer identity ProtocolError, got " + other)
      }
      transport.awaitSent(2)
      assertEquals(transport.sent.map(requestProperty(_, "kind")), Vector("call", "cancel"))
      assertEquals(control.pendingCount, 0)
      assertEquals(control.deadlineCount, 0)
      assertEquals(deadlines.pendingCount, 0)
      control.sessionStopped()
    }
  }

  spec_test("outer correlation terminalizes malformed bodies without crossing owners",
      covers = List("pide_bridge#T2")) {
    val deadlines = new ManualDeadlineScheduler
    val transport = new ScriptedTransport
    val control = bridge(transport, maxPending = 2, deadlines = deadlines,
      drainTimeoutSeconds = 1.0)

    val first = Future.fork(control.call(TextOperation("op", "first"), NeverCancelled))
    transport.awaitSent(1)
    val firstId = requestProperty(transport.sent(0), "id")
    val second = Future.fork(control.call(TextOperation("op", "second"), NeverCancelled))
    transport.awaitSent(2)
    val secondId = requestProperty(transport.sent(1), "id")

    val ambiguous = PideBridgeV1.result(secondId, "op", "ok", XML.Encode.string("wrong"))
    transport.deliver(ambiguous.function, firstId, ambiguous.body.text,
      List("operation" -> "op"))

    first.join match {
      case Left(BridgeFailure.ProtocolError(_)) => ()
      case other => fail("expected duplicate-id ProtocolError for the outer owner, got " + other)
    }
    assert(!second.is_finished, "ambiguous inner id completed the other bridge owner")
    assertEquals(control.pendingCount, 1)
    assertEquals(control.deadlineCount, 1)
    assertEquals(deadlines.pendingCount, 1)

    deliverResult(transport, secondId, "op", "second-result")
    assertEquals(second.join, Right("second-result"))
    assertEquals(control.pendingCount, 0)
    assertEquals(control.deadlineCount, 0)
    assertEquals(deadlines.pendingCount, 0)

    val emptyInner = Future.fork(
      control.call(TextOperation("op", "empty-inner"), NeverCancelled))
    transport.awaitSent(4)
    val emptyInnerId = requestProperty(transport.sent.last, "id")
    val emptyInnerReply = PideBridgeV1.result("", "op", "ok", XML.Encode.string("wrong"))
    transport.deliver(emptyInnerReply.function, emptyInnerId, emptyInnerReply.body.text,
      List("operation" -> "op"))
    emptyInner.join match {
      case Left(BridgeFailure.ProtocolError(_)) => ()
      case other => fail("expected empty inner id to fail the outer owner, got " + other)
    }
    transport.awaitSent(5)
    assertEquals(control.pendingCount, 0)
    assertEquals(control.deadlineCount, 0)
    assertEquals(deadlines.pendingCount, 0)

    val stopping = Future.fork(control.beginStop())
    transport.awaitSent(6)
    val drainId = requestProperty(transport.sent.last, "id")
    deliverDrain(transport, drainId)
    assertEquals(stopping.join, BridgeDrainOutcome.Acknowledged)

    val bodyDeadlines = new ManualDeadlineScheduler
    val bodyTransport = new ScriptedTransport
    val bodyControl = bridge(bodyTransport, deadlines = bodyDeadlines)
    val malformedBody = Future.fork(
      bodyControl.call(TextOperation("op", "body"), NeverCancelled))
    bodyTransport.awaitSent(1)
    val malformedBodyId = requestProperty(bodyTransport.sent.head, "id")
    bodyTransport.deliver(PideBridgeV1.ResultFunction, malformedBodyId, "not an envelope",
      List("operation" -> "op"))
    malformedBody.join match {
      case Left(BridgeFailure.ProtocolError(_)) => ()
      case other => fail("expected correlated malformed-body ProtocolError, got " + other)
    }
    bodyTransport.awaitSent(2)
    assertEquals(bodyTransport.sent.map(requestProperty(_, "kind")), Vector("call", "cancel"))
    assertEquals(bodyControl.pendingCount, 0)
    assertEquals(bodyControl.deadlineCount, 0)
    assertEquals(bodyDeadlines.pendingCount, 0)
    bodyControl.sessionStopped()

    val drainTransport = new ScriptedTransport
    val drainControl = bridge(drainTransport, drainTimeoutSeconds = 1.0)
    val malformedDrain = Future.fork(drainControl.beginStop())
    drainTransport.awaitSent(1)
    val malformedDrainId = requestProperty(drainTransport.sent.head, "id")
    drainTransport.deliver(PideBridgeV1.ResultFunction, malformedDrainId, "not an envelope")
    malformedDrain.join match {
      case BridgeDrainOutcome.Failed(BridgeFailure.ProtocolError(_)) => ()
      case other => fail("expected correlated malformed-drain ProtocolError, got " + other)
    }
    assert(drainControl.isStopping)
    assert(!drainControl.isStopped)
    drainControl.sessionStopped()
  }

  spec_test("base-profile hello gates ordinary calls and accepts extra operations",
      covers = List("pide_bridge#T10")) {
    val transport = new ScriptedTransport
    val control = startupBridge(transport, McpBridgeProfile.base)
    assertEquals(control.call(TextOperation("tools", "request"), NeverCancelled),
      Left(BridgeFailure.ProtocolError("PIDE bridge is not ready")))

    val ready = Future.fork(control.awaitReady(positiveDuration(1.0)))
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

  test("startup hello is single-flight and keeps its first waiter as owner") {
    val transport = new ScriptedTransport
    val control = startupBridge(transport, McpBridgeProfile.base)
    val first = Future.fork(control.awaitReady(positiveDuration(1.0)))
    transport.awaitSent(1)
    val helloId = requestProperty(transport.sent.head, "id")

    assertEquals(control.awaitReady(positiveDuration(1.0)),
      Left(BridgeFailure.ProtocolError("PIDE bridge startup hello is already in flight")))
    assertEquals(transport.sent.length, 1)
    assert(control.startupHelloPending)

    deliverHello(transport, helloId,
      operations = McpBridgeOperations.baseOperationNames.toList.sorted)
    assertEquals(first.join, Right(()))
    assert(!control.startupHelloPending)
    control.sessionStopped()
  }

  spec_test("a re-entrant successful hello remains first after send throws",
      covers = List("pide_bridge#T10")) {
    val diagnostics = collection.mutable.ListBuffer.empty[String]
    val transport = new ScriptedTransport
    val control = startupBridge(transport, McpBridgeProfile.base,
      diagnostics = message => diagnostics.synchronized { diagnostics += message })
    transport.onNextSend { message =>
      deliverHello(transport, requestProperty(message, "id"),
        operations = McpBridgeOperations.baseOperationNames.toList.sorted)
      throw new RuntimeException("send returned an error after hello")
    }

    assertEquals(control.awaitReady(positiveDuration(1.0)), Right(()))
    assertEquals(control.awaitReady(positiveDuration(1.0)), Right(()))
    assert(!control.startupHelloPending)
    assert(diagnostics.synchronized {
      diagnostics.exists(_.contains("Ignored PIDE bridge hello send failure after terminal outcome"))
    })
    control.sessionStopped()
  }

  test("largest accepted startup timeout does not overflow into immediate expiry") {
    assert(PideBridgePolicy.PositiveDuration.checked("startup", Double.MaxValue).isRight)
    val transport = new ScriptedTransport
    val control = startupBridge(transport, McpBridgeProfile.base)
    val waiting = Future.fork(control.awaitReady(positiveDuration(Double.MaxValue)))
    transport.awaitSent(1)
    deliverHello(transport, requestProperty(transport.sent.head, "id"),
      operations = McpBridgeOperations.baseOperationNames.toList.sorted)
    assertEquals(waiting.join, Right(()))
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
    val ready = Future.fork(control.awaitReady(positiveDuration(1.0)))
    transport.awaitSent(1)
    deliverHello(transport, requestProperty(transport.sent.head, "id"),
      operations = McpBridgeOperations.baseOperationNames.toList.sorted)
    assert(ready.join.left.exists(_.message.contains("ir")))
    control.sessionStopped()
  }

  test("an operation omitted from hello is rejected locally without a call envelope") {
    val transport = new ScriptedTransport
    val control = startupBridge(transport, McpBridgeProfile.base)
    val ready = Future.fork(control.awaitReady(positiveDuration(1.0)))
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
      val ready = Future.fork(control.awaitReady(positiveDuration(1.0)))
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
      transport.deliverInbound(malformed)
    }).left.exists(_.message.contains("malformed hello reply")))
  }

  test("startup hello fails on transport failure and bounded no-reply timeout") {
    val sendFailureTransport = new ScriptedTransport
    sendFailureTransport.failNextSend("cannot send hello")
    val sendFailure = startupBridge(sendFailureTransport, McpBridgeProfile.base)
    assertEquals(sendFailure.awaitReady(positiveDuration(1.0)),
      Left(BridgeFailure.TransportFailed("cannot send hello")))
    sendFailure.sessionStopped()

    val failedTransport = new ScriptedTransport
    val failed = startupBridge(failedTransport, McpBridgeProfile.base)
    val failedReady = Future.fork(failed.awaitReady(positiveDuration(1.0)))
    failedTransport.awaitSent(1)
    failedTransport.failTransport("lost")
    assertEquals(failedReady.join, Left(BridgeFailure.TransportFailed("transport terminated")))
    failed.sessionStopped()

    val silentTransport = new ScriptedTransport
    val silent = startupBridge(silentTransport, McpBridgeProfile.base)
    assertEquals(silent.awaitReady(positiveDuration(0.01)),
      Left(BridgeFailure.TimedOut(PideBridgePolicy.PositiveDuration.duration(positiveDuration(0.01)))))
    silent.sessionStopped()
  }

  test("a timed-out startup hello cannot reopen the bridge through a late reply") {
    val transport = new ScriptedTransport
    val control = startupBridge(transport, McpBridgeProfile.base)
    val waiting = Future.fork(control.awaitReady(positiveDuration(0.01)))
    transport.awaitSent(1)
    val helloId = requestProperty(transport.sent.head, "id")
    assertEquals(waiting.join,
      Left(BridgeFailure.TimedOut(PideBridgePolicy.PositiveDuration.duration(positiveDuration(0.01)))))

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

  spec_test("stop sends ordered cancellation then one drain and shares its acknowledgement",
      covers = List("pide_bridge#T3", "pide_bridge#T6")) {
    val diagnostics = collection.mutable.ListBuffer.empty[String]
    val deadlines = new ManualDeadlineScheduler
    val transport = new ScriptedTransport
    val control = bridge(transport, deadlines = deadlines, drainTimeoutSeconds = 1.0,
      diagnostics = message => diagnostics.synchronized { diagnostics += message })

    val call = Future.fork(control.call(TextOperation("op", "request"), NeverCancelled))
    transport.awaitSent(1)
    val callId = requestProperty(transport.sent.head, "id")

    val firstStop = Future.fork(control.beginStop())
    val secondStop = Future.fork(control.beginStop())
    transport.awaitSent(3)
    assertEquals(transport.sent.map(requestProperty(_, "kind")),
      Vector("call", "cancel", "drain"))
    assertEquals(requestProperty(transport.sent(1), "id"), callId)
    val drainId = requestProperty(transport.sent(2), "id")
    assertNotEquals(drainId, callId)
    assertEquals(call.join, Left(BridgeFailure.SessionStopped))
    assertEquals(control.pendingCount, 0)
    assertEquals(control.deadlineCount, 0)
    assert(deadlines.isShutdown)
    assert(control.isStopping)
    assert(!transport.isClosed)

    deliverResult(transport, callId, "op", "late")
    deliverDrain(transport, "foreign-drain")
    assert(control.isStopping)
    deliverDrain(transport, drainId)

    assertEquals(firstStop.join, BridgeDrainOutcome.Acknowledged)
    assertEquals(secondStop.join, BridgeDrainOutcome.Acknowledged)
    assert(control.isStopped)
    assert(transport.isClosed)
    assertEquals(transport.sent.count(requestProperty(_, "kind") == "drain"), 1)
    assertEquals(control.call(TextOperation("op", "post-stop"), NeverCancelled),
      Left(BridgeFailure.SessionStopped))
    assertEquals(control.beginStop(), BridgeDrainOutcome.Acknowledged)
    assertEquals(transport.sent.length, 3)
    assert(diagnostics.synchronized {
      diagnostics.exists(_.contains("Unowned PIDE bridge drain acknowledgement foreign-drain")) &&
        diagnostics.exists(_.contains("Unowned PIDE bridge reply"))
    })
    control.sessionStopped()
  }

  spec_test("a re-entrant drain acknowledgement remains first after send throws",
      covers = List("pide_bridge#T6")) {
    val diagnostics = collection.mutable.ListBuffer.empty[String]
    val transport = new ScriptedTransport
    val control = bridge(transport, drainTimeoutSeconds = 1.0,
      diagnostics = message => diagnostics.synchronized { diagnostics += message })

    transport.onNextSend { message =>
      assertEquals(requestProperty(message, "kind"), "drain")
      deliverDrain(transport, requestProperty(message, "id"))
      throw new RuntimeException("send returned an error after acknowledgement")
    }

    assertEquals(control.beginStop(), BridgeDrainOutcome.Acknowledged)
    assertEquals(control.beginStop(), BridgeDrainOutcome.Acknowledged)
    assert(control.isStopped)
    assert(transport.isClosed)
    assertEquals(transport.sent.count(requestProperty(_, "kind") == "drain"), 1)
    assert(diagnostics.synchronized {
      diagnostics.exists(_.contains("Ignored PIDE bridge drain failure after terminal outcome"))
    })
    control.sessionStopped()
  }

  spec_test("session termination is reported only after session stop succeeds",
      covers = List("pide_bridge#T6")) {
    var reported = false
    interceptMessage[RuntimeException]("session stop failed") {
      MCP_Session.stopAndReportSessionTermination(
        () => throw new RuntimeException("session stop failed"),
        () => reported = true)
    }
    assert(!reported, "failed session termination was reported as proof of Stopped")

    var order = List.empty[String]
    MCP_Session.stopAndReportSessionTermination(
      () => order :+= "session-stop",
      () => order :+= "bridge-stopped")
    assertEquals(order, List("session-stop", "bridge-stopped"))
  }

  spec_test("drain timeout requires session termination before Stopped",
      covers = List("pide_bridge#T6")) {
    val diagnostics = collection.mutable.ListBuffer.empty[String]
    val deadlines = new ManualDeadlineScheduler
    val transport = new ScriptedTransport
    val control = bridge(transport, deadlines = deadlines, drainTimeoutSeconds = 0.01,
      diagnostics = message => diagnostics.synchronized { diagnostics += message })

    val outcome = control.beginStop()
    outcome match {
      case BridgeDrainOutcome.Failed(BridgeFailure.TimedOut(delay)) =>
        assertEquals(delay.toMillis, 10L)
      case other => fail("expected timed-out drain, got " + other)
    }
    assertEquals(transport.sent.map(requestProperty(_, "kind")), Vector("drain"))
    assert(control.isStopping)
    assert(!control.isStopped)
    assert(!transport.isClosed)
    assert(deadlines.isShutdown)
    assertEquals(control.beginStop(), outcome)
    assertEquals(transport.sent.length, 1)

    deliverDrain(transport, requestProperty(transport.sent.head, "id"))
    assert(control.isStopping)
    assert(!transport.isClosed)
    assert(diagnostics.synchronized {
      diagnostics.exists(_.contains("Unowned PIDE bridge drain acknowledgement"))
    })

    control.sessionStopped()
    assert(control.isStopped)
    assert(transport.isClosed)
    assertEquals(control.beginStop(), outcome)
  }

  test("correlated drain protocol failures require the forced-stop fallback") {
    def rejected(deliver: (ScriptedTransport, String) => Unit): Unit = {
      val transport = new ScriptedTransport
      val control = bridge(transport, drainTimeoutSeconds = 1.0)
      val stopping = Future.fork(control.beginStop())
      transport.awaitSent(1)
      val id = requestProperty(transport.sent.head, "id")
      deliver(transport, id)
      stopping.join match {
        case BridgeDrainOutcome.Failed(BridgeFailure.ProtocolError(_)) => ()
        case other => fail("expected correlated drain ProtocolError, got " + other)
      }
      assert(control.isStopping)
      assert(!control.isStopped)
      assert(!transport.isClosed)
      control.sessionStopped()
      assert(control.isStopped)
    }

    rejected((transport, id) =>
      deliverDrain(transport, id, status = "protocol_error",
        payload = XML.Encode.string("ML rejected drain")))
    rejected((transport, id) =>
      deliverDrain(transport, id, properties = List("extra" -> "invalid")))
    rejected((transport, id) => deliverResult(transport, id, "op", "wrong kind"))
  }

  spec_test("drain-shaped replies immediately reject an ordinary owner",
      covers = List("pide_bridge#T2")) {
    def rejected(deliver: (ScriptedTransport, String) => Unit): Unit = {
      val deadlines = new ManualDeadlineScheduler
      val transport = new ScriptedTransport
      val control = bridge(transport, deadlines = deadlines)
      val call = Future.fork(control.call(TextOperation("op", "request"), NeverCancelled))
      transport.awaitSent(1)
      val id = requestProperty(transport.sent.head, "id")
      deliver(transport, id)
      call.join match {
        case Left(BridgeFailure.ProtocolError(_)) => ()
        case other => fail("expected wrong-kind ProtocolError, got " + other)
      }
      transport.awaitSent(2)
      assertEquals(transport.sent.map(requestProperty(_, "kind")), Vector("call", "cancel"))
      assertEquals(control.pendingCount, 0)
      assertEquals(control.deadlineCount, 0)
      assertEquals(deadlines.pendingCount, 0)
      control.sessionStopped()
    }

    rejected((transport, id) => deliverDrain(transport, id))
    rejected((transport, id) => deliverDrain(transport, id, status = "protocol_error",
      payload = XML.Encode.string("wrong control kind")))
  }

  test("drain send and transport failures require the forced-stop fallback") {
    val sendTransport = new ScriptedTransport
    val sendBridge = bridge(sendTransport, drainTimeoutSeconds = 1.0)
    sendTransport.failNextSend("cannot send drain")
    sendBridge.beginStop() match {
      case BridgeDrainOutcome.Failed(BridgeFailure.TransportFailed(detail)) =>
        assert(detail.contains("cannot send drain"))
      case other => fail("expected drain send failure, got " + other)
    }
    assert(sendBridge.isStopping)
    assert(!sendTransport.isClosed)
    sendBridge.sessionStopped()

    val failedTransport = new ScriptedTransport
    val failedBridge = bridge(failedTransport, drainTimeoutSeconds = 1.0)
    val stopping = Future.fork(failedBridge.beginStop())
    failedTransport.awaitSent(1)
    failedTransport.failTransport("drain link lost")
    stopping.join match {
      case BridgeDrainOutcome.Failed(BridgeFailure.TransportFailed(_)) => ()
      case other => fail("expected drain transport failure, got " + other)
    }
    assert(failedBridge.isStopping)
    assert(!failedBridge.isStopped)
    failedBridge.sessionStopped()
    assert(failedBridge.isStopped)
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
    assertEquals(timedOut.join,
      Left(BridgeFailure.TimedOut(PideBridgePolicy.PositiveDuration.duration(positiveDuration(5.0)))))
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

  test("payload measurement records every operation request and reply plus named large reply categories") {
    val corpus = MCP_Pide_Payload_Measure.corpus
    assertEquals(corpus.bridge_revision, PideBridgeV1.revision)
    assertEquals(
      corpus.cases.filter(_.direction == "request").map(_.operation).toSet,
      McpBridgeOperations.operationNames)
    assertEquals(
      corpus.cases.filter(_.direction == "reply").map(_.operation).toSet,
      McpBridgeOperations.operationNames)
    assertEquals(
      corpus.cases.filter(_.direction == "reply").map(_.category).toSet,
      Set("operation_reply", "tool_catalog", "theory_source_text", "documentation_text",
        "structured_prover_output"))
    assert(corpus.cases.forall(_.bytes > 0))
  }

  test("payload measurement is deterministic and maxima are derived from cases") {
    val first = MCP_Pide_Payload_Measure.corpus
    val second = MCP_Pide_Payload_Measure.corpus
    assertEquals(MCP_Pide_Payload_Measure.json(first), MCP_Pide_Payload_Measure.json(second))
    assertEquals(first.request_bytes,
      first.cases.filter(_.direction == "request").map(_.bytes).max)
    assertEquals(first.reply_bytes,
      first.cases.filter(_.direction == "reply").map(_.bytes).max)
    assertEquals(first.maxRequestBytes, MCP_Pide_Payload_Measure.default_for(first.request_bytes))
    assertEquals(first.maxReplyBytes, MCP_Pide_Payload_Measure.default_for(first.reply_bytes))
    assertEquals(first.maxRequestBytes, 262144L)
    assertEquals(first.maxReplyBytes, 1048576L)
  }

  test("payload measurement retains reviewed large source-text bases through serialization") {
    val cases = MCP_Pide_Payload_Measure.corpus.cases
    val large_request = cases.find(_.name == "request.ir_large_source_text")
      .getOrElse(fail("missing large request fixture"))
    val theory_reply = cases.find(_.name == "reply.theory_source_text")
      .getOrElse(fail("missing theory-source reply fixture"))

    assertEquals(large_request.source_text_bytes,
      Some(MCP_Pide_Payload_Measure.large_request_source_text_bytes))
    assertEquals(large_request.operation, "ir")
    assertEquals(theory_reply.source_text_bytes,
      Some(MCP_Pide_Payload_Measure.theory_source_reply_bytes))
    assert(large_request.bytes >= MCP_Pide_Payload_Measure.large_request_source_text_bytes)
    assert(theory_reply.bytes >= MCP_Pide_Payload_Measure.theory_source_reply_bytes)
  }

  test("payload measurement rejects incomplete request and reply operation coverage") {
    val cases = MCP_Pide_Payload_Measure.corpus.cases
    val missing_request = cases.filterNot(measured =>
      measured.direction == "request" && measured.operation == "theories")
    val missing_reply = cases.filterNot(measured =>
      measured.direction == "reply" && measured.operation == "theories")

    intercept[IllegalArgumentException] { MCP_Pide_Payload_Measure.validate(missing_request) }
    intercept[IllegalArgumentException] { MCP_Pide_Payload_Measure.validate(missing_reply) }
  }

  test("payload measurement check mode rejects stale artifacts without rewriting them") {
    val directory = Files.createTempDirectory("pide-payload-measure-")
    val artifact = directory.resolve("pide_bridge_payloads.json")
    val path = Path.explode(artifact.toString)
    val expected = MCP_Pide_Payload_Measure.json()
    try {
      MCP_Pide_Payload_Measure.write(path, expected)
      MCP_Pide_Payload_Measure.check(path, expected)
      val stale = expected + "stale\n"
      Files.writeString(artifact, stale, StandardCharsets.UTF_8)
      intercept[IllegalArgumentException] { MCP_Pide_Payload_Measure.check(path, expected) }
      assertEquals(Files.readString(artifact, StandardCharsets.UTF_8), stale)
    }
    finally {
      Files.deleteIfExists(artifact)
      Files.deleteIfExists(directory)
    }
  }
}
