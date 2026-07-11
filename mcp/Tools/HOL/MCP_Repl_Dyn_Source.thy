theory MCP_Repl_Dyn_Source
  imports "MCP-HOL.MCP_Repl"
begin

text \<open>Not listed in any session's ROOT theories -- exists purely to be
loaded on demand via Ir.load_theory (which forces record_theories=true
per call) so mcp_test -b can exercise repl_init's "Thy:idx" segment-spec
ok-path (plans/repl_init, T6) against a theory with real recorded
segments in a live headless session.\<close>

lemma True by simp

end
