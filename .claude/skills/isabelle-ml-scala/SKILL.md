---
name: isabelle-ml-scala
description: Working with the Isabelle/ML and Isabelle/Scala programming environments — developing, building, and testing Isabelle tools and system components, and making ML and Scala talk to each other. Use when developing Isabelle/Scala tools, Isabelle components, or code that crosses the ML/Scala boundary.
---

# Isabelle/ML and Isabelle/Scala development

**Before diving in**: this workflow runs many repeated shell commands
(`isabelle build`, `isabelle scala_build`, `isabelle components`,
`isabelle console`, etc.) that will otherwise trigger a permission
prompt every single time. Ask the user whether they'd like to set up
tool whitelisting for this project — any command used over the course
of developing the Isabelle MCP tooling is a candidate. If they agree,
invoke the `fewer-permission-prompts` skill (or `update-config` for
one-off additions) to add an allowlist to `.claude/settings.json`
rather than editing it by hand.

Isabelle has two implementation languages with distinct roles:

- **Isabelle/ML** is for *mathematics*: tools within the logic (proofs,
  tactics, domain-specific formal languages). Runs inside the prover
  process (Poly/ML). Reference: `isabelle doc implementation`.
- **Isabelle/Scala** is for *physics*: connecting to the world of systems
  and services (editors, servers, command-line tools). Runs on the JVM.
  Reference: `isabelle doc system`, chapter "Isabelle/Scala systems
  programming" (source: `src/Doc/System/Scala.thy`).

In this repo the bundled distribution at
`Isabelle2025-2_linux/Isabelle2025-2` is **reference sources only** —
never execute its `bin/isabelle`. Run every Isabelle command through the
flatpak:

```
flatpak run --command=isabelle de.tum.in.isabelle.Isabelle <args>
```

The two installs ship different Poly/ML binaries and share
`$ISABELLE_HOME_USER/heaps`, and a root session's build digest is the
SHA1 of the `poly` binary, so alternating between them invalidates Pure
and forces a full Pure → HOL rebuild every time. Where the reference
files below write `isabelle <cmd>`, that names the command — always
invoke it through the flatpak above.

## References

When developing isabelle components components, consult `references/components.md` for:

- templates
- structure of a component on disk
- components registration

When developing isabelle components in scala, consult `references/scala-components.md` for:

- example code for command line tools
- how to build a scala project in the isabelle environment

When developing isabelle components in scala, consult `references/scala-dependencies.md` for:

- using third party dependencies
- vendoring third party dependencies

When developing isabelle component in scala that interop with ML, consult `references/interop.md` for:

- calling scala functions from ML
- calling ML functions from scala

When text crosses between ML and scala — terms, proof states, theory
source, anything carrying `\<Longrightarrow>` or a cartouche — consult
`references/symbol-recoding.md` for:

- the ML-speaks-notation / scala-speaks-unicode convention
- which channels recode for you and which do not (the protocol channel
  recodes in NEITHER direction — the usual trap)
- the `Symbol.decode` / `encode` / `*_yxml` api, and the `recode`
  parameter on the yxml serialiser
- which parts of the distribution already recode, and where

When testing isabelle component in scala and ML, consult `references/testing.md` for:

- unit testing scala code
- test ML code in theories through assertions
- executing ML snippets during the isabelle bootstrap
- executing ML snippets through the isabelle ml repl
- executing scala snippets through the isabelle scala repl
- interop testing

## Key sources in the distribution

- `src/Doc/System/Scala.thy` — the authoritative chapter.
- `src/Tools/Demo/` — complete example component.
- `src/Tools/Setup/src/Build.java` — `build.props` semantics
  (`requirement_paths`: `env:` vs component-relative paths);
  `lib/scripts/getfunctions` — the `classpath` shell function;
  `contrib/postgresql-*/etc/settings` — jar-only component example.
- `src/Pure/System/isabelle_tool.scala` — tool discovery (external +
  internal via services).
- `src/Pure/System/scala.scala`, `src/Pure/System/scala.ML` — the
  ML↔Scala function protocol, both sides.
- `src/Pure/PIDE/protocol_command.ML`, `src/Pure/PIDE/session.scala`,
  `src/Pure/PIDE/headless.scala` — PIDE protocol plumbing.
