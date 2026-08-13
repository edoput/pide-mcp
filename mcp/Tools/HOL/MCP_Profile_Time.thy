theory MCP_Profile_Time
  imports "MCP-HOL.MCP"
begin

section \<open>profile_proof_time: per-command wall/cpu/gc timing\<close>

text \<open>Experimental profiler (plans/proof_profiler_time, spec
D-2026-08-13-proof-profiler-time): given a loaded theory and a block
of Isar text, run the text against a FRESH, throwaway proof state
built from that theory and report each command's wall/cpu/GC time.

Deliberately independent of \<^verbatim>\<open>Ir\<close>/ir.ML: that file is a
verbatim-reused vendor drop (MIT header) shared with two other
in-flight worktrees, so it is out of scope to edit here (and this
theory does not import \<^verbatim>\<open>MCP-HOL.MCP_Repl\<close>, so \<^verbatim>\<open>Ir\<close> is not
even in scope). This structure only mirrors the SHAPE of
\<^verbatim>\<open>Ir.init\<close>'s \<open>from_specs\<close> (\<^file>\<open>../../../ir/ir.ML\<close> around
\<open>fun init\<close>) for building a fresh state from a theory, and of
\<open>exec_text\<close> for running Isar text against a state -- neither calls
into \<^verbatim>\<open>Ir\<close>, and neither touches \<^verbatim>\<open>Ir\<close>'s process-global
\<open>repl_tab\<close>, so nothing here is visible to, or can corrupt, any live
REPL a client is mid-proof in.\<close>

ML \<open>
signature MCP_PROFILE_TIME =
sig
  val scratch_id: string
  val profile: string -> string -> string
  val profile_grouped: string -> string -> string
end;

structure MCP_Profile_Time: MCP_PROFILE_TIME =
struct

(* One profiled command: its outer-syntax name, source line (best
   effort -- 0 if the transition carries no position), its measured
   timing, the proof-nesting LEVEL right after it ran (0 = back at
   theory level; unchanged from before the transition if it failed,
   since a failed transition never advances the state -- see `go`),
   and an error message if this transition is the one that failed (see
   the module doc: at most the LAST entry carries one, since profiling
   stops there). `level` is what `profile_grouped` below groups on;
   plain `profile` ignores it entirely, so this is purely additive. *)
type entry = {name: string, line: int, timing: Timing.timing, level: int, error: string option};

(* A fixed, distinctive id for the scratch theory this function
   builds -- distinctive so a test can confirm nothing under this name
   ever reaches Ir's repl_tab (it can't: this code never calls Ir),
   and so a stray failure message naming the "theory" is legible
   rather than some meaningless gensym. *)
val scratch_id = "MCP_Profile_Time.scratch";

(* Timing.timing itself does not catch exceptions (Pure/General/
   timing.ML: `f x` runs unguarded before `result start`), so a
   transition that fails would otherwise lose its own timing entirely.
   Wrapping the call in Exn.result first -- the same idiom
   Timing.command_timing itself uses -- keeps the timing regardless of
   outcome; the caller decides what to do with the Exn.result. *)
fun timed_transition tr st = Timing.timing (Exn.result (Toplevel.command_exception false tr)) st;

fun line_of tr = the_default 0 (Position.line_of (Toplevel.pos_of tr));

(* Fold one transition at a time, stopping at the first failure.
   PARTIAL RESULTS policy (spec decision): the failing transition's
   own entry (with its measured timing and error message) is kept;
   nothing after it is attempted, because the state never advanced
   past the failure, so running further transitions against it would
   misattribute their timing to code that never executed against the
   proof state the text intended. *)
