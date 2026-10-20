package okay.store.jdbc

import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet}
import okay.effects.Effects
import okay.effects.json.{Descent, Json}
import okay.store.{Event, Kind, Record, RecordId, Store, VersionId}

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

  def update(c: Connection, sql: String, params: Any*): Int = { val s = bound(c, sql, params); try s.executeUpdate() finally s.close() }
  def query[A](c: Connection, sql: String, params: Any*)(row: ResultSet => A): List[A] = {
    val s = bound(c, sql, params)
    try { val rs = s.executeQuery(); val b = List.newBuilder[A]; while (rs.next()) b += row(rs); b.result() } finally s.close()
  }
  private def bound(c: Connection, sql: String, params: Seq[Any]): PreparedStatement = {
    val s = c.prepareStatement(sql)
    params.zipWithIndex.foreach {
      case (None, i) => s.setNull(i + 1, java.sql.Types.VARCHAR)
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

class Jdbcs[F <: Effects](implicit implementation: F) {
  val fx: F = implementation
  import fx._

  /** the handler over a connection; ids and the clock given, as the handler in memory takes them */
  def on(c: Connection, ids: () => String, now: () => Long): On = new On(c, ids, now)
  final class On(c: Connection, ids: () => String, now: () => Long) {
    import Jdbc._
    private val currentSql = "SELECT v.json FROM versions v WHERE v.rowid = (SELECT MAX(rowid) FROM versions w WHERE w.id = v.id) AND v.id NOT IN (SELECT id FROM retired)"
    private def current(id: RecordId): Option[Record] = query(c, currentSql + " AND v.id = ?", id.value)(rs => record(rs.getString(1))).headOption
    private def note(e: Event): Unit = { update(c, "INSERT INTO events (at, json) VALUES (?, ?)", e.at, Json.print(Event.shape.encode(e))); () }
    private def insert(r: Record): Unit = {
      update(c, "INSERT INTO versions (version, id, parent, kind, at, search, tags, json) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        r.version.value, r.id.value, r.parent.map(_.value), r.kind.value, r.at, searchText(r), tagsText(r), Json.print(Record.shape.encode(r))); ()
    }

    def apply[R[_], Rest[_], A](p: A ! R)(implicit sp: Split[Store, R, Rest]): A ! Rest =
      handle[Store, R, Rest, A, A](p, new TailHandler[Store, Rest, A, A] {
        def ret(a: A) = pure(a)
        def answer[X](op: Store[X]): X = op match {
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
            val json = Json.print(Kind.shape.encode(kind))
            val known = query(c, "SELECT 1 FROM kinds WHERE id = ?", kind.id.value)(_ => ()).nonEmpty
            update(c, "INSERT OR REPLACE INTO kinds (id, name, json) VALUES (?, ?, ?)", kind.id.value, kind.name, json)
            note(if (known) Event.KindRevised(kind, now()) else Event.KindDefined(kind, now())); k(())
          case Store.Add(kind, fields, meta, tags, n, k) =>
            val r = Record(RecordId(ids()), VersionId(ids()), None, kind, fields, meta, tags, n, now())
            insert(r); note(Event.RecordAdded(r)); k(r)
          case Store.Revise(id, fields, meta, tags, n, k) => current(id) match {
            case Some(old) =>
              val r = old.copy(version = VersionId(ids()), parent = Some(old.version), fields = fields, meta = meta, tags = tags, note = n, at = now())
              insert(r); note(Event.RecordRevised(r)); k(Some(r))
            case None => k(None)
          }
          case Store.Retire(id, reason, k) =>
            val at = now()
            update(c, "INSERT OR REPLACE INTO retired (id, reason, at) VALUES (?, ?, ?)", id.value, reason, at)
            note(Event.RecordRetired(id, reason, at)); k(())
          case Store.Audit(id, field, by, k) => note(Event.Accessed(id, field, by, now())); k(())
          case Store.Journaled(k) =>
            val events = query(c, "SELECT json FROM events ORDER BY seq")(rs => event(rs.getString(1)))
            val unsaved = query(c, "SELECT COUNT(*) FROM events WHERE seq > (SELECT COALESCE(MAX(seq), 0) FROM marks)")(_.getInt(1)).head
            k((events, unsaved))
          case Store.Saved(k) =>
            update(c, "DELETE FROM marks"); update(c, "INSERT INTO marks (seq) SELECT COALESCE(MAX(seq), 0) FROM events"); k(())
        }
      })
  }
}
object Jdbcs {
  def apply()(implicit fx: Effects): Jdbcs[fx.type] = { implicit val exact: fx.type = fx; new Jdbcs[fx.type] }
}
