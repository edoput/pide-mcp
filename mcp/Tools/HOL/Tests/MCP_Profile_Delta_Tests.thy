theory MCP_Profile_Delta_Tests
  imports "MCP-HOL.MCP_Profile_Delta" "MCP-HOL-Tests.MCP_Repl_Tests"
begin

text \<open>Unit tests for \<^ML_structure>\<open>MCP_Profile_Delta\<close>
(plans/proof_profiler_delta, ASSUMPTIONS A1..A6).

Imports \<^theory>\<open>MCP-HOL-Tests.MCP_Repl_Tests\<close> purely to force build ORDER
against \<^ML_structure>\<open>Ir\<close>'s process-global repl table (same reasoning as
Ir_Tests.thy's own comment on this): A6 below reads \<^ML_structure>\<open>Ir\<close>'s
repl listing before/after a profiling call, so it must not race a
concurrent suite touching the same global table.

This theory fails to load iff an assertion fails, so
\<^verbatim>\<open>isabelle build -d mcp/Tools MCP-HOL-Tests\<close> is the test runner.\<close>

ML \<open>
(*image theories are registered under their long name, e.g. "HOL.Main" --
  same idiom MCP_Repl_Tests.thy uses*)
val main =
  the_default "Main"
    (find_first (fn n => n = "Main" orelse String.isSuffix ".Main" n) (Thy_Info.get_names ()));

(*plain string scan, same low-level idiom as MCP_Repl_Tests.thy's
  count_substring -- finds the first occurrence of pat in s*)
fun index_of pat s =
  let
    val n = size pat
    fun go i =
      if i + n > size s then NONE
      else if String.substring (s, i, n) = pat then SOME i
      else go (i + 1)
  in go 0 end;

fun err_mentions f sub =
  (case Exn.capture_body f of
    Exn.Exn exn => String.isSubstring sub (Runtime.exn_message exn)
  | Exn.Res _ => false);
\<close>

section \<open>Fixture: a definition (non-proof), an induct that GROWS the goal,
an auto that SHRINKS it back to 0 subgoals, and a done that closes the
proof entirely\<close>

ML \<open>
val text1 =
  "definition pd_flag :: bool where \"pd_flag = True\"\n\n" ^
  "lemma pd_add_zero: \"xs @ [] = (xs::'a list)\"\n" ^
  "apply (induct xs)\n" ^
  "apply auto\n" ^
  "done\n";

val entries1 = MCP_Profile_Delta.profile_entries main text1;
\<^assert> (length entries1 = 5);

val e_def    = nth entries1 0;
val e_lemma  = nth entries1 1;
val e_induct = nth entries1 2;
val e_auto   = nth entries1 3;
val e_done   = nth entries1 4;

