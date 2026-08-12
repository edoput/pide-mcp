theory MCP_Task_List
  imports MCP
begin

section \<open>The machine-checked task list\<close>

text \<open>plans/proof_tasks, spec \<open>D-2026-08-12-machine-checked-task-list\<close>.

Every \<^verbatim>\<open>sorry\<close> in a theory is an obligation the PROVER knows is open, so
an agent's task list is READ out of the theory rather than written by
the agent. Progress becomes prover-measured: prove the lemma, the sorry
goes away, the list shrinks. An agent cannot report done what the
prover still counts as open.

This is a kernel fact, not a text search for the word: \<^verbatim>\<open>sorry\<close> runs
through \<^ML>\<open>Skip_Proof.cheat_tac\<close>, which invokes the \<open>skip_proof\<close>
oracle, and \<^ML>\<open>Thm_Deps.has_skip_proof\<close> is Pure's own test for it.

Enumeration is plans/recap's, unchanged: \<^ML>\<open>Facts.dest_static\<close> with the
parents' fact tables as exclusion bases, which is the spec's "diff
against the imports" done natively.\<close>

ML \<open>
signature MCP_TASK_LIST =
sig
  type task =
    {name: string, pos: Position.T, statement: string, direct: bool}
  val tasks_of: theory -> task list
  val tasks: string list -> string
  val directly_skipped: thm -> bool
  val rests_on_skip: thm -> bool
end;

structure MCP_Task_List: MCP_TASK_LIST =
struct

type task =
  {name: string, pos: Position.T, statement: string, direct: bool};


(* the skip_proof oracle *)

