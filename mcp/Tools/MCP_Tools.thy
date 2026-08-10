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
  datatype ptyp =
    String | Source | Args | Nat | Int | Bool | Term | Typ | Fact
  | Enum of string list
  | List_Of of ptyp
  val string_of_ptyp: ptyp -> string
  val print_ptyp: ptyp -> string
  type param =
    {name: string, typ: ptyp, required: bool, default: string option, description: string}
  type annotations =
    {read_only: bool option, idempotent: bool option, destructive: bool option,
     open_world: bool option}
  val default_annotations: annotations
  val read_only: annotations
  val read_only_non_idempotent: annotations
  val mutating: annotations
  val idempotent_mutating: annotations
  val destructive: annotations
  val diag_annotations: annotations
  datatype constraint = Exactly_One of string list
  type tool =
    {description: string, params: param list, constraints: constraint list,
     form: form, annotations: annotations,
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

(*constructor names sit in the VALUE namespace, distinct from the
  STRUCTURE namespace -- String/Int/Bool do not shadow the Poly/ML
  structures of those names, so e.g. String.isSubstring keeps working
  everywhere in this theory (plans/param_schema_v2). Only the two
  compound constructors are spec-pinned (Enum, List_Of); the nine
  scalar names are this plan's choice.*)
datatype ptyp =
  String | Source | Args | Nat | Int | Bool | Term | Typ | Fact
| Enum of string list
| List_Of of ptyp;

(*for error messages (Invalid value for argument ... (type ...)) --
  distinct from print_ptyp below, which emits ML SOURCE for the run
  form's declaration round-trip, not prose.*)
fun string_of_ptyp String = "string"
  | string_of_ptyp Source = "source"
  | string_of_ptyp Args = "args"
  | string_of_ptyp Nat = "nat"
  | string_of_ptyp Int = "int"
  | string_of_ptyp Bool = "bool"
  | string_of_ptyp Term = "term"
  | string_of_ptyp Typ = "typ"
  | string_of_ptyp Fact = "fact"
  | string_of_ptyp (Enum items) = "enum (" ^ space_implode " | " items ^ ")"
  | string_of_ptyp (List_Of t) = "list of " ^ string_of_ptyp t;

(*ML source for a ptyp value, so a "= run <...> (params ...)" declaration
  can round-trip through ML_Context.expression (print_param below).*)
fun print_ptyp String = "MCP_Tool.String"
  | print_ptyp Source = "MCP_Tool.Source"
  | print_ptyp Args = "MCP_Tool.Args"
  | print_ptyp Nat = "MCP_Tool.Nat"
  | print_ptyp Int = "MCP_Tool.Int"
  | print_ptyp Bool = "MCP_Tool.Bool"
  | print_ptyp Term = "MCP_Tool.Term"
  | print_ptyp Typ = "MCP_Tool.Typ"
  | print_ptyp Fact = "MCP_Tool.Fact"
  | print_ptyp (Enum items) =
      "(MCP_Tool.Enum " ^ ML_Syntax.print_list ML_Syntax.print_string items ^ ")"
  | print_ptyp (List_Of t) = "(MCP_Tool.List_Of " ^ print_ptyp t ^ ")";

type param =
  {name: string, typ: ptyp, required: bool, default: string option, description: string};

(*the four MCP hint flags (spec "tool annotations"): each independently
  absent (NONE, no premise proven) or set, deliberately NOT a closed
  enum -- a variant would buy curation over these four booleans, not
  type safety. The five buckets below reproduce the scala builtin
  table's five annotation groups EXACTLY (mcp_server.scala's
  read_only_annotations/mutating_annotations/destructive_annotations/
  idempotent_mutating_annotations/read_only_non_idempotent_annotations)
  -- destructiveHint is the one flag any bucket ever OMITS (NONE)
  rather than sets false; openWorldHint is SOME false in every one of
  them, including default_annotations, so it is the one hint every
  tool -- ML or scala -- ends up advertising.*)
type annotations =
  {read_only: bool option, idempotent: bool option, destructive: bool option,
   open_world: bool option};

(*for a declaration whose form tag proves nothing about the tool's
  behavior (string_fun, the run form when no (annotations ...) clause
  is given, a Builtin mirror -- whose annotations are inert, see
  tools_body/encode_row below).*)
val default_annotations : annotations =
  {read_only = NONE, idempotent = NONE, destructive = NONE, open_world = SOME false};

val read_only : annotations =
  {read_only = SOME true, idempotent = SOME true, destructive = NONE, open_world = SOME false};

val read_only_non_idempotent : annotations =
  {read_only = SOME true, idempotent = SOME false, destructive = NONE, open_world = SOME false};

val mutating : annotations =
  {read_only = SOME false, idempotent = SOME false, destructive = NONE, open_world = SOME false};

val idempotent_mutating : annotations =
  {read_only = SOME false, idempotent = SOME true, destructive = NONE, open_world = SOME false};

val destructive : annotations =
  {read_only = SOME false, idempotent = SOME false, destructive = SOME true,
   open_world = SOME false};

(*a diag wrap's read_only/idempotent are PROVEN at registration
  (Keyword.is_diag), not merely hinted -- diag always uses this,
  never an explicit (annotations ...) clause (see tool_cmd below).*)
val diag_annotations = read_only;

(*a cross-param constraint: Exactly_One names DECLARED, non-required,
  non-defaulted params (registration-time gate, MCP_Combinators.
  check_constraint) of which exactly one must be present in a call's
  RAW arguments (runtime gate, MCP_Combinators.validate). Constraints
  do NOT cross the wire -- they drive a runtime check and a generated
  sentence appended to the tool's description at construction time, so
  the description that DOES cross the wire already carries the rule.*)
datatype constraint = Exactly_One of string list;

type tool =
  {description: string, params: param list, constraints: constraint list,
   form: form, annotations: annotations,
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
  val param: {name: string, typ: MCP_Tool.ptyp, required: bool, default: string option,
    description: string} -> MCP_Tool.param
  val quote_string: string -> string
  val quote_cartouche: string -> string
  val validate: Proof.context -> MCP_Tool.param list -> MCP_Tool.constraint list ->
    (string * string) list -> (string * string) list
  val check_format: MCP_Tool.param list -> string -> unit
  val assemble: MCP_Tool.param list -> string -> (string * string) list -> string * int
  val exec_text: theory -> int -> string -> string
  val check_constraint: MCP_Tool.param list -> MCP_Tool.constraint -> unit
  val func: string -> (string -> string) -> MCP_Tool.tool
  val ml_run: string -> MCP_Tool.param list -> MCP_Tool.annotations -> MCP_Tool.constraint list ->
    (Proof.context -> (string * string) list -> string) -> MCP_Tool.tool
  val diag: Proof.context -> string * Position.T ->
    {description: string, params: MCP_Tool.param list, format: string,
     constraints: MCP_Tool.constraint list} -> MCP_Tool.tool
  val capture: string -> MCP_Tool.param list -> MCP_Tool.annotations -> MCP_Tool.constraint list ->
    (Proof.context -> (string * string) list -> unit) -> MCP_Tool.tool
  val arg: (string * string) list -> string -> string
  val arg_int: (string * string) list -> string -> int
end;

structure MCP_Combinators: MCP_COMBINATORS =
struct

(* parameters *)

(*a value that will be spliced VERBATIM (assemble's Enum branch, and
  args/fact) must not be able to break out of its surrounding format
  text or a JSON string -- no blank symbol (Symbol.is_blank, which
  covers space/tab/newline and Isabelle's own blank symbols, not just
  ASCII whitespace), quote, or cartouche delimiter.*)
fun token_safe s =
  forall (fn sym => not (Symbol.is_blank sym) andalso
      sym <> "\"" andalso sym <> "\<open>" andalso sym <> "\<close>")
    (Symbol.explode s);

(*the closed type universe is now MCP_Tool.ptyp itself -- the compiler
  rejects an unknown type, so there is nothing left for param to check
  for the nine scalars. param survives as the one registration-time gate
  for what the type system cannot express: enum items nonempty, distinct
  and token-safe (plans/param_schema_v2 step 3 -- token-safety is what
  makes assemble's verbatim splice defensible rather than merely
  convenient), and a declared default must be one of the items. Step 4
  adds the list-of-no-default check.*)
fun param (p: MCP_Tool.param) =
  (case #typ p of
    MCP_Tool.Enum items =>
      let
        val _ =
          if null items then error ("Empty enum for parameter " ^ quote (#name p)) else ();
        val _ =
          if has_duplicates (op =) items then
            error ("Duplicate enum items for parameter " ^ quote (#name p) ^ ": " ^
              commas_quote items)
          else ();
        val _ =
          List.app (fn item =>
            if token_safe item then ()
            else
              error ("Enum item " ^ quote item ^ " for parameter " ^ quote (#name p) ^
                " contains whitespace or a quote/cartouche character"))
            items;
        val _ =
          (case #default p of
            NONE => ()
          | SOME d =>
              if member (op =) items d then ()
              else
                error ("Default " ^ quote d ^ " for parameter " ^ quote (#name p) ^
                  " is not one of its enum items: " ^ commas_quote items));
      in p end
  | MCP_Tool.List_Of _ =>
      (case #default p of
        NONE => p
      | SOME _ =>
          error ("A list-of parameter cannot declare a default, for parameter " ^
            quote (#name p) ^
            " (no list-literal default syntax is specced; DEFERRED, plans/param_schema_v2)"))
  | _ => p);

(*registration-time gate for a constraint (param's own job, one level
  up): every named member must be a DECLARED, non-required,
  non-defaulted param -- a required or defaulted member could never
  legitimately vary between 0 and 1 occurrences in a call's raw args, so
  exactly_one over it would be either meaningless or permanently
  satisfied. Called from each of ml_run/diag/capture below (not from
  the parser), so a raw-ML declaration is gated exactly like an isar one
  (plans/param_schema_v2 step 6).*)
fun check_constraint (params: MCP_Tool.param list) (MCP_Tool.Exactly_One members) =
  let
    val _ = if null members then error "Empty exactly_one constraint" else ();
    val _ =
      if has_duplicates (op =) members then
        error ("Duplicate members in exactly_one constraint: " ^ commas_quote members)
      else ();
    val _ =
      List.app (fn m =>
        (case find_first (fn p => #name p = m) params of
          NONE =>
            error ("exactly_one constraint names undeclared parameter " ^ quote m ^
              " (declared: " ^ commas_quote (map #name params) ^ ")")
        | SOME p =>
            if #required p then
              error ("exactly_one member " ^ quote m ^
                " must be declared (optional), not required")
            else if is_some (#default p) then
              error ("exactly_one member " ^ quote m ^ " must not declare a default value")
            else ()))
        members;
  in () end;

(*"args" splices VERBATIM into the command's argument position (a
  command takes a token stream there, not a quoted value) — the default
  input type of a bare diag wrap; balanced-cartouche checked so a value
  cannot break the surrounding framing*)
val input_param: MCP_Tool.param =
  {name = "input", typ = MCP_Tool.Args, required = true, default = NONE,
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

(*a param record with everything but its ptyp carried over -- the
  shape List_Of's element-wise recursion needs at more than one call
  site (check_value, validate); SML has no {p with typ = t} update
  syntax.*)
fun with_typ (p: MCP_Tool.param) t : MCP_Tool.param =
  {name = #name p, typ = t, required = #required p,
   default = #default p, description = #description p};

fun invalid (p: MCP_Tool.param) msg =
  error ("Invalid value for argument " ^ quote (#name p) ^
    " (type " ^ MCP_Tool.string_of_ptyp (#typ p) ^ "): " ^ msg);

(*exhaustive match over MCP_Tool.ptyp -- the two old fallbacks (this
  function's "| t => error (Unknown parameter type ...)" and param's
  membership test) are UNREACHABLE now and deleted; the compiler is the
  new gate (plans/param_schema_v2 A3). Enum/List_Of are not reachable
  from isar until steps 3/4 land their parsers, but the match must
  already cover them -- written with their real semantics now rather
  than a placeholder, so those steps do not have to revisit this
  function. List_Of t recurses treating v as ONE element of type t; the
  repeated-key collection that calls this once per element is validate's
  job (step 4), not check_value's.*)
fun check_value ctxt (p: MCP_Tool.param) v =
  (case #typ p of
    MCP_Tool.String =>
      if multiline v
      then invalid p "newline in a string argument (declare it as type source)"
      else ()
  | MCP_Tool.Source =>
      if balanced_cartouche v then ()
      else invalid p "unbalanced cartouche delimiters"
  | MCP_Tool.Args =>
      if balanced_cartouche v then ()
      else invalid p "unbalanced cartouche delimiters"
  | MCP_Tool.Nat => ignore (\<^try>\<open>Value.parse_nat v catch _ => invalid p v\<close>)
  | MCP_Tool.Int => ignore (\<^try>\<open>Value.parse_int v catch _ => invalid p v\<close>)
  | MCP_Tool.Bool => ignore (\<^try>\<open>Value.parse_bool v catch _ => invalid p v\<close>)
  | MCP_Tool.Term =>
      (if balanced_cartouche v then () else invalid p "unbalanced cartouche delimiters";
       ignore (\<^try>\<open>Syntax.read_term ctxt v catch ERROR msg => invalid p msg\<close>))
  | MCP_Tool.Typ =>
      (if balanced_cartouche v then () else invalid p "unbalanced cartouche delimiters";
       ignore (\<^try>\<open>Syntax.read_typ ctxt v catch ERROR msg => invalid p msg\<close>))
  | MCP_Tool.Fact =>
      ignore (\<^try>\<open>Proof_Context.get_fact ctxt (Facts.named v)
        catch ERROR msg => invalid p msg\<close>)
  | MCP_Tool.Enum items =>
      if member (op =) items v then ()
      else invalid p (quote v ^ " is not one of " ^ commas_quote items)
  | MCP_Tool.List_Of t => check_value ctxt (with_typ p t) v);

(*named args -> validated pairs in declaration order, defaults filled in;
  unknown keys, missing required args and ill-typed values are errors.
  A List_Of param is the one case that can legitimately produce MORE
  THAN ONE output pair per param: list arguments arrive as REPEATED
  KEYS on the wire (json_args: a json array becomes repeated (key,
  element) pairs in array order, mcp_server.scala; documented
  mcp_session.scala), so AList.lookup's first-occurrence semantics
  would silently drop every element but the first -- collect every
  occurrence instead, in array order, and type-check each one against
  the element type. Absent = zero occurrences (List_Of carries no
  default, per param's own registration-time check, so there is no
  fallback value to fill in here).*)
fun validate ctxt params constraints args =
  let
    val _ =
      List.app (fn (k, _) =>
        if exists (fn p => #name p = k) params then ()
        else error ("Unknown argument " ^ quote k ^ " (declared: " ^
          commas_quote (map #name params) ^ ")")) args;
    (*Exactly_One counts DISTINCT member names present as a key in the
      RAW args, BEFORE defaults are filled in (value_of below) -- a
      defaulted member would read as permanently present, which is
      exactly why check_constraint rejects one at registration.*)
    val _ =
      List.app (fn MCP_Tool.Exactly_One members =>
        let val present = filter (fn m => exists (fn (k, _) => k = m) args) members in
          if length present = 1 then ()
          else
            error ("Exactly one of " ^ commas members ^ " is required" ^
              (if null present then "" else " (got: " ^ commas_quote present ^ ")"))
        end)
        constraints;
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
    params |> maps (fn p =>
      (case #typ p of
        MCP_Tool.List_Of t =>
          let val vs = map snd (filter (fn (k, _) => k = #name p) args) in
            if null vs then
              (if #required p then error ("Missing required argument: " ^ #name p) else [])
            else
              (List.app (check_value ctxt (with_typ p t)) vs; map (pair (#name p)) vs)
          end
      | _ =>
          (case value_of p of
            SOME v => (check_value ctxt p v; [(#name p, v)])
          | NONE => [])))
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
        (*exhaustive over MCP_Tool.ptyp; Enum splices VERBATIM like args/
          fact (spec refinement: enum items name command keywords, e.g.
          "find_definition kind: const", which quoting would break --
          membership is already checked by validate, so the splice is
          safe). List_Of t's own branch recurses treating v as ONE
          element of type t -- the repeated-key collection and space-
          join live in quoted below, not here, so this stays a total
          per-VALUE quoter reusable from both places.*)
        fun quote_typ MCP_Tool.String v = (quote_string v, 0)
          | quote_typ MCP_Tool.Source v = quote_framed v
          | quote_typ MCP_Tool.Term v = quote_framed v
          | quote_typ MCP_Tool.Typ v = quote_framed v
          | quote_typ MCP_Tool.Args v = (v, 0)  (*verbatim splice: the command's own syntax*)
          | quote_typ MCP_Tool.Fact v = (v, 0)
          | quote_typ MCP_Tool.Nat v = (v, 0)  (*validated literal*)
          | quote_typ MCP_Tool.Int v = (v, 0)
          | quote_typ MCP_Tool.Bool v = (v, 0)
          | quote_typ (MCP_Tool.Enum _) v = (v, 0)
          | quote_typ (MCP_Tool.List_Of t) v = quote_typ t v;
      in
        (case #typ p of
          MCP_Tool.List_Of t =>
            (*same repeated-key collection as validate (MCP_Tools.thy,
              "the SAME AList.lookup first-occurrence bug", plans/
              param_schema_v2 step 4) -- AList.lookup would see only the
              first element. Each element is quoted per t and the
              pieces joined with a SINGLE SPACE (spec refinement: the
              join spec is deferred, one join rule ships); the shift is
              the max across elements, matching fold_map's own rule for
              several multiline payloads in one format.*)
            let val vs = map snd (filter (fn (k, _) => k = name) args) in
              if null vs then
                if #required p then error ("Missing argument " ^ quote name) else ("", 0)
              else
                let val results = map (quote_typ t) vs
                in (space_implode " " (map #1 results), fold (Integer.max o #2) results 0) end
            end
        | _ =>
            (case AList.lookup (op =) args name of
              NONE =>
                (*validate already ran: a defaulted param is filled in by
                  the time assemble sees it, so an absent value here can
                  only be an (optional) param the caller left out.
                  Substitute the empty segment DIRECTLY, bypassing
                  type-directed quoting -- routing "" through
                  quote_string would splice the two characters "" (a
                  quoted empty string), not an empty segment.*)
                if #required p then error ("Missing argument " ^ quote name) else ("", 0)
            | SOME v => quote_typ (#typ p) v))
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

(*the constraint sentence appended to a tool's description at
  construction time (below) -- the ONLY thing that crosses the wire for
  a constraint, so this wording IS the contract; pinned by assertion,
  spec's exact text.*)
fun constraint_sentence (MCP_Tool.Exactly_One members) =
  "Exactly one of " ^ commas members ^ " is required.";

fun describe_constraints description constraints =
  fold (fn c => fn d => d ^ " " ^ constraint_sentence c) constraints description;

(*string_fun: the mvp shape, schema {input :: string}; the value is
  handed to f as-is (no validation — a plain string function). Its one
  fixed param makes a cross-param constraint meaningless, same reasoning
  as func never taking declared params at all -- no constraints
  parameter here, always [].*)
fun func description f : MCP_Tool.tool =
  {description = description,
   params =
    [{name = "input", typ = MCP_Tool.String, required = true, default = NONE,
      description = "tool input"}],
   constraints = [],
   form = MCP_Tool.String_Fun,
   annotations = MCP_Tool.default_annotations,
   run = fn _ => fn args =>
    (case AList.lookup (op =) args "input" of
      SOME input => f input
    | NONE => error "Missing required argument: input")};

(*full-power hatch: declared params, validated before f sees them*)
fun ml_run description params annotations constraints f : MCP_Tool.tool =
  let
    val params = map param params;
    val _ = List.app (check_constraint params) constraints;
  in
    {description = describe_constraints description constraints, params = params,
     constraints = constraints, form = MCP_Tool.String_Fun, annotations = annotations,
     run = fn ctxt => fn args => f ctxt (validate ctxt params constraints args)}
  end;

(*wrap a diagnostic command: registration-time checks here (position
  report -> ctrl+click on the command; keyword-class restriction), the
  run function validates + assembles + executes against the RUN
  context's theory (the designation decides, not the registration site)*)
fun diag ctxt (cmd, pos) {description, params, format = fmt, constraints} : MCP_Tool.tool =
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
    val _ = List.app (check_constraint params) constraints;
  in
    {description = describe_constraints description constraints, params = params,
     constraints = constraints, form = MCP_Tool.Diag_Wrap, annotations = MCP_Tool.diag_annotations,
     run = fn run_ctxt => fn args =>
      let
        val (text, shift) = assemble params fmt (validate run_ctxt params constraints args);
      in exec_text (Proof_Context.theory_of run_ctxt) shift text end}
  end;

(*capture form (plans/ml_builtin_migration): a run slot for writeln-style
  ML functions (most naturally Ir-shaped: report via writeln/error,
  return unit) that would otherwise have to hand-roll exec_text's
  capture-and-join-errors block themselves. PLAIN print mode, not PIDE
  -- run_tool_result (mcp_session.scala) does not strip yxml markup the
  way the repl bridge's ir_result does, so captured text must already
  be plain, the same reason exec_text uses Print_Mode.with_modes [].
  PLAIN MODE ALONE IS NOT ENOUGH, though (plans/ml_builtin_migration
  wave 1, found the hard way against Ir.show): some markup -- Sledgehammer's
  Active.sendback, and Ir.render_isar_text's Markup.markups token
  highlighting -- is emitted unconditionally, independent of the current
  print mode. So the captured output is stripped through
  XML.content_of o YXML.parse_body unconditionally before it is returned;
  this is a safe identity on text that never had any yxml to strip.
  ANNOTATIONS ARE MANDATORY here (D2, closed out plans/param_schema_v2's
  follow-up): unlike diag_wrap (Keyword.is_diag proves read_only+
  idempotent) or string_fun (one fixed param, no real behavior claim to
  make), the form tag alone proves nothing about a capture tool's
  behavior -- Ir.show is read-only, Ir.step mutating, Ir.remove
  destructive, all one form -- so tool_cmd's Tool_Capture branch
  requires an explicit (annotations ...) clause and errors at
  registration if it is missing, rather than silently defaulting.*)
fun capture description params annotations constraints f : MCP_Tool.tool =
  let
    val params = map param params;
    val _ = List.app (check_constraint params) constraints;
    val plain = XML.content_of o YXML.parse_body;
  in
    {description = describe_constraints description constraints, params = params,
     constraints = constraints, form = MCP_Tool.Capture, annotations = annotations,
     run = fn ctxt => fn args =>
      (case MCP_Output.captured (fn () =>
          Print_Mode.with_modes [] (fn () => f ctxt (validate ctxt params constraints args)) ()) of
        (Exn.Res (), output) => plain output
      | (Exn.Exn exn, output) =>
          if Exn.is_interrupt exn then Exn.reraise exn
          else error (if output = "" then Runtime.exn_message exn
                      else plain output ^ "\n" ^ Runtime.exn_message exn))}
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

\<^verbatim>\<open>mcp_tool probe = capture \<open>fn ctxt => fn args => ...\<close>
  (params ...) (annotations idempotent_mutating)\<close> —
for a writeln-style function (returns unit, reports via writeln/error);
MCP_Combinators.capture runs it under MCP_Output.captured and returns
what it printed (plans/ml_builtin_migration). Unlike the other forms,
an \<^verbatim>\<open>(annotations ...)\<close> clause is MANDATORY here (plans/param_schema_v2
D2): the form tag alone proves nothing about a capture tool's behavior
(unlike diag_wrap, where Keyword.is_diag proves read_only+idempotent at
registration), so omitting the clause is a registration-time error
rather than a silent default.

\<^verbatim>\<open>mcp_resource simps\<close> — a named/dynamic fact, pretty-printed at READ
time (dynamic collections stay current); \<^verbatim>\<open>(isar \<open>print_simpset\<close>)\<close> —
captured diagnostic output; \<^verbatim>\<open>= \<open>fn ctxt => ...\<close>\<close> — ML read function.\<close>

ML \<open>
local

(* clauses: parenthesized keyword blocks, any order, at most once *)

datatype clause =
  Descr of string | Params of MCP_Tool.param list | Format of string | Isar of string
| Annot of MCP_Tool.annotations | Constr of MCP_Tool.constraint;

(*(optional) sits after the type, matched with Args.$$$ (an ident/keyword
  token by CONTENT, Pure/Isar/args.ML:81) rather than Parse.$$$, which
  would need "optional" declared in the theory header -- a minor keyword
  lexes as one in every IMPORTING theory too, breaking any unrelated use
  of the word. No declaration needed and none wanted (plans/
  param_schema_v2, "VERIFIED PARSER FACT").*)
val optional_flag =
  Scan.optional (Parse.$$$ "(" |-- Args.$$$ "optional" --| Parse.$$$ ")" >> K true) false;

(*the nine scalar type names -> MCP_Tool.ptyp; steps 3/4 (plans/
  param_schema_v2) extend this parser with "enum (a | b)" and recursive
  "list of <ptyp>" branches. Unlike the old string-typed param, an
  unrecognized name is now a PARSE-time error (the type system has no
  "unknown" constructor to construct instead), so the check that used
  to live in MCP_Combinators.param's param_types membership test moves
  here.*)
val scalar_ptyps =
  [("string", MCP_Tool.String), ("source", MCP_Tool.Source), ("args", MCP_Tool.Args),
   ("nat", MCP_Tool.Nat), ("int", MCP_Tool.Int), ("bool", MCP_Tool.Bool),
   ("term", MCP_Tool.Term), ("typ", MCP_Tool.Typ), ("fact", MCP_Tool.Fact)];

fun read_ptyp name =
  (case AList.lookup (op =) scalar_ptyps name of
    SOME t => t
  | NONE =>
      error ("Unknown MCP tool parameter type " ^ quote name ^
        " (expected " ^ commas_quote (map #1 scalar_ptyps) ^ ")"));

(*NOT Parse.name: "term" and "typ" are themselves Pure outer-syntax
  diagnostic COMMANDS, so the lexer classifies them as Token.Keyword,
  not Token.Ident -- and command keywords delimit SPANS at the outer-
  syntax scanning pass, before any inner parser (this one included) ever
  runs on a span's tokens; Parse.keyword as an extra alternative is not
  enough to un-break an already-cut span (unlike Args.$$$'s use of the
  same trick for its OWN literal, which does not sit inside another
  command's params clause). Same fix as elsewhere in this file ("The
  command name must be QUOTED"): accept the type name bare (7 of the 9
  names never collide with a keyword) OR quoted as a string, e.g.
  \<open>x :: "term" \<open>d\<close>\<close> -- a string/cartouche token is opaque to span
  scanning, so quoting shields it.*)
(*enum (a | b | c): "enum" matched by content (Args.$$$, no new header
  keyword, same trick as "optional"); "|" is already a Pure quasi-
  command keyword, so items parse as Parse.enum1 "|" <item> inside the
  same Parse.$$$ "(" ... ")" tokens clause_block already uses.
  Registration-time validity (nonempty, distinct, token-safe items; a
  default among them) is MCP_Combinators.param's job, not the parser's --
  the grammar accepts any name list here.
  Items are exactly as liable to collide with an existing keyword as a
  type name is (e.g. "type" itself, in the plan's own canonical
  find_definition example) -- same span-scanning hazard as ptyp_parser
  above. Parse.name already covers the fix (it is short_ident ||
  long_ident || sym_ident || number || string, string included), so a
  colliding item is quoted, e.g. \<open>kind :: enum (const | thm | "type")\<close>,
  with no further change needed here.*)
val enum_ptyp =
  Args.$$$ "enum" |-- Parse.$$$ "(" |-- Parse.enum1 "|" Parse.name --| Parse.$$$ ")"
    >> MCP_Tool.Enum;

(*list of <ptyp>: "list"/"of" matched by content, same trick again; the
  element type recurses through ptyp_parser itself, so "list of list of
  string" parses (whether it is USEFUL is a schema question the plan
  leaves open). A `fun`, not a `val`, purely so the recursive reference
  to ptyp_parser inside its own definition is legal.*)
fun ptyp_parser xs =
  (enum_ptyp ||
   (Args.$$$ "list" |-- Args.$$$ "of" |-- ptyp_parser >> MCP_Tool.List_Of) ||
   ((Parse.short_ident || Parse.long_ident || Parse.sym_ident || Parse.keyword || Parse.string)
     >> read_ptyp)) xs;

(*(annotations <bucket>): the isar surface only ever names one of the
  five pre-built buckets (spec refinement, plans/param_schema_v2) --
  arbitrary hint combinations stay ML-only, via MCP_Combinators.ml_run
  taking an MCP_Tool.annotations record directly.*)
val annotation_buckets =
  [("read_only", MCP_Tool.read_only),
   ("read_only_non_idempotent", MCP_Tool.read_only_non_idempotent),
   ("mutating", MCP_Tool.mutating),
   ("idempotent_mutating", MCP_Tool.idempotent_mutating),
   ("destructive", MCP_Tool.destructive)];

fun read_annotations name =
  (case AList.lookup (op =) annotation_buckets name of
    SOME a => a
  | NONE =>
      error ("Unknown MCP tool annotations bucket " ^ quote name ^
        " (expected " ^ commas_quote (map #1 annotation_buckets) ^ ")"));

val param_entry =
  Parse.name -- (Parse.$$$ "::" |-- ptyp_parser) -- optional_flag --
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

(*"annotations" is not already a global minor keyword the way
  description/params/format/isar happen to be (declared elsewhere in
  the distribution, for unrelated purposes) -- Parse.$$$ would need it
  declared in THIS theory's header, polluting every importing theory's
  keyword table. Same fix as optional/enum/list/of: Args.$$$ matches by
  CONTENT (ident or keyword), no declaration needed or wanted.*)
fun clause_block_ct kw p =
  Parse.$$$ "(" |-- Args.$$$ kw |-- Parse.!!! (p --| Parse.$$$ ")");

val clause =
  clause_block "description" Parse.embedded >> Descr ||
  clause_block "params" (Scan.repeat1 param_entry) >> Params ||
  clause_block "format" Parse.embedded >> Format ||
  clause_block "isar" Parse.embedded >> Isar ||
  clause_block_ct "annotations" Parse.name >> (Annot o read_annotations) ||
  clause_block_ct "exactly_one" (Scan.repeat1 Parse.name) >> (Constr o MCP_Tool.Exactly_One);

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
     isar = isar,
     annot = uniq (fn Annot a => SOME a | _ => NONE) "annotations",
     constr = uniq (fn Constr c => SOME c | _ => NONE) "exactly_one"}
  end;

fun the_descr what pos NONE =
      error ("Missing (description \<open>...\<close>) clause for " ^ what ^ Position.here pos)
  | the_descr _ _ (SOME d) = d;


(* ML forms: compile the user's source in the declaring context
   (method_setup's ML_Context.expression idiom) *)

fun print_param (p: MCP_Tool.param) =
  "{name = " ^ ML_Syntax.print_string (#name p) ^
  ", typ = " ^ MCP_Tool.print_ptyp (#typ p) ^
  ", required = " ^ Bool.toString (#required p) ^
  ", default = " ^ ML_Syntax.print_option ML_Syntax.print_string (#default p) ^
  ", description = " ^ ML_Syntax.print_string (#description p) ^ "}";

fun print_annotations (a: MCP_Tool.annotations) =
  "{read_only = " ^ ML_Syntax.print_option Bool.toString (#read_only a) ^
  ", idempotent = " ^ ML_Syntax.print_option Bool.toString (#idempotent a) ^
  ", destructive = " ^ ML_Syntax.print_option Bool.toString (#destructive a) ^
  ", open_world = " ^ ML_Syntax.print_option Bool.toString (#open_world a) ^ "}";

fun print_constraint (MCP_Tool.Exactly_One members) =
  "(MCP_Tool.Exactly_One " ^ ML_Syntax.print_list ML_Syntax.print_string members ^ ")";

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
    val {descr, params, fmt, annot, constr, ...} = digest what false clauses;
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
          val _ =
            if is_some annot
            then error ("(annotations ...) clause is not meaningful for the " ^
              "diagnostic-command wrap form of " ^ what ^
              " (the command's keyword class already proves read_only/idempotent)")
            else ();
          val tool =
            MCP_Combinators.diag lthy (name, pos)
              {description = descr', params = these params,
               format = the_default "" fmt, constraints = the_list constr};
        in #2 (MCP_Tool.declare (Binding.make (name, pos)) tool lthy) end
    | SOME (Tool_Fun source) =>
        let
          val _ =
            if is_some params orelse is_some fmt orelse is_some annot orelse is_some constr
            then error ("(params/format/annotations/exactly_one ...) clauses are not " ^
              "meaningful for the string form of " ^ what)
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
          val annot_ml = print_annotations (the_default MCP_Tool.default_annotations annot);
          val constr_ml = ML_Syntax.print_list print_constraint (the_list constr);
        in
          ml_declaration
            ("MCP_Tool.declare " ^ binding_ml (name, pos) ^
              " (MCP_Combinators.ml_run " ^ ML_Syntax.print_string descr' ^
              " " ^ params_ml ^ " " ^ annot_ml ^ " " ^ constr_ml)
            source ")" lthy
        end
    | SOME (Tool_Capture source) =>
        let
          val _ =
            if is_some fmt
            then error ("(format ...) clause is not meaningful for the capture form of " ^ what)
            else ();
          (*(annotations ...) is MANDATORY here (plans/ml_builtin_migration
            D2, closed out by plans/param_schema_v2's follow-up): the
            capture form's tag alone proves nothing about a tool's
            behavior -- Ir.show is read-only, Ir.step mutating, Ir.remove
            destructive, all one form -- unlike diag_wrap, where
            Keyword.is_diag proves read_only+idempotent at registration.*)
          val annot_ml =
            (case annot of
              SOME a => print_annotations a
            | NONE =>
                error ("Missing (annotations <bucket>) clause for the capture form of " ^
                  what ^ " (the form tag alone proves nothing about its behavior)"));
          val params_ml = ML_Syntax.print_list print_param (these params);
          val constr_ml = ML_Syntax.print_list print_constraint (the_list constr);
        in
          ml_declaration
            ("MCP_Tool.declare " ^ binding_ml (name, pos) ^
              " (MCP_Combinators.capture " ^ ML_Syntax.print_string descr' ^
              " " ^ params_ml ^ " " ^ annot_ml ^ " " ^ constr_ml)
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
    val {descr, params, fmt, isar, annot, constr} = digest what true clauses;
    val _ =
      if is_some params orelse is_some fmt orelse is_some annot orelse is_some constr
      then error ("(params/format/annotations/exactly_one ...) clauses are not meaningful for " ^
        what)
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
(*ptyp crosses as an XML.Encode.variant -- TAG ORDER MUST MATCH the
  scala mirror decoder (mcp_session.scala) EXACTLY: 0 String, 1 Source,
  2 Args, 3 Nat, 4 Int, 5 Bool, 6 Term, 7 Typ, 8 Fact, 9 Enum (items in
  the body), 10 List_Of (element ptyp in the body). Every nullary
  scalar encodes to identical bytes (empty attributes, empty body), so
  a mis-ordered scala list would decode e.g. Nat as Int SILENTLY -- no
  exception, just a wrong json type (plans/param_schema_v2, "THE
  HAZARD"). Recursive for List_Of, hence a plain `fun`, not `val`.*)
fun encode_ptyp x =
  let open XML.Encode in
    variant
     [fn MCP_Tool.String => ([], []),
      fn MCP_Tool.Source => ([], []),
      fn MCP_Tool.Args => ([], []),
      fn MCP_Tool.Nat => ([], []),
      fn MCP_Tool.Int => ([], []),
      fn MCP_Tool.Bool => ([], []),
      fn MCP_Tool.Term => ([], []),
      fn MCP_Tool.Typ => ([], []),
      fn MCP_Tool.Fact => ([], []),
      fn MCP_Tool.Enum items => ([], list string items),
      fn MCP_Tool.List_Of t => ([], encode_ptyp t)]
     x
  end;

val encode_param =
  let open XML.Encode in pair string (pair encode_ptyp (pair bool (pair (option string) string))) end;

(*the four hints as (option bool) each, in the SAME field order as the
  type/scala's Tool_Annotations -- no variant/tag hazard here (unlike
  encode_ptyp): every field is independently present or absent, so
  there is no positional ambiguity between two encoded values.*)
fun encode_annotations (a: MCP_Tool.annotations) =
  let open XML.Encode in
    pair (option bool) (pair (option bool) (pair (option bool) (option bool)))
      (#read_only a, (#idempotent a, (#destructive a, #open_world a)))
  end;

fun encode_row (name, tool: MCP_Tool.tool) =
  let open XML.Encode in
    pair string (pair string (pair string (pair (list encode_param) encode_annotations)))
      (name, (#description tool, (MCP_Tool.form_tag (#form tool),
        (map (fn p => (#name p, (#typ p, (#required p, (#default p, #description p)))))
          (#params tool),
         #annotations tool))))
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

text \<open>A5 fixture (plans/param_schema_v2): one param per ptyp SCALAR
constructor, declared here rather than in Tests/MCP_Tools_Tests.thy
because MCP_Bridge_Tests (mcp_bridge_tests.scala) is
MCP_Session_Suite("MCP-Tools", "MCP_Tools") -- it serves THIS theory,
not the Tests session -- and the tag-order hazard (a mis-ordered scala
decoder list silently reads e.g. Nat as Int) can only be caught by a
live bridge case reading a REAL encoded row. Enum/List_Of join this
fixture once steps 3/4 give them isar syntax. The (annotations
destructive) clause doubles this fixture as the A10 bridge case (step
5): a declared bucket other than the default arrives with the right
hints set, over the same live encoder that would silently corrupt a
mis-ordered wire shape.\<close>
mcp_tool ptyp_fixture = run \<open>fn _ => fn _ => "ok"\<close>
  (description \<open>one param per ptyp scalar constructor, for the tag-order bridge check\<close>)
  (annotations destructive)
  (params
    p_string :: string \<open>d\<close>
    p_source :: source \<open>d\<close>
    p_args :: args \<open>d\<close>
    p_nat :: nat \<open>d\<close>
    p_int :: int \<open>d\<close>
    p_bool :: bool \<open>d\<close>
    p_term :: "term" \<open>d\<close>
    p_typ :: "typ" \<open>d\<close>
    p_fact :: fact \<open>d\<close>)

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
            {description = description, params = [], constraints = [], form = MCP_Tool.Builtin,
             (*INERT: tools_body filters Builtin rows out of ml_rows, so
               this value never reaches encode_row -- it must not
               replicate the scala row's real bucket (mcp_server.scala).*)
             annotations = MCP_Tool.default_annotations,
             run = fn _ => fn _ => error "builtin tool: dispatched Isabelle/Scala-side"}
        |> #2)
      [("repl_list", "List all open REPL proof sessions."),
       ("repl_init", "Create a new REPL proof session that imports the given theories."),
       ("repl_init_from_source", "Create a new REPL rooted at a specific command inside an existing theory."),
       ("repl_fork", "Fork a sub-REPL from an existing REPL at a given state index."),
       ("repl_remove", "Remove a REPL and all sub-REPLs forked from it."),
       ("repl_step", "Apply one Isar command to a REPL and print the resulting proof state."),
       ("repl_state", "Print the proof/theory state of a REPL at a given index."),
       ("repl_edit", "Replace a REPL step with new Isar text and re-execute from there."),
       ("repl_replay", "Re-execute all stale steps in a REPL, in order."),
       ("repl_truncate", "Discard all REPL steps after a given index."),
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
