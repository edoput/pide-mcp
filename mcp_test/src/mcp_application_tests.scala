/*  Title:      mcp_test/src/mcp_application_tests.scala

Focused contracts for the checkpoint-3 application port.  These tests carry
no connection_kernel plan links: lifecycle, scheduler, and cancellation races
remain later checkpoints.
*/

package isabelle.mcp

import isabelle._
import isabelle.mcp.application.McpApplication


class MCP_Application_Tests extends MCP_Suite {
  import McpApplication.{Cancellation, Operation, Outcome}

  private final class RecordingApplication extends McpApplication {
    var operations = List.empty[Operation]
    var cancellations = List.empty[Boolean]

    def execute(operation: Operation, cancellation: Cancellation): Outcome = {
      operations = operations :+ operation
      cancellations = cancellations :+ cancellation.isCancelled
      operation match {
        case Operation.ToolsCall("invalid", _) => Outcome.InvalidParams("recorded invalid arguments")
        case _ => Outcome.Result(JSON.Object("delegated" -> operation.toString))
      }
    }
  }

  private def handler(application: McpApplication): MCP_Server.Handler =
    new MCP_Server.Handler(
      () => MCP_Server.Ready(new Fake_Backend), application = Some(application))

  test("Handler delegates typed application operations while retaining response IDs") {
    val application = new RecordingApplication
    val h = handler(application)
    val arguments = JSON.Object("input" -> "text")

    val listed = h.handle(request(Some(11), "tools/list", None)).getOrElse(fail("missing reply"))
    h.handle(request(Some(12), "tools/call",
      Some(JSON.Object("name" -> "named", "arguments" -> arguments)))).getOrElse(fail("missing reply"))
    val invalid = h.handle(request(Some(15), "tools/call",
      Some(JSON.Object("name" -> "invalid", "arguments" -> JSON.Object()))))
      .getOrElse(fail("missing reply"))

    assertEquals(JSON.value(listed, "id"), Some(11))
    assertEquals(get(invalid, "error", "code"), MCP_Server.RPC.INVALID_PARAMS)
    assertEquals(JSON.value(invalid, "id"), Some(15))
    assertEquals(application.operations,
      List(
        Operation.ToolsList,
        Operation.ToolsCall("named", arguments),
        Operation.ToolsCall("invalid", JSON.Object())))
    assertEquals(application.cancellations, List(false, false, false))
  }

  test("application port receives no wire identity and Handler keeps control operations") {
    val application = new RecordingApplication
    val h = handler(application)

    val first = h.handle(request(Some(21), "tools/list", None)).getOrElse(fail("missing reply"))
    val second = h.handle(request(Some(22), "tools/list", None)).getOrElse(fail("missing reply"))
    val initialized = h.handle(request(Some(23), "initialize", None)).getOrElse(fail("missing reply"))
    val ping = h.handle(request(Some(24), "ping", None)).getOrElse(fail("missing reply"))

    assertEquals(JSON.value(first, "id"), Some(21))
    assertEquals(JSON.value(second, "id"), Some(22))
    assertEquals(application.operations, List(Operation.ToolsList, Operation.ToolsList))
    assertEquals(get(initialized, "result", "serverInfo", "name"), MCP_Server.server_name)
    assertEquals(get(ping, "result"), JSON.Object())
  }
  test("failed ready root listing emits one correlated error without a fallback catalogue") {
    import isabelle.mcp.connection._
    import isabelle.mcp.control.ManualDeadlineScheduler
    import isabelle.mcp.transport.ScriptedDataPlane

    var catalogueCalls = 0
    val backend = new Fake_Backend {
      override def root_context(): MCP_Session.Result =
        MCP_Session.Error("live root evaluation failed")
      override def ml_tools(context: String): MCP_Session.Tools_Reply = {
        catalogueCalls += 1
        super.ml_tools(context)
      }
    }
    val app = McpApplication.isabelle(() => McpApplication.Ready(backend),
      "MCP-Tools", Nil, "MCP_Tools")
    val failure = intercept[Throwable] {
      app.execute(Operation.ToolsList, Cancellation.Never)
    }
    assert(Exn.message(failure).contains("live root evaluation failed"))

    def checked[A](value: Either[String, A]): A = value.fold(fail(_), identity)
    val plane = new ScriptedDataPlane(Nil)
    val scheduler = new ManualSequentialScheduler
    val connection = ConnectionKernel(
      policy = ConnectionPolicy(
        revision = ProtocolRevision.V2025_03_26,
        admission = ConnectionPolicy.AdmissionPolicy(
          checked(ConnectionPolicy.MaxInFlight.checked(1))),
        timing = ConnectionPolicy.TimingPolicy(
          checked(ConnectionPolicy.RequestTimeout.checked(5.0)),
          checked(ConnectionPolicy.ShutdownDrain.checked(0.0)))),
      dataPlane = plane,
      revisionRules = new Mcp2025RevisionRules,
      scheduler = scheduler,
      deadlineScheduler = new ManualDeadlineScheduler,
      registry = new RequestRegistry(RequestRegistry.InvariantViolationPolicy.FailFast),
      application = app,
      serverInfo = ConnectionKernel.ServerInfo("test", "test"))
    connection.handle(RevisionRules.Initialize(RequestId.string("init"),
      ProtocolRevision.V2025_03_26.value))
    connection.handle(RevisionRules.Initialized)
    connection.handle(RevisionRules.Application(Operation.ToolsList, RequestId.string("failed-root")))
    assert(scheduler.runPending())
    val replies = plane.written.flatMap(JSON.Format.unapply)
      .filter(value => JSON.value(value, "id").contains("failed-root"))
    assertEquals(replies.length, 1)
    assertEquals(get(replies.head, "error", "code"), ConnectionKernel.InternalError)
    assertEquals(JSON.value(replies.head, "result"), None)
    assertEquals(connection.registry.snapshot.activeCapacity, 0)
    assertEquals(catalogueCalls, 0)
  }

}
