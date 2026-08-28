"""Registered real-process cases for plans/connection_kernel."""

from __future__ import annotations

from mcp.test.e2e.registry import e2e_test

@e2e_test(
    name="connection lifecycle, single-client ownership, and backend readiness",
    entrypoint="mcp/test/repro_connection_kernel.py",
    verifies=("connection_kernel#A2",),
    covers=("connection_kernel#T2", "connection_kernel#T9"),
    timeout_seconds=900,
)
def case_lifecycle_readiness() -> int:
    from mcp.test import repro_connection_kernel

    return repro_connection_kernel.run_lifecycle_readiness()


@e2e_test(
    name="connection cancellation under saturation and JSON-RPC batches",
    entrypoint="mcp/test/repro_connection_kernel.py",
    covers=("connection_kernel#T4", "connection_kernel#T10"),
    timeout_seconds=900,
)
def case_cancellation_batches() -> int:
    from mcp.test import repro_connection_kernel

    return repro_connection_kernel.run_cancellation_and_batches()


@e2e_test(
    name="connection EOF drain for zero, one, many, and deadline cancellation",
    entrypoint="mcp/test/repro_connection_kernel.py",
    covers=("connection_kernel#T7",),
    timeout_seconds=900,
)
def case_eof_drain() -> int:
    from mcp.test import repro_connection_kernel

    return repro_connection_kernel.run_eof_drain()
