### Contexts and context data
Manual: `src/Doc/Implementation/Prelim.thy` §"Contexts" — theory vs.
proof context vs. generic context; `Theory_Data`, `Proof_Data`,
`Generic_Data` functors (the *only* sanctioned mutable-looking state);
configuration options.
Sources: `src/Pure/context.ML`, `src/Pure/Isar/proof_context.ML`,
`src/Pure/config.ML`.

**Pitfall — parent merge order (2026-07-13, hit via a user's
codatatype).** `Context.begin_thy` first DROPS imports that are proper
subtheories of other imports (`make_parents`, `src/Pure/context.ML`),
then merges each data slot folding left-to-right from the first
surviving parent (`Theory_Data` functor, same file). Merge functions
that keep one side take the FIRST parent's value — notably the
simplifier's `mk_rews` record (`merge_ss`,
`src/Pure/raw_simplifier.ML`), which carries HOL's `mk_cong`.
Concrete failure: `imports Main "MCP-Tools.MCP_Tools"
"HOL-Library.X"` — `Main` is subsumed by `X` and dropped, the
Pure-based registry becomes the first parent, HOL's cong
preprocessing is lost, and the next `datatype`/`codatatype` raises
`SIMPLIFIER ("Congruence not a meta-equality", [case_cong ...])`.
Rule: a Pure-based theory must never end up as the first parent of a
HOL theory; give users a HOL-anchored wrapper to import instead —
local example `mcp/Tools/HOL/MCP.thy` (`MCP-HOL.MCP`), which imports
`Main` first and documents the mechanism, is exactly that wrapper for
the `mcp_tool`/`mcp_resource` commands.
