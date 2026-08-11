# An account may hold no personas at all

**Done 2026-08-11.** Commits `19d57c2` (the 409 goes), `b83bb57` (POST
/api/accounts creates the account only), `65529bf` (admin-only `:account_id`),
`c203bca` (/api/me with none), `1c00940` (seed script), `34f8830` (the UI),
`4b0e0dd` (the reload landing).

**What did the trick:** noticing that "logged in" and "has an active persona"
had been the same thing in the client, and separating them before touching
anything else in the UI. `logged-in?` is now a property of `:account`;
`:auth-user` says only which persona is active, and may be nil. Every other UI
change in this round — hiding the `+` and search buttons, naming the account in
the header, landing on the profile page — falls out of that one distinction.
Without it, relaxing the constraint would have left a header full of buttons
acting on nothing.

The second thing: the order asked me to say where a cycle went green by
deleting a branch, and holding to that changed what I wrote. A test that only
asserts a deletion is worth little; a test that asserts the *new freedom* —
remove the only persona, then check the account survives, holds an empty list,
and can still log in — is what actually pins the behaviour down.

## What changed

Zero personas is a legitimate state. This overturns a rule I wrote in the
accounts-above-personas order and defended twice — *"an account with no persona
is a login that leads nowhere"*. It leads to the profile page.

- `delete-persona-handler` lost its 409 and the docstring clause that argued
  for it. The hand-typed confirmation is the whole guard.
- `POST /api/accounts` creates only the account, and answers its `:id`.
- `POST /api/personas` gained an admin-only `:account_id`, which is how a fresh
  account gets its first persona from outside — and how the seed script works
  at all now.
- The UI: empty state on the profile page, Remove on the last persona, and a
  header that offers nothing acting on a persona that is not there.

## Decisions worth remembering

- **`:account_id` is refused for a human even when it names their own account.**
  Obeying it would have been harmless, ignoring it would have been quiet. The
  field belongs to admin, so there is exactly one rule about it and no shape of
  it an ordinary token may use.
- **An `:account_id` naming no account is a 404**, not a persona hanging off
  nothing. And since `ds/get-account` answers for humans only, one naming a
  machine user's row is a 404 too: you cannot hang a persona off a credential.
- **Admin with no `:account_id` is a 400, not a 401.** It is authenticated;
  "your request is missing something" is the honest answer.
- **A reload lands on the profile page only when the URL names no persona.**
  Arriving at `/someone-else` means to look at someone else.

## Known limitation

In dev with `:dangerously-skip-logins?` an account holding no personas cannot be
entered: that mode identifies an account by one of its personas (`?persona=`),
so an account with none has no name to give. It is a limitation of the dev
shortcut, not of the feature, and inventing a second dev auth path to work
around it would be worse than the gap.
