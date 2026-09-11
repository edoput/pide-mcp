#!/usr/bin/env python3
"""Real-process acceptance for the retained MCP server and tool catalogue.

ISABELLE selects the launcher; MCP_TEST_TIMEOUT bounds prover readiness and
MCP_HANDSHAKE_TIMEOUT bounds the handshake (after the Scala jar is built).
Temporary session/theory fixtures avoid modifying the shared registration heap.
"""
from contextlib import contextmanager
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import time
import uuid

from mcp.test.e2e.client import Client, wait_for_ready, wait_for_shout
from tools.isabelle_launcher import resolve_launcher

PROJECT_ROOT = Path(__file__).resolve().parents[2]
ISABELLE = list(resolve_launcher().argv)
TIMEOUT = float(os.environ.get("MCP_TEST_TIMEOUT", "600"))
HANDSHAKE_TIMEOUT = float(os.environ.get("MCP_HANDSHAKE_TIMEOUT", "60"))
BUILTINS = {"load_theory", "check_theory", "unload_theory", "list_sessions",
            "list_theories", "search_sources", "doc_list", "doc_read"}
failures = 0


def verdict(name, ok, detail=""):
    global failures
    failures += not ok
    limit = 2000 if not ok else 240
    print(f"{'PASS' if ok else 'FAIL'} {name}" + (f" -- {str(detail)[:limit]}" if detail else ""))


def text_of(reply):
    return "\n".join(item.get("text", "") for item in reply.get("result", {}).get("content", []))


def is_err(reply):
    return "error" in reply or bool(reply.get("result", {}).get("isError"))


def call(client, tool_name, **arguments):
    return client.request("tools/call", {"name": tool_name, "arguments": arguments})


def initialize(client):
    started = time.monotonic()
    reply = client.request("initialize", {"protocolVersion": "2025-03-26",
        "capabilities": {}, "clientInfo": {"name": "carve-out-e2e", "version": "1"}},
        timeout=HANDSHAKE_TIMEOUT)
    verdict("initialize advertises only tools", reply.get("result", {}).get("capabilities") ==
            {"tools": {"listChanged": True}}, reply)
    verdict("handshake completes within its deadline", time.monotonic() - started < HANDSHAKE_TIMEOUT)
    client.send("notifications/initialized", notification=True)
    reply = wait_for_ready(client, timeout=TIMEOUT)
    if is_err(reply):
        raise AssertionError(f"server failed to become ready: {reply}")


@contextmanager
def fixture_session(source, *, extra_theories=None):
    """Create a private session rooted in MCP-Tools, then remove its sources."""
    token = uuid.uuid4().hex[:12]
    session = f"MCP-E2E-{token}"
    theory = f"MCP_E2E_{token}"
    with tempfile.TemporaryDirectory(prefix="mcp_e2e_", dir=PROJECT_ROOT) as directory:
        path = Path(directory)
        extra_theories = extra_theories or {}
        for name, body in extra_theories.items():
            (path / f"{name}.thy").write_text(body, encoding="utf-8")
        (path / f"{theory}.thy").write_text(
            f'theory {theory}\nimports "MCP-Tools.MCP_Tools"\nbegin\n{source}\nend\n', encoding="utf-8")
        (path / "ROOT").write_text(
            f'session "{session}" = "MCP-Tools" +\n  theories\n' +
            "".join(f"    {name}\n" for name in [*extra_theories, theory]), encoding="utf-8")
        yield path, session, theory


