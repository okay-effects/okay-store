package okay.store

import okay.effects.{Runner, Sum, Void}
import okay.effects.json.Json
import okay.effects.machine.Machine
import okay.effects.free.Free
import okay.effects.shape.{Day, Schema}
import okay.effects.describe.{Domains, Op}

/** the journal round trip and its fold; the store's handler; the store described, performed from afar */
abstract class StoreChecks(val fx0: Runner, name: String) extends munit.FunSuite {
  val stores: Stores[fx0.type] = new Stores[fx0.type]()(fx0)
  val domains: Domains[fx0.type] = new Domains[fx0.type]()(fx0)
  import fx0._
  import stores._
  type S[X] = Sum[Void, Store, X]

  val passport: Kind = Kind(KindId("passport"), "Passport", Schema.Record(Vector(
    Schema.Field("number", Schema.Text(Nil), Some("Number"), None, true, None), Schema.Field("country", Schema.Text(Nil), Some("Country"), None, false, None))),
    Set("number"), List("country", "number"), Json.obj("contextual" -> Json.Bool(true)))
  val r1: Record = Record(RecordId("r1"), VersionId("v1"), None, passport.id, Json.obj("number" -> Json.Str("AB1"), "country" -> Json.Str("UA")),
    Json.obj("valid" -> Json.obj("from" -> Json.Str("2020-01-02"))), Set("travel"), None, 1000L)
  val r2: Record = r1.copy(version = VersionId("v2"), parent = Some(VersionId("v1")), fields = Json.obj("number" -> Json.Str("AB2"), "country" -> Json.Str("UA")), at = 2000L)
  val events = List(Event.KindDefined(passport, 500L), Event.RecordAdded(r1), Event.RecordRevised(r2), Event.Accessed(r1.id, "number", "owner", 3000L))
  def ids(): () => String = { var n = 0; () => { n += 1; s"id$n" } }

  test(s"$name: events and the journal round trip; a damaged line reported and skipped; the fold") {
    events.foreach(e => assertEquals(Event.shape.decode(Event.shape.encode(e)), Right(e)))
    val lines = Journal.write(Journal.Header("test", 1, 1000L), events)
    val (h, es, damaged) = Journal.read(lines)
    assertEquals(h, Some(Journal.Header("test", 1, 1000L))); assertEquals(es, events); assertEquals(damaged, Nil)
    val (_, es2, d2) = Journal.read(lines.take(2) ++ List("{garbage") ++ lines.drop(2))
    assertEquals(es2, events); assertEquals(d2.map(_.line), List(3))
    val kept = Kept.of(events)
    assertEquals(kept.current(r1.id).map(_.version), Some(VersionId("v2"))); assertEquals(kept.history(r1.id).size, 2)
    assertEquals(kept.kinds.keySet, Set(passport.id)); assertEquals(kept.unsaved, 0)
    assertEquals(kept(Event.RecordRetired(r1.id, None, 4000L)).records, Nil)
  }
  test(s"$name: the handler: define, add, revise, find by text and tag, history, retire, audit, journaled, saved") {
    val program: (List[Record], List[Record], Option[Record], Int) ! S =
      define[S](passport).flatMap(_ => add[S](passport.id, Json.obj("number" -> Json.Str("AB123"), "country" -> Json.Str("UA")), Json.Obj.empty, Set("travel"), None)).flatMap { r =>
        revise[S](r.id, Json.obj("number" -> Json.Str("AB124"), "country" -> Json.Str("UA")), Json.Obj.empty, Set("travel", "new"), Some("renewed")).flatMap(_ =>
          audit[S](r.id, "number", "owner").flatMap(_ => find[S]("ab12").flatMap(found => find[S]("", Some("new")).flatMap(tagged =>
            history[S](r.id).flatMap(h => retire[S](r.id, Some("lost")).flatMap(_ => get[S](r.id).flatMap(gone => journaled[S].flatMap { case (_, n) => saved[S].map(_ => (found, h, gone, n)) })))))))
      }
    val (kept, (found, h, gone, n)) = fx0.run(keeping(Kept.empty(), ids(), () => 7L)(program))
    assertEquals(found.map(_.fields.get("number")), List(Some(Json.Str("AB124")))); assertEquals(h.size, 2); assertEquals(gone, None); assertEquals(n, 5)
    assertEquals(kept.unsaved, 0); assertEquals(kept.events.collect { case e: Event.Accessed => e.by }, List("owner"))
    assertEquals(fx0.run(keeping(kept, ids(), () => 8L)(find[S]("", Some("new"))))._2, Nil)
    // seen: what is kept after every change, as it stands — six changes, the last the handler's own end state
    var told: List[Kept] = Nil
    val (watched, _) = fx0.run(keeping(Kept.empty(), ids(), () => 7L, k => told = k :: told)(program))
    assertEquals(told.size, 6); assertEquals(told.head, watched); assertEquals(told.tail.head.unsaved, 5)
  }
  test(s"$name: described: the same program answers the same when the store is performed from afar") {
    val program: (List[Record], List[Record], Int) ! S =
      define[S](passport).flatMap(_ => add[S](passport.id, Json.obj("number" -> Json.Str("AB123"), "country" -> Json.Str("UA")), Json.obj("x" -> Json.num(1)), Set("t"), Some("n"))).flatMap { r =>
        revise[S](r.id, Json.obj("number" -> Json.Str("AB124"), "country" -> Json.Str("UA")), r.meta, r.tags, r.note).flatMap(_ => find[S]("AB12").flatMap(found =>
          history[S](r.id).flatMap(h => retire[S](r.id, None).flatMap(_ => journaled[S].map { case (_, n) => (found, h, n) }))))
      }
    val (_, direct) = fx0.run(keeping(Kept.empty(), ids(), () => 7L)(program))
    var kept = Kept.empty(); val serverIds = ids(); var carried: List[String] = Nil
    val transport: (Op[Store, _, _], Json) => Json ! Void = (o, args) => {
      carried = o.name :: carried
      val (k, r) = fx0.run(keeping(kept, serverIds, () => 7L)(domains.serve[Store, S](Stores.describe)(o.name, args)))
      kept = k; pure[Json, Void](r.fold(ps => throw new IllegalStateException(ps.toString), j => j))
    }
    val remote = fx0.run(domains.remote[Store](Stores.describe).apply[S, Void, (List[Record], List[Record], Int)](transport)(program))
    assertEquals(remote, direct)
    assertEquals(carried.reverse, List("define", "add", "revise", "find", "history", "retire", "journaled"))
    assertEquals(Stores.describe.operations.size, 12)
  }
}
abstract class AdoptChecks(val fx0: Runner, name: String) extends munit.FunSuite {
  val stores: Stores[fx0.type] = new Stores[fx0.type]()(fx0)
  import fx0._
  import stores._
  type S[X] = Sum[Void, Store, X]
  import StorePrograms.passport
  def ids(prefix: String): () => String = { var n = 0; () => { n += 1; s"$prefix$n" } }
  def adding(number: String): Record ! S =
    add[S](passport.id, Json.obj("number" -> Json.Str(number), "country" -> Json.Str("UA")))

