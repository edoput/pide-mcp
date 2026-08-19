/*  Title:      mcp_test/src/mcp_dynamic_call_targets.scala

Reflection targets for MCP.dynamic_call that live in OUR OWN jars, so the
mechanism can be exercised without any third-party component installed
(plans/scala_mcp_tool T1, and that plan's "must not become a test
dependency" note).

They live in mcp_test rather than mcp because mcp.jar ships no test code
(see .claude/rules/scala-rebuild.md). That costs nothing: mcp_test is a
registered component, so mcp_test.jar is on the same classpath and
reflection reaches these exactly as it reaches a stranger's jar --
which, for a test of a reflective mechanism, is the point.
*/

package isabelle.mcp

import isabelle._


object Self_Test {
  def echo(text: String): String = text

  /* Proves the AMBIENT fill handed over a REAL snapshot rather than an
     empty one -- the claim plans/scala_mcp_tool A4 rests on. */
  def snapshot_info(snapshot: Document.Snapshot): String = {
    val commands = snapshot.node.commands.toList
    "node=" + snapshot.node_name.node +
      " theory=" + snapshot.node_name.theory +
      " commands=" + commands.length +
      " source_chars=" + snapshot.node.source.length +
      " spans=[" + commands.filter(_.span.name.nonEmpty).map(_.span.name).mkString(",") + "]"
  }

  /* Two methods of the same name, so D4's overload rejection has
     something real to reject. */
  def overloaded(text: String): String = text
  def overloaded(text: String, other: String): String = text + other
}
