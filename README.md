# okay-store — journaled, versioned records typed by a schema, as an effect

A `Kind` is a `Schema` with a name; a `Record` is a versioned fact of a kind — its `fields` by the schema, `meta` the
application's own, tags, a note — and a change is a new version with its parent. What happened is the `Journal`: a
header line, then an event a line, appended and never rewritten; `Kept` is the journal folded. The effect `Store`
asks and changes it (`kinds`, `find`, `get`, `history`, `define`, `add`, `revise`, `retire`, `audit`, `journaled`,
`saved`); `Stores.keeping` is its handler in memory; `Stores.describe` is the effect described, so
[okay-faces](https://github.com/okay-effects/okay-faces) serves it as REST resources or MCP tools, and performs it
from afar — a program performs `Store` in the browser and it happens on the server.

`okay-store-jdbc` is the same effect on a database: `Sqlite.open(path, kinds)` and `Jdbcs.on(connection, ids, clock)`, a
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
