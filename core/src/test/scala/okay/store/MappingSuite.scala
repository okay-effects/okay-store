package okay.store

import okay.effects.{Runner, Sum, Void}
import okay.effects.json.Json
import okay.effects.machine.Machine
import okay.effects.free.Free
import okay.effects.shape.Shape
import okay.effects.shape.Shape.{field, int, record, text}

/** TYPES MAPPED, no effect written by hand: two types, their mappings, one program over both, the same answers on
 * every backend */
final case class Customer(id: String, name: String, city: String)
final case class Sale(id: String, customer: String, item: String, qty: Int)
object Shop {
  val customer: Mapping[Customer] = Mapping("customers", record(field("id", text), field("name", text), field("city", text))(Customer(_, _, _))(c => (c.id, c.name, c.city)), _.id)
  val sale: Mapping[Sale] = Mapping("sales", record(field("id", text), field("customer", text), field("item", text), field("qty", int))(Sale(_, _, _, _))(s => (s.id, s.customer, s.item, s.qty)), _.id)

  type Out = (List[Sale], List[Sale], Long, Option[Customer], Option[Customer], List[Customer], List[Sale])
  val expected: Out = (
    List(Sale("s2", "ann", "green tea", 5), Sale("s1", "ann", "tea", 2)),
    List(Sale("s2", "ann", "green tea", 5), Sale("s3", "bo", "tea", 3)),
    3L, Some(Customer("ann", "Ann", "Kyiv")), None, List(Customer("ann", "Ann", "Kyiv")),
    List(Sale("s2", "ann", "green tea", 5), Sale("s3", "bo", "tea", 3)))

  /** the program over an implementation, in any row that has both effects */
  final class Of[F <: Runner](val fx0: F) {
    val mappings: Mappings[fx0.type] = new Mappings[fx0.type]()(fx0)
    import fx0._
    import mappings._
    type Cs[X] = Mapped[Customer, X]
    type Ss[X] = Mapped[Sale, X]
    def program[R[_]](implicit cs: In[Cs, R], ss: In[Ss, R]): Out ! R = for {
      _ <- save[Customer, R](Customer("ann", "Ann", "Kyiv")); _ <- save[Customer, R](Customer("bo", "Bo", "Lviv"))
      _ <- save[Sale, R](Sale("s1", "ann", "tea", 2)); _ <- save[Sale, R](Sale("s2", "ann", "green tea", 5)); _ <- save[Sale, R](Sale("s3", "bo", "tea", 3))
      anns <- find[Sale, R](Field("customer") is "ann", List(Field("qty").desc))
      big <- find[Sale, R](Field("qty") > 2, List(Field("id").asc))
      teas <- count[Sale, R](Field("item") contains "TEA")
      ann <- load[Customer, R]("ann"); nobody <- load[Customer, R]("zed")
      _ <- remove[Customer, R]("bo")
      left <- all[Customer, R]()
      _ <- save[Sale, R](Sale("s1", "ann", "tea", 1)); cheap <- find[Sale, R](Where.Not(Field("qty") < 2), List(Field("qty").desc))
    } yield (anns, big, teas, ann, nobody, left, cheap)
  }
}

abstract class MappingChecks(val fx0: Runner, name: String) extends munit.FunSuite {
  import fx0._
  val shop = new Shop.Of[fx0.type](fx0)
  import shop.mappings
  type D[X] = Sum[Void, Data, X]
  type DC[X] = Sum[D, shop.Cs, X]
  type DCS[X] = Sum[DC, shop.Ss, X]

