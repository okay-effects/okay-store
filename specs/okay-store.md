# okay-store — the store as an effect

Extracted from verity 2026-10-20: what was verity's `Store`, `Vault`, `Journal`, `Event`, `Kind`, `Record`, with
what was verity's own taken out and put into `meta`.

## The model

- `Kind(id, name, schema, secret, summary, meta)`: the fields as a `Schema`; which are secret; which name a record in a
  list; `meta`, the application's JSON about the kind (verity: `{"contextual": true}`).
- `Record(id, version, parent, kind, fields, meta, tags, note, at)`: `fields` by the kind's schema; `meta`, the
  application's JSON beside them (verity: context and validity); tags and a note, which `find` searches.
- `Event`: `KindDefined`, `KindRevised`, `RecordAdded`, `RecordRevised`, `RecordRetired`, `Accessed(id, field, by, at)` —
  the last is the audit: who saw which field. Every event has `at`.
- `Journal`: `Header(format, version, created)` as the first line, then an event a line; `read` reports a damaged line
  by number and skips it; `write`.
- `Kept(kinds, versions, retired, events, unsaved)`: the fold; `Kept.empty(kinds)` with an application's built-in kinds.

DECIDED: `meta` rather than type parameters. A store parametric in what a record is would make every face generic in
it too; JSON with the application's own shapes over it costs one decode at the application's edge and nothing in the
library.

## The effect

`Store` with eleven operations; `Stores[F]` the words and `keeping(initial, ids, now)`, the handler in memory with ids
and the clock given so a test is deterministic. `find(text, tag)`: text in the fields, tags and note, lowercased;
a tag that must be present.

