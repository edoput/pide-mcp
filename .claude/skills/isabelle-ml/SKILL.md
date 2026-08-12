---
name: isabelle-ml
description: Topic index for Isabelle/ML development — extending Isabelle with ML code (contexts, terms, theorems, tactics, proof methods, attributes, new Isar commands, definitional packages). Use when writing or reading Isabelle/ML code inside theories or Pure. For Scala tools, components, and the ML/Scala bridge use the isabelle-ml-scala skill instead.
---

# Isabelle/ML development — topic index

**Before diving in**: this workflow runs many repeated shell commands
(`isabelle build`, `isabelle console`, `isabelle doc`, etc.) that will
otherwise trigger a permission prompt every single time. Ask the user
whether they'd like to set up tool whitelisting for this project — any
command used over the course of developing the Isabelle MCP tooling is
a candidate. If they agree, invoke the `fewer-permission-prompts` skill
(or `update-config` for one-off additions) to add an allowlist to
`.claude/settings.json` rather than editing it by hand.

All paths below are relative to the bundled distribution
`Isabelle2025-2_linux/Isabelle2025-2` (`$ISABELLE_HOME`).

The authoritative reference is the **Implementation manual**:
`doc/implementation.pdf` (or `bin/isabelle doc implementation`). Its
*sources* are readable theories in `src/Doc/Implementation/*.thy` —
grep those first: they interleave prose with formally checked ML
examples and antiquotation-verified API signatures, so they never rot.
Second reference: the Isar reference manual (`doc/isar-ref.pdf`,
sources `src/Doc/Isar_Ref/*.thy`), especially `Spec.thy` (ML commands,
oracles) and `Outer_Syntax.thy`.

Interactive exploration: `bin/isabelle console` (ML REPL, `-l HOL` for
a HOL heap). Inside a theory, `ML \<open>...\<close>` blocks give instant
feedback in PIDE.

## Topics

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

### Contexts and context data
Manual: `src/Doc/Implementation/Prelim.thy` §"Contexts" — theory vs.
proof context vs. generic context; `Theory_Data`, `Proof_Data`,
`Generic_Data` functors (the *only* sanctioned mutable-looking state);
configuration options.
Sources: `src/Pure/context.ML`, `src/Pure/Isar/proof_context.ML`,
`src/Pure/config.ML`.

**Pitfall — parent merge order (2026-07-13, hit via a user's
codatatype).** `Context.begin_thy` first DROPS imports that are proper
subtheories of other imports (`make_parents`, `src/Pure/context.ML`),
then merges each data slot folding left-to-right from the first
surviving parent (`Theory_Data` functor, same file). Merge functions
that keep one side take the FIRST parent's value — notably the
simplifier's `mk_rews` record (`merge_ss`,
`src/Pure/raw_simplifier.ML`), which carries HOL's `mk_cong`.
Concrete failure: `imports Main "MCP-Tools.MCP_Tools"
"HOL-Library.X"` — `Main` is subsumed by `X` and dropped, the
Pure-based registry becomes the first parent, HOL's cong
preprocessing is lost, and the next `datatype`/`codatatype` raises
`SIMPLIFIER ("Congruence not a meta-equality", [case_cong ...])`.
Rule: a Pure-based theory must never end up as the first parent of a
HOL theory; give users a HOL-anchored wrapper to import instead —
local example `mcp/Tools/HOL/MCP.thy` (`MCP-HOL.MCP`), which imports
`Main` first and documents the mechanism, is exactly that wrapper for
the `mcp_tool`/`mcp_resource` commands.

### Names, bindings, name spaces
Manual: `src/Doc/Implementation/Prelim.thy` §"Names" — basic names,
indexnames, qualified names, `binding`, name spaces.
Sources: `src/Pure/name.ML`, `src/Pure/General/binding.ML`,
`src/Pure/General/name_space.ML`.

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

### Isar integration: proof methods and attributes
Manual: `src/Doc/Implementation/Isar.thy` — the `Proof.state`
machinery, `Method.METHOD`, `Method.setup`, `Attrib.setup`, rule
attributes vs. declaration attributes.
Sources: `src/Pure/Isar/method.ML`, `src/Pure/Isar/attrib.ML`,
`src/Pure/Isar/proof.ML`, `src/Pure/Tools/rule_insts.ML` (method +
attribute examples), `src/Pure/Tools/named_theorems.ML` (a complete
small attribute/data package).
Worked example: `src/Pure/ex/Guess.thy` (a full proof command built on
`Obtain`).

### New Isar commands: outer syntax and the toplevel
Declare keywords in the theory header (`keywords "foo" :: thy_decl`),
then `Outer_Syntax.command`/`local_theory'`/`maintain_command` with a
parser from `Parse`.
Sources: `src/Pure/Isar/outer_syntax.ML`, `src/Pure/Isar/toplevel.ML`
(`Toplevel.keep`, `Toplevel.theory`, ...), `src/Pure/Isar/parse.ML` +
`src/Pure/Isar/parse_spec.ML` (parser combinators for outer syntax),
`src/Pure/Isar/token.ML`, `src/Pure/Isar/args.ML`.
Manual: `src/Doc/Implementation/Integration.thy` (Isar toplevel,
theory loader database); Isar-Ref `Outer_Syntax.thy`.
Worked example: `src/HOL/Examples/Commands.thy` — three commands
(diagnostic, theory-level, local-theory-level) in ~100 lines.
Local worked example: `mcp/Tools/MCP_Tools.thy` — Name_Space-backed
registries in Generic_Data, add/del declaration attribute,
clause-based commands (`mcp_tool`/`mcp_resource`), ML-hatch code
generation, and programmatic command execution, in one theory.

