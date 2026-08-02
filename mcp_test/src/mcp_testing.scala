/*  Title:      mcp_test/src/mcp_testing.scala

Test infrastructure for the mcp component: the fake backend, the munit
base suites carrying the recurring test patterns (json access, jsonrpc
helpers, the builtin dispatch assertion, with_repl / slow_step /
await_busy for bridge tests), and the JUnit-driven runner behind
isabelle mcp_test.
*/

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
  var stopped = false
  var extra_ml_tools: List[MCP_Session.Tool_Row] = Nil
  /* (plans/builtin_activation) settable builtin activation section --
     empty by default, matching the AVAILABILITY FLOOR (no mirror
     registered -> scala serves the full builtin table); tests set this
     to (name, false) pairs to simulate a del'd mirror. */
  var builtin_activation: List[(String, Boolean)] = Nil
  var last_ir: Option[(String, List[(String, String)])] = None
  var changed_handler: String => Unit = _ => ()
  override def set_changed_handler(handler: String => Unit): Unit =
    changed_handler = handler
  /* rows carry full internal names + params like the real bridge; the
     Handler's exposure() shortens "MCP_Tools.shout" to "shout" and
     resolves tools/call back to the internal name; the params expand
     into the mvp {input :: string} schema */
  def ml_tools(designation: String, bundles: List[String]): MCP_Session.Tools_Reply =
    MCP_Session.Tools_Reply(
      MCP_Session.Tool_Row("MCP_Tools.shout", "uppercase the input", "string_fun",
        List(MCP_Session.Tool_Param(
          "input", MCP_Session.Ptyp_String, true, None, "tool input")),
        MCP_Session.Tool_Annotations.default) ::
      extra_ml_tools,
      builtin_activation)
  def ml_run(name: String, args: List[(String, String)],
      designation: String, bundles: List[String]): MCP_Session.Result =
    if (name == "MCP_Tools.shout") {
      args.collectFirst({ case ("input", v) => v }) match {
        case Some(input) => MCP_Session.Ok(input.toUpperCase)
        case None => MCP_Session.Error("Missing required argument: input")
      }
    }
    else MCP_Session.Error("Unknown MCP tool " + quote(name))
  /* tool_scope_set{repl}/tool_scope_include: settable fake repl/bundle
     universes, good enough for the scala-unit "unknown X errors naming
     X, connection state unchanged" tests -- real repl/bundle resolution
     is a bridge-suite claim (MCP_Protocol.designated_context). Bare
     theory designations are always ok here: the theory case is
     validated scala-side via resolve_context_theory before it ever
     reaches check_designation (plans/tool_scope). */
  var known_repls: Set[String] = Set("R")
  var known_bundles: Set[String] = Set("scoped_tools")
  def check_designation(designation: String, bundles: List[String]): MCP_Session.Result = {
    val repl_ok =
      if (designation.startsWith("repl:")) known_repls(designation.stripPrefix("repl:")) else true
    if (!repl_ok) {
      MCP_Session.Error("Unknown repl " + quote(designation.stripPrefix("repl:")))
    }
    else {
      bundles.find(!known_bundles(_)) match {
        case Some(bad) => MCP_Session.Error("Unknown bundle " + quote(bad))
        case None => MCP_Session.Ok("")
      }
    }
  }
  def ir(fname: String, args: List[(String, String)]): MCP_Session.Result = {
    last_ir = Some((fname, args))
    (fname, args) match {
      case ("repls", Nil) => MCP_Session.Ok("")
      case ("init", ("repl", _) :: _) => MCP_Session.Ok("Created REPL")
      case ("fork", ("repl", repl) :: ("new_repl", new_repl) :: ("state_idx", idx) :: Nil) =>
        MCP_Session.Ok("Forked " + quote(new_repl) + " from " + quote(repl) + " at state " + idx)
      case ("remove", ("repl", repl) :: Nil) => MCP_Session.Ok("Removed " + quote(repl))
      case ("step", ("repl", _) :: ("isar_text", text) :: Nil) => MCP_Session.Ok(text)
      case ("state", ("repl", _) :: ("state_idx", idx) :: Nil) => MCP_Session.Ok("state " + idx)
      case ("show", ("repl", repl) :: Nil) => MCP_Session.Ok("REPL " + quote(repl))
      case ("text", ("repl", _) :: Nil) => MCP_Session.Ok("")
      case ("edit", ("repl", _) :: ("idx", idx) :: ("isar_text", _) :: Nil) =>
        MCP_Session.Ok("Edited step " + idx)
      case ("replay", ("repl", _) :: Nil) => MCP_Session.Ok("Replayed 0 stale steps")
      case ("truncate", ("repl", _) :: ("idx", idx) :: Nil) =>
        MCP_Session.Ok("Truncated to step " + idx)
      case ("back", ("repl", _) :: Nil) => MCP_Session.Ok("Truncated to step 0")
      case ("merge", ("repl", repl) :: Nil) =>
        MCP_Session.Ok("Merged " + quote(repl) + " into \"P\" (appended as new step)")
      case ("timeout", ("repl", repl) :: ("secs", secs) :: Nil) =>
        MCP_Session.Ok("Timeout for " + quote(repl) + " set to " +
          (if (secs == "0") "unlimited" else secs + "s"))
      case ("pin", ("repl", repl) :: Nil) => MCP_Session.Ok("Pinned " + quote(repl))
      case ("unpin", ("repl", repl) :: Nil) => MCP_Session.Ok("Unpinned " + quote(repl))
      case ("rebase", ("repl", repl) :: Nil) =>
        MCP_Session.Ok("Rebased " + quote(repl) + " (0 steps marked stale; use replay to re-execute)")
      case ("sledgehammer", ("repl", repl) :: Nil) =>
        MCP_Session.Ok("sledgehammer " + quote(repl) + " (default timeout)")
      case ("sledgehammer", ("repl", repl) :: ("timeout_secs", secs) :: Nil) =>
        MCP_Session.Ok("sledgehammer " + quote(repl) + " timeout_secs=" + secs)
      case ("find_theorems", ("repl", repl) :: ("query", query) :: Nil) =>
        MCP_Session.Ok("find_theorems " + quote(repl) + " query=" + quote(query))
      case ("find_theorems",
            ("repl", repl) :: ("query", query) :: ("max_results", max) :: Nil) =>
        MCP_Session.Ok(
          "find_theorems " + quote(repl) + " query=" + quote(query) + " max_results=" + max)
      /* context promotion (plans/find_theorems): theory- and no-context
         calls -- args always carry the RESOLVED theory (canonical
         spelling), never the client's original string, so a scala unit
         test can assert on the resolved key crossing this fake bridge. */
      case ("find_theorems", ("theory", theory) :: ("query", query) :: Nil) =>
        MCP_Session.Ok("find_theorems theory=" + quote(theory) + " query=" + quote(query))
      case ("find_theorems",
            ("theory", theory) :: ("query", query) :: ("max_results", max) :: Nil) =>
        MCP_Session.Ok(
          "find_theorems theory=" + quote(theory) + " query=" + quote(query) + " max_results=" + max)
      case ("find_theorems", ("query", query) :: Nil) =>
        MCP_Session.Ok("find_theorems (default context) query=" + quote(query))
      case ("find_theorems", ("query", query) :: ("max_results", max) :: Nil) =>
        MCP_Session.Ok(
          "find_theorems (default context) query=" + quote(query) + " max_results=" + max)
      /* find_definition (plans/find_definition): reuses find_theorems'
         context-promotion resolver, so a "theory" pair (when present)
         is always already the resolved canonical spelling. */
      case ("find_definition", ("name", name) :: Nil) =>
        MCP_Session.Ok("find_definition name=" + quote(name))
      case ("find_definition", ("name", name) :: ("kind", kind) :: Nil) =>
        MCP_Session.Ok("find_definition name=" + quote(name) + " kind=" + quote(kind))
      case ("find_definition", ("name", name) :: ("theory", theory) :: Nil) =>
        MCP_Session.Ok("find_definition name=" + quote(name) + " theory=" + quote(theory))
      case ("find_definition", ("name", name) :: ("repl", repl) :: Nil) =>
        MCP_Session.Ok("find_definition name=" + quote(name) + " repl=" + quote(repl))
      case _ => MCP_Session.Error("Unknown MCP.ir function " + quote(fname))
    }
  }

  /* find_theorems' theory selector (plans/find_theorems "context
     promotion"): "Main" and its alternate spelling "HOL.Main" both
     resolve to the canonical "Main"; "FSOnly" simulates a filesystem-
     tier theory (known but not loaded, no context to search); anything
     else is unknown. Good enough for the scala-unit exclusivity/
     normalization tests -- not meant to model the real resolver. */
  def resolve_context_theory(name: String): Either[String, String] =
    name match {
      case "Main" | "HOL.Main" => Right("Main")
      case "FSOnly" =>
        Left(
          "Unknown theory " + quote(name) + " context: filesystem theory, not yet " +
            "loaded (no context to search) -- load_theory first")
      case _ => Left("Unknown theory " + quote(name))
    }
  /* repl_init_from_source (plans/repl_init_from_source): good enough for
     the scala-unit exclusivity/dispatch tests -- "Main" simulates a
     resolved, always-locatable theory (so the fake never needs a real
     snapshot), "FSOnly" the filesystem-tier "not yet loaded" case, and
     the recorded call is exposed via last_ir so tests can assert the
     backend actually saw the resolved locator. */
  def init_from_source(repl: String, theory: String,
      offset: Option[Int], pattern: Option[String], index: Option[Int]): MCP_Session.Result =
    theory match {
      case "Main" | "HOL.Main" =>
        val locator =
          offset.map("offset=" + _).orElse(pattern.map("pattern=" + _)).orElse(index.map("index=" + _))
            .getOrElse("")
        last_ir = Some(("init_from_source", List("repl" -> repl, "theory" -> theory) ++
          List("locator" -> locator)))
        MCP_Session.Ok("Created REPL " + quote(repl) + " from " + quote(theory) + " " + locator)
      case "FSOnly" =>
        MCP_Session.Error(
          "Unknown theory " + quote(theory) + " context: filesystem theory, not yet " +
            "loaded (no context to attach to) -- load_theory first")
      case _ => MCP_Session.Error("Unknown theory " + quote(theory))
    }
  /* isabelle://named/{name}: a fixed one-entry stand-in for MCP_Resource's
     registry (MCP_Tools.thy), mirroring extra_ml_tools' role for MCP_Tool. */
  var named_resources: List[(String, String)] = List(("greeting", "a static demo resource"))

  /* scope_add/scope_remove (plans/scope_add, plans/scope_remove): a small
     fixed theory universe good enough to test match-counting and tier
     tagging without a real headless session -- "Image" mirrors the
     mcp_resource_read fake's image-tier stand-in above, the rest are
     filesystem tier; "Loaded" (see loaded_theories below) starts loaded,
     i.e. in the implicit working set, and is not part of this universe
     map (it gets LoadedTier by mcp_resources' fallback, same as the real
     backend). */
  var theory_universe: Map[String, String] = Map(
    "Image" -> "image",
    "HOL-Library.Multiset" -> "filesystem",
    "HOL-Library.FSet" -> "filesystem",
    "HOL-Library.Rat" -> "filesystem",
    "Other.Theory" -> "filesystem")
  var scope_patterns: List[String] = Nil
  /* scope_show (plans/scope_show): a settable stand-in for the real
     backend's ir("repls", Nil)-derived active_repl_ids() -- good enough
     to test scope_show's repl line and its resources/list agreement
     without a real headless session. Empty by default (T1: fresh state
     has no repls). */
  var active_repls: List[String] = Nil

  def scope_add(patterns: List[String]): MCP_Session.Result = {
    val current = scope_patterns
    val distinct_patterns = patterns.distinct
    val newly_added = distinct_patterns.filterNot(current.contains)
    if (newly_added.nonEmpty) {
      scope_patterns = scope_patterns ++ newly_added
      changed_handler("resources")
    }
    val lines =
      distinct_patterns.map { p =>
        val count = theory_universe.keys.count(MCP_Session.glob_to_regex(p).matches)
        val status = if (current.contains(p)) "already in scope" else "added"
        p + ": " + status + " (" + count + " theories match)"
      }
    MCP_Session.Ok(lines.mkString("\n"))
  }

  def scope_remove(patterns: List[String]): MCP_Session.Result = {
    val current = scope_patterns
    val distinct_patterns = patterns.distinct
    val present = distinct_patterns.filter(current.contains)
    if (present.nonEmpty) {
      scope_patterns = scope_patterns.filterNot(present.contains)
      changed_handler("resources")
    }
    val notes =
      distinct_patterns.map { p =>
        if (current.contains(p)) p + ": removed" else p + ": not in scope"
      }
    val remaining_line =
      "remaining scope: " + (if (scope_patterns.isEmpty) "(none)" else scope_patterns.mkString(", "))
    MCP_Session.Ok((notes :+ remaining_line).mkString("\n"))
  }

  def mcp_resources(): List[(String, String, String)] = {
    val regexes = scope_patterns.map(MCP_Session.glob_to_regex)
    val pattern_matched = theory_universe.keys.filter(name => regexes.exists(_.matches(name))).toSet
    val scoped_theories = (loaded_theories ++ pattern_matched).toList.sorted
    ("isabelle://session", "session", "current session overview") ::
    named_resources.map { case (name, description) => ("isabelle://named/" + name, name, description) } ++
    scoped_theories.map { name =>
      val tier = theory_universe.getOrElse(name, "loaded")
      ("isabelle://theory/" + name, name, "theory (" + tier + ")")
    } ++
    active_repls.map(id => ("isabelle://repl/" + id, id, "repl"))
  }

  def scope_show(): MCP_Session.Result = {
    val pattern_lines =
      if (scope_patterns.isEmpty) List("patterns: (none)")
      else "patterns:" :: scope_patterns.map { p =>
        val count = theory_universe.keys.count(MCP_Session.glob_to_regex(p).matches)
        "  " + p + " (" + count + " theories match)"
      }
    val regexes = scope_patterns.map(MCP_Session.glob_to_regex)
    val pattern_matched = theory_universe.keys.filter(name => regexes.exists(_.matches(name))).toSet
    val scoped_theories = (loaded_theories ++ pattern_matched).toList.sorted
    val theory_lines =
      if (scoped_theories.isEmpty) List("theories: (none)")
      else "theories:" :: scoped_theories.map { name =>
        "  " + name + " (" + theory_universe.getOrElse(name, "loaded") + ")"
      }
    val repl_lines =
      if (active_repls.isEmpty) List("repls: (none)")
      else "repls:" :: active_repls.map("  " + _)
    val named_lines =
      if (named_resources.isEmpty) List("named resources: (none)")
      else "named resources:" :: named_resources.map { case (name, _) => "  " + name }
    MCP_Session.Ok((pattern_lines ++ theory_lines ++ repl_lines ++ named_lines).mkString("\n"))
  }

  private val repl_uri = """\Aisabelle://repl/([^/]+)\z""".r
  private val repl_text_uri = """\Aisabelle://repl/([^/]+)/text\z""".r
  private val named_uri = """\Aisabelle://named/([^/]+)\z""".r
  private val theory_diagnostics_uri = """\Aisabelle://theory/([^/]+)/diagnostics\z""".r
  private val theory_commands_uri = """\Aisabelle://theory/([^/]+)/commands\z""".r
  private val theory_entities_uri = """\Aisabelle://theory/([^/]+)/entities\z""".r
  private val theory_source_uri = """\Aisabelle://theory/([^/]+)\z""".r
  private val not_yet_backed_uri = """\Aisabelle://(theory/[^/]+(?:/[a-z]+)?)\z""".r

  def mcp_resource_read(uri: String): MCP_Session.Result =
    uri match {
      case "isabelle://session" =>
        MCP_Session.Ok("session: TEST\ntheories: Fake_Theory")
      case repl_text_uri(repl) => ir("text", List("repl" -> repl))
      case repl_uri(repl) => ir("show", List("repl" -> repl))
      case named_uri(name) =>
        named_resources.find(_._1 == name) match {
          case Some((_, description)) => MCP_Session.Ok(description)
          case None => MCP_Session.Error("Unknown MCP resource " + quote(name))
        }
      case theory_diagnostics_uri(name) =>
        if (name == "Image") MCP_Session.Ok(name + ": checked at build time")
        else if (loaded_theories.contains(name)) MCP_Session.Ok(name + ": ok")
        else MCP_Session.Ok(name + ": not checked -- load_theory to check")
      /* image tier only -- see mcp_session.scala's comment on the real
         theory_commands_uri/theory_source_uri/theory_entities_uri cases
         for why loaded (headless use_theories) and filesystem theories
         still fall through to not_yet_backed_uri below. */
      case theory_commands_uri(name) if name == "Image" =>
        MCP_Session.Ok("  idx  keyword  line  offset  file\n    0  theory   1     0       Image.thy")
      case theory_entities_uri(name) if name == "Image" =>
        MCP_Session.Ok("   kind     line  name\n    fact       3  Image.foo")
      case theory_source_uri(name) if name == "Image" =>
        MCP_Session.Ok("theory Image\nimports Main\nbegin\nend\n")
      case not_yet_backed_uri(_) =>
        MCP_Session.Error(
          "Resource " + quote(uri) + " is a documented template but not backed yet")
      case _ => MCP_Session.Error("Unknown MCP resource " + quote(uri))
    }

  /* wave 2 (plans/load_theory, plans/unload_theory, plans/check_theory):
     a minimal in-memory model -- "Loaded" starts loaded, "Image" is the
     fake's stand-in base-image theory, anything else is filesystem
     (never loaded) -- good enough to test the SCALA error paths
     (plans/unload_theory T2) without a real headless session. Also
     backs isabelle://theory/{name}/diagnostics' three-tier answer
     above. */
  var loaded_theories: Set[String] = Set("Loaded")
  def load_theory(name: String, master_dir: String): MCP_Session.Result = {
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
  def check_theory(name: String, master_dir: String): MCP_Session.Result = {
    loaded_theories += name
    MCP_Session.Ok(name + ": ok")
  }
  def list_sessions_info(): MCP_Session.Result =
    MCP_Session.Ok("   session       chapter  heap  theories\n   HOL           main     ✓      42\n   HOL-Library   main     ✓      18")
  def list_theories_info(session: String): MCP_Session.Result =
    MCP_Session.Ok("   theory name\n   HOL.Main\n   HOL.Nat")
  def search_sources(pattern: String): MCP_Session.Result =
    MCP_Session.Ok("   matching theories\n   HOL.Main")
  private val fake_doc_catalog: List[Doc_Catalog.Section] =
    List(Doc_Catalog.Section("Isabelle Reference Manuals",
      List(
        Doc_Catalog.Entry(
          "isar-ref", "The Isabelle/Isar Reference Manual", "Isar_Ref", Path.current),
        Doc_Catalog.Entry("NEWS", "NEWS", "plain", Path.current))))
  def doc_list(pattern: String): MCP_Session.Result =
    MCP_Session.Ok(Doc_Catalog.render(fake_doc_catalog, pattern))
  /* doc_read has no fake catalog behind it -- the real toc/section-slicing/
     windowing logic (Doc_Catalog.scan_headings/section_text/plain_read) is
     pure and tested directly against the bundled distribution's real doc
     sources (MCP_Doc_Catalog_Tests), not through Fake_Backend; this stub
     only exists to satisfy MCP_Backend's interface for dispatch-level
     tests (tools/list, tool_scope, ...) that never call it. */
  def doc_read(name: String, section: String, lines: String): MCP_Session.Result =
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
  def ml_tools(designation: String, bundles: List[String]): MCP_Session.Tools_Reply = boom
  def ml_run(name: String, args: List[(String, String)],
      designation: String, bundles: List[String]): MCP_Session.Result = boom
  def check_designation(designation: String, bundles: List[String]): MCP_Session.Result = boom
  def ir(fname: String, args: List[(String, String)]): MCP_Session.Result = boom
  def resolve_context_theory(name: String): Either[String, String] = boom
  def init_from_source(repl: String, theory: String,
      offset: Option[Int], pattern: Option[String], index: Option[Int]): MCP_Session.Result = boom
  def mcp_resources(): List[(String, String, String)] = boom
  def mcp_resource_read(uri: String): MCP_Session.Result = boom
  def scope_add(patterns: List[String]): MCP_Session.Result = boom
  def scope_remove(patterns: List[String]): MCP_Session.Result = boom
  def scope_show(): MCP_Session.Result = boom
  def load_theory(name: String, master_dir: String): MCP_Session.Result = boom
  def unload_theory(name: String): MCP_Session.Result = boom
  def check_theory(name: String, master_dir: String): MCP_Session.Result = boom
  def list_sessions_info(): MCP_Session.Result = boom
  def list_theories_info(session: String): MCP_Session.Result = boom
  def search_sources(pattern: String): MCP_Session.Result = boom
  def doc_list(pattern: String): MCP_Session.Result = boom
  def doc_read(name: String, section: String, lines: String): MCP_Session.Result = boom
  def stop(): Unit = boom
}


