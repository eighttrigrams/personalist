# /api/describe, plurama-cli registration, and two auth holes

> DONE 2026-07-30. Four things asked for, three of them mine (§2 is a host-side
> edit to `deploy-plurama-cli.sh`, which this box cannot see).
>
> **What did the trick.**
> - §1 `GET /api/describe` — copied music's implementation verbatim (they are
>   house boilerplate, byte-identical across treina and music) and docstringed
>   all 15 route handlers. `8f3dbac`.
> - §3a anonymous `POST /api/personas` — the exemption in `public-endpoint?`
>   only existed because `settings.cljs` was the one frontend write not sending
>   a token. Both halves had to move together: add `:headers (auth-headers)`
>   there, then drop the exemption. `47526ae`.
> - §3b horizontal writes — `wrap-auth` was verifying the token and throwing
>   the claims away. It now keeps them and compares `:persona` against the
>   `/api/personas/([^/]+)` segment of `:uri`, answering **403** (not 401) when
>   they differ; admin is exempt because the Settings tab edits other personas'
>   emails. Centralised in the middleware, so the next handler cannot forget it.
>   Comparing the raw `:uri` segment is safe because clout matches the raw URI
>   too and hands the handler that same undecoded string — verified, not assumed.
>   `47526ae`.
> - §4 rate limiter — asked for verify-only, and it turned up a real defect:
>   **the limiter was a no-op in production.** `app` called
>   `(wrap-rate-limit base-app)` *inside* its per-request `fn`, so
>   `create-limiter`'s atoms were reallocated on every request. 200 requests from
>   one IP against a real prod-mode server: zero 429s. Reported, then **fixed on
>   the owner's direction** by hoisting the wrap out of the request path
>   (`6faf9bd`), with the app-level tests the 17 existing primitive tests could
>   not provide. Live re-check: the 429 now arrives at request #61 as designed.
>   Deliberately left alone: `:devel :shadow?` is a hot-reload flag that also
>   switches the limiter off, so standalone `start.sh prod` skips it — options
>   laid out in the report, owner's call.
>
> **Two blockers found on the way**, fixed as separate commits because nothing
> in the app would start otherwise:
> - `config.edn.template` ships aero reader tags but `load-config` read it with
>   `clojure.edn/read-string`, so `make start` and `make start-prod` both died
>   on "No reader function for tag env". personalist was the only plurama app
>   without aero. `3d91986`.
> - the legacy 3017 port survived in start.sh, stop.sh, seed-db.sh and the
>   README while the config said 3120 — so `make stop` looked for the wrong
>   process and `:pre-seed? true` had been seeding into nothing. `c9118cd`.
>
> Verified: `clj -M:test` 34 tests / 232 assertions green (baseline 26/84);
> clj-kondo unchanged at 0 errors / 5 pre-existing warnings; both auth tests
> shown to fail against the pre-fix code; browser pass in dev **and** in real
> prod mode (`ADMIN_PASSWORD` set, against a copy of the db).
> Full write-up: `/workspace/handoffs/personalist-describe-cli-and-auth-report.md`.

# personalist: /api/describe, plurama-cli registration, and two auth holes

Four things, one pass. Personalist is the last plurama app without a
self-description endpoint and without a plurama-cli registration; while we are
in there we close two authorization holes and put the rate-limiter question on
the record.

Personalist in prod is **not standalone**: it is embedded in the plurama
umbrella (`plurama/src/plurama/server.clj:45` → `et.pe.server/build-handler`)
and reached by host routing (`plurama/config.prod.edn` `:hosts` maps
`personalist.org` and `www.personalist.org` → `:personalist`). The umbrella runs
on fly.io. Everything below is about that deployment.

## 1. `GET /api/describe`

Mirror **treina and music verbatim** — their implementations are byte-identical
house boilerplate (`treina/src/clj/et/trn/server.clj:65-102`,
`music/src/clj/et/mu/server.clj:60-93`):

- `describe-namespaces` — `'[et.pe.server et.pe.server.handlers]`
- `route-doc-re` — `#"(?s)^(GET|POST|PUT|DELETE|PATCH)\s+(\S+)\s"`
- `describe-handler` — walks `ns-publics` of those namespaces, keeps vars whose
  docstring matches the regex, emits a **flat sorted vector** of
  `{:name :ns :method :path :arglists :doc}`, sorted by `[:path :method]`.
- Route: `(GET "/describe" [] describe-handler)` first inside the `/api` context.

