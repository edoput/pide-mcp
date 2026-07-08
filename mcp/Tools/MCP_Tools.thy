theory MCP_Tools
  imports Pure
begin

section \<open>MCP tool registry\<close>

ML \<open>
signature MCP_TOOL =
sig
  type tool = {name: string, description: string, run: string -> string}
  val register: tool -> unit
  val list: unit -> tool list
  val run: string -> string -> string
end;

structure MCP_Tool: MCP_TOOL =
struct

type tool = {name: string, description: string, run: string -> string};

val registry = Synchronized.var "MCP_Tool.registry" ([]: tool list);

fun register (tool: tool) =
  Synchronized.change registry (fn tools =>
    (if exists (fn t => #name t = #name tool) tools
     then warning ("Redefining MCP tool " ^ quote (#name tool))
     else ();
     tool :: filter_out (fn t => #name t = #name tool) tools));

fun list () = rev (Synchronized.value registry);

fun run name arg =
  (case find_first (fn t => #name t = name) (Synchronized.value registry) of
    NONE => error ("Unknown MCP tool " ^ quote name)
  | SOME tool => #run tool arg);

end;
\<close>

section \<open>Protocol payloads\<close>

text \<open>Pure functions, kept apart from the protocol-command wrappers below so
they can be unit-tested (session MCP-Tools-Tests) without a PIDE context.\<close>

ML \<open>
signature MCP_PROTOCOL =
sig
  val tools_body: unit -> XML.body
  val theories_body: unit -> XML.body
  val run_tool: string -> string -> string * string
end;

structure MCP_Protocol: MCP_PROTOCOL =
struct

fun tools_body () =
  let open XML.Encode
  in list (pair string string) (map (fn t => (#name t, #description t)) (MCP_Tool.list ())) end;

(*theories loaded in this session -- heap image theories plus anything
  loaded on top of it; backs the isabelle://session resource*)
fun theories_body () =
  let open XML.Encode in list string (Thy_Info.get_names ()) end;

(*(status, output) with status = "ok" | "error"; interrupts are reraised*)
fun run_tool name arg =
  (case Exn.capture_body (fn () => MCP_Tool.run name arg) of
    Exn.Res res => ("ok", res)
  | Exn.Exn exn =>
      if Exn.is_interrupt exn then Exn.reraise exn
      else ("error", Runtime.exn_message exn));

end;
\<close>

section \<open>Protocol commands for Isabelle/Scala\<close>

ML \<open>
val _ =
  Protocol_Command.define "MCP.tools"
    (fn [] =>
      Output.protocol_message [Markup.function "MCP.tools_result"]
        [MCP_Protocol.tools_body ()]);

val _ =
  Protocol_Command.define "MCP.theories"
    (fn [] =>
      Output.protocol_message [Markup.function "MCP.theories_result"]
        [MCP_Protocol.theories_body ()]);

val _ =
  Protocol_Command.define "MCP.run_tool"
    (fn [id, name, arg] =>
      let val (status, output) = MCP_Protocol.run_tool name arg in
        Output.protocol_message
          [Markup.function "MCP.run_tool_result", ("id", id), ("status", status)]
          [[XML.Text output]]
      end);
\<close>

section \<open>Demo tool\<close>

ML \<open>
val _ =
  MCP_Tool.register
    {name = "shout",
     description = "uppercase the input",
     run = String.map Char.toUpper};
\<close>

end
