package isabelle.mcp

import isabelle._

import scala.concurrent.duration.{Duration, DurationInt}
import scala.jdk.CollectionConverters._

import org.junit.runner.{Description, JUnitCore, Request}
import org.junit.runner.manipulation.Filter
import org.junit.runner.notification.{Failure => JUnit_Failure, RunListener}


/* configuration handed from the isabelle tool to the suites (the JUnit
   runner instantiates suites via their no-arg constructor, so a global
   is the only channel) */

object MCP_Test_Config {
  var options: Options = null
  var session_dirs: List[Path] = Nil
  var progress: Progress = new Progress
}


/* fake backend */

class Fake_Backend extends MCP_Backend {
  val fake_root_context = "isabelle://context/theory/MCP_Tools"
  def root_context(): MCP_Session.Result = MCP_Session.Ok(fake_root_context)
  var stopped = false
  var extra_ml_tools: List[MCP_Session.Tool_Row] = Nil
  /* (plans/builtin_activation) settable builtin activation section --
     empty by default, matching the AVAILABILITY FLOOR (no mirror
     registered -> scala serves the full builtin table); tests set this
     to (name, false) pairs to simulate a del'd mirror. */
  var builtin_activation: List[(String, Boolean)] = Nil
  var changed_handler: String => Unit = _ => ()
  override def set_changed_handler(handler: String => Unit): Unit =
    changed_handler = handler
  /* rows carry full internal names + params like the real bridge; the
     Handler's exposure() shortens "MCP_Tools.shout" to "shout" and
     resolves tools/call back to the internal name; the params expand
     into the mvp {input :: string} schema */
  def ml_tools(context: String): MCP_Session.Tools_Reply = {
    val rows = MCP_Session.Tool_Row(
      "MCP_Tools.shout", "uppercase the input", "string_fun",
      List(MCP_Session.Tool_Param("input", MCP_Session.Ptyp_String, true, None, "tool input")),
      MCP_Session.Tool_Annotations.default) :: extra_ml_tools
    val context_param = MCP_Session.Tool_Param(
      "context", MCP_Session.Ptyp_String, false, None,
      "Optional context URL; defaults to the startup root")
    MCP_Session.Tools_Reply(
      rows.map(row => row.copy(params = row.params :+ context_param)), builtin_activation)
  }
  def ml_run(name: String, args: List[(String, String)],
      context: String): MCP_Session.Result =
    if (name == "MCP_Tools.shout") {
      args.collectFirst({ case ("input", v) => v }) match {
        case Some(input) => MCP_Session.Ok(input.toUpperCase)
        case None => MCP_Session.Error("Missing required argument: input")
      }
    }
    else MCP_Session.Error("Unknown MCP tool " + quote(name))
  def check_context(context: String): MCP_Session.Result =
    if (context.startsWith("isabelle://context/theory/")) MCP_Session.Ok(context)
    else MCP_Session.Error("Malformed MCP context locator " + quote(context))

  var loaded_theories: Set[String] = Set("Loaded")
  def load_theory(name: String, master_dir: String, include_output: Boolean,
      offset: Int, limit: Int): MCP_Session.Result = {
    loaded_theories += name
    MCP_Session.Ok(name + ": ok")
  }
  def unload_theory(name: String): MCP_Session.Result =
    if (name == "Image") {
      MCP_Session.Error(
        "Cannot unload " + quote(name) + ": it is baked into the base image (image tier)")
    }
    else if (loaded_theories.contains(name)) {
      loaded_theories -= name
      MCP_Session.Ok("Unloaded " + quote(name))
    }
    else MCP_Session.Error("Cannot unload " + quote(name) + ": it was not loaded")
  def list_sessions_info(offset: Int, limit: Int): MCP_Session.Result =
    MCP_Session.Ok("   session       chapter  heap  theories\n   HOL           main     ✓      42\n   HOL-Library   main     ✓      18")
  def list_theories_info(session: String, offset: Int, limit: Int): MCP_Session.Result =
    MCP_Session.Ok("   theory name\n   HOL.Main\n   HOL.Nat")
  def search_sources(pattern: String, offset: Int, limit: Int): MCP_Session.Result =
    MCP_Session.Ok("   matching theories\n   HOL.Main")
  private val fake_doc_catalog: List[Doc_Catalog.Section] =
    List(Doc_Catalog.Section("Isabelle Reference Manuals",
      List(
        Doc_Catalog.Entry(
          "isar-ref", "The Isabelle/Isar Reference Manual", "Isar_Ref", Path.current),
        Doc_Catalog.Entry("NEWS", "NEWS", "plain", Path.current))))
  def doc_list(pattern: String): MCP_Session.Result =
    MCP_Session.Ok(Doc_Catalog.render(fake_doc_catalog, pattern))

