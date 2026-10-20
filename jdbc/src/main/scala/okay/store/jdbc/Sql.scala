package okay.store.jdbc

import java.sql.Connection
import okay.effects.Effects
import okay.effects.json.{Descent, Json}
import okay.store.{Data, Order, Where}

/**
 * `Data` ON SQL: a collection a table of `(key, doc)`, the document a JSON text, made on first use; a `Where` compiled
 * to a condition over the database's JSON functions, by a `Dialect`. Every domain translated to `Data` runs here
 * unchanged; a database is a dialect — the JSON extraction, the text search within, the array membership, the upsert.
 */
final case class Dialect(name: String,
                         /** the value at a path within a JSON column, as an expression */ extract: (String, List[String]) => String,
                         /** `Contains`: a condition with one `?`, the lowercased text, over the leaves at a path within a JSON column */ contains: (String, List[String]) => String,
                         /** `Has`: a condition with one `?` over the array at a path within a JSON column */ has: (String, List[String]) => String,
                         /** `Eq(path, Null)`: the value at the path within a JSON column is JSON's null — present, not missing */ isNull: (String, List[String]) => String,
                         /** a table made where absent, its columns given as `"name" TYPE` */ create: (String, List[String]) => String,
                         /** put or replace, the columns given, as many `?` */ upsert: (String, List[String]) => String,
                         /** the next of a collection's sequence, one `?` the collection, the number returned; the table `_keys (collection, next)` */ fresh: String => String,
                         textType: String = "TEXT", integerType: String = "INTEGER", realType: String = "REAL")
object Dialect {
  /** a JSON path, `$.a.b`, the root `$` */
  def jsonPath(path: List[String]): String = if (path.isEmpty) "$" else "$." + path.mkString(".")
  /** an identifier, quoted; a quote within is refused */
  def quoted(name: String): String = { require(!name.contains('"'), s"an identifier has no quote: $name"); "\"" + name + "\"" }
  /** a JSON value as the database compares it: a string, a whole number, a decimal, a boolean as 1/0, null */
  def bind(v: Json): Any = v match {
    case Json.Str(s) => s
    case Json.Num(n) => if (n.isWhole && n.isValidLong) java.lang.Long.valueOf(n.toLong) else java.lang.Double.valueOf(n.toDouble)
    case Json.Bool(b) => java.lang.Integer.valueOf(if (b) 1 else 0)
    case Json.Null => None
    case other => Json.print(other)
  }
  /** SQLite with JSON1: `json_extract`, `json_tree` for the leaves within, `json_each` for an array's elements,
   * `json_type` for JSON's null as distinct from missing */
  val sqlite: Dialect = Dialect("sqlite",
    extract = (col, path) => s"json_extract($col, '${jsonPath(path)}')",
    contains = (col, path) => s"EXISTS (SELECT 1 FROM json_tree($col, '${jsonPath(path)}') WHERE type NOT IN ('object', 'array') AND instr(lower(value), ?) > 0)",
    has = (col, path) => s"EXISTS (SELECT 1 FROM json_each($col, '${jsonPath(path)}') WHERE value = ?)",
    isNull = (col, path) => s"json_type($col, '${jsonPath(path)}') = 'null'",
    create = (t, cols) => s"CREATE TABLE IF NOT EXISTS ${quoted(t)} (${cols.mkString(", ")})",
    upsert = (t, cols) => s"INSERT OR REPLACE INTO ${quoted(t)} (${cols.map(quoted).mkString(", ")}) VALUES (${cols.map(_ => "?").mkString(", ")})",
    fresh = t => s"INSERT INTO ${quoted(t)} (collection, next) VALUES (?, 1) ON CONFLICT (collection) DO UPDATE SET next = next + 1 RETURNING next")
  /** PostgreSQL, a sketch, UNCHECKED: `jsonb` paths; `Contains` over the subtree's text, so a key's name matches too,
   * unlike the reference — a `jsonb_path` form would mend it */
  val postgres: Dialect = Dialect("postgres",
    extract = (col, path) => s"($col::jsonb #>> '{${path.mkString(",")}}')",
    contains = (col, path) => s"position(? in lower($col::jsonb #>> '{${path.mkString(",")}}')) > 0",
    has = (col, path) => s"(($col::jsonb #> '{${path.mkString(",")}}') @> to_jsonb(?))",
    isNull = (col, path) => s"($col::jsonb #> '{${path.mkString(",")}}') = 'null'::jsonb",
    create = (t, cols) => s"CREATE TABLE IF NOT EXISTS ${quoted(t)} (${cols.mkString(", ")})",
    upsert = (t, cols) => s"INSERT INTO ${quoted(t)} (${cols.map(quoted).mkString(", ")}) VALUES (${cols.map(_ => "?").mkString(", ")}) ON CONFLICT (${quoted(cols.head)}) DO UPDATE SET " +
      cols.tail.map(c => s"${quoted(c)} = EXCLUDED.${quoted(c)}").mkString(", "),
    fresh = t => s"INSERT INTO ${quoted(t)} (collection, next) VALUES (?, 1) ON CONFLICT (collection) DO UPDATE SET next = ${quoted(t)}.next + 1 RETURNING next",
    integerType = "BIGINT", realType = "DOUBLE PRECISION")
  /** the sequences' table and the next of one: shared by `Data` on SQL and a table per type */
  def keysTable(c: java.sql.Connection, dialect: Dialect): Unit = {
    Jdbc.update(c, dialect.create("_keys", List(s"${quoted("collection")} ${dialect.textType} PRIMARY KEY", s"${quoted("next")} ${dialect.integerType} NOT NULL"))); ()
  }
  def fresh(c: java.sql.Connection, dialect: Dialect, collection: String): String =
    Data.key(Jdbc.query(c, dialect.fresh("_keys"), collection)(_.getLong(1)).head)
}

