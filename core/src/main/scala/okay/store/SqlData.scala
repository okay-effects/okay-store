package okay.store

import okay.effects.json.{Descent, Json}

/** a value a statement is given: SQL's own, no driver's — whoever runs the statement binds it its way (JDBC, D1,
 * a Postgres driver) */
sealed trait SqlArg
object SqlArg {
  final case class Text(s: String) extends SqlArg
  final case class Whole(n: Long) extends SqlArg
  final case class Real(d: Double) extends SqlArg
  case object Null extends SqlArg
}

/** a statement and its arguments, `?` each */
final case class SqlStatement(sql: String, args: List[SqlArg] = Nil)

/**
 * A DATABASE'S SQL for `Data` and a table per type: the JSON extraction, the text search within, the array
 * membership, the upsert, the sequences. Plain text — no connection here: the JVM's JDBC runs it (`okay-store-jdbc`),
 * and so does anything else that runs SQL (D1 and Postgres from a Worker or a Lambda, okay-serverless).
 */
final case class Dialect(name: String,
                         /** the value at a path within a JSON column, as an expression a `?` compares to — `cast` after the `?` */ extract: (String, List[String]) => String,
                         /** what a compared `?` is cast to: nothing for SQLite's scalars, `::jsonb` for Postgres's values */ cast: String,
                         /** a compared value as the database takes it: a scalar for SQLite, the JSON text for Postgres */ value: Json => SqlArg,
                         /** `Contains`: a condition with one `?`, the lowercased text, over the leaves at a path within a JSON column */ contains: (String, List[String]) => String,
                         /** the text within a text expression: one `?`, the lowercased text */ textContains: String => String,
                         /** `Has`: a condition with one `?` over the array at a path within a JSON column */ has: (String, List[String]) => String,
                         /** `Eq(path, Null)`: the value at the path within a JSON column is JSON's null — present, not missing */ isNull: (String, List[String]) => String,
                         /** a table made where absent, its columns given as `"name" TYPE` */ create: (String, List[String]) => String,
                         /** put or replace, the columns given, as many `?` */ upsert: (String, List[String]) => String,
                         /** the next of a collection's sequence, one `?` the collection, the number returned; the table `_keys (collection, next)` */ fresh: String => String,
                         textType: String = "TEXT", integerType: String = "INTEGER", realType: String = "REAL")
