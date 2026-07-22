# The method_setup idiom: compiling user ML from a cartouche into a registration

Use when an Isar command receives ML text from a cartouche (an
`Input.source`) and must turn it into a registered value (a tool, a
method, an attribute, ...). The text is not a value — it must go
through the ML compiler in the right context, and the sanctioned entry
point is `ML_Context.expression`.

## The contract that forces the shape

`ML_Context.expression` evaluates the compiled tokens as a **unit
expression for its context effect**; the expression's plain value is
SILENTLY DISCARDED. A generated expression that evaluates to, say, a
`generic -> generic` function compiles fine and does nothing — the
registration just doesn't happen. So the generated source itself must
perform the registration as a side effect: wrap the user's code in
`Theory.local_setup (...)` and run the whole thing through
`Context.proof_map`.

## The canonical instances in Pure

`Method.method_setup` (`src/Pure/Isar/method.ML`, near the end):

```sml
fun method_setup binding source comment =
  ML_Context.expression (Input.pos_of source)
    (ML_Lex.read
      ("Theory.local_setup (Method.local_setup (" ^ ML_Syntax.make_binding binding ^ ") (") @
     ML_Lex.read_source source @ ML_Lex.read (")" ^ ML_Syntax.print_string comment ^ ")"))
  |> Context.proof_map;
```

`Attrib.attribute_setup` (`src/Pure/Isar/attrib.ML`) is the same shape;
`simproc_setup` is a further relative.

## The local generalization

`mcp/Tools/MCP_Tools.thy` factors the shape into head/tail strings:

```sml
fun ml_declaration head source tail =
  ML_Context.expression (Input.pos_of source)
    (ML_Lex.read ("Theory.local_setup (fn lthy => #2 (" ^ head ^ " (") @
      ML_Lex.read_source source @
      ML_Lex.read (")" ^ tail ^ " lthy))"))
  |> Context.proof_map;
```

with `print_param` (same theory) rendering registration-time data as ML
source via `ML_Syntax.print_string` / `print_option` — the role
`ML_Syntax.make_binding` / `print_string` play in Pure.

## Why the string-joining is safe (and its rules)

Two different things are spliced, each through the safe mechanism for
its kind:

- **User code goes in as tokens**, via `ML_Lex.read_source source` —
  never string concatenation. This preserves source positions (errors
  point at the cartouche in the theory file) and never re-lexes user
  text out of a synthetic string. Pass `Input.pos_of source` as the
  position for the same reason.
- **Registration-time data goes through `ML_Syntax.print_*` printers**
  (`print_string`, `print_option`, `print_list`, ...), which emit
  properly escaped ML literals — the ML analog of parameterized
  quoting; no injection path through the data. Only the fixed
  scaffolding is a raw string, and that is entirely under your control.

Rules of the idiom:

- Every dynamic field spliced into the generated source MUST go through
  an `ML_Syntax.print_*` printer; never bare concatenation.
  (`Bool.toString` is tolerable only because bools cannot contain
  quotes.) If a field becomes structured, extend the printer stack —
  don't hand-format.
- Wrap `ML_Syntax.make_binding` output in `ML_Syntax.atomic` when
  splicing it as a function argument.
- Remember the silent-discard failure mode: if the registration
  "just doesn't happen", check that the generated expression is a unit
  expression performing `Theory.local_setup`, not one returning a
  function.

## Survey: every Pure command that accepts ML text, by approach

(From grepping `Parse.ML_source` / `ML_Context.expression` consumers in
`src/Pure`; cross-checked against Isar-Ref `src/Doc/Isar_Ref/Spec.thy`,
command tables around lines 421, 1136-1149, 1519.)

**Approach 1 — evaluate directly, no wrapping** (`ML_Context.exec` +
`ML_Context.eval_source`, `src/Pure/Pure.thy` ~248-299): `ML`,
`ML_prf`, `ML_val`, `ML_command`, `ML_file`, `ML_export`,
`SML_export`/`SML_import`. The cartouche is arbitrary ML
*declarations*, not a value of an expected type — nothing to wrap;
context effects happen only if the user code calls `Context.>>`.

**Approach 2 — this idiom**: every command expecting the cartouche to
denote a value of a specific type that must be REGISTERED. All in
`src/Pure/Isar/isar_cmd.ML` unless noted:

- `setup` / `local_setup` (isar_cmd.ML:54,59) — minimal form: wraps
  `Theory.setup (...)` / `Theory.local_setup (...)`, nothing spliced.
- `parse_ast_translation`, `parse_translation`, `print_translation`,
  `typed_print_translation`, `print_ast_translation`
  (isar_cmd.ML:67-95) — `Theory.setup (Sign.X (...))`.
- `declaration` / `syntax_declaration` (isar_cmd.ML:158) — closest
  cousin to `print_param`: splices a printed record (`Bool.toString`
  flags, `ML_Syntax.print_position`).
- `method_setup` / `attribute_setup` (method.ML, attrib.ML) — the
  canonical instances above.
- `simproc_setup` (simplifier.ML:216) — heaviest data printing: a full
  printed record incl. `ML_Syntax.print_strings` and a printed binding.
- `oracle` (isar_cmd.ML:145) — instructive variant: generates a `val`
  DECLARATION, `val name = snd (Theory.setup_result (Thm.add_oracle
  (binding, <source>)))`, so one compilation both registers the oracle
  and binds the resulting function into the ML environment.

**Approach 3 — the context-data-slot trick, for getting a value OUT.**
`ML_Context.expression` discards values, so callers that need the
compiled value back generate code that stores it in a dedicated data
slot, then read it out immediately after:

- the `tactic` method text (`Method.parse_tactic`, method.ML:326-339):
  wraps the source in `Context.>> (Method.set_tactic (fn morphism =>
  fn facts => (...)))`, then reads it back via `the_tactic`
  (the `set_tactic`/`the_tactic` slot pair, method.ML:320-324).
- the `cartouche` antiquotation in `generated_files.ML:427-435` — same
  shape with `Generated_Files.set_string` / `the_string`.

## Choosing between splicing data in and slotting values out

Pure uses the data-slot pattern only for EXTRACTION (approach 3), never
for injecting registration-time data — for that, `ML_Syntax` printing
wins: the data is simple (strings, bools, options), the printers render
it exactly, and the printed form keeps the generated expression
self-contained and inspectable. Follow suit: print data in unless it
genuinely cannot be rendered as ML syntax; use a set/get context-data
slot only when you need the compiled value back.
