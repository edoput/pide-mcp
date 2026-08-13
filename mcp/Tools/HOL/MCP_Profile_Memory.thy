theory MCP_Profile_Memory
  imports "MCP-HOL.MCP_Repl"
begin

section \<open>Per-command memory / allocation profiler\<close>

text \<open>Experimental profiler (plans/proof_profiler_memory): given a theory
to build on and a block of Isar text, run each command as its own
\<^ML>\<open>Toplevel.command_exception\<close> transition against a FRESH, throwaway
theory value -- never a live \<^ML_structure>\<open>Ir\<close> repl -- sampling live
heap size immediately before and after each transition, forcing a full
GC at each sample point (see below for why). Three empirical discoveries
went into this design, each checked interactively with
\<^verbatim>\<open>isabelle console -l HOL\<close> against this worktree's own scratch heap
BEFORE committing to code -- do not re-derive any of them.

DISCOVERY 1: the obvious guess, calling
\<^verbatim>\<open>PolyML.Statistics.getLocalStats\<close> directly from a theory's own ML
block, does NOT compile here -- "Structure (Statistics) has not been
declared in structure PolyML". \<^file>\<open>~~/src/Pure/ML_Bootstrap.thy\<close>
(loaded once, early, while building Pure) compiles
\<^file>\<open>~~/src/Pure/ML/ml_statistics.ML\<close> against the FULL low-level
\<open>PolyML\<close> structure, then deliberately replaces \<open>PolyML\<close> for every
theory built afterwards (HOL, MCP-HOL, this one included) with a
minimal stub exposing only \<open>pointerEq\<close>/\<open>IntInf\<close>/\<open>context\<close>/\<open>pretty\<close> --
\<open>Statistics\<close> is gone from user-visible \<open>PolyML\<close> everywhere outside
Pure's own bootstrap. What survives is \<^ML_structure>\<open>ML_Statistics\<close>
itself: its \<open>get: unit -> (string * string) list\<close> was compiled while
the full \<open>PolyML.Statistics\<close> was still in scope, so the closure keeps
working after the name is hidden -- this is the actually-reachable
read side.

