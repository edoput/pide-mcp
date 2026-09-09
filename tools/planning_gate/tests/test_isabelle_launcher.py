from __future__ import annotations

import json

import pytest

from tools.isabelle_launcher import LauncherError, RESOLVED_ENV, RESOLVED_FROM_ENV, resolve_launcher


def test_launcher_parses_spaces_and_preserves_fixed_empty_arguments() -> None:
    launcher = resolve_launcher({"ISABELLE": '"/tmp/a path/isabelle" --label ""'})
    assert launcher.argv == ("/tmp/a path/isabelle", "--label", "")


@pytest.mark.parametrize("value", ("", "'unterminated", "isabelle \u0000bad"))
def test_invalid_explicit_launcher_fails_without_fallback(value: str) -> None:
    with pytest.raises(LauncherError):
        resolve_launcher({"ISABELLE": value})


def test_child_uses_parent_resolution_but_honors_a_new_explicit_choice() -> None:
    parent = resolve_launcher({"ISABELLE": "adapter --fixed"})
    child = parent.child_environment({"ISABELLE": "adapter --fixed"})
    assert resolve_launcher(child).argv == ("adapter", "--fixed")
    child["ISABELLE"] = "other-adapter"
    assert resolve_launcher(child).argv == ("other-adapter",)


def test_malformed_propagated_resolution_is_rejected() -> None:
    with pytest.raises(LauncherError):
        resolve_launcher({RESOLVED_ENV: json.dumps(["adapter", 1]), RESOLVED_FROM_ENV: "null"})


def test_exec_entrypoint_preserves_arguments_and_exit_status(tmp_path) -> None:
    import os
    from pathlib import Path
    import shlex
    import subprocess
    import sys

    fake = tmp_path / 'an isabelle executable'
    fake.write_text('#!' + sys.executable + '\nimport json,sys\nprint(json.dumps(sys.argv[1:]))\nsys.exit(7)\n')
    fake.chmod(0o755)
    env = dict(os.environ, ISABELLE=shlex.join([str(fake), '--fixed', '']))
    result = subprocess.run(
        [str(Path(__file__).resolve().parents[2] / 'isabelle'), 'a b', '$(literal)'],
        cwd=tmp_path, env=env, text=True, capture_output=True,
    )
    assert result.returncode == 7
    assert json.loads(result.stdout) == ['--fixed', '', 'a b', '$(literal)']
    assert result.stderr == ''


def test_gate_passes_resolved_selection_to_e2e_workers() -> None:
    from pathlib import Path
    from tools.planning_gate.commands import registered_commands

    steps = registered_commands(Path(__file__).resolve().parents[3], environment={'ISABELLE': 'adapter --arg'})
    assert resolve_launcher(steps['e2e'].environment).argv == steps['scala-build'].argv[:-1]


def test_explicit_empty_choice_is_not_masked_by_inherited_default() -> None:
    environment = resolve_launcher({}).child_environment({})
    environment['ISABELLE'] = ''
    with pytest.raises(LauncherError):
        resolve_launcher(environment)
