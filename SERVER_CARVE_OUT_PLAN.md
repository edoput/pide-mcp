# MCP server carve-out plan

Status: implemented; reserved context, uniform wrapper ownership and mandatory
document-root selector wire are approved. The full local planning gate passed
on 2026-09-10 before checkpoint commit; committed-revision acceptance and
independent review are recorded in the implementation handoff.
Prepared 2026-09-10. Integration target: local `master`.

## Verification environment and local acceptance

The complete `tools/planning-gate done` passed with stock Isabelle2025-2 selected
through `/tmp/carve-isabelle`, private `USER_HOME` under this worktree's
`.isabelle-worktree/user`, and `ISABELLE_IDENTIFIER=mcp-wt-23e7799e182a6067`.
The adapter invokes the existing host Flatpak installation and forwards private
state variables; it is an environment artifact, not a distribution modification.
The existing project environment supplies `.venv` through an uncommitted symlink.

All required steps passed: Scala compilation, MUnit/theory catalogs, all four
theory sessions, specification/static closure, tooling, Scala unit/performance,
fresh-heap, live bridge and end-to-end tests. The static catalog has 25 plans,
369 tests and the existing 46 reviewed legacy gaps; those gaps are not new
waivers. Live tests cover wrapper ownership, failed startup/refresh recovery,
ancestor catalogue and execution refresh, stale selectors, unrelated pending
loads, cancellation/drain, and reply-size boundaries. Request-byte enforcement
remains the explicitly deferred baseline limitation described below.

## Source baseline and history

This plan is grounded in clean revision `4fcc6cccf58c0600dcc0c99f29f5b08893707a1f`,
initially detached at current master, in
`/home/edoput/.codex/worktrees/c6af/isabelle-mcp`, now branch
`codex/server-carve-out-plan`. This records the original planning baseline;
implementation now proceeds in that isolated worktree.
No applicable AGENTS.md was found in the checkout or checked ancestor directories.
Read CLAUDE.md: preserve isolated work, use simple explanations, and do not revive
REPL/session-state proof-tool baselines. Cross-worktree reads below were explicitly
requested; no other worktree was modified.

The main checkout is still on `codex/pide-resource-bounds` at `210f7cc`, with dirty
policy, test, tooling, and untracked planning files. Those edits are not this
baseline. Relative to that commit, this master has 39 changed files, 284 insertions
and 1,375 deletions, including bounded PIDE reply work and simplification of
connection/application code. Recent master history also adds installation-neutral
execution and private heap selection (`b49108e`, `712d69d`, `64e425b`, `5043f8b`).
Thus the originating claim that no launcher was known available is not proof that
this checkout lacks one. No installation or launcher had been executed during
that initial planning inventory; implementation verification now uses the selected
Isabelle2025-2 Flatpak installation with private worktree state.

Read-only overlap checks:

- `claude/oracle-sorry-urls-bb8e3d` and its clean detached worktree currently point
  to `ecbbb36`; the branch has no diff from its merge base with master. Its locator
  still accepts only `isabelle://context/…`; no `sorry://` implementation was found
  there. This does not establish that the user's work does not exist elsewhere.
- `claude/tool-scope-cleanup-3ffc77` has no unique diff against master's merge base.
  Other reachable history includes `f3c1a03`, `0f5474b`, `935b1cd`, and `00dd903`
  removing scope and moving source initialization to ML. Inspect these as prior
  attempts, not patches to cherry-pick wholesale: current master still contains
  mutable tool scope and the old Scala/IR surfaces.
- `codex/context-locator-e2e` at `8b12b83` and `f17454a` supply extension-test
  history; `codex/ir-removal-plan` at `4e0cabe` is prior planning context. Neither
  changes the approved deletion scope or requires preserving retired REPL tests.

## Result to produce

Keep the MCP server and its generic PIDE bridge, ML tool declarations/execution,
ML target resolvers, and the HOL-safe registration entry point. Keep exactly these
Scala builtins: `load_theory`, `check_theory`, `unload_theory`, `list_sessions`,
`list_theories`, `search_sources`, `doc_list`, `doc_read`. User-declared ML tools
remain extensible; they are not a hard-coded eight-tool catalogue.

