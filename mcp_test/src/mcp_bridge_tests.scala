/*  Title:      mcp_test/src/mcp_bridge_tests.scala

Bridge suites against real headless PIDE sessions (isabelle mcp_test
-L bridge): typed operations over Pure and HOL registration foundations -- the layer
Fake_Backend cannot cover.
*/

package isabelle.mcp

import isabelle._
import isabelle.mcp.connection._
import isabelle.mcp.control.ManualDeadlineScheduler
import isabelle.mcp.application.McpApplication
import isabelle.mcp.protocol.JsonRpc
import isabelle.mcp.pide.PideBridgeV1
import isabelle.mcp.transport.ScriptedDataPlane
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
  spec_test("reprocessed ancestor declarations reach the freshly resolved startup root",
      verifies = List("context_locator#A3"), covers = List("context_locator#T6")) {
    val options = MCP_Test_Config.options
    val repositoryDirs = MCP_Test_Config.session_dirs
    MCP_Session.build(options, "MCP-Tools", repositoryDirs, MCP_Test_Config.progress)
    Isabelle_System.with_tmp_dir("mcp-root-reload") { dir =>
      File.write(dir + Path.basic("ROOT"),
        "session CarveReloadFixtures = \"MCP-Tools\" +\n  theories CarveRoot\n")
      def ancestor(version: String): String =
        Symbol.encode(s"""theory CarveAncestor imports "MCP-Tools.MCP_Tools" begin
mcp_tool inherited_probe = ‹K "$version"› (description ‹$version›)
end
""")
      val root = "theory CarveRoot imports CarveAncestor begin\nend\n"
      File.write(dir + Path.basic("CarveAncestor.thy"), ancestor("version-one"))
      File.write(dir + Path.basic("CarveRoot.thy"), root)
      val backend = MCP_Session.boot(options, "MCP-Tools", dir :: repositoryDirs,
        "CarveRoot", McpBridgeProfile.base, MCP_Test_Config.progress)
      try {
        def inherited = backend.ml_tools().rows.find(_.name.endsWith(".inherited_probe"))
          .getOrElse(fail("root catalogue lost the ancestor tool"))
        val before = inherited
        assertEquals(before.description, "version-one")
        assertEquals(backend.ml_run(before.name, List("input" -> "ignored")),
          MCP_Session.Ok("version-one"))
        File.write(dir + Path.basic("CarveAncestor.thy"), ancestor("version-two"))
        assert(backend.check_theory("CarveAncestor", File.standard_path(dir)).ok)
        val after = inherited
        assertEquals(after.description, "version-two")
        assertEquals(backend.ml_run(after.name, List("input" -> "ignored")),
          MCP_Session.Ok("version-two"))
      }
      finally backend.stop()
    }
  }

}


class MCP_Root_Ownership_Tests extends MCP_Suite {
  override def munitTimeout: Duration = 10.minutes

  test("generated root wrapper imports an image theory and owns its temporary files") {
    val resources = Headless.Resources.make(MCP_Test_Config.options, "MCP-Tools",
      session_dirs = MCP_Test_Config.session_dirs, progress = MCP_Test_Config.progress)
    val session = resources.start_session(progress = MCP_Test_Config.progress)
    val imported = Document.Node.Name.loaded_theory("MCP-Tools.MCP_Tools")
    val root = MCP_Session.RootDocument.create(session, imported)
    val other = MCP_Session.RootDocument.create(session, imported)
    val directory = root.node.path.dir
    try {
      assert(root.node.path.is_file)
      assert(root.node.theory != other.node.theory)
      assert(Long_Name.base_name(root.node.theory).matches("MCP_Root_[0-9a-f]{32}"))
      assert(File.read(root.node.path).contains("imports \"MCP-Tools.MCP_Tools\""))
      root.load(MCP_Test_Config.progress)
      resources.clean_theories(session, UUID.random(), List(root.node))
      val (_, retained) = resources.purge_theories(None)
      assert(retained.contains(root.node), "ordinary cleanup removed the wrapper")
      root.release()
      assert(root.node.path.is_file, "release removed files before session termination")
      other.release()
    }
    finally {
      try { root.release(); other.release() }
      finally {
        try session.stop()
        finally { root.dispose(); other.dispose() }
      }
    }
    assert(!directory.is_dir)
    root.dispose()
  }

