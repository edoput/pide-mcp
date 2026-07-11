---
paths:
  - "plans/*"
---
# Start every implementation plan by creating tasks

Implementation plans live in `plans/` (one file per builtin tool, plus
umbrella plans for a wave or feature family — see `plans/README`). Each
plan carries numbered implementation steps and lettered/numbered
assumptions (`A1..`/`T1..`/`D1`) with their tests.

When starting to implement a plan — before touching any source file —
create tasks with TaskCreate that mirror the plan:

- one task per implementation step, in the plan's order, named after
  the step (e.g. "session_structure step 2: resource widening");
- one task for the plan's test obligations (or one per assumption
  group when they land in different layers of the pyramid);
- one final task for the close-out the plan's "done when" section
  requires (spec annotations, CHANGELOG entry, plans/README checkbox).

Keep the list live while working: set a task to in_progress when its
step begins, completed only when its verification (the step's tests,
or the rebuild rules' builds) is green. If implementation deviates
from the plan, update BOTH the task list and the plan file — the plan
is the record, the tasks are the tracker.

This applies to resuming a partially-implemented plan too: recreate
the remaining steps as tasks first, checking the plan's status header
for what already landed.
