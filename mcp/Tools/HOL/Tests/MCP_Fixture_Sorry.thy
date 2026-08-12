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
the point of it, not a defect.

VERIFIED 2026-08-12, and NOT what the spec first assumed: in a batch
build \<^verbatim>\<open>sorry\<close> is an ERROR ("Cheating requires quick_and_dirty mode!",
\<^file>\<open>~~/src/Pure/Isar/method.ML\<close>) unless \<open>quick_and_dirty\<close> is on, hence
the declaration below. The two flags are DISTINCT and only one of them
is the tool's hazard:

  \<^item> \<open>quick_and_dirty\<close> is a context config (\<^ML>\<open>Goal.quick_and_dirty\<close>)
    that merely PERMITS \<^verbatim>\<open>sorry\<close>. It adds no oracle of its own.
  \<^item> \<open>skip_proofs\<close> is a global build option read by
    \<^ML>\<open>Goal.skip_proofs_enabled\<close>, and THAT is what turns every
    non-relevant proof into a skip_proof oracle and would make every
    lemma read as a task.

So MCP_Task_List gates on skip_proofs, not on quick_and_dirty -- and
this fixture, which needs quick_and_dirty to exist at all, does not
trip that gate.\<close>

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

text \<open>\<open>quick_and_dirty\<close> is declared HERE, below the package definitions
and the honest lemmas, and that placement is load-bearing (learned the
hard way, 2026-08-12). Declared at the top of the theory instead, it
also reaches \<^verbatim>\<open>fun\<close>, whose package then skips its own termination
proof through the SAME skip_proof oracle -- and every later lemma about
\<open>flip\<close> inherits it. The first run of this fixture reported the honestly
proved \<open>flip_flip\<close> as resting on a skipped proof for exactly that
reason. Enable cheating as late as possible and only where a \<^verbatim>\<open>sorry\<close>
actually needs it.\<close>

declare [[quick_and_dirty]]

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


subsection \<open>A1 and A3, asserted (plans/proof_tasks)\<close>

text \<open>These are ASSERTIONS, not prose: a failing build is the report.
\<^verbatim>\<open>warning\<close>/\<^verbatim>\<open>writeln\<close> from a batch build reach neither stdout nor the
session log (verified 2026-08-12 -- the recorded log is empty), so the
only channel a build actually surfaces is an error. Each check below
therefore raises with the FULL observation table, so one build settles
every question at once.

A1's hypothesis: a thm's OWN proof body holds only its own oracles, so
\<open>#oracles (Thm.proof_body_of thm)\<close> separates a directly sorry'd lemma
from one that merely rests on one, while \<^ML>\<open>Thm_Deps.all_oracles\<close>
(which recurses the dependency closure) cannot. The hazard is
\<open>fulfill_norm_proof\<close>, which unions PROMISE oracles into that same field
-- and whether a cited lemma arrives as a promise depends on parallel
proofs, which this session builds with.

If this theory fails to build, READ THE MESSAGE before changing
anything: it is the experiment's result, and it decides whether
MCP_Task_List ships the direct/inherited split or degrades to one flat
list.\<close>

ML \<open>
local

fun own_oracles thm =
  (case Thm.proof_body_of thm of Proofterm.PBody {oracles, ...} => oracles);

fun oracle_names oracles = map (fn ((name, _), _) => name) oracles;

fun directly_skipped thm =
  exists (fn ((name, _), _) => name = \<^oracle_name>\<open>skip_proof\<close>) (own_oracles thm);

val cases =
  [("flip_flip", @{thm flip_flip}),
   ("is_red_Red", @{thm is_red_Red}),
   ("sorry_direct", @{thm sorry_direct}),
   ("sorry_inherited", @{thm sorry_inherited}),
   ("sorry_structured", @{thm sorry_structured})];

(*the whole table, so a failure reports every case and not just the
  one that tripped*)
val observations =
  cases |> map (fn (label, thm) =>
    "  " ^ StringCvt.padRight #" " 18 label ^
    "transitive=" ^ (if Thm_Deps.has_skip_proof [thm] then "YES" else "no ") ^
    "  own_body=[" ^ commas (oracle_names (own_oracles thm)) ^ "]");

fun report msg =
  error (msg ^ "\n\nobserved (plans/proof_tasks A1):\n" ^
    cat_lines observations);

fun check msg b = if b then () else report msg;

in

