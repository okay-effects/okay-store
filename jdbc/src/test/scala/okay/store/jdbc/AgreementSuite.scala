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

  val passport: Kind = Kind(KindId("passport"), "Passport", Schema.Record(Vector(
    Schema.Field("number", Schema.Text(Nil), Some("Number"), None, true, None), Schema.Field("country", Schema.Text(Nil), Some("Country"), None, false, None))),
    Set("number"), List("country", "number"), Json.obj("contextual" -> Json.Bool(true)))
  val note: Kind = Kind(KindId("note"), "Note", Schema.Record(Vector(Schema.Field("text", Schema.Text(Nil), Some("Text"), None, false, None))))
  def ids(): () => String = { var n = 0; () => { n += 1; s"id$n" } }
  def clock(): () => Long = { var t = 1000L; () => { t += 1; t } }

  type Out = (List[Kind], List[Record], List[Record], List[Record], Option[Record], List[Record], List[Record], (List[Event], Int), Int)
  /** define, add two, revise, find by text, by tag, by both, history, retire, get the retired, the journal, save, the count after */
  val program: Out ! S = for {
    _ <- define[S](passport); _ <- define[S](note); _ <- define[S](passport.copy(name = "Passport (travel)"))
    r <- add[S](passport.id, Json.obj("number" -> Json.Str("AB123"), "country" -> Json.Str("UA")), Json.obj("x" -> Json.num(1)), Set("travel", "old"), None)
    _ <- add[S](note.id, Json.obj("text" -> Json.Str("Ab12 is a number to keep")), Json.Obj.empty, Set("misc"), Some("a note"))
    _ <- revise[S](r.id, Json.obj("number" -> Json.Str("AB124"), "country" -> Json.Str("UA")), r.meta, Set("travel"), Some("renewed"))
    _ <- audit[S](r.id, "number", "owner")
    ks <- kinds[S]; byText <- find[S]("ab12"); byTag <- find[S]("", Some("travel")); byBoth <- find[S]("ab12", Some("misc"))
    h <- history[S](r.id); _ <- retire[S](r.id, Some("lost")); gone <- get[S](r.id); all <- find[S]("")
    j <- journaled[S]; _ <- saved[S]; j2 <- journaled[S]
  } yield (ks, byText, byTag, byBoth, gone, h, all, j, j2._2)

  test(s"$name: the same answers in memory and on SQLite") {
    val (_, inMemory) = fx0.run(keeping(Kept.empty(List(passport)), ids(), clock())(program))
    val c = Sqlite.open(":memory:", List(passport))
    val onSqlite = try fx0.run(jdbcs.on(c, ids(), clock())(program)) finally c.close()
    assertEquals(onSqlite, inMemory)
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
}
class AgreementOnMachine extends AgreementChecks(Machine, "machine")
class AgreementOnFree extends AgreementChecks(Free, "free")
