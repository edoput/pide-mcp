#!/usr/bin/env python3
"""A pending ML tool must not delay independent Scala or ML tool calls."""
import time

from mcp.test import test_mcp as T
from mcp.test.repro_connection_kernel import initialize, wait_for_capture_tools


def race(client, slow, fast):
    started = time.monotonic()
    slow_id = client.send("tools/call", slow)
    fast_id = client.send("tools/call", fast)
    received = {}
    order = []
    while len(received) < 2:
        reply = client.recv(timeout=120)
        if reply.get("id") in (slow_id, fast_id):
            assert not T.is_err(reply), reply
            received[reply["id"]] = time.monotonic() - started
            order.append(reply["id"])
    return received[slow_id], received[fast_id], order[0] == fast_id


def main():
    with T.Client(T.ISABELLE + ["mcp_server", "-s", "MCP-Tools-Tests", "-T", "MCP_Tools_Tests"]) as client:
        initialize(client)
        names = wait_for_capture_tools(client)
        for label, fast in [
            ("Scala builtin", {"name": "list_sessions", "arguments": {}}),
            ("ML tool", {"name": names["ok"], "arguments": {"x": "quick"}}),
        ]:
            slow_time, fast_time, reordered = race(client,
                {"name": names["slow"], "arguments": {}}, fast)
            T.verdict(f"pending ML call permits independent {label}",
                reordered and fast_time < 1.5 and slow_time >= 1.5,
                f"slow={slow_time:.2f}s fast={fast_time:.2f}s")
        reply = T.call(client, names["ok"], x="after-race")
        T.verdict("root catalogue remains usable after concurrent calls",
                  not T.is_err(reply) and "got:after-race" in T.text_of(reply), reply)
    return int(bool(T.failures))


if __name__ == "__main__":
    raise SystemExit(main())
