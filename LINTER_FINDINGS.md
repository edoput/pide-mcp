# Exposing component-installed tools through the MCP — the linter as the case study

Investigation, 2026-08-19. Question: can `isabelle mcp_server` expose
tools that arrive as *Isabelle components*, rather than tools we write
ourselves? The Isabelle linter is the worked example. §6 covers the
follow-up — making discovery and loading dynamic, and whether a component
can be wired in from ML. §7 records the design this settled on, which is
now DECIDED: spec `D-2026-08-19-scala-backed-mcp-tools`, plans
`plans/scala_mcp_fun` and `plans/scala_mcp_tool`.

**Short answer.** "A tool installed as a component" is three different
things, and they have three different answers:

| plane | what a component contributes | how we reach it | verdict |
|---|---|---|---|
| (a) CLI | `Isabelle_Tool` entries (`isabelle lint`) | `Isabelle_Tool.isabelle_tools()` | enumerable for free, **hostile to call** |
| (b) library | typed Scala API behind the CLI (`Linter.lint_snapshot`) | plain method call, in-process | **works — but per-component, see §4** |
| (c) ML/theory | the component's sessions + ROOTS | already covered by `mcp_tool` / `mcp_resource` | already shipped |

The linter is a good demonstrator precisely because it lives in all
three planes at once, so it contrasts them on one example.

---

## 1. The linter, in one paragraph

An Isabelle/Isar linter that flags anti-patterns in proof documents —
things an experienced user would catch in review, mostly drawn from
Gerwin Klein's style guide for Isabelle/HOL. It is an add-on component
implemented in Isabelle/Scala, with a jEdit panel and a `isabelle lint`
command-line tool. The 2022 paper reports 480 findings in Isabelle/HOL,
14016 across the rest of the distribution, and 59573 in the AFP; a
separate `afp_mandatory` bundle found 1595 submission-guideline
violations. In a 22-person survey, over 60% fixed a reported problem in
under a minute.

Repo layout (tag `Isabelle2025-2-v1.0.0`):

```
linter_base/          the engine + CLI      services = isabelle.linter.Linter_Tools
                                                       isabelle.linter.Lints
  src/linter.scala      Linter, Result, Report, isabelle_tool
  src/lint_store.scala  Lint_Store, Selection, Bundle
  src/presenter.scala   Text_Presenter, JSON_Presenter, XML_Presenter
  etc/options           lint_bundles, lints_warning, lints_error, lints_disabled
jedit_linter/         the IDE panel (we do not care)
```

**Version story is fine.** There is a release
`Isabelle2025-2-v1.0.0` (published 2026-05-04) matching our
Isabelle2025-2. So this is "register the component and call it", not
"port it first". It is *not* currently in our `ISABELLE_COMPONENTS`.

---

## 2. Why plane (b) is nearly free for us

The whole engine is this function, and it is **pure**:

```scala
(* linter_base/src/linter.scala:131 *)
def lint_snapshot(snapshot: Document.Snapshot, lint_selection: Lint_Store.Selection): Report = {
  val parsed_commands = snapshot.node
    .command_iterator()
    .map { case (command, offset) => Parsed_Command(command, offset, snapshot) }
    .toList
  lint_selection.get_lints.foldLeft(Report.init(snapshot.node_name)) {
    case (report, lint) => lint.lint(parsed_commands, report)
  }
}
```

No `Build`, no `Store`, no session, no progress, no stdout. It takes a
`Document.Snapshot` — `linter.scala:10` is `import isabelle.Document.*`,
so this is the *same* `Document.Snapshot` PIDE gives us, not a
look-alike type. And we already hold one:

```scala
(* mcp/src/mcp_session.scala:975 *)
val snapshot = session.snapshot(node_name)
val has_errors = snapshot.messages.exists { case (tree, _) => Protocol.is_error(tree) }
```

The batch CLI has to work *harder* than we do. Because `isabelle lint`
has no live session, `Linter.read_theory` (`linter.scala:89`)
reconstructs a synthetic snapshot from export markup — replaying command
spans into a `Document.State` by hand. We skip that entirely.

The selection is an immutable case class, buildable from options we
already have:

```scala
(* linter_base/src/lint_store.scala:166 *)
object Selection {
  def apply(options: Options): Selection = {
    val bundles = space_explode(',', options.string("lint_bundles"))
    ...
  }
  def empty: Selection = new Selection(Set.empty, Set.empty)
}
```

