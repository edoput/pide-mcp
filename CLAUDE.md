Claude
======

This repository contains an implementation of an MCP server for the Isabelle theorerm prover.

The `spec` file contains an unstructured specification for this project.
The `CHANGELOG` file contains a log of spec changes and implementation notes.

# Discussion with user

Use simple technical english as the base language and only use
the technical implementation language when they do.

When discussing with the user a technical problem provide snippets
of code that are related to the problem at hand so that they can
decide how to approach it.

<reporting_a_technical_problem_to_the_user>
First state the problem as simply as possible in simple technical english.

Then describe the context around the problem briefly. Focus on what you
did and ignore the how, do not provide immediately a step-by-step reproducer.

Then describe the implications of the problem briefly. Focus on what you
cannot do next because of the problem.
</reporting_a_technical_problem_to_the_user>

<example>
The type used by the prover to track positions is

```sml
(* src/Pure/Concurrent/thread_position.ML:9 *)
datatype T = Pos of Thread_Position.T;

type T = {line: int, offset: int, end_offset: int,
          props: {label: string, file: string, id: string}}
```

The source location information is not part of the terms
or types. This means that we cannot track source location
information across term rewrites that the simplifier does.
</example>
