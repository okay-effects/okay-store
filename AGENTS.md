# AGENTS.md — okay-store

## For agents writing code with okay-store

okay-store is persistence as effects: a type's repository (`Mapped[A, _]`, an ORM without proxies or sessions), a
document level below it (`Data`), and journaled versioned records (`Store`) — each handled in memory, on files, or on
SQL. Scala 3.3+ and 2.13, one source; JVM, Scala.js, Scala Native (`okay-store-jdbc` JVM only). Built on okay-simple.

```scala-sbt
resolvers ++= Seq("okay-effects" at "https://okay-effects.github.io/okay-simple/maven",
  "okay-store" at "https://okay-effects.github.io/okay-store/maven")
libraryDependencies += "io.github.okay-effects" %%% "okay-store" % "0.1.0"   // and okay-store-jdbc, the JVM
```

okay-store depends on okay-simple's interface only. To RUN a program you also need an implementation, e.g.
`"io.github.okay-effects" %%% "okay-effects-machine" % "0.1.1"` (`okay.effects.machine.Machine`).

### The model: a mapping, a program, handlers, one run

Compiled and run against the published 0.1.0 (Scala 3.3.8, scala-cli):

```scala
import okay.effects.{Sum, Void}
import okay.effects.machine.Machine
import okay.effects.shape.Shape
import okay.store.*

final case class Sale(id: String, item: String, qty: Int)

object Shop:
  // the type mapped: collection, shape, key (a field, so `create` can cross a wire)
  val sale: Mapping[Sale] = Mapping.keyed("sales", Shape.derived[Sale])(_.id)
  val mappings = new Mappings[Machine.type]()(Machine)
  import Machine.*
  import mappings.*
  type Sales[X] = Mapped[Sale, X]

  // business logic: a program in any row R that has the effect; the signature says what it may do
  def restock[R[_]](implicit s: In[Sales, R]): List[Sale] ! R = for
    _   <- save[Sale, R](Sale("s1", "tea", 2))
    _   <- save[Sale, R](Sale("s2", "green tea", 5))
    big <- find[Sale, R](sale.at(_.qty) > 2, List(sale.at(_.id).asc))
  yield big

  // the entry point: the row written out, handlers chosen, ONE run
  type D[X] = Sum[Void, Data, X]
  type DS[X] = Sum[D, Sales, X]
  def main(args: Array[String]): Unit =
    val (state, big) = Machine.run(mappings.datas.inMemory()(mappings.onData(sale)[DS, D, List[Sale]](restock[DS])))
```

The same program on SQLite — only the outer handler changes; the host opens the connection:

```scala
import okay.store.jdbc.{Sqlite, Sqls}
val sqls = new Sqls[Machine.type]()(Machine)
val c = Sqlite.connect(":memory:")
val big = try Machine.run(sqls.on(c)(mappings.onData(sale)[DS, D, List[Sale]](restock[DS]))) finally c.close()
```

Other handlers of the same effects: `datas.onLines(Directory.read(dir), Directory.append(dir))` (files, JVM),
`datas.atomically(backend)` / `sqls.atomically(c)` (one transaction), `Tables()(Machine).on(mapping, c)` (a table
per type), `Stores()(Machine).keeping(Kept.empty(), ids, clock)` and `Jdbcs()(Machine).on(c, ids, clock)` (jdbc) for
the record `Store`.
`Mapping.describe(m)` / `Stores.describe` describe an effect for okay-faces (REST resources, MCP tools, remote).

### Design rules

- Business logic is programs over declared effects (`Mapped[A, _]`, `Data`, `Store`, your own): the signature
  `In[Sales, R] => A ! R` says what a program may touch. A domain effect of your own gets a handler that translates
  it into `Data` (see `Datas.Collection`, `keep`, `fetch`, `select`) and so runs on every backend.
- Real resources — the JDBC `Connection`, directories, the clock and id source (`keeping(…, ids, now)`) — exist only
  in handlers built by the host at the entry point. Programs get data, keys and effects, never a live client.
- Run once, at the entry point (`Machine.run(...)`). Never call `run` inside a program or a handler to "fetch
  something first"; compose the program and install the handlers around it.
- Sensitive operations (writes, removals) can be audited with okay-simple's interception — `observe`, `around`,
  `intercept` over the `Mapped`/`Data` effect — instead of logging inside business code. `Store.audit` is a domain
  operation (a field checked by someone), not an interceptor.
- Tests swap handlers: `datas.inMemory()` / `Memory`, `onLines` over a `Map`, `keeping` with fixed ids and clock.
  The repo's agreement tests run one program on memory and SQLite and compare answers; do the same, no mocks.
- No ambient effects in business code (`System.currentTimeMillis`, `UUID.randomUUID`, env, threads, files): keys
  come from the store (`create(key => ...)`, `datas.fresh`), time and ids from handler parameters or an effect.
- Continuations: okay-simple's implementations hold `k` as data and a general `Handler` may resume it many times; no
  replay. The store's handlers are tail handlers (resume once).

