package okay.store.jdbc

import java.sql.{Connection, DriverManager}
import okay.effects.{Runner, Sum, Void}
import okay.effects.json.Json
import okay.effects.machine.Machine
import okay.effects.shape.{Day, Shape}
import okay.effects.shape.Shape.{bool, date, field, int, record, text}
import okay.store._

/** THE POSTGRES DIALECT, against a live database: `OKAY_PG=jdbc:postgresql://host:port/db?user=u&password=p` names
 * it, else these are skipped. Every form of `Where` against the reference, the shop's program through `Data` and as
 * a table per type, keys from the store, a type with optional, date, boolean and nested fields */
class PostgresSuite extends munit.FunSuite {
  val fx0: Machine.type = Machine
  import Machine._
  val url: Option[String] = sys.env.get("OKAY_PG")
  override def munitIgnore: Boolean = url.isEmpty
  val datas: Datas[Machine.type] = new Datas[Machine.type]()(Machine)
  val sqls: Sqls[Machine.type] = new Sqls[Machine.type]()(Machine)
  val mappings: Mappings[Machine.type] = new Mappings[Machine.type]()(Machine)
  val tables: Tables[Machine.type] = new Tables[Machine.type]()(Machine)
  type D[X] = Sum[Void, Data, X]
  def connect(): Connection = { Class.forName("org.postgresql.Driver"); DriverManager.getConnection(url.get) }
  /** a clean slate: the tables these tests make, dropped */
  def fresh(c: Connection): Unit = List("t", "sales", "customers", "purchases", "people", "_keys", "kinds", "versions", "retired", "events", "marks").foreach(t => Jdbc.update(c, s"""DROP TABLE IF EXISTS "$t""""))

  test("every form of Where compiled to Postgres against the reference on the same documents") {
    val c = connect(); fresh(c)
    val docs = List(
      "1" -> Json.obj("a" -> Json.obj("b" -> Json.Str("Hello"), "n" -> Json.num(3)), "tags" -> Json.arr(Json.Str("x"), Json.Str("y")), "ns" -> Json.arr(Json.num(1), Json.obj("c" -> Json.Str("deep")))),
      "2" -> Json.obj("a" -> Json.obj("b" -> Json.Str("world"), "n" -> Json.num(7)), "tags" -> Json.arr(Json.Str("z")), "flag" -> Json.Bool(true)),
      "3" -> Json.obj("a" -> Json.obj("n" -> Json.num(5)), "empty" -> Json.Null))
    import Where._
    val wheres = List(Eq(List("a", "b"), Json.Str("Hello")), Eq(List("empty"), Json.Null), Eq(List("flag"), Json.Bool(true)), Contains(List("a"), "ELL"), Contains(List("ns"), "dee"),
      Contains(List("a"), "b"), Has(List("tags"), Json.Str("y")), Has(List("a"), Json.Str("y")), Less(List("a", "n"), Json.num(6)), Greater(List("a", "n"), Json.num(4)),
      Not(Has(List("tags"), Json.Str("z"))), or(Eq(List("a", "n"), Json.num(9)), Eq(List("a", "n"), Json.num(7))), and(), Or(Nil),
      Not(Eq(List("flag"), Json.Bool(true))), Not(Eq(List("zz"), Json.Null)), Not(Less(List("a", "b"), Json.Str("m"))), Not(Contains(List("tags"), "z")), Contains(Nil, "3"), Eq(List("a", "n"), Json.num(5)))
    try {
      val got = Machine.run(sqls.on(c, Dialect.postgres)(docs.foldLeft(datas.put[D]("t", "0", Json.obj()))((acc, kd) => acc.flatMap(_ => datas.put[D]("t", kd._1, kd._2))).flatMap(_ => datas.delete[D]("t", "0")).flatMap(_ =>
        wheres.foldLeft(pure[List[List[String]], D](Nil))((acc, w) => acc.flatMap(rs => datas.query[D]("t", w, List(Order.by("a", "n"))).map(found => rs :+ found.map(_._1)))))))
      val reference = wheres.map(w => Order.select(docs, w, List(Order.by("a", "n")), None).map(_._1))
      assertEquals(got, reference)
      assertEquals(Machine.run(sqls.on(c, Dialect.postgres)(datas.query[D]("t", All, List(Order.desc("a", "n")), Some(2)).map(_.map(_._1)))), List("2", "3"))
      assertEquals(Machine.run(sqls.on(c, Dialect.postgres)(datas.count[D]("t", Has(List("tags"), Json.Str("x"))))), 1L)
    } finally { fresh(c); c.close() }
  }
  test("the store, a domain of one's own and a type mapped through Data on Postgres answer as in memory; keys from the store") {
    val c = connect(); fresh(c)
    val stores: Stores[Machine.type] = new Stores[Machine.type]()(Machine)
    type S[X] = Sum[Void, Store, X]
    type DS[X] = Sum[D, Store, X]
    type DP[X] = Sum[D, Purchases, X]
    val shop = new Shop.Of[Machine.type](Machine)
    type DC[X] = Sum[D, shop.Cs, X]
    type DCS[X] = Sum[DC, shop.Ss, X]
    try {
      val programs = new StorePrograms.Of[Machine.type](Machine)
      val (_, reference) = Machine.run(stores.keeping(Kept.empty(List(StorePrograms.passport)), StorePrograms.ids(), StorePrograms.clock())(programs.program[S]))
      val onPg = Machine.run(sqls.on(c, Dialect.postgres)(stores.onData(StorePrograms.ids(), StorePrograms.clock())[DS, D, StorePrograms.Out](
        datas.put[DS]("kinds", StorePrograms.passport.id.value, Kind.shape.encode(StorePrograms.passport)).flatMap(_ => programs.program[DS]))))
      assertEquals(onPg, reference)
      val on = new Purchases.OnData[Machine.type](datas, Programs.ids())
      assertEquals(Machine.run(sqls.on(c, Dialect.postgres)(on[DP, D, Programs.Out](new Programs.Of[Machine.type](Machine).purchasing[DP]))), Programs.expected)
      assertEquals(Machine.run(sqls.on(c, Dialect.postgres)(shop.mappings.onData(Shop.customer)[DC, D, Shop.Out](shop.mappings.onData(Shop.sale)[DCS, DC, Shop.Out](shop.program[DCS])))), Shop.expected)
      val (k1, k2) = Machine.run(sqls.on(c, Dialect.postgres)(datas.fresh[D]("sales").flatMap(a => datas.fresh[D]("sales").map(b => (a, b)))))
      assertEquals((k1, k2), ("000000000001", "000000000002"))
    } finally { fresh(c); c.close() }
  }
  test("a table per type on Postgres: the shop, a type with optional, date, boolean and nested fields, keys from the store") {
    val c = connect(); fresh(c)
    val shop = new Shop.Of[Machine.type](Machine)
    type C[X] = Sum[Void, shop.Cs, X]
    type CS[X] = Sum[C, shop.Ss, X]
    final case class Address(city: String, lines: List[String])
    final case class Person(id: String, name: String, born: Option[Day], vip: Boolean, score: Option[Int], address: Option[Address], tags: List[String])
    val address: Shape[Address] = record(field("city", text), field("lines", text.many()))(Address(_, _))(a => (a.city, a.lines))
    val person: Mapping[Person] = Mapping("people", record(field("id", text), field("name", text), field("born", date.optional), field("vip", bool), field("score", int.optional),
      field("address", address.optional), field("tags", text.many()))(Person(_, _, _, _, _, _, _))(p => (p.id, p.name, p.born, p.vip, p.score, p.address, p.tags)), _.id)
    type P[X] = Sum[Void, ({ type L[X] = Mapped[Person, X] })#L, X]
    import mappings._
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
      made <- create[Person, R](k => Person(k, "Cy", None, vip = false, None, None, Nil))
    } yield (a, b, noBorn, vips, kyiv, flat, tagged, anywhere, young, scored, nobody, made.id)
    try {
      assertEquals(Machine.run(tables.on(Shop.customer, c, Dialect.postgres)[C, Void, Shop.Out](tables.on(Shop.sale, c, Dialect.postgres)[CS, C, Shop.Out](shop.program[CS]))), Shop.expected)
      assertEquals(Jdbc.query(c, "SELECT item, qty FROM sales WHERE customer = 'ann' ORDER BY qty DESC")(rs => (rs.getString(1), rs.getInt(2))), List(("green tea", 5), ("tea", 1)))
      val out = Machine.run(tables.on(person, c, Dialect.postgres)[P, Void, (Option[Person], Option[Person], List[Person], List[Person], List[Person], List[Person], List[Person], List[Person], List[Person], Long, List[Person], String)](program[P]))
      assertEquals(out, (Some(ann), Some(bo), List(bo), List(ann), List(ann), List(ann), List(ann), List(ann), List(ann), 1L, Nil, "000000000001"))
    } finally { fresh(c); c.close() }
  }
  test("the database's own transaction on Postgres: committed at the end, rolled back when ended early") {
    import Machine.std.{Error, attempt, raise}
    type Er[X] = Sum[Void, ({ type L[x] = Error[String, x] })#L, X]
    type E[X] = Sum[Er, Data, X]
    val c = connect(); fresh(c)
    try {
      val a = Json.obj("n" -> Json.num(1))
      assertEquals(Machine.run(sqls.atomically(c, Dialect.postgres)[D, Void, Long](datas.put[D]("t", "a", a).flatMap(_ => datas.count[D]("t")))), 1L)
      val early = Machine.run(attempt[String](sqls.atomically(c, Dialect.postgres)[E, Er, Unit](datas.put[E]("t", "b", a).flatMap(_ => raise[String, E, Unit]("no")))))
      assertEquals(early, Left("no"))
      assertEquals(Machine.run(sqls.atomically(c, Dialect.postgres)[D, Void, Long](datas.count[D]("t"))), 1L)
    } finally { fresh(c); c.close() }
  }
}
