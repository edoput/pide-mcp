/*  Title:      mcp_test/src/mcp_heap_tests.scala

Fresh-process tests against saved Pure, MCP-Tools, and MCP-HOL heaps,
covering fixture output/error handling, persisted tool registration, and
REPL context resolution.
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
        val thy =
          (case try Thy_Info.get_theory \"MCP-Tools.MCP_Tools\" of
            SOME thy => thy
          | NONE => Thy_Info.get_theory \"MCP_Tools\");
        val ctxt = #2 (MCP_Context_Locator.resolve thy (MCP_Context_Locator.theory thy));
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

/* plans/context_locator A2: repl states expose readable contexts mid-proof.
   MCP_Repl contributes the repl resolver through inherited Theory_Data; this
   pins that registration and its evolving Proof.context in a fresh process
   against the real MCP-HOL heap. */
class MCP_Tool_Scope_Heap_Tests extends MCP_Heap_Suite("MCP-HOL") {
  test("heap: repl context locator resolves its latest state and names an unknown repl") {
    val probe =
      """
      let
        fun check b msg = if b then () else error msg;
        val root =
          (case try Thy_Info.get_theory \"MCP-HOL.MCP_Repl\" of
            SOME thy => thy
          | NONE => Thy_Info.get_theory \"MCP_Repl\");
        val _ = Ir.init \"R\" [\"Main\"];
        val _ = Ir.step \"R\" \"lemma \\\"True\\\"\";
        val ctxt = #2 (MCP_Context_Locator.resolve_string root \"isabelle://context/repl/R\");
        val _ = check (length (MCP_Tool.active (Context.Proof ctxt)) >= 0) \"active tools not readable mid-proof\";
        val _ = Ir.step \"R\" \"by simp\";
        val ctxt2 = #2 (MCP_Context_Locator.resolve_string root \"isabelle://context/repl/R\");
        val _ = check (length (MCP_Tool.active (Context.Proof ctxt2)) >= 0) \"active tools not readable after proof close\";
        val unknown_repl_msg =
          (case Exn.capture_body (fn () => MCP_Context_Locator.resolve_string root
              \"isabelle://context/repl/NOPE\") of
            Exn.Exn exn => Runtime.exn_message exn
          | Exn.Res _ => error \"expected unknown repl to fail\");
        val _ = check (String.isSubstring \"NOPE\" unknown_repl_msg) (\"unknown repl message missing id: \" ^ unknown_repl_msg);
      in () end;
      """
    val probe_literal = quote(probe.linesIterator.map(_.trim).mkString(" "))
    ml_check("""
      let
        val thy =
          (case try Thy_Info.get_theory "MCP-HOL.MCP_Repl" of
            SOME thy => thy
          | NONE => Thy_Info.get_theory "MCP_Repl");
        val ctxt = Proof_Context.init_global thy;
      in
        ML_Context.eval_in (SOME ctxt) ML_Compiler.flags Position.none
          (ML_Lex.read """ + probe_literal + """)
      end;""")
  }
}

class MCP_Heap_Fixture_Tests extends MCP_Heap_Suite("Pure") {
  spec_test("heap layer executes a fresh ML process and captures its output",
      covers = List("planning_gate#T8")) {
    val out = ml_check("""writeln "heap fixture hello";""")
    assert(out.contains("heap fixture hello"), out)
  }

  test("heap: ml reports an ML error as failure with its message") {
    val result = ml("""error "heap fixture boom";""")
    assert(!result.ok, "expected nonzero rc, got ok:\n" + result.out)
    assert(result.out.contains("heap fixture boom"), result.out)
  }
}
