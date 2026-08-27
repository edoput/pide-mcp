/*  Title:      mcp_test/src/mcp_connection_kernel_tests.scala

Checkpoint-4 contracts for validated policy, revision classification,
lifecycle control, and deterministic scheduling without Isabelle.
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
      scheduler = new DeterministicSequentialScheduler)

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
      scheduler = new DeterministicSequentialScheduler)

    assertEquals(ConnectionPolicy.MaxInFlight.value(kernel0.policy.admission.maxInFlight), 2)
    assertEquals(ConnectionPolicy.MaxInFlight.value(
      checked(ConnectionPolicy.fromOptions(changed)).admission.maxInFlight), 9)
    assert(kernel0.policy eq snapshot, "kernel must retain the construction-time policy snapshot")
  }

  test("MCP 2025-03-26 rules classify valid and invalid wire messages") {
    val initialize = request(Some(1), "initialize",
      Some(JSON.Object("protocolVersion" -> ProtocolRevision.V2025_03_26.value)))
    assertEquals(rules.classify(JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(initialize))),
      RevisionRules.Initialize(1, ProtocolRevision.V2025_03_26.value))
    assertEquals(rules.classify(JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(
      request(Some(1), "initialize", Some(JSON.Object("protocolVersion" -> "2024-11-05")))))),
      RevisionRules.Initialize(1, "2024-11-05"))
    assertEquals(rules.classify(JsonRpc.Inbound.Malformed("{")),
      RevisionRules.Invalid(RevisionRules.NullReply, RevisionRules.ParseError, "Parse error"))
    assertEquals(rules.classify(JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(
      JSON.Object("jsonrpc" -> "1.0", "id" -> 1, "method" -> "ping")))),
      RevisionRules.Invalid(RevisionRules.ReplyId(1), RevisionRules.InvalidRequest,
        "jsonrpc must be 2.0"))
    assertEquals(rules.classify(JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(
      JSON.Object("jsonrpc" -> "1.0", "method" -> "ping")))),
      RevisionRules.Invalid(RevisionRules.NoReply, RevisionRules.InvalidRequest,
        "jsonrpc must be 2.0"))
    assertEquals(rules.classify(JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(
      JSON.Object("jsonrpc" -> "2.0", "id" -> null, "method" -> "ping")))),
      RevisionRules.Invalid(RevisionRules.NullReply, RevisionRules.InvalidRequest,
        "id must be a string or number"))
    assertEquals(rules.classify(JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(
      request(Some(2), "tools/call")))),
      RevisionRules.Invalid(RevisionRules.ReplyId(2), RevisionRules.InvalidParams, "Missing tool name"))
    assertEquals(rules.classify(JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(
      request(None, "notifications/initialized")))), RevisionRules.Initialized)
    val resourceRead = JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(
      request(Some(3), "resources/read", Some(JSON.Object("uri" -> "isabelle://session")))))
    assertEquals(rules.classify(resourceRead),
      RevisionRules.Application(McpApplication.Operation.ResourcesRead("isabelle://session"), 3))
    assertEquals(rules.classify(JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(
      request(None, "tools/list")))), RevisionRules.Ignored)
    assertEquals(rules.classify(JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(
      request(None, "ping")))), RevisionRules.Ignored)
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
    val ping = JSON.Format(request(Some(2), "ping"))
    val operation = JSON.Format(request(Some(3), "tools/list"))
    val connection = kernel(List(operation, initialize, ping, ping, initialized, operation))

    assertEquals(connection.receive(),
      Some(ConnectionLifecycle.Rejected(RevisionRules.ReplyId(3), RevisionRules.InvalidRequest,
        "ordinary operations are only valid in Ready")))
    assertEquals(connection.phase, ConnectionLifecycle.Fresh)
    assertEquals(connection.admit(RevisionRules.Initialized),
      ConnectionLifecycle.Rejected(RevisionRules.NoReply, RevisionRules.InvalidRequest,
        "notifications/initialized is only valid after initialize"))
    assertEquals(connection.admit(RevisionRules.Ignored), ConnectionLifecycle.Ignored)
    assertEquals(connection.receive(), Some(ConnectionLifecycle.InitializeAccepted(
      1, ProtocolRevision.V2025_03_26.value)))
    assertEquals(connection.phase, ConnectionLifecycle.Initializing)
    assertEquals(connection.receive(), Some(ConnectionLifecycle.Rejected(RevisionRules.ReplyId(2),
      RevisionRules.InvalidRequest,
      "ping is not valid in Initializing")))
    connection.initializeCompleted()
    assertEquals(connection.phase, ConnectionLifecycle.AwaitingInitialized)
    assertEquals(connection.receive(), Some(ConnectionLifecycle.PingAccepted(2)))
    assertEquals(connection.receive(), Some(ConnectionLifecycle.InitializedAccepted))
    assertEquals(connection.phase, ConnectionLifecycle.Ready)
    assertEquals(connection.receive(), Some(ConnectionLifecycle.OperationAccepted(
      RevisionRules.Application(McpApplication.Operation.ToolsList, 3))))

    connection.beginClosing()
    assertEquals(connection.phase, ConnectionLifecycle.Closing)
    assertEquals(connection.receive(), None)
    assertEquals(connection.admit(RevisionRules.Application(McpApplication.Operation.ToolsList, 4)),
      ConnectionLifecycle.Rejected(RevisionRules.ReplyId(4), RevisionRules.InvalidRequest,
        "ordinary operations are only valid in Ready"))
    connection.finishClosing()
    assertEquals(connection.phase, ConnectionLifecycle.Closed)
    assertEquals(connection.admit(RevisionRules.Application(McpApplication.Operation.ToolsList, 5)),
      ConnectionLifecycle.Rejected(RevisionRules.ReplyId(5), RevisionRules.InvalidRequest,
        "ordinary operations are only valid in Ready"))

    val unsupported = new ConnectionLifecycle
    assertEquals(unsupported.admit(RevisionRules.Initialize(6, "2024-11-05")),
      ConnectionLifecycle.InitializeAccepted(6, "2024-11-05"))
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
}
