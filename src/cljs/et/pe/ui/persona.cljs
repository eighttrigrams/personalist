(ns et.pe.ui.persona
  "What a persona looks like wherever one is *listed* rather than entered. That
   is three places — the account's own profile page, the two persona pickers, and
   the admin's account roster — and since 006 they all have the same thing to
   say about one: whether it is private.

   A namespace for one badge, on the same grounds as et.pe.ui.provenance: it is
   presentational, it belongs to no page in particular, and the alternative is
   modals and settings reaching into the profile page for it.")

(def private-badge
  "The mark on a persona only its own account can read. Muted rather than
   alarming — it is a choice its owner made, not a warning — and worded as the
   thing itself rather than as a lock or an eye, because the picker it appears in
   is read at a glance.

   It is only ever drawn on a row the caller was entitled to see: the server
   leaves a private persona out of the list altogether for everybody else, so the
   badge cannot be a tell."
  [:span {:style {:font-size "0.7rem" :letter-spacing "0.04em" :text-transform "uppercase"
                  :color "#5c4b8a" :background "#ede9f6" :border "1px solid #d5cbee"
                  :border-radius "3px" :padding "0.1rem 0.35rem" :white-space "nowrap"}}
   "private"])
