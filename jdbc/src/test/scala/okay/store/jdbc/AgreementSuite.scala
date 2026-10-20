package okay.store.jdbc

import okay.effects.{Runner, Sum, Void}
import okay.effects.json.Json
import okay.effects.machine.Machine
import okay.effects.free.Free
import okay.effects.shape.Schema
import okay.store._

/** THE TWO HANDLERS AGREE: one program, the store in memory and the store on SQLite, the same answers — records, ids,
 * versions, events, what is unsaved; and the database keeps what the memory forgets when the connection is reopened */
abstract class AgreementChecks(val fx0: Runner, name: String) extends munit.FunSuite {
  val stores: Stores[fx0.type] = new Stores[fx0.type]()(fx0)
  val jdbcs: Jdbcs[fx0.type] = new Jdbcs[fx0.type]()(fx0)
  import fx0._
  import stores._
  type S[X] = Sum[Void, Store, X]

  import StorePrograms.{passport, ids, clock}
  val datas: Datas[fx0.type] = new Datas[fx0.type]()(fx0)
  val sqls: Sqls[fx0.type] = new Sqls[fx0.type]()(fx0)
  type D[X] = Sum[Void, Data, X]
  type DS[X] = Sum[D, Store, X]
  type DP[X] = Sum[D, Purchases, X]
  val programs = new StorePrograms.Of[fx0.type](fx0)
  val program: StorePrograms.Out ! S = programs.program[S]
  type Out = StorePrograms.Out

