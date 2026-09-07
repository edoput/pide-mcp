#!/usr/bin/env python3
"""MVP acceptance test for `isabelle mcp_server` (see spec-mvp).

Spawns the server, speaks MCP (newline-delimited JSON-RPC 2.0) over
stdio, and checks that a tool registered in Isabelle/ML can be listed
and executed in the prover.

Environment:
  ISABELLE              shell-split Isabelle command
                        (default: the project Flatpak invocation)
  MCP_TEST_TIMEOUT      per-reply timeout in seconds (default: 600) for
                        prover-backed replies -- the FIRST tools/call that
                        actually reaches the prover may still take minutes
                        on a cold session-heap build (plans/readiness: the
                        build itself was never removed, only moved off the
                        json-rpc loop).
  MCP_HANDSHAKE_TIMEOUT per-reply timeout in seconds (default: 60) for
                        `initialize` specifically: the handshake must be
                        fast REGARDLESS of session-heap state, since it no
                        longer waits on the prover (spec "server startup
                        and readiness"). NOTE this assertion needs a WARM
                        mcp.jar -- `isabelle mcp_server` recompiles the jar
                        (scala_build) in the isabelle launcher BEFORE
                        Main/run() is even entered, so no readiness work
                        can make a cold scala_build fast; run
                        `isabelle scala_build` once beforehand (or just
                        run this suite twice) if timing out here.

Exit code 0 iff all assertions pass; prints one verdict line per case.
"""

import json
import os
import re
import shlex
import shutil
import sys
import tempfile
import time

from mcp.test.e2e.client import Client, wait_for_ready, wait_for_shout

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ISABELLE = shlex.split(os.environ.get(
    "ISABELLE", "flatpak run --command=isabelle de.tum.in.isabelle.Isabelle"))
TIMEOUT = float(os.environ.get("MCP_TEST_TIMEOUT", "600"))
HANDSHAKE_TIMEOUT = float(os.environ.get("MCP_HANDSHAKE_TIMEOUT", "60"))

failures = 0


def verdict(name, ok, detail=""):
    global failures
    if not ok:
        failures += 1
    limit = 2000 if not ok else 240
    if len(detail) > limit:
        detail = detail[:limit] + " ... [truncated]"
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, " -- " + detail if detail else ""))


