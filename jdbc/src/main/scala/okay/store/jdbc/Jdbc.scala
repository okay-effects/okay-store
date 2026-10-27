package okay.store.jdbc

import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet}
import okay.effects.Effects
import okay.effects.json.{Descent, Json}
import okay.store.{Event, EventId, Kind, Record, RecordId, Store, VersionId}

/**
 * THE STORE ON A DATABASE: the same effect, another handler. A record is a row of `versions` — its JSON by
 * `Record.shape`, beside it the columns a query needs (id, version, parent, kind, at, a lowercased `search` text and
 * the tags padded with spaces) — the latest row of an id its current version; `kinds` and `retired` tables; `events`
 * the journal, appended at every write, so the audit is in the database as it is in the file; `marks` where
 * `saved` last stood, so `journaled` counts the events not yet marked, as the handler in memory does. Each
 * operation is a statement or two, answered in place; the connection's own transaction mode holds.
 */
object Sqlite {
  /** a database at `path` (`":memory:"` for one that lives with the connection), its tables made, these kinds present */
  def open(path: String, kinds: List[Kind] = Nil): Connection = { val c = connect(path); Jdbc.prepare(c, kinds); c }
  /** a connection and nothing else: for `Data` on SQL (`Sqls.on`), whose tables are the collections' */
  def connect(path: String): Connection = {
    Class.forName("org.sqlite.JDBC")   // the driver by name: a service loader under another class loader (sbt's) may miss it
    DriverManager.getConnection(s"jdbc:sqlite:$path")
  }
}

object Jdbc {
  val schema: List[String] = List(
    "CREATE TABLE IF NOT EXISTS kinds (id TEXT PRIMARY KEY, name TEXT NOT NULL, json TEXT NOT NULL)",
    "CREATE TABLE IF NOT EXISTS versions (version TEXT PRIMARY KEY, id TEXT NOT NULL, parent TEXT, kind TEXT NOT NULL, at INTEGER NOT NULL, search TEXT NOT NULL, tags TEXT NOT NULL, json TEXT NOT NULL)",
    "CREATE INDEX IF NOT EXISTS versions_by_id ON versions (id)",
    "CREATE TABLE IF NOT EXISTS retired (id TEXT PRIMARY KEY, reason TEXT, at INTEGER NOT NULL)",
    "CREATE TABLE IF NOT EXISTS events (seq INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, json TEXT NOT NULL)",
    "CREATE TABLE IF NOT EXISTS marks (seq INTEGER NOT NULL)")

  /** the tables, and the kinds given where absent — built-in kinds, with no event, as `Kept.empty(kinds)` has them */
  def prepare(c: Connection, kinds: List[Kind]): Unit = {
    schema.foreach(sql => { val s = c.createStatement(); try s.execute(sql) finally s.close() })
    kinds.foreach(k => update(c, "INSERT OR IGNORE INTO kinds (id, name, json) VALUES (?, ?, ?)", k.id.value, k.name, Json.print(Kind.shape.encode(k))))
  }

  /** a value as JDBC takes it */
  def arg(a: okay.store.SqlArg): Any = a match {
    case okay.store.SqlArg.Text(s) => s
    case okay.store.SqlArg.Whole(n) => java.lang.Long.valueOf(n)
    case okay.store.SqlArg.Real(d) => java.lang.Double.valueOf(d)
    case okay.store.SqlArg.Null => None
  }
  /** a statement run, how many rows it changed */
  def run(c: Connection, s: okay.store.SqlStatement): Int = update(c, s.sql, s.args.map(arg): _*)
  /** a statement's rows */
  def rows[A](c: Connection, s: okay.store.SqlStatement)(row: ResultSet => A): List[A] = query(c, s.sql, s.args.map(arg): _*)(row)
  /** the sequences' table, made where absent; the next key of one, a table per type's too */
  def keysTable(c: Connection, dialect: okay.store.Dialect): Unit = { run(c, okay.store.SqlData.keys(dialect)); () }
  def fresh(c: Connection, dialect: okay.store.Dialect, name: String): String =
    okay.store.Data.key(rows(c, okay.store.SqlData.fresh(dialect, name))(_.getLong(1)).head)