Delete all MCP resources, mutable resource/tool scopes, existing REPLs, old
`sledgehammer`, `find_theorems`, `find_definition`, their source/navigation
attachments, and the IR bridge/engine. Do not migrate retired tools first.
Remove their requirements and tests rather than building compatibility shims.

Preserve current readiness behavior, cancellation, deadlines, shutdown/draining,
request admission, output isolation and size protections. Preserve the current
ML declaration language and Scala builtin activation used for advertising.
A later static/configurable availability policy is outside this cut.

## Approved follow-up dispositions (2026-09-10)

Always create a private temporary server-owned wrapper theory importing exactly
the configured -T selected theory through PIDE, whether it comes from the image
or local source. The wrapper's context is both the catalogue and default execution
root. Own that live PIDE document from bootstrap through shutdown: require its
successful final end before hello/Ready and retain its document requirement.
Reprocess changed ancestors and then the wrapper for subsequent catalogue updates.
There is no source/image split or heap-root fallback. Drain bridge work before
releasing the document requirement and stopping. The mandatory internal selector
wire below and public optional context argument are approved. This does not
introduce a multi-user document service.

Every internal hello/call carries mandatory root_node, root_command and root_exec
attributes alongside the existing theory identity. The node names the owned
wrapper; command and evaluation IDs select its completed final end. Use existing
Isabelle snapshot/Document APIs only, with no prover-source patches. ML checks
current execution identity, completion, final-end state and theory identity before
selecting the root. Missing, malformed, stale or unfinished selectors fail through
correlated errors, without fallback or automatic retry. Replies, cancel/drain,
operation payloads and public context arguments remain unchanged.

Source verification found the request-byte boundary promised by the earlier
pide_bridge plan was never implemented in the baseline. The user explicitly
deferred that missing feature instead of adding it during this carve-out.
Former pide_bridge#T11 is retired from this cut with this recorded reason; it is
not verified protection or a carve-out regression. Preserve actual reply limits,
output isolation, admission and lifecycle protections. Payload measurements are
not request-byte enforcement evidence.

## Necessary surviving-source adjustment: root catalogue, target execution

Current call chain:

1. `McpApplication.current_context` caches a connection scope. `tools_list` and
   `tools_call` use that context to obtain names and activation rows.
2. `McpBridgeOperations.runTool` already transports `(context, (name, args))`.
3. `MCP_Bridge.start` in `mcp_bridge.ML` resolves the configured theory name for
   each admitted call using `theory_by_name` in the inspected baseline; replace
   this heap/global lookup with the approved current live PIDE root selector.
4. `MCP_Bridge_Base.tools` resolves the payload locator and lists that context.
   `run_tool` does likewise, then `MCP_Protocol.run_tool` checks and retrieves the
   tool in the target. `MCP_Tool.run` itself repeats target-side lookup.

Change this narrow chain so the freshly looked-up startup root supplies tool
listing, name exposure, activation, and the actual tool value. ML resolves the
opaque target URL, then calls that selected value's `#run` with the resolved
Proof.context and arguments. Merely changing the first lookup is insufficient:
calling today's `MCP_Tool.run target name args` would select in the target again.
Use a small helper accepting the selected tool or separate catalogue/execution
contexts; keep existing validation, capture, and error handling around invocation.
Do not change the resolver result type or introduce overlays.

Remove connection `scope`, its setter/show tools and cached root locator. Use
fresh root catalogue lookup on both listing and invocation; a target must never
change advertised names or cause a same-named target tool to replace the root
implementation. Keep default invocation at root when no target is requested.
The generic bridge can carry the reserved argument without a new envelope version.
For listing, the smallest initial edit can retain the existing string codec while
making ML choose the supplied bridge root; remove obsolete locator plumbing only
where it has no surviving caller.

The user-approved public encoding is an automatically advertised optional String
parameter named `context` in each ML tool's ordinary arguments object. The
framework selects the tool from the fresh root catalogue, resolves the supplied
URL in ML, removes `context` from the tool arguments and invokes the unchanged
`#run` implementation in that Proof.context. Omission defaults to the root.
A tool declaration that defines its own `context` parameter must fail clearly;
this collision is rejected rather than silently overwriting an author argument.
The eight Scala builtins do not gain `context`. There is no `_meta` encoding and
tool authors do not implement target resolution themselves. No target-encoding
decision remains pending.

