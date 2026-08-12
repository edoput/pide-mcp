# Oracles, `sorry`, and proof recording levels

An oracle is a way to make a theorem without proving it. The kernel
records that it happened, so trust is tracked instead of lost. `sorry`
is one of these. This note covers how to find oracles in a theorem,
what the record carries, and the settings that change or destroy the
answer.

## 1. `sorry` is the `skip_proof` oracle

Pure declares it once:

```sml
val (_, make_thm_cterm) =
  Theory.setup_result (Thm.add_oracle (Binding.make ("skip_proof", \<^here>), I));
(* Pure/skip_proof.ML:29 *)
```

`sorry` runs `Skip_Proof.cheat_tac`, which builds the cheated theorem
from the current subgoal:

```sml
fun cheat_tac ctxt = SUBGOAL (fn (goal, i) =>
  let ...
      val assms = Assumption.all_assms_of ctxt;
      val cheat = make_thm thy (Logic.list_implies (map Thm.term_of assms, goal));
  in ... end);
(* Pure/skip_proof.ML:36 *)
```

## 2. Finding oracles in a theorem

```sml
Thm_Deps.all_oracles       : thm list -> Proofterm.oracle list   (* thm_deps.ML:10 *)
Thm_Deps.has_skip_proof    : thm list -> bool                    (* thm_deps.ML:35 *)
Thm_Deps.pretty_thm_oracles: Proof.context -> thm list -> Pretty.T
Thm_Deps.thm_deps          : theory -> thm list -> (Proofterm.thm_id * Thm_Name.T) list
```

`has_skip_proof` is exactly the `sorry` test — it is `all_oracles`
filtered for the `skip_proof` name.

**`all_oracles` is transitive.** It walks the whole dependency closure:

```sml
fun collect (PBody {oracles, thms, ...}) =
  (if null oracles then I else apfst (cons oracles)) #>
  (tap Proofterm.join_thms thms |> fold (fn (i, thm_node) => ... collect body ...));
```

So a lemma proved honestly *from* a sorry'd lemma also reports
`skip_proof`. If you only want the lemmas that were skipped
themselves, see section 6 — that distinction is not settled.

## 3. What an oracle record carries

```sml
type oracle = (string * Position.T) * term option   (* proofterm.ML:34 *)
```

The name, a position, and the proposition the oracle produced. Whether
the position and term are actually filled in depends on the proof
recording level (next section):

```sml
val oracle =
  if Proofterm.oracle_enabled proofs
  then ((name, Position.thread_data ()), SOME prop)
  else ((name, Position.none), NONE);
(* Pure/thm.ML:1212 *)
```

The oracle is always added to the derivation either way, so
`has_skip_proof` works at every level. Only the position and term are
lost.

**Pitfall (2026-08-12): the oracle's term is not the lemma's
statement.** Look again at `cheat_tac` in section 1 — the term is the
*skipped subgoal, with the local assumptions prepended*, captured
wherever `sorry` fired. For `lemma foo: "P" sorry` in an empty context
it happens to equal `P`. Inside a structured proof with `fix`/`assume`,
or for a `sorry` in a nested `have`, it does not. If you want the
statement of the fact, use `Thm.prop_of` on the fact. Treat the oracle
term only as a hint about which subgoal was skipped.

## 4. Proof recording levels

One integer controls all of it, `Proofterm.proofs`, default 6:

```sml
fun zproof_enabled proofs = proofs = 4 orelse proofs = 5 orelse proofs = 6;
fun proof_enabled  proofs = proofs = 2 orelse proofs = 6;
fun oracle_enabled proofs = not (proofs = 0 orelse proofs = 4);
val proofs = Unsynchronized.ref 6;
(* proofterm.ML:492-496 *)
```

Level 3 is illegal and `get_proofs_level` raises on it, as it does for
anything outside 0..6.

| level | oracle position + term | `any_proofs_enabled` | `skip_proofs` can engage |
|-------|------------------------|----------------------|--------------------------|
| 0     | no                     | no                   | yes                      |
| 1     | yes                    | no                   | yes                      |
| 2     | yes                    | yes                  | no                       |
| 4     | no                     | yes                  | no                       |
| 5     | yes                    | yes                  | no                       |
| **6** (default) | **yes**      | yes                  | no                       |

Who sets the level:

