/*  Title:      mcp/src/mcp_bridge_routes.scala

Typed ownership table for one family of Scala-to-ML protocol round trips.
*/

package isabelle.mcp

import isabelle._


private[mcp] final class BridgeRoutes[A] {
  private val pending = Synchronized(Map.empty[String, Promise[A]])

  def register(id: String): Promise[A] = {
    val promise = Future.promise[A]
    pending.change { current =>
      if (current.contains(id)) error("Duplicate MCP bridge id " + quote(id))
      else current + (id -> promise)
    }
    promise
  }

  def remove(id: String): Option[Promise[A]] =
    pending.change_result(current => (current.get(id), current - id))

  def complete(id: String, result: A): Boolean =
    remove(id) match {
      case Some(promise) => promise.fulfill(result); true
      case None => false
    }

  def drain(): List[(String, Promise[A])] =
    pending.change_result(current => (current.toList, Map.empty))
}
