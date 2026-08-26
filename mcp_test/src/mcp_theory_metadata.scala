/*  Title:      mcp_test/src/mcp_theory_metadata.scala

Post-build adapter from Isabelle theory exports to the JSON manifest consumed
by tools/spec_gate.py.

Why this adapter exists
-----------------------

Each `spec_test` declaration is accumulated in Theory_Data and exported by its
declaring theory as typed XML. XML.Encode/Decode is Isabelle/ML's native,
type-directed serialization mechanism, while Isabelle/Scala provides the
project's reliable JSON encoder. The relevant theories are built in separate
sessions, so no single theory context can produce the complete cross-session
manifest.

This tool therefore reads every `mcp/spec-tests` entry from the configured
session databases, validates the exported records, merges them deterministically
and writes one atomic JSON manifest. It does not discover metadata from theory
source text and does not evaluate test bodies.

How to remove this adapter
--------------------------

The simpler eventual interface is one canonical JSON export per theory:

  1. Render the local Theory_Data records as carefully tested JSON in ML.
  2. Export them as `mcp/spec-tests.json` using Export or Generated_Files.
  3. Materialize all matching theory exports after the session builds.
  4. Let spec_gate validate and merge those JSON files directly.

That design preserves per-theory provenance and parallel-build safety while
removing the YXML decoding and cross-session Scala aggregation implemented
here. Do not replace this tool with direct filesystem writes during theory
processing: parallel or failed builds could leave partial, misleading files.
*/

package isabelle.mcp

import isabelle._

import java.nio.charset.StandardCharsets
import java.nio.file.{AtomicMoveNotSupportedException, Files, Paths, StandardCopyOption}


object MCP_Theory_Metadata {
  val schema_version = 1
  val producer = "isabelle-mcp/isabelle"
  val framework = "isabelle"
  val export_name = "mcp/spec-tests"
  val sessions: List[String] = List("MCP-Tools-Tests", "MCP-HOL-Tests")

  final case class Link(relation: String, id: String)
  final case class Test(
    session: String,
    theory: String,
    name: String,
    source_path: String,
    source_line: Int,
    source_sha1: String,
    links: List[Link])

  private val id_pattern = "[A-Za-z0-9_.-]+#[AITDQ][0-9]+".r
  private val sha1_pattern = "sha1:[0-9a-f]{40}".r

  private val decode_link: XML.Decode.T[(String, String)] =
    XML.Decode.pair(XML.Decode.string, XML.Decode.string)

  private val decode_test:
      XML.Decode.T[(String, (String, (Int, (String, List[(String, String)]))))] =
    XML.Decode.pair(XML.Decode.string,
      XML.Decode.pair(XML.Decode.string,
        XML.Decode.pair(XML.Decode.int,
          XML.Decode.pair(XML.Decode.string, XML.Decode.list(decode_link)))))

  private val decode_tests = XML.Decode.list(decode_test)

  private def canonical_source_path(path: String): String = {
    val normalized = path.replace('\\', '/')
    val marker = "/mcp/Tools/"
    val index = normalized.lastIndexOf(marker)
    if (index >= 0) normalized.substring(index + 1) else normalized
  }

  private def checked_test(session: String, theory: String,
      raw: (String, (String, (Int, (String, List[(String, String)]))))): Test = {
    val (name, (raw_path, (source_line, (source_sha1, raw_links)))) = raw
    val source_path = canonical_source_path(raw_path)
    require(session.trim.nonEmpty, "empty Isabelle session name")
    require(theory.trim.nonEmpty, "empty Isabelle theory name")
    require(name.trim.nonEmpty, "empty spec_test name in " + theory)
    require(source_path.nonEmpty && !Paths.get(source_path).isAbsolute &&
        !source_path.split('/').contains("..") && !source_path.contains('\\'),
      "spec_test source path must be canonical and relative: " + quote(source_path))
    require(source_line > 0,
      "spec_test source line must be positive for " + theory + "::" + name)
    require(sha1_pattern.pattern.matcher(source_sha1).matches(),
      "malformed theory source digest for " + theory + "::" + name)
    require(raw_links.nonEmpty,
      "spec_test has no plan links: " + theory + "::" + name)

    val links = raw_links.map { case (relation, id) =>
      require(MCP_Spec_Metadata.Link_Relations(relation),
        "unknown plan-link relation " + quote(relation) + " in " + theory + "::" + name)
      require(id_pattern.pattern.matcher(id).matches(),
        "malformed plan reference " + quote(id) + " in " + theory + "::" + name)
      val label = id.substring(id.indexOf('#') + 1)
      val expected_relation =
        if (label.startsWith("A") || label.startsWith("I")) MCP_Spec_Metadata.Verifies
        else if (label.startsWith("T")) MCP_Spec_Metadata.Covers
        else throw new IllegalArgumentException(
          "D/Q plan records cannot be test-link targets: " + quote(id))
      require(relation == expected_relation,
        relation + " cannot target " + quote(id) + " in " + theory + "::" + name)
      Link(relation, id)
    }
    val duplicate_links =
      links.groupBy(link => (link.relation, link.id)).collect {
        case ((relation, id), same) if same.lengthCompare(1) > 0 => relation + ":" + id
      }.toList.sorted
    require(duplicate_links.isEmpty,
      "duplicate plan links on " + theory + "::" + name + ": " + duplicate_links.mkString(", "))

    Test(session, theory, name, source_path, source_line, source_sha1,
      links.sortBy(link => (link.relation, link.id)))
  }

