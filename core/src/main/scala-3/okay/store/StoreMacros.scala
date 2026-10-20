package okay.store

import okay.effects.shape.{Selectors, ShapeDerivation}
import scala.quoted.*

/** `typed.at(_.field)` and `typed.at(_.a.b)` — Scala 3: the fields read from the lambda's tree, the path typed as the
 * last field, its shape found as `Shape.derived` finds a field's */
object StoreMacros:
  def at[A: Type, B: Type](path: Expr[List[String]], sel: Expr[A => B])(using Quotes): Expr[Path[?]] =
    import quotes.reflect.*
    val names = Selectors.path(sel)
    Selectors.typeOfSelected(sel).asType match
      case '[t] => '{ new Path[t]($path ++ ${ Expr(names) }, ShapeDerivation.shapeOf[t]) {} }