def test_repl_builtins():
    """tools/call on the repl_* builtins, end to end through the full
    JSON-RPC -> Handler -> Builtin_Tool -> MCP.ir -> prover path -- the one
    integration seam the MCP-Tools client above never exercises, since
    "shout" is an ML tool (MCP.run_tool), not a builtin (MCP.ir). Needs a
    server running on an I/R-capable session (MCP-HOL/MCP_Repl); the
    default session (MCP-Tools) does not load MCP_Repl and has no handler
    for MCP.ir at all.

    Covers repl_init/repl_list T7 (plans/repl_init), repl_remove T5
    (plans/repl_remove), repl_step T6 (plans/repl_step), repl_state T3
    (plans/repl_state), repl_show T3 (plans/repl_show), repl_text's
    extraction half (plans/repl_text T3 -- the splice-into-a-theory-file/
    check_theory half of T3 needs load_theory and check_theory as
    builtins, wave 2, not yet implemented), repl_edit T5 (plans/repl_edit),
    repl_replay T1 (plans/repl_replay, the idempotent no-op case --
    T2..T4's genuinely-stale-step scenarios need repl_pin/repl_rebase as
    builtins too, not yet implemented, and stay at the ml-unit layer,
    MCP_Repl_Tests.thy), repl_truncate T6 (plans/repl_truncate,
    wrong-turn recovery), repl_back T3 (plans/repl_back, the same
    wrong-turn loop via the truncate -1 shorthand), repl_timeout T4
    (plans/repl_timeout, the full chain -- setting and reading back the
    per-step timeout against a live server; T1/T2's timing-dependent and
    negative-secs edge cases stay at the ml-unit layer), and repl_pin T5 /
    repl_rebase T5 (plans/repl_pin, plans/repl_unpin, plans/repl_rebase --
    the build-a-base workflow: PinA defines a constant and pins, PinB is
    init'd from pin@PinA and proves a lemma using it, repl_rebase on an
    up-to-date REPL replies "already up to date", repl_unpin is blocked
    while PinB depends on the pin and succeeds once PinB is removed), and
    repl_fork T6 / repl_merge's deferred e2e case (plans/repl_fork,
    plans/repl_merge -- the fork -> merge round trip repl_merge's own
    CHANGELOG entry left pending until repl_fork existed as a builtin):
    create, step a lemma, check repl_state -1 shows the same goal
    repl_step just printed, step its proof, repl_edit that step's tactic,
    check repl_show lists both steps, repl_timeout sets a new per-step
    timeout and repl_show reflects it, check repl_text shows the
    edited script, repl_replay finds nothing stale to redo,
    repl_truncate -1 drops the tactic step, repl_step re-does it a
    different way, repl_text shows the new script, repl_back drops it
    again, repl_step re-does it yet another way, repl_text shows that
    script too, repl_fork a sub-REPL from the tip, prove a lemma in it,
    repl_merge it back and confirm the merged step's text and that the
    fork is gone from repl_list, list, remove, and confirm removal via a
    second list.
    (repl_edit's failure-atomicity
    promise -- editing with text that fails leaves the old step
    untouched -- is exercised at the ml-unit layer, T2, not here: a
    rejected edit is indistinguishable from a no-op at this level of
    the e2e test.)
    """
    client = Client(ISABELLE + ["mcp_server", "-s", "MCP-HOL", "-T", "MCP_Repl"])
    try:
        client.request("initialize", {
            "protocolVersion": "2025-03-26",
            "capabilities": {},
            "clientInfo": {"name": "test_mcp", "version": "0"},
        })
        client.send("notifications/initialized", notification=True)

        # plans/readiness: MCP-HOL builds HOL if the heap is stale, which
        # can take minutes -- the FIRST tools/call below must wait for
        # that instead of assuming the mvp's old blocking handshake
        # already did.
        reply = wait_for_ready(client)
        verdict("repl builtins: server becomes ready within the timeout",
                not reply.get("result", {}).get("isError", False), json.dumps(reply))

        reply = client.request("tools/list")
        tools = reply.get("result", {}).get("tools", [])
        names = [t.get("name") for t in tools]
        verdict("repl builtins: tools/list has repl_list and repl_init",
                "repl_list" in names and "repl_init" in names, json.dumps(names))

        reply = client.request("tools/call",
            {"name": "repl_init", "arguments": {"repl": "E2E", "theories": ["Main"]}})
        content = reply.get("result", {}).get("content", [])
        verdict("repl builtins: tools/call repl_init creates the repl",
                content and "Created REPL" in content[0].get("text", "")
                and not reply["result"].get("isError", False),
                json.dumps(reply))

        reply = client.request("tools/call", {"name": "repl_list", "arguments": {}})
        content = reply.get("result", {}).get("content", [])
        text = content[0].get("text", "") if content else ""
        verdict("repl builtins: tools/call repl_list shows the created repl",
                "E2E" in text and not reply["result"].get("isError", False),
                json.dumps(reply))

        reply = client.request("tools/call", {"name": "repl_step",
            "arguments": {"repl": "E2E", "isar_text": "lemma \"x + y = y + (x::nat)\""}})
        content = reply.get("result", {}).get("content", [])
        verdict("repl builtins: tools/call repl_step steps a lemma statement",
                content and "goal" in content[0].get("text", "")
                and not reply["result"].get("isError", False),
                json.dumps(reply))

        reply = client.request("tools/call",
            {"name": "repl_state", "arguments": {"repl": "E2E", "state_idx": -1}})
        content = reply.get("result", {}).get("content", [])
        verdict("repl builtins: tools/call repl_state -1 shows the goal repl_step just printed",
                content and "goal" in content[0].get("text", "")
                and not reply["result"].get("isError", False),
                json.dumps(reply))

        reply = client.request("tools/call",
            {"name": "repl_step", "arguments": {"repl": "E2E", "isar_text": "by simp"}})
        verdict("repl builtins: tools/call repl_step closes the proof",
                not reply.get("result", {}).get("isError", False), json.dumps(reply))

        reply = client.request("tools/call", {"name": "repl_edit",
            "arguments": {"repl": "E2E", "idx": 1, "isar_text": "by auto"}})
        verdict("repl builtins: tools/call repl_edit replaces the proof step's tactic",
                not reply.get("result", {}).get("isError", False), json.dumps(reply))

        reply = client.request("tools/call", {"name": "repl_show", "arguments": {"repl": "E2E"}})
        content = reply.get("result", {}).get("content", [])
        text = content[0].get("text", "") if content else ""
        verdict("repl builtins: tools/call repl_show lists both steps",
                "2 steps" in text and not reply["result"].get("isError", False),
                json.dumps(reply))

        reply = client.request("tools/call",
            {"name": "repl_timeout", "arguments": {"repl": "E2E", "secs": 5}})
        content = reply.get("result", {}).get("content", [])
        verdict("repl builtins: tools/call repl_timeout sets the per-step timeout",
                content and "5s" in content[0].get("text", "")
                and not reply["result"].get("isError", False),
                json.dumps(reply))

        reply = client.request("tools/call", {"name": "repl_show", "arguments": {"repl": "E2E"}})
        content = reply.get("result", {}).get("content", [])
        text = content[0].get("text", "") if content else ""
        verdict("repl builtins: tools/call repl_show reflects the new timeout",
                "timeout=5s" in text and not reply["result"].get("isError", False),
                json.dumps(reply))

        reply = client.request("tools/call", {"name": "repl_text", "arguments": {"repl": "E2E"}})
        content = reply.get("result", {}).get("content", [])
        text = content[0].get("text", "") if content else ""
        verdict("repl builtins: tools/call repl_text shows the edited script after repl_edit",
                text == 'lemma "x + y = y + (x::nat)"\nby auto'
                and not reply["result"].get("isError", False),
                json.dumps(reply))

        reply = client.request("tools/call", {"name": "repl_replay", "arguments": {"repl": "E2E"}})
        content = reply.get("result", {}).get("content", [])
        text = content[0].get("text", "") if content else ""
        verdict("repl builtins: tools/call repl_replay is an ok no-op with nothing stale",
                "Replayed 0 stale steps" in text and not reply["result"].get("isError", False),
                json.dumps(reply))

        reply = client.request("tools/call",
            {"name": "repl_truncate", "arguments": {"repl": "E2E", "idx": -1}})
        content = reply.get("result", {}).get("content", [])
        verdict("repl builtins: tools/call repl_truncate drops the last step",
                content and "Truncated" in content[0].get("text", "")
                and not reply["result"].get("isError", False),
                json.dumps(reply))

        reply = client.request("tools/call", {"name": "repl_step",
            "arguments": {"repl": "E2E", "isar_text": "by (simp add: add.commute)"}})
        verdict("repl builtins: tools/call repl_step re-does the truncated step a different way",
                not reply.get("result", {}).get("isError", False), json.dumps(reply))

        reply = client.request("tools/call", {"name": "repl_text", "arguments": {"repl": "E2E"}})
        content = reply.get("result", {}).get("content", [])
        text = content[0].get("text", "") if content else ""
        verdict("repl builtins: tools/call repl_text shows the new script after truncate+step",
                text == 'lemma "x + y = y + (x::nat)"\nby (simp add: add.commute)'
                and not reply["result"].get("isError", False),
                json.dumps(reply))

        reply = client.request("tools/call",
            {"name": "repl_back", "arguments": {"repl": "E2E"}})
        content = reply.get("result", {}).get("content", [])
        verdict("repl builtins: tools/call repl_back drops the last step (truncate -1 sugar)",
                content and "Truncated" in content[0].get("text", "")
                and not reply["result"].get("isError", False),
                json.dumps(reply))

        reply = client.request("tools/call", {"name": "repl_step",
            "arguments": {"repl": "E2E", "isar_text": "by simp"}})
        verdict("repl builtins: tools/call repl_step re-does the backed-out step yet another way",
                not reply.get("result", {}).get("isError", False), json.dumps(reply))

        reply = client.request("tools/call", {"name": "repl_text", "arguments": {"repl": "E2E"}})
        content = reply.get("result", {}).get("content", [])
        text = content[0].get("text", "") if content else ""
        verdict("repl builtins: tools/call repl_text shows the script after repl_back+step",
                text == 'lemma "x + y = y + (x::nat)"\nby simp'
                and not reply["result"].get("isError", False),
                json.dumps(reply))

        # plans/repl_fork T6 / plans/repl_merge (deferred e2e, T5): fork a
        # sub-REPL at E2E's tip, prove something in it, then merge it back
        # -- the fork -> merge round trip that repl_merge's own CHANGELOG
        # entry left pending until repl_fork existed as a builtin.
        reply = client.request("tools/call", {"name": "repl_fork",
            "arguments": {"repl": "E2E", "new_repl": "E2EFork", "state_idx": -1}})
        content = reply.get("result", {}).get("content", [])
        verdict("repl builtins: tools/call repl_fork forks E2EFork from E2E",
                content and "Forked" in content[0].get("text", "")
                and not reply["result"].get("isError", False),
                json.dumps(reply))

        reply = client.request("tools/call", {"name": "repl_step",
            "arguments": {"repl": "E2EFork", "isar_text": "lemma \"(1::nat) + 1 = 2\" by simp"}})
        verdict("repl builtins: tools/call repl_step proves a lemma in the fork",
                not reply.get("result", {}).get("isError", False), json.dumps(reply))

        reply = client.request("tools/call",
            {"name": "repl_merge", "arguments": {"repl": "E2EFork"}})
        content = reply.get("result", {}).get("content", [])
        verdict("repl builtins: tools/call repl_merge merges the fork back into E2E",
                content and "appended as new step" in content[0].get("text", "")
                and not reply["result"].get("isError", False),
                json.dumps(reply))

        reply = client.request("tools/call", {"name": "repl_text", "arguments": {"repl": "E2E"}})
        content = reply.get("result", {}).get("content", [])
        text = content[0].get("text", "") if content else ""
        verdict("repl builtins: tools/call repl_text shows the merged fork's step appended",
                text == 'lemma "x + y = y + (x::nat)"\nby simp\nlemma "(1::nat) + 1 = 2" by simp'
                and not reply["result"].get("isError", False),
                json.dumps(reply))

        reply = client.request("tools/call", {"name": "repl_list", "arguments": {}})
        content = reply.get("result", {}).get("content", [])
        text = content[0].get("text", "") if content else ""
        verdict("repl builtins: tools/call repl_list no longer shows the merged fork",
                "E2EFork" not in text and not reply["result"].get("isError", False),
                json.dumps(reply))

        reply = client.request("tools/call",
            {"name": "repl_remove", "arguments": {"repl": "E2E"}})
        content = reply.get("result", {}).get("content", [])
        verdict("repl builtins: tools/call repl_remove removes the repl",
                content and "E2E" in content[0].get("text", "")
                and not reply["result"].get("isError", False),
                json.dumps(reply))

        reply = client.request("tools/call", {"name": "repl_list", "arguments": {}})
        content = reply.get("result", {}).get("content", [])
        text = content[0].get("text", "") if content else ""
        verdict("repl builtins: tools/call repl_list no longer shows the removed repl",
                "E2E" not in text and not reply["result"].get("isError", False),
                json.dumps(reply))

        # build-a-base workflow (plans/repl_pin T5, plans/repl_rebase T5):
        # PinA defines a constant and pins; PinB is init'd from pin@PinA and
        # proves a lemma using it -- the pin/rebase/replay round trip.
        reply = client.request("tools/call",
            {"name": "repl_init", "arguments": {"repl": "PinA", "theories": ["Main"]}})
        verdict("repl builtins: tools/call repl_init creates PinA",
                not reply.get("result", {}).get("isError", False), json.dumps(reply))

        reply = client.request("tools/call", {"name": "repl_step",
            "arguments": {"repl": "PinA",
                           "isar_text": "definition e2e_const :: nat where \"e2e_const = 99\""}})
        verdict("repl builtins: tools/call repl_step defines a constant in PinA",
                not reply.get("result", {}).get("isError", False), json.dumps(reply))

        reply = client.request("tools/call", {"name": "repl_pin", "arguments": {"repl": "PinA"}})
        content = reply.get("result", {}).get("content", [])
        verdict("repl builtins: tools/call repl_pin pins PinA",
                content and "Pinned" in content[0].get("text", "")
                and not reply["result"].get("isError", False),
                json.dumps(reply))

        reply = client.request("tools/call",
            {"name": "repl_init", "arguments": {"repl": "PinB", "theories": ["pin@PinA"]}})
        verdict("repl builtins: tools/call repl_init creates PinB from pin@PinA",
                not reply.get("result", {}).get("isError", False), json.dumps(reply))

        reply = client.request("tools/call", {"name": "repl_step",
            "arguments": {"repl": "PinB",
                           "isar_text": "lemma \"e2e_const = 99\" by (simp add: e2e_const_def)"}})
        verdict("repl builtins: tools/call repl_step in PinB proves a lemma using PinA's constant",
                not reply.get("result", {}).get("isError", False), json.dumps(reply))

        reply = client.request("tools/call", {"name": "repl_rebase", "arguments": {"repl": "PinB"}})
        content = reply.get("result", {}).get("content", [])
        text = content[0].get("text", "") if content else ""
        verdict("repl builtins: tools/call repl_rebase on an up-to-date REPL is a no-op",
                "already up to date" in text and not reply["result"].get("isError", False),
                json.dumps(reply))

        reply = client.request("tools/call",
            {"name": "repl_unpin", "arguments": {"repl": "PinA"}})
        content = reply.get("result", {}).get("content", [])
        verdict("repl builtins: tools/call repl_unpin fails while PinB depends on the pin",
                reply.get("result", {}).get("isError", False) is True, json.dumps(reply))

        reply = client.request("tools/call",
            {"name": "repl_remove", "arguments": {"repl": "PinB"}})
        verdict("repl builtins: tools/call repl_remove removes PinB",
                not reply.get("result", {}).get("isError", False), json.dumps(reply))

        reply = client.request("tools/call",
            {"name": "repl_unpin", "arguments": {"repl": "PinA"}})
        verdict("repl builtins: tools/call repl_unpin succeeds once PinB is gone",
                not reply.get("result", {}).get("isError", False), json.dumps(reply))

        reply = client.request("tools/call",
            {"name": "repl_remove", "arguments": {"repl": "PinA"}})
        verdict("repl builtins: tools/call repl_remove removes PinA",
                not reply.get("result", {}).get("isError", False), json.dumps(reply))

        # T6 (plans/sledgehammer): the model-facing proof-search loop --
        # step a lemma, call sledgehammer, step the suggestion. Tolerant
        # of prover flakiness (T3): accept either a Try-this suggestion
        # (and confirm it closes the goal) or a clean no-proof-found
        # reply, but never a crash.
        reply = client.request("tools/call",
            {"name": "repl_init", "arguments": {"repl": "Sh6", "theories": ["Main"]}})
        verdict("sledgehammer T6: tools/call repl_init creates Sh6",
                not reply.get("result", {}).get("isError", False), json.dumps(reply))

        reply = client.request("tools/call", {"name": "repl_step",
            "arguments": {"repl": "Sh6", "isar_text": "lemma \"x + y = y + (x::nat)\""}})
        verdict("sledgehammer T6: tools/call repl_step opens the proof",
                not reply.get("result", {}).get("isError", False), json.dumps(reply))

        reply = client.request("tools/call",
            {"name": "sledgehammer", "arguments": {"repl": "Sh6"}})
        content = reply.get("result", {}).get("content", [])
        text = content[0].get("text", "") if content else ""
        is_error = reply.get("result", {}).get("isError", False)
        verdict("sledgehammer T6: tools/call sledgehammer never crashes",
                not is_error, json.dumps(reply))

        tries = [line for line in text.splitlines() if "Try this" in line]
        if tries and not is_error:
            suggestion = re.sub(r".*Try this:\s*", "", tries[0])
            suggestion = re.sub(r"\s*\([0-9.]+\s*m?s\)\s*$", "", suggestion).strip()
            reply = client.request("tools/call", {"name": "repl_step",
                "arguments": {"repl": "Sh6", "isar_text": suggestion}})
            verdict("sledgehammer T6: the suggested one-liner closes the goal via repl_step",
                    not reply.get("result", {}).get("isError", False), json.dumps(reply))

        reply = client.request("tools/call",
            {"name": "repl_remove", "arguments": {"repl": "Sh6"}})
        verdict("sledgehammer T6: tools/call repl_remove removes Sh6",
                not reply.get("result", {}).get("isError", False), json.dumps(reply))

        # T5 (plans/find_theorems): the quoting contract is livable
        # without mcp_server.py's python-side auto-quoter -- a model
        # following the description verbatim (name patterns unquoted,
        # term patterns quoted) gets hits through the full stack.
        reply = client.request("tools/call",
            {"name": "repl_init", "arguments": {"repl": "Ft5", "theories": ["Main"]}})
        verdict("find_theorems T5: tools/call repl_init creates Ft5",
                not reply.get("result", {}).get("isError", False), json.dumps(reply))

        reply = client.request("tools/call", {"name": "find_theorems",
            "arguments": {"repl": "Ft5", "query": "name:conjI"}})
        content = reply.get("result", {}).get("content", [])
        text = content[0].get("text", "") if content else ""
        verdict("find_theorems T5: name:conjI (unquoted name pattern) finds a hit",
                "conjI" in text and not reply.get("result", {}).get("isError", False),
                json.dumps(reply))

        reply = client.request("tools/call", {"name": "find_theorems",
            "arguments": {"repl": "Ft5", "query": "\"_ + _ = _ + _\""}})
        content = reply.get("result", {}).get("content", [])
        text = content[0].get("text", "") if content else ""
        verdict("find_theorems T5: \"_ + _ = _ + _\" (quoted term pattern) finds a hit",
                "theorem(s)" in text and not reply.get("result", {}).get("isError", False),
                json.dumps(reply))

        reply = client.request("tools/call",
            {"name": "repl_remove", "arguments": {"repl": "Ft5"}})
        verdict("find_theorems T5: tools/call repl_remove removes Ft5",
                not reply.get("result", {}).get("isError", False), json.dumps(reply))

        # resources: isabelle://repl/{id} and .../text (resource
        # templates), dispatched onto the same ir show/text a REPL
        # already answers through repl_show/repl_text.
        reply = client.request("resources/templates/list")
        templates = reply.get("result", {}).get("resourceTemplates", [])
        uris = {t.get("uriTemplate") for t in templates}
        verdict("resources/templates/list contains isabelle://repl/{id} and .../text",
                "isabelle://repl/{id}" in uris and "isabelle://repl/{id}/text" in uris,
                json.dumps(reply))

        # isabelle://named/{name}: MCP_Resource's registry (MCP_Tools.thy),
        # "greeting" is the demo resource registered alongside the
        # "shout" demo tool.
        reply = client.request("resources/list")
        resources = reply.get("result", {}).get("resources", [])
        uris = {r.get("uri") for r in resources}
        verdict("resources/list includes isabelle://named/greeting",
                "isabelle://named/greeting" in uris, json.dumps(reply))

        reply = client.request("resources/read", {"uri": "isabelle://named/greeting"})
        contents = reply.get("result", {}).get("contents", [])
        text = contents[0].get("text", "") if contents else ""
        verdict("resources/read isabelle://named/greeting dispatches to MCP_Resource",
                text == "hello from MCP_Resource", json.dumps(reply))

        reply = client.request("resources/read", {"uri": "isabelle://named/no_such_resource"})
        verdict("resources/read on an unknown named resource is an error",
                "error" in reply, json.dumps(reply))

        reply = client.request("tools/call",
            {"name": "repl_init", "arguments": {"repl": "Res", "theories": ["Main"]}})
        verdict("resources: tools/call repl_init creates Res",
                not reply.get("result", {}).get("isError", False), json.dumps(reply))

        reply = client.request("tools/call", {"name": "repl_step",
            "arguments": {"repl": "Res", "isar_text": "lemma \"True\" by simp"}})
        verdict("resources: tools/call repl_step steps a lemma in Res",
                not reply.get("result", {}).get("isError", False), json.dumps(reply))

        reply = client.request("resources/read", {"uri": "isabelle://repl/Res"})
        contents = reply.get("result", {}).get("contents", [])
        text = contents[0].get("text", "") if contents else ""
        verdict("resources/read isabelle://repl/Res dispatches to ir show",
                "Res" in text and "result" in reply, json.dumps(reply))

        reply = client.request("resources/read", {"uri": "isabelle://repl/Res/text"})
        contents = reply.get("result", {}).get("contents", [])
        text = contents[0].get("text", "") if contents else ""
        verdict("resources/read isabelle://repl/Res/text dispatches to ir text",
                text == 'lemma "True" by simp', json.dumps(reply))

        reply = client.request("resources/read", {"uri": "isabelle://theory/NeverLoadedE2E2/commands"})
        verdict("resources/read on a documented-but-not-yet-backed template is an error",
                "error" in reply, json.dumps(reply))

        # isabelle://theory/{name} and .../commands (see mcp_session.
        # scala's theory_source_uri/theory_commands_uri comment and
        # CHANGELOG; the earlier "segments never survive" KNOWN GAP was
        # retracted -- it was the name-normalization bug): theories
        # built with record_theories answer with real source; Main
        # comes from the stock HOL heap built WITHOUT record_theories,
        # so it genuinely has no segments and Isabelle's actionable
        # rebuild hint is the reply.
        reply = client.request("resources/read", {"uri": "isabelle://theory/MCP_Repl/commands"})
        verdict("resources/read isabelle://theory/MCP_Repl/commands -- record_theories session answers",
                "result" in reply and "error" not in reply, json.dumps(reply)[:300])
        reply = client.request("resources/read", {"uri": "isabelle://theory/Main/commands"})
        verdict("resources/read isabelle://theory/Main/commands -- no-record_theories heap names the real reason",
                "No recorded segments" in json.dumps(reply), json.dumps(reply))

        # isabelle://theory/{name}/diagnostics (plans/load_theory,
        # unblocked by wave 2): image tier for the base-image Main,
        # filesystem tier for a name nobody loaded.
        reply = client.request("resources/read", {"uri": "isabelle://theory/Main/diagnostics"})
        contents = reply.get("result", {}).get("contents", [])
        text = contents[0].get("text", "") if contents else ""
        verdict("resources/read isabelle://theory/Main/diagnostics -- image tier",
                "checked at build time" in text, json.dumps(reply))

        reply = client.request("resources/read",
            {"uri": "isabelle://theory/NeverLoadedE2E/diagnostics"})
        contents = reply.get("result", {}).get("contents", [])
        text = contents[0].get("text", "") if contents else ""
        verdict("resources/read isabelle://theory/{name}/diagnostics -- filesystem tier",
                "load_theory to check" in text, json.dumps(reply))

        reply = client.request("tools/call",
            {"name": "repl_remove", "arguments": {"repl": "Res"}})
        verdict("resources: tools/call repl_remove removes Res",
                not reply.get("result", {}).get("isError", False), json.dumps(reply))

        # wave 2 (plans/load_theory, plans/unload_theory, plans/check_theory):
        # load/unload/check through the full JSON-RPC -> Handler ->
        # Builtin_Tool -> MCP_Session.{load,unload,check}_theory path (these
        # three bypass the MCP.ir bridge entirely, unlike every builtin
        # above). check_theory T1's staleness case is the reason
        # check_theory exists at all -- edit the file between two calls and
        # confirm the SECOND call reports the NEW content, not a cached
        # read of the first. The flagship end-to-end chain from
        # plans/check_theory T4 (load -> attach -> prove -> repl_text ->
        # splice -> check_theory ok) needs repl_init_from_source ("attach"),
        # a still-deferred wave-1 tool, so this approximates it: a REPL
        # proves the lemma independently and repl_text extracts the
        # script, which is then spliced into the fixture file exactly as
        # a client would after repl_init_from_source existed.
        # Flatpak Isabelle cannot see the host's private /tmp namespace.
        # Keep real-process fixtures under the shared repository mount.
        fixture_dir = tempfile.mkdtemp(prefix="mcp_wave2_", dir=PROJECT_ROOT)
        try:
            fixture_path = os.path.join(fixture_dir, "Wave2E2E.thy")

            def write_fixture(body):
                # Isabelle's file fingerprint can retain a same-second rewrite.
                # Cross a timestamp second before replacing an existing theory,
                # otherwise the staleness test can read the previous snapshot.
                if os.path.exists(fixture_path):
                    previous_second = os.stat(fixture_path).st_mtime_ns // 1_000_000_000
                    deadline = time.monotonic() + 2
                    while (
                        time.time_ns() // 1_000_000_000 <= previous_second
                        and time.monotonic() < deadline
                    ):
                        time.sleep(0.02)
                    if time.time_ns() // 1_000_000_000 <= previous_second:
                        raise RuntimeError("filesystem timestamp did not advance")
                with open(fixture_path, "w") as f:
                    f.write(
                        "theory Wave2E2E\n  imports Main\nbegin\n\n" + body + "\n\nend\n")
                    f.flush()
                    os.fsync(f.fileno())

            write_fixture("lemma wave2_e2e_good: \"True\" by simp")
            reply = client.request("tools/call", {"name": "load_theory",
                "arguments": {"name": "Wave2E2E", "master_dir": fixture_dir}})
            content = reply.get("result", {}).get("content", [])
            text = content[0].get("text", "") if content else ""
            verdict("wave 2: tools/call load_theory loads a well-formed fixture",
                    "ok" in text and not reply.get("result", {}).get("isError", False),
                    json.dumps(reply))

            reply = client.request("resources/read",
                {"uri": "isabelle://theory/Wave2E2E/diagnostics"})
            contents = reply.get("result", {}).get("contents", [])
            text = contents[0].get("text", "") if contents else ""
            verdict("wave 2: isabelle://theory/Wave2E2E/diagnostics -- loaded tier",
                    "Wave2E2E: ok" in text, json.dumps(reply))

            reply = client.request("resources/read",
                {"uri": "isabelle://theory/Wave2E2E/entities"})
            contents = reply.get("result", {}).get("contents", [])
            text = contents[0].get("text", "") if contents else ""
            verdict("wave 2: isabelle://theory/Wave2E2E/entities -- loaded tier lists the lemma as a fact",
                    "wave2_e2e_good" in text, json.dumps(reply))

            reply = client.request("resources/read", {"uri": "isabelle://theory/HOL.HOL/entities"})
            contents = reply.get("result", {}).get("contents", [])
            text = contents[0].get("text", "") if contents else ""
            verdict("wave 2: isabelle://theory/HOL.HOL/entities -- image tier lists conjI as a fact",
                    "conjI" in text, json.dumps(reply))

            write_fixture("lemma wave2_e2e_bad: \"False\"\n  by simp")
            reply = client.request("tools/call", {"name": "check_theory",
                "arguments": {"name": "Wave2E2E", "master_dir": fixture_dir}})
            content = reply.get("result", {}).get("content", [])
            text = content[0].get("text", "") if content else ""
            verdict("wave 2: tools/call check_theory T1 -- the staleness case: "
                    "a second call reports the on-disk edit, not a cached read",
                    "line" in text and reply.get("result", {}).get("isError", False),
                    json.dumps(reply))

            write_fixture("lemma wave2_e2e_good: \"True\" by simp")
            reply = client.request("tools/call", {"name": "check_theory",
                "arguments": {"name": "Wave2E2E", "master_dir": fixture_dir}})
            verdict("wave 2: tools/call check_theory recovers once the fixture is fixed",
                    not reply.get("result", {}).get("isError", False), json.dumps(reply))

            reply = client.request("tools/call",
                {"name": "unload_theory", "arguments": {"name": "Wave2E2E"}})
            verdict("wave 2: tools/call unload_theory unloads the fixture",
                    not reply.get("result", {}).get("isError", False), json.dumps(reply))

            reply = client.request("tools/call",
                {"name": "unload_theory", "arguments": {"name": "Wave2E2E"}})
            verdict("wave 2: tools/call unload_theory on an already-unloaded theory is isError",
                    reply.get("result", {}).get("isError", False), json.dumps(reply))

            # scope_add/scope_remove/scope_show e2e (plans/scope_add T5,
            # plans/scope_show T4): scope_add's pattern match appears in
            # both resources/list and scope_show, and fires
            # notifications/resources/list_changed; scope_remove reverses
            # all three.
            client.notifications.clear()
            reply = client.request("tools/call", {"name": "scope_add",
                "arguments": {"patterns": ["HOL.Wellfounded"]}})
            verdict("wave 4: tools/call scope_add adds a pattern",
                    not reply.get("result", {}).get("isError", False), json.dumps(reply))
            verdict("wave 4: scope_add fires notifications/resources/list_changed",
                    client.await_notification("notifications/resources/list_changed"),
                    "notifications seen: %s" % json.dumps(client.notifications))

            reply = client.request("resources/list")
            resources = reply.get("result", {}).get("resources", [])
            verdict("wave 4: resources/list reflects the scope_add pattern",
                    any(r.get("uri") == "isabelle://theory/HOL.Wellfounded" for r in resources),
                    json.dumps(resources))

            reply = client.request("tools/call", {"name": "scope_show", "arguments": {}})
            content = reply.get("result", {}).get("content", [])
            text = content[0].get("text", "") if content else ""
            verdict("wave 4: scope_show reflects the scope_add pattern",
                    "HOL.Wellfounded" in text and not reply.get("result", {}).get("isError", False),
                    json.dumps(reply))

            client.notifications.clear()
            reply = client.request("tools/call", {"name": "scope_remove",
                "arguments": {"patterns": ["HOL.Wellfounded"]}})
            verdict("wave 4: tools/call scope_remove removes the pattern",
                    not reply.get("result", {}).get("isError", False), json.dumps(reply))
            verdict("wave 4: scope_remove fires notifications/resources/list_changed",
                    client.await_notification("notifications/resources/list_changed"),
                    "notifications seen: %s" % json.dumps(client.notifications))

            reply = client.request("resources/list")
            resources = reply.get("result", {}).get("resources", [])
            verdict("wave 4: resources/list no longer lists the removed pattern's match",
                    not any(r.get("uri") == "isabelle://theory/HOL.Wellfounded" for r in resources),
                    json.dumps(resources))

            reply = client.request("tools/call", {"name": "scope_show", "arguments": {}})
            content = reply.get("result", {}).get("content", [])
            text = content[0].get("text", "") if content else ""
            verdict("wave 4: scope_show no longer lists the removed pattern",
                    "HOL.Wellfounded" not in text, json.dumps(reply))

            # flagship approximation (plans/check_theory T4): a REPL proves
            # the lemma, repl_text extracts the script, python splices it
            # into a fresh fixture, check_theory confirms it on disk.
            reply = client.request("tools/call",
                {"name": "repl_init", "arguments": {"repl": "Wv2", "theories": ["Main"]}})
            verdict("wave 2: flagship -- tools/call repl_init creates Wv2",
                    not reply.get("result", {}).get("isError", False), json.dumps(reply))

            reply = client.request("tools/call", {"name": "repl_step",
                "arguments": {"repl": "Wv2", "isar_text": "lemma wave2_e2e_spliced: \"1 + 1 = (2::nat)\""}})
            verdict("wave 2: flagship -- tools/call repl_step opens the spliced lemma",
                    not reply.get("result", {}).get("isError", False), json.dumps(reply))

            reply = client.request("tools/call",
                {"name": "repl_step", "arguments": {"repl": "Wv2", "isar_text": "by simp"}})
            verdict("wave 2: flagship -- tools/call repl_step closes the spliced lemma",
                    not reply.get("result", {}).get("isError", False), json.dumps(reply))

            reply = client.request("tools/call", {"name": "repl_text", "arguments": {"repl": "Wv2"}})
            content = reply.get("result", {}).get("content", [])
            script = content[0].get("text", "") if content else ""
            verdict("wave 2: flagship -- tools/call repl_text extracts the proved script",
                    "wave2_e2e_spliced" in script
                    and not reply.get("result", {}).get("isError", False),
                    json.dumps(reply))

            write_fixture(script)
            reply = client.request("tools/call", {"name": "check_theory",
                "arguments": {"name": "Wave2E2E", "master_dir": fixture_dir}})
            verdict("wave 2: flagship -- check_theory ok on the file with the spliced-in proof",
                    not reply.get("result", {}).get("isError", False), json.dumps(reply))

            reply = client.request("tools/call",
                {"name": "repl_remove", "arguments": {"repl": "Wv2"}})
            verdict("wave 2: flagship -- tools/call repl_remove removes Wv2",
                    not reply.get("result", {}).get("isError", False), json.dumps(reply))

            # phase 3 (plans/mcp_tool_command step 5): loading a theory
            # that registers a tool with the mcp_tool command pushes
            # notifications/tools/list_changed (capability declared at
            # initialize; the emit is MCP_Tool.declare's).
            reg_path = os.path.join(fixture_dir, "RegE2E.thy")
            with open(reg_path, "w") as f:
                f.write(
                    'theory RegE2E\n'
                    '  imports Main "MCP-Tools.MCP_Tools"\n'
                    'begin\n\n'
                    'mcp_tool reg_probe = \\<open>fn s => "probe:" ^ s\\<close>\n'
                    '  (description \\<open>a runtime-registered demo tool\\<close>)\n\n'
                    'end\n')
            client.notifications.clear()
            reply = client.request("tools/call", {"name": "load_theory",
                "arguments": {"name": "RegE2E", "master_dir": fixture_dir}})
            verdict("phase 3: load_theory of an mcp_tool-registering theory is ok",
                    not reply.get("result", {}).get("isError", False), json.dumps(reply))
            verdict("phase 3: registration pushed notifications/tools/list_changed",
                    client.await_notification("notifications/tools/list_changed"),
                    "notifications seen: %s" % json.dumps(client.notifications))
        finally:
            shutil.rmtree(fixture_dir, ignore_errors=True)

    finally:
        client.close()


