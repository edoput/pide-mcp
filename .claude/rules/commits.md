---
paths:
  - "**"
  - "*"
---
# Commit discipline

Changes are recorded in separate commits by kind, and always in this
order, matching how the work flows:

1. **Spec + changelog first.** Changes to `spec` and the root `CHANGELOG`
   are committed together, on their own, before anything they describe.
   A spec decision and its dated changelog entry are one unit (see the
   changelog memory).

2. **Planning next.** Changes under `plans/` are their own commit, made
   after the spec/changelog commit and before any implementation.

3. **Implementation + tests last, together.** Source changes and the
   tests that exercise them land in one commit — code and its tests are
   a unit and are never split across commits.

Never mix these three kinds in a single commit. Because a `git add` hook
keeps every tracked file staged, commit each group with an explicit
pathspec rather than committing the whole index, e.g.:

    git commit -- spec CHANGELOG -m "..."
    git commit -- plans/ -m "..."
    git commit -- mcp/ mcp_test/ -m "..."

One logical change may therefore produce up to three commits, in the
order above.

New (untracked) files are **not** staged automatically — `git add` them
yourself before they can be committed.

Only commit sources
