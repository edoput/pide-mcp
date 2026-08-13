theory MCP_Profile_Memory_Tests
  imports "MCP-HOL.MCP_Profile_Memory"
begin

text \<open>Unit tests for \<^ML_structure>\<open>MCP_Profile_Memory\<close>
(plans/proof_profiler_memory). This theory fails to load iff a test
fails, so \<^verbatim>\<open>isabelle build -d mcp/Tools MCP-HOL-Tests\<close> is the test
runner. Kept separate from \<^theory>\<open>MCP-HOL.MCP_Profile_Memory\<close>'s own
theory so profiling churn never lands in the production MCP-HOL heap.\<close>

ML \<open>
(*image theories are registered under their long name, e.g. "HOL.Main"*)
val main =
  the_default "Main"
    (find_first (fn n => n = "Main" orelse String.isSuffix ".Main" n) (Thy_Info.get_names ()));
\<close>

section \<open>Command count: report has one row per input command\<close>

ML \<open>
(*"lemma"/"by" are separate Isar commands (each its own transition, the
  same granularity repl_step works at), so a one-command report needs a
  single unclosed goal statement, not a whole closed proof.*)
val rows1 = MCP_Profile_Memory.profile_rows main "lemma \"True\"";
val _ = \<^assert> (length rows1 = 1);

val rows2 =
  MCP_Profile_Memory.profile_rows main "lemma \"True\"\nby simp";
