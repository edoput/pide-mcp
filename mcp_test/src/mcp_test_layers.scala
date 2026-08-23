/*  Title:      mcp_test/src/mcp_test_layers.scala

Shared, data-driven vocabulary for test pyramid layers.

Layer names live exclusively in etc/test_layers.json. Scala suite registration
resolves a stable role through this module; the manifest exporter validates
against the same loaded snapshot. tools/spec_gate.py consumes the same file.
*/

package isabelle.mcp

import isabelle._

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest


object MCP_Test_Layers {
  val schema_version = 1

  final class Registry private[MCP_Test_Layers] (val roles: Map[String, String]) {
    val names: Set[String] = roles.values.toSet

    def apply(role: String): String =
      roles.getOrElse(role,
        error("Unknown test-layer role " + quote(role) +
          "; configured roles: " + roles.keys.toList.sorted.mkString(", ")))
  }

  private final case class Snapshot(registry: Registry, sha256: String)

  private val role_pattern = "[a-z][a-z0-9_]*".r
  private val name_pattern = "[a-z][a-z0-9-]*".r

  private def invalid(message: String): Nothing =
    throw new IllegalArgumentException("Invalid test-layer registry: " + message)

  def parse(text: String): Registry = {
    val root =
      try { JSON.Object.parse(text) }
      catch { case ERROR(message) => invalid(message) }

    root.get("schema_version") match {
      case Some(version: Double) if version == schema_version.toDouble =>
      case _ => invalid("schema_version must be " + schema_version)
    }

    val entries =
      root.get("layers") match {
        case Some(JSON.Object(layers)) =>
          layers.toList.map {
            case (role, name: String) => (role, name)
            case (role, _) => invalid("layer " + quote(role) + " is not a string")
          }
        case _ => invalid("layers must be an object")
      }
    if (entries.isEmpty) invalid("layers must not be empty")

    for ((role, name) <- entries) {
      if (!role_pattern.pattern.matcher(role).matches()) {
        invalid("malformed role " + quote(role))
      }
      if (!name_pattern.pattern.matcher(name).matches()) {
        invalid("malformed layer name " + quote(name) + " for role " + quote(role))
      }
    }
    val duplicate_names =
      entries.groupBy(_._2).collect {
        case (name, same) if same.lengthCompare(1) > 0 => name
      }.toList.sorted
    if (duplicate_names.nonEmpty) {
      invalid("layer names assigned to several roles: " + duplicate_names.mkString(", "))
    }

    new Registry(entries.toMap)
  }

  val path: Path = {
    val home = Path.explode(Isabelle_System.getenv_strict("ISABELLE_MCP_TEST_HOME"))
    home + Path.explode("etc/test_layers.json")
  }

  private def digest(bytes: Array[Byte]): String = {
    val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
    "sha256:" + hash.map(byte => f"${byte & 0xff}%02x").mkString
  }

  private lazy val current: Snapshot = {
    val bytes = Files.readAllBytes(path.file.toPath)
    val text = new String(bytes, StandardCharsets.UTF_8)
    Snapshot(parse(text), digest(bytes))
  }

  def apply(role: String): String = current.registry(role)
  def names: Set[String] = current.registry.names
  def sha256: String = current.sha256
}