fun go [] (st, entries) = (st, rev entries)
  | go (tr :: trs) (st, entries) =
      let
        val name = Toplevel.name_of tr;
        val line = line_of tr;
        val (timing, result) = timed_transition tr st;
      in
        case result of
          Exn.Res st' =>
            go trs (st', {name = name, line = line, timing = timing,
                          level = Toplevel.level st', error = NONE} :: entries)
        | Exn.Exn exn =>
            (* Runtime.exn_message may embed raw YXML markup (e.g. a
               Position.here suffix on a name-resolution error) rather
               than plain text -- strip it the same way every other
               ML-unit test in this tree does (MCP_Repl_Tests.thy's
               and Ir_Tests.thy's own "val plain = XML.content_of o
               YXML.parse_body"), so a report line reads as prose
               ("... (line 52)") and not a raw property dump
               ("...positionline=52offset=...no_report"). Level is
               taken from the UNCHANGED `st` (a failed transition never
               advances the state), not some notion of "level after
               failing" that doesn't exist. *)
            (st, rev ({name = name, line = line, timing = timing,
                       level = Toplevel.level st,
                       error = SOME (XML.content_of (YXML.parse_body (Runtime.exn_message exn)))}
                :: entries))
      end;

fun sum_timing entries : Timing.timing =
  fold (fn {timing, ...} => fn {elapsed, cpu, gc} =>
    {elapsed = elapsed + #elapsed timing, cpu = cpu + #cpu timing, gc = gc + #gc timing})
    entries Timing.zero;

(* slowest elapsed time first *)
fun by_elapsed_desc (e1: entry, e2: entry) = Time.compare (#elapsed (#timing e2), #elapsed (#timing e1));

fun format_entry {name, line, timing, error, ...} =
  "  line " ^ string_of_int line ^ "  " ^ name ^ "  -- " ^ Timing.message timing ^
  (case error of NONE => "" | SOME msg => "\n    ERROR: " ^ msg);

fun format_report thy_name entries stopped_early =
  let
    val n = length entries;
    val total = sum_timing entries;
    val header =
      "profiled " ^ string_of_int n ^ " command(s) against " ^ quote thy_name ^
      ", total " ^ Timing.message total ^
      (if stopped_early
       then " (stopped after an error" ^
            (case entries of
              [] => ""
            | _ => " at line " ^ string_of_int (#line (List.last entries)) ^ ")")
       else "");
    val sorted = sort by_elapsed_desc entries;
  in header ^ "\n\n" ^ String.concatWith "\n" (map format_entry sorted) end;

(* Group a DOCUMENT-ORDER entry list into lexical blocks: a new group
   opens at the entry right after the previous one closed (or at the
   very first entry), and the CURRENT group closes on the entry whose
   `level` is back to 0 -- Toplevel.level is 0 at theory level and >0
   anywhere inside a goal/proof, so "a run of entries bracketed by two
   level-0 points" is exactly one `lemma ... qed`/`theorem ... by ...`
   block (or, for a command that never leaves level 0 at all, e.g. a
   bare `definition`, a singleton group of that one command). No
   keyword whitelist (no hardcoded "lemma"/"theorem"/...): this works
   for every goal-opening command uniformly, including ones this file
   was never told about (interpretation, lift_definition with a
   side proof obligation, ...), because it reads the ACTUAL proof
   nesting depth Isabelle itself tracks rather than guessing from a
   command name.
   If the run stopped on an error mid-proof, the trailing partial
   group (never reaching level 0) is still flushed, not dropped --
   `go`'s base case below handles that. *)
fun group_entries (entries: entry list) : entry list list =
  let
    fun go ([], cur, acc) = rev (if null cur then acc else rev cur :: acc)
      | go (e :: es, cur, acc) =
          let val cur' = e :: cur
          in if #level e = 0 then go (es, [], rev cur' :: acc) else go (es, cur', acc) end;
  in go (entries, [], []) end;

fun group_total (group: entry list) : Timing.timing = sum_timing group;

(* label a group by its FIRST entry (the one that opened it) -- for a
   `lemma foo ... qed` block this is the `lemma` transition itself, so
   the label reads like the source: "line 6  lemma". *)
fun group_label (group: entry list) =
  case group of
    (e :: _) => "line " ^ string_of_int (#line e) ^ "  " ^ #name e
  | [] => "(empty group)";

fun by_group_elapsed_desc (g1: entry list, g2: entry list) =
  Time.compare (#elapsed (group_total g2), #elapsed (group_total g1));

fun indent s = String.concatWith "\n" (map (fn l => "    " ^ l) (space_explode "\n" s));

fun format_group group =
  let
    val total = group_total group;
    val sorted = sort by_elapsed_desc group;
  in
    group_label group ^ "  -- total " ^ Timing.message total ^ ", " ^
    string_of_int (length group) ^ " command(s)\n" ^
    String.concatWith "\n" (map (indent o format_entry) sorted)
  end;

(* Groups by TOTAL elapsed time, slowest lexical block first -- this
   is the actual answer to "which lexical part of the proof script
   takes the most time", one level up from the flat per-command view
   `format_report` gives. Detail within a group is still sorted
   slowest-command-first, so both questions ("which block" and "which
   step inside it") are answered by one report. *)
fun format_grouped_report thy_name entries stopped_early =
  let
    val groups = group_entries entries;
    val sorted_groups = sort by_group_elapsed_desc groups;
    val header =
      "profiled " ^ string_of_int (length entries) ^ " command(s) in " ^
      string_of_int (length groups) ^ " lexical group(s) against " ^ quote thy_name ^
      (if stopped_early then " (stopped after an error)" else "");
  in header ^ "\n\n" ^ String.concatWith "\n\n" (map format_group sorted_groups) end;

(* Build a fresh, throwaway proof state from an already-loaded theory,
   exactly the shape Ir.init's from_specs uses for a single spec
   (ir/ir.ML, `fun init`) -- Theory.begin_theory over the resolved
   parent, then Toplevel.make_state. Not a call into Ir: this state is
   never registered in Ir's repl_tab or anywhere else, so it is
   invisible to every repl_* tool and cannot be mutated by, or mutate,
   a live repl. *)
(* Print_Mode.with_modes [] (plain text, no PIDE/YXML markup): collect
   is called (via profile/profile_grouped) both directly and through
   the mcp_tool capture wrapper (which separately forces the same
   plain mode for ITS OWN output). Forcing it here too, around the
   error-message extraction specifically, keeps a failing transition's
   Runtime.exn_message plain regardless of which caller reaches this
   function -- found empirically: called with no override active, a
   position-carrying error's message came back with raw YXML report
   markup spliced in ("...positionline=52offset=...no_report"), not
   the clean parenthesised "(line 52)" a caller would expect. *)
fun collect thy_name text =
  let
    val thy = Thy_Info.get_theory thy_name;
    val scratch_thy = Theory.begin_theory (scratch_id, Position.none) [thy];
    val st0 = Toplevel.make_state (SOME scratch_thy);
    (* Position.start (line 1), not Position.none: the latter carries no
       line at all, so every transition's Toplevel.pos_of would report
       line 0 regardless of where it actually sits in "text". *)
    val transitions =
      Outer_Syntax.parse_text scratch_thy (fn () => scratch_thy) Position.start text
      (* the parser appends a synthetic trailing "<ignored>" transition
         for the span after the last real command; Toplevel.is_ignored
         filters it (and any other ignored/comment-only span) out, so
         only genuine profiled commands reach the fold and the count. *)
      |> filter_out Toplevel.is_ignored;
    val (_, entries) =
      Print_Mode.with_modes [] (fn () => go transitions (st0, [])) ();
    val stopped_early = (case entries of [] => false | _ => is_some (#error (List.last entries)));
  in (entries, stopped_early) end;

fun profile thy_name text =
  let val (entries, stopped_early) = collect thy_name text
  in format_report thy_name entries stopped_early end;

fun profile_grouped thy_name text =
  let val (entries, stopped_early) = collect thy_name text
  in format_grouped_report thy_name entries stopped_early end;

end;
\<close>

text \<open>The param names \<open>theory\<close> and \<open>text\<close> are both quoted below: both
happen to be Isabelle outer-syntax COMMAND keywords (\<^verbatim>\<open>theory\<close>
starts every file's header; \<^verbatim>\<open>text\<close> is the documentation command
used throughout this very file), and command keywords cut span
scanning BEFORE the params-clause parser ever runs on a span's tokens
-- the same class of collision \<^verbatim>\<open>MCP_Tools.thy\<close>'s own
\<open>ptyp_parser\<close> comment documents for the \<open>term\<close>/\<open>typ\<close> PARAMETER
TYPE names. A bare (unquoted) \<open>theory\<close> here truncates the surrounding
\<^verbatim>\<open>mcp_tool\<close> command mid-span, producing a baffling downstream
"name expected, but end-of-input was found" -- found by hitting
exactly that build failure once. A quoted string token is opaque to
span scanning, so quoting shields it; the registered param key is
still the literal \<open>"theory"\<close>/\<open>"text"\<close> content, matching the
\<^ML>\<open>MCP_Combinators.arg\<close> lookups in the capture body above
verbatim.\<close>

mcp_tool "profile_proof_time" = capture \<open>fn _ => fn args =>
  writeln (MCP_Profile_Time.profile
    (MCP_Combinators.arg args "theory")
    (MCP_Combinators.arg args "text"))\<close>
  (description \<open>Run Isar text against a FRESH, throwaway proof state
    built from the named (already-loaded) theory, and report
    per-command wall/cpu/GC time, slowest first. Use this to find
    which apply/sledgehammer/induction step in a proof is slow. This
    does NOT touch any live REPL -- it builds its own state and
    discards it. If a command in the text errors, the report includes
    every command that ran before it (with the failing one marked)
    and stops there; commands after the error never ran and are not
    in the report.\<close>)
  (params
    "theory" :: string \<open>the loaded theory to build the fresh proof state from, e.g. HOL.Main\<close>
    "text" :: source \<open>Isar proof text to run and profile\<close>)
  (annotations read_only)

text \<open>Hierarchical variant (spec D-2026-08-13-proof-profiler-time-hierarchical):
same collection, grouped into lexical blocks instead of one flat
list -- "which lemma/theorem block is slow" before "which step inside
it is slow". A block is exactly the run of transitions between two
points where \<^ML>\<open>Toplevel.level\<close> reads 0 (back at theory level); this
needs no hardcoded list of goal-opening command names (lemma, theorem,
...) because it reads Isabelle's own proof-nesting depth rather than
guessing from a keyword -- see \<^verbatim>\<open>group_entries\<close>'s own comment in
the ML block above. Groups are sorted by their own total elapsed time, slowest
block first; each block's own commands are still sorted
slowest-first inside it, so one report answers both "which block" and
"which step".\<close>

mcp_tool "profile_proof_time_grouped" = capture \<open>fn _ => fn args =>
  writeln (MCP_Profile_Time.profile_grouped
    (MCP_Combinators.arg args "theory")
    (MCP_Combinators.arg args "text"))\<close>
  (description \<open>Like profile_proof_time, but groups commands into
    lexical blocks (one `lemma`/`theorem`/... statement through its
    closing `qed`/`by`/`done`, or a singleton block for a bare
    theory-level command) instead of one flat per-command list.
    Blocks are sorted by their own total time, slowest first, so this
    answers "which PART of the script is slow" directly, with the
    per-command detail for that part nested underneath. Same
    fresh-throwaway-state and partial-results-on-error behavior as
    profile_proof_time.\<close>)
  (params
    "theory" :: string \<open>the loaded theory to build the fresh proof state from, e.g. HOL.Main\<close>
    "text" :: source \<open>Isar proof text to run and profile\<close>)
  (annotations read_only)

end
