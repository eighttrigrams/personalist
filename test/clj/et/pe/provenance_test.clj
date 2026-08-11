(ns et.pe.provenance-test
  "The adapter between an identity's version history and `us-vs-them`.

   None of the arithmetic is personalist's and none of it is tested here — the
   library has its own `caution_test.clj`, and that is the only place what the
   numbers mean is pinned. What is personalist's is two statements: which order
   the versions go in, and which authorship marker counts as us. Each is wrong
   silently — the ranges come back well-formed whichever way round the history is
   read — and each therefore has a test of its own.

   The third thing is the legend, which is words rather than arithmetic and is
   wrong-able the same quiet way: a legend that reads the spectrum backwards is a
   correct answer with a lie attached to it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [et.pe.provenance :as provenance]))

(defn- version
  "A version as `ds/get-identity-history` hands it over. That read is **oldest
   first**, so these are written oldest first too — which is the whole point of
   the test below."
  [author text]
  {:identity :notes :name "Notes" :text text :author author})

(deftest a-lone-version-is-whoever-wrote-it
  (testing "one version typed by a person — every line of it is his"
    (is (= [{:from 1 :to 2 :caution 1.0}]
           (provenance/ranges [(version "human" "his first line\nhis second line")]))))
  (testing "and one a machine user wrote is up for grabs"
    (is (= [{:from 1 :to 2 :caution 0.0}]
           (provenance/ranges [(version "daniel-machine" "a line\nanother line")])))))

(deftest the-history-is-not-reversed
  ;; THE ONE LINE MOST LIKELY TO GO QUIETLY WRONG, and it goes wrong by being
  ;; added rather than by being left out. Both sibling adapters — cookbook's
  ;; `caution/ranges` and rhizome's `provenance/of-versions` — call `(reverse …)`
  ;; on the way in, because their ladders arrive newest first. personalist's does
  ;; not: `ds/get-identity-history` orders `valid_from :asc`, which is already
  ;; the order `assess` replays in. Copying the siblings' reverse here would
  ;; attribute every line to whoever wrote the version *after* it — no crash, no
  ;; malformed answer, just a confident inversion of who wrote what.
  ;;
  ;; So this is written as the ladder the app actually produces: he wrote a line,
  ;; an agent added one under it. Read the right way round his line is sacred and
  ;; the agent's is free. Read backwards, exactly the opposite, and both answers
  ;; look equally well-formed.
  (let [ranges (provenance/ranges [(version "human" "his line")
                                   (version "daniel-machine" "his line\nthe agent's line")])]
    (is (= [{:from 1 :to 1 :caution 1.0}
            {:from 2 :to 2 :caution 0.0}]
           ranges)
        "his line first at 1.00 — a 0.00 here means a reverse has been added")))

(deftest us-is-the-literal-human-and-every-name-is-them
  ;; `ours` is the whole of what personalist contributes to the asymmetry.
  ;; Getting it the wrong way round is a total inversion no shape check catches.
  (testing "the marker `human` is us"
    (is (= #{"human"} provenance/ours)))

  (testing "and a machine user's name is them — by *not* being `human*`, so a
            machine user created years from now needs no code change here"
    (let [ranges (provenance/ranges [(version "some-machine-nobody-has-added-yet" "the agent's line")
                                     (version "human" "the agent's line\nhis line")])]
      (is (= [{:from 1 :to 1 :caution 0.0}
              {:from 2 :to 2 :caution 1.0}]
             ranges))))

  (testing "an unrecognised marker falls to them too, which is the safe direction:
            an agent told to be careful where it need not have been loses nothing"
    (is (= [{:from 1 :to 1 :caution 0.0}]
           (provenance/ranges [(version "who-knows" "a line")])))))

(deftest a-text-half-his-and-half-a-machine-s-has-a-range-at-each-end
  ;; The end-to-end shape: the answer an identity gets after the history this
  ;; feature exists to describe — the owner writes, an agent appends.
  (let [ranges (provenance/ranges
                [(version "human" "his first paragraph\nhis second paragraph")
                 (version "daniel-machine" (str "his first paragraph\nhis second paragraph\n"
                                                "the agent's line\nand another"))])]
    (is (= [{:from 1 :to 2 :caution 1.0}
            {:from 3 :to 4 :caution 0.0}]
           ranges))
    (testing "- the ranges are one-based, inclusive, and cover the text exactly once"
      (is (= 1 (:from (first ranges))))
      (is (= 4 (:to (last ranges))) "as many lines as the newest version has")
      (is (every? (fn [[a b]] (= (inc (:to a)) (:from b)))
                  (partition 2 1 ranges))
          "no gap and no overlap between consecutive ranges"))))

(deftest an-empty-text-is-one-line-and-not-a-crash
  ;; An identity's text is nullable in the schema, and a version with none is not
  ;; an error state — it is an identity whose text was emptied. The empty string
  ;; is the honest reading: one line, attributed to whoever the version is.
  (is (= [{:from 1 :to 1 :caution 1.0}]
         (provenance/ranges [(version "human" "")])))
  (is (= [{:from 1 :to 1 :caution 0.0}]
         (provenance/ranges [(version "daniel-machine" nil)]))))

(deftest the-legend-pairs-each-end-with-the-right-author
  ;; The inversion guard on the words. `ours` is #{"human"}, so the arithmetic
  ;; puts his end at 1.00 — us-is-the-literal-human pins that — and a legend that
  ;; reads them the other way round makes every correct answer a lie to the one
  ;; reader who has nothing else to go by. Pinned as the two pairings rather than
  ;; as the whole string, so rewording the middle is free and swapping the ends is
  ;; not.
  (is (str/includes? provenance/legend "1.00"))
  (is (str/includes? provenance/legend "0.00"))
  (testing "1.00 is the person and 0.00 the machine, in that order"
    (is (< (str/index-of provenance/legend "1.00")
           (str/index-of provenance/legend "0.00")))
    (is (< (str/index-of provenance/legend "1.00")
           (str/index-of provenance/legend "machine"))
        "the machine end is named after the human end, not before it"))
  (testing "and it accounts for the middle, which a count of versions could not"
    (is (str/includes? provenance/legend "between"))))
