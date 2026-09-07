/*  Title:      mcp_test/src/mcp_pide_payload_measure.scala

Deterministic, fixture-backed measurements for the PIDE bridge envelopes.
*/

package isabelle.mcp

import isabelle._
import isabelle.mcp.pide.{BridgeOperation, PideBridgeV1}

import java.nio.charset.StandardCharsets
import java.nio.file.{AtomicMoveNotSupportedException, Files, StandardCopyOption}


object MCP_Pide_Payload_Measure {
  val schema = "isabelle-mcp.pide-bridge-payloads/v2"
  val corpus_revision = "2026-09-07.1"
  val large_request_source_text_bytes = 89332L
  val theory_source_reply_bytes = 380170L

  final case class Case(
    name: String,
    direction: String,
    operation: String,
    category: String,
    bytes: Long,
    source_text_bytes: Option[Long] = None)

  final case class Corpus(
    corpus_revision: String,
    bridge_revision: String,
    cases: List[Case],
    request_bytes: Long,
    reply_bytes: Long,
    maxRequestBytes: Long,
    maxReplyBytes: Long)

  private val request_category = "operation_request"
  private val large_categories = Set(
    "tool_catalog", "theory_source_text", "documentation_text", "structured_prover_output")
  private val context = "isabelle://context/theory/HOL.Main"

  private def deterministic_ascii_source(label: String, bytes: Long): String = {
    require(bytes > 0 && bytes <= Int.MaxValue,
      "fixture source-text byte count must fit in a positive Int")
    val prefix = "(* " + label + "; deterministic ASCII fixture *)\\n"
    require(prefix.length <= bytes,
      "fixture source-text byte count is smaller than its prefix")
    val result = prefix + List.fill((bytes - prefix.length).toInt)("x").mkString
    require(result.getBytes(StandardCharsets.US_ASCII).length == bytes,
      "fixture source text did not retain its reviewed ASCII byte count")
    result
  }

  def default_for(maximum: Long): Long = {
    require(maximum > 0, "payload maximum must be positive")
    val doubled = Math.multiplyExact(maximum, 2L)
    var rounded = 1L
    while (rounded < doubled) {
      if (rounded > Long.MaxValue / 2L)
        throw new IllegalArgumentException("payload default overflows Long")
      rounded = Math.multiplyExact(rounded, 2L)
    }
    rounded
  }

  private def request(name: String, operation: BridgeOperation[?],
    source_text_bytes: Option[Long] = None): Case = {
    val arguments = PideBridgeV1.call("measure:request", "HOL.Main", operation.name,
      operation.requestPayload).arguments
    require(arguments.lengthCompare(1) == 0,
      "PideBridgeV1 did not produce one canonical request argument")
    Case(name, "request", operation.name, request_category, arguments.head.size, source_text_bytes)
  }

  private val encode_annotations = XML.Encode.pair(XML.Encode.option(XML.Encode.bool),
    XML.Encode.pair(XML.Encode.option(XML.Encode.bool),
      XML.Encode.pair(XML.Encode.option(XML.Encode.bool), XML.Encode.option(XML.Encode.bool))))

  /* This is the inverse of MCP_Session.decode_tools' established domain
     codec.  The fixture intentionally has no parameters, so it exercises no
     unshared Ptyp encoder; decodeReply below proves it remains a legitimate
     current tools reply. */
  private def encode_tool_row(row: MCP_Session.Tool_Row): XML.Body =
    XML.Encode.pair(XML.Encode.string,
      XML.Encode.pair(XML.Encode.string,
        XML.Encode.pair(XML.Encode.string,
          XML.Encode.pair(XML.Encode.list((_: MCP_Session.Tool_Param) => Nil),
            encode_annotations))))(
      (row.name, (row.description, (row.form, (row.params,
        (row.annotations.read_only, (row.annotations.idempotent,
          (row.annotations.destructive, row.annotations.open_world))))))))

  private def tool_catalog_payload: XML.Body = {
    val rows = (1 to 24).toList.map(index =>
      MCP_Session.Tool_Row(
        "Fixture.tool_" + f"$index%02d",
        "Deterministic tool-catalog fixture row " + index + ": " + ("description " * 12),
        "string_fun", Nil, MCP_Session.Tool_Annotations.default))
    XML.Encode.pair(XML.Encode.list(encode_tool_row),
      XML.Encode.list(XML.Encode.pair(XML.Encode.string, XML.Encode.bool)))(
        (rows, List("repl_list" -> true, "theory_read" -> false)))
  }

  private def status_text(text: String): XML.Body =
    XML.Encode.pair(XML.Encode.string, XML.Encode.string)(("ok", text))

