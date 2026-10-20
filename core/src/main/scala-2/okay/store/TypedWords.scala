package okay.store

import scala.language.experimental.macros
import scala.reflect.macros.whitebox

/** `typed.at("field")` — Scala 2.13: the field found in the case class `A` (or in the `A` of an `Option[A]`) by a
 * macro at compile time, the path typed as the field, its shape found as `Shape.derived` finds a field's */
trait TypedWords {
  /** `I` is what the lambda selects from — the `In` of the receiver, a type parameter so the conversion keeps it */
  implicit class AtWords[A, I](val typed: Typed[A] { type In = I }) {
    def at(name: String): Path[_] = macro StoreMacros.at[A]
    /** the same, the field selected: `mapping.at(_.qty) > 2`, `mapping.at(_.a.b) is "x"` — the names read from the lambda, the type the field's own */
    def at[B](sel: I => B): Path[B] = macro StoreMacros.atSel[A, B]
  }
}

object StoreMacros {
  private def bareOf(c: whitebox.Context)(t: c.Type): c.Type = {
    import c.universe._
    if (t.typeConstructor =:= typeOf[Option[_]].typeConstructor) t.typeArgs.head else t
  }
  def atSel[A: c.WeakTypeTag, B: c.WeakTypeTag](c: whitebox.Context)(sel: c.Tree): c.Tree = {
    import c.universe._
    import okay.effects.shape.ShapeMacros
    val names = ShapeMacros.selectorPath(c)(sel)
    val b = weakTypeOf[B]
    q"new _root_.okay.store.Path[$b](${c.prefix.tree}.typed.path ++ _root_.scala.List(..$names), ${ShapeMacros.shapeOf(c)(b, true)}) { type In = ${bareOf(c)(b)} }"
  }
  def at[A: c.WeakTypeTag](c: whitebox.Context)(name: c.Tree): c.Tree = {
    import c.universe._
    import okay.effects.shape.ShapeMacros
    val n = name match { case Literal(Constant(s: String)) => s; case _ => c.abort(name.pos, "a field's name here is a literal, so the compiler can check it") }
    val a = weakTypeOf[A]
    val bare = bareOf(c)(a)
    ShapeMacros.fieldsOf(c)(bare) match {
      case None => c.abort(c.enclosingPosition, s"at(\"$n\") names a field of a case class; $bare is not one — Field(\"$n\") says a path the compiler cannot check")
      case Some(fields) => fields.find(_._1 == n) match {
        case Some((_, t)) => q"new _root_.okay.store.Path[$t](${c.prefix.tree}.typed.path :+ $n, ${ShapeMacros.shapeOf(c)(t, true)}) { type In = ${bareOf(c)(t)} }"
        case None => c.abort(name.pos, s"no field '$n' in $bare: ${fields.map(_._1).mkString(", ")}")
      }
    }
  }
}
