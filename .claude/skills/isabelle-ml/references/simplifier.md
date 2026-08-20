# The simplifier: rule admission, termination, and how to debug loops

All `src/...` paths are under
`~/.local/share/flatpak/app/de.tum.in.isabelle.Isabelle/current/active/files/src`
(Isabelle2025-2). Line numbers were checked against that tree.

Two files matter: `src/Pure/raw_simplifier.ML` is the rewriting engine and
holds everything below; `src/Pure/simplifier.ML` is the thin user-facing
layer (`simp_tac`, the `simp` attribute, `simproc_setup`).

## 1. What a simp rule actually is

A declared theorem is not stored as-is. `add_simps` (`:635`) runs it through
`extract_rews` -> `mksimps` (object-logic specific: splits conjunctions,
turns `P` into `P == True`, orients HOL `=` into Pure `==`), then through
`mk_rrule` (`:576`), which produces zero or more **rrule** records (`:142`):

```sml
type rrule =
 {thm: thm,         (*the rewrite rule*)
  name: Thm_Name.T, (*name of theorem from which rewrite rule was extracted*)
  lhs: term,        (*the left-hand side*)
  elhs: cterm,      (*the eta-contracted lhs*)
  extra: bool,      (*extra variables outside of elhs*)
  fo: bool,         (*use first-order matching*)
  perm: bool};      (*the rewrite rule is permutative*)
```

`Raw_Simplifier.mk_rrules : Proof.context -> thm -> rrule list` (`:616`) is
exported and the record is **transparent** (the structure uses `:`, not `:>`),
so ML can read `#perm`, `#extra`, `#fo` directly. That is the entry point for
any rule-inspection tooling. `dest_simps`/`dest_ss` only give back names and
theorems, so they are not enough.

Rules live in a discrimination `Net` keyed on `elhs`, inside `simpset`
(`:282`), together with congruence rules, simprocs, solvers, loopers, and the
`term_ord` used for ordered rewriting.

## 2. Permutative rules — the "specific format" for AC

`decomp_simp` (`:537`) computes `perm`:

```sml
val perm =
  var_perm (Thm.term_of elhs, erhs) andalso
  not (Thm.term_of elhs aconv erhs) andalso
  not (is_Var (Thm.term_of elhs));
```

`var_perm (t, u)` (`:533`) holds when `t` and `u` are structurally equal after
treating any two `Var`s as equal, **and** carry the same variable set. So a
rule is permutative exactly when both sides are the same term up to a
permutation of its variables: `?a + ?b == ?b + ?a`, `?b + (?a + ?c) == ?a + (?b + ?c)`.
Associativity is *not* permutative — the two sides have different shapes.

Verified with `mk_rrules`:

| rule | perm |
| --- | --- |
| `add.commute` (`?a + ?b = ?b + ?a`) | true |
| `add.left_commute` (`?b + (?a + ?c) = ?a + (?b + ?c)`) | true |
| `conj_commute` | true |
| `add.assoc` | false |
| `nat_less_le` | false |

Permutative rules would obviously loop if applied freely. They are gated at
application time in `rewritec` (`:1053`):

```sml
if perm andalso is_greater_equal (term_ord (rhs', lhs'))
then (... "Cannot apply permutative rewrite rule" ... "Term does not become smaller:" ...; NONE)
```

This is **ordered rewriting**: the rule fires only when the instance strictly
decreases in `term_ord` (default `Term_Ord.term_ord`, `:353`, replaceable per
context with `set_term_ord`). Termination comes from the well-founded order,
not from the rule. This is why the AC set (`comm`, `assoc`, `left_commute`)
is safe as `simp` rules while an arbitrary rule of the same shape is not:
they are all permutative, so they all pass through the same gate.

Note the short-circuits: `mk_rrule` (`:576`) and `orient_rrule` (`:588`) both
return immediately when `perm` is true, so a permutative rule skips the
extra-variable and orientation machinery entirely.

## 3. What is checked when you declare a rule — and what is not

