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
}
