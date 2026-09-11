/*  Title:      mcp/src/mcp-main.scala

Command-line tool: isabelle mcp_server.
*/

package isabelle.mcp

import isabelle._

object Main {
  val isabelle_tool =
    Isabelle_Tool("mcp_server", "Model Context Protocol Server for PIDE", Scala_Project.here,
    { args =>
      var options = Options.init()
      var verbose = false
      var session_dirs: List[Path] = Nil
      var session_name = "MCP-Tools"
      var theory = "MCP_Tools"

      val getopts = Getopts("""
Usage: isabelle mcp_server [OPTIONS]

  Options are:
    -?           print this help
    -d DIR       include session directory (the component's own sessions,
                 MCP-Tools and MCP-HOL, are always known)
    -o OPTION    override an Isabelle system OPTION (via NAME=VAL or NAME);
                 e.g. -o mcp_shutdown_drain=30 to wait longer for
                 in-flight requests when stdin closes
    -s SESSION   session with the MCP tool registry (default: MCP-Tools)
    -T THEORY    theory registering the MCP tools (default: MCP_Tools)
    -v           verbose Isabelle progress; report resolved heap input paths,
                 installation, session sources and registry theory on stderr

  Run a Model Context Protocol server on stdin/stdout (newline-delimited
  JSON-RPC 2.0), executing tools registered in Isabelle/ML. The server
  exits when stdin is closed. Progress and diagnostics go to stderr; stdout
  is reserved for JSON-RPC.

  Startup checks the saved session image using Isabelle's build library:
    reused          the image and dependencies passed the no-build check
    build required  the normal Isabelle build must run
    build completed the build operation succeeded
  A reused image still needs prover startup and bridge-root theory processing.
  -v reports library-resolved inputs, not observed operating-system file opens.

  Examples:
    isabelle mcp_server -s MCP-HOL -T MCP-HOL.MCP
    isabelle mcp_server -v -s MCP-HOL -T MCP-HOL.MCP
    isabelle mcp_server -s MCP-Tools -T MCP-Tools.MCP_Tools

  If -v is rejected, the selected installation is loading an older MCP
  component. Check ISABELLE_MCP_HOME with isabelle getenv, then build the
  intended component with isabelle scala_build using that same environment.
""",
        "v" -> (_ => verbose = true),
        "d:" -> (arg => session_dirs = session_dirs ::: List(Path.explode(arg))),
        "o:" -> (arg => options = options + arg),
        "s:" -> (arg => session_name = arg),
        "T:" -> (arg => theory = arg))

      val more_args = getopts(args)
      if (more_args.nonEmpty) getopts.usage()

      val progress = new Console_Progress(stderr = true, verbose = verbose)

      MCP_Server.run(options, session_name, session_dirs, theory, progress = progress)
    })
}

class Tools extends Isabelle_Scala_Tools(Main.isabelle_tool)
