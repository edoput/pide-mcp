### ML language, style, and infrastructure
Manual: `src/Doc/Implementation/ML.thy` (chapter "Isabelle/ML") —
style/orthography, embedding ML into Isar (`ML`, `ML_file`, `setup`,
`declaration`), canonical argument order, message channels
(`writeln`/`warning`/`error`), exceptions, symbol strings, basic data
types, thread-safe programming, managed evaluation (futures/lazy).
Sources: `src/Pure/library.ML` (the base combinators: `|>`, `fold`,
etc.), `src/Pure/General/` (basic modules), `src/Pure/ML/`
(compiler/environment), `src/Pure/Concurrent/` (futures, synchronized
variables, par_list).
Worked example: `src/HOL/Examples/ML.thy` (ML basics inside a theory,
including a toy proof-producing function and antiquotation demos).

### ML antiquotations (`\<^term>`, `\<^const_name>`, `\<^instantiate>`, ...)
The compile-time-checked way to refer to logical entities from ML.
Definitions: `src/Pure/ML/ml_antiquotations.ML` (the standard set),
`src/Pure/ML/ml_thms.ML` (`\<^lemma>`, fact antiquotations),
`src/Pure/ML/ml_instantiate.ML` (`\<^instantiate>`),
`src/Pure/ML/ml_antiquotation.ML` (how to define your own).
Documented throughout the Implementation manual next to each topic.