  test(s"$name: every event its own identity, minted by the handler from its ids") {
    val (kept, _) = fx0.run(keeping(Kept.empty(List(passport)), ids("a"), () => 7L)(
      adding("A1").flatMap(r => audit[S](r.id, "number", "owner").flatMap(_ => retire[S](r.id, None)))))
    assertEquals(kept.events.map(_.eid.value), List("a3", "a4", "a5"))   // after the record's id and version
    assertEquals(kept.events.map(Event.identity), kept.events.map(_.eid))
    val lines = Journal.write(Journal.Header("test", 1, 7L), kept.events)
    assert(lines(1).contains("\"eid\":\"a3\""), lines(1))
    assertEquals(Journal.read(lines)._2, kept.events)
  }
  test(s"$name: a journal without ids reads; its identity a hash of what each event says, the same each time") {
    val old = List("""{"format":"test","version":1,"created":1}""",
      """{"type":"accessed","id":"r1","field":"number","by":"owner","at":5}""",
      """{"type":"accessed","id":"r1","field":"number","by":"owner","at":6}""")
    val (_, es, damaged) = Journal.read(old)
    assertEquals(damaged, Nil); assertEquals(es.map(_.eid), List(EventId.none, EventId.none))
    val first = es.map(Event.identity)
    assertEquals(Journal.read(old)._2.map(Event.identity), first)
    assert(first.forall(_.value.startsWith("h")) && first.distinct.size == 2, first.toString)
  }
  test(s"$name: adopt: two writers' journals, each taken in by the other — both have both; again, nothing more") {
    val (a, ra) = fx0.run(keeping(Kept.empty(List(passport)), ids("a"), () => 7L)(adding("A1")))
    val (b, rb) = fx0.run(keeping(Kept.empty(List(passport)), ids("b"), () => 8L)(adding("B1")))
    val file = a.events ++ b.events                     // A appended, then B
    val (b2, _) = fx0.run(keeping(b, ids("b"), () => 9L)(adopt[S](file)))
    assertEquals(b2.records.map(_.id).toSet, Set(ra.id, rb.id)); assertEquals(b2.events, file)
    assertEquals(b2.unsaved, 0)
    // B's alone: A's own kept after it, unsaved
    val (a2, _) = fx0.run(keeping(a, ids("a"), () => 9L)(adopt[S](b.events)))
    assertEquals(a2.events, b.events ++ a.events); assertEquals(a2.unsaved, 1)
    val (a3, _) = fx0.run(keeping(a2, ids("a"), () => 9L)(adopt[S](file ++ file)))
    assertEquals(a3.events, file); assertEquals(a3.unsaved, 0)   // an event once, by identity
  }
  test(s"$name: two revisions of one version from two writers: a fork, both in the history, the later current") {
    val (base, r) = fx0.run(keeping(Kept.empty(List(passport)), ids("a"), () => 7L)(adding("A1")))
    def revising(prefix: String, number: String) = fx0.run(keeping(base, ids(prefix), () => 8L)(
      revise[S](r.id, Json.obj("number" -> Json.Str(number), "country" -> Json.Str("UA")))))._1
    val (one, two) = (revising("x", "A2"), revising("y", "A3"))
    val kept = Kept.empty(List(passport)).adopt(one.events ++ two.events)
    assertEquals(kept.history(r.id).map(_.fields.get("number")), List("A3", "A2", "A1").map(n => Some(Json.Str(n))))
    assertEquals(kept.history(r.id).map(_.parent), List(Some(r.version), Some(r.version), None))
  }
}
class AdoptOnMachine extends AdoptChecks(Machine, "machine")
class AdoptOnFree extends AdoptChecks(Free, "free")
class StoreOnMachine extends StoreChecks(Machine, "machine")
class StoreOnFree extends StoreChecks(Free, "free")
