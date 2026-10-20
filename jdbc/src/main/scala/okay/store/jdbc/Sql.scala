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
                         /** the value at a path, as an expression over the column `doc` */ extract: List[String] => String,
                         /** `Contains`: a condition with one `?`, the lowercased text, over the leaves under a path */ contains: List[String] => String,
                         /** `Has`: a condition with one `?` over the array at a path */ has: List[String] => String,
                         /** `Eq(path, Null)`: the value at the path is JSON's null — present, not missing */ isNull: List[String] => String,
                         /** a table made where absent */ create: String => String,
                         /** put or replace, `(?, ?)` the key and the document */ upsert: String => String)
object Dialect {
  /** a JSON path, `$.a.b`, the root `$` */
  def jsonPath(path: List[String]): String = if (path.isEmpty) "$" else "$." + path.mkString(".")
  /** SQLite with JSON1: `json_extract`, `json_tree` for the leaves within, `json_each` for an array's elements */
  val sqlite: Dialect = Dialect("sqlite",
    extract = path => s"json_extract(doc, '${Dialect.jsonPath(path)}')",
    contains = path => s"EXISTS (SELECT 1 FROM json_tree(doc, '${Dialect.jsonPath(path)}') WHERE type NOT IN ('object', 'array') AND instr(lower(value), ?) > 0)",
    has = path => s"EXISTS (SELECT 1 FROM json_each(doc, '${Dialect.jsonPath(path)}') WHERE value = ?)",
    isNull = path => s"json_type(doc, '${Dialect.jsonPath(path)}') = 'null'",
    create = t => s"""CREATE TABLE IF NOT EXISTS "$t" (key TEXT PRIMARY KEY, doc TEXT NOT NULL)""",
    upsert = t => s"""INSERT OR REPLACE INTO "$t" (key, doc) VALUES (?, ?)""")
  /** PostgreSQL, a sketch, UNCHECKED: `jsonb` paths; `Contains` over the subtree's text, so a key's name matches too,
   * unlike the reference — a `jsonb_path` form would mend it */
  val postgres: Dialect = Dialect("postgres",
    extract = path => s"(doc::jsonb #>> '{${path.mkString(",")}}')",
    contains = path => s"position(? in lower(doc::jsonb #>> '{${path.mkString(",")}}')) > 0",
    has = path => s"((doc::jsonb #> '{${path.mkString(",")}}') @> to_jsonb(?))",
    isNull = path => s"(doc::jsonb #> '{${path.mkString(",")}}') = 'null'::jsonb",
    create = t => s"""CREATE TABLE IF NOT EXISTS "$t" (key TEXT PRIMARY KEY, doc TEXT NOT NULL)""",
    upsert = t => s"""INSERT INTO "$t" (key, doc) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET doc = EXCLUDED.doc""")
}

class Sqls[F <: Effects](implicit implementation: F) {
  val fx: F = implementation
  import fx._

  def on(c: Connection, dialect: Dialect = Dialect.sqlite): On = new On(c, dialect)
  final class On(c: Connection, dialect: Dialect) {
    import Jdbc.{query => rows, update}
    private val made = scala.collection.mutable.Set.empty[String]
    private def table(collection: String): String = {
      val t = Data.named(collection)
      if (!made(t)) { update(c, dialect.create(t)); made += t }
      t
    }
    /** a JSON value as the database compares it: a string, a whole number, a decimal, a boolean as 1/0 */
    private def bind(v: Json): Any = v match {
      case Json.Str(s) => s
      case Json.Num(n) => if (n.isWhole && n.isValidLong) java.lang.Long.valueOf(n.toLong) else java.lang.Double.valueOf(n.toDouble)
      case Json.Bool(b) => java.lang.Integer.valueOf(if (b) 1 else 0)
      case other => Json.print(other)
    }
    /** a `Where` as a condition and its parameters, in order */
    def condition(w: Where): (String, List[Any]) = w match {
      case Where.All => ("1 = 1", Nil)
      case Where.Eq(p, Json.Null) => (dialect.isNull(p), Nil)
      case Where.Eq(p, v) => (s"${dialect.extract(p)} = ?", List(bind(v)))
      case Where.Contains(p, t) => (dialect.contains(p), List(t.toLowerCase))
      case Where.Has(p, v) => (dialect.has(p), List(bind(v)))
      case Where.Less(p, v) => (s"${dialect.extract(p)} < ?", List(bind(v)))
      case Where.Greater(p, v) => (s"${dialect.extract(p)} > ?", List(bind(v)))
      case Where.And(Nil) => ("1 = 1", Nil)
      case Where.And(all) => val cs = all.map(condition); (cs.map(x => s"(${x._1})").mkString(" AND "), cs.flatMap(_._2))
      case Where.Or(Nil) => ("1 = 0", Nil)
      case Where.Or(any) => val cs = any.map(condition); (cs.map(x => s"(${x._1})").mkString(" OR "), cs.flatMap(_._2))
      case Where.Not(x) => val (sql, ps) = condition(x); (s"NOT ($sql)", ps)
    }
    private def ordering(order: List[Order]): String =
      if (order.isEmpty) "" else " ORDER BY " + order.map(o => dialect.extract(o.path) + (if (o.descending) " DESC" else " ASC")).mkString(", ")
    private def doc(text: String): Json = Descent.parse(text).fold(e => throw new IllegalStateException(s"a document in the database does not parse: ${e.message}"), j => j)

    def apply[R[_], Rest[_], A](p: A ! R)(implicit sp: Split[Data, R, Rest]): A ! Rest =
      handle[Data, R, Rest, A, A](p, new TailHandler[Data, Rest, A, A] {
        def ret(a: A) = pure(a)
        def answer[X](op: Data[X]): X = op match {
          case Data.Get(col, key, k) => k(rows(c, s"""SELECT doc FROM "${table(col)}" WHERE key = ?""", key)(rs => doc(rs.getString(1))).headOption)
          case Data.Put(col, key, d, k) => update(c, dialect.upsert(table(col)), key, Json.print(d)); k(())
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
