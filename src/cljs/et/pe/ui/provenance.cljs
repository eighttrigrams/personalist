(ns et.pe.ui.provenance
  "The view's half of the provenance answer: ranges into lines, and a number into
  a colour.

  Nothing here computes anything about who wrote what — that is
  `et.pe.provenance` (clj) over `us-vs-them`, and it arrives already answered on
  GET /api/personas/:name/identities/:id/provenance. What this adds is the two
  translations a screen needs, and both are small enough to be got quietly
  wrong, which is why they are named rather than inlined into the component.

  Follows `et.cb.ui.provenance` in cookbook, which does the same job for a
  Recipe's body — down to the two ends of the scale and the colour space they are
  mixed in, though cookbook keeps them in CSS custom properties because it has
  two themes to answer for and this app has one."
  (:require [clojure.string :as str]))

(defn split-lines
  "A text into its lines, **the way the server numbered them**.

  Not `clojure.string/split-lines`, and the difference is not cosmetic: that one
  drops trailing empty lines and the ranges keep them. A text ending in a
  newline — which is most texts typed into a textarea — is `n+1` lines to the
  API and `n` to `split-lines`, so the view would be one row short of the answer
  it is drawing, at the end, silently. `-1` keeps them."
  [text]
  (str/split (or text "") #"\n" -1))

(defn line-cautions
  "The API's ranges flattened to one number per line, indexed from 0 for the
  view's `map-indexed`.

  The answer arrives as `[{:from :to :caution}]` over one-based inclusive lines
  because that is how the underlying question is answered: the library measures
  *islands* of the person's writing rather than lines, and a stretch's number is
  a property of the stretch. Expanding it per line is a **view** convenience and
  not a truer reading of it — every line of one range carries that range's
  number, the middling ones included.

  `line-count` is passed in rather than taken from the last range, so the rows
  and the numbers come from the same string the view is about. They agree today;
  a view drawn from one text and tinted from a stale answer is the one way they
  could stop agreeing, and this makes that show up as an untinted row rather
  than as a colour attributed to the wrong line."
  [ranges line-count]
  (let [by-line (reduce (fn [acc {:keys [from to caution]}]
                          (reduce #(assoc %1 %2 caution) acc (range from (inc to))))
                        {}
                        ranges)]
    (mapv #(get by-line (inc %)) (range line-count))))

;; ---------------------------------------------------------------------------
;; Aligning a draft against the text the ranges are about
;;
;; The provenance tab used to draw the **last saved** text and only that, whatever
;; was in the editor — so a line just typed into it was not on the tab at all,
;; while the tab went on calling itself *the text as it stands now*. The owner
;; reported it against cookbook and asked whether the same held here:
;;
;; > i think you need to compare how provenance looks before and after a change.
;; > the interesting case is when i insert human edit into agentic surroundings
;;
;; It did, in its own way: cookbook showed the draft and went blank below the
;; caret, this showed a different document. Both answers left out the one thing he
;; was looking at.
;;
;; What follows is `et.cb.ui.provenance`'s, function for function and rule for
;; rule, for the reason the ns docstring above gives about the colour scale: one
;; mechanism means one thing across the suite, and a reader who has learnt how a
;; Recipe previews should not have to learn this again. Cookbook's copy carries the
;; long argument; this one carries the summary and the pointer.
;; ---------------------------------------------------------------------------

(def ^:private alignment-budget
  "The largest DP table to build once the common head and tail are off, in cells.
  Beyond it the middle is left unaligned rather than aligned slowly — 200 changed
  lines against 200 is a rewrite, and *we do not know* is the honest reading of
  one. Ordinary typing never approaches it: the trim leaves a middle the size of
  what was touched."
  40000)

(defn- prefix-count
  "How many lines `a` and `b` share from the top."
  [a b]
  (let [n (min (count a) (count b))]
    (loop [i 0]
      (if (and (< i n) (= (nth a i) (nth b i))) (recur (inc i)) i))))

(defn- suffix-count
  "How many lines `a` and `b` share from the bottom, without running back into the
  `already` lines the head claimed — so head and tail cannot overlap and the middle
  is never a negative slice."
  [a b already]
  (let [na (count a) nb (count b)
        n (- (min na nb) already)]
    (loop [i 0]
      (if (and (< i n) (= (nth a (- na i 1)) (nth b (- nb i 1))))
        (recur (inc i))
        i))))

(defn- lcs-alignment
  "For each index of `b`, the index of `a` a longest common subsequence matches it
  to, or nil. Plain O(n·m) dynamic programming over an `Int32Array`, filled from
  the bottom right so the walk back out reads forwards.

  A line can be identical and still come back nil when the chosen subsequence did
  not include it — two lines swapped round is the plain case. `draft-cautions`
  says why that lands on the safe side."
  [a b]
  (let [na (count a) nb (count b)
        w (inc nb)
        dp (js/Int32Array. (* (inc na) w))]
    (loop [i (dec na)]
      (when (>= i 0)
        (loop [j (dec nb)]
          (when (>= j 0)
            (aset dp (+ (* i w) j)
                  (if (= (nth a i) (nth b j))
                    (inc (aget dp (+ (* (inc i) w) (inc j))))
                    (max (aget dp (+ (* (inc i) w) j))
                         (aget dp (+ (* i w) (inc j))))))
            (recur (dec j))))
        (recur (dec i))))
    (let [out (js/Array. nb)]
      (loop [i 0 j 0]
        (when (< j nb)
          (cond
            (>= i na)
            (do (aset out j nil) (recur i (inc j)))

            (= (nth a i) (nth b j))
            (do (aset out j i) (recur (inc i) (inc j)))

            (>= (aget dp (+ (* (inc i) w) j)) (aget dp (+ (* i w) (inc j))))
            (recur (inc i) j)

            :else
            (do (aset out j nil) (recur i (inc j))))))
      (vec out))))

(defn- aligned-to-stored
  "For each line of `draft`, which line of `stored` it **is** — an index into
  `stored`, or nil for a line being typed now, or `:unknown` where the alignment
  was not computed.

  The third is not a nil and the two must not be collapsed: *you typed this* and
  *we did not work it out* have opposite consequences one function along, and a
  budget overrun that read as the first would claim a pasted-in body was all his."
  [stored draft]
  (let [ns (count stored) nd (count draft)
        p (prefix-count stored draft)
        s (suffix-count stored draft p)
        a (subvec stored p (- ns s))
        b (subvec draft p (- nd s))
        mid (if (> (* (count a) (count b)) alignment-budget)
              (vec (repeat (count b) :unknown))
              (mapv #(when (number? %) (+ p %)) (lcs-alignment a b)))]
    (-> (vec (range 0 p))
        (into mid)
        (into (map #(+ (- ns s) %)) (range s)))))

(defn draft-cautions
  "One number per line for the text **in the editor**, aligned against the ranges
  the server sent for the saved one.

  > A draft line the diff matches to a saved line keeps that line's caution,
  > wherever it has moved to. A line the diff matches to nothing is one being typed
  > now, so it is the person's.

  The second sentence is a claim, and what backs it is where this is called from:
  the text field of an identity the account holds, whose Save writes a version
  `handlers/author-of` stamps `human`. A line in the draft and in no saved line
  reached the text through that field, so it is theirs by the rule the server will
  apply the moment they press Save.

  **Where it is imprecise it is imprecise towards the person's end**, which is the
  safe way round and the same direction `et.pe.provenance/ours` already chooses:
  an unrecognised marker falls to *them*, so the mistake is being needlessly
  careful. Blue where red belonged costs an agent a line it could have rewritten;
  red where blue belonged costs the person a sentence.

  It survives nothing: the next read of the guarded provenance route brings ranges
  computed by `us-vs-them` over the text that actually landed."
  [saved-text ranges draft-text]
  (let [saved (vec (split-lines saved-text))
        saved-cautions (line-cautions ranges (count saved))
        draft (vec (split-lines draft-text))]
    (mapv (fn [m]
            (cond
              (= :unknown m) nil
              (nil? m) 1.0
              :else (nth saved-cautions m nil)))
          (aligned-to-stored saved draft))))

(def ^:private human-end
  "The hand-written end of the scale. Cookbook's `--provenance-human`, kept to the
  same value on purpose: one spectrum means one thing across the suite, and a
  reader who has learnt it on a Recipe should not have to learn it again here."
  "#1d6fd4")

(def ^:private agent-end
  "The machine-written end. Cookbook's `--provenance-agent`."
  "#d1344b")

(defn tint
  "The colour of a line's marker, from its caution.

  Blue at `1.0` for the person's own writing, red at `0.0` for a machine user's,
  and a real mix in between. The ends are cookbook's, and so is the reading: red
  is not a warning about the line, it says the provenance is a machine's — which
  is the thing this app weighs, and the same line is the *free* one to rewrite.

  **`oklch` and not `oklab` or `srgb`**, which is cookbook's finding and worth
  keeping in both places: oklch carries the hue round the wheel and keeps the
  chroma up, where the other two cut straight across and lose it — the midpoint
  of this blue and this red comes out a pale grey, so a half-and-half stretch
  would read as fainter than both of the ends it sits between instead of as a
  purple at the same strength.

  Nil is not a point on the spectrum — it is a line the answer says nothing
  about — so it gets neither end, and the caller draws it hollow."
  [caution]
  (when (number? caution)
    (let [pct (js/Math.round (* 100 caution))]
      (str "color-mix(in oklch, " human-end " " pct "%, " agent-end ")"))))
