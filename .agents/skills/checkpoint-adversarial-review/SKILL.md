---
name: checkpoint-adversarial-review
description: Run the Isabelle MCP checkpoint adversarial-review pass from a detached worktree with fixed subagent settings and no inherited conversation context. Use after a locally verified checkpoint commit and before continuing implementation.
---

# Checkpoint adversarial review

Use this skill only for a read-only review of an Isabelle MCP implementation
checkpoint. The primary agent remains responsible for implementation,
adjudication, repairs, verification, and commits.

## Fixed reviewer settings

Spawn exactly one review subagent with:

- `model: gpt-5.6-luna`
- `reasoning_effort: high`
- `fork_turns: none`

If that exact model and effort are unavailable, stop the review pass and tell
the user. Do not silently inherit the primary model, lower the effort, or select
a replacement.

The empty turn fork is intentional: the reviewer must not inherit the primary
agent's explanations, suspected defects, or earlier conclusions. It may inspect
the complete repository and Git history directly.

## Isolated checkpoint

Review an exact checkpoint commit, not a moving dirty worktree:

1. run the checkpoint's blocking local verification;
2. commit only the checkpoint files;
3. create a detached temporary Git worktree at that commit under a uniquely
   named temporary directory;
4. give the reviewer that worktree as its sole repository root; and
5. remove the temporary worktree after the findings are captured and
   adjudicated.

Do not copy the primary working directory or reuse its build output. Do not
include unrelated user-owned changes in the checkpoint merely to make the
review snapshot complete. A worktree isolates repository files and generated
artifacts; it does not claim container, process, installed-tool, or shared-cache
isolation.

## Review packet

Give the reviewer a self-contained, bounded prompt containing only:

1. the isolated worktree root;
2. the checkpoint commit and its base commit or ref;
3. the relevant implementation plan paths;
4. the checkpoint's claimed outcome and explicit scope exclusions;
5. the architectural and behavioral invariants it must derive and falsify;
6. the permitted verification commands or test layers; and
7. the required finding format below.

Do not include prior review conclusions or tell the reviewer which defect the
primary agent expects to find.

## Authority boundary

The reviewer is read-only:

- do not edit, format, stage, commit, or delete files;
- do not install dependencies or alter external state;
- read code, plans, specifications, tests, and Git history as needed;
- run non-destructive focused checks when useful; and
- preserve user-owned untracked files.

The reviewer must not inspect or operate on the primary working directory.

## Required output

Require findings ordered by `P0`, `P1`, then `P2`. Every finding must contain:

- a short title;
- exact `file:line` evidence;
- the violated invariant or acceptance claim;
- a concrete counterexample or failure path;
- the smallest credible repair; and
- the focused verification that would prove the repair.

Require an explicit statement when no actionable P0/P1/P2 finding remains.
Style-only suggestions and unverified speculation are not findings.

## Adjudication

The primary agent must reproduce or independently validate each finding before
changing code. It records rejected findings with the conflicting evidence,
repairs accepted findings locally, reruns the narrow checks and the checkpoint's
blocking test layers, and requests another independent pass when a repair
materially changes the reviewed invariants.
