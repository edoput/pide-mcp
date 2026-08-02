theory MCP_Tools_Tests
  imports MCP_Fixture_B MCP_Fixture_C MCP_Fixture_Sibling
begin

text \<open>Unit tests: the theory fails to load iff a test fails, so
\<^verbatim>\<open>isabelle build -d mcp/Tools MCP-Tools-Tests\<close> is the test runner.
This session is separate from MCP-Tools so the registrations below never
land in the production heap the server loads. Importing both fixture
arms (B: del, C: none) makes this theory the merge point of the
activation diamond.\<close>

section \<open>Registration: name space entities\<close>

ML \<open>
Context.>> (Context.map_theory (Named_Target.theory_map (fn lthy =>
  lthy
  |> MCP_Tool.declare \<^binding>\<open>test_tool\<close>
      {description = "a test tool", params = [], form = MCP_Tool.String_Fun,
       run = fn _ => fn args =>
        "ran:" ^ the_default "" (AList.lookup (op =) args "input")}
  |> #2)));
\<close>

ML \<open>
val context = Context.Proof \<^context>;
(*full internal name is theory-qualified*)
\<^assert> (MCP_Tool.defined context "MCP_Tools_Tests.test_tool");
(*check resolves the short external name*)
\<^assert> (MCP_Tool.check \<^context> ("test_tool", Position.none) = "MCP_Tools_Tests.test_tool");
(*unknown names error (with completion via Name_Space.check)*)
\<^assert> (Exn.is_exn (Exn.capture_body (fn () =>
  MCP_Tool.check \<^context> ("no_such_tool", Position.none))));
(*the registration records a position: ctrl+click works*)
val {pos, ...} =
  Name_Space.the_entry (MCP_Tool.space_of context) "MCP_Tools_Tests.test_tool";
\<^assert> (is_some (Position.line_of pos));
(*declared tools are active by default and runnable*)
\<^assert> (MCP_Tool.is_active context "MCP_Tools_Tests.test_tool");
\<^assert> (MCP_Tool.run \<^context> "MCP_Tools_Tests.test_tool" [("input", "x")] = "ran:x");
\<close>

ML \<open>
(*re-declaring the same binding in the same theory is a DUPLICATE error
  (Name_Space.define strict) -- the pivot's replacement for the old
  replace-by-name semantics; replay on theory re-run stays idempotent
  because each run starts from empty context data*)
\<^assert> (Exn.is_exn (Exn.capture_body (fn () =>
  \<^theory> |> Named_Target.theory_map (fn lthy =>
    lthy
    |> MCP_Tool.declare \<^binding>\<open>test_tool\<close>
        {description = "duplicate", params = [], form = MCP_Tool.String_Fun,
         run = fn _ => fn _ => ""}
    |> #2))));
\<close>

section \<open>Visibility follows imports; activation merges by union\<close>

ML \<open>
(*invisible in a sibling that does not import the registering theory*)
val sibling = Context.Theory \<^theory>\<open>MCP_Fixture_Sibling\<close>;
\<^assert> (not (MCP_Tool.defined sibling "MCP_Fixture_A.alpha"));

(*visible + active downstream of the registration (C imports A)*)
val c = Context.Theory \<^theory>\<open>MCP_Fixture_C\<close>;
\<^assert> (MCP_Tool.is_active c "MCP_Fixture_A.alpha");

(*registered but deactivated where the del happened (B)*)
val b = Context.Theory \<^theory>\<open>MCP_Fixture_B\<close>;
\<^assert> (MCP_Tool.defined b "MCP_Fixture_A.alpha");
\<^assert> (not (MCP_Tool.is_active b "MCP_Fixture_A.alpha"));

(*diamond merge point (this theory imports B and C): activation is a
  UNION, so C's add path resurrects B's del -- the pinned decision
  (plans/mcp_tool_registry); the escape hatch is a local del*)
\<^assert> (MCP_Tool.is_active (Context.Proof \<^context>) "MCP_Fixture_A.alpha");
\<close>

section \<open>Activation: declaration attribute and bundles\<close>

declare [[mcp_tools del: alpha]]
ML \<open>\<^assert> (not (MCP_Tool.is_active (Context.Proof \<^context>) "MCP_Fixture_A.alpha"))\<close>

declare [[mcp_tools add: alpha]]
ML \<open>\<^assert> (MCP_Tool.is_active (Context.Proof \<^context>) "MCP_Fixture_A.alpha")\<close>

text \<open>Bundle scoping: \<open>beta\<close> was deactivated at its registration site;
opening the bundle activates it only inside the block.\<close>

ML \<open>\<^assert> (not (MCP_Tool.is_active (Context.Proof \<^context>) "MCP_Fixture_A.beta"))\<close>

context includes beta_tools
begin
ML \<open>\<^assert> (MCP_Tool.is_active (Context.Proof \<^context>) "MCP_Fixture_A.beta")\<close>
end

ML \<open>\<^assert> (not (MCP_Tool.is_active (Context.Proof \<^context>) "MCP_Fixture_A.beta"))\<close>

section \<open>Print commands\<close>

print_mcp_tools
print_mcp_tools!
print_mcp_resources
print_mcp_resources!

section \<open>Protocol payloads\<close>

ML \<open>
(*tools_body encodes what XML.Decode recovers -- mirrors the Scala side;
  now a PAIR: ml rows (full internal name, description, form tag,
  params; active, non-builtin only) and a builtins section (base name,
  active) for every registered Builtin-form mirror
  (plans/builtin_activation).*)
(*mirrors MCP_Tools.thy's encode_ptyp -- TAG ORDER MUST MATCH exactly
  (plans/param_schema_v2 A4/A5): 0 String, 1 Source, 2 Args, 3 Nat,
  4 Int, 5 Bool, 6 Term, 7 Typ, 8 Fact, 9 Enum, 10 List_Of.*)
fun decode_ptyp body =
  let open XML.Decode in
    variant
     [fn _ => MCP_Tool.String,
      fn _ => MCP_Tool.Source,
      fn _ => MCP_Tool.Args,
      fn _ => MCP_Tool.Nat,
      fn _ => MCP_Tool.Int,
      fn _ => MCP_Tool.Bool,
      fn _ => MCP_Tool.Term,
      fn _ => MCP_Tool.Typ,
      fn _ => MCP_Tool.Fact,
      fn (_, ts) => MCP_Tool.Enum (list string ts),
      fn (_, ts) => MCP_Tool.List_Of (decode_ptyp ts)]
     body
  end;

fun decode_row_list body =
  let open XML.Decode in
    list (pair string (pair string (pair string
      (list (pair string (pair decode_ptyp (pair bool (pair (option string) string))))))))
      body
  end;
fun decode_builtin_list body = let open XML.Decode in list (pair string bool) body end;
fun decode_full body =
  let open XML.Decode in pair decode_row_list decode_builtin_list body end;
fun decode_tools body = map (fn (n, (d, (f, _))) => (n, d, f)) (#1 (decode_full body));

val (full_rows, builtin_rows) = decode_full (MCP_Protocol.tools_body \<^context>);
val rows = map (fn (n, (d, (f, _))) => (n, d, f)) full_rows;
\<^assert> (member (op =) rows ("MCP_Tools.shout", "uppercase the input", "string_fun"));
\<^assert> (exists (fn (n, _, _) => n = "MCP_Fixture_A.alpha") rows);
\<^assert> (not (exists (fn (n, _, _) => n = "MCP_Fixture_A.beta") rows));

(*params cross the bridge: shout advertises {input :: string, required}*)
val (_, (_, (_, shout_params))) =
  the (find_first (fn (n, _) => n = "MCP_Tools.shout") full_rows);
\<^assert> (shout_params = [("input", (MCP_Tool.String, (true, (NONE, "tool input"))))]);

(*A1: builtin mirrors are ordinary registry entries -- del/add round trip
  through the plain [[mcp_tools ...]] attribute like any other tool, and
  the run slot errors with the builtin message if ever invoked directly.
  A2 (first half): mirrors never enter the ml row list -- Builtin rows
  never reach exposure-name computation.*)
\<^assert> (not (exists (fn (n, _, _) => n = "MCP_Tools.repl_list") rows));
\<^assert> (member (op =) builtin_rows ("repl_list", true));
\<^assert> (member (op =) builtin_rows ("tool_scope_include", true));
\<^assert> (MCP_Tool.is_active (Context.Proof \<^context>) "MCP_Tools.repl_list");
\<^assert> (Exn.is_exn (Exn.capture_body (fn () =>
  MCP_Tool.run \<^context> "MCP_Tools.repl_list" [])));
\<close>

declare [[mcp_tools del: repl_list]]
ML \<open>
(*del hides the mirror from the builtins section (active = false) but
  the row stays registered -- A2 (second half): "hidden" (registered,
  del'd) is distinguishable from "absent" (no mirror at all)*)
val (_, builtin_rows_del) = decode_full (MCP_Protocol.tools_body \<^context>);
\<^assert> (member (op =) builtin_rows_del ("repl_list", false));
\<^assert> (MCP_Tool.defined (Context.Proof \<^context>) "MCP_Tools.repl_list");
\<^assert> (not (MCP_Tool.is_active (Context.Proof \<^context>) "MCP_Tools.repl_list"));
\<close>
declare [[mcp_tools add: repl_list]]
ML \<open>
val (_, builtin_rows_readd) = decode_full (MCP_Protocol.tools_body \<^context>);
\<^assert> (member (op =) builtin_rows_readd ("repl_list", true));
\<close>

ML \<open>\<^assert> (MCP_Protocol.run_tool \<^context> "MCP_Tools.shout" [("input", "abc")] = ("ok", "ABC"))\<close>

ML \<open>
let val (status, output) = MCP_Protocol.run_tool \<^context> "no_such_tool" [("input", "x")] in
  \<^assert> (status = "error");
  \<^assert> (String.isSubstring "no_such_tool" output)
end;
\<close>

ML \<open>
(*serving follows listing: inactive tools are not callable*)
let
  val (status, output) =
    MCP_Protocol.run_tool \<^context> "MCP_Fixture_A.beta" [("input", "x")]
in
  \<^assert> (status = "error");
  \<^assert> (String.isSubstring "Inactive" output)
end;
\<close>

section \<open>Designation\<close>

ML \<open>
(*"" designates the MCP_Tools theory itself: sees shout, not fixtures*)
val rows0 = decode_tools (MCP_Protocol.tools_body (MCP_Protocol.designated_context "" []));
\<^assert> (exists (fn (n, _, _) => n = "MCP_Tools.shout") rows0);
\<^assert> (not (exists (fn (n, _, _) => n = "MCP_Fixture_A.alpha") rows0));

(*an explicit theory designation resolves via Thy_Info (canonical key);
  only ancestor-heap theories are in Thy_Info during a batch build, so
  the fixture theories cannot be designated here -- live-designation
  coverage is the bridge suite's job*)
val tools_name =
  the (find_first (fn n => Long_Name.base_name n = "MCP_Tools") (Thy_Info.get_names ()));
val rows_t = decode_tools (MCP_Protocol.tools_body (MCP_Protocol.designated_context tools_name []));
\<^assert> (rows_t = rows0);

(*unknown designations error with the offending name*)
\<^assert> (Exn.is_exn (Exn.capture_body (fn () =>
  MCP_Protocol.designated_context "No_Such_Theory" [])));

(*unknown repl designations error with the offending id, distinct from
  an unknown theory (no repl support in the base MCP-Tools-Tests image)*)
val repl_err =
  (case Exn.capture_body (fn () => MCP_Protocol.designated_context "repl:R1" []) of
    Exn.Exn exn => Runtime.exn_message exn
  | Exn.Res _ => "");
\<^assert> (String.isSubstring "R1" repl_err andalso String.isSubstring "repl" repl_err);

(*bundle includes fold onto the resolved context; an unresolvable bundle
  name errors naming the bundle, before or after other bundles*)
\<^assert> (Exn.is_exn (Exn.capture_body (fn () =>
  MCP_Protocol.designated_context "" ["No_Such_Bundle"])));
val bundle_err =
  (case Exn.capture_body (fn () => MCP_Protocol.designated_context "" ["No_Such_Bundle"]) of
    Exn.Exn exn => Runtime.exn_message exn
  | Exn.Res _ => "");
\<^assert> (String.isSubstring "No_Such_Bundle" bundle_err);
\<close>

section \<open>Resources (exact mirror of tools)\<close>

ML \<open>
val rrows =
  let open XML.Decode in list (pair string string) (MCP_Protocol.resources_body \<^context>) end;
\<^assert> (member (op =) rrows ("MCP_Tools.greeting", "a static demo resource"));
\<^assert> (MCP_Protocol.read_resource \<^context> "MCP_Tools.greeting" =
  ("ok", "hello from MCP_Resource"));
\<^assert> (#1 (MCP_Protocol.read_resource \<^context> "no_such_resource") = "error");
\<close>

declare [[mcp_resources del: greeting]]
ML \<open>
\<^assert> (not (MCP_Resource.is_active (Context.Proof \<^context>) "MCP_Tools.greeting"));
\<^assert> (#1 (MCP_Protocol.read_resource \<^context> "MCP_Tools.greeting") = "error");
\<close>
declare [[mcp_resources add: greeting]]
ML \<open>\<^assert> (MCP_Resource.is_active (Context.Proof \<^context>) "MCP_Tools.greeting")\<close>

section \<open>Combinators: quoting matrix\<close>

ML \<open>
fun is_err f = Exn.is_exn (Exn.capture_body f);

(*inner-string quoting: quotes and backslashes escape; the value
  round-trips as DATA, never as isar*)
\<^assert> (MCP_Combinators.quote_string "plain" = "\"plain\"");
\<^assert> (MCP_Combinators.quote_string "a\"b\\c" = "\"a\\\"b\\\\c\"");

(*cartouche quoting: balanced payloads pass, a stray close is rejected*)
\<^assert> (MCP_Combinators.quote_cartouche "x + y" = "\<open>x + y\<close>");
\<^assert> (MCP_Combinators.quote_cartouche "f \<open>nested\<close> g" = "\<open>f \<open>nested\<close> g\<close>");
(*the unbalanced close delimiter is built with Symbol.close: writing it
  literally would break THIS theory's ML cartouche the same way*)
\<^assert> (is_err (fn () => MCP_Combinators.quote_cartouche ("evil" ^ Symbol.close ^ " escape")));
\<close>

section \<open>Combinators: validation\<close>

ML \<open>
fun mk_param (name, typ) required default : MCP_Tool.param =
  MCP_Combinators.param
    {name = name, typ = typ, required = required, default = default,
     description = "test param"};

val ps =
  [mk_param ("crit", MCP_Tool.String) true NONE,
   mk_param ("limit", MCP_Tool.Nat) false (SOME "40")];

(*defaults filled in declaration order*)
\<^assert> (MCP_Combinators.validate \<^context> ps [("crit", "x")] =
  [("crit", "x"), ("limit", "40")]);

(*explicit values win*)
\<^assert> (MCP_Combinators.validate \<^context> ps [("limit", "7"), ("crit", "x")] =
  [("crit", "x"), ("limit", "7")]);

(*typed errors name the argument*)
fun err_mentions f sub =
  (case Exn.capture_body f of
    Exn.Exn exn => String.isSubstring sub (Runtime.exn_message exn)
  | Exn.Res _ => false);

\<^assert> (err_mentions (fn () => MCP_Combinators.validate \<^context> ps []) "crit");
\<^assert> (err_mentions
  (fn () => MCP_Combinators.validate \<^context> ps [("crit", "x"), ("limit", "many")]) "limit");
\<^assert> (err_mentions
  (fn () => MCP_Combinators.validate \<^context> ps [("crit", "x"), ("bogus", "y")]) "bogus");

(*term params elaborate against the context*)
val tp = [mk_param ("t", MCP_Tool.Term) true NONE];
\<^assert> (MCP_Combinators.validate \<^context> tp [("t", "PROP A \<Longrightarrow> PROP A")] =
  [("t", "PROP A \<Longrightarrow> PROP A")]);
\<^assert> (err_mentions (fn () => MCP_Combinators.validate \<^context> tp [("t", "\<Longrightarrow>")]) "t");

(*A3 (plans/param_schema_v2): "unknown parameter types are rejected at
  param construction" no longer type-checks -- MCP_Tool.ptyp is a closed
  variant now, so an unknown type name is a compile error, not a runtime
  one. The compiler is the new gate; its intent moves to the enum-items
  check (A7, step 3).*)
\<close>

section \<open>Combinators: the (optional) modifier (plans/param_schema_v2 A1)\<close>

text \<open>required=false/default=NONE was always representable in the data
model (MCP_Combinators.param takes required and default independently)
but unreachable from isar until now -- param_entry always derived
required from is_none default. validate itself needed no change: its
value_of already returns NONE for an absent required=false/default=NONE
param, which map_filter already drops.\<close>

ML \<open>
val opt_ps =
  [mk_param ("crit", MCP_Tool.String) true NONE,
   mk_param ("repl", MCP_Tool.String) false NONE];

(*absent optional is dropped, not an error and not a spurious empty pair*)
\<^assert> (MCP_Combinators.validate \<^context> opt_ps [("crit", "x")] = [("crit", "x")]);
(*supplied optional is kept, in declaration order*)
\<^assert> (MCP_Combinators.validate \<^context> opt_ps [("crit", "x"), ("repl", "T")] =
  [("crit", "x"), ("repl", "T")]);

(*assemble: an absent optional referenced in the format substitutes the
  empty string rather than erroring "Missing argument"*)
\<^assert> (MCP_Combinators.assemble opt_ps "find_theorems (repl $repl) $crit"
    [("crit", "conj")] =
  ("find_theorems (repl ) \"conj\"", 0));
(*a SUPPLIED value still goes through normal type-directed quoting --
  only the absent case bypasses it*)
\<^assert> (MCP_Combinators.assemble opt_ps "find_theorems (repl $repl) $crit"
    [("crit", "conj"), ("repl", "T")] =
  ("find_theorems (repl \"T\") \"conj\"", 0));
\<close>

text \<open>A2: (optional) is declarable from isar with NO new header keyword
(Args.$$$ matches "optional" by content); mutually exclusive with a
default value.\<close>

mcp_tool optional_probe = run \<open>fn _ => fn args =>
  (case AList.lookup (op =) args "repl" of SOME v => "repl=" ^ v | NONE => "no-repl")\<close>
  (description \<open>probe for the (optional) modifier\<close>)
  (params
    repl :: string (optional) \<open>REPL id; omitted = image\<close>
    crit :: string \<open>search criteria\<close>)

ML \<open>
val optional_probe = MCP_Tool.get (Context.Proof \<^context>) "MCP_Tools_Tests.optional_probe";
val repl_param = the (find_first (fn p => #name p = "repl") (#params optional_probe));
\<^assert> (not (#required repl_param) andalso #default repl_param = NONE);
\<^assert> (MCP_Tool.run \<^context> "MCP_Tools_Tests.optional_probe" [("crit", "x")] = "no-repl");
\<^assert> (MCP_Tool.run \<^context> "MCP_Tools_Tests.optional_probe" [("crit", "x"), ("repl", "T")] =
  "repl=T");
\<close>

ML \<open>
(*(optional) together with "= default" is a registration error*)
\<^assert> (err_mentions
  (fn () => MCP_Combinators.exec_text \<^theory> 0
    ("mcp_tool \"optional_bad\" = run \<open>fn _ => fn _ => \"\"\<close> (description \<open>d\<close>) " ^
     "(params repl :: string (optional) = \<open>T\<close> \<open>x\<close>)"))
  "optional");
\<close>

section \<open>Combinators: format assembly\<close>

ML \<open>
(*type-directed quoting: string -> inner string, nat -> literal*)
\<^assert> (MCP_Combinators.assemble ps "find_theorems (limit $limit) $crit"
    [("crit", "conj"), ("limit", "40")] =
  ("find_theorems (limit 40) \"conj\"", 0));

(*source params: single-line -> inline cartouche, multiline -> framed
  on its own line with shift 1*)
val sp = [mk_param ("input", MCP_Tool.Source) true NONE];
\<^assert> (MCP_Combinators.assemble sp "ML_val $input" [("input", "1 + 1")] =
  ("ML_val \<open>1 + 1\<close>", 0));
\<^assert> (MCP_Combinators.assemble sp "ML_val $input" [("input", "val x = 1;\nval y = x;")] =
  ("ML_val \<open>\nval x = 1;\nval y = x;\<close>", 1));

(*format checking at build time: unknown placeholder, unused required*)
\<^assert> (is_err (fn () => MCP_Combinators.check_format ps "find_theorems $bogus"));
\<^assert> (is_err (fn () => MCP_Combinators.check_format ps "find_theorems (limit $limit)"));
\<^assert> (MCP_Combinators.check_format ps "find_theorems (limit $limit) $crit" = ());

(*injection: adversarial string values stay data*)
val (evil, _) =
  MCP_Combinators.assemble ps "find_theorems $crit" [("crit", "x\" and_evil \"y")];
\<^assert> (evil = "find_theorems \"x\\\" and_evil \\\"y\"");
\<close>

section \<open>Exec runner: capture and positions\<close>

ML \<open>
(*a diagnostic command through the runner returns its printed output*)
val out = MCP_Combinators.exec_text \<^theory> 0 "find_consts strict: \"prop => prop\"";
\<^assert> (String.isSubstring "Pure.prop" out);
\<close>

ML \<open>
(*ML errors report snippet-relative lines: the multiline payload starts
  on line 2 of the assembled text (shift 1), an error on ITS line 2 is
  reported as "line 2 of your input"*)
val (text, shift) =
  MCP_Combinators.assemble
    [MCP_Combinators.param
      {name = "input", typ = MCP_Tool.Source, required = true, default = NONE,
       description = "d"}]
    "ML_val $input"
    [("input", "val ok = 1;\nval bad = undefined_name_xyz;")];
\<^assert> (shift = 1);
val msg =
  (case Exn.capture_body (fn () => MCP_Combinators.exec_text \<^theory> shift text) of
    Exn.Exn exn => Runtime.exn_message exn
  | Exn.Res _ => error "expected an error from the snippet");
\<^assert> (String.isSubstring "undefined_name_xyz" msg);
\<^assert> (String.isSubstring "line 2 of your input" msg);
\<close>

section \<open>The mcp_tool command: diag form end to end\<close>

mcp_tool "find_consts"
  (description \<open>search for constants by type pattern\<close>)

ML \<open>
(*registered under the wrapping theory's name, active, diag form*)
val context = Context.Proof \<^context>;
\<^assert> (MCP_Tool.is_active context "MCP_Tools_Tests.find_consts");
\<^assert> (#form (MCP_Tool.get context "MCP_Tools_Tests.find_consts") = MCP_Tool.Diag_Wrap);
(*default schema {input :: args}; running it wraps "find_consts $input"*)
val out = MCP_Tool.run \<^context> "MCP_Tools_Tests.find_consts" [("input", "strict: \"prop => prop\"")];
\<^assert> (String.isSubstring "Pure.prop" out);
\<close>

mcp_tool "find_theorems"
  (description \<open>search theorems; criteria as in the find_theorems command\<close>)
  (params
    criteria :: args \<open>search criteria in find_theorems syntax, e.g. name: conj\<close>
    limit :: nat = 20 \<open>maximum number of results\<close>)
  (format \<open>find_theorems ($limit) $criteria\<close>)

ML \<open>
val out =
  MCP_Tool.run \<^context> "MCP_Tools_Tests.find_theorems" [("criteria", "name: conjunctionI")];
\<^assert> (String.isSubstring "conjunctionI" out);
(*declared params land on the tool*)
val tool = MCP_Tool.get (Context.Proof \<^context>) "MCP_Tools_Tests.find_theorems";
\<^assert> (map #name (#params tool) = ["criteria", "limit"]);
\<^assert> (#default (nth (#params tool) 1) = SOME "20");
\<close>

mcp_tool snippet = run \<open>fn ctxt => fn args =>
  (case AList.lookup (op =) args "n" of
    SOME n => cat_lines (replicate (Value.parse_nat n) "tick")
  | NONE => error "missing n")\<close>
  (description \<open>emit n lines of tick\<close>)
  (params n :: nat \<open>how many\<close>)

ML \<open>
\<^assert> (MCP_Tool.run \<^context> "MCP_Tools_Tests.snippet" [("n", "2")] = "tick\ntick");
(*validation guards the run form too*)
\<^assert> (err_mentions
  (fn () => MCP_Tool.run \<^context> "MCP_Tools_Tests.snippet" [("n", "no")]) "n");
\<close>

section \<open>The mcp_tool command: capture form (plans/ml_builtin_migration A1/A2)\<close>

mcp_tool capture_ok = capture \<open>fn _ => fn args =>
  writeln ("got:" ^ MCP_Combinators.arg args "x")\<close>
  (description \<open>writeln its input\<close>)
  (params x :: string \<open>echoed back\<close>)

mcp_tool capture_err = capture \<open>fn _ => fn _ => (writeln "before"; error "boom")\<close>
  (description \<open>writeln then error\<close>)

mcp_tool capture_err_only = capture \<open>fn _ => fn _ => error "silent boom"\<close>
  (description \<open>errors with no output at all\<close>)

mcp_tool capture_default = capture \<open>fn _ => fn args =>
  writeln (MCP_Combinators.arg args "greeting")\<close>
  (description \<open>arg is total for a defaulted param even when the caller omits it\<close>)
  (params greeting :: string = \<open>hello\<close> \<open>greeting text\<close>)

mcp_tool capture_int = capture \<open>fn _ => fn args =>
  writeln (string_of_int (MCP_Combinators.arg_int args "n" + 1))\<close>
  (description \<open>arg_int accessor\<close>)
  (params n :: int \<open>a number\<close>)

mcp_tool capture_bad_accessor = capture \<open>fn _ => fn args =>
  writeln (MCP_Combinators.arg args "nope")\<close>
  (description \<open>calls arg on a name outside its own params clause -- a tool bug\<close>)
  (params declared :: string \<open>present but irrelevant to the bug\<close>)

mcp_tool capture_slow = capture \<open>fn _ => fn _ =>
  (OS.Process.sleep (Time.fromReal 2.0); writeln "slow done")\<close>
  (description \<open>sleeps ~2s then writelns -- for the run_tool bridge async
    test (plans/ml_builtin_migration A4), a fast concurrent call must not
    wait behind this one\<close>)

ML \<open>
val context = Context.Proof \<^context>;

(*A1: a capture tool returns what its function printed*)
\<^assert> (MCP_Tool.run \<^context> "MCP_Tools_Tests.capture_ok" [("x", "hi")] = "got:hi");

(*A1: writeln then error -- output and message joined, in that order*)
\<^assert> (err_mentions
  (fn () => MCP_Tool.run \<^context> "MCP_Tools_Tests.capture_err" []) "before");
\<^assert> (err_mentions
  (fn () => MCP_Tool.run \<^context> "MCP_Tools_Tests.capture_err" []) "boom");

(*A1: an error with no output still surfaces the message alone*)
\<^assert> (err_mentions
  (fn () => MCP_Tool.run \<^context> "MCP_Tools_Tests.capture_err_only" []) "silent boom");

(*A2: arg is TOTAL for a declared param -- present even when the caller
  omitted it, because validate already filled in the default*)
\<^assert> (MCP_Tool.run \<^context> "MCP_Tools_Tests.capture_default" [] = "hello");
\<^assert> (MCP_Tool.run \<^context> "MCP_Tools_Tests.capture_default" [("greeting", "hi")] = "hi");

(*arg_int parses the validated string*)
\<^assert> (MCP_Tool.run \<^context> "MCP_Tools_Tests.capture_int" [("n", "41")] = "42");

(*A2: arg on a name outside the tool's own params clause is a tool-bug
  error naming the offending parameter, not a client error*)
\<^assert> (err_mentions
  (fn () => MCP_Tool.run \<^context> "MCP_Tools_Tests.capture_bad_accessor" [("declared", "x")])
  "nope");

(*the row is tagged [capture], both via the form field and print_mcp_tools*)
\<^assert> (#form (MCP_Tool.get context "MCP_Tools_Tests.capture_ok") = MCP_Tool.Capture);
\<^assert> (String.isSubstring "[capture]" (MCP_Combinators.exec_text \<^theory> 0 "print_mcp_tools"));
\<close>

text \<open>A5 (plans/ml_builtin_migration): a capture tool forks its OWN group
and registers its OWN buffer inside \<^verbatim>\<open>MCP_Output.captured\<close>
(MCP_Tools.thy). Nest it inside an already-registered OUTER group (the
shape a naive MCP.run_tool that also registered a buffer would create,
and precisely why step 5 does not) and confirm the inner tool's output
lands in ITS OWN return value, not the outer buffer -- \<^verbatim>\<open>find_buffer\<close>'s
ancestry walk must resolve to the more recently registered (inner) entry,
not the first one found by naive ancestor order.\<close>
ML \<open>
val _ =
  let
    val outer_group = Future.new_group NONE;
    val finish_outer = MCP_Output.register outer_group;
    val inner_result =
      Future.join
        ((singleton o Future.forks)
          {name = "A5 nested capture probe", group = SOME outer_group, deps = [],
           pri = 0, interrupts = true}
          (fn () => MCP_Tool.run \<^context> "MCP_Tools_Tests.capture_ok" [("x", "nested")]));
    val outer_output = finish_outer ();
  in
    \<^assert> (inner_result = "got:nested");
    \<^assert> (not (String.isSubstring "got:nested" outer_output))
  end;
\<close>

section \<open>The mcp_tool command: registration-time rejection\<close>

ML \<open>
(*run the COMMAND itself through the runner so its failures are
  observable without failing this theory: non-diag and unknown commands
  are rejected at registration, as is a missing description*)
fun reg_fails src = err_mentions (fn () => MCP_Combinators.exec_text \<^theory> 0 src);

\<^assert> (reg_fails "mcp_tool \"typedecl\" (description \<open>d\<close>)" "diagnostic");
\<^assert> (reg_fails "mcp_tool no_such_command_xyz (description \<open>d\<close>)" "no_such_command_xyz");
\<^assert> (reg_fails "mcp_tool \"find_consts\"" "description");
\<close>

section \<open>The mcp_resource command: three forms\<close>

named_theorems test_collection \<open>a dynamic fact for the read-time test\<close>

mcp_resource test_collection

ML \<open>
(*read-time evaluation: the collection is empty now ...*)
val r0 = MCP_Resource.read \<^context> "MCP_Tools_Tests.test_collection";
\<close>

declare Pure.reflexive [test_collection]

ML \<open>
(*... and a fact added AFTER registration is visible on the next read*)
val r1 = MCP_Resource.read \<^context> "MCP_Tools_Tests.test_collection";
\<^assert> (r0 <> r1);
\<^assert> (String.isSubstring "\<equiv>" r1);
\<close>

mcp_resource consts_dump (isar \<open>print_theory\<close>)
  (description \<open>the theory content listing, captured at read time\<close>)

ML \<open>
val out = MCP_Resource.read \<^context> "MCP_Tools_Tests.consts_dump";
\<^assert> (out <> "");
\<close>

ML \<open>
(*unknown fact name rejected at registration (spec phase-2 box)*)
\<^assert> (reg_fails "mcp_resource no_such_fact_xyz" "no_such_fact_xyz");
\<close>

text \<open>A13 (plans/ml_builtin_migration): the capture-form exercises above ran
\<^verbatim>\<open>MCP_Output.captured\<close> at BUILD time, which installs the Private_Output
wrappers and marks \<^verbatim>\<open>wrapped = true\<close> in a Synchronized var that survives
into the saved heap. A fresh process loading this heap re-assigns
Private_Output's functions at startup, silently discarding those wrappers
-- but \<^verbatim>\<open>wrapped\<close> still reads true there, so a later
\<^verbatim>\<open>install_wrappers ()\<close> call (inside \<^verbatim>\<open>captured\<close>) would see "already
installed" and skip re-installing them, and every capture-form tool would
silently return empty output. Reset here, exactly as MCP_Repl.thy's
own build-time self-test does.\<close>
ML \<open>MCP_Output.reset ()\<close>

end
