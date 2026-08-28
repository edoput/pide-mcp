"""Isolated end-to-end execution with process-group cleanup."""

from __future__ import annotations

from concurrent.futures import Future, ThreadPoolExecutor
from dataclasses import dataclass
import os
from pathlib import Path
import signal
import subprocess
import sys
import threading
import time
import traceback
from typing import Callable, Iterable, TextIO

from .registry import CaseDefinition, RegistryError, load_runtime_case


@dataclass(frozen=True)
class ProcessResult:
    returncode: int
    stdout: str
    stderr: str
    timed_out: bool


class ProcessSupervisor:
    """Connection-runner ownership of every currently active worker group."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._active: set[subprocess.Popen[str]] = set()

    def add(self, process: subprocess.Popen[str]) -> None:
        with self._lock:
            self._active.add(process)

    def discard(self, process: subprocess.Popen[str]) -> None:
        with self._lock:
            self._active.discard(process)

    def terminate_all(self) -> None:
        with self._lock:
            active = tuple(self._active)
        for process in active:
            _terminate_group(process)


def _terminate_group(process: subprocess.Popen[str], grace_seconds: float = 2) -> None:
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    deadline = time.monotonic() + grace_seconds
    while time.monotonic() < deadline:
        try:
            os.killpg(process.pid, 0)
        except ProcessLookupError:
            return
        time.sleep(0.02)
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass


def run_process(
    argv: list[str],
    cwd: Path,
    timeout_seconds: float,
    line_sink: Callable[[str, str], None] | None = None,
    supervisor: ProcessSupervisor | None = None,
) -> ProcessResult:
    process = subprocess.Popen(
        argv,
        cwd=cwd,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        start_new_session=True,
    )
    if supervisor is not None:
        supervisor.add(process)
    try:
        if line_sink is None:
            try:
                stdout, stderr = process.communicate(timeout=timeout_seconds)
                # A successful worker can still leave a prover descendant behind.
                _terminate_group(process)
                return ProcessResult(process.returncode, stdout, stderr, False)
            except subprocess.TimeoutExpired:
                _terminate_group(process)
                stdout, stderr = process.communicate()
                return ProcessResult(process.returncode, stdout, stderr, True)
            except KeyboardInterrupt:
                _terminate_group(process)
                process.communicate()
                raise

        stdout_lines: list[str] = []
        stderr_lines: list[str] = []
        sink_lock = threading.Lock()

        def drain(channel: str, pipe, output: list[str]) -> None:
            for line in pipe:
                output.append(line)
                with sink_lock:
                    line_sink(channel, line.rstrip("\n"))

        assert process.stdout is not None and process.stderr is not None
        readers = [
            threading.Thread(target=drain, args=("stdout", process.stdout, stdout_lines)),
            threading.Thread(target=drain, args=("stderr", process.stderr, stderr_lines)),
        ]
        for reader in readers:
            reader.start()
        timed_out = False
        try:
            process.wait(timeout=timeout_seconds)
        except subprocess.TimeoutExpired:
            timed_out = True
            _terminate_group(process)
            process.wait()
        except KeyboardInterrupt:
            _terminate_group(process)
            process.wait()
            raise
        finally:
            # Also remove a descendant left behind by a worker that exited cleanly.
            _terminate_group(process)
            for reader in readers:
                reader.join()
        return ProcessResult(
            process.returncode,
            "".join(stdout_lines),
            "".join(stderr_lines),
            timed_out,
        )
    finally:
        if supervisor is not None:
            supervisor.discard(process)


def select_cases(
    cases: Iterable[CaseDefinition], pattern: str | None
) -> tuple[CaseDefinition, ...]:
    cases = tuple(cases)
    if pattern is None:
        return cases
    selected = tuple(
        case
        for case in cases
        if pattern in case.name
        or pattern in case.identity
        or any(pattern in link for link in (*case.verifies, *case.covers))
    )
    if not selected:
        raise RegistryError(f"no e2e case matches {pattern!r}")
    return selected


def run_cases(
    root: Path,
    cases: Iterable[CaseDefinition],
    *,
    filtered: bool,
    jobs: int = 1,
    verbose: bool = False,
    stream: TextIO = sys.stdout,
) -> int:
    cases = tuple(cases)
    if jobs <= 0:
        raise RegistryError("e2e jobs must be positive")
    if filtered:
        print(
            "DIAGNOSTIC FILTERED RUN: not completion evidence",
            file=stream,
            flush=True,
        )
    print(
        f"e2e: running {len(cases)} case(s) with {min(jobs, len(cases))} worker(s)",
        file=stream,
        flush=True,
    )
    output_lock = threading.Lock()
    supervisor = ProcessSupervisor()

    def run(case: CaseDefinition) -> ProcessResult:
        prefix = f"[e2e:{case.function}]"

        def show(channel: str, line: str) -> None:
            marker = " !" if channel == "stderr" else ""
            with output_lock:
                print(f"{prefix}{marker} {line}", file=stream, flush=True)

        return run_process(
            [sys.executable, "-u", "-m", "mcp.test.e2e", "_worker", case.identity],
            root,
            case.timeout_seconds,
            line_sink=show if verbose else None,
            supervisor=supervisor,
        )

    executor = ThreadPoolExecutor(max_workers=min(jobs, len(cases)) or 1)
    futures: list[Future[ProcessResult]] = [executor.submit(run, case) for case in cases]
    try:
        results = [future.result() for future in futures]
    except BaseException:
        supervisor.terminate_all()
        for future in futures:
            future.cancel()
        executor.shutdown(wait=True, cancel_futures=True)
        raise
    else:
        executor.shutdown(wait=True)

    failures = 0
    for case, result in zip(cases, results):
        passed = result.returncode == 0 and not result.timed_out
        detail = "timeout" if result.timed_out else f"exit {result.returncode}"
        if not passed and not verbose:
            prefix = f"[e2e:{case.function}]"
            for line in result.stdout.splitlines():
                print(f"{prefix} {line}", file=stream, flush=True)
            for line in result.stderr.splitlines():
                print(f"{prefix} ! {line}", file=stream, flush=True)
        print(
            f"{'PASS' if passed else 'FAIL'} e2e {case.name} ({detail})",
            file=stream,
            flush=True,
        )
        failures += int(not passed)
    print(
        f"e2e: {len(cases) - failures} passed; {failures} failed",
        file=stream,
        flush=True,
    )
    return 1 if failures else 0


def execute_worker(identity: str) -> int:
    try:
        _, function = load_runtime_case(identity)
        result = function()
        if result is None:
            return 0
        if type(result) is not int:
            raise RegistryError(f"{identity} returned non-integer {result!r}")
        return result
    except BaseException:  # worker must turn every case failure into a nonzero result
        traceback.print_exc()
        return 1
