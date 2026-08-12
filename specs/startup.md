# Startup Modes and Environments

## Overview

The application can be started in two modes: normal mode and production mode.

## Modes

### Normal Mode (`make start`)

Starts the application using the Clojure backend directly.

- Reads `config.edn` for configuration
- If `:shadow? true` is set: starts shadow-cljs in watch mode (hot reload for ClojureScript)
- If `:shadow?` is not set or false: builds optimized JS with `shadow-cljs release`
- Starts the Clojure backend with nREPL on port 7888

### Production Mode (`make start-prod`)

Builds an uberjar and runs it directly with Java.

- Builds the application using `clj -T:build uber`
- Runs the standalone jar
- Intended for local testing of the production build before deployment

## Configuration (`config.edn`)

On first startup, if `config.edn` doesn't exist, it is auto-created with defaults:

```edn
{:db {:type :sqlite-in-memory}
 :devel {
   :pre-seed? true
   :shadow? true
   :dangerously-skip-logins? true
 }
 :server {
   :port 3017
 }
}
```

This means: use an in-memory SQLite database and seed it with demo data on startup.

### Configuration Options

| Key | Description |
|-----|-------------|
| `:db` | Database configuration (`:type` and optionally `:path`) |
| `:devel :pre-seed?` | If `true`, seed database with demo data on startup |
| `:devel :shadow?` | If `true`, use shadow-cljs watch mode for hot reload |
| `:devel :dangerously-skip-logins?` | If `true`, skip password authentication |

### Example Configurations

For development with hot reload:
```edn
{:db {:type :sqlite-in-memory}
 :devel {
   :pre-seed? true
   :shadow? true
   :dangerously-skip-logins? true
 }
 :server {
   :port 3017
 }
}
```

For persistent storage:
```edn
{:db {:type :sqlite-on-disk
      :path "data/personalist.db"}
 :devel {
   :pre-seed? false
 }
 :server {
   :port 3017
 }
}
```

## Production Environment Detection

The application determines production mode based on where it's running:

### On Fly.io

Always runs in production mode. `ADMIN_PASSWORD` environment variable is required - the app will fail to start without it.

### Locally

Production mode is determined by environment variables:

- If `DEV=true` is set and no `ADMIN_PASSWORD` → **not** production mode
- Otherwise → production mode

### Production Mode Behavior

When in production mode:
- Password authentication is required for all users
- The admin user authenticates with the value of `ADMIN_PASSWORD`
- Regular users authenticate with passwords stored in the database
- JWT tokens are used for session management

When not in production mode:
- No password is required
- Users can login by simply clicking on their persona in the login modal
- This makes it easy to test and demo the application

## Admin User

The admin user is special:
- Name: `admin`
- Email: `admin@localhost`
- Password: value of `ADMIN_PASSWORD` env var (in production)
- Has access to the Settings tab to manage personas

## Accounts and Personas

Since migration `003-accounts-above-personas` these are two things, where they
used to be one row:

| | is | holds |
|---|---|---|
| Account | an email and a password | any number of personas, including none |
| Persona | a public address (`personalist.org/:persona-id`) and a display name | its identities |

One email can therefore wear several faces, or none at all — an account holding
no personas is a legitimate state, not a degenerate one, and it lands on its
profile page, which is where it makes one. Which personas belong to the same
account is not public: `GET /api/personas` answers with ids and display names
only, so an anonymous visitor sees a flat list of personas and learns nothing
about who holds them or which of them share a login.

- Logging in takes one identifier field, `{username, password}`, and a human
  puts their **email** in it. Logging in by persona id is gone: a persona is a
  public address, not a login. The token carries the account id.
- A write under `/api/personas/:id/...` is allowed when the token's account
  holds that persona — so one login can edit every persona it owns.
- `GET /api/me` answers with the account behind the token: its email and its
  personas in order. It, `GET /api/accounts` and an identity's `/provenance`
  (see *Version Authorship* below) are the only three reads that ask for a
  token; everything else a `GET` can reach is what a visitor sees anyway.
- A logged-in user manages their own personas on the Profile tab: create one
  (a generated urbit id plus a display name), or remove one by hand-typing its
  urbit id. Removal destroys the persona and every version of every identity
  under it, permanently. The account's **last** persona may be removed too; the
  hand-typed confirmation is the whole guard, and the account is then simply an
  account with none.
- The admin's Settings tab creates *accounts* — `POST /api/accounts` takes an
  email and a password and makes only the account — and lists them with their
  personas. To give an account its first persona from outside it, admin passes
  an `:account_id` to `POST /api/personas`; that field is admin's alone, and
  anyone else naming an account is refused. It is what `scripts/seed-db.sh`
  uses.

Migrating an existing database needs no intervention: every persona row becomes
one account carrying its email and password plus one persona carrying its id and
display name, and no identity moves.

## Machine Users

Since migration `004-machine-users` an account may also hold *machine users*: a
credential for something that writes through the API on the account's behalf. A
machine user is an `accounts` row of its own, flagged `is_machine_user` and
pointing at its parent — the same shape tracker uses — but unlike tracker's,
**an account may hold as many as it likes**. That is the point of the feature:
one machine user writing personas A and C, another writing B and C.

