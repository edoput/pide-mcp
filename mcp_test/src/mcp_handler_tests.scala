/*  Title:      mcp_test/src/mcp_handler_tests.scala

Unit suites over Fake_Backend -- fast, no prover: the JSON-RPC
protocol surface, the tool surface (builtin table rows and their
dispatch onto backend.ir), the resource surface, and the pure codecs.
*/

package isabelle.mcp

import isabelle._

import java.io.{BufferedReader, ByteArrayOutputStream, PipedReader, PipedWriter, PrintStream, StringReader}
import java.nio.charset.StandardCharsets
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import isabelle.mcp.application.McpApplication
import isabelle.mcp.connection.{ConnectionKernel, ConnectionPolicy}

import scala.concurrent.duration.DurationInt


/* protocol: initialize, ping, notifications, malformed input, stdio loop */

class MCP_Protocol_Tests extends MCP_Suite {
  test("initialize echoes protocolVersion") {
    val reply = rpc("initialize", JSON.Object("protocolVersion" -> "TEST-VERSION"))
    assertEquals(get_string(reply, "result", "protocolVersion"), "TEST-VERSION")
    assertEquals(get_string(reply, "result", "serverInfo", "name"), MCP_Server.server_name)
    get(reply, "result", "capabilities", "tools")
    get(reply, "result", "capabilities", "resources")
  }

  test("initialize without params falls back to default version") {
    val reply = rpc("initialize")
    assertEquals(get_string(reply, "result", "protocolVersion"),
      MCP_Server.default_protocol_version)
  }

  test("notifications/initialized gets no reply") {
    assert(notification("notifications/initialized").isEmpty,
      "unexpected reply to notification")
  }

  test("ping replies with empty result") {
    assertEquals(get(rpc("ping"), "result"), JSON.Object())
  }

  test("unknown method with id yields -32601") {
    assertEquals(get(rpc("no/such/method"), "error", "code"),
      MCP_Server.RPC.METHOD_NOT_FOUND)
  }

  test("unknown notification is ignored") {
    assert(notification("no/such/method").isEmpty,
      "unexpected reply to unknown notification")
  }

  test("malformed json yields -32700") {
    val reply = new MCP_Server.Handler(new Fake_Backend).handle_line("{not json")
      .getOrElse(fail("expected a reply"))
    assertEquals(get(reply, "error", "code"), MCP_Server.RPC.PARSE_ERROR)
  }

  test("request without method yields -32600") {
    val reply = new MCP_Server.Handler(new Fake_Backend)
      .handle(JSON.Object("jsonrpc" -> "2.0", "id" -> 9))
      .getOrElse(fail("expected a reply"))
    assertEquals(get(reply, "error", "code"), MCP_Server.RPC.INVALID_REQUEST)
  }

  spec_test("serve drains accepted lifecycle requests before stopping on EOF",
      covers = List("planning_gate#T6", "connection_kernel#T7")) {
    val backend = new Fake_Backend
    val input =
      List(
        JSON.Format(JSON.Object("jsonrpc" -> "2.0", "id" -> "initialize",
          "method" -> "initialize", "params" -> JSON.Object("protocolVersion" -> "2025-03-26"))),
        JSON.Format(JSON.Object("jsonrpc" -> "2.0", "method" -> "notifications/initialized")),
        "",
        "{garbage",
        JSON.Format(JSON.Object("jsonrpc" -> "2.0", "id" -> "call",
          "method" -> "tools/call", "params" -> JSON.Object("name" -> "shout",
            "arguments" -> JSON.Object("input" -> "hi"))))
      ).mkString("\n")
    val out_stream = new ByteArrayOutputStream
    val out = new PrintStream(out_stream, true, StandardCharsets.UTF_8)
    MCP_Server.serve(backend, new BufferedReader(new StringReader(input)), out)
    assert(backend.stopped, "backend not stopped on EOF")
    val lines = split_lines(out_stream.toString(StandardCharsets.UTF_8)).filter(_.nonEmpty)
    assertEquals(lines.length, 3, "expected 3 reply lines")
    assert(lines.forall(l => JSON.Format.unapply(l).isDefined), "reply line not valid json")
    assert(lines(2).contains("HI"), "tool result missing from last reply")
  }
}


/* server startup and readiness (plans/readiness, spec "server startup and
   readiness"): Handler resolves a () => Readiness thunk PER CALL instead
   of taking a live backend at construction, so the json-rpc loop never
   blocks on the prover build. A1-A6 below. */

class MCP_Readiness_Tests extends MCP_Suite {
  spec_test("initialize never touches the backend, in any readiness state",
      verifies = List("readiness#A1"), covers = List("readiness#T1")) {
    val throwing = new Throwing_Backend
    var state: MCP_Server.Readiness = MCP_Server.Not_Ready("building MCP-HOL")
    val handler = new MCP_Server.Handler(() => state)
    get(rpc_on(handler, "initialize"), "result", "capabilities", "tools")

    /* the stronger half of the claim: even once the thunk resolves to a
       backend that throws on ANY call, initialize still must not touch
       it -- this is the scenario that would have caught the mvp's
       ordering bug, where the handshake itself waited on the prover. */
    state = MCP_Server.Ready(throwing)
    get(rpc_on(handler, "initialize"), "result", "capabilities", "tools")
  }

  spec_test("tools/list answers while not ready with exactly the static builtin table",
      verifies = List("readiness#A2"), covers = List("readiness#T2")) {
    val handler = new MCP_Server.Handler(() => MCP_Server.Not_Ready("building MCP-HOL"))
    val tools = get_list(rpc_on(handler, "tools/list"), "result", "tools")
    assertEquals(tools.map(t => get_string(t, "name")).toSet,
      MCP_Server.all_builtin_names.toSet)
    /* Not_Ready carries no backend at all (case class Not_Ready(progress:
       String)) -- ml_tools() being "not called" is not just an
       assertion, it is structurally impossible here. */
  }

  spec_test("tools/call while not ready is isError (not a json-rpc error), naming the progress",
      verifies = List("readiness#A3"), covers = List("readiness#T3")) {
    val handler = new MCP_Server.Handler(() => MCP_Server.Not_Ready("building MCP-HOL"))
    val reply = call_tool_on(handler, "repl_list", JSON.Object())
    assert(JSON.value(reply, "id").isDefined, "reply must echo the request id")
    val text = assert_is_error(reply)
    assert(text.contains("building MCP-HOL"),
      "expected the progress string in the not-ready text: " + text)
  }

  spec_test("Failed is reported distinctly from Not_Ready, carrying the failure message",
      verifies = List("readiness#A4"), covers = List("readiness#T4")) {
    val handler = new MCP_Server.Handler(() => MCP_Server.Failed("boom"))
    val text = assert_is_error(call_tool_on(handler, "repl_list", JSON.Object()))
    assert(text.contains("failed"), "expected \"failed\" in the failed-state text: " + text)
    assert(text.contains("boom"), "expected the failure message: " + text)
  }

  spec_test("Handler holds no cached backend -- a readiness transition is observed immediately",
      verifies = List("readiness#A5"), covers = List("readiness#T5")) {
    var state: MCP_Server.Readiness = MCP_Server.Not_Ready("building")
    val handler = new MCP_Server.Handler(() => state)
    assert_is_error(call_tool_on(handler, "repl_list", JSON.Object()))
    state = MCP_Server.Ready(new Fake_Backend)
    assert_no_error(call_tool_on(handler, "repl_list", JSON.Object()))
  }

  spec_test("isabelle://session reports the readiness state, then the backend's text once ready",
      verifies = List("readiness#A6"), covers = List("readiness#T6")) {
    var state: MCP_Server.Readiness = MCP_Server.Not_Ready("building MCP-HOL")
    val handler =
      new MCP_Server.Handler(() => state,
        session_name = "MCP-HOL", session_dirs = Nil, theory = "MCP_Repl")

    val not_ready_text =
      get_string(
        get_list(rpc_on(handler, "resources/read", JSON.Object("uri" -> "isabelle://session")),
          "result", "contents").head,
        "text")
    assert(not_ready_text.contains("session: MCP-HOL"), "missing session line: " + not_ready_text)
    assert(not_ready_text.contains("theory: MCP_Repl"), "missing theory line: " + not_ready_text)
    assert(not_ready_text.contains("not ready"), "missing not-ready status: " + not_ready_text)
    assert(not_ready_text.contains("building MCP-HOL"), "missing progress string: " + not_ready_text)

    state = MCP_Server.Ready(new Fake_Backend)
    val ready_text =
      get_string(
        get_list(rpc_on(handler, "resources/read", JSON.Object("uri" -> "isabelle://session")),
          "result", "contents").head,
        "text")
    assert(ready_text.contains("session: TEST"), "expected the real backend's own text: " + ready_text)
  }

  test("resources/list while not ready enumerates only isabelle://session") {
    val handler = new MCP_Server.Handler(() => MCP_Server.Not_Ready("building"))
    val resources = get_list(rpc_on(handler, "resources/list"), "result", "resources")
    assertEquals(resources.map(r => get_string(r, "uri")), List("isabelle://session"))
  }

  test("resources/read on a non-session uri while not ready is a protocol error naming the state") {
    val handler = new MCP_Server.Handler(() => MCP_Server.Not_Ready("building MCP-HOL"))
    val reply =
      rpc_on(handler, "resources/read", JSON.Object("uri" -> "isabelle://theory/Main"))
    assertEquals(get(reply, "error", "code"), MCP_Server.RPC.INVALID_PARAMS)
    val message = get_string(reply, "error", "message")
    assert(message.contains("building MCP-HOL"), "expected the progress string: " + message)
  }

  /* decode_message/plain_message -- no plan declares these, so they carry no
     plan link. Failed(message) is built from raw exception/prover text
     (mcp_server.scala run()), which routinely
     carries YXML position markup (literal 0x05/0x06 bytes wrapping "at
     line N of FILE" info). That markup must be stripped before the text
     is stored anywhere an MCP client reads it -- these test the pure
     decode helper directly, without a prover or background thread. */

  test("decode_message strips YXML position markup into readable text") {
    val x = YXML.X_char
    val y = YXML.Y_char
    val raw =
      "Duplicate session \"Scratch\"" +
      x + y + "position" + y + "line=1" + y + "offset=9" + y + "end_offset=18" +
      y + "file=/tmp/b/ROOT" + x +
      " (line 1 of \"/tmp/b/ROOT\")" +
      x + y + x
    val decoded = MCP_Server.decode_message(raw)
    assertEquals(decoded, "Duplicate session \"Scratch\" (line 1 of \"/tmp/b/ROOT\")")
    assert(!decoded.exists(c => c < ' ' && c != '\n' && c != '\t' && c != '\r'),
      "decoded text must contain no control characters: " + decoded)
  }

  test("decode_message round-trips a plain string with no markup unchanged") {
    val plain = "Duplicate session \"Scratch\" already in use for a different ROOT"
    assertEquals(MCP_Server.decode_message(plain), plain)
  }

  test("decode_message falls back to the raw string on malformed/partial YXML") {
    val x = YXML.X_char
    val y = YXML.Y_char
    /* an unterminated element -- push() with no matching pop(): parse_body
       raises "Malformed YXML: unbalanced element", which must not escape
       decode_message and must not silently drop the message either. */
    val broken = "oops" + x + y + "position" + y + "line=1"
    val decoded = MCP_Server.decode_message(broken)
    assertEquals(decoded, broken, "a decode failure must fall back to the original text")
  }

  test("plain_message decodes an exception's YXML-bearing message") {
    val x = YXML.X_char
    val y = YXML.Y_char
    val raw =
      "Duplicate session \"Scratch\"" + x + y + "position" + y + "line=1" + x +
      " (line 1)" + x + y + x
    val exn = ERROR(raw)
    assertEquals(MCP_Server.plain_message(exn), "Duplicate session \"Scratch\" (line 1)")
  }

  test("Failed built via plain_message carries decoded text, not raw YXML bytes") {
    val x = YXML.X_char
    val y = YXML.Y_char
    val raw =
      "Duplicate session \"Scratch\"" + x + y + "position" + y + "line=1" + x +
      " (line 1)" + x + y + x
    val handler = new MCP_Server.Handler(() => MCP_Server.Failed(MCP_Server.plain_message(ERROR(raw))))
    val text = assert_is_error(call_tool_on(handler, "repl_list", JSON.Object()))
    assert(text.contains("Duplicate session \"Scratch\" (line 1)"),
      "expected decoded failure text: " + text)
    assert(!text.exists(c => c == YXML.X_char || c == YXML.Y_char),
      "failed-status text must contain no raw YXML control bytes: " + text)
  }
}


/* tools: ML-registry tools, the builtin table rows, and their dispatch */

class MCP_Tools_Tests extends MCP_Suite {
  test("application preserves a typed bridge timeout as a timeout outcome") {
    class Timeout_Backend extends Fake_Backend {
      override def ml_run_cancellable(name: String, args: List[(String, String)],
          context: String,
          cancellation: McpApplication.Cancellation): MCP_Session.Result =
        throw MCP_Session.BridgeTimedOut(5.seconds)
    }
    val application = McpApplication.isabelle(
      () => McpApplication.Ready(new Timeout_Backend), "TEST", Nil, "MCP_Tools")
    assertEquals(
      application.execute(
        McpApplication.Operation.ToolsCall("shout", JSON.Object("input" -> "hello")),
        McpApplication.Cancellation.Never),
      McpApplication.Outcome.TimedOut(5.seconds))
  }

  spec_test("application forwards the connection cancellation handle to every prover-backed path",
      covers = List("connection_kernel#T4")) {
    class Cancellable_Backend extends Fake_Backend {
      var mlCancellation: Option[McpApplication.Cancellation] = None
      var irCancellation: Option[McpApplication.Cancellation] = None
      var forwarded = List.empty[(String, McpApplication.Cancellation)]

      private def record(name: String, cancellation: McpApplication.Cancellation): Unit =
        forwarded = forwarded :+ (name -> cancellation)

      override def direct_cancellable[A](
          cancellation: McpApplication.Cancellation)(body: => A): A = {
        record("direct", cancellation)
        body
      }

      override def ml_tools_cancellable(context: String,
          cancellation: McpApplication.Cancellation): MCP_Session.Tools_Reply = {
        record("tools", cancellation)
        super.ml_tools(context)
      }

      override def ml_run_cancellable(name: String, args: List[(String, String)],
          context: String,
          cancellation: McpApplication.Cancellation): MCP_Session.Result = {
        mlCancellation = Some(cancellation)
        record("run", cancellation)
        super.ml_run(name, args, context)
      }

      override def ir_cancellable(fname: String, args: List[(String, String)],
          cancellation: McpApplication.Cancellation): MCP_Session.Result = {
        irCancellation = Some(cancellation)
        record("ir", cancellation)
        super.ir(fname, args)
      }

      override def check_context_cancellable(context: String,
          cancellation: McpApplication.Cancellation): MCP_Session.Result = {
        record("context", cancellation)
        super.check_context(context)
      }

      override def mcp_resources_cancellable(
          cancellation: McpApplication.Cancellation): List[(String, String, String)] = {
        record("resources-list", cancellation)
        super.mcp_resources()
      }

      override def mcp_resource_read_cancellable(uri: String,
          cancellation: McpApplication.Cancellation): MCP_Session.Result = {
        record("resources-read", cancellation)
        super.mcp_resource_read(uri)
      }

      override def scope_show_cancellable(
          cancellation: McpApplication.Cancellation): MCP_Session.Result = {
        record("scope-show", cancellation)
        super.scope_show()
      }
    }

    val cancellation = new McpApplication.Cancellation {
      def isCancelled: Boolean = false
      def onCancel(callback: () => Unit): Unit = ()
    }
    val backend = new Cancellable_Backend
    val application = McpApplication.isabelle(
      () => McpApplication.Ready(backend), "TEST", Nil, "MCP_Repl")
    application.execute(
      McpApplication.Operation.ToolsCall("shout", JSON.Object("input" -> "hello")),
      cancellation)
    application.execute(
      McpApplication.Operation.ToolsCall("repl_list", JSON.Object()), cancellation)
    application.execute(McpApplication.Operation.ToolsList, cancellation)
    application.execute(McpApplication.Operation.ResourcesList, cancellation)
    application.execute(
      McpApplication.Operation.ResourcesRead("isabelle://session"), cancellation)
    application.execute(
      McpApplication.Operation.ToolsCall("tool_scope_show", JSON.Object()), cancellation)
    application.execute(
      McpApplication.Operation.ToolsCall("scope_show", JSON.Object()), cancellation)
    assert(backend.mlCancellation.exists(_ eq cancellation))
    assert(backend.irCancellation.exists(_ eq cancellation))
    assertEquals(
      backend.forwarded.map(_._1).toSet,
      Set("tools", "run", "ir", "resources-list", "resources-read", "context",
        "scope-show", "direct"))
    assert(backend.forwarded.forall(_._2 eq cancellation))
  }