The selected -T import is stable configuration. The private live wrapper supplies
the current root theory value/catalogue, which is not cached.
Registry imports merge ancestor declarations into descendants. Reprocessing an
ancestor must be followed through the reprocessed root, and a later list/call
must observe that new root. Declaration notifications remain signals to refresh;
they neither update ancestors nor automatically rebuild descendants. No global
registry, upward inheritance, or new rebuild scheduler is required here.

## File and caller inventory

Paths below are repository-relative. Symbols identify the inspected source;
line numbers will move during deletion.

| Area | Action and concrete boundary |
|---|---|
| `mcp/src/mcp_server.scala` | Split. Retain schemas, naming/exposure, JSON argument conversion, eight builtins, JSON-RPC server/startup. Delete `repl_*_tool`, the three old search tools, scope builtins, `tool_scope_builtin_names`, resource contents/templates and resource routes. Remove `Builtin_Tool.handler`'s fallback to `backend.ir_cancellable` once all eight handlers are explicit. Fix load/unload/readiness descriptions referring clients to resources. |
| `mcp/src/mcp_application.scala` | Split. Delete `ResourcesList`, `ResourceTemplatesList`, `ResourcesRead`, resource handlers/status text, connection scope and scope builtins. Retain readiness, outcomes and tools dispatch, with root/target separation above. |
| `mcp/src/mcp_revision_rules.scala` | Delete resource admission/parameter parsing branches; removed methods use existing unknown-method behavior. Keep request IDs, initialization, cancellation and tool validation. |
| `mcp/src/mcp_connection_runtime.scala`, `mcp_connection_kernel.scala`, `mcp_protocol.scala`, `mcp_data_plane.scala` | Keep lifecycle/transport machinery. Remove only resource-specific capability/notification/operation handling and stale commentary where present. Trace both the revision-rule path and `MCP_Server` compatibility dispatch; neither may retain a resources route. |
| `mcp/src/mcp_session.scala` / `MCP_Backend` | Delete `ir`/`ir_cancellable`, named-resource codecs/calls, `mcp_resources*`, `mcp_resource_read*`, resource URI regexes, `scope_*`, `scope_patterns`, `active_repl_ids`, `repl_bridge_available`, retired source initialization and context-promotion APIs. Keep bridge calls, direct-operation shutdown tracking, theory mutation locking, eight tool implementations and their live dependencies. |
| Same session file, shared helpers | Keep `theory_master_dirs`: unload uses it to obtain the master directory. Keep `image_theory`/`image_tier` for the image-unload guard; keep `resolve_theory`, tiers, `theory_map` and `base_names` for load/check directory resolution. Delete `known_theory_tiers` if its resource/scope callers are gone; delete standalone `theory_diagnostics`/`theory_entities` and command/source locators after caller checks. Keep `render_messages` and use-theories snapshots used in replies. |
| `mcp/src/mcp_bridge_operations.scala` | Delete `ir`, `resources`, `readResource` codecs and operation requirements. Public boot currently requires `McpBridgeProfile.hol`; switch to surviving base operations, dropping the IR-only distinction when unreferenced. Retain `tools`/`run_tool`; retain `check_context` for root/default resolution while used. `theories`/`ml_theories` can be deleted if the post-cut caller scan confirms only resource clients; `list_theories_info` uses `sessions_map`, not this bridge operation. |
| `mcp/src/mcp_pide_bridge.scala`, `mcp_pide_protocol.scala`, `mcp/Tools/mcp_bridge.ML` | Retain generic registration, call IDs, envelope validation, worker groups, cancellation, deadlines, hello and output bounds. Tighten known/required operation sets; do not redesign transport. Retain fresh live PIDE root selection per call. |
| `mcp/Tools/MCP_Tools.thy` | Split in place. Delete `MCP_Resource`, resource keywords/commands/attributes/parser branches/demo, `resources_body`, `read_resource`, bridge resource registrations and resource activation rows. Keep shared `MCP_Registry`, `MCP_Tool`, parameter machinery, `mcp_tool`, `mcp_test`, `MCP_Output`, tool capture, resolver setup, and `MCP_Builtin` activation foundation. Trim builtin declaration rows to the eight names. Shared parsing helpers survive when used by `mcp_tool`. |
| `mcp/Tools/mcp_context_locator.ML` | Retain ML-owned resolver registration, root-based resolution and Proof.context results. Do not add Scala URL parsing or constrain future `sorry://some/theory/1` support. Remove the retired REPL resolver registration with its owner, not the resolver extension mechanism. |
| `mcp/Tools/HOL/MCP_Repl.thy`, `MCP_Repl_Dyn_Source.thy`, all `ir/` | Delete after caller removal. This removes `MCP_Repl` dispatcher, `Ir`, ML repl tools including show/text/back, old search tools, TCP/Python REPLs, standalone Python MCP facade and engine-specific dependencies. Output capture already lives in retained `MCP_Tools.thy`; do not delete it because comments mention `ir/ml_repl.ML`. |
| `mcp/Tools/HOL/MCP.thy` | Retain HOL-first imports and safe registration foundation; remove resource documentation. `MCP-HOL` can remain a small registration session with `MCP` alone. |
| `mcp/src/doc_catalog.scala`, config/options, launch/build helpers | Keep documentation discovery/read behavior, startup theory/session inputs, actual reply/output limits and execution support. Trim retired default theory/session references and IR-only recording/build inputs. Do not delete Isabelle `Resources`/`session.resources`: they implement surviving session/load/unload operations. |

