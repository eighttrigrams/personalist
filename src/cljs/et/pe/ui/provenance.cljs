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
