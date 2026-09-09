#!/usr/bin/env python3
"""Regression: an MCP tool body can call the Isabelle Scala bridge.

The shared test launcher accepts ``ISABELLE`` as a shell-quoted command
prefix, so this script also works with an adapter such as host Flatpak.
"""

import json
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
os.environ.setdefault("MCP_TEST_TIMEOUT", "900")
import test_mcp as T

ISABELLE = T.ISABELLE
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
    client = T.Client(ISABELLE + ["mcp_server", "-s", "MCP-HOL", "-T", "MCP_Repl"])
    client.request("initialize",
        {"protocolVersion": "2024-11-05", "capabilities": {},
         "clientInfo": {"name": "repro_scala_bridge", "version": "0"}}, timeout=300)
    client.send("notifications/initialized", notification=True)
    T.wait_for_ready(client)

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

    reply = client.request("tools/call", {"name": "tool_scope_set", "arguments": {"repl": REPL}})
    T.verdict("E tool_scope_set", not is_err(reply), text_of(reply)[:120])

    reply = client.request("tools/call", {"name": "ctl_tool", "arguments": {"input": "hi"}})
    T.verdict("F control tool returns HI",
        not is_err(reply) and text_of(reply).strip() == "HI", repr(text_of(reply)[:120]))

    reply = client.request("tools/call", {"name": "doc_bridge", "arguments": {"input": ""}})
    names = text_of(reply).splitlines()
    cli = subprocess.run([*ISABELLE, "doc"], capture_output=True, text=True).stdout
    T.verdict("G tool body reaches Scala (doc catalog crosses back)",
        not is_err(reply) and len(names) > 3 and names[0].strip() in cli,
        "%d entries, first=%r" % (len(names), names[0].strip() if names else None))
    return 1 if T.failures else 0


if __name__ == "__main__":
    sys.exit(main())
