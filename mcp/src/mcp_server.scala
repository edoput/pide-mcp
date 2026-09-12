/*  Title:      mcp/src/mcp_server.scala

MCP server over stdio: newline-delimited JSON-RPC 2.0 on stdin/stdout.

Nothing but protocol replies may be written to stdout; all logging goes
through the given progress (Console_Progress(stderr = true) in the tool).
*/

package isabelle.mcp

import isabelle._
import isabelle.mcp.application.McpApplication
import isabelle.mcp.connection._
import isabelle.mcp.protocol.JsonRpc
import isabelle.mcp.tools

import java.io.{BufferedReader, PrintStream}

object MCP_Server {
  val server_name = "isabelle-mcp"
  val server_version = "0.1.0"
  val default_protocol_version = ProtocolRevision.V2025_03_26.value

  /* declared params -> JSON schema (spec phase 3 "schema over the
     bridge"): nat/int -> integer, bool -> boolean, everything else
     (string/source/args/term/typ/fact) -> string with the validation
     contract in the property description; defaults and descriptions
     carried. ML includes the optional framework context parameter in
     every row; Scala renders that metadata without interpreting URLs. */

  private def param_json_type(typ: MCP_Session.Ptyp): String =
    typ match {
      case MCP_Session.Ptyp_Nat | MCP_Session.Ptyp_Int => "integer"
      case MCP_Session.Ptyp_Bool => "boolean"
      case MCP_Session.Ptyp_List_Of(_) => "array"
      case _ => "string"  // String/Source/Args/Term/Typ/Fact/Enum
    }

  private def param_default_json(typ: MCP_Session.Ptyp, v: String): JSON.T =
    typ match {
      case MCP_Session.Ptyp_Nat | MCP_Session.Ptyp_Int => Value.Long.unapply(v).getOrElse(v)
      case MCP_Session.Ptyp_Bool => Value.Boolean.unapply(v).getOrElse(v)
      case _ => v
    }

  def ml_tool_schema(params: List[MCP_Session.Tool_Param]): JSON.Object.T =
    if (params.isEmpty) JSON.Object("type" -> "object")
    else {
      val properties =
        params.foldLeft(JSON.Object.empty) { (obj, p) =>
          val contract =
            p.typ match {
              case MCP_Session.Ptyp_Term => " (an inner-syntax term, elaborated before use)"
              case MCP_Session.Ptyp_Typ => " (an inner-syntax type, elaborated before use)"
              case MCP_Session.Ptyp_Fact => " (a fact name, resolved before use)"
              case MCP_Session.Ptyp_Source => " (verbatim source text)"
              case _ => ""
            }
          /* Enum -> {"type": "string", "enum": [...]}; List_Of e ->
             {"type": "array", "items": {"type": ...}} (plans/
             param_schema_v2, steps 3/4) -- not reachable from isar until
             those steps land their parsers, but Ptyp is a closed ADT
             from step 2 on, so the match covers them already. */
          val type_fields: JSON.Object.T =
            p.typ match {
              case MCP_Session.Ptyp_Enum(items) =>
                JSON.Object("type" -> "string", "enum" -> items)
              case MCP_Session.Ptyp_List_Of(elem) =>
                JSON.Object("type" -> "array",
                  "items" -> JSON.Object("type" -> param_json_type(elem)))
              case t => JSON.Object("type" -> param_json_type(t))
            }
          obj + (p.name ->
            (type_fields ++
              JSON.Object("description" -> (p.description + contract)) ++
              JSON.Object.apply(
                p.default.toList.map(d =>
                  "default" -> param_default_json(p.typ, d))*)))
        }
      JSON.Object(
        "type" -> "object",
        "properties" -> properties,
        "required" -> params.filter(_.required).map(_.name))
    }

