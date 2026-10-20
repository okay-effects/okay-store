# okay-store — journaled, versioned records typed by a schema, as an effect

A `Kind` is a `Schema` with a name; a `Record` is a versioned fact of a kind — its `fields` by the schema, `meta` the
application's own, tags, a note — and a change is a new version with its parent. What happened is the `Journal`: a
header line, then an event a line, appended and never rewritten; `Kept` is the journal folded. The effect `Store`
asks and changes it (`kinds`, `find`, `get`, `history`, `define`, `add`, `revise`, `retire`, `audit`, `journaled`,
`saved`); `Stores.keeping` is its handler in memory; `Stores.describe` is the effect described, so
[okay-faces](https://github.com/okay-effects/okay-faces) serves it as REST resources or MCP tools, and performs it
from afar — a program performs `Store` in the browser and it happens on the server.

TYPES MAPPED, the whole of an ORM on effects: `Mapping[A](collection, shape, key)` makes `Mapped[A, _]` the type's own
effect — `save`, `load`, `remove`, `find(mapping.at(_.qty) > 2)` with the field selected by a lambda or named, checked by the compiler and typed, `count` — and `Mappings.onData(mapping)` is its handler on any
backend of `Data`, one handler for every type; no proxies, no sessions, a value is a value. `okay-store-jdbc`'s `Tables.on(mapping, connection)`
is the same effect as a table per type — the shape's fields the columns, nested values JSON columns — readable by the database's own tools.
`Mapping.describe(mapping)` describes the type's effect for the faces: REST resources, MCP tools, performed from afar, with nothing more written.

THE LEVEL BELOW: `Data` is collections of JSON documents by key with a query every store can answer (`Where`, `Order`), and
its backends — memory, lines appended to files, SQL by a dialect in `okay-store-jdbc` (SQLite checked), a document or
key-value store the same way. A domain effect — `Store`, or an application's own — is handled by a translation into `Data`
written once over typed collections (`Datas.Collection`, `keep`, `fetch`, `select`), and runs on every backend; the
agreement tests say the backends answer alike.

`okay-store-jdbc` is also the same effect on a database directly: `Sqlite.open(path, kinds)` and `Jdbcs.on(connection, ids, clock)`, a
row per version with the record's JSON beside the columns a query needs, the events table the journal; the agreement
test runs one program on both handlers and gets the same answers.

Scala 3.3 LTS and 2.13, one source; the JVM, Scala.js and Scala Native (`jdbc` the JVM). On [okay-simple](https://github.com/okay-effects/okay-simple)
alone. The first application is [verity](https://github.com/okay-effects/verity).

```scala
val stores = new Stores[Machine.type]()(Machine)
import stores._
val program = define[S](passport).flatMap(_ => add[S](passport.id, fields)).flatMap(r => history[S](r.id))
val (kept, versions) = Machine.run(keeping(Kept.empty(), ids, clock)(program))
```