/* base suite: json access and jsonrpc/handler helpers over Fake_Backend */

abstract class MCP_Suite extends munit.FunSuite {
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

  /* like rpc(), but against a HANDLER THE CALLER OWNS instead of a fresh
     one per call -- every other request in this suite is stateless from
     Handler's point of view, but tool_scope_* (plans/tool_scope) mutates
     per-connection state (the designation, included bundles), so tests
     of it need calls to land on the SAME Handler instance. */
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

  /* the builtin dispatch pattern: tools/call on a builtin must reach
     backend.ir as exactly (fname, pairs), with an ok reply */
  def assert_dispatch(tool: String, args: JSON.Object.T,
      fname: String, pairs: List[(String, String)])(implicit loc: munit.Location): Unit = {
    val backend = new Fake_Backend
    val reply = call_tool(tool, args, backend)
    assertEquals(backend.last_ir, Some((fname, pairs)),
      "backend did not see the expected " + fname + " args")
    assert_no_error(reply)
  }
}


/* base suite for bridge tests: one shared headless PIDE session per
   suite (started in beforeAll, stopped in afterAll), plus the
   recurring repl-lifecycle patterns */

abstract class MCP_Session_Suite(session_name: String, theory: String) extends MCP_Suite {
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
        MCP_Test_Config.session_dirs, theory, progress = MCP_Test_Config.progress)
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


  /* repl lifecycle: init as setup, best-effort remove as teardown
     (tests assert their own remove when the removal is the point --
     the extra remove here then errors harmlessly) */

  def with_repl(name: String, theories: List[String] = List("Main"))(body: => Unit)
      (implicit loc: munit.Location): Unit = {
    expect_ok(session.ir("init", ("repl" -> name) :: theories.map("theories" -> _)),
      "init " + name)
    try body
    finally session.ir("remove", List("repl" -> name))
  }

  /* a step that holds the repl busy for ~2s */
  def slow_step(repl: String): Future[MCP_Session.Result] =
    Future.fork(session.ir("step",
      List("repl" -> repl,
        "isar_text" -> "ML_command \\<open>OS.Process.sleep (seconds 2.0)\\<close>")))

  /* poll until cond or deadline */
  def eventually(msg: => String, timeout: Time = Time.seconds(1.5))(cond: => Boolean)
      (implicit loc: munit.Location): Unit = {
    val deadline = Time.now() + timeout
    var ok = cond
    while (!ok && Time.now() < deadline) ok = cond
    assert(ok, msg)
  }

  def repl_listing()(implicit loc: munit.Location): String =
    expect_ok(session.ir("repls", Nil), "repls")

  def await_busy(repl: String)(implicit loc: munit.Location): Unit =
    eventually(repl + " was never observed busy") {
      session.ir("repls", Nil) match {
        case MCP_Session.Ok(text) => text.contains(repl) && text.contains("busy")
        case _ => false
      }
    }
}


