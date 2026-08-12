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

    git commit -m "..." -- spec CHANGELOG
    git commit -m "..." -- plans/
    git commit -m "..." -- mcp/ mcp_test/

The message must come BEFORE the `--`. Everything after `--` is a
pathspec, so `git commit -- spec CHANGELOG -m "..."` makes git treat
`-m` and the message itself as filenames and fail with
`error: pathspec '-m' did not match any file(s) known to git`.

One logical change may therefore produce up to three commits, in the
order above.

New (untracked) files are **not** staged automatically — `git add` them
yourself before they can be committed.

Only commit sources
