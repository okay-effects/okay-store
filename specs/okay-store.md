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

`Stores.describe: Describe[Store]` (okay-faces' `Describe`): every operation with its shapes, so `RestDomains` serves it
at `/store`, `McpFace.domain` as tools, and `remote` performs it from afar. A record's `meta` crosses as JSON.

## Not here

Encryption, merge of two journals, a database behind the handler: later, as handlers.
