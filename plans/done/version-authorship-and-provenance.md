> **Done 2026-08-11.** Six commits, one per stage, `4a30cde` → `2a2cd82`:
> migration 005 · the principal carried to the write · `et.pe.provenance` ·
> the guarded read · the view · the docs. Full suite green at 109 tests / 729
> assertions; checked in a Playwright-driven browser and reported in
> `handoffs/personalist-version-authorship-and-provenance-report.md`.
>
> **What did the trick** was that the plan's two silent-failure warnings were
> both real, and writing the test *first* is what caught a third:
>
> - The `(reverse …)` both sibling adapters need and this one must not have is
>   pinned by `provenance-test/the-history-is-not-reversed`, checked by adding
>   the reverse and watching four assertions redden.
> - Making `author` a required *positional* argument turned every forgotten
>   write into an arity error at compile time, which is how all of them were
>   found.
> - **Not in the plan:** `ds/get-identity-history` did not tie-break on `id`.
>   `valid_from` is epoch milliseconds and two versions written in the same
>   millisecond — an agent writing in a loop, precisely the writer this
>   feature is about — sorted arbitrarily, and a replay in the wrong order
>   attributes lines to whoever wrote the version beside them. Found by
>   `a-machine-user-s-version-carries-its-own-name`, which asked for three
>   versions in a row and got them back in the wrong order.
>
> The deploy plumbing needed nothing added: `plurama/Dockerfile` already puts
> the sibling at `/opt/us-vs-them` for cookbook's sake, and does so before the
> uberjar step that resolves personalist's classpath. Details, plus the
> re-takeable-machine-name caveat, are in the report.

# Version authorship, and a provenance view for the human who owns the persona

## What the owner asked for

Verbatim, in the order he said it:

1. *"from now on, we should track that, and when it was a machine, store the
   user-name of that machine user as author of that change."*
2. *"retrofit that all old versions, everything is stamped human."*
3. *"and then implement that same scheme using us-vs-them which cookbook, and
   rhizome already use."*
4. *"also add a provenance view, with which logged in human users can use to see
   provenance, but only of their own Personas — i.e. those created by themselves
   or by machine users associated under their accounts."*

The starting point is that personalist records **nothing** about who wrote a
version. `wrap-auth` (`src/clj/et/pe/server.clj:167`) builds a principal
(`:admin` / `:human` / `:machine`), uses it to answer *may you write this
persona?*, and then throws it away — it never reaches a handler. An identity
version row carries content and `valid_from` and nothing else. `specs/startup.md`
says it out loud today: *"a persona written by a machine is indistinguishable
from any other."* That sentence is what this work removes.

## The scheme, as cookbook and rhizome already have it

Read both before starting — this is an adaptation of a pattern that exists
twice, not a new invention:

- `cookbook/src/clj/et/cb/caution.clj` — the adapter. Every Recipe version
  carries `source` of `'ui'` or `'machine'`; the namespace holds three
  translations and a `legend`, and *none* of the arithmetic.
- `cookbook/resources/migrations/net/et/cb/010-backfill-version-source.edn` —
  the retrofit, and the argument for why writing the owner's answer down is not
  the same act as a schema guessing one. Ask (3)+(2) is the same move.
- `rhizome/src/clj/provenance.clj` — the same adapter against a different
  vocabulary (`app`/`obsidian` are us, `api`/`scraper` are them).
- `cookbook/src/cljs/et/cb/ui/provenance.cljs` — how the answer gets onto a
  screen. Follow its presentation idiom for ask (4).

The library is `et.uvt.caution/assess` in the `us-vs-them` sibling checkout. It
is blind to what a marker means and only ever asks whether two are equal; the
`{:ours #{...}}` option is where the app names its own side. That property is
what makes storing a machine's *name* work without touching the library.

## Decisions already taken — implement these, do not redesign them

**One column, `identities.author`, holding either the literal `human` or the
machine user's name.** A single string marker is what both siblings do, and it
is exactly the shape `assess` consumes. `ours` is then `#{"human"}` and every
machine name falls to *them* automatically — including a machine created after
this ships, with no code to update. An unrecognised marker landing on *them* is
also the right direction to fail, the same argument `rhizome/provenance.clj`
makes about an unknown source marker.

**The name `human` is refused as a machine-user name.** Otherwise a machine
could be created that forges human authorship, which is the same class of hole
as the escalation trap in `7d5fc8d`. Guard it in `add-machine-user` and in the
rename path, with a 400, and pin it with a test.

**`ALTER TABLE identities ADD COLUMN author TEXT NOT NULL DEFAULT 'human'` —
one statement, no table rebuild.** SQLite refuses `NOT NULL` on an added column
only when there is no default; with one it is legal. So the constraint and the
retrofit are the same statement, and every version that exists today is stamped
`human` by it. That is ask (2), and it is *recording the owner's answer*, not a
schema guessing — see 010's prose in cookbook, which draws exactly this
distinction. It also means no `identities` rebuild, so none of the
`sqlite_sequence` high-water-mark care that 003 and 004 needed applies here.

**But the column default must not become the way authorship gets set.** A future
write path that forgets to pass an author would silently claim a human wrote it
— a false claim in the dangerous direction. So the loud failure moves into the
function signature: `author` is a **required positional argument** of
`ds/save-identity-version` and `ds/add-identity`, not an entry in their trailing
opts map. Forgetting it is then an arity error at compile time, not a lie in the
database. Say this in the migration's comment, since the comment is where the
next reader will look for why a default is acceptable here.

**The public history read stays authorless.** `GET
/api/personas/:name/identities/:id/history` is public and must not grow an
`author` key: machine-user names would become public, and *"Nothing about
machine users is public"* is a standing property of this app. Ask (4) is served
by a **new, guarded** read instead.

