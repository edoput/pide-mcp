theory MCP_Repl
  imports Main "MCP-Tools.MCP_Tools"
begin

section \<open>I/R proving engine\<close>

text \<open>ir.ML is a symlink to ../../../ir/ir.ML — the engine has a single
source of truth and is reused verbatim (MIT, see its copyright header).\<close>

ML_file "ir.ML"

text \<open>Capture this theory as Ir's own reference point for later
ML_Compiler.eval-based name resolution (sledgehammer's deferred call):
its ML environment has both Sledgehammer (inherited from Main) and Ir
itself. Must run here, right after the \<open>ML_file "ir.ML"\<close> above, so the
captured theory value already includes Ir -- see Ir.the_self_theory.\<close>
ML \<open>Ir.set_self_theory \<^theory>\<close>

section \<open>Output routing and dispatcher\<close>

text \<open>Ir reports through \<^ML>\<open>writeln\<close> and friends. Each request runs in a
fresh \<^ML_type>\<open>Future.group\<close> registered together with an output buffer in
\<^ML_structure>\<open>MCP_Output\<close>'s routing table (MCP_Tools.thy — the substrate
moved there for the tool runner, 2026-07-11; the technique is
ir/ml_repl.ML's): wrappers append to the buffer of the group whose
descendants they run in and fall through to the original functions
everywhere else.

The dispatcher is a closed fname/args table over \<^ML_structure>\<open>Ir\<close> — no
\<^ML>\<open>ML_Compiler.eval\<close>, so no ML injection through mcp tools. Arguments are
named (see the spec's "argument encoding"): an association list of
(key, value) string pairs, list-valued arguments as repeated keys, decoded
from one yxml chunk by \<open>decode_args\<close>. Each fname owns a closed key set —
missing required key, spurious key, or an unparsable value all take the
error path with a message naming the key. \<open>run\<close> is the pure, unit-testable
surface: fname and args in, (status, output) out; the async protocol
command below is a thin wrapper around \<open>fork_run\<close>.\<close>

ML \<open>
signature MCP_REPL =
sig
  val decode_args: string -> (string * string) list
  val dispatch: string -> (string * string) list -> unit
  val fork_run: string -> (string * string) list -> (string * string) future
  val run: string -> (string * string) list -> string * string
  val reset: unit -> unit
  val set_self_theory: theory -> unit
end;

structure MCP_Repl: MCP_REPL =
struct

(*the routing substrate lives in MCP_Output (MCP_Tools.thy); reset after
  build-time use (the self-test below) so no stale "wrapped" flag persists
  into the heap — see MCP_Output.reset*)
val reset = MCP_Output.reset;

(* find_theorems context promotion (plans/find_theorems, "context
   promotion"): the default context (neither repl nor theory given) is
   "the base image's startup theory" -- the same notion ir.ML's own
   sledgehammer already relies on (Ir.the_self_theory), captured there
   for a different reason (an eval needs Ir's OWN ML environment) and
   not exported by ir.ML's signature (verbatim reuse, Amazon-header
   constraint). So MCP_Repl captures its own copy the same way: set
   below, right after this structure closes, mirroring the file-level
   \<open>Ir.set_self_theory \<^theory>\<close> call. MCP_Repl imports Main, so this
   theory is a strict descendant of the whole base image -- its global
   context sees every image fact. *)
val self_theory: theory option Synchronized.var = Synchronized.var "MCP_Repl.self_theory" NONE;
fun set_self_theory thy = Synchronized.change self_theory (K (SOME thy));
fun the_self_theory () =
  (case Synchronized.value self_theory of
    SOME thy => thy
  | NONE => error "MCP_Repl.self_theory not set (see the set_self_theory call below this structure)");


(* isabelle://theory/{name}/entities (spec's "concrete resources"):
   entities *defined* in a theory, filtered by Name_Space.theory_name --
   this is new ML code, not part of ir.ML (which stays a verbatim reuse,
   see the file-level comment above), because ir.ML has no name-space
   support at all. Image-tier only for now: Name_Space.theory_name reads
   per-entry bookkeeping baked into the theory VALUE at declaration
   time, which the heap serializes, so Thy_Info.get_theory thy_name
   resolving at all is the only precondition; no Headless/PIDE snapshot
   needed. NOTE: get_theory is an exact lookup against Thy_Info's keys,
   which mix qualified and unqualified long names ("HOL.Wellfounded"
   but plain "Main") -- the scala side (mcp_session.scala's
   image_theory) normalizes the client's spelling to the canonical key
   before it crosses the bridge; thy_name here is that resolved key. *)
fun entities thy_name =
  let
    val thy = Thy_Info.get_theory thy_name
    val target = Context.theory_long_name thy
    fun collect kind space =
      Name_Space.get_names space
      |> map_filter (fn name =>
           if Name_Space.theory_name {long = true} space name = target
           then
             let val {pos, ...} = Name_Space.the_entry space name
             in SOME (kind, name, the_default 0 (Position.line_of pos)) end
           else NONE)
    val all =
      collect "class" (Sign.class_space thy) @
      collect "type" (Sign.type_space thy) @
      collect "const" (Sign.const_space thy) @
      collect "fact" (Global_Theory.fact_space thy) @
      collect "locale" (Locale.locale_space thy)
    val sorted = sort (fn ((_, _, l1), (_, _, l2)) => int_ord (l1, l2)) all
    val _ = writeln ("   kind     line  name")
    val _ = writeln ("--------  ------  " ^ String.implode (List.tabulate (40, K #"-")))
  in
    List.app (fn (kind, name, line) =>
      writeln (StringCvt.padLeft #" " 8 kind ^ "  " ^
               StringCvt.padLeft #" " 6 (string_of_int line) ^ "  " ^ name)) sorted
  end;


(* find_theorems context promotion (plans/find_theorems, "context
   promotion"): a repl-free counterpart to Ir.find_theorems (which stays
   verbatim in ir.ML -- new ML lives here instead), searching a THEORY's
   global context rather than a repl's goal-aware proof state. No goal is
   available, so goal-based criteria (intro/elim/dest/solves) still fail
   -- Find_Theorems.find_theorems_cmd itself raises "Current goal
   required for ... search criterion" in that case; the wrapper below
   only appends a pointer at a repl, reusing ir.ML's own tally/pretty
   formatting shape so theory- and repl-context results read alike. *)
fun find_theorems_ctxt ctxt max_results query =
  let
    val criteria = Find_Theorems.read_query Position.none query
    val limit = if max_results > 0 then SOME max_results else NONE
    val (opt_found, theorems) =
      Find_Theorems.find_theorems_cmd ctxt NONE limit false criteria
        handle ERROR msg =>
          error (if String.isSubstring "goal required" msg
                 then msg ^ " -- no goal: use a repl mid-proof"
                 else msg)
    val returned = length theorems
    val tally =
      (case opt_found of
        NONE => "displaying " ^ string_of_int returned ^ " theorem(s)"
      | SOME found =>
          "found " ^ string_of_int found ^ " theorem(s)" ^
          (if returned < found then " (" ^ string_of_int returned ^ " displayed)" else ""))
    val lines = map (fn t => Pretty.string_of (Find_Theorems.pretty_thm ctxt t)) theorems
  in writeln (tally ^ ":\n" ^ String.concatWith "\n" (rev lines)) end;

(*thy_name is already the scala-resolved canonical Thy_Info key -- same
  spelling contract as entities' thy_name above*)
fun find_theorems_theory thy_name max_results query =
  find_theorems_ctxt (Proof_Context.init_global (Thy_Info.get_theory thy_name)) max_results query;


(* dispatcher: closed table over Ir, named arguments *)

(*one yxml chunk holding an association list of (key, value) string pairs —
  the mirror of XML.Encode.list (XML.Encode.pair string string) on the
  Scala side; list-valued arguments arrive as repeated keys*)
val decode_args =
  let open XML.Decode in list (pair string string) end o YXML.parse_body;

fun dispatch fname args =
  let
    fun err msg =
      error ("MCP.ir: " ^ msg ^ " for " ^ quote fname ^
        (if null args then ""
         else " (arguments: " ^ commas (map (fn (k, v) => k ^ "=" ^ quote v) args) ^ ")"));
    fun get k =
      (case AList.lookup (op =) args k of
        SOME v => v
      | NONE => err ("missing argument " ^ quote k));
    fun get_all k = map_filter (fn (k', v) => if k' = k then SOME v else NONE) args;
    fun int k v =
      Value.parse_int v
        handle Fail _ => err ("bad integer " ^ quote v ^ " for argument " ^ quote k);
    fun get_int k = int k (get k);
    fun get_int_default k d =
      (case AList.lookup (op =) args k of SOME v => int k v | NONE => d);
    fun keys ks =
      (case distinct (op =) (filter_out (member (op =) ks) (map fst args)) of
        [] => ()
      | extra => err ("unknown argument(s) " ^ commas_quote extra));
  in
    (case fname of
      "init" => (keys ["repl", "theories"]; Ir.init (get "repl") (get_all "theories"))
    | "init_from_document" =>
        (keys ["repl", "node_name", "command_id"];
         Ir.init_from_document (get "repl") (get "node_name") (get_int "command_id"))
    | "fork" =>
        (keys ["repl", "new_repl", "state_idx"];
         Ir.fork (get "repl") (get "new_repl") (get_int "state_idx"))
    | "step" => (keys ["repl", "isar_text"]; Ir.step (get "repl") (get "isar_text"))
    | "show" => (keys ["repl"]; Ir.show (get "repl"))
    | "state" => (keys ["repl", "state_idx"]; Ir.state (get "repl") (get_int "state_idx"))
    | "text" => (keys ["repl"]; Ir.text (get "repl"))
    | "edit" =>
        (keys ["repl", "idx", "isar_text"];
         Ir.edit (get "repl") (get_int "idx") (get "isar_text"))
    | "replay" => (keys ["repl"]; Ir.replay (get "repl"))
    | "truncate" => (keys ["repl", "idx"]; Ir.truncate (get "repl") (get_int "idx"))
    | "back" => (keys ["repl"]; Ir.back (get "repl"))
    | "merge" => (keys ["repl"]; Ir.merge (get "repl"))
    | "pin" => (keys ["repl"]; Ir.pin (get "repl"))
    | "unpin" => (keys ["repl"]; Ir.unpin (get "repl"))
    | "rebase" => (keys ["repl"]; Ir.rebase (get "repl"))
    | "remove" => (keys ["repl"]; Ir.remove (get "repl"))
    | "repls" => (keys []; Ir.repls ())
    | "theories" => (keys []; Ir.theories ())
    | "load_theory" => (keys ["theory_name"]; Ir.load_theory (get "theory_name"))
    | "source" =>
        (keys ["theory_name", "start", "stop"];
         Ir.source (get "theory_name") (get_int "start") (get_int "stop"))
    | "source_map" =>
        (keys ["theory_name", "start", "stop"];
         Ir.source_map (get "theory_name") (get_int "start") (get_int "stop"))
    | "sledgehammer" =>
        (keys ["repl", "timeout_secs"];
         Ir.sledgehammer (get "repl") (get_int_default "timeout_secs" 15))
    | "find_theorems" =>
        (keys ["repl", "theory", "query", "max_results"];
         let val max_results = get_int_default "max_results" 40
             val query = get "query"
         in
           (case AList.lookup (op =) args "theory" of
             SOME thy_name => find_theorems_theory thy_name max_results query
           | NONE =>
               (case AList.lookup (op =) args "repl" of
                 SOME repl => Ir.find_theorems repl max_results query
               | NONE =>
                   find_theorems_ctxt (Proof_Context.init_global (the_self_theory ()))
                     max_results query))
         end)
    | "timeout" => (keys ["repl", "secs"]; Ir.timeout (get "repl") (get_int "secs"))
    | "entities" => (keys ["theory_name"]; entities (get "theory_name"))
    | _ => error ("MCP.ir: unknown function " ^ quote fname))
  end;


(* per-request evaluation: fresh group, buffered output *)

(*below interactive priority, as in ir/ml_repl.ML*)
val ir_pri = ~1;

fun fork_run fname args =
  let
    val _ = MCP_Output.install_wrappers ();
    val group = Future.new_group NONE;
    val finish = MCP_Output.register group;
  in
    (singleton o Future.forks)
      {name = "MCP.ir." ^ fname, group = SOME group, deps = [], pri = ir_pri, interrupts = true}
      (fn () =>
        (case Exn.capture_body (fn () =>
            Print_Mode.with_modes [Print_Mode.PIDE] (fn () => dispatch fname args) ()) of
          Exn.Res () => ("ok", finish ())
        | Exn.Exn exn =>
            if Exn.is_interrupt exn then (finish (); Exn.reraise exn)
            else
              let
                val output = finish ();
                val msg = Runtime.exn_message exn;
              in ("error", if output = "" then msg else output ^ "\n" ^ msg) end))
  end;

fun run fname args = Future.join (fork_run fname args);

end;
\<close>

text \<open>Capture MCP_Repl's own reference point for find_theorems' default
context (neither repl nor theory given), right after the structure closes
-- same positioning and rationale as \<open>Ir.set_self_theory \<^theory>\<close> above.\<close>
ML \<open>MCP_Repl.set_self_theory \<^theory>\<close>

section \<open>Async protocol command for Isabelle/Scala\<close>

text \<open>Unlike the synchronous \<open>MCP.run_tool\<close>, evaluation is forked so the ML
protocol loop stays responsive (a 30s sledgehammer must not stall the
session); the reply is a dependent future so nothing ever blocks here. The
Scala side resolves its pending promise by id on \<open>MCP.ir_result\<close> — same
pattern as \<open>MCP.run_tool_result\<close>. Output is YXML (PIDE print mode); Scala
strips or interprets the markup.\<close>

ML \<open>
val _ =
  Protocol_Command.define "MCP.ir"
    (fn [id, fname, args_yxml] =>
      let
        val result = MCP_Repl.fork_run fname (MCP_Repl.decode_args args_yxml);
        val _ =
          (singleton o Future.forks)
            {name = "MCP.ir_result", group = NONE,
             deps = [Future.task_of result], pri = ~1, interrupts = false}
            (fn () =>
              let
                val (status, output) =
                  (case Future.join_result result of
                    Exn.Res res => res
                  | Exn.Exn exn => ("error", Runtime.exn_message exn));
              in
                Output.protocol_message
                  [Markup.function "MCP.ir_result", ("id", id), ("status", status)]
                  [[XML.Text output]]
              end);
      in () end);
\<close>

section \<open>Self-test\<close>

text \<open>Smoke test at build time: the session fails to build iff the bridge
is broken. The full case list lives in session MCP-HOL-Tests (see the spec's
"testing" section); this regression stays here because it exercises exactly
what the server needs from this heap.\<close>

ML \<open>
local
  val plain = XML.content_of o YXML.parse_body;
  (*image theories are registered under their long name, e.g. "HOL.Main"*)
  val main =
    the_default "Main"
      (find_first (fn n => n = "Main" orelse String.isSuffix ".Main" n) (Thy_Info.get_names ()));
  val (s1, o1) = MCP_Repl.run "init" [("repl", "T"), ("theories", main)];
  val (s2, o2) = MCP_Repl.run "step" [("repl", "T"), ("isar_text", "lemma True")];
  val (s3, _) = MCP_Repl.run "step" [("repl", "T"), ("isar_text", "by simp")];
  val (s4, o4) = MCP_Repl.run "state" [("repl", "T"), ("state_idx", "-1")];
  val (s5, _) = MCP_Repl.run "remove" [("repl", "T")];
  val (s6, o6) = MCP_Repl.run "no_such_function" [];
  (*named-args error paths: spurious key, missing required key, bad value —
    all typed status errors naming the offender, never uncaught exceptions*)
  val (s7, o7) = MCP_Repl.run "repls" [("spurious", "x")];
  val (s8, o8) = MCP_Repl.run "state" [("repl", "T")];
  val (s9, o9) = MCP_Repl.run "state" [("repl", "T"), ("state_idx", "banana")];
  (*wire decode is the mirror of the Scala encoder (same XML.Encode)*)
  val wire_args =
    [("repl", "T"), ("theories", "HOL.Main"), ("theories", "HOL-Library.Multiset"),
     ("isar_text", "lemma \"x + y = y + (x::nat)\"\n  by simp")];
  val encoded =
    YXML.string_of_body (let open XML.Encode in list (pair string string) end wire_args);
in
val _ = \<^assert> (s1 = "ok" andalso String.isSubstring "Created REPL" (plain o1));
val _ = \<^assert> (s2 = "ok" andalso String.isSubstring "True" (plain o2));
val _ = \<^assert> (s3 = "ok");
(*proof complete: "by simp" closed the goal, latest state is theory mode*)
val _ = \<^assert> (s4 = "ok" andalso not (String.isSubstring "goal" (plain o4)));
val _ = \<^assert> (s5 = "ok");
val _ = \<^assert> (s6 = "error" andalso String.isSubstring "no_such_function" (plain o6));
val _ = \<^assert> (s7 = "error" andalso String.isSubstring "spurious" (plain o7));
val _ = \<^assert> (s8 = "error" andalso String.isSubstring "state_idx" (plain o8));
val _ = \<^assert> (s9 = "error" andalso String.isSubstring "banana" (plain o9));
val _ = \<^assert> (MCP_Repl.decode_args encoded = wire_args);
val _ = \<^assert> (MCP_Repl.decode_args "" = []);
end;

(*drop build-time wrapper state from the heap — see MCP_Repl.reset*)
MCP_Repl.reset ();
\<close>

end
