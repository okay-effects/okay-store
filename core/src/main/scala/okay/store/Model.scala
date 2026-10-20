package okay.store

import okay.effects.json.Json
import okay.effects.shape.{Schema, Shape}
import okay.effects.shape.Shape.{any, field, long, record, text}

/** A KIND OF RECORD: its fields as a `Schema` — written in code or read from the journal — which fields are secret,
 * which name a record in a list (`summary`), and `meta`, whatever the application says about the kind */
final case class KindId(value: String)
final case class Kind(id: KindId, name: String, schema: Schema, secret: Set[String] = Set.empty, summary: List[String] = Nil, meta: Json.Obj = Json.Obj.empty) {
  /** the record's fields as a shape: the form, the validation */
  def shape: Shape[Json] = Shape.Loose(schema)
  /** a form for revising: the same shape, each field's default the current value */
  def shapeWith(current: Json.Obj): Shape[Json] = Shape.Loose(Kind.withDefaults(schema, current))
}
object Kind {
  def withDefaults(s: Schema, current: Json.Obj): Schema = s match {
    case Schema.Record(fields) => Schema.Record(fields.map(f => f.copy(default = current.get(f.name).orElse(f.default))))
    case Schema.Described(of, l, d) => Schema.Described(withDefaults(of, current), l, d)
    case other => other
  }
  val id: Shape[KindId] = text.map(KindId(_))(_.value)
  val shape: Shape[Kind] = record(
    field("id", id), field("name", text), field("schema", Schema.shape),
    field("secret", text.many()).withDefault(Nil), field("summary", text.many()).withDefault(Nil), field("meta", Record.obj).withDefault(Json.Obj.empty))(
    (id, name, schema, secret, summary, meta) => Kind(id, name, schema, secret.toSet, summary, meta))(
    k => (k.id, k.name, k.schema, k.secret.toList.sorted, k.summary, k.meta))
}

final case class RecordId(value: String)
final case class VersionId(value: String)
/** A VERSIONED FACT: `fields` by its kind's schema, `meta` whatever the application keeps beside them, tags and a note;
 * a change is a new version with `parent` the old, and the versions of one `RecordId` form a chain */
final case class Record(id: RecordId, version: VersionId, parent: Option[VersionId], kind: KindId, fields: Json.Obj,
                        meta: Json.Obj = Json.Obj.empty, tags: Set[String] = Set.empty, note: Option[String] = None, at: Long)
object Record {
  val id: Shape[RecordId] = text.map(RecordId(_))(_.value)
  val version: Shape[VersionId] = text.map(VersionId(_))(_.value)
  val obj: Shape[Json.Obj] = any.refine[Json.Obj] { case o: Json.Obj => Right(o); case other => Left(s"expected a record, got ${Json.kind(other)}") }(o => o)
  val tags: Shape[Set[String]] = text.many().map(_.toSet)(_.toList.sorted)
  val shape: Shape[Record] = record(
    field("id", id), field("version", version), field("parent", version.optional), field("kind", Kind.id), field("fields", obj),
    field("meta", obj).withDefault(Json.Obj.empty), field("tags", tags).withDefault(Set.empty[String]), field("note", text.optional), field("at", long))(
    (id, version, parent, kind, fields, meta, tags, note, at) => Record(id, version, parent, kind, fields, meta, tags, note, at))(
    r => (r.id, r.version, r.parent, r.kind, r.fields, r.meta, r.tags, r.note, r.at))
}
