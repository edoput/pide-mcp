# Isabelle MCP design principles

This is the human review guide for architectural and implementation decisions
made during remediation. The canonical machine-readable register is
[`design_principles.yaml`](design_principles.yaml). Review findings should cite
one or more principle IDs and explain the observable mismatch between the
intended outcome and the implementation.

Maintenance rules:

- Keep IDs stable. Supersede a principle instead of silently changing its
  meaning.
- Record concrete decisions as examples, not as universal rules unless the
  architecture actually requires them.
- Update the YAML register first and keep this guide synchronized in the same
  change.
- A principle is not completion evidence. Its checks must be represented by
  plan requirements and executed tests where behavior is observable.

## Review summary

| ID | Principle | Intended outcome |
|---|---|---|
| ARC-001 | Separate control, data, and application planes | Byte transport, lifecycle ownership, and Isabelle behavior can evolve independently. |
| ARC-002 | Wire replaceable parts only at the composition root | Runtime choices are explicit and forbidden dependencies fail at compile time. |
| ARC-003 | Model lifecycle as an explicit state machine | Illegal pre-initialization, closing, and post-stop behavior cannot arise accidentally. |
| ARC-004 | Bound admission without an implicit waiting queue | Load has a finite memory footprint and overload is immediate and observable. |
| ARC-005 | Give every asynchronous operation one terminal owner | Response, cancellation, timeout, and shutdown races cannot double-complete or leak work. |
| ARC-006 | Represent evolving policy as typed immutable values | New settings are named, validated, and supplied explicitly rather than hidden in strings or positional arguments. |
| PRO-001 | Use one versioned internal bridge protocol | Cross-language glue has one envelope lifecycle instead of one miniature protocol per operation. |
| PRO-002 | Correlate with internal string IDs | Replies and cancellation cannot cross requests or lose JSON precision. |
| PRO-003 | Separate protocol failures from operation failures | Malformed envelopes and rejected domain operations remain distinguishable and actionable. |
| PRO-004 | Keep structured PIDE values structured | PIDE markup crosses the bridge as XML bodies, never as nested YXML strings. |
| CAP-001 | Make readiness prove the advertised capability surface | A backend becomes ready only when its selected application profile is actually available. |
| ISA-001 | Prefer Isabelle-native conventions at the Isabelle boundary | Theory imports, Theory_Data, XML/YXML, Isar text, and Proof.context remain authoritative. |
| ISA-002 | Make Isabelle extensions theory-local and inherited | New tools, bridge operations, and context resolvers compose through imports without a process-global dispatcher. |
| ISA-003 | Treat a context locator as an address, not a context | Scala transports an opaque locator while ML parses it and resolves a fresh Proof.context at execution time. |
| ISA-004 | Keep registry root separate from agent working context | An invocation target cannot select another catalogue or implementation. |
| ISA-005 | Keep bundle activation in Isar text | MCP clients cannot mutate Isabelle bundle state through a parallel network control surface. |
| ISA-006 | Put reusable ML implementations in `.ML` files | Theory files declare imports and load reusable modules with `ML_file` instead of embedding large implementations. |
| TST-001 | Test contracts against replaceable deterministic implementations | The same behavior can be exercised with stdio/scripted transport and bounded/sequential scheduling. |
| TST-002 | Separate functional correctness from performance measurement | Timing noise cannot make correctness suites flaky or turn microbenchmarks into merge gates accidentally. |
| VER-001 | Distinguish static coverage from executed evidence | Test tags and the verification matrix say what covers a requirement, not that it passed. |
| VER-002 | Require every declared test layer for completion | Multiple required layers mean multiple passing test cases; no layer substitutes for another. |
| VER-003 | Feed implementation discoveries back through spec and plan | A discovered semantic change opens a refinement instead of leaving specification, plan, and code inconsistent. |
| OPS-001 | Let the composition root own irreversible shutdown | Transport reports termination; only the owner of the Isabelle session may force-stop it. |

## Architecture and ownership

### ARC-001 — Separate control, data, and application planes

The data plane owns bytes, framing, parsing, and complete writes. The control
plane owns lifecycle, negotiation, request registration, cancellation,
deadlines, admission, and shutdown. The application owns MCP operations and
Isabelle behavior.

Review for imports or callbacks that reverse those dependencies. A transport
that decides whether a request is legal, or an application service that writes
directly to stdout, is a mismatch.