object Dialect {
  /** a JSON path, `$.a.b`, the root `$` */
  def jsonPath(path: List[String]): String = if (path.isEmpty) "$" else "$." + path.mkString(".")
  /** Postgres's text-array path, `{a,b}` */
  def pgPath(path: List[String]): String = "{" + path.map(p => "\"" + p.replace("\"", "\\\"") + "\"").mkString(",") + "}"
  /** Postgres's jsonpath, `$."a"."b"` */
  def pgJsonPath(path: List[String]): String = "$" + path.map(p => "." + "\"" + p.replace("\"", "\\\"") + "\"").mkString
  /** an identifier, quoted; a quote within is refused */
  def quoted(name: String): String = { require(!name.contains('"'), s"an identifier has no quote: $name"); "\"" + name + "\"" }
  /** a JSON value as SQLite compares it: a string, a whole number, a decimal, a boolean as 1/0, null */
  def bind(v: Json): SqlArg = v match {
    case Json.Str(s) => SqlArg.Text(s)
    case Json.Num(n) => if (n.isWhole && n.isValidLong) SqlArg.Whole(n.toLong) else SqlArg.Real(n.toDouble)
    case Json.Bool(b) => SqlArg.Whole(if (b) 1L else 0L)
    case Json.Null => SqlArg.Null
    case other => SqlArg.Text(Json.print(other))
  }
  /** SQLite with JSON1 (and D1, SQLite's): `json_extract`, `json_tree` for the leaves within, `json_each` for an
   * array's elements, `json_type` for JSON's null as distinct from missing. Checked */
  val sqlite: Dialect = Dialect("sqlite",
    extract = (col, path) => s"json_extract($col, '${jsonPath(path)}')",
    cast = "", value = bind,
    contains = (col, path) => s"EXISTS (SELECT 1 FROM json_tree($col, '${jsonPath(path)}') WHERE type NOT IN ('object', 'array') AND instr(lower(value), ?) > 0)",
    textContains = x => s"instr(lower($x), ?) > 0",
    has = (col, path) => s"EXISTS (SELECT 1 FROM json_each($col, '${jsonPath(path)}') WHERE value = ?)",
    isNull = (col, path) => s"json_type($col, '${jsonPath(path)}') = 'null'",
    create = (t, cols) => s"CREATE TABLE IF NOT EXISTS ${quoted(t)} (${cols.mkString(", ")})",
    upsert = (t, cols) => s"INSERT OR REPLACE INTO ${quoted(t)} (${cols.map(quoted).mkString(", ")}) VALUES (${cols.map(_ => "?").mkString(", ")})",
    fresh = t => s"INSERT INTO ${quoted(t)} (collection, next) VALUES (?, 1) ON CONFLICT (collection) DO UPDATE SET next = next + 1 RETURNING next")
  /** PostgreSQL: `jsonb` — a value compared as a value (`#>` and `?::jsonb`, so a number is a number and a string a
   * string, by jsonb's own order), the leaves within by `jsonb_path_query` with `.**`, an array's element by `@>`,
   * JSON's null by `jsonb_typeof`. Checked against a Postgres (`OKAY_PG`). Known and said: jsonb orders strings
   * before numbers where the reference orders numbers first — compare like with like */
  val postgres: Dialect = Dialect("postgres",
    extract = (col, path) => s"($col::jsonb #> '${pgPath(path)}')",
    cast = "::jsonb", value = v => SqlArg.Text(Json.print(v)),
    contains = (col, path) => s"EXISTS (SELECT 1 FROM jsonb_path_query($col::jsonb, '${pgJsonPath(path)}.**') v WHERE jsonb_typeof(v) NOT IN ('object', 'array') AND position(? in lower(v #>> '{}')) > 0)",
    textContains = x => s"position(? in lower($x)) > 0",
    has = (col, path) => s"(jsonb_typeof($col::jsonb #> '${pgPath(path)}') = 'array' AND ($col::jsonb #> '${pgPath(path)}') @> ?::jsonb)",
    isNull = (col, path) => s"jsonb_typeof($col::jsonb #> '${pgPath(path)}') = 'null'",
    create = (t, cols) => s"CREATE TABLE IF NOT EXISTS ${quoted(t)} (${cols.mkString(", ")})",
    upsert = (t, cols) => s"INSERT INTO ${quoted(t)} (${cols.map(quoted).mkString(", ")}) VALUES (${cols.map(_ => "?").mkString(", ")}) ON CONFLICT (${quoted(cols.head)}) DO UPDATE SET " +
      cols.tail.map(c => s"${quoted(c)} = EXCLUDED.${quoted(c)}").mkString(", "),
    fresh = t => s"INSERT INTO ${quoted(t)} (collection, next) VALUES (?, 1) ON CONFLICT (collection) DO UPDATE SET next = ${quoted(t)}.next + 1 RETURNING next",
    integerType = "BIGINT", realType = "DOUBLE PRECISION")
}

/**
 * `Data` AS SQL: a collection a table of `(key, doc)`, the document a JSON text; a `Where` a condition over the
 * dialect's JSON functions. Each operation's statements, for whoever runs them; what comes back is read by `doc`. The
 * tables are made by `table` and `keys` before use (`CREATE ... IF NOT EXISTS`, so once a connection is enough).
 */
object SqlData {
  /** two-valued: a condition that is NULL is false */
  def sure(condition: String): String = s"COALESCE(($condition), FALSE)"

  /** a collection's table */
  def tableOf(collection: String): String = Data.named(collection)
  /** the table of a collection, made where absent */
  def table(d: Dialect, collection: String): SqlStatement = SqlStatement(d.create(tableOf(collection),
    List(s"${Dialect.quoted("key")} ${d.textType} PRIMARY KEY", s"${Dialect.quoted("doc")} ${d.textType} NOT NULL")))
  /** the sequences' table, made where absent: shared by `Data` on SQL and a table per type */
  def keys(d: Dialect): SqlStatement = SqlStatement(d.create("_keys",
    List(s"${Dialect.quoted("collection")} ${d.textType} PRIMARY KEY", s"${Dialect.quoted("next")} ${d.integerType} NOT NULL")))

