theory MCP_Fixture_Sorry
  imports Main
begin

text \<open>Fixture for plans/proof_tasks step 1. It carries, deliberately,
one instance of every shape the task-list tool has to get right:

  \<^item> package noise (a \<^verbatim>\<open>datatype\<close>, a \<^verbatim>\<open>fun\<close>, a \<^verbatim>\<open>definition\<close>) whose
    derived facts must never be reported as tasks -- this is also the
    corpus plans/recap's A2 fact FILTER needs;
  \<^item> two honestly proved lemmas, which must never be reported either;
  \<^item> \<open>sorry_direct\<close>, sorry'd at the top level: the canonical task;
  \<^item> \<open>sorry_inherited\<close>, proved honestly FROM \<open>sorry_direct\<close>: NOT a task
    (it closes for free when its dependency closes). Telling these two
    apart is A1, the plan's one open assumption;
  \<^item> \<open>sorry_structured\<close>, sorry'd under a local \<^verbatim>\<open>assume\<close>: the oracle's
    own term carries that assumption and so DIFFERS from the fact's
    statement -- A3, the reason the tool must read Thm.prop_of and not
    the oracle term.

The theory is expected to load with "Skipped proof" warnings; that is
the point of it, not a defect.\<close>

datatype color = Red | Green | Blue

fun flip :: "color \<Rightarrow> color" where
  "flip Red = Blue"
| "flip Green = Green"
| "flip Blue = Red"

definition is_red :: "color \<Rightarrow> bool" where
  "is_red c \<longleftrightarrow> (c = Red)"


subsection \<open>Honestly proved -- never tasks\<close>

lemma flip_flip: "flip (flip c) = c"
  by (cases c) simp_all

lemma is_red_Red: "is_red Red"
  by (simp add: is_red_def)


subsection \<open>The obligations\<close>

text \<open>Directly sorry'd: the canonical task entry.\<close>
lemma sorry_direct: "flip c \<noteq> c \<or> c = Green"
  sorry

text \<open>Proved honestly, but resting on \<open>sorry_direct\<close>. Thm_Deps.all_oracles
is transitive, so this reports the skip_proof oracle too -- yet it is
NOT a task. A1 asks whether the two can be separated.\<close>
lemma sorry_inherited: "flip c \<noteq> c \<or> c = Green \<or> is_red c"
  using sorry_direct by blast

text \<open>The sorry fires under a local \<^verbatim>\<open>assume\<close>, so Skip_Proof.cheat_tac
builds its oracle over \<open>is_red c \<Longrightarrow> flip c = Blue\<close> while the FACT's
statement is \<open>is_red c \<longrightarrow> flip c = Blue\<close>. A tool reading the oracle term
would misreport this task.\<close>
lemma sorry_structured: "is_red c \<longrightarrow> flip c = Blue"
proof
  assume "is_red c"
  then show "flip c = Blue" sorry
qed


subsection \<open>A1 probe (plans/proof_tasks A1)\<close>

text \<open>This block REPORTS rather than asserts: the direct-vs-inherited
question is settled empirically, and the plan forbids asserting an
answer that was only read off the source. Once the build has printed
the verdict it is replaced by a real \<^verbatim>\<open>\<^assert>\<close> in
MCP_Task_List_Tests.thy, and this section goes away.

The hypothesis: a thm's OWN proof body holds only its own oracles, so
\<open>#oracles (Thm.proof_body_of thm)\<close> discriminates while
\<^ML>\<open>Thm_Deps.all_oracles\<close> (which recurses the dependency closure) does
not. The hazard: \<open>fulfill_norm_proof\<close> unions PROMISE oracles into that
same field, and whether a cited lemma arrives as a promise depends on
parallel proofs, which this session builds with.\<close>

ML \<open>
local

fun own_oracles thm =
  (case Thm.proof_body_of thm of Proofterm.PBody {oracles, ...} => oracles);

fun oracle_names oracles = map (fn ((name, _), _) => name) oracles;

fun probe (label, thm) =
  let
    val transitive = Thm_Deps.has_skip_proof [thm];
    val own = oracle_names (own_oracles thm);
  in
    warning ("A1 probe  " ^ StringCvt.padRight #" " 18 label ^
      "transitive=" ^ (if transitive then "YES" else "no ") ^
      "  own_body=[" ^ commas own ^ "]")
  end;

in

val _ =
  List.app probe
   [("flip_flip", @{thm flip_flip}),
    ("is_red_Red", @{thm is_red_Red}),
    ("sorry_direct", @{thm sorry_direct}),
    ("sorry_inherited", @{thm sorry_inherited}),
    ("sorry_structured", @{thm sorry_structured})];

(*A3: the oracle's term vs the fact's statement, on the structured case*)
val _ =
  let
    val thm = @{thm sorry_structured};
    val ctxt = @{context};
    val oracle_terms =
      Thm_Deps.all_oracles [thm]
      |> map_filter (fn ((name, _), t) =>
           if name = "Pure.skip_proof" then t else NONE);
  in
    warning ("A3 probe  statement = " ^ Syntax.string_of_term ctxt (Thm.prop_of thm) ^
      "\nA3 probe  oracle    = " ^
      (case oracle_terms of
        [] => "(no term recorded -- oracles not carrying props)"
      | t :: _ => Syntax.string_of_term ctxt t))
  end;

end;
\<close>

end
