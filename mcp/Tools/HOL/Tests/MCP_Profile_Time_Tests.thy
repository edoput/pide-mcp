theory MCP_Profile_Time_Tests
  imports "MCP-HOL.MCP_Profile_Time" "MCP-HOL.MCP_Repl"
begin

text \<open>Unit tests for \<^ML_structure>\<open>MCP_Profile_Time\<close> (plans/proof_profiler_time,
spec D-2026-08-13-proof-profiler-time), at the ml layer. This theory
fails to load iff a test fails, so
\<^verbatim>\<open>isabelle build -d mcp/Tools MCP-HOL-Tests\<close> is the test runner.

Imports \<^ML_structure>\<open>MCP_Repl\<close> (not just \<^ML_structure>\<open>MCP_Profile_Time\<close>)
purely so \<^ML_structure>\<open>Ir\<close> -- compiled inside MCP_Repl.thy's
\<^verbatim>\<open>ML_file "ir.ML"\<close> -- is in scope for T5's read-only check.
\<^ML_structure>\<open>MCP_Profile_Time\<close> itself never calls into
\<^ML_structure>\<open>Ir\<close> (the whole point of building its own scratch state
rather than reusing ir.ML's), so, unlike Ir_Tests.thy, this theory
needs no import-order trick to avoid the process-global repl_tab
concurrency hazard documented there: T5 only checks ABSENCE of a
distinctive id string, which is safe under unrelated concurrent repl
churn from other suites in this session.\<close>

ML \<open>
(*image theories are registered under their long name, e.g. "HOL.Main"*)
val main =
  the_default "Main"
    (find_first (fn n => n = "Main" orelse String.isSuffix ".Main" n) (Thy_Info.get_names ()));

fun index_of pat s =
  let
    val n = size pat;
    fun go i = if i + n > size s then NONE
               else if String.substring (s, i, n) = pat then SOME i
               else go (i + 1);
  in go 0 end;

(*typed errors name the argument -- same idiom as MCP_Tools_Tests.thy's err_mentions*)
fun err_mentions f sub =
  (case Exn.capture_body f of
    Exn.Exn exn => String.isSubstring sub (Runtime.exn_message exn)
  | Exn.Res _ => false);
\<close>

section \<open>T1: command count and total-elapsed summary\<close>

ML \<open>
val text1 = "lemma \"True\" by simp";
val out1 = MCP_Profile_Time.profile main text1;
(*"lemma" and "by simp" are two separate Toplevel transitions*)
\<^assert> (String.isSubstring "profiled 2 command(s)" out1);
\<^assert> (String.isSubstring "line 1  lemma" out1);
\<^assert> (String.isSubstring "line 1  by" out1);
\<close>

section \<open>T2: sorted slowest-elapsed-first\<close>

text \<open>\<open>slow_fib\<close> is naive exponential-time recursion (no memoization);
evaluating \<open>slow_fib 30\<close> via \<open>eval\<close> (code generation + compiled
evaluation) makes roughly 2.7M recursive calls -- reliably measurable
against a trivial \<open>True\<close> proof, which is not. The trivial command is
placed FIRST in the input text and the expensive one LAST, so this
test actually exercises re-sorting, not merely echoing input order.\<close>

ML \<open>
val text2 =
  "fun slow_fib :: \"nat \<Rightarrow> nat\" where\n" ^
  "  \"slow_fib 0 = 0\"\n" ^
  "| \"slow_fib (Suc 0) = 1\"\n" ^
  "| \"slow_fib (Suc (Suc n)) = slow_fib n + slow_fib (Suc n)\"\n" ^
  "\n" ^
  "lemma trivial_first: \"True\" by simp\n" ^
  "\n" ^
  "lemma slow_fib_30: \"slow_fib 30 = 832040\" by eval";
val out2 = MCP_Profile_Time.profile main text2;
\<^assert> (not (String.isSubstring "ERROR" out2));
(*the slow_fib_30 lemma/by pair sits at line 8; trivial_first's at line 6*)
val i8 = the (index_of "line 8" out2);
val i6 = the (index_of "line 6" out2);
\<^assert> (i8 < i6);
\<close>

section \<open>T3: partial results on error, per the spec's documented policy\<close>

ML \<open>
val text3 =
  "lemma before_ok: \"True\" by simp\n" ^
  "\n" ^
  "lemma mid_open: \"True\"\n" ^
  "garbage_tactic\n" ^
  "\n" ^
  "lemma after_never: \"True\" by simp";
val out3 = MCP_Profile_Time.profile main text3;
(*4 transitions ran: lemma before_ok, by simp, lemma mid_open, garbage_tactic (failed)*)
\<^assert> (String.isSubstring "profiled 4 command(s)" out3);
\<^assert> (String.isSubstring "stopped after an error" out3);
\<^assert> (String.isSubstring "ERROR" out3);
(*the third lemma never ran and is nowhere in the report*)
\<^assert> (not (String.isSubstring "after_never" out3));
\<close>

section \<open>T4: mcp_tool registry wiring\<close>

ML \<open>
val out4 =
  MCP_Tool.run \<^context> "MCP_Profile_Time.profile_proof_time"
    [("theory", main), ("text", "lemma \"True\" by simp")];
\<^assert> (String.isSubstring "profiled 2 command(s)" out4);

(*closed arity: a spurious key is a typed error naming the key, not an uncaught exception*)
\<^assert> (err_mentions
  (fn () => MCP_Tool.run \<^context> "MCP_Profile_Time.profile_proof_time"
    [("theory", main), ("text", "lemma \"True\" by simp"), ("spurious", "x")])
  "spurious");
\<close>

section \<open>T5: no live REPL is touched\<close>

text \<open>\<^ML_structure>\<open>MCP_Profile_Time\<close> never calls into
\<^ML_structure>\<open>Ir\<close>, so no repl_tab entry can appear under any name this
module uses internally. Checked here rather than assumed: the fixed
scratch id \<^ML>\<open>MCP_Profile_Time.scratch_id\<close> is asserted absent from
\<^ML>\<open>Ir.repls\<close>'s listing both before and after a profiling call --
absence rather than before/after equality, so this is not sensitive to
unrelated concurrent repl churn from other test suites in this
session (see Ir_Tests.thy's note on the shared global table).\<close>

ML \<open>
val plain = XML.content_of o YXML.parse_body;

(*mirrors Ir_Tests.thy's own run_ir: Ir.repls is unit -> unit, reporting
  through writeln; MCP_Output.captured buffers that output.*)
fun run_ir (f: unit -> unit) : string =
  let val (result, output) = MCP_Output.captured f
  in case result of
       Exn.Res () => plain output
     | Exn.Exn exn => if Exn.is_interrupt exn then Exn.reraise exn else plain output
  end;

val listing_before = run_ir Ir.repls;
\<^assert> (not (String.isSubstring MCP_Profile_Time.scratch_id listing_before));

val _ = MCP_Profile_Time.profile main "lemma \"True\" by simp";

val listing_after = run_ir Ir.repls;
\<^assert> (not (String.isSubstring MCP_Profile_Time.scratch_id listing_after));
\<close>

section \<open>T6: profile_grouped groups by lexical block, slowest block first\<close>

text \<open>Two `lemma ... qed`/`lemma ... by` blocks, one containing the
same expensive \<open>slow_fib\<close> call T2 uses, the other trivial. The report
must show exactly 2 groups (one per lemma), and the group containing
\<open>slow_fib_30\<close> (identifiable by its line, since the group label is
"line N  lemma") must appear before the trivial group's line.\<close>

ML \<open>
val text6 =
  "fun slow_fib :: \"nat \<Rightarrow> nat\" where\n" ^
  "  \"slow_fib 0 = 0\"\n" ^
  "| \"slow_fib (Suc 0) = 1\"\n" ^
  "| \"slow_fib (Suc (Suc n)) = slow_fib n + slow_fib (Suc n)\"\n" ^
  "\n" ^
  "lemma trivial_first: \"True\" by simp\n" ^
  "\n" ^
  "lemma slow_fib_30: \"slow_fib 30 = 832040\" by eval";
val out6 = MCP_Profile_Time.profile_grouped main text6;
(*the fun definition (level 0 the whole time) is its own singleton
  group, plus one group per lemma -- 3 groups total*)
\<^assert> (String.isSubstring "3 lexical group(s)" out6);
val gi8 = the (index_of "line 8" out6);
val gi6 = the (index_of "line 6" out6);
\<^assert> (gi8 < gi6);
\<close>

section \<open>T7: profile_proof_time_grouped tool wiring\<close>

ML \<open>
val out7 =
  MCP_Tool.run \<^context> "MCP_Profile_Time.profile_proof_time_grouped"
    [("theory", main), ("text", "lemma \"True\" by simp")];
\<^assert> (String.isSubstring "1 lexical group(s)" out7);
\<^assert> (String.isSubstring "line 1  lemma" out7);
\<close>

end
