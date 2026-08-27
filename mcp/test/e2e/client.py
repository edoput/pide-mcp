"""Shared newline-delimited JSON-RPC process client for e2e cases.

Project-owned clients use string request IDs. The server's request-ID boundary
is string-only, so numeric JSON-RPC IDs are rejected before admission.
"""

from __future__ import annotations

import json
import os
import queue
import subprocess
import sys
import threading
import time
from typing import Any


DEFAULT_TIMEOUT = float(os.environ.get("MCP_TEST_TIMEOUT", "600"))


class Client:
    def __init__(self, argv: list[str], *, stderr: int | None = None):
        self.proc = subprocess.Popen(
            argv,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=sys.stderr.fileno() if stderr is None else stderr,
            text=True,
            bufsize=1,
        )
        self.replies: queue.Queue[str] = queue.Queue()
        self.notifications: list[dict[str, Any]] = []
        self.next_id = 0
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    def _read(self) -> None:
        assert self.proc.stdout is not None
        for line in self.proc.stdout:
            line = line.strip()
            if line:
                self.replies.put(line)

    def send(
        self, method: str, params: Any = None, notification: bool = False
    ) -> str | None:
        message: dict[str, Any] = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            message["params"] = params
        if not notification:
            self.next_id += 1
            message["id"] = f"e2e-{self.next_id}"
        if self.proc.stdin is None:
            raise RuntimeError("client stdin is unavailable")
        self.proc.stdin.write(json.dumps(message) + "\n")
        self.proc.stdin.flush()
        return None if notification else message["id"]

    def send_raw(self, line: str) -> None:
        if self.proc.stdin is None:
            raise RuntimeError("client stdin is unavailable")
        self.proc.stdin.write(line + "\n")
        self.proc.stdin.flush()

    def recv(self, timeout: float = DEFAULT_TIMEOUT) -> dict[str, Any]:
        value = json.loads(self.replies.get(timeout=timeout))
        if not isinstance(value, dict):
            raise AssertionError(f"server emitted non-object JSON: {value!r}")
        return value

    def request(
        self, method: str, params: Any = None, timeout: float = DEFAULT_TIMEOUT
    ) -> dict[str, Any]:
        rpc_id = self.send(method, params)
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise queue.Empty
            reply = self.recv(timeout=remaining)
            if "id" not in reply and str(reply.get("method", "")).startswith(
                "notifications/"
            ):
                self.notifications.append(reply)
                continue
            if reply.get("id") != rpc_id:
                raise AssertionError(
                    f"reply id {reply.get('id')!r} != request id {rpc_id!r}"
                )
            return reply

    def await_notification(self, method: str, timeout: float = 30) -> bool:
        if any(value.get("method") == method for value in self.notifications):
            return True
        try:
            message = self.recv(timeout=timeout)
        except queue.Empty:
            return False
        if "id" not in message and message.get("method") == method:
            return True
        if "id" not in message:
            self.notifications.append(message)
        return False

    def close(self, timeout: float = 30) -> int:
        if self.proc.stdin is not None and not self.proc.stdin.closed:
            self.proc.stdin.close()
        try:
            return self.proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            self.proc.terminate()
            try:
                return self.proc.wait(timeout=2)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                return self.proc.wait()

    def __enter__(self) -> "Client":
        return self

    def __exit__(self, exc_type, exc, traceback) -> None:
        self.close()


def wait_for_shout(
    client: Client, timeout: float = DEFAULT_TIMEOUT
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """Poll tools/list until the ML-registered shout tool appears."""
    deadline = time.monotonic() + timeout
    reply: dict[str, Any] = {}
    tools: list[dict[str, Any]] = []
    while True:
        reply = client.request("tools/list", timeout=10)
        tools = reply.get("result", {}).get("tools", [])
        if any(tool.get("name") == "shout" for tool in tools):
            return reply, tools
        if time.monotonic() >= deadline:
            return reply, tools
        time.sleep(0.5)


def wait_for_ready(
    client: Client,
    probe_name: str = "repl_list",
    probe_args: dict[str, Any] | None = None,
    timeout: float = DEFAULT_TIMEOUT,
) -> dict[str, Any]:
    """Poll a harmless prover-backed builtin until the backend is ready."""
    deadline = time.monotonic() + timeout
    reply: dict[str, Any] = {}
    while True:
        reply = client.request(
            "tools/call",
            {"name": probe_name, "arguments": probe_args or {}},
            timeout=10,
        )
        content = reply.get("result", {}).get("content", [])
        text = content[0].get("text", "") if content else ""
        if not (" is not ready:" in text or " failed to start:" in text):
            return reply
        if time.monotonic() >= deadline:
            return reply
        time.sleep(0.5)
