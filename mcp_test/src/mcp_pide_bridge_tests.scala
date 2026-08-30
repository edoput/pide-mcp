/*  Title:      mcp_test/src/mcp_pide_bridge_tests.scala

Deterministic contracts for the extracted PIDE bridge boundary.
*/

package isabelle.mcp.pide

import isabelle.Bytes
import isabelle.mcp.MCP_Suite


class MCP_Pide_Bridge_Tests extends MCP_Suite {
  private def policy(maxPending: Int = 2): PideBridgePolicy =
    PideBridgePolicy.checked(
      maxPending = maxPending,
      callTimeoutSeconds = 5.0,
      drainTimeoutSeconds = 2.0,
      maxRequestBytes = 1024,
      maxReplyBytes = 4096).fold(errors => fail(errors.mkString(", ")), identity)

  private final case class TextOperation(name: String, request: String)
      extends BridgeOperation[String] {
    def requestPayload: Bytes = Bytes(request)
    def decodeReply(payload: Bytes): Either[String, String] =
      if (payload.text.startsWith("bad:")) Left(payload.text)
      else Right(payload.text)
  }

  test("bridge policy validates all named resource bounds together") {
    val errors =
      PideBridgePolicy.checked(
        maxPending = 0,
        callTimeoutSeconds = Double.NaN,
        drainTimeoutSeconds = Double.PositiveInfinity,
        maxRequestBytes = 0,
        maxReplyBytes = -1).swap.getOrElse(fail("invalid policy was accepted"))

    assertEquals(errors,
      List(
        "maxPending must be positive",
        "callTimeout must be finite and positive",
        "drainTimeout must be finite and positive",
        "maxRequestBytes must be positive",
        "maxReplyBytes must be positive"))
  }

  test("bridge call ids come from an independent per-bridge string generator") {
    val clientId = "client-request-0"
    val generator = BridgeCallIdGenerator.test("bridge-instance")
    val first = BridgeCallId.value(generator.next())
    val second = BridgeCallId.value(generator.next())

    assertEquals(first, "bridge-instance:0")
    assertEquals(second, "bridge-instance:1")
    assertNotEquals(first, clientId)
    assertNotEquals(second, clientId)
  }

  spec_test("heterogeneous pending calls resolve reverse-order replies by internal id",
      covers = List("pide_bridge#T1")) {
    val registry = new PendingRegistry(policy().maxPending)
    val firstId = BridgeCallId.test("internal-first")
    val secondId = BridgeCallId.test("internal-second")
    val first = registry.register(firstId, TextOperation("first", "request-1"))
      .fold(failure => fail(failure.message), identity)
    val second = registry.register(secondId, TextOperation("second", "request-2"))
      .fold(failure => fail(failure.message), identity)

    assertEquals(registry.complete(secondId, "second", Bytes("reply-2")),
      PendingRegistry.Completed)
    assertEquals(registry.complete(firstId, "first", Bytes("reply-1")),
      PendingRegistry.Completed)
    assertEquals(second.result, Right("reply-2"))
    assertEquals(first.result, Right("reply-1"))
    assertEquals(registry.size, 0)
  }

