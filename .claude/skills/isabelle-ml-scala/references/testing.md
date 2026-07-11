## Unit-testing idioms (no test framework in either language)

- **Isabelle/ML**: put `\<^assert>`-based tests in a theory of a
  *separate session*; `isabelle build -d . My-Tests` is the runner
  (fails iff a test fails), and test-time side effects (e.g.
  registrations in a `Synchronized` registry) stay out of the
  production heap. Two pitfalls verified in practice: sessions cannot
  share a directory, so the test session needs
  `session My-Tests in "Tests" = My-Session + ...`; and the test
  theory must import the parent-session theory by *session-qualified*
  name (`imports "My-Session.My_Thy"`) or the loader looks for a file
  next to the test theory.
- **Isabelle/Scala**: the distribution ships no test framework, but a
  separate test component can vendor one — this project runs munit
  suites from `mcp_test/` (`isabelle mcp_test`, `-b` for
  prover-spawning suites, `-t PATTERN` to filter; vendored jars in
  `mcp_test/lib/ext/`, munit needs org.scalameta's junit-interface
  fork). Design for testability: keep request/protocol logic behind a
  small trait so tests can fake the expensive collaborator (a PIDE
  session — `Fake_Backend` in `mcp_test/src/mcp_testing.scala`), and
  parameterize I/O loops over `BufferedReader`/`PrintStream` instead
  of hardwiring `System.in`/`System.out`.
- **Heap suites** (fresh-process semantics — exactly what a live
  server inherits from a saved heap): evaluate probes via
  `isabelle ML_process -l SESSION -e '...'`. PITFALL: `-e` sources
  compile in the bootstrap ML environment, where structures defined in
  a theory's `ML \<open>...\<close>` blocks are NOT name-visible (their values
  are in the heap; the bindings are per-theory). Compile the probe
  inside the theory context instead:
  `ML_Context.eval_in (SOME (Proof_Context.init_global thy))
  ML_Compiler.flags Position.none (ML_Lex.read "...")` with `thy`
  from Thy_Info (try both qualified and unqualified keys). The probe
  travels as a single-line ML string literal (ML strings cannot span
  newlines). Pattern: `MCP_Registry_Heap_Tests` in
  `mcp_test/src/mcp_heap_tests.scala`.
- During a *batch build*, `Thy_Info` holds only ancestor-heap
  theories, not the session's own theories being built — ml-unit
  tests can only designate/look up parent-session theories; live
  lookups belong in bridge suites.
- **Interactive**: `isabelle console -d DIR -l SESSION` gives an ML
  REPL; `isabelle scala` a JVM REPL with component jars on the
  classpath.
- Worked example: the mcp + mcp_test components (`mcp_test/src/`,
  `mcp/Tools/Tests/`, spec section "testing architecture").
