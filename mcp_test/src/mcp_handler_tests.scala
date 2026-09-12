/*  Title:      mcp_test/src/mcp_handler_tests.scala

Unit suites over Fake_Backend -- fast, no prover: the JSON-RPC
protocol surface, the tool surface (builtin table rows and their
dispatch through backend handlers), and the pure codecs.
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
    assertEquals(JSON.value(get(reply, "result", "capabilities"), "resources"), None)
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
    val reply = call_tool_on(handler, "list_sessions", JSON.Object())
    assert(JSON.value(reply, "id").isDefined, "reply must echo the request id")
    val text = assert_is_error(reply)
    assert(text.contains("building MCP-HOL"),
      "expected the progress string in the not-ready text: " + text)
  }

  spec_test("Failed is reported distinctly from Not_Ready, carrying the failure message",
      verifies = List("readiness#A4"), covers = List("readiness#T4")) {
    val handler = new MCP_Server.Handler(() => MCP_Server.Failed("boom"))
    val text = assert_is_error(call_tool_on(handler, "list_sessions", JSON.Object()))
    assert(text.contains("failed"), "expected \"failed\" in the failed-state text: " + text)
    assert(text.contains("boom"), "expected the failure message: " + text)
  }

  spec_test("Handler holds no cached backend -- a readiness transition is observed immediately",
      verifies = List("readiness#A5"), covers = List("readiness#T5")) {
    var state: MCP_Server.Readiness = MCP_Server.Not_Ready("building")
    val handler = new MCP_Server.Handler(() => state)
    assert_is_error(call_tool_on(handler, "list_sessions", JSON.Object()))
    state = MCP_Server.Ready(new Fake_Backend)
    assert_no_error(call_tool_on(handler, "list_sessions", JSON.Object()))
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
    val text = assert_is_error(call_tool_on(handler, "list_sessions", JSON.Object()))
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
      var forwarded = List.empty[(String, McpApplication.Cancellation)]

      private def record(name: String, cancellation: McpApplication.Cancellation): Unit =
        forwarded = forwarded :+ (name -> cancellation)

      override def direct_cancellable[A](
          cancellation: McpApplication.Cancellation)(body: => A): A = {
        record("direct", cancellation)
        body
      }

      override def root_context_cancellable(cancellation: McpApplication.Cancellation): MCP_Session.Result = {
        record("root", cancellation)
        super.root_context()
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

    }

    val cancellation = new McpApplication.Cancellation {
      def isCancelled: Boolean = false
      def onCancel(callback: () => Unit): Unit = ()
    }
    val backend = new Cancellable_Backend
    val application = McpApplication.isabelle(
      () => McpApplication.Ready(backend), "TEST", Nil, "MCP_Tools")
    application.execute(
      McpApplication.Operation.ToolsCall("shout", JSON.Object("input" -> "hello")),
      cancellation)
    application.execute(
      McpApplication.Operation.ToolsCall("list_sessions", JSON.Object()), cancellation)
    application.execute(McpApplication.Operation.ToolsList, cancellation)
    assert(backend.mlCancellation.exists(_ eq cancellation))
    assertEquals(
      backend.forwarded.map(_._1).toSet,
      Set("root", "tools", "run", "direct"))
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

  test("retired resource methods are unsupported") {
    for (method <- List("resources/list", "resources/templates/list", "resources/read")) {
      assertEquals(get(rpc(method, JSON.Object("uri" -> "isabelle://session")), "error", "code"),
        MCP_Server.RPC.METHOD_NOT_FOUND)
    }
  }

  test("builtin catalogue contains exactly the retained seven tools") {
    assertEquals(MCP_Server.builtins.map(_.name).toSet,
      Set("load_theory", "unload_theory", "list_sessions",
        "list_theories", "search_sources", "doc_list", "doc_read"))
  }

  spec_test("the same Handler obtains a fresh root for every list and default ML call",
      covers = List("context_locator#T5")) {
    class RecordingRoot extends Fake_Backend {
      var root = "isabelle://context/theory/First"
      var listed = List.empty[String]
      var executed = List.empty[String]
      override def root_context(): MCP_Session.Result = MCP_Session.Ok(root)
      override def ml_tools(context: String): MCP_Session.Tools_Reply = {
        listed = listed :+ context
        super.ml_tools(context)
      }
      override def ml_run(name: String, args: List[(String, String)], context: String): MCP_Session.Result = {
        executed = executed :+ context
        super.ml_run(name, args, context)
      }
    }
    val backend = new RecordingRoot
    val handler = new MCP_Server.Handler(backend)
    rpc_on(handler, "tools/list")
    assert_no_error(call_tool_on(handler, "shout", JSON.Object("input" -> "first")))
    backend.root = "isabelle://context/theory/Second"
    rpc_on(handler, "tools/list")
    assert_no_error(call_tool_on(handler, "shout", JSON.Object("input" -> "second")))
    assertEquals(backend.listed, List(
      "isabelle://context/theory/First", "isabelle://context/theory/First",
      "isabelle://context/theory/Second", "isabelle://context/theory/Second"))
    assertEquals(backend.executed,
      List("isabelle://context/theory/First", "isabelle://context/theory/Second"))
  }

  test("builtin names match the mcp tool-name regex") {
    for (tool <- MCP_Server.builtins) {
      assert(tool.name.matches("^[a-zA-Z0-9_-]{1,64}$"),
        "builtin name violates the tool-name regex: " + tool.name)
    }
  }

  test("tools/list includes load_theory/unload_theory, and no check_theory") {
    val tools = get_list(rpc("tools/list"), "result", "tools")
    val names = tools.map(t => get_string(t, "name")).toSet
    assert(names.contains("load_theory"), "missing load_theory")
    assert(names.contains("unload_theory"), "missing unload_theory")
    assert(!names.contains("check_theory"),
      "check_theory should be retired -- it was identical to load_theory")
    assertEquals(required_args(tool_row("load_theory")), List("name"))
  }

  test("tools/call load_theory reaches backend.load_theory") {
    val backend = new Fake_Backend
    val reply = call_tool("load_theory",
      JSON.Object("name" -> "Draft.Foo", "master_dir" -> "/tmp"), backend)
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

  test("tools/list includes list_sessions with readOnlyHint and idempotentHint") {
    val row = tool_row("list_sessions")
    assertEquals(required_args(row), List())
    assertEquals(annotation(row, "readOnlyHint"), true)
    assertEquals(annotation(row, "idempotentHint"), true)
    assertEquals(annotation(row, "openWorldHint"), false)
  }

  test("tools/call list_sessions reaches backend.list_sessions_info") {
    val backend = new Fake_Backend
    val reply = call_tool("list_sessions", JSON.Object(), backend)
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

  test("tools/call list_theories reaches backend.list_theories_info") {
    val backend = new Fake_Backend
    val reply = call_tool("list_theories", JSON.Object("session" -> "HOL"), backend)
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

  test("tools/call search_sources reaches backend.search_sources") {
    val backend = new Fake_Backend
    val reply = call_tool("search_sources", JSON.Object("pattern" -> "Main"), backend)
    assert_no_error(reply)
    val content = get_list(reply, "result", "content")
    assert(content.nonEmpty, "result content should not be empty")
    val text = get_string(content.head, "text")
    assert(text.contains("Main") || text.nonEmpty,
      "search_sources should return results or be non-empty")
  }


  test("tools/list includes doc_list with an optional pattern parameter") {
    val row = tool_row("doc_list")
    assertEquals(required_args(row), List())
    assertEquals(property_type(row, "pattern"), "string")
    assertEquals(annotation(row, "readOnlyHint"), true)
    assertEquals(annotation(row, "idempotentHint"), true)
    assertEquals(annotation(row, "openWorldHint"), false)
  }

  test("tools/call doc_list reaches backend.doc_list") {
    val backend = new Fake_Backend
    val reply = call_tool("doc_list", JSON.Object(), backend)
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

  test("tools/call doc_read reaches backend.doc_read") {
    val backend = new Fake_Backend
    val reply = call_tool("doc_read", JSON.Object("name" -> "isar-ref"), backend)
    assert_no_error(reply)
  }

  test("tools/list: a colliding ML tool does not shadow the list_sessions builtin") {
    val backend = new Fake_Backend
    backend.extra_ml_tools =
      List(MCP_Session.Tool_Row("list_sessions", "some unrelated ml tool", "string_fun", Nil,
        MCP_Session.Tool_Annotations.default))
    val tools = get_list(rpc("tools/list", backend = backend), "result", "tools")
    val matches = tools.filter(t => get_string(t, "name") == "list_sessions")
    assertEquals(matches.length, 1, "expected exactly one list_sessions entry")
    assertEquals(get_string(matches.head, "description"),
      MCP_Server.list_sessions_tool.description,
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
    backend.builtin_activation = List("list_sessions" -> false)
    val names = get_list(rpc("tools/list", backend = backend), "result", "tools")
      .map(get_string(_, "name")).toSet
    assert(!names("list_sessions"), "list_sessions should be hidden")
    assert(names("list_theories"), "list_theories should still be listed (only list_sessions was del'd)")
  }

  test("tools/list: a builtin marked (name, true) is listed (no different from absent)") {
    val backend = new Fake_Backend
    backend.builtin_activation = List("list_sessions" -> true)
    val names = get_list(rpc("tools/list", backend = backend), "result", "tools")
      .map(get_string(_, "name")).toSet
    assert(names("list_sessions"), "list_sessions should be listed")
  }

  /* ASYMMETRIC CALLABILITY (A5): a hidden builtin dispatches exactly
     like a listed one -- tools/call precedes activation entirely, so
     the merge filter (tools/list only) never touches it. */
  test("tools/call: a builtin hidden via (name, false) is still callable") {
    val backend = new Fake_Backend
    backend.builtin_activation = List("list_sessions" -> false)
    val reply = call_tool("list_sessions", JSON.Object(), backend)
    assert_no_error(reply)
  }


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
    assertEquals(MCP_Server.exposure(List("Some_Thy.list_sessions"), Set("list_sessions")),
      Map("Some_Thy.list_sessions" -> "Some_Thy__list_sessions"))
    /* an unqualified internal name that IS the builtin name has no
       fallback spelling left: dropped */
    assertEquals(MCP_Server.exposure(List("list_sessions"), Set("list_sessions")), Map.empty)
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

  spec_test("ML tools advertise optional context while Scala builtins retain their own schemas",
      covers = List("context_locator#T4")) {
    val row = tool_row("shout")
    assertEquals(property_type(row, "context"), "string")
    assertEquals(required_args(row), List("input"))
    for (tool <- MCP_Server.builtins) {
      val properties = JSON.value(tool.input_schema, "properties").getOrElse(JSON.Object())
      assertEquals(JSON.value(properties, "context"), None)
    }
  }

  spec_test("a context URL travels unchanged as a named argument while root selects the catalogue",
      covers = List("context_locator#T4", "context_locator#T5")) {
    class RecordingContext extends Fake_Backend {
      var seen = Option.empty[(String, List[(String, String)], String)]
      override def ml_run(name: String, args: List[(String, String)], context: String): MCP_Session.Result = {
        seen = Some((name, args, context))
        super.ml_run(name, args, context)
      }
    }
    val backend = new RecordingContext
    val target = "sorry://some/theory/1"
    assert_no_error(call_tool("shout", JSON.Object("input" -> "hello", "context" -> target), backend))
    assertEquals(backend.seen,
      Some(("MCP_Tools.shout", List("input" -> "hello", "context" -> target), backend.fake_root_context)))
  }

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

  test("tools/list gives a zero-param ML tool only the optional framework context") {
    val backend = new Fake_Backend
    backend.extra_ml_tools =
      List(MCP_Session.Tool_Row("Thy_A.no_args", "takes nothing", "string_fun", Nil,
        MCP_Session.Tool_Annotations.default))
    val row = tool_row("no_args", backend)
    assertEquals(property_type(row, "context"), "string")
    assertEquals(required_args(row), Nil)
    assertEquals(get(row, "inputSchema", "properties").asInstanceOf[JSON.Object.T].keySet, Set("context"))
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

  test("initialize declares only tools listChanged") {
    val reply = rpc("initialize")
    assertEquals(get(reply, "result", "capabilities", "tools", "listChanged"), true)
    assertEquals(JSON.value(get(reply, "result", "capabilities"), "resources"), None)
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


class MCP_Codec_Tests extends MCP_Suite {
  test("json_args: a json array becomes repeated pairs in array order") {
    val args =
      MCP_Server.json_args(
        JSON.Object("label" -> "T", "theories" -> List("HOL.Main", "HOL-Library.Multiset")))
    assertEquals(args,
      List("label" -> "T", "theories" -> "HOL.Main", "theories" -> "HOL-Library.Multiset"))
  }

  test("json_args: a json integer becomes a bare (unquoted) string pair") {
    val args = MCP_Server.json_args(JSON.Object("label" -> "T", "index" -> -1))
    assertEquals(args, List("label" -> "T", "index" -> "-1"))
  }

}

class MCP_Symbol_Tests extends MCP_Suite {
  private val echo_backend = new Fake_Backend {
    override def ml_run(name: String, args: List[(String, String)], context: String): MCP_Session.Result =
      MCP_Session.Ok(args.collectFirst { case ("input", value) => value }.getOrElse(""))
  }

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


  test("text_result leaves already-unicode text alone") {
    assertEquals(
      get_string(get_list(MCP_Server.text_result(unicode), "content").head, "text"),
      unicode)
  }


  test("tools/call: symbol notation coming back from the prover reaches the client as unicode") {
    val reply = call_tool("shout", JSON.Object("input" -> notation), echo_backend)
    assert_no_error(reply)
    assertEquals(result_text(reply), unicode)
  }

  test("tools/call: unicode sent by the model survives the round trip as unicode") {
    val reply = call_tool("shout", JSON.Object("input" -> unicode), echo_backend)
    assert_no_error(reply)
    assertEquals(result_text(reply), unicode)
  }
}


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

  /* T5: windowing -- a chapter-level section (the toplevel chapter
     heading itself, spanning the whole file) is windowed at the default
     limit (Window, mcp/src/utils.scala) with a "narrow the section" note
     alongside the offset continuation hint. */
  spec_test("a chapter-sized section read windows with a narrow-the-section note",
      covers = List("doc_read#T5")) {
    val chapter = isar_ref_toc.find(_.level == 0).getOrElse(fail("no chapter heading found"))
    val in_file = isar_ref_toc.filter(_.file == chapter.file)
    val text = Doc_Catalog.section_text(in_file, chapter)
    assert(text.contains("showing") && text.contains("narrow the section"),
      "a whole-chapter read should exceed the window and get windowed: " + text.takeRight(200))
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
