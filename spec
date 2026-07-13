isabelle mcp_server — project spec

goal
----

we are writing an implementation of a mcp for the isabelle proof assistant
as an isabelle tool.

we want the mcp to allow users to write custom tools using the isabelle/ml
language. this will allow for reuse of pre-existing tools implemented by the
community.

directories
-----------

- mcp our implementation
- Isabelle2025-2_linux the isabelle distribution
  - Isabelle2025-2_linux/Isabelle2025-2/src/Tools the tools distributed along with isabelle
  - Isabelle2025-2_linux/Isabelle2025-2/src/Tools/Demo
    Implementation of a isabelle/scala tool that is available as a system component
- iq an implementation of a mcp server in isabelle/scala (jEdit plugin, TCP)
- ir Isabelle/REPL: ML proving engine (ir.ML) + standalone python mcp server;
  we reuse ir.ML in phase 2 (see phase 2 below)

status: phase 0 (research) and phase 1 (mvp) are done; phase 2 (agentic
proving) is in progress. skill: .claude/skills/isabelle-ml-scala/SKILL.md
documents the general ml/scala development workflow and should be kept
current as the project evolves.


================================================================
phase 0 — research
================================================================

- [x] how can the isabelle/scala programming environment interact with isabelle/ml and viceversa
- [x] how to develop isabelle tools
- [x] how to build isabelle tools
- [x] how to test the build
- [x] how to test the interaction between isabelle/scala and isabelle/ml

  outcome: .claude/skills/isabelle-ml-scala/SKILL.md

- [x] can we implement an MCP in isabelle/ml. what are the advantages
      (answer: not practically. ML has TCP streams (Socket_IO) and threads, but no JSON
      library, and its stdin/stdout are owned by the PIDE protocol when managed by Scala;
      a raw ML_process has free stdio but loses the document model and session management.
      ML's real advantage — direct access to prover internals — argues for writing the mcp
      *tools* in ML, not the server.)
- [x] can we implement an mcp in isabelle/scala. what are the advantages
      (answer: yes — iq proves it. isabelle/scala has isabelle.JSON for JSON-RPC, full JVM
      sockets/threads, owns its stdio (stdio transport works), manages prover sessions
      (Headless PIDE, ML_Process), and ships as a regular isabelle tool. the bundled
      `isabelle server` (Pure/Tools/server.scala) is a direct precedent: resident TCP server,
      JSON arguments, commands extensible via the Server.Commands service.
      conclusion: server in scala, user tools in ML, bridged via the protocol_command /
      Scala.function machinery.)
- [x] how to register a mcp tool in isabelle/ml and have it available in isabelle/scala
      (answer: MCP_Tool.register from ML — mvp, working; and for the no-ML happy
      path the mcp_tool / mcp_resource isar commands designed in phase 2: users
      wrap diagnostic commands or named facts in plain isar)
- [ ] what kind of tool description/serialization between isabelle/ml and isabelle/scala
      (partially answered by phase 2: builtin structured tools live in scala;
      ML user tools keep the fixed {input: string} schema until the registry
      grows a schema field — see "out of scope" in both phases below)


================================================================
phase 1 — mvp: isabelle mcp_server
================================================================

status: DONE (2026-07-07)

goal
----

an external test program spawns `isabelle mcp_server`, speaks MCP to it, and
triggers the execution of a tool that is defined and registered in Isabelle/ML
inside a theory file. the round trip proves the whole chain:

  test client <--json-rpc/stdio--> scala server <--pide protocol--> ML tool

architecture decisions (from phase 0)
--------------------------------------

- the server is Isabelle/Scala: the existing `mcp` component's tool
  `isabelle mcp_server` (mcp/src/mcp-main.scala).
- transport: stdio, newline-delimited json-rpc 2.0 (the standard MCP stdio
  transport). the scala tool owns its stdin/stdout so no bridge process is
  needed; the test client just spawns the tool and writes/reads lines.
  IMPORTANT: nothing else may write to stdout — all logging/progress goes to
  stderr (Console_Progress(stderr = true)).
- the prover is driven through a headless pide session (isabelle.Headless,
  Pure/PIDE/headless.scala): the server starts the session, loads the tool
  theory with use_theories, keeps the session alive while serving.
- ML tools are string -> string for the mvp. structured schemas come later.
- ml<->scala serialization: yxml on the bridge (XML.Encode/Decode both sides),
  json only on the client edge. tool-call payloads for the mvp are plain
  strings, so only the tool *listing* needs structured encoding.

components / deliverables
-------------------------

1. ML tool api + registry: mcp/Tools/MCP_Tools.thy   (new session `MCP-Tools`
   in mcp/Tools/ROOT, based on Pure)

   ML structure MCP_Tool providing:

     type tool = {name: string, description: string, run: string -> string}
     MCP_Tool.register: tool -> unit        (* Synchronized.var registry *)
     MCP_Tool.list: unit -> tool list
     MCP_Tool.run: string -> string -> string   (* name, arg; error if absent *)

   the same theory registers the protocol commands (side effect at load time):

     Protocol_Command.define "MCP.tools"
       args: []
       reply: Output.protocol_message (Markup.function "MCP.tools_result")
              body = yxml list of (name, description) pairs
     Protocol_Command.define "MCP.run_tool"
       args: [id, name, arg]
       reply: Output.protocol_message with function "MCP.run_tool_result",
              properties [id, status = ok|error], body = result or message.
       tool exceptions must be caught and turned into status=error replies.

   and defines + registers one demo tool for the mvp test:

     name = "shout", description = "uppercase the input",
     run = String.map Char.toUpper  (ascii is fine for the mvp)

2. scala server: extend mcp/src/mcp-main.scala (or new files under mcp/src/,
   listed in mcp/etc/build.props sources)

   - MCP_Session: wraps isabelle.Headless.start_session; installs a
     Session.Protocol_Handler (via session.init_protocol_handler) whose
     functions handle "MCP.tools_result" and "MCP.run_tool_result",
     completing scala futures/promises keyed by id.
     ml_tools(): List[(String, String)]      -- protocol_command "MCP.tools"
     ml_run(name, arg): Result[String]       -- protocol_command "MCP.run_tool"
   - MCP_Server: blocking loop over stdin lines; isabelle.JSON for
     parse/print. methods for the mvp:
       initialize            -> protocolVersion, capabilities.tools, serverInfo
       notifications/initialized -> no reply (notification)
       ping                  -> {}
       tools/list            -> ml tools as [{name, description, inputSchema}]
                                with fixed inputSchema
                                {type: object, properties: {input: {type: string}},
                                 required: [input]}
       tools/call            -> ml_run(name, arguments.input); reply
                                {content: [{type: text, text: result}]}
                                ml status=error -> {..., isError: true}
       unknown method with id -> json-rpc error -32601
     malformed json -> error -32700; requests before initialize: allowed for
     the mvp (no handshake state machine).
   - tool wiring: isabelle mcp_server [-d DIR] [-s SESSION] [-T THEORY]
     defaults: -d mcp/Tools -s MCP-Tools -T MCP_Tools. tool exits when stdin
     closes (client hangup) and stops the pide session cleanly.

3. external test program: mcp/test/test_mcp.py (python3, stdlib only)

   - spawns: isabelle mcp_server (path to isabelle from $ISABELLE or the
     bundled Isabelle2025-2_linux/Isabelle2025-2/bin/isabelle)
   - sends initialize, waits for reply, sends notifications/initialized
   - tools/list -> asserts a tool named "shout" is present
   - tools/call name=shout arguments={input: "isabelle"} ->
     asserts content[0].text == "ISABELLE"
   - tools/call name=shout with missing arguments.input -> asserts isError
   - tools/call name=no_such_tool -> asserts isError or json-rpc error
   - closes stdin, asserts the server process exits (timeout, e.g. 30s)
   - exit code 0 iff all assertions pass; prints a one-line verdict per case
   - generous startup timeout: the first reply may take minutes on a cold
     build (scala_build + session load); make it configurable, default 600s.

implementation order
--------------------

- [x] ML side alone: mcp/Tools (ROOT + MCP_Tools.thy) with an ML self-test in
      the theory (\<^assert> (MCP_Tool.run "shout" "abc" = "ABC")).
      verify: isabelle build -d mcp/Tools MCP-Tools
- [x] scala MCP_Session: headless session + protocol handler + the two
      round trips. (mcp/src/mcp_session.scala; verified via the stdio
      smoke test below rather than the repl.)
- [x] scala MCP_Server stdio loop + json-rpc methods, wired into the
      mcp_server tool entry point. (mcp/src/mcp_server.scala,
      mcp/src/mcp-main.scala)
      verified by hand: printf '...initialize...\n...tools/list...\n' | isabelle mcp_server
- [x] test client mcp/test/test_mcp.py
- [x] run the full test suite (below) and record results here

results (2026-07-07): all green. test_mcp.py exits 0 with 10/10 PASS,
both warm and against a fresh build (heap cleaned with
`isabelle build -n -c -d mcp/Tools MCP-Tools`, mcp/lib/mcp.jar removed;
the server rebuilds both itself and the session heap on startup).

implementation note: with the default -s MCP-Tools the protocol commands
are already defined in the session heap (registered at build time), so
MCP_Session skips use_theories for theories in the session image;
use_theories is only used for a theory not in the base session
(e.g. -s Pure -T MCP_Tools).

testing
-------

- [x] build test: isabelle scala_build   (compiles the component)
- [x] ML regression: isabelle build -d mcp/Tools MCP-Tools
      (theory loads, registry works, demo tool asserted in ML)
- [x] integration: python3 mcp/test/test_mcp.py   (the mvp acceptance test)
- [x] negative paths covered by the test client: unknown tool, missing
      argument, malformed json line, clean shutdown on stdin close
      (plus: unknown method -> -32601, ping, inputSchema shape)
- [x] (2026-07-07, post-mvp) refactored for unit testing; see the
      "testing architecture" section below. all four layers green.

acceptance criteria
-------------------

the mvp is done when test_mcp.py exits 0 against a fresh build, i.e. an
external program connected to the server, listed the ML-registered tool,
ran it in the prover, and got the computed result back.

out of scope for the mvp (see phase 2 / later)
-----------------------------------------------

- structured tool input schemas / serialization design (see phase 0:
  "what kind of tool description/serialization")
