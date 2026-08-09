export const meta = {
  name: 'spec-hygiene-refids',
  description: 'Assign dated decision IDs to spec sections, resolve each plan true status, then cross-link the CHANGELOG',
  phases: [
    { title: 'Read', detail: 'spec section IDs + plan statuses, in parallel (sonnet/low)' },
    { title: 'Link', detail: 'CHANGELOG cross-refs, needs the spec IDs (sonnet/low)' },
  ],
}

// Agents READ and return mappings; the orchestrator APPLIES and VERIFIES.
// Nothing here edits a source file -- an agent that rewrites `spec` in place
// cannot be reviewed before it lands, and `spec` has uncommitted user edits
// waiting to merge in the main tree.
const WT = '/home/edoput/repo/isabelle-mcp/.claude/worktrees/spec-hygiene'
const OUT = `${WT}/tools/refdata`

const RULES = `
DO NOT call the advisor tool. DO NOT spawn subagents. DO NOT edit any file
under spec, CHANGELOG or plans/ -- you only WRITE THE ONE JSON FILE named in
your task. The orchestrator applies your mapping after review.

Validate before finishing:  python3 -m json.tool <your-file> > /dev/null
Return only the requested summary object; it is read by a script.
`

phase('Read')

const specIds = agent(`Assign a stable ID to every section of an Isabelle-MCP project spec.

${RULES}

FILE: ${WT}/spec  (3761 lines). Section headings are underlined with a rule of
--- or ===; the line ABOVE the rule is the heading. There are 55 of them. A
heading may wrap onto two lines, with a parenthesised date on the second.

THE SCHEME, decided by the project owner:

  Sections that record a DECISION get   D-<date>-<slug>
  Sections that are narrative/structure  S-<slug>

<date> is the decision's own date in YYYY-MM-DD, taken from the heading when it
carries one ("(decided 2026-07-21)", "(measured 2026-07-30)", "(added
2026-07-07)") or from the section body when the heading does not. If a section
plainly records a decision but NO date can be found anywhere in it, use
D-undated-<slug> and say so in the record's note.

<slug> is lowercase, hyphenated, from the heading's own words, <= 5 words, no
articles. "server startup and readiness (decided 2026-07-21)" ->
D-2026-07-21-server-startup-readiness. Keep the words the heading uses; do not
paraphrase. Slugs must be unique across the whole file.

A section is a DECISION if it chooses between alternatives, records a
measurement that settled something, states a policy, or supersedes/withdraws an
earlier position. It is NARRATIVE if it is a goal statement, a directory
listing, an implementation order, a test inventory, an acceptance-criteria
list, or an out-of-scope list.

Write ${OUT}/spec-ids.json:
{ "sections": [ {
    "line": 412,                       // line of the heading itself
    "heading": "server startup and readiness (decided 2026-07-21)",
    "id": "D-2026-07-21-server-startup-readiness",
    "kind": "decision" | "narrative",
    "date": "2026-07-21" | null,
    "phase": "phase 0" | "phase 1" | "phase 2" | "phase 3" | "phase 4+" | "cross-cutting",
    "supersedes": ["D-..."],           // ids from THIS file only, [] if none
    "superseded_by": ["D-..."],
    "one_line": "what this section settles, <= 120 chars",
    "note": null
} ] }

Every one of the 55 headings must appear exactly once. Read the whole file:
supersedes/superseded_by are stated in prose ("supersedes this section's
original follow-up", "withdrawn", "rejected earlier, kept for the record", "do
not revisit this approach"), not with tags, and getting those links right is
the main value of this task.`,
  { label: 'spec ids (sonnet/low)', phase: 'Read', model: 'sonnet', effort: 'low',
    schema: { type: 'object', required: ['output_file', 'validated', 'count'],
      properties: { output_file: { type: 'string' }, validated: { type: 'boolean' },
        count: { type: 'integer' }, decisions: { type: 'integer' },
        undated: { type: 'array', items: { type: 'string' } },
        notable: { type: 'array', items: { type: 'string' } } } } })

