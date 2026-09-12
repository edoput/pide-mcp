Isabelle MCP server specification
=================================
id: S-isabelle-mcp-server-specification

The product is an extensible MCP server for Isabelle. Scala owns JSON-RPC,
transport, lifecycle and seven builtins. Isabelle/ML owns tool declarations,
activation, tool implementation, target resolution and execution contexts.

Public surface
--------------
id: D-2026-09-10-server-public-surface
Initialize advertises tools with listChanged. Keep initialize, ping, tools/list,
tools/call and the existing lifecycle/cancellation notifications. Resources,
templates, resource notifications, mutable scopes, REPL implementations, the IR
engine and the old proof-search/navigation tools are retired without shims.

The Scala tools are load_theory, unload_theory, list_sessions,
list_theories, search_sources, doc_list and doc_read. load_theory uses headless
session.use_theories and reports theory status/messages; re-reading and
re-checking an already-loaded theory is the same call with the same result,
so there is no separate check_theory. Unload preserves the
image guard. Discovery uses the existing session catalogue; search_sources is a
theory-name substring search. Documentation uses the existing Scala catalogue.
Keep existing public readiness responses and parameter contracts; the live-root
readiness proof below is required before entering Ready.

Retired check_theory
---------------------
id: D-2026-09-12-retire-check-theory
check_theory is removed without a shim. It was never anything but load_theory
under another name: both called the identical use_theories_result with
identical parameters, confirmed empirically (byte-identical replies for the
same theory and arguments) and structurally (mcp_session.scala's two method
bodies were the same call). load_theory already re-reads the theory file
fresh on every call and reports its current diagnostics, which is the whole
of what check_theory's "re-check after an edit" framing needed. Removing it
frees the name "check_theory" for a community mcp_tool declaration, since it
is no longer a reserved Scala builtin name.

Root catalogue and invocation target
------------------------------------
id: D-2026-09-10-root-catalogue-and-invocation-target
The startup -T option selects exactly one imported theory. The server always
creates a private temporary wrapper theory importing that selected theory through
PIDE, uniformly for image and source theories. This server-owned live wrapper is
the catalogue root and its Proof.context is the default execution context.
Resolve its current successful document value on each list/call. Bootstrap loads
the wrapper before hello and requires its successful final end before readiness.
Retain its document requirement throughout the server lifetime; drain bridge work
before release and session stop. Reprocess changed ancestors and then the wrapper
to observe updated declarations. No source/image branch or heap-root fallback is
allowed. Internal hello/call requests carry mandatory root_node, root_command
and root_exec beside the existing theory identity, using existing Isabelle APIs
without prover-source patches. ML checks current execution identity and successful
final-end state; invalid, stale or unfinished selectors fail without fallback or
automatic retry. Replies, cancellation/drain and operation payloads are unchanged.
Imports merge ancestor data into descendants;
declarations do not propagate upward or implicitly schedule descendant rebuilds.

Select tool names, activation and the actual ML tool value from this root.
Scala transports a per-call target URL as an opaque string. ML resolves it to a
Proof.context and invokes that selected tool value there. A target never selects
the catalogue or substitutes a same-named implementation. Default execution is
at the root. Every ML tool automatically advertises an optional string parameter
named context in its ordinary arguments object. The framework runner resolves
its URL in ML and removes context before invoking the unchanged #run function.
Declaring a tool's own context parameter fails clearly at declaration time.
The eight Scala builtins do not gain this argument. No _meta encoding or
manual target resolution by tool authors is required.

Keep the existing ML resolver registry and its extension direction. Do not alter
resolver result types, introduce overlays or substitute diagnostic queries in
this cleanup. Keep mcp_tool, mcp_test, typed parameters, MCP_Tool registration,
Isar activation and HOL-safe registration. Scala builtin activation filters
advertising using the root rows and retains existing callability/fallback rules.
Tool declarations trigger notifications/tools/list_changed, including while
other work is pending.

Transport and lifecycle
-----------------------
id: D-2026-09-10-transport-and-lifecycle
Maintain MCP 2025-03-26 framing and JSON-RPC behavior, initialization phases,
batch handling, validated bounded admission, cancellation, request deadlines,
exactly one terminal response owner and bounded EOF/shutdown draining. Protocol
and application failures remain distinct. Application code cannot write raw
transport bytes. Keep output capture/isolation and actual reply-size defenses.
The baseline lacks a request-byte guard. That pre-existing unimplemented boundary
is explicitly deferred by the user; former pide_bridge#T11 is retired from this
cut, not counted as passed or described as a carve-out regression. Measurement
recommendations do not establish request-byte enforcement.
The generic MCP.bridge protocol and registry remain extensible; the required
base operation set is tools, run_tool and check_context. Hello validates that
set before readiness. Shutdown completes after drain acknowledgement or proven
session termination; only the composition root owns forced stop.

Verification and deferred work
------------------------------
id: S-verification-and-deferred-work
Keep transport race/output tests, root-versus-target conflicting-tool fixtures,
ancestor reprocessing, resolver extensions, imported/sibling isolation, HOL
registration, declaration notifications and the seven builtin workflows. Remove
obsolete contracts and their tests instead of preserving compatibility. Static
metadata is not runtime evidence; tools/planning-gate done is the acceptance
command and must run at a stable revision with the selected installation.

Replacement proof-search tools, Query_Operation/with_overlay, richer target
states, fine-grained checking, static availability policy, notification batching,
and pre-prover discovery remain deferred. load/check consolidation is done
(see D-2026-09-12-retire-check-theory above), not deferred.

Stable refinement anchors
-------------------------
id: S-stable-refinement-anchors
The following retained identifiers refer to the current contracts above; their
obsolete historical feature obligations have been superseded by this carve-out.

Retained ML tool declarations
-----------------------------
id: D-2026-07-13-mcp-tools

Retained startup readiness responses
------------------------------------
id: D-2026-07-21-server-startup-readiness

Retained catalogue construction
-------------------------------
id: D-2026-07-30-catalog-long-pole-build

Retained concurrent request execution
-------------------------------------
id: D-2026-08-20-concurrent-serve-loop

Retained connection architecture
--------------------------------
id: D-undated-architecture-decisions

Concurrent serving implementation boundary
------------------------------------------
id: S-concurrent-serve-how

Deferred protocol and tool work
-------------------------------
id: S-out-scope
