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
  personas in order. It and `GET /api/accounts` are the only reads that ask for
  a token; everything else a `GET` can reach is what a visitor sees anyway.
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

Nothing about machine users is public. `GET /api/personas` is unchanged, and a
persona written by a machine is indistinguishable from any other.

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
