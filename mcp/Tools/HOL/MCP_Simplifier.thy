theory MCP_Simplifier
  imports MCP
begin

section \<open>Simplifier trace as an MCP tool\<close>

text \<open>Exposes the simplifier's legacy step tracer (\<^ML>\<open>Simplifier.simp_trace\<close>,
\<^file>\<open>~~/src/Pure/raw_simplifier.ML\<close>) programmatically, rather than only
through the interactive jEdit "Simplifier Trace" panel
(\<^file>\<open>~~/src/Pure/Tools/simplifier_trace.ML\<close>) that mechanism's
\<open>Output.result\<close>/\<open>Active\<close> markup round trip is built for a PIDE front
end, not a synchronous tool call. The legacy tracer instead reports each
rewrite step through plain \<^ML>\<open>tracing\<close> calls (\<open>cond_tracing'\<close>), which
the \<^ML_structure>\<open>MCP_Output\<close> capture substrate already routes to a
per-call buffer -- so wrapping it needs no new infrastructure, only the
\<open>capture\<close> tool form.\<close>

mcp_tool "simp_trace" = capture \<open>fn ctxt => fn args =>
  let
    val raw = MCP_Combinators.arg args "expr"
    val depth_limit = MCP_Combinators.arg_int args "depth_limit"
    val t = Syntax.read_term ctxt raw
    val ctxt' = ctxt
      |> Config.put Simplifier.simp_trace true
      |> Config.put Simplifier.simp_trace_depth_limit depth_limit
    val ct = Thm.cterm_of ctxt' t
    val result = Simplifier.rewrite ctxt' ct
  in
    writeln (Syntax.string_of_term ctxt (Thm.term_of (Thm.rhs_of result)))
  end\<close>
  (description \<open>Simplify a term with the simplifier's step tracer enabled
    and return the trace: one numbered line per rewrite attempt (the
    candidate rule and the subterm it tries to rewrite), followed by the
    fully simplified term on the last line. Use this to debug why a simp
    call loops, fails to fire a rule you expected, or picks an unexpected
    rule -- ordinary simp calls report only the end result. Tracing only
    goes as deep as depth_limit; raise it if the trace is truncated with
    "simp_trace_depth_limit exceeded!" but be aware output grows fast
    with depth on non-trivial terms.\<close>)
  (params
    expr :: "term" \<open>the term to simplify, using the current context's
      default simpset\<close>
    depth_limit :: nat = "20" \<open>maximum simplifier recursion depth to
      trace before tracing is silently dropped for deeper calls\<close>)
  (annotations read_only)

end
