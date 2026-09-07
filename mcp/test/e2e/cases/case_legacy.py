"""Registered adapters for the maintained real-process workflows."""

from __future__ import annotations

from mcp.test.e2e.registry import e2e_test


@e2e_test(
    name="public protocol, tools, resources, and REPL workflows",
    entrypoint="mcp/test/test_mcp.py",
    verifies=("python_e2e#A1",),
    covers=(
        "check_theory#T4",
        "context_locator#T5",
        "context_locator#T7",
        "find_theorems#T5",
        "load_theory#T6",
        "planning_gate#T8",
        "python_e2e#T4",
        "repl_back#T3",
        "repl_edit#T5",
        "repl_fork#T6",
        "repl_init#T5",
        "repl_init#T7",
        "repl_list#T3",
        "repl_merge#T5",
        "repl_pin#T5",
        "repl_rebase#T5",
        "repl_remove#T5",
        "repl_replay#T5",
        "repl_show#T3",
        "repl_state#T3",
        "repl_step#T6",
        "repl_text#T3",
        "repl_truncate#T6",
        "scope_add#T5",
        "scope_remove#T4",
        "scope_show#T4",
        "sledgehammer#T6",
        "unload_theory#T5",
    ),
    timeout_seconds=3600,
)
def case_public_protocol() -> int:
    from mcp.test import test_mcp

    test_mcp.failures = 0
    return test_mcp.main()


@e2e_test(
    name="concurrent serve-loop regression",
    entrypoint="mcp/test/repro_concurrent_serve.py",
    covers=("python_e2e#T5",),
    timeout_seconds=1800,
)
def case_concurrent_serve() -> int:
    from mcp.test import repro_concurrent_serve
    from mcp.test import test_mcp

    test_mcp.failures = 0
    return repro_concurrent_serve.main()


@e2e_test(
    name="duplicate session-directory startup regression",
    entrypoint="mcp/test/repro_duplicate_session.py",
    covers=("python_e2e#T5",),
    timeout_seconds=1800,
)
def case_duplicate_session() -> int:
    from mcp.test import repro_duplicate_session

    return repro_duplicate_session.main()
