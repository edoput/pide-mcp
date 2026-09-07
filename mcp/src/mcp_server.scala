/*  Title:      mcp/src/mcp_server.scala

MCP server over stdio: newline-delimited JSON-RPC 2.0 on stdin/stdout.

Nothing but protocol replies may be written to stdout; all logging goes
through the given progress (Console_Progress(stderr = true) in the tool).
*/

package isabelle.mcp

import isabelle._
import isabelle.mcp.application.{McpApplication, McpOutputPolicy}
import isabelle.mcp.connection._

import java.io.{BufferedReader, PrintStream}

object MCP_Server {
  val server_name = "isabelle-mcp"
  val server_version = "0.1.0"
  val default_protocol_version = "2025-03-26"

  /* declared params -> JSON schema (spec phase 3 "schema over the
     bridge"): nat/int -> integer, bool -> boolean, everything else
     (string/source/args/term/typ/fact) -> string with the validation
     contract in the property description; defaults and descriptions
     carried. A tool with no declared params gets the bare object schema
     (plans/ml_builtin_migration step 4) -- not every ML tool actually has
     an "input" property (e.g. a moved zero-param builtin like repl_list),
     so advertising one unconditionally would be a lie about the interface.
     MCP_Combinators.func-form tools (e.g. "shout") are unaffected: they
     always supply their own real "input" param, so params is never
     actually empty for them. */

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
    handler_fn: Option[(MCP_Backend, List[(String, String)],
      McpApplication.Cancellation) => MCP_Session.Result] = None) {
    def requires_untrusted_output: Boolean = handler_fn.isEmpty

    def handler(backend: MCP_Backend, args: List[(String, String)],
      cancellation: McpApplication.Cancellation): MCP_Session.Result =
      handler_fn match {
        case Some(f) =>
          backend.direct_cancellable(cancellation) { f(backend, args, cancellation) }
        case None => backend.ir_cancellable(fname, args, cancellation)
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
      handler_fn = Some((backend, args, cancellation) => {
        val repl = pass_arg(args, "repl")
        val theory = pass_arg(args, "theory")
        val offset = args.collectFirst({ case ("offset", v) => v.toInt })
        val pattern = args.collectFirst({ case ("pattern", v) => v })
        val index = args.collectFirst({ case ("index", v) => v.toInt })
        MCP_Session.Locator.exactly_one(offset, pattern, index) match {
          case Left(msg) => MCP_Session.Error("repl_init_from_source: " + msg)
          case Right(()) =>
            backend.init_from_source_cancellable(
              repl, theory, offset, pattern, index, cancellation)
        }
      }))

  val repl_fork_tool: Builtin_Tool =
    Builtin_Tool(
      name = "repl_fork",
      fname = "fork",
      description =
        "Fork a sub-REPL from an existing REPL at the given state index " +
        "(0 = base state, N = after step N-1, -1 = latest). The fork " +
        "starts with no steps of its own and inherits the parent's " +
        "timeout. Use it to try a proof approach without disturbing the " +
        "parent; bring the result back with repl_merge, or discard it " +
        "with repl_remove. Truncating or removing the parent past the " +
        "fork point removes the fork.",
      input_schema =
        JSON.Object(
          "type" -> "object",
          "properties" -> JSON.Object(
            "repl" -> JSON.Object("type" -> "string"),
            "new_repl" -> JSON.Object("type" -> "string"),
            "state_idx" -> JSON.Object("type" -> "integer")),
          "required" -> List("repl", "new_repl", "state_idx")),
      annotations = mutating_annotations)

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
      handler_fn = Some((backend, args, cancellation) => {
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
                backend.ir_cancellable("find_theorems",
                  args.map({ case ("theory", _) => "theory" -> resolved; case p => p }),
                  cancellation)
              case Left(msg) => MCP_Session.Error(msg)
            }
          case _ => backend.ir_cancellable("find_theorems", args, cancellation)
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
      handler_fn = Some((backend, args, cancellation) => {
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
                backend.ir_cancellable("find_definition",
                  args.map({ case ("theory", _) => "theory" -> resolved; case p => p }),
                  cancellation)
              case Left(msg) => MCP_Session.Error(msg)
            }
          case _ => backend.ir_cancellable("find_definition", args, cancellation)
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
      handler_fn = Some((backend, args, _) =>
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
      handler_fn = Some((backend, args, _) => backend.unload_theory(pass_arg(args, "name"))))

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
      handler_fn = Some((backend, args, _) =>
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
      handler_fn = Some((backend, _, _) => backend.list_sessions_info()))

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
      handler_fn = Some((backend, args, _) =>
        backend.list_theories_info(pass_arg(args, "session"))))

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
      handler_fn = Some((backend, args, _) => backend.search_sources(pass_arg(args, "pattern"))))

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
      handler_fn = Some((backend, args, _) => backend.doc_list(pass_arg(args, "pattern"))))

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
      handler_fn = Some((backend, args, _) =>
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
      handler_fn = Some((backend, args, _) => backend.scope_add(pass_args(args, "patterns"))))

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
      handler_fn = Some((backend, args, _) => backend.scope_remove(pass_args(args, "patterns"))))

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
      handler_fn = Some((backend, _, cancellation) =>
        backend.scope_show_cancellable(cancellation)))

  val builtins: List[Builtin_Tool] =
    List(repl_list_tool, repl_init_tool, repl_init_from_source_tool, repl_fork_tool, repl_remove_tool, repl_step_tool, repl_state_tool,
      repl_edit_tool, repl_replay_tool, repl_truncate_tool,
      repl_merge_tool, repl_timeout_tool, repl_pin_tool, repl_unpin_tool,
      repl_rebase_tool, sledgehammer_tool, find_theorems_tool, find_definition_tool,
      load_theory_tool, unload_theory_tool, check_theory_tool,
      list_sessions_tool, list_theories_tool, search_sources_tool,
      scope_add_tool, scope_remove_tool, scope_show_tool,
      doc_list_tool, doc_read_tool)

  /* tool_scope_show/set are per-connection Builtin_Tool values in
     IsabelleMcpApplication, so their names are listed here
     separately -- the one authoritative name list beyond `builtins`,
     kept in sync BY HAND with the two name = "..." literals below.
     Used for the exposure() reserved set (application tools/list)
     and as the drift-gate target (plans/builtin_activation, tested over
     the live bridge: mirror name set in MCP_Tools.thy == this list ++
     builtins.map(_.name), both directions). */
  val tool_scope_builtin_names: List[String] =
    List("tool_scope_show", "tool_scope_set")

  val all_builtin_names: List[String] = builtins.map(_.name) ++ tool_scope_builtin_names

  /* tool_scope_show/set (plans/context_locator, spec "context locators")
     live in the concrete per-connection application. Unlike
     every static builtin above, they close over that application's tool
     scope rather than backend/prover state. */
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
                  "tools" -> JSON.Object("listChanged" -> true),
                  "resources" -> JSON.Object("listChanged" -> true)),
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

        case Some("resources/list") =>
          execute(id, McpApplication.Operation.ResourcesList)

        case Some("resources/templates/list") =>
          execute(id, McpApplication.Operation.ResourceTemplatesList)

        case Some("resources/read") =>
          val params = JSON.value(json, "params").getOrElse(JSON.Object())
          JSON.string(params, "uri") match {
            case None => Some(RPC.error(id, RPC.INVALID_PARAMS, "Missing resource uri"))
            case Some(uri) =>
              execute(id, McpApplication.Operation.ResourcesRead(uri))
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

  private def validatedOutputPolicy(options: Options): McpOutputPolicy =
    McpOutputPolicy.checked(options.int("mcp_untrusted_output_bytes").toLong) match {
      case Right(policy) => policy
      case Left(message) => error("mcp_server: invalid output policy: " + message)
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
    val outputPolicy = validatedOutputPolicy(options)

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
        /* The production MCP application exposes IR operations. */
        MCP_Session.boot(options, session_name, session_dirs, theory,
          McpBridgeProfile.hol, progress)
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
      "Serving stdin; session " + quote(session_name) + " building in the background ...")
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
      serverInfo = ConnectionKernel.ServerInfo(server_name, server_version),
      outputPolicy = outputPolicy).serve()
  }
}
