/*  Title:      mcp/src/mcp_session.scala

Headless PIDE session serving MCP tools registered in Isabelle/ML.

The ML side (mcp/Tools/mcp_bridge.ML) defines one versioned protocol command.
Typed operations select a context locator and complete promises by internal
string request id.
*/

package isabelle.mcp

import isabelle._
import isabelle.mcp.pide.{BridgeFailure, BridgeResult,
  PideBridge, PideBridgePolicy, PideBridgeV1, SessionPideTransport}
import isabelle.mcp.application.McpApplication

import scala.util.control.NonFatal
import java.util.concurrent.locks.ReentrantLock


/* what the JSON-RPC layer (MCP_Server.Handler) needs from the prover side:
   MCP_Session is the real implementation, tests substitute MCP_Test.Fake_Backend */
trait MCP_Backend {
  /* Scala-side builtins do not cross a protocol route, but they still run in
     an admitted connection worker.  The real backend makes their blocking
     work interruptible; test backends may retain the direct default. */
  def direct_cancellable[A](cancellation: McpApplication.Cancellation)(body: => A): A = body
  /* ML creates and canonicalizes context locators; Scala stores them as
     opaque strings. root_context obtains the locator selected by the
     connection's registry-root theory. */
  def root_context(): MCP_Session.Result
  def root_context_cancellable(
    cancellation: McpApplication.Cancellation): MCP_Session.Result = root_context()
  /* Rows carry full internal names, form tags and declared params relative to
     the selected context. Exposed client names remain a Scala concern. */
  def ml_tools(context: String): MCP_Session.Tools_Reply
  def ml_tools_cancellable(context: String,
    cancellation: McpApplication.Cancellation): MCP_Session.Tools_Reply =
    ml_tools(context)
  def ml_run(name: String, args: List[(String, String)],
    context: String): MCP_Session.Result
  def ml_run_cancellable(name: String, args: List[(String, String)],
    context: String,
    cancellation: McpApplication.Cancellation): MCP_Session.Result =
    ml_run(name, args, context)
  /* Validate and canonicalize a candidate locator without committing it. */
  def check_context(context: String): MCP_Session.Result
  def check_context_cancellable(context: String,
    cancellation: McpApplication.Cancellation): MCP_Session.Result =
    check_context(context)
  /* registration events (MCP.tools_changed / MCP.resources_changed from
     MCP_Tool.declare): the server loop registers a callback that pushes
     the matching notifications/{tools,resources}/list_changed line to the
     client. Default: drop (Fake_Backend tests set their own). */
  def set_changed_handler(handler: String => Unit): Unit = ()
  def ir(fname: String, args: List[(String, String)]): MCP_Session.Result
  def ir_cancellable(fname: String, args: List[(String, String)],
    cancellation: McpApplication.Cancellation): MCP_Session.Result =
    ir(fname, args)
  /* context-taking tools (find_theorems' "context promotion", later
     find_definition): resolve a client-given theory name to the
     canonical Thy_Info key the ir bridge needs, same normalization as
     image_theory/resolve_theory. Right = resolved, ready to cross the
     bridge; Left = a user-facing error message (unknown name, or a
     filesystem-tier theory that needs load_theory first -- Thy_Info has
     no entry for those, so there is no context to search). */
  def resolve_context_theory(name: String): Either[String, String]
  /* repl_init_from_source (plans/repl_init_from_source): create `repl`
     rooted at the command/segment `theory` resolves the given locator
     to. Callers have already checked exactly one of offset/pattern/
     index is set (MCP_Session.Locator.exactly_one) -- this only needs
     to resolve tier and locator, then dispatch to the right ir fname. */
  def init_from_source(repl: String, theory: String,
    offset: Option[Int], pattern: Option[String], index: Option[Int]): MCP_Session.Result
  def init_from_source_cancellable(repl: String, theory: String,
    offset: Option[Int], pattern: Option[String], index: Option[Int],
    cancellation: McpApplication.Cancellation): MCP_Session.Result =
    init_from_source(repl, theory, offset, pattern, index)
  def mcp_resources(): List[(String, String, String)]
  def mcp_resources_cancellable(
    cancellation: McpApplication.Cancellation): List[(String, String, String)] =
    mcp_resources()
  def mcp_resource_read(uri: String): MCP_Session.Result
  def mcp_resource_read_cancellable(uri: String,
    cancellation: McpApplication.Cancellation): MCP_Session.Result =
    mcp_resource_read(uri)
  /* scope_add/scope_remove (plans/scope_add, plans/scope_remove, spec
     "scoping"): the resource scope is a set of theory-name glob patterns,
     scala-side only, no bridge. mcp_resources() enumerates matches
     against the full known theory universe (tier-tagged) plus the
     implicit working set (theories loaded via load_theory/check_theory,
     tracked independently of scope_patterns); scope never limits
     mcp_resource_read, only the listing. */
  def scope_add(patterns: List[String]): MCP_Session.Result
  def scope_remove(patterns: List[String]): MCP_Session.Result
  /* scope_show (plans/scope_show): the read side of S1 -- explicit
     patterns with match counts, plus every implicit member (theories
     loaded via load_theory/check_theory, active REPLs, registered named
     resources). No bridge of its own: patterns/theories/named resources
     are already scala-tracked state, and REPLs are read by reusing the
     existing "repls" ir fname (repl_list's own bridge call) rather than
     adding a new one. */
  def scope_show(): MCP_Session.Result
  def scope_show_cancellable(
    cancellation: McpApplication.Cancellation): MCP_Session.Result =
    scope_show()
  def load_theory(name: String, master_dir: String): MCP_Session.Result
  def unload_theory(name: String): MCP_Session.Result
  def check_theory(name: String, master_dir: String): MCP_Session.Result
  def list_sessions_info(): MCP_Session.Result
  def list_theories_info(session: String): MCP_Session.Result
  def search_sources(pattern: String): MCP_Session.Result
  /* doc_list (plans/doc_list, wave 5, spec "documentation for the agent"):
     the Doc.contents() catalog joined to doc sessions, computed once at
     startup (Doc_Catalog.make); not scope-filtered (catalog items, not
     theories -- discovery is never scoped). */
  def doc_list(pattern: String): MCP_Session.Result
  /* doc_read (plans/doc_read, wave 5): resolves `name` through the same
     Doc_Catalog doc_list serves. section addresses manuals (toc without
     it, that section's source text with it); lines addresses plain
     entries (NEWS, examples); the two are mutually exclusive, enforced
     by the handler before any catalog lookup. */
  def doc_read(name: String, section: String, lines: String): MCP_Session.Result
  def stop(): Unit
}

object MCP_Session {
  sealed abstract class Result { def ok: Boolean }
  case class Ok(text: String) extends Result { def ok = true }
  case class Error(message: String) extends Result { def ok = false }

  /* mirrors MCP_Tool.ptyp (MCP_Tools.thy) exactly -- the isar params
     clause's closed type universe (plans/param_schema_v2). Enum/List_Of
     are not reachable from isar until steps 3/4 land their parsers, but
     the ADT (like the ML datatype) carries them from this step on. */
  sealed abstract class Ptyp
  case object Ptyp_String extends Ptyp
  case object Ptyp_Source extends Ptyp
  case object Ptyp_Args extends Ptyp
  case object Ptyp_Nat extends Ptyp
  case object Ptyp_Int extends Ptyp
  case object Ptyp_Bool extends Ptyp
  case object Ptyp_Term extends Ptyp
  case object Ptyp_Typ extends Ptyp
  case object Ptyp_Fact extends Ptyp
  case class Ptyp_Enum(items: List[String]) extends Ptyp
  case class Ptyp_List_Of(elem: Ptyp) extends Ptyp