\<^assert> (#name e_def = "definition");
\<^assert> (#name e_lemma = "lemma");
\<^assert> (#name e_induct = "apply");
\<^assert> (#name e_auto = "apply");
\<^assert> (#name e_done = "done");
\<close>

section \<open>A3: non-proof (theory-level) commands read N/A, never 0\<close>

ML \<open>
\<^assert> (is_none (#before e_def) andalso is_none (#after e_def));
\<^assert> (is_none (MCP_Profile_Delta.delta_of e_def));
\<close>

section \<open>N/A at proof BOUNDARIES: opening (lemma) and closing (done)\<close>

ML \<open>
(*lemma's BEFORE is theory-level, AFTER is mid-proof -- not comparable*)
\<^assert> (is_none (#before e_lemma));
\<^assert> (is_some (#after e_lemma));
\<^assert> (is_none (MCP_Profile_Delta.delta_of e_lemma));

(*done's BEFORE is mid-proof with 0 subgoals, AFTER is theory-level --
  still N/A, not a synthetic "shrink to 0" reading, because the AFTER
  state has left proof mode entirely and has no goal to measure*)
\<^assert> (is_some (#before e_done));
\<^assert> (is_none (#after e_done));
\<^assert> (is_none (MCP_Profile_Delta.delta_of e_done));
\<close>

section \<open>A1: growth is visible -- induct turns 1 subgoal into more\<close>

ML \<open>
val delta_induct =
  (case MCP_Profile_Delta.delta_of e_induct of
    SOME d => d
  | NONE => error "expected a real delta for the induct step (both sides mid-proof)");
\<^assert> (fst delta_induct > 0);
(*sanity: list induction on this goal yields exactly 2 subgoals from 1*)
\<^assert> (fst delta_induct = 1);
\<close>

section \<open>A2: a step that shrinks WHILE STILL mid-proof reads a real
NEGATIVE delta, not N/A -- only the theory/proof BOUNDARY (done, above)
reads N/A\<close>

ML \<open>
val delta_auto =
  (case MCP_Profile_Delta.delta_of e_auto of
    SOME d => d
  | NONE => error "expected a real delta for the auto step (both sides mid-proof)");
\<^assert> (fst delta_auto < 0);
\<close>

section \<open>A5: report sorting -- largest growth first, N/A steps last, in
source order\<close>

ML \<open>
val report1 = MCP_Profile_Delta.profile main text1;

(*the growth step's own subgoal delta is +1 (A1 above) and the shrink
step's is negative -- both are literal, distinctive substrings of the
report line ("subgoals +1" / "subgoals <negative>"), so their relative
position in the formatted text is a direct check of the sort contract
without depending on line numbers or padding*)
(*must match MCP_Profile_Delta.fmt_signed's own rendering (a real "-",
  not SML's "~") since this is used to build search markers against
  the tool's actual formatted output below*)
fun signed n = if n >= 0 then "+" ^ string_of_int n else "-" ^ string_of_int (~n);

val growth_marker = "subgoals " ^ signed (fst delta_induct);
val shrink_marker = "subgoals " ^ signed (fst delta_auto);
val na_marker = "subgoals N/A";

val idx_growth =
  (case index_of growth_marker report1 of SOME i => i | NONE => error "growth marker missing");
val idx_shrink =
  (case index_of shrink_marker report1 of SOME i => i | NONE => error "shrink marker missing");
val idx_na =
  (case index_of na_marker report1 of SOME i => i | NONE => error "N/A marker missing");

\<^assert> (idx_growth < idx_shrink);
\<^assert> (idx_shrink < idx_na);
\<close>

section \<open>the summary line reports the right counts for this fixture:
5 commands, 2 with a measurable delta (induct, auto), 1 growing
(induct), 3 N/A (definition, lemma-open, done)\<close>

ML \<open>
\<^assert> (String.isSubstring "5 command(s)" report1);
\<^assert> (String.isSubstring "2 with a measurable delta" report1);
\<^assert> (String.isSubstring "1 growing" report1);
\<^assert> (String.isSubstring "3 N/A" report1);
\<close>

section \<open>A4: WHOLE-CALL failure -- a failing command anywhere in text
fails the entire call, no partial report\<close>

ML \<open>
val text_bad =
  "lemma pd_bad: \"False\"\n" ^
  "apply (rule TrueI)\n" ^
  "done\n";

\<^assert> (err_mentions (fn () => MCP_Profile_Delta.profile main text_bad) "");
(*more specific: the failure is NOT silently swallowed into a report --
  calling profile_entries directly on the same bad text also raises,
  rather than returning a short entry list for the commands before the
  failure*)
\<^assert> (err_mentions (fn () => MCP_Profile_Delta.profile_entries main text_bad) "");
\<close>

section \<open>A6: read-only -- no repl is created, claimed, or otherwise
touched by a profile_proof_delta call\<close>

ML \<open>
val (status_before, repls_before) = MCP_Repl.run "repls" [];
val _ = MCP_Profile_Delta.profile main text1;
val (status_after, repls_after) = MCP_Repl.run "repls" [];

\<^assert> (status_before = "ok" andalso status_after = "ok");
\<^assert> (repls_before = repls_after);
\<close>

section \<open>routing: the declared mcp_tool reaches MCP_Profile_Delta.profile\<close>

ML \<open>
val routed =
  MCP_Tool.run \<^context> "MCP_Profile_Delta.profile_proof_delta"
    [("theory_name", main), ("isar_text", text1)];
\<^assert> (routed = report1);
\<close>

end