  private def theories_payload: XML.Body =
    XML.Encode.list(XML.Encode.string)(List("HOL", "HOL.Main", "Fixture_Source"))

  private def resources_payload: XML.Body =
    XML.Encode.list(XML.Encode.pair(XML.Encode.string, XML.Encode.string))(
      List(
        "isabelle://resource/Fixture/one" -> "Fixture resource one",
        "isabelle://resource/Fixture/two" -> "Fixture resource two"))

  private def reply(name: String, category: String, operation: BridgeOperation[?],
    payload: XML.Body, source_text_bytes: Option[Long] = None): Case = {
    require(operation.decodeReply(payload).isRight,
      "fixture is not accepted by the current " + operation.name + " reply codec")
    val body = PideBridgeV1.result("measure:reply", operation.name, "ok", payload).body
    Case(name, "reply", operation.name, category, body.size, source_text_bytes)
  }

  def corpus: Corpus = {
    val large_request_source = deterministic_ascii_source(
      "MCP_Repl_Tests.thy reviewed source-text basis", large_request_source_text_bytes)
    val requests = List(
      request("request.check_context", McpBridgeOperations.checkContext(Some(context))),
      request("request.ir", McpBridgeOperations.ir("term", List("term" -> "x + y"))),
      request("request.read_resource", McpBridgeOperations.readResource(context, "isar-ref")),
      request("request.ir_large_source_text",
        McpBridgeOperations.ir("step", List(
          "repl" -> "Fixture",
          "isar_text" -> large_request_source)),
        Some(large_request_source_text_bytes)),
      request("request.resources", McpBridgeOperations.resources(context)),
      request("request.run_tool", McpBridgeOperations.runTool(context, "check_theory",
        List("theory" -> "HOL.Main"))),
      request("request.theories", McpBridgeOperations.theories),
      request("request.tools", McpBridgeOperations.tools(context)))

    val source_text = deterministic_ascii_source(
      "Henstock_Kurzweil_Integration.thy reviewed source-text basis", theory_source_reply_bytes)
    val documentation_text =
      "# Fixture documentation\\n\\n" +
        List.fill(48)("This deterministic documentation paragraph records bridge payload shape.\\n").mkString
    val prover_output =
      List.fill(36)("proof state: goal (1 subgoal): x = x; method simp succeeded\\n").mkString
    val replies = List(
      reply("reply.check_context", "operation_reply",
        McpBridgeOperations.checkContext(Some(context)), status_text("HOL.Main")),
      reply("reply.documentation_text", "documentation_text",
        McpBridgeOperations.readResource(context, "fixture-documentation"), status_text(documentation_text)),
      reply("reply.ir", "operation_reply",
        McpBridgeOperations.ir("term", List("term" -> "x + y")), status_text("x + y :: nat")),
      reply("reply.resources", "operation_reply",
        McpBridgeOperations.resources(context), resources_payload),
      reply("reply.structured_prover_output", "structured_prover_output",
        McpBridgeOperations.runTool(context, "fixture_prover", Nil), status_text(prover_output)),
      reply("reply.theory_source_text", "theory_source_text",
        McpBridgeOperations.readResource(context, "Fixture_Source"), status_text(source_text),
        Some(theory_source_reply_bytes)),
      reply("reply.theories", "operation_reply",
        McpBridgeOperations.theories, theories_payload),
      reply("reply.tool_catalog", "tool_catalog", McpBridgeOperations.tools(context), tool_catalog_payload))
    val all = (requests ::: replies).sortBy(_.name)
    validate(all)
    val request_maximum = all.collect {
      case measured if measured.direction == "request" => measured.bytes
    }.max
    val reply_maximum = all.collect {
      case measured if measured.direction == "reply" => measured.bytes
    }.max
    Corpus(corpus_revision, PideBridgeV1.revision, all, request_maximum, reply_maximum,
      default_for(request_maximum), default_for(reply_maximum))
  }

  def validate(cases: List[Case]): Unit = {
    require(cases.nonEmpty, "payload corpus must not be empty")
    require(cases == cases.sortBy(_.name), "payload cases must be sorted by name")
    val duplicate_names = cases.groupBy(_.name).collect {
      case (name, values) if values.lengthCompare(1) > 0 => name
    }.toList.sorted
    require(duplicate_names.isEmpty, "duplicate payload case names: " + duplicate_names.mkString(", "))
    require(cases.forall(measured => measured.bytes > 0), "payload sizes must be positive")
    require(cases.forall(_.source_text_bytes.forall(_ > 0)),
      "source-text byte bases must be positive")
    require(cases.forall(measured => Set("request", "reply")(measured.direction)),
      "payload directions must be request or reply")
    val requests = cases.filter(_.direction == "request")
    require(requests.map(_.operation).toSet == McpBridgeOperations.operationNames,
      "request cases must cover every current operation")
    val replies = cases.filter(_.direction == "reply")
    require(replies.map(_.operation).toSet == McpBridgeOperations.operationNames,
      "reply cases must cover every current operation")
    val categories = replies.map(_.category).toSet
    require(large_categories.subsetOf(categories),
      "reply cases must cover large categories: " + (large_categories -- categories).toList.sorted.mkString(", "))
  }

