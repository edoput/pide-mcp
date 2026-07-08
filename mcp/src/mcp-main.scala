/*  Title:      mcp/src/mcp-main.scala

Command-line tool: isabelle mcp_server.
*/

package isabelle.mcp

import isabelle._

object Main {
  val isabelle_tool =
    Isabelle_Tool("mcp_server", "Model Context Protocol Server for PIDE", Scala_Project.here,
    { args =>
      var session_dirs: List[Path] = Nil
      var session_name = "MCP-Tools"
      var theory = "MCP_Tools"

      val getopts = Getopts("""
Usage: isabelle mcp_server [OPTIONS]

  Options are:
    -d DIR       include session directory (default: $ISABELLE_MCP_HOME/Tools)
    -s SESSION   session with the MCP tool registry (default: MCP-Tools)
    -T THEORY    theory registering the MCP tools (default: MCP_Tools)

  Run a Model Context Protocol server on stdin/stdout (newline-delimited
  JSON-RPC 2.0), executing tools registered in Isabelle/ML. The server
  exits when stdin is closed.
""",
        "d:" -> (arg => session_dirs = session_dirs ::: List(Path.explode(arg))),
        "s:" -> (arg => session_name = arg),
        "T:" -> (arg => theory = arg))

      val more_args = getopts(args)
      if (more_args.nonEmpty) getopts.usage()

      val dirs =
        if (session_dirs.isEmpty) List(Path.explode("$ISABELLE_MCP_HOME/Tools"))
        else session_dirs

      val options = Options.init()
      val progress = new Console_Progress(stderr = true)

      MCP_Server.run(options, session_name, dirs, theory, progress = progress)
    })
}

class Tools extends Isabelle_Scala_Tools(Main.isabelle_tool)
