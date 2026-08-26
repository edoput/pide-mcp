---
paths:
  - "mcp_test/src/**/*.scala"
---

# Link Scala tests to plans with `spec_test`

When a MUnit test is evidence for a claim declared in `plans/`, register that
link with `spec_test`. Do not encode plan IDs in the human-readable test name,
and do not rely on comments or source-text matches: only registered metadata
appears in the manifest consumed by `tools/spec_gate.py`.

Use the plan filename as the ID prefix. A label declared in
`plans/example_feature` is addressed as `example_feature#<label>`.

- Active `A` and `I` labels go in `verifies`.
- `T` labels go in `covers`.
- `D` and `Q` are not test-link targets: dependencies resolve structurally and
  open questions block completion.
- One test may carry several genuine links of either kind.
- Keep an ordinary `test(...)` when no declared plan claim is actually tested.
  Never attach an unrelated label merely to improve the coverage count.
- A covered `T` label's plan layer must equal the registered suite layer.
  Layer roles and names come from `mcp_test/etc/test_layers.json`.

## Worked mapping

Given this content in `plans/example_feature`:

```text
example_feature — implementation plan
=====================================

status: in progress

assumptions and how to test them
--------------------------------

A1. a request made before the backend is ready returns a normal MCP tool
    result rather than a JSON-RPC error.

D1. the result carries the readiness progress text so clients can retry.

T1 [scala unit]: drive the handler with a fixed Not_Ready state; assert the
    response has isError=true, preserves the request id, and names progress.
```

the corresponding Scala test is:

```scala
class Example_Feature_Tests extends MCP_Suite {
  spec_test("not-ready calls return an actionable tool result",
      verifies = List("example_feature#A1"),
      covers = List("example_feature#T1")) {
    // arrange, exercise, and assert the behavior declared above
  }
}
```

`spec_test` validates the qualified ID shape and relation kind during test
registration. The exporter then records the suite, test name, source location,
layer, and links without evaluating the test body.

After adding or changing links:

1. Rebuild `mcp_test/lib/mcp_test.jar` according to `scala-rebuild.md`.
2. Run the relevant tests. `isabelle mcp_test -t 'example_feature#'` selects by
   metadata ID; its emitted manifest still contains every registered suite.
3. Run `python3 tools/spec_gate.py --test-manifest
   mcp_test/lib/munit-spec.json`.
