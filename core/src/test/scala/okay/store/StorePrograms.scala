package okay.store

import okay.effects.{Sum, Void}
import okay.effects.json.Json
import okay.effects.shape.Schema

/** ONE PROGRAM of the store every handler must answer alike: kinds, adds, a revision, an audit, finds by text, by tag,
 * by both, the history, a retirement, the journal, a save — shared by the agreement tests of every backend */
object StorePrograms {
  val passport: Kind = Kind(KindId("passport"), "Passport", Schema.Record(Vector(
    Schema.Field("number", Schema.Text(Nil), Some("Number"), None, true, None), Schema.Field("country", Schema.Text(Nil), Some("Country"), None, false, None))),
    Set("number"), List("country", "number"), Json.obj("contextual" -> Json.Bool(true)))
  val note: Kind = Kind(KindId("note"), "Note", Schema.Record(Vector(Schema.Field("text", Schema.Text(Nil), Some("Text"), None, false, None))))
  def ids(): () => String = { var n = 0; () => { n += 1; s"id$n" } }
  def clock(): () => Long = { var t = 1000L; () => { t += 1; t } }

  type Out = (List[Kind], List[Record], List[Record], List[Record], Option[Record], List[Record], List[Record], (List[Event], Int), Int)
  /** the program over an implementation: `new StorePrograms.Of(fx).program` */
  final class Of[F <: okay.effects.Runner with Singleton](val fx0: F) {
    val stores: Stores[fx0.type] = new Stores[fx0.type]()(fx0)
    import fx0._
    import stores._
    import StorePrograms.{passport, note}
    def program[S[_]](implicit in: In[Store, S]): StorePrograms.Out ! S = for {
      _ <- define[S](passport); _ <- define[S](note); _ <- define[S](passport.copy(name = "Passport (travel)"))
      r <- add[S](passport.id, Json.obj("number" -> Json.Str("AB123"), "country" -> Json.Str("UA")), Json.obj("x" -> Json.num(1)), Set("travel", "old"), None)
      _ <- add[S](note.id, Json.obj("text" -> Json.Str("Ab12 is a number to keep")), Json.Obj.empty, Set("misc"), Some("a note"))
      _ <- revise[S](r.id, Json.obj("number" -> Json.Str("AB124"), "country" -> Json.Str("UA")), r.meta, Set("travel"), Some("renewed"))
      _ <- audit[S](r.id, "number", "owner")
      ks <- kinds[S]; byText <- find[S]("ab12"); byTag <- find[S]("", Some("travel")); byBoth <- find[S]("ab12", Some("misc"))
      h <- history[S](r.id); _ <- retire[S](r.id, Some("lost")); gone <- get[S](r.id); all <- find[S]("")
      j <- journaled[S]; _ <- saved[S]; j2 <- journaled[S]
    } yield (ks, byText, byTag, byBoth, gone, h, all, j, j2._2)
  }
}