const planStatus = agent(`Resolve the TRUE status of every plan in an Isabelle-MCP project.

${RULES}

DIRECTORY: ${WT}/plans/  (51 files plus README and ASSUMPTIONS -- skip those two)

THE PROBLEM you are resolving: a plan's status is currently recorded in up to
three places that disagree.
  - plans/README carries a "- [x]" / "- [ ]" checkbox per plan
  - the plan's own header, around line 4, carries "status: planned (2026-07-09)"
  - many plans carry a SECOND "status: done (2026-07-10)" line further down,
    written when the work actually landed
The header is usually the stale one: it still says "planned" for tools that
shipped weeks ago. 21 of 45 plans disagree with the README this way.

For each plan file, read ALL of its "status:" lines plus plans/README, and
decide the one true current status. The LATEST status line in the file is
normally authoritative over the header; the README checkbox is a third opinion
and is sometimes the correct one.

Write ${OUT}/plan-status.json:
{ "plans": [ {
    "plan": "repl_list",               // filename exactly
    "status": "done" | "planned" | "blocked" | "partial" | "superseded",
    "date": "2026-07-09" | null,       // when it reached that status
    "evidence": "which line(s) you concluded from, quoted briefly",
    "header_says": "planned",          // the line-4 value, verbatim word
    "readme_says": "done" | "planned" | "absent",
    "later_says": "done" | null,       // the last in-file status: line, if any
    "disagreement": true | false,      // do the three sources conflict?
    "wave": "wave 1" | null,           // from plans/README's index
    "blocked_on": "what blocks it, if blocked" | null,
    "note": null
} ] }

All 51 plans must appear. Six have no README checkbox at all (builtin_activation,
mcp_tool_command, mcp_tool_registry, recap, resource_tool_mirrors, tool_scope) --
set readme_says "absent" for those and still resolve a status.

Be careful with plans/repl_list: it is the pilot, its README box is [x], and
BOTH its status lines say "planned". Report exactly what you find; do not
smooth it over.`,
  { label: 'plan status (sonnet/low)', phase: 'Read', model: 'sonnet', effort: 'low',
    schema: { type: 'object', required: ['output_file', 'validated', 'count'],
      properties: { output_file: { type: 'string' }, validated: { type: 'boolean' },
        count: { type: 'integer' }, disagreements: { type: 'integer' },
        by_status: { type: 'object', additionalProperties: { type: 'integer' } },
        notable: { type: 'array', items: { type: 'string' } } } } })

const [ids, statuses] = await Promise.all([specIds, planStatus])
log(`spec: ${ids?.count} sections (${ids?.decisions} decisions); plans: ${statuses?.count} (${statuses?.disagreements} disagree)`)

phase('Link')

const changelog = await agent(`Cross-link an Isabelle-MCP CHANGELOG to the spec IDs just assigned.

${RULES}

FILES:
  ${WT}/CHANGELOG          2454 lines, 52 dated entries
  ${OUT}/spec-ids.json     the spec section IDs, already assigned -- READ THIS
                           FIRST and use its "id" values VERBATIM

TWO TRAPS, both real and both easy to get backwards:
  1. The file is NEWEST-FIRST, and so is each day within it. 12 dates carry more
     than one entry (2026-07-13 and 2026-07-10 carry ten each). So within one
     date the entry that appears ABOVE is the LATER event. An entry can retract
     the entry printed below it.
  2. One entry is headed "(earlier)" and belongs before its neighbours.

For each dated entry, produce a record. Give each an ID of the form
CL-<date>-<n> where <n> counts from 1 for the EARLIEST entry of that date --
i.e. counting UP the file, not down it, so IDs run chronologically.

Write ${OUT}/changelog-ids.json:
{ "entries": [ {
    "id": "CL-2026-07-10-1",
    "line": 1234,                      // line of the dated header
    "date": "2026-07-10",
    "seq_in_day": 1,                   // 1 = earliest that day
    "title": "short title from the entry",
    "kind": "spec-decision" | "implementation" | "bugfix" | "retraction" | "measurement" | "infrastructure" | "testing" | "process",
    "spec_ids": ["D-2026-07-21-..."],  // from spec-ids.json ONLY, [] if none fit
    "plans": ["readiness"],            // plan filenames, [] if none
    "reverses": "CL-..." | null,       // another entry this walks back
    "note": null
} ] }

All 52 entries. The retraction chains are the point: 2026-07-10 contains a gap
that was declared and retracted the SAME DAY, and 2026-08-05 withdraws an
approach recorded only in plans/. Get "reverses" right.`,
  { label: 'changelog links (sonnet/low)', phase: 'Link', model: 'sonnet', effort: 'low',
    schema: { type: 'object', required: ['output_file', 'validated', 'count'],
      properties: { output_file: { type: 'string' }, validated: { type: 'boolean' },
        count: { type: 'integer' }, reversals: { type: 'integer' },
        unlinked: { type: 'integer' },
        notable: { type: 'array', items: { type: 'string' } } } } })

return { specIds: ids, planStatus: statuses, changelog }
