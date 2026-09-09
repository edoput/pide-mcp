theory MCP_Fixture_A
  imports "MCP-Tools.MCP_Tools"
begin

setup \<open>MCP_Context_Locator.register \<^binding>\<open>fixture\<close>
  (fn root => fn target =>
    if target = "self" then (target, Proof_Context.init_global root)
    else if target = "alias" then ("self", Proof_Context.init_global root)
    else error ("Unknown fixture context " ^ quote target))\<close>

text \<open>Registration site for the visibility fixtures: \<open>alpha\<close> stays
active, \<open>beta\<close> is deactivated here and re-activated only by the bundle
(the phase-3 bundle-scoping shape: registration is unconditional,
activation travels with the bundle).\<close>

setup \<open>
  Named_Target.theory_map (fn lthy =>
    lthy
    |> MCP_Tool.declare \<^binding>\<open>alpha\<close>
        {description = "fixture tool alpha", params = [], constraints = [],
         form = MCP_Tool.String_Fun, annotations = MCP_Tool.default_annotations,
         run = fn _ => fn args =>
          "alpha:" ^ the_default "" (AList.lookup (op =) args "input")}
    |> #2
    |> MCP_Tool.declare \<^binding>\<open>beta\<close>
        {description = "fixture tool beta (bundle-scoped)", params = [], constraints = [],
         form = MCP_Tool.String_Fun, annotations = MCP_Tool.default_annotations,
         run = fn _ => fn _ => "beta"}
    |> #2)
\<close>

declare [[mcp_tools del: beta]]

bundle beta_tools = [[mcp_tools add: beta]]

end
