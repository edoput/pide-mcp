#!/usr/bin/env python3
"""MVP acceptance test for `isabelle mcp_server` (see spec-mvp).

Spawns the server, speaks MCP (newline-delimited JSON-RPC 2.0) over
stdio, and checks that a tool registered in Isabelle/ML can be listed
and executed in the prover.

Environment:
  ISABELLE            path to the isabelle executable
                      (default: the bundled Isabelle2025-2_linux distribution)
  MCP_TEST_TIMEOUT    per-reply timeout in seconds (default: 600; the first
                      reply may take minutes on a cold build)

Exit code 0 iff all assertions pass; prints one verdict line per case.
"""

import json
import os
import subprocess
import sys
import threading
import queue

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DEFAULT_ISABELLE = os.path.join(
    PROJECT_ROOT, "Isabelle2025-2_linux", "Isabelle2025-2", "bin", "isabelle")
ISABELLE = os.environ.get("ISABELLE", DEFAULT_ISABELLE)
TIMEOUT = float(os.environ.get("MCP_TEST_TIMEOUT", "600"))

failures = 0


def verdict(name, ok, detail=""):
    global failures
    if not ok:
        failures += 1
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, " -- " + detail if detail else ""))


class Client:
    def __init__(self, argv):
        self.proc = subprocess.Popen(
            argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=sys.stderr.fileno(), text=True, bufsize=1)
        self.replies = queue.Queue()
        self.next_id = 0
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    def _read(self):
        for line in self.proc.stdout:
            line = line.strip()
            if line:
                self.replies.put(line)

    def send(self, method, params=None, notification=False):
        msg = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            msg["params"] = params
        if not notification:
            self.next_id += 1
            msg["id"] = self.next_id
        self.proc.stdin.write(json.dumps(msg) + "\n")
        self.proc.stdin.flush()
        return None if notification else msg["id"]

    def send_raw(self, line):
        self.proc.stdin.write(line + "\n")
        self.proc.stdin.flush()

    def recv(self, timeout=TIMEOUT):
        return json.loads(self.replies.get(timeout=timeout))

    def request(self, method, params=None, timeout=TIMEOUT):
        rpc_id = self.send(method, params)
        reply = self.recv(timeout=timeout)
        assert reply.get("id") == rpc_id, "reply id %r != request id %r" % (reply.get("id"), rpc_id)
        return reply


def main():
    if not os.path.exists(ISABELLE):
        print("FAIL setup -- isabelle executable not found: %s" % ISABELLE)
        return 1

    client = Client([ISABELLE, "mcp_server"])
    try:
        # initialize handshake (generous timeout: cold build + session start)
        reply = client.request("initialize", {
            "protocolVersion": "2025-03-26",
            "capabilities": {},
            "clientInfo": {"name": "test_mcp", "version": "0"},
        })
        result = reply.get("result", {})
        verdict("initialize", "protocolVersion" in result and "serverInfo" in result
                and "tools" in result.get("capabilities", {}),
                json.dumps(reply) if "result" not in reply else "")
        client.send("notifications/initialized", notification=True)

        # ping
        reply = client.request("ping")
        verdict("ping", reply.get("result") == {})

        # tools/list: the ML-registered demo tool must be present
        reply = client.request("tools/list")
        tools = reply.get("result", {}).get("tools", [])
        shout = [t for t in tools if t.get("name") == "shout"]
        verdict("tools/list has shout", bool(shout), json.dumps(tools))
        verdict("shout has input schema",
                bool(shout) and shout[0].get("inputSchema", {}).get("type") == "object")

        # tools/call: executed by the prover in Isabelle/ML
        reply = client.request("tools/call",
                               {"name": "shout", "arguments": {"input": "isabelle"}})
        content = reply.get("result", {}).get("content", [])
        verdict("tools/call shout",
                content and content[0].get("type") == "text"
                and content[0].get("text") == "ISABELLE"
                and not reply["result"].get("isError", False),
                json.dumps(reply))

        # negative: missing arguments.input
        reply = client.request("tools/call", {"name": "shout", "arguments": {}})
        verdict("missing input is error",
                reply.get("result", {}).get("isError", False) is True, json.dumps(reply))

        # negative: unknown tool
        reply = client.request("tools/call",
                               {"name": "no_such_tool", "arguments": {"input": "x"}})
        is_error = (reply.get("result", {}).get("isError", False) is True
                    or "error" in reply)
        verdict("unknown tool is error", is_error, json.dumps(reply))

        # negative: malformed json line
        client.send_raw("this is not json")
        reply = client.recv()
        verdict("malformed json -> -32700", reply.get("error", {}).get("code") == -32700,
                json.dumps(reply))

        # unknown method with id -> json-rpc error -32601
        reply = client.request("no/such_method")
        verdict("unknown method -> -32601", reply.get("error", {}).get("code") == -32601,
                json.dumps(reply))

        # clean shutdown on stdin close
        client.proc.stdin.close()
        try:
            rc = client.proc.wait(timeout=30)
            verdict("exit on stdin close", rc == 0, "rc=%d" % rc)
        except subprocess.TimeoutExpired:
            verdict("exit on stdin close", False, "still running after 30s")
    finally:
        if client.proc.poll() is None:
            client.proc.kill()

    print("%d failure(s)" % failures)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
