package okay.store

import okay.effects.Effects
import okay.effects.json.{Descent, Json}
import okay.effects.shape.{Problem, Shape}
import okay.effects.shape.Shape.{any, bool, deferred, field, record, sub, sum, text, unit}
import scala.util.chaining._

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
  /** A KEY FROM THE STORE: the next of the collection's own sequence, never given twice — "000000000001", so keys sort
   * as they were given; the backend keeps the count where it keeps everything else */
  final case class Fresh[X](collection: String, answer: String => X) extends Data[X]
  /** a key as the store gives it: the sequence's number, twelve digits */
  def key(n: Long): String = f"$n%012d"

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
  def fresh[R[_]](collection: String)(implicit in: In[Data, R]): String ! R = perform[Data].apply[R, String](Data.Fresh(collection, x => x))

  /** A TYPED COLLECTION: documents of a shape, keyed by a function of the value — the words a domain's handler is
   * written in. What is in the store and does not read by the shape is the store's fault, not a problem for a form */
  final case class Collection[A](name: String, shape: Shape[A], key: A => String) extends Typed[A] {
    Data.named(name)
    def path: List[String] = Nil
    type In = A
    def decode(key: String, doc: Json): A = shape.decode(doc).fold(ps => throw new IllegalStateException(s"$name/$key does not read: ${Problem.render(ps)}"), a => a)
  }
  def keep[A, R[_]](c: Collection[A], a: A)(implicit in: In[Data, R]): Unit ! R = put[R](c.name, c.key(a), c.shape.encode(a))
  def fetch[A, R[_]](c: Collection[A], key: String)(implicit in: In[Data, R]): Option[A] ! R = get[R](c.name, key).map(_.map(c.decode(key, _)))
  def remove[A, R[_]](c: Collection[A], key: String)(implicit in: In[Data, R]): Unit ! R = delete[R](c.name, key)
  def select[A, R[_]](c: Collection[A], where: Where = Where.All, order: List[Order] = Nil, limit: Option[Int] = None)(implicit in: In[Data, R]): List[A] ! R =
    query[R](c.name, where, order, limit).map(_.map { case (k, d) => c.decode(k, d) })
  /** a value made with a key from the store, kept: `add(c, key => Sale(key, ...))` */
  def add[A, R[_]](c: Collection[A], make: String => A)(implicit in: In[Data, R]): A ! R = fresh[R](c.name).flatMap { k => val a = make(k); keep[A, R](c, a).map(_ => a) }

  // ---- THE BACKENDS: what answers `Data` in place, and the handler over one
  /** the handler: every operation answered by the backend, in place */
  def on(backend: Backend): On = new On(backend)
  final class On(backend: Backend) {
    def apply[R[_], Rest[_], A](p: A ! R)(implicit sp: Split[Data, R, Rest]): A ! Rest =
      handle[Data, R, Rest, A, A](p, new TailHandler[Data, Rest, A, A] {
        def ret(a: A) = pure(a)
        def answer[X](op: Data[X]): X = backend.answer(op)
      })
  }
  /** in memory: the collections in a `Memory`, the state answered with the value */
  def inMemory(initial: Map[String, Map[String, Json]] = Map.empty): InMemory = new InMemory(initial)
  final class InMemory(initial: Map[String, Map[String, Json]]) {
    def apply[R[_], Rest[_], A](p: A ! R)(implicit sp: Split[Data, R, Rest]): (Map[String, Map[String, Json]], A) ! Rest = {
      val memory = new Memory(initial)
      on(memory)(p).map(a => (memory.state, a))
    }
  }
  /** on lines appended to files: `Lines`, with `compact` beside it */
  def onLines(read: String => List[String], append: (String, String) => Unit): OnLines = new OnLines(new Lines(read, append))
  final class OnLines(val lines: Lines) {
    def apply[R[_], Rest[_], A](p: A ! R)(implicit sp: Split[Data, R, Rest]): A ! Rest = on(lines)(p)
    def compact(collections: List[String], write: (String, List[String]) => Unit): Unit = lines.compact(collections, write)
  }

  /** ATOMICALLY: `p`'s writes kept in a log of its own (`Transaction`) and written to the backend when `p` ends — a
   * read sees the log first, a query the backend's rows with the log laid over them, in the reference's order — so
   * what ends `p` before (an error attempted outside, an abort) drops the log and nothing is written. The same on
   * every backend, as okay-simple's `transaction` is over its `Store`; a backend's own transaction is a later speed,
   * not a meaning. `Fresh` goes to the backend at once: a key given to a transaction that ends early is spent, never
   * given twice. Nested, a transaction is over a transaction */
  def atomically(backend: Backend): Atomically = new Atomically(backend)
  final class Atomically(backend: Backend) {
    def apply[R[_], Rest[_], A](p: A ! R)(implicit sp: Split[Data, R, Rest]): A ! Rest =
      handle[Data, R, Rest, A, A](p, new TailHandler[Data, Rest, A, A] {
        val transaction = new Transaction(backend)
        def ret(a: A) = { transaction.commit(); pure(a) }
        def answer[X](op: Data[X]): X = transaction.answer(op)
      })
  }
}

/** A BACKEND: what answers `Data` in place — memory, files, a database — one value, handled by `Datas.on` */
trait Backend { def answer[X](op: Data[X]): X }

