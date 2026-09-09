/*  Title:      mcp_test/src/mcp_bridge_tests.scala

Integration suites against real headless PIDE sessions (isabelle mcp_test
-L bridge). Cover typed operations over MCP-Tools, IR dispatch over
MCP-HOL/MCP_Repl, boot failures, asynchronous execution, bounded output,
and shutdown.
*/

package isabelle.mcp

import isabelle._
import isabelle.mcp.connection._
import isabelle.mcp.control.ManualDeadlineScheduler
import isabelle.mcp.application.{McpApplication, McpOutputPolicy}
import isabelle.mcp.protocol.JsonRpc
import isabelle.mcp.transport.{McpInputPolicy, ScriptedDataPlane}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration.{Duration, DurationInt}


class MCP_Boot_Failure_Tests extends MCP_Suite {
  override def munitTimeout: Duration = 10.minutes

  spec_test("failed registry-root loading cleans up before a later boot",
      covers = List("pide_bridge#T10")) {
    val options = MCP_Test_Config.options
    val sessionDirs = MCP_Test_Config.session_dirs
    MCP_Session.build(options, "MCP-Tools", sessionDirs, MCP_Test_Config.progress)

    val failure = intercept[Throwable] {
      MCP_Session.boot(options, "MCP-Tools", sessionDirs,
        "MCP_Missing_Registry_Root_For_Boot_Test", McpBridgeProfile.base,
        MCP_Test_Config.progress)
    }
    assert(Exn.message(failure).contains("MCP_Missing_Registry_Root_For_Boot_Test"),
      Exn.message(failure))

    val recovered = MCP_Session.boot(options, "MCP-Tools", sessionDirs,
      "MCP_Tools", McpBridgeProfile.base, MCP_Test_Config.progress)
    try assertEquals(recovered.bridge_operation_names, McpBridgeOperations.baseOperationNames)
    finally recovered.stop()
  }
}


/* Common protocol bridge: typed tool and resource operations over MCP-Tools. */

class MCP_Bridge_Tests extends MCP_Session_Suite(
  "MCP-Tools", "MCP_Tools", McpBridgeProfile.base) {
  spec_test("startup hello advertises precisely MCP-Tools base bridge operations",
      covers = List("pide_bridge#T10")) {
    assertEquals(session.bridge_operation_names, McpBridgeOperations.baseOperationNames)
  }

  /* the bridge carries FULL INTERNAL names + the form tag; exposed
     (shortened) names exist only in the scala layer above
     (MCP_Server.exposure, unit-tested in mcp_handler_tests.scala) */
  spec_test("bridge layer executes against a live PIDE session",
      covers = List("planning_gate#T8")) {
    val tools = session.ml_tools().rows
    val shout = tools.find(_.name == "MCP_Tools.shout")
      .getOrElse(fail("MCP_Tools.shout not in " + tools.toString))
    assertEquals(shout.description, "uppercase the input")
    assertEquals(shout.form, "string_fun")
    assertEquals(shout.params.map(p => (p.name, p.typ, p.required)),
      List(("input", MCP_Session.Ptyp_String, true)))
  }

  /* A5 (plans/param_schema_v2): the discriminating tag-order check.
     XML.Encode.variant/XML.Decode.variant key on POSITIONAL INDEX, and
     every nullary ptyp scalar encodes to identical bytes -- a
     mis-ordered scala decoder list would silently read e.g. Nat as Int,
     with no exception and no way for a scala-unit test to catch it
     (Fake rows never cross the real encoder). Only a live bridge read
     of a REAL encoded row, asserted constructor-by-constructor, can.
     Also A10: the fixture's own (annotations destructive) clause
     (MCP_Tools.thy) proves a declared bucket other than the default
     arrives over the same live encoder. */
  test("bridge: ptyp_fixture's params decode to the right constructor, tag-order proof " +
      "(A5), and its declared annotations bucket (A10)") {
    val tools = session.ml_tools().rows
    val fixture = tools.find(_.name == "MCP_Tools.ptyp_fixture")
      .getOrElse(fail("MCP_Tools.ptyp_fixture not in " + tools.toString))
    assertEquals(fixture.params.map(p => (p.name, p.typ)),
      List(
        "p_string" -> MCP_Session.Ptyp_String,
        "p_source" -> MCP_Session.Ptyp_Source,
        "p_args" -> MCP_Session.Ptyp_Args,
        "p_nat" -> MCP_Session.Ptyp_Nat,
        "p_int" -> MCP_Session.Ptyp_Int,
        "p_bool" -> MCP_Session.Ptyp_Bool,
        "p_term" -> MCP_Session.Ptyp_Term,
        "p_typ" -> MCP_Session.Ptyp_Typ,
        "p_fact" -> MCP_Session.Ptyp_Fact))
    assertEquals(fixture.annotations,
      MCP_Session.Tool_Annotations(Some(false), Some(false), Some(true), Some(false)))
  }

  test("bridge: ml_run round trip") {
    assertEquals(session.ml_run("MCP_Tools.shout", List("input" -> "isabelle")),
      MCP_Session.Ok("ISABELLE"))
  }

  test("bridge: ml_run unknown tool is an error") {
    expect_error(session.ml_run("no_such_tool", List("input" -> "x")),
      containing = "no_such_tool")
  }

  test("bridge: canonical root context equals an explicitly validated locator") {
    /* Thy_Info keying mixes qualified and unqualified names, so take the
       canonical spelling from the session itself */
    val thy_name =
      session.ml_theories().find(n => Long_Name.base_name(n) == "MCP_Tools")
        .getOrElse(fail("MCP_Tools not in ml_theories"))
    val locator = "isabelle://context/theory/" + thy_name
    assertEquals(session.check_context(locator), MCP_Session.Ok(locator))
    assertEquals(session.ml_tools(locator), session.ml_tools())
  }

  test("bridge: unknown context is a typed error, not an exception") {
    expect_error(
      session.ml_run("MCP_Tools.shout", List("input" -> "x"),
        "isabelle://context/theory/No_Such_Theory"),
      containing = "No_Such_Theory")
  }

  /* AVAILABILITY FLOOR (plans/builtin_activation): context resolution
     failure degrades to the empty pair in the operation payload (both
     sections empty), not the bare "[]" the old flat shape used --
     decoding remains total and tools/list cannot hang. */
  test("bridge: ml_tools on an unknown context does not hang") {
    val tools = session.ml_tools("isabelle://context/theory/No_Such_Theory")
    assertEquals(tools.rows, Nil)
    assertEquals(tools.builtin_activation, Nil)
  }

  /* DRIFT GATE (plans/builtin_activation, A4): the ML mirror name set
     (MCP_Tools.thy's "Builtin tool mirrors" section) must equal the
     scala builtin table's name set (MCP_Server.all_builtin_names), both
     directions -- a builtin added scala-side without a mirror, or a
     mirror outliving a removed builtin, fails this over the live
     bridge (a Fake_Backend cannot catch either half, since it never
     round-trips the real MCP_Tools.thy). */
  test("bridge: builtin mirror name set matches the scala builtin table, both directions") {
    val mirrors = session.ml_tools().builtin_activation.map(_._1).toSet
    assertEquals(mirrors, MCP_Server.all_builtin_names.toSet)
  }

  test("bridge: isabelle://session resource reads the loaded theory") {
    val text = expect_ok(session.mcp_resource_read("isabelle://session"))
    assert(text.contains("MCP_Tools"), "session resource missing theory name: " + text)
  }

  test("bridge: isabelle://session resource lists loaded theories") {
    val text = expect_ok(session.mcp_resource_read("isabelle://session"))
    assert(text.contains("theories:"), "session resource missing theories field: " + text)
    assert(text.contains("MCP_Tools"),
      "session resource theories list missing MCP_Tools: " + text)
  }

  /* isabelle://named/{name}: MCP_Resource's own registry (MCP_Tools.thy),
     mirroring MCP_Tool exactly -- "greeting" is the demo resource
     registered there alongside the "shout" demo tool. */
  test("bridge: ml_named_resources lists greeting under its full internal name") {
    val resources = session.ml_named_resources()
    assert(resources.exists(_._1 == "MCP_Tools.greeting"),
      "MCP_Tools.greeting not in " + resources.toString)
  }

  test("bridge: ml_read_resource round trip") {
    assertEquals(session.ml_read_resource("MCP_Tools.greeting"),
      MCP_Session.Ok("hello from MCP_Resource"))
  }

  test("bridge: ml_read_resource unknown name is an error") {
    expect_error(session.ml_read_resource("no_such_resource"), containing = "no_such_resource")
  }

  test("bridge: mcp_resources lists isabelle://named/greeting alongside isabelle://session") {
    val resources = session.mcp_resources()
    assert(resources.exists(_._1 == "isabelle://session"), "missing isabelle://session")
    assert(resources.exists(_._1 == "isabelle://named/greeting"),
      "missing isabelle://named/greeting: " + resources.toString)
  }

  test("bridge: isabelle://named/{name} resources/read dispatches to MCP_Resource") {
    val text = expect_ok(session.mcp_resource_read("isabelle://named/greeting"))
    assertEquals(text, "hello from MCP_Resource")
    expect_error(session.mcp_resource_read("isabelle://named/no_such_resource"),
      containing = "no_such_resource")
  }

  /* T5 (plans/doc_list): the full chain over a live server -- tools/list
     advertises doc_list, tools/call reaches the real Doc_Catalog built
     from this session's own Sessions.Structure at startup. */
  test("bridge: tools/list advertises doc_list, tools/call names the source session") {
    val handler = new MCP_Server.Handler(session)
    val tools = get_list(rpc_on(handler, "tools/list"), "result", "tools")
    assert(tools.exists(t => get_string(t, "name") == "doc_list"),
      "doc_list missing from tools/list: " + tools.toString)

    val reply = call_tool_on(handler, "doc_list", JSON.Object())
    assert_no_error(reply)
    val text = result_text(reply)
    assert(text.contains("isar-ref"), "doc_list should list the isar-ref manual: " + text)
    assert(text.contains("source: Isar_Ref"),
      "doc_list should name Isar_Ref as isar-ref's source session: " + text)
  }

  /* T7 (plans/doc_read): the full lookup workflow -- doc_list names the
     source session, doc_read without `section` gives the toc, doc_read
     with `section` gives that section's text. */
  test("bridge: doc_list -> doc_read (toc) -> doc_read (section) over a live server") {
    val handler = new MCP_Server.Handler(session)

    val toc_reply = call_tool_on(handler, "doc_read", JSON.Object("name" -> "isar-ref"))
    assert_no_error(toc_reply)
    val toc_text = result_text(toc_reply)
    assert(toc_text.contains("Defining theories"),
      "isar-ref toc should list the \"Defining theories\" section: " + toc_text)

    val section_reply =
      call_tool_on(handler, "doc_read",
        JSON.Object("name" -> "isar-ref", "section" -> "Defining theories"))
    assert_no_error(section_reply)
    val section_text = result_text(section_reply)
    assert(section_text.contains("definition--statement--proof elements"),
      "isar-ref section read should return Spec.thy's body text: " + section_text.take(200))
  }
}


