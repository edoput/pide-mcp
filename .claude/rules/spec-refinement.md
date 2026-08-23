---
paths:
  - "spec"
  - "plans/*"
  - "mcp/src/**"
  - "mcp/Tools/**"
  - "mcp_test/src/**"
  - "ir/**"
---
# When implementation finds the spec wrong, stop

The spec is written before implementation, so implementation is where
the spec's mistakes are found. A refinement records one of those. Since
2026-08-23 it is an **interrupt, not a queue entry**.

The previous convention let a plan write `spec refinement [OPEN]: ...`
and keep going, on the theory that the spec edit had to wait for its own
commit. That was a misreading of the commit discipline, and it produced
12 open refinements and zero folded ones. See `plans/README`.

## The line

**Stop** when the spec states something **false**, or **contradicts**
what the implementation must do:

- the spec annotates `repl_*` as mutating; `repl_list` is read-only;
- the spec promises `not_yet_backed_uri` where the tier resolver has to
  answer instead.

**Record and continue** when the spec is merely **silent**. Silence is a
gap the plan may fill on its own authority. It is not a conflict, and
interrupting for it will only teach the user to wave you through.

## The loop

1. Write `spec refinement [BLOCKING]: <what the spec says, what is
   actually true>` in the plan, and **stop implementing that thread**.
2. Surface the conflict to the user with `AskUserQuestion` — state what
   the spec says, what the implementation needs, and the options. Do
   everything else in the task that does not depend on the answer while
   waiting.
3. The user decides.
4. Commit the correction to `spec` + `CHANGELOG` on its own.
5. Amend the plan: the marker becomes `[FOLDED <date>]`.
6. Continue implementing.

The implementation staying dirty in the working tree across steps 4 and
5 is intended — commit with an explicit pathspec
(`.claude/rules/commits.md`).

If the user says "not now", the marker becomes
`[DEFERRED <date>: <reason>]`. The reason is required. Only the user
writes a `DEFERRED`; never assume one on their behalf.

## What not to do

- Do **not** write a new `[OPEN]` marker. It is a legacy state kept only
  so the 12 existing ones stay visible.
- Do **not** write `[BLOCKING]` and then keep implementing that thread.
  The marker means you stopped.
- Do **not** leave a `[BLOCKING]` marker behind at the end of a session.
  `tools/spec_gate.py` fails on any occurrence, because a surviving one
  means the loop was started and never finished.
- Do **not** mark a plan `done` while it owns a `BLOCKING`, `DEFERRED`
  or `OPEN` refinement. The gate fails on that too.

To write *about* a marker inside a plan rather than raise one, put it in
**double quotes** — `tools/spec_gate.py` skips a quoted marker as prose.
Backticks and single quotes do not work, and the gate will report a
malformed state. (This file is not scanned, so the backticks above are
safe here.)
