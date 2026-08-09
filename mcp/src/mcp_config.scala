/*  Title:      mcp/src/mcp_config.scala

Pre-flight check over a `-d` session-directory set (plans/
session_dirs_errors, ROOTS_ANALYSIS.md section 2 "the bugs this
exposes"). Pure Scala, no prover: Sessions.load_structure builds one
global session graph over every `-d` and throws on the first collision
(duplicate session name, bad parent, ...), which is opaque and takes
down every root, not just the offending one. This module re-derives
what load_structure would see, one layer below the fold that throws
(Root_File.entries is lazy and forcing it never enters that fold --
verified, see plans/session_dirs_errors A1), and attributes every
session name to a ROOT file, a line, and whether it came from one of
the user's own `-d` directories or from the pre-existing distribution/
AFP/component baseline.

DECIDED POLICY (2026-08-07, see plans/session_dirs_errors): a `-d`
configuration error is not degraded around. MCP_Server.run calls
check() synchronously before anything else and, if the result is
non-empty, exits via error(render(issues)) -- the server never comes
up on a bad config. This module only computes and renders the
diagnosis; it does not decide what happens next. */

package isabelle.mcp

import isabelle._

import java.io.{File => JFile}


object MCP_Config {
  /* diagnosis */

  sealed abstract class Issue { def message: String }

  case class Bad_Dir(dir: Path, detail: String) extends Issue {
    /* dir.expand.toString, not implode: a relative -d should echo back
       expanded, matching Sessions.check_session_dir's own wording for
       this exact situation ("Bad session root directory: " + dir.expand
       + "\n  (missing \"ROOT\" or \"ROOTS\")"), so this message reads
       the same as the one a bare `isabelle build` would have shown. */
    def message: String =
      "bad -d directory: " + dir.expand.toString + "\n" +
      "    " + detail
  }

  case class Root_Error(root: Path, detail: String) extends Issue {
    def message: String =
      "error reading session root " + root.implode + ":\n" +
      "    " + detail
  }

  case class Collision(name: String, sites: List[Site]) extends Issue {
    def message: String = {
      val header = "duplicate session name " + quote(name)
      val (from_components, from_dirs) = sites.partition(_.provenance == From_Components)
      if (from_components.nonEmpty) {
        val component = from_components.head
        (header ::
          from_dirs.map(s => "    declared at " + s.location) :::
          List(
            "    collides with a session already registered by a component:",
            "      " + component.location,
            "    components are not yours to drop -- rename your session instead."))
          .mkString("\n")
      }
      else {
        sites match {
          case first :: rest =>
            (header ::
              ("    declared at " + first.location) ::
              rest.map(s => "          and at " + s.location) :::
              List(
                "    session names must be unique across every -d directory.",
                "    rename one, or drop one -d."))
              .mkString("\n")
          case Nil => header
        }
      }
    }
  }

  case class Site(root: Path, pos: Position.T, provenance: Provenance) {
    /* Position.File.get returns String, not Option[String] (A2): fall
       back to the known root path when the parser left it empty. */
    def location: String = {
      val file = Position.File.get(pos)
      val path = if (file.nonEmpty) file else root.implode
      val line = Position.Line.get(pos)
      if (line > 0) path + " line " + line else path
    }
  }

  sealed abstract class Provenance
  case class From_Dir(dir: Path) extends Provenance   // one of our -d
  case object From_Components extends Provenance      // distribution/AFP baseline


  /* baseline: distribution ROOTS + every registered component (AFP,
     the mcp component itself, ...). Sessions.directories() silently
     prepends this to EVERY load_root_files(dirs = ...) call (A3), so
     forcing entries per -d would re-parse it N times; force it here,
     once, and reuse. A baseline ROOT that itself fails to parse is a
     component/distribution problem, not the user's -d set -- skip it
     rather than turn it into an Issue. */
  private def baseline_roots(): List[Sessions.Root_File] = Sessions.load_root_files(dirs = Nil)

  private def entry_sites(
    roots: List[Sessions.Root_File],
    provenance: Sessions.Root_File => Provenance,
    on_error: (Path, String) => Unit
  ): List[(String, Site)] =
    roots.flatMap { rf =>
      Exn.capture(rf.entries) match {
        case Exn.Res(entries) =>
          entries.collect {
            case e: Sessions.Session_Entry => e.name -> Site(rf.path, e.pos, provenance(rf))
          }
        case Exn.Exn(exn) =>
          on_error(rf.path, MCP_Server.decode_message(Exn.message(exn)))
          Nil
      }
    }


  /* check: empty result = the -d set is fine. Reports EVERY issue in
     one pass, never just the first (Sessions.check_session_dir and the
     load_structure fold both stop at the first problem; a user with
     two mistakes should not have to relaunch twice). */
  def check(session_dirs: List[Path]): List[Issue] =
    if (session_dirs.isEmpty) Nil  // nothing of ours to check; also skips the baseline force
    else check_nonempty(session_dirs)

  private def check_nonempty(session_dirs: List[Path]): List[Issue] = {
    val (good_dirs, bad_dirs) = session_dirs.partition(Sessions.is_session_dir(_))
    val bad_issues: List[Issue] =
      bad_dirs.map(dir => Bad_Dir(dir, "missing \"ROOT\" or \"ROOTS\""))

    val baseline = baseline_roots()
    val baseline_keys: Set[JFile] = baseline.map(_.key).toSet
    val baseline_sites: List[(String, Site)] =
      entry_sites(baseline, _ => From_Components, (_, _) => ())

    val (root_errors, dir_sites) =
      good_dirs.foldLeft((List.empty[Issue], List.empty[(String, Site)])) {
        case ((errs, sites), dir) =>
          Exn.capture(Sessions.load_root_files(dirs = List(dir))) match {
            case Exn.Exn(exn) =>
              (errs :+ Root_Error(dir, MCP_Server.decode_message(Exn.message(exn))), sites)
            case Exn.Res(roots) =>
              val contributed = roots.filterNot(rf => baseline_keys.contains(rf.key))
              var dir_errs: List[Issue] = Nil
              val new_sites =
                entry_sites(contributed, _ => From_Dir(dir),
                  (root, detail) => dir_errs = dir_errs :+ Root_Error(root, detail))
              (errs ::: dir_errs, sites ::: new_sites)
          }
      }

    val all_sites = baseline_sites ::: dir_sites
    val collisions: List[Collision] =
      all_sites.groupBy(_._1).toList.flatMap { case (name, pairs) =>
        val sites = pairs.map(_._2)
        /* a name spanning >1 root file is only OUR problem if at least
           one site came from one of the caller's own -d directories --
           otherwise this is two baseline/component roots colliding with
           each other (a broken install, not a -d mistake), and blaming
           it on -d would misdirect the user entirely. */
        if (sites.map(_.root).distinct.length > 1 && sites.exists(_.provenance != From_Components))
          List(Collision(name, sites))
        else Nil
      }.sortBy(_.name)  // stable message order across runs

    bad_issues ::: root_errors ::: collisions
  }

  def render(issues: List[Issue]): String =
    if (issues.isEmpty) ""
    else {
      "config error in -d session directories:\n\n" +
      issues.map(i => "  " + i.message).mkString("\n\n") +
      "\n\nthe server cannot start until this is fixed."
    }
}