/* MCP.ir bridge: the dispatcher over the I/R engine (MCP-HOL/MCP_Repl) */

class MCP_Ir_Bridge_Tests extends MCP_Session_Suite(
  "MCP-HOL", "MCP_Repl", McpBridgeProfile.hol) {
  test("startup hello advertises inherited base operations plus ir for MCP-HOL") {
    assertEquals(session.bridge_operation_names, McpBridgeOperations.holOperationNames)
  }

  spec_test("ir bridge: oversized request is typed and does not poison the next call",
      covers = List("pide_bridge#T11")) {
    val oversized = "x" * 262144
    session.ir("repls", List("padding" -> oversized)) match {
      case MCP_Session.Error(message) =>
        assert(message.contains("request bridge envelope"), message)
      case other => fail("expected request TooLarge, got " + other)
    }
    assert(session.ir("repls", Nil).ok)
  }

  spec_test("common v1 envelope executes all seven typed operations and reply codecs",
      verifies = List("pide_bridge#I1")) {
    val tools = session.ml_tools()
    assert(tools.rows.exists(_.name == "MCP_Tools.shout"))

    val theories = session.ml_theories()
    val replTheory = theories.find(Long_Name.base_name(_) == "MCP_Repl")
      .getOrElse(fail("MCP_Repl not in " + theories.mkString(", ")))

    assertEquals(session.ml_run("MCP_Tools.shout", List("input" -> "bridge")),
      MCP_Session.Ok("BRIDGE"))
    assert(session.check_context("isabelle://context/theory/" + replTheory).ok)
    assert(session.ir("repls", Nil).ok)

    val resources = session.ml_named_resources()
    assert(resources.exists(_._1 == "MCP_Tools.greeting"))
    assertEquals(session.ml_read_resource("MCP_Tools.greeting"),
      MCP_Session.Ok("hello from MCP_Resource"))

    assert(!session.ml_run("no_such_tool", Nil).ok)
    assert(!session.ir("no_such_function", Nil).ok)
    assert(!session.ml_read_resource("no_such_resource").ok)
  }

  spec_test("REPL creation returns an ML-generated canonical context locator",
      covers = List("context_locator#T3", "context_locator#T7")) {
    val repl = "Locator_Result"
    val main = session.ml_theories().find(Long_Name.base_name(_) == "Main")
      .getOrElse(fail("Main not present in loaded theories"))
    val expected = "isabelle://context/repl/Locator_Result"
    try {
      session.ir("init", List("repl" -> repl, "theories" -> main)) match {
        case MCP_Session.Ok(text) => assert(text.contains("Context: " + expected), text)
        case error => fail("REPL creation failed: " + error)
      }
      assertEquals(session.check_context(expected), MCP_Session.Ok(expected))
    }
    finally session.ir("remove", List("repl" -> repl))
  }

  /* The public cancellable IR API calls the production PideBridge (rather
     than the older direct PIDE helper). The deterministic bridge unit test
     owns pending/deadline accounting; here a live PIDE session proves that
     ordered cancellation reaches the registered IR operation, releases its
     REPL claim, and leaves subsequent IR calls usable. */
  spec_test("ir bridge cancellation returns promptly, releases the claim, and leaves the session usable",
      covers = List("pide_bridge#T3", "connection_kernel#T4")) {
    with_repl("CancelledIR") {
      val registry = new RequestRegistry(RequestRegistry.InvariantViolationPolicy.FailFast)
      val requestId = RequestId.string("live-ir-cancel")
      val admitted = registry.admit(requestId, RequestRegistry.AdmissionKind.Ordinary) match {
        case value: RequestRegistry.Admitted => value
        case other => fail("could not admit live IR cancellation fixture: " + other)
      }
      val slow = Future.fork(session.ir_cancellable("step",
        List("repl" -> "CancelledIR",
          "isar_text" -> "ML_command \\<open>OS.Process.sleep (seconds 5.0)\\<close>"),
        admitted.cancellation))
      await_busy("CancelledIR")

      val before = Time.now()
      assert(registry.cancel(requestId, Some("bridge fixture"))
        .isInstanceOf[RequestRegistry.Cancelled])
      expect_error(slow.join, containing = "cancelled")
      assert(Time.now() - before < Time.seconds(1.0),
        "Scala IR bridge promise did not return promptly after cancellation")
      eventually("cancelled IR operation retained the REPL claim", Time.seconds(2.0)) {
        session.ir("repls", Nil) match {
          case MCP_Session.Ok(text) => text.contains("CancelledIR") && !text.contains("busy")
          case _ => false
        }
      }
      expect_ok(session.ir("state", List("repl" -> "CancelledIR", "state_idx" -> "-1")),
        "IR session was unusable after cancellation")
    }
  }

  test("ir bridge: repls on an empty table returns ok") {
    expect_ok(session.ir("repls", Nil))
  }

  test("ir bridge: repls reflects a created and removed repl") {
    expect_ok(session.ir("init", List("repl" -> "BT", "theories" -> "Main")), "init BT")
    assert(repl_listing().contains("BT"), "listing missing BT")
    expect_ok(session.ir("remove", List("repl" -> "BT")), "remove BT")
    assert(!repl_listing().contains("BT"), "listing still shows removed BT")
  }

  test("ir bridge: unknown fname is an error naming it") {
    expect_error(session.ir("no_such_fname", Nil), containing = "no_such_fname")
  }

  /* KNOWN GAP (found while testing T6, plans/repl_init): Ir.load_theory
     refuses to run whenever Ir.is_interactive_session () is true (it
     reads Printer.show_markup_default, meant to distinguish jEdit
     from batch `isabelle build`). Our own MCP_Session backend runs a
     Headless.Session, which -- like jEdit -- goes through
     init_options_interactive and sets that ref true. So load_theory,
     and by extension repl_init's "Thy:idx" segment-spec path (which
     needs a theory loaded with record_theories via load_theory
     first), cannot currently be exercised from the live MCP server
     at all. This regression test pins the current (broken) behavior
     so a future fix to Ir.is_interactive_session's detection is
     forced to update this test, rather than the gap going unnoticed;
     see plans/repl_init T6 and plans/load_theory. */
  test("ir bridge: KNOWN GAP -- load_theory refuses to run from a headless MCP_Session (blocks repl_init's Thy:idx segment path)") {
    session.ir("load_theory", List("theory_name" -> "MCP_Repl_Dyn_Source")) match {
      case MCP_Session.Error(msg) =>
        assert(msg.contains("interactive"),
          "expected the is_interactive_session error, got: " + msg)
      case result =>
        fail("load_theory unexpectedly succeeded from a headless session " +
          "-- if this gap was fixed, replace this test with the positive " +
          "Thy:idx segment-spec case (T6, plans/repl_init): " + result.toString)
    }
  }

  test("ir bridge: async — a slow call does not block a concurrent fast one, and repl_list's busy annotation is transient") {
    with_repl("Slow") {
      val slow = slow_step("Slow")
      val fast = session.ir("repls", Nil)
      assert(fast.ok, "fast call did not return ok while slow call was in flight")
      assert(!slow.is_finished, "slow call finished before the fast reply arrived")

      await_busy("Slow")

      slow.join
      val text = repl_listing()
      assert(text.contains("Slow") && !text.contains("busy"),
        "Slow still shows busy after the step completed: " + text)
      expect_ok(session.ir("remove", List("repl" -> "Slow")), "remove Slow")
    }
  }

  spec_test("ir bridge: a slow step on one REPL does not delay a concurrent step on another",
      covers = List("repl_step#T5")) {
    with_repl("StepA") {
      with_repl("StepB") {
        val slow = slow_step("StepA")
        val fast = session.ir("step", List("repl" -> "StepB", "isar_text" -> "lemma True"))
        assert(fast.ok, "step on StepB did not return ok while StepA's step was in flight")
        assert(!slow.is_finished, "the slow step finished before the fast one returned")
        slow.join
      }
    }
  }

  spec_test("ir bridge: byte fidelity survives the scala-side yxml stripping (symbols, doubled spaces, embedded newline)",
      covers = List("repl_text#T1")) {
    with_repl("Texted") {
      val step_text = "lemma \"x \\<longrightarrow> x\"\n  by  simp"
      expect_ok(session.ir("step", List("repl" -> "Texted", "isar_text" -> step_text)),
        "step on Texted")
      assertEquals(expect_ok(session.ir("text", List("repl" -> "Texted"))), step_text,
        "text did not round-trip byte-clean")
    }
  }

  spec_test("ir bridge: show on a busy REPL errors \"is busy\", not a stale read",
      covers = List("repl_show#T2")) {
    with_repl("Shown") {
      val slow = slow_step("Shown")
      eventually("show on Shown never errored \"busy\" while the step was in flight") {
        session.ir("show", List("repl" -> "Shown")) match {
          case MCP_Session.Error(msg) => msg.contains("busy")
          case _ => false
        }
      }
      slow.join
      expect_ok(session.ir("show", List("repl" -> "Shown")),
        "show on Shown failed once the step completed")
    }
  }

  spec_test("ir bridge: full chain, init/step/fork/step fork/repls shows both with origins",
      covers = List("repl_fork#T6")) {
    with_repl("Fork6") {
      expect_ok(session.ir("step",
        List("repl" -> "Fork6", "isar_text" -> "lemma fork6: True")), "step 0 on Fork6")

      expect_ok(session.ir("fork",
        List("repl" -> "Fork6", "new_repl" -> "Fork6C", "state_idx" -> "-1")),
        "fork Fork6C from Fork6")

      expect_ok(session.ir("step",
        List("repl" -> "Fork6C", "isar_text" -> "by simp")), "step 0 on Fork6C")

      val listing = repl_listing()
      assert(listing.contains("Fork6"), "listing missing parent Fork6: " + listing)
      assert(listing.contains("Fork6C"), "listing missing fork Fork6C: " + listing)
      /* ir.ML's origin_str renders a From_REPL origin as `REPL "<parent>"
         state <i>` (Ir.repls) -- check the child's entry actually names
         its parent as origin, not just that both ids happen to appear */
      assert(listing.contains("from REPL \"Fork6\" state"),
        "Fork6C's listing entry does not mention its parent as origin: " + listing)

      /* the fork's own step never touched the parent -- parent still
         has exactly its one step */
      val parent_show = expect_ok(session.ir("show", List("repl" -> "Fork6")),
        "show Fork6 after stepping the fork")
      assert(parent_show.contains("1 steps"),
        "parent Fork6 changed by stepping its fork: " + parent_show)

      expect_ok(session.ir("remove", List("repl" -> "Fork6C")), "remove Fork6C")
    }
  }

  spec_test("ir bridge: a busy orphan blocks the whole truncate, nothing is half-removed",
      covers = List("repl_truncate#T4")) {
    with_repl("Trunc") {
      expect_ok(session.ir("step",
        List("repl" -> "Trunc", "isar_text" -> "lemma trsu1: True")), "step 0 on Trunc")
      expect_ok(session.ir("step",
        List("repl" -> "Trunc", "isar_text" -> "by simp")), "step 1 on Trunc")
      expect_ok(session.ir("step",
        List("repl" -> "Trunc", "isar_text" -> "lemma trsu2: True")), "step 2 on Trunc")
      expect_ok(session.ir("fork",
        List("repl" -> "Trunc", "new_repl" -> "TruncChild", "state_idx" -> "3")),
        "fork TruncChild")

      val slow = slow_step("TruncChild")
      await_busy("TruncChild")

      expect_error(session.ir("truncate", List("repl" -> "Trunc", "idx" -> "1")),
        containing = "busy")

      slow.join
      expect_ok(session.ir("truncate", List("repl" -> "Trunc", "idx" -> "1")),
        "truncate Trunc failed once TruncChild was no longer busy")
      assert(!repl_listing().contains("TruncChild"),
        "listing still shows TruncChild after its orphaning truncate")
    }
  }

  spec_test("ir bridge: a busy parent blocks merge without corrupting the child",
      covers = List("repl_merge#T4")) {
    with_repl("MPar") {
      expect_ok(session.ir("fork",
        List("repl" -> "MPar", "new_repl" -> "MChild", "state_idx" -> "0")), "fork MChild")
      expect_ok(session.ir("step",
        List("repl" -> "MChild", "isar_text" -> "lemma mmg1: True")), "step 0 on MChild")
      expect_ok(session.ir("step",
        List("repl" -> "MChild", "isar_text" -> "by simp")), "step 1 on MChild")

      val slow = slow_step("MPar")
      await_busy("MPar")

      expect_error(session.ir("merge", List("repl" -> "MChild")), containing = "busy")

      slow.join
      expect_ok(session.ir("merge", List("repl" -> "MChild")),
        "merge MChild into MPar failed once MPar was no longer busy")
      assert(!repl_listing().contains("MChild"),
        "listing still shows MChild after a successful merge")
    }
  }

  spec_test("ir bridge: the full chain sets and reports a per-REPL timeout",
      covers = List("repl_timeout#T4")) {
    with_repl("Tmo") {
      val text = expect_ok(session.ir("timeout", List("repl" -> "Tmo", "secs" -> "5")))
      assert(text.contains("5s"), "unexpected reply: " + text)
      val shown = expect_ok(session.ir("show", List("repl" -> "Tmo")))
      assert(shown.contains("timeout=5s"), "unexpected show: " + shown)
    }
  }

  /* T3 (plans/sledgehammer): the happy path. Tolerant of prover
     flakiness per the plan -- accept either a "Try this" line or
     a no-proof-found message, never a crash; only step the
     suggestion when one actually came back. */
  spec_test("ir bridge: happy path finds a proof for a trivial goal (tolerant of prover flakiness)",
      covers = List("sledgehammer#T3")) {
    with_repl("Sh3") {
      expect_ok(session.ir("step",
        List("repl" -> "Sh3", "isar_text" -> "lemma \"x + y = y + (x::nat)\"")), "step Sh3")
      session.ir("sledgehammer", List("repl" -> "Sh3")) match {
        case MCP_Session.Ok(text) =>
          val tries = text.linesIterator.filter(_.contains("Try this")).toList
          if (tries.nonEmpty) {
            val suggestion =
              tries.head.replaceFirst(".*Try this:\\s*", "")
                .replaceFirst("\\s*\\([0-9.]+\\s*m?s\\)\\s*$", "").trim
            expect_ok(session.ir("step", List("repl" -> "Sh3", "isar_text" -> suggestion)),
              "the suggested one-liner did not close the goal: " + suggestion)
          }
        case MCP_Session.Error(msg) =>
          fail("sledgehammer crashed instead of returning a no-proof-found message: " + msg)
      }
    }
  }

  /* T4 (plans/sledgehammer): concurrency decision recorded in
     ir.ML (Ir.with_sledgehammer_lock) and plans/sledgehammer's
     status block -- calls are serialized via a global blocking
     lock rather than a busy error, so two concurrent calls on
     different REPLs must both return sane, uncrossed results. */
  spec_test("ir bridge: two concurrent calls on different REPLs never cross outputs",
      covers = List("sledgehammer#T4")) {
    with_repl("Sh4A") {
      with_repl("Sh4B") {
        expect_ok(session.ir("step",
          List("repl" -> "Sh4A", "isar_text" -> "lemma \"x + y = y + (x::nat)\"")),
          "step Sh4A")
        expect_ok(session.ir("step",
          List("repl" -> "Sh4B", "isar_text" -> "lemma \"True \\<and> True\"")),
          "step Sh4B")

        val a = Future.fork(session.ir("sledgehammer", List("repl" -> "Sh4A")))
        val b = Future.fork(session.ir("sledgehammer", List("repl" -> "Sh4B")))
        (a.join, b.join) match {
          case (MCP_Session.Ok(_), MCP_Session.Ok(_)) => ()
          case (ra, rb) =>
            fail("expected both concurrent sledgehammer calls to return Ok: " +
              ra.toString + " / " + rb.toString)
        }
      }
    }
  }

  /* T5 (plans/sledgehammer): async under load, shared pattern with
     plans/repl_step T5 -- a fast call on another REPL returns
     while sledgehammer is still in flight on this one. */
  spec_test("ir bridge: async under load, a fast call on another repl returns while sledgehammer is in flight",
      covers = List("sledgehammer#T5")) {
    with_repl("Sh5") {
      expect_ok(session.ir("step",
        List("repl" -> "Sh5", "isar_text" -> "lemma \"x + y = y + (x::nat)\"")), "step Sh5")
      val slow = Future.fork(session.ir("sledgehammer", List("repl" -> "Sh5")))
      val fast = session.ir("repls", Nil)
      assert(fast.ok, "fast call did not return ok while sledgehammer was in flight")
      slow.join
    }
  }

  /* T6 (plans/find_theorems): interactive-fast, unlike
     sledgehammer -- no async gymnastics needed, but it must not
     block behind a slow call on another REPL either. */
  spec_test("ir bridge: returns promptly on B during a slow step on A",
      covers = List("find_theorems#T6")) {
    with_repl("FtA") {
      with_repl("FtB") {
        val slow = slow_step("FtA")
        val fast = session.ir("find_theorems", List("repl" -> "FtB", "query" -> "name:conjI"))
        assert(fast.ok, "find_theorems did not return ok while FtA's step was in flight")
        assert(!slow.is_finished, "the slow step finished before the fast find_theorems reply")
        slow.join
      }
    }
  }

  test("ir bridge: isabelle://repl/{id} and .../text resources dispatch to the real ir show/text against a live REPL") {
    with_repl("Res") {
      expect_ok(session.ir("step",
        List("repl" -> "Res", "isar_text" -> "lemma \"True\" by simp")), "step Res")
      val shown = expect_ok(session.mcp_resource_read("isabelle://repl/Res"))
      assert(shown.contains("Res"), "repl resource missing the repl id: " + shown)
      val text = expect_ok(session.mcp_resource_read("isabelle://repl/Res/text"))
      assert(text.contains("lemma \"True\" by simp"),
        "repl-text resource missing the stepped isar text: " + text)
      expect_error(session.mcp_resource_read("isabelle://repl/NoSuchRepl"))
    }
  }

  spec_test("ir bridge: the pin/unpin round trip",
      covers = List("repl_unpin#T3")) {
    with_repl("Upn") {
      expect_ok(session.ir("pin", List("repl" -> "Upn")), "pin Upn")
      val pinned = expect_ok(session.ir("show", List("repl" -> "Upn")))
      assert(pinned.contains("pinned"), "unexpected show: " + pinned)
      expect_ok(session.ir("unpin", List("repl" -> "Upn")), "unpin Upn")
      val unpinned = expect_ok(session.ir("show", List("repl" -> "Upn")))
      assert(!unpinned.contains("pinned"), "still shows pinned after unpin: " + unpinned)
    }
  }

  /* wave 2 (plans/load_theory, plans/unload_theory, plans/check_theory):
     session.load_theory/unload_theory/check_theory over Headless
     use_theories/purge_theories -- disjoint from the MCP.ir dispatcher
     (the KNOWN GAP test above), so these call session.* directly, not
     session.ir(...). fixtures are written to a fresh tmp dir per test
     (Isabelle_System.with_tmp_dir) rather than checked-in files, since
     check_theory's staleness case (T1) needs to edit a fixture on disk
     between calls. */

  private def wave2_theory(name: String, body: String): String =
    "theory " + name + "\n  imports Main\nbegin\n\n" + body + "\n\nend\n"

  private val wave2_good = "lemma wave2_good: \"True\" by simp"
  private val wave2_bad = "lemma wave2_bad: \"False\"\n  by simp"
  private val wave2_warn = "lemma wave2_warn: \"False\"\n  sorry"

  private def with_fixture_dir(files: (String, String)*)(body: Path => Unit): Unit =
    Isabelle_System.with_tmp_dir("wave2") { dir =>
      for ((name, content) <- files) File.write(dir + Path.basic(name + ".thy"), content)
      body(dir)
    }

  /* theory-resource tier matrix (isabelle://theory/{name}[/diagnostics|
     /entities|/commands]): three of the four tiers are fixed, reusable
     names -- image and filesystem-but-never-loaded need no per-test
     setup, only "loaded" is inherently dynamic (load_theory needs a
     fresh fixture dir per test) and stays inline where it's used.

     tier_image ("MCP_Repl", this suite's own -T theory) was built WITH
     record_theories (mcp/Tools/ROOT), so it exercises the full feature
     on /commands and bare /theory, not just diagnostics/entities.

     tier_filesystem ("MCP-HOL-Tests.MCP_Fixture_Nav") is a real,
     on-disk theory from the sibling MCP-HOL-Tests session: known to
     THIS session's theory_map (same -d mcp/Tools catalog) but never
     loaded here, so it is a genuine filesystem-tier example -- unlike
     the "Wave2Res*Never" names this matrix replaces, which were never
     real theory_map entries at all and so silently tested the `None`
     (wholly unrecognized) case while claiming to test "filesystem".
     Passing it to bare /theory also exercises the FileSystemTier
     real-file-read code path with real content for the first time.

     tier_unknown matches nothing anywhere: the genuinely-unrecognized
     case, kept separate and explicit so it can never again masquerade
     as "filesystem" by accident. */
  private val tier_image = "MCP_Repl"
  private val tier_filesystem = "MCP-HOL-Tests.MCP_Fixture_Nav"
  private val tier_unknown = "NoSuchTheoryWhatsoever12345"

  /* ok=true with contains="" only asserts a non-empty Ok reply (some
     tiers' exact wording isn't the point of the matrix, e.g. image's
     raw segment dump); every row still gets an explicit, visible
     expectation, so a missing/wrong case fails right here instead of
     going untested -- exactly the shape of bug this matrix exists to
     catch (see mcp_session.scala's theory_diagnostics/theory_entities/
     theory_commands_uri/theory_source_uri, and CHANGELOG's "fix:
     theory resource reads for loaded/unrecognized names"). */
  private case class Tier_Expect(ok: Boolean, contains: String = "")

  private def assert_tier(suffix: String, tier: String, name: String, expect: Tier_Expect)
      (implicit loc: munit.Location): Unit = {
    val uri = "isabelle://theory/" + name + suffix
    if (expect.ok) {
      val text = expect_ok(session.mcp_resource_read(uri), tier + " tier (" + uri + ")")
      if (expect.contains.nonEmpty) {
        assert(text.contains(expect.contains),
          tier + " tier: unexpected reply for " + uri + ": " + text)
      }
      else assert(text.nonEmpty, tier + " tier: empty reply for " + uri)
    }
    else expect_error(session.mcp_resource_read(uri), containing = expect.contains)
  }

  spec_test("wave 2: a well-formed fixture under master_dir loads and reports ok",
      covers = List("load_theory#T1")) {
    with_fixture_dir("Wave2Good1" -> wave2_theory("Wave2Good1", wave2_good)) { dir =>
      val text = expect_ok(session.load_theory("Wave2Good1", File.standard_path(dir)))
      assert(text.contains("Wave2Good1: ok"), "unexpected load_theory reply: " + text)
    }
  }

  spec_test("wave 2: a broken fixture is a line-positioned isError, and the session survives",
      covers = List("load_theory#T2")) {
    with_fixture_dir(
      "Wave2Bad1" -> wave2_theory("Wave2Bad1", wave2_bad),
      "Wave2Good2" -> wave2_theory("Wave2Good2", wave2_good)
    ) { dir =>
      val master_dir = File.standard_path(dir)
      val err = expect_error(session.load_theory("Wave2Bad1", master_dir))
      assert(err.contains("line"), "load_theory error missing a line position: " + err)
      expect_ok(session.load_theory("Wave2Good2", master_dir),
        "the session did not survive a prior load_theory error")
    }
  }

  spec_test("wave 2: re-loading an unchanged theory is ok both times",
      covers = List("load_theory#T4")) {
    with_fixture_dir("Wave2Good3" -> wave2_theory("Wave2Good3", wave2_good)) { dir =>
      val master_dir = File.standard_path(dir)
      expect_ok(session.load_theory("Wave2Good3", master_dir), "first load")
      expect_ok(session.load_theory("Wave2Good3", master_dir), "second load")
    }
  }

  spec_test("wave 2: load, unload, and load fresh again all succeed",
      covers = List("unload_theory#T1")) {
    with_fixture_dir("Wave2Unl1" -> wave2_theory("Wave2Unl1", wave2_good)) { dir =>
      val master_dir = File.standard_path(dir)
      expect_ok(session.load_theory("Wave2Unl1", master_dir), "load before unload")
      expect_ok(session.unload_theory("Wave2Unl1"), "unload")
      expect_ok(session.load_theory("Wave2Unl1", master_dir), "load after unload")
    }
  }

  spec_test("composed direct builtins serialize theory mutation and cancel a lock waiter",
      covers = List("connection_kernel#T12")) {
    with_fixture_dir("SerializedMutation" -> wave2_theory("SerializedMutation", wave2_good)) {
      dir =>
        def checked[A](value: Either[String, A]): A = value.fold(error, identity)
        val policy = ConnectionPolicy(
          revision = ProtocolRevision.V2025_03_26,
          input = checked(McpInputPolicy.checked(1048576)),
          admission = ConnectionPolicy.AdmissionPolicy(
            checked(ConnectionPolicy.MaxInFlight.checked(2))),
          timing = ConnectionPolicy.TimingPolicy(
            checked(ConnectionPolicy.RequestTimeout.checked(10.0)),
            checked(ConnectionPolicy.ShutdownDrain.checked(2.0))))
        val plane = new ScriptedDataPlane(Nil)
        val rules = new Mcp2025RevisionRules
        val registry = new RequestRegistry(RequestRegistry.InvariantViolationPolicy.FailFast)
        val application = McpApplication.isabelle(
          () => McpApplication.Ready(session), "MCP-HOL", Nil, "MCP_Repl",
          McpOutputPolicy.TestDefault)
        val kernel = ConnectionKernel(
          policy = policy,
          dataPlane = plane,
          revisionRules = rules,
          scheduler = new BoundedConcurrentScheduler(2, "theory-mutation-kernel"),
          deadlineScheduler = new ManualDeadlineScheduler,
          registry = registry,
          application = application,
          serverInfo = ConnectionKernel.ServerInfo("test", "test"))

        def request(id: Option[String], method: String,
            params: Option[JSON.Object.T] = None): RevisionRules.Message = {
          var json = JSON.Object("jsonrpc" -> "2.0", "method" -> method)
          id.foreach(value => json += ("id" -> value))
          params.foreach(value => json += ("params" -> value))
          rules.classify(JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(json)))
        }
        def call(id: String, name: String, arguments: JSON.Object.T): Unit =
          kernel.handle(request(Some(id), "tools/call",
            Some(JSON.Object("name" -> name, "arguments" -> arguments))))
        def response(id: String): Option[JSON.T] =
          plane.written.iterator.flatMap(JSON.Format.unapply).find(json =>
            JSON.string(json, "id").contains(id))
        def successful(id: String): Boolean =
          response(id).exists(json =>
            JSON.value(json, "result").flatMap(JSON.bool(_, "isError")) != Some(true) &&
              JSON.value(json, "error").isEmpty)

        val entered = new CountDownLatch(1)
        val release = new CountDownLatch(1)
        val first = new java.util.concurrent.atomic.AtomicBoolean(true)
        session.set_theory_mutation_probe(() => {
          if (first.compareAndSet(true, false)) {
            entered.countDown()
            if (!release.await(5, TimeUnit.SECONDS))
              error("theory mutation fixture release timed out")
          }
        })

        try {
          kernel.handle(request(Some("init"), "initialize",
            Some(JSON.Object("protocolVersion" -> ProtocolRevision.V2025_03_26.value))))
          kernel.handle(request(None, "notifications/initialized"))
          val masterDir = File.standard_path(dir)
          call("load", "load_theory",
            JSON.Object("name" -> "SerializedMutation", "master_dir" -> masterDir))
          assert(entered.await(5, TimeUnit.SECONDS), "load_theory did not enter mutation gate")

          call("cancelled-check", "check_theory",
            JSON.Object("name" -> "SerializedMutation", "master_dir" -> masterDir))
          eventually("check_theory was not admitted behind load_theory", Time.seconds(2.0)) {
            registry.snapshot.activeIds.contains(RequestId.string("cancelled-check"))
          }
          kernel.handle(request(None, "notifications/cancelled",
            Some(JSON.Object("requestId" -> "cancelled-check", "reason" -> "gate fixture"))))
          eventually("cancelled lock waiter retained connection capacity", Time.seconds(2.0)) {
            !registry.snapshot.activeIds.contains(RequestId.string("cancelled-check"))
          }
          release.countDown()

          eventually("load_theory did not complete after releasing mutation gate",
              Time.seconds(5.0)) { successful("load") }
          call("check", "check_theory",
            JSON.Object("name" -> "SerializedMutation", "master_dir" -> masterDir))
          eventually("check_theory did not run after cancelled waiter", Time.seconds(5.0)) {
            successful("check")
          }
          call("unload", "unload_theory", JSON.Object("name" -> "SerializedMutation"))
          eventually("unload_theory did not run after check_theory", Time.seconds(5.0)) {
            successful("unload")
          }
          assert(response("cancelled-check").isEmpty,
            "client-cancelled check_theory emitted a response")
        }
        finally {
          release.countDown()
          session.set_theory_mutation_probe(() => ())
          kernel.drainAndClose()
        }
    }
  }

  spec_test("wave 2: THE staleness case: purge-before-reload picks up an on-disk edit",
      covers = List("check_theory#T1")) {
    with_fixture_dir("Wave2Stale" -> wave2_theory("Wave2Stale", wave2_good)) { dir =>
      val master_dir = File.standard_path(dir)
      val file = dir + Path.basic("Wave2Stale.thy")
      expect_ok(session.load_theory("Wave2Stale", master_dir), "initial load")

      File.write(file, wave2_theory("Wave2Stale", wave2_bad))
      val err = expect_error(session.check_theory("Wave2Stale", master_dir))
      assert(err.contains("line"),
        "check_theory did not pick up the on-disk edit (purge-before-reload regressed): " + err)

      File.write(file, wave2_theory("Wave2Stale", wave2_good))
      expect_ok(session.check_theory("Wave2Stale", master_dir),
        "check_theory did not recover once the fixture was fixed")
    }
  }

  spec_test("wave 2: checking a never-loaded filesystem theory works (purge no-op path)",
      covers = List("check_theory#T2")) {
    with_fixture_dir("Wave2Fresh" -> wave2_theory("Wave2Fresh", wave2_good)) { dir =>
      expect_ok(session.check_theory("Wave2Fresh", File.standard_path(dir)))
    }
  }

  spec_test("wave 2: warnings are ok, errors are isError (the pinned warning policy)",
      covers = List("check_theory#T3")) {
    with_fixture_dir(
      "Wave2Warn" -> wave2_theory("Wave2Warn", wave2_warn),
      "Wave2Err" -> wave2_theory("Wave2Err", wave2_bad)
    ) { dir =>
      val master_dir = File.standard_path(dir)
      val warn_text = expect_ok(session.check_theory("Wave2Warn", master_dir),
        "a sorry-only fixture must be ok, not isError")
      assert(warn_text.contains("warning"),
        "check_theory ok reply on a sorry fixture should still mention the warning: " + warn_text)
      expect_error(session.check_theory("Wave2Err", master_dir))
    }
  }

  test("wave 2 resources: isabelle://theory/{name}/diagnostics -- all four tiers") {
    assert_tier("/diagnostics", "image", tier_image, Tier_Expect(true, "checked at build time"))
    /* filesystem and unknown are DELIBERATELY the same expectation:
       diagnostics never fails just because a theory hasn't been
       indexed, so both get the same optimistic "try load_theory"
       text -- see mcp_session.scala's theory_diagnostics. */
    assert_tier("/diagnostics", "filesystem", tier_filesystem,
      Tier_Expect(true, "not checked; load_theory to check"))
    assert_tier("/diagnostics", "unknown", tier_unknown,
      Tier_Expect(true, "not checked; load_theory to check"))

    with_fixture_dir(
      "Wave2ResOk" -> wave2_theory("Wave2ResOk", wave2_good),
      "Wave2ResErr" -> wave2_theory("Wave2ResErr", wave2_bad)
    ) { dir =>
      val master_dir = File.standard_path(dir)
      expect_ok(session.load_theory("Wave2ResOk", master_dir), "load Wave2ResOk")
      val ok_diag = expect_ok(session.mcp_resource_read("isabelle://theory/Wave2ResOk/diagnostics"))
      assert(ok_diag.contains("Wave2ResOk: ok"), "loaded/ok tier: unexpected reply: " + ok_diag)

      /* load_theory itself returns Error for a broken theory (still
         recorded in theory_master_dirs -- see mcp_session.scala), so
         the diagnostics resource must still answer for it, live, with
         "error" and the position, read-time-evaluated straight off
         the same snapshot load_theory's own reply came from. */
      session.load_theory("Wave2ResErr", master_dir)
      val err_diag =
        expect_ok(session.mcp_resource_read("isabelle://theory/Wave2ResErr/diagnostics"))
      assert(err_diag.contains("Wave2ResErr: error") && err_diag.contains("line"),
        "loaded/error tier: unexpected reply: " + err_diag)
    }
  }

  test("wave 2 resources: isabelle://theory/{name}/entities -- image tier lists real name-space entries, filtered to the defining theory") {
    /* "HOL" (image tier, Name_Space.theory_name-filterable -- unlike
       /commands' Thy_Info segments, this does NOT hit the process-local
       KNOWN GAP above) defines conjI as a fact directly -- Main and its
       other ancestors don't redefine it, so it is a clean marker that
       filtering by defining theory (not just "is it in scope") works. */
    val text = expect_ok(session.mcp_resource_read("isabelle://theory/HOL.HOL/entities"))
    assert(text.contains("fact") && text.contains("conjI"),
      "HOL's entities should list conjI as a fact: " + text)
    assert(!text.contains("  rev\n") && !text.split("\\s+").contains("rev"),
      "entities must be filtered to HOL's own definitions, not include List.rev: " + text)
  }

  test("wave 2 resources: isabelle://theory/{name}/entities -- all four tiers") {
    /* image tier's own content correctness (conjI, filtering) is the
       test above; this one is purely about tier coverage, so the
       empty-table image example (MCP_Repl, an ML-only theory) is fine
       -- entities always prints its header even with no rows. */
    assert_tier("/entities", "image", tier_image, Tier_Expect(true, "kind"))
    /* filesystem and unknown are DELIBERATELY the same expectation --
       see mcp_session.scala's theory_entities. */
    assert_tier("/entities", "filesystem", tier_filesystem, Tier_Expect(false, "not backed yet"))
    assert_tier("/entities", "unknown", tier_unknown, Tier_Expect(false, "not backed yet"))
  }

  /* image-tier name normalization (mcp_session.scala's image_theory):
     loaded_theories/Thy_Info key by canonical long names, and that
     keying mixes qualified and unqualified entries ("HOL.Wellfounded"
     vs plain "Main"), so client spellings must be resolved -- verbatim,
     session-qualified, then unique base-name match -- and the RESOLVED
     name is what crosses the ir bridge. Before the fix, "MCP_Repl"
     passed the tier gate but died in ML with "Theory loader: undefined
     entry", and "Wellfounded"/"HOL.Main" fell through to "not backed
     yet" although both theories sit in the image. */
  test("wave 2 resources: isabelle://theory/{name}/entities -- image-tier names are normalized to the canonical Thy_Info key") {
    /* the session's own -T theory by its natural base name; canonical
       key is MCP-HOL.MCP_Repl (empty table is correct: ML-only theory) */
    expect_ok(session.mcp_resource_read("isabelle://theory/MCP_Repl/entities"),
      "base-name spelling of the -T theory must resolve, not hit a raw Thy_Info error")
    /* unique base-name match across the image; canonical key is HOL.Wellfounded */
    val base = expect_ok(session.mcp_resource_read("isabelle://theory/Wellfounded/entities"),
      "unique base name of a foreign-session image theory must resolve")
    assert(base.contains("Wellfounded.wf"),
      "normalized base-name read should list Wellfounded's own entities: " + base)
    /* foreign-qualified spelling of a canonically unqualified theory (key is plain "Main") */
    expect_ok(session.mcp_resource_read("isabelle://theory/HOL.Main/entities"),
      "HOL.Main must resolve to the canonical unqualified key Main")
  }

  test("wave 2 resources: isabelle://theory/{name}/entities -- loaded tier reads live entity-def markup from the snapshot") {
    with_fixture_dir(
      "Wave2ResEnt" ->
        wave2_theory("Wave2ResEnt",
          "definition wave2_ent_const :: nat where \"wave2_ent_const = 42\"")
    ) { dir =>
      expect_ok(session.load_theory("Wave2ResEnt", File.standard_path(dir)), "load Wave2ResEnt")
      val text = expect_ok(session.mcp_resource_read("isabelle://theory/Wave2ResEnt/entities"))
      assert(text.contains("wave2_ent_const"),
        "loaded-tier entities should list the definition command's own constant: " + text)
    }
  }

  /* RETRACTED KNOWN GAP (2026-07-10): an earlier version of this test
     pinned "No recorded segments" for MCP_Repl and concluded that
     Thy_Info segments never survive into a fresh headless process.
     That diagnosis was FALSE -- an artifact of the name-normalization
     bug: the scala side forwarded the client's base spelling
     "MCP_Repl" to ML, Thy_Info.get_theory errored "undefined entry",
     and ir.ML's find_source swallowed that ERROR and rewrote it as
     "No recorded segments". With image_theory resolving to the
     canonical key (MCP-HOL.MCP_Repl), segments recorded at build time
     (record_theories in mcp/Tools/ROOT) DO survive the saved heap and
     the resource answers with real source from a live server. The
     genuine remaining error case is a theory whose session was built
     WITHOUT record_theories (the stock HOL heap): that one really has
     no segments, and Isabelle's actionable rebuild hint is the right
     reply. */
  test("wave 2 resources: isabelle://theory/{name} and .../commands -- recorded segments survive into the live server (retracted KNOWN GAP: the old failure was the name bug)") {
    val source = expect_ok(session.mcp_resource_read("isabelle://theory/MCP_Repl"),
      "the -T theory was built with record_theories; its source must be readable")
    assert(source.contains("theory MCP_Repl"),
      "source read should return the theory text: " + source.take(200))
    val commands = expect_ok(session.mcp_resource_read("isabelle://theory/MCP_Repl/commands"),
      "commands map for the -T theory must be readable")
    assert(commands.nonEmpty, "commands map should not be empty")
    /* a theory from a heap built without record_theories still errors,
       with Isabelle's own rebuild hint */
    expect_error(session.mcp_resource_read("isabelle://theory/HOL.Wellfounded"),
      containing = "No recorded segments")
  }

  test("wave 2 resources: isabelle://theory/{name}/commands -- all four tiers") {
    /* image: MCP_Repl has real recorded segments (see the retracted-
       KNOWN-GAP test above for the detailed regression pin); this row
       only asserts the tier dispatches to a non-empty Ok. */
    assert_tier("/commands", "image", tier_image, Tier_Expect(true))
    /* filesystem is its OWN case here (unlike diagnostics/entities): a
       real, on-disk, never-loaded theory gets an actionable stub, not
       an error -- see mcp_session.scala's theory_commands_uri. */
    assert_tier("/commands", "filesystem", tier_filesystem,
      Tier_Expect(true, "command map needs load_theory"))
    /* loaded and unknown are DELIBERATELY the same expectation here:
       a headless use_theories load has no Thy_Info-recorded segments
       either, same as a name theory_map has never heard of. */
    assert_tier("/commands", "unknown", tier_unknown, Tier_Expect(false, "not backed yet"))

    with_fixture_dir("Wave2ResCmd" -> wave2_theory("Wave2ResCmd", wave2_good)) { dir =>
      expect_ok(session.load_theory("Wave2ResCmd", File.standard_path(dir)), "load Wave2ResCmd")
      assert_tier("/commands", "loaded", "Wave2ResCmd", Tier_Expect(false, "not backed yet"))
    }
  }

  test("wave 2 resources: bare isabelle://theory/{name} (source) -- all four tiers") {
    assert_tier("", "image", tier_image, Tier_Expect(true, "theory MCP_Repl"))
    /* filesystem genuinely reads the file off disk here -- MCP_Fixture_Nav
       is a real theory on this session's search path, never loaded, so
       this is the FileSystemTier code path with real content, not a
       stand-in for "unknown name" (see mcp_session.scala's
       theory_source_uri and the CHANGELOG entry this matrix follows). */
    assert_tier("", "filesystem", tier_filesystem, Tier_Expect(true, "theory MCP_Fixture_Nav"))
    assert_tier("", "unknown", tier_unknown, Tier_Expect(false, "not backed yet"))

    with_fixture_dir("Wave2ResSrc" -> wave2_theory("Wave2ResSrc", wave2_good)) { dir =>
      expect_ok(session.load_theory("Wave2ResSrc", File.standard_path(dir)), "load Wave2ResSrc")
      /* loaded (not image) -- still not backed: Thy_Info has no
         recorded segments for a headless use_theories load, only for
         the classical batch-mode loader (see mcp_session.scala's
         comment on theory_source_uri/theory_commands_uri). */
      assert_tier("", "loaded", "Wave2ResSrc", Tier_Expect(false, "not backed yet"))
    }
  }

  spec_test("ir bridge: a busy descendant blocks removal, nothing is half-removed",
      covers = List("repl_remove#T4")) {
    with_repl("Par") {
      expect_ok(session.ir("fork",
        List("repl" -> "Par", "new_repl" -> "Child", "state_idx" -> "0")), "fork Child")
      val slow = slow_step("Child")

      /* wait until Child is observably busy before touching remove --
         remove is destructive, so calling it before the step has
         claimed the repl would delete Par/Child outright instead of
         exercising the busy-guard */
      await_busy("Child")

      expect_error(session.ir("remove", List("repl" -> "Par")), containing = "busy")

      slow.join
      expect_ok(session.ir("remove", List("repl" -> "Par")),
        "remove Par failed once Child was no longer busy")
      val text = repl_listing()
      assert(!text.contains("Par") && !text.contains("Child"),
        "listing still shows Par or Child after removal: " + text)
    }
  }

  /* plans/tool_scope: the SELF-EXTENSION HINGE -- a tool registered by
     the agent itself, mid-session, via repl_step, becomes callable once
     the connection's tool scope is pointed at that repl. Exercised
     through MCP_Server.Handler (the JSON-RPC layer), not session.ir
     directly, since the scope is Handler-owned connection state. */
  test("tool_scope bridge: a repl-registered tool becomes servable through its context locator") {
    /* the mcp_tool command keyword is only active in theories that
       (transitively) import MCP_Tools -- Main does not, so this repl is
       rooted in MCP_Repl itself (the session's own base theory) rather
       than the with_repl default, exactly so the self-extension step
       below can use the ordinary Isar declaration syntax. */
    with_repl("ScopeSelf", theories = List("MCP-HOL.MCP_Repl")) {
      expect_ok(
        session.ir("step",
          List("repl" -> "ScopeSelf",
            "isar_text" -> "mcp_tool scoped_tool = \\<open>String.map Char.toUpper\\<close> (description \\<open>uppercase\\<close>)")),
        "registering scoped_tool via repl_step")

      val handler = new MCP_Server.Handler(session)
      assert_no_error(call_tool_on(handler, "tool_scope_set",
        JSON.Object("context" -> "isabelle://context/repl/ScopeSelf")))

      val tools = get_list(rpc_on(handler, "tools/list"), "result", "tools")
      assert(tools.exists(t => get_string(t, "name") == "scoped_tool"),
        "scoped_tool missing from tools/list: " + tools.toString)

      val reply = call_tool_on(handler, "scoped_tool", JSON.Object("input" -> "hi"))
      assert_no_error(reply)
      assertEquals(result_text(reply), "HI")
    }
  }

  /* Bundles remain an Isabelle/Isar mechanism. The network scope contains
     only a locator; changing the REPL's Isar context changes what that same
     locator resolves to. */
  test("tool_scope bridge: bundle activation comes from Isar text, not a network mutation") {
    with_repl("ScopeBundle", theories = List("MCP-HOL.MCP_Repl")) {
      expect_ok(
        session.ir("step",
          List("repl" -> "ScopeBundle",
            "isar_text" -> "mcp_tool bundle_tool = \\<open>String.map Char.toUpper\\<close> (description \\<open>uppercase\\<close>)")),
        "registering bundle_tool via repl_step")
      expect_ok(
        session.ir("step",
          List("repl" -> "ScopeBundle", "isar_text" -> "declare [[mcp_tools del: bundle_tool]]")),
        "deactivating bundle_tool")
      expect_ok(
        session.ir("step",
          List("repl" -> "ScopeBundle",
            "isar_text" -> "bundle exploration = [[mcp_tools add: bundle_tool]]")),
        "defining the exploration bundle")
      expect_ok(
        session.ir("step",
          List("repl" -> "ScopeBundle",
            "isar_text" -> "context includes exploration begin")),
        "opening the bundle in Isar text")

      val handler = new MCP_Server.Handler(session)
      assert_no_error(
        call_tool_on(handler, "tool_scope_set",
          JSON.Object("context" -> "isabelle://context/repl/ScopeBundle")))

      def tool_names(): List[String] =
        get_list(rpc_on(handler, "tools/list"), "result", "tools").map(get_string(_, "name"))

      assert(tool_names().contains("bundle_tool"),
        "bundle_tool missing from the Isar context: " + tool_names())

      expect_ok(
        session.ir("step", List("repl" -> "ScopeBundle", "isar_text" -> "end")),
        "closing the Isar context")
      assert(!tool_names().contains("bundle_tool"),
        "bundle_tool remained active after leaving the Isar context: " + tool_names())
    }
  }

  /* ASYMMETRIC CALLABILITY (plans/builtin_activation, A5): a del'd
     builtin mirror is unlisted but still callable -- Isabelle/Scala
     dispatches builtin names BEFORE consulting activation (tools/call
     precedence), unlike a del'd ML tool (refused, "Inactive MCP
     tool"). The del here is LOCAL to this repl's context (an ordinary
     [[mcp_tools del: ...]] declaration), so it never touches the
     shared MCP_Tools theory other tests read. */
  test("tool_scope bridge: a del'd builtin mirror is unlisted but stays callable") {
    with_repl("ScopeBuiltinDel", theories = List("MCP-HOL.MCP_Repl")) {
      expect_ok(
        session.ir("step",
          List("repl" -> "ScopeBuiltinDel", "isar_text" -> "declare [[mcp_tools del: repl_list]]")),
        "deactivating the repl_list builtin mirror")

      val handler = new MCP_Server.Handler(session)
      assert_no_error(
        call_tool_on(handler, "tool_scope_set",
          JSON.Object("context" -> "isabelle://context/repl/ScopeBuiltinDel")))

      val tools = get_list(rpc_on(handler, "tools/list"), "result", "tools")
      assert(!tools.exists(t => get_string(t, "name") == "repl_list"),
        "repl_list still listed after del: " + tools.toString)

      val reply = call_tool_on(handler, "repl_list", JSON.Object())
      assert_no_error(reply)
    }
  }

  /* scope_add/scope_remove (plans/scope_add, plans/scope_remove): the
     phase-2 RESOURCE scope against a real headless session -- match
     counting over the real theory universe (D1's structure/deps maps),
     and the load_theory/unload_theory retrofit (T4). Each test uses its
     own patterns/fixture names so they don't interfere with each other
     via the session-wide scope state. */

  /* the session is shared across every test in this class (beforeAll
     starts it once) -- and so is scope state, unlike a fresh
     Fake_Backend per call. Each test below removes what it added, the
     same discipline with_repl's finally-teardown uses for repls. */
  test("scope_add#T2: match counts are computed against the real theory universe (image tier)") {
    val handler = new MCP_Server.Handler(session)
    try {
      val text =
        result_text(call_tool_on(handler, "scope_add", JSON.Object("patterns" -> List("HOL.Wellf*"))))
      assert(text.contains("HOL.Wellf*: added ("), "unexpected scope_add reply: " + text)
      assert(!text.contains("(0 theories match)"),
        "HOL.Wellf* should match at least HOL.Wellfounded in the real image: " + text)
    }
    finally call_tool_on(handler, "scope_remove", JSON.Object("patterns" -> List("HOL.Wellf*")))
  }

  test("scope_add zero-match pattern against the real universe is pinned, not an error") {
    val handler = new MCP_Server.Handler(session)
    val reply =
      call_tool_on(handler, "scope_add", JSON.Object("patterns" -> List("NoSuchScopeBridgeThy.*")))
    assert_no_error(reply)
    assert(result_text(reply).contains("NoSuchScopeBridgeThy.*: added (0 theories match)"),
      "unexpected scope_add reply: " + result_text(reply))
  }

  /* T4 (plans/scope_add / plans/unload_theory): load_theory auto-adds a
     filesystem-tier fixture to the resources/list working set, tagged
     loaded; unload_theory removes it again. */
  spec_test("bridge: load_theory auto-adds to resources/list; unload_theory removes it",
      covers = List("scope_add#T4")) {
    with_fixture_dir("ScopeBridgeLoad" -> wave2_theory("ScopeBridgeLoad", wave2_good)) { dir =>
      val handler = new MCP_Server.Handler(session)
      expect_ok(session.load_theory("ScopeBridgeLoad", File.standard_path(dir)), "load")

      val listed = get_list(rpc_on(handler, "resources/list"), "result", "resources")
      val entry = listed.find(r => get_string(r, "uri") == "isabelle://theory/ScopeBridgeLoad")
        .getOrElse(fail("ScopeBridgeLoad missing from resources/list: " + listed.toString))
      assertEquals(get_string(entry, "description"), "theory (loaded)")

      expect_ok(session.unload_theory("ScopeBridgeLoad"), "unload")
      val after_unload = get_list(rpc_on(handler, "resources/list"), "result", "resources")
      assert(!after_unload.exists(r => get_string(r, "uri") == "isabelle://theory/ScopeBridgeLoad"),
        "ScopeBridgeLoad should be gone from resources/list after unload: " + after_unload.toString)
    }
  }

  /* T5 (plans/scope_add): full chain -- scope_add, resources/list
     reflects it, list_changed fires. */
  test("scope_add#T5 bridge: scope_add's pattern match appears in resources/list and fires list_changed") {
    val handler = new MCP_Server.Handler(session)
    var seen: List[String] = Nil
    session.set_changed_handler(seen ::= _)
    assert_no_error(
      call_tool_on(handler, "scope_add", JSON.Object("patterns" -> List("HOL.Wellfounded"))))
    assertEquals(seen, List("resources"), "scope_add should fire exactly one resources notification")

    val listed = get_list(rpc_on(handler, "resources/list"), "result", "resources")
    val entry = listed.find(r => get_string(r, "uri") == "isabelle://theory/HOL.Wellfounded")
      .getOrElse(fail("HOL.Wellfounded missing from resources/list: " + listed.toString))
    assertEquals(get_string(entry, "description"), "theory (image)")

    seen = Nil
    assert_no_error(
      call_tool_on(handler, "scope_remove", JSON.Object("patterns" -> List("HOL.Wellfounded"))))
    assertEquals(seen, List("resources"), "scope_remove should fire exactly one resources notification")
    val after_remove = get_list(rpc_on(handler, "resources/list"), "result", "resources")
    assert(!after_remove.exists(r => get_string(r, "uri") == "isabelle://theory/HOL.Wellfounded"),
      "HOL.Wellfounded should be delisted after scope_remove: " + after_remove.toString)
  }

  /* T2 (plans/scope_show): a real repl (via with_repl) and a real
     load_theory fixture both show up in scope_show against a live
     session -- the one thing Fake_Backend's scala-unit T2 cases
     (Fake_Backend.active_repls, a settable stand-in) can't cover:
     active_repl_ids() actually parsing session.ir("repls", Nil)'s real
     text output. Removal of both makes them disappear again. */
  spec_test("bridge: a real repl and a real load_theory both appear, and disappear on removal",
      covers = List("scope_show#T2")) {
    val handler = new MCP_Server.Handler(session)
    with_repl("ScopeShowRepl") {
      val with_repl_text = result_text(call_tool_on(handler, "scope_show", JSON.Object()))
      assert(with_repl_text.contains("repls:") && with_repl_text.contains("  ScopeShowRepl"),
        "the live repl should be listed by scope_show: " + with_repl_text)

      with_fixture_dir("ScopeShowLoad" -> wave2_theory("ScopeShowLoad", wave2_good)) { dir =>
        expect_ok(session.load_theory("ScopeShowLoad", File.standard_path(dir)), "load")
        val both = result_text(call_tool_on(handler, "scope_show", JSON.Object()))
        assert(both.contains("  ScopeShowLoad (loaded)"),
          "the loaded theory should be listed, tier-tagged: " + both)
        assert(both.contains("  ScopeShowRepl"), "the repl should still be listed: " + both)

        expect_ok(session.unload_theory("ScopeShowLoad"), "unload")
        val after_unload = result_text(call_tool_on(handler, "scope_show", JSON.Object()))
        assert(!after_unload.contains("ScopeShowLoad"),
          "unload_theory should remove it from scope_show: " + after_unload)
      }
    }
    val after_remove = result_text(call_tool_on(handler, "scope_show", JSON.Object()))
    assert(!after_remove.contains("ScopeShowRepl"),
      "repl_remove (with_repl's teardown) should remove it from scope_show: " + after_remove)
  }

  /* repl_init_from_source (plans/repl_init_from_source): T3, T4, T5.
     T1 (exactly-one-locator) and T2 (the pure resolver) are covered
     scala-unit only (mcp_handler_tests.scala's dispatch tests and
     MCP_Locator_Tests) -- everything below needs a live PIDE session or
     this suite's own -T theory's recorded segments. */

  /* T3: Ir.init_from_document requires the command to be executed and
     finished. load_theory is synchronous (use_theories blocks until the
     whole document is checked), so every command our own resolver can
     ever pick is already finished -- there is no public way to reach a
     genuinely "still being evaluated" command through session.
     init_from_source. What IS reachable, and what this pins instead, is
     the sibling guard one layer down: an inaccessible command_id (never
     assigned in this node at all) is caught by init_from_document's own
     Exn.capture branch and reported as a clean status error, not a
     hang or an uncaught exception -- extracting a real node_name from a
     successful attach (the engine echoes it back in "from document ...
     command N") rather than guessing Isabelle's node-naming convention. */
  spec_test("an inaccessible command_id is a status error, not a hang",
      covers = List("repl_init_from_source#T3")) {
    with_fixture_dir(
      "InitFromSrcT3" -> wave2_theory("InitFromSrcT3", "lemma init_from_src_t3: \"True\" by simp")
    ) { dir =>
      expect_ok(session.load_theory("InitFromSrcT3", File.standard_path(dir)), "load")
      val created =
        expect_ok(
          session.init_from_source("T3a", "InitFromSrcT3", None, Some("lemma init_from_src_t3"), None),
          "attach by pattern")
      expect_ok(session.ir("remove", List("repl" -> "T3a")))

      val node_name =
        """from document "([^"]+)" command""".r.findFirstMatchIn(created)
          .getOrElse(fail("could not extract node_name from create reply: " + created)).group(1)

      expect_error(
        session.ir("init_from_document",
          List("repl" -> "T3b", "node_name" -> node_name, "command_id" -> "0")),
        containing = "Cannot access command")
    }
  }

  /* T4: the image-tier segment fallback (MCP_Repl.thy's
     init_from_segment) against this suite's own -T theory, whose
     segments were recorded at build time (record_theories, tier_image
     above) and DO survive into this live process (the retracted-gap
     test above). offset 0 always lands in segment 0 regardless of its
     exact text, so it needs no fragile byte-offset arithmetic; pattern
     targets a command known to appear exactly once. */
  spec_test("image-tier segment fallback resolves offset/pattern/index against recorded segments",
      covers = List("repl_init_from_source#T4")) {
    val by_pattern =
      expect_ok(
        session.init_from_source("SegPat", "MCP_Repl", None, Some("Ir.set_self_theory"), None),
        "attach by pattern to a segment")
    assert(by_pattern.contains("Created REPL"), "unexpected reply: " + by_pattern)
    expect_ok(session.ir("step", List("repl" -> "SegPat", "isar_text" -> "lemma segpat_t4: True")))
    expect_ok(session.ir("step", List("repl" -> "SegPat", "isar_text" -> "by simp")))
    val listing = repl_listing()
    assert(listing.contains("SegPat"), "SegPat missing from repl_list: " + listing)
    expect_ok(session.ir("remove", List("repl" -> "SegPat")))

    expect_ok(session.init_from_source("SegIdx", "MCP_Repl", None, None, Some(0)), "attach by index 0")
    expect_ok(session.ir("remove", List("repl" -> "SegIdx")))

    expect_ok(session.init_from_source("SegOff", "MCP_Repl", Some(0), None, None), "attach by offset 0")
    expect_ok(session.ir("remove", List("repl" -> "SegOff")))

    expect_error(
      session.init_from_source("SegBad", "MCP_Repl", None, Some("no_such_segment_text_xyz"), None),
      containing = "not found")
  }

  /* T5: full chain over the PIDE-snapshot (loaded-tier) branch -- load
     a fixture whose lemma and its proof are separate commands, attach
     by pattern to the lemma statement (AFTER semantics: the open-goal
     state right after that command, before its own on-disk "by"), step
     the SAME closing tactic through the fresh REPL, and confirm it is
     listed, steppable, its text readable, and removable. */
  spec_test("e2e attach-by-pattern on a loaded theory, step, text, remove",
      covers = List("repl_init_from_source#T5")) {
    with_fixture_dir(
      "InitFromSrcT5" ->
        wave2_theory("InitFromSrcT5",
          "definition init_src_t5_const :: nat where \"init_src_t5_const = 1\"\n\n" +
          "lemma init_src_t5_lemma: \"init_src_t5_const = 1\"\n" +
          "  by (simp add: init_src_t5_const_def)")
    ) { dir =>
      expect_ok(session.load_theory("InitFromSrcT5", File.standard_path(dir)), "load")

      val created =
        expect_ok(
          session.init_from_source("T5", "InitFromSrcT5", None, Some("lemma init_src_t5_lemma"), None),
          "attach by pattern")
      assert(created.contains("Created REPL") && created.contains("[proof]"),
        "attach should land right after the lemma statement, mid-proof: " + created)

      expect_ok(
        session.ir("step",
          List("repl" -> "T5", "isar_text" -> "by (simp add: init_src_t5_const_def)")),
        "closing the goal from the attach point")

      val listing = repl_listing()
      assert(listing.contains("T5"), "T5 missing from repl_list: " + listing)

      val text = expect_ok(session.ir("text", List("repl" -> "T5")))
      assert(text.contains("by (simp"), "repl_text should include the step: " + text)

      expect_ok(session.ir("remove", List("repl" -> "T5")))
    }
  }

  /* plans/ml_builtin_migration wave 1: repl_show/repl_text/repl_back move
     from Builtin_Tool rows + dispatcher cases to capture-form mcp_tools
     declared in MCP_Repl.thy. This is the wave's one bridge exemplar
     (per the plan's "test migration": one exemplar per wave, not one per
     tool) -- tools/list shows the moved names with the right schema and
     annotations, and tools/call repl_show round-trips against a live
     repl with no yxml markup in the result (A3). repl_show/repl_text
     ALSO keep a surviving MCP.ir dispatcher case above (resource reads);
     this exercises the NEW mcp_tool entry point instead, over
     MCP.run_tool. */
  test("wave 1: tools/list shows repl_show/repl_text/repl_back as capture tools with their {repl} schema and annotations") {
    val rows = session.ml_tools().rows
    def row(name: String): MCP_Session.Tool_Row =
      rows.find(_.name == "MCP_Repl." + name)
        .getOrElse(fail("MCP_Repl." + name + " not in ml_tools: " + rows.map(_.name)))

    val show = row("repl_show")
    val text = row("repl_text")
    val back = row("repl_back")

    for (r <- List(show, text, back)) {
      assertEquals(r.form, "capture")
      assertEquals(r.params.map(p => (p.name, p.typ, p.required)),
        List(("repl", MCP_Session.Ptyp_String, true)))
    }
    assertEquals(show.annotations.read_only, Some(true))
    assertEquals(text.annotations.read_only, Some(true))
    assertEquals(back.annotations.destructive, Some(true))
  }

  test("wave 1: tools/call repl_show round-trips against a live repl with no yxml markup") {
    with_repl("Wave1Show") {
      expect_ok(session.ir("step", List("repl" -> "Wave1Show", "isar_text" -> "lemma True")),
        "step on Wave1Show")

      val result = session.ml_run("MCP_Repl.repl_show", List("repl" -> "Wave1Show"))
      val text = expect_ok(result, "repl_show on Wave1Show")
      assert(text.contains("Wave1Show"), "repl_show output missing the repl id: " + text)
      assert(!text.exists(c => c < ' ' && c != '\n' && c != '\t' && c != '\r'),
        "repl_show output must contain no yxml control characters: " + text)
    }
  }
}


