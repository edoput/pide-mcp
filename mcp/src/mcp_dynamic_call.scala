/*  Title:      mcp/src/mcp_dynamic_call.scala

The one Scala function behind the scala_mcp_tool command
(plans/scala_mcp_tool): invoke a method named by a STRING from
Isabelle/ML, resolved reflectively against the component classpath.

Nothing here knows about any particular component. The target arrives as
text from a theory; the classpath supplies the code. That is the whole
point -- mcp.jar must not link against the jars it calls, and they must
not know about mcp.jar.

It extends Scala.Fun DIRECTLY rather than Fun_String, because only Fun
receives the Session -- and the Session is what supplies the arguments
ML cannot express (a Document.Snapshot above all).
*/

package isabelle.mcp

import isabelle._

import java.lang.reflect.{Method, InvocationTargetException}


object Dynamic_Call {
  /* target resolution: "pkg.Class.method" is ambiguous, since dots appear
     in both halves. Try longest-class-first, and report every attempt on
     failure -- "reflection failed" is not actionable. */

  def splits(target: String): List[(String, String)] = {
    val parts = target.split('.').toList
    (for (i <- (parts.length - 1) to 1 by -1)
      yield (parts.take(i).mkString("."), parts.drop(i).mkString("."))).toList
  }

  /* a scala `object Foo` compiles to class Foo$ with the singleton in a
     public static final MODULE$ field (verified against mcp.jar).

     The two failure modes are told APART on purpose (A1): "no such class"
     and "that is a class, not an object" send the reader to different
     fixes. Catches are narrow -- a blanket Throwable here would also
     swallow Exn.Interrupt and turn a cancelled call into a bogus
     resolution error. */
  def load_module(class_name: String): Either[String, AnyRef] = {
    val loader = Isabelle_System.classpath().class_loader
    try {
      val c = Class.forName(class_name + "$", true, loader)
      try Right(c.getField("MODULE$").get(null))
      catch {
        case _: NoSuchFieldException =>
          Left("found " + quote(class_name) + " but it is a class, not a scala object " +
            "(no MODULE$ field), so it has no singleton to invoke on")
      }
    }
    catch { case _: ClassNotFoundException => Left("no class " + quote(class_name)) }
  }

  sealed case class Target(module: AnyRef, method: Method) {
    def print: String = module.getClass.getName + "." + method.getName
  }

  def resolve(target: String): Either[String, Target] = {
    val probed =
      splits(target).map { case (cls, mth) =>
        val outcome =
          load_module(cls).flatMap { module =>
            module.getClass.getMethods.toList
              .filter(m => m.getName == mth && !m.isSynthetic) match {
              case Nil =>
                Left("class " + quote(cls) + " has no method " + quote(mth) + "; it has " +
                  module.getClass.getMethods.toList.map(_.getName).distinct.sorted
                    .take(12).mkString(", "))
              case ms => Right(ms.map(m => (module, m)))
            }
          }
        (cls, mth, outcome)
      }

    probed.collect({ case (_, _, Right(ms)) => ms }).flatten match {
      case Nil =>
        Left("Cannot resolve " + quote(target) + " on the component classpath.\n" +
          probed.collect({ case (c, m, Left(why)) =>
            "  as class " + quote(c) + " method " + quote(m) + ": " + why }).mkString("\n"))
      case List((module, m)) => Right(Target(module, m))
      case several =>
        /* D4: overloads are rejected, not guessed -- the declaration
           supplies names and arity, getMethod needs types. */
        Left("Ambiguous target " + quote(target) + ": " + several.length +
          " methods match.\n" +
          several.map({ case (_, m) =>
            "  " + m.getName + "(" +
            m.getParameterTypes.map(_.getName).mkString(", ") + ")" }).mkString("\n"))
    }
  }