  def doc_read(name: String, section: String, lines: String, offset: Int,
      limit: Int): MCP_Session.Result =
    MCP_Session.Ok("doc_read is not backed by " + getClass.getSimpleName)
  def stop(): Unit = stopped = true
}


/* plans/readiness A1: the strongest available form of "this code path
   does not depend on the backend" -- every method throws, so a test
   that drives Handler through it and still succeeds proves the claim
   by actually exercising the failure mode, not just by inspecting that
   Not_Ready/Failed carry no backend field. */
class Throwing_Backend extends MCP_Backend {
  private def boom: Nothing = throw new RuntimeException("backend touched unexpectedly")
  def root_context(): MCP_Session.Result = boom
  def ml_tools(context: String): MCP_Session.Tools_Reply = boom
  def ml_run(name: String, args: List[(String, String)],
      context: String): MCP_Session.Result = boom
  def check_context(context: String): MCP_Session.Result = boom
  def load_theory(name: String, master_dir: String, include_output: Boolean,
      offset: Int, limit: Int): MCP_Session.Result = boom
  def unload_theory(name: String): MCP_Session.Result = boom
  def list_sessions_info(offset: Int, limit: Int): MCP_Session.Result = boom
  def list_theories_info(session: String, offset: Int, limit: Int): MCP_Session.Result = boom
  def search_sources(pattern: String, offset: Int, limit: Int): MCP_Session.Result = boom
  def doc_list(pattern: String): MCP_Session.Result = boom
  def doc_read(name: String, section: String, lines: String, offset: Int,
      limit: Int): MCP_Session.Result = boom
  def stop(): Unit = boom
}


/* base suite: json access and jsonrpc/handler helpers over Fake_Backend */

abstract class MCP_Suite extends munit.FunSuite with MCP_Spec_Tests {
  /* json access */

  def get(json: JSON.T, path: String*)(implicit loc: munit.Location): JSON.T =
    path.foldLeft(json)((j, field) =>
      JSON.value(j, field).getOrElse(
        fail("missing field " + quote(field) + " in " + JSON.Format(j))))

  def get_string(json: JSON.T, path: String*)(implicit loc: munit.Location): String =
    get(json, path*) match {
      case s: String => s
      case other => fail("not a string: " + JSON.Format(other))
    }

  def get_list(json: JSON.T, path: String*)(implicit loc: munit.Location): List[JSON.T] =
    get(json, path*) match {
      case l: List[_] => l
      case other => fail("not a list: " + JSON.Format(other))
    }


  /* jsonrpc requests against a fresh handler */

  def request(id: Option[Long], method: String, params: Option[JSON.Object.T]): JSON.T = {
    var obj = JSON.Object("jsonrpc" -> "2.0", "method" -> method)
    for (i <- id) obj += ("id" -> i)
    for (p <- params) obj += ("params" -> p)
    obj
  }

  private var next_id: Long = 0

  def rpc(method: String, params: JSON.Object.T = null,
      backend: MCP_Backend = new Fake_Backend)(implicit loc: munit.Location): JSON.T = {
    next_id += 1
    new MCP_Server.Handler(backend)
      .handle(request(Some(next_id), method, Option(params)))
      .getOrElse(fail("expected a reply to " + method))
  }

  def notification(method: String, params: JSON.Object.T = null,
      backend: MCP_Backend = new Fake_Backend): Option[JSON.T] =
    new MCP_Server.Handler(backend).handle(request(None, method, Option(params)))

