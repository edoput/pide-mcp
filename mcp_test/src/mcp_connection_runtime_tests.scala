/*  Title:      mcp_test/src/mcp_connection_runtime_tests.scala

Composition-root and independent-readiness contracts for one MCP connection.
*/

package isabelle.mcp

import isabelle._
import isabelle.mcp.application.{McpApplication, McpOutputPolicy}
import isabelle.mcp.connection._
import isabelle.mcp.control.ManualDeadlineScheduler
import isabelle.mcp.transport.{ScriptedDataPlane, StdioDataPlane}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, IOException}
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger


class MCP_Connection_Runtime_Tests extends MCP_Suite {
  private def checked[A](value: Either[String, A]): A =
    value.fold(message => fail(message), identity)

  private def policy(maxInFlight: Int = 1, maxInputMessageBytes: Int = 1048576): ConnectionPolicy =
    ConnectionPolicy(
      revision = ProtocolRevision.V2025_03_26,
      framing = ConnectionPolicy.FramingPolicy(
        checked(ConnectionPolicy.MaxInputMessageBytes.checked(maxInputMessageBytes))),
      admission = ConnectionPolicy.AdmissionPolicy(
        checked(ConnectionPolicy.MaxInFlight.checked(maxInFlight))),
      timing = ConnectionPolicy.TimingPolicy(
        checked(ConnectionPolicy.RequestTimeout.checked(5.0)),
        checked(ConnectionPolicy.ShutdownDrain.checked(0.0))))

  private val serverInfo = ConnectionKernel.ServerInfo("runtime-test", "1")

  spec_test("composition root retains each injected port behind the shared kernel contract",
      verifies = List("connection_kernel#I1")) {
    val plane = new ScriptedDataPlane(Nil)
    val rules = new Mcp2025RevisionRules
    val scheduler = new DeterministicSequentialScheduler(2)
    val deadlines = new ManualDeadlineScheduler
    val application = new McpApplication {
      def execute(operation: McpApplication.Operation,
          cancellation: McpApplication.Cancellation) =
        McpApplication.Outcome.Result(JSON.Object())
    }
    val invariantPolicy = new RequestRegistry.InvariantViolationPolicy.MarkBroken
    val policySelections = new AtomicInteger(0)
    val senderInstallations = new AtomicInteger(0)
    val shutdowns = new AtomicInteger(0)

    val runtime = ConnectionRuntime.compose(
      policy = policy(maxInFlight = 2),
      dataPlane = plane,
      revisionRules = rules,
      scheduler = scheduler,
      deadlineScheduler = deadlines,
      application = application,
      invariantPolicy = _ => { policySelections.incrementAndGet(); invariantPolicy },
      progress = new Progress,
      installChangedSender = _ => senderInstallations.incrementAndGet(),
      onShutdown = () => shutdowns.incrementAndGet(),
      serverInfo = serverInfo)

    assert(runtime.connection.dataPlane eq plane)
    assert(runtime.connection.revisionRules eq rules)
    assert(runtime.connection.scheduler eq scheduler)
    assert(runtime.connection.deadlineScheduler eq deadlines)
    assert(runtime.connection.application eq application)
    assertEquals(policySelections.get(), 1)
    assertEquals(senderInstallations.get(), 1)

    runtime.serve()
    assertEquals(shutdowns.get(), 1)
    assert(scheduler.isShutdown)
    assert(deadlines.isShutdown)
  }