/* base suite for heap tests: raw ML evaluated by a fresh
   `isabelle ML_process` against a saved heap. fresh-process semantics
   are the point -- this is exactly the state a live mcp_server
   inherits from the heap, and the one environment build-time
   \<^assert> theories can never exercise (those run inside the
   building process, where e.g. Thy_Info segments still exist; see
   the KNOWN GAP in CHANGELOG 2026-07-10 and the entities name-
   qualification issue in plans/find_definition). no Scala context:
   \<^scala>, protocol commands and PIDE snapshots need
   MCP_Session_Suite instead. a heap load costs seconds, so batch
   related assertions into one ml() call rather than one process per
   micro-check. */

abstract class MCP_Heap_Suite(logic: String) extends munit.FunSuite {
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


/* runner: munit suites through the JUnit4 core, PASS/FAIL per test */

object MCP_Test_Runner {
  private def test_name(desc: Description): String =
    desc.getMethodName match {
      case null => desc.getDisplayName
      case name => name
    }

  def run(suites: List[Class[? <: munit.Suite]], name_filter: Option[String],
      progress: Progress): Int = {
    /* drop suites with no matching test up front: filtering a runner
       down to zero tests is a JUnit error, not an empty run */
    val selected =
      name_filter match {
        case None => suites
        case Some(pattern) =>
          suites.filter(cls =>
            cls.getDeclaredConstructor().newInstance().munitTests()
              .exists(t => t.name.contains(pattern)))
      }
    if (selected.isEmpty) { progress.echo("no tests match"); return 0 }

    val core = new JUnitCore
    core.addListener(new RunListener {
      private var failed = Set.empty[String]
      override def testFailure(failure: JUnit_Failure): Unit = {
        failed += failure.getDescription.getDisplayName
        progress.echo_error_message(
          "FAIL " + test_name(failure.getDescription) + "\n" + failure.getMessage)
      }
      override def testFinished(desc: Description): Unit =
        if (!failed(desc.getDisplayName)) progress.echo("PASS " + test_name(desc))
    })

    var req = Request.classes(selected*)
    for (pattern <- name_filter) {
      req = req.filterWith(new Filter {
        override def shouldRun(desc: Description): Boolean =
          (desc.isTest && desc.getDisplayName.contains(pattern)) ||
            desc.getChildren.asScala.exists(shouldRun)
        override def describe(): String = "name contains " + quote(pattern)
      })
    }
    core.run(req).getFailureCount
  }
}
