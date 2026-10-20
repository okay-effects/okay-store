package okay.store

import okay.effects.Effects
import okay.effects.json.Json
import okay.effects.shape.Shape.{field, int, record, text, unit}
import okay.effects.describe.{Call, Describe, Op}

/**
 * THE STORE AS AN EFFECT: what is kept, asked and changed. A program performs it; the handler keeps the state (`Kept`)
 * and the journal; where the handler runs — in memory, behind REST on a server, as tools for an agent — is the
 * handler's business, and `Stores.describe` says enough for every face to serve or carry it.
 */
sealed trait Store[X]
object Store {
  final case class Kinds[X](answer: List[Kind] => X) extends Store[X]
  final case class Find[X](text: String, tag: Option[String], answer: List[Record] => X) extends Store[X]
  final case class Get[X](id: RecordId, answer: Option[Record] => X) extends Store[X]
  final case class History[X](id: RecordId, answer: List[Record] => X) extends Store[X]
  final case class Define[X](kind: Kind, answer: Unit => X) extends Store[X]
  /** a record added: the id and the version are the handler's */
  final case class Add[X](kind: KindId, fields: Json.Obj, meta: Json.Obj, tags: Set[String], note: Option[String], answer: Record => X) extends Store[X]
  /** a new version of a record */
  final case class Revise[X](id: RecordId, fields: Json.Obj, meta: Json.Obj, tags: Set[String], note: Option[String], answer: Option[Record] => X) extends Store[X]
  final case class Retire[X](id: RecordId, reason: Option[String], answer: Unit => X) extends Store[X]
  /** an access written down: who saw which field */
  final case class Audit[X](id: RecordId, field: String, by: String, answer: Unit => X) extends Store[X]
  /** the journal as it stands, and how many events are unsaved */
  final case class Journaled[X](answer: ((List[Event], Int)) => X) extends Store[X]
  final case class Saved[X](answer: Unit => X) extends Store[X]
}

class Stores[F <: Effects](implicit implementation: F) {
  val fx: F = implementation
  import fx._

  def kinds[R[_]](implicit in: In[Store, R]): List[Kind] ! R = perform[Store].apply[R, List[Kind]](Store.Kinds(x => x))
  def find[R[_]](text: String, tag: Option[String] = None)(implicit in: In[Store, R]): List[Record] ! R = perform[Store].apply[R, List[Record]](Store.Find(text, tag, x => x))
  def get[R[_]](id: RecordId)(implicit in: In[Store, R]): Option[Record] ! R = perform[Store].apply[R, Option[Record]](Store.Get(id, x => x))
  def history[R[_]](id: RecordId)(implicit in: In[Store, R]): List[Record] ! R = perform[Store].apply[R, List[Record]](Store.History(id, x => x))
  def define[R[_]](kind: Kind)(implicit in: In[Store, R]): Unit ! R = perform[Store].apply[R, Unit](Store.Define(kind, x => x))
  def add[R[_]](kind: KindId, fields: Json.Obj, meta: Json.Obj = Json.Obj.empty, tags: Set[String] = Set.empty, note: Option[String] = None)(implicit in: In[Store, R]): Record ! R =
    perform[Store].apply[R, Record](Store.Add(kind, fields, meta, tags, note, x => x))
  def revise[R[_]](id: RecordId, fields: Json.Obj, meta: Json.Obj = Json.Obj.empty, tags: Set[String] = Set.empty, note: Option[String] = None)(implicit in: In[Store, R]): Option[Record] ! R =
    perform[Store].apply[R, Option[Record]](Store.Revise(id, fields, meta, tags, note, x => x))
  def retire[R[_]](id: RecordId, reason: Option[String] = None)(implicit in: In[Store, R]): Unit ! R = perform[Store].apply[R, Unit](Store.Retire(id, reason, x => x))
  def audit[R[_]](id: RecordId, field: String, by: String)(implicit in: In[Store, R]): Unit ! R = perform[Store].apply[R, Unit](Store.Audit(id, field, by, x => x))
  def journaled[R[_]](implicit in: In[Store, R]): (List[Event], Int) ! R = perform[Store].apply[R, (List[Event], Int)](Store.Journaled(x => x))
  def saved[R[_]](implicit in: In[Store, R]): Unit ! R = perform[Store].apply[R, Unit](Store.Saved(x => x))

