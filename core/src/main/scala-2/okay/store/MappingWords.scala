package okay.store

import okay.effects.shape.Shape
import scala.language.experimental.macros
import scala.reflect.macros.blackbox

/** `Mapping.keyed(collection, shape)(_.id)` — Scala 2.13: the key field named by a lambda, read from its tree */
trait MappingWords {
  def keyed[A](collection: String, shape: Shape[A])(key: A => String): Mapping[A] = macro MappingMacros.keyed[A]
}

object MappingMacros {
  def keyed[A: c.WeakTypeTag](c: blackbox.Context)(collection: c.Tree, shape: c.Tree)(key: c.Tree): c.Tree = {
    import c.universe._
    okay.effects.shape.ShapeMacros.selectorPath(c)(key) match {
      case List(name) => q"_root_.okay.store.Mapping.keyed[${weakTypeOf[A]}]($collection, $shape, $name)"
      case names => c.abort(key.pos, s"the key is a field of the value itself: ${names.mkString(".")} goes within")
    }
  }
}
