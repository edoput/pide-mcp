theory MCP_Profile_Delta
  imports "MCP-HOL.MCP"
begin

section \<open>Per-command proof-state-size profiler\<close>

text \<open>plans/proof_profiler_delta, spec D-2026-08-13-proof-profiler-delta:
one of three independent, parallel profiler experiments (time / memory
/ proof-size-delta); this theory covers proof-size-delta only.

Reports, per Isar command in a batch of text, how the GOAL STATE's
SIZE changed: subgoal count (\<^ML>\<open>Thm.nprems_of\<close>) and term size
(\<^ML>\<open>size_of_term\<close>, \<^file>\<open>~~/src/Pure/term.ML\<close>) of the current goal,
before and after the command runs. This surfaces a step that made the
proof WORSE -- more subgoals, a bigger term -- even though it produced
no error, so an agent can see the difference between "the tactic
succeeded" and "the tactic made progress".

Deliberately independent of \<open>Ir\<close> / \<open>MCP_Repl\<close>'s \<open>repl_tab\<close>: this tool
builds its OWN private, disposable \<^ML_type>\<open>Toplevel.state\<close>, the same
construction \<open>Ir.init\<close>'s \<open>from_specs\<close> uses (\<open>ir/ir.ML\<close>, around
\<open>Theory.begin_theory\<close> + \<open>Toplevel.make_state\<close>) -- written fresh here,
since \<open>ir.ML\<close> itself is verbatim-reused prior art (MIT header) and is
not touched. It never creates, claims, or releases any entry in
\<open>Ir\<close>'s repl table: read-only and exploratory, like
\<open>MCP_Repl.find_theorems_theory\<close>'s theory-context path.\<close>

ML \<open>
signature MCP_PROFILE_DELTA =
sig
  (*subgoal count, term size of the CURRENT goal -- NONE when the
    toplevel state is not mid-proof (theory-level), never (0, 0)*)
  type measurement = (int * int) option
  type entry = {name: string, pos: Position.T, before: measurement, after: measurement}

  val measure: Toplevel.state -> measurement
  val build_state: string -> Toplevel.state
  val profile_entries: string -> string -> entry list
  val delta_of: entry -> (int * int) option
  val format_report: entry list -> string
  val profile: string -> string -> string
end;

structure MCP_Profile_Delta: MCP_PROFILE_DELTA =
struct

type measurement = (int * int) option;
type entry = {name: string, pos: Position.T, before: measurement, after: measurement};

(*mid-proof iff Toplevel.is_proof; Proof.goal wrapped in try so an
  unusual proof-state shape degrades this ONE measurement to N/A rather
  than aborting the whole profiling run*)
fun measure st =
  if Toplevel.is_proof st then
    (case try Proof.goal (Toplevel.proof_of st) of
       SOME {goal, ...} => SOME (Thm.nprems_of goal, size_of_term (Thm.prop_of goal))
     | NONE => NONE)
  else NONE;

