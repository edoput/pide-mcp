#!/usr/bin/env python3
"""MCP.dynamic_call reaches a third-party component and lints OUR snapshot.

This is plans/scala_mcp_tool's load-bearing claim (A4/T1), proven end to
end: ML names a target as a STRING, our one reflective Scala function
resolves it on the component classpath, fills a Document.Snapshot the ML
side could never express, invokes, and hands findings back.

Nothing in mcp.jar links against the linter, and the linter knows nothing
about us -- the only thing connecting them is the target string below.

Cases:
  1   check_target  resolves isabelle.linter.Linter.lint_snapshot and reports
                    its real parameter names and types (Q4: names survive
                    into the bytecode)
  2   load_theory   a deliberately lint-dirty fixture, so the session holds a
                    live snapshot for it
  3   own-jar       Self_Test.snapshot_info proves the AMBIENT fill handed
                    over a real snapshot, not an empty one
  4   THE claim     lint_snapshot over that snapshot returns the findings the
                    fixture was built to trigger
  5   errors        the three resolution failures give three DIFFERENT
                    messages, and overloads are rejected rather than guessed
  6   over MCP      a `lint` tool via the mcp_tool ML escape hatch: in
                    tools/list, callable, returns findings
  7   the command   scala_mcp_tool itself -- a bad target fails at
                    REGISTRATION, a good one declares, advertises its
                    resolved scala signature, and serves
  8   tier 1        scala_mcp_fun over an ALREADY-registered Scala.Fun,
                    plus its three registration-time refusals: unknown
                    Fun, single Fun with >= 2 params, (optional) param
  9   D3            the lint output PARSES as JSON with named fields
  10  A3            MEASURED: the prover thread is held for the whole
                    scala call (sleep 3s blocks a trivial ML tool too)

NOTE on param names (case 7): an Isar-declared param CANNOT be called
`theory`. The params clause parses names with Parse.name and `theory` is
a command keyword, so it is rejected -- as is `text`. Declarations use
`thy`; dynamic_call accepts theory/thy/theory_name for that reason.

Requires the linter component:
    isabelle components -u <linter checkout>/linter_base
Skips the linter-dependent cases (1, 4, 6, 7, 9) if it is absent,
so the suite stays green without it.

Usage:
  ISABELLE=/path/to/isabelle python3 mcp/test/repro_dynamic_call.py

Under the flatpak, ISABELLE must be a wrapper script; from a worktree it
must also set ISABELLE_IDENTIFIER (see tools/wt-isabelle-build.sh):

  #!/usr/bin/env bash
  exec flatpak run --env=ISABELLE_IDENTIFIER=wt-<name> \\
    --command=isabelle de.tum.in.isabelle.Isabelle "$@"

Exit code 0 iff all executed assertions pass.
"""
import json
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
os.environ.setdefault("MCP_TEST_TIMEOUT", "900")
import test_mcp as T

ISABELLE = os.environ.get("ISABELLE") or sys.exit("set ISABELLE")
REPL = "DynCall"
FIXTURE_DIR = os.path.join(HERE, "lintfix")
LINT_TARGET = "isabelle.linter.Linter.lint_snapshot"


def text_of(reply):
    content = reply.get("result", {}).get("content", [])
    return content[0].get("text", "") if content else json.dumps(reply)[:400]


def is_err(reply):
    return bool(reply.get("result", {}).get("isError")) or "error" in reply


def step(client, isar_text):
    return client.request("tools/call",
        {"name": "repl_step", "arguments": {"repl": REPL, "isar_text": isar_text}})


def call(client, target, *pairs):
    """ML-side Scala.function "MCP.dynamic_call" [target, name, value, ...].

    No encoding: Scala.function moves a LIST, so each name and each value is
    its own element and no separator can corrupt a value.
    """
    args = '", "'.join((target,) + pairs)
    return step(client,
        r'ML \<open>writeln (String.concatWith "\n" '
        r'(Scala.function "MCP.dynamic_call" ["' + args + r'"]))\<close>')


def linter_installed():
    out = subprocess.run([ISABELLE, "-?"], capture_output=True, text=True).stdout
    return any(ln.strip().startswith("lint ") for ln in out.splitlines())