  /* the fill rule (plans/scala_mcp_tool D1). Each parameter is filled
     either from the ML call args (EXPLICIT, by parameter name -- names
     survive into the bytecode, see the plan's Q4) or from server state
     (AMBIENT, which ML could never express). */

  def resolve_snapshot(session: Session, theory: String): Either[String, Document.Snapshot] =
    session.get_state().stable_tip_version match {
      case None => Left("No stable document version yet; is a theory loaded?")
      case Some(version) =>
        version.nodes.domain.find(n => n.theory == theory || n.theory.endsWith("." + theory)) match {
          case Some(node_name) => Right(session.snapshot(node_name))
          case None =>
            Left("No loaded theory " + quote(theory) + ".\nLoaded: " +
              version.nodes.domain.toList.map(_.theory).sorted.take(20).mkString(", "))
        }
    }

  /* AMBIENT construction for a type we cannot name at compile time: if the
     parameter's companion has an apply(Options), build it from the session's
     own options. This is how a component's own configuration object is
     produced without mcp.jar knowing the type -- the linter's
     Lint_Store.Selection is reached exactly this way, and so is anything
     else following the same Isabelle idiom. */
  def from_options(session: Session, typ: Class[_]): Option[AnyRef] = {
    val loader = Isabelle_System.classpath().class_loader
    try {
      val companion = Class.forName(typ.getName + "$", true, loader)
      val module = companion.getField("MODULE$").get(null)
      companion.getMethods.toList
        .find(m => m.getName == "apply" &&
          m.getParameterTypes.length == 1 &&
          m.getParameterTypes.apply(0) == classOf[Options])
        .map(m => m.invoke(module, session.session_options))
    }
    catch {
      case _: ClassNotFoundException => None
      case _: NoSuchFieldException => None
    }
  }

  def fill(
    session: Session,
    method: Method,
    named: Map[String, String]
  ): Either[String, Array[AnyRef]] = {
    val params = method.getParameters.toList
    val filled =
      params.map { p =>
        val typ = p.getType
        val name = p.getName
        def explicit: Either[String, String] =
          named.get(name).toRight(
            "Missing argument " + quote(name) + " for parameter of type " + typ.getName)

        if (typ == classOf[Document.Snapshot])
          /* the ambient parameter needs a selector: which snapshot? by
             convention it comes from a `theory` argument. */
          named.get("theory")
            .toRight("A Document.Snapshot parameter needs a `theory` argument naming the theory.")
            .flatMap(resolve_snapshot(session, _).map(_.asInstanceOf[AnyRef]))
        else if (typ == classOf[Session]) Right(session)
        else if (typ == classOf[Options]) Right(session.session_options)
        else if (typ == classOf[String]) explicit.map(s => s: AnyRef)
        else if (typ == classOf[Int] || typ == classOf[java.lang.Integer])
          explicit.flatMap(s =>
            try Right(java.lang.Integer.valueOf(s.toInt): AnyRef)
            catch { case _: NumberFormatException => Left("Not an integer for " + quote(name) + ": " + quote(s)) })
        else if (typ == classOf[Boolean] || typ == classOf[java.lang.Boolean])
          explicit.flatMap(s =>
            try Right(java.lang.Boolean.valueOf(s.toBoolean): AnyRef)
            catch { case _: IllegalArgumentException => Left("Not a boolean for " + quote(name) + ": " + quote(s)) })
        else
          from_options(session, typ).toRight(
            "Cannot build parameter " + quote(name) + " of type " + typ.getName +
            ": not an explicit scalar, not ambient server state, and its companion " +
            "has no apply(Options).")
      }

    val errors = filled.collect({ case Left(msg) => msg })
    if (errors.nonEmpty) Left(errors.mkString("\n"))
    else Right(filled.collect({ case Right(v) => v }).toArray)
  }


  /* result rendering (D3). String passes through; a collection or Product
     renders elementwise, which is what makes a report-shaped result
     readable; anything else falls back to toString and says so in the
     plan rather than pretending to be structured. */