  /* TAG ORDER MUST MATCH MCP_Tools.thy's encode_ptyp EXACTLY: 0 String,
     1 Source, 2 Args, 3 Nat, 4 Int, 5 Bool, 6 Term, 7 Typ, 8 Fact,
     9 Enum, 10 List_Of. Every nullary scalar encodes to identical bytes
     (empty attributes, empty body), so a mis-ordered list here would
     decode e.g. Nat as Int SILENTLY -- no exception, just a wrong json
     type (plans/param_schema_v2, "THE HAZARD"). Recursive for List_Of,
     hence a `def`, not a `val`. */
  def decode_ptyp(body: XML.Body): Ptyp = {
    import XML.Decode._
    variant[Ptyp](List(
      { case _ => Ptyp_String },
      { case _ => Ptyp_Source },
      { case _ => Ptyp_Args },
      { case _ => Ptyp_Nat },
      { case _ => Ptyp_Int },
      { case _ => Ptyp_Bool },
      { case _ => Ptyp_Term },
      { case _ => Ptyp_Typ },
      { case _ => Ptyp_Fact },
      { case (_, ts) => Ptyp_Enum(list(string)(ts)) },
      { case (_, ts) => Ptyp_List_Of(decode_ptyp(ts)) }))(body)
  }

  case class Tool_Param(
    name: String,
    typ: Ptyp,
    required: Boolean,
    default: Option[String],
    description: String)

  /* mirrors MCP_Tool.annotations (MCP_Tools.thy) exactly -- the four MCP
     hint flags, each independently absent (no premise proven) or set
     (plans/param_schema_v2 step 5). No tag/variant hazard here (unlike
     Ptyp): every field is an independent option, so there is no
     positional ambiguity between two encoded values to get wrong. */
  case class Tool_Annotations(
    read_only: Option[Boolean],
    idempotent: Option[Boolean],
    destructive: Option[Boolean],
    open_world: Option[Boolean])

  object Tool_Annotations {
    /* MCP_Tool.default_annotations's mirror: a declaration whose form
       tag proves nothing about its behavior (plans/param_schema_v2). */
    val default: Tool_Annotations = Tool_Annotations(None, None, None, Some(false))
  }

  def decode_annotations(body: XML.Body): Tool_Annotations = {
    import XML.Decode._
    val (read_only, (idempotent, (destructive, open_world))) =
      pair(option(bool), pair(option(bool), pair(option(bool), option(bool))))(body)
    Tool_Annotations(read_only, idempotent, destructive, open_world)
  }

  case class Tool_Row(
    name: String,
    description: String,
    form: String,
    params: List[Tool_Param],
    annotations: Tool_Annotations)

  /* The tools operation payload (plans/builtin_activation): ML rows (active,
     non-builtin ML tools) plus a builtins section -- (base name, active)
     for EVERY registered Builtin-form mirror in MCP_Tools.thy, inactive
     included, so an empty section is distinguishable from "every mirror
     del'd" (the AVAILABILITY FLOOR guardrail: empty -> scala serves the
     full builtin table). */
  case class Tools_Reply(rows: List[Tool_Row], builtin_activation: List[(String, Boolean)])

  def decode_tools(body: XML.Body): List[Tool_Row] = {
    import XML.Decode._
    list(pair(string, pair(string, pair(string,
      pair(list(pair(string, pair(decode_ptyp _, pair(bool, pair(option(string), string))))),
        decode_annotations _)))))(body)
      .map({ case (name, (description, (form, (params, annotations)))) =>
        Tool_Row(name, description, form,
          params.map({ case (n, (t, (r, (d, ds)))) => Tool_Param(n, t, r, d, ds) }),
          annotations)
      })
  }

  def decode_tools_reply(body: XML.Body): Tools_Reply = {
    import XML.Decode._
    val (rows, activation) = pair(decode_tools, list(pair(string, bool)))(body)
    Tools_Reply(rows, activation)
  }

  def decode_resources(body: XML.Body): List[(String, String)] = {
    import XML.Decode._
    list(pair(string, string))(body)
  }

  def decode_theories(body: XML.Body): List[String] = {
    import XML.Decode._
    list(string)(body)
  }

  /* MCP.ir argument encoding (see the spec's "argument encoding"): named
     args as one yxml chunk holding an association list of (key, value)
     string pairs, list-valued arguments as repeated keys; the ML dispatcher
     decodes with the mirror MCP_Repl.decode_args.

     The inbound half of the client-edge recoding boundary (spec: "symbol
     recoding at the client edge"): recode = Symbol.encode turns unicode
     back into the symbol notation ML speaks, so a model may send either
     form. YXML.string_of_body applies recode to TEXT NODES ONLY
     (Pure/PIDE/yxml.scala, Output_String.string) -- which is why the
     recode goes here as a parameter and never over an assembled chunk:
     running Symbol.encode across finished yxml would walk its X/Y
     control bytes. Isabelle/Scala does the same thing one layer down in
     prover.scala's protocol_command_args (Symbol.encode_yxml), but the
     bridge uses protocol_command_raw, which skips it. Symbol.encode is a
     no-op on text that is already symbol notation (pure ascii, so its
     recoder never fires), so \<open> in a model-authored isar_text
     survives byte-identical. */
  def encode_args(args: List[(String, String)]): String = {
    import XML.Encode._
    YXML.string_of_body(list(pair(string, string))(args), recode = Symbol.encode)
  }

  def decode_args(body: XML.Body): List[(String, String)] = {
    import XML.Decode._
    list(pair(string, string))(body)
  }

  /* scope_add/scope_remove glob patterns ("HOL-Library.*", "Main"): '*'
     matches any run of characters, everything else (including '.') is
     literal -- theory long names use '.' as a qualifier separator, not a
     regex metachar, so it must be escaped like any other literal. */
  /* shared locator resolution (plans/repl_init_from_source step 2: "PURE
     where possible ... unit-tests without a prover"), reused by
     repl_init_from_source's PIDE-snapshot branch and (planned) by
     goto_definition. Items are pre-extracted (id, offset, length,
     source) so the same function serves any ordered list of addressable
     spans -- a Document.Node's commands here, an offset/pattern/index
     triple picks exactly one by construction once exactly_one has
     already been checked. */
  object Locator {
    case class Item(id: Long, offset: Text.Offset, length: Int, source: String)

    def exactly_one(offset: Option[Int], pattern: Option[String], index: Option[Int]): Either[String, Unit] = {
      val n = List(offset.isDefined, pattern.isDefined, index.isDefined).count(identity)
      if (n == 0) Left("exactly one of offset, pattern, index is required")
      else if (n > 1) Left("exactly one of offset, pattern, index is required (got more than one)")
      else Right(())
    }

    def resolve(items: List[Item], offset: Option[Int], pattern: Option[String],
        index: Option[Int]): Either[String, Long] =
      (offset, pattern, index) match {
        case (Some(o), None, None) =>
          items.find(it => o >= it.offset && o < it.offset + it.length) match {
            case Some(it) => Right(it.id)
            case None => Left("offset " + o + " is not inside any command")
          }
        case (None, Some(p), None) =>
          items.find(_.source.contains(p)) match {
            case Some(it) => Right(it.id)
            case None => Left("pattern " + quote(p) + " not found")
          }
        case (None, None, Some(i)) =>
          val idx = if (i < 0) items.length + i else i
          if (idx >= 0 && idx < items.length) Right(items(idx).id)
          else Left("index " + i + " out of range (0.." + (items.length - 1) + ")")
        case _ => Left("exactly one of offset, pattern, index is required")
      }
  }

  def glob_to_regex(pattern: String): scala.util.matching.Regex = {
    val sb = new StringBuilder
    for (c <- pattern) {
      if (c == '*') sb.append(".*")
      else if ("\\.+()[]{}|^$?".contains(c)) { sb.append('\\'); sb.append(c) }
      else sb.append(c)
    }
    ("\\A" + sb.toString + "\\z").r
  }

  /* the build half (plans/readiness): runs on the background thread while
     the json-rpc loop is already serving stdin. Split out of start() so
     MCP_Server.run can publish a progress string between build and boot;
     start() below stays the synchronous build+boot convenience the test
     suites (MCP_Session_Suite) use directly. */
  def build(
    options: Options,
    session_name: String,
    session_dirs: List[Path],
    progress: Progress = new Progress
  ): Unit = {
    val build_results =
      Build.build(options, selection = Sessions.Selection.session(session_name),
        progress = progress, build_heap = true, dirs = session_dirs)
    if (!build_results.ok) {
      error("Failed to build session " + quote(session_name) + ": " +
        Process_Result.RC.print(build_results.rc))
    }
  }

