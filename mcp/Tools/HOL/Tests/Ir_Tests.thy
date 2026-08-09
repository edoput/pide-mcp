theory Ir_Tests
  imports "MCP-HOL-Tests.MCP_Repl_Tests"
begin

text \<open>Imports MCP_Repl_Tests, not MCP-HOL.MCP_Repl directly, PURELY to
force build ORDER, not because this theory's content depends on it
logically. \<^ML_structure>\<open>Ir\<close>'s repl table is one process-global
Synchronized var, and \<open>isabelle build\<close> processes theories with no
import relationship to each other IN PARALLEL within one session. Found
empirically, not anticipated: a concurrency test below (a repl
deliberately left mid-slow-step so \<open>Ir.repls\<close> reports it "busy") made
MCP_Repl_Tests.thy's OWN unrelated \<open>repl_step\<close> T2 assertion --
\<open>not (String.isSubstring "busy" (plain o_after))\<close>, a WHOLE-TABLE
scan for the word "busy" ANYWHERE -- genuinely, correctly fail, because
this theory's concurrent repl really was busy in the shared global
table at that instant. Neither theory has a bug; two independent test
suites cannot safely share one mutable global table while either one
exercises real concurrency, without being serialized. This import is
that serialization.\<close>

text \<open>Regression coverage for \<^ML_structure>\<open>Ir\<close> ITSELF -- the vendored
I/R engine (ir/ir.ML, MIT-licensed prior art, reused verbatim) -- kept
independent of however this server chooses to EXPOSE it.

Every other repl-related suite in this tree (MCP_Repl_Tests.thy's
per-tool sections, and mcp_bridge_tests.scala's "ir bridge" tests) calls
\<^ML>\<open>Ir.fork\<close>/\<^ML>\<open>Ir.truncate\<close>/etc. through
\<^ML_structure>\<open>MCP_Repl\<close>'s \<open>dispatch\<close> function, a closed fname/args
table. plans/ml_builtin_migration deletes that table one case at a time
as each fname becomes an ordinary \<open>mcp_tool\<close> declaration -- so any test
asserting on \<^ML_structure>\<open>Ir\<close>'s OWN semantics (does fork track its
origin correctly, does truncate orphan the right forks, does a busy
claim block a concurrent operation, ...) through that table is exposed
to churn that has NOTHING to do with whether \<^ML_structure>\<open>Ir\<close> itself
still behaves correctly.

This theory calls \<^ML_structure>\<open>Ir\<close> directly -- no
\<^ML>\<open>MCP_Repl.dispatch\<close>, no fname table, no Isabelle/Scala bridge --
so it is unaffected by any future change to how tools are routed. Once
a claim here is confirmed to reproduce its bridge/ml-unit original
faithfully, that original becomes a ROUTING-ONLY test (does calling the
tool reach \<^ML_structure>\<open>Ir\<close> and relay its output), not a semantic
one -- see plans/README or the session notes for the paired-down
originals.

Output capture, without the dispatcher: \<^ML_structure>\<open>Ir\<close>'s public
interface is writeln-style (only \<^ML>\<open>Ir.context_of\<close> returns a value),
so capturing still needs \<^ML>\<open>MCP_Output.captured\<close> -- but that utility
lives in MCP_Tools.thy, independent of the fname table, so reusing it
here does not reintroduce the dependency this theory exists to avoid.

CORRECTED (an earlier version of this comment claimed \<open>run_ir\<close> ran
under a controlled PLAIN print mode; that was wrong on inspection, kept
here as a recorded mistake rather than silently rewritten). An earlier
draft wrapped the call in \<^ML>\<open>Print_Mode.with_modes []\<close>, modelled on
\<open>exec_text\<close>'s own plain-mode capture -- but \<open>with_modes []\<close> APPENDS
the empty list to the CURRENT mode (\<open>print_mode.ML\<close>: \<open>modes @
print_mode_value ()\<close>), so it changes nothing, and even a real mode list
would not have reached the worker regardless: \<^ML>\<open>MCP_Output.captured\<close>
FORKS \<open>f\<close> into a fresh thread, and print mode is thread-local state that
a fork does not inherit from the caller (only position and the generic
context cross that boundary) -- unlike \<^ML_structure>\<open>MCP_Repl\<close>'s own
\<open>fork_run\<close>, which sets its PIDE mode INSIDE the forked closure
(\<open>MCP_Repl.thy:436\<close>), not outside it. So \<open>run_ir\<close> never controlled
print mode at all, in either direction. This was found empirically:
\<^ML>\<open>Ir.sledgehammer\<close>'s "Try this: ..." suggestion carries
\<open>Active.sendback\<close> markup (for IDE clickability) that survived capture
as raw yxml control bytes (chr 5 / chr 6), which a real
\<open>Print_Mode.PIDE\<close>-exclusion would have prevented. The actual fix is
unconditional: \<open>run_ir\<close> strips yxml from every captured string via the
same \<open>plain\<close> transform MCP_Repl_Tests.thy's tests already apply by
hand, regardless of whatever print mode the forked worker happens to
run under -- safe to apply even when there is nothing to strip, since
\<^ML>\<open>YXML.parse_body\<close> on text with no yxml control characters is the
identity. The dead \<open>Print_Mode.with_modes\<close> wrapper is removed below,
not left in as inert decoration.\<close>

