### Isar integration: proof methods and attributes
Manual: `src/Doc/Implementation/Isar.thy` — the `Proof.state`
machinery, `Method.METHOD`, `Method.setup`, `Attrib.setup`, rule
attributes vs. declaration attributes.
Sources: `src/Pure/Isar/method.ML`, `src/Pure/Isar/attrib.ML`,
`src/Pure/Isar/proof.ML`, `src/Pure/Tools/rule_insts.ML` (method +
attribute examples), `src/Pure/Tools/named_theorems.ML` (a complete
small attribute/data package).
Worked example: `src/Pure/ex/Guess.thy` (a full proof command built on
`Obtain`).

### New Isar commands: outer syntax and the toplevel
Declare keywords in the theory header (`keywords "foo" :: thy_decl`),
then `Outer_Syntax.command`/`local_theory'`/`maintain_command` with a
parser from `Parse`.
Sources: `src/Pure/Isar/outer_syntax.ML`, `src/Pure/Isar/toplevel.ML`
(`Toplevel.keep`, `Toplevel.theory`, ...), `src/Pure/Isar/parse.ML` +
`src/Pure/Isar/parse_spec.ML` (parser combinators for outer syntax),
`src/Pure/Isar/token.ML`, `src/Pure/Isar/args.ML`.
Manual: `src/Doc/Implementation/Integration.thy` (Isar toplevel,
theory loader database); Isar-Ref `Outer_Syntax.thy`.
Worked example: `src/HOL/Examples/Commands.thy` — three commands
(diagnostic, theory-level, local-theory-level) in ~100 lines.
Local worked example: `mcp/Tools/MCP_Tools.thy` — Name_Space-backed
registries in Generic_Data, add/del declaration attribute,
clause-based commands (`mcp_tool`/`mcp_resource`), ML-hatch code
generation, and programmatic command execution, in one theory.

Hard-won facts (2026-07-11, all hit while building `mcp_tool`):
- **Command keywords delimit command spans** and can NEVER appear as
  inner tokens of another command, not even inside `[[...]]` or
  parentheses — the span scanner has no bracket awareness. Two
  consequences: an attribute cannot share a name with a command (the
  simproc attribute vs. `simproc_setup` precedent; our `mcp_tool`
  command forced the attribute to be `mcp_tools`), and a command name
  passed as an *argument* must be a quoted string
  (`mcp_tool "find_consts"`, like Pure's `help "..."`).
- **Every keyword declared in a header must have a command defined by
  theory end** ("Missing outer syntax command(s)" at `end` otherwise).
  You cannot reserve a keyword for later; define a stub that errors.
- **`ML_Context.expression` evaluates a *unit* expression** for its
  context effect; a generated expression that evaluates to a plain
  value (e.g. a `generic -> generic` function) compiles fine and is
  SILENTLY DISCARDED — the registration just doesn't happen. Follow
  `method_setup`'s idiom (src/Pure/Isar/method.ML): generate
  `Theory.local_setup (fn lthy => ...)` around the user source, then
  `Context.proof_map`. Wrap `ML_Syntax.make_binding` output in
  `ML_Syntax.atomic` when splicing it as an argument.
  Use when the Isar command receives ML text from a cartouche: consult
  `references/method-setup-idiom.md` for the full pattern — the Pure
  originals (`Method.method_setup`, `Attrib.attribute_setup`), the
  local `ml_declaration` generalization in `mcp/Tools/MCP_Tools.thy`,
  the splicing rules (user code as tokens via `ML_Lex.read_source`,
  data only through `ML_Syntax.print_*` printers), and a survey of
  every Pure command taking ML text, including the context-data-slot
  variant for extracting a compiled value back out.
- **Programmatic command execution**: `Outer_Syntax.parse_text thy
  (K thy) pos text` + `fold (Toplevel.command_exception false)` over
  `Toplevel.make_state (SOME thy)`. Parse at `Position.line 1`, NEVER
  `Position.none` — none erases all error positions; line 1 gives
  snippet-relative lines (linear: parsing at line N reports N+k).
  Mechanism (see "Source positions" above): `Position.none` is all
  zeros, and every accessor filters non-positive values away
  (`maybe_valid`, `position.ML:101-109`), so there is no line left for
  `Position.here` to print and no offset for `is_reported` to accept.
  `Position.line 1` is `line_file 1 ""` — line 1, no file — from which
  `Position.symbol` counts newlines forward.
  Capture output by routing `Private_Output.*_fn` through a
  group-keyed buffer table (see `MCP_Output` in
  `mcp/Tools/MCP_Tools.thy`; originally ir/ml_repl.ML's technique).
- Wrapping *arguments* of an existing command: commands take token
  streams in their own syntax, so a value spliced into an argument
  position must go in VERBATIM (e.g. `find_consts strict: "..."`) —
  wrapping it in a cartouche or quotes changes what the command
  parses (a quoted find_theorems criterion becomes a term pattern,
  and its limit option is `(40)`, not `(limit 40)`). Quote only
  genuinely value-shaped slots (terms → cartouche, strings → escaped
  inner string).
