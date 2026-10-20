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
