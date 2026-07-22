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


  /* doc_read (plans/doc_read): toc + section reads over a manual's chapter
     sources, plain-entry windowed reads. Pure functions of file paths/
     content, so they are unit-testable directly against the bundled
     distribution's real doc sources (plans/doc_read T1..T6) -- no fake
     catalog, no headless session needed (same rationale as make() above). */

  /* the window cap for oversized reads (spec "large reads are sliceable...
     a read without parameters on an oversized resource returns the first
     window ... instead of the full payload" -- the spec pins the RULE but
     no concrete size anywhere in the codebase; this is the first tool to
     implement it, so DOC_READ_WINDOW is established HERE, not reused from
     elsewhere (there is nothing to reuse yet). Chapter-sized manual
     sections and NEWS are both meant to truncate under this cap; narrowing
     the request (a more specific section, an explicit lines= window) is
     the documented way past it. */
  val window_lines: Int = 200

  private def truncate(all_lines: List[String], note_hint: String): String = {
    if (all_lines.length <= window_lines) all_lines.mkString("\n")
    else {
      val shown = all_lines.take(window_lines)
      shown.mkString("\n") +
        "\n\n[truncated, " + (all_lines.length - window_lines) + " more lines; " + note_hint + "]"
    }
  }

  /* D1a: line-anchored heading scan (the outer-syntax-span spike never
     landed, per the plan's own decision rule -- ship (a) with the line-
     initial caveat pinned by T6). Headings in the bundled doc sources are
     hand-formatted, one command per line: "section \<open>Title \label{l}\<close>"
     -- command and title cartouche on the same physical line, with an
     optional trailing \label{...} folded into the cartouche, stripped
     here for a clean title (D2: a heading not matching this shape is
     dropped, never crashes the toc). */
  sealed case class Heading(level: Int, title: String, file: Path, line: Int)

  private val heading_line =
    """^(chapter|section|subsection|subsubsection)\s+\\<open>(.*)\\<close>\s*$""".r
  private val trailing_label = """\s*\\label\{[^}]*\}\s*$""".r

  def heading_level(command: String): Int =
    command match {
      case "chapter" => 0
      case "section" => 1
      case "subsection" => 2
      case "subsubsection" => 3
    }

  def scan_headings(file: Path): List[Heading] = {
    val lines = split_lines(File.read(file))
    lines.zipWithIndex.flatMap { case (line, i) =>
      line match {
        case heading_line(command, raw_title) =>
          val title = trailing_label.replaceAllIn(raw_title, "").trim
          Some(Heading(heading_level(command), title, file, i + 1))
        case _ => None
      }
    }
  }

  def toc(files: List[Path]): List[Heading] = files.flatMap(scan_headings)

  def render_toc(headings: List[Heading]): String =
    if (headings.isEmpty) "(no headings found)"
    else
      headings.map(h =>
        "  " * h.level + h.title + "  [" + h.file.file_name + ":" + h.line + "]").mkString("\n")

  /* section lookup: case-insensitive substring match on titles within ONE
     manual's toc, resolved against the whole distinct match set -- "it is
     a search, not a compile" (plans/doc_read): unique -> read that
     section's text; ambiguous -> the candidate rows, not a guess; none ->
     an honest "no section matching" reply (probe-safe, matching doc_list's
     unmatched-pattern rule). */
  sealed abstract class Section_Lookup
  case class Unique(heading: Heading) extends Section_Lookup
  case class Ambiguous(candidates: List[Heading]) extends Section_Lookup
  case object No_Match extends Section_Lookup

  def find_section(headings: List[Heading], query: String): Section_Lookup = {
    val q = query.toLowerCase
    val candidates = headings.filter(_.title.toLowerCase.contains(q))
    candidates match {
      case List(one) => Unique(one)
      case Nil => No_Match
      case many => Ambiguous(many)
    }
  }

  /* section text: from the matched heading's line to the next heading of
     the SAME OR HIGHER level (i.e. level <= the matched one) within the
     same file, or the end of the file if none -- a nested subsection
     stays inside its parent's slice. Every bundled manual chapter is one
     file (D1's assumption), so cross-file spans are not needed. */
  def section_text(headings_in_file: List[Heading], heading: Heading): String = {
    val content = split_lines(File.read(heading.file))
    val start = heading.line - 1
    val end =
      headings_in_file
        .filter(h => h.line > heading.line && h.level <= heading.level)
        .map(_.line - 1)
        .sorted
        .headOption
        .getOrElse(content.length)
    truncate(content.slice(start, end), "narrow the section")
  }

  /* plain-entry reads (NEWS, COPYRIGHT, examples): `lines` windows exactly
     like the spec's isabelle://theory/{name}?lines=120-180 -- a 1-based,
     inclusive "start-stop" range. No `lines` given: the oversized-read
     rule (first window_lines + truncation note) applies, same as an
     unparameterized manual section read; an explicit `lines` request is
     never truncated further -- the caller asked for exactly that. */
  def plain_read(path: Path, lines: String): Either[String, String] = {
    val content = split_lines(File.read(path))
    if (lines.isEmpty) Right(truncate(content, "use lines=\"" + (window_lines + 1) + "-...\""))
    else
      lines match {
        case Lines_Range(from_s, to_s) =>
          val from = from_s.toInt
          val to = to_s.toInt
          if (from < 1 || to < from) Left("Bad lines range " + quote(lines) + ": expected start-stop, start >= 1, stop >= start")
          else Right(content.slice(from - 1, to).mkString("\n"))
        case _ =>
          Left("Bad lines range " + quote(lines) + ": expected \"start-stop\", e.g. \"120-180\"")
      }
  }

  private val Lines_Range = """^(\d+)-(\d+)$""".r
}