  /* renderer over the row's own declared MCP_Session.Tool_Annotations
     (plans/param_schema_v2 step 5) -- the hint set is a per-row property
     now (an isar (annotations <bucket>) clause, or ML's default), not
     inferred from the form tag: only Some hints render, so an all-absent
     record (no ML tool currently produces one, but the type allows it)
     yields no "annotations" key at all. */
  def ml_tool_annotations(annotations: MCP_Session.Tool_Annotations): Option[JSON.Object.T] = {
    val fields =
      annotations.read_only.toList.map("readOnlyHint" -> _) :::
      annotations.idempotent.toList.map("idempotentHint" -> _) :::
      annotations.destructive.toList.map("destructiveHint" -> _) :::
      annotations.open_world.toList.map("openWorldHint" -> _)
    if (fields.isEmpty) None else Some(JSON.Object(fields*))
  }


  /* exposed names for ML registry entries (plans/mcp_tool_registry).

     Rows cross the bridge under their full internal names ("MCP_Tools.
     shout"); clients see the base name when it is unambiguous within the
     served set and not reserved (builtins always win the bare name),
     otherwise the sanitized full name. MCP tool names must match
     ^[a-zA-Z0-9_-]{1,64}$: dots become "__", any other foreign character
     becomes "_". Entries whose exposed name is still taken (reserved, or
     a duplicate after sanitization) are DROPPED rather than shadowing.
     Pure function of the row set: tools/call resolves
     through the same map their listing used. */