class Sqls[F <: Effects](implicit implementation: F) {
  val fx: F = implementation
  import fx._

  def on(c: Connection, dialect: Dialect = Dialect.sqlite): On = new On(c, dialect)
  final class On(c: Connection, dialect: Dialect) {
    import Jdbc.{query => rows, update}
    private val made = scala.collection.mutable.Set.empty[String]
    private lazy val keys: Unit = Dialect.keysTable(c, dialect)
    private def table(collection: String): String = {
      val t = Data.named(collection)
      if (!made(t)) { update(c, dialect.create(t, List(s"${Dialect.quoted("key")} ${dialect.textType} PRIMARY KEY", s"${Dialect.quoted("doc")} ${dialect.textType} NOT NULL"))); made += t }
      t
    }
    import Dialect.bind
    /** a `Where` as a condition and its parameters, in order */
    def condition(w: Where): (String, List[Any]) = w match {
      case Where.All => ("1 = 1", Nil)
      case Where.Eq(p, Json.Null) => (dialect.isNull("doc", p), Nil)
      case Where.Eq(p, v) => (s"${dialect.extract("doc", p)} = ?", List(bind(v)))
      case Where.Contains(p, t) => (dialect.contains("doc", p), List(t.toLowerCase))
      case Where.Has(p, v) => (dialect.has("doc", p), List(bind(v)))
      case Where.Less(p, v) => (s"${dialect.extract("doc", p)} < ?", List(bind(v)))
      case Where.Greater(p, v) => (s"${dialect.extract("doc", p)} > ?", List(bind(v)))
      case Where.And(Nil) => ("1 = 1", Nil)
      case Where.And(all) => val cs = all.map(condition); (cs.map(x => s"(${x._1})").mkString(" AND "), cs.flatMap(_._2))
      case Where.Or(Nil) => ("1 = 0", Nil)
      case Where.Or(any) => val cs = any.map(condition); (cs.map(x => s"(${x._1})").mkString(" OR "), cs.flatMap(_._2))
      case Where.Not(x) => val (sql, ps) = condition(x); (s"NOT ($sql)", ps)
    }
    private def ordering(order: List[Order]): String =
      if (order.isEmpty) "" else " ORDER BY " + order.map(o => dialect.extract("doc", o.path) + (if (o.descending) " DESC" else " ASC")).mkString(", ")
    private def doc(text: String): Json = Descent.parse(text).fold(e => throw new IllegalStateException(s"a document in the database does not parse: ${e.message}"), j => j)

    def apply[R[_], Rest[_], A](p: A ! R)(implicit sp: Split[Data, R, Rest]): A ! Rest =
      handle[Data, R, Rest, A, A](p, new TailHandler[Data, Rest, A, A] {
        def ret(a: A) = pure(a)
        def answer[X](op: Data[X]): X = op match {
          case Data.Fresh(col, k) => keys; k(Dialect.fresh(c, dialect, Data.named(col)))
          case Data.Get(col, key, k) => k(rows(c, s"""SELECT doc FROM "${table(col)}" WHERE key = ?""", key)(rs => doc(rs.getString(1))).headOption)
          case Data.Put(col, key, d, k) => update(c, dialect.upsert(table(col), List("key", "doc")), key, Json.print(d)); k(())
          case Data.Delete(col, key, k) => update(c, s"""DELETE FROM "${table(col)}" WHERE key = ?""", key); k(())
          case Data.Query(col, w, o, l, k) =>
            val (cond, params) = condition(w)
            val sql = s"""SELECT key, doc FROM "${table(col)}" WHERE $cond""" + ordering(o) + l.fold("")(n => s" LIMIT $n")
            k(rows(c, sql, params: _*)(rs => (rs.getString(1), doc(rs.getString(2)))))
          case Data.Count(col, w, k) =>
            val (cond, params) = condition(w)
            k(rows(c, s"""SELECT COUNT(*) FROM "${table(col)}" WHERE $cond""", params: _*)(_.getLong(1)).head)
        }
      })
  }
}
object Sqls {
  def apply()(implicit fx: Effects): Sqls[fx.type] = { implicit val exact: fx.type = fx; new Sqls[fx.type] }
}
