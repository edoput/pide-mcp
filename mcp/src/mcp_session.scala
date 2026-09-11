/*  Title:      mcp/src/mcp_session.scala

Headless PIDE session serving MCP tools registered in Isabelle/ML.

The ML side (mcp/Tools/mcp_bridge.ML) defines one versioned protocol command.
Typed operations select a context locator and complete promises by internal
string request id.
*/

package isabelle.mcp

import isabelle._
import isabelle.mcp.pide.{BridgeDrainOutcome, BridgeFailure, BridgeResult,
  PideBridge, PideBridgePolicy, PideBridgeV1, PideRootSelector, SessionPideTransport}
import isabelle.mcp.application.McpApplication
import isabelle.mcp.control.ScheduledDeadlineScheduler
import isabelle.mcp.control.NonNegativeDuration

import scala.util.control.NonFatal
import scala.concurrent.duration.FiniteDuration
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
     the startup root catalogue. Exposed client names remain a Scala concern. */
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
  /* Declaration events trigger tools/list_changed notifications. */
  def set_changed_handler(handler: String => Unit): Unit = ()
  def load_theory(name: String, master_dir: String): MCP_Session.Result
  def unload_theory(name: String): MCP_Session.Result
  def check_theory(name: String, master_dir: String): MCP_Session.Result
  def list_sessions_info(): MCP_Session.Result
  def list_theories_info(session: String): MCP_Session.Result
  def search_sources(pattern: String): MCP_Session.Result
  /* doc_list (plans/doc_list, wave 5, spec "documentation for the agent"):
     the Doc.contents() catalog joined to doc sessions, computed once at
     startup (Doc_Catalog.make). */
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
  private[mcp] def stopAndReportSessionTermination(
    stopSession: () => Unit,
    reportStopped: () => Unit
  ): Unit = {
    stopSession()
    reportStopped()
  }

  /* A bridge-local safety deadline is not a prover/tool error.  It crosses
     the application boundary as a typed signal so ConnectionKernel retains
     public JSON-RPC timeout ownership. */
  final case class BridgeTimedOut(delay: FiniteDuration)
    extends RuntimeException("PIDE bridge call timed out after " +
      (delay.toNanos.toDouble / 1000000000.0) + " seconds")

  private[mcp] object RootDocument {
    /** The caller resolves the configured import once. Every input gets a fresh
      * live wrapper; image and source inputs differ only in their import text.
      */
    def create(session: Headless.Session, imported: Document.Node.Name): RootDocument = {
      val directory = File.path(Isabelle_System.tmp_dir("mcp-root-"))
      try {
        val name = "MCP_Root_" + UUID.random().toString.replace("-", "")
        val source = directory + Path.basic(name + ".thy")
        val importText =
          if (session.resources.loaded_theory(imported.theory)) imported.theory
          else File.standard_path(imported.path.drop_ext)
        File.write(source, "theory " + name + "\nimports " + Outer_Syntax.quote_string(importText) +
          "\nbegin\nend\n")
        val master = File.standard_path(directory)
        val node = session.resources.import_name(Sessions.DRAFT, session.master_directory(master), name)
        new RootDocument(session, node, master, Some(directory))
      }
      catch {
        case exn: Throwable =>
          try Isabelle_System.rm_tree(directory)
          catch { case cleanup: Throwable => exn.addSuppressed(cleanup) }
          throw exn
      }
    }
  }

  /** Own one live document root independently of temporary use_theories calls.
    * Requiring the root also protects its current imports: Headless purge keeps
    * every predecessor of a required node. Removed imports need not stay pinned.
    */
  private[mcp] final class RootDocument(
    val session: Headless.Session,
    val node: Document.Node.Name,
    val masterDirectory: String,
    private val ownedDirectory: Option[Path] = None
  ) {
    private val owner = UUID.random()
    private var released = false
    private var disposed = false
    @volatile private var available = false

    def selector(): BridgeResult[PideRootSelector] = {
      if (!available) Left(BridgeFailure.ProtocolError("MCP root document is not successfully loaded"))
      else try {
        val snapshot = session.snapshot(node)
        val command = snapshot.node.commands.iterator.filterNot(_.is_ignored).toList.lastOption
          .getOrElse(error("MCP root document has no final command"))
        if (command.span.name != "end") error("MCP root document has no final end command")
        val exec = snapshot.state.the_assignment(snapshot.version).check_finished.command_execs
          .getOrElse(command.id, Nil).headOption.getOrElse(error("MCP root end command has no evaluation"))
        if (command.id == 0L || exec == 0L) error("MCP root selector contains an unassigned identity")
        Right(PideRootSelector(node.theory, node.node, command.id, exec))
      }
      catch { case NonFatal(exn) => Left(BridgeFailure.ProtocolError(MCP_Server.plain_message(exn))) }
    }

    def load(progress: Progress): Unit = synchronized {
      if (released) error("MCP root ownership has been released")
      available = false
      val resources = session.resources
      if (resources.loaded_theory(node.theory))
        error("The MCP root must be a live theory document, not an image theory: " +
          quote(node.theory))
      val dependencies = resources.dependencies(List(node -> Position.none), progress = progress).check_errors
      // Pin before waiting. use_theories owns and releases a different UUID.
      resources.load_theories(session, owner, List(node), dependencies.loaded_files,
        unicode_symbols = false, progress = progress)
      val result = session.use_theories(List(File.standard_path(node.path.drop_ext)),
        master_dir = masterDirectory, progress = progress)
      if (!result.ok) error("Failed to load MCP root theory " + quote(node.theory))
      available = true
    }

    def owns(candidate: Document.Node.Name): Boolean =
      session.resources.dependencies(List(node -> Position.none)).check_errors.theories.contains(candidate)

    def release(): Unit = synchronized {
      if (!released) {
        available = false
        session.resources.unload_theories(session, owner, List(node))
        released = true
      }
    }

    /** Release may precede session termination; disposal follows the stop attempt
      * and removes only the private wrapper, never an imported source file.
      */
    def dispose(): Unit = synchronized {
      if (!disposed) {
        try release()
        finally {
          ownedDirectory.foreach(Isabelle_System.rm_tree)
          disposed = true
        }
      }
    }
  }

  private[mcp] def stopRootSession(session: Headless.Session,
      root: Option[RootDocument], reportStopped: () => Unit): Unit =
    stopOwnedRoot(() => root.foreach(_.release()), () => { session.stop(); () },
      reportStopped, () => root.foreach(_.dispose()))

  private[mcp] def stopOwnedRoot(release: () => Unit, stop: () => Unit,
      reportStopped: () => Unit, dispose: () => Unit): Unit =
    try release()
    finally {
      try stopAndReportSessionTermination(stop, reportStopped)
      finally dispose()
    }

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

  /* Documentation catalogue entry-name glob matching. */
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
    progress.echo("Checking session image " + session_name + " ...")
    /* Follow Isabelle Build.build_logic: no_build is the library's validity
       check, not a filesystem/database probe or a timing-based heuristic. */
    val checked = Build.build(options,
      selection = Sessions.Selection.session(session_name),
      build_heap = true, no_build = true, dirs = session_dirs)
    if (checked.ok) progress.echo("Session image " + session_name + ": reused")
    else {
      progress.echo("Session image " + session_name + ": build required")
      val built = Build.build(options,
        selection = Sessions.Selection.session(session_name),
        progress = progress, build_heap = true, dirs = session_dirs)
      if (!built.ok) {
        progress.echo("Session image " + session_name + ": build failed")
        error("Failed to build session " + quote(session_name) + ": " +
          Process_Result.RC.print(built.rc))
      }
      progress.echo("Session image " + session_name + ": build completed")
    }
  }

  private[mcp] def report_heap_inputs(
    resources: Headless.Resources, progress: Progress
  ): Unit = {
    if (progress.verbose) {
      val background = resources.session_background
      progress.echo("Isabelle installation: " + quote(Isabelle_System.getenv("ISABELLE_HOME")))
      progress.echo("Session: " + background.session_name)
      progress.echo("Session sources: " +
        quote(File.platform_path(resources.sessions_structure(background.session_name).dir)))
      /* Identical library resolver and resource Store used by start_session.
         These are resolved inputs, not a claim to observe OS file opens. */
      resources.store.session_heaps(background, logic = background.session_name).foreach { path =>
        progress.echo("Resolved heap input: " + quote(File.platform_path(path)))
      }
    }
  }

  /* the boot half (plans/readiness): assumes build() already produced a
     current heap. Validate derived configuration before starting the prover;
     after start_session returns, every failure path below owns exactly one
     raw-session or MCP_Session teardown. */
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

    /* wave 3 shared infrastructure: load_structure + deps + store, compute
       derived maps for library discovery (session_structure umbrella plan) */
    val structure = Sessions.load_structure(options, dirs = session_dirs)
    val deps = Sessions.deps(structure, progress = progress)
    val store = Store(options)

    val bridgeMaxPending =
      PideBridgePolicy.MaxPending.checked(options.int("mcp_max_in_flight"))
        .fold(error, identity)
    val bridgeMaxReplyBytes =
      PideBridgePolicy.PositiveBytes.checked(
        "mcp_bridge_max_reply_bytes", options.int("mcp_bridge_max_reply_bytes").toLong)
        .fold(error, identity)
    val bridgeCallTimeout =
      PideBridgePolicy.PositiveDuration.checked(
        "mcp_request_timeout", options.real("mcp_request_timeout")).fold(error, identity)
    val bridgeDrainTimeout =
      NonNegativeDuration.checked(
        "mcp_shutdown_drain", options.real("mcp_shutdown_drain")).fold(error, identity)

    report_heap_inputs(resources, progress)
    val session = resources.start_session(progress = progress)
    var ownedSession: Option[MCP_Session] = None
    var ownedRoot: Option[RootDocument] = None
    Exn.capture {
      val master = session_dirs.headOption.map(File.standard_path).getOrElse("")
      val exactImage =
        if (resources.loaded_theory(theory)) Some(theory)
        else if (!Long_Name.is_qualified(theory)) {
          val qualified = Long_Name.qualify(session_name, theory)
          if (resources.loaded_theory(qualified)) Some(qualified)
          else resources.session_base.loaded_theories.keys.filter(Long_Name.base_name(_) == theory) match {
            case List(unique) => Some(unique)
            case _ => None
          }
        }
        else None
      val imported = exactImage match {
        case Some(name) => Document.Node.Name.loaded_theory(name)
        case None => resources.import_name(Sessions.DRAFT, session.master_directory(master), theory)
      }
      progress.echo("Registry theory: " + imported.theory, verbose = true)
      val root = RootDocument.create(session, imported)
      ownedRoot = Some(root)
      root.load(progress)

      val mcpSession = new MCP_Session(session, session_name, session_dirs, theory,
        structure, deps, store, bridgeMaxPending, bridgeMaxReplyBytes, bridgeCallTimeout,
        bridgeDrainTimeout, bridgeProfile, root)
      ownedSession = Some(mcpSession)

      mcpSession.await_bridge_ready(bridgeCallTimeout) match {
        case Right(()) => ()
        case Left(failure) =>
          error("PIDE bridge startup hello failed: " + failure.message)
      }

      mcpSession
    } match {
      case Exn.Res(mcpSession) => mcpSession
      case Exn.Exn(exn) =>
        val stopped = Exn.capture {
          ownedSession match {
            case Some(mcpSession) => mcpSession.stop()
            case None => stopRootSession(session, ownedRoot, () => ())
          }
        }
        stopped match {
          case Exn.Res(_) => ()
          case Exn.Exn(stopExn) =>
            Output.warning("Failed to stop abandoned Isabelle session: " + Exn.message(stopExn))
        }
        throw exn
    }
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
  bridgeMaxReplyBytes: PideBridgePolicy.PositiveBytes,
  bridgeCallTimeout: PideBridgePolicy.PositiveDuration,
  bridgeDrainTimeout: PideBridgePolicy.NonNegativeDuration,
  bridgeProfile: McpBridgeProfile,
  private val rootDocument: MCP_Session.RootDocument
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
  }
  private object ChangeHandler extends Session.Protocol_Handler {
    private def changed(change: RegistryChange.Entry)(msg: Prover.Protocol_Output): Boolean = {
      changed_handler.value(change.event)
      true
    }

    private def tools_changed(msg: Prover.Protocol_Output): Boolean =
      changed(RegistryChange.Tools)(msg)

    override val functions: Session.Protocol_Functions =
      List(
        RegistryChange.Tools.function -> tools_changed)
  }

  session.init_protocol_handler(ChangeHandler)

  private val bridge =
    new PideBridge(
      new SessionPideTransport(session, PideBridgeV1.resultFunctions),
      bridgeMaxPending,
      bridgeMaxReplyBytes,
      bridgeCallTimeout,
      bridgeDrainTimeout,
      new ScheduledDeadlineScheduler("mcp-pide-bridge-deadline"),
      () => current_root_selector(),
      McpBridgeOperations.operationNames,
      bridgeProfile,
      PideBridgeV1)

  private[mcp] def await_bridge_ready(timeout: PideBridgePolicy.PositiveDuration): BridgeResult[Unit] =
    bridge.awaitReady(timeout)

  private[mcp] def bridge_operation_names: Set[String] = bridge.advertisedOperationNames

  private def bridge_value[A](result: BridgeResult[A]): A =
    result match {
      case Right(value) => value
      case Left(BridgeFailure.Cancelled) => throw Exn.Interrupt()
      case Left(BridgeFailure.TimedOut(delay)) => throw MCP_Session.BridgeTimedOut(delay)
      case Left(failure) => error(failure.message)
    }

  private def bridge_result(result: BridgeResult[MCP_Session.Result]): MCP_Session.Result =
    result match {
      case Right(value) => value
      case Left(BridgeFailure.Cancelled) => MCP_Session.Error("Request cancelled")
      case Left(BridgeFailure.TimedOut(delay)) => throw MCP_Session.BridgeTimedOut(delay)
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

  /* Validate and canonicalize an opaque context locator in ML. */
  def check_context(context: String): MCP_Session.Result =
    check_context_cancellable(context, McpApplication.Cancellation.Never)

  override def check_context_cancellable(context: String,
      cancellation: McpApplication.Cancellation): MCP_Session.Result =
    bridge_result(bridge.call(
      McpBridgeOperations.checkContext(Some(context)), cancellation))

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

  private[mcp] def current_root_selector(): BridgeResult[PideRootSelector] =
    rootDocument.selector()

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
        Exn.capture(rootDocument.load(new Progress)) match {
          case Exn.Res(_) => if (use_result.ok) MCP_Session.Ok(text) else MCP_Session.Error(text)
          case Exn.Exn(exn) => MCP_Session.Error(text + "\nMCP root refresh failed: " + MCP_Server.plain_message(exn))
        }
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
     the server root has a separate lifetime requirement, so its current
     imported ancestors survive this ordinary cleanup. */
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
          if (rootDocument.owns(node_name))
            MCP_Session.Error("Cannot unload " + quote(node_name.theory) +
              ": it is required by the MCP root")
          else {
            session.resources.clean_theories(session, UUID.random(), List(node_name))
            theory_master_dirs.change(_ - name)
            MCP_Session.Ok("Unloaded " + quote(node_name.theory))
          }
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
     session), plain (direct file), pdf only (no plain-text source). */
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
    try {
      direct_operations.foreach(_.interrupt())
      try {
        bridge.beginStop() match {
          case BridgeDrainOutcome.Failed(failure) =>
            Output.warning("PIDE bridge drain failed: " + failure.message +
              "; forcing Isabelle session stop")
          case BridgeDrainOutcome.Acknowledged | BridgeDrainOutcome.SessionTerminated => ()
        }
      }
      catch {
        case NonFatal(exn) =>
          Output.warning("PIDE bridge drain raised " + Exn.message(exn) +
            "; forcing Isabelle session stop")
      }
      direct_operations.foreach(_.done.join)
    }
    finally {
      MCP_Session.stopRootSession(session, Some(rootDocument), () => bridge.sessionStopped())
    }
    ()
  }
}
