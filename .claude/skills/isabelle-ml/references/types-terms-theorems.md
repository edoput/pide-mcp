### The kernel: types, terms, theorems
Manual: `src/Doc/Implementation/Logic.thy` — types, terms (`aconv`,
de Bruijn indices, `Envir`), certified entities (`ctyp`/`cterm`),
theorems and primitive inferences, object-level rules, proof terms,
instantiation.
Sources: `src/Pure/type.ML`, `src/Pure/term.ML`, `src/Pure/logic.ML`,
`src/Pure/thm.ML`, `src/Pure/more_thm.ML`, `src/Pure/drule.ML`
(derived rules), `src/Pure/pattern.ML` + `src/Pure/more_unify.ML`
(matching/unification).

### Concrete syntax: reading, printing, checking
Manual: `src/Doc/Implementation/Syntax.thy` — `Syntax.read_term` /
`Syntax.pretty_term` and the parse/check/uncheck/unparse phases.
Sources: `src/Pure/Syntax/` (grammar, AST, translations),
`src/Pure/sign.ML` (signature operations, notation).
Inner-syntax extension (grammar, translations, parse/print functions):
Isar-Ref `src/Doc/Isar_Ref/Inner_Syntax.thy`.

### Pretty printing and markup/output
Sources: `src/Pure/General/pretty.ML` (`Pretty.block`/`brk`/`writeln`),
`src/Pure/PIDE/markup.ML`, `src/Pure/Isar/proof_display.ML`.