def test_builtin_activation():
    """plans/builtin_activation, step 4: ASYMMETRIC CALLABILITY end to
    end -- a del'd builtin is unlisted but stays callable (dispatch
    precedes activation), unlike a del'd ML tool (unlisted AND
    refused). The REPL's canonical context locator is returned by
    repl_init and treated as opaque by this client; tool_scope_set uses
    that exact value, so every activation change stays local to the REPL
    context rather than touching the shared MCP_Tools theory. This is the
    same style as the tool_scope bridge suite (mcp_bridge_tests.scala).
    """
    client = Client(ISABELLE + ["mcp_server", "-s", "MCP-HOL", "-T", "MCP_Repl"])
    try:
        client.request("initialize", {
            "protocolVersion": "2025-03-26",
            "capabilities": {},
            "clientInfo": {"name": "test_mcp", "version": "0"},
        })
        client.send("notifications/initialized", notification=True)

        # plans/readiness: wait for the backend before the first real
        # tools/call (see test_repl_builtins' identical wait).
        reply = wait_for_ready(client)
        verdict("builtin_activation: server becomes ready within the timeout",
                not reply.get("result", {}).get("isError", False), json.dumps(reply))

        # mcp_tool/declare need a theory that imports MCP_Tools; MCP_Repl
        # does (transitively, via MCP-HOL's MCP theory).
        reply = client.request("tools/call", {"name": "repl_init",
            "arguments": {"repl": "BA", "theories": ["MCP-HOL.MCP_Repl"]}})
        content = reply.get("result", {}).get("content", [])
        init_text = content[0].get("text", "") if content else ""
        locator_match = re.search(r"^Context: ([^\n]+)$", init_text, re.MULTILINE)
        locator = locator_match.group(1) if locator_match else None
        verdict("builtin_activation: repl_init creates BA and returns its canonical context locator",
                locator is not None and not reply.get("result", {}).get("isError", False),
                json.dumps(reply))

        reply = client.request("tools/call", {"name": "repl_step",
            "arguments": {"repl": "BA",
                           "isar_text": "declare [[mcp_tools del: repl_list]]"}})
        verdict("builtin_activation: repl_step deactivates the repl_list builtin mirror",
                not reply.get("result", {}).get("isError", False), json.dumps(reply))

        reply = client.request("tools/call", {"name": "repl_step",
            "arguments": {"repl": "BA",
                           "isar_text":
                               "mcp_tool ba_probe = \\<open>String.map Char.toUpper\\<close>\n"
                               "  (description \\<open>uppercase\\<close>)"}})
        verdict("builtin_activation: repl_step registers an ml tool ba_probe",
                not reply.get("result", {}).get("isError", False), json.dumps(reply))

        reply = client.request("tools/call", {"name": "repl_step",
            "arguments": {"repl": "BA", "isar_text": "declare [[mcp_tools del: ba_probe]]"}})
        verdict("builtin_activation: repl_step deactivates ba_probe",
                not reply.get("result", {}).get("isError", False), json.dumps(reply))

        reply = client.request("tools/call", {"name": "repl_step",
            "arguments": {"repl": "BA",
                          "isar_text":
                              "mcp_tool ba_active_probe = \\<open>String.map Char.toUpper\\<close>\n"
                              "  (description \\<open>active uppercase\\<close>)"}})
        verdict("builtin_activation: repl_step registers an active ml tool ba_active_probe",
                not reply.get("result", {}).get("isError", False), json.dumps(reply))

        reply = client.request("tools/call",
            {"name": "tool_scope_set", "arguments": {"context": locator}})
        verdict("builtin_activation: tool_scope_set reuses repl_init's returned context locator",
                not reply.get("result", {}).get("isError", False), json.dumps(reply))

        reply = client.request("tools/call", {"name": "tool_scope_show", "arguments": {}})
        content = reply.get("result", {}).get("content", [])
        scope_text = content[0].get("text", "") if content else ""
        scope_context = scope_text.splitlines()[0] if scope_text else ""
        verdict("builtin_activation: tool_scope_show reports repl_init's returned context locator",
                locator is not None and scope_context == "Context: " + locator
                and not reply.get("result", {}).get("isError", False),
                json.dumps(reply))

        reply = client.request("tools/list")
        names = [t.get("name") for t in reply.get("result", {}).get("tools", [])]
        verdict("builtin_activation: tools/list has active ba_active_probe but neither deactivated tool",
                "ba_active_probe" in names and "repl_list" not in names and "ba_probe" not in names,
                json.dumps(names))

        reply = client.request("tools/call",
            {"name": "ba_active_probe", "arguments": {"input": "active"}})
        content = reply.get("result", {}).get("content", [])
        active_text = content[0].get("text", "") if content else ""
        verdict("builtin_activation: tools/call ba_active_probe succeeds in the REPL scope",
                active_text == "ACTIVE" and not reply.get("result", {}).get("isError", False),
                json.dumps(reply))

        # ASYMMETRIC CALLABILITY: the del'd BUILTIN stays callable...
        reply = client.request("tools/call", {"name": "repl_list", "arguments": {}})
        verdict("builtin_activation: tools/call repl_list still succeeds though unlisted",
                not reply.get("result", {}).get("isError", False), json.dumps(reply))

        # ...but the del'd ML TOOL is refused: an inactive row drops out
        # of ml_tools() entirely (tools_body serves MCP_Tool.active
        # only), so tools/call's exposed-name resolution cannot map
        # "ba_probe" back to its internal name and it is rejected as
        # undefined -- the exposed-name path's flavor of "unlisted and
        # uncallable" (MCP_Protocol.run_tool's own "Inactive MCP tool"
        # only fires when dispatching by internal name directly,
        # bypassing exposure -- see the ml-unit suite).
        reply = client.request("tools/call", {"name": "ba_probe", "arguments": {"input": "x"}})
        verdict("builtin_activation: tools/call ba_probe is refused (unlisted and uncallable)",
                reply.get("result", {}).get("isError", False) is True,
                json.dumps(reply))

        client.request("tools/call", {"name": "repl_remove", "arguments": {"repl": "BA"}})

    finally:
        client.close()


