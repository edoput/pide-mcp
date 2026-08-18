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

## Where the sources are (read this first)

All `src/...` paths below resolve under the **flatpak** distribution's
source tree:

    export S=~/.local/share/flatpak/app/de.tum.in.isabelle.Isabelle/current/active/files/src

`current/active` is a stable symlink — you never need the build hash.
Grep as `$S/Pure/...`, `$S/Doc/...`, `$S/HOL/...`.

This is always available, even in a git worktree. 

The authoritative reference is the **Implementation manual**:

    flatpak run --command=isabelle de.tum.in.isabelle.Isabelle doc implementation

Its *sources* are readable theories in `$S/Doc/Implementation/*.thy` — grep
those first: they interleave prose with formally checked ML examples and
antiquotation-verified API signatures, so they never rot. Second reference:
the Isar reference manual (`... doc isar-ref`, sources
`$S/Doc/Isar_Ref/*.thy`), especially `Spec.thy` (ML commands, oracles) and
`Outer_Syntax.thy`.

Interactive exploration in the ML REPL, append the `-l HOL` for a HOL heap:

    flatpak run --command=isabelle de.tum.in.isabelle.Isabelle console

Inside a theory, `ML \<open>...\<close>` blocks give instant feedback in PIDE.

## References

When writing Isabelle/ML, consult `references/general.md` for:

- the basics of the Isabelle/ML environment:
  + message channels
  + exceptions
  + symbol strings
  + thread-safe programming
- using Isabelle/ML from within a theory
- worked out examples

When you need mutable data, consult `references/mutable-data.md` for:

- theory context, proof context, generic context types and when to use them
- pitfalls of mutable data and theory import chaning between HOL and Pure

When you need to introduce names to the theory context, consult `references/names-and-positions.md` for:

- manipulating bindings in a theory
- source positions of bindings in a theory

When you need to work with types, terms, and theorems as values read `references/types-terms-theorems.md` for:

- index and sources for types, terms, theorems
- their concrete syntax: reading, printing, checking
- pretty printing and markup of terms

When you need to work with proofs read `references/proofs.md` for:

- tactics, tacticals, and goals
- equational reasoning through conversions and the simplifier
- structured proofs machinery

When you need to use Isar as a framework to develop commands, consult `references/isar.md` for:

- using Isar to introduce methods and attributes
- extending Isar with your own commands 

When you need to work with the prover state, consult `references/thy-info.md` for:

- the theory database

When you want to develop a definitional package, consult the `references/local-theory-and-definitional-packages.md`.

When you need to work with oracles, consult the `references/oracles.md` for:

- the sorry command implementation
- how oracles are recorded in a proof

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