  def sanitize_name(name: String): String = {
    val sanitized =
      name.replace(".", "__").map(c =>
        if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' ||
            c == '_' || c == '-') c
        else '_')
    (if (sanitized.isEmpty) "_" else sanitized).take(64)
  }

  def exposure(names: List[String], reserved: Set[String] = Set.empty): Map[String, String] = {
    val bases = names.map(name => sanitize_name(Long_Name.base_name(name)))
    val counts = bases.groupBy(identity).view.mapValues(_.length).toMap
    val candidates =
      names.lazyZip(bases).map((name, base) =>
        (name, if (counts(base) == 1 && !reserved(base)) base else sanitize_name(name)))
    candidates.foldLeft((Map.empty[String, String], reserved)) {
      case ((map, taken), (name, exposed)) =>
        if (taken(exposed)) (map, taken)
        else (map + (name -> exposed), taken + exposed)
    }._1
  }


  /* Builtin tools run through explicit Scala backend handlers.
     tools/list merges these with
     the ML tool registry; builtins always keep their bare name -- ML
     rows carry full internal names and go through exposure() above, so
     a colliding ML tool falls back to its sanitized qualified name (or
     is dropped if even that is taken). */

  case class Builtin_Tool(
    name: String,
    description: String,
    input_schema: JSON.Object.T,
    annotations: JSON.Object.T,
    handler_fn: (MCP_Backend, List[(String, String)],
      McpApplication.Cancellation) => MCP_Session.Result) {
    def handler(backend: MCP_Backend, args: List[(String, String)],
      cancellation: McpApplication.Cancellation): MCP_Session.Result =
      backend.direct_cancellable(cancellation) { handler_fn(backend, args, cancellation) }
  }

  val read_only_annotations: JSON.Object.T =
    JSON.Object("readOnlyHint" -> true, "idempotentHint" -> true, "openWorldHint" -> false)

  val mutating_annotations: JSON.Object.T =
    JSON.Object("readOnlyHint" -> false, "idempotentHint" -> false, "openWorldHint" -> false)

  val idempotent_mutating_annotations: JSON.Object.T =
    JSON.Object("readOnlyHint" -> false, "idempotentHint" -> true, "openWorldHint" -> false)

  def pass_arg(args: List[(String, String)], key: String): String =
    args.collectFirst({ case (`key`, v) => v }).getOrElse("")

  def pass_int_arg(args: List[(String, String)], key: String, default: Int): Int =
    args.collectFirst({ case (`key`, v) => v }).flatMap(Value.Int.unapply).getOrElse(default)

  def pass_bool_arg(args: List[(String, String)], key: String, default: Boolean): Boolean =
    args.collectFirst({ case (`key`, v) => v }).flatMap(Value.Boolean.unapply).getOrElse(default)

  /* offset/limit input-schema properties shared by every list-shaped tool
     (Window, mcp/src/utils.scala): one convention, copied here rather than
     each tool schema spelling it out differently. */
  val offset_limit_properties: JSON.Object.T =
    JSON.Object(
      "offset" -> JSON.Object("type" -> "integer",
        "description" -> "0-based row to start from. Omit to start from the beginning.",
        "default" -> 0),
      "limit" -> JSON.Object("type" -> "integer",
        "description" -> "Maximum rows to return. Omit for the default window; " +
          "a footer names the offset to continue from when more rows remain.",
        "default" -> Window.default_limit))

  val builtins: List[Builtin_Tool] =
    List(tools.LoadTheory.load_theory_tool, tools.UnloadTheory.unload_theory_tool,
      tools.ListSessions.list_sessions_tool,
      tools.ListTheories.list_theories_tool, tools.SearchSources.search_sources_tool,
      tools.DocList.doc_list_tool, tools.DocRead.doc_read_tool)

  /* Compatibility aliases for callers that use the historical composition
     root names.  Implementations live in isabelle.mcp.tools. */
  val load_theory_tool: Builtin_Tool = tools.LoadTheory.load_theory_tool
  val unload_theory_tool: Builtin_Tool = tools.UnloadTheory.unload_theory_tool
  val list_sessions_tool: Builtin_Tool = tools.ListSessions.list_sessions_tool
  val list_theories_tool: Builtin_Tool = tools.ListTheories.list_theories_tool
  val search_sources_tool: Builtin_Tool = tools.SearchSources.search_sources_tool
  val doc_list_tool: Builtin_Tool = tools.DocList.doc_list_tool
  val doc_read_tool: Builtin_Tool = tools.DocRead.doc_read_tool
  val all_builtin_names: List[String] = builtins.map(_.name)

  /* JSON arrays become repeated named arguments in array order. */
  def json_args(arguments: JSON.Object.T): List[(String, String)] =
    arguments.toList.flatMap({
      case (key, value: String) => List(key -> value)
      case (key, values: List[_]) =>
        values.map({
          case v: String => key -> v
          case v => key -> JSON.Format(v)
        })
      case (key, value) => List(key -> JSON.Format(value))
    })


  /* json-rpc 2.0 */

  object RPC {
    val PARSE_ERROR = JsonRpc.ErrorCode.ParseError
    val INVALID_REQUEST = JsonRpc.ErrorCode.InvalidRequest
    val METHOD_NOT_FOUND = JsonRpc.ErrorCode.MethodNotFound
    val INVALID_PARAMS = JsonRpc.ErrorCode.InvalidParams

    def response(id: JSON.T, result: JSON.T): JSON.Object.T =
      JSON.Object("jsonrpc" -> "2.0", "id" -> id, "result" -> result)

    def error(id: JSON.T, code: Int, message: String): JSON.Object.T =
      JSON.Object("jsonrpc" -> "2.0", "id" -> id,
        "error" -> JSON.Object("code" -> code, "message" -> message))
  }

  /* the outbound half of the client-edge recoding boundary (spec:
     "symbol recoding at the client edge"). Isabelle's convention is ML
     speaks symbol notation (\<Longrightarrow>) and Scala speaks unicode
     (==>); Isabelle/Scala enforces it in Pure/PIDE/prover.scala, but
     ONLY on the non-protocol channel -- message_output decodes ordinary
     output chunks and deliberately skips PROTOCOL ones. The mcp bridge
     rides the protocol channel exclusively (protocol_command_raw for the
     common MCP.bridge envelope), so nothing
     upstream ever decodes for us and the client would otherwise see raw
     \<open>...\<close>.

     Decoding here, at the single point where text becomes an mcp
     content block, rather than at each per-chunk parse site: it catches
     every string regardless of which channel produced it, and
     Symbol.decode is idempotent on already-unicode text (its recoder
     only rewrites \-initiated sequences, and its own output contains
     none), so text that DID come through the decoded channel --
     render_messages over snapshot messages -- is unharmed.

     Consequence, recorded with the decision: the client-edge property
     is identity up to symbol normalization, not byte identity. Text
     holding a literal \<foo> meant verbatim comes back as the glyph.
     Byte fidelity still holds below this point. */
  def text_result(text: String, is_error: Boolean = false): JSON.Object.T = {
    val result =
      JSON.Object("content" ->
        List(JSON.Object("type" -> "text", "text" -> Symbol.decode(text))))
    if (is_error) result + ("isError" -> true) else result
  }

  /* server startup and readiness (plans/readiness, spec "server startup
     and readiness"): MCP_Session.start (build_heap + start_session +
     use_theories) runs on a background thread instead of gating serve()'s
     first stdin read -- the prover genuinely cannot start without a built
     heap, so the only move is to stop making the CLIENT wait for it.
     Not_Ready carries a short human progress string ("building
     SESSION" / "starting session SESSION"); Ready carries the live
     backend once build+boot succeed; Failed is terminal for the process
     (no retry loop -- restarting the server is the recovery). */
  type Readiness = McpApplication.Readiness
  val Not_Ready = McpApplication.Not_Ready
  val Ready = McpApplication.Ready
  val Failed = McpApplication.Failed

  /* decode_message / plain_message: prover and build error text often
     carries YXML position markup (e.g. "Duplicate session ... \x05\x06
     position\x06line=1..."), which is literal 0x05/0x06 control bytes
     once serialized -- unreadable garbage to an MCP client, and exactly
     the text a client must act on when reported through Failed. Same
     idiom as MCP_Session.Handler.ir_result: YXML.parse_body on ordinary
     (non-YXML) text is a safe no-op, yielding a single Text leaf, so
     this is harmless to apply to messages that never had markup at all.
     Malformed/partial markup makes parse_body raise "Malformed YXML";
     Exn.capture guards that so a decode failure falls back to the raw
     string instead of losing the message entirely. */
  def decode_message(s: String): String =
    Exn.capture { XML.content(YXML.parse_body(YXML.Source(s))) } match {
      case Exn.Res(text) => text
      case Exn.Exn(_) => s
    }

  def plain_message(exn: Throwable): String = decode_message(Exn.message(exn))


  /* request handling: pure JSON in, JSON out — no I/O, no session,
     unit-testable against any MCP_Backend */

  class Handler(
    readiness: () => Readiness,
    session_name: String = "",
    session_dirs: List[Path] = Nil,
    theory: String = "",
    application: Option[McpApplication] = None
  ) {
    /* pre-readiness Handler(backend) construction (every existing test and
       serve() call site): the backend is live from the start, so this is
       just Ready(backend) with no session identity to report -- session
       identity only matters for the Not_Ready/Failed messages below,
       which this shape never produces. */
    def this(backend: MCP_Backend) = this(() => Ready(backend))

    private val mcp_application =
      application.getOrElse(
        ConnectionRuntime.isabelleApplication(readiness, session_name, session_dirs, theory))

    private def application_response(
      id: JSON.T, outcome: McpApplication.Outcome): Option[JSON.Object.T] =
      outcome match {
        case McpApplication.Outcome.Result(value) => Some(RPC.response(id, value))
        case McpApplication.Outcome.InvalidParams(message) =>
          Some(RPC.error(id, RPC.INVALID_PARAMS, message))
        case McpApplication.Outcome.TimedOut(_) =>
          Some(RPC.error(id, ConnectionKernel.RequestTimedOut, "Request timed out"))
      }

    private def execute(
      id: JSON.T, operation: McpApplication.Operation): Option[JSON.Object.T] =
      application_response(id, mcp_application.execute(operation, McpApplication.Cancellation.Never))

    def handle(json: JSON.T): Option[JSON.Object.T] = {
      val id = JSON.value(json, "id").orNull
      val has_id = JSON.value(json, "id").isDefined

      JSON.string(json, "method") match {
        case None =>
          if (has_id) Some(RPC.error(id, RPC.INVALID_REQUEST, "Missing method")) else None

        case Some("initialize") =>
          val protocol_version =
            JSON.value(json, "params").flatMap(JSON.string(_, "protocolVersion"))
              .getOrElse(default_protocol_version)
          Some(RPC.response(id,
            JSON.Object(
              "protocolVersion" -> protocol_version,
              "capabilities" ->
                JSON.Object(
                  "tools" -> JSON.Object("listChanged" -> true)),
              "serverInfo" ->
                JSON.Object("name" -> server_name, "version" -> server_version))))

        case Some("notifications/initialized") => None

        case Some("ping") => Some(RPC.response(id, JSON.Object()))

        case Some("tools/list") =>
          execute(id, McpApplication.Operation.ToolsList)

        case Some("tools/call") =>
          val params = JSON.value(json, "params").getOrElse(JSON.Object())
          JSON.string(params, "name") match {
            case None => Some(RPC.error(id, RPC.INVALID_PARAMS, "Missing tool name"))
            case Some(name) =>
              val arguments =
                JSON.value(params, "arguments") match {
                  case Some(obj: JSON.Object.T @unchecked) => obj
                  case _ => JSON.Object()
                }
              execute(id, McpApplication.Operation.ToolsCall(name, arguments))
          }

        case Some(method) =>
          if (has_id) Some(RPC.error(id, RPC.METHOD_NOT_FOUND, "Method not found: " + method))
          else None
      }
    }

    def handle_line(line: String): Option[JSON.Object.T] =
      JSON.Format.unapply(line) match {
        case None => Some(RPC.error(null, RPC.PARSE_ERROR, "Parse error"))
        case Some(json) => handle(json)
      }
  }


  private def validatedConnectionPolicy(options: Options): ConnectionPolicy =
    ConnectionPolicy.fromOptions(options) match {
      case Right(policy) => policy
      case Left(message) => error("mcp_server: invalid connection policy: " + message)
    }

  /* Injectable stream seam: the composition root selects its buffered data
     plane, so tests exercise the same connection assembly rather than a
     Handler bypass. */
  def serve(
    readiness: () => Readiness,
    in: BufferedReader,
    out: PrintStream,
    progress: Progress,
    session_name: String,
    session_dirs: List[Path],
    theory: String,
    install_changed_sender: (String => Unit) => Unit,
    on_shutdown: () => Unit,
    policy: ConnectionPolicy
  ): Unit =
    ConnectionRuntime.buffered(
      readiness, in, out, progress, session_name, session_dirs, theory,
      install_changed_sender, on_shutdown, policy,
      ConnectionKernel.ServerInfo(server_name, server_version)).serve()

  def serve(
    backend: MCP_Backend,
    in: BufferedReader,
    out: PrintStream,
    progress: Progress = new Progress,
    policy: ConnectionPolicy = validatedConnectionPolicy(Options.init())
  ): Unit =
    serve(() => Ready(backend), in, out, progress,
      session_name = "", session_dirs = Nil, theory = "",
      install_changed_sender = backend.set_changed_handler,
      on_shutdown = () => backend.stop(),
      policy = policy)


  /* stdio server on a headless PIDE session (plans/readiness, spec
     "server startup and readiness"): serve() starts reading stdin
     immediately; MCP_Session.build/boot run on a background thread,
     publishing into `cell` as they progress. The prover genuinely
     cannot start without a built heap, so this does not make the build
     itself any faster -- it only stops the json-rpc loop, and so
     `initialize`, from being gated on it. */
  def run(
    options: Options,
    session_name: String,
    session_dirs: List[Path],
    theory: String,
    progress: Progress = new Progress
  ): Unit = {
    /* config pre-flight (plans/session_dirs_errors, decided 2026-08-07):
       a bad -d set is a CONFIGURATION error, knowable before any prover
       work and unambiguously the user's to fix -- so it is checked here,
       synchronously, as the very first thing, before the Future.fork
       below and before serve() ever reads a byte of stdin. Nothing is
       served on a bad config. error() is caught by Command_Line.tool
       (the isabelle launcher), which prints each line of the message
       prefixed with "***" to STDERR and exits the process nonzero, with
       no Scala stack trace -- never write this to stdout, which is the
       JSON-RPC channel. Build/boot failures (a broken heap, a theory
       that won't compile) are a different class of problem -- reachable
       only after this check passes -- and keep going through the
       existing Failed readiness path below, unchanged. */
    val config_issues = MCP_Config.check(session_dirs)
    if (config_issues.nonEmpty) error(MCP_Config.render(config_issues))
    val policy = validatedConnectionPolicy(options)

    /* changed_sender: the list_changed notifier serve() installs on
       entry (3a) -- there is no backend yet to register it on until the
       Ready transition below picks it up. shutting_down: the latch 3b
       needs so a session that finishes booting AFTER stdin has already
       closed gets stopped instead of stored/served (see the race
       analysis at each change_result call below). */
    case class State(
      readiness: Readiness,
      changed_sender: Option[String => Unit],
      shutting_down: Boolean)
    val cell = Synchronized(State(Not_Ready("checking session image " + session_name), None, false))

    /* fire-and-forget: Command_Line.tool (the isabelle launcher) calls
       sys.exit once run() returns, which happens as soon as serve() sees
       EOF -- independent of this thread. That forcibly ends the process
       (and any ML process this thread may have spawned) even if the
       build is still in flight, so there is deliberately no interrupt
       handling here: an interrupted Build.build can leave a partial
       heap, and the process is exiting anyway (plan step 3b). */
    Future.fork {
      Exn.capture {
        MCP_Session.build(options, session_name, session_dirs, progress)
        cell.change(s => s.copy(readiness = Not_Ready("starting session " + session_name)))
        /* Verify the retained base bridge before publishing prover readiness. */
        MCP_Session.boot(options, session_name, session_dirs, theory,
          McpBridgeProfile.base, progress)
      } match {
        case Exn.Res(session) =>
          /* publish Ready and wire list_changed under the SAME lock
             on_shutdown uses below: if stdin already closed and
             shutting_down is set, stop the session just booted instead
             of storing it -- the two change_results serialize through
             Synchronized, so exactly one of "store it" / "stop it
             instead" happens, never both and never neither. */
          val abandoned =
            cell.change_result { s =>
              if (s.shutting_down) (true, s)
              else {
                s.changed_sender.foreach(session.set_changed_handler)
                (false, s.copy(readiness = Ready(session)))
              }
            }
          if (abandoned) session.stop() else progress.echo("MCP server ready")
        case Exn.Exn(exn) =>
          cell.change(s => s.copy(readiness = Failed(plain_message(exn))))
      }
    }

    progress.echo(
      "Serving stdin; session " + quote(session_name) + " checking session image in the background ...")
    ConnectionRuntime.stdio(
      () => cell.value.readiness, progress,
      sessionName = session_name, sessionDirs = session_dirs, theory = theory,
      installChangedSender = sender => cell.change(s => s.copy(changed_sender = Some(sender))),
      onShutdown = () => {
        val to_stop =
          cell.change_result { s =>
            s.readiness match {
              case Ready(backend) => (Some(backend), s.copy(shutting_down = true))
              case _ => (None, s.copy(shutting_down = true))
            }
          }
        to_stop.foreach(_.stop())
      }, policy = policy,
      serverInfo = ConnectionKernel.ServerInfo(server_name, server_version)).serve()
  }
}
