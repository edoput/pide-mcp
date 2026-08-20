#!/usr/bin/env python3
"""The serve loop handles requests CONCURRENTLY.

It used to handle each line to COMPLETION before reading the next one, so
a slow tool delayed everything behind it:

    while (!finished) {
      in.readLine() match {
        case line => handler.handle_line(line).foreach(print_json)
      }
    }

Measured before the change: a 3s tool pushed `list_sessions` -- a SCALA
builtin that never reaches the prover -- out to the same 3s. The prover
was never the bottleneck; MCP.run_tool has forked on the ML side since
2026-07-28.

Cases:
  1  a slow ML tool does not delay a prover-free scala builtin
  2  it does not delay a second ML tool either, so the prover really is
     concurrent behind the loop
  3  replies may arrive OUT OF ORDER, which is what makes this legal:
     JSON-RPC matches on id, not on arrival
  4  the tool scope survives concurrent traffic (its two fields are one
     Synchronized cell, so no reader sees a new designation carrying the
     old designation's bundles)

Usage:
  ISABELLE=/path/to/isabelle python3 mcp/test/repro_concurrent_serve.py

Exit code 0 iff all assertions pass.
"""
import json
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
os.environ.setdefault("MCP_TEST_TIMEOUT", "900")
import test_mcp as T

ISABELLE = os.environ.get("ISABELLE") or sys.exit("set ISABELLE")
REPL = "Conc"
NAP = 3.0


def text_of(reply):
    content = reply.get("result", {}).get("content", [])
    return content[0].get("text", "") if content else json.dumps(reply)[:300]


def is_err(reply):
    return bool(reply.get("result", {}).get("isError")) or "error" in reply


def step(client, isar_text):
    return client.request("tools/call",
        {"name": "repl_step", "arguments": {"repl": REPL, "isar_text": isar_text}})


def race(client, first, second):
    """Fire both without waiting, return (t_first, t_second, order)."""
    t0 = time.monotonic()
    id_a = client.send("tools/call", first)
    id_b = client.send("tools/call", second)
    seen, order = {}, []
    while len(seen) < 2:
        msg = client.recv(timeout=120)
        if "id" in msg:
            seen[msg["id"]] = time.monotonic() - t0
            order.append(msg["id"])
    return seen[id_a], seen[id_b], (order[0] == id_b)


def main():
    client = T.Client([ISABELLE, "mcp_server", "-s", "MCP-HOL", "-T", "MCP_Repl"])
    client.request("initialize",
        {"protocolVersion": "2024-11-05", "capabilities": {},
         "clientInfo": {"name": "repro_concurrent_serve", "version": "0"}}, timeout=300)
    client.send("notifications/initialized", notification=True)
    T.wait_for_ready(client)

    reply = client.request("tools/call",
        {"name": "repl_init", "arguments": {"repl": REPL, "theories": ["MCP-HOL.MCP_Repl"]}})
    T.verdict("repl_init", not is_err(reply), text_of(reply)[:120])
    if is_err(reply):
        return 1

    # a genuinely slow, prover-backed tool
    reply = step(client,
        r'mcp_tool napper = \<open>fn s => '
        r'(OS.Process.sleep (Time.fromSeconds 3); "slept " ^ s)\<close> '
        r'(description \<open>sleep 3s in the prover\<close>)')
    T.verdict("a slow ML tool to block with", not is_err(reply), text_of(reply)[:120])

    reply = step(client,
        r'mcp_tool quick = \<open>fn s => s\<close> (description \<open>echo\<close>)')
    T.verdict("a fast ML tool to race it", not is_err(reply), text_of(reply)[:120])

    client.request("tools/call", {"name": "tool_scope_set", "arguments": {"repl": REPL}})

    # 1. slow ML tool vs a SCALA builtin (never reaches the prover)
    slow_t, fast_t, reordered = race(client,
        {"name": "napper", "arguments": {"input": "x"}},
        {"name": "list_sessions", "arguments": {}})
    T.verdict("1 a slow tool no longer delays a prover-free builtin",
        fast_t < NAP * 0.5,
        "napper %.2fs, list_sessions %.2fs (was ~%.1fs before)" % (slow_t, fast_t, NAP))

    # 3. and the replies came back out of order, which is the point
    T.verdict("3 replies may arrive OUT OF ORDER (id-matched, so legal)",
        reordered, "the fast reply overtook the slow one" if reordered
        else "arrival order unchanged")

    # 2. slow ML tool vs another ML tool -- the prover is concurrent too
    slow_t, fast_t, _ = race(client,
        {"name": "napper", "arguments": {"input": "y"}},
        {"name": "quick", "arguments": {"input": "z"}})
    T.verdict("2 nor does it delay a second ML tool",
        fast_t < NAP * 0.5,
        "napper %.2fs, quick %.2fs" % (slow_t, fast_t))

    # 4. the scope survives concurrent traffic
    reply = client.request("tools/call", {"name": "tool_scope_show", "arguments": {}})
    out = text_of(reply)
    T.verdict("4 the tool scope is intact after concurrent traffic",
        not is_err(reply) and "BROKEN" not in out and REPL in out,
        out.strip().splitlines()[0][:120] if out.strip() else "")

    return 1 if T.failures else 0


if __name__ == "__main__":
    sys.exit(main())
