### The theory database (Thy_Info): what it remembers, what it does not

Manual: `src/Doc/Implementation/Integration.thy` (Isar toplevel, theory
loader database).
Sources: `src/Pure/Thy/thy_info.ML` (the database), `src/Pure/context.ML`
(theory values, `Theory_Data`), `src/Pure/global_theory.ML`
(`facts_of`, `dest_thms`), `src/Pure/facts.ML` (fact tables).

Thy_Info is a graph of dependencies, not a history. It is a
`String_Graph` keyed by theory name — one entry per name. Nodes are
theory names, edges are the imports relation. It answers "what does
this theory depend on" and "what depends on this theory". It does not
answer "what did this theory look like yesterday".

A theory value is immutable and self-contained: its own content plus
everything inherited from its parents (name spaces, the facts table,
axioms, defs, all `Theory_Data` slots). It carries no timestamp and no
pointer to any earlier version of itself.

**Reloading discards the old version.** When you reload a theory,
Thy_Info removes the old node first and every theory that depends on
it, then adds the new one. So after you edit and reload a theory there
is exactly one value for that name — the old one, and the loaded state
of everything downstream, is gone.

There is a real history, but only inside one load: while a single file
loads, each command produces a new theory value, and with the
`record_theories` option those per-command states stay reachable
(`Thy_Info.get_theory_segments`, `Thy_Info.get_theory_elements`). That
is history inside one load, not history across edits.

**Practical consequence.** To compare two versions of a theory (what
changed in a file), the prover keeps only one value per name, so it
won't do this for you directly. But two values coexist fine when their
names differ — copy the old file under a different theory name, load
it, and diff the two values with `Facts.dest_static`. See
`references/thy-info.md` for the worked recipe, including the pitfall
where the exclusion test is by name only, so a theorem whose statement
changed but kept its name won't show up as new.

# Thy_Info: the theory database, and how to diff two theory versions

Thy_Info is the loader's database of theories. It is a graph, not a
history. This note spells out what it holds, what reloading does to
it, and the recipe for comparing two versions of a theory when the
database itself won't do it for you.

## 1. A graph of dependencies, not a history

Thy_Info is a `String_Graph` keyed by theory name — one entry per
name. The nodes are theory names, the edges are the imports relation.

It answers questions like "what does this theory depend on" and "what
depends on this theory". It does not answer "what did this theory look
like yesterday". There is no version axis in the graph at all — only
one slot per name, always holding the current value.

## 2. What a theory value carries

A theory value is immutable and self-contained. Once built, it never
changes. It carries:

- its own content, plus everything inherited from its parents
- name spaces: constants, types, classes, facts, locales
- the facts table (`Global_Theory.facts_of`)
- axioms (`Theory.all_axioms_of`)
- defs
- every `Theory_Data` slot that some package registered

It carries no timestamp, and no pointer to any earlier version of
itself. Given a theory value alone, there is no way to ask "what came
before this".

## 3. Reloading discards the old version

This is the load-bearing fact: reload does not add a version, it
replaces one.

`Pure/Thy/thy_info.ML:198`, `update`, calls `remove name thys` before
`new_entry`. `remove` (same file, ~line 182) deletes that node **and**
every theory that depends on it, via `String_Graph.all_succs` — it
prints "Theory loader: removing ..." naming all of them.

So after you edit and reload `Scheduler.thy` there is exactly one
`Scheduler` value in the database. The previous one is gone, and so is
the loaded state of everything downstream that depended on it.

## 4. The one real history, and its limit

Theory values are immutable, so every command run during a load
produces a *new* theory value. There is a real chain of versions while
a single file loads. With the `record_theories` system option turned
on, those per-command states are kept and reachable:

```
Thy_Info.get_theory_segments : string -> Document_Output.segment list
Thy_Info.get_theory_elements : string -> Document_Output.segment Thy_Element.element list
```

That is history *inside* one load — the sequence of states as one file
is processed command by command. It is not history *across* edits: as
soon as you edit the file and reload, section 3 applies and the whole
chain for the old load is gone.

## 5. Comparing two versions of a theory

If you want to know what changed in a file — e.g. "what did I add to
`Scheduler.thy` since the last commit" — the prover will not do this
for you out of the box, because the database only ever holds one value
per name.

But two theory values coexist fine when their **names differ**. So the
move is:

1. Copy the old version of the file under a different theory name,
   e.g. `Scheduler_Before.thy` (with `theory Scheduler_Before`).
2. Load it, alongside the current `Scheduler`.
3. Now both values exist at once, and you can diff them directly.

Comparing is the easy part. `Facts.dest_static` takes an arbitrary
list of fact tables to exclude:

```
Facts.dest_static : bool -> Facts.T list -> Facts.T -> (string * thm list) list
```

(`Pure/facts.ML:40`.) Pass the baseline theory's `facts_of`
(`Global_Theory.facts_of Scheduler_Before_thy`) as the exclusion list
instead of the parents' fact tables, and you get exactly the facts the
new theory adds on top of the old one — not on top of the shared
imports, but on top of the old file's own content.

### Pitfall (2026-08-12): exclusion is by name only

`Facts.dest_static`'s exclusion test, `included` (`Pure/facts.ML:232`),
matches by **name only**. If a theorem keeps the same name across the
edit but its statement (its `prop`) changed, `dest_static` will *not*
report it as new — the name was already excluded, so it's treated as
"already there".

If you need to catch statement changes, not just additions and
renames, don't stop at `dest_static`'s name-based diff — pull the
`thm list` for each shared name from both fact tables and compare the
props (`Thm.prop_of`) yourself.

## Source pointers

- `Pure/Thy/thy_info.ML` — the database: the `String_Graph`, `update`,
  `remove`, `get_theory_segments`, `get_theory_elements`.
- `Pure/context.ML` — theory values, `Theory_Data`.
- `Pure/global_theory.ML` — `facts_of`, `dest_thms`.
- `Pure/facts.ML` — fact tables, `dest_static`, `included`.
- Manual: `src/Doc/Implementation/Integration.thy` — Isar toplevel and
  the theory loader database.
