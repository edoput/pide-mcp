/*  Title:      mcp/src/mcp_session.scala

Headless PIDE session serving MCP tools registered in Isabelle/ML.

The ML side (mcp/Tools/MCP_Tools.thy) defines protocol commands
"MCP.tools" and "MCP.run_tool"; their replies arrive as protocol
messages "MCP.tools_result" and "MCP.run_tool_result" and complete
promises created by ml_tools()/ml_run().
*/

package isabelle.mcp

import isabelle._

object MCP_Session {
  sealed abstract class Result { def ok: Boolean }
  case class Ok(text: String) extends Result { def ok = true }
  case class Error(message: String) extends Result { def ok = false }

  def start(
    options: Options,
    session_name: String,
    session_dirs: List[Path],
    theory: String,
    progress: Progress = new Progress
  ): MCP_Session = {
    val build_results =
      Build.build(options, selection = Sessions.Selection.session(session_name),
        progress = progress, build_heap = true, dirs = session_dirs)
    if (!build_results.ok) {
      error("Failed to build session " + quote(session_name) + ": " +
        Process_Result.RC.print(build_results.rc))
    }

    val resources =
      Headless.Resources.make(options, session_name, session_dirs = session_dirs,
        progress = progress)
    val session = resources.start_session(progress = progress)
    val mcp_session = new MCP_Session(session)

    /* theories already in the session image keep their protocol commands
       (defined at build time, persisted in the heap); anything else is
       loaded into the running session */
    val loaded =
      resources.loaded_theory(theory) ||
      resources.loaded_theory(Long_Name.qualify(session_name, theory))
    if (!loaded) {
      val master_dir =
        session_dirs.headOption.map(File.standard_path).getOrElse("")
      val use_result =
        session.use_theories(List(theory), master_dir = master_dir, progress = progress)
      if (!use_result.ok) {
        session.stop()
        error("Failed to load theory " + quote(theory))
      }
    }

    mcp_session
  }
}

class MCP_Session private(val session: Headless.Session) {
  private val tools_promises =
    Synchronized(List.empty[Promise[List[(String, String)]]])
  private val run_promises =
    Synchronized(Map.empty[String, Promise[MCP_Session.Result]])

  private object Handler extends Session.Protocol_Handler {
    private def tools_result(msg: Prover.Protocol_Output): Boolean = {
      val tools = {
        import XML.Decode._
        list(pair(string, string))(YXML.parse_body(msg.chunk))
      }
      tools_promises.change { promises =>
        promises.reverse.foreach(_.fulfill(tools))
        Nil
      }
      true
    }

    private def run_tool_result(msg: Prover.Protocol_Output): Boolean =
      Properties.get(msg.properties, "id") match {
        case Some(id) =>
          val result =
            if (Properties.get(msg.properties, "status") == Some("ok")) {
              MCP_Session.Ok(msg.text)
            }
            else MCP_Session.Error(msg.text)
          run_promises.change { promises =>
            promises.get(id).foreach(_.fulfill(result))
            promises - id
          }
          true
        case None => false
      }

    override val functions: Session.Protocol_Functions =
      List(
        "MCP.tools_result" -> tools_result,
        "MCP.run_tool_result" -> run_tool_result)
  }

  session.init_protocol_handler(Handler)

  def ml_tools(): List[(String, String)] = {
    val promise = Future.promise[List[(String, String)]]
    tools_promises.change(promise :: _)
    session.protocol_command("MCP.tools")
    promise.join
  }

  def ml_run(name: String, arg: String): MCP_Session.Result = {
    val id = UUID.random().toString
    val promise = Future.promise[MCP_Session.Result]
    run_promises.change(_ + (id -> promise))
    session.protocol_command_raw("MCP.run_tool", List(Bytes(id), Bytes(name), Bytes(arg)))
    promise.join
  }

  def stop(): Process_Result = session.stop()
}
