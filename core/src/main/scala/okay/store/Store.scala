package okay.store

import okay.effects.Effects
import okay.effects.json.Json
import okay.effects.shape.Shape.{field, int, record, text, unit}
import okay.face.{Call, Describe, Op}

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
   * `find` is by text in the fields, the tags and the note, and by a tag */
  def keeping(initial: Kept, ids: () => String, now: () => Long): Keeping = new Keeping(initial, ids, now)
  final class Keeping(initial: Kept, ids: () => String, now: () => Long) {
    def apply[R[_], Rest[_], A](p: A ! R)(implicit sp: Split[Store, R, Rest]): (Kept, A) ! Rest =
      handle[Store, Kept, R, Rest, A, (Kept, A)](p, initial, new StatefulTailHandler[Store, Kept, Rest, A, (Kept, A)] {
        def ret(v: Kept, a: A) = pure((v, a))
        def answer[X](cell: Cell[Kept], op: Store[X]): X = {
          val v = cell.get
          op match {
            case Store.Kinds(answer) => answer(v.kinds.values.toList.sortBy(_.name))
            case Store.Find(text, tag, answer) =>
              val t = text.trim.toLowerCase
              answer(v.records.filter(r => tag.forall(r.tags.contains) && (t.isEmpty || Json.print(r.fields).toLowerCase.contains(t) || r.tags.exists(_.toLowerCase.contains(t)) || r.note.exists(_.toLowerCase.contains(t)))))
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
}

object Stores {
  def apply()(implicit fx: Effects): Stores[fx.type] = { implicit val exact: fx.type = fx; new Stores[fx.type] }

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