  /** THE HANDLER IN MEMORY: what is kept in a cell, in place; ids and the clock given, so a test is deterministic.
   * `find` is `Stores.matches`: by text in the fields' values, the tags and the note, and by a tag */
  def keeping(initial: Kept, ids: () => String, now: () => Long): Keeping = new Keeping(initial, ids, now)
  final class Keeping(initial: Kept, ids: () => String, now: () => Long) {
    def apply[R[_], Rest[_], A](p: A ! R)(implicit sp: Split[Store, R, Rest]): (Kept, A) ! Rest =
      handle[Store, Kept, R, Rest, A, (Kept, A)](p, initial, new StatefulTailHandler[Store, Kept, Rest, A, (Kept, A)] {
        def ret(v: Kept, a: A) = pure((v, a))
        def answer[X](cell: Cell[Kept], op: Store[X]): X = {
          val v = cell.get
          op match {
            case Store.Kinds(answer) => answer(v.kinds.values.toList.sortBy(_.name))
            case Store.Find(text, tag, answer) => answer(v.records.filter(r => Stores.matches(text, tag, r)))
            case Store.Get(id, answer) => answer(v.current(id))
            case Store.History(id, answer) => answer(v.history(id))
            case Store.Define(kind, answer) => cell.set(v(if (v.kinds.contains(kind.id)) Event.KindRevised(kind, now()) else Event.KindDefined(kind, now()))); answer(())
            case Store.Add(kind, fields, meta, tags, note, answer) =>
              val r = Record(RecordId(ids()), VersionId(ids()), None, kind, fields, meta, tags, note, now())
              cell.set(v(Event.RecordAdded(r))); answer(r)
            case Store.Revise(id, fields, meta, tags, note, answer) => v.current(id) match {
              case Some(old) =>
                val r = old.copy(version = VersionId(ids()), parent = Some(old.version), fields = fields, meta = meta, tags = tags, note = note, at = now())
                cell.set(v(Event.RecordRevised(r))); answer(Some(r))
              case None => answer(None)
            }
            case Store.Retire(id, reason, answer) => cell.set(v(Event.RecordRetired(id, reason, now()))); answer(())
            case Store.Audit(id, field, by, answer) => cell.set(v(Event.Accessed(id, field, by, now()))); answer(())
            case Store.Journaled(answer) => answer((v.events, v.unsaved))
            case Store.Saved(answer) => cell.set(v.saved); answer(())
          }
        }
      })
  }

  /** THE STORE ON `Data`: the same effect translated to the level below — collections `kinds`, `versions`, `retired`,
   * `events`, `marks` — so it runs on every backend of `Data`: memory, lines, SQL, a document store. A version carries
   * its `seq`, the order of writing, as does an event; the current version of an id is its greatest `seq` unless the
   * id is retired. Slower than the direct handlers where a query must be folded here (the current version of every
   * id), and checked against them */
  def onData(ids: () => String, now: () => Long): OnData = new OnData(ids, now)
  final class OnData(ids: () => String, now: () => Long) {
    val datas: Datas[fx.type] = new Datas[fx.type]()(fx)
    private def seqOf(d: Json): Long = Where.at(d, List("seq")).collect { case Json.Num(n) => n.toLong }.getOrElse(0L)
    private def withSeq(j: Json, seq: Long): Json = j match { case o: Json.Obj => ("seq" -> Json.num(seq)) +: o.without("seq"); case other => other }
    private def recordOf(d: Json): Record = Record.shape.decode(d).fold(ps => throw new IllegalStateException(s"a version does not read: ${okay.effects.shape.Problem.render(ps)}"), r => r)
    private def kindOf(d: Json): Kind = Kind.shape.decode(d).fold(ps => throw new IllegalStateException(s"a kind does not read: ${okay.effects.shape.Problem.render(ps)}"), k => k)
    private def eventOf(d: Json): Event = Event.shape.decode(d).fold(ps => throw new IllegalStateException(s"an event does not read: ${okay.effects.shape.Problem.render(ps)}"), e => e)
    private def key(seq: Long): String = f"$seq%012d"

