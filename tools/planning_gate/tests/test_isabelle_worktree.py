from __future__ import annotations

import json
from pathlib import Path
import shlex
import subprocess

import pytest

from tools import isabelle_worktree
from tools.isabelle_launcher import Launcher
from tools.isabelle_worktree import State, WorktreeError


@pytest.fixture
def environment(tmp_path):
    worktree = tmp_path / 'checkout with spaces'
    (worktree / 'mcp').mkdir(parents=True)
    (worktree / 'mcp_test').mkdir()
    subprocess.run(['git', 'init', '-q', str(worktree)], check=True)
    system = tmp_path / 'system heaps'
    user = tmp_path / 'user heaps'
    user.mkdir()
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
export ISABELLE_HEAPS=''' + shlex.quote(str(user)) + '''
export ISABELLE_HEAPS_SYSTEM=''' + shlex.quote(str(system)) + '''
export POLYML_HOME=''' + shlex.quote(str(poly.parent)) + '''
export ISABELLE_IDENTIFIER=${ISABELLE_IDENTIFIER:-fixture}
case $1 in
  env) shift; exec env "$@" ;;
  version) echo Fixture ;;
  getenv)
    case $3 in
      ISABELLE_HEAPS) printf '%s\\n' "$ISABELLE_HEAPS" ;;
      ISABELLE_HEAPS_SYSTEM) printf '%s\\n' "$ISABELLE_HEAPS_SYSTEM" ;;
      ISABELLE_HOME) printf '%s\\n' "$ISABELLE_HOME" ;;
      ML_SYSTEM) printf '%s\\n' "$ML_SYSTEM" ;;
      ISABELLE_IDENTIFIER) printf '%s\\n' "$ISABELLE_IDENTIFIER" ;;
      ISABELLE_HOME_USER) printf '%s\\n' "$USER_HOME/.isabelle/$ISABELLE_IDENTIFIER" ;;
      *) exit 8 ;;
    esac ;;
  build)
    settings="$USER_HOME/.isabelle/$ISABELLE_IDENTIFIER/etc/settings"
    . "$settings"
    root="$ISABELLE_HEAPS_SYSTEM"
    [[ ! -e "$root/fail-check" ]] ;;
  *) exit 9 ;;
esac
''')
    fake.chmod(0o755)
    return worktree, Launcher((str(fake),), 'fixture'), user, base, poly


def test_setup_registers_only_checkout_components_without_copying_heaps(environment):
    worktree, launcher, user, base, _ = environment
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
    worktree, launcher, user, base, _ = environment
    (user / 'fail-check').touch()
    (base.parent / 'fail-check').touch()
    state = State(worktree, launcher)
    with state.locked(), pytest.raises(WorktreeError, match='build failed'):
        state.setup()


def test_symlinked_state_ancestor_cannot_redirect_catalog_write(environment, tmp_path):
    worktree, launcher, _, _, _ = environment
    state = State(worktree, launcher)
    outside = tmp_path / 'outside'
    outside.mkdir()
    with state.locked():
        state.user.symlink_to(outside, target_is_directory=True)
        with pytest.raises(WorktreeError, match='real directory'):
            state.setup()
    assert not list(outside.iterdir())


def test_another_owner_cannot_teardown_state(environment):
    worktree, launcher, _, _, _ = environment
    state = State(worktree, launcher)
    with state.locked():
        (state.state / 'owner.json').write_text(json.dumps({'worktree': '/other'}))
        with pytest.raises(WorktreeError, match='mismatched'):
            state.teardown()


def test_symlinked_preferences_cannot_redirect_write(environment, tmp_path):
    worktree, launcher, _, _, _ = environment
    state = State(worktree, launcher)
    outside = tmp_path / 'preferences'
    outside.write_text('untouched')
    with state.locked():
        (state.home / 'etc').mkdir(parents=True)
        (state.home / 'etc/preferences').symlink_to(outside)
        with pytest.raises(WorktreeError, match='symlinked'):
            state.setup()
    assert outside.read_text() == 'untouched'


def test_explicit_base_root_is_quoted_as_data(environment):
    worktree, launcher, _, _, _ = environment
    base_root = "/installation/base heaps/$(literal)"
    state = State(worktree, launcher, base_heaps=base_root)
    with state.locked():
        state.setup()
        assignment = (state.home / 'etc/settings').read_text().strip()
        assert shlex.split(assignment) == ['ISABELLE_HEAPS_SYSTEM=' + base_root]


def test_automatic_selection_falls_back_from_user_to_system_root(environment):
    worktree, launcher, user, base, _ = environment
    (user / 'fail-check').touch()
    state = State(worktree, launcher)
    with state.locked():
        state.setup()
        assert json.loads(state.selection.read_text())['root'] == str(base.parent)


def test_explicit_base_root_does_not_fall_back_to_installation_roots(environment):
    worktree, launcher, user, _, _ = environment
    explicit = user / 'explicit'
    explicit.mkdir()
    (explicit / 'fail-check').touch()
    state = State(worktree, launcher, base_heaps=str(explicit))
    with state.locked(), pytest.raises(WorktreeError, match='no usable base heaps'):
        state.setup()
    assert not state.selection.exists()


def test_saved_root_is_reused_and_failure_does_not_fall_back(environment):
    worktree, launcher, user, _, _ = environment
    state = State(worktree, launcher)
    with state.locked():
        state.setup()
        (user / 'fail-check').touch()
        with pytest.raises(WorktreeError, match=str(user)):
            state.setup()
        assert json.loads(state.selection.read_text())['root'] == str(user)


def test_symlinked_saved_selection_is_rejected(environment, tmp_path):
    worktree, launcher, _, _, _ = environment
    state = State(worktree, launcher)
    outside = tmp_path / 'selection'
    outside.write_text('{}')
    with state.locked():
        state.selection.symlink_to(outside)
        with pytest.raises(WorktreeError, match='symlinked'):
            state.setup()
    assert outside.read_text() == '{}'


def test_changed_installation_invalidates_saved_root(environment):
    worktree, launcher, user, base, _ = environment
    state = State(worktree, launcher)
    with state.locked():
        state.setup()
        saved = json.loads(state.selection.read_text())
        saved['installation']['argv'] = ['other-isabelle']
        state.selection.write_text(json.dumps(saved))
        (user / 'fail-check').touch()
        state.setup()
        assert json.loads(state.selection.read_text())['root'] == str(base.parent)


def test_main_discovers_checkout_from_current_directory(environment, monkeypatch):
    worktree, launcher, _, _, _ = environment
    monkeypatch.chdir(worktree / 'mcp')
    monkeypatch.setattr(isabelle_worktree, 'resolve_launcher', lambda: launcher)
    assert isabelle_worktree.main(['setup']) == 0