  test("known invalid replies fail only their owner and unowned replies diagnose") {
    val recorded = scala.collection.mutable.ListBuffer.empty[PendingRegistry.Diagnostic]
    val diagnostics = new PendingRegistry.Diagnostics {
      def report(diagnostic: PendingRegistry.Diagnostic): Unit = recorded += diagnostic
    }
    val registry = new PendingRegistry(policy().maxPending, diagnostics)
    val ownedId = BridgeCallId.test("owned")
    val otherId = BridgeCallId.test("other")
    val owned = registry.register(ownedId, TextOperation("expected", "request"))
      .fold(failure => fail(failure.message), identity)

    assertEquals(registry.complete(ownedId, "wrong", Bytes("reply")),
      PendingRegistry.Rejected)
    owned.result match {
      case Left(BridgeFailure.ProtocolError(message)) =>
        assert(message.contains("does not match"))
      case result => fail("expected ProtocolError, got " + result)
    }
    assertEquals(registry.complete(ownedId, "expected", Bytes("late")),
      PendingRegistry.Unowned)
    assertEquals(registry.complete(otherId, "expected", Bytes("unknown")),
      PendingRegistry.Unowned)
    assertEquals(recorded.toList,
      List(
        PendingRegistry.UnownedReply(ownedId, "expected"),
        PendingRegistry.UnownedReply(otherId, "expected")))

    val rejectedId = BridgeCallId.test("rejected")
    val rejected = registry.register(rejectedId, TextOperation("decoder", "request"))
      .fold(failure => fail(failure.message), identity)
    assertEquals(registry.complete(rejectedId, "decoder", Bytes("bad: malformed")),
      PendingRegistry.Rejected)
    rejected.result match {
      case Left(BridgeFailure.ProtocolError(message)) =>
        assert(message.contains("malformed"))
      case result => fail("expected returned ProtocolError, got " + result)
    }

    val throwingId = BridgeCallId.test("throwing")
    val throwing = registry.register(throwingId, new BridgeOperation[String] {
      val name = "throwing"
      val requestPayload = Bytes.empty
      def decodeReply(payload: Bytes): Either[String, String] =
        throw new IllegalArgumentException("invalid body")
    }).fold(failure => fail(failure.message), identity)
    assertEquals(registry.complete(throwingId, "throwing", Bytes("body")),
      PendingRegistry.Rejected)
    throwing.result match {
      case Left(BridgeFailure.ProtocolError(message)) =>
        assert(message.contains("invalid body"))
      case result => fail("expected decoder ProtocolError, got " + result)
    }
    assertEquals(registry.size, 0)
  }

  spec_test("pending admission is bounded without a queue and recovers capacity",
      covers = List("pide_bridge#T7")) {
    val registry = new PendingRegistry(policy(maxPending = 1).maxPending)
    val firstId = BridgeCallId.test("first")
    val secondId = BridgeCallId.test("second")
    val first = registry.register(firstId, TextOperation("op", "request"))
      .fold(failure => fail(failure.message), identity)

    assertEquals(registry.register(secondId, TextOperation("op", "request")),
      Left(BridgeFailure.Overloaded(1)))
    assertEquals(registry.size, 1)
    assert(registry.fail(firstId, BridgeFailure.Cancelled))
    assertEquals(first.result, Left(BridgeFailure.Cancelled))
    assert(registry.register(secondId, TextOperation("op", "request")).isRight)
  }

  test("checkpoint-1 characterization names all legacy operations and change events") {
    assertEquals(
      LegacyWire.operations.map(operation =>
        (operation.name, operation.command, operation.resultFunction,
          operation.argumentNames, operation.replyShape)),
      List(
        ("tools", "MCP.tools", "MCP.tools_result",
          List("id", "designation", "bundles_yxml"), LegacyWire.ReplyShape.ToolsYxml),
        ("theories", "MCP.theories", "MCP.theories_result",
          List("id"), LegacyWire.ReplyShape.TheoriesYxml),
        ("run_tool", "MCP.run_tool", "MCP.run_tool_result",
          List("id", "designation", "bundles_yxml", "name", "args_yxml"),
          LegacyWire.ReplyShape.StatusText),
        ("check_designation", "MCP.check_designation", "MCP.check_designation_result",
          List("id", "designation", "bundles_yxml"), LegacyWire.ReplyShape.StatusText),
        ("ir", "MCP.ir", "MCP.ir_result",
          List("id", "fname", "args_yxml"), LegacyWire.ReplyShape.StatusYxml),
        ("resources", "MCP.resources", "MCP.resources_result",
          List("id", "designation"), LegacyWire.ReplyShape.ResourcesYxml),
        ("read_resource", "MCP.read_resource", "MCP.read_resource_result",
          List("id", "designation", "name"), LegacyWire.ReplyShape.StatusText)))
    assertEquals(LegacyWire.changes.map(change => (change.function, change.event)),
      List(("MCP.tools_changed", "tools"), ("MCP.resources_changed", "resources")))
    assertEquals(LegacyWire.operations.map(_.name).distinct.size, 7)

    assertEquals(
      LegacyWire.arguments(LegacyWire.Ir,
        "args_yxml" -> Bytes("args"), "id" -> Bytes("id"), "fname" -> Bytes("fn"))
        .map(_.text),
      List("id", "fn", "args"))
    intercept[IllegalArgumentException] {
      LegacyWire.arguments(LegacyWire.Ir, "id" -> Bytes("id"), "fname" -> Bytes("fn"))
    }
  }
}