    def apply[R[_], Rest[_], A](p: A ! R)(implicit sp: Split[Store, R, Rest], d: In[Data, Rest]): A ! Rest = {
      def note(e: Event): Unit ! Rest = datas.count[Rest]("events").flatMap(n => datas.put[Rest]("events", key(n + 1), withSeq(Event.shape.encode(e), n + 1)))
      def insert(r: Record): Unit ! Rest = datas.count[Rest]("versions").flatMap(n => datas.put[Rest]("versions", r.version.value, withSeq(Record.shape.encode(r), n + 1)))
      def retired: Set[RecordId] ! Rest = datas.query[Rest]("retired").map(_.map { case (k, _) => RecordId(k) }.toSet)
      def current(id: RecordId): Option[Record] ! Rest = retired.flatMap(gone =>
        if (gone(id)) pure[Option[Record], Rest](None)
        else datas.query[Rest]("versions", Where.Eq(List("id"), Json.Str(id.value)), List(Order.desc("seq")), Some(1)).map(_.headOption.map { case (_, d) => recordOf(d) }))
      handle[Store, R, Rest, A, A](p, new Handler[Store, Rest, A, A] {
        def ret(a: A) = pure(a)
        def op[X](op: Store[X], k: X => A ! Rest) = op match {
          case Store.Kinds(answer) => datas.query[Rest]("kinds").flatMap(ks => k(answer(ks.map { case (_, d) => kindOf(d) }.sortBy(_.name))))
          case Store.Find(text, tag, answer) =>
            // the versions that match, newest first; a hit counts when it is its id's current version
            datas.query[Rest]("versions", Stores.finding(text, tag), List(Order.desc("seq"))).flatMap { hits =>
              val newest = hits.map { case (_, d) => recordOf(d) }.foldLeft(List.empty[Record])((acc, r) => if (acc.exists(_.id == r.id)) acc else acc :+ r)
              newest.foldLeft(pure[List[Record], Rest](Nil))((acc, r) => acc.flatMap(rs => current(r.id).map(c => if (c.exists(_.version == r.version)) rs :+ r else rs)))
            }.flatMap(rs => k(answer(rs.sortBy(r => -r.at))))
          case Store.Get(id, answer) => current(id).flatMap(r => k(answer(r)))
          case Store.History(id, answer) => datas.query[Rest]("versions", Where.Eq(List("id"), Json.Str(id.value)), List(Order.desc("seq"))).flatMap(vs => k(answer(vs.map { case (_, d) => recordOf(d) })))
          case Store.Define(kind, answer) => datas.get[Rest]("kinds", kind.id.value).flatMap(known =>
            datas.put[Rest]("kinds", kind.id.value, Kind.shape.encode(kind)).flatMap(_ => note(if (known.isDefined) Event.KindRevised(kind, now()) else Event.KindDefined(kind, now()))).flatMap(_ => k(answer(()))))
          case Store.Add(kind, fields, meta, tags, n, answer) =>
            val r = Record(RecordId(ids()), VersionId(ids()), None, kind, fields, meta, tags, n, now())
            insert(r).flatMap(_ => note(Event.RecordAdded(r))).flatMap(_ => k(answer(r)))
          case Store.Revise(id, fields, meta, tags, n, answer) => current(id).flatMap {
            case Some(old) =>
              val r = old.copy(version = VersionId(ids()), parent = Some(old.version), fields = fields, meta = meta, tags = tags, note = n, at = now())
              insert(r).flatMap(_ => note(Event.RecordRevised(r))).flatMap(_ => k(answer(Some(r))))
            case None => k(answer(None))
          }
          case Store.Retire(id, reason, answer) =>
            val at = now()
            datas.put[Rest]("retired", id.value, Json.Obj(Vector("at" -> Json.num(at)) ++ reason.map(x => "reason" -> Json.Str(x)))).flatMap(_ => note(Event.RecordRetired(id, reason, at))).flatMap(_ => k(answer(())))
          case Store.Audit(id, field, by, answer) => note(Event.Accessed(id, field, by, now())).flatMap(_ => k(answer(())))
          case Store.Journaled(answer) => datas.query[Rest]("events", Where.All, List(Order.by("seq"))).flatMap(es => datas.get[Rest]("marks", "saved").flatMap { mark =>
            val marked = mark.map(seqOf).getOrElse(0L)
            k(answer((es.map { case (_, d) => eventOf(d) }, es.count { case (_, d) => seqOf(d) > marked })))
          })
          case Store.Saved(answer) => datas.count[Rest]("events").flatMap(n => datas.put[Rest]("marks", "saved", Json.obj("seq" -> Json.num(n)))).flatMap(_ => k(answer(())))
        }
      })
    }
  }

}

object Stores {
  def apply()(implicit fx: Effects): Stores[fx.type] = { implicit val exact: fx.type = fx; new Stores[fx.type] }

  /** WHAT `find` MEANS, on every handler: the text within the fields' values (not their names), the tags or the note,
   * ignoring case; the tag present. `searchText` is the same as one text, for a handler that keeps a column of it */
  def matches(text: String, tag: Option[String], r: Record): Boolean = {
    val t = text.trim.toLowerCase
    tag.forall(r.tags.contains) && (t.isEmpty || Where.leaves(r.fields).exists(_.toLowerCase.contains(t)) || r.tags.exists(_.toLowerCase.contains(t)) || r.note.exists(_.toLowerCase.contains(t)))
  }
  def searchText(r: Record): String = (Where.leaves(r.fields) ++ r.tags.toList.sorted ++ r.note.toList).mkString("\n").toLowerCase
  /** `find`'s condition over a version document, for a handler on `Data` */
  def finding(text: String, tag: Option[String]): Where = {
    val t = text.trim.toLowerCase
    Where.And(tag.map(g => Where.Has(List("tags"), Json.Str(g))).toList ++
      (if (t.isEmpty) Nil else List(Where.Or(List(Where.Contains(List("fields"), t), Where.Contains(List("tags"), t), Where.Contains(List("note"), t))))))
  }

