(*  Title:      mcp/Tools/MCP_Assumption.thy
    Author:     isabelle-mcp

Checked citation of a plan's assumption from a theory.

The Isar answer to what munit gets from `test("repl_list#I3 ...")`. A munit
suite has a name slot; an ML test block has none -- `\<^assert> (status = "ok")`
is anonymous, so nothing says which assumption it discharges and nothing
notices when that assumption is renamed or deleted.

This does NOT introduce a new convention. mcp/Tools/HOL/Tests/MCP_Repl_Tests.thy
already groups its asserts under `section \<open>T2: fresh state ...\<close>`; the IDs are
simply unqualified (T2 means repl_list#T2 only because the theory's header prose
says so) and unchecked (a typo is invisible). This makes the existing convention
qualified and checked, in the two places a theory can carry one:

  section \<open>\<^assumption>\<open>repl_list#T2\<close> fresh state, before any repl exists\<close>

    A DOCUMENT antiquotation, checked when the theory loads -- not merely at
    document-build time. Verified: a bad id fails `isabelle build` with a
    position, exactly as an undefined fact does.

  ML \<open>\<^assumption>\<open>repl_list#T2\<close> (status = "ok")\<close>

    An ML antiquotation expanding to a named assert. Reads as \<^assert> does,
    but a failure names the assumption instead of saying "Assertion failed",
    and the id is compile-time checked.

The id universe is generated from plans/ -- see tools/gen_assumptions.py. A
renamed or deleted assumption therefore cannot leave a stale citation behind:
the build breaks at the citation, with a position, which is this project's one
enforcement idiom (a failed \<^assert> fails the build) applied to the reference
rather than to the property.
*)

theory MCP_Assumption
  imports Pure
begin

ML_file \<open>assumption_ids.ML\<close>

ML \<open>
signature MCP_ASSUMPTION =
sig
  val known: string list
  val is_known: string -> bool
  val check: string * Position.T -> string
  val holds: string -> bool -> unit
end;

structure MCP_Assumption: MCP_ASSUMPTION =
struct

val known = MCP_Assumption_Ids.ids;

val known_set = Symtab.make_set known;

fun is_known id = Symtab.defined known_set id;

fun check (id, pos) =
  if is_known id then id
  else
    let
      val plan = hd (space_explode "#" id);
      val same_plan = filter (String.isPrefix (plan ^ "#")) known;
      val hint =
        if null same_plan then
          "\nNo plan named " ^ quote plan ^ " declares any assumption."
        else
          "\nDeclared in that plan: " ^ commas_quote (take 12 same_plan);
    in
      error ("Unknown assumption " ^ quote id ^ hint ^
        "\nThe id universe is generated from plans/ by tools/gen_assumptions.py." ^
        Position.here pos)
    end;

fun holds id b =
  if b then ()
  else error ("Assumption " ^ quote id ^ " does NOT hold");

end;
\<close>

ML \<open>
Theory.setup
 (ML_Antiquotation.inline_embedded \<^binding>\<open>assumption\<close>
    (Scan.lift Parse.embedded_position >> (fn arg =>
      "MCP_Assumption.holds " ^ ML_Syntax.print_string (MCP_Assumption.check arg)))
  #> Document_Output.antiquotation_pretty_source \<^binding>\<open>assumption\<close>
    (Scan.lift Parse.embedded_position)
    (fn _ => fn arg => Pretty.str (MCP_Assumption.check arg)));
\<close>

text \<open>Self-check: the registry is non-empty and the pilot family resolves. If
plans/ ever stops declaring the wave-0 infrastructure assumptions this fails
here rather than silently weakening every citation downstream.\<close>

ML \<open>
  \<^assumption>\<open>repl_list#I1\<close> (not (null MCP_Assumption.known));
  \<^assumption>\<open>repl_list#I1\<close> (MCP_Assumption.is_known "repl_list#I5");
  \<^assert> (not (MCP_Assumption.is_known "repl_list#I999"));
\<close>

end
