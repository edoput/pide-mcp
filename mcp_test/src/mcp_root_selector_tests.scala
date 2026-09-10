/* Live validation of document-root selectors through the production ML endpoint. */
package isabelle.mcp

import isabelle._
import isabelle.mcp.pide._
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.concurrent.duration.{Duration, DurationInt}

class MCP_Root_Selector_Tests extends MCP_Suite {
  override def munitTimeout: Duration = 10.minutes

  private def selector(backend: MCP_Session): PideRootSelector =
    backend.current_root_selector().fold(failure => fail(failure.message), identity)

  private def exchange(backend: MCP_Session, id: String,
      outbound: PideTransport.Outbound): PideBridgeReply = {
    val replies = new LinkedBlockingQueue[PideBridgeReply]()
    val observer = Session.Consumer[Prover.Message]("root-selector-test-" + id) {
      case message: Prover.Protocol_Output
          if Properties.get(message.properties, "id").contains(id) &&
            Properties.get(message.properties, Markup.FUNCTION).contains(PideBridgeV1.ResultFunction) =>
        replies.offer(PideBridgeV1.decode(PideTransport.Inbound(
          PideBridgeV1.ResultFunction, message.properties, message.chunk, message.text)))
      case _ => ()
    }
    backend.session.all_messages += observer
    try {
      backend.session.protocol_command_raw(outbound.command, outbound.arguments)
      Option(replies.poll(10, TimeUnit.SECONDS)).getOrElse(fail("root selector reply timed out: " + id))
    }
    finally backend.session.all_messages -= observer
  }

  private def rejected(reply: PideBridgeReply, detail: String): Unit = reply match {
    case PideBridgeReply.Failure(_, _, failure) =>
      assert(failure.message.contains(detail), failure.message)
    case other => fail("expected selector rejection, received " + other)
  }

  private def rewrite(outbound: PideTransport.Outbound)
      (change: Properties.T => Properties.T): PideTransport.Outbound = {
    val body = YXML.parse_body(outbound.arguments.head)
    val rewritten = body match {
      case List(XML.Elem(Markup(name, properties), payload)) =>
        List(XML.Elem(Markup(name, change(properties)), payload))
      case _ => fail("unexpected request envelope")
    }
    outbound.copy(arguments = List(Bytes(YXML.string_of_body(rewritten))))
  }

  test("live selectors reject malformed fields, non-end states and stale root executions") {
    val options = MCP_Test_Config.options
    val dirs = MCP_Test_Config.session_dirs
    MCP_Session.build(options, "MCP-Tools", dirs, MCP_Test_Config.progress)
    Isabelle_System.with_tmp_dir("mcp-selector") { dir =>
      File.write(dir + Path.basic("ROOT"),
        "session SelectorFixtures = \"MCP-Tools\" +\n  theories SelectorAncestor\n")
      def ancestor(version: String): String = Symbol.encode(s"""theory SelectorAncestor
imports "MCP-Tools.MCP_Tools" begin
mcp_tool selected_probe = ‹K "$version"› (description ‹$version›)
end
""")
      val source = dir + Path.basic("SelectorAncestor.thy")
      File.write(source, ancestor("before"))
      val backend = MCP_Session.boot(options, "MCP-Tools", dir :: dirs,
        "SelectorAncestor", McpBridgeProfile.base, MCP_Test_Config.progress)
      try {
        val original = selector(backend)
        val initial = exchange(backend, "selector-initial",
          PideBridgeV1.hello("selector-initial", original))
        assert(initial.isInstanceOf[PideBridgeReply.Success], initial.toString)

        for ((key, index) <- List("root_node", "root_command", "root_exec").zipWithIndex) {
          val missingId = "selector-missing-" + index
          rejected(exchange(backend, missingId,
            rewrite(PideBridgeV1.hello(missingId, original))(_.filterNot(_._1 == key))),
            "Missing MCP bridge envelope property")
          val duplicateId = "selector-duplicate-" + index
          rejected(exchange(backend, duplicateId,
            rewrite(PideBridgeV1.hello(duplicateId, original))(props => props ::: props.filter(_._1 == key))),
            "Duplicate MCP bridge envelope property")
        }
        val malformedId = "selector-malformed"
        rejected(exchange(backend, malformedId,
          rewrite(PideBridgeV1.hello(malformedId, original))(_.map {
            case ("root_exec", _) => "root_exec" -> "0"
            case property => property
          })), "Malformed MCP bridge root_exec identity")
        rejected(exchange(backend, "selector-name",
          PideBridgeV1.hello("selector-name", original.copy(theory = "Wrong.Root"))),
          "root theory mismatch")

        val snapshot = backend.session.snapshot().version.nodes.iterator
          .find(_._1.node == original.node).map { case (node, _) => backend.session.snapshot(node) }
          .getOrElse(fail("owned root node missing"))
        val first = snapshot.node.commands.iterator.filterNot(_.is_ignored).next()
        val firstExec = snapshot.state.the_assignment(snapshot.version).check_finished.command_execs(first.id).head
        rejected(exchange(backend, "selector-non-end", PideBridgeV1.hello("selector-non-end",
          original.copy(command = first.id, exec = firstExec))), "no completed theory end")

        File.write(source, ancestor("after"))
        val checked = backend.check_theory("SelectorAncestor", File.standard_path(dir))
        assert(checked.ok, checked.toString)
        val current = selector(backend)
        assert(current.exec != original.exec, "ancestor change did not reexecute the wrapper end")
        val stale = current.copy(exec = original.exec)
        rejected(exchange(backend, "selector-stale-hello",
          PideBridgeV1.hello("selector-stale-hello", stale)), "Stale MCP bridge root execution")
        rejected(exchange(backend, "selector-stale-call",
          PideBridgeV1.call("selector-stale-call", stale, "tools", XML.Encode.string("root"))),
          "Stale MCP bridge root execution")
        val fresh = exchange(backend, "selector-fresh",
          PideBridgeV1.call("selector-fresh", current, "tools", XML.Encode.string("root")))
        assert(fresh.isInstanceOf[PideBridgeReply.Success], fresh.toString)
        assert(backend.ml_tools().rows.exists(row => row.name.endsWith(".selected_probe") &&
          row.description == "after"))
      }
      finally backend.stop()
    }
  }
}
