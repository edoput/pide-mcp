theory MCP_Tools
  imports Pure
  keywords "mcp_tool" "mcp_resource" "mcp_test" :: thy_decl
    and "print_mcp_tools" "print_mcp_resources" :: diag
    and "description" "params" "format" "run" "capture" "isar"
begin

text \<open>The header reserves \<^verbatim>\<open>mcp_test\<close> now so it never changes again;
the command itself arrives with its own phase-3 wave (see
plans/mcp_tool_command).\<close>

section \<open>MCP registries: context entities with activation\<close>

text \<open>Tools and resources are CONTEXT ENTITIES (spec phase 3, "the
pivot"): a \<^verbatim>\<open>Name_Space.table\<close> holds the registrations (so a
tool has a position -- ctrl+click -- a theory-qualified name, and
completion), and a name set holds the ACTIVATION state, both in
\<^verbatim>\<open>Generic_Data\<close>. Registration follows theory imports;
activation is toggled by the \<open>[[mcp_tools add/del: name]]\<close> declaration
attribute (PLURAL — the singular is the command keyword, and command
keywords delimit spans so they cannot appear inside \<open>[[...]]\<close>; same
reason the simproc attribute is not called simproc_setup), so bundles
scope tools with no extra machinery. Both
components merge by UNION (standard context-data behavior, as for
simpsets): a del on one import path plus an add on another resurrects
the tool; the escape hatch is a del in the importing theory.

\<^verbatim>\<open>MCP_Tool.declare\<close> is the ONE registration entry point (decided
2026-07-11): there is no other API, and re-running a theory replays
declarations into fresh data, so builds are naturally idempotent.\<close>

ML \<open>
signature MCP_REGISTRY =
sig
  type value
  val space_of: Context.generic -> Name_Space.T
  val declare: binding -> value -> local_theory -> string * local_theory
  val check: Proof.context -> xstring * Position.T -> string
  val get: Context.generic -> string -> value
  val defined: Context.generic -> string -> bool
  val list: Context.generic -> (string * value) list
  val active: Context.generic -> (string * value) list
  val is_active: Context.generic -> string -> bool
  val activate: string -> Context.generic -> Context.generic
  val deactivate: string -> Context.generic -> Context.generic
  val add_del_attribute: attribute context_parser
  val print: bool -> Proof.context -> (string * value -> Pretty.T list) -> unit
end;

functor MCP_Registry(val kind: string val changed: string type value): MCP_REGISTRY =
struct

type value = value;

structure Data = Generic_Data
(
  type T = value Name_Space.table * Symtab.set  (*definitions, active names*)
  val empty = (Name_Space.empty_table kind, Symtab.empty)
  fun merge ((table1, active1), (table2, active2)) =
    (Name_Space.merge_tables (table1, table2),
     Symtab.merge (K true) (active1, active2))
);

val table_of = #1 o Data.get;
val active_set = #2 o Data.get;
val space_of = Name_Space.space_of_table o table_of;

(*a notification for the Isabelle/Scala server (spec: listChanged);
  outside a PIDE/server process the protocol channel is undefined and
  the message is deliberately dropped*)
fun notify_changed () =
  Output.protocol_message [Markup.function changed] []
    handle Output.Protocol_Message _ => ();

(*define + activate: registered tools are active by default; scoped
  registration is deactivation at the declaration site (spec phase 3)*)