so an MCP tool can take `{theory, bundle?, lints?}` and build one
directly, without touching the option file.

Findings carry line/column, the same shape our `read_diagnostics`
already returns:

```scala
(* linter_base/src/linter.scala:20 *)
case class Result(
  lint_name: String,
  message: String,
  range: Text.Range,
  severity: Severity.Value,
  commands: List[Parsed_Command],
  short_description: Lint_Description
) {
  lazy val line_range = Line.Document(node.source).range(range)
}
```

**One gap to know about.** The shipped `JSON_Presenter`
(`presenter.scala:25`) emits `name`, `severity`, offsets, positions and
command ids — but **not** `message`. For an agent the message is the
whole point, so we would read `Report.results` directly rather than
reuse their JSON.

---

## 3. Why plane (a) does not generalise

Enumerating every component's CLI tool is genuinely free:

```scala
(* Pure/System/isabelle_tool.scala:35,73 *)
private lazy val internal_tools: List[Isabelle_Tool] =
  Isabelle_System.make_services(classOf[Isabelle_Scala_Tools]).flatMap(_.tools)

def isabelle_tools(): List[Entry] = (external_tools() ::: internal_tools).sortBy(_.name)
```

Each entry already has a `name` and a `description` — an MCP tool list,
essentially for free. Calling them is where it breaks:

- **Arguments are `List[String]`.** `Isabelle_Tool.body: List[String] => Unit` (`isabelle_tool.scala:117`).
  There is no schema to hand a client; every tool parses its own
  `Getopts`. That is the same unstructured `{input: string}` problem the
  spec already flags for ML tools, but worse, because the parse errors
  come back as console text.
- **Output goes to stdout, and stdout is our transport.** Tool bodies
  print via `Console_Progress`. Our server speaks newline-delimited
  JSON-RPC over stdio, so a tool body writing to stdout corrupts the
  protocol stream mid-session.
- **External tools exit the process.** For non-Scala tools the closure is
  literally `sys.exit(result.print_stdout.rc)` (`isabelle_tool.scala:28`).
  The dispatcher wraps internal tools in `Command_Line.tool { ... }` too,
  though calling `body` directly does dodge that particular wrapper.
- **Granularity is wrong.** `isabelle lint` runs `Build.build` over whole
  sessions before linting. That is a minutes-long batch job, not a tool
  call.

So plane (a) is fine for *discovery* ("what is installed here?") and bad
for *invocation*.

---

## 4. The extensibility point worth noting

Lints are themselves contributed by the service mechanism:

```scala
(* linter_base/src/lint_store.scala:14 *)
lazy val known_lints: List[Lint_Wrapper] =
  Isabelle_System.make_services(classOf[Isabelle_Lints]).flatMap(_.lints)

(* linter_base/src/lints.scala:859 *)
class Isabelle_Lints(val lints: Lint_Wrapper*) extends Isabelle_System.Service
```

This is classpath-based (a component declares `services =` in its
`build.props`), resolved lazily — not a mutable runtime registry, despite
what secondary sources say about "registering lints at runtime". Bundles
*are* mutable (`Bundle.register_bundle`, `lint_store.scala:109`).

The consequence: a component can add lints without touching the linter,
and if we expose the linter, those lints appear through us too — the
extension point composes one level deeper than we do.

**But do not over-read this.** It proves the *linter* is extensible, not
that other components expose MCP-friendly library APIs. The linter is the
lucky case: its CLI is a thin shell over a pure function that happens to
take exactly the object we already hold. The rest of what is installed
here — `Find_Facts`, `Mutabelle`, `TPTP`, `Dump`, `Build_Manager`,
`Sum_of_Squares` — is session-, batch- or database-shaped, and plane (b)
for those is not a method call on a snapshot. Whether plane (b) is
available is a per-component question, answered by reading that
component's sources. The linter answers "yes" unusually well.

---

## 5. Use-case, stated plainly

An agent writing Isar in a REPL gets errors and warnings from the prover
today. It gets no signal at all about *style*: proofs that should be
structured rather than `apply`-chained, `apply (auto)` where `by auto`
would do, unnamed assumptions, `sorry` left behind, AFP submission rules.
Those are exactly the things a human reviewer rejects a proof for, and
exactly the things an LLM produces by default.

