theory MCP_Fixture_Root
  imports MCP_Fixture_A
begin

text \<open>A base-profile registry root for context-locator bridge tests.  It
inherits the extension resolver from \<open>MCP_Fixture_A\<close> and contributes a
tool that is absent from its \<open>MCP_Tools\<close> ancestor.\<close>

setup \<open>
  Named_Target.theory_map (fn lthy =>
    lthy
    |> MCP_Tool.declare \<^binding>\<open>root_probe\<close>
        {description = "fixture root tool", params = [], constraints = [],
         form = MCP_Tool.String_Fun, annotations = MCP_Tool.default_annotations,
         run = fn _ => fn _ => "root"}
    |> #2)
\<close>

end
