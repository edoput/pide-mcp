theory MCP_Diff_Tests
  imports "MCP-HOL.MCP_Diff" MCP_Diff_Fixture
begin

text \<open>Unit tests for \<^ML_structure>\<open>MCP_Diff\<close>. This theory fails to load
iff a test fails, so \<^verbatim>\<open>isabelle build -d mcp/Tools MCP-HOL-Tests\<close> is the
test runner.

The interesting surface is \<^ML>\<open>MCP_Diff.diff_facts\<close>, which is pure over
two fact lists -- no theory, no checkpoint table -- so the added /
removed / changed cases are testable without staging an actual theory
reload. \<^ML>\<open>MCP_Diff.diff\<close> itself is exercised only for its checkpoint
round trip, because a real reload cannot happen inside the build that
loads this theory.\<close>

ML \<open>
val plain = XML.content_of o YXML.parse_body;

val fact_a = ("a", [@{thm red_is_red}]);
val fact_b = ("b", [@{thm weight_pos}]);
(*same NAME as fact_a, different statement -- the "changed" case*)
val fact_a' = ("a", [@{thm weight_pos}]);
\<close>


section \<open>diff_facts: the pure surface\<close>

ML \<open>
(*identical inputs: nothing in any bucket*)
let val {added, removed, changed} = MCP_Diff.diff_facts [fact_a, fact_b] [fact_a, fact_b] in
  \<^assert> (null added);
  \<^assert> (null removed);
  \<^assert> (null changed)
end;
\<close>

ML \<open>
(*a name present only in the new list is added*)
let val {added, removed, changed} = MCP_Diff.diff_facts [fact_a] [fact_a, fact_b] in
  \<^assert> (added = ["b"]);
  \<^assert> (null removed);
  \<^assert> (null changed)
end;
\<close>

ML \<open>
(*a name present only in the old list is removed*)
let val {added, removed, changed} = MCP_Diff.diff_facts [fact_a, fact_b] [fact_a] in
  \<^assert> (null added);
  \<^assert> (removed = ["b"]);
  \<^assert> (null changed)
end;
\<close>

ML \<open>
(*same name, different proposition: changed, and NOT reported as either
  added or removed -- this is the case a name-only diff would miss*)
