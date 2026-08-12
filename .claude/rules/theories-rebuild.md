---
paths:
  - "mcp/Tools/**/*.thy"
  - "mcp/Tools/ROOT"
  - "ir/*.ML"
  - "tools/wt-isabelle-build.sh"
---

# Rebuild after theory/ML changes

`mcp/Tools/ROOT` declares four sessions built from this tree:
`MCP-Tools`, `MCP-Tools-Tests` (depends on `MCP-Tools`), `MCP-HOL`
(built on `HOL`, with an added session dependency on `MCP-Tools`;
pulls in `ir/ir.ML` via the symlink at `mcp/Tools/HOL/ir.ML`), and
`MCP-HOL-Tests` (depends on `MCP-HOL`).

Unlike the Scala jar, sessions are **not** rebuilt implicitly — nothing
rebuilds them just by running `isabelle mcp_server`. Whenever you edit
a `.thy` file under `mcp/Tools/`, the `ROOT` file, or `ir/ir.ML`,
rebuild (and re-run the `\<^assert>`-based tests) with:

```
flatpak run --command=isabelle de.tum.in.isabelle.Isabelle build \
  -d mcp/Tools -v MCP-Tools MCP-Tools-Tests MCP-HOL MCP-HOL-Tests
```

Never run the repo-bundled
`Isabelle2025-2_linux/Isabelle2025-2/bin/isabelle` binary. Both
installs share `$ISABELLE_HOME_USER/heaps`, and a root session's build
digest is keyed off the SHA1 of the Poly/ML executable itself. Since
the bundled and flatpak `poly` binaries differ, each install considers
the other's Pure stale, and heap lookup (`Store.input_dirs` in
`src/Pure/Build/store.scala`: user before system, first hit wins, no
fallback) means whichever install ran last shadows the other's heaps —
forcing a full Pure → HOL → MCP-HOL rebuild on every alternation. The
bundled tree is still fine to read as an Isabelle-source reference
(same packaged version) — just never execute it.

This fails iff a theory fails to load or an assertion in
`MCP_Tools_Tests`/`MCP_Repl`/`MCP_Repl_Tests` fails, so treat a clean
build as the test signal. Do this before relying on the tool registry
or REPL bridge behaving as expected.

## Building from a git worktree

**Symptom.** The build command above fails in a worktree before compiling
anything:

```
*** Duplicate session "MCP-Tools"
    (line 1 of ".../.claude/worktrees/<name>/mcp/Tools/ROOT")
    (line 1 of "/home/edoput/repo/isabelle-mcp/mcp/Tools/ROOT")
```

Use `tools/wt-isabelle-build.sh <name> [setup|build|clean|teardown]`,
run from the main checkout. `<name>` is the worktree's name, matching
`.claude/worktrees/<name>`.

```
tools/wt-isabelle-build.sh <name> build       # setup (idempotent) + build
tools/wt-isabelle-build.sh <name> clean       # force-rebuild MCP-HOL-Tests
tools/wt-isabelle-build.sh <name> teardown    # rm -rf the scratch user dir
```

`build` runs `setup` first if the scratch Isabelle user directory
(`~/.isabelle/wt-<name>`) doesn't exist yet, then invokes
`isabelle build` under `ISABELLE_IDENTIFIER=wt-<name>` against
`.claude/worktrees/<name>/mcp/Tools`. Run it from the main checkout
(or with an absolute path) — the script hardcodes the worktree root, so
a stray `cd` inside the worktree itself doesn't matter, but it must
still be invoked with `bash`/`sh` finding it via the repo path, not a
copy.

### Confirming it built the worktree, not the main checkout

Check the theory list in the output against the worktree's `ROOT`:

```
tools/wt-isabelle-build.sh <name> build | grep "MCP-HOL-Tests: theory"
```

Every theory named in the worktree's `ROOT` must appear, and any theory
that exists only in the main checkout's `ROOT` must not. If the lists are
swapped, the worktree doesn't exist at the expected path — check
`.claude/worktrees/<name>`.

### Forcing a rebuild

Isabelle keys staleness off file **content**, not mtime, so `touch` does
nothing. To re-run a session whose sources have not changed (e.g. to see
its output again), use the `clean` action — it runs `isabelle build -c`.

### When finished with the worktree

```
tools/wt-isabelle-build.sh <name> teardown
```

That is all, **provided the scratch dir kept the `MCP-*` heaps
private** (which `setup` always does). The symlinks it creates point at
`HOL` and `Pure` only, and neither is ever rebuilt by an MCP session, so
the main checkout is untouched and needs no rebuild.

If a worktree's scratch dir was ever set up by hand with the whole
`heaps` directory symlinked (the old, unscripted approach), the
worktree's `MCP-HOL` heap overwrote the main checkout's heap of the
same name, and a running `isabelle mcp_server` serves worktree theories
until you rebuild it:

```
cd /home/edoput/repo/isabelle-mcp && flatpak run --command=isabelle \
  de.tum.in.isabelle.Isabelle build -d mcp/Tools \
  MCP-Tools MCP-Tools-Tests MCP-HOL MCP-HOL-Tests
```

### Why the script is shaped that way

- **The collision `setup` avoids.** `mcp` is a registered user-space
  Isabelle component: `$ISABELLE_HOME_USER/etc/components` lists
  `/home/edoput/repo/isabelle-mcp/mcp`, and that directory's `ROOTS` file
  contains `Tools`. So the main checkout's `mcp/Tools/ROOT` is in scope
  for every build from anywhere. Passing `-d` for the worktree's copy
  puts the same four session names in scope twice unless the scratch
  `etc/components` drops the two repo component lines — which is why
  `setup` filters them out rather than copying the file verbatim.
- **`ISABELLE_IDENTIFIER`, not `ISABELLE_HOME_USER`.**
  `etc/settings:78-81` assigns
  `ISABELLE_HOME_USER="$USER_HOME/.isabelle/$ISABELLE_IDENTIFIER"`
  unconditionally, overwriting whatever the environment said.
  `ISABELLE_IDENTIFIER` *is* honoured from the environment
  (`lib/scripts/getsettings:71-73`), so the script sets that instead.
- **`HOL` and `Pure` are symlinked, not rebuilt.** The flatpak's system
  heaps (`/app/heaps`) are EMPTY, and heap lookup is user-then-system
  with no fallback, so a scratch heaps directory with nothing in it
  forces a full Pure → HOL rebuild — which on this machine gets
  OOM-killed partway. With the two symlinks, `HOL` is reused and only
  the MCP sessions rebuild (~20s).
- **but only those two, not the whole `heaps` dir.** `log/` lives
  *inside* `heaps/`, and holds one SQLite database per session
  (`MCP-HOL-Tests.db` and friends). Symlinking the whole directory
  shares those, and the build then dies at the very end on:

  ```
  *** [SQLITE_CONSTRAINT_PRIMARYKEY] A PRIMARY KEY constraint failed
      (UNIQUE constraint failed: isabelle_session_info.session_name)
  ```

  because the main checkout already wrote a row for that session name,
  and `isabelle build -c` clears the heap, not the database row.
  Symlinking the two heap files instead keeps the databases separate
  and the collision never happens — and as a second payoff, the
  worktree never overwrites the main checkout's `MCP-*` heaps, so a
  running `isabelle mcp_server` keeps serving main-checkout theories
  while you work, and `teardown` is cleanup rather than repair. The
  `.db` copies for `HOL`/`Pure` are needed because a heap without its
  database row does not count as built.
