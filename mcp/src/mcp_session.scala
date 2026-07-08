/*  Title:      mcp/src/mcp_session.scala

Headless PIDE session serving MCP tools registered in Isabelle/ML.

The ML side (mcp/Tools/MCP_Tools.thy) defines protocol commands
"MCP.tools" and "MCP.run_tool"; their replies arrive as protocol
messages "MCP.tools_result" and "MCP.run_tool_result" and complete
promises created by ml_tools()/ml_run().
*/

package isabelle.mcp

import isabelle._


/* what the JSON-RPC layer (MCP_Server.Handler) needs from the prover side:
   MCP_Session is the real implementation, tests substitute MCP_Test.Fake_Backend */
trait MCP_Backend {
  def ml_tools(): List[(String, String)]
  def ml_run(name: String, arg: String): MCP_Session.Result
  def mcp_resources(): List[(String, String, String)]
  def mcp_resource_read(uri: String): MCP_Session.Result
  def stop(): Unit
}

object MCP_Session {
  sealed abstract class Result { def ok: Boolean }
  case class Ok(text: String) extends Result { def ok = true }
  case class Error(message: String) extends Result { def ok = false }

  def decode_tools(body: XML.Body): List[(String, String)] = {
    import XML.Decode._
    list(pair(string, string))(body)
  }

  def decode_theories(body: XML.Body): List[String] = {
    import XML.Decode._
    list(string)(body)
  }

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
    val mcp_session = new MCP_Session(session, session_name, session_dirs, theory)

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

class MCP_Session private(
  val session: Headless.Session,
  val session_name: String,
  val session_dirs: List[Path],
  val theory: String
) extends MCP_Backend {
  private val tools_promises =
    Synchronized(List.empty[Promise[List[(String, String)]]])
  private val theories_promises =
    Synchronized(List.empty[Promise[List[String]]])
  private val run_promises =
    Synchronized(Map.empty[String, Promise[MCP_Session.Result]])

  private object Handler extends Session.Protocol_Handler {
    private def tools_result(msg: Prover.Protocol_Output): Boolean = {
      val tools = MCP_Session.decode_tools(YXML.parse_body(msg.chunk))
      tools_promises.change { promises =>
        promises.reverse.foreach(_.fulfill(tools))
        Nil
      }
      true
    }

    private def theories_result(msg: Prover.Protocol_Output): Boolean = {
      val theories = MCP_Session.decode_theories(YXML.parse_body(msg.chunk))
      theories_promises.change { promises =>
        promises.reverse.foreach(_.fulfill(theories))
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
        "MCP.theories_result" -> theories_result,
        "MCP.run_tool_result" -> run_tool_result)
  }

  session.init_protocol_handler(Handler)

  def ml_tools(): List[(String, String)] = {
    val promise = Future.promise[List[(String, String)]]
    tools_promises.change(promise :: _)
    session.protocol_command("MCP.tools")
    promise.join
  }

  def ml_theories(): List[String] = {
    val promise = Future.promise[List[String]]
    theories_promises.change(promise :: _)
    session.protocol_command("MCP.theories")
    promise.join
  }

  def ml_run(name: String, arg: String): MCP_Session.Result = {
    val id = UUID.random().toString
    val promise = Future.promise[MCP_Session.Result]
    run_promises.change(_ + (id -> promise))
    session.protocol_command_raw("MCP.run_tool", List(Bytes(id), Bytes(name), Bytes(arg)))
    promise.join
  }

  /* isabelle://session: the cheap always-there overview (name, dirs, loaded
     theory, loaded theories via Thy_Info.get_names()). no repls/scope yet
     -- those need the MCP_Session.ir bridge and a scope feature that don't
     exist yet */
  def mcp_resources(): List[(String, String, String)] =
    List(("isabelle://session", "session", "current session name, dirs, loaded theories"))

  def mcp_resource_read(uri: String): MCP_Session.Result =
    uri match {
      case "isabelle://session" =>
        MCP_Session.Ok(
          "session: " + session_name + "\n" +
          "dirs: " + session_dirs.map(_.implode).mkString(", ") + "\n" +
          "theory: " + theory + "\n" +
          "theories: " + ml_theories().mkString(", "))
      case _ => MCP_Session.Error("Unknown MCP resource " + quote(uri))
    }

  def stop(): Unit = { session.stop(); () }
}
