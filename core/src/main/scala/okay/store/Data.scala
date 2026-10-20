package okay.store

import okay.effects.Effects
import okay.effects.json.{Descent, Json}
import okay.effects.shape.{Problem, Shape}
import okay.effects.shape.Shape.{any, bool, deferred, field, record, sub, sum, text, unit}

/**
 * THE LEVEL BELOW A DOMAIN: data as every store keeps it — COLLECTIONS of JSON documents by key, a query over them.
 * A SQL database (a table a collection, a JSON column the document), a document store, a key-value store, a file,
 * memory: each is a handler of `Data`. A domain effect (`Store`, an application's own `Orders`) is handled by a
 * TRANSLATION into `Data`, written once, and runs on every backend; a backend written once runs every domain. The
 * direct handlers (`Stores.keeping`, `jdbc`'s `Jdbcs.on`) are the particular mappings, kept for speed and as the
 * reference the translations are checked against.
 */
sealed trait Data[X]
object Data {
  final case class Get[X](collection: String, key: String, answer: Option[Json] => X) extends Data[X]
  /** put, or replace */
  final case class Put[X](collection: String, key: String, doc: Json, answer: Unit => X) extends Data[X]
  final case class Delete[X](collection: String, key: String, answer: Unit => X) extends Data[X]
  /** the documents that match, in order, at most `limit`, each with its key */
  final case class Query[X](collection: String, where: Where, order: List[Order], limit: Option[Int], answer: List[(String, Json)] => X) extends Data[X]
  final case class Count[X](collection: String, where: Where, answer: Long => X) extends Data[X]

  /** a collection's name: letters, digits and underscores, so it is a table's and a file's as it is */
  def named(collection: String): String =
    if (collection.matches("[a-z][a-z0-9_]*")) collection else throw new IllegalArgumentException(s"a collection is named [a-z][a-z0-9_]*, not '$collection'")
}

/** A CONDITION on a document, by paths into it — the part of a query every backend can say: equality, a text within
 * (case-insensitive, in any leaf under the path), an element of an array, less and greater, and, or, not */
sealed trait Where
object Where {
  case object All extends Where
  final case class Eq(path: List[String], value: Json) extends Where
  /** the text within any leaf under the path — a string itself, the elements of an array, the values of an object,
   * recursively — ignoring case */
  final case class Contains(path: List[String], text: String) extends Where
  /** the array at the path has the value */
  final case class Has(path: List[String], value: Json) extends Where
  final case class Less(path: List[String], than: Json) extends Where
  final case class Greater(path: List[String], than: Json) extends Where
  final case class And(all: List[Where]) extends Where
  final case class Or(any: List[Where]) extends Where
  final case class Not(w: Where) extends Where
  def and(ws: Where*): Where = And(ws.toList)
  def or(ws: Where*): Where = Or(ws.toList)

  /** a condition as a value, for a wire: `{"type": "eq", "path": [...], "value": ...}`, the connectives recursive */
  val path: Shape[List[String]] = text.many()
  lazy val shape: Shape[Where] = sum(
    sub[Where, All.type]("all", unit.map[All.type](_ => All)(_ => ())) { case All => Some(All); case _ => None },
    sub[Where, Eq]("eq", record(field("path", path), field("value", any))(Eq(_, _))(w => (w.path, w.value))) { case w: Eq => Some(w); case _ => None },
    sub[Where, Contains]("contains", record(field("path", path), field("text", text))(Contains(_, _))(w => (w.path, w.text))) { case w: Contains => Some(w); case _ => None },
    sub[Where, Has]("has", record(field("path", path), field("value", any))(Has(_, _))(w => (w.path, w.value))) { case w: Has => Some(w); case _ => None },
    sub[Where, Less]("less", record(field("path", path), field("than", any))(Less(_, _))(w => (w.path, w.than))) { case w: Less => Some(w); case _ => None },
    sub[Where, Greater]("greater", record(field("path", path), field("than", any))(Greater(_, _))(w => (w.path, w.than))) { case w: Greater => Some(w); case _ => None },
    sub[Where, And]("and", record(field("all", deferred("where")(shape).many()))(And(_))(_.all)) { case w: And => Some(w); case _ => None },
    sub[Where, Or]("or", record(field("any", deferred("where")(shape).many()))(Or(_))(_.any)) { case w: Or => Some(w); case _ => None },
    sub[Where, Not]("not", record(field("where", deferred("where")(shape)))(Not(_))(_.w)) { case w: Not => Some(w); case _ => None })

