/*  Title:      mcp_test/src/mcp_connection_kernel_tests.scala

Checkpoint-5 contracts for validated policy, revision classification,
lifecycle control, request ownership, and deterministic scheduling without
Isabelle.
*/

package isabelle.mcp

import isabelle._
import isabelle.mcp.application.McpApplication
import isabelle.mcp.connection._
import isabelle.mcp.protocol.JsonRpc
import isabelle.mcp.transport.ScriptedDataPlane


class MCP_Connection_Kernel_Tests extends MCP_Suite {
  private val rules = new Mcp2025RevisionRules

  private def checked[A](value: Either[String, A]): A =
    value.fold(message => fail(message), identity)

  private def requestId(value: Long): RequestId = RequestId.integer(value)

  private def policy(maxInFlight: Int = 2): ConnectionPolicy =
    ConnectionPolicy(
      revision = ProtocolRevision.V2025_03_26,
      admission = ConnectionPolicy.AdmissionPolicy(
        maxInFlight = checked(ConnectionPolicy.MaxInFlight.checked(maxInFlight))),
      timing = ConnectionPolicy.TimingPolicy(
        requestTimeout = checked(ConnectionPolicy.RequestTimeout.checked(5.0)),
        shutdownDrain = checked(ConnectionPolicy.ShutdownDrain.checked(0.0))))

  private def request(
    id: Option[JSON.T], method: String, params: Option[JSON.Object.T] = None): JSON.Object.T = {
    var value = JSON.Object("jsonrpc" -> "2.0", "method" -> method)
    for (valueId <- id) value += ("id" -> valueId)
    for (valueParams <- params) value += ("params" -> valueParams)
    value
  }

  private def kernel(lines: List[String]): ConnectionKernel =
    ConnectionKernel(
      policy = policy(),
      dataPlane = new ScriptedDataPlane(lines),
      revisionRules = rules,
      scheduler = new DeterministicSequentialScheduler,
      registry = new RequestRegistry(RequestRegistry.InvariantViolationPolicy.FailFast))

  private def admitted(
    registry: RequestRegistry,
    id: RequestId,
    kind: RequestRegistry.AdmissionKind
  ): RequestRegistry.Admitted =
    registry.admit(id, kind) match {
      case value: RequestRegistry.Admitted => value
      case other => fail("expected admission, got " + other)
    }

  spec_test("connection policy validates leaves and snapshots options once",
      verifies = List("connection_kernel#A1")) {
    assertEquals(ConnectionPolicy.MaxInFlight.checked(0), Left("maxInFlight must be positive"))
    assertEquals(ConnectionPolicy.RequestTimeout.checked(0.0),
      Left("requestTimeout must be finite and positive"))
    assertEquals(ConnectionPolicy.ShutdownDrain.checked(-0.1),
      Left("shutdownDrain must be finite and non-negative"))

    val initial = Options.init() + "mcp_max_in_flight=2" +
      "mcp_request_timeout=5.0" + "mcp_shutdown_drain=0.0"
    val snapshot = checked(ConnectionPolicy.fromOptions(initial))
    val changed = initial + "mcp_max_in_flight=9"
    val kernel0 = ConnectionKernel(
      policy = snapshot,
      dataPlane = new ScriptedDataPlane(Nil),
      revisionRules = rules,
      scheduler = new DeterministicSequentialScheduler,
      registry = new RequestRegistry(RequestRegistry.InvariantViolationPolicy.FailFast))

    assertEquals(ConnectionPolicy.MaxInFlight.value(kernel0.policy.admission.maxInFlight), 2)
    assertEquals(ConnectionPolicy.MaxInFlight.value(
      checked(ConnectionPolicy.fromOptions(changed)).admission.maxInFlight), 9)
    assert(kernel0.policy eq snapshot, "kernel must retain the construction-time policy snapshot")
  }