There are two different admission paths, and this asymmetry is the single most
useful thing to know.

**`default_reorient` (`:196`)** is commented in the source as the *"simple test
for looping rewrite rules and stupid orientations"*. It flags a rule when:

- the rhs has variables not bound by the lhs or premises (`rewrite_rule_extra_vars`, `:173`)
- the lhs head is a `Var`
- the lhs occurs inside the rhs or a premise (`Logic.occs`) — self-embedding
- there are no premises and the rhs is an instance of the lhs (`Pattern.matches`)
- the lhs is a bare `Const` and the rhs is not

**But `default_reorient` is only reached through `orient_rrule` (`:588`), which
is only called from `extract_safe_rrules` (`:613`), whose only call site is
`:1336` — where the simplifier turns a goal's own premises into rewrite rules.**

Rules you declare with `[simp]` go through `add_simps` -> `mk_rrule` (`:576`),
which applies a strictly weaker test, and the comment says so:

```sml
      (*weak test for loops*)
      if rewrite_rule_extra_vars prems lhs rhs orelse is_Var (Thm.term_of elhs)
      then mk_eq_True ctxt (thm, name)
      else rrule_eq_True ctxt thm name lhs elhs rhs thm
```

Even that does not *reject*: it falls back to the `P == True` form. So
`declare foo [simp]` performs no loop check at all beyond extra variables and
a schematic lhs head. Nothing warns about `f ?x = g ?x` together with
`g ?x = f ?x`, or about `h ?x = Suc (h ?x)`.

Two further pointers in the source worth knowing:

- The `FIXME` at `:169` — *"it seems that the conditions on extra variables are
  too liberal if prems are nonempty: does solving the prems really guarantee
  instantiation of all its Vars? Better: a dynamic check each time a rule is
  applied."* Isabelle's own maintainers marking the static/dynamic boundary.
- A **commented-out** check at `:655`-`:685` (`present`, `sym_present`) that
  would refuse a rule whose symmetric version is already in the simpset, with
  the note *"the current test modulo eq_rrule is too weak to be useful and
  needs to be refined"*. That is precisely the 2-cycle case, and it is
  disabled in the shipped simplifier.

Warnings the simplifier does emit (all via `cond_warning`, so only when the
context position is really visible): "Rewrite rule not in simpset" (`:511`),
"Ignoring duplicate rewrite rule" (`:522`), "No simplification procedure ..."
(`:794`, `:804`), "No such looper in simpset" (`:881`), "Extra vars on rhs"
(`:973`, simprocs only).

## 4. Termination: what actually bounds a run

`simp_depth_limit` (`:440`, default 40) sounds like a loop guard. It is not,
for the common case:

- `inc_simp_depth` is called once per `rewrite_cterm` (`:1464`-`:1478`), i.e.
  per **nested** simplifier invocation, not per rewrite step.
- The limit is tested at `:1072`, and that test sits in the `else` branch that
  handles **conditional** rewrites — the branch that recursively invokes the
  prover. Unconditional rewriting (`:1063`) never consults it.

So a loop between two unconditional rules runs at constant depth, forever.
Confirmed empirically: a theory declaring `f x = g x` and `g x = f x` as simp
rules and calling `simp` on `f 0` produces

```
Warning - Unable to increase stack - interrupting thread
*** exception Interrupt_Breakdown raised
```

with no warning at declaration time and no depth-limit message. `simp_trace`
and `simp_debug` (`:457`-`:458`) print steps but do not bound anything; the
only genuine backstops are the ML stack and the wall-clock timeout.

## 5. Dynamic instrumentation — the `trace_ops` hook

`raw_simplifier.ML:907` exposes exactly the hook needed:

```sml
type trace_ops =
 {trace_invoke: {depth: int, cterm: cterm} -> Proof.context -> Proof.context,
  trace_rrule: {unconditional: bool, cterm: cterm, thm: thm, rrule: rrule} ->
    Proof.context -> (Proof.context -> (thm * term) option) -> (thm * term) option,
  trace_simproc: {name: string, cterm: cterm} ->
    Proof.context -> (Proof.context -> thm option) -> thm option};
```