  private def start_server(body: => Unit): (Thread, AtomicReference[Throwable]) = {
    val failure = new AtomicReference[Throwable](null)
    val server = new Thread(new Runnable {
      def run(): Unit =
        try body
        catch { case exn: Throwable => failure.compareAndSet(null, exn) }
    }, "mcp-serve-test")
    /* A failing assertion must not leave the test JVM pinned on an input
       reader.  Successful tests still close the pipe and join the thread. */
    server.setDaemon(true)
    server.start()
    (server, failure)
  }

  private def check_server(
    server: Thread,
    failure: AtomicReference[Throwable],
    timeoutMillis: Long
  ): Unit = {
    server.join(timeoutMillis)
    assert(!server.isAlive, "serve did not stop after input closed")
    Option(failure.get()).foreach(throw _)
  }

  test("tools/list reports the backend tools with the fixed schema") {
    val shout = tool_row("shout")
    assertEquals(get_string(shout, "description"), "uppercase the input")
    assertEquals(required_args(shout), List("input"))
  }

  test("tools/call runs the tool") {
    val reply = call_tool("shout", JSON.Object("input" -> "isabelle"))
    assertEquals(result_text(reply), "ISABELLE")
    assert_no_error(reply)
  }

  test("tools/call without input is a tool error") {
    val text = assert_is_error(call_tool("shout", JSON.Object()))
    assert(text.contains("input"), "error text does not mention the missing argument: " + text)
  }

  test("tools/call without name yields -32602") {
    val reply = rpc("tools/call", JSON.Object("arguments" -> JSON.Object("input" -> "x")))
    assertEquals(get(reply, "error", "code"), MCP_Server.RPC.INVALID_PARAMS)
  }

  test("tools/call backend error is a tool error") {
    val text = assert_is_error(call_tool("no_such_tool", JSON.Object("input" -> "x")))
    assert(text.contains("no_such_tool"), "error text does not name the tool: " + text)
  }

  test("tools/list includes the repl_list builtin with its static metadata") {
    val row = tool_row("repl_list")
    assertEquals(get(row, "inputSchema"), JSON.Object("type" -> "object"))
    assertEquals(annotation(row, "readOnlyHint"), true)
    assertEquals(annotation(row, "idempotentHint"), true)
    assertEquals(annotation(row, "openWorldHint"), false)
  }

  test("builtin names match the mcp tool-name regex") {
    for (tool <- MCP_Server.builtins) {
      assert(tool.name.matches("^[a-zA-Z0-9_-]{1,64}$"),
        "builtin name violates the tool-name regex: " + tool.name)
    }
  }

  /* MCP_Server.all_builtin_names (the drift gate's target, plans/
     builtin_activation) sources the tool_scope trio from a hand-
     maintained literal (tool_scope_builtin_names) rather than the
     live per-connection Handler.tool_scope_builtins instances, since
     those close over connection state and cannot be listed statically.
     Weld the literal to reality here so a new connection-state tool
     added without updating it (and so without a mirror) fails a test
     immediately, rather than silently escaping the live-bridge gate. */
  test("MCP_Server.tool_scope_builtin_names matches what a fresh Handler actually serves") {
    /* everything tools/list serves beyond `builtins` and Fake_Backend's
       one fixed ml row ("shout") MUST be exactly the tool_scope trio --
       an extra connection-state tool added to Handler.tool_scope_builtins
       without updating the literal (and so without a mirror) shows up
       here as an unexpected name, caught before it could silently
       escape the drift gate (which reads the literal, not the live
       instances). */
    val names = get_list(rpc("tools/list"), "result", "tools").map(get_string(_, "name")).toSet
    val extra = names -- MCP_Server.builtins.map(_.name).toSet - "shout"
    assertEquals(extra, MCP_Server.tool_scope_builtin_names.toSet)
  }

  test("tools/call repl_list reaches backend.ir with (\"repls\", Nil)") {
    assert_dispatch("repl_list", JSON.Object(), "repls", Nil)
  }

  test("tools/list includes repl_init with its array-typed schema") {
    val row = tool_row("repl_init")
    assertEquals(required_args(row), List("repl", "theories"))
    assertEquals(property_type(row, "theories"), "array")
    assertEquals(annotation(row, "readOnlyHint"), false)
  }

  test("tools/call repl_init reaches backend.ir with repeated theories pairs") {
    assert_dispatch("repl_init",
      JSON.Object("repl" -> "T", "theories" -> List("A", "B")),
      "init", List("repl" -> "T", "theories" -> "A", "theories" -> "B"))
  }

  test("tools/list includes repl_fork with an integer state_idx") {
    val row = tool_row("repl_fork")
    assertEquals(required_args(row), List("repl", "new_repl", "state_idx"))
    assertEquals(property_type(row, "state_idx"), "integer")
    assertEquals(annotation(row, "readOnlyHint"), false)
  }

  test("tools/call repl_fork reaches backend.ir with (\"fork\", [(\"repl\", ...), (\"new_repl\", ...), (\"state_idx\", ...)])") {
    assert_dispatch("repl_fork",
      JSON.Object("repl" -> "T", "new_repl" -> "T2", "state_idx" -> -1),
      "fork", List("repl" -> "T", "new_repl" -> "T2", "state_idx" -> "-1"))
  }

  test("tools/list includes repl_remove with destructiveHint true") {
    val row = tool_row("repl_remove")
    assertEquals(required_args(row), List("repl"))
    assertEquals(annotation(row, "readOnlyHint"), false)
    assertEquals(annotation(row, "destructiveHint"), true)
  }

  test("tools/call repl_remove reaches backend.ir with (\"remove\", [(\"repl\", ...)])") {
    assert_dispatch("repl_remove", JSON.Object("repl" -> "T"),
      "remove", List("repl" -> "T"))
  }

  test("tools/list includes repl_step with its two required string args") {
    val row = tool_row("repl_step")
    assertEquals(required_args(row), List("repl", "isar_text"))
    assertEquals(annotation(row, "readOnlyHint"), false)
  }

  test("tools/call repl_step reaches backend.ir with (\"step\", [(\"repl\", ...), (\"isar_text\", ...)])") {
    assert_dispatch("repl_step", JSON.Object("repl" -> "T", "isar_text" -> "by simp"),
      "step", List("repl" -> "T", "isar_text" -> "by simp"))
  }

  test("tools/list includes repl_state with an integer state_idx and readOnlyHint") {
    val row = tool_row("repl_state")
    assertEquals(required_args(row), List("repl", "state_idx"))
    assertEquals(property_type(row, "state_idx"), "integer")
    assertEquals(annotation(row, "readOnlyHint"), true)
  }

  test("tools/call repl_state reaches backend.ir with (\"state\", [(\"repl\", ...), (\"state_idx\", ...)])") {
    assert_dispatch("repl_state", JSON.Object("repl" -> "T", "state_idx" -> -1),
      "state", List("repl" -> "T", "state_idx" -> "-1"))
  }

  // repl_show/repl_text table-membership + dispatch assertions moved:
  // plans/ml_builtin_migration wave 1 declares both as capture-form
  // mcp_tools in MCP_Repl.thy, so there is no scala Builtin_Tool row
  // left for Fake_Backend to serve here. Structural coverage (params,
  // annotations) now lives in MCP_Repl_Tests.thy's "Wave 1" section;
  // behavioral/wire coverage lives in mcp_bridge_tests.scala.

  test("tools/list includes repl_edit with its idx/isar_text schema") {
    val row = tool_row("repl_edit")
    assertEquals(required_args(row), List("repl", "idx", "isar_text"))
    assertEquals(property_type(row, "idx"), "integer")
    assertEquals(annotation(row, "readOnlyHint"), false)
  }

  test("tools/call repl_edit reaches backend.ir with (\"edit\", [(\"repl\", ...), (\"idx\", ...), (\"isar_text\", ...)])") {
    assert_dispatch("repl_edit",
      JSON.Object("repl" -> "T", "idx" -> 0, "isar_text" -> "by auto"),
      "edit", List("repl" -> "T", "idx" -> "0", "isar_text" -> "by auto"))
  }

  test("tools/list includes repl_replay with idempotentHint true") {
    val row = tool_row("repl_replay")
    assertEquals(required_args(row), List("repl"))
    assertEquals(annotation(row, "readOnlyHint"), false)
    assertEquals(annotation(row, "idempotentHint"), true)
  }

  test("tools/call repl_replay reaches backend.ir with (\"replay\", [(\"repl\", ...)])") {
    assert_dispatch("repl_replay", JSON.Object("repl" -> "T"), "replay", List("repl" -> "T"))
  }

  test("tools/list includes repl_truncate with destructiveHint true") {
    val row = tool_row("repl_truncate")
    assertEquals(required_args(row), List("repl", "idx"))
    assertEquals(annotation(row, "destructiveHint"), true)
  }

  test("tools/call repl_truncate reaches backend.ir with (\"truncate\", [(\"repl\", ...), (\"idx\", ...)])") {
    assert_dispatch("repl_truncate", JSON.Object("repl" -> "T", "idx" -> -1),
      "truncate", List("repl" -> "T", "idx" -> "-1"))
  }

  // repl_back table-membership + dispatch assertions moved: see the
  // repl_show/repl_text note above -- repl_back is wave 1's third
  // moved tool, same reasoning.

  test("tools/list includes repl_merge with destructiveHint true and its {repl} schema") {
    val row = tool_row("repl_merge")
    assertEquals(required_args(row), List("repl"))
    assertEquals(annotation(row, "destructiveHint"), true)
  }

  test("tools/call repl_merge reaches backend.ir with (\"merge\", [(\"repl\", ...)])") {
    assert_dispatch("repl_merge", JSON.Object("repl" -> "T"), "merge", List("repl" -> "T"))
  }

  test("tools/list includes repl_timeout with idempotentHint true and its repl/secs schema") {
    val row = tool_row("repl_timeout")
    assertEquals(required_args(row), List("repl", "secs"))
    assertEquals(annotation(row, "readOnlyHint"), false)
    assertEquals(annotation(row, "idempotentHint"), true)
  }

  test("tools/call repl_timeout reaches backend.ir with (\"timeout\", [(\"repl\", ...), (\"secs\", ...)])") {
    assert_dispatch("repl_timeout", JSON.Object("repl" -> "T", "secs" -> 5),
      "timeout", List("repl" -> "T", "secs" -> "5"))
  }

  test("tools/list includes repl_pin with its {repl} schema, not idempotentHint") {
    val row = tool_row("repl_pin")
    assertEquals(required_args(row), List("repl"))
    assertEquals(annotation(row, "idempotentHint"), false)
  }

  test("tools/call repl_pin reaches backend.ir with (\"pin\", [(\"repl\", ...)])") {
    assert_dispatch("repl_pin", JSON.Object("repl" -> "T"), "pin", List("repl" -> "T"))
  }

  test("tools/list includes repl_unpin with its {repl} schema") {
    val row = tool_row("repl_unpin")
    assertEquals(required_args(row), List("repl"))
  }

  test("tools/call repl_unpin reaches backend.ir with (\"unpin\", [(\"repl\", ...)])") {
    assert_dispatch("repl_unpin", JSON.Object("repl" -> "T"), "unpin", List("repl" -> "T"))
  }

  test("tools/list includes repl_rebase with idempotentHint true") {
    val row = tool_row("repl_rebase")
    assertEquals(required_args(row), List("repl"))
    assertEquals(annotation(row, "idempotentHint"), true)
  }

  test("tools/call repl_rebase reaches backend.ir with (\"rebase\", [(\"repl\", ...)])") {
    assert_dispatch("repl_rebase", JSON.Object("repl" -> "T"), "rebase", List("repl" -> "T"))
  }

  test("tools/list includes sledgehammer with an optional timeout_secs property (not in required)") {
    val row = tool_row("sledgehammer")
    assertEquals(required_args(row), List("repl"), "timeout_secs must not be in required")
    assertEquals(property_type(row, "timeout_secs"), "integer")
    assertEquals(annotation(row, "readOnlyHint"), true)
    assertEquals(annotation(row, "idempotentHint"), false,
      "sledgehammer should NOT be idempotentHint (ATP results vary run to run)")
  }

  /* T1 (plans/sledgehammer): the scala OMISSION path -- json_args only
     emits pairs for keys present in the arguments object, so omitting
     timeout_secs client-side must reach backend.ir as exactly
     [(repl,T)], leaving the ML dispatcher's own default untouched. */
  test("tools/call sledgehammer without timeout_secs reaches backend.ir with exactly [(\"repl\", ...)]") {
    assert_dispatch("sledgehammer", JSON.Object("repl" -> "T"),
      "sledgehammer", List("repl" -> "T"))
  }

  test("tools/call sledgehammer with timeout_secs reaches backend.ir with the pair present") {
    assert_dispatch("sledgehammer", JSON.Object("repl" -> "T", "timeout_secs" -> 10),
      "sledgehammer", List("repl" -> "T", "timeout_secs" -> "10"))
  }

  /* context promotion (plans/find_theorems "context promotion"): query
     is the only required property now -- repl and theory are both
     optional, mutually-exclusive context selectors. */
  test("tools/list includes find_theorems with only query required; repl/theory/max_results optional") {
    val row = tool_row("find_theorems")
    assertEquals(required_args(row), List("query"))
    assertEquals(property_type(row, "repl"), "string")
    assertEquals(property_type(row, "theory"), "string")
    assertEquals(property_type(row, "max_results"), "integer")
  }

  /* T1 (plans/find_theorems): query strings with quotes and
     underscores must cross the wire intact -- this is the tool
     where clients WILL send embedded double quotes. */
  test("tools/call find_theorems with a quoted term pattern reaches backend.ir with the exact query string, quotes included") {
    assert_dispatch("find_theorems",
      JSON.Object("repl" -> "T", "query" -> "\"_ + _ = _\""),
      "find_theorems", List("repl" -> "T", "query" -> "\"_ + _ = _\""))
  }

  test("tools/call find_theorems with max_results reaches backend.ir with the pair present") {
    assert_dispatch("find_theorems",
      JSON.Object("repl" -> "T", "query" -> "name:conjI", "max_results" -> 3),
      "find_theorems", List("repl" -> "T", "query" -> "name:conjI", "max_results" -> "3"))
  }

  /* T7 (plans/find_theorems "context promotion"): theory is normalized
     to the canonical Thy_Info key (Fake_Backend.resolve_context_theory
     resolves both "Main" and the alternate spelling "HOL.Main" to
     "Main") BEFORE crossing the bridge -- the fake only ever sees the
     resolved key, never the client's original spelling. */
  test("tools/call find_theorems with theory=Main reaches backend.ir with the resolved theory") {
    assert_dispatch("find_theorems",
      JSON.Object("theory" -> "Main", "query" -> "name:conjI"),
      "find_theorems", List("theory" -> "Main", "query" -> "name:conjI"))
  }

  test("tools/call find_theorems with theory=HOL.Main (alternate spelling) reaches backend.ir with the canonical name Main") {
    assert_dispatch("find_theorems",
      JSON.Object("theory" -> "HOL.Main", "query" -> "name:conjI"),
      "find_theorems", List("theory" -> "Main", "query" -> "name:conjI"))
  }

