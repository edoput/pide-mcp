/*  Title:      mcp_test/src/mcp_spec_metadata.scala

Structured links from munit tests to plan claims and test obligations.

The human-readable test name is deliberately not part of the link protocol.
Munit carries each link as a semantic Tag; discovery reads the registered Test
objects without evaluating their bodies and emits a deterministic JSON
manifest for tools/spec_gate.py.
*/

package isabelle.mcp

import isabelle._

import java.nio.charset.StandardCharsets
import java.nio.file.{AtomicMoveNotSupportedException, Files, Paths, StandardCopyOption}
import java.security.MessageDigest


object MCP_Spec_Metadata {
  val schema_version = 1
  val producer = "isabelle-mcp/munit"
  val framework_version = "1.1.1"

  val Verifies = "verifies"
  val Discharges = "discharges"
  val Link_Relations: Set[String] = Set(Verifies, Discharges)

  private val id_pattern = "[A-Za-z0-9_.-]+#[AITDQ][0-9]+".r

  final class Link_Tag private[MCP_Spec_Metadata] (val relation: String, val id: String)
      extends munit.Tag("mcp-spec:" + relation + ":" + id)

  final case class Suite_Def(layer: String, suite: Class[? <: munit.Suite])

  private def checked_link(relation: String, id: String): Link_Tag = {
    require(id_pattern.pattern.matcher(id).matches(),
      "malformed plan reference " + quote(id) +
        "; expected a qualified A/I/D/Q claim or T obligation")
    val test_obligation = id.substring(id.indexOf('#') + 1).startsWith("T")
    require(relation == Verifies || relation == Discharges,
      "unknown plan-link relation " + quote(relation))
    require((relation == Discharges) == test_obligation,
      relation + " cannot target " + quote(id) +
        (if (test_obligation) "; T labels must be discharged"
         else "; A/I/D/Q labels must be verified"))
    new Link_Tag(relation, id)
  }

  def test_options(name: String, verifies: Seq[String], discharges: Seq[String],
      location: munit.Location): munit.TestOptions = {
    require(name.trim.nonEmpty, "test name must not be empty")
    val links =
      verifies.map(checked_link(Verifies, _)) ++
        discharges.map(checked_link(Discharges, _))
    val duplicate_links =
      links.groupBy(link => (link.relation, link.id)).collect {
        case ((relation, id), same) if same.lengthCompare(1) > 0 => relation + ":" + id
      }.toList.sorted
    require(duplicate_links.isEmpty,
      "duplicate plan links on test " + quote(name) + ": " + duplicate_links.mkString(", "))
    new munit.TestOptions(name, links.toSet, location)
  }

  private def canonical_source_path(path: String): String = {
    val normalized = path.replace('\\', '/')
    val marker = "/mcp_test/"
    val index = normalized.lastIndexOf(marker)
    if (index >= 0) normalized.substring(index + 1) else normalized
  }

  def links(test: munit.Test): List[Link_Tag] =
    test.tags.toList.collect { case link: Link_Tag => link }
      .sortBy(link => (link.relation, link.id))

  def matches(test: munit.Test, pattern: String): Boolean =
    test.name.contains(pattern) || links(test).exists(_.id.contains(pattern))

