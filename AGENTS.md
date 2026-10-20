# AGENTS.md — okay-store

Read before anything else; then `specs/okay-store.md`.

- A store of journaled, versioned records typed by a `Schema`, as an effect: `Store` and its words in `Stores`, the
  handler in memory (`keeping`), the journal (`Journal`, `Event`, `Kept`), and `Stores.describe` — the effect described
  for every face (REST resources, MCP tools, a remote handler) by okay-simple's `Describe`.
- On okay-simple's interface, one source for Scala 3.3 and 2.13, the JVM always, Scala.js and Native when asked. The
  dependencies: okay-simple (`okay-effects`, `json`, `shape`, `describe`), nothing else.
- Nothing here knows an application: what a record is beyond its kind's fields is `meta`, the application's own JSON;
  what a kind is beyond its schema is `meta` too. verity (github.com/okay-effects/verity) is the first application;
  its context and validity live in `meta`.
- `Data` (core) is the level below every domain: collections of JSON documents, a `Where`/`Order` query with reference
  semantics (`Where.holds`, `Order.select`); backends in memory, on lines, on SQL (`jdbc`, by a `Dialect`). A domain's
  handler is a translation into `Data` over typed collections; the direct handlers are the particular mappings. A
  backend is right when it answers as the reference on the same documents — the tests say so, not the backend.
- `jdbc/`: the same effect on a database (SQLite), the JVM; a handler never changes what an operation means — the
  agreement test (`AgreementSuite`) runs one program in memory and on SQLite and compares every answer.
- Tests on both compilers, on the machine and on `Free`.
- Commits: plain messages, what and why, no attribution trailers.
