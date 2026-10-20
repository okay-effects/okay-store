package okay.store

import okay.effects.Effects
import okay.effects.describe.{Call, Describe, Op}
import okay.effects.json.Json
import okay.effects.shape.{Problem, Schema, Shape}
import okay.effects.shape.Shape.{field, int, long, record, text, unit}

/**
 * A TYPE MAPPED TO A STORE — the whole of an ORM, on effects and not on proxies. A `Mapping[A]` says what a value of
 * `A` is in a store: its collection, its `Shape` (the columns, the document, the validation) and its key. With it the
 * effect `Mapped[A, _]` — save, load, remove, find by a `Where`, count — is the type's own repository, typed by `A`,
 * and `Mappings.onData(mapping)` is its handler on `Data`, the same for every type and every backend: memory, files,
 * SQLite, whatever handles `Data`. No proxies, no sessions, no lazy anything: a program performs `save(a)`; where it
 * goes is the handlers' business, chosen at the edge. Several types are several effects in one row, each its own
 * handler, as `State[S]` is in okay-simple.
 */
final case class Mapping[A](collection: String, shape: Shape[A], key: A => String, keyField: Option[String] = None) extends Typed[A] {
  Data.named(collection)
  /** a path starts here: `mapping.at("qty")` */
  def path: List[String] = Nil
  type In = A
  def decode(key: String, doc: Json): A = shape.decode(doc).fold(ps => throw new IllegalStateException(s"$collection/$key does not read by its shape: ${Problem.render(ps)}"), a => a)
  /** the value with this key in its key field — what `create` over a wire needs; only when the key is a field */
  def rekey(a: A, key: String): A = keyField match {
    case Some(f) => shape.encode(a) match {
      case o: Json.Obj => decode(key, o.without(f).+:(f -> Json.Str(key)))
      case other => throw new IllegalStateException(s"$collection: a value encodes to ${Json.kind(other)}, not a record")
    }
    case None => throw new IllegalArgumentException(s"$collection: the key is a function of the value, not a field — create crosses a wire only with a key field")
  }
}

object Mapping extends MappingWords {
  /** THE KEY A FIELD: `Mapping.keyed("sales", shape, "id")` — the key read from the field, and put there by the store's
   * `create` (`rekey`), so `create` crosses a wire: the value without its key goes, the value with the store's key
   * comes back. `Mapping.keyed("sales", shape)(_.id)` names the field with the compiler's check */
  def keyed[A](collection: String, shape: Shape[A], field: String): Mapping[A] = {
    val fields = shape.erased match { case Schema.Record(fs) => fs.map(_.name); case Schema.Described(Schema.Record(fs), _, _) => fs.map(_.name); case _ => Vector.empty }
    require(fields.contains(field), s"$collection: no field '$field' to be the key among ${fields.mkString(", ")}")
    Mapping[A](collection, shape, a => shape.encode(a) match {
      case o: Json.Obj => o.get(field) match { case Some(Json.Str(k)) => k; case other => throw new IllegalStateException(s"$collection: the key field '$field' is ${other.map(Json.kind).getOrElse("missing")}, not text") }
      case other => throw new IllegalStateException(s"$collection: a value encodes to ${Json.kind(other)}, not a record")
    }, Some(field))
  }
  /** THE TYPE'S EFFECT DESCRIBED, from its mapping alone: so a mapped type is served — REST resources, MCP tools — and
   * performed from afar with no more said. `find` and `count` take a `Where` and an `Order` as values */
  def describe[A](m: Mapping[A]): Describe[({ type L[X] = Mapped[A, X] })#L] = new Describe[({ type L[X] = Mapped[A, X] })#L](m.collection) {
    type E[X] = Mapped[A, X]
    private val byKey = record(field("key", text))(k => k)(k => k)
    val save: Op[E, A, Unit] = Op("save", m.shape, unit, (a: A) => Mapped.Save[A, Unit](a, x => x), Some(s"a ${m.collection} value kept, by its key"), writes = true)
    val load: Op[E, String, Option[A]] = Op("load", byKey, m.shape.optional, (k: String) => Mapped.Load[A, Option[A]](k, x => x), Some("the value of a key"))
    val remove: Op[E, String, Unit] = Op("remove", byKey, unit, (k: String) => Mapped.Remove[A, Unit](k, x => x), Some("the value of a key removed"), writes = true)
    val find: Op[E, (Where, List[Order], Option[Int]), List[A]] = Op("find",
      record(field("where", Where.shape).withDefault(Where.All), field("order", Order.shape.many()).withDefault(Nil), field("limit", int.optional))((w, o, l) => (w, o, l))(x => x),
      m.shape.many(), (a: (Where, List[Order], Option[Int])) => Mapped.Find[A, List[A]](a._1, a._2, a._3, x => x), Some("the values that match, in order, at most limit"))
    val count: Op[E, Where, Long] = Op("count", record(field("where", Where.shape).withDefault(Where.All))(w => w)(w => w), long, (w: Where) => Mapped.Count[A, Long](w, x => x), Some("how many match"))
    /** `create`, when the key is a field: the value without its key (the field defaulted to "") in, the value with the
     * store's key out; over a wire, `create(make)` sends `make("")` */
    val create: Option[Op[E, A, A]] = m.keyField.map(f =>
      Op("create", m.shape.defaulted(f, Json.Str("")), m.shape, (a: A) => Mapped.Create[A, A](key => m.rekey(a, key), x => x), Some(s"a ${m.collection} value kept under a key from the store, its '$f' the store's"), writes = true))
    val operations = List(save, load, remove, find, count) ++ create.toList
    def split[X](op: E[X]): Call[X] = op match {
      case Mapped.Save(a, k) => call(save, a, k)
      case Mapped.Load(key, k) => call(load, key, k)
      case Mapped.Remove(key, k) => call(remove, key, k)
      case Mapped.Find(w, o, l, k) => call(find, (w, o, l), k)
      case Mapped.Count(w, k) => call(count, w, k)
      case Mapped.Create(make, k) => create match {
        case Some(c) => call(c, make(""), k)
        case None => throw new IllegalArgumentException(s"${m.collection}: create crosses a wire only when the key is a field (Mapping.keyed)")
      }
    }
  }
}

