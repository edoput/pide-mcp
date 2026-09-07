---
name: remediation-implementation
description: Execute a bounded remediation work package using the repository routing policy, with one implementor, one verifier, explicit stop conditions, and measured handback.
---

# Remediation implementation

Use this skill when the user wants to implement, verify, or economically
evaluate a remediation slice governed by `CODEX_REMEDIATION_ROUTING.md`.

Read that policy before acting. It is the source of truth for role routing,
packet fields, model effort, handback format, and correction limits.

## Operating mode

Treat the long-lived Sol task as design and acceptance authority. For a
settled slice, create one fresh Terra implementation task in an isolated
worktree. Give it a compact packet containing the outcome, exact scope,
fixed invariants, non-goals, acceptance command, focused tests, and escalation
questions. Do not send the full remediation transcript.

After implementation, use one Luna-medium read-only verifier against the exact
commit. The verifier reports command results and concrete defects; it does not
redesign the work or decide acceptance.

The normal loop is:

```text
packet -> Terra implementation/self-repair -> Luna-medium verification -> Sol disposition
```

Permit one bounded correction when verification finds a defect inside the
packet. Escalate instead when the issue changes an invariant, architecture,
environment contract, or scope. Use Luna-high only for an explicitly marked
independent adversarial or cross-language checkpoint. Keep Sol xhigh for
genuinely disputed architecture, concurrency, or final acceptance risk.

## Implementor contract

The implementor owns its local test-and-repair loop. It should run the stated
acceptance command, repair in-scope failures, rerun focused tests, and stop at
the first out-of-scope or architectural question. It must not silently widen
the design.

The parent-facing handback must contain only:

```text
Outcome:
Changed files:
Commit:
Acceptance command and result:
Focused tests and result:
Unresolved questions:
Changed assumptions or discoveries:
```

Do not open another routine implementor or review task after a passing
acceptance command. Close the package and record its disposition.

## Evaluation and policy updates

When asked to evaluate a run, distinguish:

- engineering results: commits, tests, evidence, unresolved work;
- delegation results: roles, model/effort, child count, tool calls, retries,
  token usage, and whether the package closed;
- economic estimates: API-equivalent estimates only, clearly separated from
  ChatGPT plan limits and opaque platform accounting.

If repeated evidence shows that the routing policy is causing avoidable
rework, update both this skill and
`CODEX_REMEDIATION_ROUTING.md` in the same change. Make the smallest
evidence-backed policy change, preserve the successful role boundaries, and
add or tighten a stop condition when appropriate.