  private def sha256(path: Path): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(Files.readAllBytes(path.file.toPath))
    "sha256:" + bytes.map(byte => f"${byte & 0xff}%02x").mkString
  }

  private def test_jar_digest: String = {
    val home = Path.explode(Isabelle_System.getenv_strict("ISABELLE_MCP_TEST_HOME"))
    sha256(home + Path.explode("lib/mcp_test.jar"))
  }

  private def test_record(definition: Suite_Def, test: munit.Test): JSON.Object.T = {
    val source_path = canonical_source_path(test.location.path)
    require(test.name.trim.nonEmpty,
      "empty test name in " + definition.suite.getName)
    require(source_path.nonEmpty && !Paths.get(source_path).isAbsolute &&
        !source_path.split('/').contains(".."),
      "test source path must be canonical and relative: " + quote(source_path))
    require(test.location.line > 0,
      "test source line must be positive for " + definition.suite.getName +
        "::" + test.name)
    val links =
      MCP_Spec_Metadata.links(test)
        .map(link => JSON.Object("relation" -> link.relation, "id" -> link.id))
    JSON.Object(
      "suite" -> definition.suite.getName,
      "name" -> test.name,
      "location" ->
        JSON.Object(
          "path" -> source_path,
          "line" -> test.location.line),
      "layer" -> definition.layer,
      "links" -> links)
  }

  def manifest(definitions: List[Suite_Def]): JSON.Object.T = {
    require(definitions.nonEmpty, "cannot export an empty test-suite registry")
    val duplicate_suites =
      definitions.groupBy(_.suite.getName).collect {
        case (name, same) if same.lengthCompare(1) > 0 => name
      }.toList.sorted
    require(duplicate_suites.isEmpty,
      "suites registered more than once: " + duplicate_suites.mkString(", "))
    for (definition <- definitions) {
      require(MCP_Test_Layers.names(definition.layer),
        "unknown test layer " + quote(definition.layer) +
          " for " + definition.suite.getName)
    }

    val records =
      definitions.sortBy(_.suite.getName).flatMap { definition =>
        val suite = definition.suite.getDeclaredConstructor().newInstance()
        val tests = suite.munitTests().toList
        val duplicate_tests =
          tests.groupBy(_.name).collect {
            case (name, same) if same.lengthCompare(1) > 0 => name
          }.toList.sorted
        require(duplicate_tests.isEmpty,
          "duplicate test names in " + definition.suite.getName + ": " +
            duplicate_tests.mkString(", "))
        tests.sortBy(test => (test.name, test.location.path, test.location.line))
          .map(test_record(definition, _))
      }

    JSON.Object(
      "schema_version" -> schema_version,
      "producer" -> producer,
      "framework" -> "munit",
      "framework_version" -> framework_version,
      "test_jar_sha256" -> test_jar_digest,
      "test_layers_sha256" -> MCP_Test_Layers.sha256,
      "tests" -> records)
  }

  def default_manifest_path: Path = {
    val home = Path.explode(Isabelle_System.getenv_strict("ISABELLE_MCP_TEST_HOME"))
    home + Path.explode("lib/munit-spec.json")
  }

  def write(path: Path, definitions: List[Suite_Def]): Unit = {
    val text = JSON.Format(manifest(definitions)) + "\n"
    require(JSON.Format.unapply(text).isDefined,
      "internal error: generated munit manifest is not valid JSON")

    val target = path.file.toPath.toAbsolutePath.normalize
    val parent = target.getParent
    require(parent != null, "manifest path has no parent: " + path)
    Files.createDirectories(parent)
    val temporary = Files.createTempFile(parent, ".munit-spec-", ".tmp")
    try {
      Files.writeString(temporary, text, StandardCharsets.UTF_8)
      try {
        Files.move(temporary, target,
          StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      }
      catch {
        case _: AtomicMoveNotSupportedException =>
          Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
      }
    }
    finally {
      Files.deleteIfExists(temporary)
    }
  }
}


trait MCP_Spec_Tests { self: munit.FunSuite =>
  final def spec_test(name: String, verifies: Seq[String] = Nil,
      discharges: Seq[String] = Nil)(body: => Any)(implicit loc: munit.Location): Unit = {
    val options = MCP_Spec_Metadata.test_options(name, verifies, discharges, loc)
    test(options)(body)
  }
}


object MCP_Spec_Metadata_Tests {
  var sentinel_runs = 0
}


class MCP_Spec_Metadata_Tests extends MCP_Suite {
  private val unit_layer = MCP_Test_Layers("scala_unit_suites")

  test("test layers are loaded parametrically from the shared registry") {
    val registry =
      MCP_Test_Layers.parse(
        """{"schema_version":1,"layers":{"quick":"custom-fast","slow":"custom-slow"}}""")
    assertEquals(registry("quick"), "custom-fast")
    assertEquals(registry.names, Set("custom-fast", "custom-slow"))
    assert(MCP_Test_Layers.names(unit_layer))
    assert(MCP_Test_Layers.sha256.startsWith("sha256:"))
  }

