# Codex remediation routing policy

Status: active project policy
Version: 2

This document defines how remediation work is divided between the long-lived
design task and bounded implementation, verification, and review tasks.

It is intended to be imported into an existing Codex task with a new user
message. Importing it does not erase conversation history. The import message
must explicitly say which earlier workflow choices it supersedes.

This policy can override earlier user-level routing choices in the task. It
cannot override system or developer instructions, repository safety rules, or
the user's current explicit request.

## Import and override

In the target task, send:

```text
Read and apply .agents/skills/remediation-implementation/CODEX_REMEDIATION_ROUTING.md
as the active remediation workflow policy.

This message supersedes earlier user-level choices in this task that route all
implementation, testing, and routine investigation through the long-lived
Sol/xhigh conversation. Keep Sol as the design and acceptance authority, but
use fresh bounded subagents for settled implementation and read-only checks.

First acknowledge the active roles and then propose the next bounded work
package. Do not resume the previous open-ended implementation loop.
```

After importing the policy, the task should treat the following as the new
default:

- Sol owns architecture, cross-language invariants, disputed concurrency
  decisions, and final acceptance.
- Terra owns settled implementation slices in an isolated worktree.
- Luna owns read-only inventories, mechanical checks, and exact test-failure
  diagnosis.
- A fresh subagent receives a compact packet, not the full parent transcript.
- The parent task receives a compact handback, not a second implementation
  transcript.

## Work package contract

Before delegation, the parent design task must produce a packet containing:

1. One concrete outcome.
2. The exact files or modules in scope.
3. Decisions and invariants that are already fixed.
4. Explicit non-goals.
5. The acceptance command and pass condition.
6. Required focused tests.
7. Questions that require architectural escalation.

If these fields cannot be filled without inventing architecture, the work
belongs in the Sol design task first.

The packet is also the unit of economic control. It must be small enough that
one implementor can finish it without rediscovering the remediation history.
Do not dispatch a second implementor for the same packet merely because the
first one produced a surprising result; inspect the result first and either
issue one bounded correction or escalate the design question.

## Role routing

### Sol design and acceptance

Use Sol for:

- architecture and ownership decisions;
- protocol, lifecycle, concurrency, and cross-language reasoning;
- deciding whether evidence is sufficient for completion;
- reviewing a Terra handback;
- resolving an escalation from an implementor.

Use xhigh only for a genuinely disputed or high-risk design packet. Use high
for an independent adversarial checkpoint review when the risk justifies it,
and medium for settled review and ordinary follow-up.

### Terra implementation

Use a fresh Terra subagent for:

- a bounded code change whose design is already fixed;
- adding or repairing focused tests from an acceptance packet;
- mechanical refactors with a disjoint write scope;
- running the specified verification command and returning evidence.

The Terra agent must not redesign the architecture, broaden the scope, or
silently decide an unresolved semantic question. It must stop and report the
question.

Start it with a fresh context (`fork_context=false`) unless a specific
artifact from the parent is required. Use an isolated worktree for edits.

### Luna verification and inventory

Use Luna at medium effort for:

- read-only repository or test-layer inventories;
- exact commands with an explicit expected pass condition;
- reproducing a reported failure and identifying its failure boundary;
- mechanical metadata or coverage checks.

Luna does not decide architecture, severity, or acceptance, and does not edit
the primary worktree for these tasks.

Use Luna-high only for an explicitly independent adversarial checkpoint or a
cross-language invariant review. Routine inventories, exact test execution,
mechanical checks, and failure-boundary diagnosis stay at Luna-medium.

## Required implementor handback

The implementation task's final response must contain only this compact
handback:

```text
Outcome:
Changed files:
Commit:
Acceptance command and result:
Focused tests and result:
Unresolved questions:
Changed assumptions or discoveries:
```

"Only" means that this is the required parent-facing result. The agent may
provide normal progress updates while working, but the parent should not ask
it to reproduce the entire reasoning transcript.

Before returning the handback, the implementor owns a short local repair loop:

1. run the acceptance command;
2. diagnose and repair failures that are inside the packet;
3. rerun the focused tests and acceptance command;
4. stop and report an unresolved question if the failure crosses an invariant,
   architecture, environment, or scope boundary.

The loop has one correction pass by default. A second correction requires a
new packet from the parent with a changed assumption or an explicit reason why
the original acceptance contract was incomplete.

## Parent integration loop

After the handback, the parent Sol task should:

1. inspect the diff and commit;
2. check the reported acceptance evidence;
3. decide whether the design and invariant boundaries still hold;
4. accept, request a bounded correction, or escalate the design question;
5. start the next packet only after the current slice has a disposition.

Do not keep an implementation subagent open after it has completed. Close it
once its result has been inspected.

The normal package shape is therefore:

```text
Sol packet -> one Terra implementor -> one Luna-medium verifier -> Sol disposition
```

Add one bounded Terra correction only when the verifier reports a concrete
defect. Add a Luna-high review only when the packet is an explicitly marked
adversarial or cross-language checkpoint. Do not keep a package open for a
second routine review after the acceptance command passes.

## Cost, context, and closure controls

- Prefer fresh packets over forks of the full remediation conversation.
- Keep packets below the information needed to implement and verify the slice.
- Do not replay prior review transcripts when a commit, file list, invariant,
  and acceptance command are sufficient.
- Do not run broad test suites after every small change unless a new failure,
  changed boundary, or unresolved risk justifies it.
- Record model, effort, task role, tool calls, input/output usage, result, and
  rework for every package when the telemetry surface is available. Treat it
  as usage telemetry, not billing; public API-equivalent estimates must be
  labeled as estimates and must exclude opaque platform costs.
- Stop on a passing acceptance command plus a clean handback. Do not spend
  additional tokens seeking a stronger-but-unspecified form of confidence.
- Record failed commands and retries in the handback. A retry is useful only
  when it changes the diagnosis, repairs the implementation, or supplies a
  required missing environment condition.
- At the end of a remediation run, report both engineering closure and
  delegation closure: completed packages, incomplete packages, retries,
  unresolved questions, and measured child usage.

## Policy maintenance

This policy is the source of truth for the remediation-routing skill. When a
run demonstrates a recurring failure in routing, update this file and the
skill together in one reviewable change. Keep the update evidence-based:

- preserve successful role boundaries unless the evidence shows a failure;
- change model or effort defaults only when the observed result, cost, or
  rework supports the change;
- add a stop condition when repeated work did not improve closure;
- do not turn a one-off environment failure into a universal routing rule.

When the policy is imported into an existing task, the import message must
state that it supersedes earlier user-level routing choices. Importing the
policy does not erase history and does not override system or developer
instructions.

## Current WP0 candidate

The first candidate packet is:

```text
Outcome: separate the deterministic test catalog from execution evidence.

Acceptance:
- catalog generation alone cannot close a done plan;
- a tagged test that fails prevents completion;
- a verification result is bound to the evaluated revision and artifacts;
- the focused planning-gate fixtures pass.
```

The parent task must fill in the exact files and command before sending this
packet to Terra.
