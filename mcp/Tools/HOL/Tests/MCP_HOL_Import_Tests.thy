theory MCP_HOL_Import_Tests
  imports "HOL-Library.BNF_Corec" "MCP-HOL.MCP" "MCP-Assumption.MCP_Assumption"
begin

spec_test \<open>HOL datatype and simplifier registration survives MCP import ordering\<close>
  covers \<open>mcp_tool_registry#T1\<close>

datatype hol_import_probe = Import_Probe nat
lemma import_probe_inject: "Import_Probe x = Import_Probe y \<longleftrightarrow> x = y" by simp

end
