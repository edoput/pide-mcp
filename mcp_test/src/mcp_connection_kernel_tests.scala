/*  Title:      mcp_test/src/mcp_connection_kernel_tests.scala

Connection-kernel contracts for validated policy, revision classification,
lifecycle control, request ownership, deadlines, bounded scheduling, batch
aggregation, and shutdown without Isabelle.
*/

package isabelle.mcp

import isabelle._
import isabelle.mcp.application.McpApplication
import isabelle.mcp.connection._
import isabelle.mcp.control.{DeadlineScheduler, ManualDeadlineScheduler}
import isabelle.mcp.protocol.JsonRpc
import isabelle.mcp.transport.{DataPlane, ScriptedDataPlane, StdioDataPlane}


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

  private def policy(
    maxInFlight: Int = 2,
    requestTimeout: Double = 5.0,
    shutdownDrain: Double = 0.0
  ): ConnectionPolicy =
    ConnectionPolicy(
      revision = ProtocolRevision.V2025_03_26,
      admission = ConnectionPolicy.AdmissionPolicy(
        maxInFlight = checked(ConnectionPolicy.MaxInFlight.checked(maxInFlight))),
      timing = ConnectionPolicy.TimingPolicy(
        requestTimeout = checked(ConnectionPolicy.RequestTimeout.checked(requestTimeout)),
        shutdownDrain = checked(ConnectionPolicy.ShutdownDrain.checked(shutdownDrain))))

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
      deadlineScheduler = new ManualDeadlineScheduler,
      registry = new RequestRegistry(RequestRegistry.InvariantViolationPolicy.FailFast),
      application = application,
      serverInfo = serverInfo)

  private def kernelWith(
    plane: ScriptedDataPlane,
    scheduler: RequestScheduler,
    maxInFlight: Int,
    app: McpApplication,
    deadlineScheduler: DeadlineScheduler = new ManualDeadlineScheduler,
    requestTimeout: Double = 5.0,
    shutdownDrain: Double = 0.0
  ): ConnectionKernel =
    ConnectionKernel(
      policy = policy(maxInFlight, requestTimeout, shutdownDrain),
      dataPlane = plane,
      revisionRules = rules,
      scheduler = scheduler,
      deadlineScheduler = deadlineScheduler,
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
      deadlineScheduler = new ManualDeadlineScheduler,
      registry = new RequestRegistry(RequestRegistry.InvariantViolationPolicy.FailFast),
      application = application,
      serverInfo = serverInfo)

    assertEquals(ConnectionPolicy.MaxInFlight.value(kernel0.policy.admission.maxInFlight), 2)
    assertEquals(ConnectionPolicy.MaxInFlight.value(
      checked(ConnectionPolicy.fromOptions(changed)).admission.maxInFlight), 9)
    assert(kernel0.policy eq snapshot, "kernel must retain the construction-time policy snapshot")
  }

  spec_test("MCP 2025-03-26 rules classify valid and invalid wire messages",
      covers = List("connection_kernel#T1")) {
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

  spec_test("one kernel contract runs against deterministic and bounded schedulers",
      covers = List("connection_kernel#T8")) {
    import java.util.concurrent.TimeUnit

    def runContract(name: String, scheduler: RequestScheduler, base: Long): Unit = {
      val plane = new ScriptedDataPlane(Nil)
      val deadlines = new ManualDeadlineScheduler
      val connection = kernelWith(plane, scheduler, 1, application, deadlines, shutdownDrain = 1.0)
      ready(connection, base)
      val id = requestId(base + 1)
      val before = plane.written.length
      connection.handle(RevisionRules.Application(McpApplication.Operation.ToolsList, id))
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
      while (plane.written.length == before && System.nanoTime() < deadline) Thread.sleep(2L)
      assertEquals(plane.written.length, before + 1,
        name + " scheduler did not complete the shared contract")
      val reply = JSON.Format.unapply(plane.written.last).getOrElse(fail("missing " + name + " reply"))
      assertEquals(get(reply, "id"), id.json)
      assert(connection.drainAndClose().drained)
      assert(scheduler.isShutdown)
      assert(deadlines.isShutdown)
    }

    runContract("deterministic", new DeterministicSequentialScheduler(1), 520)
    runContract("bounded", new BoundedConcurrentScheduler(1, "kernel-contract-worker"), 530)
  }

  spec_test("stdio data plane and bounded scheduler run the kernel contract together",
      covers = List("connection_kernel#T8")) {
    import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
    import java.nio.charset.StandardCharsets

    val input = List(
      request(Some("initialize-stdio"), "initialize",
        Some(JSON.Object("protocolVersion" -> ProtocolRevision.V2025_03_26.value))),
      request(None, "notifications/initialized"),
      request(Some("operation-stdio"), "tools/list")
    ).map(JSON.Format.apply).mkString("\n")
    val output = new ByteArrayOutputStream
    val scheduler = new BoundedConcurrentScheduler(1, "kernel-stdio-contract-worker")
    val deadlines = new ManualDeadlineScheduler
    val connection = ConnectionKernel(
      policy = policy(1, shutdownDrain = 1.0),
      dataPlane = new StdioDataPlane(
        new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output),
      revisionRules = rules,
      scheduler = scheduler,
      deadlineScheduler = deadlines,
      registry = new RequestRegistry(RequestRegistry.InvariantViolationPolicy.FailFast),
      application = application,
      serverInfo = serverInfo)
    while (connection.receiveAndExecute().nonEmpty) ()
    assert(connection.drainAndClose().drained)
    val replies = output.toString(StandardCharsets.UTF_8).linesIterator.toList
      .flatMap(JSON.Format.unapply)
    assertEquals(replies.flatMap(value => JSON.value(value, "id")).toSet,
      Set("initialize-stdio", "operation-stdio"))
    assertEquals(connection.phase, ConnectionLifecycle.Closed)
  }

  spec_test("request ids accept strings and reject every non-string value",
      covers = List("connection_kernel#T1")) {
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

  spec_test("cancellation callbacks are exactly-once, outside registry ownership, and suppress stale completion",
      covers = List("connection_kernel#T4")) {
    import java.util.concurrent.{CountDownLatch, TimeUnit}
    import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
    import RequestRegistry.{AdmissionKind, TerminalDisposition, WorkerDisposition}

    val policy = new RequestRegistry.InvariantViolationPolicy.MarkBroken
    val registry = new RequestRegistry(policy)
    val ordinary = admitted(registry, requestId(30), AdmissionKind.Ordinary)
    val initialize = admitted(registry, requestId(31), AdmissionKind.Initialize)
    val callbacks = new AtomicInteger(0)
    val outsideRegistry = new AtomicBoolean(false)
    ordinary.cancellation.onCancel(() => {
      callbacks.incrementAndGet()
      throw new IllegalStateException("one bad cooperative callback")
    })
    ordinary.cancellation.onCancel(() => {
      val reentered = new CountDownLatch(1)
      val probe = new Thread(new Runnable {
        def run(): Unit = { registry.snapshot; reentered.countDown() }
      }, "cancellation-registry-reentry")
      probe.setDaemon(true)
      probe.start()
      outsideRegistry.set(reentered.await(1, TimeUnit.SECONDS))
      callbacks.incrementAndGet()
    })
    assert(!ordinary.cancellation.isCancelled)
    assertEquals(registry.cancel(requestId(30), Some("client closed pane")),
      RequestRegistry.Cancelled(RequestRegistry.Tombstone(requestId(30), AdmissionKind.Ordinary,
        TerminalDisposition.ClientCancelled, Some("client closed pane"), capacityReleased = true)))
    assert(ordinary.cancellation.isCancelled)
    assertEquals(callbacks.get(), 2, "one failing callback prevented a later callback")
    assert(outsideRegistry.get(), "cooperative callback ran while the registry monitor was held")
    ordinary.cancellation.onCancel(() => callbacks.incrementAndGet())
    assertEquals(callbacks.get(), 3, "late registration did not run immediately")
    assertEquals(registry.snapshot.activeCapacity, 0)
    assertEquals(registry.cancel(requestId(30), None), RequestRegistry.CancellationIgnored)
    assertEquals(callbacks.get(), 3, "repeated cancellation invoked a callback twice")
    assertEquals(registry.cancel(requestId(31), None), RequestRegistry.CancellationIgnored)
    assertEquals(registry.complete(ordinary.token, WorkerDisposition.Success),
      RequestRegistry.LateIgnored(RequestRegistry.Tombstone(requestId(30), AdmissionKind.Ordinary,
        TerminalDisposition.ClientCancelled, Some("client closed pane"), capacityReleased = true)))
    assert(registry.complete(ordinary.token, WorkerDisposition.Success)
      .isInstanceOf[RequestRegistry.InvariantViolation])
    assert(policy.isBroken)
  }

  spec_test("timeout and shutdown propagate cancellation without consuming late worker completion",
      covers = List("connection_kernel#T4", "connection_kernel#T5")) {
    import java.util.concurrent.atomic.AtomicInteger
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
    val signals = new AtomicInteger(0)
    timed.cancellation.onCancel(() => signals.incrementAndGet())
    assert(!timed.cancellation.isCancelled)
    assertEquals(closingRegistry.timeout(timed.token),
      RequestRegistry.Completed(RequestRegistry.Tombstone(requestId(37), AdmissionKind.Ordinary,
        RequestRegistry.TerminalDisposition.Timeout, None, capacityReleased = true)))
    assert(timed.cancellation.isCancelled)
    assertEquals(signals.get(), 1)

    val shuttingDown = admitted(closingRegistry, requestId(38), AdmissionKind.Ordinary)
    shuttingDown.cancellation.onCancel(() => signals.incrementAndGet())
    assertEquals(closingRegistry.shutdown(), List(RequestRegistry.Tombstone(requestId(38),
      AdmissionKind.Ordinary, RequestRegistry.TerminalDisposition.Shutdown, None,
      capacityReleased = true)))
    assert(shuttingDown.cancellation.isCancelled)
    assertEquals(signals.get(), 2)
    assert(closingRegistry.complete(shuttingDown.token, WorkerDisposition.Success)
      .isInstanceOf[RequestRegistry.LateIgnored])
  }

  spec_test("request timeout and worker completion race to one response and one disposition",
      covers = List("connection_kernel#T5")) {
    val plane = new ScriptedDataPlane(Nil)
    val scheduler = new ManualSequentialScheduler
    val deadlines = new ManualDeadlineScheduler
    var sawCancellation = false
    val app = new McpApplication {
      def execute(operation: McpApplication.Operation, cancellation: McpApplication.Cancellation) = {
        sawCancellation = cancellation.isCancelled
        McpApplication.Outcome.Result(JSON.Object("late" -> true))
      }
    }
    val connection = kernelWith(plane, scheduler, 1, app, deadlines)
    ready(connection)
    val before = plane.written.length
    connection.handle(RevisionRules.Application(McpApplication.Operation.ToolsList, requestId(39)))
    assertEquals(deadlines.pendingCount, 1)
    assert(deadlines.fireNext(), "request deadline did not fire")
    val timeoutReply = JSON.Format.unapply(plane.written.last).getOrElse(fail("missing timeout reply"))
    assertEquals(get(timeoutReply, "id"), "39")
    assertEquals(get(timeoutReply, "error", "code"), ConnectionKernel.RequestTimedOut)
    assertEquals(get(timeoutReply, "error", "message"), "Request timed out")
    assertEquals(get(timeoutReply, "error", "data", "reason"), "requestTimeout")
    assertEquals(get(timeoutReply, "error", "data", "seconds"), 5.0)
    assertEquals(connection.registry.snapshot.tombstones.find(_.id == requestId(39)).map(_.disposition),
      Some(RequestRegistry.TerminalDisposition.Timeout))
    assertEquals(connection.registry.snapshot.activeCapacity, 0)

    assert(scheduler.runPending(), "timed-out worker must still reach its cooperative stop path")
    assert(sawCancellation)
    assertEquals(plane.written.length, before + 1, "late timed-out result escaped")

    val scheduler2 = new ManualSequentialScheduler
    val deadlines2 = new ManualDeadlineScheduler
    val connection2 = kernelWith(new ScriptedDataPlane(Nil), scheduler2, 1, application, deadlines2)
    ready(connection2, 540)
    connection2.handle(RevisionRules.Application(McpApplication.Operation.ToolsList, requestId(541)))
    assert(scheduler2.runPending())
    assertEquals(deadlines2.pendingCount, 0, "successful completion retained a live deadline")
    assert(!deadlines2.fireNext(), "cancelled deadline fired after worker completion")
    assertEquals(connection2.registry.snapshot.tombstones.find(_.id == requestId(541)).map(_.disposition),
      Some(RequestRegistry.TerminalDisposition.Success))
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
      new RequestRegistry(RequestRegistry.InvariantViolationPolicy.FailFast), application, serverInfo,
      new ManualDeadlineScheduler)
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
      new RequestRegistry(RequestRegistry.InvariantViolationPolicy.FailFast), application, serverInfo,
      new ManualDeadlineScheduler)
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
      new RequestRegistry(RequestRegistry.InvariantViolationPolicy.FailFast), application, serverInfo,
      new ManualDeadlineScheduler)
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

  spec_test("registry detects foreign and duplicate tokens and applies every invariant reaction",
      covers = List("connection_kernel#T6")) {
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

  spec_test("cancellation immediately after admission abandons its permit and cannot run or reply",
      covers = List("connection_kernel#T5")) {
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

  spec_test("cancellation during saturation releases registry capacity before the worker permit",
      covers = List("connection_kernel#T4")) {
    import java.util.concurrent.{CountDownLatch, TimeUnit}

    val plane = new ScriptedDataPlane(Nil)
    val scheduler = new BoundedConcurrentScheduler(1, "kernel-cancellation-worker")
    val deadlines = new ManualDeadlineScheduler
    val started = new CountDownLatch(1)
    val signalled = new CountDownLatch(1)
    val allowReturn = new CountDownLatch(1)
    val app = new McpApplication {
      def execute(operation: McpApplication.Operation, cancellation: McpApplication.Cancellation) = {
        cancellation.onCancel(() => signalled.countDown())
        started.countDown()
        if (!allowReturn.await(2, TimeUnit.SECONDS)) fail("cancelled worker was never released")
        McpApplication.Outcome.Result(JSON.Object("late" -> true))
      }
    }
    val connection = kernelWith(plane, scheduler, 1, app, deadlines, shutdownDrain = 1.0)
    ready(connection, 650)
    val before = plane.written.length
    connection.handle(RevisionRules.Application(McpApplication.Operation.ToolsList,
      RequestId.string("saturated-cancel")))
    assert(started.await(2, TimeUnit.SECONDS), "saturated cancellation worker did not start")
    connection.handle(RevisionRules.Cancelled(
      RequestId.string("saturated-cancel"), Some("client stopped")))
    assert(signalled.await(1, TimeUnit.SECONDS), "application cancellation callback did not run")
    assertEquals(connection.registry.snapshot.activeCapacity, 0,
      "terminal cancellation did not release registry capacity")

    connection.handle(RevisionRules.Application(McpApplication.Operation.ToolsList,
      RequestId.string("permit-still-owned")))
    val overloaded = JSON.Format.unapply(plane.written.last)
      .getOrElse(fail("missing scheduler-saturation response"))
    assertEquals(get(overloaded, "id"), "permit-still-owned")
    assertEquals(get(overloaded, "error", "code"), ConnectionKernel.Overloaded)
    assertEquals(plane.written.length, before + 1,
      "cancelled request emitted a response before its worker returned")

    allowReturn.countDown()
    val releasedBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    var permitReleased = false
    while (!permitReleased && System.nanoTime() < releasedBy) {
      scheduler.tryReserve() match {
        case RequestScheduler.Reserved(value) =>
          scheduler.abandon(value)
          permitReleased = true
        case RequestScheduler.Rejected => Thread.sleep(2L)
      }
    }
    assert(permitReleased, "cooperative worker return did not release its scheduler permit")

    connection.handle(RevisionRules.Application(McpApplication.Operation.ToolsList,
      RequestId.string("after-cancel")))
    val completedBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    while (!plane.written.exists(_.contains("\"id\":\"after-cancel\"")) &&
        System.nanoTime() < completedBy) Thread.sleep(2L)
    assert(plane.written.exists(_.contains("\"id\":\"after-cancel\"")),
      "scheduler did not recover after cooperative cancellation")
    assertEquals(plane.written.count(_.contains("\"id\":\"saturated-cancel\"")), 0,
      "late cancelled result escaped")
    assert(connection.drainAndClose().drained)
  }

  spec_test("terminal cancellation callbacks run outside the kernel terminal lock",
      covers = List("connection_kernel#T4")) {
    import java.util.concurrent.{CountDownLatch, TimeUnit}
    import java.util.concurrent.atomic.AtomicBoolean

    def exercise(terminal: String): Unit = {
      val plane = new ScriptedDataPlane(Nil)
      val scheduler = new BoundedConcurrentScheduler(1, "kernel-terminal-callback-" + terminal)
      val deadlines = new ManualDeadlineScheduler
      val started = new CountDownLatch(1)
      val releaseWorker = new CountDownLatch(1)
      val callbackObservedWorkerCompletion = new AtomicBoolean(false)
      val app = new McpApplication {
        def execute(operation: McpApplication.Operation, cancellation: McpApplication.Cancellation) = {
          cancellation.onCancel(() => {
            releaseWorker.countDown()
            val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var acquired = false
            while (!acquired && System.nanoTime() < limit) {
              scheduler.tryReserve() match {
                case RequestScheduler.Reserved(permit) =>
                  scheduler.abandon(permit)
                  acquired = true
                case RequestScheduler.Rejected => Thread.sleep(2L)
              }
            }
            callbackObservedWorkerCompletion.set(acquired)
          })
          started.countDown()
          if (!releaseWorker.await(2, TimeUnit.SECONDS))
            fail("terminal callback did not release the worker")
          McpApplication.Outcome.Result(JSON.Object("late" -> true))
        }
      }
      val connection = kernelWith(plane, scheduler, 1, app, deadlines,
        requestTimeout = 5.0, shutdownDrain = 0.0)
      ready(connection, 660)
      val id = RequestId.string("terminal-callback-" + terminal)
      connection.handle(RevisionRules.Application(McpApplication.Operation.ToolsList, id))
      assert(started.await(2, TimeUnit.SECONDS), terminal + " worker did not start")

      terminal match {
        case "client" => connection.handle(RevisionRules.Cancelled(id, Some("stop")))
        case "timeout" => assert(deadlines.fireNext(), "timeout was not scheduled")
        case "close" => connection.close()
      }

      assert(callbackObservedWorkerCompletion.get(),
        terminal + " callback waited while the worker was blocked on the kernel terminal lock")
      if (terminal == "timeout")
        assert(plane.written.exists(line =>
          line.contains("\"id\":\"terminal-callback-timeout\"") &&
            line.contains("\"code\":-32002")), "timeout response was not emitted")
      if (terminal != "close") connection.drainAndClose()
      assert(scheduler.isShutdown, terminal + " did not finish scheduler shutdown")
    }

    List("client", "timeout", "close").foreach(exercise)
  }

  spec_test("worker errors and exceptions release capacity and emit one owned response",
      covers = List("connection_kernel#T5")) {
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

  spec_test("EOF drain handles zero and reverse-completing in-flight requests before close",
      covers = List("connection_kernel#T7")) {
    import java.util.concurrent.{CountDownLatch, TimeUnit}

    val emptyPlane = new ScriptedDataPlane(Nil)
    val emptyDeadlines = new ManualDeadlineScheduler
    val empty = kernelWith(emptyPlane, new DeterministicSequentialScheduler(3), 3,
      application, emptyDeadlines, shutdownDrain = 1.0)
    ready(empty, 600)
    val emptyResult = empty.drainAndClose()
    assert(emptyResult.drained)
    assertEquals(emptyResult.cancelled, Nil)
    assertEquals(empty.phase, ConnectionLifecycle.Closed)
    assert(emptyDeadlines.isShutdown)

    val plane = new ScriptedDataPlane(Nil)
    val scheduler = new BoundedConcurrentScheduler(3, "kernel-drain-worker")
    val deadlines = new ManualDeadlineScheduler
    val started = new CountDownLatch(3)
    val gates = Map(
      "first" -> new CountDownLatch(1),
      "second" -> new CountDownLatch(1),
      "third" -> new CountDownLatch(1))
    val app = new McpApplication {
      def execute(operation: McpApplication.Operation, cancellation: McpApplication.Cancellation) = {
        val name = operation match {
          case McpApplication.Operation.ToolsCall(value, _) => value
          case _ => fail("unexpected drain operation " + operation)
        }
        started.countDown()
        if (!gates(name).await(2, TimeUnit.SECONDS)) fail("drain gate did not open for " + name)
        McpApplication.Outcome.Result(JSON.Object("which" -> name))
      }
    }
    val connection = kernelWith(plane, scheduler, 3, app, deadlines, shutdownDrain = 2.0)
    ready(connection, 610)
    val before = plane.written.length
    List(("first", 611L), ("second", 612L), ("third", 613L)).foreach { case (name, id) =>
      connection.handle(RevisionRules.Application(
        McpApplication.Operation.ToolsCall(name, JSON.Object()), requestId(id)))
    }
    assert(started.await(2, TimeUnit.SECONDS), "drain workers did not all start")

    def written(id: String): Boolean = plane.written.exists(_.contains("\"id\":\"" + id + "\""))
    def awaitWritten(id: String): Unit = {
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
      while (!written(id) && System.nanoTime() < deadline) Thread.sleep(2L)
      assert(written(id), "missing drained response " + id)
    }
    val releaser = new Thread(new Runnable {
      def run(): Unit = {
        gates("third").countDown(); awaitWritten("613")
        gates("second").countDown(); awaitWritten("612")
        gates("first").countDown()
      }
    }, "kernel-drain-release")
    releaser.setDaemon(true)
    releaser.start()
    val result = connection.drainAndClose()
    releaser.join(2000L)
    assert(!releaser.isAlive, "drain release thread did not finish")
    assert(result.drained)
    assertEquals(result.cancelled, Nil)
    val ids = plane.written.drop(before).flatMap(line =>
      JSON.Format.unapply(line).flatMap(value => JSON.value(value, "id")).collect { case id: String => id })
    assertEquals(ids, List("613", "612", "611"))
    assertEquals(connection.phase, ConnectionLifecycle.Closed)
    assert(scheduler.isShutdown)
    assert(deadlines.isShutdown)
  }

  spec_test("EOF deadline terminalizes remaining work before close and suppresses late output",
      covers = List("connection_kernel#T5", "connection_kernel#T7")) {
    val plane = new ScriptedDataPlane(Nil)
    val scheduler = new ManualSequentialScheduler
    val deadlines = new ManualDeadlineScheduler
    val connection = kernelWith(plane, scheduler, 1, application, deadlines,
      shutdownDrain = 0.0)
    ready(connection, 620)
    val before = plane.written.length
    val accepted = connection.handle(
      RevisionRules.Application(McpApplication.Operation.ToolsList, requestId(621))) match {
      case value: ConnectionKernel.Accepted => value
      case other => fail("expected admitted shutdown fixture, got " + other)
    }
    val result = connection.drainAndClose()
    assert(!result.drained)
    assertEquals(result.cancelled.map(_.id), List(requestId(621)))
    assertEquals(result.cancelled.map(_.disposition), List(RequestRegistry.TerminalDisposition.Shutdown))
    assert(accepted.request.cancellation.isCancelled)
    assertEquals(connection.phase, ConnectionLifecycle.Closed)
    assert(scheduler.isShutdown)
    assert(deadlines.isShutdown)
    assert(!scheduler.runPending(), "shutdown scheduler retained unstarted application work")
    assertEquals(plane.written.length, before)
  }

  spec_test("EOF drain waits for a winning response write before returning",
      covers = List("connection_kernel#T7")) {
    import java.util.concurrent.{CountDownLatch, TimeUnit}

    final class BlockingResponsePlane extends DataPlane {
      private var values = List.empty[JsonRpc.Outbound]
      val sendStarted = new CountDownLatch(1)
      val release = new CountDownLatch(1)

      def receive(): Option[JsonRpc.Inbound] = None
      def send(outbound: JsonRpc.Outbound): Unit = {
        val work = JsonRpc.value(outbound) match {
          case value: JSON.Object.T @unchecked => JSON.value(value, "id").contains("631")
          case _ => false
        }
        if (work) {
          sendStarted.countDown()
          if (!release.await(2, TimeUnit.SECONDS)) fail("blocked response was never released")
        }
        synchronized { values :+= outbound }
      }
      def written: List[JsonRpc.Outbound] = synchronized { values }
    }

    val plane = new BlockingResponsePlane
    val scheduler = new BoundedConcurrentScheduler(1, "kernel-drain-write-worker")
    val deadlines = new ManualDeadlineScheduler
    val connection = ConnectionKernel(
      policy = policy(1, shutdownDrain = 1.0), dataPlane = plane, revisionRules = rules,
      scheduler = scheduler, deadlineScheduler = deadlines,
      registry = new RequestRegistry(RequestRegistry.InvariantViolationPolicy.FailFast),
      application = application, serverInfo = serverInfo)
    ready(connection, 630)
    connection.handle(RevisionRules.Application(McpApplication.Operation.ToolsList, requestId(631)))
    assert(plane.sendStarted.await(2, TimeUnit.SECONDS), "winning response did not enter DataPlane.send")
    val release = new Thread(new Runnable {
      def run(): Unit = { Thread.sleep(50L); plane.release.countDown() }
    }, "kernel-drain-write-release")
    release.setDaemon(true)
    release.start()
    val result = connection.drainAndClose()
    release.join(2000L)
    assert(result.drained)
    assertEquals(plane.written.count(value => JsonRpc.value(value) match {
      case json: JSON.Object.T @unchecked => JSON.value(json, "id").contains("631")
      case _ => false
    }), 1)
    assertEquals(connection.phase, ConnectionLifecycle.Closed)
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
      deadlineScheduler = new ManualDeadlineScheduler,
      registry = new RequestRegistry(RequestRegistry.InvariantViolationPolicy.FailFast),
      application = application, serverInfo = serverInfo)
    intercept[IOException] {
      connection.handle(RevisionRules.Initialize(requestId(100), ProtocolRevision.V2025_03_26.value))
    }
    assertEquals(connection.phase, ConnectionLifecycle.Closing)
    assert(scheduler.isShutdown)
  }

  spec_test("output failure closes the gate before another completed worker can write",
      covers = List("connection_kernel#T5")) {
    import java.io.IOException
    import java.util.concurrent.{CountDownLatch, TimeUnit}

    final class FirstFailurePlane extends DataPlane {
      private var values = List.empty[JsonRpc.Outbound]
      val failingSendStarted = new CountDownLatch(1)
      val releaseFailure = new CountDownLatch(1)

      def receive(): Option[JsonRpc.Inbound] = None
      def send(outbound: JsonRpc.Outbound): Unit = {
        val id = JsonRpc.value(outbound) match {
          case value: JSON.Object.T @unchecked => JSON.value(value, "id")
          case _ => None
        }
        if (id.contains("fail")) {
          failingSendStarted.countDown()
          if (!releaseFailure.await(2, TimeUnit.SECONDS)) fail("failing send was never released")
          throw new IOException("closed output")
        }
        synchronized { values :+= outbound }
      }
      def written: List[JsonRpc.Outbound] = synchronized { values }
    }

    val plane = new FirstFailurePlane
    val scheduler = new BoundedConcurrentScheduler(2, "kernel-output-gate-worker")
    val deadlines = new ManualDeadlineScheduler
    val connection = ConnectionKernel(
      policy = policy(2), dataPlane = plane, revisionRules = rules, scheduler = scheduler,
      deadlineScheduler = deadlines,
      registry = new RequestRegistry(RequestRegistry.InvariantViolationPolicy.FailFast),
      application = application, serverInfo = serverInfo)
    ready(connection, 640)
    connection.handle(RevisionRules.Application(McpApplication.Operation.ToolsList,
      RequestId.string("fail")))
    assert(plane.failingSendStarted.await(2, TimeUnit.SECONDS), "first response never reached output")
    connection.handle(RevisionRules.Application(McpApplication.Operation.ToolsList,
      RequestId.string("later")))
    val completed = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    while (!connection.registry.snapshot.activeIds.isEmpty && System.nanoTime() < completed)
      Thread.sleep(2L)
    plane.releaseFailure.countDown()
    val closed = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    while (connection.phase != ConnectionLifecycle.Closing && System.nanoTime() < closed)
      Thread.sleep(2L)
    assertEquals(connection.phase, ConnectionLifecycle.Closing)
    Thread.sleep(20L)
    assertEquals(plane.written.count(value => JsonRpc.value(value) match {
      case json: JSON.Object.T @unchecked => JSON.value(json, "id").contains("later")
      case _ => false
    }), 0, "a second response started after output failure closed the gate")
  }
}