  spec_test("one-shot stdio runtime keeps one application instance for the full client lifetime",
      verifies = List("connection_kernel#A2")) {
    val messages = List(
      JSON.Object("jsonrpc" -> "2.0", "id" -> "init", "method" -> "initialize",
        "params" -> JSON.Object("protocolVersion" -> ProtocolRevision.V2025_03_26.value)),
      JSON.Object("jsonrpc" -> "2.0", "method" -> "notifications/initialized"),
      JSON.Object("jsonrpc" -> "2.0", "id" -> "first", "method" -> "tools/list"),
      JSON.Object("jsonrpc" -> "2.0", "id" -> "second", "method" -> "tools/list"))
    val bytes = messages.map(JSON.Format.apply).mkString("", "\n", "\n")
      .getBytes(StandardCharsets.UTF_8)
    val output = new ByteArrayOutputStream
    val plane = new StdioDataPlane(new ByteArrayInputStream(bytes), output, 1048576)
    val executions = new AtomicInteger(0)
    val application = new McpApplication {
      def execute(operation: McpApplication.Operation,
          cancellation: McpApplication.Cancellation) =
        McpApplication.Outcome.Result(JSON.Object("sequence" -> executions.incrementAndGet()))
    }
    val shutdowns = new AtomicInteger(0)
    val runtime = ConnectionRuntime.compose(
      policy = policy(),
      dataPlane = plane,
      revisionRules = new Mcp2025RevisionRules,
      scheduler = new DeterministicSequentialScheduler(1),
      deadlineScheduler = new ManualDeadlineScheduler,
      application = application,
      invariantPolicy = _ => RequestRegistry.InvariantViolationPolicy.FailFast,
      progress = new Progress,
      installChangedSender = _ => (),
      onShutdown = () => shutdowns.incrementAndGet(),
      serverInfo = serverInfo)

    runtime.serve()
    val replies = output.toString(StandardCharsets.UTF_8).linesIterator
      .filter(_.nonEmpty).flatMap(JSON.Format.unapply).toList
    val byId = replies.flatMap(reply => JSON.string(reply, "id").map(_ -> reply)).toMap
    assertEquals(get(byId("first"), "result", "sequence"), 1L)
    assertEquals(get(byId("second"), "result", "sequence"), 2L)
    assertEquals(executions.get(), 2)
    assertEquals(shutdowns.get(), 1)

    intercept[RuntimeException] { runtime.serve() }
    assertEquals(shutdowns.get(), 1, "a rejected second client reran backend teardown")
  }

  spec_test("oversized stdio input closes once without reaching the application",
      covers = List("connection_kernel#T13")) {
    val plane = new StdioDataPlane(
      new ByteArrayInputStream("12345\n{}\n".getBytes(StandardCharsets.UTF_8)),
      new ByteArrayOutputStream, 4)
    val executions = new AtomicInteger(0)
    val shutdowns = new AtomicInteger(0)
    val runtime = ConnectionRuntime.compose(
      policy = policy(maxInputMessageBytes = 4),
      dataPlane = plane,
      revisionRules = new Mcp2025RevisionRules,
      scheduler = new DeterministicSequentialScheduler(1),
      deadlineScheduler = new ManualDeadlineScheduler,
      application = new McpApplication {
        def execute(operation: McpApplication.Operation,
            cancellation: McpApplication.Cancellation) = {
          executions.incrementAndGet()
          McpApplication.Outcome.Result(JSON.Object())
        }
      },
      invariantPolicy = _ => RequestRegistry.InvariantViolationPolicy.FailFast,
      progress = new Progress,
      installChangedSender = _ => (),
      onShutdown = () => shutdowns.incrementAndGet(),
      serverInfo = serverInfo)

    intercept[IOException](runtime.serve())
    assertEquals(executions.get(), 0)
    assertEquals(shutdowns.get(), 1)
  }

  spec_test("MCP Ready is independent from Isabelle backend readiness",
      covers = List("connection_kernel#T9")) {
    var readiness: McpApplication.Readiness = McpApplication.Not_Ready("building MCP-HOL")
    val application = McpApplication.isabelle(
      () => readiness, "MCP-HOL", Nil, "MCP_Repl", McpOutputPolicy.TestDefault)
    val plane = new ScriptedDataPlane(Nil)
    val connection = ConnectionKernel(
      policy = policy(),
      dataPlane = plane,
      revisionRules = new Mcp2025RevisionRules,
      scheduler = new DeterministicSequentialScheduler(1),
      deadlineScheduler = new ManualDeadlineScheduler,
      registry = new RequestRegistry(RequestRegistry.InvariantViolationPolicy.FailFast),
      application = application,
      serverInfo = serverInfo)

    connection.handle(RevisionRules.Initialize(
      RequestId.string("readiness-init"), ProtocolRevision.V2025_03_26.value))
    connection.handle(RevisionRules.Initialized)
    assertEquals(connection.phase, ConnectionLifecycle.Ready)

    def call(id: String): JSON.T = {
      connection.handle(RevisionRules.Application(
        McpApplication.Operation.ToolsCall("repl_list", JSON.Object()), RequestId.string(id)))
      assertEquals(connection.phase, ConnectionLifecycle.Ready,
        "backend readiness changed MCP lifecycle acceptance")
      JSON.Format.unapply(plane.written.last).getOrElse(fail("missing readiness response"))
    }

    val notReady = assert_is_error(call("not-ready"))
    assert(notReady.contains("building MCP-HOL"), notReady)

    readiness = McpApplication.Ready(new Fake_Backend)
    assert_no_error(call("ready"))

    readiness = McpApplication.Failed("heap boot failed")
    val failed = assert_is_error(call("failed"))
    assert(failed.contains("failed") && failed.contains("heap boot failed"), failed)

    assert(connection.drainAndClose().drained)
  }
}
