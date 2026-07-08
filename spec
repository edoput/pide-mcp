We are writing an implementation of a mcp for the isabelle proof assistant as an isabelle tool.

we want the mcp to allow users to write custom tools using the isabelle/ml language. this will allows for reuse of pre-existing tools implemented by the community.

previous tasks:

- [x] how can the isabelle/scala programming environment interact with isabelle/ml and viceversa
- [x] how to develop isabelle tools
- [x] how to build isabelle tools
- [x] how to test the build
- [x] how to test the interaction between isabelle/scala and isabelle/ml

the outcome of the current task is to write a local skill related to working with the isabelle/ml and isabelle/scala environment
(done: .claude/skills/isabelle-ml-scala/SKILL.md)

current task is to find how the following high-level questions

- [x] can we implement an MCP in isabelle/ml. what are the advantages
      (answer: not practically. ML has TCP streams (Socket_IO) and threads, but no JSON
      library, and its stdin/stdout are owned by the PIDE protocol when managed by Scala;
      a raw ML_process has free stdio but loses the document model and session management.
      ML's real advantage — direct access to prover internals — argues for writing the mcp
      *tools* in ML, not the server.)
- [x] can we implement an mcp in isabelle/scala. whare are the advantages
      (answer: yes — iq proves it. isabelle/scala has isabelle.JSON for JSON-RPC, full JVM
      sockets/threads, owns its stdio (stdio transport works), manages prover sessions
      (Headless PIDE, ML_Process), and ships as a regular isabelle tool. the bundled
      `isabelle server` (Pure/Tools/server.scala) is a direct precedent: resident TCP server,
      JSON arguments, commands extensible via the Server.Commands service.
      conclusion: server in scala, user tools in ML, bridged via the protocol_command /
      Scala.function machinery.)

mvp implementation and testing: see spec-mvp

more tasks

- [ ] how to register a mcp tool in isabelle/ml and have it availabe in isabelle/scala
- [ ] what kind of tool description/serialization between isabelle/ml and isabelle/scala

The project contains the following directories

- mcp our implementation
- Isabelle2025-2_linux the isabelle distribution
  - Isabelle2025-2_linux/Isabelle2025-2/src/Tools the tools distributed along with isabelle
  - Isabelle2025-2_linux/Isabelle2025-2/src/Tools/Demo
    Implementation of a isabelle/scala tool that is available as a system component
- iq an implementation of a mcp server in isabelle/scala

