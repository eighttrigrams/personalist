# Relations stored as JSON inside the identity version

> DONE 2026-07-12. Follow-on to `relations-share-version-timeline.md`.

## Motivation

Relations are unidirectional, owned by the source identity, and never queried in
complex ways. Now that a relation is committed with its identity version's
timestamp, each version is already a snapshot — so the separate event-sourced
`relations` table is redundant. Store relations inline on the identity version.

## Model

Each `identities` version row gains a `relations` TEXT column holding a JSON array
of maps (ordered list), each `{"target": "<identity-id>", "description": <string|null>}`.
`description` is unused for now (always null) but modelled so a relation can later
carry a note. Time-travel = read that version's blob; no event replay.

## Changes

- **Migration `002-relations-in-identity.edn`**: `ALTER TABLE identities ADD COLUMN
  relations TEXT`; reconstruct each version's active relation set from the old event
  table (correlated scalar subquery — last event per relation_id ≤ version time = 'add')
  as JSON maps; then `DROP TABLE relations`. Validated against the old
  `get-active-relations` on the real DB via nREPL: **0 mismatches across 99 versions**.
- **sqlite.clj**: removed `make-relation-id` / `relation-active?` / `add-relation` /
  `get-active-relations` / `delete-relation`. Added JSON helpers; `save-identity-version`
  now carries the current relation set forward and applies adds/removes, writing the
  blob on the new version row; `update-identity` delegates (carry-forward on plain edits);
  `list-relations` reads the blob and resolves target names. `add-identity` seeds `'[]'`.
  `relation-adds` accepts a target id or a `{:target :description}` map.
- **ds.clj**: dropped `add-relation` / `delete-relation` facades.
- **handlers.clj / server.clj**: removed the `POST .../relations` and
  `DELETE .../relations/...` endpoints + handlers (relations only change via Save now).
- **Frontend**: unchanged — it already routed all relation changes through Save and
  reads via `GET .../relations`.
- **Tests**: rewrote the relation tests to mutate via `save-identity-version`; added
  `relations-carried-forward-on-plain-edit` and `relation-description-round-trips`.
  `list-relations` output now includes `:description`.

## Verification

`clj -M:test` → 26 tests / 84 assertions green. Migration applied to the real DB
(backup `data/personalist.db.pre-jsonmigration`); `relations` table gone, 13 identities'
current versions carry relations. Playwright: Problem loads Decision+Concurrency from
JSON; add "Latency" → Save (v4) → reload shows all three; v4 blob carries forward + new add.