  /** the value at a path: object members by name, array items by index */
  def at(doc: Json, path: List[String]): Option[Json] = path match {
    case Nil => Some(doc)
    case p :: rest => doc match {
      case o: Json.Obj => o.get(p).flatMap(at(_, rest))
      case Json.Arr(xs) => p.toIntOption.filter(i => i >= 0 && i < xs.size).flatMap(i => at(xs(i), rest))
      case _ => None
    }
  }
  /** the leaves' texts: a string as it is, a number or a boolean printed, null nothing */
  def leaves(v: Json): List[String] = v match {
    case Json.Str(s) => List(s)
    case Json.Null => Nil
    case Json.Arr(xs) => xs.toList.flatMap(leaves)
    case Json.Obj(fs) => fs.toList.flatMap { case (_, x) => leaves(x) }
    case other => List(Json.print(other))
  }
  /** less and greater hold between values of one kind — numbers by value, strings by text, booleans false < true —
   * and never for a missing value, a null, or two kinds, as SQL's NULL and a type mismatch compare to nothing */
  def ordered(a: Json, b: Json): Option[Int] = (a, b) match {
    case (Json.Num(x), Json.Num(y)) => Some(x.compare(y))
    case (Json.Str(x), Json.Str(y)) => Some(x.compare(y))
    case (Json.Bool(x), Json.Bool(y)) => Some(x.compare(y))
    case _ => None
  }
  /** the order for sorting, total: missing and null first, then numbers, strings, booleans, the rest by their JSON text */
  private def rank(v: Option[Json]): Int = v match { case None | Some(Json.Null) => 0; case Some(Json.Num(_)) => 1; case Some(Json.Str(_)) => 2; case Some(Json.Bool(_)) => 3; case _ => 4 }
  def compare(a: Option[Json], b: Option[Json]): Int = rank(a).compare(rank(b)) match {
    case 0 => (a, b) match {
      case (Some(x), Some(y)) => ordered(x, y).getOrElse(if (rank(a) == 0) 0 else Json.print(x).compare(Json.print(y)))
      case _ => 0
    }
    case c => c
  }
  /** THE REFERENCE SEMANTICS: what every backend's query must mean */
  def holds(w: Where, doc: Json): Boolean = w match {
    case All => true
    case Eq(path, value) => at(doc, path).contains(value)
    case Contains(path, text) => val t = text.toLowerCase; at(doc, path).exists(v => leaves(v).exists(_.toLowerCase.contains(t)))
    case Has(path, value) => at(doc, path).exists { case Json.Arr(xs) => xs.contains(value); case _ => false }
    case Less(path, than) => at(doc, path).flatMap(ordered(_, than)).exists(_ < 0)
    case Greater(path, than) => at(doc, path).flatMap(ordered(_, than)).exists(_ > 0)
    case And(all) => all.forall(holds(_, doc))
    case Or(any) => any.exists(holds(_, doc))
    case Not(x) => !holds(x, doc)
  }
}
/** an ordering by a path; ascending unless said */
final case class Order(path: List[String], descending: Boolean = false)
object Order {
  val shape: Shape[Order] = record(field("path", Where.path), field("descending", bool).withDefault(false))(Order(_, _))(o => (o.path, o.descending))
  def by(path: String*): Order = Order(path.toList)
  def desc(path: String*): Order = Order(path.toList, descending = true)
  /** documents in this order, a stable sort, the rest of the order breaking ties */
  def sort[A](docs: List[(A, Json)], order: List[Order]): List[(A, Json)] =
    order.reverse.foldLeft(docs)((ds, o) => ds.sortWith { case ((_, x), (_, y)) =>
      val c = Where.compare(Where.at(x, o.path), Where.at(y, o.path)); if (o.descending) c > 0 else c < 0 })
  /** the reference: filtered, ordered, limited */
  def select(docs: List[(String, Json)], where: Where, order: List[Order], limit: Option[Int]): List[(String, Json)] = {
    val found = sort(docs.filter { case (_, d) => Where.holds(where, d) }, order)
    limit.fold(found)(found.take)
  }
}

class Datas[F <: Effects](implicit implementation: F) {
  val fx: F = implementation
  import fx._

  def get[R[_]](collection: String, key: String)(implicit in: In[Data, R]): Option[Json] ! R = perform[Data].apply[R, Option[Json]](Data.Get(collection, key, x => x))
  def put[R[_]](collection: String, key: String, doc: Json)(implicit in: In[Data, R]): Unit ! R = perform[Data].apply[R, Unit](Data.Put(collection, key, doc, x => x))
  def delete[R[_]](collection: String, key: String)(implicit in: In[Data, R]): Unit ! R = perform[Data].apply[R, Unit](Data.Delete(collection, key, x => x))
  def query[R[_]](collection: String, where: Where = Where.All, order: List[Order] = Nil, limit: Option[Int] = None)(implicit in: In[Data, R]): List[(String, Json)] ! R =
    perform[Data].apply[R, List[(String, Json)]](Data.Query(collection, where, order, limit, x => x))
  def count[R[_]](collection: String, where: Where = Where.All)(implicit in: In[Data, R]): Long ! R = perform[Data].apply[R, Long](Data.Count(collection, where, x => x))

