### Tactics, tacticals, goals
Manual: `src/Doc/Implementation/Tactic.thy` — goal protocol
(`Goal.prove`), resolution/assumption tactics, `resolve_tac` family,
tacticals (`THEN`, `REPEAT`, `SUBGOAL`, ...).
Sources: `src/Pure/tactic.ML`, `src/Pure/tactical.ML`,
`src/Pure/goal.ML`, `src/Pure/Isar/subgoal.ML` (subgoal focus),
`src/Tools/eqsubst.ML` and `src/Tools/induct.ML` as substantial
real-world tactic code.

### Equational reasoning: conversions and the simplifier
Manual: `src/Doc/Implementation/Eq.thy` — equality rules, conversions
(`conv` type), rewriting.
Sources: `src/Pure/conv.ML`, `src/Pure/raw_simplifier.ML`,
`src/Pure/simplifier.ML`; simprocs: see `Simplifier.define_simproc` and
the example simproc in `src/Pure/ex/Def.thy`.

### Structured proof machinery: variables, assumptions, obtain
Manual: `src/Doc/Implementation/Proof.thy` — `Variable.declare_term`,
fixing/exporting, `Assumption`, `Obtain.result`, structured goals
(`SUBPROOF`, `Goal.prove` with fixed variables).
Sources: `src/Pure/variable.ML`, `src/Pure/assumption.ML`,
`src/Pure/Isar/obtain.ML`.
