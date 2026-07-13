/*  Title:      mcp_test/src/mcp_test_tool.scala

Command-line tool: isabelle mcp_test.

The default run exercises the JSON-RPC handler and the stdio loop
against Fake_Backend -- fast, no prover. With -b it additionally starts
headless PIDE sessions on MCP-Tools and MCP-HOL and tests the ML
bridges (protocol commands, promise routing), the one layer a fake
cannot cover. Suites are munit; the runner reports PASS/FAIL per test.
*/

package isabelle.mcp

import isabelle._

object MCP_Test {
  val unit_suites: List[Class[? <: munit.Suite]] =
    List(
      classOf[MCP_Protocol_Tests],
      classOf[MCP_Tools_Tests],
      classOf[MCP_Tool_Scope_Tests],
      classOf[MCP_Resources_Tests],
      classOf[MCP_Resource_Scope_Tests],
      classOf[MCP_Codec_Tests])

  /* prover-spawning suites, behind -b: heap suites (fresh ML_process
     per ml() call) first -- cheaper than the PIDE-session suites */
  val bridge_suites: List[Class[? <: munit.Suite]] =
    List(
      classOf[MCP_Heap_Fixture_Tests],
      classOf[MCP_Registry_Heap_Tests],
      classOf[MCP_Tool_Scope_Heap_Tests],
      classOf[MCP_Bridge_Tests],
      classOf[MCP_Ir_Bridge_Tests])

  val isabelle_tool =
    Isabelle_Tool("mcp_test", "run mcp component test suites", Scala_Project.here,
    { args =>
      var bridge = false
      var session_dirs: List[Path] = Nil
      var name_filter: Option[String] = None

      val getopts = Getopts("""
Usage: isabelle mcp_test [OPTIONS]

  Options are:
    -b           also run prover-spawning suites: heap tests (raw
                 ML_process against saved heaps) and ML-bridge tests
                 against real PIDE sessions
    -d DIR       session directory for -b (default: $ISABELLE_MCP_HOME/Tools)
    -t PATTERN   run only tests whose name contains PATTERN

  Run the mcp component test suites (munit): JSON-RPC handler and stdio
  loop against a fake backend (fast, no prover). With -b, additionally
  start headless PIDE sessions on MCP-Tools and MCP-HOL and test the
  protocol-command bridges (MCP.run_tool and MCP.ir) end to end.
""",
        "b" -> (_ => bridge = true),
        "d:" -> (arg => session_dirs = session_dirs ::: List(Path.explode(arg))),
        "t:" -> (arg => name_filter = Some(arg)))

      val more_args = getopts(args)
      if (more_args.nonEmpty) getopts.usage()

      val progress = new Console_Progress()

      MCP_Test_Config.options = Options.init()
      MCP_Test_Config.session_dirs =
        if (session_dirs.isEmpty) List(Path.explode("$ISABELLE_MCP_HOME/Tools"))
        else session_dirs
      MCP_Test_Config.progress = progress

      val suites = unit_suites ::: (if (bridge) bridge_suites else Nil)
      val failures = MCP_Test_Runner.run(suites, name_filter, progress)

      if (failures > 0) error(failures.toString + " test(s) failed")
      else progress.echo("All tests passed")
    })
}

class Test_Tools extends Isabelle_Scala_Tools(MCP_Test.isabelle_tool)