The linter is a ready-made, empirically-validated rule set for that gap,
and it operates on the artefact we already have in hand. Two candidate
surfaces:

- `lint_theory {theory, bundle?}` — a tool, run on demand.
- fold lint results into the existing diagnostics read, tagged as
  `style` severity — so an agent sees them without being taught to ask.

The second is more useful and more invasive; it changes what
`read_diagnostics` means.

**Do lints survive a partial REPL snapshot?** Mostly yes. Of the 24
registered lints (`lints.scala:861`), 19 derive from
`Single_Command_Lint` / `Parser_Lint` / `AST_Lint` / `Illegal_Command_Lint`
and fold over commands one at a time:

```scala
(* linter_base/src/lint.scala:52 *)
abstract class Single_Command_Lint(val name: String, val severity: Severity.Level) extends Lint {
  def lint(commands: List[Parsed_Command], report: Report): Report = commands
    .flatMap(command => lint(command, Reporter(command, name, severity, short_description)))
    .foldLeft(report)(_ + _)
```

Those are indifferent to whether the document is finished — including
`unfinished_proof`, which is an `Illegal_Command_Lint` scanning for
`sorry` (`lints.scala:446`), i.e. exactly the LLM failure mode, and safe
on a partial snapshot.

The other 5 — `apply_isar_switch`, `use_by`, `unrestricted_auto`,
`low_level_apply_chain`, `global_attribute_changes` — are
`Proper_Commands_Lint` (`lint.scala:29`), which filters to proper commands
and then matches *adjacent windows*. They do not require a complete
document either, but they will under-report at the editing frontier: a
`low_level_apply_chain` that is still being typed looks shorter than it
will be. That is a false-negative, not a crash or a false-positive, so
the REPL fold is viable — it just gets more accurate once the proof is
closed.

---

## 6. Dynamic discovery and dynamic loading

Follow-up question: can discovery happen at runtime, can we load the jar
at boot and register the tool, and can any of this be *defined from ML* —
or do we need an out-of-band protocol between component and server?

**Short answer.** Discovery is already dynamic; you do not have to build
it. Loading the jar is not the hard part either — registering the
component does it. The two real constraints are elsewhere:

1. **Someone must own the interface.** `make_services` is keyed by a
   *class*, so either the component knows about us or we know about it.
2. **The snapshot cannot cross into ML.** `Document.Snapshot` is
   Scala-side PIDE state, and the ML/Scala bridge moves strings and bytes
   only. So ML can *declare* a linter tool but cannot *implement* one —
   the declare-and-delegate half is verified working (§6.4).

### 6.1 Discovery is already reflective

```scala
(* Pure/System/classpath.scala:82 *)
val services: List[Classpath.Service_Class] = {
  val services_env = init_services(quote("ISABELLE_SCALA_SERVICES"), ...)
  val services_jars =
    jars.flatMap(jar =>
      init_services(File.standard_path(jar),
        isabelle.setup.Build.get_services(jar.toPath).asScala.toList))
  services_env ::: services_jars
}

(* Pure/System/classpath.scala:94 *)
def make_services[C](c: Class[C]): List[C] =
  for { c1 <- services if Library.is_subclass(c1, c) }
    yield c1.getDeclaredConstructor().newInstance().asInstanceOf[C]
```

Every jar on the classpath is scanned for a `META-INF/isabelle/services`
entry (`Tools/Setup/src/Build.java:360`) listing class names, which are
resolved with `Class.forName` and instantiated reflectively
(`classpath.scala:70`). Nothing is known at compile time.

Empirically, in this install:

```
$ isabelle -?
  afp_build - build and manage AFP sessions          <- AFP component
  find_facts_index - index sessions for Find_Facts   <- Find_Facts component
  mcp_server - Model Context Protocol Server for PIDE <- our component
```

None of those are known to Pure. This is already the dynamic discovery
being asked for.

### 6.2 The jar at boot is free

A component's `etc/settings` puts its jar on the classpath:

```sh
# mcp/etc/settings
ISABELLE_CLASSPATH="$ISABELLE_CLASSPATH:$COMPONENT/lib/mcp.jar"

# isabelle-linter linter_base/etc/settings
ISABELLE_LINTER_JAR="$LINTER_HOME/lib/classes/isabelle_linter.jar"
```

