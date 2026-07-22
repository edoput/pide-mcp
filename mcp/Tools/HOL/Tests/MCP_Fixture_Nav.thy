theory MCP_Fixture_Nav
  imports "MCP-HOL.MCP_Repl"
begin

text \<open>Fixture for find_definition's T5 (plans/find_definition): a real,
on-disk theory built with record_theories, so its defining commands are
recoverable as source-block text -- unlike a repl-inline definition
(which lives in an anonymous, Thy_Info-untracked theory value) or a base
heap theory (e.g. HOL.List, built without record_theories).\<close>

definition nav_test_const :: nat where
  "nav_test_const = 42"

end