  /** THE STORE DESCRIBED: every operation with its parameters' and result's shapes, for the faces — served as REST
   * resources or tools, performed from afar */
  private val byId = record(field("id", Record.id))(id => id)(id => id)
  private val written = record(field("kind", Kind.id), field("fields", Record.obj), field("meta", Record.obj).withDefault(Json.Obj.empty), field("tags", Record.tags).withDefault(Set.empty[String]), field("note", text.optional))(
    (k, f, m, t, n) => (k, f, m, t, n))(x => x)
  private val revised = record(field("id", Record.id), field("fields", Record.obj), field("meta", Record.obj).withDefault(Json.Obj.empty), field("tags", Record.tags).withDefault(Set.empty[String]), field("note", text.optional))(
    (i, f, m, t, n) => (i, f, m, t, n))(x => x)
  val kinds: Op[Store, Unit, List[Kind]] = Op("kinds", unit, Kind.shape.many(), (_: Unit) => Store.Kinds(x => x), Some("the kinds of record"))
  val find: Op[Store, (String, Option[String]), List[Record]] =
    Op("find", record(field("text", text).withDefault(""), field("tag", text.optional))((t, g) => (t, g))(x => x), Record.shape.many(), (a: (String, Option[String])) => Store.Find(a._1, a._2, x => x), Some("records by text in the fields, by tag"))
  val get: Op[Store, RecordId, Option[Record]] = Op("get", byId, Record.shape.optional, (id: RecordId) => Store.Get(id, x => x), Some("a record as it stands"))
  val history: Op[Store, RecordId, List[Record]] = Op("history", byId, Record.shape.many(), (id: RecordId) => Store.History(id, x => x), Some("a record's versions, newest first"))
  val define: Op[Store, Kind, Unit] = Op("define", Kind.shape, unit, (k: Kind) => Store.Define(k, x => x), Some("a kind defined or revised"), writes = true)
  val add: Op[Store, (KindId, Json.Obj, Json.Obj, Set[String], Option[String]), Record] =
    Op("add", written, Record.shape, (a: (KindId, Json.Obj, Json.Obj, Set[String], Option[String])) => Store.Add(a._1, a._2, a._3, a._4, a._5, x => x), Some("a record added"), writes = true)
  val revise: Op[Store, (RecordId, Json.Obj, Json.Obj, Set[String], Option[String]), Option[Record]] =
    Op("revise", revised, Record.shape.optional, (a: (RecordId, Json.Obj, Json.Obj, Set[String], Option[String])) => Store.Revise(a._1, a._2, a._3, a._4, a._5, x => x), Some("a new version"), writes = true)
  val retire: Op[Store, (RecordId, Option[String]), Unit] =
    Op("retire", record(field("id", Record.id), field("reason", text.optional))((i, r) => (i, r))(x => x), unit, (a: (RecordId, Option[String])) => Store.Retire(a._1, a._2, x => x), Some("a record retired"), writes = true)
  val audit: Op[Store, (RecordId, String, String), Unit] =
    Op("audit", record(field("id", Record.id), field("field", text), field("by", text))((i, f, b) => (i, f, b))(x => x), unit, (a: (RecordId, String, String)) => Store.Audit(a._1, a._2, a._3, x => x), Some("an access written down"), writes = true)
  val journaled: Op[Store, Unit, (List[Event], Int)] =
    Op("journaled", unit, record(field("events", Event.shape.many()), field("unsaved", int))((e, n) => (e, n))(x => x), (_: Unit) => Store.Journaled(x => x), Some("the journal, and how many events are unsaved"))
  val saved: Op[Store, Unit, Unit] = Op("saved", unit, unit, (_: Unit) => Store.Saved(x => x), Some("the journal marked saved"), writes = true)

  val describe: Describe[Store] = new Describe[Store]("store") {
    val operations = List(kinds, find, get, history, define, add, revise, retire, audit, journaled, saved)
    def split[X](op: Store[X]): Call[X] = op match {
      case Store.Kinds(k) => call(kinds, (), k)
      case Store.Find(t, g, k) => call(find, (t, g), k)
      case Store.Get(id, k) => call(get, id, k)
      case Store.History(id, k) => call(history, id, k)
      case Store.Define(kind, k) => call(define, kind, k)
      case Store.Add(kind, f, m, t, n, k) => call(add, (kind, f, m, t, n), k)
      case Store.Revise(id, f, m, t, n, k) => call(revise, (id, f, m, t, n), k)
      case Store.Retire(id, r, k) => call(retire, (id, r), k)
      case Store.Audit(id, f, b, k) => call(audit, (id, f, b), k)
      case Store.Journaled(k) => call(journaled, (), k)
      case Store.Saved(k) => call(saved, (), k)
    }
  }
}
