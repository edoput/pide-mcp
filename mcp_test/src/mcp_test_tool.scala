/*  Title:      mcp_test/src/mcp_test_tool.scala

Command-line tool: isabelle mcp_test.

The default run exercises the JSON-RPC handler and the stdio loop
against Fake_Backend -- fast, no prover. -L selects one registered
execution layer; -L all (and its compatibility alias -b) runs every
Scala layer. Suites are munit; the runner reports PASS/FAIL per test.
*/

package isabelle.mcp

import isabelle._

object MCP_Test {
  val unit_suites: List[Class[? <: munit.Suite]] =
    List(
      classOf[MCP_Spec_Metadata_Tests],
      classOf[MCP_Theory_Metadata_Tests],
      classOf[MCP_Connection_Protocol_Tests],
      classOf[MCP_Application_Tests],
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
      classOf[MCP_Doc_Read_Performance_Tests],
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

  val scala_unit_layer: String = MCP_Test_Layers("scala_unit_suites")
  val heap_layer: String = MCP_Test_Layers("heap_suites")
  val bridge_layer: String = MCP_Test_Layers("pide_suites")
  val all_selector = "all"

  val executable_layers: List[String] =
    List(scala_unit_layer, heap_layer, bridge_layer)

  def suites_for(selector: String): List[Class[? <: munit.Suite]] =
    if (selector == scala_unit_layer) unit_suites
    else if (selector == heap_layer) heap_suites
    else if (selector == bridge_layer) pide_suites
    else if (selector == all_selector) unit_suites ::: heap_suites ::: pide_suites
    else error(
      "Unknown test layer " + quote(selector) + "; expected " +
        (executable_layers ::: List(all_selector)).map(quote).mkString(", "))

  val suite_definitions: List[MCP_Spec_Metadata.Suite_Def] =
    unit_suites.map(cls =>
      MCP_Spec_Metadata.Suite_Def(scala_unit_layer, cls)) :::
      heap_suites.map(cls =>
        MCP_Spec_Metadata.Suite_Def(heap_layer, cls)) :::
      pide_suites.map(cls =>
        MCP_Spec_Metadata.Suite_Def(bridge_layer, cls))

  val isabelle_tool =
    Isabelle_Tool("mcp_test", "run mcp component test suites", Scala_Project.here,
    { args =>
      var legacy_all = false
      var selected_layer: Option[String] = None
      var session_dirs: List[Path] = Nil
      var name_filter: Option[String] = None
      var manifest: Option[Path] = None

      val getopts = Getopts("""
Usage: isabelle mcp_test [OPTIONS]

  Options are:
    -b           compatibility alias for -L all
    -L LAYER     execute scala-unit, heap, bridge, or all
                 (default: scala-unit)
    -d DIR       session directory for heap, bridge, or all
                 (default: $ISABELLE_MCP_HOME/Tools)
    -M FILE      write the full metadata manifest to FILE and exit without
                 evaluating test bodies (default output for normal runs:
                 $ISABELLE_MCP_TEST_HOME/lib/munit-spec.json)
    -t PATTERN   when executing, run only tests whose name or plan-link id
                 contains PATTERN; manifest output is never filtered

  Run the mcp component test suites (munit): JSON-RPC handler and stdio
  loop against a fake backend (fast, no prover). Heap runs fresh
  ML_process tests against saved heaps. Bridge starts headless PIDE
  sessions on MCP-Tools and MCP-HOL. Use -L all (or -b) for all three.
""",
        "b" -> (_ => legacy_all = true),
        "L:" -> (arg =>
          if (selected_layer.isDefined) error("-L may be specified only once")
          else selected_layer = Some(arg)),
        "d:" -> (arg => session_dirs = session_dirs ::: List(Path.explode(arg))),
        "M:" -> (arg => manifest = Some(Path.explode(arg))),
        "t:" -> (arg => name_filter = Some(arg)))

      val more_args = getopts(args)
      if (more_args.nonEmpty) getopts.usage()
      if (legacy_all && selected_layer.isDefined) {
        error("-b and -L are alternatives")
      }
      if (manifest.isDefined &&
          (legacy_all || selected_layer.isDefined || session_dirs.nonEmpty || name_filter.nonEmpty)) {
        error("-M cannot be combined with -b, -L, -d, or -t")
      }
      val execution_layer =
        if (legacy_all) all_selector else selected_layer.getOrElse(scala_unit_layer)
      val execution_suites = suites_for(execution_layer)
      if (session_dirs.nonEmpty && execution_layer == scala_unit_layer) {
        error("-d has no effect on the scala-unit layer")
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

          progress.echo("Running test layer " + execution_layer)
          val failures = MCP_Test_Runner.run(execution_suites, name_filter, progress)

          if (failures > 0) error(failures.toString + " test(s) failed")
          else progress.echo("All tests passed")
      }
    })
}

class Test_Tools extends Isabelle_Scala_Tools(
  MCP_Test.isabelle_tool,
  MCP_Theory_Metadata.isabelle_tool)
