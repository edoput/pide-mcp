/*  Title:      mcp_test/src/mcp_heap_tests.scala

Self-test of the MCP_Heap_Suite fixture on the smallest heap (Pure):
the helpers' contract -- output capture and error detection -- not any
mcp ML code. Heap suites proper (pinning fresh-process/heap-restart
behavior such as Thy_Info keying or the segments gap) extend
MCP_Heap_Suite with the heap under test.
*/

package isabelle.mcp

import isabelle._

/* the registry pivot's heap claim (plans/mcp_tool_registry): tools are
   function-valued context data in the MCP_Tools theory, so a FRESH
   process loading the saved heap -- exactly what a live mcp_server
   inherits -- must still see shout registered, active, and runnable.

   theory-defined ML structures (MCP_Tool, MCP_Protocol, ...) are bound
   in the THEORY's ML environment, not the raw ML_process toplevel, so
   the probe compiles inside the theory context via ML_Context.eval_in
   (Pure names only in the outer layer); an inner failure propagates and
   fails the process. */
class MCP_Registry_Heap_Tests extends MCP_Heap_Suite("MCP-Tools") {
  test("heap: shout survives registered + active and the payloads serve it") {
    val probe =
      """
      let
        val ctxt = MCP_Protocol.designated_context \"\";
        val context = Context.Proof ctxt;
        fun check b msg = if b then () else error msg;
      in
        check (MCP_Tool.defined context \"MCP_Tools.shout\") \"shout not registered\";
        check (MCP_Tool.is_active context \"MCP_Tools.shout\") \"shout not active\";
        check (MCP_Protocol.run_tool ctxt \"MCP_Tools.shout\" [(\"input\", \"abc\")] = (\"ok\", \"ABC\"))
          \"run_tool failed\";
        check (MCP_Resource.is_active context \"MCP_Tools.greeting\") \"greeting not active\";
        check (MCP_Protocol.read_resource ctxt \"MCP_Tools.greeting\" =
          (\"ok\", \"hello from MCP_Resource\")) \"read_resource failed\"
      end;
      """
    /* the probe travels as an ML string literal: quotes are pre-escaped
       above (\"), newlines must go (ML strings are single-line) */
    val probe_literal = quote(probe.linesIterator.map(_.trim).mkString(" "))
    ml_check("""
      let
        val thy =
          (case try Thy_Info.get_theory "MCP-Tools.MCP_Tools" of
            SOME thy => thy
          | NONE => Thy_Info.get_theory "MCP_Tools");
        val ctxt = Proof_Context.init_global thy;
      in
        ML_Context.eval_in (SOME ctxt) ML_Compiler.flags Position.none
          (ML_Lex.read """ + probe_literal + """)
      end;""")
  }
}

class MCP_Heap_Fixture_Tests extends MCP_Heap_Suite("Pure") {
  test("heap: ml captures writeln output") {
    val out = ml_check("""writeln "heap fixture hello";""")
    assert(out.contains("heap fixture hello"), out)
  }

  test("heap: ml reports an ML error as failure with its message") {
    val result = ml("""error "heap fixture boom";""")
    assert(!result.ok, "expected nonzero rc, got ok:\n" + result.out)
    assert(result.out.contains("heap fixture boom"), result.out)
  }
}