  test(s"$name: the same answers in memory and on SQLite") {
    val (_, inMemory) = fx0.run(keeping(Kept.empty(List(passport)), ids(), clock())(program))
    val c = Sqlite.open(":memory:", List(passport))
    val onSqlite = try fx0.run(jdbcs.on(c, ids(), clock())(program)) finally c.close()
    assertEquals(onSqlite, inMemory)
    // and translated to Data, on SQL: the same again
    val c2 = Sqlite.connect(":memory:")
    val onData = try fx0.run(sqls.on(c2)(stores.onData(ids(), clock())[DS, D, Out](
      datas.put[DS]("kinds", passport.id.value, Kind.shape.encode(passport)).flatMap(_ => programs.program[DS])))) finally c2.close()
    assertEquals(onData, inMemory)
    // and the answers mean what they should
    assertEquals(inMemory._1.map(_.name), List("Note", "Passport (travel)"))
    assertEquals(inMemory._2.map(_.kind.value), List("passport", "note"))          // newest first, the passport revised last; "ab12" in a number and in a note's text
    assertEquals(inMemory._3.map(_.fields.get("number")), List(Some(Json.Str("AB124"))))
    assertEquals(inMemory._4.map(_.kind.value), List("note"))
    assertEquals(inMemory._5, None); assertEquals(inMemory._6.size, 2); assertEquals(inMemory._7.map(_.kind.value), List("note"))
    assertEquals(inMemory._8._2, 8); assertEquals(inMemory._9, 0)
    assertEquals(inMemory._8._1.collect { case e: Event.Accessed => e.by }, List("owner"))
  }
  test(s"$name: what is written stays: the database reopened answers the same; the marks too") {
    val f = java.io.File.createTempFile("okay-store", ".db"); f.delete()
    val c1 = Sqlite.open(f.getPath, List(passport))
    val (r, unsaved1) = try fx0.run(jdbcs.on(c1, ids(), clock())(add[S](passport.id, Json.obj("number" -> Json.Str("X1"), "country" -> Json.Str("UA"))).flatMap(r => journaled[S].map(j => (r, j._2))))) finally c1.close()
    val c2 = Sqlite.open(f.getPath, List(passport))
    val (got, kinds2, unsaved2, unsaved3) = try fx0.run(jdbcs.on(c2, ids(), clock())(get[S](r.id).flatMap(g => kinds[S].flatMap(ks => journaled[S].flatMap(j => saved[S].flatMap(_ => journaled[S].map(j2 => (g, ks, j._2, j2._2)))))))) finally c2.close()
    assertEquals(got, Some(r)); assertEquals(kinds2.map(_.id), List(passport.id)); assertEquals((unsaved1, unsaved2, unsaved3), (1, 1, 0))
    f.delete()
  }
  test(s"$name: a domain of one's own on Data, on SQL: the same answers as in memory") {
    val c = Sqlite.connect(":memory:")
    val on = new Purchases.OnData[fx0.type](datas, Programs.ids())
    val out = try fx0.run(sqls.on(c)(on[DP, D, Programs.Out](new Programs.Of[fx0.type](fx0).purchasing[DP]))) finally c.close()
    assertEquals(out, Programs.expected)
  }
  test(s"$name: Where compiled to SQL: equality, null, text within, an element, less, not, or — against the reference on the same documents") {
    val c = Sqlite.connect(":memory:")
    val docs = List(
      "1" -> Json.obj("a" -> Json.obj("b" -> Json.Str("Hello"), "n" -> Json.num(3)), "tags" -> Json.arr(Json.Str("x"), Json.Str("y")), "ns" -> Json.arr(Json.num(1), Json.obj("c" -> Json.Str("deep")))),
      "2" -> Json.obj("a" -> Json.obj("b" -> Json.Str("world"), "n" -> Json.num(7)), "tags" -> Json.arr(Json.Str("z")), "flag" -> Json.Bool(true)),
      "3" -> Json.obj("a" -> Json.obj("n" -> Json.num(5)), "empty" -> Json.Null))
    import Where._
    val wheres = List(Eq(List("a", "b"), Json.Str("Hello")), Eq(List("empty"), Json.Null), Eq(List("flag"), Json.Bool(true)), Contains(List("a"), "ELL"), Contains(List("ns"), "dee"),
      Contains(List("a"), "b"), Has(List("tags"), Json.Str("y")), Has(List("a"), Json.Str("y")), Less(List("a", "n"), Json.num(6)), Greater(List("a", "n"), Json.num(4)),
      Not(Has(List("tags"), Json.Str("z"))), or(Eq(List("a", "n"), Json.num(9)), Eq(List("a", "n"), Json.num(7))), and(), Or(Nil))
    try {
      val got = fx0.run(sqls.on(c)(docs.foldLeft(datas.put[D]("t", "0", Json.obj()))((acc, kd) => acc.flatMap(_ => datas.put[D]("t", kd._1, kd._2))).flatMap(_ => datas.delete[D]("t", "0")).flatMap(_ =>
        wheres.foldLeft(pure[List[List[String]], D](Nil))((acc, w) => acc.flatMap(rs => datas.query[D]("t", w, List(Order.by("a", "n"))).map(found => rs :+ found.map(_._1)))))))
      val reference = wheres.map(w => Order.select(docs, w, List(Order.by("a", "n")), None).map(_._1))
      assertEquals(got, reference)
      assertEquals(fx0.run(sqls.on(c)(datas.query[D]("t", All, List(Order.desc("a", "n")), Some(2)).map(_.map(_._1)))), List("2", "3"))
      assertEquals(fx0.run(sqls.on(c)(datas.count[D]("t", Has(List("tags"), Json.Str("x"))))), 1L)
    } finally c.close()
  }
  test(s"$name: types mapped, on SQLite: the same answers as in memory") {
    val shop = new Shop.Of[fx0.type](fx0)
    import shop.mappings
    type DC[X] = Sum[D, shop.Cs, X]
    type DCS[X] = Sum[DC, shop.Ss, X]
    val c = Sqlite.connect(":memory:")
    val out = try fx0.run(sqls.on(c)(mappings.onData(Shop.customer)[DC, D, Shop.Out](mappings.onData(Shop.sale)[DCS, DC, Shop.Out](shop.program[DCS])))) finally c.close()
    assertEquals(out, Shop.expected)
  }
}
class AgreementOnMachine extends AgreementChecks(Machine, "machine")
class AgreementOnFree extends AgreementChecks(Free, "free")