  test("generated root wrapper imports an exact source path and reprocesses its ancestors") {
    Isabelle_System.with_tmp_dir("mcp-root-source") { dir =>
      val source = dir + Path.basic("WrapperAncestor.thy")
      def text(body: String): String =
        "theory WrapperAncestor imports \"MCP-Tools.MCP_Tools\" begin\n" + body + "\nend\n"
      File.write(source, text(""))
      val resources = Headless.Resources.make(MCP_Test_Config.options, "MCP-Tools",
        session_dirs = MCP_Test_Config.session_dirs, progress = MCP_Test_Config.progress)
      val session = resources.start_session(progress = MCP_Test_Config.progress)
      val imported = resources.import_name(Sessions.DRAFT, File.standard_path(dir), "WrapperAncestor")
      val root = MCP_Session.RootDocument.create(session, imported)
      val directory = root.node.path.dir
      try {
        assert(File.read(root.node.path).contains(Outer_Syntax.quote_string(File.standard_path(source.drop_ext))))
        root.load(MCP_Test_Config.progress)
        resources.clean_theories(session, UUID.random(), List(imported))
        val (_, retained) = resources.purge_theories(None)
        assert(retained.contains(root.node))
        assert(retained.contains(imported), "root did not protect the source ancestor")
        File.write(source, text("ML \\<open>error \"reprocessed ancestor\"\\<close>"))
        intercept[Throwable] { root.load(MCP_Test_Config.progress) }
        assert(root.selector().isLeft, "failed root refresh retained an available selector")
        File.write(source, text(""))
        root.load(MCP_Test_Config.progress)
        assert(root.selector().isRight, "repaired root did not regain a live selector")
      }
      finally {
        try root.release()
        finally {
          try session.stop()
          finally root.dispose()
        }
      }
      assert(!directory.is_dir)
      assert(source.is_file, "wrapper disposal deleted user source")
      root.dispose()
    }
  }

  test("a dedicated root requirement survives ordinary cleanup and releases explicitly") {
    Isabelle_System.with_tmp_dir("mcp-root-owner") { dir =>
      File.write(dir + Path.basic("OwnerAncestor.thy"),
        "theory OwnerAncestor imports \"MCP-Tools.MCP_Tools\" begin end")
      File.write(dir + Path.basic("OwnerRoot.thy"),
        "theory OwnerRoot imports OwnerAncestor begin end")
      val resources = Headless.Resources.make(MCP_Test_Config.options, "MCP-Tools",
        session_dirs = MCP_Test_Config.session_dirs, progress = MCP_Test_Config.progress)
      val session = resources.start_session(progress = MCP_Test_Config.progress)
      val master = File.standard_path(dir)
      val node = resources.import_name(Sessions.DRAFT, session.master_directory(master), "OwnerRoot")
      val root = new MCP_Session.RootDocument(session, node, master)
      try {
        root.load(MCP_Test_Config.progress)
        // Normal clean uses a different owner and cannot remove the root/imports.
        resources.clean_theories(session, UUID.random(), List(node))
        val (purged, retained) = resources.purge_theories(None)
        assertEquals(purged, Nil)
        assert(retained.exists(_.theory.endsWith("OwnerRoot")))
        assert(retained.exists(_.theory.endsWith("OwnerAncestor")))
        root.release()
        root.release()
        // No further document execution follows this state-level probe.
        val (released, _) = resources.purge_theories(None)
        assert(released.contains(node))
      }
      finally {
        try root.release() finally session.stop()
      }
    }
  }
}


