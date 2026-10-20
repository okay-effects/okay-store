package okay.store.jdbc

import java.sql.{Connection, ResultSet}
import okay.effects.Effects
import okay.effects.json.{Descent, Json}
import okay.effects.shape.Schema
import okay.store.{Mapped, Mapping, Order, Where}

/**
 * A TABLE PER TYPE: a `Mapping[A]` straight on SQL, no `Data` between — the shape's fields are the columns, a leaf
 * (text, number, boolean, date, choice) a column of its type, anything nested (a record, a list, a sum, any) a JSON
 * column; `_key` the primary key. A save is an upsert of the value by its columns, a find a `WHERE` on them — a
 * leaf compared as a column, a path into a nested column by the dialect's JSON functions. The table reads with the
 * database's own tools, as an ORM's would. The same effect, the same answers as `Mappings.onData` on any backend.
 */
class Tables[F <: Effects](implicit implementation: F) {
  val fx: F = implementation
  import fx._

  def on[A](m: Mapping[A], c: Connection, dialect: Dialect = Dialect.sqlite): On[A] = new On[A](m, c, dialect)
  final class On[A](m: Mapping[A], c: Connection, dialect: Dialect) {
    import Jdbc.{query => rows, update}
    import Dialect.{bind, quoted}
    type E[X] = Mapped[A, X]

    /** a column: its field's name, the leaf's schema or none for a nested value kept as JSON */
    final case class Column(name: String, leaf: Option[Schema]) {
      def sql: String = quoted(name)
      def sqlType: String = leaf match {
        case Some(Schema.Integer(_)) | Some(Schema.Bool) => dialect.integerType
        case Some(Schema.Number(_)) => dialect.realType
        case _ => dialect.textType
      }
    }
    private def stripped(s: Schema): Schema = s match { case Schema.Described(of, _, _) => stripped(of); case Schema.Optional(of) => stripped(of); case other => other }
    private def leafOf(s: Schema): Option[Schema] = stripped(s) match {
      case l @ (Schema.Text(_) | Schema.Integer(_) | Schema.Number(_) | Schema.Bool | Schema.Date | Schema.Choice(_)) => Some(l)
      case _ => None
    }
    val columns: List[Column] = stripped(m.shape.erased) match {
      case Schema.Record(fields) => fields.toList.map(f => Column(f.name, leafOf(f.schema)))
      case other => throw new IllegalArgumentException(s"a table per type takes a record, not ${other.getClass.getSimpleName}")
    }
    private val byName: Map[String, Column] = columns.map(c => c.name -> c).toMap
    val table: String = m.collection
    private lazy val made: Unit = { update(c, dialect.create(table, s"${quoted("_key")} ${dialect.textType} PRIMARY KEY" :: columns.map(col => s"${col.sql} ${col.sqlType}"))); () }

    /** the row of a value: its key, then each column's value as the database takes it */
    private def row(a: A): List[Any] = {
      val doc = m.shape.encode(a) match { case o: Json.Obj => o; case other => throw new IllegalStateException(s"$table: a value encodes to ${Json.kind(other)}, not a record") }
      m.key(a) :: columns.map(col => doc.get(col.name) match {
        case None | Some(Json.Null) => None
        case Some(v) => if (col.leaf.isDefined) bind(v) else Json.print(v)
      })
    }
    /** the value of a row: each column back to JSON by its schema, then the shape */
    private def value(key: String, rs: ResultSet): A = {
      val fields = columns.zipWithIndex.flatMap { case (col, i) =>
        val raw = rs.getObject(i + 2)
        if (raw == null) None
        else Some(col.name -> (col.leaf match {
          case Some(Schema.Integer(_)) | Some(Schema.Number(_)) => Json.Num(BigDecimal(rs.getString(i + 2)))
          case Some(Schema.Bool) => Json.Bool(rs.getInt(i + 2) != 0)
          case Some(_) => Json.Str(rs.getString(i + 2))
          case None => Descent.parse(rs.getString(i + 2)).fold(e => throw new IllegalStateException(s"$table/$key.${col.name} does not parse: ${e.message}"), j => j)
        }))
      }
      m.decode(key, Json.Obj(fields.toVector))
    }
    private val selectAll = s"SELECT ${quoted("_key")}, ${columns.map(_.sql).mkString(", ")} FROM ${quoted(table)}"

