## ML → Scala calls

Scala side: define a `Scala.Fun` and register it via the
`Scala.Functions` service (see `Pure/System/scala.scala`):

```scala
object My_Fun extends Scala.Fun_String("my_fun") {
  val here = Scala_Project.here
  def apply(arg: String): String = ...
}
class Functions extends Scala.Functions(My_Fun)   // + services entry
```

Variants: `Fun_Strings` (`List[String] => List[String]`), `Fun_String`
(`String => String`), `Fun_Bytes`; the general `Fun` is
`List[Bytes] => List[Bytes]`. Constructor flag `thread = true` runs the
call on a fresh JVM thread instead of the bounded thread farm.

ML side (formally checked antiquotations):

```
ML \<open>
  val s' = \<^scala>\<open>my_fun\<close> "arg";        (* thread-farm future *)
  val s'' = \<^scala_thread>\<open>my_fun\<close> "arg"; (* separate JVM thread *)
\<close>
```

Untyped equivalent: `Scala.function1 "my_fun" "arg"` /
`Scala.function : string -> string list -> string list`
(`Pure/System/scala.ML`). A Scala `null` result raises `Scala.Null` in
ML; Scala exceptions arrive as `ERROR`/`Fail`; interrupts propagate
both ways.

**Where it works** (verified): inside the PIDE and inside `isabelle
build` — any ML process that has a managing Scala session. In a raw
`isabelle ML_process` ("without Isabelle/Scala context") the call
raises `Exception Protocol_Message [("function", "invoke_scala"), ...]`
because nobody answers the protocol message.

Serialization: plain strings for simple data; structured data via XML
in YXML transfer syntax — ML: `YXML.string_of_body`, `YXML.parse_body`,
`XML.Encode`/`XML.Decode`; Scala mirrors these. Recode Isabelle symbols
with `isabelle.Symbol.decode`/`encode` on the Scala side. The list of
registered functions is `isabelle.Scala.functions` in Scala.

## Scala → ML calls

Scala drives the prover through a PIDE `Session`
(`Pure/PIDE/session.scala`); for programmatic use without an editor use
`isabelle.Headless` (`Pure/PIDE/headless.scala`, e.g.
`use_theories`).

Low-level protocol round trip (this is exactly how `Scala.function`
itself is implemented — read `Pure/System/scala.ML` +
`Scala.Handler` in `Pure/System/scala.scala` as the reference):

1. ML registers a command:
   `Protocol_Command.define "My.cmd" (fn args => ...)`
   (`Pure/PIDE/protocol_command.ML`; `define_bytes` for binary). The
   defining ML code must actually be loaded in the running session.
2. Scala sends: `session.protocol_command("My.cmd", args...)`
   (also `protocol_command_raw` for `Bytes`).
3. ML answers with `Output.protocol_message (Markup.my_result ...) body`.
4. Scala receives it in a `Session.Protocol_Handler` whose `functions`
   map markup names to handlers; register it as a service in
   `build.props` or dynamically via `session.init_protocol_handler`.

For one-shot batch interaction, a Scala tool can instead just run
`Isabelle_System.bash("isabelle build ...")` or use `ML_Process`.

## Testing the ML/Scala interaction

Minimal end-to-end test (verified to work): a throwaway session that
calls a Scala function from ML and asserts on the result.

`ROOT`:
```
session Test_Bridge = Pure +
  theories
    Test_Bridge
```

`Test_Bridge.thy`:
```
theory Test_Bridge
  imports Pure
begin

ML \<open>
  val s = "test";
  val s' = \<^scala>\<open>echo\<close> s;
  \<^assert> (s = s')
\<close>

end
```

Run: `isabelle build -d . -v Test_Bridge`. The build fails if the
assertion fails, so this doubles as a regression test; replace `echo`
with your own registered function. The predefined demo functions
`echo`, `sleep`, `scala_toplevel` are registered in
`$ISABELLE_HOME/etc/build.props`.

