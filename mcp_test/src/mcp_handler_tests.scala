/*  Title:      mcp_test/src/mcp_handler_tests.scala

Unit suites over Fake_Backend -- fast, no prover: the JSON-RPC
protocol surface, the tool surface (builtin table rows and their
dispatch onto backend.ir), the resource surface, and the pure codecs.
*/

package isabelle.mcp

import isabelle._

import java.io.{BufferedReader, ByteArrayOutputStream, PrintStream, StringReader}
import java.nio.charset.StandardCharsets


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

  test("serve replies per line, skips blanks, stops the backend on EOF") {
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
    assert(backend.stopped, "backend not stopped on EOF")
    val lines = split_lines(out_stream.toString(StandardCharsets.UTF_8)).filter(_.nonEmpty)
    assertEquals(lines.length, 3, "expected 3 reply lines")
    assert(lines.forall(l => JSON.Format.unapply(l).isDefined), "reply line not valid json")
    assert(lines(2).contains("HI"), "tool result missing from last reply")
  }
}


/* tools: ML-registry tools, the builtin table rows, and their dispatch */

class MCP_Tools_Tests extends MCP_Suite {
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

  test("tools/list includes repl_show with readOnlyHint and its {repl} schema") {
    val row = tool_row("repl_show")
    assertEquals(required_args(row), List("repl"))
    assertEquals(annotation(row, "readOnlyHint"), true)
  }

  test("tools/call repl_show reaches backend.ir with (\"show\", [(\"repl\", ...)])") {
    assert_dispatch("repl_show", JSON.Object("repl" -> "T"), "show", List("repl" -> "T"))
  }

  test("tools/list includes repl_text with readOnlyHint and its {repl} schema") {
    val row = tool_row("repl_text")
    assertEquals(required_args(row), List("repl"))
    assertEquals(annotation(row, "readOnlyHint"), true)
  }

  test("tools/call repl_text reaches backend.ir with (\"text\", [(\"repl\", ...)])") {
    assert_dispatch("repl_text", JSON.Object("repl" -> "T"), "text", List("repl" -> "T"))
  }

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

  test("tools/list includes repl_back with destructiveHint true and its {repl} schema") {
    val row = tool_row("repl_back")
    assertEquals(required_args(row), List("repl"))
    assertEquals(annotation(row, "destructiveHint"), true)
  }

  test("tools/call repl_back reaches backend.ir with (\"back\", [(\"repl\", ...)])") {
    assert_dispatch("repl_back", JSON.Object("repl" -> "T"), "back", List("repl" -> "T"))
  }

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

  test("tools/list includes find_theorems with an optional max_results property (not in required)") {
    val row = tool_row("find_theorems")
    assertEquals(required_args(row), List("repl", "query"))
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

  test("tools/list: a colliding ML tool does not shadow the repl_list builtin") {
    val backend = new Fake_Backend
    backend.extra_ml_tools =
      List(MCP_Session.Tool_Row("repl_list", "some unrelated ml tool", "string_fun", Nil))
    val tools = get_list(rpc("tools/list", backend = backend), "result", "tools")
    val matches = tools.filter(t => get_string(t, "name") == "repl_list")
    assertEquals(matches.length, 1, "expected exactly one repl_list entry")
    assertEquals(get_string(matches.head, "description"),
      MCP_Server.repl_list_tool.description,
      "colliding ml tool shadowed the builtin description")
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
      List(MCP_Session.Tool_Row("Thy_A.shout", "clashes with the demo tool", "string_fun", Nil))
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
        MCP_Session.Tool_Param("criteria", "args", true, None, "search criteria"),
        MCP_Session.Tool_Param("limit", "nat", false, Some("20"), "max results"),
        MCP_Session.Tool_Param("goal", "term", true, None, "a goal"))))
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

  test("tools/call forwards ALL json arguments as named pairs") {
    class Recording_Backend extends Fake_Backend {
      var seen: Option[(String, List[(String, String)])] = None
      override def ml_run(name: String, args: List[(String, String)],
          designation: String): MCP_Session.Result = {
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

  test("serve wires the changed handler to list_changed notifications") {
    val backend = new Fake_Backend
    val out_stream = new ByteArrayOutputStream
    val out = new PrintStream(out_stream, true, StandardCharsets.UTF_8)
    /* fire the backend event mid-session: EOF right after */
    val input = JSON.Format(request(Some(1), "ping", None))
    MCP_Server.serve(backend, new BufferedReader(new StringReader(input)), out)
    backend.changed_handler("tools")
    val lines = split_lines(out_stream.toString(StandardCharsets.UTF_8)).filter(_.nonEmpty)
    assert(lines.exists(_.contains("notifications/tools/list_changed")),
      "missing list_changed notification: " + lines.toString)
  }
}


/* resources: list, read, templates */

class MCP_Resources_Tests extends MCP_Suite {
  test("resources/list reports the backend resources") {
    val resources = get_list(rpc("resources/list"), "result", "resources")
    assertEquals(resources.length, 2, "expected isabelle://session + isabelle://named/greeting")
    assertEquals(get_string(resources.head, "uri"), "isabelle://session")
    assertEquals(get_string(resources.head, "name"), "session")
    assertEquals(get_string(resources(1), "uri"), "isabelle://named/greeting")
    assertEquals(get_string(resources(1), "name"), "greeting")
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

  test("MCP.ir args: repeated keys, newlines and symbols round-trip byte-clean") {
    val args =
      List(
        "repl" -> "T",
        "theories" -> "HOL.Main",
        "theories" -> "HOL-Library.Multiset",
        "isar_text" -> "lemma \"x + y = y + (x::nat)\"\n  by simp",
        "isar_text" -> "have \"A \\<Longrightarrow> A\" ⇒ α")
    val decoded =
      MCP_Session.decode_args(YXML.parse_body(YXML.Source(MCP_Session.encode_args(args))))
    assertEquals(decoded, args)
  }
}


