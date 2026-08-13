isabelle mcp_server — project spec

goal
----
id: S-goal

we are writing an implementation of a mcp for the isabelle proof assistant
as an isabelle tool.

we want the mcp to allow users to write custom tools using the isabelle/ml
language. this will allow for reuse of pre-existing tools implemented by the
community.

the flagship demonstration of that goal is the REPL module: an
interactive proving tool family declared entirely through the isar
extension mechanism below, with isabelle/scala reduced to generic
transport and session infrastructure carrying no repl-specific logic.
see "the repl as the flagship example" under phase 3.

directories
-----------
id: S-directories

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
id: S-phase-0-research

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
id: S-phase-1-mvp-isabelle-mcp

status: DONE (2026-07-07)

goal
----
id: S-goal-phase-1

an external test program spawns `isabelle mcp_server`, speaks MCP to it, and
triggers the execution of a tool that is defined and registered in Isabelle/ML
inside a theory file. the round trip proves the whole chain:

  test client <--json-rpc/stdio--> scala server <--pide protocol--> ML tool

architecture decisions (from phase 0)
--------------------------------------
id: D-undated-architecture-decisions

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
id: S-components-deliverables
superseded_by: D-2026-07-21-server-startup-readiness

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
     (the SERVER no longer blocks that way — see "server startup and
     readiness", decided 2026-07-21. this stays as test_mcp.py's own
     socket timeout, since the acceptance test still waits for a
     genuinely ready server before asserting on tool results.)

implementation order
--------------------
id: S-implementation-order

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
id: S-testing

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
id: S-acceptance-criteria

the mvp is done when test_mcp.py exits 0 against a fresh build, i.e. an
external program connected to the server, listed the ML-registered tool,
ran it in the prover, and got the computed result back.

out of scope for the mvp (see phase 2 / later)
-----------------------------------------------
id: S-out-scope-mvp
superseded_by: D-2026-07-13-mcp-tools D-2026-07-09-mcp-resources

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
id: D-2026-07-07-testing-architecture

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
id: S-phase-2-agentic-proving

status: IN PROGRESS

goal
----
id: S-goal-phase-2

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
id: S-reuse-i-r-don-t

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
id: S-sessions-file-layout

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

server startup and readiness (decided 2026-07-21)
--------------------------------------------------
id: D-2026-07-21-server-startup-readiness
supersedes: S-components-deliverables
superseded_by: D-2026-07-30-catalog-long-pole-build

supersedes the mvp's blocking startup (phase 1's "generous startup
timeout, default 600s" under test_mcp.py, which stays only as that
program's socket timeout). plan: plans/readiness.

the mvp's MCP_Server.run builds the session heap and boots the prover
BEFORE serving stdin, so `initialize` itself blocks for as long as the
build takes — minutes on a cold heap, tens of minutes if HOL must be
rebuilt. an mcp client cannot distinguish that from a hung server, and
most give up before the handshake completes. the json-rpc loop must
never be gated on the prover.

decision: the server serves immediately and carries a READINESS state;
the build and prover boot run on a background thread.

  Not_Ready(progress)   building or booting; progress is a short human
                        string ("building HOL", "starting session")
  Ready(backend)        the MCP_Backend is live
  Failed(message)       build or boot failed; terminal until restart

- protocol methods that describe the server — initialize, tools/list,
  resources/list, resources/templates/list — answer from the first
  line read, in every state. capabilities and the advertised tool set
  do not depend on the prover.
- tools/call for a prover-backed tool while Not_Ready returns an
  isError result naming the state and the progress string, not a
  json-rpc error: the call is well-formed and will succeed later, so
  it is a tool-level failure the agent can retry, not a protocol
  fault. Failed says so, and says the build failed.
- the tool list is NOT filtered by readiness. hiding tools until ready
  would rely on clients re-fetching after notifications/tools/
  list_changed, which many cache through; a tool that returns a clear
  transient error beats one that never appears. list_changed keeps its
  existing job (runtime MCP_Tool.declare) and is not driven by
  readiness transitions.
- isabelle://session reports the readiness state, so an agent has a
  read that answers "what is this server doing" before any tool works.
- the server owns the build (Build.build on the background thread,
  same call the mvp made inline). an mcp client has no shell to run
  `isabelle build -b` itself, so a probe-only server would strand it.
  a build that is already current costs a few seconds and the state
  goes straight to Ready — the common case is unchanged.

not in this decision, deliberately: streaming build progress to the
client (the Console_Progress feed is on stderr, invisible over stdio,
and mcp has no server-initiated progress channel we already use).

the catalog is the long pole, not the build (measured 2026-07-30)
------------------------------------------------------------------
id: D-2026-07-30-catalog-long-pole-build
supersedes: D-2026-07-21-server-startup-readiness

supersedes this section's original follow-up, which was "let the
prover-free tools answer WHILE THE BUILD RUNS". that follow-up is
withdrawn: it solved a scenario that does not occur. plan:
plans/readiness_catalog.

measurements against MCP-HOL, warm heaps, this machine:

  initialize reply                              2.8s
  build check (Build.build, nothing to do)       ~6s
  start_session (prover actually up)             ~8s
  catalog: load_structure + deps + Store +
    Doc_Catalog                                 ~20s
  total to Ready                                 39s

and the builds themselves, from the recorded session_timing: MCP-HOL
1.07s of proving, MCP-Tools 0.65s. HOL ships prebuilt with any Isabelle
install and our theories are tiny, so even a first run after installing
the server is dependency checking, not proving. THE BUILD IS NEVER THE
SLOW PART for an installed user.

what is slow is the catalog — and every prover-backed tool waits behind
it today for no reason, because MCP_Session's constructor takes
structure/deps/store and the whole thing is computed inside boot(),
AFTER start_session. the ordering is arbitrary: load_structure/deps/
Store/Doc_Catalog are pure source parsing with no heap dependency.

decision: extract the catalog and stop gating readiness on it.

- MCP_Catalog holds structure, deps, store, doc_catalog and the derived
  maps (sessions_map, theory_map, base_names), plus the five methods
  that need nothing else: doc_list, doc_read, list_sessions,
  list_theories, search_sources.
- it is computed on its own future, started at server startup, in
  parallel with build+boot. Ready no longer waits for it, so the prover
  goes live at ~14s instead of 39s.
- the five catalog-backed tools join that future when called. normally
  it has long since resolved; if not they wait a few seconds INSIDE a
  tools/call, which clients tolerate far better than a slow handshake.
- MCP_Backend is unchanged and MCP_Session delegates its five catalog
  methods to the MCP_Catalog it is constructed with, so Fake_Backend
  and every existing suite compile untouched.
- the readiness ADT is unchanged too: no Not_Ready(progress, Option[
  MCP_Catalog]) — the catalog is not a readiness state, it is a
  dependency the tools that need it await.

NOT catalog-backed, despite touching theory_map: scope_add,
scope_remove, scope_show and mcp_resources all go through
known_theory_tiers(), which reads session.resources.session_base.
they stay prover-gated.

noted, not fixed here: Sessions.deps in boot() is called without
.check_errors, so a session in the structure that fails to resolve is
swallowed and leaves a hole in the catalog rather than failing loudly
(observed with an AFP checkout whose HOL-Library imports do not match
this Isabelle). AFP is also most of the ~20s — a user without it
registered gets a much cheaper catalog, which is exactly the case the
lazy future costs nothing.

ml bridge: async protocol commands (the key new mechanism)
-----------------------------------------------------------
id: D-2026-07-09-ml-bridge-async-protocol-commands
superseded_by: D-2026-07-28-builtins-ml-tools D-2026-07-28-parameterised-resources

the mvp's MCP.run_tool runs the tool synchronously inside the protocol
command handler. that blocks the ML protocol loop — fine for "shout",
wrong for a 30s sledgehammer (it would stall the whole session,
including other protocol commands). phase 2 adds an async variant:

  Protocol_Command.define "MCP.ir"    (* args: [id, fname, yxml_args] *)

