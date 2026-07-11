theory MCP_Fixture_B
  imports MCP_Fixture_A
begin

text \<open>One arm of the merge diamond: deactivates \<open>alpha\<close>. The sibling
arm (MCP_Fixture_C) keeps it active; the merge in MCP_Tools_Tests pins
activation union.\<close>

declare [[mcp_tools del: alpha]]

end
