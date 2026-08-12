theory MCP_Diff
  imports MCP
begin

section \<open>Checkpointing theory values and diffing them\<close>

text \<open>What changed in a theory since the last time we looked?

The prover cannot answer this on its own. \<^ML_structure>\<open>Thy_Info\<close> is a
graph of dependencies keyed by theory NAME, with one entry per name:
reloading a theory calls \<open>remove\<close> before \<open>new_entry\<close>
(\<^file>\<open>~~/src/Pure/Thy/thy_info.ML\<close>), dropping the old value and every
theory that depended on it. A theory value carries no timestamp and no
pointer to an earlier version of itself, so after an edit there is
exactly one value for the name and nothing to compare it against.

What makes the comparison possible anyway is that a theory VALUE is
immutable and self-contained. Once we hold one, it stays valid and
fully usable even after \<^ML_structure>\<open>Thy_Info\<close> has forgotten it. So
this theory keeps a table of theory values -- a checkpoint per theory
name -- and each \<open>theory_diff\<close> call compares the current value against
the stored one and then replaces it.

The checkpoint table is IN-MEMORY and per-process: it does not survive a
restart, and it is not part of any heap. That is a deliberate choice of
convenience over durability, and it is the reason the tool's first call
on a name can only save a baseline rather than report a diff. A durable
baseline is a different mechanism (load the pre-edit file under a second
theory name and diff two live values); nothing here forecloses it,
because \<^verbatim>\<open>diff_facts\<close> below is a pure function of two fact lists and
does not care where they came from.

Diffing is by fact NAME plus statement:
\<^item> \<open>added\<close> -- a name in the current value that the checkpoint lacked;
\<^item> \<open>removed\<close> -- a name the checkpoint had and the current value lacks;
\<^item> \<open>changed\<close> -- a name in both whose statement is no longer the same
  proposition (compared with \<^ML>\<open>aconv\<close>, so bound-variable renaming does
  not count as a change).

\<open>changed\<close> is the reason this compares propositions rather than just
names: an edit that reworks a lemma's statement while keeping its name
is invisible to a name-only diff, and that is exactly the edit worth
noticing.\<close>

ML \<open>
signature MCP_DIFF =
sig
  val own_facts: theory -> (string * thm list) list
  val diff_facts: (string * thm list) list -> (string * thm list) list ->
    {added: string list, removed: string list, changed: string list}
  val checkpointed: unit -> string list
  val report: theory -> (string * thm list) list -> (string * thm list) list -> string
  val diff_theory: theory -> string
  val diff: string -> string
  val reset: unit -> unit
end;

structure MCP_Diff: MCP_DIFF =
struct

(* the checkpoint table *)

(*keyed by Context.theory_long_name, so the key is stable across the
  reload that replaces the value. Holding a theory value here keeps it
  (and its ancestors) alive after Thy_Info has dropped it -- that is the
  point, and also the cost: a checkpoint pins whatever would otherwise
  be collected on reload.*)
val checkpoints: theory Symtab.table Synchronized.var =
  Synchronized.var "MCP_Diff.checkpoints" Symtab.empty;

fun reset () = Synchronized.change checkpoints (K Symtab.empty);

fun checkpointed () = Symtab.keys (Synchronized.value checkpoints);


(* enumeration *)

(*a theory's OWN facts: everything it adds on top of its imports.
  Facts.dest_static takes the fact tables to exclude as an argument
  (Pure/facts.ML), so passing the parents' tables is the whole of the
  "minus the imports" step. verbose = false additionally drops concealed
  names, which are internal bookkeeping no reader asked about.*)
fun own_facts thy =
  Facts.dest_static false (map Global_Theory.facts_of (Theory.parents_of thy))
    (Global_Theory.facts_of thy);


(* the diff proper *)

(*a fact name binds a LIST of theorems (foo(1), foo(2), ...), so two
  versions agree only if the lists have the same length and match
  pairwise. aconv, not equality: alpha-equivalent propositions are the
  same statement.*)
fun same_thms (ths1, ths2) =
  eq_list (fn (th1, th2) => Thm.prop_of th1 aconv Thm.prop_of th2) (ths1, ths2);

(*pure over two fact lists -- no theory, no checkpoint table, no output
  formatting. This is the unit-testable surface (cf. MCP_Repl.run), and
  it is what a durable-baseline variant would reuse unchanged.*)
fun diff_facts old new =
  let
    val old_tab = Symtab.make old;
    val new_tab = Symtab.make new;
    val added = map #1 (filter_out (Symtab.defined old_tab o #1) new);
    val removed = map #1 (filter_out (Symtab.defined new_tab o #1) old);
    val changed =
      new |> map_filter (fn (name, ths) =>
        (case Symtab.lookup old_tab name of
          SOME ths0 => if same_thms (ths0, ths) then NONE else SOME name
        | NONE => NONE));
  in {added = added, removed = removed, changed = changed} end;


(* reporting *)

