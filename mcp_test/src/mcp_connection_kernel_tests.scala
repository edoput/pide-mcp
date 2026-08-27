/*  Title:      mcp_test/src/mcp_connection_kernel_tests.scala

Checkpoint-6 contracts for validated policy, revision classification,
lifecycle control, request ownership, direct-handoff scheduling, and
connection-owned wire completion without Isabelle.
*/

package isabelle.mcp

import isabelle._
import isabelle.mcp.application.McpApplication
import isabelle.mcp.connection._
import isabelle.mcp.protocol.JsonRpc
import isabelle.mcp.transport.{DataPlane, ScriptedDataPlane}


class MCP_Connection_Kernel_Tests extends MCP_Suite {
  private val rules = new Mcp2025RevisionRules

  private def checked[A](value: Either[String, A]): A =
    value.fold(message => fail(message), identity)

  private def requestId(value: Long): RequestId = RequestId.string(value.toString)

  private val application = new McpApplication {
    def execute(
      operation: McpApplication.Operation,
      cancellation: McpApplication.Cancellation
    ): McpApplication.Outcome = McpApplication.Outcome.Result(JSON.Object())
  }

  private val serverInfo = ConnectionKernel.ServerInfo("test-server", "test-version")

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
      scheduler = new DeterministicSequentialScheduler(2),
      registry = new RequestRegistry(RequestRegistry.InvariantViolationPolicy.FailFast),
      application = application,
      serverInfo = serverInfo)

  private def kernelWith(
    plane: ScriptedDataPlane,
    scheduler: RequestScheduler,
    maxInFlight: Int,
    app: McpApplication
  ): ConnectionKernel =
    ConnectionKernel(
      policy = policy(maxInFlight),
      dataPlane = plane,
      revisionRules = rules,
      scheduler = scheduler,
      registry = new RequestRegistry(RequestRegistry.InvariantViolationPolicy.FailFast),
      application = app,
      serverInfo = serverInfo)

  private def ready(connection: ConnectionKernel, id: Long = 500): Unit = {
    connection.handle(RevisionRules.Initialize(requestId(id), ProtocolRevision.V2025_03_26.value))
    connection.handle(RevisionRules.Initialized)
  }

  private def batch_values(plane: ScriptedDataPlane)(implicit loc: munit.Location): List[JSON.T] =
    JSON.Format.unapply(plane.written.last) match {
      case Some(values: List[_]) => values.asInstanceOf[List[JSON.T]]
      case other => fail("expected one aggregate JSON array, got " + other)
    }

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
      scheduler = new DeterministicSequentialScheduler(2),
      registry = new RequestRegistry(RequestRegistry.InvariantViolationPolicy.FailFast),
      application = application,
      serverInfo = serverInfo)

    assertEquals(ConnectionPolicy.MaxInFlight.value(kernel0.policy.admission.maxInFlight), 2)
    assertEquals(ConnectionPolicy.MaxInFlight.value(
      checked(ConnectionPolicy.fromOptions(changed)).admission.maxInFlight), 9)
    assert(kernel0.policy eq snapshot, "kernel must retain the construction-time policy snapshot")
  }

  test("MCP 2025-03-26 rules classify valid and invalid wire messages") {
    val initialize = request(Some("1"), "initialize",
      Some(JSON.Object("protocolVersion" -> ProtocolRevision.V2025_03_26.value)))
    assertEquals(rules.classify(JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(initialize))),
      RevisionRules.Initialize(requestId(1), ProtocolRevision.V2025_03_26.value))
    assertEquals(rules.classify(JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(
      request(Some("1"), "initialize", Some(JSON.Object("protocolVersion" -> "2024-11-05")))))),
      RevisionRules.Initialize(requestId(1), "2024-11-05"))
    assertEquals(rules.classify(JsonRpc.Inbound.Malformed("{")),
      RevisionRules.Invalid(RevisionRules.NullReply, RevisionRules.ParseError, "Parse error"))
    assertEquals(rules.classify(JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(
      JSON.Object("jsonrpc" -> "1.0", "id" -> "1", "method" -> "ping")))),
      RevisionRules.Invalid(RevisionRules.ReplyId(requestId(1)), RevisionRules.InvalidRequest,
        "jsonrpc must be 2.0"))
    assertEquals(rules.classify(JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(
      JSON.Object("jsonrpc" -> "1.0", "method" -> "ping")))),
      RevisionRules.Invalid(RevisionRules.NoReply, RevisionRules.InvalidRequest,
        "jsonrpc must be 2.0"))
    assertEquals(rules.classify(JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(
      JSON.Object("jsonrpc" -> "2.0", "id" -> null, "method" -> "ping")))),
      RevisionRules.Invalid(RevisionRules.NullReply, RevisionRules.InvalidRequest,
        "id must be a string"))
    assertEquals(rules.classify(JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(
      request(Some("2"), "tools/call")))),
      RevisionRules.Invalid(RevisionRules.ReplyId(requestId(2)), RevisionRules.InvalidParams, "Missing tool name"))
    assertEquals(rules.classify(JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(
      request(None, "notifications/initialized")))), RevisionRules.Initialized)
    val resourceRead = JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(
      request(Some("3"), "resources/read", Some(JSON.Object("uri" -> "isabelle://session")))))
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
      request(Some("8"), "notifications/cancelled", Some(JSON.Object("requestId" -> 2)))))
    val cancelledWithFraction = JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(
      request(None, "notifications/cancelled", Some(JSON.Object("requestId" -> 1.5)))))
    val cancelledWithNumericTarget = JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(
      request(None, "notifications/cancelled",
        Some(JSON.Object("requestId" -> 2, "reason" -> "client left")))))
    val cancelledWithBadReason = JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(
      request(None, "notifications/cancelled", Some(JSON.Object("requestId" -> 2, "reason" -> 3)))))
    assertEquals(rules.classify(cancelledWithWireId), RevisionRules.Ignored)
    assertEquals(rules.classify(cancelledWithFraction), RevisionRules.Ignored)
    assertEquals(rules.classify(cancelledWithNumericTarget), RevisionRules.Ignored)
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
    val initialize = JSON.Format(request(Some("1"), "initialize",
      Some(JSON.Object("protocolVersion" -> ProtocolRevision.V2025_03_26.value))))
    val initialized = JSON.Format(request(None, "notifications/initialized"))
    val earlyOperation = JSON.Format(request(Some("3"), "tools/list"))
    val initializingPing = JSON.Format(request(Some("2"), "ping"))
    val awaitingPing = JSON.Format(request(Some("4"), "ping"))
    val readyOperation = JSON.Format(request(Some("5"), "tools/list"))
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

  spec_test("deterministic schedulers execute sequentially with no waiting queue",
      verifies = List("connection_kernel#I2")) {
    var ran = List.empty[String]
    val inline = new DeterministicSequentialScheduler(1)
    val inlinePermit = inline.tryReserve().asInstanceOf[RequestScheduler.Reserved].permit
    assertEquals(inline.start(inlinePermit, () => ran = ran :+ "inline"), RequestScheduler.Started)
    assertEquals(ran, List("inline"))

    val manual = new ManualSequentialScheduler
    val firstPermit = manual.tryReserve().asInstanceOf[RequestScheduler.Reserved].permit
    assertEquals(manual.start(firstPermit, () => ran = ran :+ "first"), RequestScheduler.Started)
    assert(manual.hasPending, "manual scheduler should hold its one explicitly controlled task")
    assertEquals(manual.tryReserve(), RequestScheduler.Rejected)
    assert(manual.runPending(), "expected pending task")
    assertEquals(ran, List("inline", "first"))
    assert(!manual.hasPending, "running the task must release the slot")
    val thirdPermit = manual.tryReserve().asInstanceOf[RequestScheduler.Reserved].permit
    assertEquals(manual.start(thirdPermit, () => ran = ran :+ "third"), RequestScheduler.Started)
    assert(manual.runPending(), "expected replacement task")
    assertEquals(ran, List("inline", "first", "third"))
  }

  test("request ids accept strings and reject every non-string value") {
    val string = checked(RequestId.fromJson("alpha"))
    assertEquals(string, RequestId.string("alpha"))
    assertEquals(string.json, "alpha")
    List(null, 7, 7.0, 7.5, 9007199254740992.0).foreach { value =>
      assertEquals(RequestId.fromJson(value), Left("id must be a string"))
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

    val initializedConnection = kernel(List(JSON.Format(request(Some("52"), "initialize",
      Some(JSON.Object("protocolVersion" -> ProtocolRevision.V2025_03_26.value))))))
    initializedConnection.receive() match {
      case Some(ConnectionKernel.Accepted(ConnectionLifecycle.InitializeAccepted(id, _), admitted, _)) =>
        assertEquals(id, requestId(52))
        assertEquals(admitted.id, requestId(52))
        assert(initializedConnection.registry.snapshot.activeIds.contains(requestId(52)))
      case other => fail("expected an owned initialize admission, got " + other)
    }

    val stringIdLine = """{"jsonrpc":"2.0","id":"7","method":"ping"}"""
    val stringConnection = kernel(List(stringIdLine))
    assertEquals(stringConnection.receive().map(_.decision),
      Some(ConnectionLifecycle.Rejected(RevisionRules.ReplyId(requestId(7)), RevisionRules.InvalidRequest,
        "ping is not valid in Fresh")))

    val integerIdLine = """{"jsonrpc":"2.0","id":7,"method":"ping"}"""
    val decodedInteger = JSON.Format.unapply(integerIdLine).flatMap(JSON.value(_, "id"))
    assert(decodedInteger.exists(_.isInstanceOf[Double]), "Isabelle JSON decodes numeric tokens as Double")
    assertEquals(decodedInteger, Some(7.0))
    val integerConnection = kernel(List(integerIdLine))
    assertEquals(integerConnection.receive().map(_.decision),
      Some(ConnectionLifecycle.Rejected(RevisionRules.NullReply, RevisionRules.InvalidRequest,
        "id must be a string")))

    val fractionalIdLine = """{"jsonrpc":"2.0","id":7.5,"method":"ping"}"""
    val fractionalConnection = kernel(List(fractionalIdLine))
    assertEquals(fractionalConnection.receive().map(_.decision),
      Some(ConnectionLifecycle.Rejected(RevisionRules.NullReply, RevisionRules.InvalidRequest,
        "id must be a string")))

    val largeIdLine = """{"jsonrpc":"2.0","id":9007199254740993,"method":"ping"}"""
    val decodedLarge = JSON.Format.unapply(largeIdLine).flatMap(JSON.value(_, "id"))
    assert(decodedLarge.exists(_.isInstanceOf[Double]), "Isabelle JSON decodes numeric tokens as Double")
    assertEquals(decodedLarge, Some(9007199254740992.0))
    val largeConnection = kernel(List(largeIdLine))
    assertEquals(largeConnection.receive().map(_.decision),
      Some(ConnectionLifecycle.Rejected(RevisionRules.NullReply, RevisionRules.InvalidRequest,
        "id must be a string")))
  }

  test("typed list_changed notifications are emitted only after Ready") {
    val plane = new ScriptedDataPlane(Nil)
    val connection = kernelWith(plane, new DeterministicSequentialScheduler(2), 2, application)

    connection.listChanged(ConnectionKernel.ListChanged.Tools)
    assertEquals(plane.written, Nil)

    connection.handle(RevisionRules.Initialize(requestId(52), ProtocolRevision.V2025_03_26.value))
    connection.listChanged(ConnectionKernel.ListChanged.Tools)
    assertEquals(plane.written.length, 1, "AwaitingInitialized must not emit list_changed")

    connection.handle(RevisionRules.Initialized)
    connection.listChanged(ConnectionKernel.ListChanged.Resources)
    assertEquals(plane.written.length, 2)
    assert(plane.written.last.contains("notifications/resources/list_changed"))

    connection.beginClosing()
    connection.listChanged(ConnectionKernel.ListChanged.Tools)
    assertEquals(plane.written.length, 2)
  }

  spec_test("batch aggregation uses the same application, scheduler, and registry path",
      covers = List("connection_kernel#T10")) {
    val inbound = List(
      request(Some("first"), "tools/list"),
      request(None, "notifications/unknown"),
      request(Some("overload"), "resources/list"))
    val plane = new ScriptedDataPlane(List(JSON.Format(inbound)))
    val scheduler = new ManualSequentialScheduler
    var executions = 0
    val app = new McpApplication {
      def execute(operation: McpApplication.Operation, cancellation: McpApplication.Cancellation) = {
        executions += 1
        McpApplication.Outcome.Result(JSON.Object("operation" -> executions))
      }
    }
    val connection = kernelWith(plane, scheduler, maxInFlight = 1, app)
    ready(connection)
    val before = plane.written.length
    connection.receiveAndExecute()

    assertEquals(executions, 0, "manual scheduler proves the batch used the application scheduler")
    assertEquals(plane.written.length, before, "early overload must wait for all batch slots")
    assert(scheduler.hasPending, "first application request must be registered and scheduled")
    assert(scheduler.runPending())
    assertEquals(executions, 1)
    assertEquals(plane.written.length, before + 1)
    val values = batch_values(plane)
    assertEquals(values.length, 2)
    assert(values.exists(value => get(value, "id") == "first"))
    assert(values.exists(value => get(value, "id") == "overload" &&
      get(value, "error", "code") == ConnectionKernel.Overloaded))
    assertEquals(connection.registry.snapshot.activeCapacity, 0)
  }

  spec_test("notification-only batches emit no frame",
      covers = List("connection_kernel#T10")) {
    val plane = new ScriptedDataPlane(Nil)
    val connection = kernelWith(plane, new DeterministicSequentialScheduler(1), 1, application)
    ready(connection)
    val before = plane.written.length
    connection.handle(RevisionRules.Batch(List(
      request(None, "notifications/unknown"),
      request(None, "notifications/initialized"))))
    assertEquals(plane.written.length, before)
  }

  spec_test("revision rules reject empty and initialize batches as one non-array error",
      covers = List("connection_kernel#T10")) {
    val emptyPlane = new ScriptedDataPlane(List(JSON.Format(List.empty[JSON.T])))
    val empty = ConnectionKernel(policy(1), emptyPlane, rules, new DeterministicSequentialScheduler(1),
      new RequestRegistry(RequestRegistry.InvariantViolationPolicy.FailFast), application, serverInfo)
    empty.receiveAndExecute()
    assertEquals(JSON.Format.unapply(emptyPlane.written.last).exists(_.isInstanceOf[List[_]]), false)
    val emptyReply = JSON.Format.unapply(emptyPlane.written.last).getOrElse(fail("missing empty batch error"))
    assertEquals(get(emptyReply, "id"), null)
    assertEquals(get(emptyReply, "error", "code"), RevisionRules.InvalidRequest)

    val initializeElement = request(Some("initialize"), "initialize",
      Some(JSON.Object("protocolVersion" -> ProtocolRevision.V2025_03_26.value)))
    val initializePlane = new ScriptedDataPlane(List(JSON.Format(List(initializeElement))))
    val initialize = ConnectionKernel(policy(1), initializePlane, rules,
      new DeterministicSequentialScheduler(1),
      new RequestRegistry(RequestRegistry.InvariantViolationPolicy.FailFast), application, serverInfo)
    initialize.receiveAndExecute()
    assertEquals(JSON.Format.unapply(initializePlane.written.last).exists(_.isInstanceOf[List[_]]), false)
    val initializeReply = JSON.Format.unapply(initializePlane.written.last)
      .getOrElse(fail("missing initialize batch error"))
    assertEquals(get(initializeReply, "id"), null)
    assertEquals(get(initializeReply, "error", "code"), RevisionRules.InvalidRequest)
  }

  spec_test("batch aggregates invalid non-object elements with null ids",
      covers = List("connection_kernel#T10")) {
    val plane = new ScriptedDataPlane(Nil)
    val connection = kernelWith(plane, new DeterministicSequentialScheduler(1), 1, application)
    ready(connection)
    val before = plane.written.length
    connection.handle(RevisionRules.Batch(List(
      List(JSON.Object("nested" -> true)),
      request(Some("ping"), "ping"))))
    assertEquals(plane.written.length, before + 1)
    val values = batch_values(plane)
    assertEquals(values.length, 2)
    assert(values.exists(value => get(value, "id") == null &&
      get(value, "error", "code") == RevisionRules.InvalidRequest))
    assert(values.exists(value => get(value, "id") == "ping" && get(value, "result") == JSON.Object()))
  }

  spec_test("same-batch cancellation omits its slot and suppresses the late worker result",
      covers = List("connection_kernel#T10")) {
    val plane = new ScriptedDataPlane(Nil)
    val scheduler = new ManualSequentialScheduler
    var sawCancelled = false
    val app = new McpApplication {
      def execute(operation: McpApplication.Operation, cancellation: McpApplication.Cancellation) = {
        sawCancelled = cancellation.isCancelled
        McpApplication.Outcome.Result(JSON.Object("late" -> true))
      }
    }
    val connection = kernelWith(plane, scheduler, maxInFlight = 1, app)
    ready(connection)
    val before = plane.written.length
    connection.handle(RevisionRules.Batch(List(
      request(Some("slow"), "tools/list"),
      request(None, "notifications/cancelled",
        Some(JSON.Object("requestId" -> "slow", "reason" -> "gone"))),
      request(Some("ping"), "ping"))))
    assertEquals(plane.written.length, before + 1)
    val values = batch_values(plane)
    assertEquals(values.length, 1)
    assertEquals(get(values.head, "id"), "ping")
    assert(scheduler.runPending())
    assert(sawCancelled)
    assertEquals(plane.written.length, before + 1, "late worker result escaped its omitted batch slot")
  }

  spec_test("all-cancelled batch responses emit neither an empty array nor a late result",
      covers = List("connection_kernel#T10")) {
    val plane = new ScriptedDataPlane(Nil)
    val scheduler = new ManualSequentialScheduler
    val connection = kernelWith(plane, scheduler, maxInFlight = 1, application)
    ready(connection)
    val before = plane.written.length
    connection.handle(RevisionRules.Batch(List(
      request(Some("only"), "tools/list"),
      request(None, "notifications/cancelled", Some(JSON.Object("requestId" -> "only"))))))
    assertEquals(plane.written.length, before, "all-cancelled batch must not emit []")
    assert(scheduler.runPending())
    assertEquals(plane.written.length, before, "late all-cancelled worker result escaped")
  }

  spec_test("batch response order follows completion order, not input order",
      covers = List("connection_kernel#T10")) {
    import java.util.concurrent.{CountDownLatch, TimeUnit}

    val plane = new ScriptedDataPlane(Nil)
    val scheduler = new BoundedConcurrentScheduler(2, "kernel-batch-order-worker")
    val slowStarted = new CountDownLatch(1)
    val fastFinished = new CountDownLatch(1)
    val releaseSlow = new CountDownLatch(1)
    val app = new McpApplication {
      def execute(operation: McpApplication.Operation, cancellation: McpApplication.Cancellation) =
        operation match {
          case McpApplication.Operation.ToolsCall("slow", _) =>
            slowStarted.countDown()
            releaseSlow.await(2, TimeUnit.SECONDS)
            McpApplication.Outcome.Result(JSON.Object("which" -> "slow"))
          case McpApplication.Operation.ToolsCall("fast", _) =>
            fastFinished.countDown()
            McpApplication.Outcome.Result(JSON.Object("which" -> "fast"))
          case _ => McpApplication.Outcome.Result(JSON.Object())
        }
    }
    val connection = kernelWith(plane, scheduler, maxInFlight = 2, app)
    try {
      ready(connection)
      val before = plane.written.length
      connection.handle(RevisionRules.Batch(List(
        request(Some("slow"), "tools/call",
          Some(JSON.Object("name" -> "slow", "arguments" -> JSON.Object()))),
        request(Some("fast"), "tools/call",
          Some(JSON.Object("name" -> "fast", "arguments" -> JSON.Object()))))))
      assert(slowStarted.await(2, TimeUnit.SECONDS), "slow batch worker did not start")
      assert(fastFinished.await(2, TimeUnit.SECONDS), "fast batch worker did not finish")
      assertEquals(plane.written.length, before, "partial batch aggregate escaped before slow completion")
      releaseSlow.countDown()
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
      while (plane.written.length == before && System.nanoTime() < deadline) Thread.sleep(5L)
      assertEquals(plane.written.length, before + 1)
      val values = batch_values(plane)
      assertEquals(values.map(value => get(value, "id")), List("fast", "slow"))
    }
    finally {
      releaseSlow.countDown()
      scheduler.shutdown()
    }
  }

  spec_test("final close aborts unresolved batch contexts without a post-close aggregate",
      covers = List("connection_kernel#T10")) {
    val plane = new ScriptedDataPlane(Nil)
    val scheduler = new ManualSequentialScheduler
    val connection = kernelWith(plane, scheduler, maxInFlight = 1, application)
    ready(connection)
    val before = plane.written.length
    connection.handle(RevisionRules.Batch(List(request(Some("pending"), "tools/list"))))
    assert(scheduler.hasPending)
    connection.close()
    assertEquals(plane.written.length, before)
    assert(!scheduler.runPending())
    assertEquals(plane.written.length, before)
  }

  spec_test("aggregate emission and final close linearize under the batch output lock",
      covers = List("connection_kernel#T10")) {
    import java.util.concurrent.{CountDownLatch, TimeUnit}
    import java.util.concurrent.atomic.AtomicReference

    final class BlockingBatchPlane extends DataPlane {
      private var emitted = List.empty[JsonRpc.Outbound]
      val batchSendStarted = new CountDownLatch(1)
      val releaseBatchSend = new CountDownLatch(1)

      def receive(): Option[JsonRpc.Inbound] = None
      def send(outbound: JsonRpc.Outbound): Unit = {
        outbound match {
          case _: JsonRpc.Outbound.Batch =>
            batchSendStarted.countDown()
            if (!releaseBatchSend.await(2, TimeUnit.SECONDS))
              throw new RuntimeException("test did not release aggregate send")
          case _ => ()
        }
        synchronized { emitted = emitted :+ outbound }
      }
      def written: List[JsonRpc.Outbound] = synchronized { emitted }
    }

    val plane = new BlockingBatchPlane
    val scheduler = new BoundedConcurrentScheduler(1, "kernel-batch-close-worker")
    val connection = ConnectionKernel(policy(1), plane, rules, scheduler,
      new RequestRegistry(RequestRegistry.InvariantViolationPolicy.FailFast), application, serverInfo)
    val closeFailure = new AtomicReference[Throwable](null)
    val closeStarted = new CountDownLatch(1)
    var closer: Thread = null
    try {
      ready(connection)
      connection.handle(RevisionRules.Batch(List(request(Some("race"), "tools/list"))))
      assert(plane.batchSendStarted.await(2, TimeUnit.SECONDS), "aggregate send did not start")
      closer = new Thread(new Runnable {
        def run(): Unit = {
          closeStarted.countDown()
          try connection.close()
          catch { case exn: Throwable => closeFailure.set(exn) }
        }
      }, "kernel-batch-close-race")
      closer.setDaemon(true)
      closer.start()
      assert(closeStarted.await(2, TimeUnit.SECONDS), "close race thread did not start")
      Thread.sleep(20L)
      assert(closer.isAlive, "close must wait for an already-linearized aggregate send")
      plane.releaseBatchSend.countDown()
      closer.join(2000L)
      assert(!closer.isAlive, "close race thread did not finish")
      Option(closeFailure.get()).foreach(throw _)
      assertEquals(plane.written.collect { case _: JsonRpc.Outbound.Batch => () }.length, 1)
    }
    finally {
      plane.releaseBatchSend.countDown()
      if (closer != null) closer.join(2000L)
      scheduler.shutdown()
    }
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

  test("direct-handoff scheduler reserves exactly its bound with no task queue") {
    import java.util.concurrent.{CountDownLatch, TimeUnit}

    val scheduler = new BoundedConcurrentScheduler(2, "kernel-test-worker")
    val started = new CountDownLatch(2)
    val finished = new CountDownLatch(2)
    val release = new CountDownLatch(1)
    def task(): () => Unit = () => {
      started.countDown()
      release.await(2, TimeUnit.SECONDS)
      finished.countDown()
    }

    val first = scheduler.tryReserve().asInstanceOf[RequestScheduler.Reserved].permit
    val second = scheduler.tryReserve().asInstanceOf[RequestScheduler.Reserved].permit
    assertEquals(scheduler.start(first, task()), RequestScheduler.Started)
    assertEquals(scheduler.start(second, task()), RequestScheduler.Started)
    assert(started.await(2, TimeUnit.SECONDS), "both owned workers should start")
    assertEquals(scheduler.tryReserve(), RequestScheduler.Rejected)
    release.countDown()
    assert(finished.await(2, TimeUnit.SECONDS), "owned tasks should finish")
    val recovered = scheduler.tryReserve()
    assert(recovered.isInstanceOf[RequestScheduler.Reserved], "worker permits must recover")
    recovered match { case RequestScheduler.Reserved(permit) => scheduler.abandon(permit); case _ => () }
    scheduler.shutdown()
    assert(scheduler.isShutdown)
    assertEquals(scheduler.tryReserve(), RequestScheduler.Rejected)
  }

  test("kernel rejects a scheduler whose execution capacity disagrees with policy") {
    intercept[IllegalArgumentException] {
      kernelWith(new ScriptedDataPlane(Nil), new ManualSequentialScheduler,
        maxInFlight = 2, application)
    }
  }

  test("initialize response selects the configured revision and injected server identity") {
    val plane = new ScriptedDataPlane(Nil)
    val connection = kernelWith(plane, new DeterministicSequentialScheduler(1),
      maxInFlight = 1, application)
    connection.handle(RevisionRules.Initialize(requestId(55), "2024-11-05"))
    val reply = JSON.Format.unapply(plane.written.last).getOrElse(fail("missing initialize reply"))
    assertEquals(get(reply, "id"), "55")
    assertEquals(get(reply, "result", "protocolVersion"), ProtocolRevision.V2025_03_26.value)
    assertEquals(get(reply, "result", "serverInfo", "name"), "test-server")
    assertEquals(get(reply, "result", "serverInfo", "version"), "test-version")
  }

  test("scheduler permits are one-shot and shutdown consumes unstarted work") {
    val manual = new ManualSequentialScheduler
    val permit = manual.tryReserve().asInstanceOf[RequestScheduler.Reserved].permit
    assertEquals(manual.start(permit, () => ()), RequestScheduler.Started)
    assertEquals(manual.start(permit, () => ()), RequestScheduler.StartRejected)
    manual.abandon(permit)
    assertEquals(manual.tryReserve(), RequestScheduler.Rejected,
      "abandoning a started permit must not free worker capacity")
    assert(manual.hasPending)
    manual.shutdown()
    assert(!manual.hasPending)
    assertEquals(manual.tryReserve(), RequestScheduler.Rejected)
    assertEquals(manual.start(permit, () => ()), RequestScheduler.StartRejected)

    val left = new DeterministicSequentialScheduler(1)
    val right = new DeterministicSequentialScheduler(1)
    val foreign = right.tryReserve().asInstanceOf[RequestScheduler.Reserved].permit
    assertEquals(left.start(foreign, () => ()), RequestScheduler.StartRejected)
    left.abandon(foreign)
    assertEquals(right.start(foreign, () => ()), RequestScheduler.Started)

    val reservedOnly = new ManualSequentialScheduler
    val unstarted = reservedOnly.tryReserve().asInstanceOf[RequestScheduler.Reserved].permit
    reservedOnly.shutdown()
    assertEquals(reservedOnly.start(unstarted, () => ()), RequestScheduler.StartRejected)
  }

  spec_test("kernel saturates the configured bound without a waiting queue and recovers",
      covers = List("connection_kernel#T3")) {
    import java.util.concurrent.{CountDownLatch, TimeUnit}
    import java.util.concurrent.atomic.AtomicInteger

    val plane = new ScriptedDataPlane(Nil)
    val scheduler = new BoundedConcurrentScheduler(2, "kernel-saturation-worker")
    val started = new CountDownLatch(2)
    val release = new CountDownLatch(1)
    val finished = new CountDownLatch(2)
    val executions = new AtomicInteger(0)
    val app = new McpApplication {
      def execute(operation: McpApplication.Operation, cancellation: McpApplication.Cancellation) = {
        executions.incrementAndGet()
        started.countDown()
        release.await(2, TimeUnit.SECONDS)
        finished.countDown()
        McpApplication.Outcome.Result(JSON.Object("ok" -> true))
      }
    }
    val connection = kernelWith(plane, scheduler, maxInFlight = 2, app)
    connection.handle(RevisionRules.Initialize(requestId(60), ProtocolRevision.V2025_03_26.value))
    connection.handle(RevisionRules.Initialized)
    connection.handle(RevisionRules.Application(McpApplication.Operation.ToolsList, requestId(61)))
    connection.handle(RevisionRules.Application(McpApplication.Operation.ToolsList, requestId(62)))
    assert(started.await(2, TimeUnit.SECONDS), "configured bound should start exactly two workers")
    assertEquals(connection.registry.snapshot.activeCapacity, 2)
    assertEquals(connection.handle(RevisionRules.Application(McpApplication.Operation.ToolsList, requestId(63))).decision,
      ConnectionLifecycle.Overloaded(requestId(63)))
    assertEquals(executions.get, 2, "overloaded work must neither run nor wait")
    assertEquals(connection.registry.snapshot.activeCapacity, 2)
    val overload = JSON.Format.unapply(plane.written.last).getOrElse(fail("missing overload JSON"))
    assertEquals(get(overload, "id"), "63")
    assertEquals(get(overload, "error", "code"), ConnectionKernel.Overloaded)
    assertEquals(get(overload, "error", "data", "reason"), "maxInFlight")
    assertEquals(get(overload, "error", "data", "maxInFlight"), 2)
    release.countDown()
    assert(finished.await(2, TimeUnit.SECONDS), "admitted workers should complete")
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    while (connection.registry.snapshot.activeCapacity != 0 && System.nanoTime() < deadline)
      Thread.sleep(5)
    assertEquals(connection.registry.snapshot.activeCapacity, 0)
    connection.handle(RevisionRules.Application(McpApplication.Operation.ToolsList, requestId(64)))
    val recoveredDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    while (executions.get != 3 && System.nanoTime() < recoveredDeadline)
      Thread.sleep(5)
    assertEquals(executions.get, 3, "completion must make direct-handoff capacity available")
    scheduler.shutdown()
  }

  test("cancellation immediately after admission abandons its permit and cannot run or reply") {
    val plane = new ScriptedDataPlane(Nil)
    val scheduler = new ManualSequentialScheduler
    var executions = 0
    val app = new McpApplication {
      def execute(operation: McpApplication.Operation, cancellation: McpApplication.Cancellation) = {
        executions += 1
        McpApplication.Outcome.Result(JSON.Object())
      }
    }
    val connection = kernelWith(plane, scheduler, maxInFlight = 1, app)
    connection.handle(RevisionRules.Initialize(requestId(70), ProtocolRevision.V2025_03_26.value))
    connection.handle(RevisionRules.Initialized)
    val pending = connection.admit(RevisionRules.Application(McpApplication.Operation.ToolsList, requestId(71)))
    assertEquals(connection.registry.snapshot.activeCapacity, 1)
    connection.admit(RevisionRules.Cancelled(requestId(71), Some("gone")))
    connection.execute(pending)
    assertEquals(executions, 0)
    assert(!scheduler.hasPending)
    assertEquals(connection.registry.snapshot.activeCapacity, 0)
    assertEquals(plane.written.count(_.contains("\"id\":\"71\"")), 0)
    connection.handle(RevisionRules.Application(McpApplication.Operation.ToolsList, requestId(72)))
    assert(scheduler.hasPending, "abandoned permit must be reusable")
  }

  test("worker errors and exceptions release capacity and emit one owned response") {
    val plane = new ScriptedDataPlane(Nil)
    val scheduler = new ManualSequentialScheduler
    var mode = "error"
    val app = new McpApplication {
      def execute(operation: McpApplication.Operation, cancellation: McpApplication.Cancellation) =
        mode match {
          case "error" => McpApplication.Outcome.InvalidParams("bad arguments")
          case "exception" => throw new RuntimeException("boom")
        }
    }
    val connection = kernelWith(plane, scheduler, maxInFlight = 1, app)
    connection.handle(RevisionRules.Initialize(requestId(80), ProtocolRevision.V2025_03_26.value))
    connection.handle(RevisionRules.Initialized)
    connection.handle(RevisionRules.Application(McpApplication.Operation.ToolsList, requestId(81)))
    assert(scheduler.runPending())
    assertEquals(connection.registry.snapshot.activeCapacity, 0)
    val errorReply = JSON.Format.unapply(plane.written.last).getOrElse(fail("missing application error"))
    assertEquals(get(errorReply, "id"), "81")
    assertEquals(get(errorReply, "error", "code"), RevisionRules.InvalidParams)

    mode = "exception"
    connection.handle(RevisionRules.Application(McpApplication.Operation.ToolsList, requestId(82)))
    assert(scheduler.runPending())
    assertEquals(connection.registry.snapshot.activeCapacity, 0)
    val exceptionReply = JSON.Format.unapply(plane.written.last).getOrElse(fail("missing exception reply"))
    assertEquals(get(exceptionReply, "id"), "82")
    assertEquals(get(exceptionReply, "error", "code"), ConnectionKernel.InternalError)
    assertEquals(plane.written.count(_.contains("\"id\":\"82\"")), 1)
  }

  test("bounded workers may complete responses out of order") {
    import java.util.concurrent.{CountDownLatch, TimeUnit}

    val plane = new ScriptedDataPlane(Nil)
    val scheduler = new BoundedConcurrentScheduler(2, "kernel-order-worker")
    val slowStarted = new CountDownLatch(1)
    val releaseSlow = new CountDownLatch(1)
    val app = new McpApplication {
      def execute(operation: McpApplication.Operation, cancellation: McpApplication.Cancellation) =
        operation match {
          case McpApplication.Operation.ToolsCall("slow", _) =>
            slowStarted.countDown()
            releaseSlow.await(2, TimeUnit.SECONDS)
            McpApplication.Outcome.Result(JSON.Object("which" -> "slow"))
          case McpApplication.Operation.ToolsCall("fast", _) =>
            McpApplication.Outcome.Result(JSON.Object("which" -> "fast"))
          case _ => McpApplication.Outcome.Result(JSON.Object())
        }
    }
    val connection = kernelWith(plane, scheduler, maxInFlight = 2, app)
    connection.handle(RevisionRules.Initialize(requestId(90), ProtocolRevision.V2025_03_26.value))
    connection.handle(RevisionRules.Initialized)
    connection.handle(RevisionRules.Application(
      McpApplication.Operation.ToolsCall("slow", JSON.Object()), requestId(91)))
    assert(slowStarted.await(2, TimeUnit.SECONDS), "slow worker did not start")
    connection.handle(RevisionRules.Application(
      McpApplication.Operation.ToolsCall("fast", JSON.Object()), requestId(92)))
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    while (plane.written.count(_.contains("\"id\":\"92\"")) == 0 && System.nanoTime() < deadline)
      Thread.sleep(5)
    assertEquals(plane.written.count(_.contains("\"id\":\"92\"")), 1)
    assertEquals(plane.written.count(_.contains("\"id\":\"91\"")), 0)
    releaseSlow.countDown()
    val slowDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    while (plane.written.count(_.contains("\"id\":\"91\"")) == 0 && System.nanoTime() < slowDeadline)
      Thread.sleep(5)
    assertEquals(plane.written.count(_.contains("\"id\":\"91\"")), 1)
    scheduler.shutdown()
  }

  test("an output failure closes kernel execution and never retries the response") {
    import java.io.IOException

    val scheduler = new DeterministicSequentialScheduler(1)
    val plane = new DataPlane {
      def receive(): Option[JsonRpc.Inbound] = None
      def send(outbound: JsonRpc.Outbound): Unit = throw new IOException("closed output")
    }
    val connection = ConnectionKernel(
      policy = policy(1), dataPlane = plane, revisionRules = rules, scheduler = scheduler,
      registry = new RequestRegistry(RequestRegistry.InvariantViolationPolicy.FailFast),
      application = application, serverInfo = serverInfo)
    intercept[IOException] {
      connection.handle(RevisionRules.Initialize(requestId(100), ProtocolRevision.V2025_03_26.value))
    }
    assertEquals(connection.phase, ConnectionLifecycle.Closing)
    assert(scheduler.isShutdown)
  }
}
