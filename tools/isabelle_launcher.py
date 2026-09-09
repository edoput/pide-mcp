"""Resolve the Isabelle command prefix without invoking a shell.

``ISABELLE`` is intentionally a shell-quoted *prefix*: it can name either an
executable or an adapter followed by its fixed arguments.  This module never
expands variables, evaluates shell syntax, or probes alternative installations.
The normalized argv is propagated to child Python workers through an internal
environment value so a parent run selects the installation exactly once.
"""

from __future__ import annotations

from dataclasses import dataclass
import json
import os
import shlex
import shutil
from typing import Mapping


DEFAULT_ISABELLE = ("isabelle",)
RESOLVED_ENV = "ISABELLE_RESOLVED_ARGV"
RESOLVED_FROM_ENV = "ISABELLE_RESOLVED_FROM"


class LauncherError(ValueError):
    """The explicit Isabelle selection is malformed."""


@dataclass(frozen=True)
class Launcher:
    argv: tuple[str, ...]
    source: str

    def child_environment(self, environment: Mapping[str, str] | None = None) -> dict[str, str]:
        result = dict(os.environ if environment is None else environment)
        result[RESOLVED_ENV] = json.dumps(self.argv, ensure_ascii=False)
        result[RESOLVED_FROM_ENV] = json.dumps(result.get("ISABELLE"))
        return result


def _checked(argv: tuple[str, ...], source: str) -> Launcher:
    if not argv or not argv[0] or any("\x00" in value for value in argv):
        raise LauncherError(f"{source} must name a non-empty executable without NUL bytes")
    return Launcher(argv, source)


def _resolved(value: str) -> Launcher:
    try:
        decoded = json.loads(value)
    except json.JSONDecodeError as ex:
        raise LauncherError(f"{RESOLVED_ENV} is not valid JSON: {ex.msg}") from ex
    if not isinstance(decoded, list) or not all(isinstance(item, str) for item in decoded):
        raise LauncherError(f"{RESOLVED_ENV} must be a JSON array of strings")
    return _checked(tuple(decoded), RESOLVED_ENV)


def resolve_launcher(environment: Mapping[str, str] | None = None) -> Launcher:
    """Return the selected command prefix, parsing an explicit choice once."""
    environment = os.environ if environment is None else environment
    inherited = environment.get(RESOLVED_ENV)
    configured = environment.get("ISABELLE")
    if inherited is not None and json.dumps(configured) == environment.get(RESOLVED_FROM_ENV):
        return _resolved(inherited)
    if configured is None:
        return Launcher((shutil.which("isabelle", path=environment.get("PATH")) or "isabelle",), "default PATH command")
    try:
        launcher = _checked(tuple(shlex.split(configured)), "ISABELLE")
        executable = shutil.which(launcher.argv[0], path=environment.get("PATH"))
        return Launcher((executable or launcher.argv[0], *launcher.argv[1:]), launcher.source)
    except ValueError as ex:
        raise LauncherError(f"cannot parse ISABELLE as an argument vector: {ex}") from ex
