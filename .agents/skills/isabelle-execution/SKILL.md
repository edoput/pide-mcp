---
name: isabelle-execution
description: Run, build, test, or diagnose Isabelle MCP in an explicitly selected checkout and installation, including host Flatpak and private worktree state.
---

# Running Isabelle tools

All repository scripts select `ISABELLE` when it is set, otherwise `isabelle`
on PATH. `ISABELLE` is a shell-quoted command prefix, parsed into arguments;
it is not evaluated as shell code. Selection never falls back to another
installation after an error. `ISABELLE_TOOL` is reserved for Isabelle's own
internal setting and is no longer an external project override.

```sh
# Native installation on PATH:
tools/isabelle version

# Unpacked installation, including an executable path containing spaces:
ISABELLE='"/opt/Isabelle Distribution/bin/isabelle"' tools/planning-gate done

# Adapter installed by the desktop-container provisioning:
ISABELLE=/usr/local/bin/isabelle tools/planning-gate done
```

The current code requires Isabelle2025-2. Launcher portability does not imply
compatibility with other Isabelle API versions. Python launch helpers use the
standard library; the planning gate still requires its documented `.venv`.

`tools/isabelle` forwards arguments, stdio, cwd, and the executable's exit
status. Python entry points use the same resolver. A gate run passes its
resolved selection to end-to-end workers. An explicit new `ISABELLE` overrides
an inherited resolution. Use `python3 -m mcp.test.test_mcp` (and corresponding
module names for repro scripts) from the repository root.

`tools/isabelle-diagnose` reports the selected command, version, heap settings,
and any mismatch between active MCP components and this checkout. It runs
only shell-based `version` and `getenv` probes, with a bounded timeout.

## Flatpak and containers

A Flatpak adapter is an ordinary selected executable. Generic repository
scripts contain no Flatpak or Distrobox discovery. The desktop container's
`provision/isabelle` adapter invokes its sibling `host-command` and passes
`USER_HOME`, `ISABELLE_IDENTIFIER`, and `ISABELLE_MCP_PAYLOAD_ARTIFACT` explicitly
with Flatpak `--env` when set. `host-command` maps a container session-bus path
exactly once and leaves the desktop/keyring environment unchanged. On an
ordinary host it directly executes the command.

A raw `ISABELLE='flatpak run --command=isabelle APP_ID'` prefix is valid, but
its owner must ensure required environment and paths reach the sandbox.
Prefer the adapter when using private worktree state. Paths for checkout,
private state, temporary theories, and output must be visible to Isabelle.
The adapter does not install packages, change saved Flatpak permissions,
recreate containers, or select another distribution on failure.

## Private worktree state

```sh
python3 tools/isabelle_worktree.py --worktree /absolute/checkout setup
python3 tools/isabelle_worktree.py --worktree /absolute/checkout scala
python3 tools/isabelle_worktree.py --worktree /absolute/checkout build
python3 tools/isabelle_worktree.py --worktree /absolute/checkout test -L scala-unit
python3 tools/isabelle_worktree.py --worktree /absolute/checkout teardown
```

Any Git checkout root is accepted; it need not be under an agent-specific
directory. The old positional worktree-name interface is replaced by
`--worktree`. State is under `<checkout>/.isabelle-worktree`; only that
checkout's MCP components are registered. Supply additional `--component`
and `--root` paths explicitly before the action. The helper does not copy
the main user's component catalog or ROOTS file.

Python configures the private user directory and delegates heap selection and
validation to Isabelle's native `build -n -b Pure HOL`. Isabelle can read its
system heaps; new heaps and databases go into the checkout's private user
state. The helper does not enumerate platforms, copy base databases, or load
the main user's heaps/catalog. Missing or outdated base heaps fail setup;
report the requirement rather than building them or switching installation.
Isabelle's bootstrap can compile the registered Scala components during this
check, so setup is not read-only.

Ownership and directory checks reject symlink substitutions and mismatched
state; a lock prevents simultaneous helper operations. Concurrent commands run outside the
helper are not covered by that lock. A command that bypasses the adapter and
silently drops private-state variables fails before the build check.

For agent verification, run the full gate only in the intended environment.
Passing fixture tests or `version` does not establish full planning-gate
acceptance, Flatpak prover cleanup, or a successful run in another agent/CI.

## Choose the verification action

After Scala or component build-property changes, run `scala` before exercising
the server. Do not add `scala_build -f`: it rebuilds distribution jars, which
may be read-only. After theory or ML changes, run `build` to check the MCP
sessions; Scala compilation alone does not check theories. Use `test` for the
relevant MCP test labels. Full acceptance remains `tools/planning-gate done`;
its environment must select the same installation and intended components.
