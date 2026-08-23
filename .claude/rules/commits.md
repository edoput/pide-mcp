---
paths:
  - "**"
  - "*"
---
# Commit discipline

**Separation is the rule.** Changes are recorded in separate commits by
kind, so that later you can find one kind without reading the others:

1. **Spec + changelog.** Changes to `spec` and the root `CHANGELOG` are
   committed together, on their own. A spec decision and its dated
   changelog entry are one unit (see the changelog memory).

2. **Planning.** Changes under `plans/` are their own commit.

3. **Implementation + tests, together.** Source changes and the tests
   that exercise them land in one commit — code and its tests are a unit
   and are never split across commits.

Never mix these three kinds in a single commit.

## Order is within one change, not across a task

Where the kinds are ordered, the rule is only that a spec decision lands
**before the code that depends on it**, and a plan lands before the
implementation it plans. That is an ordering over one logical change. It
is **not** a phase ordering over a whole task, and it never means "batch
all the spec work at the front".

**A conflict discovered during implementation is its own change.** Stop,
resolve it with the user, commit spec + CHANGELOG, amend the plan, then
continue implementing. Do not record the conflict and plough on: see
`.claude/rules/spec-refinement.md` and the "spec refinement" section of
`plans/README`.

Leaving the implementation dirty in the working tree across those two
commits is the *intended* use of the pathspec form below, not a
workaround. Explicit pathspecs exist precisely so one kind can be
committed while the others are still in progress.

## Committing one kind out of a dirty tree

Because a `git add` hook keeps every tracked file staged, commit each
group with an explicit pathspec rather than committing the whole index:

    git commit -m "..." -- spec CHANGELOG
    git commit -m "..." -- plans/
    git commit -m "..." -- mcp/ mcp_test/

The message must come BEFORE the `--`. Everything after `--` is a
pathspec, so `git commit -- spec CHANGELOG -m "..."` makes git treat
`-m` and the message itself as filenames and fail with
`error: pathspec '-m' did not match any file(s) known to git`.

One logical change may therefore produce up to three commits.

New (untracked) files are **not** staged automatically — `git add` them
yourself before they can be committed.

## Only commit sources

Build outputs are not sources and never belong in a commit: the jars
(`mcp/lib/mcp.jar`, `mcp_test/lib/mcp_test.jar`), the generated munit
manifest (`mcp_test/lib/munit-spec.json`), heaps, and editor backup
files. `.gitignore` covers the known ones; check `git status` before
committing rather than trusting it to be complete.
