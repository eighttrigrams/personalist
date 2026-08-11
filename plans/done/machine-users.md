# Machine users under an account

**Done 2026-08-11.** Commits `24bae6d` (migration 004), `72fba37` (ds),
`7666ba3` (login on username), `5a95c24` (the token), `641f202` (wrap-auth),
`7d5fc8d` (the escalation trap), `666bd50` (persona creation), `87bba90`
(sqlite_sequence fix + /api/me), `fc4e63b` (the UI).

**What did the trick**, twice:

1. **Writing the escalation tests before the endpoints they guard.** The
   machine-user management routes name no persona in their URI, and `wrap-auth`
   waves any valid token through such a URI — correctly, for
   `POST /api/personas`. Writing "a machine token gets 403 from all four, and
   nothing it attempted happened" *first* meant the handlers were written with a
   gate as their first act rather than having one retrofitted. Enumerating the
   four routes in one map (`management-calls`) and driving every principal
   through all of them is what makes a fifth route added later visibly
   uncovered.

2. **Running the migration against a copy of the real database, and diffing
   `sqlite_sequence` as well as the rows.** That is what caught the
   AUTOINCREMENT reset (cookbook recipe #20): rebuilding `accounts` put the
   high-water mark back to `MAX(id)`, so the next account would be handed the id
   of one that had been deleted. It matters here more than usually because a JWT
   carries `{:account <id>}` and this app sets no expiry — a token for a deleted
   account would have started authenticating as a different one. No unit test in
   the suite could have caught it as it stood: every fixture creates rows and
   never deletes the last one, so the counter never differs from `MAX(id)`.

## The shape

A machine user is an `accounts` row, flagged `is_machine_user` and pointing at
the human account it works for — tracker's shape. It holds no password and
never reaches the login route; its whole credential is a bearer token whose
SHA-256 is all that is stored.

```
accounts        + name, for_account_id, is_machine_user,
                  can_create_personas, token_hash;  email becomes nullable
machine_persona_grants(machine_account_id, persona_id)   -- PK both
```

## Decisions worth remembering

- **The one thing not copied from tracker.** Tracker enforces one machine user
  per human with a partial unique index on `for_user_id`. This feature is
  explicitly the opposite, so there is no such index — and a test asserts an
  account can hold several, so nobody re-adds it by reflex.
- **"Only the latest token is active" is a property of the schema**, not of any
  code: one column holds the hash, so rotating overwrites. The test that pins it
  is written out anyway (mint, rotate, assert the *previous* one stops
  verifying), because that is what stops someone later adding a `machine_tokens`
  table to keep a history.
- **SHA-256, not bcrypt.** Bcrypt is slow on purpose, to guard low-entropy human
  passwords; this is 256 bits of secure randomness that needs no stretching, and
  a machine user hits the API in a loop.
- **A `pmu_` prefix** so the verifier can tell the two credential kinds apart on
  sight rather than by trying to unsign and catching the failure — and so a
  leaked token is greppable.
- **Granting a stranger's persona is a 400 for the whole request**, not a silent
  drop. A grant the caller believes it made and did not is worse than an error.
- **404, not 403, for another account's machine user** — one answer covers "no
  such machine user" and "not yours", so probing names maps nothing. Tracker's
  own device.
- **Admin is refused by the management routes**, deliberately: a machine user
  hangs off an account and admin has none.
- **Deleting a persona stays with the human**, even one the machine user created
  itself. A grant is permission to *write*. Flagged for the owner as my call.
