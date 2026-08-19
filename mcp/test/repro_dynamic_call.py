#!/usr/bin/env python3
"""MCP.dynamic_call reaches a third-party component and lints OUR snapshot.

This is plans/scala_mcp_tool's load-bearing claim (A4/T1), proven end to
end: ML names a target as a STRING, our one reflective Scala function
resolves it on the component classpath, fills a Document.Snapshot the ML
side could never express, invokes, and hands findings back.

Nothing in mcp.jar links against the linter, and the linter knows nothing
about us -- the only thing connecting them is the target string below.

Cases:
  1  check_target  resolves isabelle.linter.Linter.lint_snapshot and reports
                   its real parameter names and types (Q4: names survive
                   into the bytecode)
  2  load_theory   a deliberately lint-dirty fixture, so the session holds a
                   live snapshot for it
  3  own-jar       Self_Test.snapshot_info proves the AMBIENT fill handed
                   over a real snapshot, not an empty one
  4  THE claim     lint_snapshot over that snapshot returns the findings the
                   fixture was built to trigger

Requires the linter component:
    isabelle components -u <linter checkout>/linter_base
Skips cases 1 and 4 if it is absent, so the suite stays green without it.

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

    return 1 if T.failures else 0


if __name__ == "__main__":
    sys.exit(main())