def test_public_protocol():
    with Client(ISABELLE + ["mcp_server"]) as client:
        initialize(client)
        verdict("ping", client.request("ping").get("result") == {})
        _, tools = wait_for_shout(client)
        names = {row["name"] for row in tools}
        verdict("exact builtin and base ML catalogue", names == BUILTINS | {"shout", "ptyp_fixture"}, names)
        shout = next(row for row in tools if row["name"] == "shout")
        schema = shout["inputSchema"]
        verdict("ML schema has optional context URL", schema["properties"]["context"]["type"] == "string"
                and "context" not in schema.get("required", []), schema)
        reply = call(client, "shout", input="isabelle")
        verdict("ML tool executes", not is_err(reply) and text_of(reply) == "ISABELLE", reply)
        verdict("missing declared input fails", is_err(call(client, "shout")))
        verdict("unknown tool fails", is_err(call(client, "no_such_tool")))
        verdict("malformed context fails", is_err(call(client, "shout", input="hi", context="bad-url")))
        for method in ["resources/list", "resources/templates/list", "resources/read", "no/such_method"]:
            reply = client.request(method, {"uri": "isabelle://session"})
            verdict(f"{method} is unsupported", reply.get("error", {}).get("code") == -32601, reply)
        client.send_raw("this is not json")
        verdict("malformed JSON fails", client.recv().get("error", {}).get("code") == -32700)
        for name, args in [("list_sessions", {}), ("list_theories", {"session": "MCP-Tools"}),
                           ("search_sources", {"pattern": "MCP_Tools"}), ("doc_list", {}),
                           ("doc_read", {"name": "NEWS", "lines": "1-5"})]:
            reply = call(client, name, **args)
            verdict(f"{name} executes", not is_err(reply) and bool(text_of(reply)), reply)
        verdict("clean EOF shutdown", client.close() == 0)


def test_theory_tools_and_context():
    target = "MCP_E2E_Target_" + uuid.uuid4().hex[:10]
    target_source = f'''theory {target}
imports "MCP-Tools.MCP_Tools"
begin
mcp_tool target_only = \\<open>fn s => s\\<close>
  (description \\<open>must not enter root catalogue\\<close>)
end
'''
    source = r'''
mcp_tool context_probe = capture \<open>fn ctxt => fn args =>
  writeln (Context.theory_name {long = false} (Proof_Context.theory_of ctxt) ^ ":" ^
    MCP_Combinators.arg args "message")\<close>
  (description \<open>report invocation context\<close>)
  (params message :: string \<open>message\<close>)
  (annotations read_only)
mcp_tool hidden = \<open>fn s => s\<close> (description \<open>inactive fixture\<close>)
declare [[mcp_tools del: hidden]]
declare [[mcp_tools del: doc_list]]
'''
    with fixture_session(source, extra_theories={target: target_source}) as (path, session, theory):
        with Client(ISABELLE + ["mcp_server", "-d", str(path), "-s", session, "-T", theory]) as client:
            initialize(client)
            rows = client.request("tools/list")["result"]["tools"]
            names = {row["name"] for row in rows}
            verdict("root activation controls listing", "context_probe" in names and "hidden" not in names
                    and "doc_list" not in names and "target_only" not in names, names)
            verdict("hidden builtin remains callable", not is_err(call(client, "doc_list")))
            verdict("hidden ML tool is refused", is_err(call(client, "hidden", input="x")))
            reply = call(client, "context_probe", message="root")
            verdict("default invocation uses live wrapper", not is_err(reply)
                    and text_of(reply).startswith("MCP_Root_") and text_of(reply).endswith(":root"), reply)
            locator = f"isabelle://context/theory/{session}.{target}"
            reply = call(client, "context_probe", message="target", context=locator)
            verdict("root-selected tool executes in separate target", not is_err(reply)
                    and f"{target}:target" in text_of(reply), reply)
            verdict("target catalogue cannot supply a missing root tool",
                    is_err(call(client, "target_only", input="x", context=locator)))
            draft = "MCP_E2E_Draft"
            draft_path = path / f"{draft}.thy"
            def write_draft(body):
                draft_path.write_text(f'theory {draft}\nimports \"MCP-Tools.MCP_Tools\"\nbegin\n{body}\nend\n', encoding="utf-8")
            write_draft('ML \\<open>warning "fixture-warning"\\<close>\n'
                        'mcp_tool draft_tool = \\<open>fn s => s\\<close> (description \\<open>draft\\<close>)')
            client.notifications.clear()
            reply = call(client, "load_theory", name=draft, master_dir=str(path))
            verdict("load_theory reports warning", not is_err(reply) and "fixture-warning" in text_of(reply), reply)
            verdict("declaration emits tools notification while load is pending",
                    client.await_notification("notifications/tools/list_changed"))
            write_draft('ML \\<open>error "fixture-error"\\<close>')
            reply = call(client, "check_theory", name=draft, master_dir=str(path))
            verdict("check_theory reports edited error", "fixture-error" in text_of(reply), reply)
            write_draft('ML \\<open>writeln "fixed"\\<close>')
            reply = call(client, "check_theory", name=draft, master_dir=str(path))
            verdict("check_theory accepts repaired file", not is_err(reply) and "fixture-error" not in text_of(reply), reply)
            verdict("unload_theory removes loaded document", not is_err(call(client, "unload_theory", name=draft)))
            verdict("unload_theory rejects image theory", is_err(call(client, "unload_theory", name=theory)))


