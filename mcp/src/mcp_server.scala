/*  Title:      mcp/src/mcp_server.scala

MCP server over stdio: newline-delimited JSON-RPC 2.0 on stdin/stdout.

Nothing but protocol replies may be written to stdout; all logging goes
through the given progress (Console_Progress(stderr = true) in the tool).
*/

package isabelle.mcp

import isabelle._

import java.io.{BufferedReader, InputStreamReader, PrintStream}
import java.nio.charset.StandardCharsets

object MCP_Server {
  val server_name = "isabelle-mcp"
  val server_version = "0.1.0"
  val default_protocol_version = "2025-03-26"

  val input_schema: JSON.Object.T =
    JSON.Object(
      "type" -> "object",
      "properties" -> JSON.Object("input" -> JSON.Object("type" -> "string")),
      "required" -> List("input"))

  /* declared params -> JSON schema (spec phase 3 "schema over the
     bridge"): nat/int -> integer, bool -> boolean, everything else
     (string/source/args/term/typ/fact) -> string with the validation
     contract in the property description; defaults and descriptions
     carried. A tool without params keeps the mvp {input} schema. */

  private def param_json_type(typ: String): String =
    typ match {
      case "nat" | "int" => "integer"
      case "bool" => "boolean"
      case _ => "string"
    }

  private def param_default_json(typ: String, v: String): JSON.T =
    typ match {
      case "nat" | "int" => Value.Long.unapply(v).getOrElse(v)
      case "bool" => Value.Boolean.unapply(v).getOrElse(v)
      case _ => v
    }

  def ml_tool_schema(params: List[MCP_Session.Tool_Param]): JSON.Object.T =
    if (params.isEmpty) input_schema
    else {
      val properties =
        params.foldLeft(JSON.Object.empty) { (obj, p) =>
          val contract =
            p.typ match {
              case "term" => " (an inner-syntax term, elaborated before use)"
              case "typ" => " (an inner-syntax type, elaborated before use)"
              case "fact" => " (a fact name, resolved before use)"
              case "source" => " (verbatim source text)"
              case _ => ""
            }
          obj + (p.name ->
            (JSON.Object(
              "type" -> param_json_type(p.typ),
              "description" -> (p.description + contract)) ++
              JSON.Object.apply(
                p.default.toList.map(d =>
                  "default" -> param_default_json(p.typ, d))*)))
        }
      JSON.Object(
        "type" -> "object",
        "properties" -> properties,
        "required" -> params.filter(_.required).map(_.name))
    }

  /* form tag -> tool annotations: a diag wrap cannot mutate prover state
     (diagnostic commands, discarded toplevel state) and repeats cleanly;
     string_fun/ml_run make no such promise -> no annotations */
  def ml_tool_annotations(form: String): Option[JSON.Object.T] =
    form match {
      case "diag_wrap" =>
        Some(JSON.Object(
          "readOnlyHint" -> true, "idempotentHint" -> true, "openWorldHint" -> false))
      case _ => None
    }


  /* exposed names for ML registry entries (plans/mcp_tool_registry).

     Rows cross the bridge under their full internal names ("MCP_Tools.
     shout"); clients see the base name when it is unambiguous within the
     served set and not reserved (builtins always win the bare name),
     otherwise the sanitized full name. MCP tool names must match
     ^[a-zA-Z0-9_-]{1,64}$: dots become "__", any other foreign character
     becomes "_". Entries whose exposed name is still taken (reserved, or
     a duplicate after sanitization) are DROPPED rather than shadowing.
     Pure function of the row set: tools/call and resources/read resolve
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


  /* builtin tools: implemented here in scala, calling MCP.ir (see the
     spec's "mcp tools (new, structured)"). tools/list merges these with
     the ML tool registry; builtins always keep their bare name -- ML
     rows carry full internal names and go through exposure() above, so
     a colliding ML tool falls back to its sanitized qualified name (or
     is dropped if even that is taken). */

  /* fname is the MCP.ir dispatcher key (MCP_Repl.thy), possibly different
     from the exposed name (name/argument mapping across the layers, see
     plans/repl_list): repl_list -> "repls", repl_init -> "init". json
     property names equal the yxml pair keys (spec: "advertised tool
     metadata"), so args need no per-tool reshaping -- json_args's output
     goes straight to backend.ir. */
  /* handler_fn overrides the default ir(fname, args) dispatch for wave-2
     tools (load_theory/unload_theory/check_theory) that call scala's own
     MCP_Session.use_theories/purge_theories wrapper directly -- no MCP.ir
     bridge, no fname, disjoint from the ML dispatcher (plans/load_theory:
     "scala use_theories and ML Thy_Info are disjoint registries"). fname
     stays "" for these; it is meaningless once handler_fn is set. */
  case class Builtin_Tool(
    name: String,
    fname: String,
    description: String,
    input_schema: JSON.Object.T,
    annotations: JSON.Object.T,
    handler_fn: Option[(MCP_Backend, List[(String, String)]) => MCP_Session.Result] = None) {
    def handler(backend: MCP_Backend, args: List[(String, String)]): MCP_Session.Result =
      handler_fn match {
        case Some(f) => f(backend, args)
        case None => backend.ir(fname, args)
      }
  }

  val read_only_annotations: JSON.Object.T =
    JSON.Object("readOnlyHint" -> true, "idempotentHint" -> true, "openWorldHint" -> false)

  val mutating_annotations: JSON.Object.T =
    JSON.Object("readOnlyHint" -> false, "idempotentHint" -> false, "openWorldHint" -> false)

  /* spec refinement (plans/repl_remove): repl_remove (and repl_truncate)
     destroy state irrecoverably, so they honestly carry destructiveHint
     true -- narrower than the plain mutating_annotations bucket. */
  val destructive_annotations: JSON.Object.T =
    JSON.Object(
      "readOnlyHint" -> false, "idempotentHint" -> false,
      "destructiveHint" -> true, "openWorldHint" -> false)

  /* spec refinement (plans/repl_replay): mutating but genuinely
     idempotent in the success case -- a second replay finds zero stale
     steps and is a no-op ("Replayed 0 stale steps"), honest and useful
     for retry-happy clients. */
  val idempotent_mutating_annotations: JSON.Object.T =
    JSON.Object("readOnlyHint" -> false, "idempotentHint" -> true, "openWorldHint" -> false)

  val repl_list_tool: Builtin_Tool =
    Builtin_Tool(
      name = "repl_list",
      fname = "repls",
      description =
        "List all open REPL proof sessions. Each entry shows the REPL id, " +
        "its step count (plus stale steps, if any), the origin it was " +
        "initialized from, and whether it is currently busy executing an " +
        "operation. REPLs are created with repl_init or " +
        "repl_init_from_source and discarded with repl_remove.",
      input_schema = JSON.Object("type" -> "object"),
      annotations = read_only_annotations)

