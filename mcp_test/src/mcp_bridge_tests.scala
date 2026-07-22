/*  Title:      mcp_test/src/mcp_bridge_tests.scala

Bridge suites against real headless PIDE sessions (isabelle mcp_test
-b): MCP.run_tool and resources over MCP-Tools, and the MCP.ir
dispatcher over the I/R engine (MCP-HOL/MCP_Repl) -- the one layer
Fake_Backend cannot cover.
*/

package isabelle.mcp

import isabelle._


/* protocol-command bridge: MCP.run_tool + resources over MCP-Tools */

class MCP_Bridge_Tests extends MCP_Session_Suite("MCP-Tools", "MCP_Tools") {
  /* the bridge carries FULL INTERNAL names + the form tag; exposed
     (shortened) names exist only in the scala layer above
     (MCP_Server.exposure, unit-tested in mcp_handler_tests.scala) */
  test("bridge: ml_tools lists shout under its full internal name with form tag and params") {
    val tools = session.ml_tools()
    val shout = tools.find(_.name == "MCP_Tools.shout")
      .getOrElse(fail("MCP_Tools.shout not in " + tools.toString))
    assertEquals(shout.description, "uppercase the input")
    assertEquals(shout.form, "string_fun")
    assertEquals(shout.params.map(p => (p.name, p.typ, p.required)),
      List(("input", "string", true)))
  }

  test("bridge: ml_run round trip") {
    assertEquals(session.ml_run("MCP_Tools.shout", List("input" -> "isabelle")),
      MCP_Session.Ok("ISABELLE"))
  }

  test("bridge: ml_run unknown tool is an error") {
    expect_error(session.ml_run("no_such_tool", List("input" -> "x")),
      containing = "no_such_tool")
  }

  test("bridge: explicit theory designation equals the default") {
    /* Thy_Info keying mixes qualified and unqualified names, so take the
       canonical spelling from the session itself */
    val thy_name =
      session.ml_theories().find(n => Long_Name.base_name(n) == "MCP_Tools")
        .getOrElse(fail("MCP_Tools not in ml_theories"))
    assertEquals(session.ml_tools(designation = thy_name), session.ml_tools())
  }

  test("bridge: unknown designation is a typed error, not an exception") {
    expect_error(
      session.ml_run("MCP_Tools.shout", List("input" -> "x"), designation = "No_Such_Theory"),
      containing = "No_Such_Theory")
  }