  test(s"$name: two types mapped, one program, in memory and on lines alike") {
    val (state, inMemory) = fx0.run(mappings.datas.inMemory()(mappings.onData(Shop.customer)[DC, D, Shop.Out](mappings.onData(Shop.sale)[DCS, DC, Shop.Out](shop.program[DCS]))))
    assertEquals(inMemory, Shop.expected)
    assertEquals(state.keySet, Set("customers", "sales")); assertEquals(state("customers").keySet, Set("ann"))
    var files = Map.empty[String, List[String]]
    val onLines = fx0.run(mappings.datas.onLines(f => files.getOrElse(f, Nil), (f, l) => files = files.updated(f, files.getOrElse(f, Nil) :+ l))(
      mappings.onData(Shop.customer)[DC, D, Shop.Out](mappings.onData(Shop.sale)[DCS, DC, Shop.Out](shop.program[DCS]))))
    assertEquals(onLines, Shop.expected)
    assertEquals(files("customers.jsonl").size, 3, "two saves and a tombstone")
  }
  test(s"$name: what is in the store and does not read by the shape is the store's fault, said plainly") {
    val bad = Map("customers" -> Map("x" -> Json.obj("id" -> Json.Str("x"))))
    val e = intercept[IllegalStateException](fx0.run(mappings.datas.inMemory(bad)(mappings.onData(Shop.customer)[DC, D, Option[Customer]](mappings.load[Customer, DC]("x")))))
    assert(e.getMessage.contains("customers/x does not read"), e.getMessage)
  }
  test(s"$name: Where and Order as values: a round trip through their shapes, a JSON Schema of find's parameters") {
    val w = Where.and(Field("a") / "b" is "x", Where.or(Field("n") > 2, Where.Not(Field("tags") has "z")), Field("gone").isNull, Where.All)
    assertEquals(Where.shape.decode(Where.shape.encode(w)), Right(w))
    assertEquals(Order.shape.decode(Order.shape.encode(Field("n").desc)), Right(Field("n").desc))
    val d = Mapping.describe(Shop.sale)
    assertEquals(d.operations.map(_.name), List("save", "load", "remove", "find", "count"))
    val schema = okay.effects.shape.JsonSchema.of(d.operation("find").get.params.erased)
    assert(Json.print(schema).contains("\"where\"") && Json.print(schema).contains("$ref"), Json.print(schema))
  }
  test(s"$name: a mapped type described: the same program answers the same when performed from afar, the service in its own run") {
    val domains = new okay.effects.describe.Domains[fx0.type]()(fx0)
    val d = Mapping.describe(Shop.sale)
    type Ss[X] = Mapped[Sale, X]
    type S[X] = Sum[Void, Ss, X]
    type DS[X] = Sum[D, Ss, X]
    def program[R[_]](implicit in: In[Ss, R]) = for {
      _ <- mappings.save[Sale, R](Sale("s1", "ann", "tea", 2)); _ <- mappings.save[Sale, R](Sale("s2", "bo", "green tea", 5))
      teas <- mappings.find[Sale, R](Field("item") contains "tea", List(Field("qty").desc), Some(1)); n <- mappings.count[Sale, R](Field("qty") > 1)
      one <- mappings.load[Sale, R]("s1"); _ <- mappings.remove[Sale, R]("s1"); gone <- mappings.load[Sale, R]("s1")
    } yield (teas, n, one, gone)
    val (_, direct) = fx0.run(mappings.datas.inMemory()(mappings.onData(Shop.sale)[DS, D, (List[Sale], Long, Option[Sale], Option[Sale])](program[DS])))
    var state = Map.empty[String, Map[String, Json]]; var carried = List.empty[String]
    val transport: (okay.effects.describe.Op[Ss, _, _], Json) => Json ! Void = (o, args) => {
      carried = o.name :: carried
      val (st, r) = fx0.run(mappings.datas.inMemory(state)(mappings.onData(Shop.sale)[DS, D, Either[okay.effects.shape.Problems, Json]](domains.serve[Ss, DS](d)(o.name, args))))
      state = st; pure[Json, Void](r.fold(ps => throw new IllegalStateException(ps.toString), j => j))
    }
    val remote = fx0.run(domains.remote[Ss](d).apply[S, Void, (List[Sale], Long, Option[Sale], Option[Sale])](transport)(program[S]))
    assertEquals(remote, direct); assertEquals(direct._1.map(_.id), List("s2")); assertEquals(direct._2, 2L)
    assertEquals(carried.reverse, List("save", "save", "find", "count", "load", "remove", "load"))
  }
}
class MappingOnMachine extends MappingChecks(Machine, "machine")
class MappingOnFree extends MappingChecks(Free, "free")