def main():
    have_linter = linter_installed()
    if not have_linter:
        print("NOTE linter component absent -- cases 1 and 4 skipped")

    client = T.Client([ISABELLE, "mcp_server", "-s", "MCP-HOL", "-T", "MCP_Repl"])
    client.request("initialize",
        {"protocolVersion": "2024-11-05", "capabilities": {},
         "clientInfo": {"name": "repro_dynamic_call", "version": "0"}}, timeout=300)
    client.send("notifications/initialized", notification=True)
    T.wait_for_ready(client)

    reply = client.request("tools/call",
        {"name": "repl_init", "arguments": {"repl": REPL, "theories": ["MCP-HOL.MCP_Repl"]}})
    T.verdict("repl_init", not is_err(reply), text_of(reply)[:120])
    if is_err(reply):
        return 1

    if have_linter:
        reply = step(client,
            r'ML \<open>writeln (Scala.function1 "MCP.check_target" "' + LINT_TARGET + r'")\<close>')
        out = text_of(reply)
        T.verdict("1 check_target resolves the linter, with parameter NAMES",
            not is_err(reply) and "snapshot" in out and "lint_selection" in out,
            out.strip().splitlines()[0][:160] if out.strip() else "")

    reply = client.request("tools/call",
        {"name": "load_theory", "arguments": {"name": "Lint_Dirty", "master_dir": FIXTURE_DIR}})
    T.verdict("2 load_theory Lint_Dirty", not is_err(reply), text_of(reply)[:120])

    reply = call(client, "isabelle.mcp.Self_Test.snapshot_info", "theory", "Lint_Dirty")
    out = text_of(reply)
    ok = not is_err(reply) and "commands=" in out and "commands=0 " not in out
    T.verdict("3 ambient fill hands over a REAL snapshot (own-jar target)", ok,
        out.strip().splitlines()[0][:200] if out.strip() else "")

    if have_linter:
        reply = call(client, LINT_TARGET, "theory", "Lint_Dirty")
        out = text_of(reply)
        # the fixture is built to trip exactly these, by reading lints.scala
        wanted = ["short_name", "tactic_proofs", "implicit_rule"]
        missing = [w for w in wanted if w not in out]
        T.verdict("4 lint_snapshot over OUR headless snapshot returns findings",
            not is_err(reply) and not missing,
            "missing: %s" % missing if missing else
            "%d findings" % len([l for l in out.splitlines() if "lint_name=" in l]))

    # --- 5. the error paths A1 requires: three failures, three messages ---
    reply = step(client,
        r'ML \<open>writeln (Scala.function1 "MCP.check_target" '
        r'"no.such.Class.method")\<close>')
    T.verdict("5a missing class says so", "no class" in text_of(reply),
        text_of(reply).strip()[:110])

    reply = step(client,
        r'ML \<open>writeln (Scala.function1 "MCP.check_target" '
        r'"isabelle.mcp.Self_Test.nope")\<close>')
    T.verdict("5b missing method names it and lists what exists",
        "has no method" in text_of(reply), text_of(reply).strip()[:110])

    reply = step(client,
        r'ML \<open>writeln (Scala.function1 "MCP.check_target" '
        r'"isabelle.mcp.Self_Test.overloaded")\<close>')
    T.verdict("5c overloads are rejected, not guessed (D4)",
        "Ambiguous" in text_of(reply), text_of(reply).strip()[:110])

    # --- 6. THE deliverable: a real `lint` tool in tools/list, over MCP ---
    if have_linter:
        body = (r'fn thy => hd (Scala.function "MCP.dynamic_call" '
                r'["' + LINT_TARGET + r'", "theory", thy])')
        reply = step(client,
            r'mcp_tool lint = \<open>' + body + r'\<close> '
            r'(description \<open>style lints for a loaded theory\<close>)')
        T.verdict("6a declare a `lint` mcp_tool over dynamic_call",
            not is_err(reply), text_of(reply)[:120])

        client.request("tools/call",
            {"name": "tool_scope_set", "arguments": {"repl": REPL}})
        names = [x.get("name") for x in
                 client.request("tools/list").get("result", {}).get("tools", [])]
        T.verdict("6b `lint` is advertised in tools/list", "lint" in names,
            "%d tools" % len(names))

        reply = client.request("tools/call",
            {"name": "lint", "arguments": {"input": "Lint_Dirty"}})
        out = text_of(reply)
        missing = [w for w in ["short_name", "tactic_proofs", "implicit_rule"]
                   if w not in out]
        T.verdict("6c tools/call lint returns findings -- THE LINTER OVER MCP",
            not is_err(reply) and not missing,
            "missing: %s" % missing if missing else out.strip().splitlines()[0][:110])

    # --- 7. the scala_mcp_tool COMMAND: declare the target in Isar ---
    if have_linter:
        # 7a the point of the command: a typo fails at REGISTRATION, not at
        #    call time, with a position -- like the diag form's
        #    Outer_Syntax.check_command
        reply = step(client,
            r'scala_mcp_tool broken = \<open>no.such.Class.method\<close> '
            r'(description \<open>should not register\<close>) '
            r'(params thy :: string \<open>t\<close>)')
        T.verdict("7a a bad target fails at REGISTRATION",
            is_err(reply) and "Cannot resolve" in text_of(reply),
            text_of(reply).strip().splitlines()[0][:110] if text_of(reply).strip() else "")

        reply = step(client,
            r'scala_mcp_tool lint2 = \<open>' + LINT_TARGET + r'\<close> '
            r'(description \<open>style lints for a loaded theory\<close>) '
            r'(params thy :: string \<open>theory to lint\<close>)')
        T.verdict("7b scala_mcp_tool declares", not is_err(reply),
            text_of(reply)[:120])

        client.request("tools/call",
            {"name": "tool_scope_set", "arguments": {"repl": REPL}})
        tools = client.request("tools/list").get("result", {}).get("tools", [])
        row = [x for x in tools if x.get("name") == "lint2"]
        # the resolved scala signature is surfaced in the description --
        # the only place the tool's real scala types are visible
        T.verdict("7c lint2 advertises its resolved scala signature",
            bool(row) and "Document$Snapshot" in row[0].get("description", ""),
            (row[0].get("description", "")[-90:] if row else "absent"))

        reply = client.request("tools/call",
            {"name": "lint2", "arguments": {"thy": "Lint_Dirty"}})
        out = text_of(reply)
        missing = [w for w in ["short_name", "tactic_proofs", "implicit_rule"]
                   if w not in out]
        T.verdict("7d tools/call lint2 returns findings -- via the COMMAND",
            not is_err(reply) and not missing,
            "missing: %s" % missing if missing else out.strip().splitlines()[0][:110])

    # --- 8. scala_mcp_fun (tier 1): wrap an ALREADY-registered Scala.Fun ---
    # No reflection and no scala code of ours: Scala.function dispatches by
    # name, and Pure's own (single, bytes) flags pick the entry point.

    # 8a a nullary declaration over doc_names (Fun_String => single).
    #    Pure's Fun_String runs Library.the_single, so zero params sends
    #    exactly one element, the Fun's own default "".
    reply = step(client,
        r'scala_mcp_fun catalog = \<open>doc_names\<close> '
        r'(description \<open>the isabelle doc catalog\<close>)')
    T.verdict("8a scala_mcp_fun declares over a registered Fun",
        not is_err(reply), text_of(reply)[:120])

    client.request("tools/call",
        {"name": "tool_scope_set", "arguments": {"repl": REPL}})
    reply = client.request("tools/call", {"name": "catalog", "arguments": {}})
    out = text_of(reply)
    cli = subprocess.run([ISABELLE, "doc"], capture_output=True, text=True).stdout
    first = out.strip().splitlines()[0].strip() if out.strip() else ""
    T.verdict("8b tools/call catalog matches `isabelle doc`",
        not is_err(reply) and len(out.splitlines()) > 3 and first in cli,
        "%d entries, first=%r" % (len(out.splitlines()), first))

    # 8c an unknown Fun fails at REGISTRATION -- ML holds the function
    #    table, so this costs nothing
    reply = step(client,
        r'scala_mcp_fun nofun = \<open>no_such_scala_function\<close> '
        r'(description \<open>x\<close>)')
    T.verdict("8c an unknown Scala.Fun fails at REGISTRATION", is_err(reply),
        text_of(reply).strip().splitlines()[0][:110] if text_of(reply).strip() else "")

    # 8d A2: a single-argument Fun cannot receive two params -- both the
    #    flag and the count are known at declaration
    reply = step(client,
        r'scala_mcp_fun twoargs = \<open>doc_names\<close> '
        r'(description \<open>x\<close>) '
        r'(params a :: string \<open>a\<close> b :: string \<open>b\<close>)')
    T.verdict("8d single Fun + 2 declared params is a registration error",
        is_err(reply) and "exactly one argument" in text_of(reply),
        text_of(reply).strip().splitlines()[0][:110] if text_of(reply).strip() else "")

    # 8e D3: (optional) params are forbidden -- positional filling cannot
    #    represent a hole, and the CLIENT decides to omit
    reply = step(client,
        r'scala_mcp_fun opt = \<open>doc_names\<close> '
        r'(description \<open>x\<close>) '
        r'(params a :: string (optional) \<open>a\<close>)')
    T.verdict("8e an (optional) param is a registration error (D3)",
        is_err(reply) and "optional" in text_of(reply),
        text_of(reply).strip().splitlines()[0][:110] if text_of(reply).strip() else "")

    # --- 9. D3: findings are MACHINE-readable, not just readable ---
    if have_linter:
        reply = client.request("tools/call",
            {"name": "lint2", "arguments": {"thy": "Lint_Dirty"}})
        out = text_of(reply).strip()
        ok, detail = False, ""
        try:
            parsed = json.loads(out)
            names = sorted({row.get("lint_name") for row in parsed})
            ok = (isinstance(parsed, list) and len(parsed) >= 4
                  and all("message" in row and "range" in row for row in parsed)
                  and "short_name" in names)
            detail = "%d rows, lints=%s" % (len(parsed), names)
        except Exception as exn:
            detail = "not JSON: %s -- %r" % (exn, out[:80])
        T.verdict("9 lint output parses as JSON with named fields", ok, detail)

    # --- 10. A3: MEASURE the blocking cost, do not assume it ---
    # Scala.function parks the prover thread in Synchronized.guarded_access
    # until Scala.result comes back. Wrap Pure's own `sleep` Fun, fire it,
    # and immediately fire a trivial ML tool: if the prover is held, the
    # fast call cannot answer until the slow one finishes.
    reply = step(client,
        r'scala_mcp_fun napper = \<open>sleep\<close> '
        r'(description \<open>sleep n seconds, scala-side\<close>) '
        r'(params secs :: string \<open>seconds\<close>)')
    T.verdict("10a scala_mcp_fun over Pure's sleep", not is_err(reply),
        text_of(reply)[:100])

    reply = step(client,
        r'mcp_tool quick = \<open>fn s => s\<close> (description \<open>echo\<close>)')
    T.verdict("10b a trivial ML tool to race it", not is_err(reply),
        text_of(reply)[:100])

    client.request("tools/call",
        {"name": "tool_scope_set", "arguments": {"repl": REPL}})

    NAP = 3.0
    t0 = time.monotonic()
    id_slow = client.send("tools/call",
        {"name": "napper", "arguments": {"secs": str(NAP)}})
    id_fast = client.send("tools/call",
        {"name": "quick", "arguments": {"input": "x"}})
    seen = {}
    while len(seen) < 2:
        msg = client.recv(timeout=120)
        if "id" in msg:
            seen[msg["id"]] = time.monotonic() - t0
    slow_t, fast_t = seen.get(id_slow), seen.get(id_fast)
    blocked = fast_t is not None and fast_t >= NAP * 0.8
    T.verdict(
        "10c MEASURED: the prover thread IS held for the whole scala call",
        blocked,
        "sleep(%.1fs) answered at %.2fs; the trivial ML tool answered at "
        "%.2fs -- %s" % (NAP, slow_t or -1, fast_t or -1,
                         "BLOCKED" if blocked else "not blocked"))

    return 1 if T.failures else 0


if __name__ == "__main__":
    sys.exit(main())