`Classpath.apply` reads the jar list back out of `java.class.path`
(`classpath.scala:25`). I did not trace exactly where the launcher sets
`CLASSPATH` — `isabelle getenv CLASSPATH` comes back empty, so it happens
at exec time, not in the settings environment — but the *effect* is
proven by §6.1: `afp_build`, `find_facts_*` and `mcp_server` all resolve,
so component jars are demonstrably on `java.class.path` at boot.

So `isabelle components -u <linter>` is the whole of "load the JAR at
boot". There is no jar-loading code to write.

### 6.3 Post-boot loading works, but only locally

Isabelle does support loading jars *after* start — and there is a
precedent doing exactly the thing we would want:

```scala
(* Pure/Thy/document_build.scala:207 *)
Classpath(jar_contents = classpath).make_services(classOf[Engine])
  .find(_.name == name).getOrElse(error("Bad document_build engine " + quote(name)))
```

`Classpath(jar_files = ...)` wraps the extra jars in a `URLClassLoader`
whose parent is our own loader (`classpath.scala:53`), so `isabelle.*`
types stay shared and a `Document.Snapshot` we hold can be passed into
code from the new jar.

**The catch:** this produces a *local* `Classpath`. The global one is a
cached singleton built with no extra jars:

```scala
(* Pure/System/isabelle_system.scala:77 *)
if (_classpath.isEmpty) _classpath = Some(Classpath())
```

and `Scala.functions` is a `lazy val` over it (`scala.scala:63`). So a
jar loaded post-boot is visible only to the code that loaded it — it does
**not** appear to ML, nor to any other `make_services` caller. Runtime
loading buys hot-reload for us alone; it does not extend the session.

### 6.4 The ML question: ML declares, Scala executes

ML reaches Scala by **string name**, which is as dynamic as it gets:

```sml
(* Pure/System/scala.ML *)
val function: string -> string list -> string list
```

```scala
(* Pure/System/scala.scala:276 *)
def function_body(session: Session, name: String, args: List[Bytes]): (Tag, List[Bytes]) =
  functions.find(fun => fun.name == name) match { ... }
```

Two things follow, and they answer the question:

- **No out-of-band protocol is needed.** The service mechanism *is* the
  in-band protocol, and it already spans components in both directions.
- **But ML cannot implement a linter tool.** `Scala.function` carries
  `string list` / `Bytes.T list`. `lint_snapshot` needs a
  `Document.Snapshot`, which exists only in the Scala process and has no
  serialised form ML could hold. Our own ML tool signature is
  `run: Proof.context -> (string * string) list -> string`
  (`MCP_Tools.thy:169`) — also string-shaped.

Both halves are now settled, and they point the same way.

**ML cannot *implement* a linter tool.** That follows from the bridge's
type alone: `Bytes.T list` does not carry a `Document.Snapshot`.

**ML *can* declare-and-delegate.** Verified 2026-08-19 by round trip
through the running server — the reentrancy worry (an ML tool invoked
*through* a protocol command, replying through another) does not
materialise. Reproducer, no new Scala code and no rebuild:

```
repl_init      {repl: "DocBridge", theories: ["MCP-HOL.MCP_Repl"]}
repl_step      {repl: "DocBridge", isar_text:
                 mcp_tool doc_bridge = \<open>fn _ => Scala.function1 "doc_names" ""\<close>
                   (description \<open>scala doc catalog\<close>)}
tool_scope_set {repl: "DocBridge"}
tools/call     doc_bridge {input: ""}
  -> 26 lines: classes / codegen / corec / datatypes / demo_easychair / ...
```

`doc_names` (`Pure/Tools/doc.scala:130`) computes the doc catalog
Scala-side from the filesystem, so the result is something ML could not
fabricate; it matches `isabelle doc` exactly. A first pass with
`Scala.Echo` also worked (`echo_bridge('hi') -> 'hi'`, alongside a
pure-ML control tool returning `'HI'`), but echo is a passthrough and
proves less, hence the catalog version.

So the division **ML declares, Scala executes** is a real option rather
than a hoped-for one.

Two caveats, both worth carrying forward.