**`get-identity-history` returns oldest first — do not reverse it.** This is the
one line most likely to go quietly wrong. `ds/sqlite.clj:526` orders
`valid_from :asc`, which is already the order `assess` wants. Both siblings
*must* reverse, because their ladders arrive newest-first, and both say in their
docstrings that reading a history backwards produces a well-formed answer with
every line attributed to whoever wrote the version *after* it — no crash,
nothing malformed, just confidently inverted. Copying their `(reverse …)` here
would introduce exactly that bug. Write a test that fails if a `reverse` is ever
added.

## The work

Strictly test-driven: for each item write the test, run it and see it red, then
implement. Delete anything no test drives.

### 1. Migration `005-version-authorship.edn`

`resources/migrations/net/et/pe/`. The one `ALTER TABLE` above; `:down` is
`ALTER TABLE identities DROP COLUMN author`. Comment it in the register the
existing four migrations are written in — why a default is right here, and why
the required argument is what keeps the default from becoming a silent claim.

Test in `test/clj/et/pe/ds/migrations_test.clj`: versions written before the
migration all read `human` after it, and the column rejects a NULL.

### 2. Carry the principal to the write

- `wrap-auth`: pass the principal down, `(handler (assoc req :principal principal))`.
- A helper in `handlers` — `author-of` — answering the marker for a request:
  `:machine` → its `:name`; `:human` and `:admin` → `"human"`; **no principal at
  all → `"human"`**. That last case is dev mode, where `wrap-auth` does not
  engage at all (`prod-mode?`), and a write there is a hand at a keyboard.
  Admin is a human hand too, even when editing another account's persona.
- Thread it through every path that inserts a version: identity create, identity
  update, and the relation add/remove writes — a relation change makes a version
  (see `002`/`save-identity-version`), so it gets an author like any other.

Tests: a machine token's write lands its name in the row; a human JWT lands
`human`; dev mode lands `human`; a relation-only change lands the author of the
call that made it.

### 3. `et.pe.provenance` — the adapter

New clj namespace, modelled on `et.cb.caution`. `ours` = `#{"human"}`, a
`legend` in personalist's own vocabulary (one line, handed out *with* the
numbers — the reader may be an agent that fetched one identity and read nothing
else), and a function from a version list to ranges. No arithmetic here.

`deps.edn` gains `eighttrigrams/us-vs-them {:local/root "../us-vs-them"}`, with
a comment like cookbook's and rhizome's. Then **verify, do not rebuild, the
deploy plumbing**: cookbook and rhizome already put the sibling on the box's and
the deploy's classpath (`a2bc365`), so check `clj -Stree` resolves it in the box
and check whether `plurama/Dockerfile` and the workspace `.dockerignore` need
personalist named anywhere. Report what you find rather than guessing.

Tests: the no-reverse pin described above; `ours`; and one end-to-end shape check
that a text half written by hand and half by a machine comes back with a range
at each end.

### 4. The guarded provenance read

`GET /api/personas/:name/identities/:id/provenance`.

- 401 without a token.
- 403 unless the caller is a **human** account (or admin) that holds the
  persona. A machine token is refused here — ask (4) is for logged-in humans.
  `handlers/owning-account`, which the `/api/machine-users` routes already use
  for the same reason, is the pattern to follow.
- Body: the legend, the ranges, and the per-version authorship
  (`valid-from` + `author`) so the view can name who wrote each version.
- Note that "their own personas" needs no new ownership concept: a persona a
  machine user created belongs to the **parent account** already
  (`666bd50`, and `specs/startup.md`), so `personas.account_id = token's
  account` is the whole test.

This becomes the **third** guarded GET, after `/api/me` and `/api/accounts`.
`specs/startup.md` states that there are two — it has to be corrected, not left.

Tests: anonymous 401; another account's token 403; a machine token 403 even for
a persona it may *write*; the owner 200.

### 5. The view

In the UI, for a logged-in human, on their own persona's identity. Follow
`et.cb.ui.provenance`'s idiom: the caution spectrum over the text, plus what the
badge does there — say in words what `1.00` and `0.00` mean, since a bare number
is unreadable to someone who has not read this codebase. Hide it entirely when
the persona is not the logged-in account's; do not render a disabled or empty
version of it for a visitor.

### 6. Docs

`specs/startup.md`: a section for authorship and the provenance view, the
guarded-GET count corrected, and the sentence *"a persona written by a machine is
indistinguishable from any other"* replaced by what is now true — nothing about
machine users is public, **and** the account that owns a persona can see which
of its machines wrote what.

## Before you edit: line heritage

This repo weighs human-authored lines above generated ones, and the check is
mechanical. **Before editing an existing `.md` or `.edn` file**, run
`us-vs-them` on it from inside the repo:

```sh
us-vs-them --ours dan@eighttrigrams.net <path>
```

It prints the human-authored "islands". Lines with a proven human heritage need
a much better reason to be touched than generated ones — prefer appending or
working around them. Files you create in this task have no heritage, so nothing
to check. It applies here to `specs/startup.md` (markdown: always) and to the
existing migration `.edn` files if you touch one (config files: always). Clojure
source in personalist is *not* under this rule.

Also standing: **never strip an existing `;;` comment** while refactoring. The
comments in this codebase carry the arguments for the decisions and are worth
more than the code around them.

## Definition of done

- `make stop`, full test suite green, reported with its output.
- The UI checked in a browser by natural interaction — log in, look at an
  identity's provenance, confirm a visitor and another account see nothing —
  and the browser left open with a reproduction guideline for the owner.
- Committed in logical commits with the box's own git identity, on `main`, no
  `push`, no `rebase`.
- This file moved to `plans/done/` with a note at the top: when, what did the
  trick, and the commits.