- dynamic registration after session start (tools/list_changed notifications)
- resources, prompts, progress, cancellation, concurrent tools/call
- sessions other than Pure-based; user-supplied -d/-s/-T beyond the defaults
- auth (stdio transport inherits the spawning user's trust)
- cleanup of mcp/etc/settings and mcp/lib/Tool/mcp_server (known leftovers)


================================================================
testing architecture (added 2026-07-07, post-mvp refactor)
================================================================

the mvp was verified end-to-end only: test_mcp.py spawns the full
server (heap build + pide session + stdio). that remains the
acceptance test, but development needs fast unit tests. neither
isabelle/ml nor isabelle/scala ships a test framework; the idioms
used instead: ml — a test *session* whose theory fails to load on a
failed \<^assert>; scala — a dedicated `isabelle mcp_test` tool with
a hand-rolled runner (nonzero exit on failure).

update (2026-07-10, munit migration — full story: CHANGELOG "testing
infrastructure", plans/README "testing process"): the scala layers now
use munit 1.1.1 instead of the hand-rolled runner, vendored (with its
org.scalameta junit-interface fork — NOT com.github.sbt's, which
compiles but fails at runtime) in a SEPARATE component mcp_test/, so
mcp/lib/mcp.jar ships zero test code. suites: scala-unit over
Fake_Backend in mcp_test/src/mcp_handler_tests.scala; bridge suites
extending MCP_Session_Suite (one headless PIDE session per suite;
with_repl / slow_step / await_busy / eventually / expect_ok /
expect_error fixtures) in mcp_test/src/mcp_bridge_tests.scala; shared
infrastructure in mcp_test/src/mcp_testing.scala; suites registered in
mcp_test/src/mcp_test_tool.scala. `isabelle mcp_test` runs unit
suites, -b adds bridge (and heap) suites, -t PATTERN filters by
test-name substring. a fifth fixture, MCP_Heap_Suite
(mcp_test/src/mcp_heap_tests.scala), runs raw ML in a fresh
`isabelle ML_process` against a saved heap — the environment for
fresh-process/heap-restart claims, which neither build-time \<^assert>
theories (they run inside the building process) nor Fake_Backend can
reach. the pyramid below keeps its four layer names; only the
mechanism behind layers 2 and 3 moved.

the enabling refactor (no behavior change) separates pure logic from
transport on both sides:

- ml (mcp/Tools/MCP_Tools.thy): structure MCP_Protocol holds the
  handler bodies as pure functions — tools_body: unit -> XML.body,
  run_tool: string -> string -> string * string (status, output).
  the Protocol_Command.define wrappers are one-liners around them.
  Output.protocol_message stays out of unit tests on purpose: its
  behavior depends on the process context (raises Protocol_Message
  without a managing scala session), so the emit path is covered by
  the bridge and e2e layers instead.
- scala: trait MCP_Backend {ml_tools, ml_run, stop} (mcp_session.scala)
  is the seam between the json-rpc layer and the prover. MCP_Session
  implements it; tests substitute MCP_Test.Fake_Backend.
  MCP_Server.Handler(backend) holds the request logic (handle /
  handle_line, pure json in -> json out); MCP_Server.serve(backend,
  in, out, progress) is the loop over injectable streams; run() is
  just MCP_Session.start + serve on stdio. MCP_Session.decode_tools
  is exposed for testing the yxml decode.

test pyramid (fast to slow; results 2026-07-07 all green):

1. ml unit: isabelle build -d mcp/Tools MCP-Tools-Tests
   (mcp/Tools/Tests/MCP_Tools_Tests.thy: registry lookup, ordering,
   replace-by-name idempotence, unknown tool, tools_body
   encode/decode round trip, run_tool ok/error). a separate session
   in a separate directory — sessions cannot share a directory, and
   test registrations must not land in the production MCP-Tools heap.
   note the session-qualified import "MCP-Tools.MCP_Tools".
2. scala unit: isabelle mcp_test          (seconds, no prover)
   (mcp_test/src/mcp_handler_tests.scala over Fake_Backend: initialize
   w/ and w/o params, notifications, ping, unknown method w/ and w/o
   id, tools/list shape incl. inputSchema, tools/call
   ok/missing-input/missing-name/backend-error, -32700, -32600,
   serve loop: blank lines skipped, one reply per line, EOF ->
   backend.stop — plus every builtin's row + dispatch, resources,
   codecs; 75+ cases as of 2026-07-10).
3. bridge: isabelle mcp_test -b           (starts real sessions)
   (mcp_test/src/mcp_bridge_tests.scala: headless sessions on
   MCP-Tools and MCP-HOL — covers the protocol handlers and promise
   routing, the one layer a fake cannot; plus mcp_heap_tests.scala's
   fresh-ML_process heap suites).
4. e2e acceptance: python3 mcp/test/test_mcp.py (unchanged).

interactive testing:

- ml: isabelle console -d mcp/Tools -l MCP-Tools
  then call MCP_Tool.* / MCP_Protocol.* directly.
- scala: isabelle scala, then e.g.
    import isabelle.mcp._
    new MCP_Server.Handler(new MCP_Test.Fake_Backend)
      .handle_line("""{"jsonrpc":"2.0","id":1,"method":"ping"}""")

phase 2 guidance: implement the MCP.ir dispatcher under the same
split — a pure fname/args -> result dispatcher function, wrapped by
a thin async protocol command — and extend Fake_Backend with a fake
ir() so builtin tools are unit-testable without a prover. phase 2's
own "testing" section maps every feature to these four layers with
concrete cases.


================================================================
phase 2 — agentic proving
================================================================

status: IN PROGRESS

goal
----

expand the mvp (phase 1) to fully agentic proving: an mcp client can
explore theories, load them, check them, and conduct interactive proofs
(step/backtrack/edit, sledgehammer, find_theorems) — all through
`isabelle mcp_server`, headless, in one process.

strategy: reuse the I/R engine (ir/ir.ML, MIT-licensed) as the proving
core, driven over our existing protocol-command bridge instead of its
TCP stack. see the architecture-iq-ir-mcp memory / iq-ir-reference for
the analysis behind this.

what we reuse from I/R and what we don't
----------------------------------------

reuse:

- ir/ir.ML verbatim (keep the copyright header): structure Ir — named
  REPLs as lists of (isar text, Toplevel.state) steps; step / edit /
  replay / truncate / back / fork / merge / pin / rebase; init from
  loaded theories (Thy_Info + Theory.begin_theory + Toplevel.make_state),
  from theory segments ("Thy:idx", needs record_theories heaps), or from
  a PIDE document command (Document.state() -> Command.eval_result_state
  — works in our headless session); sledgehammer via deferred
  ML_Compiler.eval; find_theorems; per-repl timeouts; claim-based
  concurrency guard.
- the output-routing idea from ir/ml_repl.ML: wrap Private_Output.*_fn
  once, route by Future worker-group ancestry to a per-request sink.
  we replace its Message_Channel with (a) an accumulating buffer and
  (b) one protocol message per finished request.
- the mcp tool surface of ir/mcp_server.py (names, arguments, docstrings)
  as the template for our tool schemas.

do not reuse:

- tcp_handler.ML, the TCP parts of ml_repl.ML — our transport is the
  PIDE protocol we already have.
- repl.py / mcp_server.py — our scala server plays both roles.
- the standalone Bash.Server plumbing (-o bash_process_address/...) —
  only needed for raw ML_process; our headless PIDE session already
  provides the bash bridge, so sledgehammer's external provers work
  without extra wiring.

sessions and file layout
------------------------

- mcp/Tools/HOL/ir.ML: symlink to ../../../ir/ir.ML (same trick iq
  uses), so the engine has a single source of truth. the HOL/
  subdirectory is forced: sessions cannot share a directory
  (sessions.scala "Duplicate use of directory"), and mcp/Tools/ is
  MCP-Tools' — same pitfall as Tests/.
- mcp/Tools/HOL/MCP_Repl.thy (new): imports Main + MCP-Tools.MCP_Tools;
  ML_file "ir.ML" + the bridge ML below.
- new session in mcp/Tools/ROOT:

    session "MCP-HOL" in "HOL" = HOL +
      options [record_theories]
      sessions "MCP-Tools"
      theories MCP_Repl

  agentic proving needs HOL (sledgehammer, Main); the Pure-based
  MCP-Tools session stays as the mvp regression target. record_theories
  makes Thy_Info.get_theory_segments work for theories built *in this
  session*, i.e. Ir.init "R" ["MCP-HOL.Some_Thy:42"]. note: segments for
  HOL-image theories (Main etc.) require rebuilding HOL itself with
  -o record_theories=true — document as optional, don't require it.
- server default stays -s MCP-Tools for now; agentic runs use
  `isabelle mcp_server -s MCP-HOL`. flip the default when phase 2 lands.

ml bridge: async protocol command (the key new mechanism)
---------------------------------------------------------

the mvp's MCP.run_tool runs the tool synchronously inside the protocol
command handler. that blocks the ML protocol loop — fine for "shout",
wrong for a 30s sledgehammer (it would stall the whole session,
including other protocol commands). phase 2 adds an async variant:

  Protocol_Command.define "MCP.ir"    (* args: [id, fname, yxml_args] *)

argument encoding (decided 2026-07-09): named args in yxml, not
positional strings. yxml_args is one chunk holding an association list
of (key, value) string pairs — XML.Encode.list (XML.Encode.pair string
string) on the scala side, the mirror decode in the dispatcher —
with list-valued arguments (repl_init's theories) encoded as repeated
keys. rationale:

- phase 1 already fixed "yxml on the bridge, json only on the client
  edge"; this applies the same rule to calls, not just the tool
  listing.
- the tool surface has optional args (find_theorems.max_results,
  sledgehammer.timeout_secs, repl_init_from_source's offset/pattern/
  index alternatives) and one list arg — positional encoding handles
  neither without arity variants or empty-string sentinels.
- client-edge json objects and bridge pair lists stay isomorphic: each
  builtin's run function converts its json arguments near-identically,
  no per-tool positional mapping to keep in sync.
- protocol-command chunks are byte-clean, so isar text with newlines
  and symbols passes through untouched.
- values stay strings on the wire ("5", "true"); the dispatcher parses
  with Value.parse_int etc. and returns a typed status error on
  garbage — missing required key = the same error path; missing
  optional key = default. the dispatcher stays dumb.

behavior:

1. create a fresh Future group; register id -> group in a Synchronized
   routing table together with an output buffer (Synchronized string
   list ref).
2. install (once, lazily) Private_Output wrappers a la ml_repl.ML:
   inside a registered group's descendants, writeln/warning/error/
   tracing append to that request's buffer; everywhere else fall through
   to the original fns. this captures Ir's `out = writeln` output
   without touching ir.ML.
3. fork the evaluation via Future.forks {group, pri = ~1, interrupts}:
   dispatch fname to the Ir.* function (see table below) with parsed
   args. no ML_Compiler.eval for the standard surface — a closed
   dispatcher, so no ML injection through mcp tools.
4. on completion (Exn.capture): emit
     Output.protocol_message
       [Markup.function "MCP.ir_result", ("id", id), ("status", ...)]
       [buffer contents]
   and drop the routing entry. the scala side resolves the pending
   promise by id — the exact pattern MCP.run_tool_result already uses.
5. markup: prover output arrives as YXML (PIDE print mode). strip on
   the scala side with XML.content(YXML.parse_body(...)) — lossless,
   no ML changes, and keeps the door open for structured output later.

cancellation (later, cheap): keep the group in the routing table;
"MCP.ir_cancel" id -> Future.cancel_group. Ir's claim mechanism already
keeps a cancelled/failed step from corrupting the repl.

dispatcher table (fname -> Ir call), mirroring mcp_server.py:

  init, init_from_document, fork, step, show, state, text, edit,
  replay, truncate, back, merge, pin, unpin, rebase, remove, repls,
  theories, load_theory, source, source_map, sledgehammer,
  find_theorems, timeout

plus one fname that is new ML rather than an Ir call (ir.ML stays a
verbatim reuse): entities (added 2026-07-10 for isabelle://theory/
{name}/entities' image tier — Name_Space enumeration filtered by
Name_Space.theory_name, MCP_Repl.thy). fnames taking a theory_name
expect the canonical Thy_Info key; the scala side normalizes client
spellings before dispatch (see "theory-name spelling" under mcp
resources).

note on Ir.load_theory: it refuses when Printer.show_markup_default is
set — true in our headless PIDE session. keep the refusal for now;
theory loading goes through the scala path below. revisit only if
Thy_Info-loading inside a headless session proves safe.

theory loading and checking: two registries, one story
--------------------------------------------------------

fact: scala use_theories (PIDE documents) and ML Thy_Info populate
*disjoint* registries. the plan embraces that:

- load/check = scala side. new MCP_Session methods wrapping the
  headless session: use_theories (build/check a theory from disk,
  collect per-node status + error messages with positions — template:
  Server_Commands.Use_Theories in Pure/Tools/server_commands.scala).
  CORRECTED (2026-07-10, wave 2 implementation): the original
  "check_theory must purge before re-loading" premise was wrong and
  actively harmful — Headless.Resources.purge_theories only updates
  its own bookkeeping and never pushes purge_edits to the live prover
  document, so a manual purge desyncs the two and the next reload
  corrupts the document (observed: duplicated theory headers). no
  purge is needed: use_theories re-reads the file and diffs against
  its own correctly-tracked prior content on every call, so a plain
  re-run already reflects the file currently on disk. unload_theory
  goes through Resources.clean_theories (unload + purge +
  session.update in one step), the one purge path that stays in sync.
  full story: CHANGELOG 2026-07-10 ("wave 2"), plans/check_theory.
- prove = ML side. to attach a repl to a use_theories-loaded document:
  scala resolves (theory, offset | pattern | command index) to a
  command id from the snapshot, then calls Ir.init_from_document
  node_name command_id via MCP.ir. for image theories, Ir.init
  (Thy_Info) works directly, plus "Thy:idx" via recorded segments.

mcp tools (new, structured)
---------------------------

two tool kinds now exist:

1. user tools: the mvp MCP_Tool registry, string -> string, fixed
   {input: string} schema. unchanged — still the extensibility story.
2. builtin tools: implemented in scala (calling MCP_Session methods or
   MCP.ir), each with a real json schema. mcp_server.py's docstrings
   are the template. tools/list returns both kinds.

advertised tool metadata (decided 2026-07-09): what tools/list must
and may carry per tool (verified against the mcp spec, 2025-06-18
revision — the version this server targets):

- required: name (owned by the naming scheme below) and inputSchema —
  a json schema object, mandatory even for zero-arg tools
  ({"type": "object"}).
- description: optional on the wire, mandatory for us — it is the only
  text the model reads to decide when to call the tool. builtins:
  hand-written from mcp_server.py's docstrings; wrapped diagnostic
  commands: default from the command's outer-syntax comment,
  overridable (see the mcp_tool command below). there is NO protocol
  slot for examples — usage examples ride inside description strings
  (tool-level and per-property), or in the agent-facing skills.
- annotations: advisory hints, untrusted by clients, but free and
  honest for us. diagnostic-wrapped tools get {readOnlyHint: true,
  idempotentHint: true} — exactly what the Keyword.is_diag gate
  guarantees. builtins: readOnlyHint true for everything except the
  mutating surface (load_theory/unload_theory/check_theory, repl_*,
  scope_*) — refined 2026-07-09 (plans/repl_list): repl_list and its
  read-only siblings repl_show/repl_state/repl_text only read the repl
  table (Ir.repls/show/state/text are Synchronized.value reads plus
  formatting, no state change) and get {readOnlyHint: true,
  idempotentHint: true} like diagnostic-wrapped tools; the mutating
  repl_* bucket narrows to repl_init/repl_init_from_source/repl_fork/
  repl_remove/repl_step/repl_edit/repl_replay/repl_truncate/repl_back/
  repl_merge/repl_timeout/repl_pin/repl_unpin/repl_rebase. further
  refined 2026-07-09 (plans/repl_remove): repl_remove (and, by the same
  reasoning, repl_truncate) destroy state irrecoverably, so within the
  mutating bucket they additionally get {destructiveHint: true}. all our
  tools are openWorldHint: false (they never leave the prover
  process).
- outputSchema: omitted everywhere in phase 2. declaring one obliges
  the server to return conforming structuredContent on every call;
  our results are captured prover output (free-form text), so the
  obligation cannot be met. structured output is phase 3+ (the yxml
  stripping already keeps the markup door open).
- title: skipped (display sugar; name is descriptive enough).

schema representation: standard json schema, top level always
{"type": "object"} with properties/required; stay in the common
subset clients actually consume (types, description, enum, required,
items, default). per tool the schema is STATIC data fixed at
registration and shipped verbatim in every tools/list reply — what is
dynamic is the tool LIST (list_changed on registration), never an
existing tool's schema. consequences:

- builtin schemas are hand-written in scala, one per tool; the json
  property names are exactly the yxml pair keys of the argument
  encoding above, keeping the client edge and the bridge isomorphic.
- ML-registered tools have exactly two possible shapes, determined by
  the registration form, not by user input: diag_wrap (wrapped
  diagnostic command) -> {input: string (required), repl: string
  (optional)}; string_fun (ML escape hatch, string -> string) ->
  {input: string (required)}. the registry therefore carries a form
  tag, not schema json — the scala side expands the tag into the
  actual schema (and the matching annotations) at the tools/list
  merge. no schema generation in ML, no schema DSL in isar; a
  user-declared params clause is phase 3+.

tool naming and collisions (decided 2026-07-09): namespace by
registering theory. MCP has no first-class namespace concept — tool
names are flat strings, unique per server; hosts already namespace by
*server* (claude code exposes mcp__<server>__<tool>), and prefixing
inside the flat name is the sanctioned within-server pattern
(anthropic's own tool-writing guidance recommends service prefixes).
one hard constraint: the messages api requires tool names to match
^[a-zA-Z0-9_-]{1,64}$ — theory long names contain dots
(HOL-Library.Multiset), so they cannot appear raw in an exposed name.
the scheme:

- no conflict -> bare name (the common case; today's behavior).
- conflict -> the non-builtin tool is exposed as
  <theory base name>__<tool>, e.g. Multiset__find_consts. base name
  keeps the regex and the 64-char cap with room to spare; if base
  names themselves collide, escalate to the sanitized long name
  (dots -> "_": HOL-Library_Multiset__find_consts).
- builtins always keep the bare name — they are the server's own api
  surface. two *ML* tools colliding across theories both get
  qualified (symmetric; no first-wins ordering dependence).
- mechanism: the registry records the registering theory per tool.
  the mcp_tool isar command knows its theory trivially; the ML escape
  hatch captures Context.theory_long_name from the ambient context at
  load time (fallback "ML" outside any theory). the MCP.tools payload
  grows from (name, description) pairs to (name, description, theory,
  form_tag) quadruples — form_tag is the schema shape from "advertised
  tool metadata" above. exposure names are computed scala-side at the
  tools/list
  merge point as a pure function of (builtin table, ML registry) — no
  naming state, tools/call resolves incoming names through the same
  function, unit-testable against Fake_Backend.
- registry semantics shift accordingly: replacement is per
  (theory, name), so re-executing a theory stays idempotent while
  same-named tools from different theories coexist under their
  qualified names.

dynamic registration -> notifications/tools/list_changed:

registering a new tool at runtime — whether from ML (MCP_Tool.register)
or from Isar (the mcp_tool command below, which is sugar over the same
registry) — MUST push notifications/tools/list_changed from the server
to the client. mechanism, in one place so both paths get it for free:

- MCP_Tool.register ends with Output.protocol_message
  (Markup.function "MCP.tools_changed") [] — fire-and-forget, no
  payload; the client re-queries tools/list, which reads the registry
  fresh anyway (mvp already does, no cache to invalidate).
- the scala side handles it in the MCP.ir_result-style protocol
  handler and emits the json-rpc notification
  notifications/tools/list_changed on stdout.
- the server declares the capability in initialize:
  tools: {listChanged: true}.
- registrations baked into the heap at build time fire the message
  into the build process where nobody listens — harmless, and correct:
  at serve time those tools are already in the initial tools/list.
  the notification only matters for registrations after session start
  (a load_theory pulling in a theory that calls mcp_tool / the ML
  escape hatch, or direct ML evaluation).
- same story for mcp_resource / MCP_Resource registration ->
  notifications/resources/list_changed (scope changes already fire it,
  see scoping; registration is just another trigger through the same
  emit point).

theory management:

  load_theory   {name, master_dir?}    -> use_theories; per-node ok/
                                          errors(+positions) as text
  unload_theory {name}                 -> unload + purge
  check_theory  {name}                 -> reload & report diagnostics

repl lifecycle (thin wrappers over MCP.ir):

  repl_init             {repl, theories: [string]}
  repl_init_from_source {repl, theory, offset? | pattern? | index?}
                        (scala resolves to command id or segment idx)
  repl_fork             {repl, new_repl, state_idx}
  repl_remove           {repl}
  repl_list             {}

stepping:

  repl_step             {repl, isar_text}
  repl_state            {repl, state_idx}     (0=base, -1=latest)
  repl_show             {repl}
  repl_text             {repl}
  repl_edit             {repl, idx, isar_text}
  repl_replay           {repl}
  repl_truncate         {repl, idx}
  repl_back             {repl}
  repl_merge            {repl}
  repl_timeout          {repl, secs}
  repl_pin / repl_unpin / repl_rebase   {repl}

proof search:

  sledgehammer          {repl, timeout_secs?}
  find_theorems         {query, repl? | theory?, max_results?}

  find_theorems context promotion (decided 2026-07-12): repl is no
  longer required. the search needs only a context, not proof state,
  so the tool takes the same context selectors as find_definition
  below — repl (that repl's latest state, goal-aware mid-proof) OR
  theory (a loaded/image theory's global context, normalized per
  "theory-name spelling"), mutually exclusive, default = the base
  image's startup theory. this makes the entire read-only surface
  usable without ever creating a repl; goal-based criteria
  (intro/solves) still require a repl mid-proof and error cleanly
  otherwise (behavior already pinned in plans/find_theorems T4).
  the shipped wave-1 tool required repl; the delta is tracked in
  plans/find_theorems ("context promotion" section).

navigation (go to definition — reuse PIDE data, no fuzzy search):

background: iq's get_definitions guesses names (base ^ "_def",
base ^ ".simps", ...), which breaks because a name can be introduced by
many commands (definition, fun, inductive, datatype, locale, class,
...). the prover already records the truth twice, and we use both:

1. every binding, whatever command created it, goes through a
   Name_Space; Name_Space.the_entry returns {theory_long_name,
   pos: Position.T, serial, ...} — the authoritative definition site.
2. every *use* site in a checked document carries entity markup whose
   properties include def_file / def_line / def_offset / def_id
   (Position.make_entity_markup; scala side: Position.Def_File etc.,
   Rendering.entity_elements). this is exactly what jEdit ctrl-click
   uses, and it works headless from any Document.Snapshot.

tools:

  find_definition       {name, kind?, repl? | theory?}
      name-based lookup via MCP.ir: query the context's name spaces.
      kind ranges over {const, type, class, fact, locale, method,
      attribute}; omitted = search all. spaces: consts via
      Sign.const_space, *types via Sign.type_space* — this one entry
      point covers every type-introducing command (datatype, typedef,
      type_synonym, record, codatatype, ...), since they all allocate
      their constructor in the type name space; classes, facts
      (Proof_Context.facts_of), locales, methods, attributes likewise.
      for each hit return kind, full internal name, and the definition
      position. when the position falls in a theory with recorded
      segments (or a loaded PIDE document), resolve offset -> command
      and return the *defining source text* — the whole datatype/
      typedef/fun/inductive/... block — via the source_map machinery,
      not just a location. note for types: the block also surfaces
      what the name space alone can't (constructors, field names,
      the abstraction predicate of a typedef).
  goto_definition       {theory, offset | pattern}
      position-based (mirror of ctrl-click): scala side, cumulate
      Rendering.entity_elements over the snapshot at the resolved
      range, extract def_file/def_line/def_offset from the entity
      markup properties. covers anything under the cursor — constants,
      facts, *type constructors in annotations and signatures*, ML
      antiquotations — with zero name guessing: entity markup carries
      its kind property (Markup.kind = "type_name", "constant", ...),
      which the reply passes through. requires the theory to be
      use_theories-loaded (snapshots only exist for checked documents;
      for image theories fall back to find_definition).

isar commands: mcp_tool / mcp_resource (no-ML user wiring)
------------------------------------------------------------

the happy path for users: expose existing prover machinery through the
mcp server by writing *Isar*, not ML. MCP_Tools.thy declares two outer
syntax commands (header: keywords "mcp_tool" "mcp_resource" :: thy_decl)
that users invoke in their own theories:

registering tools:

  mcp_tool find_consts
      wraps an existing *diagnostic* command as an mcp tool. at
      registration: Outer_Syntax.check_command validates the name,
      Keyword.is_diag restricts to diagnostic commands (they cannot
      mutate state, so wrapping is always safe). at call time the tool
      takes {input: string, repl?: string} and runs the isar text
      "find_consts " ^ input via exec_text against either the given
      repl's latest state or (default) Toplevel.make_state of the
      registering theory; captured output is the result. zero ML.
      default description comes from the command's outer-syntax comment
      (the same text `help` shows), overridable:

  mcp_tool find_consts (description "search constants by type pattern")

  mcp_tool shout = \<open>String.map Char.toUpper\<close>
      the ML escape hatch: sugar for MCP_Tool.register, string -> string,
      keeps the mvp schema {input: string}.

registering resources:

  mcp_resource a_user_defined_simpset
      bare-name form: exposes a named fact / dynamic fact (e.g. a
      named_theorems collection backing a user simpset) as the resource
      isabelle://named/a_user_defined_simpset. read = pretty-print
      Proof_Context.get_thms at *read time*, so dynamic collections
      stay current as the theory grows. validated at registration.

  mcp_resource simpset_dump (isar \<open>print_simpset\<close>)
      diagnostic-command form: resource content = captured output of
      the isar text run against the registering theory's context.

  mcp_resource goal_hints = \<open>fn ctxt => ... : string\<close>
      ML escape hatch: Proof.context -> string, evaluated at read time.

plumbing:

- registrations land in the same registries the server already queries
  (MCP_Tool, plus a new MCP_Resource mirror: {name, description,
  read: unit -> string}), so heap persistence and live visibility work
  exactly like the mvp: baked into the image at build time, or
  registered on the fly when a user theory is loaded via load_theory.
  the server queries the registries per tools/list / resources/list
  call (mvp already does), so no cache invalidation. every registration
  through either registry additionally pushes the matching list_changed
  notification (tools or resources) to the client — see "dynamic
  registration -> notifications/tools/list_changed" above; the emit
  lives in MCP_Tool.register / MCP_Resource.register, so the isar
  commands and the ML escape hatches are covered identically.
- registration is a side effect on a Synchronized registry, same
  tradeoff the mvp already accepts (replacement keyed on
  (theory, name) — see "tool naming and collisions" — keeps
  re-execution idempotent while cross-theory duplicates coexist). the isabelle-idiomatic refinement — Theory_Data with
  merge, queried from a designated context — is noted as future work;
  it matters for interactive PIDE editing, not for headless use.
- the wrapped-command tools depend on exec_text + output capture from
  the MCP.ir bridge above, so this feature builds on phase 2's ML
  plumbing, not on new machinery.

mcp resources (read-only exploration)
---------------------------------------

resources answer "what is there to look at" without side effects; every
read maps to snapshot data (scala) or a read-only MCP.ir call (ML).

design principle: lazy everywhere. a session sitting on HOL knows
hundreds of theories and tens of thousands of entities — dumping that
at an agent buries the signal. concretely:

- resources/list returns *metadata only* (uri, name, one-line
  description, size hint where cheap); content is computed exclusively
  at resources/read time. nothing is pre-rendered or cached eagerly.

advertised resource metadata (decided 2026-07-09), per the mcp spec
(2025-06-18 revision): only uri and name are required (templates:
uriTemplate per rfc 6570 instead of uri). we additionally send:

- description: one line, always (same argument as for tools).
- mimeType: "text/plain" uniformly — everything served is
  pretty-printed text.
- annotations.audience: ["assistant"] — these resources exist for the
  model, not a human resource picker.

deliberately absent, both consequences of read-time evaluation:

- size: only where a cheap stat answers it (file-backed theory
  source); never for computed content — knowing the size would mean
  rendering at list time, violating the lazy principle.
- lastModified: dynamic fact collections and snapshots have no change
  tracking; a wrong timestamp is worse than none.

template variables (isabelle://theory/{name} etc.) can later be wired
to the mcp completion api for theory-name completion — phase 3+.
- resources/list never enumerates the image (or the wider session
  structure) unbidden: only scoped items appear concretely, and the
  default scope is just the working set (below). scope_add can pull
  any theory the session structure knows — image or filesystem —
  into the listing, where each entry carries its availability tier
  (see "exploring the library universe" below); everything else
  stays reachable through templates.
- large reads are sliceable via uri query parameters, so the agent
  pulls windows instead of documents:
    isabelle://theory/{name}?lines=120-180          (source slice)
    isabelle://theory/{name}/commands?start=40&count=20
    isabelle://theory/{name}/entities?kind=type&prefix=foo
  a read without parameters on an oversized resource returns the first
  window plus a "truncated, N total, use ?start=" note instead of the
  full payload.
- resources/list supports the mcp pagination cursor (nextCursor) from
  day one, so even the scoped listing never floods a single reply.
- read-time evaluation (already the rule for isabelle://named/...)
  applies to everything: diagnostics, entities, command maps are
  recomputed from the current snapshot/registry on each read.

scoping: the agent controls what resources/list shows. the server keeps
a scope = a set of theory names/patterns (plus, implicitly, all active
repls and mcp_resource registrations). resources/list enumerates only
in-scope items; resources/read works on ANY valid uri regardless of
scope — scope filters discovery, not access. builtin tools:

  scope_add       {patterns: [string]}   glob over long theory names,
                                         e.g. "HOL-Library.*", "Main"
  scope_remove    {patterns: [string]}
  scope_show      {}                     current scope + match counts

default scope: theories loaded via load_theory + active repls + named
resources — i.e. exactly what the agent is working on; the HOL image
stays discoverable via find_definition / find_theorems / templates but
does not clutter the listing. loading a theory auto-adds it to scope;
unload_theory removes it. scope changes fire
notifications/resources/list_changed (when the client advertises
support; otherwise harmless to skip).

concrete resources (resources/list, scope-filtered):

  isabelle://session
      the *running* session: name, dirs, loaded theories (Thy_Info
      names + PIDE nodes with status), active repls, current scope.
      backing: Ir.theories + scala session state. this is the cheap
      always-there overview — reading it never triggers proving or
      file io. (enumerating the session *structure* — everything
      buildable, loaded or not — is list_sessions/list_theories, see
      "exploring the library universe" below.)

resource templates (resources/templates/list):

  isabelle://theory/{name}
      theory source text. PIDE-loaded: snapshot node source; image or
      filesystem theory: file content via the session-structure path
      map (see "exploring the library universe" below).
  isabelle://theory/{name}/commands
      the navigation map: Ir.source_map output (index, keyword, line,
      offset, file per command). this is how an agent picks attach
      points for repl_init_from_source.
  isabelle://theory/{name}/diagnostics
      errors/warnings with positions from the PIDE snapshot (only for
      use_theories-loaded theories; image theories report "checked at
      build time"; filesystem theories report "not checked —
      load_theory to check").
  isabelle://repl/{id}
      Ir.show output (origin, steps, staleness, pin state).
  isabelle://repl/{id}/text
      Ir.text output (concatenated isar — what you'd paste into a
      theory file once the proof is done).
  isabelle://named/{name}
      user-registered resources from the mcp_resource command (named
      facts, diagnostic-command output, ML generators); listed
      concretely in resources/list since the names are known.
  isabelle://theory/{name}/entities
      entities *defined* in the theory with kinds and positions —
      consts, types (datatype/typedef/type_synonym/record/...),
      classes, facts, locales — from name-space entries filtered by
      theory_long_name (image theories) or entity-def markup in the
      snapshot (PIDE-loaded), replacing iq's regex-based get_entities.
      filterable: ?kind=type&prefix=... (see lazy design above).

theory-name spelling in {name} (decided 2026-07-10, after the entities
name-qualification issue): clients may use whatever spelling they know
— base name ("MCP_Repl", the -T spelling), session-qualified
("MCP-HOL.MCP_Repl"), or a foreign qualifier for a theory whose
canonical key is unqualified ("HOL.Main" for "Main"). the server
normalizes to the canonical long-name key (verbatim match, then
qualified by the running session, then a UNIQUE base-name match over
the image; unknown/ambiguous falls out of the image tier), because
both scala's loaded_theories and ML's Thy_Info key by canonical long
names, and that keying itself mixes qualified and unqualified entries
("HOL.Wellfounded" but plain "Main"). the resolved key — never the
client's spelling — is what crosses the ir bridge; anything else hits
exact-lookup errors (mcp_session.scala's image_theory; see CHANGELOG
2026-07-10 "entities name qualification").

later (not this phase): resources/subscribe on diagnostics via
session.commands_changed — design sketched in "out of scope (phase 3+)"
below; listChanged on repl creation/removal (scope
changes already fire it, see scoping above).

resource-read tool mirrors (flagged 2026-07-12, NOT implemented, not
scheduled): mcp client support for resources is uneven — tools are
what models call autonomously, resources are often client-mediated
(pickers, mentions) or absent. if a target client turns out unable to
read resources, the escape hatch is two builtin tools that are thin
aliases over the exact backends the templates already use:

  read_theory    {name, lines?}           = isabelle://theory/{name}
                                            (?lines= slicing included)
  list_entities  {theory, kind?, prefix?} = .../entities

no new mechanism: same theory-name normalization, same tier
resolution, same lazy/truncation rules; only the surface duplicates.
deliberately NOT built now — the known clients read resources, and
every mirror is a second name for the model to choose between.
sketches: plans/read_theory, plans/list_entities (status: flagged).
trigger to revisit: an evals/ failure showing an agent that cannot
reach theory source or entities through resources/read.

exploring the library universe (theories outside the heap)
-----------------------------------------------------------

limitation (verified 2026-07-08 against the bundled distribution): the
semantic layer only sees the base image. the HOL heap contains 119
theories (everything up to Complex_Main — so HOL.Real and HOL.Complex
*are* in it), but src/HOL holds 1467 .thy files: HOL-Library (148
theories), HOL-Analysis (104), and every other unbuilt session are
invisible to Thy_Info, the name spaces, and therefore to find_theorems
/ find_definition / entities. the same goes for all of the AFP. an ML
process has exactly one ancestry of loaded theories; there is no
cross-heap semantic query (fixing that is Find_Facts territory — see
out of scope). without dedicated machinery the agent cannot even
*discover* that HOL-Library.Multiset exists, let alone read it.

three availability tiers, which every exploration answer should name:

  image       in the base heap: full semantic queries via MCP.ir
              (name spaces, find_theorems, entities), source readable.
  loaded      use_theories documents: everything image theories have,
              plus snapshots, markup, live diagnostics.
  filesystem  in the session structure but in no loaded heap: source
              readable and text-searchable, NO semantic queries until
              promoted (load_theory) — discovery must not pretend
              otherwise.

backing mechanism (scala, verified: no heaps needed, runs in seconds):
Sessions.load_structure(options, dirs) + Sessions.deps enumerate every
session (134 in the bare distribution), its theories, and their source
paths — pure ROOT parsing. computed once at server startup from the
same dirs the server was given (-d), so a registered AFP (its ROOTS on
the component path or passed via -d) shows up with zero extra work.
keep the deps object around; it also answers uri -> path resolution
below.

builtin tools (scala-side, no ML involved):

  list_sessions  {pattern?}            session names (glob-filtered) +
                                       chapter, description, and
                                       heap_present flag (Store lookup);
                                       marks the current base image.
  list_theories  {session}             theory long names + tier
                                       (image/loaded/filesystem) +
                                       source path.
  search_sources {pattern, sessions?,  regex/literal grep over the
                  max_results?}        source files of the matching
                                       sessions (default: the base
                                       image's ancestry plus the
                                       sessions owning scoped or
                                       loaded theories — scope
                                       patterns are theory-level, so
                                       they widen the default by
                                       their owning sessions);
                                       returns {theory, line,
                                       snippet} hits.
                                       this is the discovery bridge:
                                       semantic search cannot see
                                       unloaded theories, text search
                                       can. cap results hard (lazy
                                       design as everywhere).

resource resolution widens accordingly: isabelle://theory/{name} (and
/commands) resolves filesystem theories through the session-structure
path map, so reading HOL-Library.Multiset works without loading it.
/diagnostics and /entities answer with the tier for non-loaded,
non-image theories ("filesystem theory — not checked; load_theory to
get semantics") instead of erroring opaquely. listing follows the one
scope rule: filesystem theories appear in resources/list exactly when
scope patterns match them, tier-tagged, same as image theories —
nothing outside the scope is enumerated, everything valid is readable.

loaded-tier membership (decision 2026-07-11): a theory is "loaded"
iff it has a node in the live PIDE document — including theories
pulled in TRANSITIVELY as imports of an explicitly load_theory'd one,
and imports an agent splices into a header on disk, picked up at the
next load_theory/check_theory (use_theories recomputes the dependency
closure from the header on every call; the import graph is never
cached across calls, so dependency edits are absorbed by
construction). the wave-2 implementation under-reports here: it keys
the loaded tier off the explicit-load bookkeeping (theory_master_dirs
in mcp_session.scala), so a transitive import answers the filesystem
"not checked" line on /diagnostics moments after being checked live.
fix (wave 3, plans/session_structure): tier resolution and the
/diagnostics and /entities node lookup read the current document
version's (non-empty) nodes; theory_master_dirs stays as the record
of EXPLICIT loads only — which remains what unload_theory keys on:
unloading a transitive import alone stays a "was not loaded" error
(it was never requested), and clean_theories sweeps it automatically
when its last importer is unloaded, reverting it to filesystem tier.

promotion and its cost: load_theory {name} with a session-qualified
name resolves through the same structure and use_theories-checks it
live — full semantics, price = checking time (imports outside the base
image get checked transitively; a deep HOL-Library or AFP theory can
take minutes). the cheap alternative when a whole session is needed:
build its heap (isabelle build -b SESSION) and restart the server with
-s SESSION — the startup base image is the coarse-grained exploration
scope; load_theory is the fine-grained one. the isabelle-mcp-exploring
skill (see implementation order) teaches this cost model and the
discovery chain explicitly.

AFP: with an AFP checkout registered as a component (or its dirs
passed via -d), list_sessions/list_theories/search_sources and source
reads cover it with nothing built; semantic exploration needs
load_theory (slow, per-theory) or an AFP session heap as base image.
without a local AFP there is nothing to serve — fetching sources is
the host agent's job (web tools), not the server's.

editing theories and persisting changes
----------------------------------------

policy: the server never writes theory files. editing and persistence
belong to the mcp client — the host agent (claude code etc.) has its
own file tools, user-visible diffs, permissions, and git. the server
verifies and navigates around the client's edits; a server write path
would be an invisible second writer racing the user's editor. the
splice is also textual, not semantic: a repl holds a linear list of
steps from its attach point and knows nothing about surrounding
comments or formatting — the client, which sees the whole file, is
the right place to do it.

the edit loop (the workflow the isabelle-mcp-proving skill teaches,
see implementation order):

1. load & diagnose: load_theory {name} -> per-command errors with
   positions.
2. orient: read isabelle://theory/{name} (source, sliceable),
   .../commands (the index/keyword/line/offset map — splice spans
   come from here), .../diagnostics, .../entities.
3. attach: repl_init_from_source at the failing (or interesting)
   command; base state = the prover state just before it.
4. iterate in the repl: repl_step / repl_back / repl_edit /
   sledgehammer / find_theorems. repl_edit edits a repl *step*,
   never the file; fork/merge merge repls into repls. the repl is
   an ephemeral proof workspace.
5. extract: repl_text — verified isar text.
6. persist, client side: the host splices the text into the .thy
   with its own edit tools, at the spans from step 2.
7. re-verify: check_theory {name} re-reads the file from disk ->
   green, or new positioned errors (loop back to 3).

new theories work the same way: prototype in a repl rooted at Main,
extract the body, the client writes the theory ... begin/end file,
load_theory checks it.

assumption: the host has file tools. a host without them (bare
claude desktop, web clients) cannot persist at all; if such hosts
become a target, the server-side write path moves up from phase 3 —
see out of scope for its recommended shape.

implementation order
--------------------

- [x] symlink mcp/Tools/HOL/ir.ML, write MCP_Repl.thy (ML_file + output
      wrappers + MCP.ir dispatcher + ML self-test: init from Main,
      step "lemma True", step "by simp", assert PROOF_COMPLETE), add
      MCP-HOL session with record_theories.
      verify: isabelle build -d mcp/Tools MCP-HOL
      done: builds green (self-test asserts pass; MCP-Tools-Tests still
      green). notes: session lives in mcp/Tools/HOL/ (duplicate-directory
      rule, see "sessions and file layout"); Main is registered in
      Thy_Info as "HOL.Main" — the self-test resolves the long name;
      MCP_Repl.reset () runs after the build-time self-test because
      process startup re-assigns Private_Output fns, so wrapper state
      must not persist in the heap (wrappers re-install lazily and
      double-install is harmless — they fall through when no buffer
      is registered).
- [x] scala: MCP_Session.ir(fname, args) with promise-by-id on
      MCP.ir_result (clone of ml_run); named args encoded as the yxml
      (key, value) pair list (see "argument encoding"); yxml stripping
      of results.
      done (2026-07-09/10, wave 0): every wave-1 builtin rides it; the
      async property (a slow call does not block a fast one) is pinned
      at the bridge layer (mcp_bridge_tests.scala "ir bridge: async").
- [x] scala: MCP_Session.load_theory/unload_theory/check_theory via
      headless use_theories + diagnostics extraction.
      done (2026-07-10): load_theory/check_theory both just call
      session.use_theories and render per-node ok/error + positioned
      messages; unload_theory goes through Headless.Resources.
      clean_theories (unload + purge + session.update in one step) --
      NOT session.purge_theories directly, which was tried first and
      found to corrupt the document (it never pushes its purge_edits to
      the live prover doc, so the next reload inserts fresh content on
      top of stale text still sitting in the document). This also
      corrects the plan's own "purge before re-loading" premise for
      check_theory: no purge is needed there at all, since use_theories
      already re-reads the file and diffs against its own correctly-
      tracked prior content on every call. Full story: CHANGELOG
      2026-07-10 ("wave 2"), plans/check_theory and plans/unload_theory
      status blocks. Three layers green (no ml-unit layer,
      this tool never touches MCP.ir): scala unit
      (mcp_handler_tests.scala), bridge (mcp_bridge_tests.scala, "wave
      2: ..."), e2e (test_mcp.py, "wave 2: ...").
- [x] scala: builtin tool table (name, description, schema,
      annotations, handler — see "advertised tool metadata");
      tools/list merges builtin + ML registry through the pure
      exposure-name function (namespacing on collision, see "tool
      naming and collisions"); tools/call dispatches, resolving
      qualified names through the same function.
      initialize declares tools: {listChanged: true}; protocol handler
      for MCP.tools_changed forwards notifications/tools/list_changed
      to the client.
      mostly done (2026-07-10): the table, the merge, collision
      namespacing and dispatch are in and carry all of wave 1 (18
      repl_* tools + sledgehammer + find_theorems, each with hand-
      written descriptions and annotations, plans/ has one file per
      tool) plus wave 2's three theory tools; row + dispatch pinned
      per tool in mcp_handler_tests.scala.
      closed 2026-07-11 (with plans/mcp_tool_command step 5): the ML
      emit point exists (MCP_Tool.declare / MCP_Resource.declare emit
      MCP.tools_changed / MCP.resources_changed; dropped outside a
      PIDE channel), MCP_Session forwards through a changed-handler
      callback the serve loop registers, initialize declares
      listChanged for both tools and resources, and stdout writes are
      line-atomic so notifications interleave safely with replies.
      e2e: test_mcp.py "phase 3: registration pushed notifications/
      tools/list_changed" (load_theory of an mcp_tool-registering
      fixture).
- [x] scala: resources/list, resources/templates/list, resources/read
      with the uris above; command-target resolution (offset/pattern ->
      command id) for repl_init_from_source. lazy from the start:
      metadata-only listing, pagination cursor, query-parameter slicing
      (?lines=, ?start=&count=, ?kind=&prefix=), truncation notes on
      oversized parameterless reads.
      partial (2026-07-10): resources/templates/list implemented,
      advertising all seven documented templates (static metadata,
      backend-independent). resources/read backs isabelle://repl/{id}
      and isabelle://repl/{id}/text for real (thin dispatch onto the
      same ir show/text repl_show/repl_text already use) — all four
      layers green (MCP_Test scala unit + bridge, test_mcp.py e2e).
      updated (2026-07-10, after wave 2): isabelle://theory/{name}/
      diagnostics also backed for real now that load_theory/
      check_theory exist — the spec's three-tier answer (image: "checked
      at build time"; loaded, via theory_master_dirs: a live,
      read-time-evaluated PIDE-snapshot diagnostics read through
      session.snapshot; filesystem, i.e. never loaded: "not checked —
      load_theory to check") — three layers green (scala unit, bridge,
      e2e; see mcp_session.scala's theory_diagnostics).
      done (2026-07-10): isabelle://theory/{name} (bare, source) and
      .../commands wired to the existing Ir.source/source_map MCP.ir
      fnames, gated on image tier. RETRACTED same-day false alarm: a
      first pass concluded Thy_Info.get_theory_segments never survives
      a fresh headless process (a "session-wide KNOWN GAP") — that was
      wrong. The real cause was a name-qualification bug: the client's
      original spelling ("MCP_Repl") was forwarded straight to
      Thy_Info.get_theory, an exact-key lookup against a table that
      mixes qualified and unqualified keys ("HOL.Wellfounded" but plain
      "Main"); the lookup's "undefined entry" error got rewritten by
      ir.ML's find_source into "No recorded segments", which read
      exactly like a real segments-missing condition. Fixed by
      mcp_session.scala's image_theory resolver (verbatim match, then
      session-qualified, then unique base-name match over
      loaded_theories; forwards the RESOLVED canonical key across the
      ir bridge) — with the right key, segments recorded at build time
      (record_theories, mcp/Tools/ROOT) DO survive the saved heap:
      isabelle://theory/MCP_Repl and .../commands return real source
      from a live server. A heap genuinely built WITHOUT record_theories
      (stock HOL) still answers with Isabelle's own rebuild hint, now
      the correctly-scoped remaining case. Positive pins in all three
      non-ml layers (mcp_bridge_tests.scala "recorded segments survive
      into the live server", mcp_handler_tests.scala, test_mcp.py) —
      see CHANGELOG 2026-07-10 ("entities name qualification;
      MCP_Heap_Suite").
      also done (2026-07-10): isabelle://theory/{name}/entities, image
      tier via the same image_theory-resolved MCP.ir fname "entities"
      (new ML, MCP_Repl.thy, Name_Space.theory_name filtering — genuine
      heap-serialized bookkeeping, not the segments table, so unaffected
      by the retraction above either way) and loaded tier via live
      PIDE entity-def markup on the snapshot (mcp_session.scala's
      theory_entities, Markup.Entity.Def). entities' PIDE half needed
      nothing from find_definition/goto_definition in the end, so that
      earlier dependency note is stale.
      done (2026-07-10, same day): isabelle://named/{name}, the last
      template without at least a real backing path. NOT via the
      mcp_tool/mcp_resource outer-syntax commands below (still unbuilt)
      — a new MCP_Resource structure in MCP_Tools.thy mirrors MCP_Tool
      exactly (register from a plain ML block; MCP.resources +
      MCP.read_resource protocol commands mirror MCP.theories +
      MCP.run_tool) — the outer-syntax sugar was never actually a
      precondition for the underlying registry+protocol mechanism, the
      same way MCP_Tool itself has worked without an mcp_tool command
      all along. Demo resource "greeting" registered alongside the
      existing "shout" demo tool. See CHANGELOG 2026-07-10
      ("isabelle://named/{name}: MCP_Resource registry").
      scorecard: all seven documented templates now have at least a
      real backing path. Remaining gaps are narrower than "unbacked
      template": filesystem-tier isabelle://theory/{name}, /commands
      and /entities (never-loaded theories) still need wave 3
      (session-structure path resolution); the mcp_tool/mcp_resource
      outer-syntax command SUGAR (not the registry mechanism, which
      already works) is separate future polish, tracked below.
      resources/list itself is unchanged (still just isabelle://session
      + isabelle://named/*, concrete): pagination cursor and
      query-parameter slicing are not implemented, since nothing yet
      returns a payload large enough to need them.
- [x] scala: session-structure discovery — keep Sessions.load_structure
      + deps from server startup; list_sessions / list_theories /
      search_sources tools; widen isabelle://theory/{name} resolution
      to filesystem theories via the structure's path map; tier
      answers on /diagnostics and /entities; load_theory resolves
      session-qualified names through the same structure.
      done (2026-07-12, wave 3 complete): all of the above landed —
      structure + deps at startup, resolve_theory three-tier
      resolution (not_yet_backed_uri retired), the three discovery
      builtins, load_theory session-qualified resolution, the
      loaded-tier document-based correction. scala-unit coverage
      (schemas, annotations, dispatch, result shape) in
      mcp_handler_tests.scala; the finer-grained cases in the
      "testing" section below are still open (see the note there).
      the filesystem-tier /commands spike stays open in
      plans/session_structure. CHANGELOG 2026-07-12 ("wave 3
      complete: library discovery tools").
      plan (2026-07-11): plans/session_structure (umbrella — startup
      structure/deps object, resolve_theory tier resolution, the
      resource-widening and load_theory-resolution halves; retires
      not_yet_backed_uri) + the three per-tool plans
      (plans/list_sessions, plans/list_theories,
      plans/search_sources, updated same day). one open spike flagged
      there: filesystem-tier /commands needs scala-side outer-syntax
      spans from deps, with a pinned honest fallback if not cheaply
      reachable. also carries the loaded-tier correction (decision
      2026-07-11, "loaded-tier membership" under "exploring the
      library universe"): tier = live document nodes, so transitive
      imports report "loaded", fixing wave 2's theory_master_dirs
      under-reporting.
- [ ] scala: scope state + scope_add/scope_remove/scope_show tools;
      scope-filtered resources/list; load_theory/unload_theory keep
      scope in sync; resources/list_changed notification on scope
      change (capability-gated).
- [x] ml: mcp_tool / mcp_resource outer syntax commands in MCP_Tools.thy
      (keywords in the header; check_command + is_diag validation;
      MCP_Resource registry; wrapped-command execution via exec_text).
      partial (2026-07-10): the MCP_Resource registry half (see
      CHANGELOG "isabelle://named/{name}: MCP_Resource registry").
      done 2026-07-11 (plans/mcp_tool_command; built on the pivoted
      registries, so declaration = MCP_Tool.declare underneath): the
      commands with all specced forms — diag wrap with description/
      params/format clauses, string_fun and ml_run hatches for
      mcp_tool; bare fact name (read-time), (isar ...) capture, and ML
      read function for mcp_resource — over the MCP_Combinators layer
      (params, type-directed quoting, validation, assembly, exec at
      Position.line 1 with MCP_Output capture and multiline shift-by-1
      position normalization). form tags + params cross the bridge and
      expand into JSON schemas + annotations scala-side; tools_changed/
      resources_changed emit from declare. TWO SURFACE CORRECTIONS
      forced by the outer lexer (command keywords delimit spans):
      wrapped command names are QUOTED (mcp_tool "find_consts"), and
      the activation attribute is PLURAL ([[mcp_tools add: ...]]).
      description is MANDATORY for tools (the keyword-table does not
      export command comments). ML self-test: MCP-Tools-Tests wraps
      find_consts and find_theorems in isar and runs them.
- [x] scala: MCP.resources protocol command (list + read named
      resources); tools/list expands the ML form tags into schemas +
      annotations (diag_wrap -> {input, repl?} + readOnly/idempotent
      hints, string_fun -> {input}; see "advertised tool metadata").
      done (2026-07-10), the list+read half only: MCP.resources (list)
      and MCP.read_resource (id-keyed read) protocol commands, mirroring
      MCP.theories/MCP.run_tool; MCP_Session.ml_named_resources/
      ml_read_resource on the scala side; mcp_resources()/
      mcp_resource_read wire isabelle://named/{name} to them. The
      form-tag/schema-expansion half doesn't apply yet since there is
      no form_tag to expand until the outer-syntax commands above exist
      — MCP_Resource entries are plain (name, description, read) with
      no schema of their own (resources don't take input the way tools
      do). See CHANGELOG 2026-07-10.
- [x] scala+ml: find_theorems context promotion — repl becomes
      optional, theory (image_theory-normalized) accepted as the
      alternative context selector, default = base image; schema,
      handler exclusivity check, dispatcher context resolution (new
      ML in MCP_Repl.thy, ir.ML stays verbatim). decided 2026-07-12;
      delta + tests in plans/find_theorems ("context promotion").
      (read_theory / list_entities tool mirrors are flagged but NOT
      scheduled — see "resource-read tool mirrors" above.)
- [ ] navigation: find_definition (ML name-space lookup + defining
      source via segments), goto_definition (scala snapshot cumulate
      over entity markup), isabelle://theory/{name}/entities resource.
      tests: the navigation cases in the "testing" section below —
      ml unit for find_definition, bridge for goto_definition over
      the mcp/test/Nav_Test.thy fixture.
      partial (2026-07-10): the entities RESOURCE landed early (see
      the resources item above — image tier via Name_Space, loaded
      tier via entity-def markup), and its name-space enumeration is
      exactly the per-space groundwork find_definition needs
      (worked-out mechanism + the name-qualification lesson in
      plans/find_definition). find_definition/goto_definition
      themselves not started. note the retracted segments gap
      (CHANGELOG 2026-07-10) improves the "defining source via
      segments" enrichment story: segments ARE reachable live for
      record_theories heaps.
- [ ] test client: mcp/test/test_agentic.py against -s MCP-HOL — the
      e2e layer of the "testing" section below (test_mcp.py stays
      untouched as the mvp regression against -s MCP-Tools).
- [ ] test scaffolding, grown alongside the items above rather than
      at the end: MCP-HOL-Tests session, Fake_Backend extensions +
      new mcp_test / mcp_test -b cases, mcp/test/Nav_Test.thy,
      test_agentic.py — cases enumerated in the "testing" section
      below.
- [ ] skill: distill "editing theories and persisting changes" into
      an agent-facing skill (.claude/skills/isabelle-mcp-proving/
      SKILL.md): the load -> orient -> attach -> iterate -> extract ->
      client-side splice -> check_theory loop, repl edits are not
      file edits, when to init from Main vs attach mid-theory.
      written against the settled tool names, kept current like
      isabelle-ml-scala.
- [ ] skill (parallel): discovery (.claude/skills/
      isabelle-mcp-exploring/SKILL.md) — the dependency chain of
      "exploring the library universe": discovery tools give names ->
      templates give access -> scope gives a persistent listing ->
      load_theory gives semantics. scope_add is NOT discovery (it
      only controls the listing); names come from list_sessions /
      list_theories / search_sources. teach the tiers and the
      promotion cost model (load_theory = fine-grained and slow for
      deep imports; heap build + -s restart = coarse-grained).
      worked example, kept verbatim as the skill's core:
        1. list_sessions {pattern: "HOL-*"} -> the session
           HOL-Library exists (no heap needed)
        2. list_theories {session: "HOL-Library"} -> 148 names with
           tiers, incl. HOL-Library.Multiset (filesystem) + path;
           or content-first: search_sources {pattern: "multiset"}
        3. with the name in hand: read via the template
           (isabelle://theory/HOL-Library.Multiset, no scoping
           required), scope_add to pin it into resources/list, or
           load_theory for full semantics; scope_add
           "HOL-Library.*" sidesteps individual names entirely.
- [ ] skill (parallel): resources & verification
      (.claude/skills/isabelle-mcp-verifying/SKILL.md) — which
      resource to read on which trigger, and the check loop without
      a repl. trigger patterns:
        "what is loaded / where am i"      -> isabelle://session
        "show me the theory / a region"    -> isabelle://theory/{name}
                                              (?lines= for windows)
        "where can i attach / what are
         the command spans"                -> .../commands
        "did it check / what is broken"    -> .../diagnostics
        "what does this theory define"     -> .../entities
                                              (?kind=&prefix=)
        "what did my registered resource
         say"                              -> isabelle://named/{name}
      the verification loop: load_theory {name} -> per-command errors
      with positions; read .../diagnostics for the full picture; fix
      (client-side edit or repl per isabelle-mcp-proving); check_theory
      {name} re-reads from disk -> green or new positioned errors.
      teach the tier answers (filesystem = "not checked", image =
      "checked at build time") and that diagnostics are read-time
      snapshot queries — re-reading is always safe and cheap.
- [ ] record results here

testing
-------

every feature lands with cases in the pyramid from the "testing
architecture" section (see its 2026-07-10 munit-migration update for
where the scala cases live now: mcp_test/ component, suites +
fixtures, `isabelle mcp_test [-b] [-t PATTERN]`; heap-restart claims
get MCP_Heap_Suite). new scaffolding: an MCP-HOL-Tests session for
ml unit tests (exists: mcp/Tools/HOL/Tests/MCP_Repl_Tests.thy — needs
its own directory next to HOL/ content since sessions cannot share
one), Fake_Backend extensions and new cases in
`isabelle mcp_test` (scala unit + bridge), a navigation fixture
mcp/test/Nav_Test.thy (a datatype, a fun over it, a lemma using
both — small enough to check in seconds), and mcp/test/test_agentic.py
(e2e). checkbox maintenance: [x] below means a matching case exists
and is green in the current tree; the per-tool plans (plans/) carry
the finer-grained test inventories.

1. ml unit — isabelle build -d mcp/Tools MCP-HOL-Tests
   (dispatcher and engine are testable given the heap alone: no
   protocol messages, no scala. session-qualified import
   "MCP-HOL.MCP_Repl", same pitfalls as MCP-Tools-Tests.)

   MCP.ir dispatcher (the pure fname/args -> result function):
   - [x] dispatch "repls" [] -> ok with a well-formed listing
   - [x] unknown fname -> status error naming the fname
   - [x] wrong arity / unparsable args -> status error, not an
         uncaught exception

   ir engine driven through the dispatcher:
   - [x] init ["Main"]; step "lemma True"; step "by simp" -> proof
         complete (the phase 2 self-test, kept as a regression case)
   - [x] state idx 0 = base, -1 = latest; show lists both steps
   - [x] back drops the last step; edit + replay recovers; truncate
         to 0 empties the repl
   - [x] fork is independent: stepping the fork leaves the original's
         text unchanged
   - [x] failing step (bad isar) -> error result, repl unchanged and
         usable (claim released: the next step succeeds)
   - [x] timeout: repl_timeout 1 + a sleeping step -> timeout error,
         repl still usable afterwards
   - [x] find_theorems "conjI" on a Main repl returns a hit

   output routing (the Private_Output wrappers):
   - [x] writeln inside a registered group's future lands only in
         that request's buffer (two concurrent requests don't mix)
   - [x] output outside any registered group falls through to the
         original fns (wrappers are transparent when idle)

   mcp_tool / mcp_resource commands (all in MCP_Tools_Tests.thy,
   2026-07-11):
   - [x] mcp_tool "find_consts" registers; running it returns matching
         constants
   - [x] non-diagnostic command (typedecl) rejected at registration
         naming its keyword class; unknown command name rejected
   - [x] description: pinned as MANDATORY (missing (description ...)
         rejected at registration) — the planned default from the
         command's outer-syntax comment is impossible, Outer_Syntax
         stores but does not export it (checked 2026-07-11)
   - [x] mcp_resource with an unknown fact name rejected
   - [x] named_theorems resource is read-time: read, add a thm to
         the collection, read again -> the new thm is visible
   - [x] the ml escape hatches register and run (tool and resource
         forms; the production shout/greeting demos ARE the isar
         string_fun/ML-read forms, exercised by every layer)

   find_definition (name-space lookup; needs the HOL context):
   - [ ] "conjI" -> kind fact + a position
   - [ ] {name: "option", kind: "type"} -> the datatype's position;
         the defining source text (with constructors) asserted on an
         MCP-HOL-local datatype, since segments are recorded only
         for this session's theories
   - [ ] unknown name -> empty result, not an error

2. scala unit — isabelle mcp_test
   (Fake_Backend grows a fake ir(), canned resources, an in-memory
   scope; still sub-second, still no prover.)

   builtin tools:
   - [x] tools/list = builtin table + ml registry; every entry has
         name, description, and a json-schema inputSchema listing
         its required fields
   - [x] tools/call on a builtin reaches backend.ir with the
         documented fname/args encoding (assert on what the fake saw)
   - [x] backend ir error -> isError content, not a json-rpc error
   - [ ] naming: builtin vs ml collision -> builtin keeps the bare
         name, the ml tool lists as <theory>__<name>; two ml tools
         colliding across theories both list qualified; tools/call
         resolves the qualified name; every exposed name matches
         ^[a-zA-Z0-9_-]{1,64}$ (pure exposure-name function, tested
         against the fake registry)
   - [ ] initialize response declares tools: {listChanged: true}
   - [ ] fake backend fires MCP.tools_changed -> server emits
         notifications/tools/list_changed on stdout (and the
         MCP.resources_changed mirror -> resources/list_changed)

   resources:
   - [ ] resources/list is metadata-only (no content) and enumerates
         exactly the scope: image/filesystem theories appear iff
         scoped (tier-tagged), never by default
   - [ ] pagination: nextCursor round trip — disjoint pages, cursor
         absent on the last page
   - [x] resources/templates/list contains the documented templates
   - [ ] resources/read succeeds on a valid uri outside the scope
         (scope filters discovery, not access)
   - [ ] malformed / unknown-scheme uri -> json-rpc error
   - [ ] uri parameter parsing (pure): ?lines=120-180, ?start=&count=,
         ?kind=&prefix= accepted; garbage rejected
   - [ ] oversized parameterless read -> first window + "truncated,
         N total, use ?start=" note (fake returns a large payload)
   - [x] isabelle://repl/{id} and .../text dispatch to ir show/text

   scope:
   - [ ] scope_add / scope_remove / scope_show round trip, incl.
         glob patterns ("HOL-Library.*")
   - [ ] load_theory adds to scope; unload_theory removes
   - [ ] resources/list_changed fires on scope change iff the client
         advertised the capability in initialize (test both branches)

   library discovery (against a fake session structure):
   (status 2026-07-13: the wave-3 close-out landed scala-unit
   coverage of the three tools' schemas, annotations, dispatch
   routing and result shape in mcp_handler_tests.scala; the
   specific cases below are STILL OPEN — kept unchecked honestly.
   the scope-dependent ones at the end belong to wave 4 anyway.)
   - [ ] list_sessions glob-filters and carries heap_present + the
         base-image marker
   - [ ] list_theories reports the correct tier for an image, a
         loaded, and a filesystem theory
   - [ ] search_sources returns {theory, line, snippet} hits, respects
         max_results, and scopes to the requested sessions
   - [ ] resources/read on isabelle://theory/{filesystem theory}
         serves the file content; /diagnostics on it reports the
         "not checked" tier answer instead of an error
   - [ ] loaded tier follows the document, not the explicit-load
         bookkeeping: after load_theory of a fixture importing
         another filesystem theory, the IMPORT reports "loaded" in
         list_theories and answers /diagnostics live (not "not
         checked"); after unload_theory of the importer, the import
         reverts to "filesystem"; unload_theory of the import alone
         is still a "was not loaded" error
   - [ ] scope_add "HOL-Library.*" makes those filesystem theories
         appear in resources/list with their tier (the listing side
         of the one scope rule)

   decoding (pure helpers):
   - [x] yxml stripping: XML.content(YXML.parse_body(...)) of a
         markup-bearing ir result equals the plain text
   - [x] MCP.ir_result payload decode (clone of the decode_tools test)
   - [x] MCP.ir args encode: json object -> yxml (key, value) pair
         list; list-valued arg -> repeated keys; newlines and symbols
         in values survive (the ml-side decode is exercised by the
         dispatcher unit tests and the bridge round trip)

3. bridge — isabelle mcp_test -b (real MCP-HOL session; the layer a
   fake cannot cover):
   - [x] session.ir("repls", []) round trip
   - [x] async: fire a slow ir call (a sleeping step), then a fast
         one; the fast reply arrives first — MCP.ir must not block
         the ml protocol loop (the property that motivated the async
         bridge; a synchronous regression would pass every other test)
   - [ ] a step that writelns returns that output in the result body
   - [ ] load_theory: broken .thy -> per-node errors with positions;
         good .thy -> ok; unload_theory purges
   - [ ] check_theory after an on-disk edit reports the *new* content
         (write the file between load and check: purge-then-reload
         must force a re-read; a stale snapshot would report the old
         state)
   - [ ] repl_init_from_source: offset and pattern resolve to the
         same command id; a repl initialized there can step
   - [ ] goto_definition over Nav_Test.thy: a use of the fun-defined
         constant -> position inside the fun block; a type annotation
         of the datatype -> the datatype block; the entity kind
         property ("constant", "type_name") is passed through
   - [ ] isabelle://theory/{Nav_Test}/entities lists the datatype and
         the fun with kinds; ?kind=type filters to the datatype
   - [ ] load_theory on a theory that registers a tool (mcp_tool or
         the ML escape hatch): the MCP.tools_changed protocol message
         arrives at the scala session (the ML->scala half of the
         list_changed path; the scala->client half is unit-tested
         against the fake)

4. e2e acceptance — test_mcp.py stays green against -s MCP-Tools
   (mvp regression); new test_agentic.py against -s MCP-HOL covers
   the client-visible slice end to end:
   - [ ] resources: list/read of the session overview, theory source
         + a ?lines= slice, the commands map; scope_add/scope_remove
         changes the listing; a cursor-driven listing loop terminates
   - [ ] repl lifecycle: init ["Main"], step lemma + state shows the
         goal, step "by simp" completes, back/edit/replay, text
         returns the finished isar
   - [ ] find_theorems finds conjI
   - [ ] library discovery: list_theories on HOL-Library reports
         Multiset as tier filesystem; search_sources finds it by a
         source pattern; reading isabelle://theory/HOL-Library.Multiset
         returns source although nothing was loaded (/entities and
         /diagnostics on it answer with the filesystem tier — the
         limitation is reported, not papered over)
   - [ ] goto_definition round trip over Nav_Test.thy
   - [ ] load_theory on a broken .thy reports the error with its
         position; unload_theory removes it
   - [ ] the full edit loop: load a broken .thy, attach via
         repl_init_from_source at the failing command, fix in the
         repl, repl_text, the test client splices the fix into the
         file (playing the host's role), check_theory -> green
   - [ ] user wiring: a test theory with `mcp_tool find_consts` and
         `mcp_resource my_simps` (a named_theorems collection) —
         tools/call find_consts returns matching constants,
         resources/read isabelle://named/my_simps returns the facts
   - [ ] list_changed end to end: tools/list before, load_theory of
         the registering theory, then the client receives
         notifications/tools/list_changed (and resources/list_changed
         for the mcp_resource) and tools/list now includes the new
         tool — covers both registration paths, since the test theory
         registers via isar and via the ML escape hatch
   - [ ] sledgehammer behind an env flag, skipped by default
         (external provers, nondeterministic timing)

acceptance criteria
-------------------

an mcp client, talking only mcp, can: discover a theory via resources,
read its command map, attach a repl mid-theory (or start from Main),
prove a lemma interactively (state -> step -> backtrack -> step),
extract the finished isar text, and load+check an on-disk theory
getting positioned errors — then, after the host splices a fix into
the file, re-check it green (persistence is client-side; the server
never writes theory files). a user, writing only isar, can expose an
existing diagnostic command (`mcp_tool find_consts`) and a named fact
collection (`mcp_resource a_user_defined_simpset`) and see both served
over mcp — and a client connected while that registration happens
receives notifications/tools/list_changed (resp. resources/
list_changed) without polling. discovery stays lean: resources/list
never enumerates the image (or the session structure) unbidden,
oversized reads come back windowed, and the agent can widen or
narrow what it sees with scope_add/scope_remove. the library universe
is explorable beyond the heap: the client can enumerate sessions and
their theories with availability tiers, text-search and read sources
that no heap contains (HOL-Library, a registered AFP), and promote a
filesystem theory to full semantics with load_theory — with the tier
(and the cost of promotion) always stated, never silently faked. all four test layers
of the "testing" section exit 0 against a fresh build, including the
mvp regressions (test_mcp.py, MCP-Tools-Tests, mcp_test).

out of scope (phase 3+)
-----------------------

- structured schemas for *user* ML tools (registry schema field)
- server-side write-back into theory files (persistence is client-side
  in phase 2 — see "editing theories and persisting changes"). needed
  only if hosts without file tools become a target. recommended shape
  when it comes: span-keyed tools over the machinery agents already
  use — replace_command {theory, index, new_text} or repl_write_back
  {repl} (replace from the attach command to the end of the original
  proof) — with a digest guard against concurrent edits, an automatic
  re-check after writing, and an opt-in flag (mcp_server -W)
- cancellation, progress notifications
- resource subscriptions — one designated use case: asynchronous proof
  checking via the diagnostics resource. recommended shape when it
  comes:
  * subscription target is isabelle://theory/{name}/diagnostics only
    (repl stepping stays request-driven; sledgehammer, if it ever needs
    to be non-blocking, gets progress notifications or a start/poll
    tool pair — "one result", not a content stream).
  * load_theory grows an async flag (or an async_load_theory sibling):
    return as soon as the document is dispatched instead of awaiting
    use_theories; the client subscribes and reads diagnostics as they
    evolve. the synchronous load_theory/check_theory stay the primary
    path — many hosts never subscribe, and agent clients work
    turn-by-turn.
  * backing signal: session.commands_changed (already named above).
    debounce: batch changed-command events per node and emit at most
    one notifications/resources/updated per node per interval (~1s);
    a final updated fires when the node reaches terminated/consolidated
    so clients can stop polling on quiescence.
  * lifecycle: unload_theory (and the purge in check_theory) cancels
    subscriptions on the purged node's uris — emit a last updated, then
    treat further reads as unknown-uri errors. subscriptions are
    per-connection state next to scope, not in the ML side.
  * capability-gated: only advertise resources: {subscribe: true} once
    implemented; subscribe requests before that are json-rpc errors
    per spec.
  * phase-2 guard so nothing precludes this: diagnostics reads must
    stay cheap snapshot queries (no re-elaboration), and the resources
    layer keeps uri -> node resolution in one place so subscribe can
    reuse it.
- cross-heap *semantic* search (find_theorems over sessions that are
  not in the base image). phase 2 deliberately stops at text search +
  tier reporting ("exploring the library universe" above): an ML
  process sees one theory ancestry, so true cross-session fact search
  means an index, not a query. recommended shape when it comes:
  integrate Find_Facts (isabelle find_facts_index / find_facts_server,
  in the distribution since 2025) — index the sessions of interest
  once, then expose a find_facts tool that queries the index over
  *everything indexed* regardless of what is loaded. heavyweight
  (Solr), and redundant while search_sources + load_theory cover the
  workflow.
- concurrent tools/call multiplexing on one repl (Ir claims already
  serialize safely; true parallelism = multiple repls)
- rebuilding HOL with record_theories for Main-internal segments
- jEdit/live-document integration (that niche belongs to I/Q)


================================================================
phase 3 — isar-level extensibility (tools as context entities)
================================================================

status: SPEC (brainstormed 2026-07-11; implementation follows wave 3
of phase 2). de-risking spikes run 2026-07-11, all resolved — see
"spike results" below; plans/ carries the implementation plans
(mcp_tool_registry, tool_scope, mcp_tool_command).

spike results (2026-07-11)
---------------------------

eight behavioral assumptions checked before implementation; two
forced corrections (marked CORRECTED/DEMOTED in their sections):

1. programmatic diag execution + output capture: VERIFIED. build the
   transitions with Outer_Syntax.parse_text, apply with
   Toplevel.command_exception against Toplevel.make_state, capture by
   swapping Private_Output.writeln_fn — find_consts ran and its
   output (185 chars, mentions "length"/"size") was captured, in a
   raw ML_process over the MCP-HOL heap. the diag_wrap runtime rests
   on proven ground.
2. ML_val error positions: CORRECTED — parse position matters; see
   "snippet evaluation" section. Position.none = no positions at
   all; Position.line 1 = exact snippet-relative lines.
3. local_theory commands through the same mechanism: VERIFIED.
   named_theorems + definition + lemma/by all execute against
   make_state (registered as "Main.spike_rules"); repl-side
   registration and the self-extension loop are mechanically sound.
   (eisbach `method` itself untested — not in the MCP-HOL image;
   risk considered low since it is just another local_theory
   command, but the image-vs-load_theory decision is open.)
4. bundle-scoped activation: VERIFIED end to end in a scratch Pure
   session — `bundle spike_search = [[spike_tool add: bar]]` parses
   (the [[...]] dummy-fact idiom works in bundle definitions);
   activation is visible inside `context includes ... begin/end` and
   gone after; `unbundle` persists for the rest of the theory;
   `declare [[spike_tool del/add: name]]` round-trips at theory
   level. the registration/activation split behaves exactly as
   specced. (bonus precedent found: HOL-Library.Pattern_Aliases'
   bundle carries arbitrary `declaration` blocks — an alternative
   activation vehicle if the attribute form ever falls short.)
5. protocol_message from forked futures: VERIFIED by existing code —
   MCP_Repl.thy's MCP.ir emit already fires Output.protocol_message
   from inside Future.forks and all bridge tests are green; the
   tools_changed emit needs nothing new.
6. eisbach closure introspection: NEGATIVE — METHOD_CLOSURE keeps
   closures private; schema derivation DEMOTED to explicit params
   clauses (see the eisbach section).
7. Name_Space in Generic_Data: VERIFIED — define/check/extern round
   trip, short-name resolution, error on unknowns, declaration
   position recorded with a real line number (the entity-markup /
   ctrl+click substrate). Main is keyed "Main" (unqualified) in the
   heap — the phase-2 name-normalization lesson applies to ML-side
   Thy_Info lookups here too.
8. proof-mode states: VERIFIED — Toplevel.context_of works mid-proof
   and generic data is readable there; tool_scope_set {repl} can
   read any repl state's context.

spike artifacts: throwaway (job tmp dir); the durable versions are
the ml-unit/bridge cases named in "testing" below.

goal
----

make the mcp server user-extensible entirely from Isar: an isabelle
user (or the agent itself, through repl steps) declares tools,
resources, and their tests in a theory, with real parameter schemas,
and the server picks them up — no scala changes, no raw ML required
for the common cases. the design principle: every piece reuses an
existing isabelle mechanism rather than inventing a parallel one, so
the result *feels like isabelle* — declarations are checked at
registration with positions and completion, names live in a name
space, visibility follows imports and bundles, documentation renders
through antiquotations, and tests fail the build.

the pivot: tools are context entities, not global state
---------------------------------------------------------

phases 1–2 keep tools in a global Synchronized.var — fine for a demo
registry, wrong for everything this phase wants: no positions, no
completion, no scoping, no merge semantics, invisible to the context.
phase 3 re-founds the registry on the standard package idiom
(Pure/Tools/named_theorems.ML is the 109-line model; bundle.ML the
scoping model):

- MCP_Tool becomes Generic_Data holding a Name_Space table:

    structure Data = Generic_Data
    (
      type T = tool Name_Space.table * Name_Set.T   (* defs, active *)
      ...merge = Name_Space.merge_tables / set union...
    )

    type tool =
      {description: string,
       params: param list,          (* the schema source, see below *)
       form: form,                  (* diag_wrap | method_wrap | string_fun *)
       run: Proof.context -> (string * string) list -> string}

- declaration goes through Local_Theory (named_theorems.declare is
  the template): the binding's position lands in the name-space entry,
  so PIDE entity markup, ctrl+click to the registration site, name
  completion, and theory-qualified full names (My_Thy.find_consts)
  all come for free from Name_Space.declare/check. no custom
  goto-definition machinery — find_definition (phase 2) will simply
  see a new kind of entity.

- what this buys over the Synchronized registry, concretely:
  * positions + completion + extern/qualified names (Name_Space)
  * import-following visibility: a tool registered in HOL-Library.Foo
    is visible exactly in contexts that import Foo — honest isabelle
    semantics instead of a process-global soup
  * merge along theory imports (Data.merge), replacing the ad-hoc
    (theory, name) replacement keying of phase 2
  * heap persistence unchanged (theory data serializes with the heap)
  * the phase-2 exposure-name/collision scheme simplifies: full names
    are already theory-qualified; the scala merge externs against the
    agent context, so unambiguous tools expose base names and clashes
    expose qualified ones — same visible behavior, no bespoke state.
    caveat (2026-07-11): extern'd qualified names contain dots
    (My_Thy.find_consts), which violate the ^[a-zA-Z0-9_-]{1,64}$
    tool-name regex — the dots->"__" sanitization step survives from
    phase 2; only the naming *state* goes away, not the sanitizer

- migration (decided 2026-07-11: ONE API, no compat wrapper):
  MCP_Tool.declare is the single registration entry point;
  MCP_Tool.register is deleted, and its six in-repo call sites (the
  "shout"/"greeting" demos in MCP_Tools.thy, the registrations in
  MCP_Tools_Tests.thy) are ported in the same wave — a follow-up
  step in plans/mcp_tool_registry, with a grep gate that no register
  call survives anywhere in the tree. nothing specced elsewhere
  targets register (the mcp_tool command was always specced onto the
  declaration path). the mvp test that pinned Synchronized
  replace-by-name semantics is rewritten to pin the new semantics
  (Name_Space redefinition within a theory, coexistence across
  theories). the MCP.tools / MCP.run_tool protocol commands now
  evaluate against the agent context (below) instead of reading a
  global variable. the list_changed emit stays in the one declaration
  function, so isar-, ML-, and repl-registered tools all notify.

visibility: registration vs activation, bundles, the agent context
-------------------------------------------------------------------

two layers, because isabelle separates them too:

1. REGISTRATION — the tool exists in the name space of every context
   that (transitively) imports the registering theory. registration
   is never conditional.
2. ACTIVATION — a name set in the same Generic_Data slot; only
   registered AND active tools are served. activation is manipulated
   by a declaration attribute, following the simproc precedent
   exactly (HOL says `declare [[simproc del: finite_Collect]]`):

     declare [[mcp_tool del: find_theorems]]     (* user's "no_tool" *)
     declare [[mcp_tool add: find_theorems]]

   a tool declared at theory top level is active from its declaration
   point onward (register + activate in one step, the common case).

bundles then work with zero new machinery, because opening a bundle
just applies its attributes to the current context (bundle.ML: a
bundle IS a list of (thms, attributes)):

     mcp_tool find_consts (scoped)          (* register, don't activate *)
     bundle search_tools = [[mcp_tool add: find_consts]]

   `unbundle search_tools` (theory), `context includes search_tools`
   (nested context), `including` (proof) now activate the tool with
   standard isabelle scoping — closed bundle, tool invisible; open
   bundle, tool served. sugar `mcp_tool find_consts (bundle
   search_tools)` may create both in one declaration (implementation
   decides whether the bundle is created or extended; investigate
   `mcp_tool` directly inside `bundle ... begin ... end` blocks at
   implementation time — bundle targets record notes, and the
   activation declaration must be routed into the bundle content).

the server's viewpoint — the AGENT CONTEXT: context data needs a
context to be read from. the server designates one theory (or repl)
as the agent's standpoint; tools/list = registered-and-active tools
of that context, run against it by default.

- default designation: the -T theory of the running session (MCP_Repl
  for MCP-HOL); load_theory does NOT auto-switch it (implicit scope
  jumps would be surprising; rejected alternative: union over all
  loaded theories — violates import semantics and makes tool sets
  non-reproducible).
- builtin tools (scala, new; sibling family of the phase-2 resource
  scope_* — resource scope filters *listing*, tool scope decides
  *which context defines the tool set*; keep the names distinct):

    tool_scope_show     {}          current designation + active tools
    tool_scope_set      {theory | repl}
    tool_scope_include  {bundles: [string]}   Bundle.includes applied
                                              to the agent context

  tool_scope_set {repl: R} is the self-extension hinge: tools the
  agent registers via repl_step land in R's context data, and
  designating R makes the server serve them (see "the self-extension
  loop" below).
- bridge change: MCP.tools / MCP.run_tool take the designation as an
  argument (theory name or repl id); the scala side owns the current
  designation as connection state, like the resource scope.

the parameter spec language (params clause) and schema derivation
------------------------------------------------------------------

phase 2 froze user-tool schemas to {input: string}. phase 3 adds a
declarative params clause on mcp_tool — this is the tool
SPECIFICATION LANGUAGE, and it is deliberately a closed, first-order
thing: schemas must serialize to json schema and be readable by
non-programmers, so no arbitrary parsers (a Scan parser is opaque —
you cannot derive a schema from it; this is why the clause is data,
not code).

    mcp_tool find_theorems
      (description \<open>search facts matching the given criteria\<close>)
      (params
        criteria :: string          \<open>find_theorems criteria, e.g.
                                     "conjI", "intro", "_ + _ = _ + _"\<close>
        limit    :: nat = 40        \<open>max facts returned\<close>
        with_dups :: bool = false   \<open>include duplicates\<close>)
      (format \<open>find_theorems (limit $limit $with_dups?with_dups) $criteria\<close>)

- type universe (closed, v1): string, nat, int, bool,
  enum (a | b | c), term, typ, fact, plus `list of <scalar>`.
  json mapping: string/integer/boolean; enum -> {enum: [...]};
  term/typ/fact -> string on the wire, but VALIDATED server-side
  (Syntax.read_term / read_typ / Proof_Context.get_fact against the
  run context) before the command runs — the agent gets "undefined
  fact foo" as a typed error instead of a command parse failure.
- modifiers: `= default` marks optional-with-default; `(optional)`
  optional-without-default (omitted = absent from the format);
  everything else required. each param carries a mandatory cartouche
  description — it becomes the json-schema property description, the
  text the model actually reads.
- the format clause assembles the isar text: $name substitutes the
  value with TYPE-DIRECTED QUOTING (string -> quoted/escaped inner
  string, term/typ -> cartouche-wrapped, nat/int/bool -> literal,
  list -> space- or comma-joined per an optional join spec);
  `$flag?text` emits text iff a bool is true (for option-flag
  syntax); omitted optionals erase their segment. no format clause
  defaults to `<command> $input` with a single required string param
  named input — i.e. today's diag_wrap is the degenerate case and
  stays source-compatible.
- registration-time checks: the command exists
  (Outer_Syntax.check_command) and is diagnostic (Keyword.is_diag);
  every $name resolves to a declared param; every required param is
  used; a smoke parse of the format with placeholder values is NOT
  attempted (values change parse shape; tests cover this instead).
- call-time errors: missing required key / unparsable value / unknown
  key -> typed status errors naming the parameter (the dispatcher
  stays dumb, per the phase-2 argument-encoding rules).
- the wire: the registry entry serializes params as yxml tuples
  (name, type, required, default?, description, enum values) over the
  grown MCP.tools payload; the scala side expands them into the json
  schema at the tools/list merge — generalizing phase 2's form-tag
  expansion (the two fixed forms become derived param sets; the form
  tag survives only to pick annotation hints: diag_wrap ->
  readOnly+idempotent, method_wrap -> mutating, string_fun -> bare).

under the sugar: the MCP_Combinators ML library
------------------------------------------------

the isar clause is sugar over an ML combinator library, so ML users
compose the same pieces the command uses (and the command's
implementation stays small):

    MCP_Combinators.param  : {name, typ, required, default, descr} -> param
    MCP_Combinators.diag   : string -> param list ->
                             ((string * string) list -> string) -> tool
                             (* command name, params, format function *)
    MCP_Combinators.method : string -> param list -> tool
                             (* wrap a proof method, see below *)
    MCP_Combinators.func   : (string -> string) -> tool
                             (* the mvp escape hatch, unchanged *)
    MCP_Combinators.ml_run : (Proof.context -> (string*string) list
                              -> string) -> param list -> tool
                             (* full-power escape hatch: context +
                                parsed named args, user code *)

  plus the quoting helpers the format clause compiles into
  (quote_string, quote_term, join_list, flag). wrapping an EXISTING
  ml tool (community code with its own entry point) is ml_run + a
  params list — a few lines, no protocol knowledge.

what comes almost for free (the payoff inventory)
--------------------------------------------------

every `:: diag` command is wrappable today with one mcp_tool line;
with params clauses they get real schemas. verified inventory:

- Pure: find_theorems, find_consts, term, prop, typ, thm,
  print_bundles, print_methods, print_attributes, unused_thms,
  ML_val, ML_command, print_context_tracing, ...
- HOL: value, values, quickcheck, nitpick, nunchaku, sledgehammer
  (yes — :: diag in Sledgehammer.thy), find_unused_assms,
  print_inductives, print_bnfs, print_induct_rules, ...
  quickcheck/nitpick as tools are the headline: counterexample
  search for the agent with a schema for their options.
- AFP (grep over afp-2025-06-04): entries ship their own diag
  commands — find_proof, print_named_simpset, try_hard/try_hard_all/
  try_parallel, tts_find_sbts, print_monad_rules, cakeml,
  approximate_cfrac, list_ontologies, ... — each one mcp_tool line
  away from being agent-callable once its theory is in the image or
  loaded. this is the reuse story the project goal names.

builtins are NOT displaced: the scala find_theorems/sledgehammer
stay (they run against repl states through Ir with hand-tuned
schemas). the acceptance test re-derives find_theorems in isar to
prove parity, not to replace the builtin.

snippet evaluation: ML_val and friends
----------------------------------------

`mcp_tool ML_val (params src :: string ...)` works with zero new
machinery — ML_val is a diagnostic command (Pure.thy: Isar_Cmd.ml_diag
true, ML_Compiler.verbose flags), so the wrapped tool evaluates an ML
snippet in the target context and the captured output includes
compiler errors WITH POSITIONS. two quality items are ours:

- position normalization (CORRECTED by spike, 2026-07-11): text
  parsed at Position.none produces errors with NO position at all
  (just 'At command "ML_val"') — there is nothing to subtract. the
  diag_wrap runner must parse the assembled text at Position.line 1;
  compiler errors then carry line numbers relative to the assembled
  text, exactly and linearly (verified: parsing at line 100 reports
  the line-3 error as "ML error (line 102)"). normalization =
  assemble with the command and cartouche-open on line 1 so the
  snippet's own lines start at 2, then shift reported lines by the
  constant prefix; ir.ML's own exec uses Position.none today, so the
  runner must NOT inherit that choice.
- the run context matters: default = registering theory; {repl: R}
  evaluates against R's latest state — snippet evaluation *inside an
  ongoing proof exploration*, e.g. \<^assert>-style checks over the
  goal via ML antiquotations. document both in the authoring skill.

value/term/typ/prop wrap the same way for object-level evaluation
(value for computation, term/typ for elaboration checks with good
type-error messages).

proof methods and eisbach (agent-authored automation)
------------------------------------------------------

methods act on proof states, so they are not directly tools; the
mapping goes through repls:

- method_wrap form: `mcp_tool (method) fastforce (params ...)` — the
  tool takes {repl} + declared params, validates the method name at
  registration (Method.check_name), and at call time steps the repl
  with `apply (fastforce <args>)`, returning the new proof state (or
  the failure). semantically a specialized repl_step: mutating repl
  annotations, never readOnly. this is the "one-shot automation
  button" — cheaper for the model than composing isar text when the
  method's options are what matter.
- eisbach needs NO wrapping to let the model write automation: the
  `method` command is a local_theory command, so the agent can
  already define methods through repl_step ("method my_solve = (auto;
  fail) | (metis ...)") and use them in subsequent steps. what phase
  3 adds:
  * schema derivation from eisbach signatures (DEMOTED by spike,
    2026-07-11): the declarative header (for-fixes -> term params,
    uses-facts -> fact list params, methods -> method-name params)
    would derive a schema — but METHOD_CLOSURE exports only
    apply_method/method/method_cmd; the closure record {vars,
    named_thms, methods, body} lives in private context data with no
    public accessor. so: eisbach-wrapped methods get the generic
    {repl, args: string} schema unless the user writes an explicit
    params clause (which works for any method, eisbach or not).
    automatic derivation is future work behind an upstream signature
    extension (export a closure lookup) or reparsing the defining
    source — neither blocks this phase.
  * the authoring skill teaches the loop: define method in repl ->
    mcp_tool (method) it -> mcp_test it -> repl_text -> persist to
    theory. eisbach is in the HOL image (session imports it or
    load_theory HOL-Eisbach.Eisbach), MCP-HOL should import Eisbach
    by default.
- non-eisbach methods (ML Method.setup): wrappable with an explicit
  params clause; without one they get the generic {repl, args:
  string} schema (args spliced raw into the apply).

tests in isar: the mcp_test command
-------------------------------------

users specify tests next to registrations; tests are the contract
that makes agent-authored tools trustworthy. keyword `mcp_test ::
thy_decl`:

    mcp_test length_is_found = find_consts
      (args query = \<open>"'a list => nat"\<close>)
      (expect_contains "length")

    mcp_test bad_pattern_errors = find_consts
      (args query = \<open>"(((\<close>)
      (expect_error)

    mcp_test my_check = shout
      (args input = \<open>abc\<close>)
      (check \<open>fn output => output = "ABC"\<close>)     (* ML escape hatch *)

- expectations, closed set v1: expect_contains, expect_matches
  (regex), expect_equals, expect_error, check (ML: string -> bool).
- execution: at declaration time, in the declaring context — a
  failing test fails the theory, so `isabelle build` runs the suite
  with zero runner infrastructure (the session-based testing idiom
  this repo already uses). the tool is resolved and run through the
  SAME dispatch path the server uses (context + named args), so a
  green mcp_test really pins server-visible behavior.
- slow/external tools: `mcp_test ... (tag slow)` registers without
  running; config option mcp_test.run_slow (Attrib.setup_config_bool,
  default false) opts in. sledgehammer-wrapping tests go here.
- tests are recorded in theory data (name-spaced like tools, kind
  "mcp test"): print_mcp_tests lists them; a diag command
  mcp_test_run [name|tool] reruns recorded tests on demand — which,
  being a diag command, is itself exposed as a tool, so THE AGENT CAN
  RUN THE TEST SUITE for the tools it just wrote, live, without a
  rebuild.

explorability, antiquotations, documentation
----------------------------------------------

the registry must be a first-class citizen the user can query from
every level:

- ML api (all context-relative): MCP_Tool.list ctxt, MCP_Tool.get,
  MCP_Tool.check ctxt (name, pos) with completion (the
  named_theorems.check pattern), MCP_Tool.space, MCP_Tool.active,
  MCP_Tool.activate/deactivate (what the attribute calls). the
  collection is genuinely manipulable from isabelle/ml, per the
  project goal.
- print_mcp_tools (diag; bool "!" for verbose = include inactive,
  mirroring print_bundles): name, activity, params summary, one-line
  description, definition position. itself wrappable — the agent can
  introspect its own tool surface through mcp.
- ML antiquotation \<^mcp_tool>\<open>name\<close> — checked name string
  (named_theorems' antiquotation pattern); ctrl+click via the check
  report. useful in tests, combinator code, and tool implementations
  that call other tools.
- document antiquotations (the entity_antiquotation /
  Document_Output.antiquotation_pretty_source_embedded patterns in
  document_antiquotations.ML):
    @{mcp_tool find_consts}          typeset name, checked + linked
    @{mcp_tool_description ...}      the registered description
    @{mcp_tool_schema find_consts}   the derived schema, rendered
  schema rendering styles via antiquotation options (json — verbatim
  block for system docs; table — params/types/descriptions for
  papers). registration position + checked references mean theory
  documents about tools stay correct by construction.
- mcp_resource gets full parity where it applies: name-spaced
  registry (same Generic_Data pivot), bundles/attribute activation,
  @{mcp_resource} antiquotation, mcp_test with a (resource) flag to
  test reads. resources take no input, so the params clause does not
  apply (phase 2's decision stands).

the self-extension loop (why this all composes)
-------------------------------------------------

the agent, mid-session, using only existing mcp surface plus this
phase: repl_step "method my_solve = ..." (eisbach) -> repl_step
"mcp_tool (method) my_solve" (registers in the repl's context; the
declaration fires MCP.tools_changed -> notifications/tools/
list_changed) -> tool_scope_set {repl: R} (the server now serves the
repl's tool set) -> tools/call my_solve on stuck goals elsewhere ->
repl_step "mcp_test ..." + mcp_test_run (verifies) -> repl_text ->
the host splices method + registrations + tests into a .thy ->
load_theory -> the tools exist durably. every step is an existing
mechanism; phase 3 adds no special "self-extension" feature — that
is the design working as intended.

skills impact (assessed 2026-07-11)
-------------------------------------

- .claude/skills/isabelle-ml (topic index): ADD topics — bundles
  (Pure/Isar/bundle.ML; unbundle/include/including semantics);
  Eisbach (src/HOL/Eisbach/method_closure.ML, match_method.ML, the
  method command's closure data); document antiquotations & document
  output (Pure/Thy/document_antiquotation.ML,
  document_antiquotations.ML — basic_entity / entity_antiquotation
  patterns); keyword classification (Pure/Isar/keyword.ML,
  Keyword.is_diag and friends); config options via
  Attrib.setup_config_*. the existing pointers (named_theorems,
  name_space, outer syntax) already cover the rest of this phase.
- .claude/skills/isabelle-ml-scala: UPDATE, small — the bridge
  payload conventions gain the param-descriptor yxml encoding and
  the context/designation argument on MCP.tools/MCP.run_tool;
  mechanics unchanged.
- NEW skill: isabelle-mcp-extending — the authoring guide for this
  phase's user surface (mcp_tool forms + params + format quoting
  rules, mcp_resource, mcp_test, bundle scoping, declare [[mcp_tool
  del: ...]], the combinator library, the self-extension loop and
  its persistence step). audience is both the human user writing
  theories and the agent extending itself; written against settled
  syntax at implementation time, like the other phase-2 skills.
- planned phase-2 skills, additions when written:
  isabelle-mcp-proving gains "define eisbach methods in the repl,
  wrap with mcp_tool (method), test with mcp_test_run";
  isabelle-mcp-exploring gains tool_scope_* next to scope_* (two
  different scopes — teach the distinction explicitly);
  isabelle-mcp-verifying gains the trigger row "did my tool
  registration work" -> print_mcp_tools / mcp_test_run.

implementation order
--------------------

- [x] registry pivot (done 2026-07-11): MCP_Tool on Generic_Data +
      Name_Space table (defs + active set), declare-through-
      Local_Theory, check with completion, add/del attribute,
      print_mcp_tools; MCP_Tool.register DELETED (one api — see
      migration above) with the follow-up port of all register sites
      in the same wave (grep gate clean); protocol commands take the
      designation argument. MCP_Resource identically (shared
      MCP_Registry functor). the scala exposure function (base name
      when unambiguous, sanitized qualified name otherwise) landed
      WITH the pivot rather than with tool_scope — full internal
      names cross the bridge from day one, so the client-name
      contract needed it immediately. all four layers green:
      MCP-Tools-Tests fixtures (visibility diamond, bundle scoping,
      attribute, duplicate-declare error), scala unit incl. exposure,
      bridge designation + heap-survival suites, test_mcp.py 58/58
      unchanged. plan: plans/mcp_tool_registry.
- [ ] agent context on the scala side: designation state,
      tool_scope_show/set/include builtins, tools/list + tools/call
      against the designation; exposure names via Name_Space extern
      (drop the bespoke collision function; keep its tests as the
      contract).
- [x] params clause + format compiler + type-directed quoting +
      MCP_Combinators; wire param serialization over MCP.tools and
      schema expansion scala-side (form tags reduce to annotation
      hints). done 2026-07-11 (plans/mcp_tool_command steps 1-3, 6):
      type universe string/source/ARGS/nat/int/bool/term/typ/fact —
      "args" is new (verbatim splice into a command's argument
      position, the default input of a bare diag wrap; a cartouche
      there would break the command's own argument syntax), "string"
      is single-line inner-quoted, "source" is cartouche-quoted with
      multiline framing + line-shift normalization; enum and
      list-of-scalar DEFERRED to a later wave. MCP.run_tool now
      carries named args (one yxml chunk, the MCP.ir encoding); the
      injection tests pin that adversarial values stay data.
- [x] mcp_tool command v2: diag form with params/format/description
      clauses; ML escape hatches (func = string form, ml_run = run
      form); mcp_resource's three forms. done 2026-07-11
      (plans/mcp_tool_command step 4): find_theorems and find_consts
      re-derived in isar in MCP-Tools-Tests (quoting matrix covered
      with args/nat; ML_val position normalization covered at the
      combinator layer). DEFERRED to later waves: the (scoped)
      modifier (registration without activation), enum/list params,
      and the HOL first-user (value) — MCP-Tools-Tests is Pure-based.
      SURFACE CORRECTIONS (outer lexer, see the phase-2 command item):
      quoted command names, plural activation attribute.
- [ ] method_wrap: mcp_tool (method), Method.check_name validation,
      repl-step execution; generic {repl, args} schema (closure
      derivation demoted — see the eisbach section). import Eisbach
      into MCP-HOL (decision pending: image import vs load_theory at
      runtime).
- [ ] mcp_test command: expectations, declaration-time run, slow
      tags + config option, recorded suite + mcp_test_run.
- [ ] antiquotations: \<^mcp_tool> (ML), @{mcp_tool} /
      @{mcp_tool_description} / @{mcp_tool_schema} (document, with
      rendering options).
- [ ] bundle scoping end to end: scoped registration, bundle
      activation, tool_scope_include; the closed/open bundle
      behavior pinned at every layer.
- [ ] skills: write isabelle-mcp-extending; apply the isabelle-ml /
      isabelle-ml-scala updates above.
- [ ] record results here

testing (mapped to the standard pyramid)
------------------------------------------

1. ml unit (MCP-Tools-Tests / MCP-HOL-Tests): registration declares
   into the name space with the binding position; visibility follows
   imports (tool declared in A visible in B importing A, invisible in
   sibling C); del attribute deactivates in the local context only;
   bundle round trip (scoped tool invisible; unbundle -> visible;
   nested context includes -> visible inside, invisible after);
   params: defaults applied, missing required -> typed error, enum
   rejects junk, term param validation error names the param; format
   quoting per type incl. cartouche-in-string; mcp_test expectations
   incl. expect_error; explicit params clause on a wrapped method;
   ML_val position normalization (reported line minus prefix = the
   snippet's own line; pinned against the spike's line-100 datum).
2. scala unit (mcp_test, Fake_Backend grows params in tool rows):
   schema expansion from serialized descriptors (each type, optional/
   default/enum); tools/list against a fake designation; tool_scope_*
   dispatch; annotation hints per form.
3. bridge (mcp_test -b): register-in-repl -> tools_changed arrives;
   tool_scope_set {repl} -> tools/list shows the repl-registered
   tool; run a params tool end to end (typed error round trip);
   mcp_test_run over the bridge.
4. e2e (test_agentic.py): the self-extension loop verbatim (eisbach
   method -> mcp_tool (method) -> tool_scope_set -> tools/call ->
   mcp_test_run -> persist -> load_theory -> tool still served);
   find_theorems-in-isar parity check against the builtin; bundle
   open/close changes tools/list; document build of a theory using
   @{mcp_tool_schema} renders (isabelle document run in the test).

acceptance criteria
-------------------

a user, writing only isar, can: register a diagnostic command with a
real parameter schema (types, defaults, enums, per-param docs) and
call it over mcp with typed argument errors; scope a tool inside a
bundle and observe it served exactly while the bundle is open;
exclude a tool with declare [[mcp_tool del: ...]]; wrap a proof
method (eisbach or not) and drive it against a repl; evaluate ML and
term-level snippets with errors pointing into the snippet; declare
tests next to tools that run at build time and on demand via
mcp_test_run. the agent can perform the whole self-extension loop in
one session without a rebuild. ctrl+click on a tool name in jEdit
jumps to its registration; @{mcp_tool_schema} typesets the schema in
a theory document. all four test layers green, including all phase
1–2 regressions.

out of scope (phase 4+)
-----------------------

- schema derivation from arbitrary Scan/Parse parsers (opaque by
  construction; the params clause is the answer)
- structuredContent / outputSchema for wrapped tools (results remain
  captured prover text; revisit with markup-preserving output)
- non-diagnostic command wrapping (thy_decl etc. mutate theories;
  the repl + load_theory loop is the sanctioned mutation path)
- a tool marketplace / AFP-wide index of registerable commands
  (discovery via search_sources + the inventory above suffices)
- completion api wiring for tool-name arguments (phase-3+ of the
  resources story; same mechanism would serve both)
