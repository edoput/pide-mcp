(*  A deliberately lint-dirty theory, for plans/scala_mcp_tool A4.

    Each item below trips a NAMED lint from the linter's `default` bundle.
    The lints were chosen by reading linter_base/src/lints.scala rather
    than guessed -- an earlier version of this file guessed, and the
    linter's own CLI reported nothing on it, which is what caught the
    guess. Keep the CLI (`isabelle lint -d mcp/test/lintfix Lint_Fixture`)
    as the control whenever this file changes.
*)

theory Lint_Dirty
  imports Main
begin

(* short_name: one-character names (lints.scala:530) *)
definition f :: "nat \<Rightarrow> nat" where "f n = n"
definition g :: "nat \<Rightarrow> nat" where "g n = Suc n"

(* tactic_proofs: induct_tac/rule_tac/case_tac are outdated (lints.scala:598) *)
lemma tactic_here: "rev (rev xs) = xs"
  apply (induct_tac xs)
   apply simp
  apply simp
  done

(* implicit_rule: bare `apply rule` hides which rule is used (lints.scala:653) *)
lemma implicit_here: "P \<longrightarrow> P"
  apply rule
  apply assumption
  done

end
