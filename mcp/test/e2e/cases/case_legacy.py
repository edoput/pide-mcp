"""Registered adapters for the maintained real-process workflows."""

from __future__ import annotations

from mcp.test.e2e.registry import e2e_test


@e2e_test(
    name="public protocol, retained tools, and context execution",
    entrypoint="mcp/test/test_mcp.py",
    verifies=("python_e2e#A1",),
    covers=(
        "context_locator#T5",
        "context_locator#T6",
        "load_theory#T6",
        "load_theory#T10",
        "planning_gate#T8",
        "python_e2e#T4",
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