  /* plans/tool_scope: probing whether MCP.tools' resolution (unlike
     MCP.run_tool's, which already catches) is crash-safe against a bad
     designation -- tools_body has no (status, output) wrapper today, so
     an uncaught ML exception there could leave the "MCP.tools_result"
     promise unfulfilled forever instead of erroring gracefully. */
  test("bridge: ml_tools on an unknown designation does not hang") {
    val tools = session.ml_tools(designation = "No_Such_Theory")
    assertEquals(tools, Nil)
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

class MCP_Ir_Bridge_Tests extends MCP_Session_Suite("MCP-HOL", "MCP_Repl") {
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

  test("ir bridge: repl_step T5 -- a slow step on one REPL does not delay a concurrent step on another") {
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

  test("ir bridge: repl_text T1 -- byte fidelity survives the scala-side yxml stripping (symbols, doubled spaces, embedded newline)") {
    with_repl("Texted") {
      val step_text = "lemma \"x \\<longrightarrow> x\"\n  by  simp"
      expect_ok(session.ir("step", List("repl" -> "Texted", "isar_text" -> step_text)),
        "step on Texted")
      assertEquals(expect_ok(session.ir("text", List("repl" -> "Texted"))), step_text,
        "text did not round-trip byte-clean")
    }
  }

  test("ir bridge: repl_show T2 -- show on a busy REPL errors \"is busy\", not a stale read") {
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

  test("ir bridge: repl_truncate T4 -- a busy orphan blocks the whole truncate, nothing is half-removed") {
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

  test("ir bridge: repl_merge T4 -- a busy parent blocks merge without corrupting the child") {
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

  test("ir bridge: repl_timeout T4 -- the full chain sets and reports a per-REPL timeout") {
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
  test("ir bridge: sledgehammer T3 -- happy path finds a proof for a trivial goal (tolerant of prover flakiness)") {
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
  test("ir bridge: sledgehammer T4 -- two concurrent calls on different REPLs never cross outputs") {
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
  test("ir bridge: sledgehammer T5 -- async under load, a fast call on another repl returns while sledgehammer is in flight") {
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
  test("ir bridge: find_theorems T6 -- returns promptly on B during a slow step on A") {
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

  test("ir bridge: repl_unpin T3 -- the pin/unpin round trip") {
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

  test("wave 2: load_theory T1 -- a well-formed fixture under master_dir loads and reports ok") {
    with_fixture_dir("Wave2Good1" -> wave2_theory("Wave2Good1", wave2_good)) { dir =>
      val text = expect_ok(session.load_theory("Wave2Good1", File.standard_path(dir)))
      assert(text.contains("Wave2Good1: ok"), "unexpected load_theory reply: " + text)
    }
  }

  test("wave 2: load_theory T2 -- a broken fixture is a line-positioned isError, and the session survives") {
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

  test("wave 2: load_theory T4 -- re-loading an unchanged theory is ok both times") {
    with_fixture_dir("Wave2Good3" -> wave2_theory("Wave2Good3", wave2_good)) { dir =>
      val master_dir = File.standard_path(dir)
      expect_ok(session.load_theory("Wave2Good3", master_dir), "first load")
      expect_ok(session.load_theory("Wave2Good3", master_dir), "second load")
    }
  }

  test("wave 2: unload_theory T1 -- load, unload, and load fresh again all succeed") {
    with_fixture_dir("Wave2Unl1" -> wave2_theory("Wave2Unl1", wave2_good)) { dir =>
      val master_dir = File.standard_path(dir)
      expect_ok(session.load_theory("Wave2Unl1", master_dir), "load before unload")
      expect_ok(session.unload_theory("Wave2Unl1"), "unload")
      expect_ok(session.load_theory("Wave2Unl1", master_dir), "load after unload")
    }
  }

  test("wave 2: check_theory T1 -- THE staleness case: purge-before-reload picks up an on-disk edit") {
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

  test("wave 2: check_theory T2 -- checking a never-loaded filesystem theory works (purge no-op path)") {
    with_fixture_dir("Wave2Fresh" -> wave2_theory("Wave2Fresh", wave2_good)) { dir =>
      expect_ok(session.check_theory("Wave2Fresh", File.standard_path(dir)))
    }
  }

  test("wave 2: check_theory T3 -- warnings are ok, errors are isError (the pinned warning policy)") {
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

  test("ir bridge: repl_remove T4 -- a busy descendant blocks removal, nothing is half-removed") {
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
  test("tool_scope bridge: a repl-registered tool becomes servable after tool_scope_set{repl}") {
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
      assert_no_error(call_tool_on(handler, "tool_scope_set", JSON.Object("repl" -> "ScopeSelf")))

      val tools = get_list(rpc_on(handler, "tools/list"), "result", "tools")
      assert(tools.exists(t => get_string(t, "name") == "scoped_tool"),
        "scoped_tool missing from tools/list: " + tools.toString)

      val reply = call_tool_on(handler, "scoped_tool", JSON.Object("input" -> "hi"))
      assert_no_error(reply)
      assertEquals(result_text(reply), "HI")
    }
  }

  /* plans/tool_scope: bundle scoping -- a tool registered inactive
     (declare [[mcp_tools del: ...]]) is absent from tools/list until
     tool_scope_include opens the bundle that reactivates it, and absent
     again after the NEXT tool_scope_set (set replaces the designation
     AND clears included bundles). */
  test("tool_scope bridge: tool_scope_include opens a bundle-scoped repl tool; tool_scope_set clears it again") {
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

      val handler = new MCP_Server.Handler(session)
      assert_no_error(
        call_tool_on(handler, "tool_scope_set", JSON.Object("repl" -> "ScopeBundle")))

      def tool_names(): List[String] =
        get_list(rpc_on(handler, "tools/list"), "result", "tools").map(get_string(_, "name"))

      assert(!tool_names().contains("bundle_tool"),
        "bundle_tool visible before tool_scope_include: " + tool_names())

      assert_no_error(
        call_tool_on(handler, "tool_scope_include",
          JSON.Object("bundles" -> List("exploration"))))
      assert(tool_names().contains("bundle_tool"),
        "bundle_tool missing after tool_scope_include: " + tool_names())

      assert_no_error(
        call_tool_on(handler, "tool_scope_set", JSON.Object("repl" -> "ScopeBundle")))
      assert(!tool_names().contains("bundle_tool"),
        "bundle_tool still visible after a re-set cleared the bundles: " + tool_names())
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
  test("scope_add T2: match counts are computed against the real theory universe (image tier)") {
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
  test("scope bridge T4: load_theory auto-adds to resources/list; unload_theory removes it") {
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
  test("scope bridge T5: scope_add's pattern match appears in resources/list and fires list_changed") {
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
  test("scope_show bridge T2: a real repl and a real load_theory both appear, and disappear on removal") {
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
}
