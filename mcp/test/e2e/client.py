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
        self.pending_replies: dict[str, dict[str, Any]] = {}
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

    def send_json(self, message: Any) -> None:
        """Send an arbitrary JSON-RPC envelope, including a batch."""
        self.send_raw(json.dumps(message))

    def recv_json(self, timeout: float = DEFAULT_TIMEOUT) -> Any:
        return json.loads(self.replies.get(timeout=timeout))

    def recv(self, timeout: float = DEFAULT_TIMEOUT) -> dict[str, Any]:
        value = self.recv_json(timeout)
        if not isinstance(value, dict):
            raise AssertionError(f"server emitted non-object JSON: {value!r}")
        return value

    def await_reply(
        self, rpc_id: str, timeout: float = DEFAULT_TIMEOUT
    ) -> dict[str, Any]:
        pending = self.pending_replies.pop(rpc_id, None)
        if pending is not None:
            return pending
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
            reply_id = reply.get("id")
            if reply_id == rpc_id:
                return reply
            if not isinstance(reply_id, str):
                raise AssertionError(
                    f"uncorrelated server message while waiting for {rpc_id!r}: {reply!r}"
                )
            if reply_id in self.pending_replies:
                raise AssertionError(f"duplicate reply id {reply_id!r}")
            self.pending_replies[reply_id] = reply

    def request(
        self, method: str, params: Any = None, timeout: float = DEFAULT_TIMEOUT
    ) -> dict[str, Any]:
        rpc_id = self.send(method, params)
        assert rpc_id is not None
        return self.await_reply(rpc_id, timeout)

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
        elif isinstance(message.get("id"), str):
            reply_id = message["id"]
            if reply_id in self.pending_replies:
                raise AssertionError(f"duplicate reply id {reply_id!r}")
            self.pending_replies[reply_id] = message
        return False

    def assert_no_reply(self, rpc_id: str, timeout: float = 0.5) -> None:
        if rpc_id in self.pending_replies:
            raise AssertionError(
                f"unexpected reply for cancelled request {rpc_id!r}: "
                f"{self.pending_replies[rpc_id]!r}"
            )
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return
            try:
                message = self.recv(timeout=remaining)
            except queue.Empty:
                return
            if "id" not in message:
                self.notifications.append(message)
                continue
            reply_id = message.get("id")
            if reply_id == rpc_id:
                raise AssertionError(f"unexpected reply for cancelled request {rpc_id!r}")
            if not isinstance(reply_id, str):
                raise AssertionError(f"uncorrelated server message: {message!r}")
            if reply_id in self.pending_replies:
                raise AssertionError(f"duplicate reply id {reply_id!r}")
            self.pending_replies[reply_id] = message

    def drain_json(self) -> list[Any]:
        """Drain complete envelopes after process exit, preserving wire order."""
        result: list[Any] = []
        while True:
            try:
                result.append(json.loads(self.replies.get_nowait()))
            except queue.Empty:
                return result

    def close(self, timeout: float = 30) -> int:
        if self.proc.stdin is not None and not self.proc.stdin.closed:
            self.proc.stdin.close()
        try:
            result = self.proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            self.proc.terminate()
            try:
                result = self.proc.wait(timeout=2)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                result = self.proc.wait()
        self.reader.join(timeout=2)
        return result

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
        if " failed to start:" in text:
            return reply
        if " is not ready:" not in text:
            return reply
        if time.monotonic() >= deadline:
            return reply
        time.sleep(0.5)
