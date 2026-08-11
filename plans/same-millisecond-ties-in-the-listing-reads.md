# The same-millisecond tie in the three listing reads

Its own round. The provenance work is done, green and filed; this is the defect
that surfaced beside it and was deliberately left alone there.

## The bug

Two versions of **one** identity sharing a `valid_from` millisecond. The three
reads that join on `max(valid_from)` match *both* rows and hand the identity
back twice:

    (ds/add-identity conn p "notes" "v1" "human" {:id :xxx-yyy :valid-from t})
    (ds/save-identity-version conn p :xxx-yyy "notes" "v2" "daniel-machine" {:valid-from t})

    list-identities        -> (… :text "v1") (… :text "v2")   same identity, twice
    list-recent-identities -> two items, same identity, same timestamp
    search-identities      -> the same duplication
    search ?valid_at       -> answers "v1" — the stale read, not the duplicate

## Why now, and why it is not a race

Not for the race — a machine writing one identity twice inside a millisecond is
luck. The API takes an **explicit `valid_from`**, so an importer stamping a batch
of versions with one timestamp hits this *every time*: deterministic, and the
shape of the first real machine-user workload anyone would write. The duplicate
also lands in the list the SPA renders, so the app looks broken, and it is
counted against the page limit — a page of five showing four identities.

## The rule, said once

**The latest version of an identity is the greatest `(valid_from, id)`.** Not
`max(valid_from)`, which is a tie waiting to happen, and not `max(id)` on its own
either — the API accepts an explicit `valid_from`, so a row written later can
carry an earlier timestamp and must not thereby become the latest. Among the rows
holding the greatest `valid_from`, the greatest `id` is the one written last.

`get-identity`, `get-identity-at` and `get-identity-history` already read that
way (`[[:valid_from :desc] [:id :desc]]`). This is the same rule for the reads
that ask it of many identities at once, and stating it in the shared seam is the
point: three fixes that agree today are three fixes that can drift.

## What to do

1. **One seam first.** `latest-versions-subquery` is shared by all three
   listing reads. If the tie can be broken there — by answering *which row* is
   the latest instead of *which timestamp* — the three inherit it and the rule is
   written down once. Report which reads needed their own touch anyway.
2. **`?valid_at` on search takes the same rule** at its time point: it is the
   `get-identity-at` question asked per identity, so it gets
   `get-identity-at`'s ordering.
3. **Paging is part of this.** `has-more` and the limit must count *distinct
   identities*: a page of N returns N distinct identities where a duplicate
   would otherwise have eaten a slot. Assert it directly rather than inferring
   it from the duplicate being gone.
4. **Page order needs the same tie-break for a different reason.** Two
   *different* identities can share a `valid_from` too, and offset paging over
   an unstable sort can show one of them on both pages and neither on the last.
   That is the same rule applied to the ordering rather than to the join.

## How

Test first, red, then implement, and **construct the collision** with one
explicit instant. A test that hopes two writes land in the same millisecond is a
test that cannot fail — that is exactly what happened to the first pin last
round, which passed three runs running with the fix removed.

Full suite green with its output; commit on `main` under the box's own identity,
no `push`, no `rebase`; never strip a `;;` comment. No tracker message: the owner
is at the terminal for this one.

## Definition of done

- The four reads answer one row per identity under a constructed tie, and
  `?valid_at` answers the later version.
- A page of N holds N distinct identities, and paging is stable across pages.
- The rule written once, where the next reader will look for it.
- Full suite green, report in `handoffs/`, this file in `plans/done/` with a
  note at the top.