**What was proven is one step short of the linter path.** The probe calls
a `Scala.Fun` registered *in Pure*. An ML-declared `lint_theory` would
call a **new** `Scala.Fun` shipped in our own jar. That should work —
`Scala.functions` is a `lazy val` over `make_services` (`scala.scala:63`),
resolving from the static classpath, and `mcp.jar` is on it — but it is
an inference, not the thing that was run. The remaining untested step is
"a project-owned `Scala.Fun` is reachable by name from ML", not the
bridge itself.

**Delegating blocks the prover thread.** `Scala.function` is synchronous:
`function_bytes` in `scala.ML` sits in `Synchronized.guarded_access`
waiting for the `Scala.result` protocol command to come back. For
`doc_names` that is milliseconds. For `lint_snapshot` over a real theory
it is however long linting takes, with the prover thread stalled
throughout. A Scala-side tool that resolves the snapshot itself pays no
such cost. This does not change whether the ML route works — it is the
main argument for preferring the Scala route when both are available.

And the mechanical precondition: the REPL must be rooted in a theory that
already imports the tool machinery (`MCP-HOL.MCP_Repl` above), or
`mcp_tool` does not parse — see `.claude/skills/mcp-tool-theories`.

### 6.5 Who owns the interface — three options

| option | coupling | dynamic? | fits the linter? |
|---|---|---|---|
| (a) component implements *our* service class | component → us | fully | not today (upstream must adopt) |
| (b) per-component glue in our jar | us → component | soft, via reflection | **yes** |
| (c) generic reflection over `Isabelle_Scala_Tools` | none | fully | discovery only |

**(a) is the idiomatic Isabelle answer** and has two precedents in Pure
itself, one of which the spec already cites:

```scala
(* Pure/Tools/server.scala:67 *)
class Commands(commands: Command*) extends Isabelle_System.Service
  Isabelle_System.make_services(classOf[Commands]).flatMap(_.entries)

(* Pure/Thy/document_build.scala:401 *)
abstract class Engine(val name: String) extends Isabelle_System.Service
```

In both, the *consumer* defines the interface and any component plugs in
by naming a class in `META-INF/isabelle/services`. The linter does this
itself for lints (`class Isabelle_Lints`, §4). An `MCP_Tools` service
class of ours would be the same shape, and would need no linter-specific
code at all.

**(a) is also a plausible upstream contribution.** The linter already
owns an extension point of exactly this shape (`Isabelle_Lints`), so its
maintainers would recognise the pattern; a lint-consuming MCP service
class is not an odd thing to propose.

**(b) is what the linter needs today**, because it ships
`Isabelle_Scala_Tools` and `Isabelle_Lints`, not our class. Reflection
keeps it soft, so the server still boots when the component is absent.

**(c) stays discovery-only** for the stdout/`sys.exit` reasons in §3.

---

## 7. The design that came out of this

DECIDED 2026-08-19. Spec: "scala-backed mcp tools: scala_mcp_fun and
scala_mcp_tool" (`D-2026-08-19-scala-backed-mcp-tools`). Plans:
`plans/scala_mcp_fun` (tier 1), `plans/scala_mcp_tool` (tier 2).

### 7.1 The inversion

§4 framed this as *who owns the interface* — either the component
implements our service class, or we write glue against theirs. The
decided design deletes the interface. Reflection **is** the interface,
and a string in a theory file is the only thing that names it.

```
  the §4 framing                        what was decided
  --------------                        ----------------
  mcp.jar ──knows──▶ linter.jar         mcp.jar      linter.jar
          or                                 ▲          ▲
  linter.jar ──knows──▶ mcp.jar              └─ string ─┘
                                                  in a .thy
```

Neither jar references the other. That is what buys "we don't know at
build time", and it is why §4's "who adopts whom" question stopped
mattering.

### 7.2 The dataflow

**Declaration** — static, in the user's theory, links nothing:

```
scala_mcp_tool lint = ‹isabelle.linter.Linter.lint_snapshot›
     (description ‹lint a theory›)
          │  thy_decl
          ▼
ML registry (MCP_Tool) — the same registry mcp_tool already writes to
     { name   = "lint"
     , descr  = "lint a theory"
     , target = "isabelle.linter.Linter.lint_snapshot"   ← opaque string
     , run    = fn ctxt => fn params =>
                  Scala.function "MCP.dynamic_call" (target :: encode params) }
```

