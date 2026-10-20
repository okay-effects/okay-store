package okay.store

import scala.language.experimental.macros
import scala.reflect.macros.whitebox

/** `typed.at("field")` — Scala 2.13: the field found in the case class `A` (or in the `A` of an `Option[A]`) by a
 * macro at compile time, the path typed as the field, its shape found as `Shape.derived` finds a field's */
trait TypedWords {
  implicit class AtWords[A](val typed: Typed[A]) {
    def at(name: String): Path[_] = macro StoreMacros.at[A]
  }
}

object StoreMacros {
  def at[A: c.WeakTypeTag](c: whitebox.Context)(name: c.Tree): c.Tree = {
    import c.universe._
    import okay.effects.shape.ShapeMacros
    val n = name match { case Literal(Constant(s: String)) => s; case _ => c.abort(name.pos, "a field's name here is a literal, so the compiler can check it") }
    val a = weakTypeOf[A]
    val bare = if (a.typeConstructor =:= typeOf[Option[_]].typeConstructor) a.typeArgs.head else a
    ShapeMacros.fieldsOf(c)(bare) match {
      case None => c.abort(c.enclosingPosition, s"at(\"$n\") names a field of a case class; $bare is not one — Field(\"$n\") says a path the compiler cannot check")
      case Some(fields) => fields.find(_._1 == n) match {
        case Some((_, t)) => q"new _root_.okay.store.Path[$t](${c.prefix.tree}.typed.path :+ $n, ${ShapeMacros.shapeOf(c)(t, true)})"
        case None => c.abort(name.pos, s"no field '$n' in $bare: ${fields.map(_._1).mkString(", ")}")
      }
    }
  }
}
