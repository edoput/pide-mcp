/*  Title:      mcp_test/src/mcp_application_tests.scala

Focused contracts for the checkpoint-3 application port.  These tests carry
no connection_kernel plan links: lifecycle, scheduler, and cancellation races
remain later checkpoints.
*/

package isabelle.mcp

import isabelle._
import isabelle.mcp.application.{McpApplication, McpOutputPolicy}


class MCP_Application_Tests extends MCP_Suite {
  import McpApplication.{Cancellation, Operation, Outcome}

  private final class RecordingApplication extends McpApplication {
    var operations = List.empty[Operation]
    var cancellations = List.empty[Boolean]

    def execute(operation: Operation, cancellation: Cancellation): Outcome = {
      operations = operations :+ operation
      cancellations = cancellations :+ cancellation.isCancelled
      operation match {
        case Operation.ResourcesRead(_) => Outcome.InvalidParams("recorded invalid resource")
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
    h.handle(request(Some(13), "resources/list", None)).getOrElse(fail("missing reply"))
    h.handle(request(Some(14), "resources/templates/list", None)).getOrElse(fail("missing reply"))
    val invalid = h.handle(request(Some(15), "resources/read",
      Some(JSON.Object("uri" -> "isabelle://missing")))).getOrElse(fail("missing reply"))

    assertEquals(JSON.value(listed, "id"), Some(11))
    assertEquals(get(invalid, "error", "code"), MCP_Server.RPC.INVALID_PARAMS)
    assertEquals(JSON.value(invalid, "id"), Some(15))
    assertEquals(application.operations,
      List(
        Operation.ToolsList,
        Operation.ToolsCall("named", arguments),
        Operation.ResourcesList,
        Operation.ResourceTemplatesList,
        Operation.ResourcesRead("isabelle://missing")))
    assertEquals(application.cancellations, List(false, false, false, false, false))
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

  test("disabled untrusted output hides and rejects output-dependent tools before dispatch") {
    class OutputBackend extends Fake_Backend {
      var runs = List.empty[String]
      override def ml_tools(context: String): MCP_Session.Tools_Reply = {
        val reply = super.ml_tools(context)
        reply.copy(rows = reply.rows :+
          MCP_Session.Tool_Row(
            "Fixture.diagnostic", "prints diagnostics", "diag_wrap", Nil,
            MCP_Session.Tool_Annotations.default))
      }
      override def ml_run(name: String, args: List[(String, String)],
          context: String): MCP_Session.Result = {
        runs = runs :+ name
        super.ml_run(name, args, context)
      }
    }

    val backend = new OutputBackend
    val application = McpApplication.isabelle(
      () => McpApplication.Ready(backend), "TEST", Nil, "MCP_Tools",
      McpOutputPolicy.Disabled)

    val listed = application.execute(Operation.ToolsList, Cancellation.Never) match {
      case Outcome.Result(value) => get_list(value, "tools").map(get_string(_, "name"))
      case other => fail("unexpected tools/list outcome: " + other)
    }
    assert(listed.contains("shout"), "direct string result was hidden")
    assert(!listed.contains("diagnostic"), "output-dependent ML tool was advertised")
    assert(!listed.contains("repl_list"), "output-dependent IR builtin was advertised")

    def rejected(name: String): Unit =
      application.execute(Operation.ToolsCall(name, JSON.Object()), Cancellation.Never) match {
        case Outcome.Result(value) =>
          assert(JSON.Format(value).contains("mcp_untrusted_output_bytes is 0"))
        case other => fail("unexpected tools/call outcome: " + other)
      }

    rejected("diagnostic")
    rejected("Fixture.diagnostic")
    rejected("repl_list")
    assertEquals(backend.runs, Nil)
    assertEquals(backend.last_ir, None)

    application.execute(
      Operation.ToolsCall("shout", JSON.Object("input" -> "safe")), Cancellation.Never)
    assertEquals(backend.runs, List("MCP_Tools.shout"))
  }

  test("untrusted output policy rejects negative limits and accepts zero") {
    assert(McpOutputPolicy.checked(-1).isLeft)
    assertEquals(McpOutputPolicy.checked(0), Right(McpOutputPolicy.Disabled))
    assert(McpOutputPolicy.checked(1).exists(_.allowsUntrustedOutput))
  }
}