  test("spec metadata export includes every registered suite") {
    val manifest = MCP_Spec_Metadata.manifest(MCP_Test.suite_definitions)
    val exported_suites =
      get_list(manifest, "tests").map(test => get_string(test, "suite")).toSet
    val registered_suites = MCP_Test.suite_definitions.map(_.suite.getName).toSet
    assertEquals(exported_suites, registered_suites)
  }

  test("spec metadata discovery is deterministic and does not evaluate test bodies") {
    val before = MCP_Spec_Metadata_Tests.sentinel_runs
    MCP_Spec_Metadata.manifest(
      List(MCP_Spec_Metadata.Suite_Def(unit_layer, classOf[MCP_Spec_Metadata_Tests])))
    assertEquals(MCP_Spec_Metadata_Tests.sentinel_runs, before,
      "discovery evaluated a test body")

    val manifest =
      MCP_Spec_Metadata.manifest(
        List(MCP_Spec_Metadata.Suite_Def(unit_layer, classOf[MCP_Readiness_Tests])))
    assert(get_string(manifest, "test_jar_sha256").startsWith("sha256:"))

    val linked = get_list(manifest, "tests").filter(test => get_list(test, "links").nonEmpty)
    assertEquals(linked.length, 6)
    val initialize =
      linked.find(test => get_string(test, "name").startsWith("initialize never"))
        .getOrElse(fail("readiness initialize test missing from manifest"))
    assertEquals(get_string(initialize, "layer"), unit_layer)
    assert(get_string(initialize, "location", "path").endsWith("mcp_handler_tests.scala"))

    val links = get_list(initialize, "links")
    assertEquals(
      links.map(link => (get_string(link, "relation"), get_string(link, "id"))),
      List(("discharges", "readiness#T1"), ("verifies", "readiness#A1")))
  }

  test("spec metadata rejects malformed and mistyped links at registration") {
    val location = new munit.Location("fixture.scala", 1)
    intercept[IllegalArgumentException] {
      MCP_Spec_Metadata.test_options("bad", List("readiness#T1"), Nil, location)
    }
    intercept[IllegalArgumentException] {
      MCP_Spec_Metadata.test_options("bad", Nil, List("readiness#A1"), location)
    }
    intercept[IllegalArgumentException] {
      MCP_Spec_Metadata.test_options("bad", List("not qualified"), Nil, location)
    }
  }

  test("spec metadata rejects incomplete suite registries before writing") {
    intercept[IllegalArgumentException] {
      MCP_Spec_Metadata.manifest(Nil)
    }
    intercept[IllegalArgumentException] {
      MCP_Spec_Metadata.manifest(
        List(MCP_Spec_Metadata.Suite_Def("typo", classOf[MCP_Spec_Metadata_Tests])))
    }
    intercept[IllegalArgumentException] {
      val suite = MCP_Spec_Metadata.Suite_Def(unit_layer, classOf[MCP_Spec_Metadata_Tests])
      MCP_Spec_Metadata.manifest(List(suite, suite))
    }
  }

  test("test-layer registry rejects malformed or ambiguous entries") {
    intercept[IllegalArgumentException] {
      MCP_Test_Layers.parse("""{"schema_version":1,"layers":{}}""")
    }
    intercept[IllegalArgumentException] {
      MCP_Test_Layers.parse(
        """{"schema_version":1,"layers":{"first":"same","second":"same"}}""")
    }
    intercept[IllegalArgumentException] {
      MCP_Test_Layers.parse(
        """{"schema_version":1,"layers":{"bad role":"valid-name"}}""")
    }
    intercept[RuntimeException] {
      MCP_Test_Layers("missing")
    }
  }

  test("spec metadata discovery body sentinel") {
    MCP_Spec_Metadata_Tests.sentinel_runs += 1
  }
}