  /** a `Where` as a condition and its arguments, in order. A leaf's condition is `COALESCE(..., FALSE)`: a missing
   * value makes SQL's NULL, which `NOT` would lose, where the reference says a predicate on nothing is false */
  def condition(d: Dialect, w: Where): (String, List[SqlArg]) = w match {
    case Where.All => ("1 = 1", Nil)
    case Where.Eq(p, Json.Null) => (sure(d.isNull("doc", p)), Nil)
    case Where.Eq(p, v) => (sure(s"${d.extract("doc", p)} = ?${d.cast}"), List(d.value(v)))
    case Where.Contains(p, t) => (sure(d.contains("doc", p)), List(SqlArg.Text(t.toLowerCase)))
    case Where.Has(p, v) => (sure(d.has("doc", p)), List(d.value(v)))
    case Where.Less(p, v) => (sure(s"${d.extract("doc", p)} < ?${d.cast}"), List(d.value(v)))
    case Where.Greater(p, v) => (sure(s"${d.extract("doc", p)} > ?${d.cast}"), List(d.value(v)))
    case Where.And(Nil) => ("1 = 1", Nil)
    case Where.And(all) => val cs = all.map(condition(d, _)); (cs.map(x => s"(${x._1})").mkString(" AND "), cs.flatMap(_._2))
    case Where.Or(Nil) => ("1 = 0", Nil)
    case Where.Or(any) => val cs = any.map(condition(d, _)); (cs.map(x => s"(${x._1})").mkString(" OR "), cs.flatMap(_._2))
    case Where.Not(x) => val (sql, ps) = condition(d, x); (s"NOT ($sql)", ps)
  }
  def ordering(d: Dialect, order: List[Order]): String =
    if (order.isEmpty) "" else " ORDER BY " + order.map(o => d.extract("doc", o.path) + (if (o.descending) " DESC" else " ASC")).mkString(", ")

  /** a document's text, as read back */
  def doc(text: String): Json = Descent.parse(text).fold(e => throw new IllegalStateException(s"a document in the database does not parse: ${e.message}"), j => j)

  /** `Get`: one row, `doc` */
  def get(collection: String, key: String): SqlStatement =
    SqlStatement(s"SELECT doc FROM ${Dialect.quoted(tableOf(collection))} WHERE key = ?", List(SqlArg.Text(key)))
  def put(d: Dialect, collection: String, key: String, doc: Json): SqlStatement =
    SqlStatement(d.upsert(tableOf(collection), List("key", "doc")), List(SqlArg.Text(key), SqlArg.Text(Json.print(doc))))
  def delete(collection: String, key: String): SqlStatement =
    SqlStatement(s"DELETE FROM ${Dialect.quoted(tableOf(collection))} WHERE key = ?", List(SqlArg.Text(key)))
  /** `Query`: rows of `key, doc` */
  def query(d: Dialect, collection: String, where: Where, order: List[Order], limit: Option[Int]): SqlStatement = {
    val (cond, args) = condition(d, where)
    SqlStatement(s"SELECT key, doc FROM ${Dialect.quoted(tableOf(collection))} WHERE $cond" + ordering(d, order) +
      limit.fold("")(n => s" LIMIT $n"), args)
  }
  /** `Count`: one row, the count */
  def count(d: Dialect, collection: String, where: Where): SqlStatement = {
    val (cond, args) = condition(d, where)
    SqlStatement(s"SELECT COUNT(*) FROM ${Dialect.quoted(tableOf(collection))} WHERE $cond", args)
  }
  /** `Fresh`: one row, the number; `Data.key` of it is the key. A table per type passes its table's name */
  def fresh(d: Dialect, name: String): SqlStatement = SqlStatement(d.fresh("_keys"), List(SqlArg.Text(name)))
}