(*sanity: the honest lemmas carry no skipped proof at all*)
val _ = check "flip_flip rests on a skipped proof -- fixture is wrong"
  (not (Thm_Deps.has_skip_proof [@{thm flip_flip}]));
val _ = check "is_red_Red rests on a skipped proof -- fixture is wrong"
  (not (Thm_Deps.has_skip_proof [@{thm is_red_Red}]));

(*both sorry'd lemmas and the one resting on one are caught by the
  TRANSITIVE test -- this is what Thm_Deps.has_skip_proof gives us*)
val _ = check "sorry_direct is not seen as resting on a skipped proof"
  (Thm_Deps.has_skip_proof [@{thm sorry_direct}]);
val _ = check "sorry_inherited is not seen as resting on a skipped proof"
  (Thm_Deps.has_skip_proof [@{thm sorry_inherited}]);

(*A1 SETTLED NEGATIVE (2026-08-12, this fixture). The own-proof-body
  reading is not merely unreliable, it is EMPTY: no thm here carries any
  oracle in its own PBody, sorry_direct included. The oracles are only
  reachable by joining the proof futures and recursing the dependency
  closure, which is precisely what Thm_Deps.all_oracles does
  (Proofterm.join_thms in its `collect`). So the hypothesis is dead, and
  it is pinned here so nobody retries it.*)
val _ = check "A1 has flipped: some thm's own proof body now carries an \
  \oracle. The direct/inherited split was built on this being empty -- \
  \re-run the experiment and revisit plans/proof_tasks A1."
  (forall (fn (_, thm) => null (own_oracles thm)) cases);

(*A1b, the plan's stated fallback: walk NAMED dependencies instead.
  A fact that rests on a skipped proof is INHERITED if some other
  sorry-resting fact is among its named dependencies, and DIRECT
  otherwise.*)
val _ =
  let
    val dep_names =
      Thm_Deps.thm_deps \<^theory> #> map (Thm_Name.print o #2);
    val deps_of = fn thm => dep_names [thm];
    fun mentions substring names =
      exists (fn n => String.isSubstring substring n) names;
    val deps_table =
      cases |> map (fn (label, thm) =>
        "  " ^ StringCvt.padRight #" " 18 label ^
        "deps=[" ^ commas (deps_of thm) ^ "]");
    fun report_deps msg =
      error (msg ^ "\n\nnamed dependencies (plans/proof_tasks A1b):\n" ^
        cat_lines deps_table);
    fun check_deps msg b = if b then () else report_deps msg;
  in
    check_deps "A1b: sorry_inherited does not name sorry_direct among \
      \its dependencies, so the dependency walk cannot classify it \
      \either. Degrade to one flat list."
      (mentions "sorry_direct" (deps_of @{thm sorry_inherited}));
    check_deps "A1b: sorry_direct names sorry_inherited among its \
      \dependencies, which would invert the classification."
      (not (mentions "sorry_inherited" (deps_of @{thm sorry_direct})))
  end;

(*A3: the oracle's term is the skipped SUBGOAL with local assumptions
  prepended, so on the structured case it must DIFFER from the fact's
  own statement. If these ever coincide the fixture has stopped
  exercising the divergence and the A3 hazard would go untested.*)
val _ =
  let
    val thm = @{thm sorry_structured};
    val prop = Thm.prop_of thm;
    val oracle_terms =
      Thm_Deps.all_oracles [thm]
      |> map_filter (fn ((name, _), t) =>
           if name = \<^oracle_name>\<open>skip_proof\<close> then t else NONE);
  in
    (*A3 SETTLED (2026-08-12, this fixture), and NOT as the spec first
      claimed. Reading Pure's sources said oracle_enabled at the default
      proof level keeps `SOME prop`; in an actual batch build the
      recorded oracles carry NO term. thm.ML:1213 takes term and
      position from the SAME branch, so the oracle's position is absent
      here too -- which retires the "oracle position as a secondary
      hint" idea along with it.

      For the tool this only strengthens the design: the statement was
      always going to come from Thm.prop_of on the fact, and it now
      turns out the oracle term is not merely the wrong term but no
      term at all.*)
    check "A3 has flipped: an oracle now carries a term. The tool still \
      \reads Thm.prop_of and is unaffected, but the spec's account of \
      \what an oracle records needs revisiting."
      (null oracle_terms);
    ignore prop
  end;

end;
\<close>

end