  def update(c: Connection, sql: String, params: Any*): Int = { val s = bound(c, sql, params); try s.executeUpdate() finally s.close() }
  def query[A](c: Connection, sql: String, params: Any*)(row: ResultSet => A): List[A] = {
    val s = bound(c, sql, params)
    try { val rs = s.executeQuery(); val b = List.newBuilder[A]; while (rs.next()) b += row(rs); b.result() } finally s.close()
  }
  private def bound(c: Connection, sql: String, params: Seq[Any]): PreparedStatement = {
    val s = c.prepareStatement(sql)
    params.zipWithIndex.foreach {
      case (None, i) => s.setObject(i + 1, null)
      case (Some(x), i) => s.setObject(i + 1, x)
      case (x, i) => s.setObject(i + 1, x)
    }
    s
  }
  def record(json: String): Record = Descent.parse(json).left.map(_.message).flatMap(j => Record.shape.decode(j).left.map(okay.effects.shape.Problem.render)).fold(e => throw new IllegalStateException(s"a record in the database does not read: $e"), r => r)
  def kind(json: String): Kind = Descent.parse(json).left.map(_.message).flatMap(j => Kind.shape.decode(j).left.map(okay.effects.shape.Problem.render)).fold(e => throw new IllegalStateException(s"a kind in the database does not read: $e"), k => k)
  def event(json: String): Event = Descent.parse(json).left.map(_.message).flatMap(j => Event.shape.decode(j).left.map(okay.effects.shape.Problem.render)).fold(e => throw new IllegalStateException(s"an event in the database does not read: $e"), x => x)
  /** what `find` searches: `Stores.searchText`, the fields' values, the tags, the note, as every handler means it */
  def searchText(r: Record): String = okay.store.Stores.searchText(r)
  def tagsText(r: Record): String = r.tags.toList.sorted.mkString(" ", " ", " ")
}

class Jdbcs[F <: Effects with Singleton](implicit implementation: F) {
  val fx: F = implementation
  import fx._