`Stores.describe: Describe[Store]` (okay-simple's `describe` module): every operation with its shapes, so `RestDomains` serves it
at `/store`, `McpFace.domain` as tools, and `remote` performs it from afar. A record's `meta` crosses as JSON.

## Data — the level below a domain (2026-10-20)

Three levels, each a handler's translation away from the next:

| level | what | who writes it |
|---|---|---|
| a domain | `Store`; an application's own (`Purchases` in the tests) | the application, once |
| `Data` | collections of JSON documents by key: `Get`, `Put`, `Delete`, `Query(where, order, limit)`, `Count` | the library |
| a backend | a handler of `Data`: memory, lines appended to files, SQL by a dialect; a document store, a key-value store the same way | the library, or whoever has the database |

A domain's handler is a TRANSLATION into `Data` — `Stores.onData`, `Purchases.OnData` — written once and run on every
backend; a backend written once runs every domain. `Datas.Collection[A](name, shape, key)` and its words `keep`,
`fetch`, `remove`, `select` are what a domain's handler is written in: a typed collection by a `Shape`, the backend's
JSON never seen. The direct handlers — `Stores.keeping` in memory, `jdbc`'s `Jdbcs.on` straight on SQLite — are the
particular mappings, faster where a query must otherwise be folded above the backend (the current version of each
id), and the reference every translation is checked against.

`Where` is the condition every backend can say: `Eq`, `Contains` (a text within any leaf under a path, ignoring case —
values, not names), `Has` (an element of an array), `Less`, `Greater`, `And`, `Or`, `Not`; `Order` by paths; a limit.
`Where.holds` and `Order.select` are the reference semantics; a backend's query means that or it is wrong, and the SQL
test compiles every form and compares with the reference on the same documents. What `Where` cannot say (a join, a
group) the translation does above the backend, in the program — honestly slower, and still right.

Backends built: `Datas.inMemory` (a cell), `Datas.onLines(read, append)` (a collection a file of lines appended,
`{"key", "doc"}` or `{"key", "gone": true}`, the last line of a key winning; `Directory.read/append` on the JVM),
`Sqls.on(connection, dialect)` in `jdbc` (a table `(key, doc)` per collection made on first use; `Dialect.sqlite`
checked — `json_extract`, `json_tree` for the leaves within, `json_each` for an array, `json_type` for JSON's null as
distinct from missing — `Dialect.postgres` a sketch, unchecked). Not built, and how they would go: MongoDB (`Where`
is a subset of its query language, one collection a collection), Redis or RocksDB (a key `collection:key`, `Query` a
scan with `Where.holds` — or an index the translation keeps), DynamoDB and Cassandra (a partition a collection, the
same scan). A graph or a search engine is another vocabulary, not a backend of this one.

DECIDED: `Data` over JSON documents, not over rows of columns. Every store of the kinds above holds a document or a
value; a column store holds it as JSON too, and `Shape` gives the columns when a backend wants them — `jdbc`'s
`Tables`, a table per type for a `Mapping`, is that, straight on SQL and not through `Data`. The cost is a JSON column where a relational purist wants columns; the gain is one
`Data` for all.

Agreement, checked on the machine and on Free: `StorePrograms` is one program of the store; `keeping`, `onData` on
memory, on lines, on SQLite, and `Jdbcs.on` answer it alike. `Programs` is one program of `Purchases`; memory, lines
and SQLite answer it alike. Compaction of the lines, transactions across operations, a cache over `onLines`: not done.

## Mapping — a type mapped, the whole of an ORM (2026-10-20)

A domain may be anything; what every domain needs first is its types in a store. `Mapping[A](collection, shape,
key)` says what a value of `A` is there — its collection, its `Shape` (the document, the columns, the validation),
its key — and that is all an ORM is here:

- `Mapped[A, _]` is the type's effect, the repository typed by `A`: `save`, `load`, `remove`, `find(where, order,
  limit)`, `all`, `count`, the words in `Mappings`. In a row it is `In[({ type L[X] = Mapped[A, X] })#L, R]`, one per
  type, as okay-simple's `State[S]`; a program over several types has several in its row.
- `Mappings.onData(mapping)` is the handler, ONE for every type: a save a put of the encoded value by its key, a find
  a query decoded by the shape. The backend is whatever handles `Data` next — memory, lines, SQLite, a document store.
- `Field("qty") > 2`, `Field("item") contains "tea"`, `Field("customer") is "ann"`, `Field("qty").desc`: a `Where` and an
  `Order` that read; `Field("a") / "b"` a path within.

On effects, not on proxies: no session, no identity map, no lazy loading, no change tracking. A value is a value; a
program says `save(a)` and where it goes is a handler chosen at the edge; what is in the store and does not read by
the shape is the store's fault, said plainly, not a problem for a form. Relations are keys in fields and a second
`find`; a join is the program's, above the backend (DECIDED, as for `Data`).

Checked on the machine and on Free: two types (`Customer`, `Sale`), one program over both, the same answers in
memory, on lines, on SQLite through `Data`, and on SQLite as a table per type. Not done: a `Describe` of a
`Mapped[A]` for the faces (`find` would need a shape of `Where`), keys generated by the store.

### A table per type (`jdbc`'s `Tables`, 2026-10-20)

`Tables.on(mapping, connection, dialect)` handles `Mapped[A]` straight on SQL, no `Data` between: the shape's fields
are the columns — a leaf (text, integer, number, boolean, date, choice) a column of its type, `Optional` nullable,
anything nested (a record, a list, a sum, `any`) a JSON column — and `_key` the primary key, the mapping's key, kept
beside the fields since a key is a function and not always one field. A save is an upsert by columns; a find a
`WHERE` on them: a path's head a column, compared as a column when a leaf, by the dialect's JSON functions within
when nested; `Contains(Nil, t)` the text within every column; a path no column has holds for nothing. The table reads
with the database's own tools, as an ORM's would; `PRAGMA table_info` in the test says the columns.

The reference's order was mended by it: `Less` and `Greater` hold only between values of one kind — numbers,
strings, booleans — and never for a missing value or a null, as SQL's `NULL` and a type mismatch compare to nothing;
sorting stays total (missing and null first, then numbers, strings, booleans, the rest by JSON text). Not aligned
and said: SQLite orders a boolean kept as an integer among numbers, and compares text to a number by type class
where the reference says nothing holds; compare like with like.

## The store on a database (`jdbc`, 2026-10-20)

`okay-store-jdbc`, `okay.store.jdbc`, the JVM. `Sqlite.open(path, kinds)` makes the tables and puts the kinds given
where absent (built-in kinds, no event, as `Kept.empty(kinds)` has them); `Jdbcs.on(connection, ids, now)` is the
handler, a `TailHandler` answering in place, a statement or two per operation:

- `versions`: a row per version — the record's JSON by `Record.shape`, beside it `id`, `version`, `parent`, `kind`,
  `at`, `search` (the fields' text, tags and note lowercased) and `tags` (padded with spaces); the latest row of an id
  (`MAX(rowid)`) its current version unless the id is in `retired`. `find` is `instr` on `search` and `tags`, so no
  wildcard escaping; newest first by `at`, as in memory.
- `kinds`: the kind's JSON; `define` revises where the id is known, defines otherwise, with the event either way.
- `events`: the journal, appended at every write, the audit (`Accessed`) included, as the file has it; `marks` where
  `saved` last stood, so `journaled` counts the events not yet marked — the same number the handler in memory gives.

DECIDED: the same meaning on every handler, checked, not promised: `AgreementSuite` runs one program — kinds, adds,
a revision, an audit, finds by text, by tag, by both, the history, a retirement, the journal, a save — in memory and on
SQLite and compares every answer, on the machine and on Free; and reopens a database file to find what was written.
`Class.forName` loads the driver: sbt's class loader does not always serve it to `DriverManager`.

Not done: transactions across operations (each statement is its own; a `Saved` could become a commit under a
connection with autocommit off), other databases (the SQL is plain enough for H2 or Postgres with `instr` swapped).

## Not here

Encryption, merge of two journals: later, as handlers.