Tool declarations emit `MCP.tools_changed`; retain the `RegistryChange.Tools`
protocol handler and `notifications/tools/list_changed` routing, including during
pending work. Delete `RegistryChange.Resources`, `MCP.resources_changed` and its
MCP notification. Initialize advertises tools with listChanged and no resources
capability. Retain initialize/ping/tools and existing lifecycle notifications;
this cut does not add prompts or another protocol family.

## Lean order of cuts

1. Establish root catalogue/target execution and the reserved optional context
   argument with discriminating fixtures. Remove mutable tool scope.
2. Remove resource routes/capabilities/notifications, resource scope tools and
   ML named-resource registration. Remove backend methods and resource-only
   snapshot/source readers with their callers in the same change.
3. Remove every retired Scala and ML tool, then the `ir` bridge operation,
   `MCP_Repl` theories and all `ir/`. Trim startup profile and builtin activation
   rows. There is no intermediate requirement to migrate retired tools.
4. Reduce sessions/tests/docs/evals and plan obligations to the retained server.
   Run meaningful retained checks, then the adjusted repository acceptance gate.

These are dependency-ordered cuts, not new work-package machinery. Each may be
one reviewable commit or combined when keeping the tree buildable is simpler.
Do not refactor surviving code simply to reduce line counts.

## Collateral cleanup

- `mcp/Tools/ROOT`: keep MCP-Tools, MCP-Assumption/test linkage as needed and the
  HOL registration session; remove REPL/IR test theory entries. Retain or replace
  the HOL test session with the small HOL-safe registration regression, rather
  than deleting that regression with `MCP_Repl_Tests`. Remove `record_theories`
  only after verifying no retained fixture/export needs it.
- Delete `HOL/Tests/Ir_Tests.thy`, REPL tests and `MCP_Fixture_Nav` when their
  remaining callers are retired. Split `Tests/MCP_Tools_Tests.thy` and fixture
  theories: retain registry merge, tool typing/capture, activation, root and
  resolver extension tests; remove resource assertions.
- Split `mcp_test/src/mcp_{application,handler,bridge,heap,pide_bridge}_tests.scala`
  and connection tests. Keep transport races/cancellation/output tests; replace
  retired REPL/resource workloads with a tiny test ML tool where the assertion
  is about surviving transport. Delete tests whose only assertion is the
  abandoned resource/REPL contract. Update `mcp_testing.scala` fake backends,
  layers, metadata exports and `mcp_pide_payload_measure.scala` operation samples.
- Apply the same rule to `mcp/test/test_mcp.py`, e2e `case_legacy.py`, registry,
  connection fixtures and repro scripts. Do not retain a retired operation just
  because an old concurrency test happens to call it.