  def render(result: AnyRef): String =
    result match {
      case null => ""
      case s: String => s
      case xml: XML.Tree => XML.content(List(xml))
      case it: Iterable[_] => it.map(render_item).mkString("\n")
      case other =>
        /* a report-shaped object: prefer a no-arg accessor returning a
           collection over the identity-hash toString. */
        val accessor =
          other.getClass.getMethods.toList
            .filter(m => m.getParameterTypes.isEmpty && !m.isSynthetic)
            .find(m => m.getName == "results" || m.getName == "entries")
        accessor.flatMap(m =>
          try {
            m.invoke(other) match {
              case it: Iterable[_] => Some(it.map(render_item).mkString("\n"))
              case _ => None
            }
          }
          catch { case _: Throwable => None }
        ).getOrElse(String.valueOf(other))
    }

  /* A finding-shaped result is almost always a case class. Render it by
     FIELD NAME (scala 3 keeps productElementNames), which turns
     Result(short_name,Name "f" is too short.,[587..588],...) into something
     an agent can read -- and drop fields that drag half the document model
     in with them (a Parsed_Command carries its whole Snapshot). */
  private val Bulky = 240

  private def render_item(item: Any): String =
    item match {
      case p: Product =>
        val fields =
          p.productElementNames.zip(p.productIterator).toList
            .filter({ case (_, v) => !v.isInstanceOf[Iterable[_]] })
            .map({ case (k, v) => k -> String.valueOf(v) })
            .filter({ case (_, v) => v.length <= Bulky })
        if (fields.isEmpty) p.productPrefix
        else fields.map({ case (k, v) => k + "=" + v }).mkString(p.productPrefix + "(", ", ", ")")
      case other => String.valueOf(other)
    }


  /* the Fun. args = target :: name1 :: value1 :: name2 :: value2 :: ...
     No encoding is needed: Scala.function moves a LIST, so each name and
     each value is its own element and no separator can corrupt a value. */

  object Call extends Scala.Fun("MCP.dynamic_call") {
    val here = Scala_Project.here

    def invoke(session: Session, args: List[Bytes]): List[Bytes] = {
      val strings = args.map(_.text)
      strings match {
        case Nil => error("MCP.dynamic_call: missing target")
        case target :: rest =>
          if (rest.length % 2 != 0)
            error("MCP.dynamic_call: arguments must be name/value pairs, got " + rest.length)
          val named =
            rest.grouped(2).collect({ case List(n, v) => n -> v }).toMap

          val outcome =
            for {
              t <- resolve(target)
              argv <- fill(session, t.method, named)
              value <-
                try Right(t.method.invoke(t.module, argv: _*))
                catch {
                  /* unwrap: a bare InvocationTargetException tells the
                     caller nothing about what actually failed. */
                  case e: InvocationTargetException =>
                    val cause = if (e.getCause == null) e else e.getCause
                    Left(t.print + " raised " + cause.getClass.getName +
                      Option(cause.getMessage).map(": " + _).getOrElse(""))
                  case e: IllegalArgumentException =>
                    Left(t.print + ": argument mismatch: " + e.getMessage)
                }
            } yield render(value)

          outcome match {
            case Right(text) => List(Bytes(text))
            case Left(msg) => error(msg)
          }
      }
    }
  }

  /* check_target: the same resolution, without invoking, so the
     scala_mcp_tool command can PROVE its target at registration and a
     typo fails with a position instead of at call time. */
  object Check extends Scala.Fun_String("MCP.check_target") {
    val here = Scala_Project.here
    def apply(target: String): String =
      resolve(target) match {
        case Right(t) =>
          t.method.getParameters.toList
            .map(p => p.getName + ": " + p.getType.getName).mkString(", ")
        case Left(msg) => error(msg)
      }
  }
}

class Functions extends Scala.Functions(Dynamic_Call.Call, Dynamic_Call.Check)