  /* the boot half (plans/readiness): assumes build() already produced a
     current heap. Headless.Resources.start_session boots from
     store.session_heaps -- there is nothing lazy left on the ML side, this
     is the earliest the prover can come up. */
  def boot(
    options: Options,
    session_name: String,
    session_dirs: List[Path],
    theory: String,
    bridgeProfile: McpBridgeProfile,
    progress: Progress = new Progress
  ): MCP_Session = {
    val resources =
      Headless.Resources.make(options, session_name, session_dirs = session_dirs,
        progress = progress)
    val session = resources.start_session(progress = progress)

    /* wave 3 shared infrastructure: load_structure + deps + store, compute
       derived maps for library discovery (session_structure umbrella plan) */
    val structure = Sessions.load_structure(options, dirs = session_dirs)
    val deps = Sessions.deps(structure, progress = progress)
    val store = Store(options)

    val bridgeMaxPending =
      PideBridgePolicy.MaxPending.checked(options.int("mcp_max_in_flight"))
        .fold(error, identity)
    val mcp_session = new MCP_Session(session, session_name, session_dirs, theory,
      structure, deps, store, bridgeMaxPending, bridgeProfile)

    /* theories already in the session image keep their protocol commands
       (defined at build time, persisted in the heap); anything else is
       loaded into the running session. The image qualifies theories by
       their DEFINING session (MCP_Tools lives in the image as
       "MCP-Tools.MCP_Tools" even when the running session is MCP-HOL),
       so an unqualified -T must also match by base name -- otherwise the
       default configuration only works when a -d happens to make the
       theory file findable on disk. */
    val loaded =
      resources.loaded_theory(theory) ||
      resources.loaded_theory(Long_Name.qualify(session_name, theory)) ||
      (!Long_Name.is_qualified(theory) &&
        resources.session_base.loaded_theories.keys.exists(Long_Name.base_name(_) == theory))
    if (!loaded) {
      val master_dir =
        session_dirs.headOption.map(File.standard_path).getOrElse("")
      val use_result =
        session.use_theories(List(theory), master_dir = master_dir, progress = progress)
      if (!use_result.ok) {
        session.stop()
        error("Failed to load theory " + quote(theory))
      }
    }

    val startupTimeout =
      PideBridgePolicy.PositiveSeconds.checked(
        "mcp_request_timeout", options.real("mcp_request_timeout")).fold(error, identity)
    mcp_session.await_bridge_ready(PideBridgePolicy.PositiveSeconds.value(startupTimeout)) match {
      case Right(()) => ()
      case Left(failure) =>
        mcp_session.stop()
        error("PIDE bridge startup hello failed: " + failure.message)
    }

    mcp_session
  }

  def start(
    options: Options,
    session_name: String,
    session_dirs: List[Path],
    theory: String,
    bridgeProfile: McpBridgeProfile,
    progress: Progress = new Progress
  ): MCP_Session = {
    build(options, session_name, session_dirs, progress)
    boot(options, session_name, session_dirs, theory, bridgeProfile, progress)
  }
}

