/*  Title:      mcp/src/utils.scala

Small stateless helpers shared across the mcp package, with no home of
their own in a single tool's source file.
*/

package isabelle.mcp

import isabelle._


/* Range-windowing shared by every list-shaped tool result (list_sessions,
   list_theories, search_sources, load_theory diagnostics,
   doc_read's oversized-read fallback): an agent-controlled offset/limit
   over already-formatted rows, mirroring this harness's own file-reading
   tool's offset/limit convention rather than each tool inventing its own
   truncation scheme. */
object Window {
  val default_limit: Int = 200

  /* hint: an optional call-site-specific note (e.g. "narrow the section")
     appended alongside the offset/limit continuation hint -- useful when
     offset/limit alone doesn't cover every way to get a smaller result
     (doc_read can also narrow via `section`). */
  def apply(rows: List[String], offset: Int, limit: Int, hint: Option[String] = None): String = {
    val total = rows.length
    val slice = rows.slice(offset, offset + limit)
    val shown_end = offset + slice.length

    val buf = new StringBuilder
    for ((row, i) <- slice.zipWithIndex) {
      if (i > 0) buf.append('\n')
      buf.append(row)
    }
    if (!(offset == 0 && shown_end >= total)) {
      val remaining = total - shown_end
      buf.append("\n\n")
      buf.append("[showing %d-%d of %d".format(offset + 1, shown_end, total))
      if (remaining > 0) buf.append("; offset=%d for more".format(shown_end))
      for (h <- hint) buf.append("; ").append(h)
      buf.append(']')
    }
    buf.toString
  }
}
