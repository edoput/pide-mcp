# Source positions across the ML/Scala boundary

Same shape of problem as `symbol-recoding.md`: the two sides speak
different units, Isabelle/Scala converts for you on some paths and not on
others, and the paths it does not cover are exactly the ones a custom tool
builds on.

Full write-up with live measurements: `POSITION_FINDINGS.md` at the repo
root. This file is the boundary-crossing subset.

## Four coordinate systems

| system | base | unit | where you meet it |
| --- | --- | --- | --- |
| `Position.offset` / `Symbol.Offset` | 1 | **Isabelle symbol** | ML; any `Position.T` property list |
| `Text.Offset` | 0 | **Java char** (UTF-16 unit) | Scala; `Text.Range`, `snapshot.cumulate` results |
| `Line.Position(line, column)` | 0 / 0 | line, Java char | Scala; the LSP-facing type |
| `Position.line` property | 1 | line | ML properties |

**The convention: ML counts symbols from 1, Scala counts chars from 0.**
`\<forall>` is one symbol and nine characters, so the two disagree by an
amount that depends on the text *before* the offset — you cannot correct it
with a constant.

Measured on a 164-character theory containing `\<forall>` and `\<and>`:
151 symbols, and symbol offset `105` denotes char offset `117`
(`117 = (105 - 1) + 13`, where `13 = 8 + 5` is the surplus of the two
symbols before it). Hand a client the raw `105` and it lands twelve
characters early, mid-token.

## Where the conversion happens

One function does **both** jobs — `Pure/General/symbol.scala:185-200`:

```scala
def decode(symbol_offset: Offset): Text.Offset = {
  val sym = symbol_offset - 1              // 1-based -> 0-based
  ...
  if (i < 0) sym
  else index(i).chr + sym - index(i).sym   // symbols -> chars
}
```

The index is built only over multi-char symbols (`symbol.scala:166-178`),
so on pure-ascii text `decode` degenerates to `offset - 1`. **This is why
the bug hides**: everything looks correct until the first `\<forall>`.

`Symbol.Text_Chunk` (`symbol.scala:214-247`) packages an index without
keeping the text; `Text_Chunk.incorporate` additionally clips to the chunk
and retries one symbol back, which is the tolerant variant used for markup.

## The API

| call | does |
| --- | --- |
| `Symbol.Text_Chunk(text)` | build an index over some text |
| `chunk.decode(symbol_offset)` | symbol offset → `Text.Offset` |
| `chunk.decode(symbol_range)` | `Symbol.Range` → `Text.Range` |
| `Line.Document(text)` | line-oriented view |
| `doc.range(text_range)` | `Text.Range` → `Line.Range` (line/column) |
| `doc.offset(line_pos)` | `Line.Position` → `Text.Offset` |
| `Store.source_file(raw_name)` | resolve a `def_file` value to a real path |
| `snapshot.find_command_position(id, offset)` | id-addressed → `Line.Node_Position` |

`Line.Position` → LSP is a straight field copy — `lsp.scala:185-187` emits
`"line" -> pos.line, "character" -> pos.column`, both already 0-based.
Anything else (byte offsets, codepoint offsets, 1-based columns) has no
support in Isabelle; you write it yourself.

## Two addressing modes, four extractors

`Position.T = Properties.T` in Scala (`position.scala:11`) — there is no
Position *class*, only extractors. The four that matter encode the
file-vs-id split (`position.scala:72-114`):

```scala
Position.Item_File(name, line, range)      // file-addressed use site
Position.Item_Id(id, range)                // id-addressed use site
Position.Item_Def_File(name, line, range)  // file-addressed definition
Position.Item_Def_Id(id, range)            // id-addressed definition
```

Matching on them is how you learn which mode you were handed. **id-addressed**
means the offset is relative to one command's own source, resolvable only
through `snapshot.find_command_position`. **file-addressed** means an
offset into a file you must read yourself to convert.

`Position.JSON` (`position.scala:138-163`) exists and emits
`line`/`offset`/`end_offset`/`file`/`id` — but in raw ML units, so it is
not suitable to hand a client unconverted.

## Traps

- **`Position.Range` fabricates a range.** `position.scala:57` —
  `case (Offset(start), _) => Some(Text.Range(start, start + 1))`. It never
  returns `None` when there is an offset, so a definition site lacking
  `def_end_offset` silently yields a **one-symbol** range that is not the
  entity's extent. Measured: the `theory` command entity has `def_offset`
  and no `def_end_offset`. `Item_*` fabricate the same way via
  `getOrElse (offset + 1)`. Test `End_Offset` yourself if the extent matters.
- **`line` means two things.** `Position.Line` (property) is 1-based;
  `Line.Position.line` is 0-based. VSCode writes the conversion out
  explicitly as `(line1 - 1) max 0`.
- **`Snapshot.messages` does not use the message's own position.**
  `document.scala:720-726` synthesises a file-addressed position covering
  the **command keyword** (`command_span.scala:92-97`) and pairs it with
  every message of that command. The message's own props are id-addressed
  and, measured, also point at the keyword — so switching to them buys
  nothing. What *is* thrown away is the message body's markup: error
  messages embed `<entity def_file=... def_line=...>` for every constant
  and type they mention, and `XML.content` destroys all of it.
- **`def_file` values are not paths.** They come as `~~/src/HOL/Nat.thy`
  or as bare filenames like `pure_syn.ML`. `Store.source_file`
  (`store.scala:305-321`) expands `~~` and probes `~~/src/Pure` plus the ML
  sources dir for bare names.
- **Which text an offset indexes is a choice.**
  `Headless.Session.use_theories(..., unicode_symbols: Boolean = false)`
  reaches `headless.scala:658`, `Symbol.output(unicode_symbols, File.read(path))`.
  With `false` the document text is the file verbatim; with `true` it is
  decoded. An offset without a statement of which variant it indexes is not
  a location. This repo takes the default `false`, so document offsets match
  what a client reads off disk.
- **Not every position resolves to a file.** Bound variables get a `def_id`
  with no `def_file`; local facts get a bare `def=<serial>` with no position
  at all (the definition site *is* where the markup sits).

## Who already does this correctly

`Tools/VSCode/src/vscode_rendering.scala:242-306` is the reference
implementation — read it before designing anything. The dispatch is exactly
the four extractors (`:277-289`), and the file branch shows the full
conversion plus its graceful degradation:

```scala
resources.get_file_content(resources.node_name(file)) match {
  case Some(text) =>
    val chunk = Symbol.Text_Chunk(text)
    val doc = Line.Document(text)
    doc.range(chunk.decode(range))
  case _ =>
    Line.Range(Line.Position((line1 - 1) max 0))   // no text: line only
}
```

Note it **reads the target file** — a `def_offset` into `HOL.thy` is
meaningless without `HOL.thy`'s text.

In this repo: `mcp_session.scala` `render_messages` currently reports only
`Position.Line` from the synthesised position (correct as far as it goes,
and it wisely discards the raw offsets); `mcp_config.scala` `Site.location`
formats `Position.File` + `Position.Line` for ROOT diagnostics.

## Rules of thumb

- Convert at **one point**, where a position meets the client — the same
  discipline as symbol recoding. A raw `offset` travelling through your own
  code is fine; a raw `offset` in a tool payload is a bug.
- Always emit `line` alongside any offset. It survives when the text is
  unavailable, and it is the fallback every consumer already implements.
- To convert, you need the *exact* text the document was built from. If you
  cannot get it, degrade to the line number rather than guessing.
- `Symbol.length(text)` vs `text.length` is a one-line check for whether a
  given file can even exhibit the problem.
