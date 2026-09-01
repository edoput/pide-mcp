(*  Title:      mcp/Tools/mcp_context_locator.ML
    Author:     isabelle-mcp

Theory-extensible addresses for Isabelle proof contexts.
*)

signature MCP_CONTEXT_LOCATOR =
sig
  type T
  type resolver = theory -> string -> string * Proof.context

  val parse: string -> T
  val make: string -> string -> T
  val print: T -> string
  val kind_of: T -> string
  val target_of: T -> string
  val theory: theory -> T

  val register: binding -> resolver -> theory -> theory
  val registered: theory -> string list
  val resolve: theory -> T -> T * Proof.context
  val resolve_string: theory -> string -> string * Proof.context

  val theory_resolver: resolver
end;


structure MCP_Context_Locator: MCP_CONTEXT_LOCATOR =
struct

datatype T = Locator of {kind: string, target: string};
type resolver = theory -> string -> string * Proof.context;

val prefix = "isabelle://context/";

fun ascii_lower c = #"a" <= c andalso c <= #"z";
fun ascii_digit c = #"0" <= c andalso c <= #"9";
fun kind_tail c = ascii_lower c orelse ascii_digit c orelse c = #"_" orelse c = #"-";

fun check_kind kind =
  (case String.explode kind of
    first :: rest =>
      if ascii_lower first andalso forall kind_tail rest then kind
      else error ("Malformed MCP context-locator kind " ^ quote kind)
  | [] => error "Empty MCP context-locator kind");

fun unreserved c =
  (#"a" <= c andalso c <= #"z") orelse
  (#"A" <= c andalso c <= #"Z") orelse
  ascii_digit c orelse member (op =) [#"-", #".", #"_", #"~"] c;

val hex = "0123456789ABCDEF";

fun encode_byte c =
  if unreserved c then String.str c
  else
    let val n = Char.ord c
    in "%" ^ String.str (String.sub (hex, n div 16)) ^
      String.str (String.sub (hex, n mod 16))
    end;

fun encode_target target =
  if target = "" then error "Empty MCP context-locator target"
  else String.implode (maps (String.explode o encode_byte) (String.explode target));

fun hex_value c =
  if #"0" <= c andalso c <= #"9" then SOME (Char.ord c - Char.ord #"0")
  else if #"A" <= c andalso c <= #"F" then SOME (10 + Char.ord c - Char.ord #"A")
  else NONE;

fun decode_target source =
  let
    fun malformed () = error ("Malformed MCP context-locator target " ^ quote source);
    fun decode [] result = String.implode (rev result)
      | decode (#"%" :: a :: b :: rest) result =
          (case (hex_value a, hex_value b) of
            (SOME hi, SOME lo) =>
              let val c = Char.chr (16 * hi + lo)
              in
                if unreserved c then malformed ()
                else decode rest (c :: result)
              end
          | _ => malformed ())
      | decode (#"%" :: _) _ = malformed ()
      | decode (c :: rest) result =
          if unreserved c then decode rest (c :: result) else malformed ();
    val target = if source = "" then malformed () else decode (String.explode source) [];
  in target end;

fun split_kind source =
  let
    fun split _ [] = error ("Malformed MCP context locator " ^ quote source)
      | split result (#"/" :: rest) = (String.implode (rev result), String.implode rest)
      | split result (c :: rest) = split (c :: result) rest;
  in split [] (String.explode source) end;

fun parse source =
  if not (String.isPrefix prefix source) then
    error ("MCP context locator must start with " ^ quote prefix ^ ": " ^ quote source)
  else
    let
      val rest = String.extract (source, size prefix, NONE);
      val (kind, encoded_target) = split_kind rest;
      val kind' = check_kind kind;
      val target = decode_target encoded_target;
    in Locator {kind = kind', target = target} end;

fun kind_of (Locator {kind, ...}) = kind;
fun target_of (Locator {target, ...}) = target;

fun theory thy =
  Locator {kind = "theory", target = Context.theory_long_name thy};

fun print (Locator {kind, target}) =
  prefix ^ check_kind kind ^ "/" ^ encode_target target;

fun make kind target =
  let val locator = Locator {kind = check_kind kind, target = target}
  in parse (print locator) end;

type entry = {serial: serial, position: Position.T, resolver: resolver};

fun eq_entry
    ({serial = serial1, ...}: entry, {serial = serial2, ...}: entry) =
  serial1 = serial2;

structure Resolver_Data = Theory_Data
(
  type T = entry Symtab.table;
  val empty = Symtab.empty;
  val merge = Symtab.merge eq_entry;
);

fun register binding resolver thy =
  let
    val kind = check_kind (Binding.name_of binding);
    val position = Binding.pos_of binding;
    val table = Resolver_Data.get thy;
    val _ =
      (case Symtab.lookup table kind of
        NONE => ()
      | SOME {position = previous, ...} =>
          error ("Duplicate MCP context resolver kind " ^ quote kind ^
            Position.here position ^ Position.here previous));
    val entry = {serial = serial (), position = position, resolver = resolver};
  in Resolver_Data.put (Symtab.update (kind, entry) table) thy end;

fun registered thy = sort_strings (Symtab.keys (Resolver_Data.get thy));

fun resolve root (locator as Locator {kind, target}) =
  (case Symtab.lookup (Resolver_Data.get root) kind of
    NONE => error ("Unknown MCP context-locator kind " ^ quote kind)
  | SOME {resolver, ...} =>
      let
        val (canonical_target, context) = resolver root target;
        val canonical = Locator {kind = kind, target = canonical_target};
        val _ = parse (print canonical);
      in (canonical, context) end);

fun resolve_string root source =
  let val (canonical, context) = resolve root (parse source)
  in (print canonical, context) end;

fun theory_resolver root name =
  let
    fun root_name thy =
      name = Context.theory_long_name thy orelse name = Context.theory_base_name thy;
    val thy =
      if root_name root then root
      else
        (case try (Context.get_theory {long = false} root) name of
          SOME thy => thy
        | NONE => Thy_Info.get_theory name);
  in (Context.theory_long_name thy, Proof_Context.init_global thy) end;

end;