class MCP_Session private(
  val session: Headless.Session,
  val session_name: String,
  val session_dirs: List[Path],
  val theory: String,
  val structure: Sessions.Structure,
  val deps: Sessions.Deps,
  val store: Store,
  bridgeMaxPending: PideBridgePolicy.MaxPending,
  bridgeProfile: McpBridgeProfile
) extends MCP_Backend {
  private final class DirectOperation {
    val id: String = UUID.random().toString
    val thread: Thread = Thread.currentThread()
    val done: Promise[Unit] = Future.promise[Unit]
    private var active = true
    private var signalled = false

    def interrupt(): Unit = synchronized {
      if (active && !signalled) {
        signalled = true
        thread.interrupt()
      }
    }

    def finish(): Unit = synchronized {
      active = false
      /* BoundedConcurrentScheduler reuses JVM workers.  Never leak one
         request's interrupt status into the next admitted request. */
      Thread.interrupted()
    }
  }

  private case class DirectState(
    closing: Boolean = false,
    operations: Map[String, DirectOperation] = Map.empty)

  private val direct_state = Synchronized(DirectState())

  override def direct_cancellable[A](
      cancellation: McpApplication.Cancellation)(body: => A): A = {
    val operation = new DirectOperation
    val admitted = direct_state.change_result { state =>
      if (state.closing) (false, state)
      else
        (true, state.copy(operations = state.operations + (operation.id -> operation)))
    }
    if (!admitted) throw Exn.Interrupt()
    cancellation.onCancel(() => operation.interrupt())
    try {
      if (cancellation.isCancelled) throw Exn.Interrupt()
      body
    }
    finally {
      operation.finish()
      direct_state.change(state =>
        state.copy(operations = state.operations - operation.id))
      operation.done.fulfill(())
    }
  }

  private def begin_direct_shutdown(): List[DirectOperation] =
    direct_state.change_result(state =>
      (state.operations.values.toList, state.copy(closing = true)))

  private val theory_mutation_lock = new ReentrantLock(true)
  private val theory_mutation_probe = Synchronized[() => Unit](() => ())

  private[mcp] def set_theory_mutation_probe(probe: () => Unit): Unit =
    theory_mutation_probe.change(_ => probe)

  private def serialized_theory_mutation[A](body: => A): A = {
    theory_mutation_lock.lockInterruptibly()
    try {
      theory_mutation_probe.value()
      body
    }
    finally theory_mutation_lock.unlock()
  }
  /* wave 3 infrastructure: derived maps over structure + deps (computed
     once at startup for library discovery) */
  private val sessions_map: Map[String, (String, String, List[String])] = {
    structure.imports_graph.keys.foldLeft(Map.empty[String, (String, String, List[String])]) {
      case (acc, name) =>
        val info = structure(name)
        val theories = deps.get(name) match {
          case Some(base) =>
            base.known_theories.keys.map(Long_Name.base_name).toList.distinct.sorted
          case None => Nil
        }
        acc + (name -> (info.chapter, info.description, theories))
    }
  }

  private val theory_map: Map[String, (String, Path)] = {
    structure.imports_graph.keys.foldLeft(Map.empty[String, (String, Path)]) {
      case (acc, sess_name) =>
        deps.get(sess_name) match {
          case Some(base) =>
            base.known_theories.foldLeft(acc) { case (acc2, (thy_long_name, entry)) =>
              acc2 + (thy_long_name -> (sess_name, entry.name.path))
            }
          case None => acc
        }
    }
  }

  private val base_names: Map[String, List[String]] = {
    theory_map.keys.groupBy(Long_Name.base_name).view.mapValues(_.toList.sorted).toMap
  }

  /* wave 5 (plans/doc_list): the documentation catalog, computed once at
     startup alongside the maps above (same lifecycle, same rationale --
     pure parsing, no heaps). */
  private val doc_catalog: List[Doc_Catalog.Section] = Doc_Catalog.make(structure)

  /* wave 5 (plans/doc_read): a manual's chapter toc, memoized per source
     session -- the sources are read-only distribution files, so scanning
     is done lazily (only manuals doc_read is actually asked about pay the
     cost) and cached forever once computed. */
  private val doc_toc_cache: Synchronized[Map[String, List[Doc_Catalog.Heading]]] =
    Synchronized(Map.empty)

  private def manual_files(session_name: String): List[Path] =
    deps.get(session_name) match {
      case Some(base) => base.proper_session_theories.map(_.path)
      case None => Nil
    }

  private def manual_toc(session_name: String): List[Doc_Catalog.Heading] =
    doc_toc_cache.change_result { cache =>
      cache.get(session_name) match {
        case Some(toc) => (toc, cache)
        case None =>
          val toc = Doc_Catalog.toc(manual_files(session_name))
          (toc, cache + (session_name -> toc))
      }
    }

  private val changed_handler: Synchronized[String => Unit] =
    Synchronized(_ => ())
  private object RegistryChange {
    final case class Entry(function: String, event: String)
    val Tools = Entry("MCP.tools_changed", "tools")
    val Resources = Entry("MCP.resources_changed", "resources")
  }
  private object ChangeHandler extends Session.Protocol_Handler {
    private def changed(change: RegistryChange.Entry)(msg: Prover.Protocol_Output): Boolean = {
      changed_handler.value(change.event)
      true
    }

    private def tools_changed(msg: Prover.Protocol_Output): Boolean =
      changed(RegistryChange.Tools)(msg)

    private def resources_changed(msg: Prover.Protocol_Output): Boolean =
      changed(RegistryChange.Resources)(msg)

    override val functions: Session.Protocol_Functions =
      List(
        RegistryChange.Tools.function -> tools_changed,
        RegistryChange.Resources.function -> resources_changed)
  }

  session.init_protocol_handler(ChangeHandler)

  private val bridge =
    new PideBridge(
      new SessionPideTransport(session, PideBridgeV1.resultFunctions),
      bridgeMaxPending,
      theory,
      McpBridgeOperations.operationNames,
      bridgeProfile,
      PideBridgeV1)

  private[mcp] def await_bridge_ready(timeoutSeconds: Double): BridgeResult[Unit] =
    bridge.awaitReady(timeoutSeconds)

  private[mcp] def bridge_operation_names: Set[String] = bridge.advertisedOperationNames

  private def bridge_value[A](result: BridgeResult[A]): A =
    result match {
      case Right(value) => value
      case Left(BridgeFailure.Cancelled) => throw Exn.Interrupt()
      case Left(failure) => error(failure.message)
    }

  private def bridge_result(result: BridgeResult[MCP_Session.Result]): MCP_Session.Result =
    result match {
      case Right(value) => value
      case Left(BridgeFailure.Cancelled) => MCP_Session.Error("Request cancelled")
      case Left(failure) => MCP_Session.Error(failure.message)
    }

  override def set_changed_handler(handler: String => Unit): Unit =
    changed_handler.change(_ => handler)

  /* PideBridgeV1 serializes the entire typed envelope with Symbol.encode as
     the client-edge recoding step. Operation codecs therefore manipulate XML
     values and never recode an already assembled YXML string. */

  def root_context(): MCP_Session.Result =
    root_context_cancellable(McpApplication.Cancellation.Never)

  override def root_context_cancellable(
      cancellation: McpApplication.Cancellation): MCP_Session.Result =
    bridge_result(bridge.call(McpBridgeOperations.checkContext(None), cancellation))

  private def root_context_value(cancellation: McpApplication.Cancellation): String =
    root_context_cancellable(cancellation) match {
      case MCP_Session.Ok(context) => context
      case MCP_Session.Error(message) => error(message)
    }

  def ml_tools(): MCP_Session.Tools_Reply =
    ml_tools(root_context_value(McpApplication.Cancellation.Never))

  def ml_tools(context: String): MCP_Session.Tools_Reply =
    ml_tools_cancellable(context, McpApplication.Cancellation.Never)

  override def ml_tools_cancellable(context: String,
      cancellation: McpApplication.Cancellation): MCP_Session.Tools_Reply =
    bridge_value(bridge.call(McpBridgeOperations.tools(context), cancellation))

  def ml_theories(): List[String] =
    ml_theories_cancellable(McpApplication.Cancellation.Never)

  private[mcp] def ml_theories_cancellable(
      cancellation: McpApplication.Cancellation): List[String] =
    bridge_value(bridge.call(McpBridgeOperations.theories, cancellation))

  def ml_run(name: String, args: List[(String, String)],
      context: String): MCP_Session.Result =
    ml_run_cancellable(name, args, context, McpApplication.Cancellation.Never)

  def ml_run(name: String, args: List[(String, String)]): MCP_Session.Result =
    ml_run(name, args, root_context_value(McpApplication.Cancellation.Never))

  override def ml_run_cancellable(name: String, args: List[(String, String)],
      context: String,
      cancellation: McpApplication.Cancellation): MCP_Session.Result =
    bridge_result(bridge.call(
      McpBridgeOperations.runTool(context, name, args), cancellation))

  /* Validate and canonicalize a context locator before the application
     commits it as per-connection state. */
  def check_context(context: String): MCP_Session.Result =
    check_context_cancellable(context, McpApplication.Cancellation.Never)

  override def check_context_cancellable(context: String,
      cancellation: McpApplication.Cancellation): MCP_Session.Result =
    bridge_result(bridge.call(
      McpBridgeOperations.checkContext(Some(context)), cancellation))

  /* MCP.ir: the I/R engine dispatcher (MCP_Repl.thy), named args, async
     (a slow call must not block a concurrent fast one) */
  def ir(fname: String, args: List[(String, String)]): MCP_Session.Result =
    ir_cancellable(fname, args, McpApplication.Cancellation.Never)

  override def ir_cancellable(fname: String, args: List[(String, String)],
      cancellation: McpApplication.Cancellation): MCP_Session.Result =
    bridge_result(bridge.call(McpBridgeOperations.ir(fname, args), cancellation))

  /* isabelle://named/{name}: user-registered resources from MCP_Resource
     (MCP_Tools.thy), the mcp_tool/mcp_resource ML registry -- mirrors
     ml_tools()/ml_run() exactly (MCP_Resource is MCP_Tool's sibling). */
  def ml_named_resources(context: String): List[(String, String)] =
    ml_named_resources_cancellable(context, McpApplication.Cancellation.Never)

  def ml_named_resources(): List[(String, String)] =
    ml_named_resources(root_context_value(McpApplication.Cancellation.Never))

  private[mcp] def ml_named_resources_cancellable(context: String,
      cancellation: McpApplication.Cancellation): List[(String, String)] =
    bridge_value(bridge.call(McpBridgeOperations.resources(context), cancellation))

  def ml_read_resource(name: String, context: String): MCP_Session.Result =
    ml_read_resource_cancellable(name, context, McpApplication.Cancellation.Never)

  def ml_read_resource(name: String): MCP_Session.Result =
    ml_read_resource(name, root_context_value(McpApplication.Cancellation.Never))

  private[mcp] def ml_read_resource_cancellable(name: String, context: String,
      cancellation: McpApplication.Cancellation): MCP_Session.Result =
    bridge_result(bridge.call(McpBridgeOperations.readResource(context, name), cancellation))

  /* isabelle://session: the cheap always-there overview (name, dirs, loaded
     theory, loaded theories via Thy_Info.get_names()) */
  def mcp_resources(): List[(String, String, String)] =
    mcp_resources_cancellable(McpApplication.Cancellation.Never)

  override def mcp_resources_cancellable(
      cancellation: McpApplication.Cancellation): List[(String, String, String)] = {
    val rows = ml_named_resources_cancellable(root_context_value(cancellation), cancellation)
    val exposed = MCP_Server.exposure(rows.map(_._1))
    val universe = known_theory_tiers()
    val regexes = scope_patterns.value.map(MCP_Session.glob_to_regex)
    val pattern_matched = universe.keys.filter(name => regexes.exists(_.matches(name))).toSet
    /* the implicit working set: theories loaded via load_theory/
       check_theory are in scope regardless of any pattern (S1's "loading
       a theory auto-adds it to scope"); image theories are NOT dumped in
       by default -- they are the search space patterns filter into, not
       the default listing. */
    val scoped_theories = (theory_master_dirs.value.keySet ++ pattern_matched).toList.sorted
    ("isabelle://session", "session", "current session name, dirs, loaded theories") ::
    rows.flatMap { case (name, description) =>
      exposed.get(name).map(x => ("isabelle://named/" + x, x, description))
    } ++
    scoped_theories.map { name =>
      val tier = universe.getOrElse(name, LoadedTier)
      ("isabelle://theory/" + name, name, "theory (" + tier.name + ")")
    } ++
    active_repl_ids(cancellation).map(id => ("isabelle://repl/" + id, id, "repl"))
  }

  /* MCP.ir (fname "repls" included) is a protocol command defined ONLY by
     MCP_Repl.thy (the HOL/Ir layer) -- sessions built on the base
     MCP_Tools.thy alone (e.g. this project's own "MCP-Tools" test
     session) never register it. Calling ir() there would send a
     protocol command nothing answers, leaving the promise unfulfilled
     forever: a real hang, hit once by mcp_resources()/scope_show()
     unconditionally calling active_repl_ids() against such a session.
     Guard on whether MCP_Repl is actually in this session's image
     (computed once, no protocol round-trip) before ever sending "repls". */
  private val repl_bridge_available: Boolean =
    session.resources.session_base.loaded_theories.keys.exists(Long_Name.base_name(_) == "MCP_Repl")

  /* active REPL ids, read by reusing repl_list's own "repls" ir fname
     rather than adding a new bridge call (plans/scope_show: "no bridge").
     Ir.repls() (ir/ir.ML) only ever formats human text through `out`, one
     line per repl: "    ID (n steps..., from ..., ...)" -- the id is the
     token up to the first " (". */
  private val repl_line = """\A\s*(\S+) \(.*\)\z""".r
  private def active_repl_ids(
      cancellation: McpApplication.Cancellation = McpApplication.Cancellation.Never): List[String] =
    if (!repl_bridge_available) Nil
    else ir_cancellable("repls", Nil, cancellation) match {
      case MCP_Session.Ok(text) => text.linesIterator.collect({ case repl_line(id) => id }).toList
      case MCP_Session.Error(_) => Nil
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
    mcp_resource_read_cancellable(uri, McpApplication.Cancellation.Never)

  override def mcp_resource_read_cancellable(uri: String,
      cancellation: McpApplication.Cancellation): MCP_Session.Result =
    uri match {
      case "isabelle://session" =>
        MCP_Session.Ok(
          "session: " + session_name + "\n" +
          "dirs: " + session_dirs.map(_.implode).mkString(", ") + "\n" +
          "theory: " + theory + "\n" +
          "theories: " + ml_theories_cancellable(cancellation).mkString(", "))
      /* isabelle://repl/{id} and .../text (spec's resource templates):
         thin dispatch onto the same MCP.ir bridge repl_show/repl_text
         use, so a REPL is readable as a resource with no separate
         backing mechanism. */
      case repl_text_uri(repl_id) =>
        ir_cancellable("text", List("repl" -> repl_id), cancellation)
      case repl_uri(repl_id) =>
        ir_cancellable("show", List("repl" -> repl_id), cancellation)
      /* isabelle://named/{name}: thin dispatch onto MCP_Resource's own
         registry via the MCP.read_resource protocol command -- mirrors
         MCP.run_tool exactly (see ml_read_resource above). The uri holds
         the EXPOSED name; resolve it back to the full internal name
         through the same exposure map resources/list used. */
      case named_uri(name) =>
        val context = root_context_value(cancellation)
        val exposed =
          MCP_Server.exposure(ml_named_resources_cancellable(context, cancellation).map(_._1))
        val internal = exposed.collectFirst({ case (i, x) if x == name => i }).getOrElse(name)
        ml_read_resource_cancellable(internal, context, cancellation)
      /* isabelle://theory/{name}/diagnostics: unblocked by wave 2
         (load_theory/check_theory), per the plans' gating chain. */
      case theory_diagnostics_uri(name) => theory_diagnostics(name)
      /* isabelle://theory/{name}/commands and the bare form (source):
         Ir.source_map/Ir.source (MCP.ir fnames "source_map"/"source",
         see MCP_Repl.thy's dispatcher) read off Thy_Info.
         get_theory_segments, so they work for image theories whose
         session was built with record_theories (mcp/Tools/ROOT sets it
         for this tree) -- segments recorded at build time DO survive
         the saved heap into a live server. (A previous KNOWN GAP note
         here claimed they never do; that was FALSE, an artifact of
         forwarding the client's unresolved name to ML: Thy_Info.
         get_theory errored "undefined entry" and ir.ML's find_source
         rewrote it as "No recorded segments". image_theory's
         normalization fixed it.) Theories from heaps built WITHOUT
         record_theories genuinely have no segments and get Isabelle's
         actionable rebuild hint. Non-image (wave-2-loaded or
         filesystem) theories fall through to not_yet_backed_uri below
         -- Headless.Session.use_theories goes through the PIDE
         document model, not Thy_Info, so they never have segments. */
      case theory_commands_uri(name) if image_tier(name) =>
        ir_cancellable("source_map",
          List("theory_name" -> image_theory(name).get, "start" -> "0", "stop" -> "-1"),
          cancellation)
      case theory_commands_uri(name) =>
        resolve_theory(name) match {
          case Some((_, FileSystemTier(_))) =>
            MCP_Session.Ok(name + ": filesystem theory — command map needs load_theory")
          /* a genuinely loaded (wave-2) theory and a wholly unrecognized
             name both lack a Thy_Info-recorded command map -- neither is
             "unknown" in the sense of resource reads (which report state
             rather than fail), so both get the documented not-backed-yet
             text rather than a bespoke error per case. */
          case _ =>
            MCP_Session.Error(
              "Resource " + quote("isabelle://theory/" + name + "/commands") +
              " is a documented template but not backed yet (only image theories built " +
              "with record_theories have a recorded command map); see spec's resource " +
              "templates section")
        }
      case theory_source_uri(name) if image_tier(name) =>
        ir_cancellable("source",
          List("theory_name" -> image_theory(name).get, "start" -> "0", "stop" -> "-1"),
          cancellation)
      case theory_source_uri(name) =>
        resolve_theory(name) match {
          case Some((_, FileSystemTier(path))) =>
            Exn.capture(File.read(path)) match {
              case Exn.Res(content) => MCP_Session.Ok(content)
              case Exn.Exn(exn) =>
                MCP_Session.Error(
                  "Failed to read " + quote(path.toString) + ": " + MCP_Server.plain_message(exn))
            }
          /* same rationale as /commands above: loaded and unrecognized
             alike, no recorded source to serve. */
          case _ =>
            MCP_Session.Error(
              "Resource " + quote("isabelle://theory/" + name) +
              " is a documented template but not backed yet (only image theories built " +
              "with record_theories have recorded source); see spec's resource " +
              "templates section")
        }
      /* isabelle://theory/{name}/entities: image tier via new ML
         (MCP_Repl.thy's entities function, dispatcher fname "entities")
         over Name_Space.theory_name filtering -- NOT the same gap as
         /commands above, since it reads per-entry bookkeeping baked
         into the theory value itself (heap-serialized), not
         Thy_Info.get_theory_segments (process-local, lost on restart).
         Loaded (wave-2) tier via PIDE entity-def markup on the live
         snapshot -- see theory_entities below. Filesystem (never
         loaded) theories still fall through to not_yet_backed_uri. */
      case theory_entities_uri(name) => theory_entities(name, cancellation)
      /* documented templates (resource_templates in mcp_server.scala)
         whose backing needs a later wave -- only true for unknown theories
         now (steps 2-3 backed all documented paths). */
      case not_yet_backed_uri(_) =>
        MCP_Session.Error(
          "Unknown theory in resource " + quote(uri) +
          "; see list_theories or search_sources to discover available theories")
      case _ => MCP_Session.Error("Unknown MCP resource " + quote(uri))
    }

  /* image-tier name normalization: clients name theories in whatever
     form they know -- base ("MCP_Repl", the -T spelling), this session's
     qualifier ("MCP-HOL.MCP_Repl"), a foreign qualifier for a theory
     whose canonical key is unqualified ("HOL.Main" for "Main") -- but
     both loaded_theories here and Thy_Info on the ML side key by the
     canonical long name, and that keying itself mixes qualified and
     unqualified entries ("HOL.Wellfounded" vs plain "Main"). Resolve:
     verbatim, then qualified by this session, then a UNIQUE base-name
     match over the whole image; unknown or ambiguous -> None (not image
     tier). Whatever crosses the ir bridge must be the RESOLVED name,
     never the client's spelling: Thy_Info.get_theory is an exact lookup
     and errors with a bare "undefined entry" otherwise. */
  private def image_theory(name: String): Option[String] = {
    val resources = session.resources
    if (resources.loaded_theory(name)) Some(name)
    else {
      val qualified = Long_Name.qualify(session_name, name)
      if (resources.loaded_theory(qualified)) Some(qualified)
      else {
        val base = Long_Name.base_name(name)
        resources.session_base.loaded_theories.keys.filter(
          key => Long_Name.base_name(key) == base) match {
          case List(unique) => Some(unique)
          case _ => None
        }
      }
    }
  }

  private def image_tier(name: String): Boolean = image_theory(name).isDefined

  def resolve_context_theory(name: String): Either[String, String] =
    resolve_theory(name) match {
      case Some((resolved, ImageTier)) => Right(resolved)
      case Some((resolved, LoadedTier)) => Right(resolved)
      case Some((_, FileSystemTier(_))) =>
        Left(
          "Unknown theory " + quote(name) + " context: filesystem theory, not yet " +
            "loaded (no context to search) -- load_theory first")
      case None => Left("Unknown theory " + quote(name))
    }

  /* repl_init_from_source: loaded tier has a live PIDE snapshot, so the
     locator resolves against its command list (MCP_Session.Locator,
     command ids from Document.Node.command_iterator) and the REPL is
     created via the "init_from_document" ir fname -- the state AFTER
     the located command (Ir.init_from_document's eval_result_state).
     Image tier has no PIDE document; the analogous resolution happens
     ML-side against Thy_Info.get_theory_segments (MCP_Repl.thy's
     init_from_segment, dispatcher fname "init_from_segment") since
     segment text is only ever available in that process. Filesystem
     tier and unknown names have no context to attach to. */
  def init_from_source(repl: String, theory: String,
      offset: Option[Int], pattern: Option[String], index: Option[Int]): MCP_Session.Result =
    init_from_source_cancellable(
      repl, theory, offset, pattern, index, McpApplication.Cancellation.Never)

  override def init_from_source_cancellable(repl: String, theory: String,
      offset: Option[Int], pattern: Option[String], index: Option[Int],
      cancellation: McpApplication.Cancellation): MCP_Session.Result =
    resolve_theory(theory) match {
      case Some((resolved, LoadedTier)) =>
        theory_master_dirs.value.get(resolved) match {
          case Some(master_dir) =>
            val node_name =
              session.resources.import_name(
                Sessions.DRAFT, session.master_directory(master_dir), resolved)
            val snapshot = session.snapshot(node_name)
            val items =
              snapshot.node.command_iterator(Text.Range.full).toList
                .filter({ case (cmd, _) => cmd.is_proper })
                .map({ case (cmd, off) => MCP_Session.Locator.Item(cmd.id, off, cmd.length, cmd.source) })
            MCP_Session.Locator.resolve(items, offset, pattern, index) match {
              case Right(command_id) =>
                ir_cancellable("init_from_document",
                  List("repl" -> repl, "node_name" -> node_name.node,
                    "command_id" -> command_id.toString), cancellation)
              case Left(msg) => MCP_Session.Error("repl_init_from_source: " + msg)
            }
          case None =>
            MCP_Session.Error(quote(resolved) + ": loaded theory (no snapshot available)")
        }
      case Some((resolved, ImageTier)) =>
        ir_cancellable("init_from_segment",
          List("repl" -> repl, "theory_name" -> resolved) ++
            offset.toList.map(o => "offset" -> o.toString) ++
            pattern.toList.map(p => "pattern" -> p) ++
            index.toList.map(i => "index" -> i.toString), cancellation)
      case Some((_, FileSystemTier(_))) =>
        MCP_Session.Error(
          "Unknown theory " + quote(theory) + " context: filesystem theory, not yet " +
            "loaded (no context to attach to) -- load_theory first")
      case None => MCP_Session.Error("Unknown theory " + quote(theory))
    }

  /* resolve_theory: unified three-tier resolution for filesystem-tier
     resource widening (step 2), load_theory session-qualified resolution
     (step 3), and client-facing queries. Returns (long_name, tier, path)
     where tier is "image" | "loaded" | "filesystem" and path is only
     populated for filesystem tier. Checks tiers in order of authority. */
  sealed abstract class Tier { def name: String }
  case object ImageTier extends Tier { def name = "image" }
  case object LoadedTier extends Tier { def name = "loaded" }
  case class FileSystemTier(path: Path) extends Tier { def name = "filesystem" }

  private def resolve_theory(name: String): Option[(String, Tier)] = {
    image_theory(name) match {
      case Some(resolved) => Some((resolved, ImageTier))
      case None =>
        if (theory_master_dirs.value.contains(name)) {
          Some((name, LoadedTier))
        }
        else {
          theory_map.get(name) match {
            case Some((session, path)) => Some((name, FileSystemTier(path)))
            case None =>
              val base = Long_Name.base_name(name)
              base_names.get(base) match {
                case Some(candidates) if candidates.length == 1 =>
                  theory_map.get(candidates(0)) match {
                    case Some((session, path)) => Some((candidates(0), FileSystemTier(path)))
                    case None => None
                  }
                case _ => None
              }
          }
        }
    }
  }

  /* the spec's three-tier answer for isabelle://theory/{name}/diagnostics:
     image theories are "checked at build time" (no live snapshot to
     read); theories we've loaded via load_theory/check_theory (tracked
     in theory_master_dirs) get a live PIDE-snapshot diagnostics read,
     recomputed on every call (read-time evaluation, per the spec); any
     other name is a filesystem theory we haven't promoted, so it gets
     the documented "not checked" nudge rather than an error -- resource
     reads report state, they don't fail just because a theory happens
     to have errors or hasn't been loaded. */
  private def theory_diagnostics(name: String): MCP_Session.Result = {
    resolve_theory(name) match {
      case Some((resolved, ImageTier)) =>
        MCP_Session.Ok(resolved + ": checked at build time")
      case Some((resolved, LoadedTier)) =>
        theory_master_dirs.value.get(name) match {
          case Some(master_dir) =>
            val node_name =
              session.resources.import_name(
                Sessions.DRAFT, session.master_directory(master_dir), name)
            val snapshot = session.snapshot(node_name)
            val has_errors = snapshot.messages.exists { case (tree, _) => Protocol.is_error(tree) }
            val msgs = render_messages(snapshot.messages)
            val header = name + ": " + (if (has_errors) "error" else "ok")
            MCP_Session.Ok(if (msgs.isEmpty) header else header + "\n" + msgs.map("  " + _).mkString("\n"))
          case None => MCP_Session.Ok(name + ": loaded theory (status not available)")
        }
      /* a wholly unrecognized name is optimistic here too, same as a
         known-but-unloaded filesystem theory: diagnostics never fails
         just because a theory hasn't been indexed, and load_theory
         itself is the actionable next step either way (it will error
         cleanly there if the name really doesn't exist). */
      case Some((_, FileSystemTier(_))) | None =>
        MCP_Session.Ok(name + ": filesystem theory — not checked; load_theory to check")
    }
  }

  /* isabelle://theory/{name}/entities: image tier defers to the ir
     bridge's "entities" fname (MCP_Repl.thy, Name_Space.theory_name
     filtering -- works, unlike /commands, since it reads heap-
     serialized bookkeeping); loaded (wave-2) tier reads the live
     snapshot's entity-DEFINITION markup directly (Markup.Entity.Def --
     a def occurrence carries its own kind/name at its own position, no
     cross-referencing needed, since we're already sitting at the def
     site); filesystem (never loaded) theories get the same "documented
     template, not backed yet" text the generic not_yet_backed_uri
     fallback uses, since there is no PIDE snapshot and no image
     name-space to query. */
  private def theory_entities(name: String,
      cancellation: McpApplication.Cancellation): MCP_Session.Result = {
    resolve_theory(name) match {
      case Some((resolved, ImageTier)) =>
        ir_cancellable("entities", List("theory_name" -> resolved), cancellation)
      case Some((_, LoadedTier)) =>
        theory_master_dirs.value.get(name) match {
          case Some(master_dir) =>
            val node_name =
              session.resources.import_name(
                Sessions.DRAFT, session.master_directory(master_dir), name)
            val snapshot = session.snapshot(node_name)
            val doc = Line.Document(snapshot.node.source)
            val defs =
              snapshot.select(Text.Range.full, Markup.Elements(Markup.ENTITY), _ => {
                case Text.Info(range, elem) =>
                  (Markup.Entity.unapply(elem.markup), Markup.Entity.Def.unapply(elem.markup)) match {
                    case (Some((kind, entity_name)), Some(_)) =>
                      Some((kind, entity_name, range.start))
                    case _ => None
                  }
              }).map(_.info).distinct.sortBy(_._3)
            val header = "   kind     line  name"
            val rows =
              defs.map { case (kind, entity_name, offset) =>
                val line = doc.position(offset).line + 1
                "%8s  %6d  %s".format(kind, line, entity_name)
              }
            MCP_Session.Ok(if (rows.isEmpty) header else header + "\n" + rows.mkString("\n"))
          case None => MCP_Session.Ok(name + ": loaded theory (entities not available)")
        }
      /* filesystem tier and a wholly unrecognized name alike: no PIDE
         snapshot and no image name-space to query, so both get the
         same documented not-backed-yet text. */
      case Some((_, FileSystemTier(_))) | None =>
        MCP_Session.Error(
          "Resource " + quote("isabelle://theory/" + name + "/entities") +
          " is a documented template but not backed yet (filesystem theories need load_theory); " +
          "see spec's resource templates section")
    }
  }

  /* wave 2 (theory management): scala-side use_theories/purge_theories,
     disjoint from the MCP.ir bridge -- Ir.load_theory's headless refusal
     (KNOWN GAP, see the ir_bridge_tests test naming it) is deliberately
     left alone; these three tools never call it. */

  /* unload_theory needs to resolve name -> Document.Node.Name the same
     way use_theories did (resources.import_name(qualifier, master_dir,
     name)), or it targets the wrong node -- but unload_theory's
     inputSchema is just {name}, no master_dir. The master_dir used at
     load/check time is recorded here, keyed by the user-facing name, so
     unload_theory can resolve consistently without asking for it again.
     Doubles as the "was this ever loaded" registry for the not-loaded
     error path. */
  private val theory_master_dirs: Synchronized[Map[String, String]] =
    Synchronized(Map.empty)

  /* scope_add/scope_remove state: an ordered, duplicate-free list of glob
     patterns (S1, plans/scope_add). Insertion order is preserved for
     stable scope_add/scope_remove replies; membership in resources/list
     is the union of pattern matches with the implicit working set
     (theory_master_dirs, checked independently below). */
  private val scope_patterns: Synchronized[List[String]] = Synchronized(Nil)

  /* the full known theory universe, tier-tagged, for scope_add's match
     counting and resources/list's listing: image tier from the session
     base, filesystem tier from D1's theory_map, loaded tier for anything
     tracked in theory_master_dirs that isn't already image tier (checking
     a filesystem theory promotes it to loaded, not the other way round). */
  private def known_theory_tiers(): Map[String, Tier] = {
    val image: Map[String, Tier] =
      session.resources.session_base.loaded_theories.keys.map(_ -> ImageTier).toMap
    val filesystem: Map[String, Tier] =
      theory_map.keys.filterNot(image.contains).map(name => name -> FileSystemTier(theory_map(name)._2)).toMap
    val loaded: Map[String, Tier] =
      theory_master_dirs.value.keys.filterNot(k => image.contains(k) || filesystem.contains(k))
        .map(_ -> LoadedTier).toMap
    image ++ filesystem ++ loaded
  }

  def scope_add(patterns: List[String]): MCP_Session.Result = {
    val universe = known_theory_tiers()
    val current = scope_patterns.value
    val distinct_patterns = patterns.distinct
    val newly_added = distinct_patterns.filterNot(current.contains)
    if (newly_added.nonEmpty) {
      scope_patterns.change(_ ++ newly_added)
      changed_handler.value("resources")
    }
    val lines =
      distinct_patterns.map { p =>
        val count = universe.keys.count(MCP_Session.glob_to_regex(p).matches)
        val status = if (current.contains(p)) "already in scope" else "added"
        p + ": " + status + " (" + count + " theories match)"
      }
    MCP_Session.Ok(lines.mkString("\n"))
  }

  def scope_remove(patterns: List[String]): MCP_Session.Result = {
    val current = scope_patterns.value
    val distinct_patterns = patterns.distinct
    val present = distinct_patterns.filter(current.contains)
    if (present.nonEmpty) {
      scope_patterns.change(_.filterNot(present.contains))
      changed_handler.value("resources")
    }
    val notes =
      distinct_patterns.map { p =>
        if (current.contains(p)) p + ": removed" else p + ": not in scope"
      }
    val remaining = scope_patterns.value
    val remaining_line =
      "remaining scope: " + (if (remaining.isEmpty) "(none)" else remaining.mkString(", "))
    MCP_Session.Ok((notes :+ remaining_line).mkString("\n"))
  }

  def scope_show(): MCP_Session.Result =
    scope_show_cancellable(McpApplication.Cancellation.Never)

  override def scope_show_cancellable(
      cancellation: McpApplication.Cancellation): MCP_Session.Result = {
    val universe = known_theory_tiers()
    val patterns = scope_patterns.value
    val pattern_lines =
      if (patterns.isEmpty) List("patterns: (none)")
      else "patterns:" :: patterns.map { p =>
        val count = universe.keys.count(MCP_Session.glob_to_regex(p).matches)
        "  " + p + " (" + count + " theories match)"
      }
    val regexes = patterns.map(MCP_Session.glob_to_regex)
    val pattern_matched = universe.keys.filter(name => regexes.exists(_.matches(name))).toSet
    val scoped_theories = (theory_master_dirs.value.keySet ++ pattern_matched).toList.sorted
    val theory_lines =
      if (scoped_theories.isEmpty) List("theories: (none)")
      else "theories:" :: scoped_theories.map { name =>
        "  " + name + " (" + universe.getOrElse(name, LoadedTier).name + ")"
      }
    val repl_ids = active_repl_ids(cancellation)
    val repl_lines =
      if (repl_ids.isEmpty) List("repls: (none)")
      else "repls:" :: repl_ids.map("  " + _)
    val rows = ml_named_resources_cancellable(root_context_value(cancellation), cancellation)
    val exposed = MCP_Server.exposure(rows.map(_._1))
    val named_names = rows.flatMap { case (name, _) => exposed.get(name) }
    val named_lines =
      if (named_names.isEmpty) List("named resources: (none)")
      else "named resources:" :: named_names.map("  " + _)
    MCP_Session.Ok((pattern_lines ++ theory_lines ++ repl_lines ++ named_lines).mkString("\n"))
  }

  private def render_messages(messages: List[(XML.Elem, Position.T)]): List[String] =
    messages.map({ case (tree, pos) =>
      val line = Position.Line.get(pos)
      val kind = if (Protocol.is_error(tree)) "error" else "warning"
      "line " + line + " (" + kind + "): " + XML.content(List(tree))
    })

  /* shared by load_theory and check_theory: run use_theories and render a
     per-node status report; isError only on genuine errors (server_commands.
     scala's Use_Theories reference idiom for messages/positions), never on
     warnings alone -- pinned as the warning policy (plans/check_theory
     step 1). */
  private def use_theories_result(name: String, master_dir: String): MCP_Session.Result = {
    val result =
      Exn.capture {
        session.use_theories(List(name), master_dir = master_dir, progress = new Progress)
      }
    result match {
      case Exn.Res(use_result) =>
        theory_master_dirs.change(_ + (name -> master_dir))
        val lines =
          for ((node_name, status) <- use_result.nodes) yield {
            val snapshot = use_result.snapshot(node_name)
            val msgs = render_messages(snapshot.messages)
            val header = node_name.theory + ": " + (if (status.ok) "ok" else "error")
            if (msgs.isEmpty) header else header + "\n" + msgs.map("  " + _).mkString("\n")
          }
        val text = lines.mkString("\n")
        if (use_result.ok) MCP_Session.Ok(text) else MCP_Session.Error(text)
      case Exn.Exn(exn) =>
        MCP_Session.Error(
          "Failed to load theory " + quote(name) + ": " + MCP_Server.plain_message(exn))
    }
  }

  def load_theory(name: String, master_dir: String): MCP_Session.Result =
    serialized_theory_mutation {
      val resolved_master_dir =
        if (master_dir.isEmpty) {
          resolve_theory(name) match {
            case Some((_, FileSystemTier(path))) =>
              File.standard_path(path.dir)
            case Some((_, _)) => master_dir
            case None => master_dir
          }
        }
        else master_dir
      use_theories_result(name, resolved_master_dir)
    }

  /* unlike check_theory, unload_theory needs an actual document-level
     removal, not just a fresh use_theories call -- so it goes through
     Resources.clean_theories (unload_theories + purge_theories(None) +
     session.update in one state.change), the one purge path that DOES
     push its edits to the live prover document and so doesn't desync
     Resources' bookkeeping from it (see check_theory's comment for the
     corrupting alternative this replaced). Note clean_theories' purge
     step sweeps every currently-unrequired node, not just this one --
     harmless in practice since MCP_Session never keeps a theory
     "required" past the end of its own use_theories call (see
     Headless.Session.use_theories' finally-block auto-unload), so
     nothing else should be pinned required when this runs. */
  def unload_theory(name: String): MCP_Session.Result =
    serialized_theory_mutation {
      if (image_tier(name)) {
        MCP_Session.Error(
          "Cannot unload " + quote(name) + ": it is baked into the base image (image tier)")
      }
      else theory_master_dirs.value.get(name) match {
        case None => MCP_Session.Error("Cannot unload " + quote(name) + ": it was not loaded")
        case Some(master_dir) =>
          val node_name =
            session.resources.import_name(
              Sessions.DRAFT, session.master_directory(master_dir), name)
          session.resources.clean_theories(session, UUID.random(), List(node_name))
          theory_master_dirs.change(_ - name)
          MCP_Session.Ok("Unloaded " + quote(node_name.theory))
      }
    }

  /* check_theory: NO explicit purge before reload (a correction of the
     plan's original "purge before re-reading" assumption, pinned here
     after two corrupting experiments -- see CHANGELOG). Headless.
     Resources.purge_theories only updates its own bookkeeping and never
     pushes purge_edits via session.update, so a manual purge desyncs
     Resources' record of the node's old content from what the live
     prover document actually holds; the NEXT use_theories then diffs
     the new file content against a phantom "no prior content" baseline
     and inserts it on top of the still-present old text, corrupting the
     document (observed directly: duplicated theory headers / outer
     syntax errors). use_theories reads the file fresh and diffs against
     its OWN correctly-tracked prior content on every call, so a plain
     re-run already picks up on-disk edits with no purge needed. */
  def check_theory(name: String, master_dir: String): MCP_Session.Result =
    serialized_theory_mutation {
      val resolved_master_dir =
        if (master_dir.isEmpty) {
          resolve_theory(name) match {
            case Some((_, FileSystemTier(path))) =>
              File.standard_path(path.dir)
            case Some((_, _)) => master_dir
            case None => master_dir
          }
        }
        else master_dir
      use_theories_result(name, resolved_master_dir)
    }

  /* wave 3 library discovery tools: pure functions over structure/deps
     maps and store (no prover) */

  /* list_sessions: return all known sessions as text report with metadata. */
  def list_sessions_info(): MCP_Session.Result = {
    val sessions = sessions_map
      .map { case (name, (chapter, description, theories)) =>
        val heap_present = store.get_session(name).defined
        (name, chapter, description, heap_present, name == session_name, theories.length)
      }
      .toList
      .sortBy(_._1)
    val header = "   session       chapter  heap  theories"
    val rows = sessions.map { case (name, chapter, desc, heap, is_base, count) =>
      val heap_marker = if (heap) "✓" else " "
      val base_marker = if (is_base) " [BASE]" else ""
      "%-18s %-12s  %s      %3d%s".format(name, chapter, heap_marker, count, base_marker)
    }
    MCP_Session.Ok(if (rows.isEmpty) header else header + "\n" + rows.mkString("\n"))
  }

  /* list_theories: return theories in a given session with file paths. */
  def list_theories_info(sess: String): MCP_Session.Result = {
    sessions_map.get(sess) match {
      case None =>
        MCP_Session.Error("Unknown session " + quote(sess) + "; use list_sessions to discover")
      case Some((_, _, theories)) =>
        val header = "   theory name"
        val rows = theories.sorted
        MCP_Session.Ok(if (rows.isEmpty) header else header + "\n" + rows.map("   " + _).mkString("\n"))
    }
  }

  /* search_sources: find theories by name pattern. */
  def search_sources(pattern: String): MCP_Session.Result = {
    val matches =
      if (pattern.isEmpty) Nil
      else theory_map.keys.filter(_.contains(pattern)).toList.sorted
    val header = "   matching theories"
    MCP_Session.Ok(if (matches.isEmpty) header + " (no matches)" else header + "\n" + matches.map("   " + _).mkString("\n"))
  }

  /* doc_list: the memoized catalog, glob-filtered and rendered -- see
     Doc_Catalog.render for the filtering/rendering rules. */
  def doc_list(pattern: String): MCP_Session.Result =
    MCP_Session.Ok(Doc_Catalog.render(doc_catalog, pattern))

  /* doc_read (plans/doc_read): resolve `name` through the catalog
     doc_list serves, then dispatch on entry kind -- manual (source
     session), plain (direct file), pdf only (no plain-text source). No
     ir call: file reads only, through the deps path map (same shape as
     the filesystem-tier theory resource read). */
  def doc_read(name: String, section: String, lines: String): MCP_Session.Result = {
    if (section.nonEmpty && lines.nonEmpty)
      return MCP_Session.Error(
        "doc_read: \"section\" and \"lines\" are mutually exclusive -- section addresses " +
        "manuals, lines addresses plain-text entries")

    doc_catalog.flatMap(_.entries).find(_.name == name) match {
      case None =>
        MCP_Session.Ok(
          "no documentation entry " + quote(name) + "; call doc_list to see the catalog")

      case Some(entry) if entry.source == "pdf only" =>
        if (section.nonEmpty || lines.nonEmpty)
          MCP_Session.Error(
            "doc_read: " + quote(name) + " has no plain-text source in this distribution " +
            "(pdf only at " + entry.path.toString + "); section/lines do not apply")
        else
          MCP_Session.Ok(
            "pdf only at " + entry.path.toString + "; no plain-text source in this " +
            "distribution -- the host agent can read the pdf directly.")

      case Some(entry) if entry.source == "plain" =>
        if (section.nonEmpty)
          MCP_Session.Error(
            "doc_read: " + quote(name) + " is a plain-text entry -- it has no sections " +
            "(use \"lines\" to window it, or omit both for the first window)")
        else
          Doc_Catalog.plain_read(entry.path, lines) match {
            case Right(text) => MCP_Session.Ok(text)
            case Left(msg) => MCP_Session.Error("doc_read: " + msg)
          }

      case Some(entry) => // manual: entry.source names the doc session
        if (lines.nonEmpty)
          MCP_Session.Error(
            "doc_read: " + quote(name) + " is a manual -- \"lines\" addresses plain-text " +
            "entries; use \"section\" instead (omit both for the table of contents)")
        else {
          val toc = manual_toc(entry.source)
          if (section.isEmpty) MCP_Session.Ok(Doc_Catalog.render_toc(toc))
          else
            Doc_Catalog.find_section(toc, section) match {
              case Doc_Catalog.Unique(heading) =>
                val headings_in_file = toc.filter(_.file == heading.file)
                MCP_Session.Ok(Doc_Catalog.section_text(headings_in_file, heading))
              case Doc_Catalog.Ambiguous(candidates) =>
                MCP_Session.Ok(
                  "ambiguous section " + quote(section) + ", matches:\n" +
                  Doc_Catalog.render_toc(candidates))
              case Doc_Catalog.No_Match =>
                MCP_Session.Ok(
                  "no section matching " + quote(section) + " in " + quote(name) +
                  "; call doc_read without \"section\" for the table of contents")
            }
        }
    }
  }

  def stop(): Unit = {
    val direct_operations = begin_direct_shutdown()
    bridge.beginStop()
    direct_operations.foreach(_.interrupt())
    direct_operations.foreach(_.done.join)
    try session.stop()
    finally bridge.sessionStopped()
    ()
  }
}