**Call** — one Scala function of ours, forever:

```
MCP client ──tools/call {name:"lint", arguments:{theory:"Foo"}}──▶ Handler
                                                                     │
   Handler finds "lint" in the ML registry (already how mcp_tool works)
                                                                     ▼
ML: tool.run ──Scala.function "MCP.dynamic_call" [target,"Foo"]──▶ ✖ ML thread parks
                                                                     ▼
Scala: MCP.dynamic_call (the ONE Fun we own)
   1. split target → class "isabelle.linter.Linter$", method "lint_snapshot"
   2. Class.forName(cls, true, Isabelle_System.classpath().class_loader)
   3. .getField("MODULE$").get(null)          ← scala object singleton
   4. reflect signature, check args, invoke
                                                                     ▼
   result ──Scala.result protocol command──▶ ML unblocks ──▶ Handler ──▶ client
```

### 7.3 Why the linter is reachable at all

`lint_snapshot(snapshot: Document.Snapshot, selection: Lint_Store.Selection)`.
ML can produce **neither** argument. A `dynamic_call` that only forwarded
ML's strings could never reach it.

But `dynamic_call` runs in the server process, and a `Scala.Fun` is
handed the `Session`:

```scala
(* Pure/System/scala.scala:22 *)
abstract class Fun(val name: String, val thread: Boolean = false) {
  def invoke(session: Session, args: List[Bytes]): List[Bytes]
}
```

`Fun_String` and `Fun_Strings` are conveniences that *throw the session
away*; extend `Fun` directly to keep it.

So the signature check is not only validation — it is **dispatch**. Each
parameter is filled from one of two sources:

| parameter type | filled from |
|---|---|
| `String`, `Int`, `Boolean` | the ML call args — **explicit** |
| `Document.Snapshot` | `session.snapshot(name)` — **ambient** |
| `Session`, `Options`, `Sessions.Deps` | server state — **ambient** |
| `Lint_Store.Selection` | a coercion from a string |

ML supplies what ML can express; the server supplies what only it has;
the signature says which is which. That is what turns "call string
functions" into "call `lint_snapshot`".

### 7.4 Two tiers, solving different problems

**Tier 1 — `scala_mcp_fun`, no reflection.** If the component ships a
`Scala.Functions` service, ML can call it by name *today*:

```
scala_mcp_fun lint = ‹my_lint›   ⟹   run = Scala.function "my_lint" ∘ encode
```

Proven by §6.4's probe. Their `Fun` gets the session, so it resolves the
snapshot itself — no injection machinery. Cost to the component author: a
`Fun` subclass and one `services` line in `build.props`. And ML can
*validate* the target at declaration, because it already holds the
function table:

```sml
(* Pure/Build/resources.ML:26 *)
val check_scala_function:
  Proof.context -> string * Position.T -> string * (bool * bool)
```

resolved against `#scala_functions` in the session base, which Scala
ships to ML at session start (`resources.scala:51,57`). The
`(single, bytes)` flags report the Fun's argument shape. So tier 1 gets
the same declaration-time proof `mcp_tool` has via
`Outer_Syntax.check_command`.

**Tier 2 — `scala_mcp_tool`, reflection.** Buys exactly one thing:
components that did *not* ship a `Scala.Functions`. Third-party code you
cannot change. **The linter is precisely that** — it ships
`Linter_Tools` (CLI) and `Lints` (its own extension point), and no
`Scala.Functions`.

Tier 1 is "the user develops a new Scala tool" (they own the jar). Tier 2
is "expose a tool whose author never heard of us". Different problems,
hence two commands rather than one with a mode flag.

### 7.5 Where the real work is

Not the plumbing — that is proven. It is **coercion**: ML hands over
strings, the method wants `List[Path]`, `Options`, `Severity.Level`.
Generic types do survive erasure (verified: `javap` on our own `mcp.jar`
shows `find_section(List<Doc_Catalog$Heading>, String)`, so
`getGenericParameterTypes` recovers the real type), so you can *see*
what is wanted — producing it is the job. A small `String => T` table,
and anything outside it a hard registration error.

Four costs, all recorded as behavior in the spec rather than left
implicit:

1. **Blocking** — `Scala.function` parks the prover thread in
   `Synchronized.guarded_access` for the whole Scala computation.
   Microseconds for `doc_names`; the full lint for `lint_snapshot`. The
   main argument for a Scala-resident tool where one exists, and the
   reason async `MCP.run_tool` (`plans/ml_builtin_migration`) matters
   more once these land.
2. **Overloads** — `getMethod` needs parameter types, ML supplies names
   and arity. Reject ambiguity; make the declaration disambiguate.
3. **Trust** — an arbitrary class and method named from a theory.
   Theories already carry `ML ‹...›`, which is strictly more powerful,
   so no new hole is opened.
4. **Validation timing** — check the target at declaration, not at call
   time, so a typo fails at registration with a position.

---

## Open questions

- ~~Does registering `linter_base` perturb the base image?~~ **SETTLED
  2026-08-19: installed, and it does not.** Corrections to what this file
  previously asserted:
  - The release ships **no prebuilt jar**. `Isabelle2025-2-v1.0.0` has
    **zero assets** — it is a source tag. The earlier claim was inferred
    from `etc/settings` naming `lib/classes/isabelle_linter.jar`, but that
    path is the build's *output*, not a shipped file. You must build it.
  - Building works under the flatpak: plain `isabelle scala_build` (no
    `-f`) produced a 430KB `isabelle_linter.jar` in seconds. The
    read-only-jar problem does not arise.
  - Register `linter_base`, **not** the repo root — the root's
    `etc/components` also lists `jedit_linter`, which wants jEdit jars.
  - The jar reaches the classpath even though `linter_base/etc/settings`
    has **no `classpath` line** — `build.props`'s `module =
    $ISABELLE_LINTER_JAR` is enough. Confirmed by `isabelle -?` listing
    `lint`, `lint_bundles`, `lint_descriptions`, all served by the jar's
    `Linter_Tools` service. `isabelle lint_bundles` returns the five
    bundles, so `Lint_Store` and its static bundle init work too.
- Do we take a hard dependency on the component, or degrade gracefully
  when it is absent? (`ISABELLE_LINTER_JAR` is only on the classpath if
  installed, so this is a real branch.)
- Does `Selection` need the component's `etc/options` present, or is
  building one in code (`Selection.empty.add_bundles(...)`) enough to
  skip the option dependency entirely?
- ~~ML -> Scala callback unproven~~ — **settled 2026-08-19, it works**
  (§6.4). Still worth a regression test: `mcp_test/src/mcp_bridge_tests.scala`
  already has the tool_scope self-extension cases this would sit next to.

## Solved in passing: why `isabelle -?` lists `mcp_server` twice

Installing the linter answered this by controlled comparison.

A component's jar reaches `java.class.path` from its `build.props`
`module` declaration alone. `mcp/etc/settings` *also* adds it explicitly:

```sh
# mcp/etc/settings — the redundant line
ISABELLE_CLASSPATH="$ISABELLE_CLASSPATH:$COMPONENT/lib/mcp.jar"
```

So `mcp.jar` lands on the classpath twice, `Classpath.services` scans it
twice, `isabelle.mcp.Tools` is instantiated twice, and the tool is listed
twice. The linter declares `module` and has **no** `classpath` line — and
its three tools each appear exactly **once**. Same mechanism, one
variable different.

FIXED in 64a703d (PR #7): the `ISABELLE_CLASSPATH` line is gone from
`mcp/etc/settings`. Verified 1 occurrence of `mcp_server` in `isabelle -?`
afterwards, against 2 before.

It stopped being cosmetic the moment the component declared its first
Scala function: a duplicated *tool* is a repeated row in a listing, but a
duplicated *Scala function* raises `DUP "MCP.dynamic_call"` at session
startup, because ML builds the function table into a `Symtab`. So the
`scala_mcp_tool` work depends on this fix rather than merely benefiting
from it.

## Sources

- A Linter for Isabelle: Implementation and Evaluation — https://arxiv.org/abs/2207.10424
- isabelle-linter sources, tag `Isabelle2025-2-v1.0.0` — read directly for every signature quoted above

Further reading (not consulted): the bachelor thesis behind the paper,
https://www21.in.tum.de/students/past/linter/assets/linter.pdf — more
implementation detail than the 4-page workshop version.
- isabelle-linter — https://github.com/isabelle-prover/isabelle-linter