`trace_rrule` wraps every rule application: it sees the redex, the instantiated
rule, the `rrule` record (so `perm`, `name`, `fo` are available), and holds the
continuation. It can inspect the result (`Thm.rhs_of` on the returned equation)
and can **suppress** a step by returning `NONE`. That is enough for loop
detection and for breaking a loop.

Constraints that shape any implementation — each of these cost a debugging
round, so take them as given:

1. **One global slot, last writer wins.** `Trace_Ops` is `Theory_Data` with
   `fun merge (trace_ops, _) = trace_ops` (`:915`-`:921`) and `set_trace_ops =
   Trace_Ops.put` (`:924`). There is no getter in the signature, so you cannot
   read the installed ops and delegate to them — installing replaces.
2. **Pure already occupies it.** `src/Pure/Tools/simplifier_trace.ML:396`
   installs the interactive PIDE trace at theory setup. Its default mode is
   `Disabled` and `interactive = false` (`:45`-`:46`), so replacing it costs
   only the jEdit trace panel — irrelevant headless.
3. **The installing theory must be a proper ancestor.** `Theory_Data` is
   inherited at theory creation, so give the installing theory `imports Main`
   and have clients import *it*. Sideways merges are unreliable: with
   `imports Main Simp_Loop` the installation was lost outright and the looping
   theory ran to stack exhaustion again, and with `imports Simp_Loop Main`
   detection still did not fire, for a reason I did not chase. Instrumenting an
   existing development therefore means editing its imports or rebuilding the
   image — you cannot retrofit the hook onto a prebuilt `Main`.
4. **Context flows in, never out.** `trace_rrule` can hand a modified context
   to its continuation, but cannot return one. A functional log therefore
   cannot accumulate across sibling steps; you need a ref. The simplifier has
   the same problem with its own trace-depth flag and solves it the same way —
   see the `depth: int * bool Unsynchronized.ref` field of `simpset` (`:282`).
5. **A single global ref is wrong.** Isabelle proves in parallel futures, so a
   process-wide log interleaves unrelated rewrite chains and invents cycles
   that never happened (observed as a bogus `disj_not1`/`de_Morgan_conj`
   report). Allocate a fresh ref per top-level invocation in `trace_invoke`
   when `depth <= 1` and store it in `Proof_Data`; every nested step inherits
   the same context and so the same ref.

## 6. Detecting the loop

Two detectors are needed; neither subsumes the other.

**Chain re-entry.** Record each successful step as an edge `lhs --rule--> rhs`.
Step `e2` *follows* `e1` when `lhs(e2) = rhs(e1)`. Starting from the new step's
own lhs, walk back through edges while they chain, and report a cycle if the
new rhs re-enters a term already on that chain.

Starting from the new step's lhs is what makes it sound. Checking only whether
some earlier edge's lhs equals the new rhs gives false positives: the
simplifier legitimately reaches a term it rewrote before at a different
position, and that is progress, not a loop. That mistake is exactly what
produced the `disj_not1` false positive above.

**Self-embedding.** A non-permutative step whose rhs still contains its own
redex (`Term.exists_subterm (fn t => Envir.aeconv (t, lhs)) rhs`) re-fires at
the inner occurrence forever. One observation is enough; no chain is needed.
`h ?x = Suc (h ?x)` is caught here, and chain re-entry misses it because the
term grows and never repeats.

**What both miss: growing loops.** `f ?x = g (g (g (g (g (g ?x)))))` together
with `g ?x = f ?x` never revisits a term, so no chain closes and no redex is
embedded. Only a **step budget** catches it. Budget exhaustion must `raise`,
not veto: returning `NONE` merely fails that one rule, the simplifier tries the
next, and the budget re-trips forever (observed: 100000+ repeated warnings).
Aborting mid-run is safe — the simplifier builds theorems functionally.