def test_live_root_refresh():
    token = uuid.uuid4().hex[:12]
    ancestor = f"RefreshAncestor_{token}"
    root = f"RefreshRoot_{token}"
    with tempfile.TemporaryDirectory(prefix="mcp_refresh_", dir=PROJECT_ROOT) as directory:
        path = Path(directory)
        (path / "ROOT").write_text(
            f'session "Refresh-{token}" = "MCP-Tools" +\n  theories {root}\n', encoding="utf-8")
        (path / f"{root}.thy").write_text(
            f'theory {root} imports {ancestor} begin end\n', encoding="utf-8")
        def write_ancestor(version):
            (path / f"{ancestor}.thy").write_text(
                f'theory {ancestor} imports "MCP-Tools.MCP_Tools" begin\n'
                f'mcp_tool inherited_probe = \\<open>K "{version}"\\<close> '
                f'(description \\<open>{version}\\<close>)\nend\n', encoding="utf-8")
        write_ancestor("version-one")
        with Client(ISABELLE + ["mcp_server", "-d", str(path), "-s", "MCP-Tools", "-T", root]) as client:
            initialize(client)
            for version in ["version-one", "version-two"]:
                if version == "version-two":
                    write_ancestor(version)
                    reply = call(client, "check_theory", name=ancestor, master_dir=str(path))
                    verdict("reprocess imported local ancestor", not is_err(reply), reply)
                rows = client.request("tools/list")["result"]["tools"]
                probe = next((row for row in rows if row["name"] == "inherited_probe"), {})
                verdict(f"live wrapper catalogue {version}", probe.get("description") == version, probe)
                reply = call(client, "inherited_probe", input="ignored")
                verdict(f"live wrapper execution {version}", not is_err(reply) and text_of(reply) == version, reply)


def test_startup_diagnostics():
    with tempfile.TemporaryFile(mode="w+") as stderr:
        with Client(ISABELLE + ["mcp_server", "-v", "-s", "MCP-HOL", "-T", "MCP-HOL.MCP"],
                    stderr=stderr.fileno()) as client:
            initialize(client)
            verdict("verbose startup preserves JSON-RPC", client.request("ping").get("result") == {})
        stderr.seek(0)
        log = stderr.read()
        verdict("verbose startup reports library-resolved heap inputs",
                "Resolved heap input: " in log and "Session: MCP-HOL" in log, log)
        verdict("verbose startup reports resolved registry theory",
                "Registry theory: MCP-HOL.MCP" in log, log)
        verdict("startup reports image check outcome",
                "Session image MCP-HOL: reused" in log or
                "Session image MCP-HOL: build completed" in log, log)


def main():
    if not ISABELLE or shutil.which(ISABELLE[0]) is None:
        print("FAIL setup -- Isabelle command not found")
        return 1
    test_public_protocol()
    test_startup_diagnostics()
    test_theory_tools_and_context()
    test_live_root_refresh()
    print(f"{failures} failure(s)")
    return int(bool(failures))


if __name__ == "__main__":
    sys.exit(main())