/** IN MEMORY: the collections in a map; the sequences in `_keys`, a name no collection may take */
final class Memory(initial: Map[String, Map[String, Json]] = Map.empty) extends Backend {
  @volatile var state: Map[String, Map[String, Json]] = initial
  private def docs(c: String) = state.getOrElse(Data.named(c), Map.empty[String, Json])
  def answer[X](op: Data[X]): X = op match {
    case Data.Fresh(c, answer) =>
      val keys = state.getOrElse("_keys", Map.empty[String, Json])
      val next = keys.get(Data.named(c)).flatMap(Where.at(_, List("next"))).collect { case Json.Num(n) => n.toLong }.getOrElse(0L) + 1
      state = state.updated("_keys", keys.updated(c, Json.obj("next" -> Json.num(next)))); answer(Data.key(next))
    case Data.Get(c, k, answer) => answer(docs(c).get(k))
    case Data.Put(c, k, d, answer) => state = state.updated(c, docs(c).updated(k, d)); answer(())
    case Data.Delete(c, k, answer) => state = state.updated(c, docs(c) - k); answer(())
    case Data.Query(c, w, o, l, answer) => answer(Order.select(docs(c).toList, w, o, l))
    case Data.Count(c, w, answer) => answer(docs(c).count { case (_, d) => Where.holds(w, d) }.toLong)
  }
}

/** ON LINES APPENDED: a collection is a file of lines, `{"key": k, "doc": d}` for a put and `{"key": k, "gone": true}`
 * for a delete, appended and never rewritten — the last line of a key wins, read by folding the file; crash-safe.
 * `read` and `append` are the platform's: a directory's files (`Directory`), a test's map. `compact` rewrites a file
 * as one line per key kept, by `write` — housekeeping at the edge, by the one who holds the files */
final class Lines(read: String => List[String], append: (String, String) => Unit) extends Backend {
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
  /** the sequences: a line per key given, `{"collection": c, "next": n}`, the last of a collection its count */
  private def counts: Map[String, Long] = read("_keys.jsonl").foldLeft(Map.empty[String, Long]) { (m, line) =>
    Descent.parse(line).toOption match {
      case Some(o: Json.Obj) => (o.get("collection"), o.get("next")) match { case (Some(Json.Str(c)), Some(Json.Num(n))) => m.updated(c, n.toLong); case _ => m }
      case _ => m
    }
  }
  def answer[X](op: Data[X]): X = op match {
    case Data.Fresh(c, answer) =>
      val next = counts.getOrElse(Data.named(c), 0L) + 1
      append("_keys.jsonl", Json.print(Json.obj("collection" -> Json.Str(c), "next" -> Json.num(next)))); answer(Data.key(next))
    case Data.Get(c, k, answer) => answer(docs(c).get(k))
    case Data.Put(c, k, d, answer) => append(file(c), Json.print(Json.obj("key" -> Json.Str(k), "doc" -> d))); answer(())
    case Data.Delete(c, k, answer) => append(file(c), Json.print(Json.obj("key" -> Json.Str(k), "gone" -> Json.Bool(true)))); answer(())
    case Data.Query(c, w, o, l, answer) => answer(Order.select(docs(c).toList, w, o, l))
    case Data.Count(c, w, answer) => answer(docs(c).count { case (_, d) => Where.holds(w, d) }.toLong)
  }
  def compact(collections: List[String], write: (String, List[String]) => Unit): Unit = {
    collections.foreach { c => write(file(c), docs(c).toList.map { case (k, d) => Json.print(Json.obj("key" -> Json.Str(k), "doc" -> d)) }) }
    val cs = counts
    if (cs.nonEmpty) write("_keys.jsonl", cs.toList.sortBy(_._1).map { case (c, n) => Json.print(Json.obj("collection" -> Json.Str(c), "next" -> Json.num(n))) })
  }
}

/** A TRANSACTION over a backend: the writes in a log until `commit`, the reads through it — `Datas.atomically` */
final class Transaction(under: Backend) extends Backend {
  /** a collection's keys to a document, or to nothing for a delete */
  @volatile var log: Map[String, Map[String, Option[Json]]] = Map.empty
  private def of(c: String) = log.getOrElse(c, Map.empty[String, Option[Json]])
  private def overlay(c: String, rows: List[(String, Json)], w: Where, o: List[Order], l: Option[Int]): List[(String, Json)] = {
    val kept = rows.filterNot { case (key, _) => of(c).contains(key) } ++ of(c).toList.collect { case (key, Some(d)) if Where.holds(w, d) => (key, d) }
    val sorted = Order.sort(kept, o)
    l.fold(sorted)(sorted.take)
  }
  def answer[X](op: Data[X]): X = op match {
    case Data.Get(c, key, answer) => of(c).get(key) match { case Some(d) => answer(d); case None => under.answer(Data.Get(c, key, x => x)).pipe(answer) }
    case Data.Put(c, key, doc, answer) => log = log.updated(c, of(c).updated(key, Some(doc))); answer(())
    case Data.Delete(c, key, answer) => log = log.updated(c, of(c).updated(key, None)); answer(())
    case Data.Query(c, w, o, l, answer) =>
      if (of(c).isEmpty) answer(under.answer(Data.Query(c, w, o, l, x => x)))
      else answer(overlay(c, under.answer(Data.Query(c, w, o, None, x => x)), w, o, l))
    case Data.Count(c, w, answer) =>
      if (of(c).isEmpty) answer(under.answer(Data.Count(c, w, x => x)))
      else answer(overlay(c, under.answer(Data.Query(c, w, Nil, None, x => x)), w, Nil, None).size.toLong)
    case Data.Fresh(c, answer) => answer(under.answer(Data.Fresh(c, x => x)))
  }
  /** the log written to the backend, in order, and emptied */
  def commit(): Unit = {
    log.foreach { case (c, ks) => ks.foreach { case (key, Some(d)) => under.answer(Data.Put(c, key, d, x => x)); case (key, None) => under.answer(Data.Delete(c, key, x => x)) } }
    log = Map.empty
  }
}

object Datas {
  def apply()(implicit fx: Effects): Datas[fx.type] = { implicit val exact: fx.type = fx; new Datas[fx.type] }
}
