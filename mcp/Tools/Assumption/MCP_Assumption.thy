(*  Title:      mcp/Tools/Assumption/MCP_Assumption.thy
    Author:     isabelle-mcp

Structured links from Isabelle theory tests to plan claims and obligations.

The plan registry remains authoritative. Isabelle records qualified IDs and
checks their shape and relation at declaration time; tools/spec_gate.py checks
the exported references against plans/ASSUMPTIONS. This is deliberately
symmetric with munit's spec_test metadata: theory tests update Theory_Data and
export one deterministic YXML payload per theory at theory completion.
*)

theory MCP_Assumption
  imports Pure
  keywords
    "spec_test" :: thy_decl
    and "verifies" "covers"
begin

ML \<open>
signature MCP_ASSUMPTION =
sig
  datatype relation = Verifies | Covers
  type link = {relation: relation, id: string}
  type test =
    {theory: string, name: string, source_path: string, source_line: int,
     source_sha1: string, links: link list}

  val check_id: string * Position.T -> string
  val holds: string -> bool -> unit
  val declare: string * Position.T ->
    (string * Position.T) list -> (string * Position.T) list -> theory -> theory
  val local_tests: theory -> test list
end;

structure MCP_Assumption: MCP_ASSUMPTION =
struct

datatype relation = Verifies | Covers;
type link = {relation: relation, id: string};
type test =
  {theory: string, name: string, source_path: string, source_line: int,
   source_sha1: string, links: link list};

fun relation_name Verifies = "verifies"
  | relation_name Covers = "covers";