  /* Like rpc(), but against a handler the caller owns. Context-locator
     selection is per-connection state, so its tests must reuse one handler. */
  def rpc_on(handler: MCP_Server.Handler, method: String, params: JSON.Object.T = null)
      (implicit loc: munit.Location): JSON.T = {
    next_id += 1
    handler.handle(request(Some(next_id), method, Option(params)))
      .getOrElse(fail("expected a reply to " + method))
  }

  def call_tool_on(handler: MCP_Server.Handler, name: String, args: JSON.Object.T)
      (implicit loc: munit.Location): JSON.T =
    rpc_on(handler, "tools/call", JSON.Object("name" -> name, "arguments" -> args))


  /* tools/list rows */

  def tool_row(name: String, backend: MCP_Backend = new Fake_Backend)
      (implicit loc: munit.Location): JSON.T = {
    val tools = get_list(rpc("tools/list", backend = backend), "result", "tools")
    tools.find(t => get_string(t, "name") == name)
      .getOrElse(fail(name + " missing from tools/list: " + tools.toString))
  }

  def required_args(row: JSON.T)(implicit loc: munit.Location): List[JSON.T] =
    get_list(row, "inputSchema", "required")

  def annotation(row: JSON.T, name: String)(implicit loc: munit.Location): JSON.T =
    get(row, "annotations", name)

  def property_type(row: JSON.T, prop: String)(implicit loc: munit.Location): String =
    get_string(row, "inputSchema", "properties", prop, "type")


  /* tools/call */

  def call_tool(name: String, args: JSON.Object.T,
      backend: MCP_Backend = new Fake_Backend)(implicit loc: munit.Location): JSON.T =
    rpc("tools/call", JSON.Object("name" -> name, "arguments" -> args), backend)

  def result_text(reply: JSON.T)(implicit loc: munit.Location): String =
    get_string(get_list(reply, "result", "content").head, "text")

  def assert_no_error(reply: JSON.T)(implicit loc: munit.Location): Unit =
    assert(JSON.value(get(reply, "result"), "isError").isEmpty,
      "unexpected isError: " + JSON.Format(reply))

  def assert_is_error(reply: JSON.T)(implicit loc: munit.Location): String = {
    assertEquals(JSON.value(get(reply, "result"), "isError"), Some(true),
      "expected isError: " + JSON.Format(reply))
    result_text(reply)
  }

}


abstract class MCP_Session_Suite(session_name: String, theory: String,
  bridgeProfile: McpBridgeProfile) extends MCP_Suite {
  override def munitTimeout: Duration = 10.minutes

  private var session0: MCP_Session = null
  def session: MCP_Session =
    if (session0 == null) fail("no PIDE session (beforeAll failed?)")
    else session0

  override def beforeAll(): Unit = {
    MCP_Test_Config.progress.echo(
      "Starting PIDE session " + session_name + " for " + getClass.getSimpleName + " ...")
    session0 =
      MCP_Session.start(MCP_Test_Config.options, session_name,
        MCP_Test_Config.session_dirs, theory, bridgeProfile,
        progress = MCP_Test_Config.progress)
  }

  override def afterAll(): Unit = if (session0 != null) session0.stop()


  /* results */

  def expect_ok(result: MCP_Session.Result, clue: => String = "expected Ok")
      (implicit loc: munit.Location): String =
    result match {
      case MCP_Session.Ok(text) => text
      case MCP_Session.Error(msg) => fail(clue + " -- got error: " + msg)
    }

  def expect_error(result: MCP_Session.Result, containing: String = "")
      (implicit loc: munit.Location): String =
    result match {
      case MCP_Session.Error(msg) =>
        assert(msg.contains(containing),
          "error does not mention " + quote(containing) + ": " + msg)
        msg
      case MCP_Session.Ok(text) => fail("expected an error, got Ok: " + text)
    }


  /* poll until cond or deadline */
  def eventually(msg: => String, timeout: Time = Time.seconds(1.5))(cond: => Boolean)
      (implicit loc: munit.Location): Unit = {
    val deadline = Time.now() + timeout
    var ok = cond
    while (!ok && Time.now() < deadline) ok = cond
    assert(ok, msg)
  }

}