  test("tools/call find_theorems with an unknown theory is a status error naming the theory, nothing crosses the bridge") {
    val backend = new Fake_Backend
    val reply = call_tool("find_theorems", JSON.Object("theory" -> "Bogus", "query" -> "name:conjI"), backend)
    assert_is_error(reply)
    assertEquals(backend.last_ir, None, "nothing should cross the ir bridge on resolution failure")
  }

  /* T8: default context (neither repl nor theory) -- the headline use
     case of the promotion, find_theorems reachable with zero repls. */
  test("tools/call find_theorems with neither repl nor theory reaches backend.ir with just query (default context)") {
    assert_dispatch("find_theorems",
      JSON.Object("query" -> "name:conjI"),
      "find_theorems", List("query" -> "name:conjI"))
  }

  /* T9: repl and theory together is a handler-side error naming both
     keys; nothing crosses the bridge. */
  test("tools/call find_theorems with both repl and theory is an error naming both, nothing crosses the bridge") {
    val backend = new Fake_Backend
    val reply =
      call_tool("find_theorems",
        JSON.Object("repl" -> "T", "theory" -> "Main", "query" -> "name:conjI"), backend)
    val msg = assert_is_error(reply)
    assertEquals(backend.last_ir, None, "nothing should cross the ir bridge when repl and theory are both given")
    assert(msg.contains("repl") && msg.contains("theory"), "error should name both keys: " + msg)
  }

  /* find_definition (plans/find_definition): the context selector shape
     (repl | theory, mutually exclusive, resolved via
     resolve_context_theory) is exactly find_theorems' context
     promotion, reused verbatim -- see T7..T9 above for the same
     coverage pattern. */
  test("tools/list includes find_definition with only name required; kind/repl/theory optional") {
    val row = tool_row("find_definition")
    assertEquals(required_args(row), List("name"))
    assertEquals(property_type(row, "kind"), "string")
    assertEquals(property_type(row, "repl"), "string")
    assertEquals(property_type(row, "theory"), "string")
    assertEquals(annotation(row, "readOnlyHint"), true)
    assertEquals(annotation(row, "idempotentHint"), true)
  }

  test("tools/call find_definition with just name reaches backend.ir with exactly [(\"name\", ...)]") {
    assert_dispatch("find_definition", JSON.Object("name" -> "rev"),
      "find_definition", List("name" -> "rev"))
  }

  test("tools/call find_definition with kind reaches backend.ir with the pair present") {
    assert_dispatch("find_definition", JSON.Object("name" -> "rev", "kind" -> "const"),
      "find_definition", List("name" -> "rev", "kind" -> "const"))
  }

  test("tools/call find_definition with theory=HOL.Main (alternate spelling) reaches backend.ir with the canonical name Main") {
    assert_dispatch("find_definition",
      JSON.Object("name" -> "rev", "theory" -> "HOL.Main"),
      "find_definition", List("name" -> "rev", "theory" -> "Main"))
  }

  test("tools/call find_definition with an unknown theory is a status error naming the theory, nothing crosses the bridge") {
    val backend = new Fake_Backend
    val reply = call_tool("find_definition", JSON.Object("name" -> "rev", "theory" -> "Bogus"), backend)
    assert_is_error(reply)
    assertEquals(backend.last_ir, None, "nothing should cross the ir bridge on resolution failure")
  }

  test("tools/call find_definition with both repl and theory is an error naming both, nothing crosses the bridge") {
    val backend = new Fake_Backend
    val reply =
      call_tool("find_definition",
        JSON.Object("name" -> "rev", "repl" -> "T", "theory" -> "Main"), backend)
    val msg = assert_is_error(reply)
    assertEquals(backend.last_ir, None, "nothing should cross the ir bridge when repl and theory are both given")
    assert(msg.contains("repl") && msg.contains("theory"), "error should name both keys: " + msg)
  }

  /* repl_init_from_source (plans/repl_init_from_source): T1 (exactly-
     one-locator, handler-side, same shape as find_theorems/
     find_definition's repl/theory exclusivity) and T2's dispatch half
     (theory resolves to what Fake_Backend.init_from_source sees) --
     T2's pure resolver coverage (offset/pattern/index -> id, plus the
     not-found/out-of-range error cases) is MCP_Locator_Tests below,
     over MCP_Session.Locator directly (no backend needed at all). */
  test("tools/list includes repl_init_from_source with repl/theory required; offset/pattern/index optional") {
    val row = tool_row("repl_init_from_source")
    assertEquals(required_args(row), List("repl", "theory"))
    assertEquals(property_type(row, "offset"), "integer")
    assertEquals(property_type(row, "pattern"), "string")
    assertEquals(property_type(row, "index"), "integer")
  }

  test("tools/call repl_init_from_source with no locator is an error naming the rule, backend never called") {
    val backend = new Fake_Backend
    val reply =
      call_tool("repl_init_from_source", JSON.Object("repl" -> "R", "theory" -> "Main"), backend)
    val msg = assert_is_error(reply)
    assertEquals(backend.last_ir, None, "nothing should reach the backend when no locator is given")
    assert(msg.contains("exactly one"), "error should name the exactly-one-locator rule: " + msg)
  }

  test("tools/call repl_init_from_source with two locators is an error naming the rule, backend never called") {
    val backend = new Fake_Backend
    val reply =
      call_tool("repl_init_from_source",
        JSON.Object("repl" -> "R", "theory" -> "Main", "offset" -> 0, "pattern" -> "lemma"), backend)
    val msg = assert_is_error(reply)
    assertEquals(backend.last_ir, None, "nothing should reach the backend when two locators are given")
    assert(msg.contains("exactly one"), "error should name the exactly-one-locator rule: " + msg)
  }

  test("tools/call repl_init_from_source with pattern reaches backend.init_from_source with the resolved locator") {
    val backend = new Fake_Backend
    val reply =
      call_tool("repl_init_from_source",
        JSON.Object("repl" -> "R", "theory" -> "Main", "pattern" -> "lemma foo"), backend)
    assert_no_error(reply)
    assertEquals(backend.last_ir,
      Some(("init_from_source",
        List("repl" -> "R", "theory" -> "Main", "locator" -> "pattern=lemma foo"))))
  }

  test("tools/call repl_init_from_source with offset reaches backend.init_from_source with the resolved locator") {
    val backend = new Fake_Backend
    val reply =
      call_tool("repl_init_from_source",
        JSON.Object("repl" -> "R", "theory" -> "Main", "offset" -> 42), backend)
    assert_no_error(reply)
    assertEquals(backend.last_ir,
      Some(("init_from_source", List("repl" -> "R", "theory" -> "Main", "locator" -> "offset=42"))))
  }

  test("tools/call repl_init_from_source with index reaches backend.init_from_source with the resolved locator") {
    val backend = new Fake_Backend
    val reply =
      call_tool("repl_init_from_source",
        JSON.Object("repl" -> "R", "theory" -> "Main", "index" -> 0), backend)
    assert_no_error(reply)
    assertEquals(backend.last_ir,
      Some(("init_from_source", List("repl" -> "R", "theory" -> "Main", "locator" -> "index=0"))))
  }

  test("tools/call repl_init_from_source on a filesystem-tier theory is a status error naming load_theory") {
    val backend = new Fake_Backend
    val reply =
      call_tool("repl_init_from_source",
        JSON.Object("repl" -> "R", "theory" -> "FSOnly", "index" -> 0), backend)
    val msg = assert_is_error(reply)
    assert(msg.contains("load_theory"), "error should point at load_theory: " + msg)
  }

  /* wave 2 (plans/load_theory, plans/unload_theory, plans/check_theory):
     these three tools bypass the MCP.ir bridge entirely -- they call
     backend.load_theory/unload_theory/check_theory directly, not
     backend.ir, so backend.last_ir must stay untouched. */
  test("tools/list includes load_theory/unload_theory/check_theory") {
    val tools = get_list(rpc("tools/list"), "result", "tools")
    val names = tools.map(t => get_string(t, "name")).toSet
    assert(names.contains("load_theory"), "missing load_theory")
    assert(names.contains("unload_theory"), "missing unload_theory")
    assert(names.contains("check_theory"), "missing check_theory")
    assertEquals(required_args(tool_row("load_theory")), List("name"))
  }

  test("tools/call load_theory reaches backend.load_theory, not backend.ir") {
    val backend = new Fake_Backend
    val reply = call_tool("load_theory",
      JSON.Object("name" -> "Draft.Foo", "master_dir" -> "/tmp"), backend)
    assert(backend.last_ir.isEmpty, "load_theory must not touch the ir bridge")
    assert(backend.loaded_theories.contains("Draft.Foo"),
      "backend.load_theory was not called with the right name")
    assert_no_error(reply)
  }

  test("tools/call load_theory without master_dir reaches backend with an empty master_dir, not a missing-argument error") {
    assert_no_error(call_tool("load_theory", JSON.Object("name" -> "Bare")))
  }

  /* T2 (plans/unload_theory): both error paths -- never loaded, and
     baked into the base image -- are clean isError, not a crash. */
  test("tools/call unload_theory on a never-loaded theory is a clean isError") {
    assert_is_error(call_tool("unload_theory", JSON.Object("name" -> "NeverLoaded")))
  }

  test("tools/call unload_theory on an image theory is a clean isError naming the image tier") {
    val text = assert_is_error(call_tool("unload_theory", JSON.Object("name" -> "Image")))
    assert(text.contains("image"), "error should name the image tier: " + text)
  }

  test("tools/call unload_theory on a loaded theory succeeds") {
    assert_no_error(call_tool("unload_theory", JSON.Object("name" -> "Loaded")))
  }

  test("tools/call check_theory reaches backend.check_theory, not backend.ir") {
    val backend = new Fake_Backend
    val reply = call_tool("check_theory", JSON.Object("name" -> "Draft.Foo"), backend)
    assert(backend.last_ir.isEmpty, "check_theory must not touch the ir bridge")
    assert_no_error(reply)
  }

  /* wave 3 (plans/session_structure, plans/list_sessions, plans/list_theories,
     plans/search_sources): library discovery tools. these three bypass the
     MCP.ir bridge entirely, like wave 2, and call backend.list_sessions_info(),
     backend.list_theories_info(), backend.search_sources() directly. */

  test("tools/list includes list_sessions with readOnlyHint and idempotentHint") {
    val row = tool_row("list_sessions")
    assertEquals(required_args(row), List())
    assertEquals(annotation(row, "readOnlyHint"), true)
    assertEquals(annotation(row, "idempotentHint"), true)
    assertEquals(annotation(row, "openWorldHint"), false)
  }

  test("tools/call list_sessions reaches backend.list_sessions_info, not backend.ir") {
    val backend = new Fake_Backend
    val reply = call_tool("list_sessions", JSON.Object(), backend)
    assert(backend.last_ir.isEmpty, "list_sessions must not touch the ir bridge")
    assert_no_error(reply)
    val content = get_list(reply, "result", "content")
    assert(content.nonEmpty, "result content should not be empty")
    val text = get_string(content.head, "text")
    assert(text.contains("HOL"), "list_sessions should mention HOL session")
  }

  test("tools/list includes list_theories with a required session parameter") {
    val row = tool_row("list_theories")
    assertEquals(required_args(row), List("session"))
    assertEquals(property_type(row, "session"), "string")
    assertEquals(annotation(row, "readOnlyHint"), true)
    assertEquals(annotation(row, "idempotentHint"), true)
    assertEquals(annotation(row, "openWorldHint"), false)
  }

  test("tools/call list_theories reaches backend.list_theories_info, not backend.ir") {
    val backend = new Fake_Backend
    val reply = call_tool("list_theories", JSON.Object("session" -> "HOL"), backend)
    assert(backend.last_ir.isEmpty, "list_theories must not touch the ir bridge")
    assert_no_error(reply)
    val content = get_list(reply, "result", "content")
    assert(content.nonEmpty, "result content should not be empty")
    val text = get_string(content.head, "text")
    assert(text.contains("HOL.Main") || text.contains("Main"),
      "list_theories output should contain theory names")
  }

  test("tools/list includes search_sources with a required pattern parameter") {
    val row = tool_row("search_sources")
    assertEquals(required_args(row), List("pattern"))
    assertEquals(property_type(row, "pattern"), "string")
    assertEquals(annotation(row, "readOnlyHint"), true)
    assertEquals(annotation(row, "idempotentHint"), true)
    assertEquals(annotation(row, "openWorldHint"), false)
  }

  test("tools/call search_sources reaches backend.search_sources, not backend.ir") {
    val backend = new Fake_Backend
    val reply = call_tool("search_sources", JSON.Object("pattern" -> "Main"), backend)
    assert(backend.last_ir.isEmpty, "search_sources must not touch the ir bridge")
    assert_no_error(reply)
    val content = get_list(reply, "result", "content")
    assert(content.nonEmpty, "result content should not be empty")
    val text = get_string(content.head, "text")
    assert(text.contains("Main") || text.nonEmpty,
      "search_sources should return results or be non-empty")
  }

  /* wave 5 (plans/doc_list): the documentation catalog tool. Like the
     wave 3 tools above, bypasses the MCP.ir bridge and calls
     backend.doc_list() directly; pattern is optional (empty = list
     everything, unlike search_sources' empty-means-nothing). */

  test("tools/list includes doc_list with an optional pattern parameter") {
    val row = tool_row("doc_list")
    assertEquals(required_args(row), List())
    assertEquals(property_type(row, "pattern"), "string")
    assertEquals(annotation(row, "readOnlyHint"), true)
    assertEquals(annotation(row, "idempotentHint"), true)
    assertEquals(annotation(row, "openWorldHint"), false)
  }

  test("tools/call doc_list reaches backend.doc_list, not backend.ir") {
    val backend = new Fake_Backend
    val reply = call_tool("doc_list", JSON.Object(), backend)
    assert(backend.last_ir.isEmpty, "doc_list must not touch the ir bridge")
    assert_no_error(reply)
    val content = get_list(reply, "result", "content")
    assert(content.nonEmpty, "result content should not be empty")
    val text = get_string(content.head, "text")
    assert(text.contains("isar-ref"), "doc_list should mention the isar-ref manual")
    assert(text.contains("source: Isar_Ref"), "doc_list should name the source session")
  }

  test("tools/call doc_list with a pattern filters entry names") {
    val backend = new Fake_Backend
    val reply = call_tool("doc_list", JSON.Object("pattern" -> "isar*"), backend)
    val text = get_string(get_list(reply, "result", "content").head, "text")
    assert(text.contains("isar-ref"), "pattern isar* should keep isar-ref")
    assert(!text.contains("NEWS"), "pattern isar* should filter out NEWS")
  }

  /* wave 5 (plans/doc_read): reads a doc_list entry. Fake_Backend has no
     real catalog behind doc_read (see mcp_testing.scala), so these tests
     only exercise the wiring (schema, dispatch bypasses ir) -- the actual
     toc/section/window logic is tested directly against real doc sources
     in MCP_Doc_Read_Tests. */

  test("tools/list includes doc_read with name required, section/lines optional") {
    val row = tool_row("doc_read")
    assertEquals(required_args(row), List("name"))
    assertEquals(property_type(row, "name"), "string")
    assertEquals(property_type(row, "section"), "string")
    assertEquals(property_type(row, "lines"), "string")
    assertEquals(annotation(row, "readOnlyHint"), true)
    assertEquals(annotation(row, "idempotentHint"), true)
    assertEquals(annotation(row, "openWorldHint"), false)
  }

  test("tools/call doc_read reaches backend.doc_read, not backend.ir") {
    val backend = new Fake_Backend
    val reply = call_tool("doc_read", JSON.Object("name" -> "isar-ref"), backend)
    assert(backend.last_ir.isEmpty, "doc_read must not touch the ir bridge")
    assert_no_error(reply)
  }

  test("tools/list: a colliding ML tool does not shadow the repl_list builtin") {
    val backend = new Fake_Backend
    backend.extra_ml_tools =
      List(MCP_Session.Tool_Row("repl_list", "some unrelated ml tool", "string_fun", Nil,
        MCP_Session.Tool_Annotations.default))
    val tools = get_list(rpc("tools/list", backend = backend), "result", "tools")
    val matches = tools.filter(t => get_string(t, "name") == "repl_list")
    assertEquals(matches.length, 1, "expected exactly one repl_list entry")
    assertEquals(get_string(matches.head, "description"),
      MCP_Server.repl_list_tool.description,
      "colliding ml tool shadowed the builtin description")
  }

