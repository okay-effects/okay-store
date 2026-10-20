package okay.store

import okay.effects.{Runner, Sum, Void}
import okay.effects.json.Json
import okay.effects.machine.Machine
import okay.effects.free.Free
import okay.effects.shape.Shape
import okay.effects.shape.Shape.{bool, field, int, record, text}

/** A DOMAIN OF ONE'S OWN on `Data`: an effect, its handler a translation into typed collections — written once, run on
 * every backend. The test's example: purchases placed, listed open, found by item, closed */
final case class Purchase(id: String, item: String, qty: Int, open: Boolean)
object Purchase { val shape: Shape[Purchase] = record(field("id", text), field("item", text), field("qty", int), field("open", bool))(Purchase(_, _, _, _))(p => (p.id, p.item, p.qty, p.open)) }
sealed trait Purchases[X]
object Purchases {
  final case class Place[X](item: String, qty: Int, answer: Purchase => X) extends Purchases[X]
  final case class Open[X](answer: List[Purchase] => X) extends Purchases[X]
  final case class Of[X](item: String, answer: List[Purchase] => X) extends Purchases[X]
  final case class Close[X](id: String, answer: Boolean => X) extends Purchases[X]

  /** the handler: each operation a few words over one typed collection; the backend is whoever handles `Data` */
  final class OnData[F <: okay.effects.Effects](val datas: Datas[F], ids: () => String) {
    import datas.fx._
    import datas._
    val purchases: Collection[Purchase] = Collection("purchases", Purchase.shape, _.id)
    def apply[R[_], Rest[_], A](p: A ! R)(implicit sp: Split[Purchases, R, Rest], d: In[Data, Rest]): A ! Rest =
      handle[Purchases, R, Rest, A, A](p, new Handler[Purchases, Rest, A, A] {
        def ret(a: A) = pure(a)
        def op[X](op: Purchases[X], k: X => A ! Rest) = op match {
          case Place(item, qty, answer) => val x = Purchase(ids(), item, qty, open = true); keep[Purchase, Rest](purchases, x).flatMap(_ => k(answer(x)))
          case Open(answer) => select[Purchase, Rest](purchases, Where.Eq(List("open"), Json.Bool(true)), List(Order.by("id"))).flatMap(xs => k(answer(xs)))
          case Of(item, answer) => select[Purchase, Rest](purchases, Where.Contains(List("item"), item), List(Order.desc("qty"), Order.by("id"))).flatMap(xs => k(answer(xs)))
          case Close(id, answer) => fetch[Purchase, Rest](purchases, id).flatMap {
            case Some(x) => keep[Purchase, Rest](purchases, x.copy(open = false)).flatMap(_ => k(answer(true)))
            case None => k(answer(false))
          }
        }
      })
  }
}

/** THE PROGRAM every backend must answer alike, and its expected answer */
object Programs {
  def ids(): () => String = { var n = 0; () => { n += 1; f"p$n%03d" } }
  type Out = (List[Purchase], List[Purchase], Boolean, Boolean, List[Purchase])
  final class Of[F <: Runner](val fx0: F) {
    import fx0._
    def purchasing[P[_]](implicit in: In[Purchases, P]): Out ! P = {
      def place(item: String, qty: Int): Purchase ! P = perform[Purchases].apply[P, Purchase](Purchases.Place(item, qty, x => x))
      for {
      a <- place("tea", 2); b <- place("green tea", 5); _ <- place("coffee", 1)
      open <- perform[Purchases].apply[P, List[Purchase]](Purchases.Open(x => x))
      teas <- perform[Purchases].apply[P, List[Purchase]](Purchases.Of("TEA", x => x))
      closed <- perform[Purchases].apply[P, Boolean](Purchases.Close(a.id, x => x))
      again <- perform[Purchases].apply[P, Boolean](Purchases.Close("nope", x => x))
      open2 <- perform[Purchases].apply[P, List[Purchase]](Purchases.Open(x => x))
    } yield (open, teas, closed, again, open2)
    }
  }
  val expected: Out = (
    List(Purchase("p001", "tea", 2, true), Purchase("p002", "green tea", 5, true), Purchase("p003", "coffee", 1, true)),
    List(Purchase("p002", "green tea", 5, true), Purchase("p001", "tea", 2, true)), true, false,
    List(Purchase("p002", "green tea", 5, true), Purchase("p003", "coffee", 1, true)))
}

abstract class DataChecks(val fx0: Runner, name: String) extends munit.FunSuite {
  import fx0._
  val datas: Datas[fx0.type] = new Datas[fx0.type]()(fx0)
  val stores: Stores[fx0.type] = new Stores[fx0.type]()(fx0)
  type D[X] = Sum[Void, Data, X]
  type DP[X] = Sum[D, Purchases, X]
  type DS[X] = Sum[D, Store, X]
  type S[X] = Sum[Void, Store, X]