(*one fact can carry many theorems; print a few and count the rest
  rather than flooding the client with a datatype's simp set*)
val max_shown = 3;

(*[sorry] is not decoration: Thm_Deps.has_skip_proof tests for the
  skip_proof oracle (Pure/skip_proof.ML), which is what `sorry` invokes,
  so a marked entry is one the prover itself says is unproved. NOTE it
  is TRANSITIVE -- all_oracles walks the whole dependency closure -- so a
  lemma proved honestly FROM a sorry'd one is marked too. Telling those
  apart is a separate question and this tool does not claim to.*)
fun describe ctxt tab name =
  (case Symtab.lookup tab name of
    NONE => "  " ^ name
  | SOME ths =>
      let
        val mark = if Thm_Deps.has_skip_proof ths then "   [sorry]" else "";
        val (shown, rest) = chop max_shown ths;
        val stmts = map (fn th => "      " ^ Syntax.string_of_term ctxt (Thm.prop_of th)) shown;
        val more =
          if null rest then []
          else ["      ... (" ^ string_of_int (length rest) ^ " more)"];
      in cat_lines (("  " ^ name ^ mark) :: stmts @ more) end);

fun section _ _ _ [] = []
  | section ctxt tab title names =
      (title ^ " (" ^ string_of_int (length names) ^ "):") :: map (describe ctxt tab) names;

fun report thy old new =
  let
    val ctxt = Proof_Context.init_global thy;
    val old_tab = Symtab.make old;
    val new_tab = Symtab.make new;
    val {added, removed, changed} = diff_facts old new;
  in
    if null added andalso null removed andalso null changed then
      "no change since the last checkpoint (" ^ string_of_int (length new) ^ " facts)"
    else
      cat_lines
        (section ctxt new_tab "added" added @
         section ctxt old_tab "removed" removed @
         section ctxt new_tab "changed" changed)
  end;


(* the tool entry point *)

(*read the current value, swap it into the table, and compare against
  whatever was there before -- one atomic change_result, so two
  concurrent calls on the same name cannot both see the same baseline.

  PLAIN print mode: MCP_Combinators.ml_run (the `run` form) does NOT set
  it, unlike exec_text and the capture form, and run_tool_result
  (mcp_session.scala) does not strip yxml -- so pretty-printed terms must
  be made plain here or markup reaches the client.*)
fun diff_theory thy =
  let
    val key = Context.theory_long_name thy;
    val current = own_facts thy;
    val previous =
      Synchronized.change_result checkpoints (fn tab =>
        (Symtab.lookup tab key, Symtab.update (key, thy) tab));
  in
    (case previous of
      NONE =>
        "checkpoint saved for " ^ quote key ^ " (" ^ string_of_int (length current) ^
          " facts of its own).\nNo earlier checkpoint in this session, so there is nothing to" ^
          " compare yet -- reload the theory and call theory_diff again."
    | SOME old_thy =>
        Print_Mode.with_modes [] (fn () => report thy (own_facts old_thy) current) ())
  end;

(*the name-resolving wrapper the tool actually calls. Kept separate from
  diff_theory because Thy_Info only knows theories that are already
  LOADED -- in particular, during a session build the theories of that
  same session are not in it yet, so a test can only reach the engine
  through diff_theory.*)
fun diff thy_name = diff_theory (Thy_Info.get_theory thy_name);

end;
\<close>

text \<open>The parameter name is written \<^verbatim>\<open>"theory"\<close>, QUOTED, and that is not
optional: \<^verbatim>\<open>theory\<close> is an outer-syntax command keyword, and command
keywords delimit command spans at a scanning pass that runs before any
inner parser sees the tokens -- a bare \<open>theory\<close> here would cut this
\<^verbatim>\<open>mcp_tool\<close> span short. A string token is opaque to span scanning, and
\<^ML>\<open>Parse.name\<close> (the params clause's name parser) accepts a string, so
quoting shields it while still naming the parameter \<open>theory\<close> for
clients. Same rule as the quoted command name in
\<^verbatim>\<open>mcp_tool "find_consts"\<close>.

\<open>mutating\<close>, not \<open>read_only\<close>: the call writes a checkpoint. It is not
idempotent either -- calling twice in a row reports the changes and then
reports none, because the first call moved the baseline.\<close>

mcp_tool "theory_diff" = run \<open>fn _ => fn args =>
  MCP_Diff.diff (MCP_Combinators.arg args "theory")\<close>
  (description \<open>Report what changed in a theory since the last time this tool
    was called on it, then save a new checkpoint. Lists facts added,
    removed, and changed (same name, different statement), with each
    statement printed and a [sorry] mark on anything resting on a
    skipped proof. The FIRST call on a theory can only save a baseline:
    reload the theory and call again to see a diff. Checkpoints live in
    memory for the life of the server process and are lost on restart.\<close>)
  (annotations mutating)
  (params "theory" :: string \<open>the theory to diff, by its loaded name\<close>)

end