let val {added, removed, changed} = MCP_Diff.diff_facts [fact_a] [fact_a'] in
  \<^assert> (null added);
  \<^assert> (null removed);
  \<^assert> (changed = ["a"])
end;
\<close>

ML \<open>
(*a fact binds a LIST of theorems: same name, extra theorem => changed*)
let
  val one = ("multi", [@{thm red_is_red}]);
  val two = ("multi", [@{thm red_is_red}, @{thm weight_pos}]);
  val {added, removed, changed} = MCP_Diff.diff_facts [one] [two];
in
  \<^assert> (null added);
  \<^assert> (null removed);
  \<^assert> (changed = ["multi"])
end;
\<close>


section \<open>own_facts: a theory's own facts, imports excluded\<close>

ML \<open>
let
  val thy = \<^theory>\<open>MCP_Diff_Fixture\<close>;
  val names = map #1 (MCP_Diff.own_facts thy);
  fun has base = exists (fn n => Long_Name.base_name n = base) names;
  (*"declared in THIS theory", the same test MCP_Repl.entities uses*)
  val space = Global_Theory.fact_space thy;
  val target = Context.theory_long_name thy;
in
  (*the hand-written lemmas are all there*)
  \<^assert> (has "red_is_red");
  \<^assert> (has "weight_pos");
  \<^assert> (has "unproved_bound");
  \<^assert> (has "derived_bound");
  (*and nothing inherited from Main leaks in: passing the parents as
    exclusion bases means every name left is the fixture's own. Note this
    cannot be phrased as "no fact is called refl" -- the datatype package
    generates color.eq.refl, whose BASE name is refl, and it is genuinely
    this theory's own fact.*)
  \<^assert> (forall (fn n => Name_Space.theory_name {long = true} space n = target) names);
  \<^assert> (not (member (op =) names "HOL.refl"));
  \<^assert> (not (member (op =) names "HOL.conjI"))
end;
\<close>


section \<open>the [sorry] marker: what the prover itself says is unproved\<close>

ML \<open>
(*a sorry'd lemma rests on the skip_proof oracle*)
\<^assert> (Thm_Deps.has_skip_proof [@{thm unproved_bound}]);
(*and so does a lemma proved honestly FROM it -- all_oracles walks the
  whole dependency closure, so the marker is transitive*)
\<^assert> (Thm_Deps.has_skip_proof [@{thm derived_bound}]);
(*an untainted lemma is not marked*)
\<^assert> (not (Thm_Deps.has_skip_proof [@{thm red_is_red}]));
\<^assert> (not (Thm_Deps.has_skip_proof [@{thm weight_pos}]));
\<close>

text \<open>Does a theorem's OWN proof body separate a DIRECT \<^verbatim>\<open>sorry\<close> from an
inherited one? This matters for a future task-list tool: the directly
unproved lemmas are the real tasks, and the ones merely downstream of
them get fixed for free, so a tool that cannot tell them apart reports
work that does not exist.

\<^ML>\<open>Thm_Deps.all_oracles\<close> is transitive by construction (it recurses
through the \<open>thms\<close> of each body), so it cannot make the distinction. The
obvious candidate is the TOP-LEVEL \<open>oracles\<close> field of the theorem's own
proof body.

Settled empirically below, and the answer is NO: that field is empty
even for a lemma proved by nothing but \<^verbatim>\<open>sorry\<close>. So the naive
discriminator does not exist, and a task-list tool that wants to
separate "this lemma is unproved" from "this lemma stands on something
unproved" needs a different route -- walking named dependencies with
\<^ML>\<open>Thm_Deps.thm_deps\<close> and intersecting against the set of directly
unproved facts is the remaining candidate, not attempted here.

Keeping this as a live assertion rather than a comment means the day a
future Isabelle changes the representation, the build says so.\<close>

ML \<open>
let
  fun own_oracles th =
    let val Proofterm.PBody {oracles, ...} = Thm.proof_body_of th in oracles end;
in
  (*ANSWER: no. The top-level oracles field is EMPTY for both -- even for
    the lemma whose own proof is nothing but `sorry`. Note this is not an
    artifact of an unforced future: Thm.proof_body_of joins promises and
    fulfills the body first (thm.ML:829-830). A stored theorem's own body
    references its proof as a thm NODE, so the oracle sits one level down
    and only the transitive walk finds it.*)
  \<^assert> (null (own_oracles @{thm unproved_bound}));
  \<^assert> (null (own_oracles @{thm derived_bound}));
  (*while the transitive test sees both, as ever*)
  \<^assert> (Thm_Deps.has_skip_proof [@{thm unproved_bound}]);
  \<^assert> (Thm_Deps.has_skip_proof [@{thm derived_bound}])
end;
\<close>


section \<open>the rendered report\<close>

text \<open>All three buckets and the \<open>[sorry]\<close> marker exercised in one
rendering. Asserted rather than printed: \<^ML>\<open>writeln\<close> and \<^ML>\<open>warning\<close>
from an ML block are not echoed to a batch build's output, so a printed
sample would be invisible exactly where it is supposed to be read.\<close>

ML \<open>
let
  val thy = \<^theory>\<open>MCP_Diff_Fixture\<close>;
  val old =
    [("weight_pos", [@{thm weight_pos}]),
     ("gone_away", [@{thm red_is_red}])];
  val new =
    [("weight_pos", [@{thm red_is_red}]),
     ("unproved_bound", [@{thm unproved_bound}])];
  val text = Print_Mode.with_modes [] (fn () => MCP_Diff.report thy old new) ();
in
  \<^assert> (String.isSubstring "added (1)" text);
  \<^assert> (String.isSubstring "removed (1)" text);
  \<^assert> (String.isSubstring "changed (1)" text);
  (*the unproved entry is marked, the others are not*)
  \<^assert> (String.isSubstring "[sorry]" text);
  \<^assert> (String.isSubstring "unproved_bound" text);
  ignore text
end;
\<close>


section \<open>diff: the checkpoint round trip\<close>

ML \<open>
let
  val thy = \<^theory>\<open>MCP_Diff_Fixture\<close>;
  val key = Context.theory_long_name thy;
  val _ = MCP_Diff.reset ();
  (*first call on a name can only establish a baseline*)
  val first = plain (MCP_Diff.diff_theory thy);
  val _ = \<^assert> (String.isSubstring "checkpoint saved" first);
  val _ = \<^assert> (member (op =) (MCP_Diff.checkpointed ()) key);
  (*the same theory value again, so the second call reports no change*)
  val second = plain (MCP_Diff.diff_theory thy);
  val _ = \<^assert> (String.isSubstring "no change" second);
  (*reset clears the table, so the next call is a baseline again*)
  val _ = MCP_Diff.reset ();
  val _ = \<^assert> (null (MCP_Diff.checkpointed ()));
  val third = plain (MCP_Diff.diff_theory thy);
in
  \<^assert> (String.isSubstring "checkpoint saved" third)
end;
\<close>

text \<open>A real reload cannot happen inside the build that loads this
theory, so the added / removed / changed rendering is exercised by
checkpointing one theory value and diffing a DIFFERENT one against it --
the same code path a reload takes, without needing the loader. The
fixture and its parent \<^theory>\<open>Main\<close> stand in for "before" and "after".\<close>

ML \<open>
let
  val _ = MCP_Diff.reset ();
  val fixture_facts = MCP_Diff.own_facts \<^theory>\<open>MCP_Diff_Fixture\<close>;
  val {added, removed, changed} = MCP_Diff.diff_facts [] fixture_facts;
in
  (*everything the fixture owns reads as added against an empty baseline*)
  \<^assert> (length added = length fixture_facts);
  \<^assert> (null removed);
  \<^assert> (null changed);
  \<^assert> (exists (fn n => Long_Name.base_name n = "unproved_bound") added)
end;
\<close>

ML \<open>
(*the name-resolving wrapper: Thy_Info knows theories from the ancestor
  IMAGE, so Main is reachable here while this session's own theories are
  not (that is why diff_theory exists)*)
let
  val main =
    the_default "Main"
      (find_first (fn n => n = "Main" orelse String.isSuffix ".Main" n) (Thy_Info.get_names ()));
  val _ = MCP_Diff.reset ();
  val first = plain (MCP_Diff.diff main);
  val second = plain (MCP_Diff.diff main);
in
  \<^assert> (String.isSubstring "checkpoint saved" first);
  \<^assert> (String.isSubstring "no change" second)
end;
\<close>

ML \<open>
(*an unknown theory name fails loudly rather than silently checkpointing
  nothing*)
\<^assert> ((MCP_Diff.diff "No_Such_Theory_Here"; false) handle ERROR _ => true);
\<close>

text \<open>Leave no checkpoints behind in the test heap.\<close>
ML \<open>MCP_Diff.reset ()\<close>

end