  /* plans/builtin_activation: the merge filter over Fake_Backend's
     settable builtin_activation section. */

  test("tools/list: an empty builtins section serves the full builtin table (availability floor)") {
    val backend = new Fake_Backend
    backend.builtin_activation = Nil
    val names = get_list(rpc("tools/list", backend = backend), "result", "tools")
      .map(get_string(_, "name")).toSet
    for (n <- MCP_Server.all_builtin_names) assert(names(n), n + " missing from the floor listing")
  }

  test("tools/list: a builtin marked (name, false) is hidden from the listing") {
    val backend = new Fake_Backend
    backend.builtin_activation = List("repl_list" -> false)
    val names = get_list(rpc("tools/list", backend = backend), "result", "tools")
      .map(get_string(_, "name")).toSet
    assert(!names("repl_list"), "repl_list should be hidden")
    assert(names("repl_init"), "repl_init should still be listed (only repl_list was del'd)")
  }

  test("tools/list: a builtin marked (name, true) is listed (no different from absent)") {
    val backend = new Fake_Backend
    backend.builtin_activation = List("repl_list" -> true)
    val names = get_list(rpc("tools/list", backend = backend), "result", "tools")
      .map(get_string(_, "name")).toSet
    assert(names("repl_list"), "repl_list should be listed")
  }

  /* ASYMMETRIC CALLABILITY (A5): a hidden builtin dispatches exactly
     like a listed one -- tools/call precedes activation entirely, so
     the merge filter (tools/list only) never touches it. */
  test("tools/call: a builtin hidden via (name, false) is still callable") {
    val backend = new Fake_Backend
    backend.builtin_activation = List("repl_list" -> false)
    val reply = call_tool("repl_list", JSON.Object(), backend)
    assertEquals(backend.last_ir, Some(("repls", Nil)),
      "backend did not see the expected repls args")
    assert_no_error(reply)
  }

  /* exposure: the pure exposed-name function over full internal names
     (plans/mcp_tool_registry; extended by plans/tool_scope) */

  test("exposure: unambiguous entries get their base name") {
    assertEquals(MCP_Server.exposure(List("MCP_Tools.shout")),
      Map("MCP_Tools.shout" -> "shout"))
  }

  test("exposure: a base-name clash across theories qualifies both, dots become __") {
    val names = List("Thy_A.probe", "Thy_B.probe")
    assertEquals(MCP_Server.exposure(names),
      Map("Thy_A.probe" -> "Thy_A__probe", "Thy_B.probe" -> "Thy_B__probe"))
  }

  test("exposure: reserved (builtin) names force qualification or drop the entry") {
    assertEquals(MCP_Server.exposure(List("Some_Thy.repl_list"), Set("repl_list")),
      Map("Some_Thy.repl_list" -> "Some_Thy__repl_list"))
    /* an unqualified internal name that IS the builtin name has no
       fallback spelling left: dropped */
    assertEquals(MCP_Server.exposure(List("repl_list"), Set("repl_list")), Map.empty)
  }

  test("exposure: results always match the MCP tool-name regex") {
    val names = List("Thy'x.weïrd toöl", ("Long_Thy." + "a" * 80))
    val exposed = MCP_Server.exposure(names).values
    val regex = "^[a-zA-Z0-9_-]{1,64}$".r
    assert(exposed.nonEmpty)
    assert(exposed.forall(x => regex.matches(x)), exposed.toString)
  }

  test("tools/call resolves an exposed qualified name to the internal row") {
    val backend = new Fake_Backend
    backend.extra_ml_tools =
      List(MCP_Session.Tool_Row("Thy_A.shout", "clashes with the demo tool", "string_fun", Nil,
        MCP_Session.Tool_Annotations.default))
    /* base name "shout" now ambiguous: both rows serve qualified */
    val tools = get_list(rpc("tools/list", backend = backend), "result", "tools")
    val names = tools.map(t => get_string(t, "name"))
    assert(names.contains("MCP_Tools__shout") && names.contains("Thy_A__shout"),
      "expected qualified exposure, got: " + names.toString)
    assert(!names.contains("shout"), "ambiguous base name still exposed: " + names.toString)
    val reply = call_tool("MCP_Tools__shout", JSON.Object("input" -> "hi"), backend)
    assertEquals(result_text(reply), "HI")
  }

  /* schema expansion: declared params -> JSON schema (spec phase 3
     "schema over the bridge") */

  test("tools/list expands declared params into typed schemas with defaults") {
    val backend = new Fake_Backend
    backend.extra_ml_tools = List(
      MCP_Session.Tool_Row("Thy_A.finder", "searches", "diag_wrap", List(
        MCP_Session.Tool_Param("criteria", MCP_Session.Ptyp_Args, true, None, "search criteria"),
        MCP_Session.Tool_Param("limit", MCP_Session.Ptyp_Nat, false, Some("20"), "max results"),
        MCP_Session.Tool_Param("goal", MCP_Session.Ptyp_Term, true, None, "a goal")),
        /* mirrors MCP_Tool.read_only (MCP_Combinators.diag always uses
           diag_annotations, plans/param_schema_v2 step 5) */
        MCP_Session.Tool_Annotations(Some(true), Some(true), None, Some(false))))
    val row = tool_row("finder", backend)
    assertEquals(property_type(row, "criteria"), "string")
    assertEquals(property_type(row, "limit"), "integer")
    assertEquals(get(row, "inputSchema", "properties", "limit", "default"), 20L)
    assertEquals(required_args(row), List("criteria", "goal"))
    /* term params state their elaboration contract in the description */
    assert(get_string(row, "inputSchema", "properties", "goal", "description")
      .contains("term"))
    /* diag wraps advertise read-only + idempotent */
    assertEquals(annotation(row, "readOnlyHint"), true)
    assertEquals(annotation(row, "idempotentHint"), true)
  }

  test("tools/list expands an enum param into {type: string, enum: [...]} " +
      "(plans/param_schema_v2 A9)") {
    val backend = new Fake_Backend
    backend.extra_ml_tools = List(
      MCP_Session.Tool_Row("Thy_A.finder", "searches", "string_fun", List(
        MCP_Session.Tool_Param("kind",
          MCP_Session.Ptyp_Enum(List("const", "thm", "type")),
          false, Some("const"), "what to look for")),
        MCP_Session.Tool_Annotations.default))
    val row = tool_row("finder", backend)
    assertEquals(property_type(row, "kind"), "string")
    assertEquals(get_list(row, "inputSchema", "properties", "kind", "enum"),
      List("const", "thm", "type"))
    assertEquals(get(row, "inputSchema", "properties", "kind", "default"), "const")
    assertEquals(required_args(row), Nil)
  }

  test("tools/list expands a list-of param into {type: array, items: {...}} " +
      "(plans/param_schema_v2 A8/A9)") {
    val backend = new Fake_Backend
    backend.extra_ml_tools = List(
      MCP_Session.Tool_Row("Thy_A.finder", "searches", "string_fun", List(
        MCP_Session.Tool_Param("names",
          MCP_Session.Ptyp_List_Of(MCP_Session.Ptyp_String),
          true, None, "names to search")),
        MCP_Session.Tool_Annotations.default))
    val row = tool_row("finder", backend)
    assertEquals(property_type(row, "names"), "array")
    assertEquals(get(row, "inputSchema", "properties", "names", "items"),
      JSON.Object("type" -> "string"))
    assertEquals(required_args(row), List("names"))
  }

  test("tools/list gives a zero-param ML tool the bare object schema, " +
      "not the mvp {input} shape (plans/ml_builtin_migration step 4)") {
    val backend = new Fake_Backend
    backend.extra_ml_tools =
      List(MCP_Session.Tool_Row("Thy_A.no_args", "takes nothing", "string_fun", Nil,
        MCP_Session.Tool_Annotations.default))
    val row = tool_row("no_args", backend)
    assertEquals(get(row, "inputSchema"), JSON.Object("type" -> "object"))
    /* a real func-form tool (declared params always non-empty) is unaffected */
    val shout = tool_row("shout", backend)
    assertEquals(required_args(shout), List("input"))
  }

  test("tools/list renders an ML row's own annotations record -- only the " +
      "Some hints, and no \"annotations\" key at all for an all-absent record " +
      "(plans/param_schema_v2 A10)") {
    val backend = new Fake_Backend
    backend.extra_ml_tools = List(
      MCP_Session.Tool_Row("Thy_A.destroyer", "wipes something", "string_fun", Nil,
        MCP_Session.Tool_Annotations(Some(false), Some(false), Some(true), Some(false))),
      MCP_Session.Tool_Row("Thy_A.mystery", "no hints at all", "string_fun", Nil,
        MCP_Session.Tool_Annotations(None, None, None, None)))
    val destroyer = tool_row("destroyer", backend)
    assertEquals(annotation(destroyer, "readOnlyHint"), false)
    assertEquals(annotation(destroyer, "idempotentHint"), false)
    assertEquals(annotation(destroyer, "destructiveHint"), true)
    assertEquals(annotation(destroyer, "openWorldHint"), false)
    val mystery = tool_row("mystery", backend)
    assertEquals(JSON.value(mystery, "annotations"), None)
  }

  test("tools/call forwards ALL json arguments as named pairs") {
    class Recording_Backend extends Fake_Backend {
      var seen: Option[(String, List[(String, String)])] = None
      override def ml_run(name: String, args: List[(String, String)],
          context: String): MCP_Session.Result = {
        seen = Some((name, args))
        MCP_Session.Ok("ok")
      }
    }
    val backend = new Recording_Backend
    val reply = call_tool("shout", JSON.Object("input" -> "x", "limit" -> 3), backend)
    assert_no_error(reply)
    val (seen_name, seen_args) = backend.seen.getOrElse(fail("ml_run never reached"))
    assertEquals(seen_name, "MCP_Tools.shout")
    assertEquals(seen_args.sorted, List("input" -> "x", "limit" -> "3"))
  }

  /* listChanged: capability + notification wiring (spec phase 2's open
     half of the builtin-table item, closed by plans/mcp_tool_command
     step 5) */

  test("initialize declares tools and resources listChanged") {
    val reply = rpc("initialize")
    assertEquals(get(reply, "result", "capabilities", "tools", "listChanged"), true)
    assertEquals(get(reply, "result", "capabilities", "resources", "listChanged"), true)
  }

  test("serve emits list_changed through its live connection data plane") {
    class Notifying_Backend extends Fake_Backend {
      val changed = new CountDownLatch(1)
      override def ml_run(name: String, args: List[(String, String)],
          context: String): MCP_Session.Result = {
        changed_handler("tools")
        changed.countDown()
        super.ml_run(name, args, context)
      }
    }
    val backend = new Notifying_Backend
    val out_stream = new ByteArrayOutputStream
    val out = new PrintStream(out_stream, true, StandardCharsets.UTF_8)
    val writer = new PipedWriter
    val reader = new BufferedReader(new PipedReader(writer))
    val (server, server_failure) = start_server { MCP_Server.serve(backend, reader, out) }
    try {
      List(
        JSON.Object("jsonrpc" -> "2.0", "id" -> "initialize", "method" -> "initialize",
          "params" -> JSON.Object("protocolVersion" -> "2025-03-26")),
        JSON.Object("jsonrpc" -> "2.0", "method" -> "notifications/initialized"),
        JSON.Object("jsonrpc" -> "2.0", "id" -> "call", "method" -> "tools/call",
          "params" -> JSON.Object("name" -> "shout", "arguments" -> JSON.Object("input" -> "hi")))
      ).foreach(json => writer.write(JSON.Format(json) + "\n"))
      writer.flush()
      assert(backend.changed.await(2, TimeUnit.SECONDS), "tool call never raised list_changed")
      val lines = split_lines(out_stream.toString(StandardCharsets.UTF_8)).filter(_.nonEmpty)
      assert(lines.exists(_.contains("notifications/tools/list_changed")),
        "missing list_changed notification: " + lines.toString)
    }
    finally writer.close()
    check_server(server, server_failure, 2000L)
  }

  private def serve_policy(
    maxInFlight: Int,
    requestTimeout: Double = 60.0,
    shutdownDrain: Double = 1.0
  ): ConnectionPolicy =
    ConnectionPolicy.fromOptions(
      Options.init() + ("mcp_max_in_flight=" + maxInFlight) +
        ("mcp_request_timeout=" + requestTimeout) +
        ("mcp_shutdown_drain=" + shutdownDrain)) match {
      case Right(policy) => policy
      case Left(message) => fail(message)
    }

  private def eventually(label: String, timeoutMillis: Long = 2000L)(condition: => Boolean): Unit = {
    val deadline = System.nanoTime() + timeoutMillis * 1000000L
    while (!condition && System.nanoTime() < deadline) Thread.sleep(5L)
    assert(condition, label)
  }

  spec_test("production serve rejects saturation without a waiting queue and recovers capacity",
      verifies = List("connection_kernel#I2")) {
    class Blocking_Backend extends Fake_Backend {
      val calls = new AtomicInteger(0)
      val first_started = new CountDownLatch(2)
      val first_release = new CountDownLatch(1)
      val first_finished = new CountDownLatch(2)
      val fourth_started = new CountDownLatch(1)
      val fourth_release = new CountDownLatch(1)

      override def ml_run(name: String, args: List[(String, String)],
          context: String): MCP_Session.Result = {
        calls.incrementAndGet() match {
          case n if n <= 2 =>
            first_started.countDown()
            if (!first_release.await(5, TimeUnit.SECONDS)) fail("first workers were not released")
            first_finished.countDown()
          case 3 =>
            fourth_started.countDown()
            if (!fourth_release.await(5, TimeUnit.SECONDS)) fail("recovered worker was not released")
          case n => fail("unexpected queued execution " + n)
        }
        super.ml_run(name, args, context)
      }
    }

    val backend = new Blocking_Backend
    val out_stream = new ByteArrayOutputStream
    val out = new PrintStream(out_stream, true, StandardCharsets.UTF_8)
    val writer = new PipedWriter
    val reader = new BufferedReader(new PipedReader(writer))
    val (server, server_failure) = start_server {
      MCP_Server.serve(backend, reader, out, policy = serve_policy(2))
    }
    def send(json: JSON.T): Unit = writer.write(JSON.Format(json) + "\n")

    try {
      send(JSON.Object("jsonrpc" -> "2.0", "id" -> "initialize", "method" -> "initialize",
        "params" -> JSON.Object("protocolVersion" -> "2025-03-26")))
      send(JSON.Object("jsonrpc" -> "2.0", "method" -> "notifications/initialized"))
      List("one", "two", "overload").foreach(id =>
        send(JSON.Object("jsonrpc" -> "2.0", "id" -> id, "method" -> "tools/call",
          "params" -> JSON.Object("name" -> "shout", "arguments" -> JSON.Object("input" -> id)))))
      writer.flush()

      assert(backend.first_started.await(2, TimeUnit.SECONDS), "configured work never started")
      eventually("N+1 did not receive the immediate overload reply") {
        out_stream.toString(StandardCharsets.UTF_8).contains("\"id\":\"overload\"") &&
          out_stream.toString(StandardCharsets.UTF_8).contains("\"code\":-32001")
      }
      assertEquals(backend.calls.get(), 2, "saturation must not queue application work")

      backend.first_release.countDown()
      assert(backend.first_finished.await(2, TimeUnit.SECONDS), "accepted work did not finish")
      eventually("accepted work did not release its production worker permits") {
        val output = out_stream.toString(StandardCharsets.UTF_8)
        output.contains("\"id\":\"one\"") && output.contains("\"id\":\"two\"")
      }
      val recoveryDeadline = System.nanoTime() + 2000000000L
      var recoveryAttempt = 0
      while (backend.fourth_started.getCount > 0L && System.nanoTime() < recoveryDeadline) {
        val id = "recovered-" + recoveryAttempt
        send(JSON.Object("jsonrpc" -> "2.0", "id" -> id, "method" -> "tools/call",
          "params" -> JSON.Object("name" -> "shout", "arguments" -> JSON.Object("input" -> id))))
        writer.flush()
        recoveryAttempt += 1
        Thread.sleep(10L)
      }
      assert(backend.fourth_started.getCount == 0L, "capacity did not recover")
      assertEquals(backend.calls.get(), 3)
    }
    finally {
      backend.first_release.countDown()
      backend.fourth_release.countDown()
      writer.close()
    }
    check_server(server, server_failure, 3000L)
  }

