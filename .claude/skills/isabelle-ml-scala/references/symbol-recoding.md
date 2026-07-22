# Symbol recoding across the ML/Scala boundary

Isabelle carries the same text in two alphabets, and which one you hold
depends on which side of the boundary you are on and which channel you
crossed it by.

- **symbol notation** — pure ascii, `\<Longrightarrow>`, `\<open>`,
  `\<^sub>`. This is what ML speaks internally and what theory sources
  traditionally hold on disk.
- **unicode** — `⟹`, `‹`, what an editor or a terminal shows.

**The convention: ML speaks symbol notation, Scala speaks unicode.**
Isabelle/Scala normally maintains this for you. The trap is that it does
not maintain it on *every* channel, and the exceptions are exactly the
ones a custom tool is most likely to build on.

## Where the recoding happens (and does not)

All of this is `Pure/PIDE/prover.scala`.

Inbound, in `message_output`:

```scala
if (kind == Markup.PROTOCOL) protocol_output(props, chunks)   // RAW — no decode
else output(kind, props, chunks.flatMap(decode_xml))          // decode_xml = Symbol.decode
```

Message properties get `Symbol.decode` via `decode_prop`. Ordinary
writeln/warning/error bodies get it via `decode_xml`. **Protocol output
chunks get nothing** — `Prover.Protocol_Output.chunk` is raw `Bytes`,
and `.text` is its undecoded string.

Outbound:

- `protocol_command_args` / `protocol_command` → applies
  `Symbol.encode_yxml` to each argument.
- `protocol_command_raw` → sends raw `Bytes`, **no encoding**.

So: the ordinary PIDE message channel is recoded for you in both
directions; the protocol-command channel is recoded in neither. If you
define a `Protocol_Command` and read its replies off `Protocol_Output`,
you own the recoding on both sides. Nothing upstream will do it.

## The API

**Isabelle/Scala** — `Pure/General/symbol.scala`:

| call | direction |
| --- | --- |
| `Symbol.decode(text)` | symbol notation → unicode |
| `Symbol.encode(text)` | unicode → symbol notation |
| `Symbol.decode_yxml(text, cache)` | parse yxml, decoding text nodes |
| `Symbol.decode_yxml_failsafe(text)` | as above, tolerant of malformed input |
| `Symbol.encode_yxml(body)` | serialise body, encoding text nodes |

`Pure/PIDE/yxml.scala` is where the recode actually lands, and the
important detail is *where* it applies:

```scala
YXML.string_of_body(body, recode = Symbol.encode)
YXML.bytes_of_body(body,  recode = Symbol.encode)
YXML.parse_body(source,   recode = Symbol.decode)
```

`recode` is applied in `Output_String.string` / `Output_Bytes.string`,
i.e. **to text nodes only**. This is why you pass `recode` as a
parameter and never run `Symbol.encode` over an already-assembled yxml
string: the assembled form contains X/Y control bytes, and a blanket
recode would walk them.

Two properties worth relying on:

- **`decode` is idempotent.** Its recoder only fires on `\`-initiated
  sequences and its own output contains none, so decoding
  already-unicode text is a no-op. That is what makes it safe to decode
  at one late choke point even when some of the text arrived via the
  already-decoded channel.
- **`encode` is a no-op on text that is already symbol notation** (pure
  ascii — the encoder's table is keyed on unicode glyphs). So an inbound
  `encode` lets a caller send *either* alphabet.

**Isabelle/ML** — `Pure/General/symbol.ML`. Beware a false friend:
`Symbol.decode : symbol -> sym` here *classifies* a single symbol
(`Char | UTF8 | Sym | Control | Malformed | EOF`); it is **not** the
counterpart of Scala's text-recoding `Symbol.decode`. The ML side
generally stays in symbol notation and does not recode at all — that is
the whole point of the convention. Related: `Symbol.explode`,
`Symbol.is_utf8`.

## Who already uses it

- `Pure/PIDE/prover.scala` — the boundary itself, as above.
- `Pure/Thy/thy_header.scala` — `parse_header(...).map(Symbol.decode)`.
- `Pure/Thy/thy_syntax.scala` — `Symbol.decode(node_source) == node_source`
  as the "this file is already unicode" test.
- `Pure/Build/resources.scala` — decodes theory text before parsing spans.
- `Pure/Build/build_job.scala` — `Symbol.encode` on captured stdout;
  `YXML.bytes_of_body(xml, recode = Symbol.encode)`.
- `Pure/Build/browser_info.scala` — `HTML.text(Symbol.decode(text))` when
  generating html.
- `Pure/Tools/debugger.scala`, `print_operation.scala`, `dump.scala`,
  `profiling.scala` — all recode at their own edges.
- **this repo** — `MCP_Server.text_result` / `resource_contents` apply
  `Symbol.decode` outbound; `MCP_Session.encode_args` / `encode_names`
  pass `recode = Symbol.encode` inbound. Needed precisely because the mcp
  bridge rides `protocol_command_raw` / `Protocol_Output`, the
  un-recoded channel. See the spec's "symbol recoding at the client
  edge".

## Rules of thumb

- Recode at **one point per direction**, at the edge where text meets
  the outside world — not at each parse site. `decode`'s idempotence
  makes the late choke point safe; scattering it does not scale.
- Pass `recode` to the yxml serialiser/parser. Never post-process an
  assembled yxml string.
- Byte fidelity and client-facing rendering are different goals. Once
  you decode at an edge, the property below that edge is still byte
  identity, but *across* it becomes identity up to symbol
  normalisation — send `\<Longrightarrow>`, get `⟹` back. Assert byte
  fidelity below the edge, not through it.

## The `etc/symbols` table

Format: `\<name>  code: 0xNNNN  group: ...  abbrev: ...`. In
Isabelle2025-2: 512 entries, 440 carry a `code:`, 410 distinct code
points, and **none** in the unicode private use area — so decoded output
needs no special font. The ~72 without a `code:` (mostly `\<^const>`,
`\<^cterm>`, the ml antiquotation controls) pass through `decode`
unchanged, which is what you want: byte precision is preserved exactly
where it matters.

Control symbols are a known limit: `\<^sub>` / `\<^sup>` / `\<^bold>`
decode to the *marker* glyphs `⇩` / `⇧` / `❙` (U+21E9 / U+21E7 / U+2759),
not to typeset sub/superscripts. jEdit renders those with font styling;
unicode cannot express it.
