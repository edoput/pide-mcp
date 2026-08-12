---
paths:
  - "mcp/Tools/**/*.thy"
  - "mcp/Tools/ROOT"
  - "ir/*.ML"
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

Follow these steps. `<name>` is the worktree's name throughout; keep it
the same in all of them.

### Step 1 — pin the worktree path in a variable

Do this in every shell you build from. `-d` is resolved relative to the
current directory, so a stray `cd` (or a resumed session, which resets
the working directory to the main checkout) will silently build the MAIN
checkout instead — with no error, because that path is the one the
component already registers.

```
WT=/home/edoput/repo/isabelle-mcp/.claude/worktrees/<name>
```

Use `$WT/mcp/Tools` as the `-d` argument from here on, never a relative
path.

### Step 2 — create the scratch Isabelle user directory (once per worktree)

Symlink only the `HOL` and `Pure` heap **files**. Everything else —
`log/` and the `MCP-*` heaps — stays private to the worktree.

```
S=~/.isabelle/wt-<name>
R=~/.isabelle/Isabelle2025-2/heaps/polyml-5.9.2_x86_64_32-linux
H="$S/heaps/polyml-5.9.2_x86_64_32-linux"

mkdir -p "$S/etc" "$H/log"
ln -s "$R/HOL" "$H/HOL"
ln -s "$R/Pure" "$H/Pure"
cp "$R/log/HOL.db" "$R/log/Pure.db" "$H/log/"
printf '/home/edoput/repo/afp-current/afp-2025-06-04\n' > "$S/etc/components"
cp ~/.isabelle/Isabelle2025-2/ROOTS "$S/ROOTS"
```

The `etc/components` file above is the real one **minus** the two repo
component lines (`.../isabelle-mcp/mcp` and `.../isabelle-mcp/mcp_test`).
Those are what put the main checkout's `ROOT` in scope. Keep the AFP line
only if a session you build needs it.

**Do not symlink the whole `heaps` directory.** `log/` lives *inside*
it, and `log/` holds one SQLite database per session
(`MCP-HOL-Tests.db` and friends). Sharing those makes the build die
after compiling everything, with:

```
*** [SQLITE_CONSTRAINT_PRIMARYKEY] A PRIMARY KEY constraint failed
    (UNIQUE constraint failed: isabelle_session_info.session_name)
```

because the main checkout already wrote a row for that session name.
`isabelle build -c` does not clear it — the clean removes the heap, not
the database row. Symlinking the two heap files instead keeps the
databases separate and the collision never happens.

Keeping the `MCP-*` heaps private has a second payoff: the worktree no
longer overwrites the main checkout's heaps, so a running
`isabelle mcp_server` keeps serving main-checkout theories while you
work, and step 6 below becomes cleanup rather than repair.

### Step 3 — build, selecting that directory with `ISABELLE_IDENTIFIER`

```
flatpak run --env=ISABELLE_IDENTIFIER=wt-<name> \
  --command=isabelle de.tum.in.isabelle.Isabelle build \
  -d "$WT/mcp/Tools" -v MCP-Tools MCP-Tools-Tests MCP-HOL MCP-HOL-Tests
```

Do **not** try `--env=ISABELLE_HOME_USER=...` instead — it is silently
ignored (see "Why" below).

### Step 4 — confirm it built the worktree, not the main checkout

Check the theory list in the output against the worktree's `ROOT`:

```
... | grep "MCP-HOL-Tests: theory"
```

Every theory named in the worktree's `ROOT` must appear, and any theory
that exists only in the main checkout's `ROOT` must not. If the lists are
swapped, `-d` pointed at the wrong tree — go back to step 1.

### Step 5 — forcing a rebuild

Isabelle keys staleness off file **content**, not mtime, so `touch` does
nothing. To re-run a session whose sources have not changed (e.g. to see
its output again), clean it first:

```
flatpak run --env=ISABELLE_IDENTIFIER=wt-<name> \
  --command=isabelle de.tum.in.isabelle.Isabelle build -c \
  -d "$WT/mcp/Tools" -v MCP-HOL-Tests
```

### Step 6 — when finished with the worktree

```
rm -rf ~/.isabelle/wt-<name>
```

That is all, **provided step 2 kept the `MCP-*` heaps private**. The
symlinks point at `HOL` and `Pure` only, and neither is ever rebuilt by
an MCP session, so the main checkout is untouched and needs no rebuild.

If you instead symlinked the whole `heaps` directory (the older form of
step 2), the worktree's `MCP-HOL` heap overwrote the main checkout's
heap of the same name, and a running `isabelle mcp_server` serves
worktree theories until you rebuild it:

```
cd /home/edoput/repo/isabelle-mcp && flatpak run --command=isabelle \
  de.tum.in.isabelle.Isabelle build -d mcp/Tools \
  MCP-Tools MCP-Tools-Tests MCP-HOL MCP-HOL-Tests
```

### Why each step is shaped that way

- **The collision (step 2).** `mcp` is a registered user-space Isabelle
  component: `$ISABELLE_HOME_USER/etc/components` lists
  `/home/edoput/repo/isabelle-mcp/mcp`, and that directory's `ROOTS` file
  contains `Tools`. So the main checkout's `mcp/Tools/ROOT` is in scope
  for every build from anywhere. Passing `-d` for the worktree's copy
  puts the same four session names in scope twice.
- **`ISABELLE_IDENTIFIER`, not `ISABELLE_HOME_USER` (step 3).**
  `etc/settings:78-81` assigns
  `ISABELLE_HOME_USER="$USER_HOME/.isabelle/$ISABELLE_IDENTIFIER"`
  unconditionally, overwriting whatever the environment said.
  `ISABELLE_IDENTIFIER` *is* honoured from the environment
  (`lib/scripts/getsettings:71-73`), so it is the supported lever.
- **`HOL` and `Pure` must be symlinked (step 2).** The flatpak's system
  heaps (`/app/heaps`) are EMPTY, and heap lookup is user-then-system
  with no fallback, so a scratch heaps directory with nothing in it
  forces a full Pure → HOL rebuild — which on this machine gets
  OOM-killed partway. With the two symlinks, `HOL` is reused and only
  the MCP sessions rebuild (~20s).
- **but only those two (step 2).** Sharing `log/` shares the per-session
  SQLite databases, and the build then fails at the very end on a
  PRIMARY KEY conflict against the main checkout's row. Sharing the
  `MCP-*` heaps makes the worktree overwrite the main checkout's. Both
  are avoided by symlinking the two files rather than the directory.
  The `.db` copies for `HOL`/`Pure` are needed because a heap without
  its database row does not count as built.
