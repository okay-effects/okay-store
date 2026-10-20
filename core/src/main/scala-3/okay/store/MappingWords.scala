package okay.store

import okay.effects.shape.{Selectors, Shape}
import scala.quoted.*

/** `Mapping.keyed(collection, shape)(_.id)` — Scala 3: the key field named by a lambda, read from its tree */
trait MappingWords:
  inline def keyed[A](collection: String, shape: Shape[A])(inline key: A => String): Mapping[A] = ${ MappingWords.keyed[A]('collection, 'shape, 'key) }

object MappingWords:
  def keyed[A: Type](collection: Expr[String], shape: Expr[Shape[A]], key: Expr[A => String])(using Quotes): Expr[Mapping[A]] =
    import quotes.reflect.*
    Selectors.path(key) match
      case List(name) => '{ Mapping.keyed[A]($collection, $shape, ${ Expr(name) }) }
      case names => report.errorAndAbort("the key is a field of the value itself: " + names.mkString(".") + " goes within")