```sml
if Options.default_bool "export_standard_proofs" then Proofterm.proofs := 2 else ();
let val proofs = Options.default_int "record_proofs"
in if proofs < 0 then () else Proofterm.proofs := proofs end;
(* Pure/System/isabelle_process.ML:201 *)
```

`etc/options:131` declares `record_proofs : int = -1`, and negative
means "do not override". So a normal session runs at level 6, where
oracles carry both position and term. **You do not need full proof
terms to audit oracles.**

## 5. Settings that make a `sorry` audit meaningless

Two different switches, often confused.

**`skip_proofs`** (system option, `etc/options:135`, default false).
When on, whole proofs are replaced by `sorry`:

```sml
if Goal.skip_proofs_enabled () andalso not (is_relevant state) then
  state |> proof (SOME (Method.sorry_text true, #2 initial'))
(* Pure/Isar/proof.ML:1190 *)
```

Every skipped lemma then reports `skip_proof`, so any tool that lists
sorries reports the entire theory.

It is partly self-neutralizing, which is worth knowing:

```sml
fun skip_proofs_enabled () =
  let val skip = Options.default_bool "skip_proofs" in
    if Proofterm.any_proofs_enabled () andalso skip then
      (warning "Proof terms enabled -- cannot skip proofs"; false)
    else skip
  end;
(* Pure/goal.ML:102 *)
```

At the default level 6, `any_proofs_enabled ()` is true, so
`skip_proofs` is refused with a warning. It only really engages at
levels 0 and 1. **Level 1 is the dangerous one**: proofs are skipped
*and* oracles carry full position information, so fabricated sorries
look exactly like real ones.

**`quick_and_dirty`** (config option, `Pure/goal.ML:245`, default
false) is narrower. It makes `Goal.prove_sorry` cheat rather than
prove, so it affects tools and packages that call it, not every user
proof:

```sml
fun prove_sorry ctxt xs asms prop tac =
  if Config.get ctxt quick_and_dirty then
    prove ctxt xs asms prop (fn _ => ALLGOALS (Skip_Proof.cheat_tac ctxt))
  else ...
```

A tool that reports unproved lemmas should read both settings and
either refuse to answer or stamp the answer with them. Otherwise the
list looks plausible and means nothing.

## 6. Open: telling a direct `sorry` from an inherited one

Since `all_oracles` is transitive, it does not by itself say whether
*this* lemma was skipped or merely rests on one that was.

The plausible discriminator is a theorem's own proof body:
`#oracles (Thm.proof_body_of thm)` for the direct case, `all_oracles`
for the closure. Reading `collect` (section 2) supports it — the body's
own `oracles` field is consed before the recursion into `thms`.

**But this is unverified**, because `fulfill_norm_proof` unions the
oracles of *promises* into that same field:

```sml
val oracles =
  unions_oracles
    (fold (fn (_, PBody {oracles, ...}) => not (null oracles) ? cons oracles) ps [oracles0]);
(* proofterm.ML:1999 *)
```

Whether a cited lemma arrives as a promise (its oracles merged in, so
it reads as direct) or as a separate node (recursed, so it reads as
inherited) depends on parallel proofs and on how the dependency was
used. Settle it with a fixture before relying on it:

```isar
lemma A: "P" sorry
lemma B: "Q" using A by simp
```

If `#oracles (Thm.proof_body_of B_thm)` is empty, the discriminator
works. If not, fall back to walking named dependencies with
`Thm_Deps.thm_deps` and intersecting against the separately computed
set of directly-skipped lemmas.

## Source pointers

- `Pure/skip_proof.ML` — the `skip_proof` oracle, `cheat_tac`.
- `Pure/thm_deps.ML` — `all_oracles`, `has_skip_proof`, `thm_deps`.
- `Pure/thm.ML` — `add_oracle`, and the oracle record at line 1212.
- `Pure/proofterm.ML` — `oracle` type, the level predicates, `PBody`.
- `Pure/goal.ML` — `skip_proofs_enabled`, `quick_and_dirty`,
  `prove_sorry`.
- `Pure/System/isabelle_process.ML` — where the level is set from
  options.
- Worked example: `src/HOL/Examples/Iff_Oracle.thy`.
- Isar-Ref `src/Doc/Isar_Ref/Spec.thy` §"Oracles".
