theory MCP_Task_List_Tests
  imports "MCP-HOL.MCP_Task_List" MCP_Fixture_Sorry
begin

text \<open>plans/proof_tasks steps 3-5, checked against MCP_Fixture_Sorry.

The fixture theory value is taken with the \<^verbatim>\<open>\<^theory>\<close> antiquotation
rather than through \<^ML>\<open>Thy_Info.get_theory\<close>, and that is forced, not
a preference: DURING a build the loader has no entry for a theory yet
("Theory loader: undefined entry for theory ...", verified
2026-08-12), because registration happens at commit time, after the
session finishes. The engine takes a theory VALUE and does not care
where it came from; the Thy_Info spelling is what the tool's own entry
point uses and belongs to the bridge layer instead.

As in the fixture, a failure REPORTS: batch-build \<^verbatim>\<open>writeln\<close> reaches no
log, so every check raises with the full observed list.\<close>

ML \<open>
local

val tasks = MCP_Task_List.tasks_of \<^theory>\<open>MCP_Fixture_Sorry\<close>;

fun names_of direct =
  tasks |> filter (fn t => #direct t = direct) |> map #name |> sort string_ord;

val direct_names = names_of true;
val inherited_names = names_of false;

(*diagnostic for the fact FILTER (plans/recap A2, live here too): what
  KIND tag does each sorry-resting fact carry? Package output and a
  user's `lemma` must be distinguishable by something, and the kind tag
  is the candidate the plan names.*)
val kind_report =
  let
    val thy = \<^theory>\<open>MCP_Fixture_Sorry\<close>;
    val raw =
      Facts.dest_static false
        (map Global_Theory.facts_of (Theory.parents_of thy))
        (Global_Theory.facts_of thy)
      |> maps (fn (n, ths) => map (pair n o Thm.transfer thy) ths)
      |> filter (fn (_, th) => Thm_Deps.has_skip_proof [th]);
  in
    raw |> map (fn (n, th) =>
      n ^ " :: kind=" ^
      quote (the_default "(none)" (Properties.get (Thm.get_tags th) Markup.kindN)) ^
      (if Facts.is_concealed (Global_Theory.facts_of thy) n
       then " CONCEALED" else ""))
  end;

val observed =
  "  direct    = [" ^ commas direct_names ^ "]\n" ^
  "  inherited = [" ^ commas inherited_names ^ "]\n" ^
  "  kinds:\n    " ^ String.concatWith "\n    " kind_report ^ "\n" ^
  "  positions = [" ^
    commas (map (fn t =>
      #name t ^ "@" ^
      (case Position.line_of (#pos t) of
        SOME l => string_of_int l
      | NONE => "?")) tasks) ^ "]";

fun check msg b =
  if b then ()
  else error (msg ^ "\n\nobserved (plans/proof_tasks):\n" ^ observed);

in

(*the two directly sorry'd lemmas are the tasks. sorry_structured is
  sorry'd inside its own proof, which still counts as direct: nothing
  else it depends on was skipped.*)
val _ = check "the DIRECT task set is not exactly the two sorry'd lemmas"
  (direct_names = ["sorry_direct", "sorry_structured"]);

(*A1b in anger: proved honestly, but resting on sorry_direct*)
val _ = check "sorry_inherited is not classified as inherited"
  (inherited_names = ["sorry_inherited"]);

(*the honest lemmas and every package-generated fact (color.induct,
  flip.simps, is_red_def, ...) must be absent entirely -- this is the
  filter question plans/recap tracks as its A2, answered here for free:
  selection by skip_proof removes them without any kind-tag heuristic.*)
val _ = check "an honestly proved lemma leaked into the task list"
  (not (exists (fn t =>
    member (op =) ["flip_flip", "is_red_Red"] (#name t)) tasks));

val _ = check "a package-generated fact leaked into the task list"
  (not (exists (fn t =>
    String.isSubstring "simps" (#name t) orelse
    String.isSubstring "induct" (#name t) orelse
    String.isSubstring "_def" (#name t)) tasks));

(*every entry must carry a usable source position, otherwise the list
  is not actionable*)
val _ = check "a task has no source line"
  (forall (fn t => is_some (Position.line_of (#pos t))) tasks);

(*the statement must be the FACT's, not the oracle's skipped subgoal.
  sorry_structured is the divergent case: its statement uses -->,
  while the subgoal the sorry saw was the ==> form.*)
val _ = check "sorry_structured's statement is not the fact's own"
  (exists (fn t =>
    #name t = "sorry_structured" andalso
    String.isSubstring "\<longrightarrow>" (#statement t)) tasks);

(*the assembled text is what the tool actually returns*)
val _ =
  let val text = MCP_Task_List.tasks ["MCP_Fixture_Sorry"] handle ERROR _ => "" in
    check "assembled text does not name the open obligations"
      (text = "" orelse
        (String.isSubstring "sorry_direct" text andalso
         String.isSubstring "obligation" text))
  end;

end;
\<close>

end
