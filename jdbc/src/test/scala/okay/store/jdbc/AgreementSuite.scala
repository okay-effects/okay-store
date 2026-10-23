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
  test(s"$name: adopt, the same in memory, on SQLite and on Data: a journal of others taken in, again nothing more") {
    val uuids = () => java.util.UUID.randomUUID().toString
    val (other, _) = fx0.run(keeping(Kept.empty(List(passport)), uuids, clock())(program))
    val journal = other.events
    def taking[R[_]](implicit in: In[Store, R]) = for {
      _ <- adopt[R](journal); all <- find[R](""); ks <- kinds[R]; j <- journaled[R]
      _ <- adopt[R](journal); j2 <- journaled[R]
    } yield (all, ks, j._1, j2._1.size)
    val (_, inMemory) = fx0.run(keeping(Kept.empty(List(passport)), ids(), clock())(taking[S]))
    val c = Sqlite.open(":memory:", List(passport))
    val onSqlite = try fx0.run(jdbcs.on(c, ids(), clock())(taking[S])) finally c.close()
    val c2 = Sqlite.connect(":memory:")
    type Taken = (List[Record], List[Kind], List[Event], Int)
    val onData = try fx0.run(sqls.on(c2)(stores.onData(ids(), clock())[DS, D, Taken](
      datas.put[DS]("kinds", passport.id.value, Kind.shape.encode(passport)).flatMap(_ => taking[DS]))))
      finally c2.close()
    assertEquals(onSqlite, inMemory); assertEquals(onData, inMemory)
    assertEquals(inMemory._1.map(_.kind.value), List("note")); assertEquals(inMemory._3, journal)
    assertEquals(inMemory._4, journal.size)
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
      Not(Has(List("tags"), Json.Str("z"))), or(Eq(List("a", "n"), Json.num(9)), Eq(List("a", "n"), Json.num(7))), and(), Or(Nil),
      Not(Eq(List("flag"), Json.Bool(true))), Not(Eq(List("zz"), Json.Null)), Not(Less(List("a", "b"), Json.Str("m"))), Not(Contains(List("tags"), "z")))
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
  test(s"$name: a table per type: the same answers as in memory; the table has the shape's columns") {
    val shop = new Shop.Of[fx0.type](fx0)
    val tables: Tables[fx0.type] = new Tables[fx0.type]()(fx0)
    type C[X] = Sum[Void, shop.Cs, X]
    type CS[X] = Sum[C, shop.Ss, X]
    val c = Sqlite.connect(":memory:")
    try {
      val out = fx0.run(tables.on(Shop.customer, c)[C, Void, Shop.Out](tables.on(Shop.sale, c)[CS, C, Shop.Out](shop.program[CS])))
      assertEquals(out, Shop.expected)
      assertEquals(Jdbc.query(c, "PRAGMA table_info(sales)")(rs => (rs.getString(2), rs.getString(3))), List(("_key", "TEXT"), ("id", "TEXT"), ("customer", "TEXT"), ("item", "TEXT"), ("qty", "INTEGER")))
      assertEquals(Jdbc.query(c, "SELECT item, qty FROM sales WHERE customer = 'ann' ORDER BY qty DESC")(rs => (rs.getString(1), rs.getInt(2))), List(("green tea", 5), ("tea", 1)), "readable by the database's own tools")
    } finally c.close()
  }
  test(s"$name: a table per type with optional, date, boolean and nested fields: round trip, null, a path within, the text within everything") {
    import okay.effects.shape.{Day, Shape}
    import okay.effects.shape.Shape.{bool, date, field, int, record, text}
    final case class Address(city: String, lines: List[String])
    final case class Person(id: String, name: String, born: Option[Day], vip: Boolean, score: Option[Int], address: Option[Address], tags: List[String])
    val address: Shape[Address] = record(field("city", text), field("lines", text.many()))(Address(_, _))(a => (a.city, a.lines))
    val person: Mapping[Person] = Mapping("people", record(field("id", text), field("name", text), field("born", date.optional), field("vip", bool), field("score", int.optional),
      field("address", address.optional), field("tags", text.many()))(Person(_, _, _, _, _, _, _))(p => (p.id, p.name, p.born, p.vip, p.score, p.address, p.tags)), _.id)
    val mappings: Mappings[fx0.type] = new Mappings[fx0.type]()(fx0)
    val tables: Tables[fx0.type] = new Tables[fx0.type]()(fx0)
    import mappings._
    type P[X] = Sum[Void, ({ type L[X] = Mapped[Person, X] })#L, X]
    type DP[X] = Sum[D, ({ type L[X] = Mapped[Person, X] })#L, X]
    val ann = Person("ann", "Ann", Some(Day(1990, 5, 6)), vip = true, Some(7), Some(Address("Kyiv", List("Main 1", "flat 2"))), List("x", "y"))
    val bo = Person("bo", "Bo", None, vip = false, None, None, Nil)
    def program[R[_]](implicit in: In[({ type L[X] = Mapped[Person, X] })#L, R]) = for {
      _ <- save[Person, R](ann); _ <- save[Person, R](bo)
      a <- load[Person, R]("ann"); b <- load[Person, R]("bo")
      noBorn <- find[Person, R](Field("born").isNull); vips <- find[Person, R](Field("vip") is true)
      kyiv <- find[Person, R](Field("address") / "city" is "Kyiv"); flat <- find[Person, R](Field("address") / "lines" contains "FLAT")
      tagged <- find[Person, R](Field("tags") has "y"); anywhere <- find[Person, R](Field() contains "main")
      young <- find[Person, R](Field("born") > "1980-01-01", List(Field("name").desc)); scored <- count[Person, R](Field("score") < 10)
      nobody <- find[Person, R](Field("zzz") is 1)
    } yield (a, b, noBorn, vips, kyiv, flat, tagged, anywhere, young, scored, nobody)
    val expected = (Some(ann), Some(bo), List(bo), List(ann), List(ann), List(ann), List(ann), List(ann), List(ann), 1L, Nil)
    val (_, inMemory) = fx0.run(mappings.datas.inMemory()(onData(person)[DP, D, (Option[Person], Option[Person], List[Person], List[Person], List[Person], List[Person], List[Person], List[Person], List[Person], Long, List[Person])](program[DP])))
    assertEquals(inMemory, expected)
    val c = Sqlite.connect(":memory:")
    val onTable = try fx0.run(tables.on(person, c)[P, Void, (Option[Person], Option[Person], List[Person], List[Person], List[Person], List[Person], List[Person], List[Person], List[Person], Long, List[Person])](program[P])) finally c.close()
    assertEquals(onTable, expected)
  }
  test(s"$name: keys from the store on SQL: Data on SQL and a table per type give the next of the collection's sequence, kept across connections") {
    val shop = new Shop.Of[fx0.type](fx0)
    import shop.mappings
    type DS[X] = Sum[D, shop.Ss, X]
    type S2[X] = Sum[Void, shop.Ss, X]
    def program[R[_]](implicit in: In[shop.Ss, R]) = for {
      a <- mappings.create[Sale, R](k => Sale(k, "ann", "tea", 1)); _ <- mappings.remove[Sale, R](a.id); b <- mappings.create[Sale, R](k => Sale(k, "bo", "coffee", 2))
    } yield (a.id, b.id)
    val f = java.io.File.createTempFile("okay-store", ".db"); f.delete()
    val c1 = Sqlite.connect(f.getPath)
    val viaData = try fx0.run(sqls.on(c1)(mappings.onData(Shop.sale)[DS, D, (String, String)](program[DS]))) finally c1.close()
    assertEquals(viaData, ("000000000001", "000000000002"))
    val c2 = Sqlite.connect(f.getPath)
    val again = try fx0.run(sqls.on(c2)(mappings.onData(Shop.sale)[DS, D, (String, String)](program[DS]))) finally c2.close()
    assertEquals(again, ("000000000003", "000000000004"), "the sequence is the database's, not the connection's")
    val tables: Tables[fx0.type] = new Tables[fx0.type]()(fx0)
    val c3 = Sqlite.connect(":memory:")
    val viaTable = try fx0.run(tables.on(Shop.sale, c3)[S2, Void, (String, String)](program[S2])) finally c3.close()
    assertEquals(viaTable, ("000000000001", "000000000002"))
    f.delete()
  }
  test(s"$name: atomically over SQL: the writes seen within, written at the end, none when the transaction ends early") {
    import fx0.std._
    type Er[X] = Sum[Void, ({ type L[x] = Error[String, x] })#L, X]
    type E[X] = Sum[Er, Data, X]
    val c = Sqlite.connect(":memory:")
    val sql = sqls.backend(c)
    try {
      val a = Json.obj("n" -> Json.num(1))
      def inside[R[_]](implicit in: In[Data, R]) = datas.put[R]("t", "a", a).flatMap(_ => datas.count[R]("t")).flatMap(n => datas.get[R]("t", "a").map(d => (n, d)))
      assertEquals(fx0.run(datas.atomically(sql)[D, Void, (Long, Option[Json])](inside[D])), (1L, Some(a)))
      assertEquals(fx0.run(datas.on(sql)(datas.count[D]("t"))), 1L)
      val early = fx0.run(attempt[String](datas.atomically(sql)[E, Er, Unit](datas.put[E]("t", "b", a).flatMap(_ => raise[String, E, Unit]("no")))))
      assertEquals(early, Left("no")); assertEquals(fx0.run(datas.on(sql)(datas.count[D]("t"))), 1L)
    } finally c.close()
  }
  test(s"$name: the database's own transaction: seen within, committed at the end, rolled back when ended early") {
    import fx0.std._
    type Er[X] = Sum[Void, ({ type L[x] = Error[String, x] })#L, X]
    type E[X] = Sum[Er, Data, X]
    val c = Sqlite.connect(":memory:")
    try {
      val a = Json.obj("n" -> Json.num(1))
      def inside[R[_]](implicit in: In[Data, R]) = datas.put[R]("t", "a", a).flatMap(_ => datas.count[R]("t")).flatMap(n => datas.query[R]("t", Where.All, Nil, Some(1)).map(rs => (n, rs.map(_._1))))
      assertEquals(fx0.run(sqls.atomically(c)[D, Void, (Long, List[String])](inside[D])), (1L, List("a")))
      assert(c.getAutoCommit, "committed, the connection back to autocommit")
      assertEquals(fx0.run(sqls.on(c)(datas.count[D]("t"))), 1L)
      // ended early: the transaction is left open, uncommitted — the next one rolls it back
      val early = fx0.run(attempt[String](sqls.atomically(c)[E, Er, Unit](datas.put[E]("t", "b", a).flatMap(_ => raise[String, E, Unit]("no")))))
      assertEquals(early, Left("no")); assert(!c.getAutoCommit, "left open")
      assertEquals(fx0.run(sqls.atomically(c)[D, Void, Long](datas.count[D]("t"))), 1L, "rolled back by the next")
    } finally c.close()
  }
}
class AgreementOnMachine extends AgreementChecks(Machine, "machine")
class AgreementOnFree extends AgreementChecks(Free, "free")
