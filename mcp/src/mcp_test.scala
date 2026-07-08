/*  Title:      mcp/src/mcp_test.scala

Unit tests for the mcp component: isabelle mcp_test.

The default run exercises the JSON-RPC handler and the stdio loop
against Fake_Backend — fast, no prover. With -b it additionally starts
a headless PIDE session on MCP-Tools and tests the ML bridge (protocol
commands, promise routing), the one layer a fake cannot cover.
*/

package isabelle.mcp

import isabelle._

import java.io.{BufferedReader, ByteArrayOutputStream, PrintStream, StringReader}
import java.nio.charset.StandardCharsets

object MCP_Test {
  /* fake backend */

  class Fake_Backend extends MCP_Backend {
    var stopped = false
    def ml_tools(): List[(String, String)] = List(("shout", "uppercase the input"))
    def ml_run(name: String, arg: String): MCP_Session.Result =
      if (name == "shout") MCP_Session.Ok(arg.toUpperCase)
      else MCP_Session.Error("Unknown MCP tool " + quote(name))
    def mcp_resources(): List[(String, String, String)] =
      List(("isabelle://session", "session", "current session overview"))
    def mcp_resource_read(uri: String): MCP_Session.Result =
      if (uri == "isabelle://session")
        MCP_Session.Ok("session: TEST\ntheories: Fake_Theory")
      else MCP_Session.Error("Unknown MCP resource " + quote(uri))
    def stop(): Unit = stopped = true
  }


  /* assertions and json access */

  private def check(cond: Boolean, msg: => String): Unit = if (!cond) error(msg)

  private def get(json: JSON.T, path: String*): JSON.T =
    path.foldLeft(json)((j, field) =>
      JSON.value(j, field).getOrElse(
        error("missing field " + quote(field) + " in " + JSON.Format(j))))

  private def get_string(json: JSON.T, path: String*): String =
    get(json, path: _*) match {
      case s: String => s
      case other => error("not a string: " + JSON.Format(other))
    }

  private def get_list(json: JSON.T, path: String*): List[JSON.T] =
    get(json, path: _*) match {
      case l: List[_] => l
      case other => error("not a list: " + JSON.Format(other))
    }

  private def request(id: Option[Long], method: String, params: Option[JSON.Object.T]): JSON.T = {
    var obj = JSON.Object("jsonrpc" -> "2.0", "method" -> method)
    for (i <- id) obj += ("id" -> i)
    for (p <- params) obj += ("params" -> p)
    obj
  }


  /* test runner */

  type Test = (String, () => Unit)

  def run_tests(progress: Progress, tests: List[Test]): Int = {
    var failures = 0
    for ((name, body) <- tests) {
      Exn.capture { body() } match {
        case Exn.Res(_) => progress.echo("PASS " + name)
        case Exn.Exn(exn) =>
          failures += 1
          progress.echo_error_message("FAIL " + name + ": " + Exn.message(exn))
      }
    }
    failures
  }


  /* unit tests: handler + loop over Fake_Backend */