sealed trait Mapped[A, X]
object Mapped {
  /** put, or replace: the key is the value's */
  final case class Save[A, X](a: A, answer: Unit => X) extends Mapped[A, X]
  final case class Load[A, X](key: String, answer: Option[A] => X) extends Mapped[A, X]
  final case class Remove[A, X](key: String, answer: Unit => X) extends Mapped[A, X]
  final case class Find[A, X](where: Where, order: List[Order], limit: Option[Int], answer: List[A] => X) extends Mapped[A, X]
  final case class Count[A, X](where: Where, answer: Long => X) extends Mapped[A, X]
  /** a value made with a key from the store and kept: `create(key => Sale(key, ...))` — the key the store's, never
   * given twice; the value's own `key` is expected to be it */
  final case class Create[A, X](make: String => A, answer: A => X) extends Mapped[A, X]
}

/** a path into a document, for a `Where` that reads: `field("qty") > 3`, `field("item") contains "tea"` */
final case class Field(path: List[String]) {
  def is(v: Json): Where = Where.Eq(path, v)
  def is(s: String): Where = is(Json.Str(s))
  def is(n: Int): Where = is(Json.num(n))
  def is(n: Long): Where = is(Json.num(n))
  def is(b: Boolean): Where = is(Json.Bool(b))
  def isNull: Where = is(Json.Null)
  def contains(text: String): Where = Where.Contains(path, text)
  def has(v: Json): Where = Where.Has(path, v)
  def has(s: String): Where = has(Json.Str(s))
  def <(v: Json): Where = Where.Less(path, v)
  def <(n: Int): Where = this < Json.num(n)
  def <(n: Long): Where = this < Json.num(n)
  def <(s: String): Where = this < Json.Str(s)
  def >(v: Json): Where = Where.Greater(path, v)
  def >(n: Int): Where = this > Json.num(n)
  def >(n: Long): Where = this > Json.num(n)
  def >(s: String): Where = this > Json.Str(s)
  def asc: Order = Order(path)
  def desc: Order = Order(path, descending = true)
  def /(name: String): Field = Field(path :+ name)
}
object Field { def apply(path: String*): Field = new Field(path.toList) }