The flat-vector shape is the one plurama-cli's skill documents
(`plurama-cli <app> /api/describe | jq '.[] | {method, path}'`). Tracker's
`{:endpoints … :skill …}` shape is the *exception*, not the model — it wraps the
list because it also serves its agent skill markdown. Personalist has no skill,
so it takes the plain treina/music shape.

Handlers live in `et.pe.server.handlers` and currently carry **no docstrings at
all**. Each of the 15 route handlers gets one, first line
`METHOD /api/path — explanation`, then the body fields, query params, auth
requirement and error statuses, as treina's do:

| handler | route |
|---|---|
| `list-personas-handler` | `GET /api/personas` |
| `add-persona-handler` | `POST /api/personas` |
| `update-persona-handler` | `PUT /api/personas/:name` |
| `generate-id-handler` | `GET /api/generate-id` |
| `password-required-handler` | `GET /api/auth/required` |
| `persona-login-handler` | `POST /api/auth/login` |
| `list-identities-handler` | `GET /api/personas/:name/identities` |
| `list-recent-identities-handler` | `GET /api/personas/:name/identities/recent` (`limit`=5, `offset`=0) |
| `search-identities-handler` | `GET /api/personas/:name/identities/search` (`q`, `valid_at`) |
| `add-identity-handler` | `POST /api/personas/:name/identities` (body `id?`, `name`, `text`, `valid_from?`; 409 on duplicate id) |
| `update-identity-handler` | `PUT /api/personas/:name/identities/:id` (body `name`, `text`, `valid_from?`, `relation_adds`, `relation_removes`) |
| `get-identity-at-handler` | `GET /api/personas/:name/identities/:id/at` (`time`, required) |
| `get-identity-history-handler` | `GET /api/personas/:name/identities/:id/history` |
| `list-relations-handler` | `GET /api/personas/:name/identities/:id/relations` (`time?`) |
| `get-identity-handler` | `GET /api/personas/:name/identities/:id` |

Plus `describe-handler` itself. `password-required-handler` and
`persona-login-handler` are higher-order (they take `prod-mode?` and return the
handler) — the docstring on the var is what describe reads, so they need no
restructuring.

Non-route publics in the same namespace (`set-conn!`, `set-config!`,
`ensure-conn`, `verify-token-check`, `wrap-rate-limit`) must stay out of the
listing; the regex already does that, and `build-handler`'s existing docstring
does not match it either.

**Test** (mirroring the intent of `tracker/test/unit/et/tr/describe_integration_test.clj`,
adapted — personalist has no HTTP test harness, so call the handler var
directly): every public var in `et.pe.server.handlers` whose name ends in
`-handler` appears in describe's output. That is the guard that keeps a future
handler from shipping undocumented.

## 2. plurama-cli registration — read-only, no credentials

Owner's decision: *"everything that is public should be readable. which is
everything i believe at the moment, as a viewer in the internet would see it."*

So personalist joins `deploy-plurama-cli.sh`'s `APPS` **with an empty username**,
like blog, music and rhizome:

```
"personalist|https://personalist.org||"
```

No login, no cached token, no secret — the CLI sees exactly what an anonymous
visitor sees. This works because `wrap-auth` guards only mutating requests, so
every `GET` under `/api` is public. Writes through the CLI will 401, which is
correct.

