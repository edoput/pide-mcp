from __future__ import annotations

import json
from pathlib import Path
import shlex

import pytest

from tools.isabelle_launcher import Launcher
from tools.isabelle_worktree import State, WorktreeError, selected_installation


@pytest.fixture
def environment(tmp_path):
    worktree = tmp_path / 'checkout with spaces'
    (worktree / 'mcp').mkdir(parents=True)
    (worktree / 'mcp_test').mkdir()
    system = tmp_path / 'system heaps'
    base = system / 'polyml-fixture_platform'
    (base / 'log').mkdir(parents=True)
    for name in ('Pure', 'HOL', 'log/Pure.db', 'log/HOL.db'):
        (base / name).write_text('seed ' + name)
    poly = tmp_path / 'poly' / 'platform'
    poly.mkdir(parents=True)
    (poly / 'poly').write_text('runtime one')
    fake = tmp_path / 'isabelle'
    fake.write_text('''#!/usr/bin/env bash
set -eu
export ISABELLE_HOME='fixture-installation'
export ML_SYSTEM=polyml-fixture
export ISABELLE_HEAPS=''' + shlex.quote(str(tmp_path / 'missing user heaps')) + '''
export ISABELLE_HEAPS_SYSTEM=''' + shlex.quote(str(system)) + '''
export POLYML_HOME=''' + shlex.quote(str(poly.parent)) + '''
export ISABELLE_IDENTIFIER=${ISABELLE_IDENTIFIER:-fixture}
case $1 in
  env) shift; exec env "$@" ;;
  version) echo Fixture ;;
  getenv) printf '%s\\n' "$USER_HOME/.isabelle/$ISABELLE_IDENTIFIER" ;;
  build) exit 0 ;;
  *) exit 9 ;;
esac
''')
    fake.chmod(0o755)
    return worktree, Launcher((str(fake),), 'fixture'), base, poly


def test_system_only_seeds_are_discovered_and_copied_privately(environment):
    worktree, launcher, base, _ = environment
    state = State(worktree, launcher)
    with state.locked():
        state.setup()
        config = (state.home / 'etc/components').read_text().splitlines()
        assert config == [str(worktree / 'mcp'), str(worktree / 'mcp_test')]
        private = state.home / 'heaps' / base.name
        assert (private / 'HOL').read_bytes() == (base / 'HOL').read_bytes()
        assert not (private / 'HOL').is_symlink()
        (private / 'log/HOL.db').write_text('private changes')
        assert (base / 'log/HOL.db').read_text() == 'seed log/HOL.db'


def test_changed_runtime_is_rejected_before_reusing_state(environment):
    worktree, launcher, _, poly = environment
    state = State(worktree, launcher)
    with state.locked():
        state.setup()
        (poly / 'poly').write_text('runtime two')
        with pytest.raises(WorktreeError, match='changed'):
            state.setup()


def test_symlinked_state_ancestor_cannot_redirect_catalog_write(environment, tmp_path):
    worktree, launcher, _, _ = environment
    state = State(worktree, launcher)
    outside = tmp_path / 'outside'
    outside.mkdir()
    with state.locked():
        state.user.symlink_to(outside, target_is_directory=True)
        with pytest.raises(WorktreeError, match='real directory'):
            state.setup()
    assert not list(outside.iterdir())


def test_another_owner_cannot_teardown_state(environment):
    worktree, launcher, _, _ = environment
    state = State(worktree, launcher)
    with state.locked():
        (state.state / 'owner.json').write_text(json.dumps({'worktree': '/other'}))
        with pytest.raises(WorktreeError, match='mismatched'):
            state.teardown()


def test_missing_base_database_is_an_actionable_error(environment):
    _, launcher, base, _ = environment
    (base / 'log/HOL.db').unlink()
    with pytest.raises(WorktreeError, match='build base sessions explicitly'):
        selected_installation(launcher)
