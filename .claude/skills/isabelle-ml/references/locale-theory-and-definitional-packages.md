### Local theory and definitional packages
Manual: `src/Doc/Implementation/Local_Theory.thy` — `local_theory`
type, `Local_Theory.define`/`note`, morphisms and declarations.
Sources: `src/Pure/Isar/local_theory.ML`,
`src/Pure/Isar/specification.ML` (the standard `definition`/`theorem`
wrappers — model your package's interface on these),
`src/Pure/Isar/generic_target.ML`, `src/Pure/morphism.ML`,
`src/Pure/Isar/typedecl.ML` (small complete package).
Worked example: `src/Pure/ex/Def.thy` — a complete definitional
package (~150 lines): parser, `def`/`def_cmd` convention (internal
vs. string-input entry points), context data, simproc.
Bigger real packages: `src/HOL/Tools/typedef.ML`,
`src/HOL/Tools/inductive.ML`.