(*fresh, disposable toplevel state rooted in one heap-resident theory --
  mirrors Ir.init's from_specs (ir/ir.ML), new code, ir.ML untouched*)
fun build_state thy_name =
  let
    val thy = Thy_Info.get_theory thy_name
    val thy' = Theory.begin_theory ("MCP_Profile_Delta." ^ thy_name, Position.none) [thy]
  in Toplevel.make_state (SOME thy') end;

(*WHOLE-CALL error, matching ir.ML's own exec_text: a raised exception
  in Toplevel.command_exception propagates straight out of the fold --
  no catch here, no partial entry list reaches the caller. Mirrors
  repl_step's documented failure atomicity rather than inventing a
  second convention (spec entry, "error handling").*)
fun profile_entries thy_name text =
  let
    val st0 = build_state thy_name
    val base_thy = Toplevel.theory_of st0
    (*Outer_Syntax.parse_text inserts an <ignored> transition for every
      whitespace/comment gap between real commands (empirically: one
      per line break, not just leading/trailing) -- these are no-ops
      under Toplevel.command_exception (same as ir.ML's own exec_text,
      which folds over them too) but would otherwise double the report
      with spurious rows, so they are dropped here: "one line per
      command" means one line per REAL command.*)
    val transitions =
      filter (not o Toplevel.is_ignored)
        (Outer_Syntax.parse_text base_thy (fn () => base_thy) Position.none text)
    fun step (tr, (st, entries)) =
      let
        val before = measure st
        val st' = Toplevel.command_exception false tr st
        val after = measure st'
      in (st', {name = Toplevel.name_of tr, pos = Toplevel.pos_of tr,
                before = before, after = after} :: entries)
      end
    val (_, rev_entries) = List.foldl step (st0, []) transitions
  in rev rev_entries end;

(*a real delta exists only when BOTH sides are mid-proof -- a command
  that OPENS (lemma) or CLOSES (qed/done) a proof crosses the
  theory/proof boundary and reports N/A for its own delta, by design:
  the two states either side are not comparable goal states*)
fun delta_of ({before = SOME (n0, s0), after = SOME (n1, s1), ...}: entry) =
      SOME (n1 - n0, s1 - s0)
  | delta_of _ = NONE;

fun line_of pos = the_default 0 (Position.line_of pos);

fun fmt_signed n = if n >= 0 then "+" ^ string_of_int n else string_of_int n;

fun format_entry (e as {name, pos, ...}: entry) =
  let
    val (nd, sd) =
      case delta_of e of
        SOME (n, s) => (fmt_signed n, fmt_signed s)
      | NONE => ("N/A", "N/A")
  in
    "  " ^ name ^ "  line " ^ string_of_int (line_of pos) ^
    "  subgoals " ^ nd ^ "  size " ^ sd
  end;

(*group 0 (real delta) before group 1 (N/A); within group 0, largest
  growth first -- nprems_delta descending, ties by size_delta
  descending; within group 1, \<^ML>\<open>sort\<close> (a STABLE mergesort,
  Pure/library.ML) preserves source order since every N/A key is equal*)
fun order_key e =
  case delta_of e of
    SOME (n, s) => (0, (~n, ~s))
  | NONE => (1, (0, 0));

fun format_report entries =
  let
    val sorted =
      sort (fn (e1, e2) => prod_ord int_ord (prod_ord int_ord int_ord)
              (order_key e1, order_key e2))
        entries
    val total = length entries
    val with_delta = length (filter (is_some o delta_of) entries)
    val grown =
      length (filter (fn e =>
        case delta_of e of SOME (n, s) => n > 0 orelse s > 0 | NONE => false) entries)
    val na = total - with_delta
    val header =
      "profile_proof_delta: " ^ string_of_int total ^ " command(s), " ^
      string_of_int with_delta ^ " with a measurable delta, " ^
      string_of_int grown ^ " growing, " ^ string_of_int na ^ " N/A (theory-level or a\
      \ proof-boundary command)"
  in cat_lines (header :: map format_entry sorted) end;

fun profile thy_name text = format_report (profile_entries thy_name text);

end;
\<close>

text \<open>NOTE: the second parameter is named \<open>isar_text\<close>, not \<open>text\<close> as the
original brief's input contract said -- bare \<open>text\<close> is itself a
registered Isabelle/Isar COMMAND keyword, so the outer-syntax scanner
splits the \<open>mcp_tool\<close> command's own span right there (the same class
of clash \<open>mcp/Tools/MCP_Tools.thy\<close> already documents for
\<open>term\<close>/\<open>typ\<close> as ptyp names). Discovered empirically: \<open>text\<close> failed to
parse as a param name with "name expected, but end-of-input was
found". \<open>isar_text\<close> also matches \<open>repl_step\<close>'s own parameter name for
the same kind of argument. The SAME clash applies to the first
parameter -- bare \<open>theory\<close> is THE Isar command keyword (\<open>theory ...
imports ... begin\<close>), so it is named \<open>theory_name\<close> instead.\<close>

mcp_tool profile_proof_delta = run \<open>fn _ => fn args =>
  MCP_Profile_Delta.profile (MCP_Combinators.arg args "theory_name") (MCP_Combinators.arg args "isar_text")\<close>
  (description \<open>Profile a batch of Isar text against a fresh, disposable
    state rooted in the given theory: report, per command, how the goal
    state's SIZE changed (subgoal count and term size), sorted with the
    largest-growing steps first. Use this to spot a step that made a
    proof WORSE -- more subgoals, a bigger term -- even though it
    produced no error, e.g. an induct without the right generalization
    or an unfold that inflates a term. Non-proof (theory-level) commands
    and any command that opens or closes a proof (lemma, qed) report
    N/A, not 0 -- there is no comparable goal state either side of
    those. Read-only: does not touch any repl. WHOLE-CALL failure: if
    any command in text fails, the call errors with no partial report,
    matching repl_step's own failure-atomicity convention -- profile
    text that already replays cleanly, e.g. via repl_step/repl_replay.\<close>)
  (annotations read_only)
  (params
    theory_name :: string \<open>heap-resident theory name to root a fresh state
      in (e.g. "HOL.Main"), resolved via Thy_Info.get_theory\<close>
    isar_text :: source \<open>Isar source: one or more commands/lemmas to profile\<close>)

end
