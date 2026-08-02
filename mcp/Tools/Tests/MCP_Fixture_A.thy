theory MCP_Fixture_A
  imports "MCP-Tools.MCP_Tools"
begin

text \<open>Registration site for the visibility fixtures: \<open>alpha\<close> stays
active, \<open>beta\<close> is deactivated here and re-activated only by the bundle
(the phase-3 bundle-scoping shape: registration is unconditional,
activation travels with the bundle).\<close>

setup \<open>
  Named_Target.theory_map (fn lthy =>
    lthy
    |> MCP_Tool.declare \<^binding>\<open>alpha\<close>
        {description = "fixture tool alpha", params = [], form = MCP_Tool.String_Fun,
         annotations = MCP_Tool.default_annotations,
         run = fn _ => fn args =>
          "alpha:" ^ the_default "" (AList.lookup (op =) args "input")}
    |> #2
    |> MCP_Tool.declare \<^binding>\<open>beta\<close>
        {description = "fixture tool beta (bundle-scoped)", params = [],
         form = MCP_Tool.String_Fun, annotations = MCP_Tool.default_annotations,
         run = fn _ => fn _ => "beta"}
    |> #2)
\<close>

declare [[mcp_tools del: beta]]

bundle beta_tools = [[mcp_tools add: beta]]

end