/* base suite for heap tests: raw ML evaluated by a fresh
   `isabelle ML_process` against a saved heap. fresh-process semantics
   are the point -- this is exactly the state a live mcp_server
   inherits from the heap, and the one environment build-time
   \<^assert> theories can never exercise (those run inside the
   building process). No Scala context:
   \<^scala>, protocol commands and PIDE snapshots need
   MCP_Session_Suite instead. a heap load costs seconds, so batch
   related assertions into one ml() call rather than one process per
   micro-check. */

abstract class MCP_Heap_Suite(logic: String) extends munit.FunSuite with MCP_Spec_Tests {
  override def munitTimeout: Duration = 10.minutes

  /* one-shot evaluation: result.ok iff source evaluates without
     exception (an ML error exits nonzero and prints "Exception- ...");
     stderr is merged into out */
  def ml(source: String): Process_Result =
    Isabelle_System.bash(
      "\"$ISABELLE_TOOL\" ML_process -r" +
        MCP_Test_Config.session_dirs.map(d => " -d " + File.bash_path(d)).mkString +
        " -l " + Bash.string(logic) +
        " -e " + Bash.string(source) +
        " -e " + Bash.string("exit 0;"))

  def ml_check(source: String, clue: => String = "ML evaluation failed")
      (implicit loc: munit.Location): String = {
    val result = ml(source)
    assert(result.ok, clue + "\n" + result.out)
    result.out
  }

  /* the common shape: one test = one ML snippet that must evaluate
     cleanly (use error/\<^assert> in the snippet for the checks) */
  def ml_test(name: String)(source: String): Unit =
    test(name) { ml_check(source) }
}


/* runner: munit suites through the JUnit4 core. Completion output is quiet;
   focused diagnosis can request each passing test explicitly. */

object MCP_Test_Runner {
  private def test_name(desc: Description): String =
    desc.getMethodName match {
      case null => desc.getDisplayName
      case name => name
    }

  def run(suites: List[Class[? <: munit.Suite]], name_filter: Option[String],
      progress: Progress, verbose: Boolean = false): Int = {
    val test_classes =
      suites.flatMap { cls =>
        cls.getDeclaredConstructor().newInstance().munitTests().map { test =>
          (cls.getName, test.name) -> MCP_Spec_Metadata.test_class(test)
        }
      }.toMap

    def classified_name(desc: Description): String = {
      val name = test_name(desc)
      val test_class = test_classes.getOrElse((desc.getClassName, name),
        MCP_Spec_Metadata.Functional)
      "[" + test_class + "] " + name
    }

    /* drop suites with no matching test up front: filtering a runner
       down to zero tests is a JUnit error, not an empty run */
    var selected_test_keys = Set.empty[(String, String)]
    val selected =
      name_filter match {
        case None => suites
        case Some(pattern) =>
          suites.filter { cls =>
            val matches =
              cls.getDeclaredConstructor().newInstance().munitTests()
                .filter(MCP_Spec_Metadata.matches(_, pattern))
            selected_test_keys ++= matches.map(test => (cls.getName, test.name))
            matches.nonEmpty
          }
      }
    if (selected.isEmpty) { progress.echo("no tests match"); return 0 }

    val core = new JUnitCore
    core.addListener(new RunListener {
      private var failed = Set.empty[String]
      override def testFailure(failure: JUnit_Failure): Unit = {
        failed += failure.getDescription.getDisplayName
        progress.echo_error_message(
          "FAIL " + classified_name(failure.getDescription) + "\n" + failure.getMessage)
      }
      override def testFinished(desc: Description): Unit =
        if (verbose && !failed(desc.getDisplayName))
          progress.echo("PASS " + classified_name(desc))
    })

    var req = Request.classes(selected*)
    for (pattern <- name_filter) {
      req = req.filterWith(new Filter {
        override def shouldRun(desc: Description): Boolean =
          (desc.isTest && selected_test_keys((desc.getClassName, test_name(desc)))) ||
            desc.getChildren.asScala.exists(shouldRun)
        override def describe(): String = "name or plan link contains " + quote(pattern)
      })
    }
    core.run(req).getFailureCount
  }
}
