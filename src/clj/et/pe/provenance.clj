(ns et.pe.provenance
  "How careful an agent should be in each part of an identity's text — the
  `us-vs-them` question, asked of the version ladder `ds/get-identity-history`
  already keeps.

  **Personalist now records the two things the library needs.** Since migration
  005 every identity version carries an `author` — the literal `human`, or the
  name of the machine user that wrote it — and every version keeps its own text,
  because a version *is* a row here and nothing is ever overwritten. That is
  exactly a history of texts under identifiable authorship, which is the only
  input `et.uvt.caution/assess` asks for, so this namespace is an adapter and
  nothing more. None of the arithmetic is here and none of it should come here:
  the library is a sibling checkout wired in by `:local/root`, and its
  `caution_test.clj` is the specification of what the numbers mean.

  What *is* personalist's is the two statements below, and the words handed out
  with the answer. Both statements are small and both are silently wrong-able —
  the ranges come back well-formed whichever way round the history is read, and
  whichever side is called ours — which is why each has a test of its own next
  door.

  **This is the same adapter cookbook and rhizome each have, and it differs from
  both in one line: there is no `reverse` here.** Their ladders arrive newest
  first and mine arrives oldest first. See `ranges`."
  (:require [et.uvt.caution :as uvt]))

(def ours
  "Which authorship marker counts as us: the one a person's write carries.

  A set of one, and it stays a set of one however many machine users the owner
  creates. That is the point of storing a machine's *name* rather than the word
  `machine`: `assess` is blind to what a marker means and only ever asks whether
  two are equal, so naming our side once here puts every other marker on theirs
  automatically — a machine user made years after this shipped, with no code
  changed and no enumeration to keep in step.

  It also means an **unrecognised** marker falls to them, which is the right
  direction to fail, the same argument `rhizome/provenance` makes about an
  unknown source. Being needlessly careful in a stretch an agent could have
  rewritten costs an edit nobody made; the other mistake tells an agent that the
  owner's own paragraph is free to overwrite.

  `handlers/author-of` is what puts this string in the column, and migration 005
  is what put it in every row that predates the column. Those two and this are
  the only places the literal appears in the clj side."
  #{"human"})

(def legend
  "How to read the numbers, in one line, to be handed out *with* them on every
  read that carries them.

  Not redundancy, and not documentation that belongs in `/api/describe`: the
  reader may be an agent that fetched one identity and read nothing else in this
  codebase, and to that reader a bare `0.0` beside a line range is a number it
  would have to already know how to read. So the scale travels with the answer.
  Cookbook's `caution/legend` is here for the same reason and says so at greater
  length.

  In personalist's own vocabulary — its markers are `human` and machine-user
  names, and an agent holding this string is not in a position to translate
  someone else's app's.

  What it adds to a count of versions is the middle. A version is one author's or
  the other's; a *line* can sit in a stretch both have worked on, and a number
  between the ends is what says so."
  (str "1.00 is a stretch written wholly by hand and is not yours to rewrite; "
       "0.00 is a stretch written wholly by a machine user and is free to edit. "
       "In between, both have worked on it and the number is the share of its "
       "lines that are the person's — so anything above 0.00 still has a line of "
       "theirs in it."))

(defn ranges
  "The ranges of an identity's newest text, each with how careful an agent should
  be in it — `1.0` the person's, `0.0` up for grabs, and the spectrum in between
  where the two have been mixed. `[{:from :to :caution}]`, one-based and
  inclusive, in the numbering an editor already uses.

  `versions` is `(ds/get-identity-history …)` **as it comes**. Two things happen
  to it here and each is a decision:

  - **It is not reversed**, and that is the line worth reading twice. `assess`
    replays a history forwards; `get-identity-history` orders `valid_from :asc`
    and so already arrives oldest first. Cookbook and rhizome both *must*
    reverse, because their ladders come newest first, and both spend a paragraph
    on it — so the tempting thing to do when adapting their code is to copy the
    `(reverse …)` across. That would introduce exactly the bug their paragraphs
    are about: the fold would replay the history backwards and every line would
    be attributed to whoever wrote the version *after* it, which is not a crash
    and not a malformed answer, just a confident inversion of who wrote what.
    `provenance-test/the-history-is-not-reversed` is what says so.
  - **The text is the version's `:text`**, not its `:name`. A name is one line,
    and there is nothing to be careful *within* a single line: caution is a
    statement about where inside a text the boundaries are, so it is asked of the
    only field that has an inside.

  A nil text is read as the empty string — one line, attributed to whoever the
  version is. The column is nullable and an identity whose text was emptied is
  not an error state, so this refuses to throw on it rather than guarding
  against a missing column.

  There is always at least one version, so there is always an answer: an identity
  exists by having been written."
  [versions]
  (uvt/assess (mapv (fn [{:keys [text author]}]
                      {:text (or text "") :source author})
                    versions)
              {:ours ours}))