/* Common protocol bridge: typed tool operations over MCP-Tools. */

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
      verifies = List("pide_bridge#I1"),
      covers = List("planning_gate#T8", "pide_bridge#T9")) {
    val root = expect_ok(session.root_context())
    assertEquals(session.check_context(root), MCP_Session.Ok(root))
    assertEquals(session.ml_run("MCP_Tools.shout", List("input" -> "base"), root), MCP_Session.Ok("BASE"))
    val tools = session.ml_tools().rows
    val shout = tools.find(_.name == "MCP_Tools.shout")
      .getOrElse(fail("MCP_Tools.shout not in " + tools.toString))
    assertEquals(shout.description, "uppercase the input")
    assertEquals(shout.form, "string_fun")
    assertEquals(shout.params.map(p => (p.name, p.typ, p.required)),
      List(("input", MCP_Session.Ptyp_String, true), ("context", MCP_Session.Ptyp_String, false)))
  }

  spec_test("base bridge accepts an exact reply limit, reports correlated TooLarge, and recovers",
      covers = List("pide_bridge#T12")) {
    val options = MCP_Test_Config.options + "mcp_bridge_max_reply_bytes=1024"
    val bounded = MCP_Session.boot(options, "MCP-Tools", MCP_Test_Config.session_dirs,
      "MCP_Tools", McpBridgeProfile.base, MCP_Test_Config.progress)
    try {
      val limit = 1024L
      // Hello is :0; the first two ordinary calls have equally sized :1/:2 ids.
      val id = "00000000-0000-0000-0000-000000000000:1"
      def status(text: String): XML.Body =
        XML.Encode.pair(XML.Encode.string, XML.Encode.self)(("ok", List(XML.Text(text))))
      val empty = PideBridgeV1.result(id, "run_tool", "ok", status(""))
      val exactText = "x" * (limit - empty.body.size).toInt
      assertEquals(PideBridgeV1.result(id, "run_tool", "ok", status(exactText)).body.size.toLong, limit)
      val root = "isabelle://context/theory/MCP_Tools"
      assertEquals(bounded.ml_run("MCP_Tools.shout", List("input" -> exactText), root),
        MCP_Session.Ok(exactText.toUpperCase))
      val message = expect_error(bounded.ml_run("MCP_Tools.shout", List("input" -> (exactText + "x")), root))
      assert(message.contains("reply bridge envelope has " + (limit + 1L)), message)
      assert(message.contains("limit is " + limit), message)
      assertEquals(bounded.ml_run("MCP_Tools.shout", List("input" -> "ok"), root), MCP_Session.Ok("OK"))
    }
    finally bounded.stop()
  }

  spec_test("tool declarations notify independently while a document load is pending",
      covers = List("pide_bridge#T9")) {
    val changed = new CountDownLatch(1)
    session.set_changed_handler(event => if (event == "tools") changed.countDown())
    try {
      Isabelle_System.with_tmp_dir("bridge-declaration") { dir =>
        val name = "BridgeDeclaration"
        File.write(dir + Path.basic(name + ".thy"),
          "theory " + name + "\nimports \"MCP-Tools.MCP_Tools\"\nbegin\n" +
          "mcp_tool document_probe = \\<open>fn s => s\\<close> " +
          "(description \\<open>document fixture\\<close>)\n" +
          "ML \\<open>OS.Process.sleep (Time.fromReal 2.0)\\<close>\nend\n")
        val loading = Future.fork(session.load_theory(name, File.standard_path(dir)))
        try {
          assert(changed.await(5, TimeUnit.SECONDS), "declaration notification was not delivered")
          assert(!loading.is_finished, "declaration notification waited for document load completion")
          assertEquals(session.ml_run("MCP_Tools.shout", List("input" -> "pending")), MCP_Session.Ok("PENDING"))
          assert(session.ml_tools().rows.exists(_.name == "MCP_Tools.shout"))
          expect_ok(loading.join)
        }
        finally {
          loading.join_result
          expect_ok(session.unload_theory(name))
        }
      }
    }
    finally session.set_changed_handler(_ => ())
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
        "p_fact" -> MCP_Session.Ptyp_Fact,
        "context" -> MCP_Session.Ptyp_String))
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

  spec_test("bridge: canonical root context equals an explicitly validated locator",
      verifies = List("context_locator#A2", "context_locator#I1")) {
    /* Thy_Info keying mixes qualified and unqualified names, so take the
       canonical spelling from the session itself */
    val locator = expect_ok(session.root_context())
    assertEquals(session.check_context(locator), MCP_Session.Ok(locator))
    assertEquals(session.ml_tools(locator), session.ml_tools())
  }

  spec_test("bridge: unknown context is a typed error, not an exception",
      covers = List("context_locator#T3", "context_locator#T4")) {
    expect_error(
      session.ml_run("MCP_Tools.shout", List("input" -> "x",
        "context" -> "isabelle://context/theory/No_Such_Theory")),
      containing = "No_Such_Theory")
    for (target <- List("bad-url", "isabelle://context/unknown_kind/x")) {
      expect_error(session.ml_run("MCP_Tools.shout", List("input" -> "x", "context" -> target)))
    }
  }

  spec_test("bridge: target input does not change the root catalogue",
      covers = List("context_locator#T5")) {
    assertEquals(session.ml_tools("isabelle://context/theory/No_Such_Theory"), session.ml_tools())
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



class MCP_Theory_Bridge_Tests extends MCP_Session_Suite(
  "MCP-HOL", "MCP", McpBridgeProfile.base) {

  test("image theories cannot be unloaded and discovery remains usable") {
    expect_error(session.unload_theory("Main"), containing = "image")
    assert(expect_ok(session.list_sessions_info()).contains("MCP-HOL"))
    assert(expect_ok(session.list_theories_info("MCP-HOL")).contains("MCP"))
    assert(expect_ok(session.search_sources("MCP")).contains("MCP"))
  }

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
          admission = ConnectionPolicy.AdmissionPolicy(
            checked(ConnectionPolicy.MaxInFlight.checked(2))),
          timing = ConnectionPolicy.TimingPolicy(
            checked(ConnectionPolicy.RequestTimeout.checked(10.0)),
            checked(ConnectionPolicy.ShutdownDrain.checked(2.0))))
        val plane = new ScriptedDataPlane(Nil)
        val rules = new Mcp2025RevisionRules
        val registry = new RequestRegistry(RequestRegistry.InvariantViolationPolicy.FailFast)
        val application = McpApplication.isabelle(
          () => McpApplication.Ready(session), "MCP-HOL", Nil, "MCP")
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

}

