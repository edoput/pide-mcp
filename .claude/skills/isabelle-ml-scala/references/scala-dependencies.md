## Third-party dependencies (jars)

There is no dependency resolution anywhere in the Isabelle build — no
sbt, no Maven. Jars are **vendored by hand** (download from Maven
Central, commit or fetch-script them), *including transitive
dependencies*. The bundled compiler is Scala 3.3.4 on JDK 21, so pick
`_3` artifacts (`_2.13` also loads). Before vendoring, check what the
`scala-3.3.4` contrib component already puts on the classpath
(scala-xml, parser-combinators, swing, parallel-collections — see its
`etc/settings`).

A jar must be visible at two distinct times:

1. **Compile time** — list it in `requirements` in `etc/build.props`.
   Each entry is either `env:VAR` (a colon-separated settings
   variable, e.g. `env:ISABELLE_SCALA_JAR`) or a **plain path
   relative to the component dir** (resolution:
   `src/Tools/Setup/src/Build.java`, `requirement_paths`):

   ```
   requirements = \
     env:ISABELLE_SCALA_JAR \
     lib/ext/scalacheck_3-1.18.0.jar
   ```

2. **Runtime** — tools run from `ISABELLE_CLASSPATH`; add the jar in
   `etc/settings` with the `classpath` function
   (`lib/scripts/getfunctions`, `function classpath`):

   ```bash
   classpath "$COMPONENT/lib/ext/scalacheck_3-1.18.0.jar"
   ```

   This does not contradict the "do NOT manually append to
   `ISABELLE_CLASSPATH`" warning above: that warning covers your own
   `module` jar, which the module system handles; *third-party* jars
   are not modules and need the explicit `classpath` entry.

Alternative: a **jar-only component**, the distribution's own pattern
for external libraries — `contrib/postgresql-42.7.8` is the canonical
example: a directory holding just the jar and a one-line
`etc/settings` (`classpath "$COMPONENT/lib/postgresql-42.7.8.jar"`),
registered with `isabelle components -u`. Use this to keep a test-only
or optional dependency out of your main component; other components'
`requirements` can then reach it via a settings variable you define
(e.g. `MYLIB_JAR="$COMPONENT/lib/mylib.jar"` in the jar component's
settings, `env:MYLIB_JAR` in the consumer's `build.props`).

The same `env:VAR` trick links sibling components: a separate test
component can require the production jar by defining
`MYCOMP_JAR="$COMPONENT/lib/mycomp.jar"` in the main component's
`etc/settings` and listing `env:MYCOMP_JAR` in its own requirements.

### Resolution and versioning: coursier as a dev-time fetcher

Vendoring means resolving the transitive graph and pinning versions
yourself. Do it with the coursier CLI (`cs`) as a *developer utility*
— run once when adding or bumping a dependency, never as part of the
Isabelle build:

```bash
cs fetch org.scalacheck::scalacheck:1.18.0     # resolves + downloads,
                                               # prints jar paths from
                                               # ~/.cache/coursier
cs resolve org.scalacheck::scalacheck:1.18.0   # just prints the graph
```

Copy the printed jars into the component (e.g. `lib/ext/`), skipping
what the `scala-3.3.4` contrib component already provides
(scala-library, scala-xml, ...). Keep the exact version in the jar
file name — the file name in `requirements`/`classpath` entries IS the
version pin (the distribution does the same: `postgresql-42.7.8.jar`).
To bump: re-run `cs fetch` with the new version, swap the jars, update
the two entries. If a dependency recurs often enough, commit a small
fetch script with pinned coordinates instead of documenting the steps.
(`::` in coordinates means "append the Scala suffix" — it selects the
`_3` artifact.)

### Why not sbt (or another build tool)

What the Isabelle build lacks is dependency *resolution*, not
compilation — so a resolver-as-fetcher (coursier, above) fills the
gap; adopting sbt as a build system does not, and works against
load-bearing assumptions:

- The module system's guarantees assume jars built by
  `isabelle scala_build`: service discovery (`services`), implicit
  rebuild on any `isabelle` invocation, and freshness via the SHA1
  shasum stored inside the jar (`META-INF/isabelle/shasum`). An
  sbt-built jar is foreign to all three — you either break "never
  stale" or maintain two build definitions that drift.
- The central dependency is unresolvable anyway: `isabelle.jar`
  (Isabelle/Scala Pure) is not published to Maven Central; under sbt
  it would be an unmanaged jar pointing into the distribution, and
  tests still need the full settings environment and heaps.
- Distributions are self-contained and offline-buildable by design
  (pinned contrib tarballs); sbt reintroduces network-at-build-time
  unless the resolved graph is vendored — at which point sbt adds
  nothing over the vendored jars.
- No upstream precedent: nothing in the distribution uses sbt
  (verified by full-tree sweep; the `org.scala-sbt`-groupId jars in
  `contrib/scala-3.3.4/lib/` are Zinc compiler interfaces, not the
  build tool). `isabelle scala_project` generates IntelliJ/Maven
  layouts, not sbt.
