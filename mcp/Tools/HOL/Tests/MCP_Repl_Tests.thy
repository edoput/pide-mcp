theory MCP_Repl_Tests
  imports "MCP-HOL.MCP_Repl"
begin

text \<open>Unit tests for the MCP.ir dispatcher (\<^ML_structure>\<open>MCP_Repl\<close>) over
the I/R engine, at the ml layer named in plans/repl_list (assumptions
I2, T1..T4; T5's transient busy annotation needs real concurrency and is
covered at the bridge layer instead, see mcp_test -b).

This theory fails to load iff a test fails, so
\<^verbatim>\<open>isabelle build -d mcp/Tools MCP-HOL-Tests\<close> is the test runner. Kept
separate from \<^theory>\<open>MCP-HOL.MCP_Repl\<close>'s own build-time smoke test so
this session's repl churn never lands in the production MCP-HOL heap the
server loads.\<close>

ML \<open>
val plain = XML.content_of o YXML.parse_body;
(*image theories are registered under their long name, e.g. "HOL.Main"*)
val main =
  the_default "Main"
    (find_first (fn n => n = "Main" orelse String.isSuffix ".Main" n) (Thy_Info.get_names ()));
(*count (non-overlapping) occurrences of pat in s -- used to check the
  number of "[stale]" marks in a repl_show listing without depending on
  render_isar_text's exact token spacing*)
fun count_substring pat s =
  let val n = size pat
      fun go i acc =
        if i + n > size s then acc
        else if String.substring (s, i, n) = pat then go (i + n) (acc + 1)
        else go (i + 1) acc
  in go 0 0 end;
\<close>

section \<open>T2: fresh state, before any repl exists\<close>

ML \<open>
let val (status, output) = MCP_Repl.run "repls" [] in
  \<^assert> (status = "ok");
  \<^assert> (not (String.isSubstring "steps" (plain output)))
end;
\<close>

section \<open>I2: named-args error paths on a zero-arg fname\<close>

ML \<open>
(*empty args: accepted*)
let val (status, _) = MCP_Repl.run "repls" [] in \<^assert> (status = "ok") end;
(*spurious key: closed arity, typed error naming the key -- not an uncaught exception*)
let val (status, output) = MCP_Repl.run "repls" [("spurious", "x")] in
  \<^assert> (status = "error");
  \<^assert> (String.isSubstring "spurious" (plain output))
end;
\<close>

section \<open>T1/T3: repls reflects the live table, no caching\<close>

ML \<open>
val (s_init, o_init) = MCP_Repl.run "init" [("repl", "T"), ("theories", main)];
val _ = \<^assert> (s_init = "ok" andalso String.isSubstring "Created REPL" (plain o_init));

val (s1, o1) = MCP_Repl.run "repls" [];
val _ = \<^assert> (s1 = "ok");
val _ = \<^assert> (String.isSubstring "T" (plain o1));
val _ = \<^assert> (String.isSubstring "0 steps" (plain o1));

val (s_step, _) = MCP_Repl.run "step" [("repl", "T"), ("isar_text", "lemma True")];
val _ = \<^assert> (s_step = "ok");

val (s2, o2) = MCP_Repl.run "repls" [];
val _ = \<^assert> (s2 = "ok");
val _ = \<^assert> (String.isSubstring "1 steps" (plain o2));

val (s_rm, _) = MCP_Repl.run "remove" [("repl", "T")];
val _ = \<^assert> (s_rm = "ok");

val (s3, o3) = MCP_Repl.run "repls" [];
val _ = \<^assert> (s3 = "ok");
val _ = \<^assert> (not (String.isSubstring "T (" (plain o3)));
\<close>

section \<open>T4: read-only -- repeating repls changes nothing, the repl still steps\<close>

ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "T4"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");

val (_, listing_a) = MCP_Repl.run "repls" [];
val (_, listing_b) = MCP_Repl.run "repls" [];
val _ = \<^assert> (plain listing_a = plain listing_b);

(*the repl is unaffected by the read-only listing calls: it still steps*)
val (s_step, o_step) = MCP_Repl.run "step" [("repl", "T4"), ("isar_text", "lemma True")];
val _ = \<^assert> (s_step = "ok" andalso String.isSubstring "True" (plain o_step));

val (s_rm, _) = MCP_Repl.run "remove" [("repl", "T4")];
val _ = \<^assert> (s_rm = "ok");
\<close>

section \<open>repl_init (plans/repl_init): T1..T6\<close>

text \<open>T1: repeated "theories" keys preserve array order; independent
theories succeed regardless of merge order.\<close>
ML \<open>
(*a genuine ancestor of main, distinct from it, already in the image --
  avoids depending on a theory (e.g. HOL-Library.Multiset) that may not be
  loaded into the MCP-HOL heap*)
val other =
  Context.theory_name {long = true} (hd (Theory.ancestors_of (Thy_Info.get_theory main)));

val (s_fwd, _) = MCP_Repl.run "init" [("repl", "Ti1"), ("theories", main), ("theories", other)];
val _ = \<^assert> (s_fwd = "ok");
val (s_rm1, _) = MCP_Repl.run "remove" [("repl", "Ti1")];
val _ = \<^assert> (s_rm1 = "ok");

val (s_bwd, _) = MCP_Repl.run "init" [("repl", "Ti2"), ("theories", other), ("theories", main)];
val _ = \<^assert> (s_bwd = "ok");
val (s_rm2, _) = MCP_Repl.run "remove" [("repl", "Ti2")];
val _ = \<^assert> (s_rm2 = "ok");
\<close>

text \<open>T2: an empty theories array is an ENGINE error ("at least one
spec"), not a dispatcher/key error -- the key itself may be absent.\<close>
ML \<open>
val (s_empty, o_empty) = MCP_Repl.run "init" [("repl", "Ti3")];
val _ = \<^assert> (s_empty = "error");
val _ = \<^assert> (String.isSubstring "at least one spec" (plain o_empty));

(*no repl was created*)
val (_, o_after) = MCP_Repl.run "repls" [];
val _ = \<^assert> (not (String.isSubstring "Ti3" (plain o_after)));
\<close>

text \<open>T3: duplicate id is an engine error and leaves the existing repl
untouched.\<close>
ML \<open>
val (s_first, _) = MCP_Repl.run "init" [("repl", "Ti4"), ("theories", main)];
val _ = \<^assert> (s_first = "ok");

val (s_dup, o_dup) = MCP_Repl.run "init" [("repl", "Ti4"), ("theories", main)];
val _ = \<^assert> (s_dup = "error");
val _ = \<^assert> (String.isSubstring "already exists" (plain o_dup));

(*the existing repl still works*)
val (s_step, _) = MCP_Repl.run "step" [("repl", "Ti4"), ("isar_text", "lemma True")];
val _ = \<^assert> (s_step = "ok");
val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Ti4")];
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T4: an unknown theory name is a status error naming the theory; no
repl is created.\<close>
ML \<open>
val (s_bad, o_bad) = MCP_Repl.run "init" [("repl", "Ti5"), ("theories", "No.Such_Theory")];
val _ = \<^assert> (s_bad = "error");
val _ = \<^assert> (String.isSubstring "No.Such_Theory" (plain o_bad));
val (_, o_after) = MCP_Repl.run "repls" [];
val _ = \<^assert> (not (String.isSubstring "Ti5" (plain o_after)));
\<close>

text \<open>T5: image theories resolve under their long name.\<close>
ML \<open>
val (s_long, _) = MCP_Repl.run "init" [("repl", "Ti6"), ("theories", main)];
val _ = \<^assert> (s_long = "ok");
val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Ti6")];
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T6 (part): "Thy:idx" segment specs error when mixed with a plain
theory spec, and "pin@name" errors cleanly when there is no such pin.
Both checks trip before segment resolution (the mix check is purely
structural on the parsed spec list; the pin check looks up the repl
table), so no theory with recorded segments is needed here. The
positive "Thy:idx" ok-path needs a theory loaded with record_theories
in a LIVE session (Ir.load_theory forces this per call, but segments
are process-local -- they do not survive into a persisted session
heap, so a separate "isabelle build" test session can never observe
them); that half of T6 is covered at the bridge layer instead, see
mcp_test -b ("ir bridge: repl_init with a Thy:idx segment spec").\<close>
ML \<open>
val (s_mix, o_mix) =
  MCP_Repl.run "init" [("repl", "Ti8"), ("theories", main), ("theories", "Dummy:0")];
val _ = \<^assert> (s_mix = "error");
val _ = \<^assert> (String.isSubstring "Cannot mix theory and segment specs" (plain o_mix));

val (s_pin, o_pin) = MCP_Repl.run "init" [("repl", "Ti9"), ("theories", "pin@nope")];
val _ = \<^assert> (s_pin = "error");
val _ = \<^assert> (String.isSubstring "No REPL" (plain o_pin));
\<close>

section \<open>repl_fork (plans/repl_fork): T2..T4\<close>

text \<open>T2: index semantics match repl_state's -- 0 is the base state, N
is the state after step N-1, -1 is the latest (equal to fork at the
highest index); indices past the end error "out of range". Faithfulness
of "-1 equals fork at 2" is checked by comparing the STATE each fork
starts from (a fork's own state_idx=0 is its fork point), not by
string-comparing the "Forked ... at state N" acknowledgement -- that
message always echoes the raw argument, so fork@-1 and fork@2 print
different text even though they land on the same state.\<close>
ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "Tfk2"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");
val (s1, _) = MCP_Repl.run "step" [("repl", "Tfk2"), ("isar_text", "lemma tfk2: True")];
val _ = \<^assert> (s1 = "ok");
val (s2, _) = MCP_Repl.run "step" [("repl", "Tfk2"), ("isar_text", "by simp")];
val _ = \<^assert> (s2 = "ok");

val (s_f0, _) = MCP_Repl.run "fork" [("repl", "Tfk2"), ("new_repl", "Tfk2f0"), ("state_idx", "0")];
val _ = \<^assert> (s_f0 = "ok");
val (s_f1, _) = MCP_Repl.run "fork" [("repl", "Tfk2"), ("new_repl", "Tfk2f1"), ("state_idx", "1")];
val _ = \<^assert> (s_f1 = "ok");
val (s_fneg1, _) =
  MCP_Repl.run "fork" [("repl", "Tfk2"), ("new_repl", "Tfk2fneg1"), ("state_idx", "-1")];
val _ = \<^assert> (s_fneg1 = "ok");
val (s_f2, _) = MCP_Repl.run "fork" [("repl", "Tfk2"), ("new_repl", "Tfk2f2"), ("state_idx", "2")];
val _ = \<^assert> (s_f2 = "ok");

(*fork@-1 and fork@2 must start from the same state: each fork's own
  index 0 IS its fork point, so compare repl_state(fork, 0) across the
  two forks instead of the parent's states directly*)
val (_, o_state_neg1) = MCP_Repl.run "state" [("repl", "Tfk2fneg1"), ("state_idx", "0")];
val (_, o_state_2) = MCP_Repl.run "state" [("repl", "Tfk2f2"), ("state_idx", "0")];
val _ = \<^assert> (plain o_state_neg1 = plain o_state_2);

val (s_f99, o_f99) =
  MCP_Repl.run "fork" [("repl", "Tfk2"), ("new_repl", "Tfk2f99"), ("state_idx", "99")];
val _ = \<^assert> (s_f99 = "error");
val _ = \<^assert> (String.isSubstring "out of range" (plain o_f99));

val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Tfk2")];
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T3: a duplicate new_repl id errors and changes nothing; the
parent's claim is released, so it still steps afterwards.\<close>
ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "Tfk3"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");
val (s_dup, _) = MCP_Repl.run "init" [("repl", "Tfk3dup"), ("theories", main)];
val _ = \<^assert> (s_dup = "ok");

val (s_f, o_f) = MCP_Repl.run "fork" [("repl", "Tfk3"), ("new_repl", "Tfk3dup"), ("state_idx", "0")];
val _ = \<^assert> (s_f = "error");
val _ = \<^assert> (String.isSubstring "already exists" (plain o_f));

val (s_step, _) = MCP_Repl.run "step" [("repl", "Tfk3"), ("isar_text", "lemma tfk3: True")];
val _ = \<^assert> (s_step = "ok");

val (s_rm1, _) = MCP_Repl.run "remove" [("repl", "Tfk3")];
val _ = \<^assert> (s_rm1 = "ok");
val (s_rm2, _) = MCP_Repl.run "remove" [("repl", "Tfk3dup")];
val _ = \<^assert> (s_rm2 = "ok");
\<close>

text \<open>T4: the fork is independent of its parent in both directions --
stepping the fork does not change the parent, and stepping the parent
afterwards does not change the fork.\<close>
ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "Tfk4"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");
val (s1, _) = MCP_Repl.run "step" [("repl", "Tfk4"), ("isar_text", "lemma tfk4: True")];
val _ = \<^assert> (s1 = "ok");
val (s2, _) = MCP_Repl.run "step" [("repl", "Tfk4"), ("isar_text", "by simp")];
val _ = \<^assert> (s2 = "ok");

(*fork at the tip -- theory-mode state (the proof is already closed on
  the parent), so BOTH sides can independently open a fresh lemma below*)
val (s_f, _) = MCP_Repl.run "fork" [("repl", "Tfk4"), ("new_repl", "Tfk4C"), ("state_idx", "-1")];
val _ = \<^assert> (s_f = "ok");

val (s_cstep, _) = MCP_Repl.run "step" [("repl", "Tfk4C"), ("isar_text", "lemma tfk4c: True by simp")];
val _ = \<^assert> (s_cstep = "ok");
val (_, o_parent_after_child_step) = MCP_Repl.run "show" [("repl", "Tfk4")];
val _ = \<^assert> (String.isSubstring "2 steps" (plain o_parent_after_child_step));

val (s_pstep, _) = MCP_Repl.run "step" [("repl", "Tfk4"), ("isar_text", "lemma tfk4b: True by simp")];
val _ = \<^assert> (s_pstep = "ok");
val (_, o_child_after_parent_step) = MCP_Repl.run "show" [("repl", "Tfk4C")];
val _ = \<^assert> (String.isSubstring "1 steps" (plain o_child_after_parent_step));

val (s_rm_c, _) = MCP_Repl.run "remove" [("repl", "Tfk4C")];
val _ = \<^assert> (s_rm_c = "ok");
val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Tfk4")];
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T5: the orphan rule (truncating the parent strictly below the
fork point removes the fork) is exercised in repl_truncate's own T3
(further down this theory), which explicitly shares this claim across
both plans -- no separate assertion is duplicated here.\<close>

section \<open>repl_remove (plans/repl_remove): T1..T3\<close>

text \<open>T1: removing an unknown repl is a status error naming it, not ok;
the tool is not idempotent -- a second removal of the same id errors too.\<close>
ML \<open>
val (s_nope, o_nope) = MCP_Repl.run "remove" [("repl", "nope")];
val _ = \<^assert> (s_nope = "error");
val _ = \<^assert> (String.isSubstring "No REPL" (plain o_nope));

val (s_first, _) = MCP_Repl.run "init" [("repl", "Tr1"), ("theories", main)];
val _ = \<^assert> (s_first = "ok");
val (s_rm1, _) = MCP_Repl.run "remove" [("repl", "Tr1")];
val _ = \<^assert> (s_rm1 = "ok");
val (s_rm2, o_rm2) = MCP_Repl.run "remove" [("repl", "Tr1")];
val _ = \<^assert> (s_rm2 = "error");
val _ = \<^assert> (String.isSubstring "No REPL" (plain o_rm2));
\<close>

text \<open>T2: removal is recursive and reports every removed id.\<close>
ML \<open>
val (s_p, _) = MCP_Repl.run "init" [("repl", "Tr2"), ("theories", main)];
val _ = \<^assert> (s_p = "ok");
val (s_f1, _) = MCP_Repl.run "fork" [("repl", "Tr2"), ("new_repl", "Tr2a"), ("state_idx", "0")];
val _ = \<^assert> (s_f1 = "ok");
val (s_f2, _) = MCP_Repl.run "fork" [("repl", "Tr2a"), ("new_repl", "Tr2b"), ("state_idx", "0")];
val _ = \<^assert> (s_f2 = "ok");

val (s_rm, o_rm) = MCP_Repl.run "remove" [("repl", "Tr2")];
val _ = \<^assert> (s_rm = "ok");
val _ = \<^assert> (String.isSubstring "Tr2" (plain o_rm));
val _ = \<^assert> (String.isSubstring "Tr2a" (plain o_rm));
val _ = \<^assert> (String.isSubstring "Tr2b" (plain o_rm));

val (_, o_after) = MCP_Repl.run "repls" [];
val _ = \<^assert> (not (String.isSubstring "Tr2" (plain o_after)));
\<close>

text \<open>T3: pin dependents block removal; removing the dependent first
frees the pin owner.\<close>
ML \<open>
val (s_a, _) = MCP_Repl.run "init" [("repl", "Tr3a"), ("theories", main)];
val _ = \<^assert> (s_a = "ok");
val (s_pin, _) = MCP_Repl.run "pin" [("repl", "Tr3a")];
val _ = \<^assert> (s_pin = "ok");
val (s_b, _) = MCP_Repl.run "init" [("repl", "Tr3b"), ("theories", "pin@Tr3a")];
val _ = \<^assert> (s_b = "ok");

val (s_rm_blocked, o_rm_blocked) = MCP_Repl.run "remove" [("repl", "Tr3a")];
val _ = \<^assert> (s_rm_blocked = "error");
val _ = \<^assert> (String.isSubstring "depend on its pin" (plain o_rm_blocked));

val (s_rm_b, _) = MCP_Repl.run "remove" [("repl", "Tr3b")];
val _ = \<^assert> (s_rm_b = "ok");
val (s_rm_a, _) = MCP_Repl.run "remove" [("repl", "Tr3a")];
val _ = \<^assert> (s_rm_a = "ok");
\<close>

section \<open>repl_step (plans/repl_step): T1..T4\<close>

text \<open>T1: isar text survives the trip byte-clean, including Isabelle
symbols and a multi-line statement.\<close>
ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "Ts1"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");

val stmt = "lemma \"x \<longrightarrow> x\"\n  by simp";
val (s_step, o_step) = MCP_Repl.run "step" [("repl", "Ts1"), ("isar_text", stmt)];
val _ = \<^assert> (s_step = "ok");

val (s_text, o_text) = MCP_Repl.run "text" [("repl", "Ts1")];
val _ = \<^assert> (s_text = "ok");
val _ = \<^assert> (String.isSubstring "\<longrightarrow>" (plain o_text));

val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Ts1")];
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T2: failure atomicity -- a failing step leaves the step count and
last state unchanged, and the repl is left usable (claim released).\<close>
ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "Ts2"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");

val (s_ok, _) = MCP_Repl.run "step" [("repl", "Ts2"), ("isar_text", "lemma True")];
val _ = \<^assert> (s_ok = "ok");

val (_, o_before) = MCP_Repl.run "repls" [];
val _ = \<^assert> (String.isSubstring "Ts2 (1 steps" (plain o_before));

val (s_bad, o_bad) = MCP_Repl.run "step" [("repl", "Ts2"), ("isar_text", "garbage_tactic")];
val _ = \<^assert> (s_bad = "error");

val (_, o_after) = MCP_Repl.run "repls" [];
val _ = \<^assert> (String.isSubstring "Ts2 (1 steps" (plain o_after));
val _ = \<^assert> (not (String.isSubstring "busy" (plain o_after)));

(*the repl is still usable -- the claim was released even though the step failed*)
val (s_next, o_next) = MCP_Repl.run "step" [("repl", "Ts2"), ("isar_text", "by simp")];
val _ = \<^assert> (s_next = "ok");

val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Ts2")];
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T3: a timed-out step behaves like a failed step -- it errors
mentioning the timeout, leaves the state unchanged, and the repl stays
usable afterwards.\<close>
ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "Ts3"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");
val (s_to, _) = MCP_Repl.run "timeout" [("repl", "Ts3"), ("secs", "1")];
val _ = \<^assert> (s_to = "ok");

val (s_slow, o_slow) =
  MCP_Repl.run "step" [("repl", "Ts3"),
    ("isar_text", "ML_command \<open>OS.Process.sleep (seconds 3.0)\<close>")];
val _ = \<^assert> (s_slow = "error");
val _ = \<^assert> (String.isSubstring "timed out" (plain o_slow));

val (_, o_after) = MCP_Repl.run "repls" [];
val _ = \<^assert> (String.isSubstring "Ts3 (0 steps" (plain o_after));

val (s_next, _) = MCP_Repl.run "step" [("repl", "Ts3"), ("isar_text", "lemma True")];
val _ = \<^assert> (s_next = "ok");

val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Ts3")];
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T4: a successful step inside a proof prints the goal state, not
just an acknowledgement.\<close>
ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "Ts4"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");

val (s_step, o_step) =
  MCP_Repl.run "step" [("repl", "Ts4"), ("isar_text", "lemma \"x + y = y + (x::nat)\"")];
val _ = \<^assert> (s_step = "ok");
val _ = \<^assert> (String.isSubstring "goal" (plain o_step));

val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Ts4")];
val _ = \<^assert> (s_rm = "ok");
\<close>

section \<open>repl_state (plans/repl_state): T1..T2\<close>

text \<open>T1: index arithmetic -- on a repl with 2 steps, -1 equals index 2
(the latest state); 0 is the base state; indices past either end error
"out of range".\<close>
ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "Tst1"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");
val (s1, _) = MCP_Repl.run "step" [("repl", "Tst1"), ("isar_text", "lemma True")];
val _ = \<^assert> (s1 = "ok");
val (s2, _) = MCP_Repl.run "step" [("repl", "Tst1"), ("isar_text", "by simp")];
val _ = \<^assert> (s2 = "ok");

val (s_neg1, o_neg1) = MCP_Repl.run "state" [("repl", "Tst1"), ("state_idx", "-1")];
val (s_2, o_2) = MCP_Repl.run "state" [("repl", "Tst1"), ("state_idx", "2")];
val _ = \<^assert> (s_neg1 = "ok" andalso s_2 = "ok");
val _ = \<^assert> (plain o_neg1 = plain o_2);

val (s_0, _) = MCP_Repl.run "state" [("repl", "Tst1"), ("state_idx", "0")];
val _ = \<^assert> (s_0 = "ok");

val (s_oob_hi, o_oob_hi) = MCP_Repl.run "state" [("repl", "Tst1"), ("state_idx", "3")];
val _ = \<^assert> (s_oob_hi = "error");
val _ = \<^assert> (String.isSubstring "out of range" (plain o_oob_hi));

val (s_oob_lo, o_oob_lo) = MCP_Repl.run "state" [("repl", "Tst1"), ("state_idx", "-4")];
val _ = \<^assert> (s_oob_lo = "error");
val _ = \<^assert> (String.isSubstring "out of range" (plain o_oob_lo));

val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Tst1")];
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T2: reading a state does not disturb the repl -- no claim taken,
repeatable, the repl still steps afterwards.\<close>
ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "Tst2"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");
val (s1, _) = MCP_Repl.run "step" [("repl", "Tst2"), ("isar_text", "lemma True")];
val _ = \<^assert> (s1 = "ok");

val (_, o_a) = MCP_Repl.run "state" [("repl", "Tst2"), ("state_idx", "-1")];
val (_, o_b) = MCP_Repl.run "state" [("repl", "Tst2"), ("state_idx", "-1")];
val _ = \<^assert> (plain o_a = plain o_b);

val (s_step, _) = MCP_Repl.run "step" [("repl", "Tst2"), ("isar_text", "by simp")];
val _ = \<^assert> (s_step = "ok");

val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Tst2")];
val _ = \<^assert> (s_rm = "ok");
\<close>

section \<open>repl_show (plans/repl_show): T1..T2\<close>

text \<open>T1: the output carries origin, step count, and indices starting
at 0. The plan's stale-mark half of T1 ("edit step 0 with auto_replay
off") is not reachable through the current MCP.ir dispatch surface: the
engine's default config has auto_replay=true (ir.ML:65) and no fname
exposes toggling it, so \<^ML>\<open>Ir.edit\<close> always replays subsequent steps
immediately, leaving nothing stale to observe. The "[stale]" rendering
path itself (ir.ML:547) is exercised once a genuinely stale-producing
operation (e.g. repl_rebase after a pin change) is covered by its own
plan; noted here rather than silently dropped.\<close>
ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "Tsh1"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");
val (s1, _) = MCP_Repl.run "step" [("repl", "Tsh1"), ("isar_text", "lemma True")];
val _ = \<^assert> (s1 = "ok");
val (s2, _) = MCP_Repl.run "step" [("repl", "Tsh1"), ("isar_text", "by simp")];
val _ = \<^assert> (s2 = "ok");

val (s_show, o_show) = MCP_Repl.run "show" [("repl", "Tsh1")];
val _ = \<^assert> (s_show = "ok");
val txt = plain o_show;
val _ = \<^assert> (String.isSubstring "2 steps" txt);
val _ = \<^assert> (String.isSubstring "from" txt);
val _ = \<^assert> (String.isSubstring "  0  " txt);
val _ = \<^assert> (String.isSubstring "  1  " txt);
val _ = \<^assert> (not (String.isSubstring "[stale]" txt));

val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Tsh1")];
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T2: read-only and repeatable when the repl is idle; still errors
"is busy" when the repl is claimed (documented, not tested here since
that needs real concurrency -- see mcp_test -b).\<close>
ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "Tsh2"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");
val (s1, _) = MCP_Repl.run "step" [("repl", "Tsh2"), ("isar_text", "lemma True")];
val _ = \<^assert> (s1 = "ok");

val (_, o_a) = MCP_Repl.run "show" [("repl", "Tsh2")];
val (_, o_b) = MCP_Repl.run "show" [("repl", "Tsh2")];
val _ = \<^assert> (plain o_a = plain o_b);

val (s_step, _) = MCP_Repl.run "step" [("repl", "Tsh2"), ("isar_text", "by simp")];
val _ = \<^assert> (s_step = "ok");

val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Tsh2")];
val _ = \<^assert> (s_rm = "ok");
\<close>

section \<open>repl_text (plans/repl_text): T1..T2\<close>

text \<open>T1: byte fidelity end to end -- an Isabelle symbol, an inner
string with doubled spaces, and an embedded newline survive the round
trip verbatim.\<close>
ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "Ttx1"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");

val step1 = "lemma \"x \<longrightarrow> x\"\n  by  simp";
val (s1, _) = MCP_Repl.run "step" [("repl", "Ttx1"), ("isar_text", step1)];
val _ = \<^assert> (s1 = "ok");

val (s_text, o_text) = MCP_Repl.run "text" [("repl", "Ttx1")];
val _ = \<^assert> (s_text = "ok");
val _ = \<^assert> (plain o_text = step1);

val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Ttx1")];
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T2: zero steps is ok with empty output, not an error.\<close>
ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "Ttx2"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");

val (s_text, o_text) = MCP_Repl.run "text" [("repl", "Ttx2")];
val _ = \<^assert> (s_text = "ok");
val _ = \<^assert> (plain o_text = "");

val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Ttx2")];
val _ = \<^assert> (s_rm = "ok");
\<close>

section \<open>repl_edit (plans/repl_edit): T1..T4\<close>

text \<open>T1: idx is a plain 0-based index, negatives are not supported --
out of range at both ends, 0 is valid.\<close>
ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "Ted1"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");
val (s1, _) = MCP_Repl.run "step" [("repl", "Ted1"), ("isar_text", "lemma True")];
val _ = \<^assert> (s1 = "ok");
val (s2, _) = MCP_Repl.run "step" [("repl", "Ted1"), ("isar_text", "by simp")];
val _ = \<^assert> (s2 = "ok");

val (s_neg, o_neg) = MCP_Repl.run "edit" [("repl", "Ted1"), ("idx", "-1"), ("isar_text", "lemma True")];
val _ = \<^assert> (s_neg = "error");
val _ = \<^assert> (String.isSubstring "out of range" (plain o_neg));

val (s_hi, o_hi) = MCP_Repl.run "edit" [("repl", "Ted1"), ("idx", "2"), ("isar_text", "lemma True")];
val _ = \<^assert> (s_hi = "error");
val _ = \<^assert> (String.isSubstring "out of range" (plain o_hi));

val (s_ok, _) = MCP_Repl.run "edit" [("repl", "Ted1"), ("idx", "0"), ("isar_text", "lemma True")];
val _ = \<^assert> (s_ok = "ok");

val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Ted1")];
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T2: failure atomicity -- a failing edit leaves the old step
untouched and the claim released.\<close>
ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "Ted2"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");
val (s1, _) = MCP_Repl.run "step" [("repl", "Ted2"), ("isar_text", "lemma True")];
val _ = \<^assert> (s1 = "ok");
val (s2, _) = MCP_Repl.run "step" [("repl", "Ted2"), ("isar_text", "by simp")];
val _ = \<^assert> (s2 = "ok");

val (_, o_before) = MCP_Repl.run "show" [("repl", "Ted2")];

val (s_bad, _) = MCP_Repl.run "edit" [("repl", "Ted2"), ("idx", "0"), ("isar_text", "garbage_tactic")];
val _ = \<^assert> (s_bad = "error");

val (_, o_after) = MCP_Repl.run "show" [("repl", "Ted2")];
val _ = \<^assert> (plain o_before = plain o_after);
val _ = \<^assert> (not (String.isSubstring "[stale]" (plain o_after)));

(*the repl is still usable -- the claim was released even though the edit failed*)
val (s_next, _) = MCP_Repl.run "step" [("repl", "Ted2"), ("isar_text", "lemma \"1 = (1::nat)\"")];
val _ = \<^assert> (s_next = "ok");

val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Ted2")];
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T3: the reply names the count of subsequent steps as "marked
stale"; under this server's actual default (auto_replay=true, ir.ML:65,
see the repl_edit description), they are replayed immediately, so
\<^ML>\<open>Ir.show\<close> shows them clean right after, not stale (the plan's
literal "auto_replay off" scenario is not reachable through MCP.ir --
same gap noted in plans/repl_show T1).\<close>
ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "Ted3"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");
val (s1, _) = MCP_Repl.run "step" [("repl", "Ted3"), ("isar_text", "lemma True")];
val _ = \<^assert> (s1 = "ok");
val (s2, _) = MCP_Repl.run "step" [("repl", "Ted3"), ("isar_text", "by simp")];
val _ = \<^assert> (s2 = "ok");
val (s3, _) = MCP_Repl.run "step" [("repl", "Ted3"), ("isar_text", "lemma \"1 = (1::nat)\"")];
val _ = \<^assert> (s3 = "ok");
val (s4, _) = MCP_Repl.run "step" [("repl", "Ted3"), ("isar_text", "by simp")];
val _ = \<^assert> (s4 = "ok");

val (s_edit, o_edit) = MCP_Repl.run "edit" [("repl", "Ted3"), ("idx", "0"), ("isar_text", "lemma True")];
val _ = \<^assert> (s_edit = "ok");
val _ = \<^assert> (String.isSubstring "3 subsequent steps marked stale" (plain o_edit));

val (_, o_show) = MCP_Repl.run "show" [("repl", "Ted3")];
val _ = \<^assert> (not (String.isSubstring "[stale]" (plain o_show)));
val _ = \<^assert> (String.isSubstring "4 steps" (plain o_show));

val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Ted3")];
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T4: editing a pinned repl marks the pin stale -- release always
calls mark_pin_stale on mutation, regardless of the operation kind.\<close>
ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "Ted4"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");
val (s1, _) = MCP_Repl.run "step" [("repl", "Ted4"), ("isar_text", "lemma True")];
val _ = \<^assert> (s1 = "ok");
val (s2, _) = MCP_Repl.run "step" [("repl", "Ted4"), ("isar_text", "by simp")];
val _ = \<^assert> (s2 = "ok");

val (s_pin, _) = MCP_Repl.run "pin" [("repl", "Ted4")];
val _ = \<^assert> (s_pin = "ok");
val (_, o_pinned) = MCP_Repl.run "show" [("repl", "Ted4")];
val _ = \<^assert> (String.isSubstring "pinned)" (plain o_pinned));
val _ = \<^assert> (not (String.isSubstring "pinned [stale]" (plain o_pinned)));

val (s_edit, _) = MCP_Repl.run "edit" [("repl", "Ted4"), ("idx", "0"), ("isar_text", "lemma True")];
val _ = \<^assert> (s_edit = "ok");
val (_, o_after) = MCP_Repl.run "show" [("repl", "Ted4")];
val _ = \<^assert> (String.isSubstring "pinned [stale]" (plain o_after));

val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Ted4")];
val _ = \<^assert> (s_rm = "ok");
\<close>

section \<open>repl_replay (plans/repl_replay): T1..T4\<close>

text \<open>Genuinely stale steps (as opposed to repl_edit's tail, which
this server always auto-replays immediately -- see plans/repl_edit T3)
are produced here via repl_rebase instead: \<^ML>\<open>Ir.pin\<close> increments
the pin's version on every call regardless of content change, so
re-pinning a REPL that another REPL was initialized "pin@..." from is
enough to make \<^ML>\<open>Ir.rebase\<close> see a version mismatch and mark the
dependent's steps stale, with NO replay -- exactly the fixture
repl_replay needs.\<close>

text \<open>T1: no stale steps is an ok no-op, not an error.\<close>
ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "Trp1"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");
val (s1, _) = MCP_Repl.run "step" [("repl", "Trp1"), ("isar_text", "lemma True")];
val _ = \<^assert> (s1 = "ok");
val (s2, _) = MCP_Repl.run "step" [("repl", "Trp1"), ("isar_text", "by simp")];
val _ = \<^assert> (s2 = "ok");

val (s_replay, o_replay) = MCP_Repl.run "replay" [("repl", "Trp1")];
val _ = \<^assert> (s_replay = "ok");
val _ = \<^assert> (String.isSubstring "Replayed 0 stale steps" (plain o_replay));

val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Trp1")];
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T2: replay executes the stale suffix and re-chains states -- the
replayed state matches what a fresh run of the same script would give.\<close>
ML \<open>
val (s_a, _) = MCP_Repl.run "init" [("repl", "Trp2a"), ("theories", main)];
val _ = \<^assert> (s_a = "ok");
val (s_pin1, _) = MCP_Repl.run "pin" [("repl", "Trp2a")];
val _ = \<^assert> (s_pin1 = "ok");

val (s_b, _) = MCP_Repl.run "init" [("repl", "Trp2b"), ("theories", "pin@Trp2a")];
val _ = \<^assert> (s_b = "ok");
val (s_bs, _) = MCP_Repl.run "step" [("repl", "Trp2b"), ("isar_text", "lemma \"1 = (1::nat)\"")];
val _ = \<^assert> (s_bs = "ok");
val (s_bs2, _) = MCP_Repl.run "step" [("repl", "Trp2b"), ("isar_text", "by simp")];
val _ = \<^assert> (s_bs2 = "ok");
val (_, o_before) = MCP_Repl.run "state" [("repl", "Trp2b"), ("state_idx", "-1")];

(*re-pinning Trp2a bumps its pin version -- Trp2b's stored spec (old
version) now disagrees with the current one, so rebase has genuine work*)
val (s_pin2, _) = MCP_Repl.run "pin" [("repl", "Trp2a")];
val _ = \<^assert> (s_pin2 = "ok");
val (s_rebase, o_rebase) = MCP_Repl.run "rebase" [("repl", "Trp2b")];
val _ = \<^assert> (s_rebase = "ok");
val _ = \<^assert> (String.isSubstring "2 steps marked stale" (plain o_rebase));
val (_, o_staleshow) = MCP_Repl.run "show" [("repl", "Trp2b")];
val _ = \<^assert> (String.isSubstring "[stale]" (plain o_staleshow));

val (s_replay, o_replay) = MCP_Repl.run "replay" [("repl", "Trp2b")];
val _ = \<^assert> (s_replay = "ok");
val _ = \<^assert> (String.isSubstring "Replayed 2 stale steps" (plain o_replay));

val (_, o_after) = MCP_Repl.run "state" [("repl", "Trp2b"), ("state_idx", "-1")];
val _ = \<^assert> (plain o_before = plain o_after);
val (_, o_cleanshow) = MCP_Repl.run "show" [("repl", "Trp2b")];
val _ = \<^assert> (not (String.isSubstring "[stale]" (plain o_cleanshow)));

val (s_rm_b, _) = MCP_Repl.run "remove" [("repl", "Trp2b")];
val _ = \<^assert> (s_rm_b = "ok");
val (s_rm_a, _) = MCP_Repl.run "remove" [("repl", "Trp2a")];
val _ = \<^assert> (s_rm_a = "ok");
\<close>

text \<open>T3: a failure mid-replay surfaces as a status error, and the
repl is left exactly as it was before the attempt -- ALL steps that
were stale beforehand are still stale afterward, not just the failing
one and its tail. This is with_claim's atomicity (ir.ML:305): on
exception it releases the ORIGINAL claimed snapshot, not whatever
replay_repl had built up before raising, so a failed replay is a
no-op on the visible state, never a partial one. (Resolves the plan's
open NOTE: the exception discards r' entirely -- "all steps stay
stale" is the actual behavior, not "prefix clean, tail stale".)
Constructed by rebasing onto a theory that has dropped a constant a
later step depends on.\<close>
ML \<open>
val (s_a, _) = MCP_Repl.run "init" [("repl", "Trp3a"), ("theories", main)];
val _ = \<^assert> (s_a = "ok");
val (s_foo, _) =
  MCP_Repl.run "step" [("repl", "Trp3a"),
    ("isar_text", "definition foo :: nat where \"foo = 3\"")];
val _ = \<^assert> (s_foo = "ok");
val (s_pin1, _) = MCP_Repl.run "pin" [("repl", "Trp3a")];
val _ = \<^assert> (s_pin1 = "ok");

val (s_b, _) = MCP_Repl.run "init" [("repl", "Trp3b"), ("theories", "pin@Trp3a")];
val _ = \<^assert> (s_b = "ok");
val (s_b0, _) = MCP_Repl.run "step" [("repl", "Trp3b"), ("isar_text", "lemma su0: True")];
val _ = \<^assert> (s_b0 = "ok");
val (s_b0b, _) = MCP_Repl.run "step" [("repl", "Trp3b"), ("isar_text", "by simp")];
val _ = \<^assert> (s_b0b = "ok");
val (s_b1, _) =
  MCP_Repl.run "step" [("repl", "Trp3b"), ("isar_text", "lemma \"foo = (3::nat)\"")];
val _ = \<^assert> (s_b1 = "ok");
val (s_b1b, _) = MCP_Repl.run "step" [("repl", "Trp3b"), ("isar_text", "by (simp add: foo_def)")];
val _ = \<^assert> (s_b1b = "ok");

(*redefine Trp3a's step 0 to drop foo -- edit auto-replays immediately
  (no subsequent steps of its own), so Trp3a's last state now has no foo*)
val (s_edit, _) =
  MCP_Repl.run "edit" [("repl", "Trp3a"), ("idx", "0"),
    ("isar_text", "definition bar :: nat where \"bar = 3\"")];
val _ = \<^assert> (s_edit = "ok");
val (s_pin2, _) = MCP_Repl.run "pin" [("repl", "Trp3a")];
val _ = \<^assert> (s_pin2 = "ok");

val (s_rebase, o_rebase) = MCP_Repl.run "rebase" [("repl", "Trp3b")];
val _ = \<^assert> (s_rebase = "ok");
val _ = \<^assert> (String.isSubstring "4 steps marked stale" (plain o_rebase));

val (s_replay, o_replay) = MCP_Repl.run "replay" [("repl", "Trp3b")];
val _ = \<^assert> (s_replay = "error");

val (_, o_show) = MCP_Repl.run "show" [("repl", "Trp3b")];
val show_txt = plain o_show;
(*with_claim releases the ORIGINAL snapshot on exception (ir.ML:305),
  discarding whatever replay_repl had rebuilt so far -- all 4 steps
  that were stale before the attempt are still stale after it*)
val _ = \<^assert> (count_substring "[stale]" show_txt = 4);

(*the repl is still inspectable, not corrupted*)
val (s_state, _) = MCP_Repl.run "state" [("repl", "Trp3b"), ("state_idx", "0")];
val _ = \<^assert> (s_state = "ok");

val (s_rm_b, _) = MCP_Repl.run "remove" [("repl", "Trp3b")];
val _ = \<^assert> (s_rm_b = "ok");
val (s_rm_a, _) = MCP_Repl.run "remove" [("repl", "Trp3a")];
val _ = \<^assert> (s_rm_a = "ok");
\<close>

text \<open>T4: replay respects the repl's CURRENT timeout, not whatever was
in effect when the (now stale) step first succeeded.\<close>
ML \<open>
val (s_a, _) = MCP_Repl.run "init" [("repl", "Trp4a"), ("theories", main)];
val _ = \<^assert> (s_a = "ok");
val (s_pin1, _) = MCP_Repl.run "pin" [("repl", "Trp4a")];
val _ = \<^assert> (s_pin1 = "ok");

val (s_b, _) = MCP_Repl.run "init" [("repl", "Trp4b"), ("theories", "pin@Trp4a")];
val _ = \<^assert> (s_b = "ok");
val (s_slow, _) =
  MCP_Repl.run "step" [("repl", "Trp4b"),
    ("isar_text", "ML_command \<open>OS.Process.sleep (seconds 2.0)\<close>")];
val _ = \<^assert> (s_slow = "ok");

val (s_pin2, _) = MCP_Repl.run "pin" [("repl", "Trp4a")];
val _ = \<^assert> (s_pin2 = "ok");
val (s_rebase, _) = MCP_Repl.run "rebase" [("repl", "Trp4b")];
val _ = \<^assert> (s_rebase = "ok");

val (s_to, _) = MCP_Repl.run "timeout" [("repl", "Trp4b"), ("secs", "1")];
val _ = \<^assert> (s_to = "ok");

val (s_replay, o_replay) = MCP_Repl.run "replay" [("repl", "Trp4b")];
val _ = \<^assert> (s_replay = "error");
val _ = \<^assert> (String.isSubstring "timed out" (plain o_replay));

val (_, o_show) = MCP_Repl.run "show" [("repl", "Trp4b")];
val _ = \<^assert> (String.isSubstring "[stale]" (plain o_show));

val (s_rm_b, _) = MCP_Repl.run "remove" [("repl", "Trp4b")];
val _ = \<^assert> (s_rm_b = "ok");
val (s_rm_a, _) = MCP_Repl.run "remove" [("repl", "Trp4a")];
val _ = \<^assert> (s_rm_a = "ok");
\<close>

section \<open>repl_truncate (plans/repl_truncate): T1..T3, T5\<close>

text \<open>T1: truncate's negative-index mapping (n+idx-1) differs from
state/fork's (n+1+idx) -- truncate -1 KEEPS n-1 steps (drops one), not
n as state's -1 would suggest.\<close>
ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "Ttc1"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");
val (s1, _) = MCP_Repl.run "step" [("repl", "Ttc1"), ("isar_text", "lemma su1: True")];
val _ = \<^assert> (s1 = "ok");
val (s2, _) = MCP_Repl.run "step" [("repl", "Ttc1"), ("isar_text", "by simp")];
val _ = \<^assert> (s2 = "ok");
val (s3, _) = MCP_Repl.run "step" [("repl", "Ttc1"), ("isar_text", "lemma su2: True")];
val _ = \<^assert> (s3 = "ok");

val (s_tc1, o_tc1) = MCP_Repl.run "truncate" [("repl", "Ttc1"), ("idx", "-1")];
val _ = \<^assert> (s_tc1 = "ok");
val (_, o_show1) = MCP_Repl.run "show" [("repl", "Ttc1")];
val _ = \<^assert> (String.isSubstring "2 steps" (plain o_show1));

val (s_tc0, _) = MCP_Repl.run "truncate" [("repl", "Ttc1"), ("idx", "0")];
val _ = \<^assert> (s_tc0 = "ok");
val (_, o_show0) = MCP_Repl.run "show" [("repl", "Ttc1")];
val _ = \<^assert> (String.isSubstring "1 steps" (plain o_show0));

val (s_lo, o_lo) = MCP_Repl.run "truncate" [("repl", "Ttc1"), ("idx", "-99")];
val _ = \<^assert> (s_lo = "error");
val _ = \<^assert> (String.isSubstring "out of range" (plain o_lo));

val (s_hi, o_hi) = MCP_Repl.run "truncate" [("repl", "Ttc1"), ("idx", "99")];
val _ = \<^assert> (s_hi = "error");
val _ = \<^assert> (String.isSubstring "out of range" (plain o_hi));

val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Ttc1")];
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T2: the kept prefix is untouched -- no re-execution, no
staleness, and the repl continues stepping from the kept state.\<close>
ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "Ttc2"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");
val (s1, _) = MCP_Repl.run "step" [("repl", "Ttc2"), ("isar_text", "lemma su1: True")];
val _ = \<^assert> (s1 = "ok");
val (s2, _) = MCP_Repl.run "step" [("repl", "Ttc2"), ("isar_text", "by simp")];
val _ = \<^assert> (s2 = "ok");
val (s3, _) = MCP_Repl.run "step" [("repl", "Ttc2"), ("isar_text", "lemma su2: True")];
val _ = \<^assert> (s3 = "ok");

val (s_tc, _) = MCP_Repl.run "truncate" [("repl", "Ttc2"), ("idx", "-1")];
val _ = \<^assert> (s_tc = "ok");
val (_, o_show) = MCP_Repl.run "show" [("repl", "Ttc2")];
val _ = \<^assert> (not (String.isSubstring "[stale]" (plain o_show)));

(*the kept prefix ends at "by simp" (theory level, proof closed) -- the
  next step continues from there with a new command, not a tactic*)
val (s_next, _) = MCP_Repl.run "step" [("repl", "Ttc2"), ("isar_text", "lemma su3: True")];
val _ = \<^assert> (s_next = "ok");

val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Ttc2")];
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T3: orphan removal -- forks at discarded states disappear, forks
at kept states survive (shared with plans/repl_fork T5).\<close>
ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "Ttc3"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");
val (s1, _) = MCP_Repl.run "step" [("repl", "Ttc3"), ("isar_text", "lemma su1: True")];
val _ = \<^assert> (s1 = "ok");
val (s2, _) = MCP_Repl.run "step" [("repl", "Ttc3"), ("isar_text", "by simp")];
val _ = \<^assert> (s2 = "ok");
val (s3, _) = MCP_Repl.run "step" [("repl", "Ttc3"), ("isar_text", "lemma su2: True")];
val _ = \<^assert> (s3 = "ok");

val (s_f1, _) = MCP_Repl.run "fork" [("repl", "Ttc3"), ("new_repl", "Ttc3f1"), ("state_idx", "1")];
val _ = \<^assert> (s_f1 = "ok");
val (s_f3, _) = MCP_Repl.run "fork" [("repl", "Ttc3"), ("new_repl", "Ttc3f3"), ("state_idx", "3")];
val _ = \<^assert> (s_f3 = "ok");

(*truncate to idx=1 (keep steps 0,1): the orphan cutoff is si > idx, so
  fork@1 (si=1, not > 1) survives and fork@3 (si=3 > 1) is orphaned*)
val (s_tc, o_tc) = MCP_Repl.run "truncate" [("repl", "Ttc3"), ("idx", "1")];
val _ = \<^assert> (s_tc = "ok");

val (_, o_after) = MCP_Repl.run "repls" [];
val after_txt = plain o_after;
val _ = \<^assert> (String.isSubstring "Ttc3f1" after_txt);
val _ = \<^assert> (not (String.isSubstring "Ttc3f3" after_txt));

val (s_rm_f1, _) = MCP_Repl.run "remove" [("repl", "Ttc3f1")];
val _ = \<^assert> (s_rm_f1 = "ok");
val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Ttc3")];
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T5: pin goes stale on truncate, so pin-dependents can't silently
build on a state that no longer exists.\<close>
ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "Ttc5"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");
val (s1, _) = MCP_Repl.run "step" [("repl", "Ttc5"), ("isar_text", "lemma su1: True")];
val _ = \<^assert> (s1 = "ok");
val (s2, _) = MCP_Repl.run "step" [("repl", "Ttc5"), ("isar_text", "by simp")];
val _ = \<^assert> (s2 = "ok");

val (s_pin, _) = MCP_Repl.run "pin" [("repl", "Ttc5")];
val _ = \<^assert> (s_pin = "ok");
val (s_tc, _) = MCP_Repl.run "truncate" [("repl", "Ttc5"), ("idx", "0")];
val _ = \<^assert> (s_tc = "ok");
val (_, o_show) = MCP_Repl.run "show" [("repl", "Ttc5")];
val _ = \<^assert> (String.isSubstring "pinned [stale]" (plain o_show));

val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Ttc5")];
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>repl_back (plans/repl_back) T1..T2 moved to Ir_Tests.thy, which
calls \<^ML>\<open>Ir.back\<close> directly (plans/ml_builtin_migration wave 1: "back"
is no longer a dispatcher fname -- \<^ML>\<open>MCP_Repl.run "back"\<close> would now
error "unknown function". repl_back is declared as a capture-form
mcp_tool below; see its params/annotations block.)\<close>

section \<open>repl_merge (plans/repl_merge): T1..T3\<close>

text \<open>T1(a): fork at the tip -- merge appends the child's concatenated
text as one new step.\<close>
ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "Tmg1a"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");
val (s0, _) = MCP_Repl.run "step" [("repl", "Tmg1a"), ("isar_text", "lemma tmg1a: True")];
val _ = \<^assert> (s0 = "ok");
val (s1, _) = MCP_Repl.run "step" [("repl", "Tmg1a"), ("isar_text", "by simp")];
val _ = \<^assert> (s1 = "ok");

val (s_f, _) = MCP_Repl.run "fork"
  [("repl", "Tmg1a"), ("new_repl", "Tmg1aC"), ("state_idx", "-1")];
val _ = \<^assert> (s_f = "ok");
val (sc0, _) = MCP_Repl.run "step" [("repl", "Tmg1aC"), ("isar_text", "lemma tmg1c: True")];
val _ = \<^assert> (sc0 = "ok");
val (sc1, _) = MCP_Repl.run "step" [("repl", "Tmg1aC"), ("isar_text", "by simp")];
val _ = \<^assert> (sc1 = "ok");

val (s_mg, o_mg) = MCP_Repl.run "merge" [("repl", "Tmg1aC")];
val _ = \<^assert> (s_mg = "ok");
val _ = \<^assert> (String.isSubstring "appended as new step" (plain o_mg));

val (_, o_show) = MCP_Repl.run "show" [("repl", "Tmg1a")];
val _ = \<^assert> (String.isSubstring "3 steps" (plain o_show));
val (_, o_text) = MCP_Repl.run "text" [("repl", "Tmg1a")];
val _ = \<^assert> (String.isSubstring "lemma tmg1c: True\nby simp" (plain o_text));

(*the child was deleted by the merge*)
val (_, o_repls) = MCP_Repl.run "repls" [];
val _ = \<^assert> (not (String.isSubstring "Tmg1aC" (plain o_repls)));

val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Tmg1a")];
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T1(b): fork at an interior state -- merge replaces the step at
the fork index. (auto_replay=true on this server always replays any
subsequent steps immediately, same gap as repl_edit's own T3 / repl_show
T1 -- so this test checks the replace message and step count, not a
[stale] mark that would never be observable here.)\<close>
ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "Tmg1b"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");
val (s0, _) = MCP_Repl.run "step" [("repl", "Tmg1b"), ("isar_text", "lemma tmg1b1: True")];
val _ = \<^assert> (s0 = "ok");
val (s1, _) = MCP_Repl.run "step" [("repl", "Tmg1b"), ("isar_text", "by simp")];
val _ = \<^assert> (s1 = "ok");
val (s2, _) = MCP_Repl.run "step" [("repl", "Tmg1b"), ("isar_text", "lemma tmg1b2: True")];
val _ = \<^assert> (s2 = "ok");

val (s_f, _) = MCP_Repl.run "fork"
  [("repl", "Tmg1b"), ("new_repl", "Tmg1bC"), ("state_idx", "1")];
val _ = \<^assert> (s_f = "ok");
val (sc0, _) = MCP_Repl.run "step" [("repl", "Tmg1bC"), ("isar_text", "by auto")];
val _ = \<^assert> (sc0 = "ok");

val (s_mg, o_mg) = MCP_Repl.run "merge" [("repl", "Tmg1bC")];
val _ = \<^assert> (s_mg = "ok");
val _ = \<^assert> (String.isSubstring "replaced step 1" (plain o_mg));

val (_, o_show) = MCP_Repl.run "show" [("repl", "Tmg1b")];
val _ = \<^assert> (String.isSubstring "3 steps" (plain o_show));
val (_, o_text) = MCP_Repl.run "text" [("repl", "Tmg1b")];
val _ = \<^assert> (String.isSubstring "by auto" (plain o_text));

val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Tmg1b")];
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T2: merging a REPL that is not a sub-REPL (From_Theory origin)
errors, and the REPL itself is unaffected -- it still steps.\<close>
ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "Tmg2"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");

val (s_mg, o_mg) = MCP_Repl.run "merge" [("repl", "Tmg2")];
val _ = \<^assert> (s_mg = "error");
val _ = \<^assert> (String.isSubstring "is not a sub-REPL" (plain o_mg));

val (s_step, _) = MCP_Repl.run "step" [("repl", "Tmg2"), ("isar_text", "lemma tmg2: True")];
val _ = \<^assert> (s_step = "ok");

val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Tmg2")];
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T3: a merge that fails in the parent leaves BOTH REPLs alive and
unchanged -- the parent's tip goal is edited (by repl_edit) after the
fork so the child's tactic no longer applies at merge time.\<close>
ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "Tmg3"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");
val (s0, _) = MCP_Repl.run "step" [("repl", "Tmg3"), ("isar_text", "lemma tmg3: True")];
val _ = \<^assert> (s0 = "ok");

val (s_f, _) = MCP_Repl.run "fork"
  [("repl", "Tmg3"), ("new_repl", "Tmg3C"), ("state_idx", "1")];
val _ = \<^assert> (s_f = "ok");
val (sc0, _) = MCP_Repl.run "step" [("repl", "Tmg3C"), ("isar_text", "by simp")];
val _ = \<^assert> (sc0 = "ok");

(*moves the goal out from under the child's fork-time tactic*)
val (s_ed, _) = MCP_Repl.run "edit"
  [("repl", "Tmg3"), ("idx", "0"), ("isar_text", "lemma tmg3: \"(1::nat) + 1 = 3\"")];
val _ = \<^assert> (s_ed = "ok");

val (s_mg, _) = MCP_Repl.run "merge" [("repl", "Tmg3C")];
val _ = \<^assert> (s_mg = "error");

val (_, o_repls) = MCP_Repl.run "repls" [];
val repls_txt = plain o_repls;
val _ = \<^assert> (String.isSubstring "Tmg3" repls_txt);
val _ = \<^assert> (String.isSubstring "Tmg3C" repls_txt);

(*both still answer show/step -- neither was left half-mutated*)
val (_, o_show) = MCP_Repl.run "show" [("repl", "Tmg3")];
val _ = \<^assert> (String.isSubstring "1 steps" (plain o_show));
val (s_cstep, _) = MCP_Repl.run "step" [("repl", "Tmg3C"), ("isar_text", "ML_command \<open>()\<close>")];
val _ = \<^assert> (s_cstep = "ok");

val (s_rm_c, _) = MCP_Repl.run "remove" [("repl", "Tmg3C")];
val _ = \<^assert> (s_rm_c = "ok");
val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Tmg3")];
val _ = \<^assert> (s_rm = "ok");
\<close>

section \<open>repl_timeout (plans/repl_timeout): T1..T3\<close>

text \<open>T1: the set value actually bounds steps -- secs=1 makes a
sleeping step fail, secs=0 makes the same step run to completion
(shared fixture shape with plans/repl_step T3).\<close>
ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "Tto1"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");

val (s_to1, _) = MCP_Repl.run "timeout" [("repl", "Tto1"), ("secs", "1")];
val _ = \<^assert> (s_to1 = "ok");
val (s_slow, o_slow) =
  MCP_Repl.run "step" [("repl", "Tto1"),
    ("isar_text", "ML_command \<open>OS.Process.sleep (seconds 2.0)\<close>")];
val _ = \<^assert> (s_slow = "error");
val _ = \<^assert> (String.isSubstring "timed out" (plain o_slow));

val (s_to0, _) = MCP_Repl.run "timeout" [("repl", "Tto1"), ("secs", "0")];
val _ = \<^assert> (s_to0 = "ok");
val (s_ok, _) =
  MCP_Repl.run "step" [("repl", "Tto1"),
    ("isar_text", "ML_command \<open>OS.Process.sleep (seconds 2.0)\<close>")];
val _ = \<^assert> (s_ok = "ok");

val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Tto1")];
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T2: negative secs -- pinning the engine behavior. exec_text only
bounds when timeout_secs > 0, so a negative value behaves like 0
(unlimited) for STEP EXECUTION -- a slow step still succeeds. But
show's rendering only special-cases exactly 0 as "unlimited"; a
negative value prints via SML's \<^ML>\<open>string_of_int\<close>, which spells
negative numbers with a tilde, not a minus sign -- "timeout=~1s", not
"timeout=-1s". Both halves are pinned here since neither is obviously
the "right" answer and a future change to either should be a
deliberate, visible one.\<close>
ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "Tto2"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");

val (s_to, _) = MCP_Repl.run "timeout" [("repl", "Tto2"), ("secs", "-1")];
val _ = \<^assert> (s_to = "ok");
val (s_slow, _) =
  MCP_Repl.run "step" [("repl", "Tto2"),
    ("isar_text", "ML_command \<open>OS.Process.sleep (seconds 2.0)\<close>")];
val _ = \<^assert> (s_slow = "ok");

val (_, o_show) = MCP_Repl.run "show" [("repl", "Tto2")];
val _ = \<^assert> (String.isSubstring "timeout=~1s" (plain o_show));

val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Tto2")];
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T3: show reflects the timeout, and 0 renders as "unlimited"
rather than "0s".\<close>
ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "Tto3"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");

val (s_to5, _) = MCP_Repl.run "timeout" [("repl", "Tto3"), ("secs", "5")];
val _ = \<^assert> (s_to5 = "ok");
val (_, o_show5) = MCP_Repl.run "show" [("repl", "Tto3")];
val _ = \<^assert> (String.isSubstring "timeout=5s" (plain o_show5));

val (s_to0, _) = MCP_Repl.run "timeout" [("repl", "Tto3"), ("secs", "0")];
val _ = \<^assert> (s_to0 = "ok");
val (_, o_show0) = MCP_Repl.run "show" [("repl", "Tto3")];
val _ = \<^assert> (String.isSubstring "timeout=unlimited" (plain o_show0));

val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Tto3")];
val _ = \<^assert> (s_rm = "ok");
\<close>

section \<open>repl_pin (plans/repl_pin): T1..T4\<close>

text \<open>T1: pinning mid-proof is refused with the engine's message;
finishing the proof first makes it pin cleanly.\<close>
ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "Tpn1"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");
val (s_step, _) = MCP_Repl.run "step" [("repl", "Tpn1"), ("isar_text", "lemma True")];
val _ = \<^assert> (s_step = "ok");

val (s_pin_mid, o_pin_mid) = MCP_Repl.run "pin" [("repl", "Tpn1")];
val _ = \<^assert> (s_pin_mid = "error");
val _ = \<^assert> (String.isSubstring "in a proof state" (plain o_pin_mid));

val (s_fin, _) = MCP_Repl.run "step" [("repl", "Tpn1"), ("isar_text", "by simp")];
val _ = \<^assert> (s_fin = "ok");
val (s_pin, _) = MCP_Repl.run "pin" [("repl", "Tpn1")];
val _ = \<^assert> (s_pin = "ok");

val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Tpn1")];
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T2: the pin round trip -- A defines a constant, pins, B is
init'd from pin@A and can use A's definition, and B's origin prints
"pin@A".\<close>
ML \<open>
val (s_a, _) = MCP_Repl.run "init" [("repl", "Tpn2A"), ("theories", main)];
val _ = \<^assert> (s_a = "ok");
val (s_def, _) = MCP_Repl.run "step"
  [("repl", "Tpn2A"), ("isar_text", "definition pn2_const :: nat where \"pn2_const = 42\"")];
val _ = \<^assert> (s_def = "ok");
val (s_pin, _) = MCP_Repl.run "pin" [("repl", "Tpn2A")];
val _ = \<^assert> (s_pin = "ok");

val (s_b, _) = MCP_Repl.run "init" [("repl", "Tpn2B"), ("theories", "pin@Tpn2A")];
val _ = \<^assert> (s_b = "ok");
val (_, o_show_b) = MCP_Repl.run "show" [("repl", "Tpn2B")];
val _ = \<^assert> (String.isSubstring "pin@Tpn2A" (plain o_show_b));

val (s_use, _) = MCP_Repl.run "step"
  [("repl", "Tpn2B"), ("isar_text", "lemma \"pn2_const = 42\" by (simp add: pn2_const_def)")];
val _ = \<^assert> (s_use = "ok");

val (s_rm_b, _) = MCP_Repl.run "remove" [("repl", "Tpn2B")];
val _ = \<^assert> (s_rm_b = "ok");
val (s_rm_a, _) = MCP_Repl.run "remove" [("repl", "Tpn2A")];
val _ = \<^assert> (s_rm_a = "ok");
\<close>

text \<open>T3: staleness -- mutating A after the pin marks it stale, a NEW
init from pin@A errors, existing dependents are untouched, and
re-pinning clears the staleness for future inits.\<close>
ML \<open>
val (s_a, _) = MCP_Repl.run "init" [("repl", "Tpn3A"), ("theories", main)];
val _ = \<^assert> (s_a = "ok");
val (s_pin, _) = MCP_Repl.run "pin" [("repl", "Tpn3A")];
val _ = \<^assert> (s_pin = "ok");

val (s_b, _) = MCP_Repl.run "init" [("repl", "Tpn3B"), ("theories", "pin@Tpn3A")];
val _ = \<^assert> (s_b = "ok");

(*any mutation on A marks its pin stale, even an unrelated step -- kept
at theory level (closed in one step) so A can be re-pinned below*)
val (s_step_a, _) =
  MCP_Repl.run "step" [("repl", "Tpn3A"), ("isar_text", "lemma tpn3: True by simp")];
val _ = \<^assert> (s_step_a = "ok");
val (_, o_show_a) = MCP_Repl.run "show" [("repl", "Tpn3A")];
val _ = \<^assert> (String.isSubstring "pinned [stale]" (plain o_show_a));

val (s_c, o_c) = MCP_Repl.run "init" [("repl", "Tpn3C"), ("theories", "pin@Tpn3A")];
val _ = \<^assert> (s_c = "error");
val _ = \<^assert> (String.isSubstring "is stale" (plain o_c));

(*B, initialized before the staleness, still steps normally*)
val (s_b_step, _) = MCP_Repl.run "step" [("repl", "Tpn3B"), ("isar_text", "lemma tpn3b: True")];
val _ = \<^assert> (s_b_step = "ok");

val (s_repin, _) = MCP_Repl.run "pin" [("repl", "Tpn3A")];
val _ = \<^assert> (s_repin = "ok");
val (s_c2, _) = MCP_Repl.run "init" [("repl", "Tpn3C"), ("theories", "pin@Tpn3A")];
val _ = \<^assert> (s_c2 = "ok");

val (s_rm_c, _) = MCP_Repl.run "remove" [("repl", "Tpn3C")];
val _ = \<^assert> (s_rm_c = "ok");
val (s_rm_b, _) = MCP_Repl.run "remove" [("repl", "Tpn3B")];
val _ = \<^assert> (s_rm_b = "ok");
val (s_rm_a, _) = MCP_Repl.run "remove" [("repl", "Tpn3A")];
val _ = \<^assert> (s_rm_a = "ok");
\<close>

text \<open>T4: re-pinning bumps the version -- pinning twice both succeed
(the version bump itself is exercised end to end via plans/repl_rebase
T1).\<close>
ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "Tpn4"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");
val (s_pin1, _) = MCP_Repl.run "pin" [("repl", "Tpn4")];
val _ = \<^assert> (s_pin1 = "ok");
val (s_pin2, _) = MCP_Repl.run "pin" [("repl", "Tpn4")];
val _ = \<^assert> (s_pin2 = "ok");

val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Tpn4")];
val _ = \<^assert> (s_rm = "ok");
\<close>

section \<open>repl_unpin (plans/repl_unpin): T1..T2\<close>

text \<open>T1: unpinning without a pin errors naming the repl.\<close>
ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "Tup1"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");
val (s_unpin, o_unpin) = MCP_Repl.run "unpin" [("repl", "Tup1")];
val _ = \<^assert> (s_unpin = "error");
val _ = \<^assert> (String.isSubstring "is not pinned" (plain o_unpin));

val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Tup1")];
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T2: dependents block unpin; removing the dependent frees it; A's
steps are unchanged throughout.\<close>
ML \<open>
val (s_a, _) = MCP_Repl.run "init" [("repl", "Tup2A"), ("theories", main)];
val _ = \<^assert> (s_a = "ok");
val (s_step_a, _) =
  MCP_Repl.run "step" [("repl", "Tup2A"), ("isar_text", "lemma tup2: True by simp")];
val _ = \<^assert> (s_step_a = "ok");
val (s_pin, _) = MCP_Repl.run "pin" [("repl", "Tup2A")];
val _ = \<^assert> (s_pin = "ok");
val (s_b, _) = MCP_Repl.run "init" [("repl", "Tup2B"), ("theories", "pin@Tup2A")];
val _ = \<^assert> (s_b = "ok");

val (s_unpin_blocked, o_unpin_blocked) = MCP_Repl.run "unpin" [("repl", "Tup2A")];
val _ = \<^assert> (s_unpin_blocked = "error");
val _ = \<^assert> (String.isSubstring "depend" (plain o_unpin_blocked));

val (_, o_show_before) = MCP_Repl.run "show" [("repl", "Tup2A")];
val _ = \<^assert> (String.isSubstring "1 steps" (plain o_show_before));

val (s_rm_b, _) = MCP_Repl.run "remove" [("repl", "Tup2B")];
val _ = \<^assert> (s_rm_b = "ok");
val (s_unpin, _) = MCP_Repl.run "unpin" [("repl", "Tup2A")];
val _ = \<^assert> (s_unpin = "ok");

val (_, o_show_after) = MCP_Repl.run "show" [("repl", "Tup2A")];
val after_txt = plain o_show_after;
val _ = \<^assert> (not (String.isSubstring "pinned" after_txt));
val _ = \<^assert> (String.isSubstring "1 steps" after_txt);

val (s_rm_a, _) = MCP_Repl.run "remove" [("repl", "Tup2A")];
val _ = \<^assert> (s_rm_a = "ok");
\<close>

section \<open>repl_rebase (plans/repl_rebase): T1..T4\<close>

text \<open>T1: the whole point, end to end -- A gains a definition and
re-pins; rebase B marks its steps stale; replay lets a NEW step in B
use A's new definition; a second rebase with nothing changed says
"already up to date".\<close>
ML \<open>
val (s_a, _) = MCP_Repl.run "init" [("repl", "Trb1A"), ("theories", main)];
val _ = \<^assert> (s_a = "ok");
val (s_pin1, _) = MCP_Repl.run "pin" [("repl", "Trb1A")];
val _ = \<^assert> (s_pin1 = "ok");

val (s_b, _) = MCP_Repl.run "init" [("repl", "Trb1B"), ("theories", "pin@Trb1A")];
val _ = \<^assert> (s_b = "ok");
val (s_b_step, _) =
  MCP_Repl.run "step" [("repl", "Trb1B"), ("isar_text", "lemma trb1b: True by simp")];
val _ = \<^assert> (s_b_step = "ok");

val (s_def, _) = MCP_Repl.run "step"
  [("repl", "Trb1A"), ("isar_text", "definition trb1_const :: nat where \"trb1_const = 7\"")];
val _ = \<^assert> (s_def = "ok");
val (s_pin2, _) = MCP_Repl.run "pin" [("repl", "Trb1A")];
val _ = \<^assert> (s_pin2 = "ok");

val (s_rebase, o_rebase) = MCP_Repl.run "rebase" [("repl", "Trb1B")];
val _ = \<^assert> (s_rebase = "ok");
val _ = \<^assert> (String.isSubstring "marked stale" (plain o_rebase));

val (s_replay, _) = MCP_Repl.run "replay" [("repl", "Trb1B")];
val _ = \<^assert> (s_replay = "ok");
val (s_new_step, _) = MCP_Repl.run "step"
  [("repl", "Trb1B"), ("isar_text", "lemma \"trb1_const = 7\" by (simp add: trb1_const_def)")];
val _ = \<^assert> (s_new_step = "ok");

val (s_rebase_again, o_rebase_again) = MCP_Repl.run "rebase" [("repl", "Trb1B")];
val _ = \<^assert> (s_rebase_again = "ok");
val _ = \<^assert> (String.isSubstring "already up to date" (plain o_rebase_again));

val (s_rm_b, _) = MCP_Repl.run "remove" [("repl", "Trb1B")];
val _ = \<^assert> (s_rm_b = "ok");
val (s_rm_a, _) = MCP_Repl.run "remove" [("repl", "Trb1A")];
val _ = \<^assert> (s_rm_a = "ok");
\<close>

text \<open>T2: a stale pin blocks rebase with the resolve_spec error.\<close>
ML \<open>
val (s_a, _) = MCP_Repl.run "init" [("repl", "Trb2A"), ("theories", main)];
val _ = \<^assert> (s_a = "ok");
val (s_pin, _) = MCP_Repl.run "pin" [("repl", "Trb2A")];
val _ = \<^assert> (s_pin = "ok");
val (s_b, _) = MCP_Repl.run "init" [("repl", "Trb2B"), ("theories", "pin@Trb2A")];
val _ = \<^assert> (s_b = "ok");

(*mutate A WITHOUT re-pinning -- its pin is now stale*)
val (s_step_a, _) = MCP_Repl.run "step" [("repl", "Trb2A"), ("isar_text", "lemma trb2: True")];
val _ = \<^assert> (s_step_a = "ok");

val (s_rebase, o_rebase) = MCP_Repl.run "rebase" [("repl", "Trb2B")];
val _ = \<^assert> (s_rebase = "error");
val _ = \<^assert> (String.isSubstring "is stale" (plain o_rebase));

val (s_rm_b, _) = MCP_Repl.run "remove" [("repl", "Trb2B")];
val _ = \<^assert> (s_rm_b = "ok");
val (s_rm_a, _) = MCP_Repl.run "remove" [("repl", "Trb2A")];
val _ = \<^assert> (s_rm_a = "ok");
\<close>

text \<open>T3: non-From_Theory origins (forks) are refused.\<close>
ML \<open>
val (s_a, _) = MCP_Repl.run "init" [("repl", "Trb3A"), ("theories", main)];
val _ = \<^assert> (s_a = "ok");
val (s_pin, _) = MCP_Repl.run "pin" [("repl", "Trb3A")];
val _ = \<^assert> (s_pin = "ok");
val (s_b, _) = MCP_Repl.run "init" [("repl", "Trb3B"), ("theories", "pin@Trb3A")];
val _ = \<^assert> (s_b = "ok");
val (s_f, _) = MCP_Repl.run "fork" [("repl", "Trb3B"), ("new_repl", "Trb3C"), ("state_idx", "0")];
val _ = \<^assert> (s_f = "ok");

val (s_rebase, o_rebase) = MCP_Repl.run "rebase" [("repl", "Trb3C")];
val _ = \<^assert> (s_rebase = "error");
val _ = \<^assert> (String.isSubstring "does not support rebase" (plain o_rebase));

val (s_rm_c, _) = MCP_Repl.run "remove" [("repl", "Trb3C")];
val _ = \<^assert> (s_rm_c = "ok");
val (s_rm_b, _) = MCP_Repl.run "remove" [("repl", "Trb3B")];
val _ = \<^assert> (s_rm_b = "ok");
val (s_rm_a, _) = MCP_Repl.run "remove" [("repl", "Trb3A")];
val _ = \<^assert> (s_rm_a = "ok");
\<close>

text \<open>T4: rebase does NOT replay -- state is unchanged until replay
runs, only the stale marks and the base theory move.\<close>
ML \<open>
val (s_a, _) = MCP_Repl.run "init" [("repl", "Trb4A"), ("theories", main)];
val _ = \<^assert> (s_a = "ok");
val (s_pin1, _) = MCP_Repl.run "pin" [("repl", "Trb4A")];
val _ = \<^assert> (s_pin1 = "ok");
val (s_b, _) = MCP_Repl.run "init" [("repl", "Trb4B"), ("theories", "pin@Trb4A")];
val _ = \<^assert> (s_b = "ok");
val (s_b_step, _) = MCP_Repl.run "step" [("repl", "Trb4B"), ("isar_text", "lemma trb4b: True")];
val _ = \<^assert> (s_b_step = "ok");
val (_, o_state_before) = MCP_Repl.run "state" [("repl", "Trb4B"), ("state_idx", "-1")];
val state_before = plain o_state_before;

val (s_def, _) = MCP_Repl.run "step"
  [("repl", "Trb4A"), ("isar_text", "definition trb4_const :: nat where \"trb4_const = 3\"")];
val _ = \<^assert> (s_def = "ok");
val (s_pin2, _) = MCP_Repl.run "pin" [("repl", "Trb4A")];
val _ = \<^assert> (s_pin2 = "ok");

val (s_rebase, _) = MCP_Repl.run "rebase" [("repl", "Trb4B")];
val _ = \<^assert> (s_rebase = "ok");
val (_, o_show) = MCP_Repl.run "show" [("repl", "Trb4B")];
val _ = \<^assert> (count_substring "[stale]" (plain o_show) = 1);

(*state -1 is UNCHANGED pre-replay -- rebase alone does not re-execute*)
val (_, o_state_after) = MCP_Repl.run "state" [("repl", "Trb4B"), ("state_idx", "-1")];
val _ = \<^assert> (plain o_state_after = state_before);

val (s_replay, _) = MCP_Repl.run "replay" [("repl", "Trb4B")];
val _ = \<^assert> (s_replay = "ok");

val (s_rm_b, _) = MCP_Repl.run "remove" [("repl", "Trb4B")];
val _ = \<^assert> (s_rm_b = "ok");
val (s_rm_a, _) = MCP_Repl.run "remove" [("repl", "Trb4A")];
val _ = \<^assert> (s_rm_a = "ok");
\<close>

section \<open>sledgehammer (plans/sledgehammer): T2\<close>

text \<open>T2: sledgehammer requires the REPL to be mid-proof
(Toplevel.proof_of raises on a theory-level state). A fresh REPL with no
steps is at theory level, so sledgehammer must fail cleanly with a status
error, not hang or return an empty reply.\<close>

ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "Tsh2"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");
val (s_sh, _) = MCP_Repl.run "sledgehammer" [("repl", "Tsh2")];
val _ = \<^assert> (s_sh = "error");
val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Tsh2")];
val _ = \<^assert> (s_rm = "ok");
\<close>

section \<open>find_theorems (plans/find_theorems): T2..T4\<close>

text \<open>T2: a malformed query (unbalanced quotes) is a status error from
Find_Theorems.read_query, not a crash.\<close>

ML \<open>
val (s_init, _) = MCP_Repl.run "init" [("repl", "Tft2"), ("theories", main)];
val _ = \<^assert> (s_init = "ok");
val (s_ft, _) = MCP_Repl.run "find_theorems" [("repl", "Tft2"), ("query", "\"_ + _")];
val _ = \<^assert> (s_ft = "error");
val (s_rm, _) = MCP_Repl.run "remove" [("repl", "Tft2")];
val _ = \<^assert> (s_rm = "ok");
\<close>

text \<open>T3: results respect max_results and the tally says so; max_results
0 means unlimited (limit = NONE per ir.ML). "name:conj" matches several
theorems in Main (conjI, conjE, conjunct1, conjunct2, ...).\<close>

ML \<open>
val (s_init3, _) = MCP_Repl.run "init" [("repl", "Tft3"), ("theories", main)];
val _ = \<^assert> (s_init3 = "ok");

val (s_ft3a, o_ft3a) =
  MCP_Repl.run "find_theorems" [("repl", "Tft3"), ("query", "name:conj"), ("max_results", "3")];
val text3a = plain o_ft3a;
val _ = \<^assert> (s_ft3a = "ok");
(*empirical finding: Find_Theorems.find_theorems_cmd returns opt_found =
  NONE whenever a limit cuts the search short (the exhaustive count is
  unknown), so a bounded query's tally is "displaying N theorem(s)", not
  "found N (M displayed)" as one might optimistically assume -- the
  found/displayed split only applies when the FULL unbounded search
  completes and later gets re-reported with a smaller returned count,
  which ir.ML's own limit-only-truncates-the-cmd-call design does not
  produce. Pin the real shape here.*)
val _ = \<^assert> (String.isSubstring "displaying 3 theorem(s)" text3a);
val lines3a = filter (fn l => l <> "") (String.fields (fn c => c = #"\n") text3a);
val _ = \<^assert> (length lines3a - 1 = 3) (*tally line + 3 theorem lines*);

val (s_ft3b, o_ft3b) =
  MCP_Repl.run "find_theorems" [("repl", "Tft3"), ("query", "name:conj"), ("max_results", "0")];
val text3b = plain o_ft3b;
val _ = \<^assert> (s_ft3b = "ok");
val _ = \<^assert> (String.isSubstring "found " text3b) (*unlimited: exhaustive count is known*);
val lines3b = filter (fn l => l <> "") (String.fields (fn c => c = #"\n") text3b);
val _ = \<^assert> (length lines3b - 1 > 3) (*strictly more hits than the bounded call showed*);

val (s_rm3, _) = MCP_Repl.run "remove" [("repl", "Tft3")];
val _ = \<^assert> (s_rm3 = "ok");
\<close>

text \<open>T4: goal-based criteria (intro) work mid-proof and return plausible
rules for a conjunction goal; at theory level with no goal, pin the
observed behavior (a status error, since Find_Theorems.find_theorems_cmd
needs a goal for goal-based criteria).\<close>

ML \<open>
val (s_init4, _) = MCP_Repl.run "init" [("repl", "Tft4"), ("theories", main)];
val _ = \<^assert> (s_init4 = "ok");

val (s_ft4a, _) = MCP_Repl.run "find_theorems" [("repl", "Tft4"), ("query", "intro")];
val _ = \<^assert> (s_ft4a = "error") (*theory level, no goal*);

val (s_step4, _) =
  MCP_Repl.run "step" [("repl", "Tft4"), ("isar_text", "lemma \"True \<and> True\"")];
val _ = \<^assert> (s_step4 = "ok");
val (s_ft4b, o_ft4b) = MCP_Repl.run "find_theorems" [("repl", "Tft4"), ("query", "intro")];
val _ = \<^assert> (s_ft4b = "ok");
val _ = \<^assert> (String.isSubstring "conjI" (plain o_ft4b));

val (s_rm4, _) = MCP_Repl.run "remove" [("repl", "Tft4")];
val _ = \<^assert> (s_rm4 = "ok");
\<close>

section \<open>find_theorems context promotion (plans/find_theorems, "context
promotion"): T7..T10\<close>

text \<open>T7: theory-context search works from the global context -- both
the base-name spelling "Main" and its own long form resolve to the same
theory, and results match a fresh-repl search on the same query.\<close>

ML \<open>
val (s_ft7a, o_ft7a) = MCP_Repl.run "find_theorems" [("theory", "Main"), ("query", "name:conjI")];
val _ = \<^assert> (s_ft7a = "ok");
val _ = \<^assert> (String.isSubstring "conjI" (plain o_ft7a));

val (s_init7, _) = MCP_Repl.run "init" [("repl", "Tft7"), ("theories", main)];
val _ = \<^assert> (s_init7 = "ok");
val (s_ft7b, o_ft7b) = MCP_Repl.run "find_theorems" [("repl", "Tft7"), ("query", "name:conjI")];
val _ = \<^assert> (s_ft7b = "ok");
val _ = \<^assert> (plain o_ft7a = plain o_ft7b) (*same hits, theory context == fresh repl context*);
val (s_rm7, _) = MCP_Repl.run "remove" [("repl", "Tft7")];
val _ = \<^assert> (s_rm7 = "ok");
\<close>

text \<open>T8: the default context (neither repl nor theory) searches the base
image -- the headline use case of the promotion: no repl need ever exist.\<close>

ML \<open>
val (s_ft8, o_ft8) = MCP_Repl.run "find_theorems" [("query", "name:conjI")];
val _ = \<^assert> (s_ft8 = "ok");
val _ = \<^assert> (String.isSubstring "conjI" (plain o_ft8));
\<close>

text \<open>T10: goal-based criteria without a repl (theory context, and the
default context) error cleanly -- Find_Theorems.find_theorems_cmd's own
"Current goal required" message, with the promotion's added pointer at
using a repl mid-proof.\<close>

ML \<open>
val (s_ft10a, o_ft10a) = MCP_Repl.run "find_theorems" [("theory", "Main"), ("query", "intro")];
val _ = \<^assert> (s_ft10a = "error");
val _ = \<^assert> (String.isSubstring "no goal" (plain o_ft10a));
val _ = \<^assert> (String.isSubstring "repl mid-proof" (plain o_ft10a));

val (s_ft10b, o_ft10b) = MCP_Repl.run "find_theorems" [("query", "intro")];
val _ = \<^assert> (s_ft10b = "error");
val _ = \<^assert> (String.isSubstring "no goal" (plain o_ft10b));
val _ = \<^assert> (String.isSubstring "repl mid-proof" (plain o_ft10b));
\<close>

section \<open>find_definition (plans/find_definition): T1..T5\<close>

text \<open>T1: kind coverage -- one hit each for a const, a type introduced by
datatype, a class, a fact, a locale, a method, an attribute.\<close>

ML \<open>
fun fd_ok kind name args =
  let val (s, o_) = MCP_Repl.run "find_definition" args
  in \<^assert> (s = "ok" andalso String.isSubstring ("kind: " ^ kind) (plain o_) andalso
             String.isSubstring name (plain o_))
  end;

val _ = fd_ok "const" "rev" [("name", "rev"), ("kind", "const")];
val _ = fd_ok "type" "list" [("name", "list"), ("kind", "type")];
val _ = fd_ok "class" "ord" [("name", "ord"), ("kind", "class")];
val _ = fd_ok "fact" "conjI" [("name", "conjI"), ("kind", "fact")];
val _ = fd_ok "locale" "order" [("name", "order"), ("kind", "locale")];
val _ = fd_ok "method" "simp" [("name", "simp"), ("kind", "method")];
val _ = fd_ok "attribute" "simp" [("name", "simp"), ("kind", "attribute")];
\<close>

text \<open>T2: the type-space claim specifically -- datatype, typedef,
type_synonym and record all land their constructor in the type name
space.\<close>

ML \<open>
val (s_ty1, o_ty1) = MCP_Repl.run "find_definition" [("name", "list"), ("kind", "type")];
val _ = \<^assert> (s_ty1 = "ok" andalso String.isSubstring "kind: type" (plain o_ty1));
(*nat is a typedecl/datatype-family type from the base image*)
val (s_ty2, o_ty2) = MCP_Repl.run "find_definition" [("name", "nat"), ("kind", "type")];
val _ = \<^assert> (s_ty2 = "ok" andalso String.isSubstring "kind: type" (plain o_ty2));
\<close>

text \<open>T3: an unknown name is ok with "no definition found" (probe-safe,
like the discovery tools), NOT a status error; a bad kind value IS an
error (closed enum).\<close>

ML \<open>
val (s_unk, o_unk) = MCP_Repl.run "find_definition" [("name", "no_such_name_xyz")];
val _ = \<^assert> (s_unk = "ok" andalso String.isSubstring "no definition found" (plain o_unk));

val (s_badk, o_badk) =
  MCP_Repl.run "find_definition" [("name", "rev"), ("kind", "bogus")];
val _ = \<^assert> (s_badk = "error" andalso String.isSubstring "bogus" (plain o_badk));
\<close>

text \<open>T4: context selection -- a constant defined only in a repl's context
is found with repl=..., not without; theory=<image thy> scopes to that
theory's view.\<close>

ML \<open>
val (s_init_fd, _) = MCP_Repl.run "init" [("repl", "Tfd4"), ("theories", main)];
val _ = \<^assert> (s_init_fd = "ok");
val (s_def_fd, _) =
  MCP_Repl.run "step" [("repl", "Tfd4"), ("isar_text", "definition my_foo :: nat where \"my_foo = 0\"")];
val _ = \<^assert> (s_def_fd = "ok");

val (s_fd4a, o_fd4a) =
  MCP_Repl.run "find_definition" [("name", "my_foo"), ("repl", "Tfd4"), ("kind", "const")];
val _ = \<^assert> (s_fd4a = "ok" andalso String.isSubstring "my_foo" (plain o_fd4a));

val (s_fd4b, o_fd4b) = MCP_Repl.run "find_definition" [("name", "my_foo"), ("kind", "const")];
val _ = \<^assert> (s_fd4b = "ok" andalso String.isSubstring "no definition found" (plain o_fd4b));

val (s_fd4c, o_fd4c) =
  MCP_Repl.run "find_definition" [("name", "conjI"), ("theory", "Main"), ("kind", "fact")];
val _ = \<^assert> (s_fd4c = "ok" andalso String.isSubstring "conjI" (plain o_fd4c));

val (s_rm_fd, _) = MCP_Repl.run "remove" [("repl", "Tfd4")];
val _ = \<^assert> (s_rm_fd = "ok");
\<close>

text \<open>T5: source-block enrichment -- graceful position-only fallback when
no segments are available. Two branches, both unavailable-by-construction
within THIS build: a repl-inline definition lives in an anonymous
theory value the segment table never indexes (Name_Space.theory_name
reports the repl id itself, e.g. "Tfd5", not a Thy_Info key), and a base
heap theory (HOL.List, for "rev") was built without record_theories.
The POSITIVE branch -- a record_theories theory's hit carrying the whole
defining command text -- needs a theory that is Thy_Info-registered as
already loaded, which this session's OWN theories are not until the
build finishes and the heap is loaded fresh by a later process (the same
constraint noted for entities' heap-survival claim); verified instead
via a live mcp_server probe against the MCP_Fixture_Nav fixture, see
plans/find_definition.\<close>

ML \<open>
val (s_init_fd5, _) = MCP_Repl.run "init" [("repl", "Tfd5"), ("theories", main)];
val _ = \<^assert> (s_init_fd5 = "ok");
val (s_def_fd5, _) =
  MCP_Repl.run "step" [("repl", "Tfd5"),
    ("isar_text", "definition my_bar :: nat where \"my_bar = 1\"")];
val _ = \<^assert> (s_def_fd5 = "ok");

val (s_fd5c, o_fd5c) =
  MCP_Repl.run "find_definition" [("name", "my_bar"), ("repl", "Tfd5"), ("kind", "const")];
val _ = \<^assert> (s_fd5c = "ok" andalso String.isSubstring "my_bar" (plain o_fd5c) andalso
                not (String.isSubstring "definition my_bar" (plain o_fd5c)));

val (s_fd5b, o_fd5b) =
  MCP_Repl.run "find_definition" [("name", "rev"), ("kind", "const")];
val _ = \<^assert> (s_fd5b = "ok" andalso String.isSubstring "kind: const" (plain o_fd5b) andalso
                not (String.isSubstring "\n\n" (plain o_fd5b)) (*no source block appended*));

val (s_rm_fd5, _) = MCP_Repl.run "remove" [("repl", "Tfd5")];
val _ = \<^assert> (s_rm_fd5 = "ok");
\<close>

section \<open>Output routing (the Private_Output wrappers; spec phase-2 boxes)\<close>

ML \<open>
(*writeln inside a registered group's future lands only in that
  request's buffer: two concurrent requests don't mix*)
val (s_ora, _) = MCP_Repl.run "init" [("repl", "Out_A"), ("theories", main)];
val (s_orb, _) = MCP_Repl.run "init" [("repl", "Out_B"), ("theories", main)];
val _ = \<^assert> (s_ora = "ok" andalso s_orb = "ok");

val fut_a =
  MCP_Repl.fork_run "step"
    [("repl", "Out_A"), ("isar_text", "ML_command \<open>writeln \"ROUTE_TAG_A\"\<close>")];
val fut_b =
  MCP_Repl.fork_run "step"
    [("repl", "Out_B"), ("isar_text", "ML_command \<open>writeln \"ROUTE_TAG_B\"\<close>")];
val (s_a, o_a) = Future.join fut_a;
val (s_b, o_b) = Future.join fut_b;
val _ = \<^assert> (s_a = "ok" andalso s_b = "ok");
val _ = \<^assert> (String.isSubstring "ROUTE_TAG_A" (plain o_a));
val _ = \<^assert> (not (String.isSubstring "ROUTE_TAG_B" (plain o_a)));
val _ = \<^assert> (String.isSubstring "ROUTE_TAG_B" (plain o_b));
val _ = \<^assert> (not (String.isSubstring "ROUTE_TAG_A" (plain o_b)));

val _ = MCP_Repl.run "remove" [("repl", "Out_A")];
val _ = MCP_Repl.run "remove" [("repl", "Out_B")];
\<close>

ML \<open>
(*output outside any registered group falls through to the ORIGINAL
  functions -- the wrappers are transparent when idle. Reset first so
  install_wrappers re-wraps over our probe as "original", trigger any
  request to install, then observe a plain writeln reach the probe.*)
val _ = MCP_Repl.reset ();
val routing_probe = Synchronized.var "routing_probe" ([]: string list);
val orig_writeln = ! Private_Output.writeln_fn;
val _ =
  Private_Output.writeln_fn :=
    (fn ss => Synchronized.change routing_probe (cons (implode ss)));
val (s_idle, _) = MCP_Repl.run "repls" [];
val _ = writeln "IDLE_FALLTHROUGH";
val _ = Private_Output.writeln_fn := orig_writeln;
val _ = MCP_Repl.reset ();
val _ = \<^assert> (s_idle = "ok");
val _ =
  \<^assert> (exists (String.isSubstring "IDLE_FALLTHROUGH")
    (Synchronized.value routing_probe));
\<close>

(*drop this session's repl churn and wrapper state -- see MCP_Repl.reset*)
ML \<open>MCP_Repl.reset ()\<close>

section \<open>Wave 1 (plans/ml_builtin_migration): repl_show/repl_text/repl_back
as capture-form mcp_tools\<close>

text \<open>A9 interface preservation, structural half: each tool has exactly
the deleted Builtin_Tool row's single \<open>repl :: string\<close> required param
and annotation bucket. The behavioral half (does the tool actually call
\<^ML>\<open>Ir.show\<close>/\<^ML>\<open>Ir.text\<close>/\<^ML>\<open>Ir.back\<close>) is exercised at the bridge
layer (mcp_bridge_tests.scala) for repl_show, plus Ir_Tests.thy's direct
\<^ML>\<open>Ir.back\<close> coverage for repl_back's semantics -- this block only
checks the declaration matches the interface the deleted row advertised.\<close>
ML \<open>
val context = Context.Proof \<^context>;

fun single_repl_param (tool: MCP_Tool.tool) =
  (case #params tool of
    [p] =>
      #name p = "repl" andalso #typ p = MCP_Tool.String andalso
      #required p andalso #default p = NONE
  | _ => false);

val repl_show_tool = MCP_Tool.get context "MCP_Repl.repl_show";
val repl_text_tool = MCP_Tool.get context "MCP_Repl.repl_text";
val repl_back_tool = MCP_Tool.get context "MCP_Repl.repl_back";

val _ = \<^assert> (#form repl_show_tool = MCP_Tool.Capture);
val _ = \<^assert> (#form repl_text_tool = MCP_Tool.Capture);
val _ = \<^assert> (#form repl_back_tool = MCP_Tool.Capture);

val _ = \<^assert> (single_repl_param repl_show_tool);
val _ = \<^assert> (single_repl_param repl_text_tool);
val _ = \<^assert> (single_repl_param repl_back_tool);

val _ = \<^assert> (#annotations repl_show_tool = MCP_Tool.read_only);
val _ = \<^assert> (#annotations repl_text_tool = MCP_Tool.read_only);
val _ = \<^assert> (#annotations repl_back_tool = MCP_Tool.destructive);
\<close>

text \<open>the surviving dispatcher cases for "show"/"text" (resource reads,
isabelle://repl/{id} and .../text) still work; "back" is gone from the
dispatcher entirely (superseded by the mcp_tool declaration above), and
calling it now fails as an unknown function, not a silent success.\<close>
ML \<open>
val (s_show_alive, _) = MCP_Repl.run "show" [("repl", "nonexistent-repl-id")];
val _ = \<^assert> (s_show_alive = "error"); (*repl id lookup fails, not the fname*)
val (s_text_alive, _) = MCP_Repl.run "text" [("repl", "nonexistent-repl-id")];
val _ = \<^assert> (s_text_alive = "error");
val (s_back_gone, o_back_gone) = MCP_Repl.run "back" [("repl", "nonexistent-repl-id")];
val _ = \<^assert> (s_back_gone = "error" andalso String.isSubstring "unknown function" (plain o_back_gone));
\<close>

text \<open>A3 regression: \<open>Ir.show\<close>'s token rendering (\<open>Markup.markups\<close> in
\<open>Ir.render_isar_text\<close>) emits yxml markup UNCONDITIONALLY, the same
failure mode found against Sledgehammer's \<open>Active.sendback\<close>
(Ir_Tests.thy) -- plain print mode alone does not suffice.
\<^ML>\<open>MCP_Combinators.capture\<close> now strips unconditionally; this pins the
fix against the exact tool that first exposed the gap.\<close>
ML \<open>
val _ = MCP_Repl.run "init" [("repl", "ShowStrip"), ("theories", main)];
val _ = MCP_Repl.run "step" [("repl", "ShowStrip"), ("isar_text", "lemma True")];
val show_out = MCP_Tool.run \<^context> "MCP_Repl.repl_show" [("repl", "ShowStrip")];
val _ = \<^assert> (not (String.isSubstring (chr 5) show_out));
val _ = \<^assert> (not (String.isSubstring (chr 6) show_out));
val _ = \<^assert> (String.isSubstring "ShowStrip" show_out);
val _ = MCP_Repl.run "remove" [("repl", "ShowStrip")];
\<close>

end
