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
end;

structure MCP_Profile_Time: MCP_PROFILE_TIME =
struct

(* One profiled command: its outer-syntax name, source line (best
   effort -- 0 if the transition carries no position), its measured
   timing, and an error message if this transition is the one that
   failed (see the module doc: at most the LAST entry carries one,
   since profiling stops there). *)
type entry = {name: string, line: int, timing: Timing.timing, error: string option};

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
            go trs (st', {name = name, line = line, timing = timing, error = NONE} :: entries)
        | Exn.Exn exn =>
            (st, rev ({name = name, line = line, timing = timing,
                       error = SOME (Runtime.exn_message exn)} :: entries))
      end;

fun sum_timing entries : Timing.timing =
  fold (fn {timing, ...} => fn {elapsed, cpu, gc} =>
    {elapsed = elapsed + #elapsed timing, cpu = cpu + #cpu timing, gc = gc + #gc timing})
    entries Timing.zero;

(* slowest elapsed time first *)
fun by_elapsed_desc (e1: entry, e2: entry) = Time.compare (#elapsed (#timing e2), #elapsed (#timing e1));

fun format_entry {name, line, timing, error} =
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

(* Build a fresh, throwaway proof state from an already-loaded theory,
   exactly the shape Ir.init's from_specs uses for a single spec
   (ir/ir.ML, `fun init`) -- Theory.begin_theory over the resolved
   parent, then Toplevel.make_state. Not a call into Ir: this state is
   never registered in Ir's repl_tab or anywhere else, so it is
   invisible to every repl_* tool and cannot be mutated by, or mutate,
   a live repl. *)
fun profile thy_name text =
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
    val (_, entries) = go transitions (st0, []);
    val stopped_early = (case entries of [] => false | _ => is_some (#error (List.last entries)));
  in format_report thy_name entries stopped_early end;

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

end