Decision example: `PideTransport` carries only commands and bytes;
`PideBridge` owns call IDs and terminal races; typed operations own their
payload codecs.

### ARC-002 — Wire replaceable parts only at the composition root

Transport, revision rules, scheduler, application, invariant reaction, bridge
protocol, and backend capability profile are narrow ports. Only the
composition root chooses their concrete implementations.

Review for literal session-name checks, global singletons, or production
classes constructing their own policy implementation. The rejected
`sessionName == "MCP-HOL"` readiness heuristic is a concrete mismatch: the
composition root must select an explicit base or HOL bridge profile.

### ARC-003 — Model lifecycle as an explicit state machine

Connection lifecycle and Isabelle readiness are separate state machines. A
fresh MCP connection accepts only `initialize`; after it, the server awaits
`notifications/initialized`; only Ready accepts ordinary operations. Closing
and Closed admit no new work.

Review every handler against the state transition table. Convenience paths
that allow normal requests before initialization, or make readiness implicit
in a non-null field, violate the principle.

### ARC-004 — Bound admission without an implicit waiting queue

Concurrency is configurable but bounded. Saturation rejects immediately with
a structured overload result, and capacity returns when the terminal owner
releases a request. There is no hidden unbounded executor queue.

Review both active work and queued work. A fixed thread pool with an unbounded
queue is still unbounded. Lifecycle notifications may use a separate control
path only when ordering and protocol semantics justify it.

### ARC-005 — Give every asynchronous operation one terminal owner

An admitted request has exactly one terminal outcome: response or
cancellation. Reply, cancellation, timeout, send failure, transport
termination, and shutdown compete to remove one registry entry; losing events
cannot complete another request.

Review for multiple promise maps, broadcast completion, late-result delivery,
or cleanup that occurs outside the winning transition. Duplicate completion is
an invariant violation, not ordinary behavior to ignore silently.

### ARC-006 — Represent evolving policy as typed immutable values

Policy values use named fields, validated constructors, and explicit profiles.
They are read once and passed into the connection or bridge kernel. Adding a
field produces useful compile-time pressure at construction sites.

Review for positional tuples, stringly typed modes, mutable global options, or
defaults that silently select application semantics. `ConnectionPolicy`,
`PideBridgePolicy`, and the explicit base/HOL bridge profile are examples.

## Protocol and capability boundaries

### PRO-001 — Use one versioned internal bridge protocol

Scala and ML share one request/result envelope with revision, kind, internal
ID, registry-root theory, operation, status, and typed payload. Operation
handlers extend a registry; they do not introduce another command, promise
map, cancellation group, or response function.

Review new operations for new protocol functions or lifecycle ownership. The
former seven operation-specific wire routes were the motivating mismatch.

### PRO-002 — Correlate with internal string IDs

Bridge IDs are generated independently of public JSON-RPC IDs, remain strings,
and are never exposed to MCP clients. Every result and cancellation is
correlated by that ID.

Review for FIFO assumptions, numeric conversion, reuse of client IDs, missing
IDs on replies, and maps keyed only by operation kind.

### PRO-003 — Separate protocol failures from operation failures

Wrong revision, malformed envelope, unknown operation, invalid status, and
codec failure are protocol failures. A registered handler that runs and
rejects its arguments produces an operation-domain result. Remote executor
exceptions remain a third explicit bridge failure.

Review code that infers failure kind from display text or maps every error into
one `Error(String)`. The caller must be able to choose recovery based on the
failure category.

### PRO-004 — Keep structured PIDE values structured

XML/YXML bodies containing PIDE markup cross the envelope as `XML.body` /
`XML.Body`. They are not first serialized into a string and then placed inside
another YXML envelope.

Review every codec pair symmetrically. The observed counterexample was a
structured result whose markup made `XML.Decode.string` raise `XML_Body`; the
repair paired ML `XML.Encode.self` with Scala `XML.Decode.self`.

### CAP-001 — Make readiness prove the advertised capability surface

A bounded internal hello runs after the registry-root theory is available.
The selected application profile declares required operation names; readiness
requires the right bridge revision and every required operation. Extra
theory-contributed operations are allowed, but calls are admitted only for
operations actually advertised.

Review for readiness based on heap startup alone, literal session-name
heuristics, or a static Scala catalog that permits calls missing from ML.

## Isabelle integration