  test("MCP 2025-03-26 rules classify valid and invalid wire messages") {
    val initialize = request(Some(1), "initialize",
      Some(JSON.Object("protocolVersion" -> ProtocolRevision.V2025_03_26.value)))
    assertEquals(rules.classify(JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(initialize))),
      RevisionRules.Initialize(requestId(1), ProtocolRevision.V2025_03_26.value))
    assertEquals(rules.classify(JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(
      request(Some(1), "initialize", Some(JSON.Object("protocolVersion" -> "2024-11-05")))))),
      RevisionRules.Initialize(requestId(1), "2024-11-05"))
    assertEquals(rules.classify(JsonRpc.Inbound.Malformed("{")),
      RevisionRules.Invalid(RevisionRules.NullReply, RevisionRules.ParseError, "Parse error"))
    assertEquals(rules.classify(JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(
      JSON.Object("jsonrpc" -> "1.0", "id" -> 1, "method" -> "ping")))),
      RevisionRules.Invalid(RevisionRules.ReplyId(requestId(1)), RevisionRules.InvalidRequest,
        "jsonrpc must be 2.0"))
    assertEquals(rules.classify(JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(
      JSON.Object("jsonrpc" -> "1.0", "method" -> "ping")))),
      RevisionRules.Invalid(RevisionRules.NoReply, RevisionRules.InvalidRequest,
        "jsonrpc must be 2.0"))
    assertEquals(rules.classify(JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(
      JSON.Object("jsonrpc" -> "2.0", "id" -> null, "method" -> "ping")))),
      RevisionRules.Invalid(RevisionRules.NullReply, RevisionRules.InvalidRequest,
        "id must be a string or exactly representable integer"))
    assertEquals(rules.classify(JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(
      request(Some(2), "tools/call")))),
      RevisionRules.Invalid(RevisionRules.ReplyId(requestId(2)), RevisionRules.InvalidParams, "Missing tool name"))
    assertEquals(rules.classify(JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(
      request(None, "notifications/initialized")))), RevisionRules.Initialized)
    val resourceRead = JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(
      request(Some(3), "resources/read", Some(JSON.Object("uri" -> "isabelle://session")))))
    assertEquals(rules.classify(resourceRead),
      RevisionRules.Application(McpApplication.Operation.ResourcesRead("isabelle://session"), requestId(3)))
    assertEquals(rules.classify(JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(
      request(None, "tools/list")))), RevisionRules.Ignored)
    assertEquals(rules.classify(JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(
      request(None, "ping")))), RevisionRules.Ignored)
    val cancelled = JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(
      request(None, "notifications/cancelled",
        Some(JSON.Object("requestId" -> "slow", "reason" -> "user left")))))
    assertEquals(rules.classify(cancelled),
      RevisionRules.Cancelled(RequestId.string("slow"), Some("user left")))
    val cancelledWithWireId = JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(
      request(Some(8), "notifications/cancelled", Some(JSON.Object("requestId" -> 2)))))
    val cancelledWithFraction = JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(
      request(None, "notifications/cancelled", Some(JSON.Object("requestId" -> 1.5)))))
    val cancelledWithBadReason = JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(
      request(None, "notifications/cancelled", Some(JSON.Object("requestId" -> 2, "reason" -> 3)))))
    assertEquals(rules.classify(cancelledWithWireId), RevisionRules.Ignored)
    assertEquals(rules.classify(cancelledWithFraction), RevisionRules.Ignored)
    assertEquals(rules.classify(cancelledWithBadReason), RevisionRules.Ignored)
    assertEquals(rules.classify(JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(
      request(None, "not/a/method")))), RevisionRules.Ignored)
    assertEquals(rules.classify(JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Batch(Nil))),
      RevisionRules.Invalid(RevisionRules.NullReply, RevisionRules.InvalidRequest,
        "Empty batch is invalid"))
    assertEquals(rules.classify(JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Batch(List(initialize)))),
      RevisionRules.Invalid(RevisionRules.NullReply, RevisionRules.InvalidRequest,
        "initialize must be a standalone request"))
    assertEquals(RevisionRules.error(4, RevisionRules.InvalidParams, "Missing resource uri"),
      JSON.Object("jsonrpc" -> "2.0", "id" -> 4,
        "error" -> JSON.Object("code" -> RevisionRules.InvalidParams,
          "message" -> "Missing resource uri")))
  }

  spec_test("lifecycle accepts only the MCP 2025-03-26 transition table",
      covers = List("connection_kernel#T2")) {
    val initialize = JSON.Format(request(Some(1), "initialize",
      Some(JSON.Object("protocolVersion" -> ProtocolRevision.V2025_03_26.value))))
    val initialized = JSON.Format(request(None, "notifications/initialized"))
    val earlyOperation = JSON.Format(request(Some(3), "tools/list"))
    val initializingPing = JSON.Format(request(Some(2), "ping"))
    val awaitingPing = JSON.Format(request(Some(4), "ping"))
    val readyOperation = JSON.Format(request(Some(5), "tools/list"))
    val connection = kernel(List(
      earlyOperation, initialize, initializingPing, awaitingPing, initialized, readyOperation))

    assertEquals(connection.receive().map(_.decision),
      Some(ConnectionLifecycle.Rejected(RevisionRules.ReplyId(requestId(3)), RevisionRules.InvalidRequest,
        "ordinary operations are only valid in Ready")))
    assertEquals(connection.phase, ConnectionLifecycle.Fresh)
    assertEquals(connection.admit(RevisionRules.Initialized).decision,
      ConnectionLifecycle.Rejected(RevisionRules.NoReply, RevisionRules.InvalidRequest,
        "notifications/initialized is only valid after initialize"))
    assertEquals(connection.admit(RevisionRules.Ignored).decision, ConnectionLifecycle.Ignored)
    assertEquals(connection.receive().map(_.decision), Some(ConnectionLifecycle.InitializeAccepted(
      requestId(1), ProtocolRevision.V2025_03_26.value)))
    assertEquals(connection.phase, ConnectionLifecycle.Initializing)
    assertEquals(connection.receive().map(_.decision), Some(ConnectionLifecycle.Rejected(RevisionRules.ReplyId(requestId(2)),
      RevisionRules.InvalidRequest,
      "ping is not valid in Initializing")))
    connection.initializeCompleted()
    assertEquals(connection.phase, ConnectionLifecycle.AwaitingInitialized)
    assertEquals(connection.receive().map(_.decision), Some(ConnectionLifecycle.PingAccepted(requestId(4))))
    assertEquals(connection.receive().map(_.decision), Some(ConnectionLifecycle.InitializedAccepted))
    assertEquals(connection.phase, ConnectionLifecycle.Ready)
    assertEquals(connection.receive().map(_.decision), Some(ConnectionLifecycle.OperationAccepted(
      RevisionRules.Application(McpApplication.Operation.ToolsList, requestId(5)))))

    connection.beginClosing()
    assertEquals(connection.phase, ConnectionLifecycle.Closing)
    assertEquals(connection.receive().map(_.decision), None)
    assertEquals(connection.admit(RevisionRules.Application(McpApplication.Operation.ToolsList, requestId(6))).decision,
      ConnectionLifecycle.Rejected(RevisionRules.ReplyId(requestId(6)), RevisionRules.InvalidRequest,
        "ordinary operations are only valid in Ready"))
    connection.finishClosing()
    assertEquals(connection.phase, ConnectionLifecycle.Closed)
    assertEquals(connection.admit(RevisionRules.Application(McpApplication.Operation.ToolsList, requestId(7))).decision,
      ConnectionLifecycle.Rejected(RevisionRules.ReplyId(requestId(7)), RevisionRules.InvalidRequest,
        "ordinary operations are only valid in Ready"))

    val unsupported = new ConnectionLifecycle
    assertEquals(unsupported.admit(RevisionRules.Initialize(requestId(6), "2024-11-05")),
      ConnectionLifecycle.InitializeAccepted(requestId(6), "2024-11-05"))
    assertEquals(policy().revision, ProtocolRevision.V2025_03_26)
  }

  test("deterministic schedulers execute sequentially with no waiting queue") {
    var ran = List.empty[String]
    val inline = new DeterministicSequentialScheduler
    assertEquals(inline.submit(() => ran = ran :+ "inline"), RequestScheduler.Accepted)
    assertEquals(ran, List("inline"))

    val manual = new ManualSequentialScheduler
    assertEquals(manual.submit(() => ran = ran :+ "first"), RequestScheduler.Accepted)
    assert(manual.hasPending, "manual scheduler should hold its one explicitly controlled task")
    assertEquals(manual.submit(() => ran = ran :+ "second"), RequestScheduler.Rejected)
    assert(manual.runPending(), "expected pending task")
    assertEquals(ran, List("inline", "first"))
    assert(!manual.hasPending, "running the task must release the slot")
    assertEquals(manual.submit(() => ran = ran :+ "third"), RequestScheduler.Accepted)
    assert(manual.runPending(), "expected replacement task")
    assertEquals(ran, List("inline", "first", "third"))
  }

  test("request ids normalize finite safe integer values while rejecting unsafe values") {
    val string = checked(RequestId.fromJson("alpha"))
    val integer = checked(RequestId.fromJson(7))
    assertEquals(string, RequestId.string("alpha"))
    assertEquals(string.json, "alpha")
    assertEquals(integer, RequestId.integer(7))
    assertEquals(integer.json, 7)
    assertEquals(RequestId.fromJson(null), Left("id must be a string or exactly representable integer"))
    val decodedInteger = checked(RequestId.fromJson(7.0))
    assertEquals(decodedInteger, RequestId.integer(7))
    assertEquals(decodedInteger.json, 7L)
    assertEquals(RequestId.fromJson(7.5), Left("id must be a string or exactly representable integer"))
    assertEquals(RequestId.fromJson(9007199254740992.0),
      Left("id must be a string or exactly representable integer"))
    intercept[IllegalArgumentException] {
      RequestId.integer(9007199254740992L)
    }
  }

  test("request registry owns terminal dispositions and releases ordinary capacity once") {
    import RequestRegistry.{AdmissionKind, TerminalDisposition, WorkerDisposition}

    val workerDispositions = List(
      WorkerDisposition.Success -> TerminalDisposition.Success,
      WorkerDisposition.ApplicationError -> TerminalDisposition.ApplicationError,
      WorkerDisposition.Exception -> TerminalDisposition.Exception)
    workerDispositions.zipWithIndex.foreach { case ((worker, terminal), index) =>
      val registry = new RequestRegistry(RequestRegistry.InvariantViolationPolicy.FailFast)
      val accepted = admitted(registry, requestId(index + 1), AdmissionKind.Ordinary)
      assertEquals(registry.snapshot.activeCapacity, 1)
      assertEquals(registry.complete(accepted.token, worker),
        RequestRegistry.Completed(RequestRegistry.Tombstone(requestId(index + 1),
          AdmissionKind.Ordinary, terminal, None, capacityReleased = true)))
      assertEquals(registry.snapshot.activeCapacity, 0)
      assertEquals(registry.snapshot.activeIds, Set.empty)
      assertEquals(registry.admit(requestId(index + 1), AdmissionKind.Ordinary),
        RequestRegistry.DuplicateId(requestId(index + 1)))
    }

    val controls = new RequestRegistry(RequestRegistry.InvariantViolationPolicy.FailFast)
    admitted(controls, requestId(20), AdmissionKind.Initialize)
    admitted(controls, requestId(21), AdmissionKind.Ping)
    admitted(controls, requestId(22), AdmissionKind.Control)
    assertEquals(controls.snapshot.activeCapacity, 0)
  }

  test("cancellation wins once, exposes the application signal, and suppresses one stale completion") {
    import RequestRegistry.{AdmissionKind, TerminalDisposition, WorkerDisposition}

    val policy = new RequestRegistry.InvariantViolationPolicy.MarkBroken
    val registry = new RequestRegistry(policy)
    val ordinary = admitted(registry, requestId(30), AdmissionKind.Ordinary)
    val initialize = admitted(registry, requestId(31), AdmissionKind.Initialize)
    assert(!ordinary.cancellation.isCancelled)
    assertEquals(registry.cancel(requestId(30), Some("client closed pane")),
      RequestRegistry.Cancelled(RequestRegistry.Tombstone(requestId(30), AdmissionKind.Ordinary,
        TerminalDisposition.ClientCancelled, Some("client closed pane"), capacityReleased = true)))
    assert(ordinary.cancellation.isCancelled)
    assertEquals(registry.snapshot.activeCapacity, 0)
    assertEquals(registry.cancel(requestId(31), None), RequestRegistry.CancellationIgnored)
    assertEquals(registry.complete(ordinary.token, WorkerDisposition.Success),
      RequestRegistry.LateIgnored(RequestRegistry.Tombstone(requestId(30), AdmissionKind.Ordinary,
        TerminalDisposition.ClientCancelled, Some("client closed pane"), capacityReleased = true)))
    assert(registry.complete(ordinary.token, WorkerDisposition.Success)
      .isInstanceOf[RequestRegistry.InvariantViolation])
    assert(policy.isBroken)
  }

  test("timeout and shutdown cancel owned work without consuming late worker completion") {
    import RequestRegistry.{AdmissionKind, WorkerDisposition}

    val registry = new RequestRegistry(new RequestRegistry.InvariantViolationPolicy.MarkBroken)
    val succeeded = admitted(registry, requestId(35), AdmissionKind.Ordinary)
    registry.complete(succeeded.token, WorkerDisposition.Success)
    assert(registry.timeout(succeeded.token).isInstanceOf[RequestRegistry.TimerIgnored])

    val cancelled = admitted(registry, requestId(36), AdmissionKind.Ordinary)
    registry.cancel(requestId(36), None)
    assert(registry.timeout(cancelled.token).isInstanceOf[RequestRegistry.TimerIgnored])
    assert(registry.complete(cancelled.token, WorkerDisposition.Success)
      .isInstanceOf[RequestRegistry.LateIgnored])
    assert(registry.complete(cancelled.token, WorkerDisposition.Success)
      .isInstanceOf[RequestRegistry.InvariantViolation])

    val closingRegistry = new RequestRegistry(new RequestRegistry.InvariantViolationPolicy.MarkBroken)
    val timed = admitted(closingRegistry, requestId(37), AdmissionKind.Ordinary)
    assert(!timed.cancellation.isCancelled)
    assertEquals(closingRegistry.timeout(timed.token),
      RequestRegistry.Completed(RequestRegistry.Tombstone(requestId(37), AdmissionKind.Ordinary,
        RequestRegistry.TerminalDisposition.Timeout, None, capacityReleased = true)))
    assert(timed.cancellation.isCancelled)

    val shuttingDown = admitted(closingRegistry, requestId(38), AdmissionKind.Ordinary)
    assertEquals(closingRegistry.shutdown(), List(RequestRegistry.Tombstone(requestId(38),
      AdmissionKind.Ordinary, RequestRegistry.TerminalDisposition.Shutdown, None,
      capacityReleased = true)))
    assert(shuttingDown.cancellation.isCancelled)
    assert(closingRegistry.complete(shuttingDown.token, WorkerDisposition.Success)
      .isInstanceOf[RequestRegistry.LateIgnored])
  }

  test("kernel reserves rejected request IDs before lifecycle admission") {
    val connection = kernel(Nil)
    assertEquals(connection.admit(RevisionRules.Application(
      McpApplication.Operation.ToolsList, requestId(50))).decision,
      ConnectionLifecycle.Rejected(RevisionRules.ReplyId(requestId(50)), RevisionRules.InvalidRequest,
        "ordinary operations are only valid in Ready"))
    assertEquals(connection.admit(RevisionRules.Initialize(
      requestId(50), ProtocolRevision.V2025_03_26.value)).decision,
      ConnectionLifecycle.Rejected(RevisionRules.ReplyId(requestId(50)), RevisionRules.InvalidRequest,
        "Request id must not be reused during a connection"))

    assertEquals(connection.admit(RevisionRules.Invalid(
      RevisionRules.ReplyId(requestId(51)), RevisionRules.InvalidRequest, "bad request")).decision,
      ConnectionLifecycle.Rejected(RevisionRules.ReplyId(requestId(51)), RevisionRules.InvalidRequest,
        "bad request"))
    assertEquals(connection.admit(RevisionRules.Initialize(
      requestId(51), ProtocolRevision.V2025_03_26.value)).decision,
      ConnectionLifecycle.Rejected(RevisionRules.ReplyId(requestId(51)), RevisionRules.InvalidRequest,
        "Request id must not be reused during a connection"))

    val initializedConnection = kernel(List(JSON.Format(request(Some(52), "initialize",
      Some(JSON.Object("protocolVersion" -> ProtocolRevision.V2025_03_26.value))))))
    initializedConnection.receive() match {
      case Some(ConnectionKernel.Accepted(ConnectionLifecycle.InitializeAccepted(id, _), admitted)) =>
        assertEquals(id, requestId(52))
        assertEquals(admitted.id, requestId(52))
        assert(initializedConnection.registry.snapshot.activeIds.contains(requestId(52)))
      case other => fail("expected an owned initialize admission, got " + other)
    }

    val safeIdLine = """{"jsonrpc":"2.0","id":7,"method":"ping"}"""
    val decodedSafe = JSON.Format.unapply(safeIdLine).flatMap(JSON.value(_, "id"))
    assert(decodedSafe.exists(_.isInstanceOf[Double]), "Isabelle JSON decodes numeric tokens as Double")
    assertEquals(decodedSafe, Some(7.0))
    val safeConnection = kernel(List(safeIdLine))
    assertEquals(safeConnection.receive().map(_.decision),
      Some(ConnectionLifecycle.Rejected(RevisionRules.ReplyId(requestId(7)), RevisionRules.InvalidRequest,
        "ping is not valid in Fresh")))

    val fractionalIdLine = """{"jsonrpc":"2.0","id":7.5,"method":"ping"}"""
    val fractionalConnection = kernel(List(fractionalIdLine))
    assertEquals(fractionalConnection.receive().map(_.decision),
      Some(ConnectionLifecycle.Rejected(RevisionRules.NullReply, RevisionRules.InvalidRequest,
        "id must be a string or exactly representable integer")))

    val largeIdLine = """{"jsonrpc":"2.0","id":9007199254740993,"method":"ping"}"""
    val decodedLarge = JSON.Format.unapply(largeIdLine).flatMap(JSON.value(_, "id"))
    assert(decodedLarge.exists(_.isInstanceOf[Double]), "Isabelle JSON decodes numeric tokens as Double")
    assertEquals(decodedLarge, Some(9007199254740992.0))
    val largeConnection = kernel(List(largeIdLine))
    assertEquals(largeConnection.receive().map(_.decision),
      Some(ConnectionLifecycle.Rejected(RevisionRules.NullReply, RevisionRules.InvalidRequest,
        "id must be a string or exactly representable integer")))
  }

  test("registry detects foreign and duplicate tokens and applies every invariant reaction") {
    import RequestRegistry.{AdmissionKind, WorkerDisposition}

    val failFast = new RequestRegistry(RequestRegistry.InvariantViolationPolicy.FailFast)
    val foreign = admitted(
      new RequestRegistry(RequestRegistry.InvariantViolationPolicy.FailFast), requestId(40), AdmissionKind.Ping)
    intercept[IllegalStateException] {
      failFast.complete(foreign.token, WorkerDisposition.Success)
    }

    var closed = false
    var logs = List.empty[String]
    val logAndClose = new RequestRegistry.InvariantViolationPolicy.LogAndClose(
      close = () => closed = true,
      log = message => logs = logs :+ message)
    val logged = new RequestRegistry(logAndClose)
    val loggedRequest = admitted(logged, requestId(41), AdmissionKind.Ping)
    logged.complete(loggedRequest.token, WorkerDisposition.Success)
    assert(logged.complete(loggedRequest.token, WorkerDisposition.Success)
      .isInstanceOf[RequestRegistry.InvariantViolation])
    assert(closed)
    assertEquals(logs.length, 1)

    val markBroken = new RequestRegistry.InvariantViolationPolicy.MarkBroken
    val diagnostic = new RequestRegistry(markBroken)
    assert(diagnostic.complete(foreign.token, WorkerDisposition.Success)
      .isInstanceOf[RequestRegistry.InvariantViolation])
    assert(markBroken.violation.isDefined)
    assertEquals(diagnostic.admit(requestId(42), AdmissionKind.Ordinary),
      RequestRegistry.Broken(requestId(42)))
    assert(admitted(diagnostic, requestId(43), AdmissionKind.Diagnostic).token != null)
  }
}
