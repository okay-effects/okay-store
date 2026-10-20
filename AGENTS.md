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
- Tests on both compilers, on the machine and on `Free`.
- Commits: plain messages, what and why, no attribution trailers.
