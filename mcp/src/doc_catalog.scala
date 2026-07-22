/*  Title:      mcp/src/doc_catalog.scala

doc_list's backing data (plans/doc_list): the distribution's documentation
catalog (Doc.contents, Pure/Tools/doc.scala), joined per entry to the doc
session that generates it. Pure function of (Sessions.Structure), so it is
unit-testable directly against the bundled distribution's real structure --
no fake catalog needed (see plans/doc_list's T1..T4).
*/

package isabelle.mcp

import isabelle._


object Doc_Catalog {
  sealed case class Entry(name: String, title: String, source: String, path: Path)
  sealed case class Section(title: String, entries: List[Entry])

  /* the join itself, pure over names (T2 unit target): map EVERY variant
     name to its session -- harmless for the singleton-variant doc sessions
     in the bundled distribution, correct for any multi-variant one a user
     might add (document_variants = "a:b=t" gives variant names "a" and
     "b", both -> the same session). */
  def join(acc: Map[String, String], session: String, variant_names: List[String])
      : Map[String, String] =
    variant_names.foldLeft(acc)({ case (acc2, name) => acc2 + (name -> session) })

  /* D1: document_variants is already split/parsed by Sessions.Structure
     (Info.document_variants: List[Document_Build.Document_Variant], each
     .name already stripped of any "=tags" suffix) -- no manual ":"/"="
     splitting needed here, unlike the plan's original sketch. */
  def variant_map(structure: Sessions.Structure): Map[String, String] =
    structure.imports_graph.keys.foldLeft(Map.empty[String, String]) {
      case (acc, name) =>
        val info = structure(name)
        if (info.doc_group) join(acc, name, info.document_variants.map(_.name)) else acc
    }

  /* D2: Doc.contents() needs ML_Settings (only the Examples section
     actually uses it, to expand $ML_SOURCES); degrade to release notes +
     main contents (manuals, no examples) if ML_Settings.init() or
     Doc.contents() itself fails in the server context. */
  private def contents(): Doc.Contents =
    Exn.capture(Doc.contents(ML_Settings.init())) match {
      case Exn.Res(c) => c
      case Exn.Exn(_) => Doc.release_notes() ++ Doc.main_contents()
    }

  def make(structure: Sessions.Structure): List[Section] = {
    val variants = variant_map(structure)
    contents().sections.map { section =>
      Section(section.print_title,
        section.entries.map { entry =>
          val source =
            variants.get(entry.name) match {
              case Some(sess) => sess
              case None => if (entry.path.is_pdf) "pdf only" else "plain"
            }
          Entry(entry.name, entry.title, source, entry.path)
        })
    }
  }

  /* pattern: glob over entry names (MCP_Session.glob_to_regex), same
     machinery scope_add/scope_remove use; empty pattern = everything
     (unlike search_sources' empty-returns-nothing -- this is a catalog
     listing, not a search over an unbounded universe). An unmatched
     pattern is a valid, empty listing (probe-safe), not an error. */
  def render(sections: List[Section], pattern: String): String = {
    val regex = if (pattern.isEmpty) None else Some(MCP_Session.glob_to_regex(pattern))
    val filtered =
      sections.map(s => s.copy(entries = s.entries.filter(e => regex.forall(_.matches(e.name)))))
        .filter(_.entries.nonEmpty)
    val body =
      if (filtered.isEmpty) "(no matching documentation entries)"
      else filtered.map { s =>
        val rows =
          s.entries.map(e => "  %-16s %-50s [source: %s]".format(e.name, e.title, e.source))
        (s.title + ":" :: rows).mkString("\n")
      }.mkString("\n\n")
    body + "\n\nUse doc_read to read a manual (by its source session, chapter-level plain " +
      "text -- never the pdf) or a plain-text entry (release notes, examples) directly; " +
      "search_sources over a manual's source session name greps its chapters."
  }
}