class Mappings[F <: Effects](implicit implementation: F) {
  val fx: F = implementation
  import fx._
  val datas: Datas[fx.type] = new Datas[fx.type]()(fx)

  /** the type's effect, as a row's member: `In[Of[A]#L, R]` */
  type Of[A] = { type L[X] = Mapped[A, X] }

  def save[A, R[_]](a: A)(implicit in: In[({ type L[X] = Mapped[A, X] })#L, R]): Unit ! R =
    perform[({ type L[X] = Mapped[A, X] })#L].apply[R, Unit](Mapped.Save[A, Unit](a, x => x))
  def load[A, R[_]](key: String)(implicit in: In[({ type L[X] = Mapped[A, X] })#L, R]): Option[A] ! R =
    perform[({ type L[X] = Mapped[A, X] })#L].apply[R, Option[A]](Mapped.Load[A, Option[A]](key, x => x))
  def remove[A, R[_]](key: String)(implicit in: In[({ type L[X] = Mapped[A, X] })#L, R]): Unit ! R =
    perform[({ type L[X] = Mapped[A, X] })#L].apply[R, Unit](Mapped.Remove[A, Unit](key, x => x))
  def find[A, R[_]](where: Where = Where.All, order: List[Order] = Nil, limit: Option[Int] = None)(implicit in: In[({ type L[X] = Mapped[A, X] })#L, R]): List[A] ! R =
    perform[({ type L[X] = Mapped[A, X] })#L].apply[R, List[A]](Mapped.Find[A, List[A]](where, order, limit, x => x))
  def all[A, R[_]](order: List[Order] = Nil)(implicit in: In[({ type L[X] = Mapped[A, X] })#L, R]): List[A] ! R = find[A, R](Where.All, order)
  def count[A, R[_]](where: Where = Where.All)(implicit in: In[({ type L[X] = Mapped[A, X] })#L, R]): Long ! R =
    perform[({ type L[X] = Mapped[A, X] })#L].apply[R, Long](Mapped.Count[A, Long](where, x => x))
  def create[A, R[_]](make: String => A)(implicit in: In[({ type L[X] = Mapped[A, X] })#L, R]): A ! R =
    perform[({ type L[X] = Mapped[A, X] })#L].apply[R, A](Mapped.Create[A, A](make, x => x))

  /** THE HANDLER, one for every type: the mapping's effect onto `Data` — a save a put of the encoded value by its
   * key, a find a query decoded by the shape — so the type is in whatever handles `Data` next */
  def onData[A](m: Mapping[A]): OnData[A] = new OnData[A](m)
  final class OnData[A](m: Mapping[A]) {
    type E[X] = Mapped[A, X]
    def apply[R[_], Rest[_], B](p: B ! R)(implicit sp: Split[E, R, Rest], d: In[Data, Rest]): B ! Rest =
      handle[E, R, Rest, B, B](p, new Handler[E, Rest, B, B] {
        def ret(b: B) = pure(b)
        def op[X](op: E[X], k: X => B ! Rest) = op match {
          case Mapped.Save(a, answer) => datas.put[Rest](m.collection, m.key(a), m.shape.encode(a)).flatMap(_ => k(answer(())))
          case Mapped.Load(key, answer) => datas.get[Rest](m.collection, key).flatMap(d => k(answer(d.map(m.decode(key, _)))))
          case Mapped.Remove(key, answer) => datas.delete[Rest](m.collection, key).flatMap(_ => k(answer(())))
          case Mapped.Find(w, o, l, answer) => datas.query[Rest](m.collection, w, o, l).flatMap(ds => k(answer(ds.map { case (key, d) => m.decode(key, d) })))
          case Mapped.Count(w, answer) => datas.count[Rest](m.collection, w).flatMap(n => k(answer(n)))
          case Mapped.Create(make, answer) => datas.fresh[Rest](m.collection).flatMap { key => val a = make(key); datas.put[Rest](m.collection, m.key(a), m.shape.encode(a)).flatMap(_ => k(answer(a))) }
        }
      })
  }
}
object Mappings {
  def apply()(implicit fx: Effects): Mappings[fx.type] = { implicit val exact: fx.type = fx; new Mappings[fx.type] }
}