- It **never logs in**. It holds no password, and `POST /api/auth/login`
  answers its name with the same flat 401 as a name nobody has ever used.
- Its whole credential is a **bearer token**, minted in the UI: 32 random bytes
  with a `pmu_` prefix, of which only a SHA-256 is stored. So it is shown
  exactly once, when created and when rotated. There is one column holding the
  hash, which is why "only the latest token is active" is a property of the
  schema rather than of any code — rotating overwrites, and whatever was using
  the old token stops working at that instant.
- What it may write is **exactly the personas granted to it**, one row per
  grant, and not everything under its parent account. Removing a persona
  removes the grants naming it.
- With `can_create_personas` it may create a persona. The new persona belongs
  to the **parent account**, and the machine user is granted write on it in the
  same breath, so it may use what it just made. Deleting a persona stays with
  the human account.
- Every machine-user management route (`/api/machine-users`) refuses a machine
  token. Those URIs name no persona, so the write guard alone would wave any
  valid token through them; each handler therefore checks for itself that the
  caller is a human account owning the target. Without that, a machine user
  could grant itself the whole account or mint itself a token its owner could
  not revoke.
- `GET /api/me` answers a machine user about *itself* — its name, its
  permission, its granted persona ids — and never the account's roster.

Nothing about machine users is public. `GET /api/personas` is unchanged, and no
public read says which of an account's writers wrote what — but the account that
holds a persona can now see which of its machines wrote which lines of it. That
is *Version Authorship*, next.

## Version Authorship and the Provenance View

Since migration `005-version-authorship` every identity version carries an
`author`: the literal `human`, or the **name** of the machine user that wrote
it. The name and not the word "machine", because an account may hold several and
which of them wrote a line is the thing worth recording — and because every
marker that is not `human` is then an agent's automatically, with no list to keep
in step when a new machine user is created.

- **Every version that existed before the migration reads `human`.** One
  `ALTER TABLE ... DEFAULT 'human'` is both the constraint and the retrofit; it
  records the owner's own answer about those versions rather than a schema
  guessing at them.
- **The write path names its author, and cannot forget to.** `wrap-auth` already
  resolved the token to a principal in order to answer *may you write this
  persona?*; it now passes that down, and `handlers/author-of` turns it into the
  marker. `author` is a *required positional argument* of the datastore's write
  functions, so a path that forgot it is an arity error rather than a version
  that silently claims a person wrote it. Admin counts as a person; so does a
  write in dev mode, where nothing authenticates and the writer is a hand at a
  keyboard.
- **A relation change is a version and gets an author like any other**, that of
  the call that committed it.
- **A machine user may not be named `human`** — 400, case-insensitively. It
  would otherwise be able to forge human authorship of everything it wrote.

`GET /api/personas/:name/identities/:id/provenance` is the third guarded read.
It answers `{:legend, :ranges, :versions}` — how careful an agent should be in
each stretch of the text as it stands now (`1.00` written by hand, `0.00` by a
machine user, in between both), the sentence that says what those numbers mean,
and who wrote each version. The arithmetic is the `us-vs-them` sibling
checkout's, wired in by `:local/root` exactly as cookbook and rhizome wire it;
`et.pe.provenance` only says which marker counts as the person's and hands the
history over in the order the library replays it.

401 without a token, 403 for another account's, and **403 for a machine token
even on a persona it may write** — this one is for logged-in humans. The public
history read next door still carries no author at all, because a machine
marker is a machine user's own name. In the UI the account that holds the
persona gets a *Show provenance* button on its identities; nobody else is shown
a control at all, not even a disabled one.

### It rides along on the plain identity read

That route is not the only way in. `GET /api/personas/:name/identities/:id` —
the ordinary single-identity read, public as it always was — **carries the
answer in the same body** for a caller entitled to it:

```json
{"identity": "…", "name": "…", "text": "…",
 "provenance": {"legend": "1.00 is a stretch written wholly by hand …",
                "ranges": [{"from": 1, "to": 3, "caution": 1.0},
                           {"from": 4, "to": 5, "caution": 0.0}]}}
```

An agent about to rewrite a text reads that identity first, and what it needs
to know — which lines are a person's — has to be *there*: a second call to find
out what it may rewrite is a call it will not make. Cookbook rides `caution`
along on `GET /api/recipes/:id` for the same reason and rhizome's REST
`get-item` calls it the one number in that API written for an agent to act on.

- Served to a **machine token on a persona it may write** — the grant is the
  entitlement, the same grant that lets it PUT there — to the **account that
  holds the persona**, and to **admin**.
- **Absent, not empty**, for anyone else: a visitor, another account, a machine
  token on a persona it was not granted. The ranges are not even computed for
  them; the arithmetic is quadratic in lines and this is the app's
  single-identity read.