Hard-won facts (2026-07-11, all hit while building `mcp_tool`):
- **Command keywords delimit command spans** and can NEVER appear as
  inner tokens of another command, not even inside `[[...]]` or
  parentheses — the span scanner has no bracket awareness. Two
  consequences: an attribute cannot share a name with a command (the
  simproc attribute vs. `simproc_setup` precedent; our `mcp_tool`
  command forced the attribute to be `mcp_tools`), and a command name
  passed as an *argument* must be a quoted string
  (`mcp_tool "find_consts"`, like Pure's `help "..."`).
- **Every keyword declared in a header must have a command defined by
  theory end** ("Missing outer syntax command(s)" at `end` otherwise).
  You cannot reserve a keyword for later; define a stub that errors.
- **`ML_Context.expression` evaluates a *unit* expression** for its
  context effect; a generated expression that evaluates to a plain
  value (e.g. a `generic -> generic` function) compiles fine and is
  SILENTLY DISCARDED — the registration just doesn't happen. Follow
  `method_setup`'s idiom (src/Pure/Isar/method.ML): generate
  `Theory.local_setup (fn lthy => ...)` around the user source, then
  `Context.proof_map`. Wrap `ML_Syntax.make_binding` output in
  `ML_Syntax.atomic` when splicing it as an argument.
  Use when the Isar command receives ML text from a cartouche: consult
  `references/method-setup-idiom.md` for the full pattern — the Pure
  originals (`Method.method_setup`, `Attrib.attribute_setup`), the
  local `ml_declaration` generalization in `mcp/Tools/MCP_Tools.thy`,
  the splicing rules (user code as tokens via `ML_Lex.read_source`,
  data only through `ML_Syntax.print_*` printers), and a survey of
  every Pure command taking ML text, including the context-data-slot
  variant for extracting a compiled value back out.
- **Programmatic command execution**: `Outer_Syntax.parse_text thy
  (K thy) pos text` + `fold (Toplevel.command_exception false)` over
  `Toplevel.make_state (SOME thy)`. Parse at `Position.line 1`, NEVER
  `Position.none` — none erases all error positions; line 1 gives
  snippet-relative lines (linear: parsing at line N reports N+k).
  Capture output by routing `Private_Output.*_fn` through a
  group-keyed buffer table (see `MCP_Output` in
  `mcp/Tools/MCP_Tools.thy`; originally ir/ml_repl.ML's technique).
- Wrapping *arguments* of an existing command: commands take token
  streams in their own syntax, so a value spliced into an argument
  position must go in VERBATIM (e.g. `find_consts strict: "..."`) —
  wrapping it in a cartouche or quotes changes what the command
  parses (a quoted find_theorems criterion becomes a term pattern,
  and its limit option is `(40)`, not `(limit 40)`). Quote only
  genuinely value-shaped slots (terms → cartouche, strings → escaped
  inner string).

### The theory database (Thy_Info): what it remembers, what it does not
Manual: `src/Doc/Implementation/Integration.thy` (Isar toplevel, theory
loader database).
Sources: `src/Pure/Thy/thy_info.ML` (the database), `src/Pure/context.ML`
(theory values, `Theory_Data`), `src/Pure/global_theory.ML`
(`facts_of`, `dest_thms`), `src/Pure/facts.ML` (fact tables).

Thy_Info is a graph of dependencies, not a history. It is a
`String_Graph` keyed by theory name — one entry per name. Nodes are
theory names, edges are the imports relation. It answers "what does
this theory depend on" and "what depends on this theory". It does not
answer "what did this theory look like yesterday".

A theory value is immutable and self-contained: its own content plus
everything inherited from its parents (name spaces, the facts table,
axioms, defs, all `Theory_Data` slots). It carries no timestamp and no
pointer to any earlier version of itself.

**Reloading discards the old version.** When you reload a theory,
Thy_Info removes the old node first and every theory that depends on
it, then adds the new one. So after you edit and reload a theory there
is exactly one value for that name — the old one, and the loaded state
of everything downstream, is gone.

There is a real history, but only inside one load: while a single file
loads, each command produces a new theory value, and with the
`record_theories` option those per-command states stay reachable
(`Thy_Info.get_theory_segments`, `Thy_Info.get_theory_elements`). That
is history inside one load, not history across edits.

**Practical consequence.** To compare two versions of a theory (what
changed in a file), the prover keeps only one value per name, so it
won't do this for you directly. But two values coexist fine when their
names differ — copy the old file under a different theory name, load
it, and diff the two values with `Facts.dest_static`. See
`references/thy-info.md` for the worked recipe, including the pitfall
where the exclusion test is by name only, so a theorem whose statement
changed but kept its name won't show up as new.

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

### Pretty printing and markup/output
Sources: `src/Pure/General/pretty.ML` (`Pretty.block`/`brk`/`writeln`),
`src/Pure/PIDE/markup.ML`, `src/Pure/Isar/proof_display.ML`.

## Worked examples, smallest first

1. `src/HOL/Examples/ML.thy` — ML in theories, antiquotations.
2. `src/HOL/Examples/Commands.thy` — defining Isar commands.
3. `src/HOL/Examples/Iff_Oracle.thy` — an oracle.
4. `src/Pure/ex/Def.thy` — a definitional package with simproc.
5. `src/Pure/ex/Guess.thy` — a proof command on top of Obtain.
6. `src/Pure/Tools/named_theorems.ML` — attribute + context data.
7. `src/HOL/Tools/typedef.ML` — a production definitional package.

## Building and testing

Use the session-based `\<^assert>` testing idiom, `isabelle console`,
and component packaging documented in the **isabelle-ml-scala** skill —
that skill owns build/test mechanics and the ML/Scala boundary; this
one owns the in-logic ML APIs.