  def json(value: Corpus = corpus): String = {
    validate(value.cases)
    val request_maximum = value.cases.collect {
      case measured if measured.direction == "request" => measured.bytes
    }.max
    val reply_maximum = value.cases.collect {
      case measured if measured.direction == "reply" => measured.bytes
    }.max
    require(value.request_bytes == request_maximum && value.reply_bytes == reply_maximum,
      "payload maxima must agree with cases")
    require(value.maxRequestBytes == default_for(request_maximum) &&
      value.maxReplyBytes == default_for(reply_maximum),
      "payload defaults must derive from maxima")
    JSON.Format(JSON.Object(
      "schema" -> schema,
      "bridge_revision" -> value.bridge_revision,
      "corpus_revision" -> value.corpus_revision,
      "cases" -> value.cases.map(measured => JSON.Object(
        "name" -> measured.name,
        "direction" -> measured.direction,
        "operation" -> measured.operation,
        "category" -> measured.category,
        "bytes" -> measured.bytes,
        "source_text_bytes" -> measured.source_text_bytes.getOrElse(0L))),
      "maxima" -> JSON.Object(
        "request_bytes" -> value.request_bytes,
        "reply_bytes" -> value.reply_bytes),
      "defaults" -> JSON.Object(
        "maxRequestBytes" -> value.maxRequestBytes,
        "maxReplyBytes" -> value.maxReplyBytes),
      "provenance" -> JSON.Object(
        "large_request_source_text" -> JSON.Object(
          "bytes" -> large_request_source_text_bytes,
          "observed_path" -> "mcp/Tools/HOL/Tests/MCP_Repl_Tests.thy",
          "observation" -> "current largest repository theory; committed deterministic ASCII fixture"),
        "theory_source_reply" -> JSON.Object(
          "bytes" -> theory_source_reply_bytes,
          "observed_path" -> "HOL/Analysis/Henstock_Kurzweil_Integration.thy",
          "observation" -> "current largest shipped Isabelle2025-2 theory; committed deterministic ASCII fixture"),
        "default_rule" -> "next power of two at or above twice each serialized maximum"))) + "\n"
  }

  def default_path: Path =
    Option(System.getenv("ISABELLE_MCP_PAYLOAD_ARTIFACT")).getOrElse("") match {
      case "" => Path.explode("$ISABELLE_MCP_HOME/../plans/evidence/pide_bridge_payloads.json")
      case path => Path.explode(path)
    }

  def write(path: Path, text: String = json()): Unit = {
    val target = path.file.toPath.toAbsolutePath.normalize
    val parent = target.getParent
    require(parent != null, "payload artifact path has no parent: " + path)
    Files.createDirectories(parent)
    val temporary = Files.createTempFile(parent, ".pide-bridge-payloads-", ".tmp")
    try {
      Files.writeString(temporary, text, StandardCharsets.UTF_8)
      try Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING)
      catch {
        case _: AtomicMoveNotSupportedException =>
          Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
      }
    }
    finally { Files.deleteIfExists(temporary) }
  }

  def check(path: Path, text: String = json()): Unit = {
    val target = path.file.toPath
    require(Files.isRegularFile(target), "missing payload artifact: " + path)
    val actual = new String(Files.readAllBytes(target), StandardCharsets.UTF_8)
    require(actual == text, "stale payload artifact: " + path)
  }

  val isabelle_tool =
    Isabelle_Tool("mcp_pide_measure", "measure deterministic PIDE bridge payload fixtures",
      Scala_Project.here,
    { args =>
      var check_only = false
      val getopts = Getopts("""
Usage: isabelle mcp_pide_measure [OPTIONS]

  Options are:
    -c           check the committed artifact without rewriting it

  Serialize deterministic current-codec fixtures through PideBridgeV1 and
  write the resulting envelope byte measurements.  The default artifact is
  plans/evidence/pide_bridge_payloads.json relative to ISABELLE_MCP_HOME.
""", "c" -> (_ => check_only = true))
      val more_args = getopts(args)
      if (more_args.nonEmpty) getopts.usage()
      val path = default_path
      if (check_only) check(path)
      else write(path)
      new Console_Progress().echo(
        (if (check_only) "Checked" else "Wrote") + " PIDE bridge payload artifact " + path)
    })
}