  /** the handler over a connection; ids and the clock given, as the handler in memory takes them */
  def on(c: Connection, ids: () => String, now: () => Long): On = new On(c, ids, now, None)
  /** ATOMICALLY, THE DATABASE'S OWN: the store's operations in one transaction of the database — begun at the first,
   * committed when `p` ends; what ends `p` before leaves it open, and the next `atomically` on the connection (or its
   * closing) rolls it back, as `Sqls.atomically` for `Data`. A record added and an event noted are one write */
  def atomically(c: Connection, ids: () => String, now: () => Long): On = new On(c, ids, now, Some(new Transaction(c)))
  final class On(c: Connection, ids: () => String, now: () => Long, transaction: Option[Transaction]) {
    import Jdbc._
    private val currentSql = "SELECT v.json FROM versions v WHERE v.rowid = (SELECT MAX(rowid) FROM versions w WHERE w.id = v.id) AND v.id NOT IN (SELECT id FROM retired)"
    private def current(id: RecordId): Option[Record] = query(c, currentSql + " AND v.id = ?", id.value)(rs => record(rs.getString(1))).headOption
    private def note(e: Event): Unit = { update(c, "INSERT INTO events (at, json) VALUES (?, ?)", e.at, Json.print(Event.shape.encode(e))); () }
    private def eid(): EventId = EventId(ids())
    private def putKind(kind: Kind): Unit = {
      update(c, "INSERT OR REPLACE INTO kinds (id, name, json) VALUES (?, ?, ?)", kind.id.value, kind.name,
        Json.print(Kind.shape.encode(kind))); ()
    }
    private def retire(id: RecordId, reason: Option[String], at: Long): Unit = {
      update(c, "INSERT OR REPLACE INTO retired (id, reason, at) VALUES (?, ?, ?)", id.value, reason, at); ()
    }
    /** an event from elsewhere taken in, as its operation would have written it; a version already here stays */
    private def taken(e: Event): Unit = {
      e match {
        case Event.KindDefined(kind, _, _) => putKind(kind)
        case Event.KindRevised(kind, _, _) => putKind(kind)
        case Event.RecordAdded(r, _) => insert(r, "INSERT OR IGNORE")
        case Event.RecordRevised(r, _) => insert(r, "INSERT OR IGNORE")
        case Event.RecordRetired(id, reason, at, _) => retire(id, reason, at)
        case _: Event.Accessed => ()
      }
      note(e)
    }
    private def insert(r: Record, verb: String = "INSERT"): Unit = {
      update(c, verb + " INTO versions (version, id, parent, kind, at, search, tags, json)" +
        " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        r.version.value, r.id.value, r.parent.map(_.value), r.kind.value, r.at, searchText(r), tagsText(r), Json.print(Record.shape.encode(r))); ()
    }

    def apply[R[_], Rest[_], A](p: A ! R)(implicit sp: Split[Store, R, Rest]): A ! Rest =
      handle[Store, R, Rest, A, A](p, new TailHandler[Store, Rest, A, A] {
        def ret(a: A) = { transaction.foreach(_.commit()); pure(a) }
        def answer[X](op: Store[X]): X = { transaction.foreach(_.begin()); answered(op) }
      })
    private def answered[X](op: Store[X]): X = op match {
          case Store.Kinds(k) => k(query(c, "SELECT json FROM kinds ORDER BY name")(rs => kind(rs.getString(1))))
          case Store.Find(text, tag, k) =>
            val t = text.trim.toLowerCase
            val (sql, params) = (tag, t.nonEmpty) match {
              case (Some(g), true) => (currentSql + " AND instr(v.search, ?) > 0 AND instr(v.tags, ?) > 0", List(t, s" $g "))
              case (Some(g), false) => (currentSql + " AND instr(v.tags, ?) > 0", List(s" $g "))
              case (None, true) => (currentSql + " AND instr(v.search, ?) > 0", List(t))
              case (None, false) => (currentSql, Nil)
            }
            k(query(c, sql + " ORDER BY v.at DESC, v.rowid DESC", params: _*)(rs => record(rs.getString(1))))
          case Store.Get(id, k) => k(current(id))
          case Store.History(id, k) => k(query(c, "SELECT json FROM versions WHERE id = ? ORDER BY rowid DESC", id.value)(rs => record(rs.getString(1))))
          case Store.Define(kind, k) =>
            val known = query(c, "SELECT 1 FROM kinds WHERE id = ?", kind.id.value)(_ => ()).nonEmpty
            putKind(kind)
            val at = now()
            note(if (known) Event.KindRevised(kind, at, eid()) else Event.KindDefined(kind, at, eid())); k(())
          case Store.Add(kind, fields, meta, tags, n, k) =>
            val r = Record(RecordId(ids()), VersionId(ids()), None, kind, fields, meta, tags, n, now())
            insert(r); note(Event.RecordAdded(r, eid())); k(r)
          case Store.Revise(id, fields, meta, tags, n, k) => current(id) match {
            case Some(old) =>
              val r = old.copy(version = VersionId(ids()), parent = Some(old.version), fields = fields, meta = meta, tags = tags, note = n, at = now())
              insert(r); note(Event.RecordRevised(r, eid())); k(Some(r))
            case None => k(None)
          }
          case Store.Retire(id, reason, k) =>
            val at = now()
            retire(id, reason, at)
            note(Event.RecordRetired(id, reason, at, eid())); k(())
          case Store.Audit(id, field, by, k) => val at = now(); note(Event.Accessed(id, field, by, at, eid())); k(())
          case Store.Adopt(journal, k) =>
            val known = query(c, "SELECT json FROM events")(rs => Event.identity(event(rs.getString(1)))).toSet
            journal.distinctBy(Event.identity).filterNot(e => known(Event.identity(e))).foreach(taken); k(())
          case Store.Journaled(k) =>
            val events = query(c, "SELECT json FROM events ORDER BY seq")(rs => event(rs.getString(1)))
            val unsaved = query(c, "SELECT COUNT(*) FROM events WHERE seq > (SELECT COALESCE(MAX(seq), 0) FROM marks)")(_.getInt(1)).head
            k((events, unsaved))
          case Store.Saved(k) =>
            update(c, "DELETE FROM marks"); update(c, "INSERT INTO marks (seq) SELECT COALESCE(MAX(seq), 0) FROM events"); k(())
        }
  }
}

/** A CONNECTION'S TRANSACTION: begun at the first operation, committed at the end; one left open by a program that
 * ended early is rolled back by the next `begin` */
final class Transaction(c: Connection) {
  @volatile private var open = false
  def begin(): Unit = synchronized { if (!open) { if (!c.getAutoCommit) c.rollback() else c.setAutoCommit(false); open = true } }
  def commit(): Unit = synchronized { if (open) { c.commit(); c.setAutoCommit(true); open = false } }
  def rollback(): Unit = synchronized { if (open) { c.rollback(); c.setAutoCommit(true); open = false } }
}
object Jdbcs {
  def apply()(implicit fx: Effects): Jdbcs[fx.type] = { implicit val exact: fx.type = fx; new Jdbcs[fx.type] }
}