  spec_test("production deadline emits one timeout response and suppresses the late backend result",
      covers = List("connection_kernel#T5")) {
    class Blocking_Backend extends Fake_Backend {
      val started = new CountDownLatch(1)
      val release = new CountDownLatch(1)

      override def ml_run(name: String, args: List[(String, String)],
          context: String): MCP_Session.Result = {
        started.countDown()
        if (!release.await(2, TimeUnit.SECONDS)) fail("timeout fixture backend was never released")
        super.ml_run(name, args, context)
      }
    }

    val backend = new Blocking_Backend
    val out_stream = new ByteArrayOutputStream
    val out = new PrintStream(out_stream, true, StandardCharsets.UTF_8)
    val writer = new PipedWriter
    val reader = new BufferedReader(new PipedReader(writer))
    val policy = serve_policy(1, requestTimeout = 0.05, shutdownDrain = 1.0)
    val (server, server_failure) = start_server {
      MCP_Server.serve(backend, reader, out, progress = new Progress, policy = policy)
    }
    try {
      List(
        JSON.Object("jsonrpc" -> "2.0", "id" -> "initialize", "method" -> "initialize",
          "params" -> JSON.Object("protocolVersion" -> "2025-03-26")),
        JSON.Object("jsonrpc" -> "2.0", "method" -> "notifications/initialized"),
        JSON.Object("jsonrpc" -> "2.0", "id" -> "timed", "method" -> "tools/call",
          "params" -> JSON.Object("name" -> "shout",
            "arguments" -> JSON.Object("input" -> "late")))
      ).foreach(json => writer.write(JSON.Format(json) + "\n"))
      writer.flush()
      assert(backend.started.await(2, TimeUnit.SECONDS), "timed request never reached the backend")

      def timedReplies: List[JSON.T] =
        split_lines(out_stream.toString(StandardCharsets.UTF_8)).flatMap(JSON.Format.unapply)
          .filter(value => JSON.value(value, "id").contains("timed"))
      eventually("production timeout response was not emitted") { timedReplies.nonEmpty }
      val timeout = timedReplies.head
      assertEquals(get(timeout, "error", "code"), ConnectionKernel.RequestTimedOut)
      assertEquals(get(timeout, "error", "data", "reason"), "requestTimeout")

      backend.release.countDown()
      writer.close()
      check_server(server, server_failure, 2000L)
      assertEquals(timedReplies.length, 1, "late production backend result escaped")
    }
    finally {
      backend.release.countDown()
      try writer.close() catch { case _: Throwable => () }
    }
  }

  spec_test("production EOF drains an ordinary reply before backend teardown",
      covers = List("connection_kernel#T7")) {
    class Draining_Backend extends Fake_Backend {
      val started = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val stoppedLatch = new CountDownLatch(1)
      private var events = List.empty[String]

      override def ml_run(name: String, args: List[(String, String)],
          context: String): MCP_Session.Result = {
        started.countDown()
        if (!release.await(2, TimeUnit.SECONDS)) fail("drain fixture backend was never released")
        synchronized { events :+= "completed" }
        super.ml_run(name, args, context)
      }

      override def stop(): Unit = {
        synchronized { events :+= "stopped" }
        super.stop()
        stoppedLatch.countDown()
      }

      def eventOrder: List[String] = synchronized { events }
    }

    val backend = new Draining_Backend
    val out_stream = new ByteArrayOutputStream
    val out = new PrintStream(out_stream, true, StandardCharsets.UTF_8)
    val writer = new PipedWriter
    val reader = new BufferedReader(new PipedReader(writer))
    val policy = serve_policy(1, requestTimeout = 60.0, shutdownDrain = 1.0)
    val (server, server_failure) = start_server {
      MCP_Server.serve(backend, reader, out, progress = new Progress, policy = policy)
    }
    try {
      List(
        JSON.Object("jsonrpc" -> "2.0", "id" -> "initialize-drain", "method" -> "initialize",
          "params" -> JSON.Object("protocolVersion" -> "2025-03-26")),
        JSON.Object("jsonrpc" -> "2.0", "method" -> "notifications/initialized"),
        JSON.Object("jsonrpc" -> "2.0", "id" -> "ordinary-drain", "method" -> "tools/call",
          "params" -> JSON.Object("name" -> "shout",
            "arguments" -> JSON.Object("input" -> "drained")))
      ).foreach(json => writer.write(JSON.Format(json) + "\n"))
      writer.flush()
      assert(backend.started.await(2, TimeUnit.SECONDS), "ordinary drain request never started")
      writer.close()
      assert(!backend.stoppedLatch.await(100, TimeUnit.MILLISECONDS),
        "backend teardown ran before the in-flight request completed")
      backend.release.countDown()
      check_server(server, server_failure, 2000L)
      val replies = split_lines(out_stream.toString(StandardCharsets.UTF_8))
        .flatMap(JSON.Format.unapply)
      assert(replies.exists(value => JSON.value(value, "id").contains("ordinary-drain")),
        "ordinary response was dropped during production EOF drain")
      assertEquals(backend.eventOrder, List("completed", "stopped"))
    }
    finally {
      backend.release.countDown()
      try writer.close() catch { case _: Throwable => () }
    }
  }
}


/* tool_scope_show/set (plans/context_locator): the connection's tool
   scope, distinct from the phase-2 RESOURCE scope (scope_add/...) --
   resource scope filters resource LISTING, tool scope picks WHICH
   CONTEXT DEFINES THE TOOL SET. every test here shares ONE Handler via
   rpc_on/call_tool_on (the ordinary rpc()/call_tool() helpers build a
   fresh, stateless Handler per call, wrong for scope persistence). */

class MCP_Tool_Scope_Tests extends MCP_Suite {
  test("tools/list includes locator-based tool_scope_show/set schemas") {
    val show = tool_row("tool_scope_show")
    assertEquals(annotation(show, "readOnlyHint"), true)
    val set = tool_row("tool_scope_set")
    assertEquals(required_args(set), List("context"))
    assertEquals(property_type(set, "context"), "string")
    assert(!MCP_Server.all_builtin_names.contains("tool_scope_include"))
  }

  test("tool_scope_show obtains the canonical default locator from the backend") {
    val handler = new MCP_Server.Handler(new Fake_Backend)
    val text = result_text(call_tool_on(handler, "tool_scope_show", JSON.Object()))
    assert(text.contains("isabelle://context/theory/MCP_Tools"), text)
    assert(text.contains("MCP_Tools.shout"), text)
  }

  test("tool_scope_show: a scope that goes stale after tool_scope_set reports BROKEN, not a silent zero") {
    val backend = new Fake_Backend
    val handler = new MCP_Server.Handler(backend)
    assert_no_error(call_tool_on(handler, "tool_scope_set",
      JSON.Object("context" -> "isabelle://context/repl/R")))
    /* the repl existed at set-time (check_context passed) but is
       gone by the time tool_scope_show reads it -- e.g. repl_remove'd
       in between, so tool_scope_show checks before listing. */
    backend.known_repls = Set.empty
    val text = result_text(call_tool_on(handler, "tool_scope_show", JSON.Object()))
    assert(text.contains("BROKEN"), text)
    assert(text.contains("R"), text)
  }

  test("tool_scope_set: missing context is an error") {
    val handler = new MCP_Server.Handler(new Fake_Backend)
    assert_is_error(call_tool_on(handler, "tool_scope_set", JSON.Object()))
  }

  test("tool_scope_set: malformed locator is an isError and leaves state unchanged") {
    val handler = new MCP_Server.Handler(new Fake_Backend)
    val msg = assert_is_error(call_tool_on(handler, "tool_scope_set",
      JSON.Object("context" -> "No_Such_Theory")))
    assert(msg.contains("No_Such_Theory"), msg)
    val show = result_text(call_tool_on(handler, "tool_scope_show", JSON.Object()))
    assert(show.contains("isabelle://context/theory/MCP_Tools"),
      "scope should be unchanged: " + show)
  }

  test("tool_scope_set: theory locator round-trips through tool_scope_show") {
    val handler = new MCP_Server.Handler(new Fake_Backend)
    assert_no_error(call_tool_on(handler, "tool_scope_set",
      JSON.Object("context" -> "isabelle://context/theory/HOL.Main")))
    val show = result_text(call_tool_on(handler, "tool_scope_show", JSON.Object()))
    assert(show.contains("isabelle://context/theory/HOL.Main"), show)
  }

  test("tool_scope_set: unknown repl locator is an isError and leaves state unchanged") {
    val handler = new MCP_Server.Handler(new Fake_Backend)
    val msg = assert_is_error(call_tool_on(handler, "tool_scope_set",
      JSON.Object("context" -> "isabelle://context/repl/NOPE")))
    assert(msg.contains("NOPE"), msg)
    val show = result_text(call_tool_on(handler, "tool_scope_show", JSON.Object()))
    assert(show.contains("isabelle://context/theory/MCP_Tools"),
      "scope should be unchanged: " + show)
  }

  test("tool_scope_set: known repl locator round-trips through tool_scope_show") {
    val handler = new MCP_Server.Handler(new Fake_Backend)
    assert_no_error(call_tool_on(handler, "tool_scope_set",
      JSON.Object("context" -> "isabelle://context/repl/R")))
    val show = result_text(call_tool_on(handler, "tool_scope_show", JSON.Object()))
    assert(show.contains("isabelle://context/repl/R"), show)
  }

  test("a colliding ML tool does not shadow tool_scope_show") {
    val backend = new Fake_Backend
    backend.extra_ml_tools =
      List(MCP_Session.Tool_Row("Some_Theory.tool_scope_show", "not the real one",
        "string_fun", Nil, MCP_Session.Tool_Annotations.default))
    val row = tool_row("tool_scope_show", backend)
    assert(get_string(row, "description").contains("Show the current context locator"),
      get_string(row, "description"))
  }
}


/* resources: list, read, templates */

class MCP_Resources_Tests extends MCP_Suite {
  test("resources/list reports the backend resources") {
    /* a fresh Fake_Backend seeds loaded_theories = Set("Loaded") (a
       fixture for the unload_theory error-path tests), which the S1
       resource-scope implicit working set now surfaces as
       isabelle://theory/Loaded -- see plans/scope_add. */
    val resources = get_list(rpc("resources/list"), "result", "resources")
    assertEquals(resources.length, 3,
      "expected isabelle://session + isabelle://named/greeting + isabelle://theory/Loaded")
    assertEquals(get_string(resources.head, "uri"), "isabelle://session")
    assertEquals(get_string(resources.head, "name"), "session")
    assertEquals(get_string(resources(1), "uri"), "isabelle://named/greeting")
    assertEquals(get_string(resources(1), "name"), "greeting")
    assertEquals(get_string(resources(2), "uri"), "isabelle://theory/Loaded")
    assertEquals(get_string(resources(2), "description"), "theory (loaded)")
  }

  /* isabelle://named/{name}: MCP_Resource's registry (real backing:
     mcp_session.scala's ml_named_resources/ml_read_resource dispatching
     to MCP_Tools.thy's MCP_Resource, tested for real in
     mcp_bridge_tests.scala; here just the routing through Fake_Backend). */
  test("resources/read on isabelle://named/{name} dispatches to the named-resource registry") {
    val reply = rpc("resources/read", JSON.Object("uri" -> "isabelle://named/greeting"))
    val contents = get_list(reply, "result", "contents")
    assertEquals(get_string(contents.head, "text"), "a static demo resource")
  }

  test("resources/read on isabelle://named/{name} for an unknown name is an error") {
    val reply = rpc("resources/read", JSON.Object("uri" -> "isabelle://named/no_such_resource"))
    assertEquals(get(reply, "error", "code"), MCP_Server.RPC.INVALID_PARAMS)
  }

  test("resources/read returns the resource contents") {
    val reply = rpc("resources/read", JSON.Object("uri" -> "isabelle://session"))
    val contents = get_list(reply, "result", "contents")
    assertEquals(get_string(contents.head, "uri"), "isabelle://session")
    val text = get_string(contents.head, "text")
    assert(text.contains("session: TEST"), "bad contents text: " + text)
    assert(text.contains("theories: Fake_Theory"), "missing theories line: " + text)
  }

  test("resources/read on an unknown uri yields an error") {
    val reply = rpc("resources/read", JSON.Object("uri" -> "isabelle://no-such-resource"))
    assertEquals(get(reply, "error", "code"), MCP_Server.RPC.INVALID_PARAMS)
  }

  test("resources/read without uri yields -32602") {
    val reply = rpc("resources/read", JSON.Object())
    assertEquals(get(reply, "error", "code"), MCP_Server.RPC.INVALID_PARAMS)
  }

  test("resources/templates/list contains the documented templates, incl. isabelle://repl/{id} and its /text sibling") {
    val templates = get_list(rpc("resources/templates/list"), "result", "resourceTemplates")
    val by_uri = templates.map(t => get_string(t, "uriTemplate")).toSet
    assert(by_uri.contains("isabelle://repl/{id}"), "missing isabelle://repl/{id} template")
    assert(by_uri.contains("isabelle://repl/{id}/text"),
      "missing isabelle://repl/{id}/text template")
    assert(by_uri.contains("isabelle://theory/{name}"), "missing isabelle://theory/{name}")
    assert(by_uri.contains("isabelle://named/{name}"), "missing isabelle://named/{name}")
    templates.foreach(t =>
      assertEquals(get_string(t, "mimeType"), "text/plain",
        "every template is text/plain: " + t))
  }

  /* isabelle://repl/{id} and .../text: thin dispatch onto the same
     MCP.ir bridge repl_show/repl_text use (see Fake_Backend and, for
     real, MCP_Session.mcp_resource_read). */
  test("resources/read on isabelle://repl/{id} dispatches to ir show") {
    val reply = rpc("resources/read", JSON.Object("uri" -> "isabelle://repl/T"))
    val contents = get_list(reply, "result", "contents")
    assertEquals(get_string(contents.head, "uri"), "isabelle://repl/T")
    assertEquals(get_string(contents.head, "text"), "REPL \"T\"", "did not reach ir show")
  }

  test("resources/read on isabelle://repl/{id}/text dispatches to ir text") {
    val reply = rpc("resources/read", JSON.Object("uri" -> "isabelle://repl/T/text"))
    val contents = get_list(reply, "result", "contents")
    assertEquals(get_string(contents.head, "uri"), "isabelle://repl/T/text")
  }

  test("resources/read on a documented-but-not-yet-backed template names the gap, not a bare unknown-resource error") {
    val reply = rpc("resources/read", JSON.Object("uri" -> "isabelle://theory/Main"))
    assertEquals(get(reply, "error", "code"), MCP_Server.RPC.INVALID_PARAMS)
    assert(get_string(reply, "error", "message").contains("not backed yet"),
      "error should name the gap, not just say unknown: " + JSON.Format(reply))
  }

  /* isabelle://theory/{name}/diagnostics (plans/load_theory,
     unblocked by wave 2): the spec's three-tier answer, distinct from
     the other still-unbacked theory templates above. */
  test("resources/read on isabelle://theory/{name}/diagnostics -- image tier reports checked at build time") {
    val reply = rpc("resources/read", JSON.Object("uri" -> "isabelle://theory/Image/diagnostics"))
    val contents = get_list(reply, "result", "contents")
    assertEquals(get_string(contents.head, "text"), "Image: checked at build time")
  }

  test("resources/read on isabelle://theory/{name}/diagnostics -- loaded tier reads live diagnostics") {
    val reply = rpc("resources/read", JSON.Object("uri" -> "isabelle://theory/Loaded/diagnostics"))
    val contents = get_list(reply, "result", "contents")
    assertEquals(get_string(contents.head, "text"), "Loaded: ok")
  }

