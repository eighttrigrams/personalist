# Accounts above personas

**Done 2026-08-11.** Commits `ff49c69` (migration 003), `503838d` (backend),
`c89e05c` (frontend).

**What did the trick:** writing migration 003's test first, against a database
built the way production's was — 001 and 002 applied, the four prod-shaped
persona rows inserted, and only *then* 003. A migration runs once against real
data and cannot be redone, so it is the one thing here that had to be right
before anything else was written. Everything downstream (the ds layer, the
token, the guard) fell out of the shape that test pinned down.

The second thing that earned its keep was driving the finished UI in a browser
in **two** modes rather than one. The dev mode (`:dangerously-skip-logins?`) and
the prod-like mode (`ADMIN_PASSWORD` set, so `wrap-auth` actually engages) fail
differently, and the one real bug — removing the *active* persona left the
client holding a dead id, which in dev is also the thing that names the account
to the server — only appears in dev.

## The change

One table carried two ideas. `personas.id` is an urbit-style two-word name and
it is the public address (`personalist.org/namlys-lasduc/<identity-id>`), while
`email` and `password_hash` on the same row were the login credential. The row
is now cut in two, so one credential can own many addresses:

```
accounts(id INTEGER PK AUTOINCREMENT, email TEXT UNIQUE NOT NULL, password_hash TEXT)
personas(id TEXT PK, account_id INTEGER NOT NULL, name TEXT NOT NULL, sort_order INTEGER NOT NULL)
```

`identities` was not touched. Its `persona_id` holds the urbit name and the
urbit name survives the split unchanged, so not one identity row moved.

The privacy rule everything else follows from: **an anonymous reader learns that
personas exist and nothing about who holds them.** `GET /api/personas` stopped
returning `:email`; `GET /api/me` and `GET /api/accounts` are the only reads
that ask for a token, and they guard themselves inside the handler rather than
widening `wrap-auth`'s rule for every other GET.

## Decisions worth remembering

- **The display name belongs to the persona, not the account.** An account is
  email + password and nothing a visitor ever sees.
- **Removal is real destruction** — the persona and every version of every
  identity under it, no history, no undo. The hand-typed confirmation is
  enforced on the server as well as in the dialog; an account's last persona is
  refused with a 409.
- **`:down` refuses rather than picking a survivor.** It can only restore the
  old single-table shape while every account holds exactly one persona; two
  personas cannot both carry one email into a UNIQUE column. It is written so
  that case hits the constraint, with the INSERT ordered before any DROP so the
  failing statement has not yet destroyed anything (`:transactions false` means
  there is nothing to roll back).
- **Ownership became a lookup.** `wrap-auth` compares the token's account
  against the account holding the persona named in the URI, so one login covers
  every persona it owns and a persona nobody holds is owned by nobody. F1
  (`reserved-persona-ids`) and F2 (URL-decoding the URI segment) both survive;
  the whole auth suite was reworked onto seeded personas, since a token alone
  no longer means anything.
- **One dev-only auth path**, `?persona=<id>`, read in exactly one helper
  (`handlers/acting-account`) and only while `allow-skip-logins?` is true.