(*a thm's OWN proof body vs its whole dependency closure. Thm_Deps
  .all_oracles recurses the closure (Pure/thm_deps.ML), so a lemma
  proved honestly FROM a sorry'd one reports the oracle too -- true,
  but not a task: it closes for free when its dependency closes. The
  own-body reading is what separates them, and it is plans/proof_tasks
  A1 -- see MCP_Fixture_Sorry.thy, which settles it empirically. If A1
  comes out negative the classification degrades to "everything is
  direct" and the assembled text says so, rather than lying.*)
fun own_oracles thm =
  (case Thm.proof_body_of thm of Proofterm.PBody {oracles, ...} => oracles);

fun is_skip ((name, _): string * Position.T, _: term option) =
  name = \<^oracle_name>\<open>skip_proof\<close>;

fun directly_skipped thm = exists is_skip (own_oracles thm);
fun rests_on_skip thm = Thm_Deps.has_skip_proof [thm];

(*the oracle's own position is the SORRY SITE, which is a better hint
  than the declaration site when they differ (a sorry nested inside a
  long structured proof). Secondary: the fact-space entry is primary,
  see task_of below.*)
fun skip_positions thm =
  own_oracles thm |> map_filter (fn (o' as ((_, pos), _)) =>
    if is_skip o' andalso pos <> Position.none then SOME pos else NONE);


(* the skip_proofs gate *)

(*Goal.skip_proofs_enabled (Pure/Isar/proof.ML) turns EVERY non-relevant
  proof into the same oracle, so under quick_and_dirty every lemma in
  the theory reads as a task and the list is noise. Refuse rather than
  return a plausible wrong answer -- a silently-wrong task list is worse
  than no task list (spec, "two hazards"). The caller rebuilds without
  quick_and_dirty; a stamped-but-returned variant is recorded in the
  plan as a revisit, not shipped.*)
fun check_skip_proofs () =
  if Goal.skip_proofs_enabled () then
    error ("proof_tasks: this session runs with skipped proofs " ^
      "(quick_and_dirty / skip_proofs), which turns every proof into the " ^
      "same skip_proof oracle -- every lemma would be reported as a task. " ^
      "Rebuild the target theory without it and call again.")
  else ();


(* enumeration *)

(*the theory's OWN facts: Facts.dest_static takes the exclusion bases
  natively, so passing the parents' tables IS the diff against the
  imports. verbose=false additionally drops concealed names.

  NOTE on package noise (plans/recap A2): a datatype/fun also puts
  foo.simps, foo.induct into this space. For RECAP that filter is
  load-bearing; here it is nearly free, because a package-generated
  theorem resting on a sorry is not a thing that happens -- the
  selection below removes them anyway. The plan says explicitly: do not
  block on the filter here.*)
fun own_facts thy =
  Facts.dest_static false
    (map Global_Theory.facts_of (Theory.parents_of thy))
    (Global_Theory.facts_of thy);


(* one task entry *)

(*name: the EXTERNED spelling -- the name a user would actually type.
  position: from the fact space (Name_Space.the_entry on
    Global_Theory.fact_space), the DECLARATION site. This is the idiom
    already in this tree (MCP_Repl.entities). The oracle's position is
    reported as a secondary hint when it differs.
  statement: Thm.prop_of on the FACT -- never the oracle's term.
    Skip_Proof.cheat_tac builds its oracle over
    `Logic.list_implies (map Thm.term_of assms, goal)`, the skipped
    SUBGOAL with the local assumptions prepended; inside a structured
    proof that is NOT the lemma (MCP_Fixture_Sorry.sorry_structured is
    exactly this case).*)
fun task_of ctxt thy (name, thm) =
  let
    val facts = Global_Theory.facts_of thy;
    val extern = Facts.extern ctxt facts name;
    (*the_entry raises for a name with no entry (dynamic facts); fall
      back to the oracle's own sorry-site position, then to none*)
    val pos =
      (case try (#pos o Name_Space.the_entry (Facts.space_of facts)) name of
        SOME p => p
      | NONE => (case skip_positions thm of p :: _ => p | [] => Position.none));
    val stmt = Syntax.string_of_term ctxt (Thm.prop_of thm);
  in
    {name = extern, pos = pos, statement = stmt, direct = directly_skipped thm}
  end;

fun tasks_of thy =
  let
    val _ = check_skip_proofs ();
    val ctxt = Proof_Context.init_global thy;
    (*a fact is a thm LIST; report the ones that rest on a skipped
      proof, keeping the fact name (indexed when the fact is plural)*)
    fun entries (name, thms) =
      let val n = length thms in
        thms |> map_index (fn (i, thm) =>
          if rests_on_skip thm then
            SOME (if n = 1 then name else name ^ "(" ^ string_of_int (i + 1) ^ ")", thm)
          else NONE)
        |> map_filter I
      end;
  in
    own_facts thy
    |> maps entries
    |> map (task_of ctxt thy)
    |> sort (fn (t1: task, t2: task) =>
         int_ord (the_default 0 (Position.line_of (#pos t1)),
                  the_default 0 (Position.line_of (#pos t2))))
  end;


(* text assembly *)

fun string_of_pos pos =
  (case (Position.file_of pos, Position.line_of pos) of
    (SOME file, SOME line) => file ^ ":" ^ string_of_int line
  | (NONE, SOME line) => "line " ^ string_of_int line
  | _ => "(no position)");

fun render_task (t: task) =
  "  " ^ #name t ^ "  [" ^ string_of_pos (#pos t) ^ "]\n" ^
  "      " ^ #statement t;

fun render_theory thy_name tasks =
  let
    val (direct, inherited) = List.partition #direct tasks;
    val header =
      "theory " ^ thy_name ^ ": " ^
      (case length direct of
        0 => "no open obligations"
      | 1 => "1 open obligation"
      | n => string_of_int n ^ " open obligations");
    val direct_block =
      if null direct then []
      else [String.concatWith "\n\n" (map render_task direct)];
    (*not tasks: these close for free when their dependency closes.
      Listed so the reader is not surprised that they also carry the
      oracle, and so a wrong A1 verdict is visible rather than silent.*)
    val inherited_block =
      if null inherited then []
      else
        ["  resting on a skipped proof, but not themselves skipped\n" ^
         "  (these close when the obligations above do):\n\n" ^
         String.concatWith "\n\n" (map render_task inherited)];
  in
    String.concatWith "\n\n" (header :: direct_block @ inherited_block)
  end;

(*thy_name is the resolved Thy_Info key, same spelling contract as
  MCP_Repl.entities / find_theorems_theory*)
fun tasks_one thy_name =
  render_theory thy_name (tasks_of (Thy_Info.get_theory thy_name));

fun tasks thy_names =
  (case thy_names of
    [] => error "proof_tasks: no theories given (parameter \"theories\")"
  | _ => String.concatWith "\n\n" (map tasks_one thy_names));

end;
\<close>


subsection \<open>Registration\<close>

text \<open>A list-valued parameter arrives as REPEATED KEYS in the decoded
argument list, so \<^ML>\<open>MCP_Combinators.arg\<close> (the scalar reader) does not
apply -- collect them instead.\<close>

ML \<open>
fun proof_tasks_theories args =
  map_filter (fn (k, v) => if k = "theories" then SOME v else NONE) args;
\<close>

mcp_tool "proof_tasks" = run \<open>fn _ => fn args =>
    MCP_Task_List.tasks (proof_tasks_theories args)\<close>
  (description \<open>List the proof obligations a theory still leaves open --
    every lemma proved with `sorry`. This is the theory's OWN task
    list, read from the prover rather than written by hand: each entry
    is a fact the kernel records as resting on a skipped proof, with
    its name, its position in the source, and its statement. Prove one
    and it leaves the list. Entries are split into obligations that are
    themselves skipped (the real tasks) and ones that merely rest on a
    skipped lemma (they close for free when their dependency does).
    Targets THEORIES, not repls -- a repl's transient facts exist in no
    file. Refuses if the session runs with quick_and_dirty, where every
    proof carries the same oracle and the list would be meaningless.\<close>)
  (params theories :: list of string \<open>the theory names to inspect\<close>)
  (annotations read_only)

end