  test("resources/read on isabelle://theory/{name}/diagnostics -- filesystem tier nudges to load_theory") {
    val reply =
      rpc("resources/read", JSON.Object("uri" -> "isabelle://theory/NeverLoaded/diagnostics"))
    val contents = get_list(reply, "result", "contents")
    assert(get_string(contents.head, "text").contains("load_theory to check"),
      "filesystem-tier diagnostics should nudge to load_theory: " + JSON.Format(reply))
  }

  /* dispatch-routing only: Fake_Backend models what these would return
     IF Ir.source/source_map's Thy_Info segments were reachable -- the
     real MCP_Session hits a KNOWN GAP where they never are, against a
     live headless process (see mcp_bridge_tests.scala's "KNOWN GAP"
     test and mcp_session.scala's theory_source_uri/theory_commands_uri
     comment). This test only pins that image-tier names route to the
     ir bridge rather than the not_yet_backed_uri fallback. */
  test("resources/read on isabelle://theory/{name} and .../commands -- image tier routes to the ir bridge, not the not-backed-yet fallback") {
    val source = rpc("resources/read", JSON.Object("uri" -> "isabelle://theory/Image"))
    assert(get_string(get_list(source, "result", "contents").head, "text").contains("theory Image"),
      "image-tier source should route to the ir bridge: " + JSON.Format(source))

    val commands = rpc("resources/read", JSON.Object("uri" -> "isabelle://theory/Image/commands"))
    assert(get_string(get_list(commands, "result", "contents").head, "text").contains("idx"),
      "image-tier commands should route to the ir bridge: " + JSON.Format(commands))
  }

  test("resources/read on isabelle://theory/{name} -- non-image tier still not backed") {
    val reply = rpc("resources/read", JSON.Object("uri" -> "isabelle://theory/NeverLoaded"))
    assertEquals(get(reply, "error", "code"), MCP_Server.RPC.INVALID_PARAMS)
    assert(get_string(reply, "error", "message").contains("not backed yet"),
      "non-image theory source should still name the gap: " + JSON.Format(reply))
  }

  /* isabelle://theory/{name}/entities -- unlike /commands, this genuinely
     works for image theories (Name_Space.theory_name reads heap-
     serialized bookkeeping, not the process-local Thy_Info segments
     /commands hits); see mcp_bridge_tests.scala for the real,
     non-simulated confirmation against a live session. */
  test("resources/read on isabelle://theory/{name}/entities -- image tier is backed for real") {
    val reply = rpc("resources/read", JSON.Object("uri" -> "isabelle://theory/Image/entities"))
    assert(get_string(get_list(reply, "result", "contents").head, "text").contains("fact"),
      "image-tier entities should route to the ir bridge: " + JSON.Format(reply))
  }

  test("resources/read on isabelle://theory/{name}/entities -- non-image tier still not backed") {
    val reply = rpc("resources/read", JSON.Object("uri" -> "isabelle://theory/NeverLoaded/entities"))
    assertEquals(get(reply, "error", "code"), MCP_Server.RPC.INVALID_PARAMS)
    assert(get_string(reply, "error", "message").contains("not backed yet"),
      "non-image theory entities should still name the gap: " + JSON.Format(reply))
  }
}


/* scope_add/scope_remove (plans/scope_add, plans/scope_remove): the
   phase-2 RESOURCE scope -- a set of theory-name glob patterns
   controlling what resources/list enumerates, distinct from the
   tool_scope_* family above (the agent CONTEXT). every test shares one
   Fake_Backend so scope state persists across the add/remove/list
   calls that make up a scenario. */
class MCP_Resource_Scope_Tests extends MCP_Suite {
  test("tools/list includes scope_add/scope_remove with their schemas") {
    val add = tool_row("scope_add")
    assertEquals(required_args(add), List("patterns"))
    assertEquals(property_type(add, "patterns"), "array")
    assertEquals(annotation(add, "readOnlyHint"), false)
    assertEquals(annotation(add, "idempotentHint"), true)

    val remove = tool_row("scope_remove")
    assertEquals(required_args(remove), List("patterns"))
    assertEquals(property_type(remove, "patterns"), "array")
  }

  /* T1 (plans/scope_add S1): fresh scope + Fake_Backend -> resources/list
     shows only isabelle://session, isabelle://named/greeting and the
     implicit isabelle://theory/Loaded (see the fixed "resources/list
     reports the backend resources" test above) -- no pattern-matched
     entries until scope_add runs. */
  test("fresh scope: resources/list has no pattern-matched entries") {
    val backend = new Fake_Backend
    val resources = get_list(rpc("resources/list", backend = backend), "result", "resources")
    assert(!resources.exists(r => get_string(r, "uri") == "isabelle://theory/Image"),
      "fresh scope should not list the image theory: " + resources.toString)
  }

  /* T2: match counts in the reply are computed against the full known
     universe (Fake_Backend.theory_universe), tier included. */
  test("scope_add reports match counts against the known theory universe") {
    val backend = new Fake_Backend
    val text = result_text(call_tool("scope_add", JSON.Object("patterns" -> List("HOL-Library.*")), backend))
    assert(text.contains("HOL-Library.*: added (3 theories match)"),
      "unexpected scope_add reply: " + text)
  }

  test("scope_add with a zero-match pattern is accepted, pinned as count 0, not an error") {
    val backend = new Fake_Backend
    val reply = call_tool("scope_add", JSON.Object("patterns" -> List("NoSuchPrefix.*")), backend)
    assert_no_error(reply)
    assert(result_text(reply).contains("NoSuchPrefix.*: added (0 theories match)"),
      "zero-match scope_add should still report count 0: " + result_text(reply))
  }

  /* T3: idempotency -- adding the same pattern twice keeps the same
     scope (no duplicate listing entries) and the second reply says
     already present. */
  test("scope_add is idempotent: adding the same pattern twice reports already-in-scope the second time") {
    val backend = new Fake_Backend
    call_tool("scope_add", JSON.Object("patterns" -> List("HOL-Library.*")), backend)
    val second = call_tool("scope_add", JSON.Object("patterns" -> List("HOL-Library.*")), backend)
    assert(result_text(second).contains("HOL-Library.*: already in scope"),
      "duplicate scope_add should say already in scope: " + result_text(second))
    assertEquals(backend.scope_patterns, List("HOL-Library.*"), "no duplicate pattern stored")
  }

  /* the added pattern's matches appear in resources/list, tier-tagged. */
  test("scope_add's matches appear in resources/list, tier-tagged") {
    val backend = new Fake_Backend
    call_tool("scope_add", JSON.Object("patterns" -> List("HOL-Library.*")), backend)
    val resources = get_list(rpc("resources/list", backend = backend), "result", "resources")
    val multiset = resources.find(r => get_string(r, "uri") == "isabelle://theory/HOL-Library.Multiset")
      .getOrElse(fail("HOL-Library.Multiset missing from resources/list: " + resources.toString))
    assertEquals(get_string(multiset, "description"), "theory (filesystem)")
  }

  test("scope_add on an image-tier pattern tags it image") {
    val backend = new Fake_Backend
    call_tool("scope_add", JSON.Object("patterns" -> List("Image")), backend)
    val resources = get_list(rpc("resources/list", backend = backend), "result", "resources")
    val image = resources.find(r => get_string(r, "uri") == "isabelle://theory/Image")
      .getOrElse(fail("Image missing from resources/list: " + resources.toString))
    assertEquals(get_string(image, "description"), "theory (image)")
  }

  /* T1 (plans/scope_remove): add then remove restores the previous
     resources/list exactly. */
  test("scope_add then scope_remove of the same pattern restores the previous resources/list") {
    val backend = new Fake_Backend
    val before = get_list(rpc("resources/list", backend = backend), "result", "resources")
    call_tool("scope_add", JSON.Object("patterns" -> List("HOL-Library.*")), backend)
    call_tool("scope_remove", JSON.Object("patterns" -> List("HOL-Library.*")), backend)
    val after = get_list(rpc("resources/list", backend = backend), "result", "resources")
    assertEquals(after, before, "resources/list should be back to its pre-scope-add state")
  }

  /* T2 (plans/scope_remove): literal removal semantics -- removing a
     theory name that happened to match an added glob does not remove
     the glob itself; the glob remains and its matches stay listed. */
  test("scope_remove is literal: removing a matched theory name, not the glob, is a no-op reported as not-in-scope") {
    val backend = new Fake_Backend
    call_tool("scope_add", JSON.Object("patterns" -> List("HOL-Library.*")), backend)
    val reply =
      call_tool("scope_remove", JSON.Object("patterns" -> List("HOL-Library.Multiset")), backend)
    assert(result_text(reply).contains("HOL-Library.Multiset: not in scope"),
      "literal removal of a non-pattern name should say not in scope: " + result_text(reply))
    assertEquals(backend.scope_patterns, List("HOL-Library.*"), "the glob itself must survive")
    val resources = get_list(rpc("resources/list", backend = backend), "result", "resources")
    assert(resources.exists(r => get_string(r, "uri") == "isabelle://theory/HOL-Library.Multiset"),
      "Multiset should still be listed: " + resources.toString)
  }

  /* T3 (plans/scope_remove): implicit members (theories loaded via
     load_theory, tracked in loaded_theories here) are not removable by
     scope_remove -- "Loaded" is in scope by load, not by pattern. */
  test("scope_remove of an implicit (loaded) member's name does not delist it") {
    val backend = new Fake_Backend
    val reply = call_tool("scope_remove", JSON.Object("patterns" -> List("Loaded")), backend)
    assert(result_text(reply).contains("Loaded: not in scope"),
      "an implicit member's name was never a pattern: " + result_text(reply))
    val resources = get_list(rpc("resources/list", backend = backend), "result", "resources")
    assert(resources.exists(r => get_string(r, "uri") == "isabelle://theory/Loaded"),
      "the implicitly-loaded theory should still be listed: " + resources.toString)
  }

  /* T4 (plans/scope_add): load_theory auto-adds to the implicit working
     set; unload removes it -- via Fake_Backend.loaded_theories, which
     mcp_resources() folds into scope regardless of any pattern. */
  test("load_theory auto-adds to the scope listing; unload_theory removes it") {
    val backend = new Fake_Backend
    call_tool("load_theory", JSON.Object("name" -> "HOL-Library.Rat"), backend)
    val listed = get_list(rpc("resources/list", backend = backend), "result", "resources")
    assert(listed.exists(r => get_string(r, "uri") == "isabelle://theory/HOL-Library.Rat"),
      "load_theory should auto-add to resources/list: " + listed.toString)
    call_tool("unload_theory", JSON.Object("name" -> "HOL-Library.Rat"), backend)
    val after_unload = get_list(rpc("resources/list", backend = backend), "result", "resources")
    assert(!after_unload.exists(r => get_string(r, "uri") == "isabelle://theory/HOL-Library.Rat"),
      "unload_theory should remove it from resources/list: " + after_unload.toString)
  }

  /* list_changed: scope_add/scope_remove fire notifications/resources/
     list_changed on an actual change; a no-op (duplicate add, absent
     remove) fires nothing. */
  test("scope_add fires resources list_changed on an actual change, not on a duplicate") {
    val backend = new Fake_Backend
    var seen: List[String] = Nil
    backend.changed_handler = seen ::= _
    call_tool("scope_add", JSON.Object("patterns" -> List("HOL-Library.*")), backend)
    assertEquals(seen, List("resources"), "first add should fire exactly one resources notification")
    seen = Nil
    call_tool("scope_add", JSON.Object("patterns" -> List("HOL-Library.*")), backend)
    assertEquals(seen, Nil, "duplicate add should not fire a notification")
  }

  test("scope_remove fires resources list_changed on an actual change, not on a no-op") {
    val backend = new Fake_Backend
    call_tool("scope_add", JSON.Object("patterns" -> List("HOL-Library.*")), backend)
    var seen: List[String] = Nil
    backend.changed_handler = seen ::= _
    call_tool("scope_remove", JSON.Object("patterns" -> List("NoSuchPattern")), backend)
    assertEquals(seen, Nil, "removing an absent pattern should not fire a notification")
    call_tool("scope_remove", JSON.Object("patterns" -> List("HOL-Library.*")), backend)
    assertEquals(seen, List("resources"), "removing a present pattern should fire exactly one notification")
  }
}


/* scope_show (plans/scope_show): the read side of S1 -- explicit
   patterns with match counts, plus every implicit member (loaded
   theories, active repls, named resources). Zero-arg like repl_list. */
class MCP_Scope_Show_Tests extends MCP_Suite {
  test("tools/list includes scope_show with its schema") {
    val show = tool_row("scope_show")
    assertEquals(get(show, "inputSchema"), JSON.Object("type" -> "object"))
    assertEquals(annotation(show, "readOnlyHint"), true)
    assertEquals(annotation(show, "idempotentHint"), true)
  }

  /* T1: fresh state -> only the implicit working set (Fake_Backend's
     "Loaded" theory, no patterns, no repls, the fixed "greeting" named
     resource). */
  spec_test("fresh state names only the implicit members",
      covers = List("scope_show#T1")) {
    val backend = new Fake_Backend
    val text = result_text(call_tool("scope_show", JSON.Object(), backend))
    assert(text.contains("patterns: (none)"), "no patterns yet: " + text)
    assert(text.contains("theories:") && text.contains("  Loaded (loaded)"),
      "the startup theory should be listed as loaded: " + text)
    assert(text.contains("repls: (none)"), "no repls yet: " + text)
    assert(text.contains("named resources:") && text.contains("  greeting"),
      "the fixed named resource should be listed: " + text)
  }

  /* T2 (patterns): scope_add's pattern shows up with its match count;
     scope_remove makes it disappear again. */
  spec_test("a scope_add pattern appears in scope_show; scope_remove removes it",
      covers = List("scope_show#T2")) {
    val backend = new Fake_Backend
    call_tool("scope_add", JSON.Object("patterns" -> List("HOL-Library.*")), backend)
    val added = result_text(call_tool("scope_show", JSON.Object(), backend))
    assert(added.contains("  HOL-Library.* (3 theories match)"),
      "the added pattern should be listed with its match count: " + added)
    call_tool("scope_remove", JSON.Object("patterns" -> List("HOL-Library.*")), backend)
    val removed = result_text(call_tool("scope_show", JSON.Object(), backend))
    assert(removed.contains("patterns: (none)"), "the removed pattern should disappear: " + removed)
  }

  /* T2 (repls): Fake_Backend.active_repls is the settable stand-in for
     the real backend's ir("repls")-derived list -- a created/removed
     repl shows up/disappears the same way a loaded theory does. */
  spec_test("an active repl appears in scope_show; its removal makes it disappear",
      covers = List("scope_show#T2")) {
    val backend = new Fake_Backend
    backend.active_repls = List("R")
    val present = result_text(call_tool("scope_show", JSON.Object(), backend))
    assert(present.contains("repls:") && present.contains("  R"), "R should be listed: " + present)
    backend.active_repls = Nil
    val absent = result_text(call_tool("scope_show", JSON.Object(), backend))
    assert(absent.contains("repls: (none)"), "R should be gone: " + absent)
  }

  /* T2 (load_theory): the implicit working set tracked via
     load_theory/check_theory shows up the same way. */
  spec_test("load_theory's implicit member appears in scope_show; unload_theory removes it",
      covers = List("scope_show#T2")) {
    val backend = new Fake_Backend
    call_tool("load_theory", JSON.Object("name" -> "HOL-Library.Rat"), backend)
    val loaded = result_text(call_tool("scope_show", JSON.Object(), backend))
    assert(loaded.contains("  HOL-Library.Rat (filesystem)"),
      "load_theory's theory should be listed, tier-tagged: " + loaded)
    call_tool("unload_theory", JSON.Object("name" -> "HOL-Library.Rat"), backend)
    val unloaded = result_text(call_tool("scope_show", JSON.Object(), backend))
    assert(!unloaded.contains("HOL-Library.Rat"), "unload_theory should remove it: " + unloaded)
  }