### ISA-001 — Prefer Isabelle-native conventions at the Isabelle boundary

Use XML/YXML for PIDE data, Theory_Data for theory-inherited declarations,
Proof.context for execution, theory imports for extension, and Isar text for
user-visible declarations. External conventions are adapters at the MCP edge,
not replacements for Isabelle semantics.

Review proposals to put JSON inside PIDE, maintain a process-global dispatcher,
or reconstruct Isabelle state in Scala merely because those forms are familiar
outside Isabelle.

### ISA-002 — Make Isabelle extensions theory-local and inherited

Bridge operations and context resolvers are registered in Theory_Data. A
theory inherits declarations through imports and may extend them; unrelated
theories do not see them, and duplicate registration fails.

Review central match statements that must be edited for every extension,
mutable callbacks installed by descendant theories, or registries detached
from the selected root theory.

### ISA-003 — Treat a context locator as an address, not a context

A target URL names an existing context. Scala transports it opaquely; ML owns
parsing and resolution to Proof.context at execution. Keep the resolver extension
mechanism and avoid cached contexts or Scala branches on resolver kinds.

### ISA-004 — Keep registry root separate from agent working context

The server always creates a private live PIDE wrapper importing exactly the -T
selected theory. The wrapper determines the inherited catalogue and resolver
registry and supplies default execution, for both image and source selections.
Own it as a live PIDE document for the server lifetime. Require successful final
end before Ready; drain before releasing the document and stopping. There is no
heap-root fallback. Resolve its current theory value for each list/call. Select the actual ML tool
value from the root, then invoke it in the resolved target context. A target
must not substitute a same-named tool or change the advertised catalogue.

Imports merge ancestor declarations into descendants; notifications signal a
refresh and do not update ancestors or rebuild descendants automatically.

### ISA-005 — Keep bundle activation in Isar text

Bundles remain an Isabelle authoring mechanism. Users activate them with
imports and Isar declarations. MCP schemas and bridge payloads contain no
bundle list or remote activation mutation.

Review for network-controlled bundle names, request-time
`Bundle.includes_cmd`, or duplicated bundle state in Scala.

### ISA-006 — Put reusable ML implementations in `.ML` files

Substantial reusable structures live in standalone ML modules and theory files
load them using `ML_file`. Theories retain declarative imports, setup, and
registration.

Review large implementation blocks embedded in `.thy` files when they form a
reusable module with a stable signature.

## Testing and completion

### TST-001 — Test contracts against replaceable deterministic implementations

The same protocol/lifecycle contract should run against stdio and scripted
data planes, and against bounded-concurrent and deterministic-sequential
schedulers. Deterministic barriers represent races; sleeps are not the primary
oracle.

Review tests coupled to production I/O, tests that cannot inject ordering, and
architecture that prevents a sequential replay implementation.

### TST-002 — Separate functional correctness from performance measurement

Functional tests assert behavior and block completion. Performance tests use a
separate runner, controlled warmup and measurement, and explicit budgets.
MUnit may host functional tests but is not treated as a benchmarking system.

Review wall-clock thresholds inside ordinary unit tests and performance runs
that silently replace correctness coverage.

### VER-001 — Distinguish static coverage from executed evidence

`covers` tags and the verification matrix are static declarations linking plan
requirements to test cases. They can be generated without executing a test and
therefore cannot close a plan by themselves.

Review completion logic that treats a registered link as a passing result.

### VER-002 — Require every declared test layer for completion

When a requirement lists several layers, each layer needs at least one linked,
passing test case. Every declared blocking layer, the Scala suite, theory
builds, and static gates must pass. A filtered run can close only what it
actually covers.

Review relabelling that weakens a test merely to clear the matrix, or one green
layer substituting for another.

### VER-003 — Feed implementation discoveries back through spec and plan

An implementation discovery that changes promised behavior, ownership, or a
public representation opens a spec refinement. The refinement is resolved and
folded into the specification and plan before the code is considered complete.

Review comments or code that document a new rule while the plan and spec still
describe the old behavior.

### OPS-001 — Let the composition root own irreversible shutdown

The transport may detach receivers and report termination. The bridge may stop
admission, cancel calls, and request a bounded ML drain. Only the composition
root owns `Headless.Session.stop` and uses it as the forced fallback.

Review lower layers that stop the Isabelle process directly, or code that
claims `Stopped` merely because Scala promises were removed while ML workers
remain.