AMENDED 2026-07-28 (phase 3, "builtins as ML tools" below): MCP.run_tool
FORKS TOO, so the mechanism described in this section now covers BOTH
commands. The paragraph above is the spec's answer to "why two protocol
commands?" — and once the repl/search builtins move into the ML registry
they are served by MCP.run_tool, so a 30s sledgehammer reaches the prover
through THAT command and the same argument applies to it. Isabelle/Scala
needs no change: MCP_Session.ml_run is already UUID-keyed and
promise-based, the same shape ir() uses; only the ML handler changes,
from computing (status, output) inline to forking and posting by id.
Cancellation (deferred, below) likewise applies to both once both fork.

  BUFFERS BELONG TO ONE LAYER. MCP.ir's fork_run registers an output
  buffer because the Ir.* functions are writeln-style — the dispatcher
  is \<open>string -> (string * string) list -> unit\<close> and everything arrives
  through the output channel. MCP_Tool.run RETURNS its string, and a
  moved tool does its own capture via MCP_Output.captured, so
  run_tool's fork must register NOTHING. MCP_Output.find_buffer walks
  the worker's group ancestry (Task_Queue.str_of_groups) for any
  registered gid, so registering at two levels of one task tree is
  genuinely ambiguous, not a theoretical worry.

  CONCURRENCY: tool execution goes from serial to concurrent. The
  registry is Synchronized, contexts are immutable, and MCP.ir has
  forked since phase 2, so this is expected to be safe — but it is a
  real change in execution model, called out here rather than left
  implicit.

  MCP.ir SURVIVES, shrunk from ~28 fnames to 8 and changed in
  character: the ~20 tool fnames leave with the builtin table, and what
  remains is only what Isabelle/Scala itself calls — \<open>repls\<close> (the
  resources/list repl enumeration), \<open>show\<close>/\<open>text\<close> (the
  isabelle://repl/{id} templates), \<open>source\<close>/\<open>source_map\<close>/\<open>entities\<close>
  (the IMAGE TIER of the theory templates), and
  \<open>init_from_document\<close>/\<open>init_from_segment\<close> (repl_init_from_source's two
  locator branches, resolved against a PIDE snapshot Isabelle/Scala
  owns). show/text/repls are called BOTH ways today, so after the move
  Ir.show gains two entry points — a registry declaration and a
  surviving dispatcher case. That duplication is DELIBERATE: routing
  the resource read through MCP.run_tool would put it behind
  MCP_Tool.is_active, so \<open>declare [[mcp_tools del: repl_show]]\<close> would
  break isabelle://repl/{id} — and the resource surface has its own
  scope mechanism (scope_add patterns) that tool activation must not
  preempt. The NAME stays: renaming to something like MCP.internal is
  churn in a working bridge (the MCP.ir_result promise key, fork_run,
  and the bridge suites all carry it) for a command a later wave may
  retire outright.

  RETIRING MCP.ir (NOT this wave): the five image-tier reads could move
  to MCP.read_resource if MCP_Resource grew PARAMETERS — its read
  function is \<open>Proof.context -> string\<close> today, with nowhere to put a
  template's {id}. \<open>repls\<close> needs dynamic registration besides
  (MCP_Resource is a Name_Space of theory-level declarations; a repl
  created at runtime has no declaration site), and the two init_from_*
  would want purpose-specific commands. Doing all three dissolves
  MCP.ir into typed commands — run_tool, read_resource, and one PIDE
  handoff — cleaner than a string-keyed switch. It does NOT remove the
  Isabelle/Scala -> ML call path or the router above it:
  mcp_resource_read is a THREE-TIER router (image -> ML; filesystem ->
  File.read in Isabelle/Scala, for theories the prover never loaded and
  Thy_Info therefore cannot see; loaded/unknown -> a composed
  diagnostic), and tier resolution rests on Sessions.load_structure /
  deps / Store, which has no ML equivalent. Parameterised resources are
  their own feature, comparable in size to the param-schema work and
  wanting the same ptyp machinery.

  AMENDED 2026-07-28, see "parameterised resources" below. The
  PARAMETERS half is now decided (read grows an argument list, params
  reuse the tool machinery, the uri clause is published metadata rather
  than a routing mechanism). Three corrections to the paragraph above:

  - "\<open>repls\<close> needs dynamic registration" is WRONG and superseded. There
    was never a case for per-repl registry entries: isabelle://repl/{id}
    is ONE templated resource that needs to be askable which instances
    exist. That is an ENUMERATION question and it is PENDING — deferred
    until a client other than claude code has been observed consuming
    resources/list. So MCP.ir cannot retire in this wave regardless of
    the parameters work.
  - the two init_from_* do NOT collapse into a single command: they
    carry different arguments (init_from_document takes node_name +
    command_id from the PIDE locator, init_from_segment takes
    theory_name + the offset/pattern/index triple). Two handoffs, not
    one.
  - the dispatcher has TEN live cases, not the eight fnames Isabelle/
    Scala calls: \<open>theories\<close> and \<open>load_theory\<close> (MCP_Repl.thy) have no
    builtin row and no Isabelle/Scala call site but do have live test
    callers. Retiring MCP.ir must dispose of them too — delete the
    cases or move their callers — so "shrunk to 8" understates it.

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

symbol recoding at the client edge (decided 2026-07-22)
-------------------------------------------------------
id: D-2026-07-22-symbol-recoding-client-edge

isabelle's convention at the ml/scala boundary is: ML speaks symbol
notation (\<Longrightarrow>, \<open>...\<close>), scala speaks unicode
(⟹, ‹...›). isabelle/scala normally enforces it for us in
Pure/PIDE/prover.scala — message_output decodes ordinary output chunks
(Symbol.decode via decode_xml), protocol_command_args encodes outgoing
arguments (Symbol.encode_yxml). both deliberately skip the PROTOCOL
channel: message_output branches to protocol_output BEFORE decoding,
and protocol_command_raw does not encode.

the bridge rides that channel exclusively (protocol_command_raw for
MCP.ir / MCP.tools / MCP.run_tool / MCP.read_resource /
MCP.check_designation), so nothing upstream recodes for us and raw
symbol notation was reaching the mcp client verbatim. this qualifies
the "protocol-command chunks are byte-clean, so isar text with newlines
and symbols passes through untouched" note above: byte-cleanliness is
real and still holds on the wire; it was never the same thing as
speaking the client's alphabet.

decision: the mcp server does the recoding itself, at the CLIENT EDGE
— exactly one point in each direction, mirroring phase 1's "yxml on the
bridge, json only on the client edge".

- outbound (decode, \<foo> -> unicode): MCP_Server.text_result and
  MCP_Server.resource_contents apply Symbol.decode to the text as it
  becomes an mcp content block. at the client edge rather than at the
  per-chunk parse sites, because it catches every string regardless of
  which channel produced it, and Symbol.decode is idempotent on
  already-unicode text (its recoder rewrites only \-initiated
  sequences, and its own output contains none) — so text that DID come
  through the decoded channel (render_messages over snapshot messages)
  is unharmed by the second pass.
- inbound (encode, unicode -> \<foo>): MCP_Session.encode_args and
  encode_names pass recode = Symbol.encode to YXML.string_of_body,
  which applies it to TEXT NODES ONLY (Pure/PIDE/yxml.scala,
  Output_String.string). the recode MUST go in as that parameter and
  never over an assembled chunk: running Symbol.encode across finished
  yxml would walk its X/Y control bytes. the model may therefore send
  either alphabet; both arrive at ML as symbol notation.
- the bare Bytes(...) arguments of the other protocol_command_raw call
  sites are deliberately NOT encoded: request ids are UUIDs,
  designations are repl ids / theory long names / bundle names, tool
  and resource names are the exposed mcp names, which the mcp name
  charset restricts to [A-Za-z0-9_-]. all ascii by construction, so
  Symbol.encode would be a no-op. the one argument that can carry
  model-authored term text is the run_tool / ir payload, and that IS
  encoded because it rides encode_args.

consequence, recorded rather than hidden: the CLIENT-EDGE property
weakens from byte identity to identity up to symbol normalization —
send \<Longrightarrow>, get ⟹ back. byte identity still holds strictly
below the decode point, which is where the fidelity tests assert it
(plans/repl_text T1, plans/repl_step T1, ml unit + bridge); those are
unaffected. text holding a literal \<foo> that was meant verbatim (a
tool printing symbol notation as data) comes back as the glyph.

known limitations, accepted:

- the control symbols \<^sub> / \<^sup> / \<^bold> decode to marker
  glyphs ⇩ / ⇧ / ❙ (U+21E9/U+21E7/U+2759), not to typeset sub- and
  superscripts. jEdit renders those with font styling; unicode cannot
  express it. round-tripping through encode is exact.
- ~72 of the 512 entries in etc/symbols carry no code: field (mostly
  \<^const>, \<^cterm>, the ml antiquotation controls). they have no
  unicode to decode to and pass through untouched — desirable: byte
  precision is preserved exactly where it matters.
- tools/list and resources/list DESCRIPTIONS do not pass through
  text_result or resource_contents, so symbol notation written in an
  mcp_tool's `description \<open>...\<close>` still reaches the client
  raw; likewise the backend-derived message on resources/read's
  RPC.error branch. genuine remaining sites, left open deliberately:
  both are metadata/error paths that bypass the two content edges, and
  widening the boundary to cover them is a separate decision.

theory loading and checking: two registries, one story
--------------------------------------------------------
id: D-undated-theory-loading-checking-registries-story

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
id: D-2026-07-13-mcp-tools
superseded_by: D-2026-07-13-builtin-tools-activation-layer

two tool kinds now exist:

1. user tools: the mvp MCP_Tool registry, string -> string, fixed
   {input: string} schema. unchanged — still the extensibility story.
2. builtin tools: implemented in scala (calling MCP_Session methods or
   MCP.ir), each with a real json schema. mcp_server.py's docstrings
   are the template. tools/list returns both kinds.

updated 2026-07-13: the two kinds remain implementation SUBSTRATES
(where schema/dispatch live), but visibility unifies — every builtin
name is mirrored into the ML registry so the one activation layer
(the [[mcp_tools add/del: ...]] attribute, bundles, tool_scope)
governs both kinds. see phase 3, "builtin tools in the activation
layer".

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
  (unchanged by the 2026-07-13 activation unification: builtin mirror
  rows carry no run function and never enter the exposure-name
  computation as ML tools — a user tool that collides with a builtin
  name still gets theory-qualified, the builtin still wins the bare
  name.)
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
id: D-undated-isar-commands-mcp-tool-mcp
superseded_by: S-implementation-order-phase-3

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
id: D-2026-07-09-mcp-resources

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

resource-read tool mirrors (DECIDED 2026-07-15, planned; supersedes
the 2026-07-12 "flagged, not scheduled" note)
. . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .

two operating assumptions govern the resource surface. they interlock;
record them together, not as floating notes.

- PRIMARY (human-in-the-loop): the current — and, on a ~1-year horizon,
  the expected main — usage is a HUMAN directing the model through the
  client (claude code). when a resource is needed, the USER mentions it
  (the client-mediated @server:uri path). autonomous resource-reading
  by the model is NOT required for this mode to work: the human is the
  agent that pulls resources into context. this is why the resource
  surface exists as resources and not as tools — its designed consumer
  (the human, via the client's picker/mention UI) matches the mcp
  resource control model exactly.
- SECONDARY — RESOLVED POSITIVE (2026-07-15, tested against claude
  code / this client): claude code DOES let the MODEL enumerate
  (resources/list) and read (resources/read) resources autonomously,
  mid-turn, without a human mention. the client exposes three
  model-invokable tools for this — ListMcpResourcesTool,
  ReadMcpResourceTool, ReadMcpResourceDirTool — and a mid-turn test
  drove ListMcpResourcesTool(server=isabelle) and two
  ReadMcpResourceTool reads (isabelle://session, isabelle://named/
  greeting) with no user @-mention of any uri; all three returned
  content. so the resource surface is NOT fixed into human-in-the-loop
  only: in claude code an unattended agent reaches it directly. the
  named check resolves the model-facing way, which RELIEVES (does not
  eliminate) the pressure toward mirrors — the mirror decision below
  still stands for clients that surface resources to the human ONLY, and
  for a mode that wants the read surface as tools the model is nudged to
  call. (was: UNVERIFIED, MUST BE RESOLVED; same shape as the "host has
  file tools" assumption under "editing theories".)

decision: build a builtin TOOL mirror for every readable resource, so
a fully-autonomous-proving mode can hide the resource surface and serve
the equivalent tools instead — tools being the primitive whose control
model (model-invoked) matches an unattended agent. "we now need the
equivalent tools" elevates these from flagged→required; wave slotting
in the implementation order is left open (see plans/README).

the mirror set (one tool per readable resource):

  read_theory      {name, lines?}            = isabelle://theory/{name}
  read_commands    {theory, start?, count?}  = .../commands
  read_diagnostics {theory}                  = .../diagnostics
  list_entities    {theory, kind?, prefix?}  = .../entities
  read_session     {}                        = isabelle://session
  read_named       {name}                    = isabelle://named/{name}

already covered — do NOT add mirrors: isabelle://repl/{id} and
.../text already have tool twins, repl_show / repl_text (same Ir.show /
Ir.text backing). read_diagnostics is genuinely distinct from
load_theory/check_theory: it reads the CURRENT diagnostics across tiers
without re-loading. naming: read_* for the new tools; list_entities
keeps its enumeration-flavored name (plan-level, not worth churn).

no new backend: each mirror is a thin call into the exact resolution
path resources/read already uses (theory-name normalization, tier
resolution, lazy/truncation/slicing). the hard rule — factor each uri
handler so tool and resource share ONE function; a mirror must never
grow behavior its resource lacks, or the two surfaces drift. NOR may it
LACK behavior its resource HAS (added 2026-07-30): that is the same
drift running the other way, and it is the direction parameterised
resources actually broke — see the invariant below.

activation (the tools half — clean, rides the phase-3 activation layer):
the mirrors are new BUILTIN tools, so once they exist they mirror into
the ML registry and obey [[mcp_tools add/del]] / bundles like every
other builtin (the drift gate at "builtin tools in the activation
layer" requires their mirror rows). they ship REGISTERED-BUT-INACTIVE
by default, so human-in-the-loop tools/list stays uncluttered; the
bundle
  bundle autonomous_proving = [[mcp_tools add: read_theory read_commands
    read_diagnostics list_entities read_session read_named]]
activates them. the theory carrying this bundle is what an
autonomous-proving run designates as its agent context (tool_scope).
NOTE this does NOT contradict "resources are NOT mirrored" under
"builtin tools in the activation layer": that line forbids mirroring
resource TEMPLATES into the tool-activation registry; these mirrors are
first-class tools that happen to read the same data.

resource suppression (the resources half — mechanism DEFERRED to the
plan): hiding the resource surface has no existing mechanism — resource
scope is scope_add patterns over theory NAMES, and templates are not
per-name entities the activation attribute can touch. likely shape: a
disposition on the designated agent-context theory (the autonomous_proving
theory) that makes resources/list and resources/templates/list return
empty for that context. sketch only; settle the mechanism in the plan.

EVERY DECLARED RESOURCE IS REACHABLE AS A TOOL (decided 2026-07-30;
mechanism PENDING)
. . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .

the six-tool list above is not the rule, it is one INSTANCE of the rule
evaluated on 2026-07-15, when the readable resources were seven
hand-written \<open>MCP_Server.resource_templates\<close> rows plus a
\<open>mcp_resource\<close> command that produced exactly one shape (no arguments,
one concrete uri \<open>isabelle://named/<declname>\<close>). the rule itself is:

  every resource this server DECLARES must also be readable through a
  tool — the same content, from the same handler, for a client (or a
  mode) that only ever sees tools.

"declares" includes what USERS declare. parameterised resources (see
that section) turned \<open>mcp_resource\<close> into a real extension point: a
resource now carries \<open>params\<close> — the SAME param/ptyp universe as tools —
and may claim a uri outside the reserved \<open>theory/ repl/ session
named/\<close> prefixes. two consequences, both of which the hand-written
list gets WRONG today and neither of which is a mere omission:

- \<open>read_named {name}\<close> is strictly LESS capable than the resource it
  mirrors. its schema has one string property and no argument slot, so
  a parameterised named resource (\<open>goal_hints{?depth}\<close>) is readable at
  \<open>isabelle://named/goal_hints?depth=5\<close> and NOT readable by tool at all
  — the tool can only ever request the defaults. this violates the
  no-drift rule in the LACK direction stated above.
- a resource in the free namespace has no tool twin whatsoever, and
  nothing anywhere notices. the six names are a literal list in the
  spec, the plans and the bundle; a registry that grows entries at
  theory-declaration time cannot be tracked by a literal list.

TERMINOLOGY, because "mirror" is already overloaded (a builtin's ML
MIRROR ROW under "builtin tools in the activation layer" is an
activation-registry shadow of a scala row — an entirely different
relation): call the resource→tool relation a TOOL TWIN. the six shipped
ones keep the name "mirror" in their section title, plan filenames and
the bundle, since renaming working plans is churn.

MECHANISM: OPEN. three candidates, recorded so a plan does not invent a
fourth silently. do not implement one before the choice is settled.

- (a) \<open>read_named\<close> grows an argument passthrough — {name, args} where
  args is an untyped blob (a query string, or a list of pairs). one
  tool covers every named resource, present and future, with no
  registry walk. ARGUMENT AGAINST, and it is the strong one: an
  untyped args blob is exactly what param_schema_v2 exists to abolish
  — resources reuse the tool param machinery precisely so their
  arguments are typed and validated, and this hands the model a
  string to guess at. it also leaves the free namespace uncovered.
- (b) one GENERIC \<open>read_resource {uri}\<close> tool, routing any uri through
  \<open>mcp_resource_read\<close>. covers the whole surface including query
  parameters in one row, and is the shape claude code's own
  ReadMcpResourceTool already has. against: it puts uri construction
  on the model, publishes no per-resource schema, and its coverage is
  bounded by the ROUTING GAP (see parameterised resources) — for the
  free namespace it would return "Unknown MCP resource" like
  resources/read does.
- (c) GENERATED twins: project each registry entry into a tool row
  mechanically, since resource params ARE tool params — the same move
  that makes resources/templates/list a generated listing. the twin
  emits a derived inputSchema (no contradiction with the "resources
  have no inputSchema" asymmetry: that asymmetry is about the mcp
  Resource object, and a twin is a Tool object). ARGUMENT FOR, and it
  is a real one: ML resources are addressed by (name, args), NOT by
  template matching, so a generated twin dispatches straight to
  \<open>MCP.read_resource\<close> and READS the free namespace that resources/read
  cannot route — the tool surface would close the routing gap without
  giving ML a template matcher. ARGUMENTS AGAINST: generated names
  land in the TOOL namespace, where the free resource namespace can
  collide with a builtin tool name (a user resource named
  \<open>load_theory\<close>), so a naming/collision rule is needed; and the
  Isabelle/Scala-resident theory-tier resources cannot be generated
  this way at all — tier resolution has no ML equivalent — so (c) is
  a mechanism for the ML half only and the shipped six stay
  hand-written either way.

FOURTH DISCRIMINATOR — ACTIVATION, which cuts hardest against (c): the
\<open>autonomous_proving\<close> bundle above is itself a literal six-name list,
and this section has just argued that a literal list cannot track a
registry growing at theory-declaration time. under (b) the bundle
merely gains \<open>read_resource\<close>. under (c) the bundle CANNOT NAME its
twins — a twin for a user resource does not exist until the user
declares it, yet the twins ship REGISTERED-BUT-INACTIVE, so a generated
twin would arrive inactive with nothing able to activate it. closing
that needs either a set-valued activation (activate every resource
twin — a kind [[mcp_tools add: ...]] does not have today) or generated
twins defaulting to ACTIVE, which reopens the tools/list clutter the
inactive-by-default rule exists to prevent. settle this WITH the
mechanism, not after it.

DRIFT GATE the invariant requires (the reason to state it as a rule and
not a list): declared-resource set ≡ resource-read-tool set, both
directions, checked by a test — the same shape as the builtin drift
gate. this is the gate whose ABSENCE the parameterised-resources
section complains about for \<open>resource_templates\<close> ("advertised before
their backing exists, and with NO drift gate — unlike the builtin
mirror list, nothing checks them against reality"). the twin gate must
not be a second literal list; it reads both registries.

plans: plans/read_theory, plans/read_commands, plans/read_diagnostics,
plans/list_entities, plans/read_session, plans/read_named (per-tool),
under the umbrella plans/resource_tool_mirrors (assumptions, the shared
uri-handler factoring, the autonomous_proving bundle, resource
suppression, and the twin invariant + its open mechanism and drift
gate).

exploring the library universe (theories outside the heap)
-----------------------------------------------------------
id: D-2026-07-08-exploring-library-universe

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

documentation for the agent (manuals, requirements, recap)
-----------------------------------------------------------
id: D-2026-07-14-documentation-agent

decided 2026-07-14 (CHANGELOG same date). three related concerns.
the first (doc_list/doc_read) is scheduled — wave 5 in the
implementation order and plans/README; the other two are recorded
design directions, NOT scheduled (no plan files yet).

manuals as tools: doc_list / doc_read
. . . . . . . . . . . . . . . . . . .

the distribution's manuals are generated from theory sources that
ship with it: src/Doc/<Manual>/*.thy — plain text, one file per
chapter, sectioned by chapter/section headings, greppable (Isar_Ref
is ~10 files, ~674KB total). the pdfs in doc/ are the human
rendering; the agent never reads them. the structured catalog
already exists: isabelle.Doc.contents() (Pure/Tools/doc.scala)
parses doc/Contents into sections and entries {name, title, path} —
the same data the jEdit documentation panel shows — plus release
notes (NEWS, plain text) and examples. the doc-entry name maps to
its source session with no new enumeration machinery: src/Doc is on
the distribution's top-level ROOTS, so the session structure the
server already computes at startup contains every doc session
(session group "doc"), and each carries document_variants =
"<doc name>" ("Isar_Ref" -> "isar-ref", "Logics_ZF" -> "logics-ZF")
— the join key.

tools, not resources (the resource-read tool mirrors reasoning,
applied in reverse: tools are what models call autonomously, and
documentation lookup happens mid-proof, unprompted):

  doc_list  {pattern?}         the Doc.contents() catalog: name,
                               title, section, whether plain-text
                               sources exist for doc_read (glob-
                               filtered over entry names).
  doc_read  {name, section?,   read documentation from its sources.
             lines?}           manual entries: no section -> table
                               of contents (chapter/section headings
                               with positions); section -> that
                               section's source text, truncation-
                               capped. plain entries (NEWS,
                               examples): file content with lines
                               windowing. pdf-only entries (no
                               source in the bundle): honest "pdf
                               only at <path>" reply, probe-safe.

search: no new machinery — the doc sessions are in the session
structure, so search_sources {pattern, sessions: ["Isar_Ref"]}
already greps the manuals; doc_list's reply names the source
session per entry to make that reachable.

the catalog is user-extensible (verified 2026-07-14 against
doc.scala): Doc.dirs() reads $ISABELLE_DOCS, a colon-separated path
list, each directory contributing entries via a plain-text Contents
file (Section headers + "name title" lines; entries resolve to
<dir>/<name> or <name>.pdf, non-pdf plain-text entries legal). a
project's papers/ folder registered that way (the mcp component's
etc/settings can carry ISABELLE_DOCS="$ISABELLE_DOCS:...") shows up
in doc_list with zero server code, and @{doc your-paper} becomes
build-time-checked in theories. pickup is settings-time, not
per-repo dynamic — if dynamic discovery ever matters it is a
doc_list extension, not a Doc-catalog feature.

scope note: doc entries are catalog items, not theories; they do
not enter the resource scope and doc_list is not scope-filtered
(same footing as list_sessions — discovery is never scoped, only
the resources listing is).

details: plans/doc_list, plans/doc_read.

requirements traceability (recorded 2026-07-14, not scheduled)
. . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .

context: agent sessions will start from external documents (pdfs of
ideas to formalize) and must end in work a human can verify against
those documents. external documents stay CLIENT-side — the host
agent reads pdfs natively; a server-side pdf parser buys nothing.
the server owns the formal side of the link only.

direction: a lightweight ML package in the MCP-Tools/MCP-HOL tree —

  - a `requirement` command declaring an id + prose + source pointer
    ("REQ-3", ‹the scheduler never starves a task›, ‹source.pdf
    §4.2›) into theory data;
  - a [satisfies REQ-3] attribute on theorems (+ a declaration hook
    for definitions/constants);
  - a print_requirements diagnostic emitting the traceability
    matrix: requirement -> linked entities, plus UNCOVERED
    requirements. being a diagnostic command, the existing mcp_tool
    machinery exposes it over MCP for free — no new builtin.

honesty machinery the matrix must include (an unaudited matrix is
not ground truth for autonomous proving): thm_oracles / Thm_Deps to
flag theorems resting on oracles or sorry (skip_proofs), and
axiomatization detection over the session's theories.

linking INTO the source document (verified 2026-07-14): @{doc name}
checks only a catalog name — the markup chain (Markup.doc ->
jedit_editor.hyperlink_doc -> Isabelle_System.pdf_viewer) carries
no page/section anywhere. the clean path is a custom antiquotation,
@{doc_section "req-source" "3.2"}: Doc.check-validate the name like
@{doc} does, emit Markup.url with a hyperref fragment
(#page=/#nameddest=) IDE-side and \href document-side — a few dozen
lines of ML, same checked-reference shape as `requirement` /
[satisfies]. combined with the extensible catalog above (the source
pdf registered as a doc entry), requirement source pointers become
checked references instead of prose.

prior art: Isabelle/DOF (Brucker & Wolff) solves exactly this —
ontology-typed text elements (text*[req1::requirement]‹...›),
machine-checked references between informal requirements and formal
entities, certification ontologies (CENELEC 50128). it is an
external component with its own release cadence and latex
toolchain: steal its data-model shape, do not depend on it.

recap theory (recorded 2026-07-14, not scheduled)
. . . . . . . . . . . . . . . . . . . . . . . . .

autonomous sessions should end with a CHECKED recap, not a prose
summary. shape: a generated Recap.thy importing the session's
theories that restates each claimed theorem and proves it by
reference —

  theorem recap_no_starvation: "\<And>t. t \<in> tasks \<Longrightarrow> eventually_runs t"
    by (fact Scheduler.no_starvation)

`by (fact ...)` fails at build time if the named theorem is missing
OR its statement drifted, so `isabelle build` on the recap is the
human's verification step. antiquotations (@{thm}, @{const}) carry
the softer entries (formalized definitions, theory list); the
requirements data above supplies the linking prose per entry. the
recap doubles as ground truth for long tasks: a fresh session
load_theory's Recap.thy and knows exactly what is established.

mechanism sketch: a `recap` builtin — session/repl id in, Recap.thy
TEXT out; the client writes the file (the "server never writes
theory files" policy holds). ML side enumerates the new facts by
diffing Global_Theory.facts_of against the imports; the reply also
carries the trust audit (oracles, sorries, axioms) so a recap that
rests on a skipped proof says so.

editing theories and persisting changes
----------------------------------------
id: D-undated-editing-theories-persisting-changes

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
id: S-implementation-order-phase-2

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
      (the resource-read tool mirrors — read_theory, read_commands,
      read_diagnostics, list_entities, read_session, read_named — are
      now DECIDED/planned, not flagged; see "resource-read tool
      mirrors" above and plans/resource_tool_mirrors.)
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
- [x] scala: documentation tools doc_list / doc_read — Doc.contents()
      catalog joined to src/Doc source sessions via document_variants,
      table-of-contents + section reads over the chapter .thy files,
      plain-entry (NEWS) windowed reads, pdf-only honest fallback.
      no ML involved. see "documentation for the agent" above;
      plans/doc_list, plans/doc_read (wave 5).
- [ ] record results here

testing
-------
id: S-testing-phase-2

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
   - [x] repl_init_from_source: offset and pattern resolve to the
         same command id; a repl initialized there can step
         (2026-07-16: MCP_Ir_Bridge_Tests T3..T5, plans/repl_init_from_source)
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
id: S-acceptance-criteria-phase-2

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
id: S-out-scope

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
id: S-phase-3-isar-level-extensibility

status: SPEC (brainstormed 2026-07-11; implementation follows wave 3
of phase 2). de-risking spikes run 2026-07-11, all resolved — see
"spike results" below; plans/ carries the implementation plans
(mcp_tool_registry, tool_scope, mcp_tool_command).

spike results (2026-07-11)
---------------------------
id: D-2026-07-11-spike-results

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
id: S-goal-phase-3

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
id: D-2026-07-11-pivot-tools-context-entities-global
supersedes: S-components-deliverables D-undated-isar-commands-mcp-tool-mcp

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
id: D-undated-visibility-registration-vs-activation-bundles

two layers, because isabelle separates them too:

1. REGISTRATION — the tool exists in the name space of every context
   that (transitively) imports the registering theory. registration
   is never conditional.
2. ACTIVATION — a name set in the same Generic_Data slot; only
   registered AND active tools are served. activation is manipulated
   by a declaration attribute, following the simproc precedent
   exactly (HOL says `declare [[simproc del: finite_Collect]]`):

     declare [[mcp_tools del: find_theorems]]    (* user's "no_tool" *)
     declare [[mcp_tools add: find_theorems]]

   (attribute name is PLURAL — mcp_tool is a command keyword and
   command keywords delimit spans, so it cannot appear inside
   [[...]]; corrected 2026-07-13 throughout this spec to match the
   implementation. and since 2026-07-13 this example is literally
   true: find_theorems is a BUILTIN, and the attribute governs its
   listing too — see "builtin tools in the activation layer" below.)

   a tool declared at theory top level is active from its declaration
   point onward (register + activate in one step, the common case).

bundles then work with zero new machinery, because opening a bundle
just applies its attributes to the current context (bundle.ML: a
bundle IS a list of (thms, attributes)):

     mcp_tool find_consts (scoped)          (* register, don't activate *)
     bundle search_tools = [[mcp_tools add: find_consts]]

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

builtin tools in the activation layer (decided 2026-07-13)
-----------------------------------------------------------
id: D-2026-07-13-builtin-tools-activation-layer
supersedes: D-2026-07-13-mcp-tools
superseded_by: D-2026-07-28-builtins-ml-tools

problem: the activation machinery above only reached ML-registered
tools, while the bulk of the served surface (~25 builtins and
growing) sat in a static scala table outside it — unlisted from
print_mcp_tools, untouchable by the attribute and bundles,
unprunable. the whole point of scoping is letting the user (and the
agent) shrink tools/list to the task at hand; a scope mechanism that
cannot touch four fifths of the surface misses it. decision: ONE
ACTIVATION LAYER over TWO IMPLEMENTATION SUBSTRATES.

- mirror rows: MCP_Tools.thy declares every scala builtin's name
  into the MCP_Tool registry — a new form Builtin (form tag
  "builtin"), the one-line description duplicated from the scala
  table, no real run function (the run slot errors "builtin tool:
  dispatched Isabelle/Scala-side"; dispatch never reaches it, see
  callability below). mirrors are registered active, like any
  top-level declaration. from here the attribute, bundles,
  print_mcp_tools (rows show [builtin]), the tool_scope_* family and
  the phase-3 antiquotations work over builtins with ZERO new
  user-facing machinery:

    declare [[mcp_tools del: repl_fork repl_merge repl_rebase]]
    bundle exploration = [[mcp_tools add: list_theories find_theorems]]

- the wire: the MCP.tools payload grows a second section, builtins =
  (name, active) for EVERY registered builtin-form row (not just the
  active ones — the merge must distinguish "hidden" from "absent").
  the ML-tool rows are unchanged.
- the merge (scala, still a pure function): tools/list = builtin
  table rows whose mirror is active, plus active ML rows under the
  exposure-name function. AVAILABILITY FLOOR: an EMPTY builtins
  section means the designated context carries no mirrors (a theory
  that does not import MCP_Tools, no ML session yet, a broken -T
  theory) — then the FULL builtin table is served. the pre-2026-07-13
  two-table merge is thereby the degraded mode: diagnosing a broken
  heap never loses the scala tools, which is exactly when they are
  needed. deactivating every builtin is still expressible (all
  mirrors present, none active) because the section carries inactive
  rows.
- callability is ASYMMETRIC, deliberately: a deactivated BUILTIN is
  unlisted but STILL CALLABLE — tools/call dispatches builtin names
  scala-side before consulting activation, unchanged. rationale:
  activation filters discovery, not access (the same rule the
  resource scope pins: "scope filters discovery, not access");
  builtins wrap scala capabilities with NO alternate route (ML
  cannot call into scala), and unlisted-but-callable makes lockout
  structurally impossible — a client that knows tool_scope_show's
  name can always recover, so NO non-scopable meta-core is needed.
  a deactivated ML tool stays unlisted AND uncallable (unchanged,
  pinned by MCP_Protocol.run_tool): ML tools wrap Isar the repl
  reaches anyway, so nothing is severed.
- drift gate: the mirror name set must equal the builtin table name
  set, both directions, pinned by a scala-side test over the live
  bridge — a builtin added in scala without a mirror (or a stale
  mirror after a removal) fails the suite, so the duplicated name
  list cannot rot silently.
- resources are NOT mirrored: the resource surface has its own scope
  mechanism (scope_add patterns over theory names), and resource
  templates are not per-name entities the way tools are. MCP_Resource
  (named resources) already rides the registry natively. this is about
  the ACTIVATION registry only, and says nothing about READ ACCESS: a
  resource still owes a TOOL TWIN, per "every declared resource is
  reachable as a tool". riding the activation registry natively does
  NOT make a resource tool-readable — read_named's insufficiency for
  parameterised resources is exactly that confusion cashed out.

SCOPED 2026-07-28 (see "builtins as ML tools" below): everything above
holds for builtins that REMAIN Isabelle/Scala-backed. For the ~20
repl/search builtins that move into the ML registry it does not — and
mostly by its own terms:

- mirror rows, and with them the DRIFT GATE, cease to exist for a
  moved tool: one substrate means nothing to keep in sync. Both shrink
  to the Isabelle/Scala-resident remainder (load_theory/unload_theory/
  check_theory, list_sessions/list_theories/search_sources, doc_list/
  doc_read, scope_*, tool_scope_*, repl_init_from_source) and are
  deleted outright if that remainder ever empties.
- ASYMMETRIC CALLABILITY DISSOLVES for moved tools rather than being
  overturned. Its rationale above is that builtins "wrap scala
  capabilities with NO alternate route (ML cannot call into scala)";
  for a tool whose logic is already ML that premise is false, and the
  rule's OWN second clause governs instead — a deactivated ML tool is
  unlisted AND uncallable, because "ML tools wrap Isar the repl
  reaches anyway, so nothing is severed". A moved tool therefore
  becomes del'able-and-uncallable like any ML tool. This is the
  existing rule applying to a case it did not enumerate, not a
  reversal of it.
- the AVAILABILITY FLOOR narrows the same way: an empty builtins
  section still means "serve the full Isabelle/Scala table", but that
  table no longer holds the moved tools, so a broken heap or an
  unresolvable designation drops them from tools/list instead of
  falling back to a scala row. For repl tools that is honest — with no
  prover there is no REPL to list — but it IS a behaviour change, and
  is called out rather than discovered.

plan: plans/builtin_activation.

builtins as ML tools (decided 2026-07-28)
------------------------------------------
id: D-2026-07-28-builtins-ml-tools
supersedes: D-2026-07-13-builtin-tools-activation-layer S-comes-almost-free

problem: a repl builtin is specified THREE TIMES. repl_replay exists as
a Builtin_Tool row in mcp_server.scala (description, json inputSchema,
annotations), as a dispatcher case in MCP_Repl.thy
(\<open>| "replay" => (keys ["repl"]; Ir.replay (get "repl"))\<close> — a
hand-written untyped schema, since that keys list IS a required-argument
declaration), and as a one-line mirror in MCP_Tools.thy. The description
is written twice, the argument names three times, and nothing checks the
three against each other. The same holds in the constraint layer:
repl_init_from_source's exactly-one rule is implemented separately as
Locator.exactly_one (Isabelle/Scala) and init_from_segment_index (ML),
with two error strings.

decision: the ~20 builtins whose logic is ALREADY ML move into the ML
registry, so each is declared once. This is the project goal applied to
its own surface — builtins stop being privileged relative to the user
tools the goal is about.

- MOVABLE (logic already entirely ML, reached only through Ir): the
  repl_* family, sledgehammer, find_theorems, find_definition.
- STAYING Isabelle/Scala: load_theory/unload_theory/check_theory
  (headless-PIDE use_theories, deliberately disjoint from ML's Thy_Info
  — plans/load_theory), list_sessions/list_theories/search_sources and
  doc_list/doc_read (Sessions.load_structure, Store, filesystem),
  scope_*/tool_scope_* (per-connection Handler state that exists
  nowhere else), and repl_init_from_source, whose locator resolves
  against a live PIDE snapshot. That one SPLITS rather than moves: the
  tool stays Isabelle/Scala and keeps reaching ML through MCP.ir.

what a moved tool looks like — one declaration replacing all three
sites:

  mcp_tool "repl_replay" = capture \<open>fn _ => fn args => Ir.replay (arg args "repl")\<close>
    (description \<open>Re-execute all stale steps in a REPL, in order ...\<close>)
    (params repl :: string \<open>the REPL id\<close>)
    (annotations idempotent_mutating)

\<open>keys ["repl"]\<close> and \<open>"required" -> List("repl")\<close> collapse into the one
params line, the description exists once, and the fname/name mapping
disappears because there is no second name to map to.

a FIFTH FORM is required, and this is the non-obvious part. The Ir.*
functions are WRITELN-STYLE — \<open>dispatch\<close> is typed
\<open>string -> (string * string) list -> unit\<close>, everything arriving through
the output channel — while MCP_Tool's run slot returns a string. Neither
existing hatch fits: ml_run would make each of ~20 tools hand-roll the
capture-and-join-errors block exec_text already implements for diag
wraps. So MCP_Combinators grows

  capture: string -> param list ->
    (Proof.context -> (string * string) list -> unit) -> tool

with form tag "capture": the user's function prints, the combinator
captures through MCP_Output.captured, joins output with any error, and
returns. fork_run's buffer half effectively moves here and is shared. An
\<open>arg\<close> accessor comes with it — validate already guarantees required args
present and defaults filled, so the lookup can be total, where the
dispatcher today uses raw AList.lookup with its own error path. Capture
is also WHY annotations must be declared rather than derived (see the
params section): Ir.show is read-only, Ir.step mutating, Ir.remove
destructive, all one form — the form tag cannot supply the hint, so this
form has no default and requires the clause.

INTERFACE PRESERVATION is structural, not byte-exact. Param names, json
types, required sets and defaults reproduce exactly. DESCRIPTIONS DO
NOT: ml_tool_schema appends a type contract to each property description
(source -> " (verbatim source text)", term -> " (an inner-syntax term,
elaborated before use)"), which the hand-written builtin schemas have no
equivalent of, so a moved \<open>isar_text :: source\<close> gains a suffix. Judged an
improvement and accepted — recorded because it is not a no-diff move.

TESTING shifts layer rather than shrinking. The param -> json
TRANSFORMATION stays fast and prover-free (ml_tool_schema takes a
Tool_Param list; feed it synthetic params). What moves is the assertion
of WHICH TOOL HAS WHICH PARAMS: mcp_handler_tests.scala checks the real
builtin table over Fake_Backend today, and after the move Fake_Backend
can only serve invented rows — i.e. assert about the fake. Those
assertions land as \<^assert> in MCP_Repl_Tests (where a clean build IS
the test signal) or as bridge cases against a live session. A real cost:
~20 tools' interface assertions leave a 192-case prover-free suite for
the build and the slow bridge suite. Against it, the drift gate, the
mirror list and MCP_Server.all_builtin_names all delete, and one bug
class — schema says max_results optional, dispatcher's keys list forgot
it — becomes unrepresentable.

ORDER: the param-schema work (enum, list, the (optional) modifier, the
ptyp variant, the annotations record, exactly_one) lands FIRST and is
independently valuable — it improves user-written ML tools whether or
not a single builtin moves — then async run_tool (ML-only, no
Isabelle/Scala change), then the tools in waves, simplest first
(repl_show, repl_text, repl_back: one \<open>repl :: string\<close> param each),
keeping the drift gate green until the last one leaves. Everything up to
and including async is independently reversible.

plans: plans/param_schema_v2, plans/ml_builtin_migration.

repl designations: put the tools theory in repl_init's own imports
(decided 2026-08-05)
--------------------------------------------------------------------
id: D-2026-08-05-repl-designations-put-tools-theory

this resolves the one question BLOCKING the move above.

problem: a client opening a repl using a designation \<open>repl:ID\<close> does
not see tools that are defined in theories the repl does not import.

decision: only tools from imported theories are available in the repl.
This also means that until a user imports \<open>MCP_Repl\<close> in their own
theory -- the theory where the repl tools are defined and registered
-- those tools are not visible.

\<open>repl_init\<close> itself is one of the 20 MOVABLE tools ("builtins as ML
tools" above): it becomes an ordinary \<open>mcp_tool\<close> declaration, with
\<open>theories\<close> an EXPLICIT, undefaulted parameter. There is no automatic
injection of \<open>MCP_Repl\<close> -- a client that wants repl-scoped tools must
name the theory itself, every time:

  repl_init(repl="my_repl", theories=["HOL.Main", "MCP-HOL.MCP_Repl"])

Theories and their tools are then merged automatically in the registry
by Isabelle's own theory import mechanism: \<open>Ir\<close> can build a repl's
theory from a list of names, which lets us choose which theories --
and so which tools -- are visible in a repl, simply by naming them.

Instead of introducing "MCP_Repl" or any other theory dynamically, we
rely on Isabelle's imports to drive the list of available tools, by
merging the parent theories' own tools. This pattern works as expected
for user extensibility, and is also explicit about which tools are
available.

why this is enough: the self-extension workflow (a client declares a
brand new tool live, inside a running repl, via \<open>repl_step\<close>) already
requires the repl to be rooted in a theory that imports \<open>MCP_Tools\<close> —
otherwise the \<open>mcp_tool\<close> keyword itself does not parse. Every existing
self-extension test already roots its repl in \<open>MCP_Repl\<close> for exactly
this reason. So this decision costs the self-extension workflow
nothing new; it only makes explicit, for the ordinary case too, what
self-extension already required.

\<open>repl_init_from_source\<close> is NOT a gap this decision needs to cover.
That command attaches a repl to a position inside an EXISTING document,
whose theory is fixed by a \<open>.thy\<close> file someone already wrote, so it can
never be given \<open>MCP_Repl\<close> as an extra import. This is not a loss: the
ordinary repl tools (\<open>repl_step\<close>, \<open>repl_show\<close>, ...) are looked up
against the DEFAULT designation, independent of which repl the client
happens to be operating on, so calling them against a
\<open>repl_init_from_source\<close> repl is unaffected either way. The only thing
that needs a repl's own theory to import \<open>MCP_Tools\<close> is
\<open>tool_scope_set {repl}\<close> (self-extension, local activation control),
and that was already unusable on a \<open>repl_init_from_source\<close> repl before
this decision even existed, for the same keyword reason given above —
an existing math document was never going to import \<open>MCP_Tools\<close>. No
workflow is newly broken.

rejected earlier, kept for the record: resolving a \<open>repl:ID\<close>
designation by merging the repl's theory with the server's theory at
lookup time (spiked, then implemented, then withdrawn 2026-08-05). It
looked correct and passed every test that did not touch an open proof
— but Isabelle cannot merge a theory while a proof inside it is open,
and a repl sits mid-proof between almost every step. This is not a bug
we can work around; it is how Isabelle's own bookkeeping for open
proofs works. Do not revisit this approach.

see: .claude/skills/mcp-tool-theories for the full pattern, with a
worked example theory.

plans: plans/ml_builtin_migration (step 2, now a documentation-only
step — see the plan).

the repl as the flagship example (decided 2026-08-05)
--------------------------------------------------------
id: D-2026-08-05-repl-flagship-example

restates and sharpens the goal above, now that phase 3 exists to make
it literal. the CONTRIBUTION this project claims is not the REPL and
not the MCP server: it is the mcp_tool/mcp_resource ISAR EXTENSION
MECHANISM — registry + params clause + validate/capture + activation
+ bundles + antiquotations ("builtin tools in the activation layer",
"the parameter spec language", "builtins as ML tools", "the
self-extension loop"). the REPL module is the FLAGSHIP EXAMPLE offered
in evidence: once plans/ml_builtin_migration lands, an interactive,
stateful, prover-internal tool family of real size (repl lifecycle,
stepping, forking/merging/pinning/rebasing, sledgehammer,
find_theorems — the ~20 tools enumerated in "builtins as ML tools") is
declared EXACTLY ONCE EACH, in isar, in MCP_Repl.thy — not because the
REPL is special, but because the mechanism is general enough that even
the server's own "privileged" builtins turn out to be ordinary
instances of it. that sentence is already in the spec ("builtins stop
being privileged relative to the user tools the goal is about"); this
section exists to say plainly that THAT is the paper's claim, and the
REPL is what proves it rather than what it is about.

consequence for isabelle/scala's role, stated as the end state rather
than left implicit in the migration plan: after ml_builtin_migration,
mcp_server.scala carries no repl-specific business logic at all — no
tool descriptions, no argument schemas, no dispatch table for Ir.*.
what remains scala-side is generic: json-rpc transport, session
lifecycle (readiness, build, PIDE boot), and the handful of
capabilities enumerated below that genuinely have no ML equivalent.
the repl's entire tool surface becomes legible by reading one isar
theory.

the honest boundary — what "completely in isabelle/ml or isar" does
NOT mean, audited so the paper's claim survives review:

- TRANSPORT stays scala by a phase-0 decision, not an oversight: ML
  has no json library, and its stdio is owned by the PIDE protocol
  when managed by scala (phase 0's first research question). symbol
  recoding at the client edge is scala for the same reason.
- fifteen builtins stay scala because they wrap isabelle/scala-only
  apis with no ML counterpart, not because moving them was deferred:
  load_theory/unload_theory/check_theory (headless-PIDE use_theories,
  a registry Thy_Info cannot see); list_sessions/list_theories/
  search_sources/doc_list/doc_read (Sessions.load_structure/deps/
  Store, filesystem); scope_*/tool_scope_* (per-connection Handler
  state that exists nowhere else — there is no "connection" concept in
  ML). see "builtins as ML tools" for the full accounting.
- repl_init_from_source SPLITS rather than moves: its locator resolves
  a source position against a live PIDE document snapshot
  (Document.Node.command_iterator), which is isabelle/scala's alone.
- find_theorems and find_definition are listed MOVABLE in "builtins as
  ML tools" but are SPLIT CANDIDATES per plans/ml_builtin_migration's
  S3/S4: their theory-name normalization
  (MCP_Session.resolve_context_theory) and repl/theory
  mutual-exclusion check have no ML equivalent either. do not claim
  these two as clean moves until wave 6 resolves S3/S4.
- MCP.ir does not retire with this migration — it survives at 8-10
  dispatcher cases (repls, show/text, source/source_map/entities image
  tier, init_from_document/init_from_segment), the isabelle/scala ->
  ML call path for exactly the capabilities above that need a live
  PIDE snapshot or session-structure lookup. "completely in isar"
  describes the TOOL SURFACE users and the agent call; it does not
  claim the bridge beneath it disappears.
- ir.ML ITSELF IS NOT THIS PROJECT'S WORK. it is MIT-licensed prior art
  (project ir/), reused verbatim with its copyright header intact —
  a deliberate reuse decision (phase 2's "what we reuse from I/R"),
  not original contribution. this repo's edits to it are small and
  named explicitly, not folded silently into "verbatim": Ir.context_of
  (added for tool_scope, commit 51bf4781) is the count as of this
  decision. a paper crediting the repl ENGINE would be crediting the
  wrong project; the claim here is about the REGISTRATION MECHANISM
  around it, which is wholly this repo's.

what already exists to typeset this for a paper: the antiquotation
rendering styles under "explorability, antiquotations, documentation"
(@{mcp_tool_schema name}, "table — params/types/descriptions for
papers") are specced for exactly this use, and generate FROM the live
registry rather than being hand-transcribed — so a paper figure stays
correct as the tool set evolves, the same registration-position/
checked-reference argument the spec already makes for theory documents
about tools.

status: this is a NARRATIVE decision (which claim the project makes),
not a new technical one — the mechanism it points at (param_schema_v2,
the capture form, the repl_init naming convention) is already fully
specced. the remaining work is exactly plans/ml_builtin_migration's
waves 1..6 — see plans/README.

parameterised resources (decided 2026-07-28; enumeration PENDING)
------------------------------------------------------------------
id: D-2026-07-28-parameterised-resources
supersedes: S-explorability-antiquotations-documentation

the driver is USER EXTENSIBILITY, not MCP.ir cleanup. \<open>mcp_resource\<close>
today produces exactly one shape —

    type resource = {description: string, read: Proof.context -> string}

— zero arguments, one concrete uri \<open>isabelle://named/<declname>\<close>. every
INTERESTING resource, the ones carrying {name}, {id}, ?lines=,
?start=&count=, is instead a hand-written row in
\<open>MCP_Server.resource_templates\<close>: seven entries with hard-coded uri
shapes and descriptions, advertised (its own comment admits) before
their backing exists, and with NO drift gate — unlike the builtin
mirror list, nothing checks them against reality. a user therefore
cannot write a resource that takes an argument at all.

parameterising MCP_Resource makes \<open>mcp_resource\<close> a real extension point
and makes resources/templates/list a GENERATED listing. retiring MCP.ir
falls out of that; it is not the goal. it also completes the symmetry
"builtins as ML tools" begins — with ML tools AND ML resources the whole
repl surface (engine, tool wrapper, resource view) is replaceable by the
user in one language.

DEPENDS on the param-schema work landing first: this reuses ptyp,
check_value and validate wholesale rather than growing a second copy.

decision 1: resources reuse the TOOL parameter machinery verbatim. the
read function grows an argument list and the entry grows params:

    type resource =
      {description: string,
       uri: string,            (* rfc 6570 template; see decision 2 *)
       params: param list,     (* the SAME param/ptyp as MCP_Tool *)
       read: Proof.context -> (string * string) list -> string}

the already-specced query parameters land exactly on that universe and
motivated it: \<open>?kind=type&prefix=foo\<close> is enum plus an optional string,
\<open>?start=40&count=20\<close> is nat with defaults, \<open>?lines=120-180\<close> is an
optional string. one validate, one check_value, one wire encoder serving
both MCP.tools and MCP.resources. in isar:

    mcp_resource goal_hints
      uri \<open>isabelle://named/goal_hints{?depth}\<close>
      params (depth :: nat = 3 \<open>search depth\<close>)
      = \<open>fn ctxt => fn args => ...\<close>

ASYMMETRY WITH TOOLS, pinned because someone will later try to "fix" it:
an mcp Resource/ResourceTemplate object has NO inputSchema — only
uriTemplate, name, description, mimeType, annotations. a resource's
params therefore never reach the client as a schema. their client-facing
product is the uriTemplate string plus generated description prose;
their runtime product is validation. do NOT emit a schema for resources.

COMPATIBILITY, stated because a plan author will otherwise assume one
half: the ISAR SURFACE is preserved — a bare \<open>mcp_resource my_simps\<close>
keeps working unchanged (see the default in decision 2). the ML LAYER is
NOT — the record gains two fields and read changes arity, so every
MCP_Resource.declare site and MCP_Protocol.read_resource change, the
MCP.read_resource protocol command grows a fourth argument holding the
yxml args chunk (mirroring MCP.run_tool, which it already mirrors in
shape: uuid-keyed, promise-backed), and MCP_Session.mcp_resources'
3-tuple widens, which reaches Fake_Backend.

decision 2: the uri clause is declared METADATA, not routing. ML
resources are addressed by (name, args) — \<open>MCP.read_resource id
designation name args\<close> — and never by template matching on the ML side.
the uri string is what gets PUBLISHED, not what gets PARSED.

rationale: routing cannot move to ML even in principle for the theory
family. three of the five image-tier reads back \<open>isabelle://theory/...\<close>,
and mcp_resource_read must extract {name} and resolve image vs
filesystem vs loaded BEFORE it can know whether ML is the right target
at all; tier resolution rests on Sessions.load_structure / deps / Store,
which has no ML equivalent. Isabelle/Scala parses the uri regardless, so
a template matcher in both languages would buy nothing.

namespace: FREE, with reserved prefixes. \<open>theory/\<close>, \<open>repl/\<close>, \<open>session\<close>
and bare \<open>named/{name}\<close> belong to the server-shipped resources; anything
else is the user's. allowing declarations outside \<open>named/\<close> is what later
lets \<open>repl/{id}\<close> and \<open>theory/{name}\<close> themselves become ML declarations
rather than Isabelle/Scala rows.

default: no uri clause and no params yields \<open>isabelle://named/<declname>\<close>
— exactly today's behaviour, so existing declarations are untouched.

three registration-time checks, all cheap, all free drift gates:
- the template's variable set equals the param name set (a mismatch
  errors at the declaration, not at read time);
- path variables ({name}) must be required params, query variables
  ({?lines}) optional or defaulted — every specced case already
  satisfies this;
- no two registered resources declare the same template.

ROUTING GAP, recorded as a gap and NOT as a decision: mcp_resource_read
is a flat regex table (repl_uri, named_uri, theory_source_uri, ...)
ending in \<open>case _ => "Unknown MCP resource"\<close>. there is no fallback arm
offering an unmatched uri to ML. so a user template outside the reserved
prefixes PUBLISHES in resources/templates/list but does NOT read — the
free namespace is declarable before it is routable. closing it means
Isabelle/Scala growing a fallback that resolves an unmatched uri to a
registry entry, which necessarily gives ML some template matching after
all and so partly walks back "metadata only" for the user namespace.
that mechanism is undecided; do not let a plan invent it.

TOOL ACCESS (added 2026-07-30): whatever a user declares here must also
be readable through a TOOL, per the invariant under "resource-read tool
mirrors". parameterising this record is what broke the existing
arrangement — \<open>read_named {name}\<close> has no slot for a resource's params,
so a declaration with a \<open>params\<close> clause is reachable by uri and not by
tool. the twin mechanism is OPEN there (three candidates); note only
that candidate (c), generating a twin per registry entry, interacts
with BOTH of this section's open items: it would route the free
namespace by (name, args) and so sidestep the routing gap above, and it
needs no enumerator, since a twin advertises a schema rather than
instances. neither of those is a reason to settle decision 3 early.

PENDING (decision 3, deferred 2026-07-28): enumeration — how a templated
resource reports which instances currently exist, so resources/list can
show \<open>isabelle://repl/{id}\<close> concretely per live repl as the default
scope requires. NOT "dynamic registration": we never wanted per-repl
registry entries, \<open>isabelle://repl/{id}\<close> is ONE templated resource that
needs to be askable for its instances. the candidate is an optional
enumerator, dual to read, returning either concrete uris (ML formats) or
argument tuples (Isabelle/Scala expands); it stays OUT of the record
above until decided, so nothing builds a field whose semantics are
unagreed. deferred deliberately: the answer depends on how a real client
other than claude code consumes resources/list, which is unmeasured.

standing defect to carry into that decision, true TODAY and independent
of it: active_repl_ids (mcp_session.scala) populates the repl entries by
REGEX-SCRAPING human prose — Ir.repls() formats one line per repl for a
person and Isabelle/Scala parses the id back out with
\<open>\A\s*(\S+) \(.*\)\z\<close>, dropping non-matching lines via collect. reformat
that line in ir.ML and every repl silently vanishes from the listing,
with no error anywhere.

drift hazard the decision inherits: once ML declares templates and
Isabelle/Scala routes them, the two can disagree — enumerated uris that
do not route. same class as the builtin drift gate and wanting the same
answer, a bridge test round-tripping every enumerated uri through
mcp_resource_read.

plan: unwritten as of 2026-07-28. the read half (decisions 1 and 2) is
plannable now; the listing half waits on decision 3.

the parameter spec language (params clause) and schema derivation
------------------------------------------------------------------
id: D-2026-07-28-parameter-spec-language-schema-derivation
supersedes: D-undated-isar-commands-mcp-tool-mcp

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

- type universe (closed, v1): string, source, args, nat, int, bool,
  enum (a | b | c), term, typ, fact, plus `list of <scalar>`.
  string is single-line inner-quoted; source is cartouche-quoted with
  multiline framing (line-shift normalized); args splices verbatim
  into the wrapped command's argument position — the default input of
  a bare diag wrap. (source/args landed 2026-07-11 and are folded
  back into this universe 2026-07-28; the implementation-order entry
  below carries their detail.)
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
  (name, type, required, default?, description) over the grown
  MCP.tools payload, where type is a VARIANT (scalars nullary, enum
  carrying its items, list carrying its element type) rather than a
  flat string — so the universe can grow without another positional
  field and nested types compose (decided 2026-07-28, superseding the
  6-field "enum values" tuple specced here originally: the shipped
  wire is 5 fields with a flat string type, and the enum field was
  never built). the scala side expands them into the json
  schema at the tools/list merge — generalizing phase 2's form-tag
  expansion (the two fixed forms become derived param sets).
- annotations are a DECLARED per-tool field (decided 2026-07-28), not
  derived from the form tag: an optional record of the four MCP hints
  (read_only, idempotent, destructive, open_world), each
  independently absent or set, so "declare nothing" stays
  representable on the wire — which is what every string_fun/ml_run
  tool ships today (MCP_Server.ml_tool_annotations returns None for
  every form but diag_wrap). Deliberately NOT a closed enum: the
  field has no nesting and no validation logic, so a variant would
  buy curation rather than type safety — contrast the type universe
  above, where exhaustiveness and list nesting earn it. The five
  buckets the scala builtin table distinguishes (read_only,
  read_only_non_idempotent, mutating, idempotent_mutating,
  destructive) survive as NAMED ML CONSTANTS over that record, so a
  genuinely new combination needs no spec edit.
  Resolution when an \<open>(annotations ...)\<close> clause is absent: the form
  tag supplies the hints where the form PROVES them — diag_wrap ->
  readOnly+idempotent, since a diagnostic command discards its
  toplevel state and that is checked at registration (Keyword.is_diag)
  — and otherwise \<open>MCP_Tool.default_annotations\<close> applies:
  open_world = false, the other three unset. Every tool this server
  serves acts on the running prover and none reaches the open world,
  so that one hint holds by construction; leaving
  read_only/idempotent/destructive unset lets the CLIENT's spec
  defaults stand, and those read an unannotated tool as destructive —
  the conservative direction for a tool that has not said what it
  does. Note this means omission is never neutral: the MCP defaults
  are readOnlyHint false, destructiveHint TRUE, idempotentHint false,
  openWorldHint TRUE, so silence is a claim, not its absence.
  Derivation alone cannot express the five buckets, and the per-tool
  honesty they encode (plans/repl_list's read-only listing,
  plans/repl_replay's idempotent replay — both flagged "spec
  refinement" in their plans) must survive a tool moving substrate.
- CROSS-PARAM CONSTRAINTS (decided 2026-07-28) are a second axis over
  the type universe above, which is per-param and first-order by
  construction and so cannot express a relationship BETWEEN params.
  One constructor ships:

    Exactly_One of string list

  declared as \<open>(exactly_one offset pattern index)\<close>. A single
  declaration drives exactly two things: the runtime check in
  \<open>validate\<close> — the enforcement — and a generated sentence appended to
  the tool's description ("Exactly one of offset, pattern, index is
  required."). It does NOT emit json-schema \<open>oneOf\<close>: client support
  for it is patchy, the encoding over required-sets is verbose and
  easy to get subtly wrong, and the audience for a tool schema here
  is a model, which reads the description. One rule, one
  implementation, no second surface to drift from the check.
  This retires a real duplication: the same exactly-one rule is
  written twice today — Locator.exactly_one (mcp_session.scala) and
  init_from_segment_index's fallthrough (MCP_Repl.thy) — with two
  error strings and nothing checking they agree. Locator.exactly_one
  STAYS, since repl_init_from_source's PIDE-snapshot branch never
  goes through the registry, but it stops being the second copy of a
  rule the ML side also owns.
  DEFERRED (2026-07-28), an At_Most_One constructor: find_theorems
  and find_definition take repl and theory as independent optionals
  and silently let theory win when both are given (the dispatcher
  nests the lookups with theory outermost), discarding the repl
  argument with no diagnostic; the json schema does not express the
  relationship either. Declaring it would turn that silent precedence
  into an error on two shipped tools — a client-visible break — so
  the constructor is not built until that break is wanted. The
  precedence is written down here so it is a KNOWN gap rather than an
  undocumented one.

under the sugar: the MCP_Combinators ML library
------------------------------------------------
id: S-under-sugar-mcp-combinators-ml

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
id: S-comes-almost-free
superseded_by: D-2026-07-28-builtins-ml-tools

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

builtins are not displaced BY THIS SECTION: the payoff inventory above
is about wrapping diagnostic commands, and re-deriving find_theorems in
isar is a PARITY check on that mechanism, not a replacement for the
builtin. SUPERSEDED IN PART 2026-07-28: the builtin
find_theorems/sledgehammer do move into the ML registry eventually, but
under "builtins as ML tools" below and for an unrelated reason — one
declaration replacing three parallel specifications — not as a
consequence of the diag-wrap payoff. The acceptance test survives
unchanged; after the move the builtin IS the isar-derived thing, so
parity becomes definitional rather than checked.

snippet evaluation: ML_val and friends
----------------------------------------
id: S-snippet-evaluation-ml-val-friends

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
id: S-proof-methods-eisbach

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
id: D-undated-tests-isar-mcp-test-command

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
id: S-explorability-antiquotations-documentation
superseded_by: D-2026-07-28-parameterised-resources

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
id: S-self-extension-loop

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
id: D-2026-07-11-skills-impact

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
  rules, mcp_resource, mcp_test, bundle scoping, declare [[mcp_tools
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
id: S-implementation-order-phase-3

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
- [x] agent context on the scala side (done 2026-07-13): Handler-owned
      connection state (designation: "" default | bare canonical theory
      name | "repl:ID"; included bundle names, cleared on every
      tool_scope_set); tool_scope_show/set/include builtins;
      tools/list + tools/call read the designation and bundles.
      ml: MCP_Protocol.designated_context grows the repl branch (a
      hook MCP_Repl.thy installs as Ir.context_of, itself
      last_state(the_repl id) |> Toplevel.context_of, the same shape
      Ir.find_theorems already relies on) and folds bundle names via
      Bundle.includes_cmd; MCP.tools/MCP.resources wrapped
      crash-safe (designated_context_safe, degrading to the empty
      list on a stale/bad designation -- found live via a bridge
      test: the unwrapped call hung the "MCP.tools_result" promise
      forever) mirroring MCP.run_tool's existing (status, output)
      shape; new MCP.check_designation command validates a candidate
      repl/bundle designation before tool_scope_set/include commit it
      (the theory case validates for free via resolve_context_theory,
      which also supplies the "normalize before storing" spelling
      rule). SPEC REFINEMENT: the wire designation stays a BARE
      theory name (no "theory:" prefix) rather than growing one --
      the mcp_tool_registry wire contract already shipped bare names
      and tests against it, so only "repl:ID" is new, disambiguated
      by its own prefix. one new ir.ML export, \<^ML>\<open>Ir.context_of\<close>
      (additive, mirrors the existing set_self_theory/
      sledgehammer_state MCP-integration hooks in the same file).
      exposure names via Name_Space extern (drop the bespoke collision
      function; keep its tests as the contract) is DEFERRED -- an
      internal refactor, not part of tool_scope's user-visible
      surface; the bespoke exposure() function is unchanged. builtin
      coverage (tool_scope_show listing builtin activity) is likewise
      DEFERRED to plans/builtin_activation landing (composes either
      order; A6 there). plan: plans/tool_scope.
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
      the (optional) modifier, and the HOL first-user (value) —
      MCP-Tools-Tests is Pure-based. the (optional) gap was NOT
      recorded at the time and is written down 2026-07-28: param_entry
      derives required = is_none default, so optional-WITHOUT-default
      is unreachable from isar (the data model already supports it —
      MCP_Combinators.param takes required and default independently,
      and validate's value_of drops an absent optional — but no ML
      site has ever set required = false either, so the path is
      untested end to end). it gates find_theorems (repl/theory),
      find_definition (kind/repl/theory) and repl_init_from_source
      (offset/pattern/index), whose optional args carry no default
      and whose absent-vs-empty distinction is semantic; hence it is
      a work item in plans/param_schema_v2 rather than a modifier to
      drop.
      SURFACE CORRECTIONS (outer lexer, see the phase-2 command item):
      quoted command names, plural activation attribute.
      LANDED 2026-08-02 (plans/param_schema_v2), discharging every
      DEFERRED item above: enum (a | b | c) reaches isar and splices
      VERBATIM into the format (membership is already checked by
      validate, so quoting it would break e.g. "kind: const" —
      SPEC REFINEMENT); `list of <scalar>` reaches isar, arrives on
      the wire as repeated keys (the same convention as a json array
      argument), and assembles by quoting each element per its own
      type and space-joining the pieces — one join rule ships rather
      than a configurable spec (SPEC REFINEMENT); a list-of parameter
      carries no default, since no list-literal default syntax is
      specced (SPEC REFINEMENT, recorded as deferred rather than
      invented here); the (optional) gap recorded 2026-07-28 is
      discharged (param_entry stops deriving required from
      is_none default) and an absent optional substitutes the empty
      segment directly, bypassing type-directed quoting — routing ""
      through the type's own quoter would splice a quoted empty
      string, not an empty one (SPEC REFINEMENT). The param type
      itself becomes a closed variant (MCP_Tool.ptyp) rather than a
      flat string, crossing the wire as an XML.Encode.variant — every
      nullary scalar encodes identically, so a mis-ordered scala
      decoder list would silently read e.g. nat as int, catchable only
      by a live bridge fixture reading a real encoded row, never a
      scala-unit test (a synthetic row never crosses the encoder).
      Declared per-tool annotations (the four MCP hints as independent
      options; five named ML constants over default_annotations;
      diag_wrap's are still derived — Keyword.is_diag proves them at
      registration — everything else defaults unless declared) and the
      exactly_one cross-param constraint (Exactly_One of string list;
      a runtime count in validate over the RAW arguments, before
      defaults are filled; a generated sentence appended to the
      description, never a json schema oneOf) both land exactly as
      decided (see "builtins as ML tools" and "cross-param
      constraints" below); the isar (annotations ...) clause takes
      exactly one of the five bucket names (SPEC REFINEMENT —
      arbitrary hint combinations stay ML-only, via
      MCP_Combinators.ml_run taking the record directly). All four
      layers green; A1-A13 each pinned at the layer the plan states
      (ml-unit, scala-unit or bridge).
- [x] builtin activation unification (decided 2026-07-13, done
      2026-07-16): ml mirror rows for every scala builtin (form
      Builtin, tag "builtin", run slot errors "builtin tool:
      dispatched Isabelle/Scala-side"), declared in one folded
      MCP_Tool.declare pass at the end of MCP_Tools.thy; MCP.tools'
      wire shape grows from a flat row list to a PAIR (ml rows,
      builtins section); the builtins section is (base name, active)
      for every registered Builtin-form row, inactive included, so
      "hidden" (registered, del'd) is distinguishable from "absent"
      (no mirror). scala: MCP_Session.Tools_Reply(rows,
      builtin_activation) replaces the bare row list end to end
      (trait, promise, Fake_Backend); the merge rule hides iff
      explicitly (name, false), so an empty/missing section hides
      nothing and the availability floor falls out with no special
      case (MCP_Protocol.empty_tools_body covers the designation-
      resolution-failure branch, which must stay a PAIR too --
      feeding the old bare "[]" to a pair decoder would have hung the
      "MCP.tools_result" promise, caught by a bridge test). The
      exposure() RESERVED set stays the FULL builtin table regardless
      of activation -- only the LISTING is filtered -- so a del'd
      builtin's bare name can never be grabbed by a same-named ML
      tool. tools/call is unchanged: builtin dispatch already
      preceded activation. all four layers green: MCP-Tools-Tests
      (mirror del/add round trip, run-slot error, hidden-vs-absent);
      scala unit (empty-floor, hidden-filter, listed-true, hidden-
      still-callable, all against Fake_Backend.builtin_activation);
      bridge (drift gate over the live MCP_Tools.thy mirrors vs.
      MCP_Server.all_builtin_names, a repl-local del leaves a builtin
      unlisted yet callable); test_mcp.py (a del'd builtin stays
      callable though unlisted, a del'd ML tool is refused). plan:
      plans/builtin_activation.
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
id: S-testing-phase-3

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
   dispatch; annotation hints rendered from a row's own declared
   record (plans/param_schema_v2 — no longer derived from the form
   tag), Some-only, all-absent -> no annotations key.
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

builtin activation cases ride the same layers (2026-07-13; details
in plans/builtin_activation): ml unit — mirror del/add round trip,
mirror run slot errors; scala unit — merge filters by the builtins
section, empty section serves the full table (availability floor),
collision with a builtin name still qualifies the ML tool; bridge —
drift gate (mirror set == table set), del in a designated context
drops the builtin from MCP.tools; e2e — del'd builtin absent from
tools/list yet tools/call succeeds, del'd ML tool absent and
refused.

acceptance criteria
-------------------
id: S-acceptance-criteria-phase-3

a user, writing only isar, can: register a diagnostic command with a
real parameter schema (types, defaults, enums, per-param docs) and
call it over mcp with typed argument errors; scope a tool inside a
bundle and observe it served exactly while the bundle is open;
exclude a tool with declare [[mcp_tools del: ...]] — builtins
included: a del'd builtin leaves tools/list but stays callable, a
del'd ML tool leaves and is refused; wrap a proof
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
id: S-out-scope-phase-3

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
- a rendering app for terms — contemplated 2026-07-22, NOT planned;
  the findings are recorded below so the ground does not have to be
  resurveyed


contemplated: a rendering app for terms (not planned)
-----------------------------------------------------
id: D-undated-contemplated-rendering-app-terms

the question that prompted this: "symbol recoding at the client edge"
buys readable unicode (⟹, ‹...›, λ, ∀) in any terminal, but it cannot
express what jEdit does with font styling — sub/superscripts, bold, and
the colouring that distinguishes a free variable from a bound one. an
mcp app could, by shipping html the client renders instead of text.
recorded as contemplated only. what follows is the survey, not a plan.

the load-bearing unknown, and the reason this is not planned: the
mechanism is an mcp UI resource (a `ui://` resource whose html the
client renders in a sandboxed iframe, linked from a tool via `_meta`).
that is a moving extension to mcp and none of the survey below matters
unless the target client actually renders one. claude code is a
terminal client and does not; claude desktop / web are the candidates.
VERIFY THAT FIRST against the current spec — everything else here
answers only "how would we feed it".

what is already on the wire. MCP_Repl.fork_run wraps every dispatch in
Print_Mode.with_modes [Print_Mode.PIDE], and the MCP.ir protocol
command's own comment already states the intended fork: "Output is YXML
(PIDE print mode); Scala strips or interprets the markup". the scala
side currently takes the STRIPS branch — MCP_Session's ir_result does
XML.content(YXML.parse_body(...)) and throws the markup away. the app
is the "interprets" branch; the extension point was designed in and is
simply unexercised. no ml change is needed to get at it.

but the markup covers terms, not chrome. output that goes through
Pretty / Find_Theorems.pretty_thm carries the semantic markup (Markup
FREE / BOUND / VAR / SKOLEM / TFREE / TVAR / CONSTANT / ENTITY /
TYPING, in Pure/PIDE/markup.scala). output that ir hand-assembles as
plain strings — the find_theorems tally, find_definition's "kind: /
name: / position:" header, the repl_list column header — carries none.
those render as plain text. giving THEM semantic markup is real ml
work; the terms are free. this is the honest answer to "how much can
be done at the ml level": for terms, none; for chrome, all of it.

how to render html from isabelle (the part worth not rediscovering):

- Pure/Build/browser_info.scala, make_html(elements, xml) — the
  existing converter from a marked-up XML.Body to html: html_class
  wraps markup as span/div, entity kinds become entity_def/entity_ref
  links. it hangs off a build-oriented Browser_Info context and its
  cross-reference resolution wants session deps we will not have, so
  COPY the span/entity logic (~40 lines); do not expect to call it
  standalone.
- Pure/PIDE/rendering.scala — the shared jEdit/vscode rendering tables:
  entity_elements, tooltip_elements, foreground / text_color. these are
  the palette and the element filters; they map markup to the classes a
  stylesheet would carry.
- fonts: NOT needed. of the 512 entries in etc/symbols, 440 carry a
  code: field, 410 distinct code points, and ZERO of them land in the
  unicode private use area — every glyph is real unicode that a system
  font can show. the Isabelle DejaVu fonts are a jEdit convenience, not
  a requirement for html.

two tiers, and they are not the same size:

- tier 1, interpret what is already there: stop calling XML.content,
  keep the body, convert the markup. buys colouring, type tooltips,
  click-to-definition. line breaks stay baked in at the margin ml
  formatted with, so the pane does not reflow.
- tier 2, responsive: switch the ir output path from writeln-a-
  formatted-string to Pretty.symbolic_output (Pure/General/pretty.ML),
  which emits the UNFORMATTED pretty tree with block/break markup
  intact; Pretty.formatted(body, margin, recode) on the scala side
  (Pure/General/pretty.scala) then re-wraps at the client's width —
  exactly how jEdit and vscode reflow. this is a real change to how ml
  produces and captures output (symbolic_output returns Bytes.T, not a
  writeln string), not merely "the server stops stripping".

coupling with the recoding boundary, so the two are not designed apart:
Symbol.decode at text_result decodes a FLATTENED string. an app path
never flattens, so it must apply the recode to the text nodes inside
the body instead — both make_html-style conversion and Pretty.formatted
take a recode parameter for exactly this. wire it there or the app
silently reintroduces raw symbol notation.

profile_proof_delta (per-command goal-size profiler)
-----------------------------------------------------
id: D-2026-08-13-proof-profiler-delta

one of three independent, parallel profiler experiments for Isabelle
proof scripts (time / memory / proof-size-delta); this entry covers
proof-size-delta only, the other two are separate spec entries of
their own.

purpose: an agent driving a proof cannot see, from the text alone,
whether a step is making progress toward Q.E.D. or making the goal
state WORSE — more subgoals, bigger terms, both symptoms of a tactic
that "succeeded" (no error) but did something unhelpful (e.g. `induct`
without the right generalization, or an `unfold` that expands a
recursive definition into a much larger term). the fix is not a new
proof method, it is VISIBILITY: report, per command, how the goal
state's SIZE changed, so the agent can spot the step that blew up the
proof rather than shrinking it.

mechanism: `profile_proof_delta {theory_name, isar_text}` builds a fresh,
disposable toplevel state rooted in `theory` (`Thy_Info.get_theory`
plus `Theory.begin_theory` + `Toplevel.make_state`, mirroring
`ir/ir.ML`'s own `init`/`from_specs`, but as new code — `ir/ir.ML` is
verbatim-reused and is not touched), parses `isar_text` into transitions
(`Outer_Syntax.parse_text`), and folds `Toplevel.command_exception`
over them exactly like `ir/ir.ML`'s `exec_text`. Before and after each
transition, if the toplevel state is mid-proof (`Toplevel.is_proof`),
the goal is measured: `Thm.nprems_of` (subgoal count) and
`size_of_term (Thm.prop_of goal)` (term size, `Pure/term.ML`) via
`Proof.goal (Toplevel.proof_of st)`. A theory-level state (not
mid-proof) reports both numbers as ABSENT (N/A), not zero — a
theory-level command is not "a proof step that shrank from nothing",
it simply isn't a proof step. The delta (after minus before) is
reported only when BOTH sides are mid-proof; a step that ENTERS a
proof (`lemma`) or LEAVES one (`qed`) therefore reports N/A for that
step's own delta, by design — the two states either side of it are
not comparable goal states.

input: `{theory_name: string, isar_text: string}` — named `theory_name`
and `isar_text`, not `theory`/`text`: both are themselves registered
Isar COMMAND keywords (`theory ... imports ... begin`, `text \<open>...\<close>`),
so neither can be used as an `mcp_tool` parameter name (the
outer-syntax scanner splits the tool declaration's own span at that
keyword; found empirically, see plans/proof_profiler_delta). `isar_text`
also matches `repl_step`'s own parameter name for the same kind of
argument. `isar_text` is a batch of Isar source (one or more
commands/lemmas), not a single repl_step; this
tool builds its own private toplevel state and is entirely separate
from the `ir/ir.ML` repl machinery — it reads a theory and returns a
report, it never touches `repl_tab` and never claims/releases a repl.
read-only / exploratory, like `find_theorems`'s theory-context path.

output: one line per command (name, position, subgoal-count delta,
term-size delta or N/A), sorted with the largest-growing proof steps
first (steps that shrink, hold steady, or are N/A sort after, in
source order), plus a summary line.

error handling: WHOLE-CALL error, matching `exec_text`'s own fold —
if any transition raises, the whole call fails with the offending
command's name/position in the message and no partial report. Chosen
for consistency with `repl_step`'s existing failure semantics ("if a
step FAILS, state is unchanged") rather than inventing a second
convention; the tool's job is to profile TEXT THAT WORKS, not to
triage syntax errors — `repl_step`/`check_theory` already do that.

plan: plans/proof_profiler_delta. implementation:
mcp/Tools/HOL/MCP_Profile_Delta.thy, structure MCP_Profile_Delta,
`mcp_tool profile_proof_delta`.