DISCOVERY 2: the obvious byte-level signal from that interface,
\<open>size_allocation - size_allocation_free\<close> ("bytes currently live in the
nursery"), is USELESS in practice. \<open>size_allocation\<close> is not a fixed
nursery capacity; Poly/ML grows it on demand, and
\<open>size_allocation_free\<close> grows in lockstep with it -- a 2,000,000-element
list allocation was observed to move \<open>size_allocation\<close> from 17,825,792
to 82,837,504 (+65,011,712, matching the list's true byte cost almost
exactly) while \<open>size_allocation_free\<close> moved by nearly the same amount,
leaving their DIFFERENCE close to unchanged. The same problem hits the
next-most-obvious signal, \<open>size_heap\<close> (Poly/ML's total heap size) taken
as a raw before/after delta: it DOES track allocation while the process
still needs to grow its heap, but once the heap is already large enough
to absorb an allocation into existing headroom (true of any real, live
proving session, and even of this worktree's own build-time \<^verbatim>\<open>MCP-HOL-
Tests\<close> session after its first few theories loaded), the same
500,000-element allocation that measured multiple megabytes fresh
measured a flat 0 -- confirmed by instrumenting this very theory's own
test suite before settling on the final design.

DISCOVERY 3, and the one this profiler actually uses: Poly/ML's gauges
only become trustworthy once garbage is actually collected, and a
forced collection is reachable the same way \<^ML_structure>\<open>ML_Statistics\<close>
is -- \<^file>\<open>~~/src/Pure/ML/ml_heap.ML\<close> defines \<^ML>\<open>ML_Heap.full_gc\<close> as
\<open>PolyML.fullGC\<close>, compiled (like \<open>ML_Statistics.get\<close>) while the full
\<open>PolyML\<close> was still visible, and is EXPORTED in \<^verbatim>\<open>ML_HEAP\<close>'s
signature (unlike \<open>ML_Statistics\<close>'s reliance on a closure over an
otherwise-hidden name, this one is a first-class reachable value). So
each sample point here forces \<^ML>\<open>ML_Heap.full_gc\<close> before reading
\<open>size_heap - size_heap_free_last_full_GC\<close> -- both of which, immediately
after a full collection, describe the TRUE live byte count, not a
gauge that can grow or shrink around real data for unrelated reasons.
The signal is therefore the delta in that live-byte figure across one
transition: NET memory retained by the command, not gross allocation
churn (a command that allocates heavily but reclaims almost all of it
reads as cheap here -- an honest limitation, not a bug, and arguably
the more relevant number for "which step risks a memory blow-up AT
SCALE", since retained state is what compounds across a long proof
script while reclaimed churn does not). The cost is two forced full
GCs per profiled command, which is slow for a large heap -- an
accepted trade for an experimental tool that values a real number over
a fast, meaningless one; see plans/proof_profiler_memory's ASSUMPTIONS.
The statistics interface is also PROCESS-WIDE, so genuinely concurrent
activity from other requests could still pollute a reading (forcing a
GC does not isolate this call from other threads' allocations that
race with it) -- a residual honesty caveat, not eliminated by the
forced-GC design.

Construction of the throwaway state mirrors \<^ML>\<open>Ir.init\<close>'s \<open>from_specs\<close>
branch (ir/ir.ML:439-462): \<^ML>\<open>Theory.begin_theory\<close> over the named
theory, then \<^ML>\<open>Toplevel.make_state\<close>. Nothing here touches
\<^verbatim>\<open>Ir.repl_tab\<close> (private to ir.ML) or registers anywhere Isabelle keeps
state, so no repl this tool touches is ever visible to
\<open>repl_list\<close>/\<open>repl_show\<close> and no \<open>Ir\<close> function is called at all -- this
theory is new ML, not a modification of the verbatim-reused ir.ML.\<close>

ML \<open>
signature MCP_PROFILE_MEMORY =
sig
  type row = {index: int, name: string, line: int, delta: int, error: string option}
  val profile_rows: string -> string -> row list
  val format_report: row list -> string
  val profile: string -> string -> string
end;

structure MCP_Profile_Memory: MCP_PROFILE_MEMORY =
struct

type row = {index: int, name: string, line: int, delta: int, error: string option};

(*All ML_Statistics values arrive as strings on this interface;
  Value.parse_int is the same numeric parser the rest of this
  codebase's argument decoding uses (MCP_Repl.thy).*)
fun stat_int props key =
  (case AList.lookup (op =) props key of
    SOME v => Value.parse_int v
  | NONE => 0);

(*force a full GC, THEN read: only right after a collection do
  size_heap and size_heap_free_last_full_GC describe live bytes rather
  than a growable gauge (see the theory header's Discovery 3).*)
fun live_bytes () =
  (ML_Heap.full_gc ();
   let val props = ML_Statistics.get ()
   in stat_int props "size_heap" - stat_int props "size_heap_free_last_full_GC" end);

(*one throwaway theory value, exactly Ir.init's from_specs shape
  (ir/ir.ML:439-462) for a single already-loaded theory spec -- never
  registered anywhere, so it is garbage the instant this call returns.*)
fun fresh_state thy_name =
  let
    val thy = Thy_Info.get_theory thy_name
    val id = "MCP_Profile_Memory_" ^ string_of_int (serial ())
    val thy' = Theory.begin_theory (id, Position.none) [thy]
  in Toplevel.make_state (SOME thy') end;

(*run every transition in order against the fresh state, sampling around
  each one. Error handling (plans/proof_profiler_memory, "error
  handling"): PARTIAL RESULTS, not whole-call failure -- a script that
  blows up on step 7 still tells you steps 1..6's memory profile, which
  is exactly the data an agent chasing a memory blow-up wants. On the
  first failing transition, execution STOPS (later transitions may
  depend on the failed one's state, e.g. a `by simp` after a `lemma`
  that itself failed to parse into a goal) and that row is recorded
  with \<open>error = SOME msg\<close>, \<open>delta = 0\<close> -- not silently dropped, not
  fatal to the whole call.*)
fun profile_rows thy_name text =
  let
    val st0 = fresh_state thy_name
    (*Outer_Syntax.parse_text interleaves a "<ignored>" pseudo-transition
      (a no-op Keep, empirically confirmed via isabelle console -- see
      plans/proof_profiler_memory) for whitespace/comment gaps between
      real commands; it carries no proof content and running it changes
      nothing, so it is dropped here rather than reported as a
      zero-content "command" in a per-command profile.*)
    (*seed with Position.start, not Position.none: Position.line_of
      treats an all-zero line/offset position as invalid, so every row
      would report line 0 downstream (empirically confirmed) -- the
      same fix plans/proof_profiler_delta's own exec_text-alike loop
      needed.*)
    val transitions =
      filter (fn tr => Toplevel.name_of tr <> "<ignored>")
        (Outer_Syntax.parse_text (Toplevel.theory_of st0)
          (fn () => Toplevel.theory_of st0) Position.start text)
    fun step (tr, (st, idx, rows, stopped)) =
      if stopped then (st, idx, rows, stopped)
      else
        let
          val name = Toplevel.name_of tr
          val line = the_default 0 (Position.line_of (Toplevel.pos_of tr))
          (*NB: not named "before"/"after" -- both are predeclared infix
            identifiers in the SML basis (General.before), so using them
            as plain variable names here mis-parses the following `val`*)
          val stat_pre = live_bytes ()
        in
          case Exn.capture_body (fn () => Toplevel.command_exception false tr st) of
            Exn.Res st' =>
              let
                val delta = live_bytes () - stat_pre;
                val row = {index = idx, name = name, line = line, delta = delta,
                  error = NONE} : row
              in (st', idx + 1, row :: rows, false) end
          | Exn.Exn exn =>
              if Exn.is_interrupt exn then Exn.reraise exn
              else
                let
                  val row = {index = idx, name = name, line = line, delta = 0,
                    error = SOME (Runtime.exn_message exn)} : row
                in (st, idx + 1, row :: rows, true) end
        end
    val (_, _, rows_rev, _) = List.foldl step (st0, 0, [], false) transitions
  in rev rows_rev end;

fun commas_int (n: int) =
  let
    val s = Int.toString (Int.abs n)
    val sign = if n < 0 then "-" else ""
    val digits = String.explode s
    val n_digits = length digits
    fun group (i, c) =
      if i > 0 andalso (n_digits - i) mod 3 = 0 then "," ^ String.str c else String.str c
  in sign ^ String.concat (map group (ListPair.zip (0 upto n_digits - 1, digits))) end;

fun format_row {index, name, line, delta, error} =
  let
    val head = "#" ^ string_of_int index ^ "  line " ^ string_of_int line ^ "  " ^ name
  in
    case error of
      SOME msg => head ^ "  ABORTED: " ^ msg
    | NONE => head ^ "  " ^ commas_int delta ^ " bytes retained"
  end;

(*highest allocation first; a row that ABORTED (error <> NONE) carries no
  memory reading at all, so it sorts last regardless of its (unset,
  zero) delta -- it is not "the cheapest command", it is unmeasured.*)
fun format_report rows =
  let
    val (ok, failed) = List.partition (fn ({error, ...}: row) => error = NONE) rows
    val sorted = sort (fn ((r1: row), (r2: row)) => int_ord (#delta r2, #delta r1)) ok
    val total = List.foldl (fn ((r: row), acc) => #delta r + acc) 0 ok
    val n = length rows
    val lines = map format_row sorted @ map format_row failed
    val summary =
      "profiled " ^ string_of_int (length ok) ^ "/" ^ string_of_int n ^
      " command(s), total " ^ commas_int total ^ " bytes retained" ^
      (if null failed then ""
       else " (stopped at command #" ^ string_of_int (#index (hd failed)) ^ ")")
  in if null rows then "no commands in input" else String.concatWith "\n" lines ^ "\n\n" ^ summary end;

fun profile thy_name text = format_report (profile_rows thy_name text);

end;
\<close>

section \<open>MCP tool\<close>

text \<open>NB: the second param is named \<open>isar_text\<close>, not \<open>text\<close> -- \<open>text\<close> is
itself an Isar outer-syntax documentation command (used throughout this
very file), and command keywords delimit SPANS at the outer-syntax
scanning pass, before \<^verbatim>\<open>param_entry\<close>'s inner parser ever runs (same
hazard \<^verbatim>\<open>ptyp_parser\<close>'s own comment documents for the \<open>term\<close>/\<open>typ\<close>
TYPE names, MCP_Tools.thy). Using \<open>text\<close> as a bare param NAME here cuts
the \<open>mcp_tool\<close> command's span short, exactly the same way -- confirmed
empirically (not guessed) while building this theory.\<close>

mcp_tool profile_proof_memory = capture \<open>fn _ => fn args =>
  let
    val thy_name = MCP_Combinators.arg args "theory_name"
    val isar_text = MCP_Combinators.arg args "isar_text"
  in writeln (MCP_Profile_Memory.profile thy_name isar_text) end\<close>
  (description \<open>Run each Isar command in `isar_text` against a fresh, throwaway
    copy of `theory_name` and report per-command NET Poly/ML memory
    retained, highest first -- a proxy for which step in a proof risks
    a memory blow-up at scale. Each sample point forces a full garbage
    collection then reads live heap bytes, so the number is bytes of
    memory still reachable after the command minus bytes reachable
    before it -- retained state, not gross allocation: a command that
    allocates heavily but reclaims nearly all of it reads as cheap.
    This is slower than an unprofiled run (two forced GCs per command)
    but gives a real, not approximated, retained-bytes figure. Never
    touches any repl_step REPL: this runs its own isolated, disposable
    theory state. If a command fails, profiling stops there and the
    report shows every command up to and including the failure (marked
    ABORTED), not just an error.\<close>)
  (params
    theory_name :: string \<open>already-loaded theory name to build the throwaway
      state on, e.g. HOL.Main or a session-qualified name -- same lookup
      as repl_init's theories, via Thy_Info.get_theory\<close>
    isar_text :: source \<open>Isar source text: one or more commands
      (lemma/proof/apply/by/... or plain theory-level commands), parsed
      and run in order\<close>)
  (annotations read_only)

end