- Delete retired `evals/repl_*`, scope/search proof-tool and `goto_definition`
  evaluations. Keep evaluations for the eight survivors and ML tool behavior;
  remove resource twins from their assertions.
- Supersede/delete active `plans/repl_*`, scope plans, `tool_scope`,
  `resource_tool_mirrors`, `read_*` resource plans, `list_entities`,
  `goto_definition`, the three proof-search plans and `ml_builtin_migration`
  obligations tied to retired tools. Amend mixed `context_locator`,
  `mcp_tool_registry`, `mcp_tool_command`, `builtin_activation`, `pide_bridge`,
  readiness, connection, verification and surviving tool plans. Reconcile
  dependency links, `spec_test` IDs, `plans/ASSUMPTIONS`, label audit/refdata,
  metadata tables and `tools/planning_gate/commands.py` session inventory.
  Do not mark deleted obligations as verified or leave them as acceptance debt.
- Rewrite active `spec`, README, relevant CHANGELOG claims, `plans/README`,
  `evals/README`, review invariants/design principles and repository skills or
  examples that advertise removed features. Search tracked backup files
  `mcp/src/mcp-main.scala~` and `mcp/etc/settings~` too; remove obsolete copies
  instead of leaving an apparent alternate implementation. Keep Git history as
  historical evidence; no compatibility/archive copy of retired code is needed.

## Verification of the smaller server

For this plan: inspected source/callers, branch/worktree/history and instruction
files; checked document whitespace and referenced file paths. No runtime result
is claimed. During implementation use the repository isabelle-execution skill
and selected installation/private worktree state; no main-checkout heap writes.

The resulting acceptance evidence should cover:

- Startup/hello on the reduced Pure and HOL registration foundations, no IR
  required. Exact surviving Scala builtin names and matching ML activation rows.
- Root fixture R importing A; separate target B with a conflicting same-name
  tool. List from R, execute R's tool in B, reject inactive/missing root tools
  regardless of B's registration. Resolve an extension target wholly in ML.
  Test malformed target failure and default root execution. Verify optional String
  context on every ML schema, absence from Scala builtin schemas, clear rejection
  of author-owned context declarations and stripping before #run receives args.
- Reprocess ancestor A and then root R, and demonstrate fresh catalogue/tool
  behavior on later calls. Retain sibling/import-merge isolation checks.
- Tool declaration notification during pending work; no resource capability,
  route, templates or resource notification. Removed method names follow the
  existing unsupported-method path and retired builtin names are not listed.
- Existing cancellation/deadline/admission/shutdown/output-bound regressions
  with retained or fixture tools; interrupted work does not corrupt later calls.
- Load/check a small theory, warning/error reporting, edit and check again,
  unload plus image-unload rejection; session/theory discovery, substring name
  search, documentation list/read. Preserve current not-ready/failed behavior.
- HOL import fixture exercising datatype/simplifier registration with MCP in
  different import positions, so removal of REPL does not erase the foundation
  regression.

Run the adjusted `tools/planning-gate static`, relevant scala-unit, ml-unit,
heap/bridge/e2e layers and finally `tools/planning-gate done` using the selected
installation. Record exact revision, command and result. Metadata success is
not runtime acceptance; environment failures are reported separately. No gate
against the pre-carve-out obligations is a requirement to preserve those
obligations. A final tracked-source search for resource routes, `MCP_Resource`,
`MCP_Repl`, IR calls and retired tool names should distinguish retained negative
tests/history from accidentally surviving implementation.

## Deliberately deferred

`load_theory` and `check_theory` presently duplicate directory resolution and
both call headless `session.use_theories`; retain both public tools. A private
helper is optional if edits touch that duplication anyway. `search_sources`
currently uses name `.contains(pattern)`, not content grep; retain its name and
behavior. Discovery/docs could later run before the prover. Public readiness
responses remain as-is, with the approved live-root finalization check before Ready. Query_Operation/with_overlay, richer Toplevel.state results,
fine-grained checking, replacement proof-search tools, notification batching and
static availability policy are subsequent tool design, not MCP prerequisites.