  def decode(session: String, theory: String, body: XML.Body): List[Test] =
    decode_tests(body).map(checked_test(session, theory, _))

  private def checked_test(test: Test): Test =
    checked_test(test.session, test.theory,
      (test.name,
        (test.source_path,
          (test.source_line,
            (test.source_sha1, test.links.map(link => (link.relation, link.id)))))))

  private def theory_record(tests: List[Test]): JSON.Object.T = {
    val head = tests.head
    val sources = tests.map(test => (test.source_path, test.source_sha1)).distinct
    require(sources.lengthCompare(1) == 0,
      "inconsistent source metadata in theory " + quote(head.theory))
    val (path, sha1) = sources.head
    JSON.Object(
      "session" -> head.session,
      "theory" -> head.theory,
      "source" -> JSON.Object("path" -> path, "sha1" -> sha1))
  }

  private def test_record(test: Test): JSON.Object.T =
    JSON.Object(
      "session" -> test.session,
      "theory" -> test.theory,
      "name" -> test.name,
      "location" ->
        JSON.Object("path" -> test.source_path, "line" -> test.source_line),
      "layer" -> MCP_Test_Layers("ml_unit_tests"),
      "links" -> test.links.map(link =>
        JSON.Object("relation" -> link.relation, "id" -> link.id)))

  def manifest(all_tests: List[Test]): JSON.Object.T = {
    require(all_tests.nonEmpty, "cannot export an empty Isabelle spec_test registry")
    val checked_tests = all_tests.map(checked_test)
    require(checked_tests.map(_.session).toSet == sessions.toSet,
      "metadata must contain every configured Isabelle test session: " +
        sessions.mkString(", "))

    val tests = checked_tests.sortBy(test => (test.session, test.theory, test.name))
    val duplicate_tests =
      tests.groupBy(test => (test.session, test.theory, test.name)).collect {
        case ((session, theory, name), same) if same.lengthCompare(1) > 0 =>
          session + "::" + theory + "::" + name
      }.toList.sorted
    require(duplicate_tests.isEmpty,
      "duplicate Isabelle test identities: " + duplicate_tests.mkString(", "))

    val theories =
      tests.groupBy(test => (test.session, test.theory)).toList
        .sortBy(_._1).map { case (_, theory_tests) => theory_record(theory_tests) }

    JSON.Object(
      "schema_version" -> schema_version,
      "producer" -> producer,
      "framework" -> framework,
      "export_name" -> export_name,
      "test_layers_sha256" -> MCP_Test_Layers.sha256,
      "sessions" -> sessions,
      "theories" -> theories,
      "tests" -> tests.map(test_record))
  }

  def collect(options: Options = Options.init()): List[Test] = {
    val store = Store(options)
    sessions.flatMap { session =>
      using(Export.open_session_context0(store, session)) { session_context =>
        val entries =
          session_context.entry_names(session = session)
            .filter(_.name == export_name)
            .sortBy(entry => (entry.theory, entry.name))
        require(entries.nonEmpty,
          "session " + quote(session) + " contains no " + quote(export_name) +
            " exports; build the test session first")
        entries.flatMap { entry_name =>
          val entry = session_context(entry_name.theory, entry_name.name)
          val tests = decode(session, entry_name.theory, entry.yxml())
          require(tests.nonEmpty,
            "empty " + quote(export_name) + " export in theory " + quote(entry_name.theory))
          tests
        }
      }
    }
  }

