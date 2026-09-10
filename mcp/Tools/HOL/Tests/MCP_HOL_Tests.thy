theory MCP_HOL_Tests
  imports "MCP-HOL.MCP" "HOL-Library.BNF_Corec" "MCP-Assumption.MCP_Assumption"
begin

spec_test \<open>HOL datatype and simplifier registration survives MCP import ordering\<close>
  covers \<open>mcp_tool_registry#T1\<close>

datatype hol_registration_probe = Probe nat
lemma probe_inject: "Probe x = Probe y \<longleftrightarrow> x = y" by simp
mcp_tool hol_probe = \<open>I\<close> (description \<open>HOL registration probe\<close>)
ML \<open>\<^assert> (MCP_Tool.defined (Context.Proof \<^context>) "MCP_HOL_Tests.hol_probe")\<close>

end