- **Never `:versions` here.** Per-version authorship names the account's machine
  users, and this is the route a machine user reads; it is told nothing about
  its siblings, which `GET /api/me` already refuses it. That key stays on the
  guarded `/provenance` route, which is what the human's own view reads.
- In dev with `:dangerously-skip-logins?` every caller counts as admin, so
  every caller is served it — as with every other guarded read in that mode.

## Private Personas

Since migration `006-private-personas` a persona may be **private**. Until then
a persona was one thing — a public address — and every `GET` under
`/api/personas/:name/...` was unauthenticated by design, because personalist
serves what a visitor of personalist.org sees. A private persona is where that
stops being true of one of them.

**Private means unreadable, not merely unlisted.** It is absent from
`GET /api/personas` *and* its identities, history, relations, search and single
reads answer exactly as an id nobody has ever used does. An index a persona is
missing from while its address still serves anyone who guesses it would be a
promise this app does not keep.

**404, never 403, and the same 404 an unknown id gets** — body and status both.
The existence of a private persona is part of what is private; a 403 would
confirm it and let a stranger map an account by probing ids. `owned-machine-user`
already draws that line for machine user names. This is also why the guarded
`/provenance` route now asks *does this persona exist* before it asks *who are
you*: it used to answer 401 for an unknown persona, which would have made 404
there mean "private" and nothing else.

Who may read one:

- the **account that holds it**
- **admin**, by the authority that lets Settings edit another account's persona
- a **machine user granted it** — the grant is the entitlement, as it is for
  writing and for the riding provenance. It would be a strange grant that let an
  agent write a persona it cannot fetch. A *sibling* machine user of the same
  account, granted only the public personas, is told nothing.

Nothing about **writing** changes. `wrap-auth` already answers "may you write
this persona?" and a private one is written by exactly the people who could write
it before — and is still refused with a 403 rather than a 404, because being
allowed to write is a different question from being allowed to know it is there.

- The rule is **one middleware**, `wrap-private-personas`, not a line in each
  handler: seven read routes hang under `/api/personas/:name` and the eighth
  would have to remember. It is threaded *inside* `wrap-params`, unlike
  `wrap-auth`, because in dev the acting account is named by `?persona=`.
- **It engages in dev too**, which `wrap-auth` does not.
  `:dangerously-skip-logins?` skips *logins* — it says no password is needed to
  act as somebody, not that everybody is everybody — and privacy is the feature
  itself rather than a credential check. A feature switched off on the owner's
  own laptop is one he can never look at. That mode still names an actor, so
  `?persona=<id>` and `?persona=admin` are how a dev caller says who is asking.
  In that mode it is a **demonstration rather than a protection**, and it cannot
  be otherwise: nothing authenticates there, so naming the private persona itself
  in `?persona=` acts as its account and opens it — exactly as anyone can already
  write any persona there. It is off in prod by `ensure-app-options`, which is
  where the rule is a rule.
- **`GET /api/personas` is the one thing that still bends in dev**, where it
  lists every persona, private ones included. In that mode the list *is* the
  login screen — the auth modal offers it and clicking a row is how you become
  somebody — so filtering it would take away a way in rather than protect a
  secret, and an account whose only persona is private could not be logged into
  at all. The handler already bent that way for the same reason: it prepends an
  `:admin` row so the switcher can offer it. The rows carry `private` either way,
  and the UI badges them.
- The **client now carries its credential on every persona-scoped read**, not
  only on the three that ask about an account. Without it the owner's own browser
  would be told his own persona does not exist.
- A private persona's **id is still spent**. It is an address whether or not it
  answers, and `GET /api/generate-id` handing it out again would leak its
  existence to whoever asked next.
- `POST /api/personas` takes `:private`, defaulting to false — what every persona
  was before 006 — so one meant to be private is private from its first instant
  rather than public for as long as it takes to toggle it. `PUT
  /api/personas/:name` takes it too, and **a key absent from the body is left
  alone**: a plain rename cannot publish a persona by omission.
- Publishing and hiding are the same button and neither is confirmed by hand,
  unlike removing a persona. Hiding destroys nothing and publishing is undone by
  pressing it again; what the hand-typed confirmation guards is the step there is
  no way back from.

The rollback of `006` **publishes every private persona** — there is one column
holding the fact, so dropping it cannot mean anything else. Unlike `005`, whose
rollback loses a record, this one discloses what somebody chose to keep to
themselves. Make them public by hand first if a rollback is really what you want.

## Seeding

When `pre-seed?` is `true` in `config.edn`:
- The seed script (`scripts/seed-db.sh`) runs automatically after startup
- Creates demo personas (alice, bob) with sample identities and relations
- Useful for demos and development

When `pre-seed?` is `false`:
- Database starts empty (or with existing data if using persistent storage)
- Used in production where real data should persist

## Quick Reference

| Command | Description |
|---------|-------------|
| `make start` | Start app (uses config.edn for shadow? setting) |
| `make start-prod` | Build and run uberjar locally |
| `make stop` | Stop the application |
| `make restart` | Restart the application |
| `make restart-prod` | Restart in production mode |