  def unit_tests(): List[Test] = {
    def handler() = new MCP_Server.Handler(new Fake_Backend)

    List(
      ("initialize echoes protocolVersion", () => {
        val reply = handler().handle(request(Some(1), "initialize",
          Some(JSON.Object("protocolVersion" -> "TEST-VERSION")))).get
        check(get_string(reply, "result", "protocolVersion") == "TEST-VERSION",
          "protocolVersion not echoed")
        check(get_string(reply, "result", "serverInfo", "name") == MCP_Server.server_name,
          "bad serverInfo.name")
        get(reply, "result", "capabilities", "tools")
        get(reply, "result", "capabilities", "resources")
      }),

      ("initialize without params falls back to default version", () => {
        val reply = handler().handle(request(Some(1), "initialize", None)).get
        check(get_string(reply, "result", "protocolVersion") ==
          MCP_Server.default_protocol_version, "unexpected default protocolVersion")
      }),

      ("notifications/initialized gets no reply", () =>
        check(handler().handle(request(None, "notifications/initialized", None)).isEmpty,
          "unexpected reply to notification")),

      ("ping replies with empty result", () => {
        val reply = handler().handle(request(Some(2), "ping", None)).get
        check(get(reply, "result") == JSON.Object(), "non-empty ping result")
      }),

      ("unknown method with id yields -32601", () => {
        val reply = handler().handle(request(Some(3), "no/such/method", None)).get
        check(get(reply, "error", "code") == MCP_Server.RPC.METHOD_NOT_FOUND,
          "expected METHOD_NOT_FOUND")
      }),

      ("unknown notification is ignored", () =>
        check(handler().handle(request(None, "no/such/method", None)).isEmpty,
          "unexpected reply to unknown notification")),

      ("tools/list reports the backend tools with the fixed schema", () => {
        val reply = handler().handle(request(Some(4), "tools/list", None)).get
        val tools = get_list(reply, "result", "tools")
        check(tools.length == 1, "expected 1 tool, got " + tools.length)
        check(get_string(tools.head, "name") == "shout", "bad tool name")
        check(get_string(tools.head, "description") == "uppercase the input",
          "bad tool description")
        check(get_list(tools.head, "inputSchema", "required") == List("input"),
          "bad inputSchema.required")
      }),

      ("tools/call runs the tool", () => {
        val reply = handler().handle(request(Some(5), "tools/call",
          Some(JSON.Object("name" -> "shout",
            "arguments" -> JSON.Object("input" -> "isabelle"))))).get
        val content = get_list(reply, "result", "content")
        check(get_string(content.head, "text") == "ISABELLE", "bad tool result")
        check(JSON.value(get(reply, "result"), "isError").isEmpty, "unexpected isError")
      }),

      ("tools/call without input is a tool error", () => {
        val reply = handler().handle(request(Some(6), "tools/call",
          Some(JSON.Object("name" -> "shout", "arguments" -> JSON.Object())))).get
        check(get(reply, "result", "isError") == true, "missing isError")
        check(get_string(get_list(reply, "result", "content").head, "text").contains("input"),
          "error text does not mention the missing argument")
      }),

      ("tools/call without name yields -32602", () => {
        val reply = handler().handle(request(Some(7), "tools/call",
          Some(JSON.Object("arguments" -> JSON.Object("input" -> "x"))))).get
        check(get(reply, "error", "code") == MCP_Server.RPC.INVALID_PARAMS,
          "expected INVALID_PARAMS")
      }),

      ("tools/call backend error is a tool error", () => {
        val reply = handler().handle(request(Some(8), "tools/call",
          Some(JSON.Object("name" -> "no_such_tool",
            "arguments" -> JSON.Object("input" -> "x"))))).get
        check(get(reply, "result", "isError") == true, "missing isError")
        check(get_string(get_list(reply, "result", "content").head, "text")
          .contains("no_such_tool"), "error text does not name the tool")
      }),

      ("resources/list reports the backend resources", () => {
        val reply = handler().handle(request(Some(10), "resources/list", None)).get
        val resources = get_list(reply, "result", "resources")
        check(resources.length == 1, "expected 1 resource, got " + resources.length)
        check(get_string(resources.head, "uri") == "isabelle://session", "bad resource uri")
        check(get_string(resources.head, "name") == "session", "bad resource name")
      }),

      ("resources/read returns the resource contents", () => {
        val reply = handler().handle(request(Some(11), "resources/read",
          Some(JSON.Object("uri" -> "isabelle://session")))).get
        val contents = get_list(reply, "result", "contents")
        check(get_string(contents.head, "uri") == "isabelle://session", "bad contents uri")
        val text = get_string(contents.head, "text")
        check(text.contains("session: TEST"), "bad contents text: " + text)
        check(text.contains("theories: Fake_Theory"), "missing theories line: " + text)
      }),

      ("resources/read on an unknown uri yields an error", () => {
        val reply = handler().handle(request(Some(12), "resources/read",
          Some(JSON.Object("uri" -> "isabelle://no-such-resource")))).get
        check(get(reply, "error", "code") == MCP_Server.RPC.INVALID_PARAMS,
          "expected INVALID_PARAMS")
      }),

      ("resources/read without uri yields -32602", () => {
        val reply = handler().handle(request(Some(13), "resources/read",
          Some(JSON.Object()))).get
        check(get(reply, "error", "code") == MCP_Server.RPC.INVALID_PARAMS,
          "expected INVALID_PARAMS")
      }),

      ("malformed json yields -32700", () => {
        val reply = handler().handle_line("{not json").get
        check(get(reply, "error", "code") == MCP_Server.RPC.PARSE_ERROR,
          "expected PARSE_ERROR")
      }),

      ("request without method yields -32600", () => {
        val reply = handler().handle(JSON.Object("jsonrpc" -> "2.0", "id" -> 9)).get
        check(get(reply, "error", "code") == MCP_Server.RPC.INVALID_REQUEST,
          "expected INVALID_REQUEST")
      }),

      ("serve replies per line, skips blanks, stops the backend on EOF", () => {
        val backend = new Fake_Backend
        val input =
          List(
            JSON.Format(request(Some(1), "initialize", None)),
            "",
            "{garbage",
            JSON.Format(request(Some(2), "tools/call",
              Some(JSON.Object("name" -> "shout",
                "arguments" -> JSON.Object("input" -> "hi")))))
          ).mkString("\n")
        val out_stream = new ByteArrayOutputStream
        val out = new PrintStream(out_stream, true, StandardCharsets.UTF_8)
        MCP_Server.serve(backend, new BufferedReader(new StringReader(input)), out)
        check(backend.stopped, "backend not stopped on EOF")
        val lines = split_lines(out_stream.toString(StandardCharsets.UTF_8)).filter(_.nonEmpty)
        check(lines.length == 3, "expected 3 reply lines, got " + lines.length)
        check(lines.forall(l => JSON.Format.unapply(l).isDefined), "reply line not valid json")
        check(lines(2).contains("HI"), "tool result missing from last reply")
      }))
  }