  /** A TYPED COLLECTION: documents of a shape, keyed by a function of the value — the words a domain's handler is
   * written in. What is in the store and does not read by the shape is the store's fault, not a problem for a form */
  final case class Collection[A](name: String, shape: Shape[A], key: A => String) extends Typed[A] {
    Data.named(name)
    def path: List[String] = Nil
    def decode(key: String, doc: Json): A = shape.decode(doc).fold(ps => throw new IllegalStateException(s"$name/$key does not read: ${Problem.render(ps)}"), a => a)
  }
  def keep[A, R[_]](c: Collection[A], a: A)(implicit in: In[Data, R]): Unit ! R = put[R](c.name, c.key(a), c.shape.encode(a))
  def fetch[A, R[_]](c: Collection[A], key: String)(implicit in: In[Data, R]): Option[A] ! R = get[R](c.name, key).map(_.map(c.decode(key, _)))
  def remove[A, R[_]](c: Collection[A], key: String)(implicit in: In[Data, R]): Unit ! R = delete[R](c.name, key)
  def select[A, R[_]](c: Collection[A], where: Where = Where.All, order: List[Order] = Nil, limit: Option[Int] = None)(implicit in: In[Data, R]): List[A] ! R =
    query[R](c.name, where, order, limit).map(_.map { case (k, d) => c.decode(k, d) })

  // ---- THE BACKENDS of no platform: memory, and lines appended to files
  /** in memory: the collections in a cell, answered in place */
  def inMemory(initial: Map[String, Map[String, Json]] = Map.empty): InMemory = new InMemory(initial)
  final class InMemory(initial: Map[String, Map[String, Json]]) {
    def apply[R[_], Rest[_], A](p: A ! R)(implicit sp: Split[Data, R, Rest]): (Map[String, Map[String, Json]], A) ! Rest =
      handle[Data, Map[String, Map[String, Json]], R, Rest, A, (Map[String, Map[String, Json]], A)](p, initial,
        new StatefulTailHandler[Data, Map[String, Map[String, Json]], Rest, A, (Map[String, Map[String, Json]], A)] {
          def ret(s: Map[String, Map[String, Json]], a: A) = pure((s, a))
          def answer[X](cell: Cell[Map[String, Map[String, Json]]], op: Data[X]): X = {
            def docs(c: String) = cell.get.getOrElse(Data.named(c), Map.empty[String, Json])
            op match {
              case Data.Get(c, k, answer) => answer(docs(c).get(k))
              case Data.Put(c, k, d, answer) => cell.set(cell.get.updated(c, docs(c).updated(k, d))); answer(())
              case Data.Delete(c, k, answer) => cell.set(cell.get.updated(c, docs(c) - k)); answer(())
              case Data.Query(c, w, o, l, answer) => answer(Order.select(docs(c).toList, w, o, l))
              case Data.Count(c, w, answer) => answer(docs(c).count { case (_, d) => Where.holds(w, d) }.toLong)
            }
          }
        })
  }

  /** ON LINES APPENDED: a collection is a file of lines, `{"key": k, "doc": d}` for a put and `{"key": k, "gone": true}`
   * for a delete, appended and never rewritten — the last line of a key wins, read by folding the file; crash-safe,
   * compacted never (yet). `read` and `append` are the platform's: a directory's files (`Directory`), a test's map */
  def onLines(read: String => List[String], append: (String, String) => Unit): OnLines = new OnLines(read, append)
  final class OnLines(read: String => List[String], append: (String, String) => Unit) {
    private def file(c: String) = Data.named(c) + ".jsonl"
    private def docs(c: String): Map[String, Json] = read(file(c)).filter(_.trim.nonEmpty).foldLeft(Map.empty[String, Json]) { (m, line) =>
      Descent.parse(line).toOption match {
        case Some(o: Json.Obj) => (o.get("key"), o.get("doc"), o.get("gone")) match {
          case (Some(Json.Str(k)), Some(d), _) => m.updated(k, d)
          case (Some(Json.Str(k)), None, Some(Json.Bool(true))) => m - k
          case _ => m
        }
        case _ => m
      }
    }
    def apply[R[_], Rest[_], A](p: A ! R)(implicit sp: Split[Data, R, Rest]): A ! Rest =
      handle[Data, R, Rest, A, A](p, new TailHandler[Data, Rest, A, A] {
        def ret(a: A) = pure(a)
        def answer[X](op: Data[X]): X = op match {
          case Data.Get(c, k, answer) => answer(docs(c).get(k))
          case Data.Put(c, k, d, answer) => append(file(c), Json.print(Json.obj("key" -> Json.Str(k), "doc" -> d))); answer(())
          case Data.Delete(c, k, answer) => append(file(c), Json.print(Json.obj("key" -> Json.Str(k), "gone" -> Json.Bool(true)))); answer(())
          case Data.Query(c, w, o, l, answer) => answer(Order.select(docs(c).toList, w, o, l))
          case Data.Count(c, w, answer) => answer(docs(c).count { case (_, d) => Where.holds(w, d) }.toLong)
        }
      })
  }
}
object Datas {
  def apply()(implicit fx: Effects): Datas[fx.type] = { implicit val exact: fx.type = fx; new Datas[fx.type] }
}
