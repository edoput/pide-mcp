#!/usr/bin/env python3
"""Real-stdio acceptance cases for the replaceable connection kernel.

Each function starts the shipped ``isabelle mcp_server`` process and speaks
only its public newline-delimited JSON-RPC protocol.  The registered e2e runner
executes each group in an isolated worker; this file remains a useful focused
diagnostic entrypoint.
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import queue
import shutil
import tempfile
import time
from typing import Any, Callable
import uuid

from mcp.test.e2e.client import Client, wait_for_ready
from tools.isabelle_launcher import resolve_launcher


PROJECT_ROOT = Path(__file__).resolve().parents[2]
ISABELLE = list(resolve_launcher().argv)
PROTOCOL = "2025-03-26"


def server(*arguments: str, stderr: int | None = None) -> Client:
    return Client(ISABELLE + ["mcp_server", *arguments], stderr=stderr)


def initialize(client: Client) -> dict[str, Any]:
    reply = client.request(
        "initialize",
        {
            "protocolVersion": PROTOCOL,
            "capabilities": {},
            "clientInfo": {"name": "connection-kernel-e2e", "version": "1"},
        },
        timeout=60,
    )
    assert reply.get("result", {}).get("protocolVersion") == PROTOCOL, reply
    client.send("notifications/initialized", notification=True)
    return reply


def tool_text(reply: dict[str, Any]) -> str:
    content = reply.get("result", {}).get("content", [])
    return content[0].get("text", "") if content else ""


def tool_error(reply: dict[str, Any]) -> bool:
    return bool(reply.get("result", {}).get("isError", False))


def error_code(reply: dict[str, Any]) -> int | None:
    return reply.get("error", {}).get("code")


def wait_for_capture_tools(client: Client) -> dict[str, str]:
    deadline = time.monotonic() + 600
    while True:
        reply = client.request("tools/list", timeout=10)
        rows = reply.get("result", {}).get("tools", [])
        names = [row.get("name") for row in rows if isinstance(row.get("name"), str)]

        def exposed(base: str) -> str | None:
            return next(
                (name for name in names if name == base or name.endswith("__" + base)),
                None,
            )

        capture_ok = exposed("capture_ok")
        capture_slow = exposed("capture_slow")
        if capture_ok is not None and capture_slow is not None:
            return {"ok": capture_ok, "slow": capture_slow}
        if time.monotonic() >= deadline:
            raise AssertionError(f"capture tools did not appear: {names!r}")
        time.sleep(0.1)


def run_lifecycle_readiness() -> int:
    """Fresh/Awaiting/Ready lifecycle plus independent prover readiness."""
    token = f"{os.getpid()}_{uuid.uuid4().hex[:8]}"
    fixture = Path(tempfile.mkdtemp(prefix="mcp_readiness_e2e_", dir=PROJECT_ROOT))
    theory = f"MCP_Readiness_E2E_{token}"
    session = f"MCP-Readiness-E2E-{token}"
    (fixture / "ROOT").write_text(
        f'session "{session}" = "MCP-Tools" +\n'
        f"  theories\n    {theory}\n",
        encoding="utf-8",
    )
    (fixture / f"{theory}.thy").write_text(
        f"theory {theory}\n"
        '  imports "MCP-Tools.MCP_Tools"\n'
        "begin\n"
        "ML \\<open>OS.Process.sleep (seconds 5.0)\\<close>\n"
        "end\n",
        encoding="utf-8",
    )

    client = server("-d", str(fixture), "-s", session, "-T", theory)
    try:
        # Fresh rejects every request except initialize.
        assert error_code(client.request("ping", timeout=60)) == -32600
        initialize_reply = client.request(
            "initialize",
            {
                "protocolVersion": PROTOCOL,
                "capabilities": {},
                "clientInfo": {"name": "lifecycle-e2e", "version": "1"},
            },
            timeout=60,
        )
        assert initialize_reply.get("result", {}).get("protocolVersion") == PROTOCOL

        # AwaitingInitialized admits ping but not ordinary operations.
        assert error_code(client.request("tools/list", timeout=60)) == -32600
        assert client.request("ping", timeout=60).get("result") == {}
        client.send("notifications/initialized", notification=True)

        # MCP is Ready while the intentionally slow session build is not.
        tools = client.request("tools/list", timeout=60)
        assert isinstance(tools.get("result", {}).get("tools"), list), tools
        not_ready = client.request(
            "tools/call", {"name": "list_sessions", "arguments": {}}, timeout=60
        )
        assert tool_error(not_ready), not_ready
        assert " is not ready:" in tool_text(not_ready), not_ready

        # Re-initialize is still invalid after the connection reached Ready.
        assert error_code(
            client.request("initialize", {"protocolVersion": PROTOCOL}, timeout=60)
        ) == -32600

        ready = wait_for_ready(
            client, probe_name="shout", probe_args={"input": "ready"}
        )
        assert not tool_error(ready), ready
        assert tool_text(ready) == "READY", ready

    finally:
        returncode = client.close(timeout=30)
        shutil.rmtree(fixture, ignore_errors=True)
    assert returncode == 0, returncode

    # A failed Isabelle startup is an application state, not a connection
    # lifecycle failure: the Ready MCP connection still answers ping.
    failed_session = f"MCP-No-Such-Session-E2E-{os.getpid()}"
    failed_client = server("-s", failed_session, "-T", "No_Such_Theory")
    try:
        initialize(failed_client)
        deadline = time.monotonic() + 30
        failed: dict[str, Any] = {}
        while time.monotonic() < deadline:
            failed = failed_client.request(
                "tools/call", {"name": "list_sessions", "arguments": {}}, timeout=10
            )
            if " failed to start:" in tool_text(failed):
                break
            time.sleep(0.05)
        assert tool_error(failed) and " failed to start:" in tool_text(failed), failed
        assert failed_client.request("ping", timeout=10).get("result") == {}
    finally:
        failed_returncode = failed_client.close(timeout=30)
    assert failed_returncode == 0, failed_returncode
    return 0


def _test_server(
    *,
    max_in_flight: int,
    shutdown_drain: float = 5.0,
    stderr: int | None = None,
) -> tuple[Client, dict[str, str]]:
    client = server(
        "-o",
        f"mcp_max_in_flight={max_in_flight}",
        "-o",
        f"mcp_shutdown_drain={shutdown_drain}",
        "-o",
        "mcp_request_timeout=60",
        "-s",
        "MCP-Tools-Tests",
        "-T",
        "MCP_Tools_Tests",
        stderr=stderr,
    )
    initialize(client)
    ready = wait_for_ready(client, probe_name="shout", probe_args={"input": "ready"})
    assert not tool_error(ready) and tool_text(ready) == "READY", ready
    tools = wait_for_capture_tools(client)
    return client, tools


def _tool_request(rpc_id: str, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
    return {
        "jsonrpc": "2.0",
        "id": rpc_id,
        "method": "tools/call",
        "params": {"name": name, "arguments": arguments},
    }


def _cancel_notification(rpc_id: str) -> dict[str, Any]:
    return {
        "jsonrpc": "2.0",
        "method": "notifications/cancelled",
        "params": {"requestId": rpc_id, "reason": "e2e cancellation"},
    }


def run_cancellation_and_batches() -> int:
    """Cancellation under saturation and all client-visible batch shapes."""
    client, tools = _test_server(max_in_flight=1)
    try:
        # A notification-only batch emits no frame: the following ping must be
        # the next envelope, not an empty response array.
        client.send_json(
            [
                {"jsonrpc": "2.0", "method": "notifications/unknown"},
                _cancel_notification("unknown-request"),
            ]
        )
        ping_id = client.send("ping")
        assert ping_id is not None
        assert client.await_reply(ping_id, timeout=10).get("result") == {}

        client.send_json([])
        empty = client.recv_json(timeout=10)
        assert isinstance(empty, dict) and error_code(empty) == -32600, empty

        client.send_json(
            [
                {
                    "jsonrpc": "2.0",
                    "id": "batch-init",
                    "method": "initialize",
                    "params": {"protocolVersion": PROTOCOL},
                },
                {"jsonrpc": "2.0", "id": "batch-init-ping", "method": "ping"},
            ]
        )
        initialize_batch = client.recv_json(timeout=10)
        assert isinstance(initialize_batch, dict), initialize_batch
        assert error_code(initialize_batch) == -32600, initialize_batch

        client.send_json(
            [
                {"jsonrpc": "2.0", "id": "batch-list", "method": "tools/list"},
                {"jsonrpc": "2.0", "method": "notifications/unknown"},
                17,
                {"jsonrpc": "2.0", "id": "batch-ping", "method": "ping"},
            ]
        )
        mixed = client.recv_json(timeout=10)
        assert isinstance(mixed, list) and len(mixed) == 3, mixed
        mixed_ids = {value.get("id") for value in mixed}
        assert mixed_ids == {"batch-list", "batch-ping", None}, mixed

        # Per-element admission uses the same bound: the second tool is an
        # immediate overload, while the aggregate waits for the first.
        client.send_json(
            [
                _tool_request("batch-slow", tools["slow"], {}),
                _tool_request("batch-overload", tools["ok"], {"x": "overload"}),
            ]
        )
        saturated = client.recv_json(timeout=10)
        assert isinstance(saturated, list) and len(saturated) == 2, saturated
        saturated_by_id = {value.get("id"): value for value in saturated}
        assert error_code(saturated_by_id["batch-overload"]) == -32001, saturated
        assert tool_text(saturated_by_id["batch-slow"]) == "slow done", saturated

        # A same-batch cancellation omits only the cancelled response slot.
        client.send_json(
            [
                _tool_request("batch-cancelled", tools["slow"], {}),
                _cancel_notification("batch-cancelled"),
                {"jsonrpc": "2.0", "id": "batch-cancel-ping", "method": "ping"},
            ]
        )
        cancelled_batch = client.recv_json(timeout=10)
        assert isinstance(cancelled_batch, list), cancelled_batch
        assert [value.get("id") for value in cancelled_batch] == ["batch-cancel-ping"], (
            cancelled_batch
        )

        # Standalone cancellation wins while capacity is saturated.  It emits
        # no response and capacity becomes usable again after cooperative ML
        # interruption releases the worker permit.
        # The previous cancellation resolves its batch as soon as terminal
        # ownership changes, while the interrupted ML worker can need a short
        # moment to return its scheduler permit.  Probe with the slow request
        # itself until it is observably admitted instead of assuming that an
        # emitted batch aggregate also means worker teardown has completed.
        while True:
            slow_id = client.send(
                "tools/call", {"name": tools["slow"], "arguments": {}}
            )
            assert slow_id is not None
            try:
                early = client.await_reply(slow_id, timeout=0.2)
            except queue.Empty:
                break
            assert error_code(early) == -32001, early
            time.sleep(0.02)
        overload_id = client.send(
            "tools/call", {"name": tools["ok"], "arguments": {"x": "blocked"}}
        )
        assert overload_id is not None
        overload = client.await_reply(overload_id, timeout=10)
        assert error_code(overload) == -32001, overload
        client.send(
            "notifications/cancelled",
            {"requestId": slow_id, "reason": "client stopped"},
            notification=True,
        )

        recovered = False
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline and not recovered:
            recovery_id = client.send(
                "tools/call", {"name": tools["ok"], "arguments": {"x": "recovered"}}
            )
            assert recovery_id is not None
            recovery = client.await_reply(recovery_id, timeout=10)
            if error_code(recovery) == -32001:
                time.sleep(0.02)
            else:
                assert tool_text(recovery) == "got:recovered", recovery
                recovered = True
        assert recovered, "worker capacity did not recover after cancellation"
        client.assert_no_reply(slow_id, timeout=0.5)
    finally:
        returncode = client.close(timeout=30)
    assert returncode == 0, returncode
    return 0


def _close_and_messages(client: Client, timeout: float = 30) -> tuple[int, list[Any]]:
    returncode = client.close(timeout=timeout)
    return returncode, client.drain_json()


def run_eof_drain() -> int:
    """Zero/one/many EOF drain plus deadline cancellation."""
    zero = server("-o", "mcp_shutdown_drain=5")
    initialize(zero)
    zero_code, zero_messages = _close_and_messages(zero)
    assert zero_code == 0 and zero_messages == [], (zero_code, zero_messages)

    one, one_tools = _test_server(max_in_flight=1)
    one_id = one.send("tools/call", {"name": one_tools["slow"], "arguments": {}})
    assert one_id is not None
    one_code, one_messages = _close_and_messages(one)
    assert one_code == 0, one_code
    assert [value.get("id") for value in one_messages] == [one_id], one_messages

    many, many_tools = _test_server(max_in_flight=2)
    slow_id = many.send("tools/call", {"name": many_tools["slow"], "arguments": {}})
    fast_id = many.send(
        "tools/call", {"name": many_tools["ok"], "arguments": {"x": "fast"}}
    )
    assert slow_id is not None and fast_id is not None
    many_code, many_messages = _close_and_messages(many)
    assert many_code == 0, many_code
    many_ids = [value.get("id") for value in many_messages]
    assert many_ids == [fast_id, slow_id], many_messages

    with tempfile.TemporaryFile(mode="w+") as deadline_stderr:
        deadline_client, deadline_tools = _test_server(
            max_in_flight=1,
            shutdown_drain=0.05,
            stderr=deadline_stderr.fileno(),
        )
        cancelled_id = deadline_client.send(
            "tools/call", {"name": deadline_tools["slow"], "arguments": {}}
        )
        assert cancelled_id is not None
        deadline_code, deadline_messages = _close_and_messages(
            deadline_client, timeout=10
        )
        deadline_stderr.seek(0)
        shutdown_log = deadline_stderr.read()
        assert deadline_code == 0, deadline_code
        assert "waited 0.050s; cancelled 1" in shutdown_log, shutdown_log
        assert all(value.get("id") != cancelled_id for value in deadline_messages), (
            deadline_messages
        )
    return 0


CASES: dict[str, Callable[[], int]] = {
    "lifecycle": run_lifecycle_readiness,
    "cancellation-batch": run_cancellation_and_batches,
    "eof": run_eof_drain,
}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("case", choices=[*CASES, "all"], nargs="?", default="all")
    args = parser.parse_args(argv)
    selected = CASES.items() if args.case == "all" else [(args.case, CASES[args.case])]
    for name, function in selected:
        function()
        print(f"PASS connection-kernel e2e {name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
