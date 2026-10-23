package okay.store

import okay.effects.json.Json
import okay.effects.shape.{Problem, Shape}
import okay.effects.shape.Shape.{field, int, long, record, sub, sum, text}

/** an event's identity: minted by the handler from its `ids`; `none` for an event made without one */
final case class EventId(value: String)
object EventId {
  val none: EventId = EventId("")
  val shape: Shape[EventId] = text.map(EventId(_))(_.value)
}

/** WHAT HAPPENED, in order: the journal is this, a line a JSON object per event, appended and never rewritten. An
 * access written down (`Accessed`: who saw which field) is part of it, so the audit is the data's own. Each event has
 * `eid`, its identity, so a writer appends only what a file lacks */
sealed trait Event { def at: Long; def eid: EventId }
object Event {
  final case class KindDefined(kind: Kind, at: Long, eid: EventId = EventId.none) extends Event
  final case class KindRevised(kind: Kind, at: Long, eid: EventId = EventId.none) extends Event
  final case class RecordAdded(record: Record, eid: EventId = EventId.none) extends Event { def at: Long = record.at }
  final case class RecordRevised(record: Record, eid: EventId = EventId.none) extends Event { def at: Long = record.at }
  final case class RecordRetired(id: RecordId, reason: Option[String], at: Long, eid: EventId = EventId.none)
    extends Event
  final case class Accessed(id: RecordId, field: String, by: String, at: Long, eid: EventId = EventId.none)
    extends Event

  /** WHO AN EVENT IS: its `eid`, or — an event without one, as a journal written before ids has them — a hash of what
   * it says, so the same line read twice is the same event */
  def identity(e: Event): EventId =
    if (e.eid != EventId.none) e.eid else EventId("h" + fnv(Json.print(shape.encode(e))))
  /** FNV-1a, 64 bits, as hex: the same on every platform */
  private def fnv(s: String): String = {
    var h = 0xcbf29ce484222325L
    s.foreach { c => h = (h ^ (c & 0xffff).toLong) * 0x100000001b3L }
    val hex = java.lang.Long.toHexString(h)
    ("0" * (16 - hex.length)) + hex
  }

  private val eid = field("eid", EventId.shape).withDefault(EventId.none)
  val shape: Shape[Event] = sum(
    sub[Event, KindDefined]("kind-defined", record(eid, field("kind", Kind.shape), field("at", long))(
      (i, k, at) => KindDefined(k, at, i))(e => (e.eid, e.kind, e.at))) {
      case e: KindDefined => Some(e); case _ => None },
    sub[Event, KindRevised]("kind-revised", record(eid, field("kind", Kind.shape), field("at", long))(
      (i, k, at) => KindRevised(k, at, i))(e => (e.eid, e.kind, e.at))) {
      case e: KindRevised => Some(e); case _ => None },
    sub[Event, RecordAdded]("record-added", record(eid, field("record", Record.shape))(
      (i, r) => RecordAdded(r, i))(e => (e.eid, e.record))) { case e: RecordAdded => Some(e); case _ => None },
    sub[Event, RecordRevised]("record-revised", record(eid, field("record", Record.shape))(
      (i, r) => RecordRevised(r, i))(e => (e.eid, e.record))) { case e: RecordRevised => Some(e); case _ => None },
    sub[Event, RecordRetired]("record-retired", record(eid, field("id", Record.id), field("reason", text.optional),
      field("at", long))((i, id, r, at) => RecordRetired(id, r, at, i))(e => (e.eid, e.id, e.reason, e.at))) {
      case e: RecordRetired => Some(e); case _ => None },
    sub[Event, Accessed]("accessed", record(eid, field("id", Record.id), field("field", text), field("by", text),
      field("at", long))((i, id, f, b, at) => Accessed(id, f, b, at, i))(e => (e.eid, e.id, e.field, e.by, e.at))) {
      case e: Accessed => Some(e); case _ => None })
}

/** THE FILE: a header line — the application's format name and version, when it was created — then an event a line.
 * Plain; the header is made for an encryption wrapper later */
object Journal {
  final case class Header(format: String, version: Int, created: Long)
  val header: Shape[Header] = record(field("format", text), field("version", int), field("created", long))(Header(_, _, _))(h => (h.format, h.version, h.created))
  final case class Damaged(line: Int, problems: List[Problem])

  /** the lines of a file: the header checked, every event decoded, a damaged line reported by number and skipped */
  def read(lines: List[String]): (Option[Header], List[Event], List[Damaged]) = {
    val numbered = lines.zipWithIndex.filter(_._1.trim.nonEmpty)
    numbered match {
      case Nil => (None, Nil, Nil)
      case (first, _) :: rest =>
        val h = okay.effects.json.parse(first).left.map(e => List(Problem(Nil, e.message))).flatMap(header.decode)
        val events = rest.map { case (l, i) =>
          okay.effects.json.parse(l).left.map(e => List(Problem(Nil, e.message))).flatMap(Event.shape.decode).left.map(Damaged(i + 1, _))
        }
        (h.toOption, events.collect { case Right(e) => e }, h.left.toOption.map(Damaged(1, _)).toList ++ events.collect { case Left(d) => d })
    }
  }
  def write(h: Header, events: List[Event]): List[String] = Json.print(header.encode(h)) :: events.map(e => Json.print(Event.shape.encode(e)))
}

/** WHAT IS KEPT: the journal folded — the kinds, every record's versions newest first, the retired, the events, how
 * many of them are unsaved */
final case class Kept(kinds: Map[KindId, Kind], versions: Map[RecordId, List[Record]], retired: Set[RecordId], events: List[Event], unsaved: Int) {
  def kind(id: KindId): Option[Kind] = kinds.get(id)
  def current(id: RecordId): Option[Record] = if (retired(id)) None else versions.get(id).flatMap(_.headOption)
  /** the records alive, newest first */
  def records: List[Record] = versions.toList.collect { case (id, v :: _) if !retired(id) => v }.sortBy(-_.at)
  def history(id: RecordId): List[Record] = versions.getOrElse(id, Nil)
  def apply(e: Event): Kept = {
    val next = e match {
      case Event.KindDefined(k, _, _) => copy(kinds = kinds.updated(k.id, k))
      case Event.KindRevised(k, _, _) => copy(kinds = kinds.updated(k.id, k))
      case Event.RecordAdded(r, _) => copy(versions = versions.updated(r.id, List(r)))
      case Event.RecordRevised(r, _) => copy(versions = versions.updated(r.id, r :: versions.getOrElse(r.id, Nil)))
      case Event.RecordRetired(id, _, _, _) => copy(retired = retired + id)
      case _: Event.Accessed => this
    }
    next.copy(events = events :+ e, unsaved = unsaved + 1)
  }
  def saved: Kept = copy(unsaved = 0)
  /** a journal as written — by this store and by any other writer of the same file — taken in: its fold, an event once
   * by identity, then this one's own events it lacks, those unsaved */
  def adopt(journal: List[Event]): Kept = {
    val once = journal.distinctBy(Event.identity)
    val known = once.map(Event.identity).toSet
    events.filterNot(e => known(Event.identity(e))).foldLeft(Kept.of(once, kinds.values.toList))(_ apply _)
  }
}
object Kept {
  /** nothing kept but these kinds, built in */
  def empty(kinds: List[Kind] = Nil): Kept = Kept(kinds.map(k => k.id -> k).toMap, Map.empty, Set.empty, Nil, 0)
  def of(events: List[Event], kinds: List[Kind] = Nil): Kept = events.foldLeft(empty(kinds))(_ apply _).saved
}
