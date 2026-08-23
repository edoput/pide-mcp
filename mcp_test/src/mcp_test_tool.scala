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
      classOf[MCP_Spec_Metadata_Tests],
      classOf[MCP_Theory_Metadata_Tests],
      classOf[MCP_Protocol_Tests],
      classOf[MCP_Readiness_Tests],
      classOf[MCP_Tools_Tests],
      classOf[MCP_Tool_Scope_Tests],
      classOf[MCP_Resources_Tests],
      classOf[MCP_Resource_Scope_Tests],
      classOf[MCP_Scope_Show_Tests],
      classOf[MCP_Codec_Tests],
      classOf[MCP_Symbol_Tests],
      classOf[MCP_Locator_Tests],
      classOf[MCP_Doc_Catalog_Tests],
      classOf[MCP_Doc_Read_Tests],
      classOf[MCP_Config_Tests])

  /* prover-spawning suites, behind -b: heap suites (fresh ML_process
     per ml() call) first -- cheaper than the PIDE-session suites */
  val heap_suites: List[Class[? <: munit.Suite]] =
    List(
      classOf[MCP_Heap_Fixture_Tests],
      classOf[MCP_Registry_Heap_Tests],
      classOf[MCP_Tool_Scope_Heap_Tests])

  val pide_suites: List[Class[? <: munit.Suite]] =
    List(
      classOf[MCP_Bridge_Tests],
      classOf[MCP_Ir_Bridge_Tests],
      classOf[MCP_Run_Tool_Async_Tests])

  val bridge_suites: List[Class[? <: munit.Suite]] = heap_suites ::: pide_suites

  val suite_definitions: List[MCP_Spec_Metadata.Suite_Def] =
    unit_suites.map(cls =>
      MCP_Spec_Metadata.Suite_Def(MCP_Test_Layers("scala_unit_suites"), cls)) :::
      heap_suites.map(cls =>
        MCP_Spec_Metadata.Suite_Def(MCP_Test_Layers("heap_suites"), cls)) :::
      pide_suites.map(cls =>
        MCP_Spec_Metadata.Suite_Def(MCP_Test_Layers("pide_suites"), cls))

  val isabelle_tool =
    Isabelle_Tool("mcp_test", "run mcp component test suites", Scala_Project.here,
    { args =>
      var bridge = false
      var session_dirs: List[Path] = Nil
      var name_filter: Option[String] = None
      var manifest: Option[Path] = None

      val getopts = Getopts("""
Usage: isabelle mcp_test [OPTIONS]

  Options are:
    -b           also run prover-spawning suites: heap tests (raw
                 ML_process against saved heaps) and ML-bridge tests
                 against real PIDE sessions
    -d DIR       session directory for -b (default: $ISABELLE_MCP_HOME/Tools)
    -M FILE      write the full metadata manifest to FILE and exit without
                 evaluating test bodies (default output for normal runs:
                 $ISABELLE_MCP_TEST_HOME/lib/munit-spec.json)
    -t PATTERN   when executing, run only tests whose name or plan-link id
                 contains PATTERN; manifest output is never filtered

  Run the mcp component test suites (munit): JSON-RPC handler and stdio
  loop against a fake backend (fast, no prover). With -b, additionally
  start headless PIDE sessions on MCP-Tools and MCP-HOL and test the
  protocol-command bridges (MCP.run_tool and MCP.ir) end to end.
""",
        "b" -> (_ => bridge = true),
        "d:" -> (arg => session_dirs = session_dirs ::: List(Path.explode(arg))),
        "M:" -> (arg => manifest = Some(Path.explode(arg))),
        "t:" -> (arg => name_filter = Some(arg)))

      val more_args = getopts(args)
      if (more_args.nonEmpty) getopts.usage()
      if (manifest.isDefined && (bridge || session_dirs.nonEmpty || name_filter.nonEmpty)) {
        error("-M cannot be combined with -b, -d, or -t")
      }

      val progress = new Console_Progress()

      val manifest_path = manifest.getOrElse(MCP_Spec_Metadata.default_manifest_path)
      MCP_Spec_Metadata.write(manifest_path, suite_definitions)
      progress.echo("Wrote full test metadata manifest to " + manifest_path)

      manifest match {
        case Some(_) =>
        case None =>
          MCP_Test_Config.options = Options.init()
          MCP_Test_Config.session_dirs =
            if (session_dirs.isEmpty) List(Path.explode("$ISABELLE_MCP_HOME/Tools"))
            else session_dirs
          MCP_Test_Config.progress = progress

          val suites = unit_suites ::: (if (bridge) bridge_suites else Nil)
          val failures = MCP_Test_Runner.run(suites, name_filter, progress)

          if (failures > 0) error(failures.toString + " test(s) failed")
          else progress.echo("All tests passed")
      }
    })
}

class Test_Tools extends Isabelle_Scala_Tools(
  MCP_Test.isabelle_tool,
  MCP_Theory_Metadata.isabelle_tool)
