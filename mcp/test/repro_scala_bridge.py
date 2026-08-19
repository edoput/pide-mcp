#!/usr/bin/env python3
"""Does ML reach Scala (Scala.function) from inside an MCP tool body?

Settles the reentrancy question: an ML tool is invoked *through* a
protocol command, and Scala.function replies through another. Verified
working 2026-08-19 (see LINTER_FINDINGS.md section 6.4).

Isolates three things:
  B  Scala.function from a plain ML command in repl_step  -- bridge alive?
  F  a control ML tool, no Scala call                     -- harness sane?
  G  an ML tool whose body calls Scala                    -- THE question

G uses `doc_names` (Pure/Tools/doc.scala:130), which computes the doc
catalog Scala-side from the filesystem, so its result is something ML
could not fabricate -- unlike `echo`, which is a passthrough. No new
Scala code and no rebuild are needed: both functions are registered in
Pure (scala.scala:355).

Usage:
  ISABELLE=/path/to/isabelle python3 mcp/test/repro_scala_bridge.py

Under the flatpak, ISABELLE must be a wrapper script:
  #!/usr/bin/env bash
  exec flatpak run --command=isabelle de.tum.in.isabelle.Isabelle "$@"

Exit code 0 iff the bridge works.
"""
import json
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
os.environ.setdefault("MCP_TEST_TIMEOUT", "900")
import test_mcp as T

ISABELLE = os.environ.get("ISABELLE") or sys.exit("set ISABELLE")
REPL = "ScalaBridge"


def text_of(reply):
    content = reply.get("result", {}).get("content", [])
    return content[0].get("text", "") if content else json.dumps(reply)[:300]


def is_err(reply):
    return bool(reply.get("result", {}).get("isError")) or "error" in reply


def step(client, isar_text):
    return client.request("tools/call",
        {"name": "repl_step", "arguments": {"repl": REPL, "isar_text": isar_text}})


def main():
    client = T.Client([ISABELLE, "mcp_server", "-s", "MCP-HOL", "-T", "MCP_Repl"])
    client.request("initialize",
        {"protocolVersion": "2024-11-05", "capabilities": {},
         "clientInfo": {"name": "repro_scala_bridge", "version": "0"}}, timeout=300)
    client.send("notifications/initialized", notification=True)
    T.wait_for_ready(client)

    # the repl must be rooted in a theory that imports the tool machinery,
    # or `mcp_tool` does not parse -- see .claude/skills/mcp-tool-theories
    reply = client.request("tools/call",
        {"name": "repl_init", "arguments": {"repl": REPL, "theories": ["MCP-HOL.MCP_Repl"]}})
    T.verdict("repl_init", not is_err(reply), text_of(reply)[:120])
    if is_err(reply):
        return 1

    reply = step(client, r'ML \<open>val echoed = Scala.function1 "echo" "hi-from-ml"\<close>')
    T.verdict("B plain ML Scala.function1",
        not is_err(reply) and "hi-from-ml" in text_of(reply), text_of(reply)[:160])

    reply = step(client,
        r'mcp_tool ctl_tool = \<open>String.map Char.toUpper\<close> (description \<open>control\<close>)')
    T.verdict("C register control tool", not is_err(reply), text_of(reply)[:120])

    reply = step(client,
        r'mcp_tool doc_bridge = \<open>fn _ => Scala.function1 "doc_names" ""\<close>'
        r' (description \<open>scala doc catalog\<close>)')
    T.verdict("D register bridge tool", not is_err(reply), text_of(reply)[:120])

    reply = client.request("tools/call",
        {"name": "tool_scope_set", "arguments": {"repl": REPL}})
    T.verdict("E tool_scope_set", not is_err(reply), text_of(reply)[:120])

    reply = client.request("tools/call", {"name": "ctl_tool", "arguments": {"input": "hi"}})
    T.verdict("F control tool returns HI",
        not is_err(reply) and text_of(reply).strip() == "HI", repr(text_of(reply)[:120]))

    reply = client.request("tools/call", {"name": "doc_bridge", "arguments": {"input": ""}})
    names = text_of(reply).splitlines()
    # ground truth from the CLI, independent of the prover
    cli = subprocess.run([ISABELLE, "doc"], capture_output=True, text=True).stdout
    T.verdict("G tool body reaches Scala (doc catalog crosses back)",
        not is_err(reply) and len(names) > 3 and names[0].strip() in cli,
        "%d entries, first=%r" % (len(names), names[0].strip() if names else None))

    return 1 if T.failures else 0


if __name__ == "__main__":
    sys.exit(main())