Consequence: personalist's login handler needs **no** change. It currently
accepts `{:id … :password …}` / `{:email … :password …}` while the rest of the
suite (and plurama-cli's `login!`) speaks `{:username … :password …}`. That
divergence is now only cosmetic; note it, do not fix it in this pass.

This edit is host-side (the base repo is not mounted into the box) — the
coordinator makes it, the human re-runs `./deploy-plurama-cli.sh`.

## 3. Two authorization holes (owner chose: close both)

Today `wrap-auth` (`personalist/src/clj/et/pe/server.clj:76`) engages only when
`(prod-mode?)`, only for mutating requests under `/api`, and only checks that a
token *verifies*. Reads are unguarded by design and stay that way.

### 3a. Anonymous persona creation

`public-endpoint?` (`server.clj:71`) exempts the exact URI `/api/personas` from
auth, so `POST /api/personas` — minting a persona — is unauthenticated in prod.
Anyone on the internet can create personas on personalist.org.

Why the exemption exists: `settings.cljs:62` is the only caller, and it is the
one write in the whole frontend that does **not** pass `:headers (auth-headers)`
(`update-persona`, `add-identity`, `update-identity` all do). Removing the
exemption without fixing the caller would break the admin Settings tab in prod.

Fix, both halves together:

- drop `(= uri "/api/personas")` from `public-endpoint?`, leaving
  `/api/auth/login` as the only public write;
- add `:headers (auth-headers)` to the `POST` in `settings.cljs` (and
  `auth-headers` to that namespace's `:refer` list).

Any valid token may still create a persona. Restricting persona creation to the
admin token specifically was offered and **not** chosen — leave that door open,
do not implement it.

### 3b. Horizontal writes across personas

Bigger, and found while reading for 3a: nothing ever checks that the token's
persona matches the `:name` in the URL. Persona A's token can create and edit
identities under persona B (`POST /api/personas/:name/identities`,
`PUT /api/personas/:name/identities/:id`) and can edit B's email and display
name (`PUT /api/personas/:name`). Mitigating: in prod, personas are
admin-created, so the set of token holders is small and known — but it is still
a hole and the owner wants it closed.

Shape:

- `wrap-auth` currently verifies and discards the claims. Keep the claims:
  `verify-token-check` returns the unsigned map, whose `:persona` is the persona
  id as a **string** (`create-token` does `(name persona-name)`), while the DB
  side keys personas as keywords and the URL segment is a string — compare as
  strings.
- Enforce ownership in **one** place, not per handler: a mutating request whose
  URI matches `^/api/personas/([^/]+)` must have `:persona` equal to that
  segment, or be `"admin"`. Centralised is the point — a per-handler check is
  something the next handler forgets. `wrap-auth` sits above the routes, so
  compojure's `:params` are not bound yet; derive the segment from `:uri`.
- Admin exemption is load-bearing: the admin Settings tab edits *other*
  personas' emails.
- Status: `403` for authenticated-but-not-yours (the code only ever emits 401
  today; 403 is the honest one — say so in the report).
- Everything stays inside the existing `(prod-mode?)` gate, so dev with
  `:dangerously-skip-logins? true` is unaffected.

**Tests** — the primary proof, since none of this engages in dev mode: drive the
middleware directly with `with-redefs [et.pe.server/prod-mode? (constantly true)]`
over anonymous `POST /api/personas` → 401; valid token → through; A's token
writing under B → 403; A under A → through; admin under anyone → through.

## 4. Rate limiter — verify and report, change nothing

It exists: `handlers/wrap-rate-limit` (`handlers.clj:254`) over
`et.pe.middleware.rate-limit`, per-IP 60/min (`PER_IP_RATE_LIMIT`) and global
180/min (`GLOBAL_RATE_LIMIT`), sliding 60s window, `429` with an empty body,
warn logs on a 10s cooldown. 17 unit tests in
`test/clj/et/pe/middleware/rater_limit_test.clj` cover the primitives (the
filename's typo is mirrored in its `ns`, so it does load and run).

It is **active in prod**: `app` skips the limiter when `:devel :shadow?` is
true, and the umbrella's `config.prod.edn` gives `:personalist` no `:devel` key
at all — so `shadow-mode?` is false and `build-handler` returns the wrapped
handler. In dev it is bypassed (both `personalist/config.edn` and
`plurama/config.edn` set `:shadow? true`). The limiter is also the *outermost*
wrapper, so it throttles unauthenticated traffic too — which is what makes 3a's
fix stick rather than merely slow an attacker down.

Two things to put on the record rather than fix:

- **Client-IP attribution.** `get-client-ip` reads `fly-client-ip`, then
  `x-real-ip`, then `:remote-addr`. plurama runs on fly, so `fly-client-ip`
  should be present — but if it is not, every request collapses into one bucket
  and the per-IP limit degrades into an effective 60/min for the whole world.
  Reason it out from the code and `plurama/fly.toml`; state the conclusion and
  what would confirm it. No calls to prod from the box.
- **`wrap-rate-limit` itself is untested** — the env parsing, the IP extraction
  and the 429 shape have no coverage, only the primitives beneath them.

## Out of scope, recorded

- `GET /api/personas` returns every persona's **email** to anonymous callers
  (`list-personas-handler`, and the anonymous UI does fetch it). The owner's
  stated model is that everything public is readable; whether persona emails
  belong in that set is his call, not this pass's. Report it, do not change it.
- The `{:username …}` vs `{:id …}` login divergence (§2).
- Restricting persona creation to admin (§3a).
