(*  Title:      mcp/Tools/mcp_bridge.ML
    Author:     isabelle-mcp

Versioned, theory-extensible Isabelle/Scala bridge endpoint.
*)

signature MCP_BRIDGE =
sig
  type handler = Future.group -> theory -> XML.body -> XML.body

  type root_selector =
    {theory: string, node: string, command_id: Document_ID.command, exec_id: Document_ID.exec}
  val root_selector: Properties.T -> root_selector
  val revision: string
  val register: binding -> handler -> theory -> theory
  val registered: theory -> string list
  val invoke: theory -> string -> Future.group -> XML.body -> XML.body
  val select_result: int -> string -> string -> string -> XML.body -> XML.tree
end;


structure MCP_Bridge: MCP_BRIDGE =
struct

type handler = Future.group -> theory -> XML.body -> XML.body;
type entry = {serial: serial, position: Position.T, handler: handler};

val revision = "1";
val command = "MCP.bridge";
val result_function = "MCP.bridge_result";
val request_element = "mcp_bridge";
val result_element = "mcp_bridge_result";

fun eq_entry
    ({serial = serial1, ...}: entry, {serial = serial2, ...}: entry) =
  serial1 = serial2;

structure Operation_Data = Theory_Data
(
  type T = entry Symtab.table;
  val empty = Symtab.empty;
  val merge = Symtab.merge eq_entry;
);

