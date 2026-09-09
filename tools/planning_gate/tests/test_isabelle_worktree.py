from __future__ import annotations

import json
from pathlib import Path
import shlex

import pytest

from tools.isabelle_launcher import Launcher
from tools.isabelle_worktree import State, WorktreeError


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
  build) [[ ! -e "$ISABELLE_HEAPS_SYSTEM/fail-check" ]] ;;
  *) exit 9 ;;
esac
''')
    fake.chmod(0o755)
    return worktree, Launcher((str(fake),), 'fixture'), base, poly


def test_setup_registers_only_checkout_components_without_copying_heaps(environment):
    worktree, launcher, base, _ = environment
    state = State(worktree, launcher)
    before = {p: p.read_bytes() for p in base.rglob('*') if p.is_file()}
    with state.locked():
        state.setup()
        assert (state.home / 'etc/components').read_text().splitlines() == [
            str(worktree / 'mcp'), str(worktree / 'mcp_test')]
        assert list((state.home / 'heaps').iterdir()) == []
        assert (state.home / 'etc/preferences').read_text() == 'system_heaps = false\n'
    assert all(p.read_bytes() == content for p, content in before.items())


def test_native_heap_validation_failure_is_propagated(environment):
    worktree, launcher, base, _ = environment
    (base.parent / 'fail-check').touch()
    state = State(worktree, launcher)
    with state.locked(), pytest.raises(WorktreeError, match='build failed'):
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


def test_symlinked_preferences_cannot_redirect_write(environment, tmp_path):
    worktree, launcher, _, _ = environment
    state = State(worktree, launcher)
    outside = tmp_path / 'preferences'
    outside.write_text('untouched')
    with state.locked():
        (state.home / 'etc').mkdir(parents=True)
        (state.home / 'etc/preferences').symlink_to(outside)
        with pytest.raises(WorktreeError, match='symlinked'):
            state.setup()
    assert outside.read_text() == 'untouched'