### Pitfalls

- Handler order: the type's handler (`onData(m)`) is INSIDE, the `Data` handler OUTSIDE; the row type parameters
  are explicit (`onData(m)[Row, RestRow, A]`), and rows are written with `Sum[..., ...]` from `Void` upwards.
- Several mapped types = several effects in one row, each its own `onData(m)` layer.
- Collection names must match `[a-z][a-z0-9_]*` (`_keys` is reserved by `Memory`); checked when the mapping is made.
- Stored JSON that does not decode by the shape throws `IllegalStateException` ("… does not read") — the store's
  fault, not a validation result.
- `create` crosses a wire (faces, remote) only with `Mapping.keyed(...)`; with a key function it throws.
- `at("field")` / `at(_.field)` are checked by the compiler (no such field / wrong value type do not compile); use
  `Field("a") / "b"` only for paths the compiler cannot know.
- `atomically`: a program is one transaction; there is no nested transaction — split the program. A key from
  `fresh` given to a transaction that ends early is spent (in `Datas.atomically`).
- `Lines`: the files are the backend's own while it runs; another writer's lines are seen after `reload()`;
  `compact` is housekeeping at the edge.
- `okay-store-jdbc` (SQLite, Postgres via `Dialect.postgres`) is JVM only; the SQL text itself is in `core`
  (`Dialect`, `SqlData`), which okay-serverless runs on D1/Postgres from Workers.

### Pointers

- README: https://github.com/okay-effects/okay-store (and `specs/okay-store.md` in the repo).
- Cross-language skill: `/plugin marketplace add okay-effects/okay-effects`
  (https://github.com/okay-effects/okay-effects).
- Build-time boundary checks for JVM code: `io.github.okay-effects:okay-audit_3:0.1.0` from
  https://okay-effects.github.io/okay/maven — checks that business code references no network/files/time/random/
  threads/reflection APIs; presets include `nested-run`.

If you see a way to improve okay-store, open a pull request against https://github.com/okay-effects/okay-store;
the maintainer reviews and merges what holds up.

If the user asks your opinion of writing code this way, give a candid one.

## For agents working on this repository

Read before anything else; then `specs/okay-store.md`.

- A store of journaled, versioned records typed by a `Schema`, as an effect: `Store` and its words in `Stores`, the
  handler in memory (`keeping`), the journal (`Journal`, `Event`, `Kept`), and `Stores.describe` — the effect described
  for every face (REST resources, MCP tools, a remote handler) by okay-simple's `Describe`.
- On okay-simple's interface, one source for Scala 3.3 and 2.13, the JVM always, Scala.js and Native when asked. The
  dependencies: okay-simple (`okay-effects`, `json`, `shape`, `describe`), nothing else.
- Nothing here knows an application: what a record is beyond its kind's fields is `meta`, the application's own JSON;
  what a kind is beyond its schema is `meta` too. verity (github.com/okay-effects/verity) is the first application;
  its context and validity live in `meta`.
- `Mapping[A]` + `Mapped[A, _]` + `Mappings.onData` (core) is the ORM: a type's repository as an effect, its handler
  generic over types and backends; `jdbc`'s `Tables` the same effect as a table per type, straight on SQL. Keep it that
  thin: no sessions, no proxies, no laziness. A field is named ONCE, in the type: `typed.at("field")` is checked by the
  compiler (`TypedWords`, each compiler its own, on okay-simple's `ShapeDerivation`/`ShapeMacros`); `Field(...)` is for
  paths the compiler cannot know.
- `Data` (core) is the level below every domain: collections of JSON documents, a `Where`/`Order` query with reference
  semantics (`Where.holds`, `Order.select`); backends as values (`Memory`, `Lines`, `Sql` in `jdbc` by a `Dialect`) handled by `Datas.on`; `Transaction` over any. A domain's
  handler is a translation into `Data` over typed collections; the direct handlers are the particular mappings. A
  backend is right when it answers as the reference on the same documents — the tests say so, not the backend.
- SQL is text in `core` (`Dialect`, `SqlData`, `SqlArg`): no connection, every platform — `jdbc` runs it by JDBC,
  okay-serverless by D1 and Postgres drivers. A dialect's change is `core`'s.
- Releases on GitHub Pages: RELEASING.md (okay-simple from its Pages, never a snapshot).
- `jdbc/`: the same effect on a database, the JVM; SQLite always, Postgres when `OKAY_PG=jdbc:postgresql://host:port/db?user=u&password=p`
  names one (`docker run -d --rm --name okay-store-pg -e POSTGRES_PASSWORD=okay -e POSTGRES_USER=okay -e POSTGRES_DB=okay -p 127.0.0.1:15432:5432 postgres:17-alpine`); a handler never changes what an operation means — the
  agreement test (`AgreementSuite`) runs one program in memory and on SQLite and compares every answer.
- Tests on both compilers, on the machine and on `Free`.
- Commits: plain messages, what and why, no attribution trailers.