fun check_operation name =
  if name <> "" andalso forall (fn c =>
      Char.isAlphaNum c orelse c = #"_" orelse c = #"-") (String.explode name)
  then name
  else error ("Malformed MCP bridge operation " ^ quote name);

fun register binding handler thy =
  let
    val name = check_operation (Binding.name_of binding);
    val position = Binding.pos_of binding;
    val table = Operation_Data.get thy;
    val _ =
      (case Symtab.lookup table name of
        NONE => ()
      | SOME {position = previous, ...} =>
          error ("Duplicate MCP bridge operation " ^ quote name ^
            Position.here position ^ Position.here previous));
    val entry = {serial = serial (), position = position, handler = handler};
  in Operation_Data.put (Symtab.update (name, entry) table) thy end;

fun registered thy = sort_strings (Symtab.keys (Operation_Data.get thy));

fun invoke root name group payload =
  (case Symtab.lookup (Operation_Data.get root) name of
    SOME {handler, ...} => handler group root payload
  | NONE => error ("Unknown MCP bridge operation " ^ quote name));

fun unique_property properties name =
  (case filter (fn (key, _) => key = name) properties of
    [(_, value)] => SOME value
  | [] => NONE
  | _ => error ("Duplicate MCP bridge envelope property " ^ quote name));

fun required_property properties name =
  (case unique_property properties name of
    SOME value => if value = "" then error ("Empty MCP bridge " ^ name) else value
  | NONE => error ("Missing MCP bridge envelope property " ^ quote name));

fun check_properties allowed properties =
  List.app (fn (name, _) =>
    if member (op =) allowed name then ()
    else error ("Unknown MCP bridge envelope property " ^ quote name)) properties;

fun result_envelope id operation status payload =
  XML.Elem ((result_element,
    [("revision", revision), ("kind", "result"), ("id", id),
     ("operation", operation), ("status", status)]), payload);

fun too_large_payload actual limit =
  XML.Encode.pair XML.Encode.string XML.Encode.string
    (Int.toString actual, Int.toString limit);

(* Kept pure so the publication boundary and its regression test select the
   same complete YXML envelope.  The replacement is deliberately not fed back
   through this selector. *)
fun select_result limit id operation status payload =
  let
    val envelope = result_envelope id operation status payload;
    val actual = Bytes.size (YXML.bytes_of envelope);
  in
    if actual <= limit then envelope
    else result_envelope id operation "too_large" (too_large_payload actual limit)
  end;

fun max_reply_bytes () =
  let val limit = Options.default_int "mcp_bridge_max_reply_bytes" in
    if limit > 0 then limit
    else error "mcp_bridge_max_reply_bytes must be positive"
  end;

fun publish id operation status payload =
  Output.protocol_message
    [Markup.function result_function, ("id", id), ("operation", operation)]
    [[select_result (max_reply_bytes ()) id operation status payload]];

fun drain_envelope id status payload =
  XML.Elem ((result_element,
    [("revision", revision), ("kind", "drain"), ("id", id), ("status", status)]), payload);

fun publish_drain_result id status payload =
  Output.protocol_message [Markup.function result_function, ("id", id)]
    [[drain_envelope id status payload]];

fun publish_drain id = publish_drain_result id "ok" [];

fun error_payload message = XML.Encode.string message;

fun protocol_error id operation message =
  publish id operation "protocol_error" (error_payload message);

fun drain_protocol_error id message =
  publish_drain_result id "protocol_error" (error_payload message);

fun remote_result id operation joined =
  (case joined of
    Exn.Res payload => publish id operation "ok" payload
  | Exn.Exn exn => publish id operation "remote_error"
      (error_payload (Runtime.exn_message exn)));

type root_selector =
  {theory: string, node: string, command_id: Document_ID.command, exec_id: Document_ID.exec};

fun root_selector properties : root_selector =
  let
    fun identity key =
      let
        val source = required_property properties key;
        val value =
          (case try Document_ID.parse source of
            SOME value => value
          | NONE => error ("Malformed MCP bridge " ^ key ^ " identity " ^ quote source));
        val _ = value <> Document_ID.none andalso Document_ID.print value = source orelse
          error ("Malformed MCP bridge " ^ key ^ " identity " ^ quote source);
      in value end;
  in
    {theory = required_property properties "theory",
     node = required_property properties "root_node",
     command_id = identity "root_command", exec_id = identity "root_exec"}
  end;

fun document_root ({theory, node, command_id, exec_id}: root_selector) =
  let
    val execution =
      (case try (fn () => Document.command_exec (Document.state ()) node command_id) () of
        SOME (SOME (evaluation, _)) => evaluation
      | _ => error ("Missing MCP bridge root command in " ^ quote node));
    val _ = Command.eval_exec_id execution = exec_id orelse
      error "Stale MCP bridge root execution";
    val _ = Command.eval_finished execution orelse
      error "MCP bridge root execution is not finished";
    val state = Command.eval_result_state execution;
    val _ = Toplevel.is_end_theory state orelse
      error "MCP bridge root command has no completed theory end";
    val root = Toplevel.end_theory Position.none state;
    val actual = Context.theory_long_name root;
    val _ = actual = theory orelse
      error ("MCP bridge root theory mismatch: expected " ^ quote theory ^
        ", found " ^ quote actual);
  in root end;

fun execute_call id selector operation payload =
  let
    val root = document_root selector;
    val _ =
      Symtab.defined (Operation_Data.get root) operation orelse
        error ("Unknown MCP bridge operation " ^ quote operation ^
          " in theory " ^ quote (Context.theory_long_name root));
  in
    MCP_Cancellation.fork_group id ("MCP.bridge." ^ operation)
      (fn group => invoke root operation group payload)
      (fn joined => remote_result id operation joined)
      (List.app publish_drain)
  end;

fun execute_hello id selector =
  let
    val root = document_root selector;
    val payload = XML.Encode.pair XML.Encode.string (XML.Encode.list XML.Encode.string)
      (revision, registered root);
  in publish id "hello" "ok" payload end;

fun command_error properties exn =
  let
    val message = Runtime.exn_message exn;
    fun recover name =
      (case filter (fn (key, _) => key = name) properties of
        [(_, value)] => SOME value
      | _ => NONE);
    val id = recover "id";
    val kind = recover "kind";
    val operation = recover "operation";
  in
    (case (id, kind, operation) of
      (SOME id', SOME "drain", _) => drain_protocol_error id' message
    | (SOME id', _, SOME operation') => protocol_error id' operation' message
    | _ => Output.error_message ("Uncorrelated MCP bridge envelope: " ^ message))
  end;

fun dispatch_properties properties payload =
  let
    val actual_revision = required_property properties "revision";
    val _ = actual_revision = revision orelse
      error ("Unsupported MCP bridge revision " ^ quote actual_revision);
    val kind = required_property properties "kind";
  in
    if kind = "hello" then
      let
        val _ = check_properties ["revision", "kind", "id", "theory", "operation",
          "root_node", "root_command", "root_exec"] properties;
        val id = required_property properties "id";
        val selector = root_selector properties;
        val operation = required_property properties "operation";
        val _ = operation = "hello" orelse error "Invalid MCP bridge hello operation";
        val _ = null payload orelse error "MCP bridge hello has a payload";
      in execute_hello id selector end
    else if kind = "call" then
      let
        val _ = check_properties ["revision", "kind", "id", "theory", "operation",
          "root_node", "root_command", "root_exec"] properties;
        val id = required_property properties "id";
        val selector = root_selector properties;
        val operation = check_operation (required_property properties "operation");
      in execute_call id selector operation payload end
    else if kind = "cancel" then
      let
        val _ = check_properties ["revision", "kind", "id"] properties;
        val id = required_property properties "id";
        val _ = null payload orelse error "MCP bridge cancellation has a payload";
      in ignore (MCP_Cancellation.cancel id) end
    else if kind = "drain" then
      let
        val _ = check_properties ["revision", "kind", "id"] properties;
        val id = required_property properties "id";
        val _ = null payload orelse error "MCP bridge drain has a payload";
        val acknowledgements = MCP_Cancellation.drain id;
      in List.app publish_drain acknowledgements end
    else error ("Unknown MCP bridge envelope kind " ^ quote kind)
  end;

fun dispatch source =
  (case Exn.capture_body (fn () => YXML.parse_body source) of
    Exn.Res [XML.Elem ((name, properties), payload)] =>
      if name <> request_element then
        Output.error_message ("Uncorrelated MCP bridge envelope: expected " ^
          quote request_element ^ ", found " ^ quote name)
      else
        (case Exn.capture_body (fn () => dispatch_properties properties payload) of
          Exn.Res () => ()
        | Exn.Exn exn => command_error properties exn)
  | Exn.Res _ =>
      Output.error_message ("Uncorrelated MCP bridge envelope: expected one " ^
        request_element ^ " element")
  | Exn.Exn exn =>
      Output.error_message ("Cannot decode MCP bridge envelope: " ^ Runtime.exn_message exn));

val _ =
  Protocol_Command.define command
    (fn [source] => dispatch source
      | _ => Output.error_message "MCP.bridge expects exactly one YXML envelope");

end;
