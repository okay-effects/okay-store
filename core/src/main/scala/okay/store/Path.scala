package okay.store

import okay.effects.json.Json
import okay.effects.shape.Shape

/** SOMETHING OF A TYPE `A` at a path: a mapping, a collection, a path into a value — what `at("field")` is said of, the
 * field checked against `A` by the compiler and the result a `Path` typed as the field (`TypedWords`, each compiler
 * its own) */
trait Typed[A] { def path: List[String] }
object Typed extends TypedWords

/** A PATH INTO A VALUE, TYPED: `mapping.at("qty") > 2`, `mapping.at("address").at("city") is "Kyiv"`, `.at("tags") has "y"`
 * — a `Where` or an `Order` with the field's name checked and its type known, the value encoded by the field's shape.
 * `Field("qty")` says the same of a path the compiler cannot check */
final class Path[T](val path: List[String], val shape: Shape[T]) extends Typed[T] {
  def is(t: T): Where = Where.Eq(path, shape.encode(t))
  def isNull: Where = Where.Eq(path, Json.Null)
  def <(t: T): Where = Where.Less(path, shape.encode(t))
  def >(t: T): Where = Where.Greater(path, shape.encode(t))
  /** the text within any leaf under the path, ignoring case */
  def contains(text: String): Where = Where.Contains(path, text)
  /** an element of the list at the path */
  def has[E](e: E)(implicit ev: T <:< List[E], es: Shape[E]): Where = Where.Has(path, es.encode(e))
  def asc: Order = Order(path)
  def desc: Order = Order(path, descending = true)
  /** the same path, untyped */
  def field: Field = Field(path)
  override def toString: String = s"Path(${path.mkString(".")})"
}
