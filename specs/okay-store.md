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

A backend is a VALUE, `Backend { def answer[X](op: Data[X]): X }`, handled by `Datas.on(backend)`: `Memory(initial)`
(its `state` read after), `Lines(read, append)` (a collection a file of lines appended, `{"key", "doc"}` or `{"key",
"gone": true}`, the last line of a key winning; `Directory.read/append/write` on the JVM; `compact(collections, write)`
rewrites a file as one line per key kept — housekeeping at the edge, by whoever holds the files), `Sql(connection,
dialect)` in `jdbc`; `Datas.inMemory`/`onLines` and `Sqls.on` are the words over them. `Transaction(under)` is a
backend over a backend (`Datas.atomically(backend)(p)`): `p`'s writes in a log, its reads through it, a query the
backend's rows with the log laid over in the reference's order, all written when `p` ends — so an error attempted
outside, or an abort, ending `p` early writes nothing; `Fresh` goes through at once, a key spent is never given
twice; nested, a transaction is over a transaction. The same on every backend, as okay-simple's `transaction` is
over its `Store`; a database's own transaction is a later speed, not a meaning. Checked on memory, lines and SQLite.

Backends built, then: `Memory`, `Lines`,
`Sqls.on(connection, dialect)` in `jdbc` (a table `(key, doc)` per collection made on first use; `Dialect.sqlite`
checked — `json_extract`, `json_tree` for the leaves within, `json_each` for an array, `json_type` for JSON's null as
distinct from missing; `Dialect.postgres` CHECKED against a Postgres 17 in Docker (`PostgresSuite`, run when
`OKAY_PG` names the database) — `jsonb` with `#>` and a `?::jsonb` compared as a value, `jsonb_path_query` with `.**`
for the leaves within, `@>` for an array's element, `jsonb_typeof` for JSON's null; a dialect says `cast` and `value`
for how a compared value travels, and `textContains` for a text column).

DECIDED, found on Postgres: a leaf's condition is `COALESCE((...), FALSE)` in every compiler of `Where`. SQL's logic is
three-valued — a predicate on a missing value is NULL, and `NOT` of it is NULL, so a row is lost — where the reference
is two-valued: a predicate on nothing is false, and its negation true. The reference tests say `Not(Eq(missing, v))`,
`Not(Has(missing, v))`, `Not(Less(...))`, `Not(Contains(...))` and hold on SQLite and on Postgres. Not built, and how they would go: MongoDB (`Where`
is a subset of its query language, one collection a collection), Redis or RocksDB (a key `collection:key`, `Query` a
scan with `Where.holds` — or an index the translation keeps), DynamoDB and Cassandra (a partition a collection, the
same scan). A graph or a search engine is another vocabulary, not a backend of this one.

DECIDED: `Data` over JSON documents, not over rows of columns. Every store of the kinds above holds a document or a
value; a column store holds it as JSON too, and `Shape` gives the columns when a backend wants them — `jdbc`'s
`Tables`, a table per type for a `Mapping`, is that, straight on SQL and not through `Data`. The cost is a JSON column where a relational purist wants columns; the gain is one
`Data` for all.

`Fresh` is the one operation the memory and the lines backends keep a count for; a document store or a key-value
store keeps it the same way, under a reserved name.

Agreement, checked on the machine and on Free: `StorePrograms` is one program of the store; `keeping`, `onData` on
memory, on lines, on SQLite, and `Jdbcs.on` answer it alike. `Programs` is one program of `Purchases`; memory, lines
and SQLite answer it alike.

`Lines` folds a file ONCE, at first use, into a cache a put or a delete keeps right (the line appended, the map
changed), the sequences likewise; so a read is a map's and the file is the journal. The files are the backend's own
while it runs — a `Lines` is one writer, as a journal has one — and another writer's lines are seen after `reload()`.
Checked by counting the reads.

## Mapping — a type mapped, the whole of an ORM (2026-10-20)

A domain may be anything; what every domain needs first is its types in a store. `Mapping[A](collection, shape,
key)` says what a value of `A` is there — its collection, its `Shape` (the document, the columns, the validation),
its key — and that is all an ORM is here:

- `Mapped[A, _]` is the type's effect, the repository typed by `A`: `save`, `load`, `remove`, `find(where, order,
  limit)`, `all`, `count`, the words in `Mappings`. In a row it is `In[({ type L[X] = Mapped[A, X] })#L, R]`, one per
  type, as okay-simple's `State[S]`; a program over several types has several in its row.
- `Mappings.onData(mapping)` is the handler, ONE for every type: a save a put of the encoded value by its key, a find
  a query decoded by the shape. The backend is whatever handles `Data` next — memory, lines, SQLite, a document store.
- `mapping.at(_.qty) > 2`, `mapping.at("item") contains "tea"`, `mapping.at(_.address).at(_.city) is "Kyiv"`, `pair.at(_.a.city)`,
  `mapping.at(_.tags) has "y"`, `mapping.at("qty").desc` — a field by a lambda or by its name: a `Where` and an `Order` with THE FIELD CHECKED BY THE
  COMPILER against the mapped type (Scala 3 by `Mirror`, 2.13 by a macro) and the path typed as the field — a name
  mistyped, a value of another type, an element of another type do not compile; through an `Option` as through the
  value. `at` is said of anything `Typed[A]`: a `Mapping`, a `Collection`, a `Path`. `Field("qty") > 2` says the same
  of a path the compiler cannot check — a kind's fields read from a file, a query built from text.

On effects, not on proxies: no session, no identity map, no lazy loading, no change tracking. A value is a value; a
program says `save(a)` and where it goes is a handler chosen at the edge; what is in the store and does not read by
the shape is the store's fault, said plainly, not a problem for a form. Relations are keys in fields and a second
`find`; a join is the program's, above the backend (DECIDED, as for `Data`).

Checked on the machine and on Free: two types (`Customer`, `Sale`), one program over both, the same answers in
memory, on lines, on SQLite through `Data`, and on SQLite as a table per type.

KEYS FROM THE STORE (2026-10-20): `Data.Fresh(collection)` is the next of the collection's own sequence, never given
twice, as `Data.key(n)` — twelve digits, so keys sort as they were given; `Datas.fresh`, `Datas.add(c, key => a)`;
`Mapped.Create(key => a)` and `Mappings.create(key => Sale(key, ...))` make a value with the key and keep it — the
value's own `key` is expected to be it. Each backend keeps the sequences where it keeps everything else: memory in a
collection `_keys` (a name no collection may take), lines in `_keys.jsonl` (a line per key given, the last of a
collection its count), SQL in a table `_keys (collection, next)` by one statement, `INSERT ... ON CONFLICT DO UPDATE
... RETURNING` — the database's, not the connection's, so two connections never agree on a key. A table per type
uses the same table.

THE KEY A FIELD (2026-10-21): `Mapping.keyed(collection, shape)(_.id)` — the field named by a lambda the compiler
checks (a string name too, `keyed(collection, shape, "id")`, checked when the mapping is made) — says the key is a
field: `key` reads it, `rekey(a, key)` puts it, through the shape's JSON. So `create` CROSSES A WIRE: `Mapping.describe`
has `create` whose parameters are the shape with the key field defaulted to "", the value without its key goes, the
value with the store's key comes back; over a wire `create(make)` sends `make("")`. A mapping whose key is a function
of the value has no `create` over a wire — `save` is its word — and `split` says so by name. DECIDED against a draft
type per entity (an ORM's DTO): the shape already knows the fields, a second type says nothing new.

`Mapping.describe(mapping)` is the type's effect DESCRIBED from the mapping alone — `save(A)`, `load(key)`,
`remove(key)`, `find(where, order, limit)`, `count(where)` with the shapes of `A`, `Where` (recursive, through
`deferred`) and `Order` — so a mapped type is served by okay-faces as REST resources or MCP tools and performed
from afar with nothing more written: declare the type, map it, describe it, and it has every face. Checked: a
program over a mapped type answers the same performed from afar through `Domains.remote` against `Domains.serve`
in its own run; `Where` and `Order` round-trip through their shapes; `find`'s parameters have a JSON Schema.

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
where the reference says nothing holds; Postgres's jsonb orders strings before numbers where the reference orders
numbers first; compare like with like.

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