  /* T3: agreement with resources/list -- every theory/repl scope_show
     names is also listed by resources/list (as a uri), and vice versa
     (restricting resources/list to the theory/repl uris it shares with
     scope_show's vocabulary). */
  spec_test("scope_show's theories and repls agree with resources/list",
      covers = List("scope_show#T3")) {
    val backend = new Fake_Backend
    backend.active_repls = List("R")
    call_tool("scope_add", JSON.Object("patterns" -> List("HOL-Library.*")), backend)
    val shown = result_text(call_tool("scope_show", JSON.Object(), backend))
    val shown_theories =
      shown.linesIterator.dropWhile(_ != "theories:").drop(1).takeWhile(_.startsWith("  "))
        .map(_.trim.takeWhile(_ != ' ')).toSet
    val shown_repls =
      shown.linesIterator.dropWhile(_ != "repls:").drop(1).takeWhile(_.startsWith("  "))
        .map(_.trim).toSet
    val resources = get_list(rpc("resources/list", backend = backend), "result", "resources")
    val listed_theories =
      resources.flatMap(r => get_string(r, "uri").stripPrefix("isabelle://theory/") match {
        case s if s != get_string(r, "uri") => Some(s)
        case _ => None
      }).toSet
    val listed_repls =
      resources.flatMap(r => get_string(r, "uri").stripPrefix("isabelle://repl/") match {
        case s if s != get_string(r, "uri") => Some(s)
        case _ => None
      }).toSet
    assertEquals(shown_theories, listed_theories, "theories must agree between scope_show and resources/list")
    assertEquals(shown_repls, listed_repls, "repls must agree between scope_show and resources/list")
  }
}


/* pure codecs: tools/call json conversion, MCP.ir yxml argument encoding */

class MCP_Codec_Tests extends MCP_Suite {
  test("json_args: a json array becomes repeated pairs in array order") {
    val args =
      MCP_Server.json_args(
        JSON.Object("repl" -> "T", "theories" -> List("HOL.Main", "HOL-Library.Multiset")))
    assertEquals(args,
      List("repl" -> "T", "theories" -> "HOL.Main", "theories" -> "HOL-Library.Multiset"))
  }

  test("json_args: a json integer becomes a bare (unquoted) string pair") {
    val args = MCP_Server.json_args(JSON.Object("repl" -> "T", "state_idx" -> -1))
    assertEquals(args, List("repl" -> "T", "state_idx" -> "-1"))
  }

  test("MCP.ir args: empty argument object encodes to the empty pair list") {
    val encoded = MCP_Session.encode_args(Nil)
    assertEquals(MCP_Session.decode_args(YXML.parse_body(YXML.Source(encoded))),
      Nil: List[(String, String)])
  }

  /* was "round-trip byte-clean" before the client-edge recoding boundary
     landed: encode_args now carries recode = Symbol.encode, so the
     property splits in two -- ascii symbol notation is still byte-clean
     (this test), unicode is normalized INTO symbol notation on the wire
     (the next one, and MCP_Symbol_Tests). */
  test("MCP.ir args: repeated keys, newlines and ascii symbol notation round-trip byte-clean") {
    val args =
      List(
        "repl" -> "T",
        "theories" -> "HOL.Main",
        "theories" -> "HOL-Library.Multiset",
        "isar_text" -> "lemma \"x + y = y + (x::nat)\"\n  by simp",
        "isar_text" -> "have \"A \\<Longrightarrow> A\" \\<Rightarrow> \\<alpha>")
    val decoded =
      MCP_Session.decode_args(YXML.parse_body(YXML.Source(MCP_Session.encode_args(args))))
    assertEquals(decoded, args)
  }

  test("MCP.ir args: unicode is normalized to symbol notation on the wire") {
    val decoded =
      MCP_Session.decode_args(YXML.parse_body(YXML.Source(
        MCP_Session.encode_args(List("isar_text" -> "have \"A ⟹ A\" ⇒ α ‹inner›")))))
    assertEquals(decoded,
      List("isar_text" ->
        "have \"A \\<Longrightarrow> A\" \\<Rightarrow> \\<alpha> \\<open>inner\\<close>"))
  }
}


/* the client-edge recoding boundary (spec: "symbol recoding at the
   client edge"). ML speaks symbol notation, the mcp client speaks
   unicode, and the protocol channel the bridge rides recodes in NEITHER
   direction (Pure/PIDE/prover.scala skips Symbol.decode for PROTOCOL
   chunks and protocol_command_raw skips Symbol.encode_yxml), so the
   mcp server does it itself: Symbol.decode in text_result /
   resource_contents, recode = Symbol.encode in encode_args /
   encode_names. */

class MCP_Symbol_Tests extends MCP_Suite {
  private val unicode = "have \"A ⟹ A\" ⇒ α ‹inner›"
  private val notation = "have \"A \\<Longrightarrow> A\" \\<Rightarrow> \\<alpha> \\<open>inner\\<close>"

  /* the property text_result's placement relies on: decoding text that
     is ALREADY unicode must not change it, so content that arrived via
     the non-protocol channel (render_messages over snapshot messages,
     which Isabelle decoded upstream) survives a second decode intact */
  test("Symbol.decode is idempotent on already-decoded text") {
    assertEquals(Symbol.decode(unicode), unicode)
    assertEquals(Symbol.decode(Symbol.decode(notation)), Symbol.decode(notation))
    assertEquals(Symbol.decode(notation), unicode)
  }

  test("Symbol.encode is a no-op on text that is already symbol notation") {
    assertEquals(Symbol.encode(notation), notation)
    assertEquals(Symbol.encode(unicode), notation)
  }

  /* the known limitation: sub/superscript and bold CONTROL symbols
     decode to marker glyphs, not to typeset text -- jEdit does that
     with font styling and unicode cannot express it */
  test("control symbols decode to marker glyphs, and round-trip through encode") {
    val decoded = Symbol.decode("x\\<^sub>1 y\\<^sup>2 \\<^bold>z")
    assertEquals(decoded, "x⇩" + "1 y⇧" + "2 ❙z")
    assertEquals(Symbol.encode(decoded), "x\\<^sub>1 y\\<^sup>2 \\<^bold>z")
  }

  /* symbols with no code: field in etc/symbols (mostly \<^const>,
     \<^cterm>, the ml antiquotation controls) have no unicode to decode
     to and must pass through untouched -- byte precision preserved
     exactly where it matters */
  test("symbols without a unicode code pass through decode unchanged") {
    val text = "\\<^const>foo \\<^cterm>bar"
    assertEquals(Symbol.decode(text), text)
  }

  test("text_result decodes symbol notation for the client") {
    assertEquals(
      get_string(get_list(MCP_Server.text_result(notation), "content").head, "text"),
      unicode)
  }

  test("text_result decodes error text too") {
    val result = MCP_Server.text_result(notation, is_error = true)
    assertEquals(get_string(get_list(result, "content").head, "text"), unicode)
    assertEquals(get(result, "isError"), true)
  }

  test("resource_contents decodes symbol notation for the client") {
    assertEquals(
      get_string(get_list(MCP_Server.resource_contents("isabelle://repl/T", notation),
        "contents").head, "text"),
      unicode)
  }

  test("text_result leaves already-unicode text alone") {
    assertEquals(
      get_string(get_list(MCP_Server.text_result(unicode), "content").head, "text"),
      unicode)
  }

  /* end to end over the handler: repl_step's fake echoes isar_text back,
     so what the client sees is exactly what the outbound edge produced.
     Note this exercises the OUTBOUND half only -- the fake backend is
     handed the args before encode_args runs (that lives in the real
     MCP_Session), which is why the inbound half is asserted at the
     codec level above. */
  test("tools/call: symbol notation coming back from the prover reaches the client as unicode") {
    val reply = call_tool("repl_step", JSON.Object("repl" -> "T", "isar_text" -> notation))
    assert_no_error(reply)
    assertEquals(result_text(reply), unicode)
  }

  test("tools/call: unicode sent by the model survives the round trip as unicode") {
    val reply = call_tool("repl_step", JSON.Object("repl" -> "T", "isar_text" -> unicode))
    assert_no_error(reply)
    assertEquals(result_text(reply), unicode)
  }
}


/* MCP_Session.Locator (plans/repl_init_from_source step 2): the PURE
   half of command-target resolution -- offset/pattern/index against a
   canned command list, no prover needed. Mirrors the shape (and error
   wording expectations) of the ML-side init_from_segment resolver in
   MCP_Repl.thy, which the bridge tests exercise instead (segment text
   is only ever available in the process that recorded it). */
class MCP_Locator_Tests extends MCP_Suite {
  private val items =
    List(
      MCP_Session.Locator.Item(1L, 0, 10, "lemma foo"),
      MCP_Session.Locator.Item(2L, 10, 8, "by simp"),
      MCP_Session.Locator.Item(3L, 18, 15, "lemma bar: True"))

  test("exactly_one: zero locators is an error") {
    assertEquals(MCP_Session.Locator.exactly_one(None, None, None).isLeft, true)
  }

  test("exactly_one: two locators is an error") {
    assertEquals(MCP_Session.Locator.exactly_one(Some(0), Some("x"), None).isLeft, true)
    assertEquals(MCP_Session.Locator.exactly_one(Some(0), None, Some(0)).isLeft, true)
    assertEquals(MCP_Session.Locator.exactly_one(None, Some("x"), Some(0)).isLeft, true)
  }

  test("exactly_one: exactly one locator is accepted") {
    assertEquals(MCP_Session.Locator.exactly_one(Some(0), None, None), Right(()))
    assertEquals(MCP_Session.Locator.exactly_one(None, Some("x"), None), Right(()))
    assertEquals(MCP_Session.Locator.exactly_one(None, None, Some(0)), Right(()))
  }

  test("resolve: offset picks the containing command") {
    assertEquals(MCP_Session.Locator.resolve(items, Some(12), None, None), Right(2L))
    assertEquals(MCP_Session.Locator.resolve(items, Some(0), None, None), Right(1L))
    assertEquals(MCP_Session.Locator.resolve(items, Some(32), None, None), Right(3L))
  }

  test("resolve: offset outside every command is an error") {
    assertEquals(MCP_Session.Locator.resolve(items, Some(1000), None, None).isLeft, true)
  }

  test("resolve: pattern picks the first command whose source contains it") {
    assertEquals(MCP_Session.Locator.resolve(items, None, Some("lemma"), None), Right(1L))
    assertEquals(MCP_Session.Locator.resolve(items, None, Some("simp"), None), Right(2L))
    assertEquals(MCP_Session.Locator.resolve(items, None, Some("bar"), None), Right(3L))
  }

  test("resolve: pattern not found is an error") {
    assertEquals(MCP_Session.Locator.resolve(items, None, Some("no_such_text"), None).isLeft, true)
  }

  test("resolve: index picks the nth command, negative indices count from the end") {
    assertEquals(MCP_Session.Locator.resolve(items, None, None, Some(0)), Right(1L))
    assertEquals(MCP_Session.Locator.resolve(items, None, None, Some(2)), Right(3L))
    assertEquals(MCP_Session.Locator.resolve(items, None, None, Some(-1)), Right(3L))
  }

  test("resolve: index out of range is an error") {
    assertEquals(MCP_Session.Locator.resolve(items, None, None, Some(3)).isLeft, true)
    assertEquals(MCP_Session.Locator.resolve(items, None, None, Some(-4)).isLeft, true)
  }
}


/* wave 5 (plans/doc_list): Doc_Catalog is a pure function of
   Sessions.Structure -- runs against the REAL bundled distribution's
   structure and Doc.contents(), no fake catalog, no headless PIDE
   session needed (Sessions.load_structure alone is cheap). */

class MCP_Doc_Catalog_Tests extends MCP_Suite {
  private def real_structure(): Sessions.Structure =
    Sessions.load_structure(MCP_Test_Config.options, dirs = MCP_Test_Config.session_dirs)

  private lazy val catalog: List[Doc_Catalog.Section] = Doc_Catalog.make(real_structure())

  private def entry(name: String): Doc_Catalog.Entry =
    catalog.flatMap(_.entries).find(_.name == name)
      .getOrElse(fail("no catalog entry named " + quote(name)))

  /* T1: the join claim, three naming shapes -- hyphen vs underscore,
     case + hyphen, and a name collision with the theory Main (the join
     is over doc SESSIONS only, so "main" unambiguously means the Main
     manual, not the HOL theory). */
  spec_test("isar-ref joins to session Isar_Ref", covers = List("doc_list#T1")) {
    assertEquals(entry("isar-ref").source, "Isar_Ref")
  }

  spec_test("logics-ZF joins to session Logics_ZF", covers = List("doc_list#T1")) {
    assertEquals(entry("logics-ZF").source, "Logics_ZF")
  }

  spec_test("main joins to session Main", covers = List("doc_list#T1")) {
    assertEquals(entry("main").source, "Main")
  }

  /* T2 (revised, see plans/doc_list): Doc_Catalog.join is the pure fold
     doing the mapping -- test it directly over synthetic
     (session, variant-names) pairs, no Sessions.Structure involved. */
  spec_test("join maps every variant name to the session",
      covers = List("doc_list#T2")) {
    val m = Doc_Catalog.join(Map.empty, "My_Doc", List("a", "b"))
    assertEquals(m, Map("a" -> "My_Doc", "b" -> "My_Doc"))
  }

  spec_test("join across sessions accumulates into one map",
      covers = List("doc_list#T2")) {
    val m0 = Doc_Catalog.join(Map.empty, "Sess_A", List("x"))
    val m1 = Doc_Catalog.join(m0, "Sess_B", List("y", "z"))
    assertEquals(m1, Map("x" -> "Sess_A", "y" -> "Sess_B", "z" -> "Sess_B"))
  }

  /* T3: plain entries (release notes) -- NEWS is readable directly, not
     via a doc session. */
  spec_test("NEWS is a plain entry, not joined to a session",
      covers = List("doc_list#T3")) {
    assertEquals(entry("NEWS").source, "plain")
  }

  /* T4: filtering is probe-safe -- a real pattern narrows the listing,
     an unmatched one is an EMPTY listing, not an error. */
  spec_test("pattern isar* returns exactly the isar-ref entry",
      covers = List("doc_list#T4")) {
    val text = Doc_Catalog.render(catalog, "isar*")
    assert(text.contains("isar-ref"), "isar-ref should be listed")
    assert(!text.contains("logics-ZF"), "logics-ZF should be filtered out")
    assert(!text.contains("NEWS"), "NEWS should be filtered out")
  }

  spec_test("an unmatched pattern is an empty listing, not an error",
      covers = List("doc_list#T4")) {
    val text = Doc_Catalog.render(catalog, "zzz_no_such_entry_zzz*")
    assert(text.contains("no matching documentation entries"),
      "unmatched pattern should report an empty listing")
  }

  spec_test("empty pattern lists everything", covers = List("doc_list#T4")) {
    val all = Doc_Catalog.render(catalog, "")
    assert(all.contains("isar-ref") && all.contains("NEWS"),
      "empty pattern should list both manuals and plain entries")
  }
}


/* The expensive catalog fixture is measured once and shared. Correctness
   tests do not inherit a hidden performance assertion from MUnit's safety
   timeout; the dedicated performance suite below owns the explicit budget. */

object MCP_Doc_Read_Fixture {
  final case class Loaded(
    isar_ref_files: List[Path],
    isar_ref_toc: List[Doc_Catalog.Heading],
    news_path: Path)

  lazy val loaded: Loaded = {
    val structure =
      Sessions.load_structure(MCP_Test_Config.options, dirs = MCP_Test_Config.session_dirs)
    val deps = Sessions.deps(structure, progress = MCP_Test_Config.progress)
    val files = deps("Isar_Ref").proper_session_theories.map(_.path)
    val toc = Doc_Catalog.toc(files)
    val news =
      Doc_Catalog.make(structure).flatMap(_.entries).find(_.name == "NEWS")
        .getOrElse(error("no NEWS entry in the catalog")).path
    Loaded(files, toc, news)
  }
}


class MCP_Doc_Read_Performance_Tests extends MCP_Suite {
  override def munitTimeout = 10.minutes

