## Anatomy of a component with a Scala tool

Template: `src/Tools/Demo` in the distribution (see its `README.md`).
A component is a directory:

```
mycomp/
  etc/settings      # bash script, extends the Isabelle environment
  etc/build.props   # declarative Scala/Java build (Java properties, UTF-8)
  src/*.scala       # sources
  lib/mycomp.jar    # build output (generated)
```

`etc/settings` minimal content (`$COMPONENT` is the component dir):

```bash
MYCOMP_HOME="$COMPONENT"
```

Do NOT manually append to `ISABELLE_CLASSPATH`/`ISABELLE_TOOLS` for a
jar declared in `build.props` — the module system handles the classpath
and service discovery. (`ISABELLE_TOOLS` is only for *external* tools:
executable scripts placed directly in a listed directory.)

`etc/build.props` keys (file names relative to the component dir;
settings variables like `$ISABELLE_HOME` allowed):

- `title` (required) — human-readable name, shown during builds.
- `module` — output jar path, e.g. `lib/mycomp.jar`. Absent → no build.
- `requirements` — jars needed for compilation, e.g.
  `env:ISABELLE_SCALA_JAR` (the main `isabelle.jar`); `env:VAR` expands
  a colon-separated settings variable.
- `sources` — `.scala`/`.java` files (both languages may be mixed).
- `services` — class names registered as `isabelle.Isabelle_System.Service`
  providers. **Without this, your tool/functions are never discovered.**
- Also: `resources` (`source:target` pairs copied into the jar),
  `no_build`, `main`, `scalac_options`, `javac_options`.

## Registering the component

User-space registration (records the path in `~/.isabelle/Isabelle2025-2/etc/components`):

```
isabelle components -u /path/to/mycomp     # add; -x removes
isabelle components -l                     # list registered components
```
