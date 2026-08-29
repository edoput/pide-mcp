"""POSIX process ownership helpers for the end-to-end harness.

Workers own a session.  Each JSON-RPC client owns a process group inside that
session.  This lets normal client teardown target one server tree while the
outer runner can still recover every subgroup after a hard worker timeout.
"""

from __future__ import annotations

import os
from pathlib import Path
import signal
import time


def _signal_group(process_group: int, value: signal.Signals) -> None:
    try:
        os.killpg(process_group, value)
    except ProcessLookupError:
        pass


def _group_exists(process_group: int) -> bool:
    live_groups = _live_process_groups()
    if live_groups is not None:
        return process_group in live_groups
    try:
        os.killpg(process_group, 0)
        return True
    except ProcessLookupError:
        return False


def _live_process_groups() -> set[int] | None:
    """Return groups with at least one non-zombie process on Linux."""
    proc = Path("/proc")
    if not proc.is_dir():
        return None
    groups: set[int] = set()
    for entry in proc.iterdir():
        if not entry.name.isdigit():
            continue
        try:
            value = (entry / "stat").read_text(encoding="ascii")
            # comm is parenthesized and may itself contain spaces or ')'.
            fields = value[value.rfind(")") + 2 :].split()
            state = fields[0]
            process_group = int(fields[2])
        except (FileNotFoundError, PermissionError, ValueError, IndexError):
            continue
        if state not in {"Z", "X"}:
            groups.add(process_group)
    return groups


def _wait_for_groups(process_groups: set[int], deadline: float) -> set[int]:
    remaining = set(process_groups)
    while remaining and time.monotonic() < deadline:
        remaining = {group for group in remaining if _group_exists(group)}
        if remaining:
            time.sleep(0.02)
    return remaining


def terminate_process_group(process_group: int, grace_seconds: float = 2) -> None:
    """Terminate one client-owned process group, including its descendants."""
    _signal_group(process_group, signal.SIGTERM)
    remaining = _wait_for_groups(
        {process_group}, time.monotonic() + grace_seconds
    )
    for group in remaining:
        _signal_group(group, signal.SIGKILL)


def wait_for_process_exit(process_id: int, timeout_seconds: float) -> bool:
    """Wait for a child to exit without reaping it or releasing its PID/PGID."""
    deadline = time.monotonic() + timeout_seconds
    options = os.WEXITED | os.WNOHANG | os.WNOWAIT
    while True:
        try:
            result = os.waitid(os.P_PID, process_id, options)
        except ChildProcessError as ex:
            raise RuntimeError(
                f"process {process_id} was reaped outside its Client owner"
            ) from ex
        if result is not None and result.si_pid == process_id:
            return True
        if time.monotonic() >= deadline:
            return False
        time.sleep(0.02)


def process_groups_in_session(session_id: int) -> set[int]:
    """Return every live process group belonging to a Linux worker session."""
    groups: set[int] = set()
    proc = Path("/proc")
    if not proc.is_dir():
        return {session_id}
    for entry in proc.iterdir():
        if not entry.name.isdigit():
            continue
        pid = int(entry.name)
        try:
            if os.getsid(pid) == session_id:
                groups.add(os.getpgid(pid))
        except (ProcessLookupError, PermissionError):
            continue
    return groups


def terminate_process_session(session_id: int, grace_seconds: float = 2) -> None:
    """Terminate all process groups still owned by one e2e worker session."""
    groups = process_groups_in_session(session_id)
    # Stop client subgroups before the worker group so the worker cannot create
    # another subgroup between discovery and its own termination.
    for group in sorted(groups, key=lambda value: value == session_id):
        _signal_group(group, signal.SIGTERM)
    remaining = _wait_for_groups(groups, time.monotonic() + grace_seconds)
    # Re-scan after TERM to catch any subgroup created during the first scan.
    new_groups = process_groups_in_session(session_id)
    live_groups = _live_process_groups()
    remaining.update(new_groups if live_groups is None else new_groups & live_groups)
    for group in remaining:
        _signal_group(group, signal.SIGKILL)