  spec_test("documentation catalog construction stays within 30 seconds",
      covers = List("planning_gate#T7", "planning_gate#T8"),
      test_class = MCP_Spec_Metadata.Performance) {
    /* MCP_Session receives Structure and Deps from backend startup before it
       constructs Doc_Catalog.  This budget measures the named product path,
       not unrelated dependency-graph construction. */
    val structure =
      Sessions.load_structure(MCP_Test_Config.options, dirs = MCP_Test_Config.session_dirs)
    val started = Time.now()
    Doc_Catalog.make(structure)
    val elapsed = Time.now() - started
    val budget = Time.seconds(30)
    assert(elapsed <= budget,
      "documentation catalog performance budget exceeded: " +
        elapsed.message + " > " + budget.message)
  }
}


/* plans/doc_read: heading scan / toc / section slicing / plain-entry
   windowing, all pure functions of file paths -- run against the REAL
   Isar_Ref chapter sources. These are functional assertions only. */

class MCP_Doc_Read_Tests extends MCP_Suite {
  override def munitTimeout = 10.minutes

  private def isar_ref_files: List[Path] = MCP_Doc_Read_Fixture.loaded.isar_ref_files
  private def isar_ref_toc: List[Doc_Catalog.Heading] = MCP_Doc_Read_Fixture.loaded.isar_ref_toc
  private def news_path: Path = MCP_Doc_Read_Fixture.loaded.news_path

  /* T1: toc claim -- headings from ALL chapter files, both chapter and
     section levels present, every row carrying file + line. */
  spec_test("Isar_Ref toc has more than 40 rows spanning multiple files",
      covers = List("doc_read#T1", "planning_gate#T7")) {
    assert(isar_ref_toc.length > 40,
      "expected > 40 headings in Isar_Ref, got " + isar_ref_toc.length)
    assert(isar_ref_toc.map(_.file).distinct.length > 1,
      "expected headings from more than one chapter file")
  }

  spec_test("toc includes both chapter and section levels, all with a line",
      covers = List("doc_read#T1")) {
    assert(isar_ref_toc.exists(_.level == 0), "expected at least one chapter heading")
    assert(isar_ref_toc.exists(_.level == 1), "expected at least one section heading")
    assert(isar_ref_toc.forall(_.line > 0), "every heading should carry a positive line")
  }

  /* T2: a pinned section (Spec.thy's "Defining theories \label{sec:begin-
     thy}", spanning up to the next section "Local theory targets") --
     text contains a phrase from its body and stops before the next
     section's title. */
  spec_test("section extraction stops at the next same-level heading",
      covers = List("doc_read#T2")) {
    Doc_Catalog.find_section(isar_ref_toc, "Defining theories") match {
      case Doc_Catalog.Unique(heading) =>
        val in_file = isar_ref_toc.filter(_.file == heading.file)
        val text = Doc_Catalog.section_text(in_file, heading)
        assert(text.contains("definition--statement--proof elements"),
          "section text should contain a phrase from Spec.thy's body: " + text.take(200))
        assert(!text.contains("Local theory targets"),
          "section text should stop before the next section's title")
      case other => fail("expected a unique match for \"Defining theories\", got " + other)
    }
  }

  /* T3: it is a search, not a compile -- ambiguous/unknown queries never
     guess. "proof" matches many section titles across Isar_Ref. */
  spec_test("an ambiguous section query returns candidates, not text",
      covers = List("doc_read#T3")) {
    Doc_Catalog.find_section(isar_ref_toc, "proof") match {
      case Doc_Catalog.Ambiguous(candidates) => assert(candidates.length > 1)
      case other => fail("expected Ambiguous for \"proof\", got " + other)
    }
  }

  spec_test("an unknown section query is No_Match, not an error",
      covers = List("doc_read#T3")) {
    assertEquals(
      Doc_Catalog.find_section(isar_ref_toc, "zzz_no_such_section_zzz"), Doc_Catalog.No_Match)
  }

  /* T4: plain entries -- lines windows exactly; section is an argument
     error surfaced by MCP_Session.doc_read (Fake_Backend has no real
     plain file, so this exercises Doc_Catalog.plain_read directly against
     NEWS). */
  spec_test("plain_read with an explicit lines window returns exactly that window",
      covers = List("doc_read#T4")) {
    val Right(text) = Doc_Catalog.plain_read(news_path, "1-5"): @unchecked
    assertEquals(split_lines(text).length, 5)
  }

  spec_test("plain_read rejects a malformed lines range",
      covers = List("doc_read#T4")) {
    assert(Doc_Catalog.plain_read(news_path, "not-a-range").isLeft)
  }

  /* T5: truncation -- a chapter-level section (the toplevel chapter
     heading itself, spanning the whole file) truncates at the window with
     the "narrow" note. */
  spec_test("a chapter-sized section read truncates with a narrow-the-section note",
      covers = List("doc_read#T5")) {
    val chapter = isar_ref_toc.find(_.level == 0).getOrElse(fail("no chapter heading found"))
    val in_file = isar_ref_toc.filter(_.file == chapter.file)
    val text = Doc_Catalog.section_text(in_file, chapter)
    assert(text.contains("truncated") && text.contains("narrow the section"),
      "a whole-chapter read should exceed the window and truncate: " + text.takeRight(200))
  }

  /* T6 (D1a canary): every heading-command occurrence in the bundled
     Isar_Ref sources is found by the line-anchored scanner -- guards
     against a future distribution reformatting headings onto multiple
     physical lines, which the scanner would silently miss. The reference
     count is a plain line-start check, independent of the scanner's own
     cartouche-matching regex. */
  spec_test("scanner heading count matches a raw line-start count",
      covers = List("doc_read#T6")) {
    val command = """^(chapter|section|subsection|subsubsection)\b""".r
    val raw_count =
      isar_ref_files.map(f => split_lines(File.read(f)).count(l => command.findFirstIn(l).isDefined)).sum
    assertEquals(isar_ref_toc.length, raw_count)
  }
}


/* plans/session_dirs_errors: MCP_Config.check/render over temp-dir ROOT
   fixtures -- pure scala, no prover, no MCP_Session. A1/A2/A3 in the
   plan are the empirical claims this suite locks down as regression
   tests: forcing Root_File.entries on a colliding -d set never enters
   the load_structure fold that throws (T1), Position carries file+line
   per entry (exercised throughout via Site.location), and a -d root
   colliding with an already-registered component is distinguishable
   from a -d-vs--d collision (the plan's T3, using "HOL" -- always
   present in the baseline, unlike an AFP session name, so the test does
   not depend on AFP being registered in the environment it runs in). */

/* Only two cases below cite a plan id. The suite grew its own local T
   numbering, which drifted from plans/session_dirs_errors: the plan's T2
   is "Position carries file+line", not the clean-set case, and the
   Root_Error case is the plan's T3b -- a label gen_assumptions.py cannot
   register (its LABEL regex wants whitespace after the digits, so the "b"
   rejects the line). Citing them would name assumptions they do not check,
   so they stay unlabelled until the plan and the suite are reconciled. */

class MCP_Config_Tests extends MCP_Suite {
  private def root(dir: Path, sessions: (String, String)*): Unit = {
    val text =
      sessions.map { case (name, theory) =>
        "session " + quote(name) + " = HOL +\n" +
        "  theories\n" +
        "    " + theory + "\n"
      }.mkString("\n")
    File.write(dir + Path.basic("ROOT"), text)
  }

  private def with_two_projects(body: (Path, Path, Path) => Unit): Unit =
    Isabelle_System.with_tmp_dir("session_dirs_errors") { base =>
      val alpha = base + Path.basic("proj_alpha")
      val beta = base + Path.basic("proj_beta")
      Isabelle_System.make_directory(alpha)
      Isabelle_System.make_directory(beta)
      root(alpha, "Alpha" -> "Alpha_Defs", "Scratch" -> "Alpha_Scratch")
      root(beta, "Beta" -> "Beta_Defs", "Scratch" -> "Beta_Scratch")
      body(base, alpha, beta)
    }

  /* T1 / A1: two colliding -d roots. check() must not throw (unlike
     Sessions.load_structure over the same dirs) and must report exactly
     one Collision naming "Scratch", both ROOT paths, and both lines. */
  spec_test("two colliding -d roots produce a Collision, and check() does not throw",
      verifies = List("session_dirs_errors#A1"), covers = List("session_dirs_errors#T1")) {
    with_two_projects { (_, alpha, beta) =>
      val issues = MCP_Config.check(List(alpha, beta))
      val collisions = issues.collect { case c: MCP_Config.Collision => c }
      assertEquals(collisions.length, 1, "expected exactly one Collision, got: " + issues)
      val c = collisions.head
      assertEquals(c.name, "Scratch")
      assertEquals(c.sites.length, 2)
      val roots = c.sites.map(_.root)
      assert(roots.contains(alpha + Path.basic("ROOT")), "missing proj_alpha/ROOT: " + roots)
      assert(roots.contains(beta + Path.basic("ROOT")), "missing proj_beta/ROOT: " + roots)
      assert(c.sites.forall(s => Position.Line.get(s.pos) > 0),
        "every colliding site should carry a line number: " + c.sites)
    }
  }

  /* a clean -d set (no collisions, no bad dirs) reports nothing. */
  test("a clean -d set produces an empty issue list") {
    Isabelle_System.with_tmp_dir("session_dirs_errors") { base =>
      val alpha = base + Path.basic("proj_alpha")
      Isabelle_System.make_directory(alpha)
      root(alpha, "Alpha" -> "Alpha_Defs")
      assertEquals(MCP_Config.check(List(alpha)), Nil)
    }
  }

  /* a nonexistent -d dir is a Bad_Dir; two bad dirs produce two
     issues (the report-everything requirement -- check_session_dir
     itself throws on the first, so this is specifically what the
     pre-flight buys). */
  test("a nonexistent -d dir produces a Bad_Dir") {
    Isabelle_System.with_tmp_dir("session_dirs_errors") { base =>
      val missing = base + Path.basic("does_not_exist")
      val issues = MCP_Config.check(List(missing))
      assertEquals(issues, List(MCP_Config.Bad_Dir(missing, "missing \"ROOT\" or \"ROOTS\"")))
      // render() must cite the EXPANDED path (dir.expand.toString, not
      // implode -- see "deviations from the plan" in
      // plans/session_dirs_errors), matching Sessions.check_session_dir's
      // own wording for this exact situation.
      val text = MCP_Config.render(issues)
      assert(text.contains(missing.expand.toString),
        "render() should cite the expanded bad-dir path: " + text)
      assert(text.contains("missing \"ROOT\" or \"ROOTS\""), "render(): " + text)
    }
  }

  /* Root_Error: a ROOT file that exists (so it is not a Bad_Dir) but does
     not parse. Exercises the OTHER Exn.capture layer in check() -- forcing
     Root_File.entries, not Sessions.load_root_files itself -- and the
     Bad_Dir/Root_Error detail path through MCP_Server.decode_message the
     Exn.message-decode prerequisite exists for. */
  test("a ROOT file with a syntax error produces a Root_Error") {
    Isabelle_System.with_tmp_dir("session_dirs_errors") { base =>
      val bad = base + Path.basic("proj_bad")
      Isabelle_System.make_directory(bad)
      File.write(bad + Path.basic("ROOT"), "this is not valid ROOT syntax {{{\n")
      val issues = MCP_Config.check(List(bad))
      val errors = issues.collect { case e: MCP_Config.Root_Error => e }
      assertEquals(errors.length, 1, "expected exactly one Root_Error, got: " + issues)
      assertEquals(errors.head.root, bad + Path.basic("ROOT"))
      assert(errors.head.detail.nonEmpty, "Root_Error detail should not be empty")
      val text = MCP_Config.render(issues)
      assert(text.contains((bad + Path.basic("ROOT")).implode),
        "render() should cite the offending ROOT path: " + text)
    }
  }

  test("two bad -d dirs produce two issues, not just the first") {
    Isabelle_System.with_tmp_dir("session_dirs_errors") { base =>
      val missing1 = base + Path.basic("does_not_exist_1")
      val missing2 = base + Path.basic("does_not_exist_2")
      val issues = MCP_Config.check(List(missing1, missing2))
      assertEquals(issues.length, 2, "expected two Bad_Dir issues, got: " + issues)
      assert(issues.forall(_.isInstanceOf[MCP_Config.Bad_Dir]))
    }
  }

  /* T4 / A3: a -d root colliding with an ALREADY-REGISTERED component
     session ("HOL", always in the baseline -- see the class comment) is
     classified From_Components, not From_Dir, and render() tells the
     user to rename THEIRS, never mentioning dropping the component. */
  spec_test("a -d session colliding with a component is classified From_Components",
      verifies = List("session_dirs_errors#A3"), covers = List("session_dirs_errors#T3")) {
    Isabelle_System.with_tmp_dir("session_dirs_errors") { base =>
      val mine = base + Path.basic("myproj")
      Isabelle_System.make_directory(mine)
      root(mine, "HOL" -> "My_HOL_Clash")
      val issues = MCP_Config.check(List(mine))
      val collisions = issues.collect { case c: MCP_Config.Collision => c }
      assertEquals(collisions.length, 1, "expected exactly one Collision, got: " + issues)
      val c = collisions.head
      assertEquals(c.name, "HOL")
      val provenances = c.sites.map(_.provenance).toSet
      assert(provenances.contains(MCP_Config.From_Components),
        "expected a From_Components site: " + c.sites)
      assert(provenances.exists { case MCP_Config.From_Dir(d) => d == mine; case _ => false },
        "expected a From_Dir(mine) site: " + c.sites)

      val text = MCP_Config.render(issues)
      assert(text.contains("rename your session"),
        "component collision should tell the user to rename theirs: " + text)
      assert(!text.contains("drop one -d"),
        "component collision is not a drop-either-one case: " + text)
    }
  }

  /* T5: render() output is non-empty, multi-line, and names both the
     session and both colliding file paths -- the "actionable" bar the
     plan sets. */
  test("render() is non-empty, multi-line, and names the session and both paths") {
    with_two_projects { (_, alpha, beta) =>
      val issues = MCP_Config.check(List(alpha, beta))
      val text = MCP_Config.render(issues)
      assert(text.nonEmpty, "render() of a nonempty issue list should not be empty")
      assert(text.contains("\n"), "render() should be multi-line: " + text)
      assert(text.contains("Scratch"), "render() should name the colliding session: " + text)
      assert(text.contains((alpha + Path.basic("ROOT")).implode),
        "render() should cite proj_alpha/ROOT: " + text)
      assert(text.contains((beta + Path.basic("ROOT")).implode),
        "render() should cite proj_beta/ROOT: " + text)
    }
  }

  test("render() of an empty issue list is empty") {
    assertEquals(MCP_Config.render(Nil), "")
  }

  /* the default launch (no -d at all) must short-circuit before ever
     forcing the baseline -- there is nothing of the caller's for check()
     to report, and the baseline force is the expensive part (COST TRAP,
     plans/session_dirs_errors: ~760ms with AFP registered). */
  test("check() of an empty -d list is empty without forcing the baseline") {
    assertEquals(MCP_Config.check(Nil), Nil)
  }

  /* regression guard for the "baseline-only collision" fix: a Collision
     is only reported when at least one site is From_Dir. Two baseline/
     component roots colliding with each other (not exercised here --
     this environment's baseline has none) must never be attributed to a
     -d the caller didn't even pass; this locks the SHAPE of that
     guard down against a clean single -d, where by construction every
     site but HOL/Pure/... is From_Dir or absent. */
  test("a clean -d set never reports a Collision with only From_Components sites") {
    Isabelle_System.with_tmp_dir("session_dirs_errors") { base =>
      val alpha = base + Path.basic("proj_alpha")
      Isabelle_System.make_directory(alpha)
      root(alpha, "Alpha" -> "Alpha_Defs")
      val collisions = MCP_Config.check(List(alpha)).collect { case c: MCP_Config.Collision => c }
      assert(collisions.forall(_.sites.exists(_.provenance != MCP_Config.From_Components)),
        "every reported Collision must have at least one From_Dir site: " + collisions)
    }
  }
}
