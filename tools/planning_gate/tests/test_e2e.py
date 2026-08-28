from __future__ import annotations

import io
import json
import os
from pathlib import Path
import sys
import time

import pytest

from mcp.test.e2e.client import Client
from mcp.test.e2e.registry import RegistryError, discover_cases, producer
from mcp.test.e2e.runner import ProcessResult, run_cases, run_process, select_cases
from tools.planning_gate.tooling import spec_test


def write_case(root: Path, name: str, source: str) -> Path:
    path = root / f"mcp/test/e2e/cases/case_{name}.py"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(source, encoding="utf-8")
    return path


def write_entrypoint(root: Path, name: str = "mcp/test/entry.py") -> str:
    path = root / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("raise RuntimeError('discovery imported an entrypoint')\n", encoding="utf-8")
    return name


def case_source(
    entrypoint: str,
    *,
    name: str = "fixture case",
    verifies: str = "()",
    covers: str = "('python_e2e#T1',)",
    extra: str = "",
) -> str:
    return f'''from mcp.test.e2e.registry import e2e_test

raise RuntimeError("discovery imported this module")

@e2e_test(
    name={name!r},
    entrypoint={entrypoint!r},
    verifies={verifies},
    covers={covers},
    {extra}
)
def case_fixture():
    raise RuntimeError("discovery evaluated this body")
'''


@spec_test(
    verifies=("python_e2e#A2",),
    covers=("python_e2e#T1", "python_e2e#T2"),
)
def test_e2e_discovery_is_body_free_deterministic_and_strict(tmp_path: Path) -> None:
    entrypoint = write_entrypoint(tmp_path)
    path = write_case(tmp_path, "fixture", case_source(entrypoint))

    first = producer_from_fixture(tmp_path, {entrypoint})
    second = producer_from_fixture(tmp_path, {entrypoint})
    assert json.dumps(first.to_json(), sort_keys=True) == json.dumps(
        second.to_json(), sort_keys=True
    )
    assert first.tests[0].links[0].id == "python_e2e#T1"

    path.write_text(
        case_source(entrypoint, covers="('bad id',)"), encoding="utf-8"
    )
    with pytest.raises(RegistryError, match="malformed covers"):
        discover_cases(tmp_path, required_entrypoints={entrypoint})

    path.write_text(
        case_source(entrypoint, covers="('python_e2e#T1', 'python_e2e#T1')"),
        encoding="utf-8",
    )
    with pytest.raises(RegistryError, match="duplicate covers"):
        discover_cases(tmp_path, required_entrypoints={entrypoint})

    path.write_text(case_source(entrypoint, extra="layer='e2e',"), encoding="utf-8")
    with pytest.raises(RegistryError, match="unknown or missing"):
        discover_cases(tmp_path, required_entrypoints={entrypoint})


def producer_from_fixture(root: Path, required: set[str]):
    """Validate fixture discovery through the common producer contract."""
    from tools.planning_gate.matrix import manifest_from_json

    cases = discover_cases(root, required_entrypoints=required)
    return manifest_from_json(
        {
            "schema": "isabelle-mcp.verification-producer/v1",
            "producer": "isabelle-mcp/python-e2e",
            "artifact": {"fixture": "true"},
            "tests": [
                {
                    "identity": case.identity,
                    "name": case.name,
                    "layer": "e2e",
                    "location": {"path": case.source, "line": case.line},
                    "verifies": list(case.verifies),
                    "covers": list(case.covers),
                }
                for case in cases
            ],
        }
    )


@spec_test(covers=("python_e2e#T1",))
def test_e2e_inventory_rejects_omitted_entrypoints_and_duplicate_names(
    tmp_path: Path,
) -> None:
    first = write_entrypoint(tmp_path, "mcp/test/first.py")
    second = write_entrypoint(tmp_path, "mcp/test/second.py")
    write_case(tmp_path, "first", case_source(first, name="same"))

    with pytest.raises(RegistryError, match="inventory mismatch"):
        discover_cases(tmp_path, required_entrypoints={first, second})

    write_case(tmp_path, "second", case_source(second, name="same"))
    with pytest.raises(RegistryError, match="duplicate e2e case name"):
        discover_cases(tmp_path, required_entrypoints={first, second})


def process_exists(pid: int) -> bool:
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False


def await_gone(pid: int) -> None:
    deadline = time.monotonic() + 3
    while process_exists(pid) and time.monotonic() < deadline:
        time.sleep(0.02)
    assert not process_exists(pid), f"process {pid} survived runner cleanup"


@spec_test(
    verifies=("python_e2e#I1",),
    covers=("python_e2e#T3",),
)
def test_e2e_process_runner_propagates_failure_timeout_and_cleans_descendants(
    tmp_path: Path,
) -> None:
    failed = run_process(
        [sys.executable, "-c", "raise AssertionError('case failed')"], tmp_path, 5
    )
    assert failed.returncode != 0
    assert "AssertionError" in failed.stderr

    script = (
        "import subprocess,sys,time; "
        "p=subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)'], "
        "stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL); "
        "print(p.pid, flush=True); time.sleep(60)"
    )
    timed_out = run_process([sys.executable, "-c", script], tmp_path, 0.2)
    assert timed_out.timed_out
    await_gone(int(timed_out.stdout.strip()))


