## Writing a command-line tool in Scala

```scala
package isabelle.mycomp

import isabelle._

object My_Tool {
  val isabelle_tool =
    Isabelle_Tool("mycomp_hello", "one-line description", Scala_Project.here,
      { args =>
        var options = Options.init()
        val getopts = Getopts("""
Usage: isabelle mycomp_hello [OPTIONS]
""",
          "o:" -> (arg => options = options + arg))
        val more_args = getopts(args)
        val progress = new Console_Progress()
        progress.echo("hello")
      })
}

class Tools extends Isabelle_Scala_Tools(My_Tool.isabelle_tool)
```

and in `build.props`: `services = isabelle.mycomp.Tools`.

Pitfalls verified in practice:

- Tool discovery goes through the service registry
  (`Isabelle_Tool.internal_tools` collects `Isabelle_Scala_Tools`
  instances). A missing `services` entry yields
  `Unknown Isabelle tool: "mycomp_hello"` even though the jar builds.
- The default `new Progress` is a **silent sink** — `progress.echo`
  prints nothing. Use `new Console_Progress()` in the tool wrapper.
- Choose a tool-name prefix unlikely to clash with existing tools.

## Building and testing the build

- Builds are **implicit**: any `isabelle` Scala invocation (including
  running the tool itself) rebuilds out-of-date modules first.
  Up-to-date checks use SHA1 digests stored in
  `META-INF/isabelle/shasum` inside the jar.
- Explicit build: `isabelle scala_build` (`-q` quiet). Avoid `-f` (force) — it fails under flatpak with `Read-only file system` when trying to rebuild `isabelle.jar` in the read-only image.
  Compile errors surface here.
- `isabelle` with no arguments lists all available tools — grep it to
  confirm registration.
- Interactive exploration: `isabelle scala` (Scala REPL with the full
  Isabelle classpath; `import isabelle._`). `isabelle console` gives
  the ML equivalent.
- IDE setup: `isabelle scala_project -f -L -G` (or `-M` for Maven)
  generates an IntelliJ-importable project with symlinked sources.