fun new_entry binding x context =
  let
    val (name, table') = Name_Space.define context true (binding, x) (table_of context);
  in (name, Data.map (fn (_, active) => (table', Symtab.insert_set name active)) context) end;

fun declare binding x lthy =
  let
    val (name, lthy') =
      lthy
      |> Local_Theory.background_theory_result (fn thy =>
          let val (name, context') = new_entry binding x (Context.Theory thy)
          in (name, Context.the_theory context') end)
      ||> Local_Theory.map_contexts (fn _ => fn ctxt =>
          Context.the_proof (#2 (new_entry binding x (Context.Proof ctxt))));
    val _ = notify_changed ();
  in (name, lthy') end;

fun check ctxt arg =
  #1 (Name_Space.check (Context.Proof ctxt) (table_of (Context.Proof ctxt)) arg);

fun get context name = Name_Space.get (table_of context) name;
fun defined context name = is_some (Name_Space.lookup (table_of context) name);

fun list context = Name_Space.dest_table (table_of context);
fun is_active context name = Symtab.defined (active_set context) name;
fun active context = filter (is_active context o #1) (list context);

fun activate name = Data.map (apsnd (Symtab.insert_set name));
fun deactivate name = Data.map (apsnd (Symtab.remove_set name));

(*declare [[<kind> add: name]] / [[<kind> del: name]]; bare name = add*)
val add_del_attribute =
  Scan.optional (Scan.lift ((Args.add >> K true || Args.del >> K false) --| Args.colon)) true
    -- Args.context -- Scan.lift Parse.name_position >>
    (fn ((add, ctxt), arg) =>
      let val name = check ctxt arg in
        Thm.declaration_attribute
          (K (if add then activate name else deactivate name))
      end);

(*shared body of the print_mcp_* commands: entity markup on every name
  (ctrl+click to the registration), "!" includes inactive entries*)
fun print verbose ctxt prt_value =
  let
    val context = Context.Proof ctxt;
    val table = table_of context;
    val space = Name_Space.space_of_table table;
    fun prt (name, x) =
      Pretty.block
        (Pretty.mark_str (Name_Space.markup space name, Name_Space.extern ctxt space name) ::
          Pretty.str (if is_active context name then "" else " (inactive)") ::
          prt_value (name, x));
    val entries =
      list context |> filter (fn (name, _) => verbose orelse is_active context name);
  in
    Pretty.writeln
      (Pretty.big_list (kind ^ (if verbose then " (all):" else " (active):"))
        (map prt entries))
  end;

end;
\<close>

section \<open>MCP tool registry\<close>

ML \<open>
signature MCP_TOOL =
sig
  datatype form = String_Fun | Diag_Wrap | Method_Wrap | Builtin | Capture
  val form_tag: form -> string
  type param =
    {name: string, typ: string, required: bool, default: string option, description: string}
  type tool =
    {description: string, params: param list, form: form,
     run: Proof.context -> (string * string) list -> string}
  val space_of: Context.generic -> Name_Space.T
  val declare: binding -> tool -> local_theory -> string * local_theory
  val check: Proof.context -> xstring * Position.T -> string
  val get: Context.generic -> string -> tool
  val defined: Context.generic -> string -> bool
  val list: Context.generic -> (string * tool) list
  val active: Context.generic -> (string * tool) list
  val is_active: Context.generic -> string -> bool
  val activate: string -> Context.generic -> Context.generic
  val deactivate: string -> Context.generic -> Context.generic
  val run: Proof.context -> string -> (string * string) list -> string
end;

structure MCP_Tool: MCP_TOOL =
struct

datatype form = String_Fun | Diag_Wrap | Method_Wrap | Builtin | Capture;

fun form_tag String_Fun = "string_fun"
  | form_tag Diag_Wrap = "diag_wrap"
  | form_tag Method_Wrap = "method_wrap"
  | form_tag Builtin = "builtin"
  | form_tag Capture = "capture";

type param =
  {name: string, typ: string, required: bool, default: string option, description: string};

type tool =
  {description: string, params: param list, form: form,
   run: Proof.context -> (string * string) list -> string};

structure Registry =
  MCP_Registry(val kind = "mcp_tool" val changed = "MCP.tools_changed" type value = tool);

open Registry;

fun run ctxt name args = #run (get (Context.Proof ctxt) name) ctxt args;

val _ =
  Theory.setup
    (Attrib.setup \<^binding>\<open>mcp_tools\<close> add_del_attribute
      "activation of MCP tools in the context");

val _ =
  Outer_Syntax.command \<^command_keyword>\<open>print_mcp_tools\<close>
    "print MCP tools available in the context (\"!\" includes inactive)"
    (Parse.opt_bang >> (fn verbose =>
      Toplevel.keep (fn st =>
        print verbose (Toplevel.context_of st) (fn (_, tool) =>
          [Pretty.str (" [" ^ form_tag (#form tool) ^ "]: " ^ #description tool)]))));

end;
\<close>

section \<open>MCP named-resource registry\<close>

text \<open>Backs isabelle://named/{name} (spec's "concrete resources"): a
user-registered resource, listed concretely in resources/list since the
names are known. An exact mirror of MCP_Tool by construction -- the same
\<^verbatim>\<open>MCP_Registry\<close> instance shape, attribute, and print
command.\<close>

ML \<open>
signature MCP_RESOURCE =
sig
  type resource = {description: string, read: Proof.context -> string}
  val space_of: Context.generic -> Name_Space.T
  val declare: binding -> resource -> local_theory -> string * local_theory
  val check: Proof.context -> xstring * Position.T -> string
  val get: Context.generic -> string -> resource
  val defined: Context.generic -> string -> bool
  val list: Context.generic -> (string * resource) list
  val active: Context.generic -> (string * resource) list
  val is_active: Context.generic -> string -> bool
  val activate: string -> Context.generic -> Context.generic
  val deactivate: string -> Context.generic -> Context.generic
  val read: Proof.context -> string -> string
end;

structure MCP_Resource: MCP_RESOURCE =
struct

type resource = {description: string, read: Proof.context -> string};

structure Registry =
  MCP_Registry(
    val kind = "mcp_resource" val changed = "MCP.resources_changed" type value = resource);

open Registry;

fun read ctxt name = #read (get (Context.Proof ctxt) name) ctxt;

val _ =
  Theory.setup
    (Attrib.setup \<^binding>\<open>mcp_resources\<close> add_del_attribute
      "activation of MCP resources in the context");

val _ =
  Outer_Syntax.command \<^command_keyword>\<open>print_mcp_resources\<close>
    "print MCP resources available in the context (\"!\" includes inactive)"
    (Parse.opt_bang >> (fn verbose =>
      Toplevel.keep (fn st =>
        print verbose (Toplevel.context_of st) (fn (_, resource) =>
          [Pretty.str (": " ^ #description resource)]))));

end;
\<close>

section \<open>Output capture\<close>

text \<open>The routing substrate (moved here from MCP_Repl.thy, which now
builds on this structure): a table from Future group id to output
buffer, plus lazily installed \<^verbatim>\<open>Private_Output\<close> wrappers that append to
the buffer of the calling task's group and FALL THROUGH to the original
functions when no buffer is registered (transparent when idle — pinned
by ml-unit tests in MCP-HOL-Tests). \<open>captured\<close> is the synchronous shape
tool execution needs: run a function in a fresh group with a registered
buffer and return its result together with everything it printed.\<close>

ML \<open>
signature MCP_OUTPUT =
sig
  val install_wrappers: unit -> unit
  val register: Future.group -> (unit -> string)
  val captured: (unit -> 'a) -> 'a Exn.result * string
  val reset: unit -> unit
end;

structure MCP_Output: MCP_OUTPUT =
struct

(* routing table: Future group id -> output buffer *)

val buffers: (int * string list Synchronized.var) list Synchronized.var =
  Synchronized.var "MCP_Output.buffers" [];

(*walk the worker's group ancestry, as ir/ml_repl.ML does*)
fun find_buffer () =
  (case Future.worker_group () of
    NONE => NONE
  | SOME group =>
      let
        val gs = Task_Queue.str_of_groups group;
        fun lookup [] = NONE
          | lookup ((gid, buf) :: rest) =
              if String.isSubstring (string_of_int gid) gs
              then SOME buf else lookup rest;
      in lookup (Synchronized.value buffers) end);


(* lazy Private_Output wrappers *)

val wrapped = Synchronized.var "MCP_Output.wrapped" false;

fun install_wrappers () =
  Synchronized.change wrapped (fn already =>
    if already then true
    else
      let
        fun wrap (r: (string list -> unit) Unsynchronized.ref) =
          let val orig = ! r in
            r := (fn ss =>
              (case find_buffer () of
                SOME buf => Synchronized.change buf (cons (implode ss))
              | NONE => orig ss))
          end;
        fun wrap_err (r: (serial * string list -> unit) Unsynchronized.ref) =
          let val orig = ! r in
            r := (fn (i, ss) =>
              (case find_buffer () of
                SOME buf => Synchronized.change buf (cons (implode ss))
              | NONE => orig (i, ss)))
          end;
      in
        wrap Private_Output.writeln_fn;
        wrap Private_Output.writeln_urgent_fn;
        wrap Private_Output.state_fn;
        wrap Private_Output.information_fn;
        wrap Private_Output.tracing_fn;
        wrap Private_Output.warning_fn;
        wrap Private_Output.legacy_fn;
        wrap_err Private_Output.error_message_fn;
        true
      end);

(*process startup (isabelle_process, build job) re-assigns the
  Private_Output functions, silently discarding wrappers inherited from the
  heap — so a persisted "wrapped = true" would disable routing for good.
  Reset after build-time use; re-installation in a live process is harmless
  because wrappers fall through when no buffer is registered.*)
fun reset () =
  (Synchronized.change wrapped (K false);
   Synchronized.change buffers (K []));

(*register a buffer for a group; the returned function unregisters it and
  yields the captured output*)
fun register group =
  let
    val gid = Task_Queue.group_id group;
    val buffer = Synchronized.var "MCP_Output.buffer" ([]: string list);
    val _ = Synchronized.change buffers (cons (gid, buffer));
  in
    fn () =>
      (Synchronized.change buffers (filter_out (fn (g, _) => g = gid));
       cat_lines (rev (Synchronized.value buffer)))
  end;

(*synchronous capture: fork f into a fresh registered group and join.
  Interrupts are NOT swallowed here — callers inspect the Exn.result.*)
fun captured f =
  let
    val _ = install_wrappers ();
    val group = Future.new_group NONE;
    val finish = register group;
    val res =
      Future.join_result
        ((singleton o Future.forks)
          {name = "MCP_Output.captured", group = SOME group, deps = [],
           pri = 0, interrupts = true} f);
  in (res, finish ()) end;

end;
\<close>

section \<open>Combinators: params, quoting, assembly, execution\<close>

text \<open>The ML layer under the \<^verbatim>\<open>mcp_tool\<close> command (spec phase 3
"combinators"): the command is a thin parser over these, and community
ML tools wrap through them directly. The closed parameter-type universe
is string (single-line, inner-string quoted+escaped), source (verbatim
payload, cartouche-quoted; multiline payloads are framed \<open>\<open>\<newline>...\<close>\<close>
and reported line numbers shift back accordingly), args (verbatim
splice into a command's argument position — the default input of a
bare diag wrap), nat/int/bool (validated literals), term/typ
(elaborated against the run context, cartouche-quoted), fact (resolved
against the run context, substituted bare). Assembled text is parsed
at \<^verbatim>\<open>Position.line 1\<close> — NOT
Position.none, which erases error positions entirely (spike 2,
2026-07-11).\<close>

ML \<open>
signature MCP_COMBINATORS =
sig
  val param_types: string list
  val param: {name: string, typ: string, required: bool, default: string option,
    description: string} -> MCP_Tool.param
  val quote_string: string -> string
  val quote_cartouche: string -> string
  val validate: Proof.context -> MCP_Tool.param list -> (string * string) list ->
    (string * string) list
  val check_format: MCP_Tool.param list -> string -> unit
  val assemble: MCP_Tool.param list -> string -> (string * string) list -> string * int
  val exec_text: theory -> int -> string -> string
  val func: string -> (string -> string) -> MCP_Tool.tool
  val ml_run: string -> MCP_Tool.param list ->
    (Proof.context -> (string * string) list -> string) -> MCP_Tool.tool
  val diag: Proof.context -> string * Position.T ->
    {description: string, params: MCP_Tool.param list, format: string} -> MCP_Tool.tool
  val capture: string -> MCP_Tool.param list ->
    (Proof.context -> (string * string) list -> unit) -> MCP_Tool.tool
  val arg: (string * string) list -> string -> string
  val arg_int: (string * string) list -> string -> int
end;

structure MCP_Combinators: MCP_COMBINATORS =
struct

(* parameters *)

val param_types =
  ["string", "source", "args", "nat", "int", "bool", "term", "typ", "fact"];

fun param (p: MCP_Tool.param) =
  if member (op =) param_types (#typ p) then p
  else
    error ("Unknown MCP tool parameter type " ^ quote (#typ p) ^
      " (expected " ^ commas_quote param_types ^ ")");

(*"args" splices VERBATIM into the command's argument position (a
  command takes a token stream there, not a quoted value) — the default
  input type of a bare diag wrap; balanced-cartouche checked so a value
  cannot break the surrounding framing*)
val input_param: MCP_Tool.param =
  {name = "input", typ = "args", required = true, default = NONE,
   description = "arguments for the wrapped command, in its own syntax"};


(* quoting: values must round-trip as DATA, never parse as isar *)

val quote_string =
  quote o translate_string (fn "\"" => "\\\"" | "\\" => "\\\\" | s => s);

(*a payload may contain cartouches of its own, but only balanced ones —
  a stray close delimiter would escape the quoting context*)
fun balanced_cartouche s =
  let
    fun go depth [] = depth = 0
      | go depth ("\<open>" :: ss) = go (depth + 1) ss
      | go depth ("\<close>" :: ss) = depth > 0 andalso go (depth - 1) ss
      | go depth (_ :: ss) = go depth ss;
  in go 0 (Symbol.explode s) end;

fun quote_cartouche s =
  if balanced_cartouche s then cartouche s
  else error ("Unbalanced cartouche delimiters in value " ^ quote s);

val multiline = exists_string (fn s => s = "\n");

(*multiline payloads start on their own line so error positions inside
  them are snippet-relative modulo a fixed shift of 1 (see exec_text);
  with several multiline payloads in one format the shift is approximate*)
fun quote_framed s =
  if multiline s then ("\<open>" ^ "\n" ^ s ^ "\<close>", 1)
  else (quote_cartouche s, 0);


(* validation: typed errors naming the argument *)

fun invalid (p: MCP_Tool.param) msg =
  error ("Invalid value for argument " ^ quote (#name p) ^
    " (type " ^ #typ p ^ "): " ^ msg);

fun check_value ctxt (p: MCP_Tool.param) v =
  (case #typ p of
    "string" =>
      if multiline v
      then invalid p "newline in a string argument (declare it as type source)"
      else ()
  | "source" =>
      if balanced_cartouche v then ()
      else invalid p "unbalanced cartouche delimiters"
  | "args" =>
      if balanced_cartouche v then ()
      else invalid p "unbalanced cartouche delimiters"
  | "nat" => ignore (\<^try>\<open>Value.parse_nat v catch _ => invalid p v\<close>)
  | "int" => ignore (\<^try>\<open>Value.parse_int v catch _ => invalid p v\<close>)
  | "bool" => ignore (\<^try>\<open>Value.parse_bool v catch _ => invalid p v\<close>)
  | "term" =>
      (if balanced_cartouche v then () else invalid p "unbalanced cartouche delimiters";
       ignore (\<^try>\<open>Syntax.read_term ctxt v catch ERROR msg => invalid p msg\<close>))
  | "typ" =>
      (if balanced_cartouche v then () else invalid p "unbalanced cartouche delimiters";
       ignore (\<^try>\<open>Syntax.read_typ ctxt v catch ERROR msg => invalid p msg\<close>))
  | "fact" =>
      ignore (\<^try>\<open>Proof_Context.get_fact ctxt (Facts.named v)
        catch ERROR msg => invalid p msg\<close>)
  | t => error ("Unknown parameter type " ^ quote t));

(*named args -> validated pairs in declaration order, defaults filled in;
  unknown keys, missing required args and ill-typed values are errors*)
fun validate ctxt params args =
  let
    val _ =
      List.app (fn (k, _) =>
        if exists (fn p => #name p = k) params then ()
        else error ("Unknown argument " ^ quote k ^ " (declared: " ^
          commas_quote (map #name params) ^ ")")) args;
    fun value_of (p: MCP_Tool.param) =
      (case AList.lookup (op =) args (#name p) of
        SOME v => SOME v
      | NONE =>
          (case #default p of
            SOME d => SOME d
          | NONE =>
              if #required p
              then error ("Missing required argument: " ^ #name p)
              else NONE));
  in
    params |> map_filter (fn p =>
      (case value_of p of
        SOME v => (check_value ctxt p v; SOME (#name p, v))
      | NONE => NONE))
  end;


(* format strings: literal text with $name placeholders *)

datatype chunk = Lit of string | Var of string;

fun scan_format fmt =
  let
    fun is_id c = Char.isAlphaNum c orelse c = #"_";
    fun flush [] acc = acc
      | flush lit acc = Lit (String.implode (rev lit)) :: acc;
    fun go acc lit [] = rev (flush lit acc)
      | go acc lit (#"$" :: cs) =
          (case chop_prefix is_id cs of
            ([], _) => go acc (#"$" :: lit) cs
          | (ids, rest) => go (Var (String.implode ids) :: flush lit acc) [] rest)
      | go acc lit (c :: cs) = go acc (c :: lit) cs;
  in go [] [] (String.explode fmt) end;

fun placeholders fmt = map_filter (fn Var n => SOME n | Lit _ => NONE) (scan_format fmt);

fun check_format params fmt =
  let
    val vars = placeholders fmt;
    val _ =
      List.app (fn n =>
        if exists (fn p => #name p = n) params then ()
        else error ("Unknown parameter $" ^ n ^ " in format")) vars;
    val _ =
      List.app (fn p =>
        if #required p andalso is_none (#default p) andalso
          not (member (op =) vars (#name p))
        then error ("Required parameter " ^ quote (#name p) ^ " unused in format")
        else ()) params;
  in () end;

(*type-directed quoting; yields the assembled text and the line shift of
  multiline payloads (0 or 1)*)
fun assemble params fmt args =
  let
    fun quoted name =
      let
        val p =
          (case find_first (fn p => #name p = name) params of
            SOME p => p
          | NONE => error ("Unknown parameter $" ^ name ^ " in format"));
      in
        (case AList.lookup (op =) args name of
          NONE =>
            (*validate already ran: a defaulted param is filled in by the
              time assemble sees it, so an absent value here can only be
              an (optional) param the caller left out. Substitute the
              empty segment DIRECTLY, bypassing type-directed quoting --
              routing "" through quote_string would splice the two
              characters "" (a quoted empty string), not an empty
              segment.*)
            if #required p then error ("Missing argument " ^ quote name) else ("", 0)
        | SOME v =>
            (case #typ p of
              "string" => (quote_string v, 0)
            | "source" => quote_framed v
            | "term" => quote_framed v
            | "typ" => quote_framed v
            | "args" => (v, 0)  (*verbatim splice: the command's own syntax*)
            | "fact" => (v, 0)
            | _ => (v, 0)))  (*nat/int/bool: validated literals*)
      end;
    val (pieces, shift) =
      fold_map
        (fn Lit s => (fn shift => (s, shift))
          | Var n => (fn shift =>
              let val (s, k) = quoted n in (s, Int.max (shift, k)) end))
        (scan_format fmt) 0;
  in (implode pieces, shift) end;


(* execution: parse at line 1, run against a state of the given theory,
   capture output; error positions inside multiline payloads shift back *)

fun shift_line_numbers 0 msg = msg
  | shift_line_numbers shift msg =
      let
        fun digits n (c :: cs) =
              if Char.isDigit c then digits (10 * n + (Char.ord c - 48)) cs
              else (n, c :: cs)
          | digits n [] = (n, []);
        val prefix = String.explode "line ";
        fun go [] = []
          | go (input as c :: cs) =
              if is_prefix (op =) prefix input then
                (case List.drop (input, 5) of
                  d :: _ =>
                    if Char.isDigit d then
                      let val (n, rest) = digits 0 (List.drop (input, 5)) in
                        String.explode
                          (if n > shift
                           then "line " ^ string_of_int (n - shift) ^ " of your input"
                           else "line " ^ string_of_int n) @ go rest
                      end
                    else c :: go cs
                | [] => c :: go cs)
              else c :: go cs;
      in String.implode (go (String.explode msg)) end;

fun exec_text thy shift text =
  let
    val (res, output) =
      MCP_Output.captured (fn () =>
        Print_Mode.with_modes [] (fn () =>
          ignore (fold (Toplevel.command_exception false)
            (Outer_Syntax.parse_text thy (K thy) (Position.line 1) text)
            (Toplevel.make_state (SOME thy)))) ());
  in
    (case res of
      Exn.Res () => output
    | Exn.Exn exn =>
        if Exn.is_interrupt exn then Exn.reraise exn
        else
          let val msg = shift_line_numbers shift (Runtime.exn_message exn)
          in error (if output = "" then msg else output ^ "\n" ^ msg) end)
  end;


(* tool builders: what the mcp_tool command's forms compile onto *)

(*string_fun: the mvp shape, schema {input :: string}; the value is
  handed to f as-is (no validation — a plain string function)*)
fun func description f : MCP_Tool.tool =
  {description = description,
   params =
    [{name = "input", typ = "string", required = true, default = NONE,
      description = "tool input"}],
   form = MCP_Tool.String_Fun,
   run = fn _ => fn args =>
    (case AList.lookup (op =) args "input" of
      SOME input => f input
    | NONE => error "Missing required argument: input")};

(*full-power hatch: declared params, validated before f sees them*)
fun ml_run description params f : MCP_Tool.tool =
  let val params = map param params in
    {description = description, params = params, form = MCP_Tool.String_Fun,
     run = fn ctxt => fn args => f ctxt (validate ctxt params args)}
  end;

(*wrap a diagnostic command: registration-time checks here (position
  report -> ctrl+click on the command; keyword-class restriction), the
  run function validates + assembles + executes against the RUN
  context's theory (the designation decides, not the registration site)*)
fun diag ctxt (cmd, pos) {description, params, format = fmt} : MCP_Tool.tool =
  let
    val _ = Outer_Syntax.check_command ctxt (cmd, pos);
    val keywords = Thy_Header.get_keywords (Proof_Context.theory_of ctxt);
    val _ =
      Keyword.is_diag keywords cmd orelse
        error ("Not a diagnostic command: " ^ quote cmd ^
          (case Keyword.command_kind keywords cmd of
            SOME kind => " (keyword class " ^ quote kind ^ ")"
          | NONE => ""));
    val params = if null params then [input_param] else map param params;
    val fmt = if fmt = "" then cmd ^ " $input" else fmt;
    val _ = check_format params fmt;
  in
    {description = description, params = params, form = MCP_Tool.Diag_Wrap,
     run = fn run_ctxt => fn args =>
      let
        val (text, shift) = assemble params fmt (validate run_ctxt params args);
      in exec_text (Proof_Context.theory_of run_ctxt) shift text end}
  end;

(*capture form (plans/ml_builtin_migration): a run slot for writeln-style
  ML functions (most naturally Ir-shaped: report via writeln/error,
  return unit) that would otherwise have to hand-roll exec_text's
  capture-and-join-errors block themselves. PLAIN print mode, not PIDE
  -- run_tool_result (mcp_session.scala) does not strip yxml markup the
  way the repl bridge's ir_result does, so captured text must already
  be plain, the same reason exec_text uses Print_Mode.with_modes [].
  NOTE: the mcp_tool command's capture form does not yet require an
  (annotations ...) clause -- that mechanism belongs to
  plans/param_schema_v2 (D2), not yet landed; see plans/ml_builtin_migration
  step 3 and its recorded deviation.*)
fun capture description params f : MCP_Tool.tool =
  let val params = map param params in
    {description = description, params = params, form = MCP_Tool.Capture,
     run = fn ctxt => fn args =>
      (case MCP_Output.captured (fn () =>
          Print_Mode.with_modes [] (fn () => f ctxt (validate ctxt params args)) ()) of
        (Exn.Res (), output) => output
      | (Exn.Exn exn, output) =>
          if Exn.is_interrupt exn then Exn.reraise exn
          else error (if output = "" then Runtime.exn_message exn
                      else output ^ "\n" ^ Runtime.exn_message exn))}
  end;

(*total accessors for a capture tool's own function: validate already
  guarantees every DECLARED parameter is present (required args checked,
  defaults filled in), so a lookup of a declared name never fails here.
  An absent key means the tool asked for a name it never put in its own
  params clause -- a tool bug, not a client error, so it reads as one.*)
fun arg args name =
  (case AList.lookup (op =) args name of
    SOME v => v
  | NONE =>
      error ("mcp_tool capture: undeclared parameter " ^ quote name ^
        " (not in this tool's own params clause)"));

fun arg_int args name = Value.parse_int (arg args name);

end;
\<close>

section \<open>The mcp_tool / mcp_resource commands\<close>

text \<open>The no-ML user wiring (spec phase 2 "isar commands", phase 3
"parameter spec language"): thin parsers over \<^verbatim>\<open>MCP_Combinators\<close>,
registering through \<^verbatim>\<open>MCP_Tool.declare\<close> / \<^verbatim>\<open>MCP_Resource.declare\<close> like
every other path. Forms:

\<^verbatim>\<open>mcp_tool "find_consts" (description \<open>...\<close>)\<close> — wrap a diagnostic
command; with \<^verbatim>\<open>(params ...)\<close> and \<^verbatim>\<open>(format \<open>...\<close>)\<close> for declared
parameters. The command name must be QUOTED: command keywords delimit
spans, so a bare command name would cut the mcp_tool span short (the
same lexer rule that renamed the attribute, see above). The description
clause is MANDATORY for tools: the outer-syntax comment of a command is
not accessible from keywords (checked 2026-07-11 — Outer_Syntax stores
but does not export it).

\<^verbatim>\<open>mcp_tool shout = \<open>String.map Char.toUpper\<close>\<close> — string_fun hatch.

\<^verbatim>\<open>mcp_tool probe = run \<open>fn ctxt => fn args => ...\<close> (params ...)\<close> —
full-power hatch with declared parameters.

\<^verbatim>\<open>mcp_tool probe = capture \<open>fn ctxt => fn args => ...\<close> (params ...)\<close> —
for a writeln-style function (returns unit, reports via writeln/error);
MCP_Combinators.capture runs it under MCP_Output.captured and returns
what it printed (plans/ml_builtin_migration). Unlike the other forms,
this one carries no default annotations bucket -- pending
plans/param_schema_v2's D2, an (annotations ...) clause is not yet
required or even accepted here.

\<^verbatim>\<open>mcp_resource simps\<close> — a named/dynamic fact, pretty-printed at READ
time (dynamic collections stay current); \<^verbatim>\<open>(isar \<open>print_simpset\<close>)\<close> —
captured diagnostic output; \<^verbatim>\<open>= \<open>fn ctxt => ...\<close>\<close> — ML read function.\<close>

ML \<open>
local

(* clauses: parenthesized keyword blocks, any order, at most once *)

datatype clause =
  Descr of string | Params of MCP_Tool.param list | Format of string | Isar of string;

(*(optional) sits after the type, matched with Args.$$$ (an ident/keyword
  token by CONTENT, Pure/Isar/args.ML:81) rather than Parse.$$$, which
  would need "optional" declared in the theory header -- a minor keyword
  lexes as one in every IMPORTING theory too, breaking any unrelated use
  of the word. No declaration needed and none wanted (plans/
  param_schema_v2, "VERIFIED PARSER FACT").*)
val optional_flag =
  Scan.optional (Parse.$$$ "(" |-- Args.$$$ "optional" --| Parse.$$$ ")" >> K true) false;

val param_entry =
  Parse.name -- (Parse.$$$ "::" |-- Parse.name) -- optional_flag --
    Scan.option (Parse.$$$ "=" |-- Parse.embedded) -- Parse.embedded
  >> (fn ((((name, typ), optional), default), description) =>
      let
        val _ =
          if optional andalso is_some default then
            error ("(optional) is mutually exclusive with a default value, for parameter " ^
              quote name)
          else ();
      in
        MCP_Combinators.param
          {name = name, typ = typ, required = not optional andalso is_none default,
           default = default, description = description}
      end);

fun clause_block kw p =
  Parse.$$$ "(" |-- Parse.$$$ kw |-- Parse.!!! (p --| Parse.$$$ ")");

val clause =
  clause_block "description" Parse.embedded >> Descr ||
  clause_block "params" (Scan.repeat1 param_entry) >> Params ||
  clause_block "format" Parse.embedded >> Format ||
  clause_block "isar" Parse.embedded >> Isar;

fun digest what allow_isar clauses =
  let
    fun uniq g name =
      (case map_filter g clauses of
        [] => NONE
      | [x] => SOME x
      | _ => error ("Multiple (" ^ name ^ " ...) clauses for " ^ what));
    val isar = uniq (fn Isar s => SOME s | _ => NONE) "isar";
    val _ =
      if is_some isar andalso not allow_isar
      then error ("(isar ...) clause is not meaningful for " ^ what) else ();
  in
    {descr = uniq (fn Descr s => SOME s | _ => NONE) "description",
     params = uniq (fn Params ps => SOME ps | _ => NONE) "params",
     fmt = uniq (fn Format s => SOME s | _ => NONE) "format",
     isar = isar}
  end;

fun the_descr what pos NONE =
      error ("Missing (description \<open>...\<close>) clause for " ^ what ^ Position.here pos)
  | the_descr _ _ (SOME d) = d;


(* ML forms: compile the user's source in the declaring context
   (method_setup's ML_Context.expression idiom) *)

fun print_param (p: MCP_Tool.param) =
  "{name = " ^ ML_Syntax.print_string (#name p) ^
  ", typ = " ^ ML_Syntax.print_string (#typ p) ^
  ", required = " ^ Bool.toString (#required p) ^
  ", default = " ^ ML_Syntax.print_option ML_Syntax.print_string (#default p) ^
  ", description = " ^ ML_Syntax.print_string (#description p) ^ "}";

(*the method_setup idiom: the generated source is a UNIT expression
  registering through Theory.local_setup — ML_Context.expression
  evaluates for its context effect and discards plain values*)
fun ml_declaration head source tail =
  ML_Context.expression (Input.pos_of source)
    (ML_Lex.read ("Theory.local_setup (fn lthy => #2 (" ^ head ^ " (") @
      ML_Lex.read_source source @
      ML_Lex.read (")" ^ tail ^ " lthy))"))
  |> Context.proof_map;

fun binding_ml (name, pos) = ML_Syntax.atomic (ML_Syntax.make_binding (name, pos));


(* mcp_tool *)

datatype tool_form =
  Tool_Fun of Input.source | Tool_Run of Input.source | Tool_Capture of Input.source;

val tool_form =
  Parse.$$$ "=" |--
    (Parse.$$$ "run" |-- Parse.ML_source >> Tool_Run ||
     Parse.$$$ "capture" |-- Parse.ML_source >> Tool_Capture ||
     Parse.ML_source >> Tool_Fun);

fun tool_cmd ((name, pos), (form, clauses)) lthy =
  let
    val what = "mcp_tool " ^ quote name;
    val {descr, params, fmt, ...} = digest what false clauses;
    val descr' = the_descr what pos descr;
  in
    (case form of
      NONE =>
        let
          val _ =
            (case fmt of
              NONE =>
                if is_some params
                then error ("(params ...) without (format ...) for " ^ what)
                else ()
            | SOME _ => ());
          val tool =
            MCP_Combinators.diag lthy (name, pos)
              {description = descr', params = these params,
               format = the_default "" fmt};
        in #2 (MCP_Tool.declare (Binding.make (name, pos)) tool lthy) end
    | SOME (Tool_Fun source) =>
        let
          val _ =
            if is_some params orelse is_some fmt
            then error ("(params/format ...) clauses are not meaningful for the " ^
              "string form of " ^ what)
            else ();
        in
          ml_declaration
            ("MCP_Tool.declare " ^ binding_ml (name, pos) ^
              " (MCP_Combinators.func " ^ ML_Syntax.print_string descr')
            source ")" lthy
        end
    | SOME (Tool_Run source) =>
        let
          val _ =
            if is_some fmt
            then error ("(format ...) clause is not meaningful for the run form of " ^ what)
            else ();
          val params_ml = ML_Syntax.print_list print_param (these params);
        in
          ml_declaration
            ("MCP_Tool.declare " ^ binding_ml (name, pos) ^
              " (MCP_Combinators.ml_run " ^ ML_Syntax.print_string descr' ^
              " " ^ params_ml)
            source ")" lthy
        end
    | SOME (Tool_Capture source) =>
        let
          val _ =
            if is_some fmt
            then error ("(format ...) clause is not meaningful for the capture form of " ^ what)
            else ();
          (*NOT YET: an (annotations ...) clause requirement here, per
            plans/ml_builtin_migration step 3 -- that mechanism (the
            record, the five named constants, the scala half) is
            plans/param_schema_v2's D2, not landed. a capture tool
            declared today carries no annotations at all (scala falls
            through to `case _ => None`, mcp_server.scala:77) until D2
            lands and this clause is added.*)
          val params_ml = ML_Syntax.print_list print_param (these params);
        in
          ml_declaration
            ("MCP_Tool.declare " ^ binding_ml (name, pos) ^
              " (MCP_Combinators.capture " ^ ML_Syntax.print_string descr' ^
              " " ^ params_ml)
            source ")" lthy
        end)
  end;

val _ =
  Outer_Syntax.local_theory \<^command_keyword>\<open>mcp_tool\<close>
    "register an MCP tool (diagnostic-command wrap or ML escape hatch)"
    (Parse.position Parse.name -- (Scan.option tool_form -- Scan.repeat clause)
      >> tool_cmd);


(* mcp_resource *)

fun fact_resource name descr : MCP_Resource.resource =
  {description = descr,
   read = fn ctxt =>
    Print_Mode.with_modes [] (fn () =>
      Proof_Context.get_fact ctxt (Facts.named name)
      |> map (Thm.pretty_thm ctxt)
      |> Pretty.chunks |> Pretty.string_of) ()};

fun isar_resource text descr : MCP_Resource.resource =
  {description = descr,
   read = fn ctxt =>
    MCP_Combinators.exec_text (Proof_Context.theory_of ctxt) 0 text};

fun resource_cmd ((name, pos), (form, clauses)) lthy =
  let
    val what = "mcp_resource " ^ quote name;
    val {descr, params, fmt, isar} = digest what true clauses;
    val _ =
      if is_some params orelse is_some fmt
      then error ("(params/format ...) clauses are not meaningful for " ^ what)
      else ();
    val binding = Binding.make (name, pos);
  in
    (case form of
      NONE =>
        (case isar of
          SOME text =>
            #2 (MCP_Resource.declare binding
              (isar_resource text (the_descr what pos descr)) lthy)
        | NONE =>
            let
              (*bare-name form: validate the fact reference NOW, read later*)
              val _ = Proof_Context.get_fact lthy (Facts.Named ((name, pos), NONE));
              val descr' =
                the_default ("named fact " ^ quote name ^ " (read-time)") descr;
            in #2 (MCP_Resource.declare binding (fact_resource name descr') lthy) end)
    | SOME source =>
        let
          val _ =
            if is_some isar
            then error ("(isar ...) clause conflicts with the ML form of " ^ what)
            else ();
        in
          ml_declaration
            ("MCP_Resource.declare " ^ binding_ml (name, pos) ^
              " {description = " ^ ML_Syntax.print_string (the_descr what pos descr) ^
              ", read = (")
            source ")}" lthy
        end)
  end;

val _ =
  Outer_Syntax.local_theory \<^command_keyword>\<open>mcp_resource\<close>
    "register an MCP resource (named fact, diagnostic output, or ML read function)"
    (Parse.position Parse.name --
      (Scan.option (Parse.$$$ "=" |-- Parse.ML_source) -- Scan.repeat clause)
      >> resource_cmd);


(* mcp_test: keyword reserved now (spec phase 3), command in its own wave *)

val _ =
  Outer_Syntax.command \<^command_keyword>\<open>mcp_test\<close>
    "reserved for MCP tool tests (not implemented yet; see spec phase 3)"
    (Scan.succeed (Toplevel.theory (fn _ =>
      error "mcp_test is not implemented yet (reserved keyword; see spec phase 3)")));

in end;
\<close>

section \<open>Protocol payloads\<close>

text \<open>Pure functions, kept apart from the protocol-command wrappers below so
they can be unit-tested (session MCP-Tools-Tests) without a PIDE context.
All context-relative now: the DESIGNATION argument picks the context
whose registered-AND-active entries are served (plans/mcp_tool_registry).
"" = this theory, MCP_Tools, the guaranteed-present default; a bare
canonical theory long name selects that theory (unchanged, shipped
contract — kept bare rather than growing a "theory:" prefix, a
deliberate deviation from the plan's literal wire format so the
already-shipped bridge tests keep working; see plans/tool_scope,
"spec refinement"); "repl:ID" selects a repl's current context
(plans/tool_scope) via a hook the HOL layer installs (this theory has
no notion of repls). A second argument, bundle_names, is folded onto
the resolved context via \<^ML>\<open>Bundle.includes_cmd\<close> (Isar's "context
includes"), left-to-right, first unresolvable name wins.\<close>

ML \<open>
signature MCP_PROTOCOL =
sig
  val set_repl_context_hook: (string -> Proof.context) -> unit
  val set_default_theory: theory -> unit
  val designated_context: string -> string list -> Proof.context
  val designated_context_safe: string -> string list -> Proof.context option
  val decode_args: string -> (string * string) list
  val decode_names: string -> string list
  val tools_body: Proof.context -> XML.body
  val empty_tools_body: XML.body
  val theories_body: unit -> XML.body
  val run_tool: Proof.context -> string -> (string * string) list -> string * string
  val resources_body: Proof.context -> XML.body
  val read_resource: Proof.context -> string -> string * string
end;

structure MCP_Protocol: MCP_PROTOCOL =
struct

(*default theory hook: NONE means fall back to MCP_Tools itself (the
  Pure-based MCP-Tools test image, where nothing sets it). installed by
  MCP_Repl.thy, mirroring repl_context_hook below, so this base layer
  never names the HOL layer's theory -- widens the out-of-the-box
  default strictly, since MCP_Repl imports MCP_Tools.*)
val default_theory_hook : theory option Synchronized.var =
  Synchronized.var "MCP_Protocol.default_theory_hook" NONE;

fun set_default_theory thy = Synchronized.change default_theory_hook (K (SOME thy));

(*"" = the registry's own theory: deterministic, in every server heap,
  and it sees exactly the tools declared below (today's default view)
  when no hook is set. Thy_Info keying mixes qualified and unqualified
  names, so try both.*)
fun default_theory () =
  (case Synchronized.value default_theory_hook of
    SOME thy => thy
  | NONE =>
      (case try Thy_Info.get_theory "MCP-Tools.MCP_Tools" of
        SOME thy => thy
      | NONE => Thy_Info.get_theory "MCP_Tools"));

(*installed by MCP_Repl.thy (the HOL layer, which owns the repl
  registry); NONE = no repl support in this session (e.g. the plain
  MCP-Tools test image) -- repl designations then fail with a message
  saying so, rather than a missing-hook internal error.*)
val repl_context_hook : (string -> Proof.context) option Synchronized.var =
  Synchronized.var "MCP_Protocol.repl_context_hook" NONE;

fun set_repl_context_hook f = Synchronized.change repl_context_hook (K (SOME f));

fun repl_context id =
  (case Synchronized.value repl_context_hook of
    SOME f => f id
  | NONE =>
      error ("Unknown repl " ^ quote id ^
        " in MCP designation (no repl support in this session)"));

fun resolve_designation "" = Proof_Context.init_global (default_theory ())
  | resolve_designation designation =
      (case try (unprefix "repl:") designation of
        SOME id => repl_context id
      | NONE =>
          (case try Thy_Info.get_theory designation of
            SOME thy => Proof_Context.init_global thy
          | NONE => error ("Unknown theory " ^ quote designation ^ " in MCP designation")));

fun designated_context designation bundle_names =
  fold (fn name => fn ctxt =>
      Bundle.includes_cmd [((true, Position.none), (name, Position.none))] ctxt
        handle ERROR msg => error (msg ^ " (bundle " ^ quote name ^ " in MCP designation)"))
    bundle_names (resolve_designation designation);

(*crash-safe variant for wire commands with no (status, output) shape of
  their own (tools_body/resources_body, unlike run_tool/read_resource):
  a stale or bad designation (e.g. a repl removed after tool_scope_set)
  must not leave the client's tools/list request unanswered -- degrade
  to NONE (the caller serves the empty-payload floor) rather than
  letting the exception escape the protocol command uncaught, which
  would leave the promise on the Scala side unfulfilled forever.*)
fun designated_context_safe designation bundle_names =
  (case Exn.capture_body (fn () => designated_context designation bundle_names) of
    Exn.Res ctxt => SOME ctxt
  | Exn.Exn exn => if Exn.is_interrupt exn then Exn.reraise exn else NONE);

(*named args cross as one yxml chunk holding an association list — the
  same encoding as MCP.ir's arguments (MCP_Session.encode_args)*)
fun decode_args yxml =
  let open XML.Decode in list (pair string string) (YXML.parse_body yxml) end;

(*bundle names cross the same way, as a flat yxml list of strings*)
fun decode_names yxml =
  let open XML.Decode in list string (YXML.parse_body yxml) end;

(*rows are (full internal name, description, form tag, params);
  Isabelle/Scala computes the exposed (shortened, sanitized) names —
  see MCP_Server.exposure — and expands params into a JSON schema.
  Builtin-form rows (the scala builtin table's ML mirrors below, spec
  phase 3 "builtin tools in the activation layer") carry no real
  params and are EXCLUDED here -- they enter the builtins section
  instead, so they never reach exposure-name computation (guardrail
  A3, plans/builtin_activation).*)
val encode_param =
  let open XML.Encode in pair string (pair string (pair bool (pair (option string) string))) end;

fun encode_row (name, tool: MCP_Tool.tool) =
  let open XML.Encode in
    pair string (pair string (pair string (list encode_param)))
      (name, (#description tool, (MCP_Tool.form_tag (#form tool),
        map (fn p => (#name p, (#typ p, (#required p, (#default p, #description p)))))
          (#params tool))))
  end;

(*(base name, active): the BASE name, not the theory-qualified full
  name, so it matches the scala builtin table's bare names directly
  (the drift gate, plans/builtin_activation). ALL registered mirrors,
  inactive included, so scala can tell "hidden" (registered, del'd)
  from "absent" (no mirror at all) -- the AVAILABILITY FLOOR
  guardrail: an empty section means scala serves its full table.*)
fun encode_builtin (name, active) =
  let open XML.Encode in pair string bool (Long_Name.base_name name, active) end;

fun tools_body ctxt =
  let
    val context = Context.Proof ctxt;
    val ml_rows =
      MCP_Tool.active context
      |> filter (fn (_, tool) => #form tool <> MCP_Tool.Builtin);
    val builtin_rows =
      MCP_Tool.list context
      |> filter (fn (_, tool) => #form tool = MCP_Tool.Builtin)
      |> map (fn (name, _) => (name, MCP_Tool.is_active context name));
  in
    let open XML.Encode in pair (list encode_row) (list encode_builtin) (ml_rows, builtin_rows) end
  end;

(*the availability floor's empty shape (MCP.tools, designation
  resolution failure, MCP_Protocol.designated_context_safe = NONE):
  still a PAIR -- the wire shape scala always expects -- with both
  sections empty, so scala's decoder degrades to the full builtin
  table (and zero ML tools) without a special-cased wire shape.*)
val empty_tools_body : XML.body =
  let open XML.Encode in pair (list encode_row) (list encode_builtin) ([], []) end;

(*theories loaded in this session -- heap image theories plus anything
  loaded on top of it; backs the isabelle://session resource*)
fun theories_body () =
  let open XML.Encode in list string (Thy_Info.get_names ()) end;

(*(status, output) with status = "ok" | "error"; interrupts are reraised.
  Serving follows listing: a registered-but-inactive tool is not
  callable, matching its absence from tools_body.*)
fun run_tool ctxt name args =
  (case Exn.capture_body (fn () =>
      let
        val context = Context.Proof ctxt;
        val _ = MCP_Tool.get context name;
        val _ =
          MCP_Tool.is_active context name orelse
            error ("Inactive MCP tool " ^ quote name);
      in MCP_Tool.run ctxt name args end) of
    Exn.Res res => ("ok", res)
  | Exn.Exn exn =>
      if Exn.is_interrupt exn then Exn.reraise exn
      else ("error", Runtime.exn_message exn));

fun resources_body ctxt =
  let open XML.Encode in
    list (pair string string)
      (map (fn (name, resource) => (name, #description resource))
        (MCP_Resource.active (Context.Proof ctxt)))
  end;

(*mirrors run_tool's (status, output) shape and inactive policy*)
fun read_resource ctxt name =
  (case Exn.capture_body (fn () =>
      let
        val context = Context.Proof ctxt;
        val _ = MCP_Resource.get context name;
        val _ =
          MCP_Resource.is_active context name orelse
            error ("Inactive MCP resource " ^ quote name);
      in MCP_Resource.read ctxt name end) of
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
    (fn [designation, bundles_yxml] =>
      Output.protocol_message [Markup.function "MCP.tools_result"]
        [(case MCP_Protocol.designated_context_safe designation
            (MCP_Protocol.decode_names bundles_yxml) of
           SOME ctxt => MCP_Protocol.tools_body ctxt
         | NONE => MCP_Protocol.empty_tools_body)]);

val _ =
  Protocol_Command.define "MCP.theories"
    (fn [] =>
      Output.protocol_message [Markup.function "MCP.theories_result"]
        [MCP_Protocol.theories_body ()]);
\<close>

text \<open>ASYNC (plans/ml_builtin_migration step 5): the same two-future shape
as \<open>MCP.ir\<close> above (MCP_Repl.thy) -- fork the work, then fork a SECOND,
non-interruptible future depending on it that posts \<open>(status, output)\<close> by
id. Posting must happen from that dependent future, never from inside the
worker: if the worker were interrupted, posting from within it would never
run and the Scala promise would hang forever (the same failure mode
\<open>designated_context_safe\<close>'s comment records). Unlike \<open>MCP.ir\<close>'s
\<open>fork_run\<close>, this registers NO output buffer -- \<^verbatim>\<open>run_tool\<close> below returns
its result as a plain string, and any capture-form tool it reaches captures
its own output via \<^verbatim>\<open>MCP_Output.captured\<close>; registering a second buffer
here would make that capture ambiguous (\<open>find_buffer\<close>'s group-ancestry walk
takes the first match found, MCP_Tools.thy above). Plain print mode
throughout, unlike \<open>MCP.ir\<close>'s PIDE mode -- \<open>run_tool_result\<close> on the Scala
side does not strip yxml markup.\<close>

ML \<open>
val _ =
  Protocol_Command.define "MCP.run_tool"
    (fn [id, designation, bundles_yxml, name, args_yxml] =>
      let
        val result =
          (singleton o Future.forks)
            {name = "MCP.run_tool." ^ name, group = NONE, deps = [], pri = ~1, interrupts = true}
            (fn () =>
              case Exn.capture_body (fn () =>
                  MCP_Protocol.designated_context designation
                    (MCP_Protocol.decode_names bundles_yxml)) of
                Exn.Res ctxt =>
                  MCP_Protocol.run_tool ctxt name (MCP_Protocol.decode_args args_yxml)
              | Exn.Exn exn =>
                  if Exn.is_interrupt exn then Exn.reraise exn
                  else ("error", Runtime.exn_message exn));
        val _ =
          (singleton o Future.forks)
            {name = "MCP.run_tool_result", group = NONE,
             deps = [Future.task_of result], pri = ~1, interrupts = false}
            (fn () =>
              let
                val (status, output) =
                  (case Future.join_result result of
                    Exn.Res res => res
                  | Exn.Exn exn => ("error", Runtime.exn_message exn));
              in
                Output.protocol_message
                  [Markup.function "MCP.run_tool_result", ("id", id), ("status", status)]
                  [[XML.Text output]]
              end);
      in () end);
\<close>

text \<open>tool_scope_set/tool_scope_include (plans/tool_scope) validate a
candidate designation BEFORE committing it as connection state ("unknown
theory/repl/bundle in scope calls -> isError, connection state
unchanged"). The theory case validates scala-side for free (via
resolve_context_theory, the same normalization that must run before
storing anyway); the repl and bundle cases need the prover, hence this
command -- it mirrors run_tool's own resolution phase, discarding the
context (only success/failure and the message matter here).\<close>

ML \<open>
val _ =
  Protocol_Command.define "MCP.check_designation"
    (fn [id, designation, bundles_yxml] =>
      let
        val (status, output) =
          (case Exn.capture_body (fn () =>
              MCP_Protocol.designated_context designation (MCP_Protocol.decode_names bundles_yxml)) of
            Exn.Res _ => ("ok", "")
          | Exn.Exn exn =>
              if Exn.is_interrupt exn then Exn.reraise exn
              else ("error", Runtime.exn_message exn));
      in
        Output.protocol_message
          [Markup.function "MCP.check_designation_result", ("id", id), ("status", status)]
          [[XML.Text output]]
      end);
\<close>

ML \<open>
val _ =
  Protocol_Command.define "MCP.resources"
    (fn [designation] =>
      Output.protocol_message [Markup.function "MCP.resources_result"]
        [(case MCP_Protocol.designated_context_safe designation [] of
           SOME ctxt => MCP_Protocol.resources_body ctxt
         | NONE => [])]);

val _ =
  Protocol_Command.define "MCP.read_resource"
    (fn [id, designation, name] =>
      let
        val (status, output) =
          (case Exn.capture_body (fn () => MCP_Protocol.designated_context designation []) of
            Exn.Res ctxt => MCP_Protocol.read_resource ctxt name
          | Exn.Exn exn =>
              if Exn.is_interrupt exn then Exn.reraise exn
              else ("error", Runtime.exn_message exn));
      in
        Output.protocol_message
          [Markup.function "MCP.read_resource_result", ("id", id), ("status", status)]
          [[XML.Text output]]
      end);
\<close>

section \<open>Demo tool and resource\<close>

text \<open>Registered through the commands above — the production heap
exercises the isar surface itself, not just the ML layer under it.\<close>

mcp_tool shout = \<open>String.map Char.toUpper\<close>
  (description \<open>uppercase the input\<close>)

mcp_resource greeting = \<open>K "hello from MCP_Resource"\<close>
  (description \<open>a static demo resource\<close>)

section \<open>Builtin tool mirrors\<close>

text \<open>The scala builtin table (mcp_server.scala, spec phase 3 "builtin
tools in the activation layer"; plans/builtin_activation) is mirrored
here so activation (\<open>[[mcp_tools add/del: ...]]\<close>, bundles) is uniform
across both implementation substrates -- builtins keep their scala
table (descriptions, json schemas, dispatch); only their NAMES also
live here. A mirror carries no real params or run body: Isabelle/Scala
dispatches builtin calls BEFORE consulting activation (tools/call
precedence, kept deliberately -- ASYMMETRIC CALLABILITY, a del'd
builtin stays callable, only unlisted), so \<^verbatim>\<open>run\<close> here is
unreachable in practice and errors loudly if ever invoked directly.
The DRIFT GATE (plans/builtin_activation, tested over the live bridge)
pins this list against the live scala table -- keep both in sync by
hand when a builtin is added, renamed, or removed.\<close>

ML \<open>
val _ =
  Context.>> (Context.map_theory (Named_Target.theory_map (fn lthy =>
    lthy
    |> fold (fn (name, description) => fn lthy =>
        lthy
        |> MCP_Tool.declare (Binding.name name)
            {description = description, params = [], form = MCP_Tool.Builtin,
             run = fn _ => fn _ => error "builtin tool: dispatched Isabelle/Scala-side"}
        |> #2)
      [("repl_list", "List all open REPL proof sessions."),
       ("repl_init", "Create a new REPL proof session that imports the given theories."),
       ("repl_init_from_source", "Create a new REPL rooted at a specific command inside an existing theory."),
       ("repl_fork", "Fork a sub-REPL from an existing REPL at a given state index."),
       ("repl_remove", "Remove a REPL and all sub-REPLs forked from it."),
       ("repl_step", "Apply one Isar command to a REPL and print the resulting proof state."),
       ("repl_state", "Print the proof/theory state of a REPL at a given index."),
       ("repl_show", "Describe one REPL: origin, timeout, pin status, and its steps."),
       ("repl_text", "Print the concatenated Isar text of all steps in a REPL."),
       ("repl_edit", "Replace a REPL step with new Isar text and re-execute from there."),
       ("repl_replay", "Re-execute all stale steps in a REPL, in order."),
       ("repl_truncate", "Discard all REPL steps after a given index."),
       ("repl_back", "Revert the last successful REPL step."),
       ("repl_merge", "Merge a sub-REPL back into its parent."),
       ("repl_timeout", "Set the per-step timeout in seconds for one REPL."),
       ("repl_pin", "Pin (snapshot) a REPL's current theory state."),
       ("repl_unpin", "Remove a REPL's pin."),
       ("repl_rebase", "Re-resolve a REPL's init specs against current pin versions."),
       ("sledgehammer", "Run Sledgehammer on the REPL's current proof state."),
       ("find_theorems", "Search for theorems by name, goal-relevance, or term pattern."),
       ("find_definition", "Find where a name is defined across the prover's name spaces."),
       ("load_theory", "Load and check a theory from disk into the running session."),
       ("unload_theory", "Unload a theory that was loaded with load_theory."),
       ("check_theory", "Re-read a theory file from disk and check it."),
       ("list_sessions", "List all Isabelle sessions known to the server."),
       ("list_theories", "List all theories in a given Isabelle session."),
       ("search_sources", "Search for theories by substring match on their long name."),
       ("scope_add", "Add theory-name patterns to the resource scope."),
       ("scope_remove", "Remove theory-name patterns from the resource scope."),
       ("scope_show", "Show the current resource scope, explicit and implicit."),
       ("doc_list", "List the Isabelle documentation catalog."),
       ("doc_read", "Read Isabelle documentation from its plain-text sources."),
       ("tool_scope_show", "Show the current tool scope (agent context)."),
       ("tool_scope_set", "Set the tool scope to a theory or a repl."),
       ("tool_scope_include", "Open bundles in the current tool scope.")])));
\<close>

end