@spec_test(verifies=("python_e2e#I1",))
def test_shared_e2e_client_correlates_reply_and_notification(tmp_path: Path) -> None:
    server = r'''import json, sys
for line in sys.stdin:
    request = json.loads(line)
    print(json.dumps({"jsonrpc":"2.0","method":"notifications/tools/list_changed"}), flush=True)
    print(json.dumps({"jsonrpc":"2.0","id":request["id"],"result":{"ok":True}}), flush=True)
'''
    client = Client([sys.executable, "-c", server])
    try:
        reply = client.request("fixture", timeout=2)
        assert reply["result"] == {"ok": True}
        assert client.notifications[0]["method"] == "notifications/tools/list_changed"
    finally:
        assert client.close() == 0

    root = Path(__file__).resolve().parents[3]
    assert "from mcp.test.e2e.client import Client" in (
        root / "mcp/test/test_mcp.py"
    ).read_text(encoding="utf-8")
    assert "from mcp.test.e2e.client import Client as JsonRpcClient" in (
        root / "mcp/test/repro_duplicate_session.py"
    ).read_text(encoding="utf-8")


@spec_test(verifies=("python_e2e#I1",))
def test_shared_e2e_client_correlates_out_of_order_replies_and_batches() -> None:
    server = r'''import json, sys
first = json.loads(sys.stdin.readline())
second = json.loads(sys.stdin.readline())
print(json.dumps({"jsonrpc":"2.0","id":second["id"],"result":{"order":2}}), flush=True)
print(json.dumps({"jsonrpc":"2.0","id":first["id"],"result":{"order":1}}), flush=True)
batch = json.loads(sys.stdin.readline())
print(json.dumps([{"jsonrpc":"2.0","id":value["id"],"result":{}} for value in batch]), flush=True)
'''
    client = Client([sys.executable, "-c", server])
    try:
        first = client.send("first")
        second = client.send("second")
        assert first is not None and second is not None
        assert client.await_reply(first, timeout=2)["result"] == {"order": 1}
        assert client.await_reply(second, timeout=2)["result"] == {"order": 2}

        client.send_json(
            [
                {"jsonrpc": "2.0", "id": "batch-a", "method": "ping"},
                {"jsonrpc": "2.0", "id": "batch-b", "method": "ping"},
            ]
        )
        batch = client.recv_json(timeout=2)
        assert isinstance(batch, list)
        assert {value["id"] for value in batch} == {"batch-a", "batch-b"}
    finally:
        assert client.close() == 0


@spec_test(covers=("python_e2e#T3",))
def test_e2e_process_runner_cleans_lingering_child_after_success(tmp_path: Path) -> None:
    script = (
        "import subprocess,sys; "
        "p=subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)'], "
        "stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL); "
        "print(p.pid, flush=True)"
    )
    completed = run_process([sys.executable, "-c", script], tmp_path, 5)
    assert completed.returncode == 0
    await_gone(int(completed.stdout.strip()))


@spec_test(covers=("python_e2e#T3",))
def test_e2e_process_runner_propagates_interrupt(monkeypatch: pytest.MonkeyPatch) -> None:
    import mcp.test.e2e.runner as runner

    events: list[str] = []

    class InterruptingProcess:
        pid = 12345
        returncode = None

        def communicate(self, timeout=None):
            events.append(f"communicate:{timeout}")
            if timeout is not None:
                raise KeyboardInterrupt
            return "", ""

    monkeypatch.setattr(runner.subprocess, "Popen", lambda *args, **kwargs: InterruptingProcess())
    monkeypatch.setattr(runner, "_terminate_group", lambda process: events.append("terminate"))

    with pytest.raises(KeyboardInterrupt):
        runner.run_process(["fixture"], Path("."), 1)
    assert events == ["communicate:1", "terminate", "communicate:None"]


@spec_test(covers=("python_e2e#T6",))
def test_filtered_e2e_run_is_prominently_diagnostic(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    entrypoint = write_entrypoint(tmp_path)
    write_case(tmp_path, "fixture", case_source(entrypoint))
    cases = discover_cases(tmp_path, required_entrypoints={entrypoint})
    selected = select_cases(cases, "python_e2e#T1")
    monkeypatch.setattr(
        "mcp.test.e2e.runner.run_process",
        lambda argv, cwd, timeout, **kwargs: ProcessResult(0, "", "", False),
    )
    output = io.StringIO()

    assert run_cases(tmp_path, selected, filtered=True, stream=output) == 0
    assert "DIAGNOSTIC FILTERED RUN: not completion evidence" in output.getvalue()