  /* bridge tests: real headless session on MCP-Tools */

  def bridge_tests(
    options: Options,
    session_dirs: List[Path],
    progress: Progress
  ): Int = {
    progress.echo("Starting PIDE session for bridge tests ...")
    val session =
      MCP_Session.start(options, "MCP-Tools", session_dirs, "MCP_Tools", progress = progress)
    try {
      run_tests(progress,
        List(
          ("bridge: ml_tools lists shout", () => {
            val tools = session.ml_tools()
            check(tools.exists(_._1 == "shout"), "shout not in " + tools.toString)
          }),

          ("bridge: ml_run round trip", () =>
            check(session.ml_run("shout", "isabelle") == MCP_Session.Ok("ISABELLE"),
              "bad ml_run result")),

          ("bridge: ml_run unknown tool is an error", () =>
            session.ml_run("no_such_tool", "x") match {
              case MCP_Session.Error(msg) =>
                check(msg.contains("no_such_tool"), "error message does not name the tool")
              case result => error("expected error, got " + result.toString)
            }),

          ("bridge: isabelle://session resource reads the loaded theory", () =>
            session.mcp_resource_read("isabelle://session") match {
              case MCP_Session.Ok(text) =>
                check(text.contains("MCP_Tools"), "session resource missing theory name: " + text)
              case result => error("expected Ok, got " + result.toString)
            }),

          ("bridge: isabelle://session resource lists loaded theories", () =>
            session.mcp_resource_read("isabelle://session") match {
              case MCP_Session.Ok(text) =>
                check(text.contains("theories:"), "session resource missing theories field: " + text)
                check(text.contains("MCP_Tools"),
                  "session resource theories list missing MCP_Tools: " + text)
              case result => error("expected Ok, got " + result.toString)
            })))
    }
    finally session.stop()
  }


  /* isabelle tool wrapper */

  val isabelle_tool =
    Isabelle_Tool("mcp_test", "run mcp component unit tests", Scala_Project.here,
    { args =>
      var bridge = false
      var session_dirs: List[Path] = Nil

      val getopts = Getopts("""
Usage: isabelle mcp_test [OPTIONS]

  Options are:
    -b           also test the ML bridge against a real PIDE session
    -d DIR       session directory for -b (default: $ISABELLE_MCP_HOME/Tools)

  Run the mcp component unit tests: JSON-RPC handler and stdio loop
  against a fake backend (fast, no prover). With -b, additionally start
  a headless PIDE session on MCP-Tools and test the protocol-command
  bridge end to end.
""",
        "b" -> (_ => bridge = true),
        "d:" -> (arg => session_dirs = session_dirs ::: List(Path.explode(arg))))

      val more_args = getopts(args)
      if (more_args.nonEmpty) getopts.usage()

      val progress = new Console_Progress()

      var failures = run_tests(progress, unit_tests())
      if (bridge) {
        val dirs =
          if (session_dirs.isEmpty) List(Path.explode("$ISABELLE_MCP_HOME/Tools"))
          else session_dirs
        failures += bridge_tests(Options.init(), dirs, progress)
      }

      if (failures > 0) error(failures.toString + " test(s) failed")
      else progress.echo("All tests passed")
    })
}