ML \<open>
(*mirrors MCP_Repl.run/dispatch's (status, output) contract and its
  output+message join on error (MCP_Repl.thy's fork_run), but calling
  Ir.* directly -- no fname, no dispatch table.*)
val plain = XML.content_of o YXML.parse_body;

fun run_ir (f: unit -> unit) : string * string =
  let val (result, output) = MCP_Output.captured f
  in case result of
       Exn.Res () => ("ok", plain output)
     | Exn.Exn exn =>
         if Exn.is_interrupt exn then Exn.reraise exn
         else
           let val msg = Runtime.exn_message exn
           in ("error", plain (if output = "" then msg else output ^ "\n" ^ msg)) end
  end;

(*image theories are registered under their long name, e.g. "HOL.Main"*)
val main =
  the_default "Main"
    (find_first (fn n => n = "Main" orelse String.isSuffix ".Main" n) (Thy_Info.get_names ()));

(*count (non-overlapping) occurrences of pat in s*)
fun count_substring pat s =
  let val n = size pat
      fun go i acc =
        if i + n > size s then acc
        else if String.substring (s, i, n) = pat then go (i + n) (acc + 1)
        else go (i + 1) acc
  in go 0 0 end;

(*find the FIRST "(" at or after `from` whose content matches a
  sledgehammer timing marker's shape, "<digits/dot>+ <ws>* m? s )" --
  e.g. "(0.7 ms)" or "(3 s)". Mirrors mcp_bridge_tests.scala:396-397's
  regex \(\s*\([0-9.]+\s*m?s\)\s*$\ precisely, rather than just taking
  the first or last paren in the string: sledgehammer's own report can
  contain MULTIPLE "Try this:" suggestions (one per method it tried),
  each possibly with its own parenthesized tactic (e.g. "by (metis
  ...)"), so neither "first paren" nor "last paren in the remainder" is
  safe -- only a shape-matched search correctly stops at the timing
  marker belonging to the FIRST suggestion.*)
fun find_timing_paren s from =
  let
    val n = size s;
    fun skip p j = if j < n andalso p (String.sub (s, j)) then skip p (j + 1) else j;
    fun is_timing_marker i =
      let
        val j1 = skip (fn c => Char.isDigit c orelse c = #".") i;
        val j2 = skip Char.isSpace j1;
        val j3 = if j2 < n andalso String.sub (s, j2) = #"m" then j2 + 1 else j2;
        val j4 = if j3 < n andalso String.sub (s, j3) = #"s" then j3 + 1 else j3;
      in j1 > i andalso j4 < n andalso String.sub (s, j4) = #")" end;
    fun go i =
      if i >= n then NONE
      else if String.sub (s, i) = #"(" andalso is_timing_marker (i + 1) then SOME i
      else go (i + 1)
  in go from end;

(*poll cond until true or timeout_secs elapses -- mirrors
  mcp_testing.scala's eventually/await_busy, for the concurrency tests
  below that need to observe a repl mid-claim without a fixed sleep*)
fun eventually_ir timeout_secs cond =
  let
    val deadline = Time.+ (Time.now (), Time.fromReal timeout_secs);
    fun loop () = cond () orelse (Time.< (Time.now (), deadline) andalso loop ())
  in loop () end;
\<close>

text \<open>Smoke test: run_ir round-trips ok/error correctly, and its output
is genuinely yxml-free -- not because plain mode guarantees this
universally (it does not, see \<open>run_ir\<close>'s own comment: \<open>Active.sendback\<close>
markup from \<^ML>\<open>Ir.sledgehammer\<close> is a real counterexample), but because
\<open>run_ir\<close> strips it unconditionally. Checked directly rather than
assumed.\<close>
ML \<open>
val (s_smoke, o_smoke) = run_ir (fn () => Ir.init "IrSmoke" ["Main"]);
val _ = \<^assert> (s_smoke = "ok");
val _ = \<^assert> (String.isSubstring "Created REPL" o_smoke);
(*no yxml control characters (STX = chr 2, the tag opener) survive*)
val _ = \<^assert> (List.all (fn c => Char.ord c <> 2) (String.explode o_smoke));

val (s_dup, o_dup) = run_ir (fn () => Ir.init "IrSmoke" ["Main"]);
val _ = \<^assert> (s_dup = "error");
val _ = \<^assert> (String.isSubstring "already exists" o_dup);

val (s_rm, _) = run_ir (fn () => Ir.remove "IrSmoke");
val _ = \<^assert> (s_rm = "ok");
\<close>

section \<open>Table basics -- MCP_Repl_Tests.thy "T1/T3" and "T4"\<close>

text \<open>NOTE on repl ids throughout this theory: every id below is
prefixed \<open>Ir\<close> (\<open>IrT\<close>, \<open>IrTi1\<close>, \<open>IrTfk2\<close>, ...), distinct from
MCP_Repl_Tests.thy's bare ids (\<open>T\<close>, \<open>Ti1\<close>, \<open>Tfk2\<close>, ...) even though
both theories test the same claims -- \<^ML_structure>\<open>Ir\<close>'s own repl
table is one process-global table shared by every theory in this
session, and \<open>isabelle build\<close> may process independent theories
concurrently, so an accidental id collision between this theory and
MCP_Repl_Tests.thy would be a real, if rare, source of flaky "already
exists" failures.\<close>

text \<open>T1/T3: repls reflects the live table, no caching.\<close>
ML \<open>
val (s_init, o_init) = run_ir (fn () => Ir.init "IrT" [main]);
val _ = \<^assert> (s_init = "ok" andalso String.isSubstring "Created REPL" o_init);

val (s1, o1) = run_ir Ir.repls;
val _ = \<^assert> (s1 = "ok");
val _ = \<^assert> (String.isSubstring "IrT" o1);
val _ = \<^assert> (String.isSubstring "0 steps" o1);

val (s_step, _) = run_ir (fn () => Ir.step "IrT" "lemma True");
val _ = \<^assert> (s_step = "ok");

val (s2, o2) = run_ir Ir.repls;
val _ = \<^assert> (s2 = "ok");
val _ = \<^assert> (String.isSubstring "1 steps" o2);

val (s_rm, _) = run_ir (fn () => Ir.remove "IrT");
val _ = \<^assert> (s_rm = "ok");

val (s3, o3) = run_ir Ir.repls;
val _ = \<^assert> (s3 = "ok");
val _ = \<^assert> (not (String.isSubstring "IrT (" o3));
\<close>

text \<open>T4: read-only -- repeating repls changes nothing, the repl still
steps.

HISTORY, corrected rather than silently rewritten: an earlier version
of this test replaced the original's full-listing equality
(\<open>listing_a = listing_b\<close>) with a substring-only check, reasoning that
"Ir_Tests.thy and MCP_Repl_Tests.thy both depend only on MCP_Repl, not
on each other" and so could run concurrently within one \<open>isabelle
build\<close> session, making a whole-table snapshot comparison racy against
the OTHER theory's own repl churn. That reasoning is now STALE: this
theory imports MCP_Repl_Tests (see the file header) precisely to force
sequential build order between the two, so no other theory can touch
\<^ML_structure>\<open>Ir\<close>'s repl table while this test runs. The substring-only
check was also, independently, not equivalent to the original claim it
claimed to preserve -- checking the same substring in both listings
twice never actually compares the two listings to each other, so it
would not have caught the original's real claim (repls is genuinely
read-only) failing. Restored to the original's own assertion.\<close>
ML \<open>
val (s_init4, _) = run_ir (fn () => Ir.init "IrT4" [main]);
val _ = \<^assert> (s_init4 = "ok");

val (_, listing_a) = run_ir Ir.repls;
val (_, listing_b) = run_ir Ir.repls;
val _ = \<^assert> (listing_a = listing_b);

(*the repl is unaffected by the read-only listing calls: it still steps*)
val (s_step4, o_step4) = run_ir (fn () => Ir.step "IrT4" "lemma True");
val _ = \<^assert> (s_step4 = "ok" andalso String.isSubstring "True" o_step4);

val (s_rm4, _) = run_ir (fn () => Ir.remove "IrT4");
val _ = \<^assert> (s_rm4 = "ok");
\<close>

section \<open>repl_init (plans/repl_init): T1..T6\<close>

text \<open>T1: theory specs succeed regardless of merge order. \<^ML>\<open>Ir.init\<close>
takes the theory list directly -- no repeated-key wire encoding to
translate here, that concern belongs to the (soon-to-be-deleted)
dispatcher's own argument decoding, not to \<^ML_structure>\<open>Ir\<close>.\<close>
ML \<open>
(*a genuine ancestor of main, distinct from it, already in the image --
  avoids depending on a theory that may not be loaded into MCP-HOL*)
val other =
  Context.theory_name {long = true} (hd (Theory.ancestors_of (Thy_Info.get_theory main)));

val (s_fwd, _) = run_ir (fn () => Ir.init "IrTi1" [main, other]);
val _ = \<^assert> (s_fwd = "ok");
val (s_rm1, _) = run_ir (fn () => Ir.remove "IrTi1");
val _ = \<^assert> (s_rm1 = "ok");

val (s_bwd, _) = run_ir (fn () => Ir.init "IrTi2" [other, main]);
val _ = \<^assert> (s_bwd = "ok");
val (s_rm2, _) = run_ir (fn () => Ir.remove "IrTi2");
val _ = \<^assert> (s_rm2 = "ok");
\<close>

text \<open>T2: an empty theories list is an engine error ("at least one
spec").\<close>
ML \<open>
val (s_empty, o_empty) = run_ir (fn () => Ir.init "IrTi3" []);
val _ = \<^assert> (s_empty = "error");
val _ = \<^assert> (String.isSubstring "at least one spec" o_empty);

(*no repl was created*)
val (_, o_after) = run_ir Ir.repls;
val _ = \<^assert> (not (String.isSubstring "IrTi3" o_after));
\<close>

text \<open>T3: duplicate id is an engine error and leaves the existing repl
untouched.\<close>
ML \<open>
val (s_first, _) = run_ir (fn () => Ir.init "IrTi4" [main]);
val _ = \<^assert> (s_first = "ok");

val (s_dup, o_dup) = run_ir (fn () => Ir.init "IrTi4" [main]);
val _ = \<^assert> (s_dup = "error");
val _ = \<^assert> (String.isSubstring "already exists" o_dup);

(*the existing repl still works*)
val (s_step, _) = run_ir (fn () => Ir.step "IrTi4" "lemma True");
val _ = \<^assert> (s_step = "ok");
val (s_rm, _) = run_ir (fn () => Ir.remove "IrTi4");
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T4: an unknown theory name is a status error naming the theory; no
repl is created.\<close>
ML \<open>
val (s_bad, o_bad) = run_ir (fn () => Ir.init "IrTi5" ["No.Such_Theory"]);
val _ = \<^assert> (s_bad = "error");
val _ = \<^assert> (String.isSubstring "No.Such_Theory" o_bad);
val (_, o_after) = run_ir Ir.repls;
val _ = \<^assert> (not (String.isSubstring "IrTi5" o_after));
\<close>

text \<open>T5: image theories resolve under their long name.\<close>
ML \<open>
val (s_long, _) = run_ir (fn () => Ir.init "IrTi6" [main]);
val _ = \<^assert> (s_long = "ok");
val (s_rm, _) = run_ir (fn () => Ir.remove "IrTi6");
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T6 (part): "Thy:idx" segment specs error when mixed with a plain
theory spec, and "pin@name" errors cleanly when there is no such pin.
The positive "Thy:idx" ok-path needs record_theories in a LIVE session
(same constraint as the original) -- covered at the bridge layer, not
here.\<close>
ML \<open>
val (s_mix, o_mix) = run_ir (fn () => Ir.init "IrTi8" [main, "Dummy:0"]);
val _ = \<^assert> (s_mix = "error");
val _ = \<^assert> (String.isSubstring "Cannot mix theory and segment specs" o_mix);

val (s_pin, o_pin) = run_ir (fn () => Ir.init "IrTi9" ["pin@nope"]);
val _ = \<^assert> (s_pin = "error");
val _ = \<^assert> (String.isSubstring "No REPL" o_pin);
\<close>

section \<open>repl_fork (plans/repl_fork): T2..T4, plus the bridge suite's T6\<close>

text \<open>T2: index semantics -- 0 is the base state, N is the state after
step N-1, -1 is the latest. Faithfulness of "-1 equals fork at 2" is
checked by comparing the state EACH fork starts from
(\<^ML>\<open>Ir.state\<close> at each fork's own index 0), not the parent's states
directly.\<close>
ML \<open>
val (s_init, _) = run_ir (fn () => Ir.init "IrTfk2" [main]);
val _ = \<^assert> (s_init = "ok");
val (s1, _) = run_ir (fn () => Ir.step "IrTfk2" "lemma tfk2: True");
val _ = \<^assert> (s1 = "ok");
val (s2, _) = run_ir (fn () => Ir.step "IrTfk2" "by simp");
val _ = \<^assert> (s2 = "ok");

val (s_f0, _) = run_ir (fn () => Ir.fork "IrTfk2" "IrTfk2f0" 0);
val _ = \<^assert> (s_f0 = "ok");
val (s_f1, _) = run_ir (fn () => Ir.fork "IrTfk2" "IrTfk2f1" 1);
val _ = \<^assert> (s_f1 = "ok");
val (s_fneg1, _) = run_ir (fn () => Ir.fork "IrTfk2" "IrTfk2fneg1" ~1);
val _ = \<^assert> (s_fneg1 = "ok");
val (s_f2, _) = run_ir (fn () => Ir.fork "IrTfk2" "IrTfk2f2" 2);
val _ = \<^assert> (s_f2 = "ok");

val (_, o_state_neg1) = run_ir (fn () => Ir.state "IrTfk2fneg1" 0);
val (_, o_state_2) = run_ir (fn () => Ir.state "IrTfk2f2" 0);
val _ = \<^assert> (o_state_neg1 = o_state_2);

val (s_f99, o_f99) = run_ir (fn () => Ir.fork "IrTfk2" "IrTfk2f99" 99);
val _ = \<^assert> (s_f99 = "error");
val _ = \<^assert> (String.isSubstring "out of range" o_f99);

val (s_rm, _) = run_ir (fn () => Ir.remove "IrTfk2");
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T3: a duplicate new_repl id errors and changes nothing; the
parent's claim is released, so it still steps afterwards.\<close>
ML \<open>
val (s_init, _) = run_ir (fn () => Ir.init "IrTfk3" [main]);
val _ = \<^assert> (s_init = "ok");
val (s_dup, _) = run_ir (fn () => Ir.init "IrTfk3dup" [main]);
val _ = \<^assert> (s_dup = "ok");

val (s_f, o_f) = run_ir (fn () => Ir.fork "IrTfk3" "IrTfk3dup" 0);
val _ = \<^assert> (s_f = "error");
val _ = \<^assert> (String.isSubstring "already exists" o_f);

val (s_step, _) = run_ir (fn () => Ir.step "IrTfk3" "lemma tfk3: True");
val _ = \<^assert> (s_step = "ok");

val (s_rm1, _) = run_ir (fn () => Ir.remove "IrTfk3");
val _ = \<^assert> (s_rm1 = "ok");
val (s_rm2, _) = run_ir (fn () => Ir.remove "IrTfk3dup");
val _ = \<^assert> (s_rm2 = "ok");
\<close>

text \<open>T4: the fork is independent of its parent in both directions.\<close>
ML \<open>
val (s_init, _) = run_ir (fn () => Ir.init "IrTfk4" [main]);
val _ = \<^assert> (s_init = "ok");
val (s1, _) = run_ir (fn () => Ir.step "IrTfk4" "lemma tfk4: True");
val _ = \<^assert> (s1 = "ok");
val (s2, _) = run_ir (fn () => Ir.step "IrTfk4" "by simp");
val _ = \<^assert> (s2 = "ok");

val (s_f, _) = run_ir (fn () => Ir.fork "IrTfk4" "IrTfk4C" ~1);
val _ = \<^assert> (s_f = "ok");

val (s_cstep, _) = run_ir (fn () => Ir.step "IrTfk4C" "lemma tfk4c: True by simp");
val _ = \<^assert> (s_cstep = "ok");
val (_, o_parent_after_child_step) = run_ir (fn () => Ir.show "IrTfk4");
val _ = \<^assert> (String.isSubstring "2 steps" o_parent_after_child_step);

val (s_pstep, _) = run_ir (fn () => Ir.step "IrTfk4" "lemma tfk4b: True by simp");
val _ = \<^assert> (s_pstep = "ok");
val (_, o_child_after_parent_step) = run_ir (fn () => Ir.show "IrTfk4C");
val _ = \<^assert> (String.isSubstring "1 steps" o_child_after_parent_step);

val (s_rm_c, _) = run_ir (fn () => Ir.remove "IrTfk4C");
val _ = \<^assert> (s_rm_c = "ok");
val (s_rm, _) = run_ir (fn () => Ir.remove "IrTfk4");
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>bridge T6: the full chain also checks \<^ML>\<open>Ir.repls\<close>'s ORIGIN
rendering for a From_REPL entry -- \<open>origin_str\<close> prints it as
\<open>REPL "<parent>" state <i>\<close>, which none of the sequential T2..T4 checks
above happen to exercise (they check step counts and error messages,
never the origin string itself). Ported from
mcp_bridge_tests.scala:294-322, sequential here since the claim itself
needs no concurrency.\<close>
ML \<open>
val (s_init6, _) = run_ir (fn () => Ir.init "IrFk6" [main]);
val _ = \<^assert> (s_init6 = "ok");
val (s_step6, _) = run_ir (fn () => Ir.step "IrFk6" "lemma fork6: True");
val _ = \<^assert> (s_step6 = "ok");

val (s_fork6, _) = run_ir (fn () => Ir.fork "IrFk6" "IrFk6C" ~1);
val _ = \<^assert> (s_fork6 = "ok");
val (s_cstep6, _) = run_ir (fn () => Ir.step "IrFk6C" "by simp");
val _ = \<^assert> (s_cstep6 = "ok");

val (_, listing6) = run_ir Ir.repls;
val _ = \<^assert> (String.isSubstring "IrFk6" listing6);
val _ = \<^assert> (String.isSubstring "IrFk6C" listing6);
val _ = \<^assert> (String.isSubstring "from REPL \"IrFk6\" state" listing6);

(*the fork's own step never touched the parent*)
val (_, o_parent_show6) = run_ir (fn () => Ir.show "IrFk6");
val _ = \<^assert> (String.isSubstring "1 steps" o_parent_show6);

val (s_rm6, _) = run_ir (fn () => Ir.remove "IrFk6C");
val _ = \<^assert> (s_rm6 = "ok");
val (s_rm6p, _) = run_ir (fn () => Ir.remove "IrFk6");
val _ = \<^assert> (s_rm6p = "ok");
\<close>

section \<open>repl_remove (plans/repl_remove): T1..T3, plus the bridge suite's T4\<close>

text \<open>T1: removing an unknown repl is a status error naming it, not ok;
the tool is not idempotent -- a second removal of the same id errors too.\<close>
ML \<open>
val (s_nope, o_nope) = run_ir (fn () => Ir.remove "IrNope");
val _ = \<^assert> (s_nope = "error");
val _ = \<^assert> (String.isSubstring "No REPL" o_nope);

val (s_first, _) = run_ir (fn () => Ir.init "IrTr1" [main]);
val _ = \<^assert> (s_first = "ok");
val (s_rm1, _) = run_ir (fn () => Ir.remove "IrTr1");
val _ = \<^assert> (s_rm1 = "ok");
val (s_rm2, o_rm2) = run_ir (fn () => Ir.remove "IrTr1");
val _ = \<^assert> (s_rm2 = "error");
val _ = \<^assert> (String.isSubstring "No REPL" o_rm2);
\<close>

text \<open>T2: removal is recursive and reports every removed id.\<close>
ML \<open>
val (s_p, _) = run_ir (fn () => Ir.init "IrTr2" [main]);
val _ = \<^assert> (s_p = "ok");
val (s_f1, _) = run_ir (fn () => Ir.fork "IrTr2" "IrTr2a" 0);
val _ = \<^assert> (s_f1 = "ok");
val (s_f2, _) = run_ir (fn () => Ir.fork "IrTr2a" "IrTr2b" 0);
val _ = \<^assert> (s_f2 = "ok");

val (s_rm, o_rm) = run_ir (fn () => Ir.remove "IrTr2");
val _ = \<^assert> (s_rm = "ok");
val _ = \<^assert> (String.isSubstring "IrTr2" o_rm);
val _ = \<^assert> (String.isSubstring "IrTr2a" o_rm);
val _ = \<^assert> (String.isSubstring "IrTr2b" o_rm);

val (_, o_after) = run_ir Ir.repls;
val _ = \<^assert> (not (String.isSubstring "IrTr2" o_after));
\<close>

text \<open>T3: pin dependents block removal; removing the dependent first
frees the pin owner.\<close>
ML \<open>
val (s_a, _) = run_ir (fn () => Ir.init "IrTr3a" [main]);
val _ = \<^assert> (s_a = "ok");
val (s_pin, _) = run_ir (fn () => Ir.pin "IrTr3a");
val _ = \<^assert> (s_pin = "ok");
val (s_b, _) = run_ir (fn () => Ir.init "IrTr3b" ["pin@IrTr3a"]);
val _ = \<^assert> (s_b = "ok");

val (s_rm_blocked, o_rm_blocked) = run_ir (fn () => Ir.remove "IrTr3a");
val _ = \<^assert> (s_rm_blocked = "error");
val _ = \<^assert> (String.isSubstring "depend on its pin" o_rm_blocked);

val (s_rm_b, _) = run_ir (fn () => Ir.remove "IrTr3b");
val _ = \<^assert> (s_rm_b = "ok");
val (s_rm_a, _) = run_ir (fn () => Ir.remove "IrTr3a");
val _ = \<^assert> (s_rm_a = "ok");
\<close>

text \<open>bridge T4: a busy descendant blocks removal, nothing is
half-removed. Genuine concurrency, unlike T1..T3 above -- ported from
mcp_bridge_tests.scala:799-819's Future.fork + poll-for-busy shape,
using \<^ML>\<open>eventually_ir\<close> in place of the bridge's \<open>await_busy\<close>.\<close>
ML \<open>
val (s_init4, _) = run_ir (fn () => Ir.init "IrPar4" [main]);
val _ = \<^assert> (s_init4 = "ok");
val (s_fork4, _) = run_ir (fn () => Ir.fork "IrPar4" "IrChild4" 0);
val _ = \<^assert> (s_fork4 = "ok");

val slow4 =
  Future.fork (fn () =>
    run_ir (fn () => Ir.step "IrChild4" "ML_command \<open>OS.Process.sleep (seconds 2.0)\<close>"));

val busy_seen4 =
  eventually_ir 1.5 (fn () =>
    let val (_, listing) = run_ir Ir.repls
    in String.isSubstring "IrChild4" listing andalso String.isSubstring "busy" listing end);
val _ = \<^assert> busy_seen4;

val (s_rm_blocked4, o_rm_blocked4) = run_ir (fn () => Ir.remove "IrPar4");
val _ = \<^assert> (s_rm_blocked4 = "error");
val _ = \<^assert> (String.isSubstring "busy" o_rm_blocked4);

val _ = Future.join slow4;
val (s_rm4, _) = run_ir (fn () => Ir.remove "IrPar4");
val _ = \<^assert> (s_rm4 = "ok");
val (_, o_after4) = run_ir Ir.repls;
val _ = \<^assert> (not (String.isSubstring "IrPar4" o_after4));
val _ = \<^assert> (not (String.isSubstring "IrChild4" o_after4));
\<close>

section \<open>repl_step (plans/repl_step): T1..T4, plus the bridge suite's T5\<close>

text \<open>T1: isar text survives the trip byte-clean, including Isabelle
symbols and a multi-line statement.\<close>
ML \<open>
val (s_init, _) = run_ir (fn () => Ir.init "IrTs1" [main]);
val _ = \<^assert> (s_init = "ok");

val stmt = "lemma \"x \<longrightarrow> x\"\n  by simp";
val (s_step, _) = run_ir (fn () => Ir.step "IrTs1" stmt);
val _ = \<^assert> (s_step = "ok");

val (s_text, o_text) = run_ir (fn () => Ir.text "IrTs1");
val _ = \<^assert> (s_text = "ok");
val _ = \<^assert> (String.isSubstring "\<longrightarrow>" o_text);

val (s_rm, _) = run_ir (fn () => Ir.remove "IrTs1");
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T2: failure atomicity -- a failing step leaves the step count and
last state unchanged, and the repl is left usable (claim released). The
"not busy anywhere" check is a WHOLE-TABLE scan (same hazard class as
the table-basics T4 note above) -- safe here only because every prior
concurrency test in this theory joins its Future before moving on, so
nothing is left mid-claim by the time this sequential test runs.\<close>
ML \<open>
val (s_init2, _) = run_ir (fn () => Ir.init "IrTs2" [main]);
val _ = \<^assert> (s_init2 = "ok");

val (s_ok2, _) = run_ir (fn () => Ir.step "IrTs2" "lemma True");
val _ = \<^assert> (s_ok2 = "ok");

val (_, o_before2) = run_ir Ir.repls;
val _ = \<^assert> (String.isSubstring "IrTs2 (1 steps" o_before2);

val (s_bad2, _) = run_ir (fn () => Ir.step "IrTs2" "garbage_tactic");
val _ = \<^assert> (s_bad2 = "error");

val (_, o_after2) = run_ir Ir.repls;
val _ = \<^assert> (String.isSubstring "IrTs2 (1 steps" o_after2);
val _ = \<^assert> (not (String.isSubstring "busy" o_after2));

(*the repl is still usable -- the claim was released even though the step failed*)
val (s_next2, _) = run_ir (fn () => Ir.step "IrTs2" "by simp");
val _ = \<^assert> (s_next2 = "ok");

val (s_rm2, _) = run_ir (fn () => Ir.remove "IrTs2");
val _ = \<^assert> (s_rm2 = "ok");
\<close>

text \<open>T3: a timed-out step behaves like a failed step.\<close>
ML \<open>
val (s_init3, _) = run_ir (fn () => Ir.init "IrTs3" [main]);
val _ = \<^assert> (s_init3 = "ok");
val (s_to3, _) = run_ir (fn () => Ir.timeout "IrTs3" 1);
val _ = \<^assert> (s_to3 = "ok");

val (s_slow3, o_slow3) =
  run_ir (fn () => Ir.step "IrTs3" "ML_command \<open>OS.Process.sleep (seconds 3.0)\<close>");
val _ = \<^assert> (s_slow3 = "error");
val _ = \<^assert> (String.isSubstring "timed out" o_slow3);

val (_, o_after3) = run_ir Ir.repls;
val _ = \<^assert> (String.isSubstring "IrTs3 (0 steps" o_after3);

val (s_next3, _) = run_ir (fn () => Ir.step "IrTs3" "lemma True");
val _ = \<^assert> (s_next3 = "ok");

val (s_rm3, _) = run_ir (fn () => Ir.remove "IrTs3");
val _ = \<^assert> (s_rm3 = "ok");
\<close>

text \<open>T4: a successful step inside a proof prints the goal state, not
just an acknowledgement.\<close>
ML \<open>
val (s_init4s, _) = run_ir (fn () => Ir.init "IrTs4" [main]);
val _ = \<^assert> (s_init4s = "ok");

val (s_step4s, o_step4s) =
  run_ir (fn () => Ir.step "IrTs4" "lemma \"x + y = y + (x::nat)\"");
val _ = \<^assert> (s_step4s = "ok");
val _ = \<^assert> (String.isSubstring "goal" o_step4s);

val (s_rm4s, _) = run_ir (fn () => Ir.remove "IrTs4");
val _ = \<^assert> (s_rm4s = "ok");
\<close>

text \<open>bridge T5: a slow step on one REPL does not delay a concurrent
step on another. Genuinely ir.ML's own property -- the internal claim
mechanism claims per-repl-id, not globally, so two DIFFERENT repls' claims are
independent regardless of any async dispatch layer; this was initially
omitted (a real gap, not a deliberate one) since the async layer itself
is our own code, but the underlying per-repl independence this test
actually checks belongs here and needs no dispatch layer to observe.
Ported from mcp_bridge_tests.scala:257-267.\<close>
ML \<open>
val (s_init5s, _) = run_ir (fn () => Ir.init "IrStepA" [main]);
val _ = \<^assert> (s_init5s = "ok");
val (s_initB5s, _) = run_ir (fn () => Ir.init "IrStepB" [main]);
val _ = \<^assert> (s_initB5s = "ok");

val slow5s =
  Future.fork (fn () =>
    run_ir (fn () => Ir.step "IrStepA" "ML_command \<open>OS.Process.sleep (seconds 2.0)\<close>"));
(*give the slow step a moment to actually claim IrStepA before racing the fast one*)
val _ = eventually_ir 1.0 (fn () =>
  let val (_, listing) = run_ir Ir.repls
  in String.isSubstring "IrStepA" listing andalso String.isSubstring "busy" listing end);

val (s_fast5s, _) = run_ir (fn () => Ir.step "IrStepB" "lemma True");
val _ = \<^assert> (s_fast5s = "ok");
val _ = \<^assert> (not (Future.is_finished slow5s));

val _ = Future.join slow5s;
val (s_rm_a5s, _) = run_ir (fn () => Ir.remove "IrStepA");
val _ = \<^assert> (s_rm_a5s = "ok");
val (s_rm_b5s, _) = run_ir (fn () => Ir.remove "IrStepB");
val _ = \<^assert> (s_rm_b5s = "ok");
\<close>

section \<open>repl_state (plans/repl_state): T1..T2\<close>

text \<open>T1: index arithmetic -- on a repl with 2 steps, -1 equals index 2;
0 is the base state; indices past either end error "out of range".\<close>
ML \<open>
val (s_init, _) = run_ir (fn () => Ir.init "IrTst1" [main]);
val _ = \<^assert> (s_init = "ok");
val (s1, _) = run_ir (fn () => Ir.step "IrTst1" "lemma True");
val _ = \<^assert> (s1 = "ok");
val (s2, _) = run_ir (fn () => Ir.step "IrTst1" "by simp");
val _ = \<^assert> (s2 = "ok");

val (s_neg1, o_neg1) = run_ir (fn () => Ir.state "IrTst1" ~1);
val (s_2, o_2) = run_ir (fn () => Ir.state "IrTst1" 2);
val _ = \<^assert> (s_neg1 = "ok" andalso s_2 = "ok");
val _ = \<^assert> (o_neg1 = o_2);

val (s_0, _) = run_ir (fn () => Ir.state "IrTst1" 0);
val _ = \<^assert> (s_0 = "ok");

val (s_oob_hi, o_oob_hi) = run_ir (fn () => Ir.state "IrTst1" 3);
val _ = \<^assert> (s_oob_hi = "error");
val _ = \<^assert> (String.isSubstring "out of range" o_oob_hi);

val (s_oob_lo, o_oob_lo) = run_ir (fn () => Ir.state "IrTst1" ~4);
val _ = \<^assert> (s_oob_lo = "error");
val _ = \<^assert> (String.isSubstring "out of range" o_oob_lo);

val (s_rm, _) = run_ir (fn () => Ir.remove "IrTst1");
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T2: reading a state does not disturb the repl -- no claim taken,
repeatable, the repl still steps afterwards.\<close>
ML \<open>
val (s_init2, _) = run_ir (fn () => Ir.init "IrTst2" [main]);
val _ = \<^assert> (s_init2 = "ok");
val (s1_2, _) = run_ir (fn () => Ir.step "IrTst2" "lemma True");
val _ = \<^assert> (s1_2 = "ok");

val (_, o_a) = run_ir (fn () => Ir.state "IrTst2" ~1);
val (_, o_b) = run_ir (fn () => Ir.state "IrTst2" ~1);
val _ = \<^assert> (o_a = o_b);

val (s_step2, _) = run_ir (fn () => Ir.step "IrTst2" "by simp");
val _ = \<^assert> (s_step2 = "ok");

val (s_rm2, _) = run_ir (fn () => Ir.remove "IrTst2");
val _ = \<^assert> (s_rm2 = "ok");
\<close>

section \<open>repl_show (plans/repl_show): T1..T2, plus the bridge suite's T2\<close>

text \<open>T1: the output carries origin, step count, and indices starting
at 0.\<close>
ML \<open>
val (s_init, _) = run_ir (fn () => Ir.init "IrTsh1" [main]);
val _ = \<^assert> (s_init = "ok");
val (s1, _) = run_ir (fn () => Ir.step "IrTsh1" "lemma True");
val _ = \<^assert> (s1 = "ok");
val (s2, _) = run_ir (fn () => Ir.step "IrTsh1" "by simp");
val _ = \<^assert> (s2 = "ok");

val (s_show, o_show) = run_ir (fn () => Ir.show "IrTsh1");
val _ = \<^assert> (s_show = "ok");
val _ = \<^assert> (String.isSubstring "2 steps" o_show);
val _ = \<^assert> (String.isSubstring "from" o_show);
val _ = \<^assert> (String.isSubstring "  0  " o_show);
val _ = \<^assert> (String.isSubstring "  1  " o_show);
val _ = \<^assert> (not (String.isSubstring "[stale]" o_show));

val (s_rm, _) = run_ir (fn () => Ir.remove "IrTsh1");
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T2: read-only and repeatable when the repl is idle.\<close>
ML \<open>
val (s_init2, _) = run_ir (fn () => Ir.init "IrTsh2" [main]);
val _ = \<^assert> (s_init2 = "ok");
val (s1_2, _) = run_ir (fn () => Ir.step "IrTsh2" "lemma True");
val _ = \<^assert> (s1_2 = "ok");

val (_, o_a) = run_ir (fn () => Ir.show "IrTsh2");
val (_, o_b) = run_ir (fn () => Ir.show "IrTsh2");
val _ = \<^assert> (o_a = o_b);

val (s_step2, _) = run_ir (fn () => Ir.step "IrTsh2" "by simp");
val _ = \<^assert> (s_step2 = "ok");

val (s_rm2, _) = run_ir (fn () => Ir.remove "IrTsh2");
val _ = \<^assert> (s_rm2 = "ok");
\<close>

text \<open>bridge T2: show on a BUSY repl errors "is busy", not a stale
read -- the case the sequential T2 above explicitly deferred as needing
real concurrency. Polls \<^ML>\<open>Ir.show\<close> directly (not \<^ML>\<open>Ir.repls\<close>),
matching mcp_bridge_tests.scala:279-291's own shape.\<close>
ML \<open>
val (s_init_bt2, _) = run_ir (fn () => Ir.init "IrShownBt2" [main]);
val _ = \<^assert> (s_init_bt2 = "ok");

val slow_bt2 =
  Future.fork (fn () =>
    run_ir (fn () => Ir.step "IrShownBt2" "ML_command \<open>OS.Process.sleep (seconds 2.0)\<close>"));

val busy_seen_bt2 =
  eventually_ir 1.5 (fn () =>
    case run_ir (fn () => Ir.show "IrShownBt2") of
      ("error", msg) => String.isSubstring "busy" msg
    | _ => false);
val _ = \<^assert> busy_seen_bt2;

val _ = Future.join slow_bt2;
val (s_show_bt2, _) = run_ir (fn () => Ir.show "IrShownBt2");
val _ = \<^assert> (s_show_bt2 = "ok");

val (s_rm_bt2, _) = run_ir (fn () => Ir.remove "IrShownBt2");
val _ = \<^assert> (s_rm_bt2 = "ok");
\<close>

section \<open>repl_text (plans/repl_text): T1..T2\<close>

text \<open>T1: byte fidelity end to end -- an Isabelle symbol, an inner
string with doubled spaces, and an embedded newline survive the round
trip verbatim.\<close>
ML \<open>
val (s_init, _) = run_ir (fn () => Ir.init "IrTtx1" [main]);
val _ = \<^assert> (s_init = "ok");

val step1 = "lemma \"x \<longrightarrow> x\"\n  by  simp";
val (s1, _) = run_ir (fn () => Ir.step "IrTtx1" step1);
val _ = \<^assert> (s1 = "ok");

val (s_text, o_text) = run_ir (fn () => Ir.text "IrTtx1");
val _ = \<^assert> (s_text = "ok");
val _ = \<^assert> (o_text = step1);

val (s_rm, _) = run_ir (fn () => Ir.remove "IrTtx1");
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T2: zero steps is ok with empty output, not an error.\<close>
ML \<open>
val (s_init2, _) = run_ir (fn () => Ir.init "IrTtx2" [main]);
val _ = \<^assert> (s_init2 = "ok");

val (s_text2, o_text2) = run_ir (fn () => Ir.text "IrTtx2");
val _ = \<^assert> (s_text2 = "ok");
val _ = \<^assert> (o_text2 = "");

val (s_rm2, _) = run_ir (fn () => Ir.remove "IrTtx2");
val _ = \<^assert> (s_rm2 = "ok");
\<close>

section \<open>repl_edit (plans/repl_edit): T1..T4\<close>

text \<open>T1: idx is a plain 0-based index, negatives are not supported --
out of range at both ends, 0 is valid.\<close>
ML \<open>
val (s_init, _) = run_ir (fn () => Ir.init "IrTed1" [main]);
val _ = \<^assert> (s_init = "ok");
val (s1, _) = run_ir (fn () => Ir.step "IrTed1" "lemma True");
val _ = \<^assert> (s1 = "ok");
val (s2, _) = run_ir (fn () => Ir.step "IrTed1" "by simp");
val _ = \<^assert> (s2 = "ok");

val (s_neg, o_neg) = run_ir (fn () => Ir.edit "IrTed1" ~1 "lemma True");
val _ = \<^assert> (s_neg = "error");
val _ = \<^assert> (String.isSubstring "out of range" o_neg);

val (s_hi, o_hi) = run_ir (fn () => Ir.edit "IrTed1" 2 "lemma True");
val _ = \<^assert> (s_hi = "error");
val _ = \<^assert> (String.isSubstring "out of range" o_hi);

val (s_ok, _) = run_ir (fn () => Ir.edit "IrTed1" 0 "lemma True");
val _ = \<^assert> (s_ok = "ok");

val (s_rm, _) = run_ir (fn () => Ir.remove "IrTed1");
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T2: failure atomicity -- a failing edit leaves the old step
untouched and the claim released.\<close>
ML \<open>
val (s_init2, _) = run_ir (fn () => Ir.init "IrTed2" [main]);
val _ = \<^assert> (s_init2 = "ok");
val (s1_2, _) = run_ir (fn () => Ir.step "IrTed2" "lemma True");
val _ = \<^assert> (s1_2 = "ok");
val (s2_2, _) = run_ir (fn () => Ir.step "IrTed2" "by simp");
val _ = \<^assert> (s2_2 = "ok");

val (_, o_before) = run_ir (fn () => Ir.show "IrTed2");

val (s_bad, _) = run_ir (fn () => Ir.edit "IrTed2" 0 "garbage_tactic");
val _ = \<^assert> (s_bad = "error");

val (_, o_after) = run_ir (fn () => Ir.show "IrTed2");
val _ = \<^assert> (o_before = o_after);
val _ = \<^assert> (not (String.isSubstring "[stale]" o_after));

(*the repl is still usable -- the claim was released even though the edit failed*)
val (s_next, _) = run_ir (fn () => Ir.step "IrTed2" "lemma \"1 = (1::nat)\"");
val _ = \<^assert> (s_next = "ok");

val (s_rm2, _) = run_ir (fn () => Ir.remove "IrTed2");
val _ = \<^assert> (s_rm2 = "ok");
\<close>

text \<open>T3: the reply names the count of subsequent steps as "marked
stale"; under this server's default (auto_replay=true), they are
replayed immediately, so \<^ML>\<open>Ir.show\<close> shows them clean right after.\<close>
ML \<open>
val (s_init3, _) = run_ir (fn () => Ir.init "IrTed3" [main]);
val _ = \<^assert> (s_init3 = "ok");
val (s1_3, _) = run_ir (fn () => Ir.step "IrTed3" "lemma True");
val _ = \<^assert> (s1_3 = "ok");
val (s2_3, _) = run_ir (fn () => Ir.step "IrTed3" "by simp");
val _ = \<^assert> (s2_3 = "ok");
val (s3_3, _) = run_ir (fn () => Ir.step "IrTed3" "lemma \"1 = (1::nat)\"");
val _ = \<^assert> (s3_3 = "ok");
val (s4_3, _) = run_ir (fn () => Ir.step "IrTed3" "by simp");
val _ = \<^assert> (s4_3 = "ok");

val (s_edit, o_edit) = run_ir (fn () => Ir.edit "IrTed3" 0 "lemma True");
val _ = \<^assert> (s_edit = "ok");
val _ = \<^assert> (String.isSubstring "3 subsequent steps marked stale" o_edit);

val (_, o_show) = run_ir (fn () => Ir.show "IrTed3");
val _ = \<^assert> (not (String.isSubstring "[stale]" o_show));
val _ = \<^assert> (String.isSubstring "4 steps" o_show);

val (s_rm3, _) = run_ir (fn () => Ir.remove "IrTed3");
val _ = \<^assert> (s_rm3 = "ok");
\<close>

text \<open>T4: editing a pinned repl marks the pin stale.\<close>
ML \<open>
val (s_init4, _) = run_ir (fn () => Ir.init "IrTed4" [main]);
val _ = \<^assert> (s_init4 = "ok");
val (s1_4, _) = run_ir (fn () => Ir.step "IrTed4" "lemma True");
val _ = \<^assert> (s1_4 = "ok");
val (s2_4, _) = run_ir (fn () => Ir.step "IrTed4" "by simp");
val _ = \<^assert> (s2_4 = "ok");

val (s_pin, _) = run_ir (fn () => Ir.pin "IrTed4");
val _ = \<^assert> (s_pin = "ok");
val (_, o_pinned) = run_ir (fn () => Ir.show "IrTed4");
val _ = \<^assert> (String.isSubstring "pinned)" o_pinned);
val _ = \<^assert> (not (String.isSubstring "pinned [stale]" o_pinned));

val (s_edit4, _) = run_ir (fn () => Ir.edit "IrTed4" 0 "lemma True");
val _ = \<^assert> (s_edit4 = "ok");
val (_, o_after4) = run_ir (fn () => Ir.show "IrTed4");
val _ = \<^assert> (String.isSubstring "pinned [stale]" o_after4);

val (s_rm4, _) = run_ir (fn () => Ir.remove "IrTed4");
val _ = \<^assert> (s_rm4 = "ok");
\<close>

section \<open>repl_replay (plans/repl_replay): T1..T4\<close>

text \<open>Genuinely stale steps are produced via repl_rebase: \<^ML>\<open>Ir.pin\<close>
bumps the pin's version on every call, so re-pinning a REPL that
another REPL was initialized "pin@..." from makes \<^ML>\<open>Ir.rebase\<close> see a
version mismatch and mark the dependent's steps stale, with NO replay.\<close>

text \<open>T1: no stale steps is an ok no-op, not an error.\<close>
ML \<open>
val (s_init, _) = run_ir (fn () => Ir.init "IrTrp1" [main]);
val _ = \<^assert> (s_init = "ok");
val (s1, _) = run_ir (fn () => Ir.step "IrTrp1" "lemma True");
val _ = \<^assert> (s1 = "ok");
val (s2, _) = run_ir (fn () => Ir.step "IrTrp1" "by simp");
val _ = \<^assert> (s2 = "ok");

val (s_replay, o_replay) = run_ir (fn () => Ir.replay "IrTrp1");
val _ = \<^assert> (s_replay = "ok");
val _ = \<^assert> (String.isSubstring "Replayed 0 stale steps" o_replay);

val (s_rm, _) = run_ir (fn () => Ir.remove "IrTrp1");
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T2: replay executes the stale suffix and re-chains states -- the
replayed state matches what a fresh run of the same script would give.\<close>
ML \<open>
val (s_a, _) = run_ir (fn () => Ir.init "IrTrp2a" [main]);
val _ = \<^assert> (s_a = "ok");
val (s_pin1, _) = run_ir (fn () => Ir.pin "IrTrp2a");
val _ = \<^assert> (s_pin1 = "ok");

val (s_b, _) = run_ir (fn () => Ir.init "IrTrp2b" ["pin@IrTrp2a"]);
val _ = \<^assert> (s_b = "ok");
val (s_bs, _) = run_ir (fn () => Ir.step "IrTrp2b" "lemma \"1 = (1::nat)\"");
val _ = \<^assert> (s_bs = "ok");
val (s_bs2, _) = run_ir (fn () => Ir.step "IrTrp2b" "by simp");
val _ = \<^assert> (s_bs2 = "ok");
val (_, o_before) = run_ir (fn () => Ir.state "IrTrp2b" ~1);

(*re-pinning IrTrp2a bumps its pin version*)
val (s_pin2, _) = run_ir (fn () => Ir.pin "IrTrp2a");
val _ = \<^assert> (s_pin2 = "ok");
val (s_rebase, o_rebase) = run_ir (fn () => Ir.rebase "IrTrp2b");
val _ = \<^assert> (s_rebase = "ok");
val _ = \<^assert> (String.isSubstring "2 steps marked stale" o_rebase);
val (_, o_staleshow) = run_ir (fn () => Ir.show "IrTrp2b");
val _ = \<^assert> (String.isSubstring "[stale]" o_staleshow);

val (s_replay, o_replay) = run_ir (fn () => Ir.replay "IrTrp2b");
val _ = \<^assert> (s_replay = "ok");
val _ = \<^assert> (String.isSubstring "Replayed 2 stale steps" o_replay);

val (_, o_after) = run_ir (fn () => Ir.state "IrTrp2b" ~1);
val _ = \<^assert> (o_before = o_after);
val (_, o_cleanshow) = run_ir (fn () => Ir.show "IrTrp2b");
val _ = \<^assert> (not (String.isSubstring "[stale]" o_cleanshow));

val (s_rm_b, _) = run_ir (fn () => Ir.remove "IrTrp2b");
val _ = \<^assert> (s_rm_b = "ok");
val (s_rm_a, _) = run_ir (fn () => Ir.remove "IrTrp2a");
val _ = \<^assert> (s_rm_a = "ok");
\<close>

text \<open>T3: a failure mid-replay surfaces as a status error, and ALL
steps that were stale beforehand are still stale afterward, not just
the failing one and its tail -- the internal claim/release mechanism's
atomicity releases the ORIGINAL claimed snapshot on exception,
discarding whatever the replay had built up so far.\<close>
ML \<open>
val (s_a3, _) = run_ir (fn () => Ir.init "IrTrp3a" [main]);
val _ = \<^assert> (s_a3 = "ok");
val (s_foo, _) =
  run_ir (fn () => Ir.step "IrTrp3a" "definition foo :: nat where \"foo = 3\"");
val _ = \<^assert> (s_foo = "ok");
val (s_pin1_3, _) = run_ir (fn () => Ir.pin "IrTrp3a");
val _ = \<^assert> (s_pin1_3 = "ok");

val (s_b3, _) = run_ir (fn () => Ir.init "IrTrp3b" ["pin@IrTrp3a"]);
val _ = \<^assert> (s_b3 = "ok");
val (s_b0, _) = run_ir (fn () => Ir.step "IrTrp3b" "lemma su0: True");
val _ = \<^assert> (s_b0 = "ok");
val (s_b0b, _) = run_ir (fn () => Ir.step "IrTrp3b" "by simp");
val _ = \<^assert> (s_b0b = "ok");
val (s_b1, _) = run_ir (fn () => Ir.step "IrTrp3b" "lemma \"foo = (3::nat)\"");
val _ = \<^assert> (s_b1 = "ok");
val (s_b1b, _) = run_ir (fn () => Ir.step "IrTrp3b" "by (simp add: foo_def)");
val _ = \<^assert> (s_b1b = "ok");

(*redefine IrTrp3a's step 0 to drop foo -- edit auto-replays immediately*)
val (s_edit3, _) =
  run_ir (fn () => Ir.edit "IrTrp3a" 0 "definition bar :: nat where \"bar = 3\"");
val _ = \<^assert> (s_edit3 = "ok");
val (s_pin2_3, _) = run_ir (fn () => Ir.pin "IrTrp3a");
val _ = \<^assert> (s_pin2_3 = "ok");

val (s_rebase3, o_rebase3) = run_ir (fn () => Ir.rebase "IrTrp3b");
val _ = \<^assert> (s_rebase3 = "ok");
val _ = \<^assert> (String.isSubstring "4 steps marked stale" o_rebase3);

val (s_replay3, _) = run_ir (fn () => Ir.replay "IrTrp3b");
val _ = \<^assert> (s_replay3 = "error");

val (_, o_show3) = run_ir (fn () => Ir.show "IrTrp3b");
val _ = \<^assert> (count_substring "[stale]" o_show3 = 4);

(*the repl is still inspectable, not corrupted*)
val (s_state3, _) = run_ir (fn () => Ir.state "IrTrp3b" 0);
val _ = \<^assert> (s_state3 = "ok");

val (s_rm_b3, _) = run_ir (fn () => Ir.remove "IrTrp3b");
val _ = \<^assert> (s_rm_b3 = "ok");
val (s_rm_a3, _) = run_ir (fn () => Ir.remove "IrTrp3a");
val _ = \<^assert> (s_rm_a3 = "ok");
\<close>

text \<open>T4: replay respects the repl's CURRENT timeout, not whatever was
in effect when the (now stale) step first succeeded.\<close>
ML \<open>
val (s_a4, _) = run_ir (fn () => Ir.init "IrTrp4a" [main]);
val _ = \<^assert> (s_a4 = "ok");
val (s_pin1_4, _) = run_ir (fn () => Ir.pin "IrTrp4a");
val _ = \<^assert> (s_pin1_4 = "ok");

val (s_b4, _) = run_ir (fn () => Ir.init "IrTrp4b" ["pin@IrTrp4a"]);
val _ = \<^assert> (s_b4 = "ok");
val (s_slow4, _) =
  run_ir (fn () => Ir.step "IrTrp4b" "ML_command \<open>OS.Process.sleep (seconds 2.0)\<close>");
val _ = \<^assert> (s_slow4 = "ok");

val (s_pin2_4, _) = run_ir (fn () => Ir.pin "IrTrp4a");
val _ = \<^assert> (s_pin2_4 = "ok");
val (s_rebase4, _) = run_ir (fn () => Ir.rebase "IrTrp4b");
val _ = \<^assert> (s_rebase4 = "ok");

val (s_to4, _) = run_ir (fn () => Ir.timeout "IrTrp4b" 1);
val _ = \<^assert> (s_to4 = "ok");

val (s_replay4, o_replay4) = run_ir (fn () => Ir.replay "IrTrp4b");
val _ = \<^assert> (s_replay4 = "error");
val _ = \<^assert> (String.isSubstring "timed out" o_replay4);

val (_, o_show4) = run_ir (fn () => Ir.show "IrTrp4b");
val _ = \<^assert> (String.isSubstring "[stale]" o_show4);

val (s_rm_b4, _) = run_ir (fn () => Ir.remove "IrTrp4b");
val _ = \<^assert> (s_rm_b4 = "ok");
val (s_rm_a4, _) = run_ir (fn () => Ir.remove "IrTrp4a");
val _ = \<^assert> (s_rm_a4 = "ok");
\<close>

section \<open>repl_truncate (plans/repl_truncate): T1..T3,T5, plus the bridge suite's T4\<close>

text \<open>T1: truncate's negative-index mapping (n+idx-1) differs from
state/fork's (n+1+idx) -- truncate -1 KEEPS n-1 steps, not n.\<close>
ML \<open>
val (s_init, _) = run_ir (fn () => Ir.init "IrTtc1" [main]);
val _ = \<^assert> (s_init = "ok");
val (s1, _) = run_ir (fn () => Ir.step "IrTtc1" "lemma su1: True");
val _ = \<^assert> (s1 = "ok");
val (s2, _) = run_ir (fn () => Ir.step "IrTtc1" "by simp");
val _ = \<^assert> (s2 = "ok");
val (s3, _) = run_ir (fn () => Ir.step "IrTtc1" "lemma su2: True");
val _ = \<^assert> (s3 = "ok");

val (s_tc1, _) = run_ir (fn () => Ir.truncate "IrTtc1" ~1);
val _ = \<^assert> (s_tc1 = "ok");
val (_, o_show1) = run_ir (fn () => Ir.show "IrTtc1");
val _ = \<^assert> (String.isSubstring "2 steps" o_show1);

val (s_tc0, _) = run_ir (fn () => Ir.truncate "IrTtc1" 0);
val _ = \<^assert> (s_tc0 = "ok");
val (_, o_show0) = run_ir (fn () => Ir.show "IrTtc1");
val _ = \<^assert> (String.isSubstring "1 steps" o_show0);

val (s_lo, o_lo) = run_ir (fn () => Ir.truncate "IrTtc1" ~99);
val _ = \<^assert> (s_lo = "error");
val _ = \<^assert> (String.isSubstring "out of range" o_lo);

val (s_hi, o_hi) = run_ir (fn () => Ir.truncate "IrTtc1" 99);
val _ = \<^assert> (s_hi = "error");
val _ = \<^assert> (String.isSubstring "out of range" o_hi);

val (s_rm, _) = run_ir (fn () => Ir.remove "IrTtc1");
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T2: the kept prefix is untouched -- no re-execution, no
staleness, and the repl continues stepping from the kept state.\<close>
ML \<open>
val (s_init2, _) = run_ir (fn () => Ir.init "IrTtc2" [main]);
val _ = \<^assert> (s_init2 = "ok");
val (s1_2, _) = run_ir (fn () => Ir.step "IrTtc2" "lemma su1: True");
val _ = \<^assert> (s1_2 = "ok");
val (s2_2, _) = run_ir (fn () => Ir.step "IrTtc2" "by simp");
val _ = \<^assert> (s2_2 = "ok");
val (s3_2, _) = run_ir (fn () => Ir.step "IrTtc2" "lemma su2: True");
val _ = \<^assert> (s3_2 = "ok");

val (s_tc, _) = run_ir (fn () => Ir.truncate "IrTtc2" ~1);
val _ = \<^assert> (s_tc = "ok");
val (_, o_show) = run_ir (fn () => Ir.show "IrTtc2");
val _ = \<^assert> (not (String.isSubstring "[stale]" o_show));

val (s_next, _) = run_ir (fn () => Ir.step "IrTtc2" "lemma su3: True");
val _ = \<^assert> (s_next = "ok");

val (s_rm2, _) = run_ir (fn () => Ir.remove "IrTtc2");
val _ = \<^assert> (s_rm2 = "ok");
\<close>

text \<open>T3: orphan removal -- forks at discarded states disappear, forks
at kept states survive.\<close>
ML \<open>
val (s_init3, _) = run_ir (fn () => Ir.init "IrTtc3" [main]);
val _ = \<^assert> (s_init3 = "ok");
val (s1_3, _) = run_ir (fn () => Ir.step "IrTtc3" "lemma su1: True");
val _ = \<^assert> (s1_3 = "ok");
val (s2_3, _) = run_ir (fn () => Ir.step "IrTtc3" "by simp");
val _ = \<^assert> (s2_3 = "ok");
val (s3_3, _) = run_ir (fn () => Ir.step "IrTtc3" "lemma su2: True");
val _ = \<^assert> (s3_3 = "ok");

val (s_f1, _) = run_ir (fn () => Ir.fork "IrTtc3" "IrTtc3f1" 1);
val _ = \<^assert> (s_f1 = "ok");
val (s_f3, _) = run_ir (fn () => Ir.fork "IrTtc3" "IrTtc3f3" 3);
val _ = \<^assert> (s_f3 = "ok");

(*truncate to idx=1 (keep steps 0,1): the orphan cutoff is si > idx, so
  fork@1 survives and fork@3 is orphaned*)
val (s_tc3, _) = run_ir (fn () => Ir.truncate "IrTtc3" 1);
val _ = \<^assert> (s_tc3 = "ok");

val (_, o_after3) = run_ir Ir.repls;
val _ = \<^assert> (String.isSubstring "IrTtc3f1" o_after3);
val _ = \<^assert> (not (String.isSubstring "IrTtc3f3" o_after3));

val (s_rm_f1, _) = run_ir (fn () => Ir.remove "IrTtc3f1");
val _ = \<^assert> (s_rm_f1 = "ok");
val (s_rm3, _) = run_ir (fn () => Ir.remove "IrTtc3");
val _ = \<^assert> (s_rm3 = "ok");
\<close>

text \<open>T5: pin goes stale on truncate.\<close>
ML \<open>
val (s_init5, _) = run_ir (fn () => Ir.init "IrTtc5" [main]);
val _ = \<^assert> (s_init5 = "ok");
val (s1_5, _) = run_ir (fn () => Ir.step "IrTtc5" "lemma su1: True");
val _ = \<^assert> (s1_5 = "ok");
val (s2_5, _) = run_ir (fn () => Ir.step "IrTtc5" "by simp");
val _ = \<^assert> (s2_5 = "ok");

val (s_pin5, _) = run_ir (fn () => Ir.pin "IrTtc5");
val _ = \<^assert> (s_pin5 = "ok");
val (s_tc5, _) = run_ir (fn () => Ir.truncate "IrTtc5" 0);
val _ = \<^assert> (s_tc5 = "ok");
val (_, o_show5) = run_ir (fn () => Ir.show "IrTtc5");
val _ = \<^assert> (String.isSubstring "pinned [stale]" o_show5);

val (s_rm5, _) = run_ir (fn () => Ir.remove "IrTtc5");
val _ = \<^assert> (s_rm5 = "ok");
\<close>

text \<open>bridge T4: a busy orphan blocks the WHOLE truncate, nothing is
half-removed.\<close>
ML \<open>
val (s_init_bt4, _) = run_ir (fn () => Ir.init "IrTrunc" [main]);
val _ = \<^assert> (s_init_bt4 = "ok");
val (s1_bt4, _) = run_ir (fn () => Ir.step "IrTrunc" "lemma trsu1: True");
val _ = \<^assert> (s1_bt4 = "ok");
val (s2_bt4, _) = run_ir (fn () => Ir.step "IrTrunc" "by simp");
val _ = \<^assert> (s2_bt4 = "ok");
val (s3_bt4, _) = run_ir (fn () => Ir.step "IrTrunc" "lemma trsu2: True");
val _ = \<^assert> (s3_bt4 = "ok");
val (s_fork_bt4, _) = run_ir (fn () => Ir.fork "IrTrunc" "IrTruncChild" 3);
val _ = \<^assert> (s_fork_bt4 = "ok");

val slow_bt4 =
  Future.fork (fn () =>
    run_ir (fn () => Ir.step "IrTruncChild" "ML_command \<open>OS.Process.sleep (seconds 2.0)\<close>"));

val busy_seen_bt4 =
  eventually_ir 1.5 (fn () =>
    let val (_, listing) = run_ir Ir.repls
    in String.isSubstring "IrTruncChild" listing andalso String.isSubstring "busy" listing end);
val _ = \<^assert> busy_seen_bt4;

val (s_tc_blocked, o_tc_blocked) = run_ir (fn () => Ir.truncate "IrTrunc" 1);
val _ = \<^assert> (s_tc_blocked = "error");
val _ = \<^assert> (String.isSubstring "busy" o_tc_blocked);

val _ = Future.join slow_bt4;
val (s_tc_bt4, _) = run_ir (fn () => Ir.truncate "IrTrunc" 1);
val _ = \<^assert> (s_tc_bt4 = "ok");
val (_, o_after_bt4) = run_ir Ir.repls;
val _ = \<^assert> (not (String.isSubstring "IrTruncChild" o_after_bt4));

val (s_rm_bt4, _) = run_ir (fn () => Ir.remove "IrTrunc");
val _ = \<^assert> (s_rm_bt4 = "ok");
\<close>

section \<open>repl_back (plans/repl_back): T1..T2\<close>

text \<open>T1: back is truncate -1's sugar -- equivalent behavior including
the out-of-range edge on a 0-step repl.\<close>
ML \<open>
val (s_init, _) = run_ir (fn () => Ir.init "IrTbk1" [main]);
val _ = \<^assert> (s_init = "ok");
val (s1, _) = run_ir (fn () => Ir.step "IrTbk1" "lemma sb1: True");
val _ = \<^assert> (s1 = "ok");
val (s2, _) = run_ir (fn () => Ir.step "IrTbk1" "by simp");
val _ = \<^assert> (s2 = "ok");

val (s_b1, _) = run_ir (fn () => Ir.back "IrTbk1");
val _ = \<^assert> (s_b1 = "ok");
val (_, o_show1) = run_ir (fn () => Ir.show "IrTbk1");
val _ = \<^assert> (String.isSubstring "1 steps" o_show1);

val (s_b0, _) = run_ir (fn () => Ir.back "IrTbk1");
val _ = \<^assert> (s_b0 = "ok");
val (_, o_show0) = run_ir (fn () => Ir.show "IrTbk1");
val _ = \<^assert> (String.isSubstring "0 steps" o_show0);

val (s_berr, o_berr) = run_ir (fn () => Ir.back "IrTbk1");
val _ = \<^assert> (s_berr = "error");
val _ = \<^assert> (String.isSubstring "out of range" o_berr);

val (s_rm, _) = run_ir (fn () => Ir.remove "IrTbk1");
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T2: the description's trap -- back after a FAILED step discards
the last GOOD step, since a failed repl_step never joins the repl.\<close>
ML \<open>
val (s_init2, _) = run_ir (fn () => Ir.init "IrTbk2" [main]);
val _ = \<^assert> (s_init2 = "ok");
val (s1_2, _) = run_ir (fn () => Ir.step "IrTbk2" "lemma sb2: True");
val _ = \<^assert> (s1_2 = "ok");
val (s_fail, _) = run_ir (fn () => Ir.step "IrTbk2" "this is not isar");
val _ = \<^assert> (s_fail = "error");

val (s_b, _) = run_ir (fn () => Ir.back "IrTbk2");
val _ = \<^assert> (s_b = "ok");
val (_, o_show) = run_ir (fn () => Ir.show "IrTbk2");
val _ = \<^assert> (String.isSubstring "0 steps" o_show);

val (s_rm2, _) = run_ir (fn () => Ir.remove "IrTbk2");
val _ = \<^assert> (s_rm2 = "ok");
\<close>

section \<open>repl_merge (plans/repl_merge): T1..T3, plus the bridge suite's T4\<close>

text \<open>T1(a): fork at the tip -- merge appends the child's concatenated
text as one new step.\<close>
ML \<open>
val (s_init, _) = run_ir (fn () => Ir.init "IrTmg1a" [main]);
val _ = \<^assert> (s_init = "ok");
val (s0, _) = run_ir (fn () => Ir.step "IrTmg1a" "lemma tmg1a: True");
val _ = \<^assert> (s0 = "ok");
val (s1, _) = run_ir (fn () => Ir.step "IrTmg1a" "by simp");
val _ = \<^assert> (s1 = "ok");

val (s_f, _) = run_ir (fn () => Ir.fork "IrTmg1a" "IrTmg1aC" ~1);
val _ = \<^assert> (s_f = "ok");
val (sc0, _) = run_ir (fn () => Ir.step "IrTmg1aC" "lemma tmg1c: True");
val _ = \<^assert> (sc0 = "ok");
val (sc1, _) = run_ir (fn () => Ir.step "IrTmg1aC" "by simp");
val _ = \<^assert> (sc1 = "ok");

val (s_mg, o_mg) = run_ir (fn () => Ir.merge "IrTmg1aC");
val _ = \<^assert> (s_mg = "ok");
val _ = \<^assert> (String.isSubstring "appended as new step" o_mg);

val (_, o_show) = run_ir (fn () => Ir.show "IrTmg1a");
val _ = \<^assert> (String.isSubstring "3 steps" o_show);
val (_, o_text) = run_ir (fn () => Ir.text "IrTmg1a");
val _ = \<^assert> (String.isSubstring "lemma tmg1c: True\nby simp" o_text);

(*the child was deleted by the merge*)
val (_, o_repls) = run_ir Ir.repls;
val _ = \<^assert> (not (String.isSubstring "IrTmg1aC" o_repls));

val (s_rm, _) = run_ir (fn () => Ir.remove "IrTmg1a");
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T1(b): fork at an interior state -- merge replaces the step at
the fork index.\<close>
ML \<open>
val (s_init1b, _) = run_ir (fn () => Ir.init "IrTmg1b" [main]);
val _ = \<^assert> (s_init1b = "ok");
val (s0_1b, _) = run_ir (fn () => Ir.step "IrTmg1b" "lemma tmg1b1: True");
val _ = \<^assert> (s0_1b = "ok");
val (s1_1b, _) = run_ir (fn () => Ir.step "IrTmg1b" "by simp");
val _ = \<^assert> (s1_1b = "ok");
val (s2_1b, _) = run_ir (fn () => Ir.step "IrTmg1b" "lemma tmg1b2: True");
val _ = \<^assert> (s2_1b = "ok");

val (s_f1b, _) = run_ir (fn () => Ir.fork "IrTmg1b" "IrTmg1bC" 1);
val _ = \<^assert> (s_f1b = "ok");
val (sc0_1b, _) = run_ir (fn () => Ir.step "IrTmg1bC" "by auto");
val _ = \<^assert> (sc0_1b = "ok");

val (s_mg1b, o_mg1b) = run_ir (fn () => Ir.merge "IrTmg1bC");
val _ = \<^assert> (s_mg1b = "ok");
val _ = \<^assert> (String.isSubstring "replaced step 1" o_mg1b);

val (_, o_show1b) = run_ir (fn () => Ir.show "IrTmg1b");
val _ = \<^assert> (String.isSubstring "3 steps" o_show1b);
val (_, o_text1b) = run_ir (fn () => Ir.text "IrTmg1b");
val _ = \<^assert> (String.isSubstring "by auto" o_text1b);

val (s_rm1b, _) = run_ir (fn () => Ir.remove "IrTmg1b");
val _ = \<^assert> (s_rm1b = "ok");
\<close>

text \<open>T2: merging a REPL that is not a sub-REPL errors, and the REPL
itself is unaffected -- it still steps.\<close>
ML \<open>
val (s_init2, _) = run_ir (fn () => Ir.init "IrTmg2" [main]);
val _ = \<^assert> (s_init2 = "ok");

val (s_mg2, o_mg2) = run_ir (fn () => Ir.merge "IrTmg2");
val _ = \<^assert> (s_mg2 = "error");
val _ = \<^assert> (String.isSubstring "is not a sub-REPL" o_mg2);

val (s_step2, _) = run_ir (fn () => Ir.step "IrTmg2" "lemma tmg2: True");
val _ = \<^assert> (s_step2 = "ok");

val (s_rm2, _) = run_ir (fn () => Ir.remove "IrTmg2");
val _ = \<^assert> (s_rm2 = "ok");
\<close>

text \<open>T3: a merge that fails in the parent leaves BOTH REPLs alive and
unchanged -- the parent's tip goal is edited after the fork so the
child's tactic no longer applies at merge time.\<close>
ML \<open>
val (s_init3, _) = run_ir (fn () => Ir.init "IrTmg3" [main]);
val _ = \<^assert> (s_init3 = "ok");
val (s0_3, _) = run_ir (fn () => Ir.step "IrTmg3" "lemma tmg3: True");
val _ = \<^assert> (s0_3 = "ok");

val (s_f3, _) = run_ir (fn () => Ir.fork "IrTmg3" "IrTmg3C" 1);
val _ = \<^assert> (s_f3 = "ok");
val (sc0_3, _) = run_ir (fn () => Ir.step "IrTmg3C" "by simp");
val _ = \<^assert> (sc0_3 = "ok");

(*moves the goal out from under the child's fork-time tactic*)
val (s_ed3, _) =
  run_ir (fn () => Ir.edit "IrTmg3" 0 "lemma tmg3: \"(1::nat) + 1 = 3\"");
val _ = \<^assert> (s_ed3 = "ok");

val (s_mg3, _) = run_ir (fn () => Ir.merge "IrTmg3C");
val _ = \<^assert> (s_mg3 = "error");

val (_, o_repls3) = run_ir Ir.repls;
val _ = \<^assert> (String.isSubstring "IrTmg3" o_repls3);
val _ = \<^assert> (String.isSubstring "IrTmg3C" o_repls3);

(*both still answer show/step -- neither was left half-mutated*)
val (_, o_show3) = run_ir (fn () => Ir.show "IrTmg3");
val _ = \<^assert> (String.isSubstring "1 steps" o_show3);
val (s_cstep3, _) = run_ir (fn () => Ir.step "IrTmg3C" "ML_command \<open>()\<close>");
val _ = \<^assert> (s_cstep3 = "ok");

val (s_rm_c3, _) = run_ir (fn () => Ir.remove "IrTmg3C");
val _ = \<^assert> (s_rm_c3 = "ok");
val (s_rm3, _) = run_ir (fn () => Ir.remove "IrTmg3");
val _ = \<^assert> (s_rm3 = "ok");
\<close>

text \<open>bridge T4: a busy PARENT blocks merge without corrupting the
child.\<close>
ML \<open>
val (s_init_bt4, _) = run_ir (fn () => Ir.init "IrMPar" [main]);
val _ = \<^assert> (s_init_bt4 = "ok");
val (s_fork_bt4, _) = run_ir (fn () => Ir.fork "IrMPar" "IrMChild" 0);
val _ = \<^assert> (s_fork_bt4 = "ok");
val (s_c0_bt4, _) = run_ir (fn () => Ir.step "IrMChild" "lemma mmg1: True");
val _ = \<^assert> (s_c0_bt4 = "ok");
val (s_c1_bt4, _) = run_ir (fn () => Ir.step "IrMChild" "by simp");
val _ = \<^assert> (s_c1_bt4 = "ok");

val slow_bt4 =
  Future.fork (fn () =>
    run_ir (fn () => Ir.step "IrMPar" "ML_command \<open>OS.Process.sleep (seconds 2.0)\<close>"));

val busy_seen_bt4 =
  eventually_ir 1.5 (fn () =>
    let val (_, listing) = run_ir Ir.repls
    in String.isSubstring "IrMPar" listing andalso String.isSubstring "busy" listing end);
val _ = \<^assert> busy_seen_bt4;

val (s_mg_blocked, o_mg_blocked) = run_ir (fn () => Ir.merge "IrMChild");
val _ = \<^assert> (s_mg_blocked = "error");
val _ = \<^assert> (String.isSubstring "busy" o_mg_blocked);

val _ = Future.join slow_bt4;
val (s_mg_bt4, _) = run_ir (fn () => Ir.merge "IrMChild");
val _ = \<^assert> (s_mg_bt4 = "ok");
val (_, o_after_bt4) = run_ir Ir.repls;
val _ = \<^assert> (not (String.isSubstring "IrMChild" o_after_bt4));

val (s_rm_bt4, _) = run_ir (fn () => Ir.remove "IrMPar");
val _ = \<^assert> (s_rm_bt4 = "ok");
\<close>

section \<open>repl_timeout (plans/repl_timeout): T1..T3\<close>

text \<open>NOTE: the bridge suite's "repl_timeout T4 -- the full chain sets
and reports a per-REPL timeout" (mcp_bridge_tests.scala:374-381) checks
the set-then-show round trip at \<open>secs=5\<close>. That claim is a strict subset
of T3 below (which covers 5s AND the 0=unlimited rendering case), so
there is nothing unique to port from it -- recorded here rather than
silently dropped without comment.\<close>

text \<open>T1: the set value actually bounds steps -- secs=1 makes a
sleeping step fail, secs=0 makes the same step run to completion.\<close>
ML \<open>
val (s_init, _) = run_ir (fn () => Ir.init "IrTto1" [main]);
val _ = \<^assert> (s_init = "ok");

val (s_to1, _) = run_ir (fn () => Ir.timeout "IrTto1" 1);
val _ = \<^assert> (s_to1 = "ok");
val (s_slow, o_slow) =
  run_ir (fn () => Ir.step "IrTto1" "ML_command \<open>OS.Process.sleep (seconds 2.0)\<close>");
val _ = \<^assert> (s_slow = "error");
val _ = \<^assert> (String.isSubstring "timed out" o_slow);

val (s_to0, _) = run_ir (fn () => Ir.timeout "IrTto1" 0);
val _ = \<^assert> (s_to0 = "ok");
val (s_ok, _) =
  run_ir (fn () => Ir.step "IrTto1" "ML_command \<open>OS.Process.sleep (seconds 2.0)\<close>");
val _ = \<^assert> (s_ok = "ok");

val (s_rm, _) = run_ir (fn () => Ir.remove "IrTto1");
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T2: negative secs -- exec_text only bounds when timeout_secs > 0,
so a negative value behaves like 0 (unlimited) for STEP EXECUTION, but
show's rendering only special-cases exactly 0 as "unlimited"; a
negative value prints via SML's \<^ML>\<open>string_of_int\<close>, spelled with a
tilde.\<close>
ML \<open>
val (s_init2, _) = run_ir (fn () => Ir.init "IrTto2" [main]);
val _ = \<^assert> (s_init2 = "ok");

val (s_to2, _) = run_ir (fn () => Ir.timeout "IrTto2" ~1);
val _ = \<^assert> (s_to2 = "ok");
val (s_slow2, _) =
  run_ir (fn () => Ir.step "IrTto2" "ML_command \<open>OS.Process.sleep (seconds 2.0)\<close>");
val _ = \<^assert> (s_slow2 = "ok");

val (_, o_show2) = run_ir (fn () => Ir.show "IrTto2");
val _ = \<^assert> (String.isSubstring "timeout=~1s" o_show2);

val (s_rm2, _) = run_ir (fn () => Ir.remove "IrTto2");
val _ = \<^assert> (s_rm2 = "ok");
\<close>

text \<open>T3: show reflects the timeout, and 0 renders as "unlimited"
rather than "0s".\<close>
ML \<open>
val (s_init3, _) = run_ir (fn () => Ir.init "IrTto3" [main]);
val _ = \<^assert> (s_init3 = "ok");

val (s_to5, _) = run_ir (fn () => Ir.timeout "IrTto3" 5);
val _ = \<^assert> (s_to5 = "ok");
val (_, o_show5) = run_ir (fn () => Ir.show "IrTto3");
val _ = \<^assert> (String.isSubstring "timeout=5s" o_show5);

val (s_to0_3, _) = run_ir (fn () => Ir.timeout "IrTto3" 0);
val _ = \<^assert> (s_to0_3 = "ok");
val (_, o_show0_3) = run_ir (fn () => Ir.show "IrTto3");
val _ = \<^assert> (String.isSubstring "timeout=unlimited" o_show0_3);

val (s_rm3, _) = run_ir (fn () => Ir.remove "IrTto3");
val _ = \<^assert> (s_rm3 = "ok");
\<close>

section \<open>repl_pin (plans/repl_pin): T1..T4\<close>

text \<open>T1: pinning mid-proof is refused with the engine's message;
finishing the proof first makes it pin cleanly.\<close>
ML \<open>
val (s_init, _) = run_ir (fn () => Ir.init "IrTpn1" [main]);
val _ = \<^assert> (s_init = "ok");
val (s_step, _) = run_ir (fn () => Ir.step "IrTpn1" "lemma True");
val _ = \<^assert> (s_step = "ok");

val (s_pin_mid, o_pin_mid) = run_ir (fn () => Ir.pin "IrTpn1");
val _ = \<^assert> (s_pin_mid = "error");
val _ = \<^assert> (String.isSubstring "in a proof state" o_pin_mid);

val (s_fin, _) = run_ir (fn () => Ir.step "IrTpn1" "by simp");
val _ = \<^assert> (s_fin = "ok");
val (s_pin, _) = run_ir (fn () => Ir.pin "IrTpn1");
val _ = \<^assert> (s_pin = "ok");

val (s_rm, _) = run_ir (fn () => Ir.remove "IrTpn1");
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T2: the pin round trip -- A defines a constant, pins, B is
init'd from pin@A and can use A's definition, and B's origin prints
"pin@A".\<close>
ML \<open>
val (s_a, _) = run_ir (fn () => Ir.init "IrTpn2A" [main]);
val _ = \<^assert> (s_a = "ok");
val (s_def, _) = run_ir (fn () =>
  Ir.step "IrTpn2A" "definition pn2_const :: nat where \"pn2_const = 42\"");
val _ = \<^assert> (s_def = "ok");
val (s_pin, _) = run_ir (fn () => Ir.pin "IrTpn2A");
val _ = \<^assert> (s_pin = "ok");

val (s_b, _) = run_ir (fn () => Ir.init "IrTpn2B" ["pin@IrTpn2A"]);
val _ = \<^assert> (s_b = "ok");
val (_, o_show_b) = run_ir (fn () => Ir.show "IrTpn2B");
val _ = \<^assert> (String.isSubstring "pin@IrTpn2A" o_show_b);

val (s_use, _) = run_ir (fn () =>
  Ir.step "IrTpn2B" "lemma \"pn2_const = 42\" by (simp add: pn2_const_def)");
val _ = \<^assert> (s_use = "ok");

val (s_rm_b, _) = run_ir (fn () => Ir.remove "IrTpn2B");
val _ = \<^assert> (s_rm_b = "ok");
val (s_rm_a, _) = run_ir (fn () => Ir.remove "IrTpn2A");
val _ = \<^assert> (s_rm_a = "ok");
\<close>

text \<open>T3: staleness -- mutating A after the pin marks it stale, a NEW
init from pin@A errors, existing dependents are untouched, and
re-pinning clears the staleness for future inits.\<close>
ML \<open>
val (s_a3, _) = run_ir (fn () => Ir.init "IrTpn3A" [main]);
val _ = \<^assert> (s_a3 = "ok");
val (s_pin3, _) = run_ir (fn () => Ir.pin "IrTpn3A");
val _ = \<^assert> (s_pin3 = "ok");

val (s_b3, _) = run_ir (fn () => Ir.init "IrTpn3B" ["pin@IrTpn3A"]);
val _ = \<^assert> (s_b3 = "ok");

(*any mutation on A marks its pin stale, even an unrelated step*)
val (s_step_a3, _) = run_ir (fn () => Ir.step "IrTpn3A" "lemma tpn3: True by simp");
val _ = \<^assert> (s_step_a3 = "ok");
val (_, o_show_a3) = run_ir (fn () => Ir.show "IrTpn3A");
val _ = \<^assert> (String.isSubstring "pinned [stale]" o_show_a3);

val (s_c3, o_c3) = run_ir (fn () => Ir.init "IrTpn3C" ["pin@IrTpn3A"]);
val _ = \<^assert> (s_c3 = "error");
val _ = \<^assert> (String.isSubstring "is stale" o_c3);

(*B, initialized before the staleness, still steps normally*)
val (s_b_step3, _) = run_ir (fn () => Ir.step "IrTpn3B" "lemma tpn3b: True");
val _ = \<^assert> (s_b_step3 = "ok");

val (s_repin3, _) = run_ir (fn () => Ir.pin "IrTpn3A");
val _ = \<^assert> (s_repin3 = "ok");
val (s_c2_3, _) = run_ir (fn () => Ir.init "IrTpn3C" ["pin@IrTpn3A"]);
val _ = \<^assert> (s_c2_3 = "ok");

val (s_rm_c3, _) = run_ir (fn () => Ir.remove "IrTpn3C");
val _ = \<^assert> (s_rm_c3 = "ok");
val (s_rm_b3, _) = run_ir (fn () => Ir.remove "IrTpn3B");
val _ = \<^assert> (s_rm_b3 = "ok");
val (s_rm_a3, _) = run_ir (fn () => Ir.remove "IrTpn3A");
val _ = \<^assert> (s_rm_a3 = "ok");
\<close>

text \<open>T4: re-pinning bumps the version -- pinning twice both succeed.\<close>
ML \<open>
val (s_init4, _) = run_ir (fn () => Ir.init "IrTpn4" [main]);
val _ = \<^assert> (s_init4 = "ok");
val (s_pin1_4, _) = run_ir (fn () => Ir.pin "IrTpn4");
val _ = \<^assert> (s_pin1_4 = "ok");
val (s_pin2_4, _) = run_ir (fn () => Ir.pin "IrTpn4");
val _ = \<^assert> (s_pin2_4 = "ok");

val (s_rm4, _) = run_ir (fn () => Ir.remove "IrTpn4");
val _ = \<^assert> (s_rm4 = "ok");
\<close>

section \<open>repl_unpin (plans/repl_unpin): T1..T2\<close>

text \<open>NOTE: the bridge suite's "repl_unpin T3 -- the pin/unpin round
trip" (mcp_bridge_tests.scala:476-484) is a bare pin/show/unpin/show
sequence, strictly subsumed by T2 below (which additionally covers the
dependent-blocking case) -- nothing unique to port.\<close>

text \<open>T1: unpinning without a pin errors naming the repl.\<close>
ML \<open>
val (s_init, _) = run_ir (fn () => Ir.init "IrTup1" [main]);
val _ = \<^assert> (s_init = "ok");
val (s_unpin, o_unpin) = run_ir (fn () => Ir.unpin "IrTup1");
val _ = \<^assert> (s_unpin = "error");
val _ = \<^assert> (String.isSubstring "is not pinned" o_unpin);

val (s_rm, _) = run_ir (fn () => Ir.remove "IrTup1");
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T2: dependents block unpin; removing the dependent frees it; A's
steps are unchanged throughout.\<close>
ML \<open>
val (s_a, _) = run_ir (fn () => Ir.init "IrTup2A" [main]);
val _ = \<^assert> (s_a = "ok");
val (s_step_a, _) = run_ir (fn () => Ir.step "IrTup2A" "lemma tup2: True by simp");
val _ = \<^assert> (s_step_a = "ok");
val (s_pin, _) = run_ir (fn () => Ir.pin "IrTup2A");
val _ = \<^assert> (s_pin = "ok");
val (s_b, _) = run_ir (fn () => Ir.init "IrTup2B" ["pin@IrTup2A"]);
val _ = \<^assert> (s_b = "ok");

val (s_unpin_blocked, o_unpin_blocked) = run_ir (fn () => Ir.unpin "IrTup2A");
val _ = \<^assert> (s_unpin_blocked = "error");
val _ = \<^assert> (String.isSubstring "depend" o_unpin_blocked);

val (_, o_show_before) = run_ir (fn () => Ir.show "IrTup2A");
val _ = \<^assert> (String.isSubstring "1 steps" o_show_before);

val (s_rm_b, _) = run_ir (fn () => Ir.remove "IrTup2B");
val _ = \<^assert> (s_rm_b = "ok");
val (s_unpin, _) = run_ir (fn () => Ir.unpin "IrTup2A");
val _ = \<^assert> (s_unpin = "ok");

val (_, o_show_after) = run_ir (fn () => Ir.show "IrTup2A");
val _ = \<^assert> (not (String.isSubstring "pinned" o_show_after));
val _ = \<^assert> (String.isSubstring "1 steps" o_show_after);

val (s_rm_a, _) = run_ir (fn () => Ir.remove "IrTup2A");
val _ = \<^assert> (s_rm_a = "ok");
\<close>

section \<open>repl_rebase (plans/repl_rebase): T1..T4\<close>

text \<open>T1: the whole point, end to end -- A gains a definition and
re-pins; rebase B marks its steps stale; replay lets a NEW step in B
use A's new definition; a second rebase with nothing changed says
"already up to date".\<close>
ML \<open>
val (s_a, _) = run_ir (fn () => Ir.init "IrTrb1A" [main]);
val _ = \<^assert> (s_a = "ok");
val (s_pin1, _) = run_ir (fn () => Ir.pin "IrTrb1A");
val _ = \<^assert> (s_pin1 = "ok");

val (s_b, _) = run_ir (fn () => Ir.init "IrTrb1B" ["pin@IrTrb1A"]);
val _ = \<^assert> (s_b = "ok");
val (s_b_step, _) = run_ir (fn () => Ir.step "IrTrb1B" "lemma trb1b: True by simp");
val _ = \<^assert> (s_b_step = "ok");

val (s_def, _) = run_ir (fn () =>
  Ir.step "IrTrb1A" "definition trb1_const :: nat where \"trb1_const = 7\"");
val _ = \<^assert> (s_def = "ok");
val (s_pin2, _) = run_ir (fn () => Ir.pin "IrTrb1A");
val _ = \<^assert> (s_pin2 = "ok");

val (s_rebase, o_rebase) = run_ir (fn () => Ir.rebase "IrTrb1B");
val _ = \<^assert> (s_rebase = "ok");
val _ = \<^assert> (String.isSubstring "marked stale" o_rebase);

val (s_replay, _) = run_ir (fn () => Ir.replay "IrTrb1B");
val _ = \<^assert> (s_replay = "ok");
val (s_new_step, _) = run_ir (fn () =>
  Ir.step "IrTrb1B" "lemma \"trb1_const = 7\" by (simp add: trb1_const_def)");
val _ = \<^assert> (s_new_step = "ok");

val (s_rebase_again, o_rebase_again) = run_ir (fn () => Ir.rebase "IrTrb1B");
val _ = \<^assert> (s_rebase_again = "ok");
val _ = \<^assert> (String.isSubstring "already up to date" o_rebase_again);

val (s_rm_b, _) = run_ir (fn () => Ir.remove "IrTrb1B");
val _ = \<^assert> (s_rm_b = "ok");
val (s_rm_a, _) = run_ir (fn () => Ir.remove "IrTrb1A");
val _ = \<^assert> (s_rm_a = "ok");
\<close>

text \<open>T2: a stale pin blocks rebase with the resolve_spec error.\<close>
ML \<open>
val (s_a2, _) = run_ir (fn () => Ir.init "IrTrb2A" [main]);
val _ = \<^assert> (s_a2 = "ok");
val (s_pin2_2, _) = run_ir (fn () => Ir.pin "IrTrb2A");
val _ = \<^assert> (s_pin2_2 = "ok");
val (s_b2, _) = run_ir (fn () => Ir.init "IrTrb2B" ["pin@IrTrb2A"]);
val _ = \<^assert> (s_b2 = "ok");

(*mutate A WITHOUT re-pinning -- its pin is now stale*)
val (s_step_a2, _) = run_ir (fn () => Ir.step "IrTrb2A" "lemma trb2: True");
val _ = \<^assert> (s_step_a2 = "ok");

val (s_rebase2, o_rebase2) = run_ir (fn () => Ir.rebase "IrTrb2B");
val _ = \<^assert> (s_rebase2 = "error");
val _ = \<^assert> (String.isSubstring "is stale" o_rebase2);

val (s_rm_b2, _) = run_ir (fn () => Ir.remove "IrTrb2B");
val _ = \<^assert> (s_rm_b2 = "ok");
val (s_rm_a2, _) = run_ir (fn () => Ir.remove "IrTrb2A");
val _ = \<^assert> (s_rm_a2 = "ok");
\<close>

text \<open>T3: non-From_Theory origins (forks) are refused.\<close>
ML \<open>
val (s_a3, _) = run_ir (fn () => Ir.init "IrTrb3A" [main]);
val _ = \<^assert> (s_a3 = "ok");
val (s_pin3, _) = run_ir (fn () => Ir.pin "IrTrb3A");
val _ = \<^assert> (s_pin3 = "ok");
val (s_b3, _) = run_ir (fn () => Ir.init "IrTrb3B" ["pin@IrTrb3A"]);
val _ = \<^assert> (s_b3 = "ok");
val (s_f3, _) = run_ir (fn () => Ir.fork "IrTrb3B" "IrTrb3C" 0);
val _ = \<^assert> (s_f3 = "ok");

val (s_rebase3, o_rebase3) = run_ir (fn () => Ir.rebase "IrTrb3C");
val _ = \<^assert> (s_rebase3 = "error");
val _ = \<^assert> (String.isSubstring "does not support rebase" o_rebase3);

val (s_rm_c3, _) = run_ir (fn () => Ir.remove "IrTrb3C");
val _ = \<^assert> (s_rm_c3 = "ok");
val (s_rm_b3, _) = run_ir (fn () => Ir.remove "IrTrb3B");
val _ = \<^assert> (s_rm_b3 = "ok");
val (s_rm_a3, _) = run_ir (fn () => Ir.remove "IrTrb3A");
val _ = \<^assert> (s_rm_a3 = "ok");
\<close>

text \<open>T4: rebase does NOT replay -- state is unchanged until replay
runs, only the stale marks and the base theory move.\<close>
ML \<open>
val (s_a4, _) = run_ir (fn () => Ir.init "IrTrb4A" [main]);
val _ = \<^assert> (s_a4 = "ok");
val (s_pin1_4, _) = run_ir (fn () => Ir.pin "IrTrb4A");
val _ = \<^assert> (s_pin1_4 = "ok");
val (s_b4, _) = run_ir (fn () => Ir.init "IrTrb4B" ["pin@IrTrb4A"]);
val _ = \<^assert> (s_b4 = "ok");
val (s_b_step4, _) = run_ir (fn () => Ir.step "IrTrb4B" "lemma trb4b: True");
val _ = \<^assert> (s_b_step4 = "ok");
val (_, o_state_before4) = run_ir (fn () => Ir.state "IrTrb4B" ~1);

val (s_def4, _) = run_ir (fn () =>
  Ir.step "IrTrb4A" "definition trb4_const :: nat where \"trb4_const = 3\"");
val _ = \<^assert> (s_def4 = "ok");
val (s_pin2_4, _) = run_ir (fn () => Ir.pin "IrTrb4A");
val _ = \<^assert> (s_pin2_4 = "ok");

val (s_rebase4, _) = run_ir (fn () => Ir.rebase "IrTrb4B");
val _ = \<^assert> (s_rebase4 = "ok");
val (_, o_show4) = run_ir (fn () => Ir.show "IrTrb4B");
val _ = \<^assert> (count_substring "[stale]" o_show4 = 1);

(*state -1 is UNCHANGED pre-replay*)
val (_, o_state_after4) = run_ir (fn () => Ir.state "IrTrb4B" ~1);
val _ = \<^assert> (o_state_after4 = o_state_before4);

val (s_replay4, _) = run_ir (fn () => Ir.replay "IrTrb4B");
val _ = \<^assert> (s_replay4 = "ok");

val (s_rm_b4, _) = run_ir (fn () => Ir.remove "IrTrb4B");
val _ = \<^assert> (s_rm_b4 = "ok");
val (s_rm_a4, _) = run_ir (fn () => Ir.remove "IrTrb4A");
val _ = \<^assert> (s_rm_a4 = "ok");
\<close>

section \<open>sledgehammer (plans/sledgehammer): T2, plus the bridge suite's T3/T4\<close>

text \<open>T2: sledgehammer requires the REPL to be mid-proof
(\<^ML>\<open>Toplevel.proof_of\<close> raises on a theory-level state).\<close>
ML \<open>
val (s_init, _) = run_ir (fn () => Ir.init "IrTsh2sh" [main]);
val _ = \<^assert> (s_init = "ok");
val (s_sh, _) = run_ir (fn () => Ir.sledgehammer "IrTsh2sh" 15);
val _ = \<^assert> (s_sh = "error");
val (s_rm, _) = run_ir (fn () => Ir.remove "IrTsh2sh");
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>bridge T3: happy path finds a proof for a trivial goal, tolerant
of prover flakiness. This is the one claim in this whole theory that is
NOT really about \<^ML_structure>\<open>Ir\<close> at all -- it tests whether
Sledgehammer ITSELF (HOL's own bundled ATP-integration tool, called
BY \<^ML>\<open>Ir.sledgehammer\<close> but not part of ir.ML's own logic) can find
a proof, one layer further removed from this repo's own code than
anything else translated here. Ported anyway, since the original bridge
test treats it as ir-adjacent regression coverage; flagged explicitly
so a future reader does not mistake it for \<^ML_structure>\<open>Ir\<close>'s own
semantics.\<close>
ML \<open>
val (s_init3, _) = run_ir (fn () => Ir.init "IrSh3" [main]);
val _ = \<^assert> (s_init3 = "ok");
val (s_step3, _) = run_ir (fn () => Ir.step "IrSh3" "lemma \"x + y = y + (x::nat)\"");
val _ = \<^assert> (s_step3 = "ok");

val (s_sh3, o_sh3) = run_ir (fn () => Ir.sledgehammer "IrSh3" 15);
val _ =
  if s_sh3 = "error" then error ("sledgehammer crashed instead of returning a " ^
    "no-proof-found message: " ^ o_sh3)
  else ();
(*o_sh3 is already yxml-stripped by run_ir (the Active.sendback finding
  above). Extract everything between the FIRST "Try this:" and ITS OWN
  trailing timing paren, via find_timing_paren -- sledgehammer's report
  can carry multiple "Try this:" suggestions (one per method that
  found a proof), so neither "first paren" nor "last paren in the
  remainder" is safe: a suggested tactic can itself contain parens
  (e.g. "by (simp add: add.commute)" or "by (metis ...)"), and a LATER
  suggestion's own timing paren can appear far past the first one's.
  Both were tried and both broke on a real run -- see find_timing_paren's
  own comment.*)
val (_, after_marker) = Substring.position "Try this:" (Substring.full o_sh3);
val _ =
  if Substring.isEmpty after_marker then () (*no suggestion this run -- tolerated, same as the bridge original*)
  else
    let
      val after_marker' = Substring.string (Substring.triml (size "Try this:") after_marker);
      val before_paren =
        case find_timing_paren after_marker' 0 of
          NONE => after_marker'
        | SOME i => String.substring (after_marker', 0, i);
      val suggestion = String.concatWith " " (String.tokens Char.isSpace before_paren);
      val (s_use, o_use) = run_ir (fn () => Ir.step "IrSh3" suggestion);
    in \<^assert> (s_use = "ok" orelse
          error ("the suggested one-liner did not close the goal: " ^
            suggestion ^ " -- " ^ o_use))
    end;

val (s_rm3, _) = run_ir (fn () => Ir.remove "IrSh3");
val _ = \<^assert> (s_rm3 = "ok");
\<close>

text \<open>bridge T4: two concurrent sledgehammer calls on DIFFERENT repls
never cross outputs. Genuinely ir.ML's own decision, recorded in ir.ML
itself (plans/sledgehammer T4): concurrent calls are serialized via a
global blocking lock rather than rejected with a busy error, since
\<open>sledgehammer_state\<close>/\<open>sledgehammer_result\<close> are single SHARED slots,
not per-call. This checks that the serialization is safe, not merely
that it exists: both calls must still return \<open>ok\<close> (not corrupt or
crash each other), independent of which one the lock lets through
first. Ported from mcp_bridge_tests.scala:412-431 -- initially omitted
(a real gap, not a deliberate one), found while checking this theory's
coverage against the bridge suite.\<close>
ML \<open>
val (s_init4A, _) = run_ir (fn () => Ir.init "IrSh4A" [main]);
val _ = \<^assert> (s_init4A = "ok");
val (s_init4B, _) = run_ir (fn () => Ir.init "IrSh4B" [main]);
val _ = \<^assert> (s_init4B = "ok");
val (s_stepA4, _) = run_ir (fn () => Ir.step "IrSh4A" "lemma \"x + y = y + (x::nat)\"");
val _ = \<^assert> (s_stepA4 = "ok");
val (s_stepB4, _) = run_ir (fn () => Ir.step "IrSh4B" "lemma \"True \<and> True\"");
val _ = \<^assert> (s_stepB4 = "ok");

val fut_a4 = Future.fork (fn () => run_ir (fn () => Ir.sledgehammer "IrSh4A" 15));
val fut_b4 = Future.fork (fn () => run_ir (fn () => Ir.sledgehammer "IrSh4B" 15));
val (s_a4, _) = Future.join fut_a4;
val (s_b4, _) = Future.join fut_b4;
val _ = \<^assert> (s_a4 = "ok" andalso s_b4 = "ok");

val (s_rm_a4, _) = run_ir (fn () => Ir.remove "IrSh4A");
val _ = \<^assert> (s_rm_a4 = "ok");
val (s_rm_b4, _) = run_ir (fn () => Ir.remove "IrSh4B");
val _ = \<^assert> (s_rm_b4 = "ok");
\<close>

section \<open>find_theorems (plans/find_theorems): T2..T4 ONLY\<close>

text \<open>SCOPE, checked against MCP_Repl.thy:382-395 rather than assumed:
the dispatcher's "find_theorems" case has THREE branches keyed on which
of \<open>theory\<close>/\<open>repl\<close> is given. Only the \<open>repl\<close> branch calls
\<^ML>\<open>Ir.find_theorems\<close> (verbatim ir.ML). The \<open>theory\<close> branch calls
\<open>find_theorems_theory\<close>, and the no-argument default calls
\<open>find_theorems_ctxt\<close> -- BOTH of these are functions DEFINED IN
MCP_Repl.thy itself (see its own comment: "a repl-free counterpart to
Ir.find_theorems ... new ML lives here instead"), not ir.ML. So T2..T4
below (all repl-based) genuinely belong here; the "context promotion"
tests T7..T10 (plans/find_theorems, theory=/default context) test OUR
OWN code, not the vendored engine, and are deliberately NOT ported --
moving them here would misattribute whose code is under test. They stay
exactly where they are.\<close>

text \<open>T2: a malformed query is a status error, not a crash.\<close>
ML \<open>
val (s_init, _) = run_ir (fn () => Ir.init "IrTft2" [main]);
val _ = \<^assert> (s_init = "ok");
val (s_ft, _) = run_ir (fn () => Ir.find_theorems "IrTft2" 40 "\"_ + _");
val _ = \<^assert> (s_ft = "error");
val (s_rm, _) = run_ir (fn () => Ir.remove "IrTft2");
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T3: results respect max_results and the tally says so; 0 means
unlimited.\<close>
ML \<open>
val (s_init3, _) = run_ir (fn () => Ir.init "IrTft3" [main]);
val _ = \<^assert> (s_init3 = "ok");

val (s_ft3a, o_ft3a) = run_ir (fn () => Ir.find_theorems "IrTft3" 3 "name:conj");
val _ = \<^assert> (s_ft3a = "ok");
val _ = \<^assert> (String.isSubstring "displaying 3 theorem(s)" o_ft3a);
val lines3a = filter (fn l => l <> "") (String.fields (fn c => c = #"\n") o_ft3a);
val _ = \<^assert> (length lines3a - 1 = 3);

val (s_ft3b, o_ft3b) = run_ir (fn () => Ir.find_theorems "IrTft3" 0 "name:conj");
val _ = \<^assert> (s_ft3b = "ok");
val _ = \<^assert> (String.isSubstring "found " o_ft3b);
val lines3b = filter (fn l => l <> "") (String.fields (fn c => c = #"\n") o_ft3b);
val _ = \<^assert> (length lines3b - 1 > 3);

val (s_rm3, _) = run_ir (fn () => Ir.remove "IrTft3");
val _ = \<^assert> (s_rm3 = "ok");
\<close>

text \<open>T4: goal-based criteria work mid-proof; at theory level with no
goal, a status error.\<close>
ML \<open>
val (s_init4, _) = run_ir (fn () => Ir.init "IrTft4" [main]);
val _ = \<^assert> (s_init4 = "ok");

val (s_ft4a, _) = run_ir (fn () => Ir.find_theorems "IrTft4" 40 "intro");
val _ = \<^assert> (s_ft4a = "error");

val (s_step4, _) = run_ir (fn () => Ir.step "IrTft4" "lemma \"True \<and> True\"");
val _ = \<^assert> (s_step4 = "ok");
val (s_ft4b, o_ft4b) = run_ir (fn () => Ir.find_theorems "IrTft4" 40 "intro");
val _ = \<^assert> (s_ft4b = "ok");
val _ = \<^assert> (String.isSubstring "conjI" o_ft4b);

val (s_rm4, _) = run_ir (fn () => Ir.remove "IrTft4");
val _ = \<^assert> (s_rm4 = "ok");
\<close>

end