  val repl_init_tool: Builtin_Tool =
    Builtin_Tool(
      name = "repl_init",
      fname = "init",
      description =
        "Create a new REPL proof session that imports the given Isabelle " +
        "theories. This is equivalent to writing `theory T imports A B C " +
        "begin ...` in a .thy file, and it is the only way to make a " +
        "theory's definitions, lemmas, and notations available for " +
        "stepping. Theories not in the initial heap must be loaded first " +
        "with load_theory.\n\n" +
        "`theories` is a list of theory specs. Examples:\n" +
        "- [\"Main\"] -- start from the standard HOL library\n" +
        "- [\"HOL-Library.Multiset\"] -- import one theory\n" +
        "- [\"HOL-Library.Multiset\", \"HOL-Library.FSet\"] -- import and " +
        "merge multiple theories\n" +
        "- [\"MySession.MyTheory:42\"] -- start from source segment 42 of " +
        "a recorded theory (single spec only)\n" +
        "- [\"pin@A\"] -- start from the pinned state of REPL A (use " +
        "repl_pin first)\n" +
        "- [\"pin@A\", \"Main\"] -- merge a pin with a theory\n\n" +
        "The REPL id must be new; remove an old REPL with repl_remove " +
        "first. Discard with repl_remove, inspect with repl_list.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" -> JSON.Object(
            "repl" -> JSON.Object("type" -> "string"),
            "theories" ->
              JSON.Object("type" -> "array", "items" -> JSON.Object("type" -> "string"))),
          "required" -> List("repl", "theories")),
      annotations = mutating_annotations)

  /* repl_init_from_source (plans/repl_init_from_source): the only
     wave-1 tool whose scala handler is more than a pass-through -- the
     client speaks (theory, offset | pattern | index), the exactly-one-
     locator check happens here (schema can't express it, same shape as
     find_theorems/find_definition's repl/theory exclusivity), and the
     resolution itself (tier, then command/segment lookup) is
     MCP_Session.init_from_source's job. */
  val repl_init_from_source_tool: Builtin_Tool =
    Builtin_Tool(
      name = "repl_init_from_source",
      fname = "",
      description =
        "Create a new REPL proof session rooted at a specific command " +
        "inside an existing theory, so you can step from the middle of " +
        "a proof or after a definition instead of rebuilding context " +
        "from imports. Give the theory's long name plus exactly one " +
        "locator: `offset` (character offset into the source), " +
        "`pattern` (a literal source substring; its first occurrence " +
        "picks the command), or `index` (command/segment index). Works " +
        "on theories loaded with load_theory (PIDE document) and on " +
        "image theories with recorded segments. The REPL starts at the " +
        "state AFTER the located command.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" -> JSON.Object(
            "repl" -> JSON.Object("type" -> "string"),
            "theory" -> JSON.Object("type" -> "string"),
            "offset" -> JSON.Object("type" -> "integer"),
            "pattern" -> JSON.Object("type" -> "string"),
            "index" -> JSON.Object("type" -> "integer")),
          "required" -> List("repl", "theory")),
      annotations = mutating_annotations,
      handler_fn = Some((backend, args) => {
        val repl = pass_arg(args, "repl")
        val theory = pass_arg(args, "theory")
        val offset = args.collectFirst({ case ("offset", v) => v.toInt })
        val pattern = args.collectFirst({ case ("pattern", v) => v })
        val index = args.collectFirst({ case ("index", v) => v.toInt })
        MCP_Session.Locator.exactly_one(offset, pattern, index) match {
          case Left(msg) => MCP_Session.Error("repl_init_from_source: " + msg)
          case Right(()) => backend.init_from_source(repl, theory, offset, pattern, index)
        }
      }))

  val repl_remove_tool: Builtin_Tool =
    Builtin_Tool(
      name = "repl_remove",
      fname = "remove",
      description =
        "Remove a REPL and all sub-REPLs forked from it. Fails if any of " +
        "them is busy executing an operation, or if other REPLs were " +
        "initialized from this REPL's pin (unpin dependents or remove " +
        "them first). The reply names every REPL that was removed.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" -> JSON.Object("repl" -> JSON.Object("type" -> "string")),
          "required" -> List("repl")),
      annotations = destructive_annotations)

  val repl_step_tool: Builtin_Tool =
    Builtin_Tool(
      name = "repl_step",
      fname = "step",
      description =
        "Apply one Isar command to a REPL and print the resulting proof " +
        "state. Examples: 'lemma \"True\"', 'by simp', 'definition ...'. " +
        "Do not send 'theory' headers -- the theory context was set by " +
        "repl_init. IMPORTANT: if a step FAILS (error result), the REPL " +
        "state is UNCHANGED -- do NOT call repl_back to undo a failed " +
        "step. Steps are subject to the REPL's timeout (default 10s, see " +
        "repl_timeout); a timed-out step is a failed step.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" -> JSON.Object(
            "repl" -> JSON.Object("type" -> "string"),
            "isar_text" -> JSON.Object("type" -> "string")),
          "required" -> List("repl", "isar_text")),
      annotations = mutating_annotations)

  val repl_state_tool: Builtin_Tool =
    Builtin_Tool(
      name = "repl_state",
      fname = "state",
      description =
        "Print the proof/theory state of a REPL at a given index: 0 = " +
        "the base state (right after init), N = the state after step " +
        "N-1, -1 = the latest state. Use it to re-read the current goal " +
        "without re-running anything, or to inspect an earlier state " +
        "before repl_fork / repl_truncate.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" -> JSON.Object(
            "repl" -> JSON.Object("type" -> "string"),
            "state_idx" -> JSON.Object("type" -> "integer")),
          "required" -> List("repl", "state_idx")),
      annotations = read_only_annotations)

  val repl_show_tool: Builtin_Tool =
    Builtin_Tool(
      name = "repl_show",
      fname = "show",
      description =
        "Describe one REPL: origin, timeout, pin status, and the " +
        "numbered list of its steps with staleness marks and proof-level " +
        "indentation. This is the map of the REPL -- use it to find the " +
        "step index for repl_edit / repl_truncate / repl_fork, and to " +
        "see which steps are stale after repl_edit / repl_rebase. For " +
        "the state at a point use repl_state; for the raw Isar text use " +
        "repl_text.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" -> JSON.Object("repl" -> JSON.Object("type" -> "string")),
          "required" -> List("repl")),
      annotations = read_only_annotations)

  val repl_text_tool: Builtin_Tool =
    Builtin_Tool(
      name = "repl_text",
      fname = "text",
      description =
        "Print the concatenated Isar text of all steps in a REPL, " +
        "newline-separated, exactly as they were sent. This is the " +
        "verified proof script: after a successful proof, splice this " +
        "text into the theory file. Stale steps are included as-is -- " +
        "run repl_replay first if you need the text to be verified " +
        "end-to-end.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" -> JSON.Object("repl" -> JSON.Object("type" -> "string")),
          "required" -> List("repl")),
      annotations = read_only_annotations)

  val repl_edit_tool: Builtin_Tool =
    Builtin_Tool(
      name = "repl_edit",
      fname = "edit",
      description =
        "Replace the step at index `idx` with new Isar text and " +
        "re-execute it from that point's pre-state. Subsequent steps " +
        "are automatically re-executed too (auto-replay is always on in " +
        "this server), so the REPL is left fully up to date -- no " +
        "separate repl_replay call is needed. If the new text FAILS, " +
        "the REPL is unchanged -- the old step survives. repl_edit edits " +
        "the REPL's step list, NOT the theory file on disk.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" -> JSON.Object(
            "repl" -> JSON.Object("type" -> "string"),
            "idx" -> JSON.Object("type" -> "integer"),
            "isar_text" -> JSON.Object("type" -> "string")),
          "required" -> List("repl", "idx", "isar_text")),
      annotations = mutating_annotations)

  val repl_replay_tool: Builtin_Tool =
    Builtin_Tool(
      name = "repl_replay",
      fname = "replay",
      description =
        "Re-execute all stale steps in a REPL, in order, each from its " +
        "predecessor's state. Steps become stale after repl_edit (the " +
        "tail) or repl_rebase (all of them). Non-stale steps are not " +
        "re-run. If a replayed step fails, replay stops there with the " +
        "error. Replies with the number of steps replayed.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" -> JSON.Object("repl" -> JSON.Object("type" -> "string")),
          "required" -> List("repl")),
      annotations = idempotent_mutating_annotations)

  val repl_truncate_tool: Builtin_Tool =
    Builtin_Tool(
      name = "repl_truncate",
      fname = "truncate",
      description =
        "Discard all steps after index `idx`, keeping steps 0..idx " +
        "(idx = -1 with negative counting: -1 drops the last step, -2 " +
        "the last two, ...; idx 0 keeps only step 0). Sub-REPLs forked " +
        "from a discarded state are removed too. If the REPL is pinned, " +
        "the pin goes stale. Nothing is re-executed -- the kept prefix " +
        "stays verified. For dropping just the last step, repl_back is " +
        "the shorthand.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" -> JSON.Object(
            "repl" -> JSON.Object("type" -> "string"),
            "idx" -> JSON.Object("type" -> "integer")),
          "required" -> List("repl", "idx")),
      annotations = destructive_annotations)

  val repl_back_tool: Builtin_Tool =
    Builtin_Tool(
      name = "repl_back",
      fname = "back",
      description =
        "Revert the last SUCCESSFUL step (shorthand for repl_truncate " +
        "with idx -1). Only call it after a step that succeeded -- a " +
        "FAILED repl_step left the state unchanged, so repl_back after " +
        "a failure would discard the last GOOD step. Sub-REPLs forked " +
        "from the discarded state are removed; a pin goes stale.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" -> JSON.Object("repl" -> JSON.Object("type" -> "string")),
          "required" -> List("repl")),
      annotations = destructive_annotations)

  val repl_merge_tool: Builtin_Tool =
    Builtin_Tool(
      name = "repl_merge",
      fname = "merge",
      description =
        "Merge a sub-REPL back into its parent: the sub-REPL's steps " +
        "are concatenated into a single block of Isar text and " +
        "re-executed in the parent at the fork point -- as a " +
        "replacement of the step at that index, or appended if the " +
        "fork was at the parent's latest state. On success the " +
        "sub-REPL is deleted. Fails if the argument is not a sub-REPL, " +
        "if either REPL is busy, or if the re-executed text fails in " +
        "the parent (both REPLs then survive unchanged).",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" -> JSON.Object("repl" -> JSON.Object("type" -> "string")),
          "required" -> List("repl")),
      annotations = destructive_annotations)

  val repl_timeout_tool: Builtin_Tool =
    Builtin_Tool(
      name = "repl_timeout",
      fname = "timeout",
      description =
        "Set the per-step timeout in seconds for one REPL (0 = " +
        "unlimited; default 10s). Applies to repl_step, repl_edit, " +
        "repl_replay and repl_merge re-execution. DO NOT raise it " +
        "above 10s without a specific reason: calls like metis, auto, " +
        "blast, force should finish in 5s, and a step that needs " +
        "longer usually points at a proof that ought to be broken " +
        "down. Forked REPLs inherit the parent's timeout at fork time.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" -> JSON.Object(
            "repl" -> JSON.Object("type" -> "string"),
            "secs" -> JSON.Object("type" -> "integer")),
          "required" -> List("repl", "secs")),
      annotations = idempotent_mutating_annotations)

  val repl_pin_tool: Builtin_Tool =
    Builtin_Tool(
      name = "repl_pin",
      fname = "pin",
      description =
        "Pin (snapshot) a REPL's current theory state so other REPLs " +
        "can build on it: pass \"pin@NAME\" in repl_init's theories to " +
        "start from the pinned state. The REPL must be at theory " +
        "level, not mid-proof. If the pinned REPL is modified " +
        "afterwards (step, edit, truncate), the pin is marked STALE " +
        "-- dependents keep working on the old snapshot until you " +
        "re-pin here and repl_rebase there. Re-pinning bumps the pin " +
        "version.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" -> JSON.Object("repl" -> JSON.Object("type" -> "string")),
          "required" -> List("repl")),
      annotations = mutating_annotations)

  val repl_unpin_tool: Builtin_Tool =
    Builtin_Tool(
      name = "repl_unpin",
      fname = "unpin",
      description =
        "Remove a REPL's pin. Fails if other REPLs were initialized " +
        "from this pin (remove them first, or leave the pin in " +
        "place). Unpinning does not change the REPL's own steps or " +
        "state.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" -> JSON.Object("repl" -> JSON.Object("type" -> "string")),
          "required" -> List("repl")),
      annotations = mutating_annotations)

  val repl_rebase_tool: Builtin_Tool =
    Builtin_Tool(
      name = "repl_rebase",
      fname = "rebase",
      description =
        "Re-resolve a REPL's init specs against the CURRENT pin " +
        "versions and rebuild its base theory. All steps are marked " +
        "stale -- call repl_replay afterwards to re-execute them on " +
        "the new base. Only works on REPLs created by repl_init from " +
        "theory/pin specs; fails if any referenced pin is stale " +
        "(re-pin it first). A REPL already on the latest pins replies " +
        "'already up to date'.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" -> JSON.Object("repl" -> JSON.Object("type" -> "string")),
          "required" -> List("repl")),
      annotations = idempotent_mutating_annotations)

  /* spec refinement (plans/sledgehammer): read-only -- run_sledgehammer
     only searches, the repl state is untouched -- but NOT idempotent,
     since external ATP results vary run to run. */
  val read_only_non_idempotent_annotations: JSON.Object.T =
    JSON.Object("readOnlyHint" -> true, "idempotentHint" -> false, "openWorldHint" -> false)

  /* first tool with an OPTIONAL property: "required" omits timeout_secs,
     and no special handler code is needed to omit the pair when the
     client omits the argument -- json_args already only emits pairs for
     keys actually present in the arguments object, so the ML dispatcher's
     own default (get_int_default "timeout_secs" 15) applies untouched. */
  val sledgehammer_tool: Builtin_Tool =
    Builtin_Tool(
      name = "sledgehammer",
      fname = "sledgehammer",
      description =
        "Run Sledgehammer on the REPL's current proof state: external " +
        "ATPs search for a proof and successful attempts come back as " +
        "'Try this: ...' lines with a one-liner you can pass to " +
        "repl_step. Requires the REPL to be mid-proof (after a lemma " +
        "statement). DO NOT set timeout_secs above 15 -- the 15s " +
        "default is almost always sufficient; Sledgehammer very rarely " +
        "finds proofs beyond that.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" ->
            JSON.Object(
              "repl" -> JSON.Object("type" -> "string"),
              "timeout_secs" -> JSON.Object("type" -> "integer", "default" -> 15)),
          "required" -> List("repl")),
      annotations = read_only_non_idempotent_annotations)

  /* spec decision (plans/find_theorems): mcp_server.py's python-side
     query auto-quoting heuristic (bare term patterns silently wrapped in
     quotes) is NOT reimplemented here -- the description teaches the
     quoting contract instead. Revisit only if e2e shows models failing
     at it. */
  /* context promotion (plans/find_theorems "context promotion", decided
     2026-07-12): repl is no longer required -- the search needs a
     context, not proof state. repl and theory are mutually exclusive,
     handler-enforced below (same pattern find_definition will use);
     theory is normalized to the canonical Thy_Info key via
     resolve_context_theory before it crosses the ir bridge, same
     theory-name spelling rule as every other theory-taking surface. */
  val find_theorems_tool: Builtin_Tool =
    Builtin_Tool(
      name = "find_theorems",
      fname = "find_theorems",
      description =
        "Search for theorems. Criteria: name:foo (name pattern, " +
        "unquoted), intro / elim / dest / solves (goal-based, need a " +
        "current goal), simp:\"term\" (simplification rules for a " +
        "term), or \"pattern\" (term pattern). Terms and patterns " +
        "MUST be quoted: \"_ + _\", \"_ @ _\"; name patterns are NOT " +
        "quoted: name:append. Prefix a criterion with - to negate it. " +
        "Examples: name:conjI, \"_ + _ = _\", simp:\"True\", " +
        "-name:foo. Multiple criteria are space-separated and " +
        "conjoined. Context: pass `repl` to search that REPL's " +
        "current context (goal-aware mid-proof -- needed for " +
        "intro/elim/dest/solves), or `theory` for a loaded/image " +
        "theory's global context; default is the base image. " +
        "Goal-based criteria require a REPL that is mid-proof.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" ->
            JSON.Object(
              "query" -> JSON.Object("type" -> "string"),
              "repl" -> JSON.Object("type" -> "string"),
              "theory" -> JSON.Object("type" -> "string"),
              "max_results" -> JSON.Object("type" -> "integer", "default" -> 40)),
          "required" -> List("query")),
      annotations = read_only_annotations,
      handler_fn = Some((backend, args) => {
        val repl = args.collectFirst({ case ("repl", v) => v })
        val theory = args.collectFirst({ case ("theory", v) => v })
        (repl, theory) match {
          case (Some(r), Some(t)) =>
            MCP_Session.Error(
              "find_theorems: repl and theory are mutually exclusive (got repl=" +
                quote(r) + ", theory=" + quote(t) + ")")
          case (_, Some(t)) =>
            backend.resolve_context_theory(t) match {
              case Right(resolved) =>
                backend.ir("find_theorems",
                  args.map({ case ("theory", _) => "theory" -> resolved; case p => p }))
              case Left(msg) => MCP_Session.Error(msg)
            }
          case _ => backend.ir("find_theorems", args)
        }
      }))

  /* find_definition (plans/find_definition): NAME-based lookup across the
     prover's name spaces (consts, types, classes, facts, locales,
     methods, attributes). Context selector shape and its mutual-
     exclusivity/normalization are exactly find_theorems' context
     promotion, reused verbatim -- resolve_context_theory was already
     generalized for this ("later find_definition", MCP_Backend). */
  val find_definition_tool: Builtin_Tool =
    Builtin_Tool(
      name = "find_definition",
      fname = "find_definition",
      description =
        "Find where a name is defined. Looks the name up in the " +
        "prover's name spaces -- constants, types, classes, facts, " +
        "locales, methods, attributes -- so it works for anything any " +
        "command introduced (definition, fun, datatype, typedef, " +
        "record, inductive, locale, ...). `kind` restricts the search " +
        "(const | type | class | fact | locale | method | attribute); " +
        "omitted searches all. Context: pass `repl` to search in that " +
        "REPL's context, or `theory` for a loaded/image theory's " +
        "global context; default is the base image. Each hit reports " +
        "kind, full internal name, the definition position, and -- " +
        "when the defining theory has recorded segments or a loaded " +
        "document -- the complete defining source block (the whole " +
        "datatype/fun/typedef command, showing constructors and " +
        "fields the name space alone cannot).",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" ->
            JSON.Object(
              "name" -> JSON.Object("type" -> "string"),
              "kind" -> JSON.Object(
                "type" -> "string",
                "enum" -> List("const", "type", "class", "fact", "locale", "method", "attribute")),
              "repl" -> JSON.Object("type" -> "string"),
              "theory" -> JSON.Object("type" -> "string")),
          "required" -> List("name")),
      annotations = read_only_annotations,
      handler_fn = Some((backend, args) => {
        val repl = args.collectFirst({ case ("repl", v) => v })
        val theory = args.collectFirst({ case ("theory", v) => v })
        (repl, theory) match {
          case (Some(r), Some(t)) =>
            MCP_Session.Error(
              "find_definition: repl and theory are mutually exclusive (got repl=" +
                quote(r) + ", theory=" + quote(t) + ")")
          case (_, Some(t)) =>
            backend.resolve_context_theory(t) match {
              case Right(resolved) =>
                backend.ir("find_definition",
                  args.map({ case ("theory", _) => "theory" -> resolved; case p => p }))
              case Left(msg) => MCP_Session.Error(msg)
            }
          case _ => backend.ir("find_definition", args)
        }
      }))

  /* wave 2 (theory management, scala-side): pass_args pulls "name"/
     "master_dir" out of the yxml-shaped pair list json_args already
     produces, so these three tools reuse the same argument encoding as
     every ir-backed tool even though they never touch the ir bridge. */
  def pass_arg(args: List[(String, String)], key: String): String =
    args.collectFirst({ case (`key`, v) => v }).getOrElse("")

  /* scope_add/scope_remove's "patterns" is a json array of strings,
     json_args's repeated-key encoding (same shape as repl_init's
     "theories") -- pull every value back out in array order. */
  def pass_args(args: List[(String, String)], key: String): List[String] =
    args.collect({ case (`key`, v) => v })

  val load_theory_tool: Builtin_Tool =
    Builtin_Tool(
      name = "load_theory",
      fname = "",
      description =
        "Load and check a theory from disk (with its transitive " +
        "dependencies) into the running session, by session-qualified " +
        "long name (\"HOL-Library.Multiset\") or by path via " +
        "master_dir. After loading, the theory is 'loaded' tier: " +
        "source, commands, diagnostics and entities resources answer " +
        "live, repl_init_from_source can attach to it, and it is " +
        "auto-added to the resource scope. Replies with per-theory " +
        "ok/error status; errors carry positions. Loading is the " +
        "expensive promotion -- a deep import chain outside the base " +
        "image can take minutes; see list_theories for what is " +
        "already available.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" ->
            JSON.Object(
              "name" -> JSON.Object("type" -> "string"),
              "master_dir" -> JSON.Object("type" -> "string")),
          "required" -> List("name")),
      annotations = idempotent_mutating_annotations,
      handler_fn = Some((backend, args) =>
        backend.load_theory(pass_arg(args, "name"), pass_arg(args, "master_dir"))))

  val unload_theory_tool: Builtin_Tool =
    Builtin_Tool(
      name = "unload_theory",
      fname = "",
      description =
        "Unload a theory that was loaded with load_theory: removes " +
        "its PIDE document (and purges the snapshot) and drops it " +
        "from the resource scope. Its resources revert to the " +
        "'filesystem' tier (source still readable, no semantics). " +
        "Cannot unload theories baked into the base image, and does " +
        "not touch REPLs that were initialized from the theory's " +
        "document -- remove or keep them explicitly.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" -> JSON.Object("name" -> JSON.Object("type" -> "string")),
          "required" -> List("name")),
      annotations = mutating_annotations,
      handler_fn = Some((backend, args) => backend.unload_theory(pass_arg(args, "name"))))

  val check_theory_tool: Builtin_Tool =
    Builtin_Tool(
      name = "check_theory",
      fname = "",
      description =
        "Re-read a theory file from disk and check it, then report " +
        "its diagnostics (errors and warnings with positions). Use " +
        "this after editing the file -- e.g. after splicing in a " +
        "proof extracted with repl_text -- to verify the file as it " +
        "now stands. Equivalent to unload_theory followed by " +
        "load_theory. A clean reply means the theory checks; errors " +
        "carry line positions for the next edit round.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" ->
            JSON.Object(
              "name" -> JSON.Object("type" -> "string"),
              "master_dir" -> JSON.Object("type" -> "string")),
          "required" -> List("name")),
      annotations = idempotent_mutating_annotations,
      handler_fn = Some((backend, args) =>
        backend.check_theory(pass_arg(args, "name"), pass_arg(args, "master_dir"))))

  val list_sessions_tool: Builtin_Tool =
    Builtin_Tool(
      name = "list_sessions",
      fname = "",
      description =
        "List all Isabelle sessions known to the server, enumerated " +
        "from ROOT files on the configured session directories " +
        "(distribution, AFP if registered, etc.). Each entry shows " +
        "session name, chapter, whether a built heap exists, and " +
        "theory count. Mark the session the server is running as base " +
        "image. Sessions are coarse-grained units: theories in the base " +
        "image are queryable now; others require load_theory (slow) or " +
        "a heap rebuild + server restart (fast, coarse). Follow with " +
        "list_theories to see what is in a session.",
      input_schema = JSON.Object("type" -> "object", "properties" -> JSON.Object.empty, "required" -> List()),
      annotations = JSON.Object("readOnlyHint" -> true, "idempotentHint" -> true, "openWorldHint" -> false),
      handler_fn = Some((backend, _) => backend.list_sessions_info()))

  val list_theories_tool: Builtin_Tool =
    Builtin_Tool(
      name = "list_theories",
      fname = "",
      description =
        "List all theories in a given Isabelle session (by name, as " +
        "shown by list_sessions). Each entry is a long theory name; " +
        "use load_theory to load one, search_sources for a name search.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" -> JSON.Object("session" -> JSON.Object("type" -> "string")),
          "required" -> List("session")),
      annotations = JSON.Object("readOnlyHint" -> true, "idempotentHint" -> true, "openWorldHint" -> false),
      handler_fn = Some((backend, args) => backend.list_theories_info(pass_arg(args, "session"))))

  val search_sources_tool: Builtin_Tool =
    Builtin_Tool(
      name = "search_sources",
      fname = "",
      description =
        "Search for theories by substring match. Scans all theories " +
        "across all sessions and returns long names that contain the " +
        "given pattern. Empty pattern returns no results (use " +
        "list_theories for a full enumeration of one session).",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" -> JSON.Object("pattern" -> JSON.Object("type" -> "string")),
          "required" -> List("pattern")),
      annotations = JSON.Object("readOnlyHint" -> true, "idempotentHint" -> true, "openWorldHint" -> false),
      handler_fn = Some((backend, args) => backend.search_sources(pass_arg(args, "pattern"))))

  /* wave 5 (plans/doc_list, spec "documentation for the agent"): the
     Doc.contents() catalog (manuals, release notes, examples), joined per
     entry to the doc session doc_read will serve chapters from. Not
     scope-filtered: catalog items, not theories -- discovery is never
     scoped (spec "scope note"). */
  val doc_list_tool: Builtin_Tool =
    Builtin_Tool(
      name = "doc_list",
      fname = "",
      description =
        "List the Isabelle documentation catalog: the manuals, release " +
        "notes, and examples shipped with the distribution (what " +
        "`isabelle doc` shows). Each entry reports name, title, its " +
        "catalog section, and how it is readable: manuals name the " +
        "source session whose theory files doc_read serves (chapter-" +
        "level plain text -- never the pdf); plain-text entries (NEWS, " +
        "examples) are read directly. Grep across manuals with " +
        "search_sources using the source session names. Glob `pattern` " +
        "filters entry names.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" -> JSON.Object("pattern" -> JSON.Object("type" -> "string")),
          "required" -> List()),
      annotations = read_only_annotations,
      handler_fn = Some((backend, args) => backend.doc_list(pass_arg(args, "pattern"))))

  /* wave 5 (plans/doc_read, spec "documentation for the agent"): reads a
     doc_list entry from its plain-text source -- manuals resolve through
     the catalog to their src/Doc session's chapter .thy files (toc
     without `section`, that section's source text with it); NEWS/examples
     are plain files (`lines` windows them). Never the pdf. */
  val doc_read_tool: Builtin_Tool =
    Builtin_Tool(
      name = "doc_read",
      fname = "",
      description =
        "Read Isabelle documentation from its plain-text sources. `name` " +
        "is a doc_list entry (e.g. \"isar-ref\", \"system\", \"NEWS\"). " +
        "For manuals: without `section`, returns the table of contents -- " +
        "chapter and section headings with their source file and line; " +
        "with `section`, returns that section's source text (substring " +
        "match on headings; an ambiguous match lists the candidates). " +
        "Manual text is Isar theory source -- prose with antiquotations " +
        "-- not the rendered pdf. For plain-text entries (NEWS, examples), " +
        "returns file content; `lines` (e.g. \"120-180\") windows it. Long " +
        "sections are truncated with a note; narrow with a more specific " +
        "`section` or use search_sources over the manual's source " +
        "session. `section` and `lines` are mutually exclusive -- section " +
        "addresses manuals, lines addresses plain entries.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" ->
            JSON.Object(
              "name" -> JSON.Object("type" -> "string"),
              "section" -> JSON.Object("type" -> "string"),
              "lines" -> JSON.Object("type" -> "string")),
          "required" -> List("name")),
      annotations = read_only_annotations,
      handler_fn = Some((backend, args) =>
        backend.doc_read(
          pass_arg(args, "name"), pass_arg(args, "section"), pass_arg(args, "lines"))))

  /* wave 4 (plans/scope_add, plans/scope_remove, spec "scoping"): the
     resource scope is a set of theory-name glob patterns controlling
     what resources/list enumerates -- scope filters DISCOVERY, never
     ACCESS (resources/read works on any valid uri regardless). Scala
     state only (MCP_Session.scope_patterns), no bridge. */
  val scope_add_tool: Builtin_Tool =
    Builtin_Tool(
      name = "scope_add",
      fname = "",
      description =
        "Add theory-name patterns to the resource scope -- the set of " +
        "theories that resources/list enumerates. Glob over long names: " +
        "\"HOL-Library.*\", \"Main\". Matching theories appear in the " +
        "listing tagged with their availability tier (image/loaded/" +
        "filesystem). Scope only controls the LISTING: any valid " +
        "resource uri is readable regardless, and scope_add does NOT " +
        "load or check anything (use load_theory for semantics). " +
        "Replies with the added patterns and their current match counts.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" ->
            JSON.Object("patterns" ->
              JSON.Object("type" -> "array", "items" -> JSON.Object("type" -> "string"))),
          "required" -> List("patterns")),
      annotations = idempotent_mutating_annotations,
      handler_fn = Some((backend, args) => backend.scope_add(pass_args(args, "patterns"))))

  val scope_remove_tool: Builtin_Tool =
    Builtin_Tool(
      name = "scope_remove",
      fname = "",
      description =
        "Remove theory-name patterns from the resource scope. Patterns " +
        "are removed literally (the exact strings previously added), not " +
        "by re-matching -- removing \"HOL-Library.*\" removes that " +
        "pattern, not individual theories it matched. The implicit scope " +
        "members (theories loaded with load_theory, active REPLs, named " +
        "resources) are not removable this way; unload_theory / " +
        "repl_remove govern those. Replies with the remaining scope.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" ->
            JSON.Object("patterns" ->
              JSON.Object("type" -> "array", "items" -> JSON.Object("type" -> "string"))),
          "required" -> List("patterns")),
      annotations = idempotent_mutating_annotations,
      handler_fn = Some((backend, args) => backend.scope_remove(pass_args(args, "patterns"))))

  val scope_show_tool: Builtin_Tool =
    Builtin_Tool(
      name = "scope_show",
      fname = "",
      description =
        "Show the current resource scope: the explicit patterns with " +
        "their match counts, plus the implicit members -- theories " +
        "loaded with load_theory, active REPLs, and registered named " +
        "resources. This is what resources/list will enumerate. Scope " +
        "never limits resource reads, only the listing.",
      input_schema = JSON.Object("type" -> "object"),
      annotations = read_only_annotations,
      handler_fn = Some((backend, _) => backend.scope_show()))

  val builtins: List[Builtin_Tool] =
    List(repl_list_tool, repl_init_tool, repl_init_from_source_tool, repl_remove_tool, repl_step_tool, repl_state_tool,
      repl_show_tool, repl_text_tool, repl_edit_tool, repl_replay_tool, repl_truncate_tool,
      repl_back_tool, repl_merge_tool, repl_timeout_tool, repl_pin_tool, repl_unpin_tool,
      repl_rebase_tool, sledgehammer_tool, find_theorems_tool, find_definition_tool,
      load_theory_tool, unload_theory_tool, check_theory_tool,
      list_sessions_tool, list_theories_tool, search_sources_tool,
      scope_add_tool, scope_remove_tool, scope_show_tool,
      doc_list_tool, doc_read_tool)

  /* tool_scope_show/set/include (below) are per-connection Builtin_Tool
     values (Handler.tool_scope_builtins), so their names are listed here
     separately -- the one authoritative name list beyond `builtins`,
     kept in sync BY HAND with the three name = "..." literals below.
     Used for the exposure() reserved set (Handler.handle, tools/list)
     and as the drift-gate target (plans/builtin_activation, tested over
     the live bridge: mirror name set in MCP_Tools.thy == this list ++
     builtins.map(_.name), both directions). */
  val tool_scope_builtin_names: List[String] =
    List("tool_scope_show", "tool_scope_set", "tool_scope_include")

  val all_builtin_names: List[String] = builtins.map(_.name) ++ tool_scope_builtin_names

  /* tool_scope_show/set/include (plans/tool_scope, spec "the agent
     context"): unlike every other builtin above, these read and mutate
     the CONNECTION's tool scope, not backend/prover state -- Builtin_Tool
     values are shared, static (module-level), so their handler_fn cannot
     close over a Handler's per-connection var directly. Handler builds
     its OWN three Builtin_Tool values (tool_scope_builtins below),
     closing over its own scope_designation/scope_bundles, and dispatch
     looks them up ALONGSIDE the static list (builtins ++
     tool_scope_builtins), which also reserves their names for
     exposure() purposes. */
  def format_designation(designation: String): String =
    if (designation == "") "default (the base registry theory)"
    else if (designation.startsWith("repl:")) "repl " + quote(designation.stripPrefix("repl:"))
    else "theory " + quote(designation)

  /* json arguments object -> the named yxml pair list MCP.ir expects;
     a string property becomes one pair, a json array of strings becomes
     repeated (key, element) pairs IN ARRAY ORDER (repl_init.theories);
     any other json value falls back to its json rendering as a single
     pair (no builtin tool needs more than that yet) */
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
    val PARSE_ERROR = -32700
    val INVALID_REQUEST = -32600
    val METHOD_NOT_FOUND = -32601
    val INVALID_PARAMS = -32602

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
     rides the protocol channel exclusively (protocol_command_raw for
     MCP.ir/MCP.tools/MCP.run_tool/MCP.read_resource), so nothing
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
     Byte fidelity still holds below this point, which is where the
     ml-unit and bridge fidelity tests (plans/repl_text T1,
     plans/repl_step T1) assert it. */
  def text_result(text: String, is_error: Boolean = false): JSON.Object.T = {
    val result =
      JSON.Object("content" ->
        List(JSON.Object("type" -> "text", "text" -> Symbol.decode(text))))
    if (is_error) result + ("isError" -> true) else result
  }

  def resource_contents(uri: String, text: String): JSON.Object.T =
    JSON.Object("contents" ->
      List(JSON.Object("uri" -> uri, "mimeType" -> "text/plain",
        "text" -> Symbol.decode(text))))

  /* resource templates (spec's "resource templates (resources/templates/
     list)"): static metadata, the same set regardless of backend or
     session state -- unlike resources/list (concrete, scope-filtered),
     a template's existence does not depend on anything being loaded.
     NOT every template is backed by a working resources/read yet: only
     isabelle://repl/{id} and its /text sibling dispatch to the ir
     bridge today (see MCP_Session.mcp_resource_read); the rest need
     later waves (theory source/commands/diagnostics/entities need
     session-structure discovery and load_theory, isabelle://named/
     needs the mcp_resource ML command) and reply with a clear "not
     yet implemented" error naming the gap rather than pretending to
     work -- listed here anyway since the template CONTRACT (the uri
     shape) is settled even before every backing lands, matching the
     spec's own testing checklist ("resources/templates/list contains
     the documented templates"). */
  def resource_template(uriTemplate: String, name: String, description: String): JSON.Object.T =
    JSON.Object(
      "uriTemplate" -> uriTemplate,
      "name" -> name,
      "description" -> description,
      "mimeType" -> "text/plain",
      "annotations" -> JSON.Object("audience" -> List("assistant")))

  val resource_templates: List[JSON.Object.T] =
    List(
      resource_template("isabelle://theory/{name}", "theory",
        "Theory source text: PIDE-loaded theories serve the live " +
        "snapshot source, image/filesystem theories serve file " +
        "content via the session-structure path map."),
      resource_template("isabelle://theory/{name}/commands", "theory-commands",
        "Navigation map of a theory's command spans (index, keyword, " +
        "line, offset, file) -- how to pick attach points for " +
        "repl_init_from_source."),
      resource_template("isabelle://theory/{name}/diagnostics", "theory-diagnostics",
        "Errors and warnings with positions from the PIDE snapshot; " +
        "image theories report checked-at-build-time, filesystem " +
        "theories report not-checked."),
      resource_template("isabelle://theory/{name}/entities", "theory-entities",
        "Entities defined in a theory (consts, types, classes, facts, " +
        "locales) with kinds and positions."),
      resource_template("isabelle://repl/{id}", "repl",
        "One REPL's origin, steps, staleness marks, and pin state " +
        "(Ir.show)."),
      resource_template("isabelle://repl/{id}/text", "repl-text",
        "One REPL's concatenated Isar text, newline-separated, " +
        "exactly as sent (Ir.text)."),
      resource_template("isabelle://named/{name}", "named",
        "A user-registered mcp_resource (named facts, diagnostic " +
        "command output, or an ML generator's result)."))


  /* server startup and readiness (plans/readiness, spec "server startup
     and readiness"): MCP_Session.start (build_heap + start_session +
     use_theories) runs on a background thread instead of gating serve()'s
     first stdin read -- the prover genuinely cannot start without a built
     heap, so the only move is to stop making the CLIENT wait for it.
     Not_Ready carries a short human progress string ("building
     SESSION" / "starting session SESSION"); Ready carries the live
     backend once build+boot succeed; Failed is terminal for the process
     (no retry loop -- restarting the server is the recovery). */
  sealed abstract class Readiness
  case class Not_Ready(progress: String) extends Readiness
  case class Ready(backend: MCP_Backend) extends Readiness
  case class Failed(message: String) extends Readiness


  /* request handling: pure JSON in, JSON out — no I/O, no session,
     unit-testable against any MCP_Backend */

  class Handler(
    readiness: () => Readiness,
    session_name: String = "",
    session_dirs: List[Path] = Nil,
    theory: String = ""
  ) {
    /* pre-readiness Handler(backend) construction (every existing test and
       serve() call site): the backend is live from the start, so this is
       just Ready(backend) with no session identity to report -- session
       identity only matters for the Not_Ready/Failed messages below,
       which this shape never produces. */
    def this(backend: MCP_Backend) = this(() => Ready(backend))

    /* the not-ready/failed tool-call and resource-read text (spec "server
       startup and readiness"): named by session, not by a generic
       placeholder, so a client watching multiple servers can tell them
       apart. */
    private def not_ready_text(progress: String): String =
      "session " + session_name + " is not ready: " + progress + ". This tool needs " +
      "the prover; retry shortly. Read isabelle://session for status."

    private def failed_text(message: String): String =
      "session " + session_name + " failed to start: " + message + ". The server " +
      "cannot serve prover-backed tools; restart it after fixing the build."

    /* isabelle://session while not ready (spec): the same overview shape
       the real backend reports when Ready, minus the Thy_Info round trip
       nothing can answer yet. */
    private def session_state_text(status: String): String =
      "session: " + session_name + "\n" +
      "dirs: " + session_dirs.map(_.implode).mkString(", ") + "\n" +
      "theory: " + theory + "\n" +
      "status: " + status

    /* the AGENT CONTEXT (spec): "" = default (the -T theory); a bare
       canonical theory long name; "repl:ID". Connection state, like the
       phase-2 resource scope -- reset per connection, not persisted. */
    private var scope_designation: String = ""
    private var scope_bundles: List[String] = Nil

    private val tool_scope_show_tool: Builtin_Tool =
      Builtin_Tool(
        name = "tool_scope_show",
        fname = "",
        description =
          "Show the current tool scope: which theory or repl context " +
          "the server reads user-registered tools from, the bundles " +
          "included in it, and the tools registered and active there. " +
          "Tools are context entities in Isabelle: a tool is visible " +
          "when the scope's context (transitively) imports its " +
          "registering theory and it has not been deactivated " +
          "(declare [[mcp_tools del: ...]] or a closed bundle).",
        input_schema = JSON.Object("type" -> "object"),
        annotations = read_only_annotations,
        handler_fn = Some((backend, _) => {
          val bundles_text =
            if (scope_bundles.isEmpty) "none" else scope_bundles.mkString(", ")
          /* a designation valid at tool_scope_set time can go stale
             later (e.g. its repl was removed): ml_tools degrades a bad
             designation to an empty list (MCP.tools' crash-safety
             floor), indistinguishable from a merely-empty scope unless
             checked separately -- so check first and say so, rather
             than silently reporting "0 active tools". */
          backend.check_designation(scope_designation, scope_bundles) match {
            case MCP_Session.Error(msg) =>
              MCP_Session.Ok(
                "Tool scope: " + format_designation(scope_designation) + " (BROKEN: " + msg +
                  ") -- use tool_scope_set to point it at a valid theory or repl\n" +
                "Included bundles: " + bundles_text)
            case MCP_Session.Ok(_) =>
              val rows = backend.ml_tools(scope_designation, scope_bundles).rows
              MCP_Session.Ok(
                "Tool scope: " + format_designation(scope_designation) + "\n" +
                "Included bundles: " + bundles_text + "\n" +
                "Active tools (" + rows.length + "): " +
                (if (rows.isEmpty) "none" else rows.map(_.name).mkString(", ")))
          }
        }))

    private val tool_scope_set_tool: Builtin_Tool =
      Builtin_Tool(
        name = "tool_scope_set",
        fname = "",
        description =
          "Set the tool scope to a theory (by name, any known spelling) " +
          "or to a repl (by id). Repl scope serves the tools of the " +
          "repl's CURRENT state -- use this after registering a tool " +
          "via repl_step to call it without persisting the theory " +
          "first. Replaces the designation AND clears any bundles " +
          "included with tool_scope_include (fresh context, no " +
          "accumulated soup).",
        input_schema =
          JSON.Object(
            "type" -> "object",
            "properties" -> JSON.Object(
              "theory" -> JSON.Object("type" -> "string"),
              "repl" -> JSON.Object("type" -> "string")),
            "required" -> List()),
        annotations = mutating_annotations,
        handler_fn = Some((backend, args) => {
          val theory = args.collectFirst({ case ("theory", v) => v })
          val repl = args.collectFirst({ case ("repl", v) => v })
          (theory, repl) match {
            case (Some(t), Some(r)) =>
              MCP_Session.Error(
                "tool_scope_set: theory and repl are mutually exclusive (got theory=" +
                  quote(t) + ", repl=" + quote(r) + ")")
            case (None, None) =>
              MCP_Session.Error("tool_scope_set: exactly one of theory or repl is required")
            case (Some(t), None) =>
              backend.resolve_context_theory(t) match {
                case Right(canonical) =>
                  scope_designation = canonical
                  scope_bundles = Nil
                  MCP_Session.Ok("Tool scope set to theory " + quote(canonical))
                case Left(msg) => MCP_Session.Error(msg)
              }
            case (None, Some(r)) =>
              val candidate = "repl:" + r
              backend.check_designation(candidate) match {
                case MCP_Session.Ok(_) =>
                  scope_designation = candidate
                  scope_bundles = Nil
                  MCP_Session.Ok("Tool scope set to repl " + quote(r))
                case error @ MCP_Session.Error(_) => error
              }
          }
        }))

    private val tool_scope_include_tool: Builtin_Tool =
      Builtin_Tool(
        name = "tool_scope_include",
        fname = "",
        description =
          "Open bundles in the current tool scope (like Isar's `context " +
          "includes`): tools activated by those bundles become servable " +
          "until the scope changes (tool_scope_set). Bundle names " +
          "resolve in the scope's context.",
        input_schema =
          JSON.Object(
            "type" -> "object",
            "properties" -> JSON.Object(
              "bundles" ->
                JSON.Object("type" -> "array", "items" -> JSON.Object("type" -> "string"))),
            "required" -> List("bundles")),
        annotations = mutating_annotations,
        handler_fn = Some((backend, args) => {
          val bundles = args.collect({ case ("bundles", v) => v })
          val candidate = scope_bundles ++ bundles
          backend.check_designation(scope_designation, candidate) match {
            case MCP_Session.Ok(_) =>
              scope_bundles = candidate
              MCP_Session.Ok("Included bundle(s): " + bundles.mkString(", "))
            case error @ MCP_Session.Error(_) => error
          }
        }))

    private val tool_scope_builtins: List[Builtin_Tool] =
      List(tool_scope_show_tool, tool_scope_set_tool, tool_scope_include_tool)

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
                  "tools" -> JSON.Object("listChanged" -> true),
                  "resources" -> JSON.Object("listChanged" -> true)),
              "serverInfo" ->
                JSON.Object("name" -> server_name, "version" -> server_version))))

        case Some("notifications/initialized") => None

        case Some("ping") => Some(RPC.response(id, JSON.Object()))

        case Some("tools/list") =>
          val all_builtins = builtins ++ tool_scope_builtins
          readiness() match {
            /* not ready / failed (plans/readiness): no prover to ask, so
               the reply is the static builtin table alone -- an
               ML-registered tool the server has never seen cannot be
               described. Deliberately NOT filtered by activation either
               (that needs backend.ml_tools() too): a client that lists
               early sees every builtin; the ML rows and any hidden-builtin
               narrowing appear on its next tools/list once ready. */
            case Not_Ready(_) | Failed(_) =>
              val builtin_json =
                all_builtins.map(t =>
                  JSON.Object(
                    "name" -> t.name,
                    "description" -> t.description,
                    "inputSchema" -> t.input_schema,
                    "annotations" -> t.annotations))
              Some(RPC.response(id, JSON.Object("tools" -> builtin_json)))

            case Ready(backend) =>
              /* the RESERVED set for exposure() stays the FULL table
                 regardless of activation: a del'd builtin is hidden but
                 still callable (ASYMMETRIC CALLABILITY), so its bare name
                 must stay reserved -- otherwise a same-named ML tool could
                 grab the bare name while builtin dispatch precedence
                 (tools/call below) still shadows it into being uncallable. */
              val builtin_names = all_builtins.map(_.name).toSet
              val reply = backend.ml_tools(scope_designation, scope_bundles)
              val rows = reply.rows
              /* hide iff explicitly (name, false): an empty/missing section
                 (no mirror registered -- broken -T theory, no MCP_Tools
                 import) hides nothing, which IS the availability floor
                 (plans/builtin_activation) -- no special case needed. */
              val hidden = reply.builtin_activation.collect({ case (n, false) => n }).toSet
              val visible_builtins = all_builtins.filterNot(t => hidden(t.name))
              val builtin_json =
                visible_builtins.map(t =>
                  JSON.Object(
                    "name" -> t.name,
                    "description" -> t.description,
                    "inputSchema" -> t.input_schema,
                    "annotations" -> t.annotations))
              val exposed = exposure(rows.map(_.name), builtin_names)
              val ml_json =
                rows.flatMap(row =>
                  exposed.get(row.name).map(x =>
                    JSON.Object(
                      "name" -> x,
                      "description" -> row.description,
                      "inputSchema" -> ml_tool_schema(row.params)) ++
                    JSON.Object.apply(
                      ml_tool_annotations(row.form).toList.map("annotations" -> _)*)))
              Some(RPC.response(id, JSON.Object("tools" -> (builtin_json ++ ml_json))))
          }

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
              readiness() match {
                /* not ready / failed (plans/readiness): the call is
                   well-formed and will succeed later, so this is a
                   tool-level failure the agent can retry (isError), not a
                   json-rpc error -- the name is not even looked up, since
                   nothing prover-backed can run regardless of which tool
                   was named. */
                case Not_Ready(progress) =>
                  Some(RPC.response(id, text_result(not_ready_text(progress), is_error = true)))
                case Failed(message) =>
                  Some(RPC.response(id, text_result(failed_text(message), is_error = true)))
                case Ready(backend) =>
                  (builtins ++ tool_scope_builtins).find(_.name == name) match {
                    case Some(tool) =>
                      tool.handler(backend, json_args(arguments)) match {
                        case MCP_Session.Ok(text) =>
                          Some(RPC.response(id, text_result(text)))
                        case MCP_Session.Error(message) =>
                          Some(RPC.response(id, text_result(message, is_error = true)))
                      }
                    case None =>
                      /* resolve the exposed name back to the full internal
                         name through the same map tools/list used; unknown
                         names pass through for the ML side's error. Arguments
                         go over as named pairs; missing/ill-typed values are
                         the ML validator's job (typed errors name the arg). */
                      val exposed =
                        exposure(backend.ml_tools(scope_designation, scope_bundles).rows.map(_.name),
                          (builtins ++ tool_scope_builtins).map(_.name).toSet)
                      val internal =
                        exposed.collectFirst({ case (i, x) if x == name => i }).getOrElse(name)
                      backend.ml_run(internal, json_args(arguments), scope_designation, scope_bundles) match {
                        case MCP_Session.Ok(text) =>
                          Some(RPC.response(id, text_result(text)))
                        case MCP_Session.Error(message) =>
                          Some(RPC.response(id, text_result(message, is_error = true)))
                      }
                  }
              }
          }

        case Some("resources/list") =>
          readiness() match {
            /* not ready / failed (plans/readiness): isabelle://session is
               the one resource that always exists and needs no backend --
               it is the read that answers "what is this server doing". */
            case Not_Ready(_) | Failed(_) =>
              Some(RPC.response(id, JSON.Object("resources" -> List(
                JSON.Object("uri" -> "isabelle://session", "name" -> "session",
                  "description" -> "current session name, dirs, loaded theories")))))
            case Ready(backend) =>
              val resources =
                backend.mcp_resources().map({ case (uri, name, description) =>
                  JSON.Object("uri" -> uri, "name" -> name, "description" -> description)
                })
              Some(RPC.response(id, JSON.Object("resources" -> resources)))
          }

        case Some("resources/templates/list") =>
          Some(RPC.response(id, JSON.Object("resourceTemplates" -> resource_templates)))

        case Some("resources/read") =>
          val params = JSON.value(json, "params").getOrElse(JSON.Object())
          JSON.string(params, "uri") match {
            case None => Some(RPC.error(id, RPC.INVALID_PARAMS, "Missing resource uri"))
            case Some(uri) =>
              readiness() match {
                case Ready(backend) =>
                  backend.mcp_resource_read(uri) match {
                    case MCP_Session.Ok(text) => Some(RPC.response(id, resource_contents(uri, text)))
                    case MCP_Session.Error(message) =>
                      Some(RPC.error(id, RPC.INVALID_PARAMS, message))
                  }
                /* not ready / failed (plans/readiness): isabelle://session
                   reports the state instead of the Thy_Info listing (which
                   needs a round trip nothing can answer yet); every other
                   uri names the state as a protocol-level error, same
                   error shape a genuinely unknown uri would get. */
                case Not_Ready(progress) =>
                  if (uri == "isabelle://session")
                    Some(RPC.response(id,
                      resource_contents(uri, session_state_text("not ready (" + progress + ")"))))
                  else Some(RPC.error(id, RPC.INVALID_PARAMS, not_ready_text(progress)))
                case Failed(message) =>
                  if (uri == "isabelle://session")
                    Some(RPC.response(id,
                      resource_contents(uri, session_state_text("failed (" + message + ")"))))
                  else Some(RPC.error(id, RPC.INVALID_PARAMS, failed_text(message)))
              }
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


  /* server loop over injectable streams (tests drive it with strings).

     No default arguments here (Scala forbids two overloads that both
     declare them) -- the backend-only overload below carries the
     defaults instead, and run() passes everything explicitly. */
  def serve(
    readiness: () => Readiness,
    in: BufferedReader,
    out: PrintStream,
    progress: Progress,
    session_name: String,
    session_dirs: List[Path],
    theory: String,
    install_changed_sender: (String => Unit) => Unit,
    on_shutdown: () => Unit
  ): Unit = {
    val handler = new Handler(readiness, session_name, session_dirs, theory)

    /* replies and server-initiated notifications interleave on stdout:
       writes are line-atomic via the lock */
    def print_json(json: JSON.T): Unit =
      out.synchronized {
        out.println(JSON.Format(json))
        out.flush()
      }

    /* MCP.tools_changed / MCP.resources_changed (MCP_Tool.declare in a
       theory loaded at runtime) -> notifications/{tools,resources}/
       list_changed; capability listChanged: true is declared at
       initialize. install_changed_sender hands the sender to whoever
       owns the backend -- immediately for the backend-only overload
       below (the backend already exists), or the readiness cell's
       Ready-publish path in run() (plans/readiness 3a: serve() itself
       runs before any backend exists, so there is nothing to register
       the sender ON here). */
    install_changed_sender(what =>
      print_json(JSON.Object(
        "jsonrpc" -> "2.0", "method" -> ("notifications/" + what + "/list_changed"))))

    try {
      var finished = false
      while (!finished) {
        in.readLine() match {
          case null => finished = true
          case line if line.isBlank =>
          case line => handler.handle_line(line).foreach(print_json)
        }
      }
    }
    finally {
      progress.echo("Shutting down ...")
      on_shutdown()
    }
  }

  def serve(
    backend: MCP_Backend,
    in: BufferedReader,
    out: PrintStream,
    progress: Progress = new Progress
  ): Unit =
    serve(() => Ready(backend), in, out, progress,
      session_name = "", session_dirs = Nil, theory = "",
      install_changed_sender = backend.set_changed_handler,
      on_shutdown = () => backend.stop())


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
    val cell = Synchronized(State(Not_Ready("building " + session_name), None, false))

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
        MCP_Session.boot(options, session_name, session_dirs, theory, progress)
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
          cell.change(s => s.copy(readiness = Failed(Exn.message(exn))))
      }
    }

    val stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
    progress.echo(
      "Serving stdin; session " + quote(session_name) + " building in the background ...")
    serve(
      () => cell.value.readiness,
      stdin, System.out, progress,
      session_name = session_name, session_dirs = session_dirs, theory = theory,
      install_changed_sender = sender => cell.change(s => s.copy(changed_sender = Some(sender))),
      on_shutdown = () => {
        val to_stop =
          cell.change_result { s =>
            s.readiness match {
              case Ready(backend) => (Some(backend), s.copy(shutting_down = true))
              case _ => (None, s.copy(shutting_down = true))
            }
          }
        to_stop.foreach(_.stop())
      })
  }
}