def main():
    if not ISABELLE or shutil.which(ISABELLE[0]) is None:
        print("FAIL setup -- isabelle command not found: %s" % " ".join(ISABELLE))
        return 1

    client = Client(ISABELLE + ["mcp_server"])
    try:
        # initialize handshake (plans/readiness, spec "server startup and
        # readiness"): must be fast NOW, regardless of session-heap state
        # -- the json-rpc loop is no longer gated on the prover build.
        # Supersedes the mvp's "generous 600s timeout for the first
        # reply", which was exactly the blocking behavior this decision
        # removed; see HANDSHAKE_TIMEOUT's docstring note re: a cold
        # mcp.jar (scala_build), which is unrelated and still slow.
        start = time.monotonic()
        reply = client.request("initialize", {
            "protocolVersion": "2025-03-26",
            "capabilities": {},
            "clientInfo": {"name": "test_mcp", "version": "0"},
        }, timeout=HANDSHAKE_TIMEOUT)
        elapsed = time.monotonic() - start
        result = reply.get("result", {})
        verdict("initialize", "protocolVersion" in result and "serverInfo" in result
                and "tools" in result.get("capabilities", {}),
                json.dumps(reply) if "result" not in reply else "")
        verdict("initialize is fast, not gated on the prover build (%.1fs)" % elapsed,
                elapsed < HANDSHAKE_TIMEOUT, "%.1fs >= %gs timeout" % (elapsed, HANDSHAKE_TIMEOUT))
        client.send("notifications/initialized", notification=True)

        # ping
        reply = client.request("ping")
        verdict("ping", reply.get("result") == {})

        # tools/list: the ML-registered demo tool must be present. Polled,
        # not asserted on the first reply -- that first reply may still be
        # Not_Ready (builtins only, no ML rows; see wait_for_shout) while
        # the session heap builds/boots in the background. This is where
        # the mvp's generous cold-build timeout still applies.
        reply, tools = wait_for_shout(client)
        shout = [t for t in tools if t.get("name") == "shout"]
        verdict("tools/list has shout (polled until the backend is ready)",
                bool(shout), json.dumps(tools))
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
        rc = client.close()
        verdict("exit on stdin close", rc == 0, "rc=%d" % rc)
    finally:
        client.close()

    test_repl_builtins()
    test_builtin_activation()

    print("%d failure(s)" % failures)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
