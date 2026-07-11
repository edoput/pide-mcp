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

In this repo the distribution lives at
`Isabelle2025-2_linux/Isabelle2025-2`; use its `bin/isabelle` for all
commands below.

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