val _ = \<^assert> (length rows2 = 2);
val _ = \<^assert> (#index (nth rows2 0) = 0 andalso #index (nth rows2 1) = 1);
val _ = \<^assert> (forall (fn (r: MCP_Profile_Memory.row) => #error r = NONE) rows2);
\<close>

section \<open>A demonstrably large allocation ranks above a trivial proof\<close>

text \<open>One command builds a large list directly in ML (2,000,000 triples,
tens of MB) and binds it to a persistent top-level name -- binding
matters (plans/proof_profiler_memory, "why bind"): this profiler
measures NET RETAINED memory after a forced full GC (the theory's
Discovery 3), so an unbound/discarded allocation (\<^verbatim>\<open>val _ = ...\<close>)
would be dead and collected before the post-command sample runs,
reading as ~0 -- binding the list to a name keeps it reachable via the
resulting theory's ML environment, exactly the "retained state"
scenario this signal is meant to catch. The other command is the
smallest possible open goal statement. Deliberately NOT \<^verbatim>\<open>value\<close> --
that drives Isabelle's code generator (slow: compiles fresh ML). The
big-allocation command's delta must exceed the trivial one's, and the
formatted report must list it FIRST (sorted highest-first).\<close>

ML \<open>
val big_text =
  "ML \<open>val big_data = List.tabulate (2000000, fn i => (i, i, i))\<close>\n" ^
  "lemma trivial_true: \"True\"";  (*states the goal only -- one transition,
    no "by" needed since this test only compares memory, not proof
    completion*)

val rows = MCP_Profile_Memory.profile_rows main big_text;
val _ = \<^assert> (length rows = 2);
val _ = \<^assert> (forall (fn (r: MCP_Profile_Memory.row) => #error r = NONE) rows);

val big_delta = #delta (nth rows 0);
val small_delta = #delta (nth rows 1);
val _ = \<^assert> (big_delta > small_delta);

val report = MCP_Profile_Memory.format_report rows;
(*the ML allocator (the big one) must be reported before "lemma" (the
  trivial one) -- i.e. its line appears earlier in the report text.
  Find the index of the first line naming each command and compare.*)
fun line_start_of pat s =
  let val lines = String.tokens (fn c => c = #"\n") s
      fun go (_, []) = ~1
        | go (i, l :: ls) = if String.isSubstring pat l then i else go (i + 1, ls)
  in go (0, lines) end;
(*rows are labelled by Toplevel command keyword ("ML"/"lemma"), not by
  the lemma's own bound name -- "trivial_true" never appears in the
  report itself.*)
val i_big = line_start_of "ML" report;
val i_small = line_start_of "lemma" report;
val _ = \<^assert> (i_big >= 0 andalso i_small >= 0 andalso i_big < i_small);
\<close>

section \<open>Error handling: partial results, stop at first failure\<close>

ML \<open>
val err_text =
  "lemma ok_one: \"True\"\n" ^          (*succeeds: opens a goal*)
  "lemma bad_reopen: \"True\"\n" ^      (*fails: ok_one's goal is still
    open, so a second top-level "lemma" is an illegal transition in
    Proof mode -- no "by" pairing needed to force a failure here*)
  "lemma never_reached: \"True\"";      (*never reached*)

val rows_err = MCP_Profile_Memory.profile_rows main err_text;
(*profiling stops at the failing command -- the report shows the
  successful command before it and the failure itself, not the
  never-reached third command*)
val _ = \<^assert> (length rows_err = 2);
val _ = \<^assert> (#error (nth rows_err 0) = NONE);
val _ = \<^assert> (is_some (#error (nth rows_err 1)));

val report_err = MCP_Profile_Memory.format_report rows_err;
val _ = \<^assert> (String.isSubstring "ABORTED" report_err);
val _ = \<^assert> (String.isSubstring "stopped at command #1" report_err);
\<close>

section \<open>Empty input\<close>

ML \<open>
val rows_empty = MCP_Profile_Memory.profile_rows main "";
val _ = \<^assert> (null rows_empty);
val _ = \<^assert> (MCP_Profile_Memory.format_report rows_empty = "no commands in input");
\<close>

section \<open>Isolation: profiling never touches Ir.repl_tab\<close>

text \<open>NOT a byte-identical before/after \<open>repls\<close> listing -- this build
runs its ML tests in a shared process alongside \<^verbatim>\<open>MCP_Repl_Tests\<close>
and \<^verbatim>\<open>Ir_Tests\<close>, whose own async repl churn can still be settling
concurrently (observed directly: a stray "busy" repl from an unrelated
test appeared in one snapshot and was gone in the next), so the exact
listing is not this test's business to pin down. What this profiler
promises is narrower and directly testable: it never creates a repl_tab
entry of its OWN -- \<^verbatim>\<open>MCP_Profile_Memory.fresh_state\<close> (private to the
theory's ML block) ids every throwaway theory
\<open>"MCP_Profile_Memory_" ^ serial ()\<close> and that prefix must never appear
in \<open>repls\<close>' output, before or after.\<close>

ML \<open>
fun no_profiler_repl () =
  let val (status, output) = MCP_Repl.run "repls" []
  in
    \<^assert> (status = "ok");
    not (String.isSubstring "MCP_Profile_Memory_" (XML.content_of (YXML.parse_body output)))
  end;

val _ = \<^assert> (no_profiler_repl ());
val _ = MCP_Profile_Memory.profile main "lemma \"True\"\nby simp";
val _ = \<^assert> (no_profiler_repl ());
\<close>

section \<open>mcp_tool wiring: profile_proof_memory is registered and callable\<close>

text \<open>NB: not \<^ML>\<open>MCP_Protocol.designated_context "" []\<close> -- that resolves
against the pinned DEFAULT theory (\<^ML>\<open>MCP_Protocol.set_default_theory\<close>,
last called by \<^verbatim>\<open>MCP-HOL.MCP_Repl\<close> itself), which \<^verbatim>\<open>MCP_Profile_Memory\<close>
never re-points, so a tool declared there is invisible through that
particular hook. \<^ML>\<open>\<^context>\<close> here is this Tests theory's own context,
which sees every ancestor's tools directly (ordinary theory-context
visibility, the same as MCP_Tools_Tests.thy's own probes) -- what this
section tests is that the tool exists and runs, not the default-
designation mechanism.\<close>

ML \<open>
val bases =
  map (Long_Name.base_name o #1) (MCP_Tool.list (Context.Proof \<^context>));
val _ = \<^assert> (member (op =) bases "profile_proof_memory");
\<close>

ML \<open>
val tool_ctxt = Context.Proof \<^context>;
val (_, tool) =
  the (find_first (fn (name, _) => Long_Name.base_name name = "profile_proof_memory")
    (MCP_Tool.list tool_ctxt));
val out = #run tool \<^context> [("theory_name", main), ("isar_text", "lemma \"True\"")];
val _ = \<^assert> (String.isSubstring "bytes" out);
val _ = \<^assert> (String.isSubstring "profiled 1/1 command" out);
\<close>

end
