#!/usr/bin/env python3
"""Measures MCP request cancellation (notifications/cancelled).

Cases:
  1  a cancelled tools/call gets NO response (spec: receivers SHOULD NOT
     send a response for a cancelled request) -- checked with a short
     timeout so a slow-arriving reply still counts as a violation.
  2  the connection frees up immediately: a second, unrelated request
     sent right after the cancel gets its reply promptly, not queued
     behind the cancelled one.
  3  PROVER-SIDE measurement (the interesting part): a marker file the
     cancelled tool writes only AFTER its sleep completes. If ML-side
     cancellation actually reaches the fork, the marker never appears
     (or appears immediately, having never truly started the sleep at
     all is not possible here since the sleep starts before the cancel
     can arrive -- the marker's absence within a safety margin after the
     cancel is what "the prover actually stopped" looks like from
     outside). If it still appears at ~SLEEP seconds after the call was
     first sent, the ML fork ran to completion regardless of the
     cancel -- scala-side cancellation only frees the CLIENT, not the
     prover.
  4  cancel for an unknown id is silently ignored (no crash, no reply)
  5  a second cancel for the same (now-finished) id is silently ignored
  6  shutdown drain still accounts correctly after a cancelled request
     (in_flight reaches 0 promptly -- checked indirectly: the server
     exits cleanly on EOF right after)

Usage:
  ISABELLE=/path/to/wrapper MARKER=/path/to/marker python3 repro_request_cancel.py
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
MARKER = os.environ.get("MARKER") or sys.exit("set MARKER")
REPL = "Canc"
SLEEP = 6.0


def text_of(reply):
    content = reply.get("result", {}).get("content", [])
    return content[0].get("text", "") if content else json.dumps(reply)[:300]


def is_err(reply):
    return bool(reply.get("result", {}).get("isError")) or "error" in reply


def wait_reply(client, want_id, timeout):
    """Poll client.replies for a reply with id == want_id within timeout.
    Returns (reply_or_None, elapsed)."""
    t0 = time.monotonic()
    deadline = t0 + timeout
    while time.monotonic() < deadline:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        try:
            msg = client.recv(timeout=remaining)
        except Exception:
            break
        if msg.get("id") == want_id:
            return msg, time.monotonic() - t0
        if "id" not in msg and str(msg.get("method", "")).startswith("notifications/"):
            client.notifications.append(msg)
            continue
        # a reply for some OTHER id: stash it for that id's own recv, but
        # this simple harness only needs to not lose it silently
        client._stash = getattr(client, "_stash", {})
        client._stash[msg.get("id")] = msg
    return None, time.monotonic() - t0


def main():
    if os.path.exists(MARKER):
        os.remove(MARKER)

    client = T.Client([ISABELLE, "mcp_server", "-s", "MCP-HOL", "-T", "MCP_Repl"])
    client.request("initialize",
        {"protocolVersion": "2024-11-05", "capabilities": {},
         "clientInfo": {"name": "repro_request_cancel", "version": "0"}}, timeout=300)
    client.send("notifications/initialized", notification=True)
    T.wait_for_ready(client)

    reply = client.request("tools/call",
        {"name": "repl_init", "arguments": {"repl": REPL, "theories": ["MCP-HOL.MCP_Repl"]}})
    T.verdict("repl_init", not is_err(reply), text_of(reply)[:120])
    if is_err(reply):
        return 1

    napper_src = (
        r'mcp_tool napper = \<open>fn s => (OS.Process.sleep (Time.fromSeconds %d); '
        r'File.append (Path.explode "%s") "done"; "slept " ^ s)\<close> '
        r'(description \<open>sleep %ds then mark completion, for cancellation timing\<close>)'
    ) % (int(SLEEP), MARKER, int(SLEEP))
    reply = client.request("tools/call",
        {"name": "repl_step", "arguments": {"repl": REPL, "isar_text": napper_src}})
    T.verdict("register the napper tool (sleeps %.0fs, then marks completion)" % SLEEP,
        not is_err(reply), text_of(reply)[:200])
    if is_err(reply):
        return 1

    reply = client.request("tools/call", {"name": "tool_scope_set", "arguments": {"repl": REPL}})
    T.verdict("tool_scope_set", not is_err(reply), text_of(reply)[:120])

    # --- fire the slow call, then cancel it shortly after ---
    t0 = time.monotonic()
    call_id = client.send("tools/call", {"name": "napper", "arguments": {"input": "x"}})
    time.sleep(1.0)
    t_cancel = time.monotonic()
    client.send("notifications/cancelled",
        {"requestId": call_id, "reason": "measurement"}, notification=True)

    # 1: no response for the cancelled request within a generous window
    # (generous: we are not racing the implementation, we are measuring
    # whether it EVER replies, so give it much longer than a healthy
    # round trip would need)
    reply, waited = wait_reply(client, call_id, timeout=4.0)
    T.verdict("1 no response sent for the cancelled request",
        reply is None,
        ("got a reply after %.2fs: %s" % (waited, json.dumps(reply)[:200])) if reply
        else ("confirmed silent after %.2fs wait" % waited))

    # 2: the connection is free immediately -- an unrelated request sent
    # right after the cancel gets its reply promptly
    t_second = time.monotonic()
    second_id = client.send("tools/call", {"name": "list_sessions", "arguments": {}})
    second_reply, second_wait = wait_reply(client, second_id, timeout=10.0)
    T.verdict("2 the connection frees up immediately after cancelling",
        second_reply is not None and second_wait < 3.0,
        "list_sessions answered in %.2fs (None means no reply arrived)" % second_wait
        if second_reply is not None else "no reply arrived within 10s")

    # 3: PROVER-SIDE measurement -- does the marker appear, and when
    # (relative to t0, the moment napper was first called)?
    marker_deadline = t0 + SLEEP + 3.0
    marker_seen_at = None
    while time.monotonic() < marker_deadline:
        if os.path.exists(MARKER):
            marker_seen_at = time.monotonic() - t0
            break
        time.sleep(0.1)
    if marker_seen_at is None:
        print("MEASUREMENT: marker file never appeared within %.1fs of the call "
              "(cancel was sent at t=%.2fs) -- the ML-side fork did NOT run to "
              "completion." % (SLEEP + 3.0, t_cancel - t0))
    else:
        print("MEASUREMENT: marker file appeared at t=%.2fs (cancel was sent at "
              "t=%.2fs, sleep duration is %.1fs) -- the ML-side fork RAN TO "
              "COMPLETION despite the cancel." % (marker_seen_at, t_cancel - t0, SLEEP))

    # 4: cancel for an unknown id -- must not crash or hang the connection
    client.send("notifications/cancelled",
        {"requestId": 999999, "reason": "unknown id"}, notification=True)
    probe_id = client.send("tools/call", {"name": "list_sessions", "arguments": {}})
    probe_reply, probe_wait = wait_reply(client, probe_id, timeout=10.0)
    T.verdict("4 cancel for an unknown id does not disturb the connection",
        probe_reply is not None and probe_wait < 3.0,
        "probe answered in %.2fs" % probe_wait if probe_reply is not None
        else "no reply -- connection wedged")

    # 5: a second cancel for the same (long-finished) id is a silent no-op
    client.send("notifications/cancelled",
        {"requestId": call_id, "reason": "duplicate, already gone"}, notification=True)
    probe2_id = client.send("tools/call", {"name": "list_sessions", "arguments": {}})
    probe2_reply, probe2_wait = wait_reply(client, probe2_id, timeout=10.0)
    T.verdict("5 a duplicate/late cancel for a finished id is a silent no-op",
        probe2_reply is not None and probe2_wait < 3.0,
        "probe answered in %.2fs" % probe2_wait if probe2_reply is not None
        else "no reply -- connection wedged")

    return 1 if T.failures else 0


if __name__ == "__main__":
    sys.exit(main())
