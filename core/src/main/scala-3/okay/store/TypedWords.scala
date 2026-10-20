package okay.store

import okay.effects.shape.ShapeDerivation
import scala.compiletime.{constValue, error}
import scala.deriving.Mirror

/** `typed.at("field")` — Scala 3: the field found in the case class `A` (or in the `A` of an `Option[A]`) by its
 * `Mirror` at compile time, the path typed as the field, its shape found as `Shape.derived` finds a field's */
trait TypedWords:
  extension [A](t: Typed[A])
    inline def at[N <: String & Singleton](name: N)(using m: Mirror.ProductOf[TypedWords.Bare[A]]): Path[ShapeDerivation.TypeOf[N, m.MirroredElemLabels, m.MirroredElemTypes]] =
      inline if ShapeDerivation.has[N, m.MirroredElemLabels] then
        new Path(t.path :+ name, ShapeDerivation.shapeOf[ShapeDerivation.TypeOf[N, m.MirroredElemLabels, m.MirroredElemTypes]]) {}
      else error("no field '" + constValue[N] + "' in the type")
    /** the same, the field selected: `mapping.at(_.qty) > 2`, `mapping.at(_.a.b) is "x"` — the names read from the lambda, the type the field's own */
    transparent inline def at[B](inline sel: TypedWords.Bare[A] => B): Path[?] = ${ StoreMacros.at[TypedWords.Bare[A], B]('{ t.path }, 'sel) }

object TypedWords:
  /** the type a path goes into: through an `Option` */
  type Bare[A] = A match
    case Option[b] => b
    case _ => A
