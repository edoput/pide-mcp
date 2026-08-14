### Names, bindings, name spaces
Manual: `src/Doc/Implementation/Prelim.thy` §"Names" — basic names,
indexnames, qualified names, `binding`, name spaces.
Sources: `src/Pure/name.ML`, `src/Pure/General/binding.ML`,
`src/Pure/General/name_space.ML`.

### Source positions
Sources: `src/Pure/General/position.ML`,
`src/Pure/Concurrent/thread_position.ML`,
`src/Pure/General/symbol_pos.ML`, `src/Pure/General/input.ML`,
`src/Pure/context_position.ML`, `src/Pure/Syntax/term_position.ML`,
`src/Pure/PIDE/markup.ML` (the property names).
Full write-up with live measurements: `POSITION_FINDINGS.md` at the repo
root.

`Position.T` is one flat record — `line`, `offset`, `end_offset`, `label`,
`file`, `id` — with absence encoded as `0`/`""` rather than as options
(`position.ML:80-113`). Offsets **count Isabelle symbols**, start at **1**,
and `end_offset` is exclusive (`position.ML:4-6`). A range is not a
separate type: it is a pair of positions, collapsed into one carrying
`end_offset`.

**A position is either file-addressed or id-addressed.** `Position.file` /
`line_file` set `file` and blank `id`; `Position.id` / `id_only` do the
reverse (`position.ML:148-170`). `Position.is_reported` requires an **id**,
not a file (`:250`) — being "reported" is a PIDE-document notion, not a
filesystem one. Command execution installs
`Position.id_only (Document_ID.print exec_id)` as the ambient position
(`src/Pure/PIDE/command.ML:263-273`), so anything your ML raises during a
command is id-addressed, with no line and no file.

How a position reaches your code, most-used first:

- **ambient thread-local** — `Position.thread_data ()`, installed with
  `Position.setmp_thread_data`. This is why a bare `error "..."` lands in
  the right place without threading a position through every function.
- `Binding.pos_of` — declared names remember where they were written
  (`binding.ML:16-19`); that origin is what later becomes an entity's
  `def_*` properties.
- `Token.pos_of` / `Token.range_of` — outer-syntax tokens.
- `Input.pos_of` / `Input.range_of` — a cartouche argument carries its own
  range (`input.ML`), which is what makes an error inside embedded ML or a
  method argument point at the right characters.
- `Symbol_Pos.T = Symbol.symbol * Position.T` — the lexer level, one
  position per symbol.

**Terms carry no positions.** `term` and `typ` have no position field at
all. The parser smuggles positions through as the *name* of a `Free` /
`TFree` variable — YXML-encoded, attached via the `_constrain`,
`_constrainAbs`, `_ofsort` markers — and strips them after checking
(`term_position.ML`, `strip_positions` at `:131-137`). Consequence: a
checked term handed back by the kernel is positionless, so "where in the
source did this subterm come from" **cannot** be answered by inspecting the
term. It has to go through the markup emitted during parsing/checking.

**Markup can be silently absent.** `Context_Position` gates every report on
three conditions at once (`context_position.ML:67-68`): the `pide_reports`
option, `Print_Mode.PIDE_enabled ()`, and a per-context `visible` config
flag (`:43`) that packages routinely turn *off* for internal elaboration.
When markup you expect is missing, suspect `Context_Position.set_visible
false` upstream before suspecting a bug.

**Defining entities so that ctrl-click works.** `Position.entity_markup` /
`make_entity_markup` (`position.ML:234-242`): a *definition* site gets
`def=<serial>` plus its own position under the plain property names; a
*use* site gets `ref=<serial>` plus the definition's position under
`def_`-prefixed names (`def_file`, `def_line`, `def_offset`, ...). A use
site therefore carries everything needed to jump, with no lookup table —
which is what every navigation feature is built on.

Consuming these positions from Scala — the unit and base conversions,
which are not optional — belongs to the **isabelle-ml-scala** skill; load
that skill and read its `references/positions.md`.
