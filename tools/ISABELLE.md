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
tools/wt-isabelle-build.sh --worktree /absolute/checkout setup
tools/wt-isabelle-build.sh --worktree /absolute/checkout scala
tools/wt-isabelle-build.sh --worktree /absolute/checkout build
tools/wt-isabelle-build.sh --worktree /absolute/checkout test -L scala-unit
tools/wt-isabelle-build.sh --worktree /absolute/checkout teardown
```

Any Git checkout root is accepted; it need not be under an agent-specific
directory. The old positional worktree-name interface is replaced by
`--worktree`. State is under `<checkout>/.isabelle-worktree`; only that
checkout's MCP components are registered. Supply additional `--component`
and `--root` paths explicitly before the action. The helper does not copy
the main user's component catalog or ROOTS file.

The helper queries heap roots inside the selected installation, including
system heaps inaccessible directly from an agent container. It discovers
complete Pure/HOL seed sets without a hardcoded release or platform path.
If several exist, select `--heap-id`; the selected runtime must still accept
them. Missing base sessions are an actionable error, not an implicit build.

Base heaps and databases are copied into private writable state, not shared
as mutable files. Their source digests and the Poly/ML executable digest bind
reuse to the selected installation. Every setup checks `build -n -b Pure HOL`
and verifies that the private user directory reached Isabelle. This does not
rebuild base heaps, but Isabelle's bootstrap may compile configured Scala
components. Setup is therefore not a read-only command.

Ownership and directory checks reject symlink substitutions and mismatched
state; a lock prevents simultaneous helper operations. Installation changes
require explicit teardown before setup. Concurrent commands run outside the
helper are not covered by that lock. A command that bypasses the adapter and
silently drops private-state variables fails before the build check.

For agent verification, run the full gate only in the intended environment.
Passing fixture tests or `version` does not establish full planning-gate
acceptance, Flatpak prover cleanup, or a successful run in another agent/CI.
