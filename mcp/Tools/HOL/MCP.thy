theory MCP
  imports Main "MCP-Tools.MCP_Tools"
begin

text \<open>The HOL-anchored registration entry point: HOL developments that
want the \<^verbatim>\<open>mcp_tool\<close> / \<^verbatim>\<open>mcp_resource\<close> commands should import THIS
theory, not the Pure-based \<^verbatim>\<open>MCP-Tools.MCP_Tools\<close> directly.

Importing a Pure-based theory into a HOL import list is a footgun:
\<^ML>\<open>Context.begin_thy\<close> drops imports subsumed by other imports
(\<open>make_parents\<close>), so a list like

  \<^verbatim>\<open>imports Main "MCP-Tools.MCP_Tools" "HOL-Library.BNF_Corec"\<close>

loses \<open>Main\<close> (a proper subtheory of \<open>BNF_Corec\<close>) and the Pure-based
registry becomes the FIRST merged parent. Theory data merges fold
left-to-right from the first parent, and the simplifier keeps
\<open>mk_rews\<close> -- including HOL's \<open>mk_cong\<close>, which turns
\<open>Trueprop (x = y)\<close> congruences into meta-equalities -- from the left
argument only (\<open>merge_ss\<close>, \<^file>\<open>~~/src/Pure/raw_simplifier.ML\<close>). The
merged theory then runs on Pure's cong setup, and the first
\<^verbatim>\<open>datatype\<close>/\<^verbatim>\<open>codatatype\<close> fails with
\<open>SIMPLIFIER ("Congruence not a meta-equality", ...)\<close> while
registering its case_cong rule.

Because this theory imports \<open>Main\<close> first, its own simpset carries
HOL's \<open>mk_rews\<close>, so it is safe in ANY import position.\<close>

end