fun ascii_alpha c =
  (#"a" <= c andalso c <= #"z") orelse (#"A" <= c andalso c <= #"Z");
fun ascii_digit c = #"0" <= c andalso c <= #"9";
fun plan_char c =
  ascii_alpha c orelse ascii_digit c orelse member (op =) [#"_", #".", #"-"] c;

fun valid_id id =
  (case space_explode "#" id of
    [plan, label] =>
      not (null (String.explode plan)) andalso forall plan_char (String.explode plan) andalso
      (case String.explode label of
        kind :: digits =>
          member (op =) [#"A", #"I", #"T", #"D", #"Q"] kind andalso
          not (null digits) andalso forall ascii_digit digits
      | [] => false)
  | _ => false);

fun check_id (id, pos) =
  if valid_id id then id
  else error ("Malformed plan reference " ^ quote id ^
    "; expected plan#[A|I|T|D|Q]<number>" ^ Position.here pos);

fun is_test_obligation id =
  (case space_explode "#" id of
    [_, label] => String.isPrefix "T" label
  | _ => false);

fun is_verifiable_claim id =
  (case space_explode "#" id of
    [_, label] => String.isPrefix "A" label orelse String.isPrefix "I" label
  | _ => false);

fun check_link relation arg =
  let
    val id = check_id arg;
    val test_obligation = is_test_obligation id;
    val well_typed =
      (case relation of Covers => test_obligation | Verifies => is_verifiable_claim id);
    val _ = well_typed orelse
      error (relation_name relation ^ " cannot target " ^ quote id ^
        (if test_obligation then "; T labels must be covered"
         else "; only A/I labels may be verified") ^ Position.here (#2 arg));
  in {relation = relation, id = id} end;

fun holds id b =
  if b then () else error ("Assumption " ^ quote id ^ " does NOT hold");

fun eq_link ({relation = relation1, id = id1}: link,
    {relation = relation2, id = id2}: link) = relation1 = relation2 andalso id1 = id2;

fun eq_test ({theory = theory1, name = name1, source_path = source_path1,
      source_line = source_line1, source_sha1 = source_sha1_1, links = links1}: test,
    {theory = theory2, name = name2, source_path = source_path2,
      source_line = source_line2, source_sha1 = source_sha1_2, links = links2}: test) =
  theory1 = theory2 andalso name1 = name2 andalso source_path1 = source_path2 andalso
  source_line1 = source_line2 andalso source_sha1_1 = source_sha1_2 andalso
  eq_list eq_link (links1, links2);

structure Data = Theory_Data
(
  type T = test list;
  val empty = [];
  val merge = Library.merge eq_test;
);

fun local_tests thy =
  let val theory = Context.theory_long_name thy
  in filter (fn test => #theory test = theory) (Data.get thy) end;

fun source_info pos =
  let
    val source_path =
      (case Position.file_of pos of
        SOME path => path
      | NONE => error ("spec_test has no source file" ^ Position.here pos));
    val source_line =
      (case Position.line_of pos of
        SOME line => line
      | NONE => error ("spec_test has no source line" ^ Position.here pos));
    val source_sha1 = "sha1:" ^ SHA1.rep (SHA1.digest (File.read (Path.explode source_path)));
  in (source_path, source_line, source_sha1) end;

fun declare (name, pos) verifies covers thy =
  let
    val _ =
      if exists (not o Char.isSpace) (String.explode name) then ()
      else error ("spec_test name must not be empty" ^ Position.here pos);
    val links = map (check_link Verifies) verifies @ map (check_link Covers) covers;
    val _ =
      if null links then
        error ("spec_test needs at least one verifies or covers link" ^ Position.here pos)
      else ();
    val duplicates = Library.duplicates eq_link links;
    val _ =
      if null duplicates then ()
      else error ("Duplicate plan link on spec_test " ^ quote name ^ Position.here pos);
    val theory = Context.theory_long_name thy;
    val _ =
      if exists (fn test => #theory test = theory andalso #name test = name) (Data.get thy) then
        error ("Duplicate spec_test name " ^ quote name ^ " in theory " ^ quote theory ^
          Position.here pos)
      else ();
    val (source_path, source_line, source_sha1) = source_info pos;
    val test =
      {theory = theory, name = name, source_path = source_path, source_line = source_line,
       source_sha1 = source_sha1, links = links};
  in Data.map (cons test) thy end;

fun encode_link ({relation, id}: link) =
  XML.Encode.pair XML.Encode.string XML.Encode.string (relation_name relation, id);

fun encode_test ({name, source_path, source_line, source_sha1, links, ...}: test) =
  XML.Encode.pair XML.Encode.string
    (XML.Encode.pair XML.Encode.string
      (XML.Encode.pair XML.Encode.int
        (XML.Encode.pair XML.Encode.string (XML.Encode.list encode_link))))
    (name, (source_path, (source_line, (source_sha1, links))));

fun export_tests thy =
  let
    val tests = sort (string_ord o apply2 #name) (local_tests thy);
    val _ =
      if null tests then ()
      else Export.export thy \<^path_binding>\<open>mcp/spec-tests\<close>
        (XML.Encode.list encode_test tests);
  in () end;

val _ =
  (Theory.setup o Thy_Info.add_presentation) (fn _ => export_tests);

val _ =
  Theory.setup
   (ML_Antiquotation.inline_embedded \<^binding>\<open>assumption\<close>
      (Scan.lift Parse.embedded_position >> (fn arg =>
        "MCP_Assumption.holds " ^ ML_Syntax.print_string (check_id arg))) #>
    Document_Output.antiquotation_pretty_source \<^binding>\<open>assumption\<close>
      (Scan.lift Parse.embedded_position)
      (fn _ => fn arg => Pretty.str (check_id arg)));

val _ =
  Outer_Syntax.command \<^command_keyword>\<open>spec_test\<close>
    "register structured plan links for an Isabelle theory test"
    (Parse.embedded_position --
      Scan.optional
        (\<^keyword>\<open>verifies\<close> |-- Parse.and_list1 Parse.embedded_position) [] --
      Scan.optional
        (\<^keyword>\<open>covers\<close> |-- Parse.and_list1 Parse.embedded_position) [] >>
      (fn ((name, verifies), covers) =>
        Toplevel.theory (declare name verifies covers)));

end;
\<close>

text \<open>The declaration validates identity shape and relation locally. The
authoritative existence and layer checks happen after export in
<^verbatim>\<open>tools/spec_gate.py\<close>.\<close>

ML \<open>
  \<^assumption>\<open>repl_list#I1\<close> true;
  \<^assert> (Exn.is_exn (Exn.capture MCP_Assumption.check_id
    ("repl_list#T", Position.none)));
  \<^assert> (Exn.is_exn (Exn.capture MCP_Assumption.check_id
    ("not qualified", Position.none)));
\<close>

end
