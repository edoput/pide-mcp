### Oracles, `sorry`, and proof recording levels
Worked example: `src/HOL/Examples/Iff_Oracle.thy`.
Sources: `Thm.add_oracle` in `src/Pure/thm.ML`; `src/Pure/skip_proof.ML`
(the `skip_proof` oracle behind `sorry`); `src/Pure/thm_deps.ML`
(finding oracles in a theorem); `src/Pure/proofterm.ML` (the oracle
record and the recording levels); `src/Pure/goal.ML` (`skip_proofs`,
`quick_and_dirty`). Isar-Ref `Spec.thy` §"Oracles".

An oracle makes a theorem without proving it, and the kernel records
that it happened. `sorry` is one: it is the `skip_proof` oracle
(`skip_proof.ML:29`). To find them:

```
Thm_Deps.all_oracles    : thm list -> Proofterm.oracle list
Thm_Deps.has_skip_proof : thm list -> bool     (* exactly the sorry test *)
```

Three facts that decide whether an audit built on these means anything:

- **`all_oracles` is transitive.** It walks the whole dependency
  closure, so a lemma proved honestly *from* a sorry'd lemma also
  reports `skip_proof`. Telling a direct `sorry` from an inherited one
  is not settled — see the reference.
- **Oracles are recorded at normal settings.** The level is
  `Proofterm.proofs`, default 6, and `record_proofs` is `-1` ("do not
  override"). At level 6 each oracle carries a position and the
  proposition. You do not need full proof terms to audit oracles.
- **`skip_proofs` and `quick_and_dirty` can turn every lemma into a
  `sorry`.** A tool that lists unproved lemmas must read those settings
  and refuse or stamp its answer, or the list is silently noise.
  `skip_proofs` is partly self-neutralizing — at the default level it
  is refused with a warning (`goal.ML:102`) — but it does engage at
  proof levels 0 and 1.

**Pitfall (2026-08-12): the oracle's term is not the lemma's
statement.** `cheat_tac` cheats the *subgoal with local assumptions
prepended*, at the point `sorry` fired. Use `Thm.prop_of` on the fact
for the statement.

See `references/oracles-and-sorry.md` for the level table, which
settings engage where, and the fixture that settles the direct-vs-
inherited question.