**Veto vs. abort.** On a detected cycle, returning `NONE` for the closing step
is a clean loop breaker: the run terminates normally and the diagnosis names
both rules and shows the concrete terms, e.g.

```
Simplifier loop detected, vetoing closing step:
  Loop_Demo.fg:  f 0  ~>  g 0
  Loop_Demo.gf:  g 0  ~>  f 0
```

Measured on this implementation: a 227-theorem corpus (`HOL/Library/Sublist`
and `HOL/Library/Nat_Bijection`, imports rewritten to pull in the hook) plus a
19-lemma AC/arithmetic/list suite ran with **zero false positives** and all
proofs still succeeding. Both corpora assert `null (Simp_Loop.cycles ())` at
the end rather than printing a count, so a veto that fires but leaves the proof
provable by some other rule still fails the run — that is the false-positive
mode the earlier `disj_not1` bug had, and a count nobody reads would hide it.

Cost was below measurement resolution at this size: 12s CPU with the hook and
12s without, three runs each, no wall-clock difference. The same flat-loop
theory that exhausted the ML stack finishes in one second.

Budget headroom: across the whole corpus the largest number of rewrite steps in
a single top-level invocation was **76**, against a default budget of 5000. Get
that number before choosing a default — a budget below the legitimate maximum
aborts valid proofs, and the failure looks like an unrelated error. `Simp_Loop`
keeps a high-water mark (`max_observed`) for exactly this, and the budget is a
config option (`simp_loop_max_steps`) for developments that need more.

## 7. Static analysis — what is and is not possible

Termination of a rewrite system is undecidable in general, and Isabelle does
not attempt it: the shipped admission test is the five-line weak check in
`mk_rrule`, and the one cross-rule check that was written (`sym_present`) is
commented out. So "does adding this rule keep simp terminating" cannot be
answered statically in full.

What *is* worth doing statically, because it is cheap and catches the common
mistakes, is to run the `default_reorient` predicates on rules that never see
them — i.e. anything declared `[simp]` — plus the `extra`/`fo`/`perm` flags
from `mk_rrules`. Guard the orientation predicates with `not perm`: for a
permutative rule the rhs *is* an instance of the lhs by construction, so an
unguarded check reports `add.commute` as a certain loop.

Single-rule screens cannot see a 2-cycle, which needs the rest of the simpset.
Rather than reimplementing matching against the whole rule net, the practical
answer is a **dynamic pre-flight**: freeze the candidate rule's schematics,
add the rule to the current simpset, normalise the rule's own left-hand side
with the loop detector armed, and report what it observes. Cheap, exact about
the culprits, and it reuses the machinery already built.

Take the redex from `mk_rrules`, not from the raw theorem: `mk_rrules` has
already run `mksimps`, so each `rrule` carries a Pure meta-equality, whereas
the declared theorem is still stated with HOL `=` and has no `Pure.eq` to take
apart.

Verdicts from the prototype, showing the division of labour:

| candidate | static screen | pre-flight |
| --- | --- | --- |
| `f ?x = g ?x` (with `g ?x = f ?x` declared) | nothing — needs cross-rule info | names both rules and shows `f x ~> g x ~> f x` |
| `h ?x = Suc (h ?x)` | "lhs occurs in the rhs — certain loop" | confirms, one edge |
| `f ?x = g (g (g (g (g (g ?x)))))` (with `g ?x = f ?x`) | "rhs is much larger than the lhs" | step budget exhausted — growing loop |
| `add.commute` | clean (guarded by `perm`) | reaches a normal form |
| `rev_rev_ident`, `nat_less_le` | clean | reaches a normal form |

## 8. Working code

`simp/` in this repo holds the prototype these findings came from:
`Simp_Loop.thy` (instrumentation and detection), `Simp_Check.thy` (static
screens plus pre-flight), and the demo/validation theories. See `simp/README`
for how to run them.

Useful configuration options while debugging: `simp_trace`, `simp_debug`,
`simp_trace_depth_limit` (default 1 — raise it to see nested invocations),
`simp_depth_limit`.
