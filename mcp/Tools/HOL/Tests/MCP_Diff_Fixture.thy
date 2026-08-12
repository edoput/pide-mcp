theory MCP_Diff_Fixture
  imports Main
begin

text \<open>A small target theory for \<^verbatim>\<open>MCP_Diff\<close>'s tests. It imports only
\<^theory>\<open>Main\<close> on purpose: it is a plain proof development, the kind of
theory the tool is pointed AT, and it must not depend on the tool.

It carries one of each thing that puts facts into a theory: a
\<^verbatim>\<open>datatype\<close> and a \<^verbatim>\<open>fun\<close> (which contribute package facts nobody wrote
by hand), a \<^verbatim>\<open>definition\<close>, two real lemmas, one \<^verbatim>\<open>sorry\<close>'d lemma, and
one lemma proved honestly FROM the sorry'd one. The last pair is what
pins the transitivity of \<^ML>\<open>Thm_Deps.has_skip_proof\<close>.\<close>

datatype color = Red | Green | Blue

definition is_red :: "color \<Rightarrow> bool"
  where "is_red c \<longleftrightarrow> c = Red"

fun weight :: "color \<Rightarrow> nat"
  where
    "weight Red = 1"
  | "weight Green = 2"
  | "weight Blue = 3"

lemma red_is_red: "is_red Red"
  by (simp add: is_red_def)

lemma weight_pos: "weight c > 0"
  by (cases c) simp_all

text \<open>Deliberately unproved: the marker test depends on this resting on
the \<open>skip_proof\<close> oracle.

\<^verbatim>\<open>sorry\<close> is REFUSED in a batch build unless \<open>quick_and_dirty\<close> is on --
\<^file>\<open>~~/src/Pure/Isar/method.ML\<close> raises "Cheating requires
quick_and_dirty mode!" otherwise. So the flag is turned on for exactly
this one lemma and turned straight back off, rather than being set for
the session: left on, it would silently permit a \<^verbatim>\<open>sorry\<close> anywhere
downstream, which is the opposite of what a theory about detecting
skipped proofs should do.\<close>

declare [[quick_and_dirty = true]]

lemma unproved_bound: "weight c < 10"
  sorry

declare [[quick_and_dirty = false]]

text \<open>Proved honestly, but FROM an unproved lemma -- so it is tainted
too, and every oracle-based check must say so.\<close>
lemma derived_bound: "weight c < 20"
  using unproved_bound[of c] by linarith

end