class MCP_Run_Tool_Async_Tests
  extends MCP_Session_Suite(
    "MCP-Tools-Tests", "MCP_Tools_Tests", McpBridgeProfile.base) {


  spec_test("base bridge deadline interrupts a started ML tool and recovers",
      covers = List("pide_bridge#T4")) {
    Isabelle_System.with_tmp_dir("bridge-deadline") { dir =>
      val theory = "BridgeDeadlineFixture"
      val sessionName = "MCP-Bridge-Deadline-Fixture"
      File.write(dir + Path.basic("ROOT"),
        "session \"" + sessionName + "\" = \"MCP-Tools\" +\n  theories " + theory + "\n")
      File.write(dir + Path.basic(theory + ".thy"),
        "theory " + theory + "\nimports \"MCP-Tools.MCP_Tools\"\nbegin\n" +
        "ML \\<open>val lifecycle = Synchronized.var \"deadline lifecycle\" \"idle\";\\<close>\n" +
        "mcp_tool slow = run \\<open>fn _ => fn _ => let " +
        "val _ = Synchronized.change lifecycle (K \"started\"); " +
        "val result = Exn.capture_body (fn () => (OS.Process.sleep (Time.fromReal 5.0); \"done\")); " +
        "val _ = Thread_Attributes.uninterruptible_body (fn _ => Synchronized.change lifecycle " +
        "(K (if (case result of Exn.Exn exn => Exn.is_interrupt exn | Exn.Res _ => false) then \"interrupted\" else \"finished\"))); " +
        "in Exn.release result end\\<close> (description \\<open>slow fixture\\<close>) (annotations mutating)\n" +
        "mcp_tool state = run \\<open>fn _ => fn _ => Synchronized.value lifecycle\\<close> " +
        "(description \\<open>lifecycle fixture\\<close>) (annotations read_only)\nend\n")
      val timed = MCP_Session.start(MCP_Test_Config.options + "mcp_request_timeout=1.0",
        sessionName, MCP_Test_Config.session_dirs :+ dir, theory, McpBridgeProfile.base,
        MCP_Test_Config.progress)
      val root = "isabelle://context/theory/" + sessionName + "." + theory
      def state(): MCP_Session.Result = timed.ml_run(theory + ".state", Nil, root)
      try {
        val slow = Future.fork(timed.ml_run(theory + ".slow", Nil, root))
        eventually("deadline fixture did not start", Time.seconds(0.8)) {
          state() == MCP_Session.Ok("started")
        }
        val timeout = intercept[MCP_Session.BridgeTimedOut] { slow.join }
        assertEquals(timeout.delay, 1.seconds)
        eventually("deadline did not interrupt admitted ML work", Time.seconds(3.0)) {
          state() == MCP_Session.Ok("interrupted")
        }
        assertEquals(timed.ml_run("MCP_Tools.shout", List("input" -> "after"), root), MCP_Session.Ok("AFTER"))
      }
      finally timed.stop()
    }
  }

  lazy val test_theory: String = expect_ok(session.root_context())

  spec_test("framework context executes a root-only tool in an ancestor target",
      covers = List("context_locator#T4", "context_locator#T5")) {
    val target = expect_ok(session.check_context("isabelle://context/theory/MCP_Tools"))
    val rootResult = expect_ok(run("target_probe"))
    assert(rootResult.split('.').last.startsWith("MCP_Root_"), rootResult)
    val targetResult = expect_ok(run("target_probe", List("context" -> target)))
    assert(targetResult.endsWith("MCP_Tools") && !targetResult.endsWith("MCP_Tools_Tests"))
    assertEquals(session.ml_tools(target), session.ml_tools())
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

  spec_test("live connection kernel cancels an ML tool and remains usable",
      covers = List("connection_kernel#T4", "connection_kernel#T11")) {
    def checked[A](value: Either[String, A]): A = value.fold(error, identity)
    val policy = ConnectionPolicy(
      revision = ProtocolRevision.V2025_03_26,
      admission = ConnectionPolicy.AdmissionPolicy(
        checked(ConnectionPolicy.MaxInFlight.checked(1))),
      timing = ConnectionPolicy.TimingPolicy(
        checked(ConnectionPolicy.RequestTimeout.checked(10.0)),
        checked(ConnectionPolicy.ShutdownDrain.checked(1.0))))
    val plane = new ScriptedDataPlane(Nil)
    val rules = new Mcp2025RevisionRules
    val registry = new RequestRegistry(RequestRegistry.InvariantViolationPolicy.FailFast)
    val testTheory = test_theory
    val application = McpApplication.isabelle(
      () => McpApplication.Ready(session), "MCP-Tools-Tests", Nil, "MCP_Tools_Tests")
    val kernel = ConnectionKernel(
      policy = policy,
      dataPlane = plane,
      revisionRules = rules,
      scheduler = new BoundedConcurrentScheduler(1, "live-tool-kernel"),
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
      kernel.handle(request(Some("slow"), "tools/call",
        Some(JSON.Object("name" -> "capture_slow", "arguments" -> JSON.Object()))))
      Thread.sleep(100)
      assert(!plane.written.exists(line =>
        JSON.Format.unapply(line).flatMap(JSON.string(_, "id")).contains("slow")),
        "slow tool completed before cancellation")
      kernel.handle(request(None, "notifications/cancelled",
        Some(JSON.Object("requestId" -> "slow", "reason" -> "bridge fixture"))))
      eventually("cancelled tool worker retained connection capacity", Time.seconds(2.0)) {
        registry.snapshot.activeIds.isEmpty
      }

      kernel.handle(request(Some("fast"), "tools/call",
        Some(JSON.Object("name" -> "capture_ok", "arguments" -> JSON.Object("x" -> "after-cancel")))))
      eventually("connection did not answer after tool cancellation", Time.seconds(2.0)) {
        plane.written.exists(line =>
          JSON.Format.unapply(line).flatMap(JSON.string(_, "id")).contains("fast"))
      }
      assert(!plane.written.exists(line =>
        JSON.Format.unapply(line).flatMap(JSON.string(_, "id")).contains("slow")),
        "client-cancelled tool call emitted a response: " + plane.written.mkString(" | "))
    }
    finally kernel.drainAndClose()
  }

  def run(name: String, args: List[(String, String)] = Nil): MCP_Session.Result =
    session.ml_run("MCP_Tools_Tests." + name, args, test_theory)

  spec_test("run-tool bridge cancellation unblocks Scala and leaves later calls usable",
      covers = List("connection_kernel#T4", "pide_bridge#T3")) {
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

  spec_test("bridge: run_tool async -- a slow capture tool does not block a concurrent fast one",
      covers = List("pide_bridge#T1", "pide_bridge#T8")) {
    val slow = Future.fork(run("capture_slow"))
    val fast = run("capture_ok", List("x" -> "hi"))

    assertEquals(fast, MCP_Session.Ok("got:hi"))
    assert(!slow.is_finished, "the slow capture tool finished before the fast reply arrived")

    val slow_result = slow.join
    assertEquals(slow_result, MCP_Session.Ok("slow done"))
  }

  spec_test("bridge: run_tool async -- concurrent capture tools do not cross outputs",
      covers = List("pide_bridge#T1")) {
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
      covers = List("pide_bridge#T6", "connection_kernel#T4", "connection_kernel#T11", "connection_kernel#T12")) {
    val testTheory = expect_ok(session.root_context())
    val pending = Future.fork(session.ml_run_cancellable(
      "MCP_Tools_Tests.capture_slow", Nil, testTheory, McpApplication.Cancellation.Never))
    val direct = Future.fork(session.direct_cancellable(McpApplication.Cancellation.Never) {
      Thread.sleep(5000)
      "unexpected completion"
    })
    Thread.sleep(100)
    assert(!pending.is_finished && !direct.is_finished,
      "shutdown fixtures completed before backend stop")

    session.stop()
    stopped = true
    assert(pending.is_finished && direct.is_finished,
      "backend stop returned before pending work terminated")
    expect_error(pending.join, containing = "session stopped")
    assert(Exn.is_exn(direct.join_result), "direct work escaped backend stop")
  }
}
