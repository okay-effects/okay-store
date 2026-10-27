package okay.store.jdbc

import java.sql.Connection
import okay.effects.Effects
import okay.store.{Backend, Data, Datas, SqlData, SqlStatement}

/** `Data` ON SQL by JDBC: the statements `okay.store.SqlData`'s, the dialect `okay.store.Dialect` (named here as ever) */
class Sqls[F <: Effects with Singleton](implicit implementation: F) {
  val fx: F = implementation
  val datas: Datas[fx.type] = new Datas[fx.type]()(fx)
  /** the backend: `Data` on this connection, by the dialect */
  def backend(c: Connection, dialect: Dialect = Dialect.sqlite): Sql = new Sql(c, dialect)
  /** the handler over it */
  def on(c: Connection, dialect: Dialect = Dialect.sqlite): datas.On = datas.on(backend(c, dialect))

  /** ATOMICALLY, THE DATABASE'S OWN: `p`'s operations go straight to the database inside its transaction — it sees its
   * own unfinished writes, a query keeps its `LIMIT` — and `p`'s end is `COMMIT`, one atomic write; what ends `p`
   * before leaves the transaction open, and the next `atomically` on the connection, or its closing, rolls it back
   * (`Transactional.rollback` does at once). The same meaning as `Datas.atomically` over the journal, faster, and the
   * database's isolation. A key from `Fresh` is the transaction's too: rolled back, it is given again — never to a
   * committed use twice */
  def atomically(c: Connection, dialect: Dialect = Dialect.sqlite): Atomically = new Atomically(new Transactional(backend(c, dialect), c))
  final class Atomically(transactional: Transactional) {
    import fx._
    def apply[R[_], Rest[_], A](p: A ! R)(implicit sp: Split[Data, R, Rest]): A ! Rest =
      handle[Data, R, Rest, A, A](p, new TailHandler[Data, Rest, A, A] {
        def ret(a: A) = { transactional.commit(); pure(a) }
        def answer[X](op: Data[X]): X = { transactional.begin(); transactional.answer(op) }
      })
  }
}

/** a connection's transaction around a `Sql` backend: begun at the first operation, committed at the end; a
 * transaction left open by a program that ended early is rolled back by the next `begin` */
final class Transactional(sql: Sql, c: Connection) extends Backend {
  private val transaction = new Transaction(c)
  def begin(): Unit = transaction.begin()   // a transaction another left open is rolled back first
  def answer[X](op: Data[X]): X = sql.answer(op)
  def commit(): Unit = transaction.commit()
  def rollback(): Unit = transaction.rollback()
}
object Sqls {
  def apply()(implicit fx: Effects): Sqls[fx.type] = { implicit val exact: fx.type = fx; new Sqls[fx.type] }
  /** two-valued: a condition that is NULL is false */
  def sure(condition: String): String = SqlData.sure(condition)
}

/** `Data` ON A CONNECTION, a `Backend`: a table `(key, doc)` per collection made on first use; each operation
 * `SqlData`'s statement */
final class Sql(c: Connection, dialect: Dialect) extends Backend {
  private val made = scala.collection.mutable.Set.empty[String]
  private lazy val keys: Unit = { Jdbc.run(c, SqlData.keys(dialect)); () }
  private def table(collection: String): Unit =
    if (!made(collection)) { Jdbc.run(c, SqlData.table(dialect, collection)); made += collection }
  private def rows[A](s: SqlStatement)(row: java.sql.ResultSet => A): List[A] = Jdbc.rows(c, s)(row)

  def answer[X](op: Data[X]): X = op match {
    case Data.Fresh(col, k) => keys; k(Data.key(rows(SqlData.fresh(dialect, Data.named(col)))(_.getLong(1)).head))
    case Data.Get(col, key, k) => table(col); k(rows(SqlData.get(col, key))(rs => SqlData.doc(rs.getString(1))).headOption)
    case Data.Put(col, key, d, k) => table(col); Jdbc.run(c, SqlData.put(dialect, col, key, d)); k(())
    case Data.Delete(col, key, k) => table(col); Jdbc.run(c, SqlData.delete(col, key)); k(())
    case Data.Query(col, w, o, l, k) =>
      table(col); k(rows(SqlData.query(dialect, col, w, o, l))(rs => (rs.getString(1), SqlData.doc(rs.getString(2)))))
    case Data.Count(col, w, k) => table(col); k(rows(SqlData.count(dialect, col, w))(_.getLong(1)).head)
  }
}
