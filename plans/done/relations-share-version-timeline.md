# Relations share the identity version's timeline

> DONE 2026-07-12. Verified via `clj -M:test` (new regression test
> `save-identity-version-shares-relation-timeline`) and a full Playwright run on
> eighttrigrams → Problem (add → Save → reload → time-travel → deferred-delete/discard).
> Two-part fix: (1) commit relations with the version's timestamp; (2) stop truncating
> the URL `time` param to whole seconds (see "Precision follow-on" below).


## Problem

Relations were persisted immediately (own `valid_from` = server "now"), independent
of identity versions (also own `valid_from` = server "now"). Because a relation can be
added seconds *after* the latest text version, viewing the latest version queries
relations "as of that version's timestamp" and the newer relation is invisible.

Repro: eighttrigrams (`namlys-lasduc`) → Problem (`matmug-havruc`) shows Decision on
first select (relations fetched with no time bound), but a reload / time-travel fetches
relations at the latest version's `valid_from` (21:11:15), which predates the relation's
own `valid_from` (21:11:53) → Decision disappears.

## Decision (chosen by the user)

Unify the timelines: **a relation add/remove is a pending edit committed only on Save,
tagged with the exact `valid_from` of the identity version that Save creates.**

- Deletes are deferred too (same as adds).
- Save **always** creates a new version (even if name/text unchanged) to anchor the relation.
- Forward-only: no migration of already-mis-tagged relations (e.g. the existing
  Problem→Decision stays hidden on the latest version until re-added).

## Implementation

Backend:
- `sqlite/save-identity-version` — one `valid-from` T; write the version, then apply
  relation adds/removes all at T.
- `ds/save-identity-version` facade.
- `update-identity-handler` accepts `relation_adds` (target ids) + `relation_removes`
  (`source/target` ids), fixes one Instant T, delegates.

Frontend:
- `:pending-relation-adds` (vec) / `:pending-relation-removes` (set of rel ids) in state.
- `add-relation` / `delete-relation` now mutate pending state instead of hitting the API.
- Save (`update-identity`) sends pending sets, clears them, refetches.
- `relations-list` renders the effective list (persisted − pending-removes + pending-adds)
  in edit mode, marks unsaved adds, and only shows the X/∞ affordances in edit mode.
- pending cleared on `select-identity` (navigate away = discard).

## Precision follow-on (found during verification)

Committing the relation at the version's timestamp is necessary but not sufficient:
`format-time-for-url` truncated the URL `time` param to whole seconds. A freshly-saved
version carries sub-second precision (e.g. `12:08:57.846Z`), so reloading at the
truncated `12:08:57Z` queried `valid_from <= .000` and excluded both the version and
the relations committed with it. Fix: `format-time-for-url` now preserves full
millisecond precision. Confirmed via API: relations at `...:57Z` → Decision only;
at `...:57.846Z` → Decision + Concurrency.