/* run_tool async (plans/ml_builtin_migration step 5, A4/A5): the same
   two-future shape as MCP.ir above, over the capture-form test tools
   declared in MCP_Tools_Tests.thy (MCP-Tools has no genuinely slow tool of
   its own to exercise this with). A6's own bridge case -- two capture
   tools concurrently against two DIFFERENT repls -- needs a real
   repl-designated capture tool, which does not exist until wave 1 lands
   (and wave 1 was blocked on the S1 context decision); this suite
   is the adjacent claim available today: two DIFFERENT capture tools
   running concurrently under one context do not cross outputs. */
class MCP_Run_Tool_Async_Tests
  extends MCP_Session_Suite(
    "MCP-Tools-Tests", "MCP_Tools_Tests", McpBridgeProfile.base) {

  /* These test tools live in the test theory, so select its explicit context
     rather than the connection's MCP_Tools registry-root context. */
  lazy val test_theory: String =
    "isabelle://context/theory/" +
      session.ml_theories().find(n => Long_Name.base_name(n) == "MCP_Tools_Tests")
        .getOrElse(fail("MCP_Tools_Tests not in ml_theories"))

  spec_test("single bridge registry correlates a fast reply before an earlier slow reply",
      covers = List("pide_bridge#T1", "pide_bridge#T8")) {
    val slow = Future.fork(
      session.ml_read_resource("MCP_Tools_Tests.slow_resource", test_theory))
    Thread.sleep(100)
    assert(!slow.is_finished, "slow resource completed before the fast call")

    val fast = session.ml_read_resource("MCP_Tools_Tests.test_collection", test_theory)
    assert(fast.ok, "fast resource failed while the earlier call remained pending: " + fast)
    assert(!slow.is_finished, "earlier slow call completed before the later fast reply")
    assert(slow.join.ok, "slow resource did not receive its own eventual reply")
  }

  spec_test("bridge routes correlate concurrent distinguishable catalogs and resources",
      covers = List("connection_kernel#T11")) {
    /* Keep this correlation probe at the configured default maxPending (8).
       Immediate overload beyond the bound has its own deterministic test. */
    val probes: List[(String, () => Boolean)] = List(
      "valid tools" -> (() =>
        session.ml_tools(test_theory).rows.exists(_.name == "MCP_Tools_Tests.capture_ok")),
      "invalid tools" -> (() => session.ml_tools("No_Such_Theory").rows.isEmpty),
      "valid resources" -> (() =>
        session.ml_named_resources(test_theory)
          .exists(_._1 == "MCP_Tools_Tests.slow_resource")),
      "invalid resources" -> (() => session.ml_named_resources("No_Such_Theory").isEmpty),
      "valid context" -> (() =>
        session.check_context(test_theory).isInstanceOf[MCP_Session.Ok]),
      "invalid context" -> (() =>
        session.check_context("isabelle://context/theory/No_Such_Theory")
          .isInstanceOf[MCP_Session.Error]),
      "valid resource read" -> (() =>
        session.ml_read_resource("MCP_Tools_Tests.test_collection", test_theory).ok),
      "invalid resource read" -> (() =>
        !session.ml_read_resource("MCP_Tools_Tests.no_such_resource", test_theory).ok))

    val ready = new CountDownLatch(probes.length)
    val release = new CountDownLatch(1)
    val results = probes.zipWithIndex.map { case ((label, probe), index) =>
      label -> Future.thread(name = "bridge-route-probe-" + index, daemon = true) {
        ready.countDown()
        if (!release.await(5, TimeUnit.SECONDS)) error("bridge route start barrier timed out")
        probe()
      }
    }
    assert(ready.await(5, TimeUnit.SECONDS), "concurrent bridge probes did not reach barrier")
    release.countDown()
    results.foreach { case (label, result) =>
      assert(result.join, label + " received another request's bridge reply")
    }
  }

  spec_test("named-resource bridge cancellation returns promptly and remains usable",
      covers = List("connection_kernel#T11", "connection_kernel#T4")) {
    val registry = new RequestRegistry(RequestRegistry.InvariantViolationPolicy.FailFast)
    val requestId = RequestId.string("live-resource-cancel")
    val admitted = registry.admit(requestId, RequestRegistry.AdmissionKind.Ordinary) match {
      case value: RequestRegistry.Admitted => value
      case other => fail("could not admit live resource cancellation fixture: " + other)
    }
    val slow = Future.fork(session.ml_read_resource_cancellable(
      "MCP_Tools_Tests.slow_resource", test_theory, admitted.cancellation))
    Thread.sleep(100)
    assert(!slow.is_finished, "slow resource fixture completed before cancellation")

    val before = Time.now()
    assert(registry.cancel(requestId, Some("bridge fixture"))
      .isInstanceOf[RequestRegistry.Cancelled])
    expect_error(slow.join, containing = "cancelled")
    assert(Time.now() - before < Time.seconds(1.0),
      "Scala resource bridge promise did not return promptly after cancellation")
    assertEquals(
      session.ml_read_resource("MCP_Tools_Tests.test_collection", test_theory).ok,
      true,
      "resource bridge was unusable after cancellation")
  }

  spec_test("direct Scala backend work is interrupted without poisoning the reused worker",
      covers = List("connection_kernel#T4")) {
    val registry = new RequestRegistry(RequestRegistry.InvariantViolationPolicy.FailFast)
    val requestId = RequestId.string("live-direct-cancel")
    val admitted = registry.admit(requestId, RequestRegistry.AdmissionKind.Ordinary) match {
      case value: RequestRegistry.Admitted => value
      case other => fail("could not admit direct cancellation fixture: " + other)
    }
    val slow = Future.fork(session.direct_cancellable(admitted.cancellation) {
      Thread.sleep(5000)
      "unexpected completion"
    })
    Thread.sleep(100)
    assert(!slow.is_finished, "slow direct fixture completed before cancellation")

    val before = Time.now()
    assert(registry.cancel(requestId, Some("direct fixture"))
      .isInstanceOf[RequestRegistry.Cancelled])
    assert(Exn.is_exn(slow.join_result), "cancelled direct work returned normally")
    assert(Time.now() - before < Time.seconds(1.0),
      "direct Scala work did not return promptly after cancellation")
    assertEquals(
      session.direct_cancellable(McpApplication.Cancellation.Never) { "usable" },
      "usable",
      "direct cancellation poisoned later worker work")
  }

  spec_test("live connection kernel cancels a named-resource read and remains usable",
      covers = List("connection_kernel#T4", "connection_kernel#T11")) {
    def checked[A](value: Either[String, A]): A = value.fold(error, identity)
    val policy = ConnectionPolicy(
      revision = ProtocolRevision.V2025_03_26,
      input = checked(McpInputPolicy.checked(1048576)),
      admission = ConnectionPolicy.AdmissionPolicy(
        checked(ConnectionPolicy.MaxInFlight.checked(1))),
      timing = ConnectionPolicy.TimingPolicy(
        checked(ConnectionPolicy.RequestTimeout.checked(10.0)),
        checked(ConnectionPolicy.ShutdownDrain.checked(1.0))))
    val plane = new ScriptedDataPlane(Nil)
    val rules = new Mcp2025RevisionRules
    val registry = new RequestRegistry(RequestRegistry.InvariantViolationPolicy.FailFast)
    val testTheory = test_theory
    val backend = new Fake_Backend {
      override def mcp_resource_read_cancellable(uri: String,
          cancellation: McpApplication.Cancellation): MCP_Session.Result = {
        val name =
          if (uri.endsWith("/slow_resource")) "MCP_Tools_Tests.slow_resource"
          else "MCP_Tools_Tests.test_collection"
        session.ml_read_resource_cancellable(name, testTheory, cancellation)
      }
    }
    val application = McpApplication.isabelle(
      () => McpApplication.Ready(backend), "MCP-Tools-Tests", Nil, "MCP_Tools_Tests",
      McpOutputPolicy.TestDefault)
    val kernel = ConnectionKernel(
      policy = policy,
      dataPlane = plane,
      revisionRules = rules,
      scheduler = new BoundedConcurrentScheduler(1, "live-resource-kernel"),
      deadlineScheduler = new ManualDeadlineScheduler,
      registry = registry,
      application = application,
      serverInfo = ConnectionKernel.ServerInfo("test", "test"))

    def request(id: Option[String], method: String,
        params: Option[JSON.Object.T] = None): RevisionRules.Message = {
      var json = JSON.Object("jsonrpc" -> "2.0", "method" -> method)
      id.foreach(value => json += ("id" -> value))
      params.foreach(value => json += ("params" -> value))
      rules.classify(JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(json)))
    }

    try {
      kernel.handle(request(Some("init"), "initialize",
        Some(JSON.Object("protocolVersion" -> ProtocolRevision.V2025_03_26.value))))
      kernel.handle(request(None, "notifications/initialized"))
      kernel.handle(request(Some("slow"), "resources/read",
        Some(JSON.Object("uri" -> "isabelle://named/slow_resource"))))
      Thread.sleep(100)
      kernel.handle(request(None, "notifications/cancelled",
        Some(JSON.Object("requestId" -> "slow", "reason" -> "bridge fixture"))))
      eventually("cancelled resource worker retained connection capacity", Time.seconds(2.0)) {
        registry.snapshot.activeIds.isEmpty
      }

      kernel.handle(request(Some("fast"), "resources/read",
        Some(JSON.Object("uri" -> "isabelle://named/test_collection"))))
      eventually("connection did not answer after resource cancellation", Time.seconds(2.0)) {
        plane.written.exists(line =>
          JSON.Format.unapply(line).flatMap(JSON.string(_, "id")).contains("fast"))
      }
      assert(!plane.written.exists(line =>
        JSON.Format.unapply(line).flatMap(JSON.string(_, "id")).contains("slow")),
        "client-cancelled resource read emitted a response: " + plane.written.mkString(" | "))
    }
    finally kernel.drainAndClose()
  }

  def run(name: String, args: List[(String, String)] = Nil): MCP_Session.Result =
    session.ml_run("MCP_Tools_Tests." + name, args, test_theory)

  spec_test("run-tool bridge cancellation unblocks Scala and leaves later calls usable",
      covers = List("connection_kernel#T4")) {
    val registry = new RequestRegistry(RequestRegistry.InvariantViolationPolicy.FailFast)
    val requestId = RequestId.string("live-run-tool-cancel")
    val admitted = registry.admit(requestId, RequestRegistry.AdmissionKind.Ordinary) match {
      case value: RequestRegistry.Admitted => value
      case other => fail("could not admit live run-tool cancellation fixture: " + other)
    }
    val slow = Future.fork(session.ml_run_cancellable(
      "MCP_Tools_Tests.capture_slow", Nil, test_theory, admitted.cancellation))
    assertEquals(run("capture_ok", List("x" -> "before-cancel")),
      MCP_Session.Ok("got:before-cancel"))
    assert(!slow.is_finished, "slow run-tool fixture completed before cancellation")

    val before = Time.now()
    assert(registry.cancel(requestId, Some("bridge fixture"))
      .isInstanceOf[RequestRegistry.Cancelled])
    expect_error(slow.join, containing = "cancelled")
    assert(Time.now() - before < Time.seconds(1.0),
      "Scala run-tool bridge promise did not return promptly after cancellation")
    assertEquals(run("capture_ok", List("x" -> "after-cancel")),
      MCP_Session.Ok("got:after-cancel"))
  }

  test("bridge: run_tool async -- a slow capture tool does not block a concurrent fast one") {
    val slow = Future.fork(run("capture_slow"))
    val fast = run("capture_ok", List("x" -> "hi"))

    assertEquals(fast, MCP_Session.Ok("got:hi"))
    assert(!slow.is_finished, "the slow capture tool finished before the fast reply arrived")

    val slow_result = slow.join
    assertEquals(slow_result, MCP_Session.Ok("slow done"))
  }

  spec_test("bridge: run_tool async -- concurrent capture tools do not cross outputs",
      verifies = List("ml_builtin_migration#A5", "ml_builtin_migration#A6")) {
    val slow = Future.fork(run("capture_slow"))
    val fast = run("capture_ok", List("x" -> "hi"))
    val slow_result = slow.join

    (fast, slow_result) match {
      case (MCP_Session.Ok(fast_text), MCP_Session.Ok(slow_text)) =>
        assert(!fast_text.contains("slow done"), "fast result leaked the slow tool's output")
        assert(!slow_text.contains("got:hi"), "slow result leaked the fast tool's output")
      case other => fail("expected both calls to succeed: " + other.toString)
    }
  }

  test("bridge: run_tool async -- an erroring slow call still resolves its promise") {
    val slow = Future.fork(run("capture_err_only"))
    val fast = run("capture_ok", List("x" -> "hi"))

    assertEquals(fast, MCP_Session.Ok("got:hi"))
    expect_error(slow.join, containing = "silent boom")
  }
}