  test(s"$name: Where, the reference: paths, equality, text within leaves, an element, less and greater, the connectives") {
    val d = Json.obj("a" -> Json.obj("b" -> Json.Str("Hello"), "n" -> Json.num(3)), "tags" -> Json.arr(Json.Str("x"), Json.Str("y")), "ns" -> Json.arr(Json.num(1), Json.obj("c" -> Json.Str("deep"))))
    import Where._
    assert(holds(Eq(List("a", "b"), Json.Str("Hello")), d) && !holds(Eq(List("a", "b"), Json.Str("hello")), d) && !holds(Eq(List("zz"), Json.Null), d))
    assert(holds(Contains(List("a"), "ELL"), d) && holds(Contains(List("ns"), "dee"), d) && holds(Contains(Nil, "3"), d) && !holds(Contains(List("a"), "b"), d), "keys are not text")
    assert(holds(Has(List("tags"), Json.Str("y")), d) && !holds(Has(List("tags"), Json.Str("z")), d) && !holds(Has(List("a"), Json.Str("y")), d))
    assert(holds(Less(List("a", "n"), Json.num(4)), d) && holds(Greater(List("a", "n"), Json.num(2)), d) && !holds(Less(List("zz"), Json.num(4)), d))
    assert(holds(and(Eq(List("a", "n"), Json.num(3)), Not(Has(List("tags"), Json.Str("z")))), d) && holds(or(Eq(List("a", "n"), Json.num(9)), All), d) && !holds(Or(Nil), d))
    assertEquals(Order.sort(List(("1", Json.obj("k" -> Json.num(2))), ("2", Json.obj()), ("3", Json.obj("k" -> Json.num(1)))), List(Order.by("k"))).map(_._1), List("2", "3", "1"))
    assertEquals(Order.sort(List(("1", Json.obj("k" -> Json.num(2))), ("2", Json.obj()), ("3", Json.obj("k" -> Json.num(1)))), List(Order.desc("k"))).map(_._1), List("1", "3", "2"))
  }
  test(s"$name: a domain of one's own on Data: the same answers in memory and on lines") {
    val onMemory = new Purchases.OnData[fx0.type](datas, Programs.ids())
    val (state, inMemory) = fx0.run(datas.inMemory()(onMemory[DP, D, Programs.Out](new Programs.Of[fx0.type](fx0).purchasing[DP])))
    assertEquals(inMemory, Programs.expected)
    assertEquals(state("purchases").size, 3)
    var files = Map.empty[String, List[String]]
    val onLines = new Purchases.OnData[fx0.type](datas, Programs.ids())
    val lined = fx0.run(datas.onLines(f => files.getOrElse(f, Nil), (f, l) => files = files.updated(f, files.getOrElse(f, Nil) :+ l))(onLines[DP, D, Programs.Out](new Programs.Of[fx0.type](fx0).purchasing[DP])))
    assertEquals(lined, Programs.expected)
    assertEquals(files("purchases.jsonl").size, 4, "three puts and the close's put")
    // a delete is a tombstone, the last line of a key wins
    val after = fx0.run(datas.onLines(f => files.getOrElse(f, Nil), (f, l) => files = files.updated(f, files.getOrElse(f, Nil) :+ l))(
      datas.delete[D]("purchases", "p002").flatMap(_ => datas.count[D]("purchases"))))
    assertEquals(after, 2L)
  }
  test(s"$name: the store translated to Data answers as the store in memory, on both backends of Data") {
    val (_, reference) = fx0.run(stores.keeping(Kept.empty(List(StorePrograms.passport)), StorePrograms.ids(), StorePrograms.clock())(new StorePrograms.Of[fx0.type](fx0).program[S]))
    val initial = Map("kinds" -> Map(StorePrograms.passport.id.value -> Kind.shape.encode(StorePrograms.passport)))
    val (_, onMemory) = fx0.run(datas.inMemory(initial)(stores.onData(StorePrograms.ids(), StorePrograms.clock())[DS, D, StorePrograms.Out](new StorePrograms.Of[fx0.type](fx0).program[DS])))
    assertEquals(onMemory, reference)
    var files = Map("kinds.jsonl" -> List(Json.print(Json.obj("key" -> Json.Str("passport"), "doc" -> Kind.shape.encode(StorePrograms.passport)))))
    val onLines = fx0.run(datas.onLines(f => files.getOrElse(f, Nil), (f, l) => files = files.updated(f, files.getOrElse(f, Nil) :+ l))(
      stores.onData(StorePrograms.ids(), StorePrograms.clock())[DS, D, StorePrograms.Out](new StorePrograms.Of[fx0.type](fx0).program[DS])))
    assertEquals(onLines, reference)
    assert(files.keySet == Set("kinds.jsonl", "versions.jsonl", "retired.jsonl", "events.jsonl", "marks.jsonl"), files.keySet.toString)
  }
  test(s"$name: atomically: a transaction sees its own writes, the backend sees them when it ends, nothing when it ends early; on memory and on lines; compacted") {
    import fx0.std._
    type Er[X] = Sum[Void, ({ type L[x] = Error[String, x] })#L, X]
    type E[X] = Sum[Er, Data, X]
    val a = Json.obj("n" -> Json.num(1)); val b = Json.obj("n" -> Json.num(2)); val c = Json.obj("n" -> Json.num(3))
    def inside[R[_]](implicit in: In[Data, R]) = for {
      _ <- datas.put[R]("t", "a", a); _ <- datas.put[R]("t", "b", b); _ <- datas.delete[R]("t", "x")
      seen <- datas.get[R]("t", "a"); gone <- datas.get[R]("t", "x")
      found <- datas.query[R]("t", Where.Greater(List("n"), Json.num(0)), List(Order.desc("n")), Some(2))
      n <- datas.count[R]("t", Where.All); key <- datas.fresh[R]("t")
    } yield (seen, gone, found.map(_._1), n, key)
    val initial = Map("t" -> Map("x" -> Json.obj("n" -> Json.num(9)), "c" -> c))
    val memory = new Memory(initial)
    val out = fx0.run(datas.atomically(memory)[D, Void, (Option[Json], Option[Json], List[String], Long, String)](inside[D]))
    assertEquals(out, (Some(a), None, List("c", "b"), 3L, "000000000001"))
    assertEquals(memory.state("t"), Map("a" -> a, "b" -> b, "c" -> c))
    // ended early: an error attempted outside drops the log — nothing written, the key spent
    val fresh = new Memory(initial)
    val early = fx0.run(attempt[String](datas.atomically(fresh)[E, Er, Unit](inside[E].flatMap(_ => raise[String, E, Unit]("no")))))
    assertEquals(early, Left("no")); assertEquals(fresh.state("t"), initial("t")); assertEquals(fresh.state("_keys")("t"), Json.obj("next" -> Json.num(1)))
    var files = Map("t.jsonl" -> initial("t").toList.map { case (k, d) => Json.print(Json.obj("key" -> Json.Str(k), "doc" -> d)) })
    val lines = new Lines(f => files.getOrElse(f, Nil), (f, l) => files = files.updated(f, files.getOrElse(f, Nil) :+ l))
    assertEquals(fx0.run(datas.atomically(lines)[D, Void, (Option[Json], Option[Json], List[String], Long, String)](inside[D])), out)
    assertEquals(files("t.jsonl").size, 5, "two kept, two puts and a delete appended")
    // compacted: one line per key kept
    lines.compact(List("t"), (f, ls) => files = files.updated(f, ls))
    assertEquals(files("t.jsonl").size, 3); assertEquals(files("_keys.jsonl").size, 1)
    assertEquals(fx0.run(datas.on(lines)(datas.query[D]("t", Where.All, List(Order.by("n"))).map(_.map(_._1)))), List("a", "b", "c"))
    // a count within sees the log, the backend is written once at the end
    val m2 = new Memory()
    val counted = fx0.run(datas.atomically(m2)[D, Void, Long](datas.put[D]("t", "a", a).flatMap(_ => datas.count[D]("t"))))
    assertEquals(counted, 1L); assertEquals(m2.state("t").keySet, Set("a"))
  }
  test(s"$name: Lines reads a file once: the cache answers after, a put or a delete changes it, reload sees another writer") {
    var files = Map("t.jsonl" -> List(Json.print(Json.obj("key" -> Json.Str("a"), "doc" -> Json.obj("n" -> Json.num(1))))))
    var reads = 0
    val lines = new Lines(f => { reads += 1; files.getOrElse(f, Nil) }, (f, l) => files = files.updated(f, files.getOrElse(f, Nil) :+ l))
    val program = for {
      n1 <- datas.count[D]("t"); _ <- datas.put[D]("t", "b", Json.obj("n" -> Json.num(2))); n2 <- datas.count[D]("t")
      _ <- datas.delete[D]("t", "a"); keys <- datas.query[D]("t").map(_.map(_._1)); k1 <- datas.fresh[D]("t"); k2 <- datas.fresh[D]("t")
    } yield (n1, n2, keys, k1, k2)
    assertEquals(fx0.run(datas.on(lines)(program)), (1L, 2L, List("b"), "000000000001", "000000000002"))
    assertEquals(reads, 2, "t.jsonl once, _keys.jsonl once")
    // another writer's line is not seen until reload
    files = files.updated("t.jsonl", files("t.jsonl") :+ Json.print(Json.obj("key" -> Json.Str("c"), "doc" -> Json.obj("n" -> Json.num(3)))))
    assertEquals(fx0.run(datas.on(lines)(datas.count[D]("t"))), 1L)
    lines.reload()
    assertEquals(fx0.run(datas.on(lines)(datas.count[D]("t"))), 2L); assertEquals(reads, 3)
    lines.compact(List("t"), (f, ls) => files = files.updated(f, ls))
    assertEquals(files("t.jsonl").size, 2); assertEquals(fx0.run(datas.on(lines)(datas.count[D]("t"))), 2L); assertEquals(reads, 4, "the counts for _keys, read once more after the reload")
  }
}
class DataOnMachine extends DataChecks(Machine, "machine")
class DataOnFree extends DataChecks(Free, "free")