    /** a leaf column's text, as the reference reads a leaf: a boolean "true"/"false", a number as printed */
    private def text(col: Column): String = col.leaf match {
      case Some(Schema.Bool) => s"CASE ${col.sql} WHEN 1 THEN 'true' WHEN 0 THEN 'false' END"
      case Some(Schema.Integer(_)) | Some(Schema.Number(_)) => s"CAST(${col.sql} AS TEXT)"
      case _ => col.sql
    }
    private def containsIn(col: Column, rest: List[String]): String =
      if (col.leaf.isDefined) s"instr(lower(${text(col)}), ?) > 0" else dialect.contains(col.sql, rest)
    /** a `Where` as a condition and its parameters: the head of a path a column, the rest within a JSON column */
    def condition(w: Where): (String, List[Any]) = w match {
      case Where.All => ("1 = 1", Nil)
      case Where.And(Nil) => ("1 = 1", Nil)
      case Where.And(all) => val cs = all.map(condition); (cs.map(x => s"(${x._1})").mkString(" AND "), cs.flatMap(_._2))
      case Where.Or(Nil) => ("1 = 0", Nil)
      case Where.Or(any) => val cs = any.map(condition); (cs.map(x => s"(${x._1})").mkString(" OR "), cs.flatMap(_._2))
      case Where.Not(x) => val (sql, ps) = condition(x); (s"NOT ($sql)", ps)
      case Where.Contains(Nil, t) => (columns.map(col => s"(${containsIn(col, Nil)})").mkString(" OR "), columns.map(_ => t.toLowerCase))
      case Where.Eq(Nil, _) | Where.Has(Nil, _) | Where.Less(Nil, _) | Where.Greater(Nil, _) =>
        throw new IllegalArgumentException("a table per type compares columns: a path names a field")
      case Where.Eq(p, v) => byName.get(p.head) match {
        case None => ("1 = 0", Nil)
        case Some(col) if col.leaf.isDefined => if (p.tail.nonEmpty) ("1 = 0", Nil) else if (v == Json.Null) (s"${col.sql} IS NULL", Nil) else (s"${col.sql} = ?", List(bind(v)))
        case Some(col) => if (v == Json.Null) (dialect.isNull(col.sql, p.tail), Nil) else (s"${dialect.extract(col.sql, p.tail)} = ?", List(bind(v)))
      }
      case Where.Contains(p, t) => byName.get(p.head) match {
        case None => ("1 = 0", Nil)
        case Some(col) if col.leaf.isDefined => if (p.tail.nonEmpty) ("1 = 0", Nil) else (containsIn(col, Nil), List(t.toLowerCase))
        case Some(col) => (dialect.contains(col.sql, p.tail), List(t.toLowerCase))
      }
      case Where.Has(p, v) => byName.get(p.head) match {
        case Some(col) if col.leaf.isEmpty => (dialect.has(col.sql, p.tail), List(bind(v)))
        case _ => ("1 = 0", Nil)
      }
      case Where.Less(p, v) => compared(p, "<", v)
      case Where.Greater(p, v) => compared(p, ">", v)
    }
    private def compared(p: List[String], op: String, v: Json): (String, List[Any]) = byName.get(p.head) match {
      case None => ("1 = 0", Nil)
      case Some(col) if col.leaf.isDefined => if (p.tail.nonEmpty) ("1 = 0", Nil) else (s"${col.sql} $op ?", List(bind(v)))
      case Some(col) => (s"${dialect.extract(col.sql, p.tail)} $op ?", List(bind(v)))
    }
    private def ordering(order: List[Order]): String = if (order.isEmpty) "" else " ORDER BY " + order.map { o =>
      val expr = byName.get(o.path.head) match {
        case Some(col) if col.leaf.isDefined => col.sql
        case Some(col) => dialect.extract(col.sql, o.path.tail)
        case None => "NULL"
      }
      expr + (if (o.descending) " DESC" else " ASC")
    }.mkString(", ")

    def apply[R[_], Rest[_], B](p: B ! R)(implicit sp: Split[E, R, Rest]): B ! Rest =
      handle[E, R, Rest, B, B](p, new TailHandler[E, Rest, B, B] {
        def ret(b: B) = pure(b)
        def answer[X](op: E[X]): X = { made; op match {
          case Mapped.Save(a, k) => update(c, dialect.upsert(table, "_key" :: columns.map(_.name)), row(a): _*); k(())
          case Mapped.Load(key, k) => k(rows(c, s"$selectAll WHERE ${quoted("_key")} = ?", key)(rs => value(rs.getString(1), rs)).headOption)
          case Mapped.Remove(key, k) => update(c, s"DELETE FROM ${quoted(table)} WHERE ${quoted("_key")} = ?", key); k(())
          case Mapped.Find(w, o, l, k) =>
            val (cond, params) = condition(w)
            k(rows(c, s"$selectAll WHERE $cond" + ordering(o) + l.fold("")(n => s" LIMIT $n"), params: _*)(rs => value(rs.getString(1), rs)))
          case Mapped.Count(w, k) =>
            val (cond, params) = condition(w)
            k(rows(c, s"SELECT COUNT(*) FROM ${quoted(table)} WHERE $cond", params: _*)(_.getLong(1)).head)
        } }
      })
  }
}
object Tables {
  def apply()(implicit fx: Effects): Tables[fx.type] = { implicit val exact: fx.type = fx; new Tables[fx.type] }
}