class MCP_Bridge_Shutdown_Tests
  extends MCP_Session_Suite(
    "MCP-Tools-Tests", "MCP_Tools_Tests", McpBridgeProfile.base) {
  private var stopped = false

  override def afterAll(): Unit = if (!stopped) super.afterAll()

  spec_test("backend stop cancels every pending bridge and joins direct Scala work",
      covers = List("connection_kernel#T4", "connection_kernel#T11", "connection_kernel#T12")) {
    val testTheory =
      "isabelle://context/theory/" +
        session.ml_theories().find(n => Long_Name.base_name(n) == "MCP_Tools_Tests")
          .getOrElse(fail("MCP_Tools_Tests not in ml_theories"))
    val resource = Future.fork(session.ml_read_resource_cancellable(
      "MCP_Tools_Tests.slow_resource", testTheory, McpApplication.Cancellation.Never))
    val direct = Future.fork(session.direct_cancellable(McpApplication.Cancellation.Never) {
      Thread.sleep(5000)
      "unexpected completion"
    })
    Thread.sleep(100)
    assert(!resource.is_finished && !direct.is_finished,
      "shutdown fixtures completed before backend stop")

    session.stop()
    stopped = true
    assert(resource.is_finished && direct.is_finished,
      "backend stop returned before pending work terminated")
    expect_error(resource.join, containing = "session stopped")
    assert(Exn.is_exn(direct.join_result), "direct work escaped backend stop")
  }
}


class MCP_Bounded_Output_Bridge_Tests
  extends MCP_Session_Suite(
    "MCP-HOL", "MCP_Repl", McpBridgeProfile.hol,
    options => options + "mcp_untrusted_output_bytes=64") {

  spec_test("live bridge inherits the per-call ML output budget",
      covers = List("pide_bridge#T13")) {
    with_repl("BoundedOutput") {
      session.ml_run("MCP_Repl.repl_show", List("repl" -> "BoundedOutput")) match {
        case MCP_Session.Ok(output) =>
          assert(output.nonEmpty, "positive output budget retained nothing")
          assert(output.length <= 64, "output exceeded configured bound: " + output.length)
        case other => fail("bounded output tool failed: " + other)
      }
    }
  }
}