  def default_manifest_path: Path = {
    val home = Path.explode(Isabelle_System.getenv_strict("ISABELLE_MCP_TEST_HOME"))
    home + Path.explode("lib/isabelle-spec.json")
  }

  def write(path: Path, tests: List[Test]): Unit = {
    val text = JSON.Format(manifest(tests)) + "\n"
    require(JSON.Format.unapply(text).isDefined,
      "internal error: generated Isabelle manifest is not valid JSON")

    val target = path.file.toPath.toAbsolutePath.normalize
    val parent = target.getParent
    require(parent != null, "manifest path has no parent: " + path)
    Files.createDirectories(parent)
    val temporary = Files.createTempFile(parent, ".isabelle-spec-", ".tmp")
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

  val isabelle_tool =
    Isabelle_Tool("mcp_theory_metadata",
      "export structured Isabelle theory-test metadata", Scala_Project.here,
    { args =>
      var manifest: Option[Path] = None

      val getopts = Getopts("""
Usage: isabelle mcp_theory_metadata [OPTIONS]

  Options are:
    -M FILE      write the full Isabelle metadata manifest to FILE
                 (default: $ISABELLE_MCP_TEST_HOME/lib/isabelle-spec.json)

  Read every spec_test export from the configured Isabelle test sessions and
  write one deterministic JSON manifest. Test bodies are not evaluated here;
  build MCP-Tools-Tests and MCP-HOL-Tests before running this command.
""",
        "M:" -> (arg => manifest = Some(Path.explode(arg))))

      val more_args = getopts(args)
      if (more_args.nonEmpty) getopts.usage()

      val path = manifest.getOrElse(default_manifest_path)
      val tests = collect()
      write(path, tests)
      new Console_Progress().echo("Wrote full Isabelle test metadata manifest to " + path)
    })
}


class MCP_Theory_Metadata_Tests extends MCP_Suite {
  private def sample(session: String, theory: String, name: String): MCP_Theory_Metadata.Test =
    MCP_Theory_Metadata.Test(
      session = session,
      theory = theory,
      name = name,
      source_path = "mcp/Tools/Tests/" + theory + ".thy",
      source_line = 7,
      source_sha1 = "sha1:" + "0" * 40,
      links = List(
        MCP_Theory_Metadata.Link("verifies", "sample#A1"),
        MCP_Theory_Metadata.Link("covers", "sample#T1")))

  test("Isabelle metadata manifest is deterministic and uses the shared ML layer") {
    val tests =
      List(
        sample("MCP-HOL-Tests", "Z_Test", "z"),
        sample("MCP-Tools-Tests", "A_Test", "a"))
    val manifest = MCP_Theory_Metadata.manifest(tests)
    assertEquals(get_string(manifest, "producer"), MCP_Theory_Metadata.producer)
    assertEquals(get_string(manifest, "test_layers_sha256"), MCP_Test_Layers.sha256)
    assertEquals(
      get_list(manifest, "tests").map(test => get_string(test, "layer")).distinct,
      List(MCP_Test_Layers("ml_unit_tests")))
    assertEquals(
      get_list(manifest, "tests").map(test => get_string(test, "name")),
      List("z", "a"))
  }

  test("Isabelle metadata exporter rejects incomplete and duplicate input") {
    intercept[IllegalArgumentException] {
      MCP_Theory_Metadata.manifest(List(sample("MCP-Tools-Tests", "A_Test", "a")))
    }
    val complete =
      List(
        sample("MCP-Tools-Tests", "A_Test", "same"),
        sample("MCP-HOL-Tests", "Z_Test", "z"))
    intercept[IllegalArgumentException] {
      MCP_Theory_Metadata.manifest(complete.head :: complete)
    }
    intercept[IllegalArgumentException] {
      MCP_Theory_Metadata.manifest(
        complete.updated(0,
          complete.head.copy(
            links = List(MCP_Theory_Metadata.Link("verifies", "sample#T1")))))
    }
    intercept[IllegalArgumentException] {
      MCP_Theory_Metadata.manifest(
        complete.updated(0, complete.head.copy(source_path = "/absolute/Test.thy")))
    }
  }
}
