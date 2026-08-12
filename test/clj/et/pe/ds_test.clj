(ns et.pe.ds-test
  (:require [clojure.test :refer [deftest testing is are use-fixtures]]
            [et.pe.ds :as ds])
  (:import [java.time Instant]))

(def ^:dynamic *conn-type* nil)

(def ^:dynamic conn nil)

(defn sqlite-in-memory [f]
  (binding [*conn-type* :sqlite-in-memory]
    (f)))

(defmacro testing-with-conn [string & body]
  `(testing ~string (binding [conn (ds/init-conn *conn-type* {})]
                      ~@body
                      (ds/close-conn conn))))

(defmacro are= [& body]
  `(are [expected actual] (= expected actual) ~@body))

(defmacro sets-are= [& body]
  `(are [expected actual] (= (set expected) (set actual)) ~@body))

(use-fixtures :once sqlite-in-memory)

(defn- persona!
  "An account with one persona under it, and that persona back. Most tests here
   only want something to hang identities off; since 003 that takes two rows."
  [id email]
  (ds/add-persona conn (ds/add-account conn email nil) id nil)
  (ds/get-persona-by-id conn id))

(deftest accounts
  (testing-with-conn "an account is an email and a password and nothing else"
    (let [acc (ds/add-account conn "d@et.n" "hash-of-d")]
      (testing "- minting one hands back its id"
        (is (integer? acc)))
      (testing "- an email can only be spent once"
        (are=
         false (ds/add-account conn "d@et.n" "hash-of-someone-else")
         1 (count (ds/list-accounts conn))))
      (testing "- and it is found by that email, or by its id"
        (are=
         {:id acc :email "d@et.n"} (ds/get-account-by-email conn "d@et.n")
         {:id acc :email "d@et.n"} (ds/get-account conn acc)
         nil                       (ds/get-account-by-email conn "nobody@et.n")))
      (testing "- the password now hangs off the account, not off a persona"
        (are=
         "hash-of-d" (ds/get-account-password-hash conn acc)
         nil         (ds/get-account-password-hash conn 9999))))))

(deftest personas-belong-to-an-account
  (testing-with-conn "many personas under one email"
    (let [mine (ds/add-account conn "d@et.n" nil)
          theirs (ds/add-account conn "e@et.n" nil)]
      (are=
       true (ds/add-persona conn mine :dan nil)
       true (ds/add-persona conn mine :dan2 "Second Face")
       true (ds/add-persona conn theirs :eve nil))
      (testing "- a persona id is global, because it is the public address"
        (are=
         false (ds/add-persona conn theirs :dan "not yours")
         3     (count (ds/list-personas conn))))
      (testing "- what an anonymous reader gets carries no email at all"
        (sets-are=
         [{:id :dan :name "dan" :private? false}
          {:id :dan2 :name "Second Face" :private? false}
          {:id :eve :name "eve" :private? false}]
         (ds/list-personas conn)))
      (testing "- an account's own personas, in the order it holds them"
        (are=
         [{:id :dan :name "dan" :sort-order 0 :private? false}
          {:id :dan2 :name "Second Face" :sort-order 1 :private? false}]
         (ds/list-personas-for-account conn mine)
         [{:id :eve :name "eve" :sort-order 0 :private? false}]
         (ds/list-personas-for-account conn theirs)))
      (testing "- a persona knows which account holds it; that is the ownership check"
        (are=
         {:id :dan :name "dan" :account-id mine :sort-order 0 :private? false} (ds/get-persona-by-id conn :dan)
         {:id :eve :name "eve" :account-id theirs :sort-order 0 :private? false} (ds/get-persona-by-id conn :eve)
         nil (ds/get-persona-by-id conn :nobody)))
      (testing "- the display name is the persona's own, and one of the two things editable"
        (ds/update-persona conn :dan {:name "Renamed"})
        (are=
         "Renamed" (:name (ds/get-persona-by-id conn :dan))
         nil       (ds/update-persona conn :nobody {:name "x"})))
      (testing "- the other is whether it is private, and absent is not false"
        (ds/update-persona conn :dan {:private? true})
        (are= true (:private? (ds/get-persona-by-id conn :dan)))
        (testing "- a rename says nothing about it, so it cannot publish by omission"
          (ds/update-persona conn :dan {:name "Renamed again"})
          (are=
           true          (:private? (ds/get-persona-by-id conn :dan))
           "Renamed again" (:name (ds/get-persona-by-id conn :dan))))
        (testing "- and an update naming nothing at all is a no-op, not a syntax error:
                  honeysql renders an empty :set as `UPDATE personas SET WHERE ...`"
          (are= {:success true} (ds/update-persona conn :dan {}))
          (are= true (:private? (ds/get-persona-by-id conn :dan))))
        (testing "- false publishes it again"
          (ds/update-persona conn :dan {:private? false})
          (are= false (:private? (ds/get-persona-by-id conn :dan)))))
      (testing "- a persona can be minted private, so it is never public for an instant"
        (are=
         true  (ds/add-persona conn mine :hidden "Hidden" {:private? true})
         true  (:private? (ds/get-persona-by-id conn :hidden))
         false (:private? (ds/get-persona-by-id conn :dan2)))
        (testing "- and its id is spent like any other address"
          (are= false (ds/add-persona conn theirs :hidden "Mine now")))))))

(deftest listing-accounts-for-the-admin
  (testing-with-conn "the Settings listing: accounts, their emails, their personas"
    (let [a (ds/add-account conn "a@et.n" nil)
          b (ds/add-account conn "b@et.n" nil)]
      (ds/add-persona conn a :one "One")
      (ds/add-persona conn a :two "Two")
      (ds/add-persona conn b :three "Three")
      (are=
       [{:id a :email "a@et.n" :personas [{:id :one :name "One" :sort-order 0 :private? false}
                                          {:id :two :name "Two" :sort-order 1 :private? false}]}
        {:id b :email "b@et.n" :personas [{:id :three :name "Three" :sort-order 0 :private? false}]}]
       (ds/list-accounts conn)))))

(deftest deleting-a-persona-takes-its-identities-with-it
  (testing-with-conn "removal is real destruction — no history, no undo"
    (let [acc (ds/add-account conn "d@et.n" nil)
          _ (ds/add-persona conn acc :doomed nil)
          _ (ds/add-persona conn acc :spared nil)
          doomed (ds/get-persona-by-id conn :doomed)
          spared (ds/get-persona-by-id conn :spared)
          gone (ds/add-identity conn doomed "goes" "away" "human")
          kept (ds/add-identity conn spared "stays" "put" "human")]
      ;; a second version, so it is the whole history that goes and not one row
      (ds/update-identity conn doomed gone "goes" "away, edited" "human")
      (are=
       2 (count (ds/get-identity-history conn doomed gone))
       1 (count (ds/list-identities conn spared)))

      (is (true? (ds/delete-persona conn :doomed)))

      (testing "- the persona row is gone"
        (are=
         nil          (ds/get-persona-by-id conn :doomed)
         [{:id :spared :name "spared" :private? false}] (ds/list-personas conn)))
      (testing "- and every version of every identity under it"
        (are=
         []  (ds/get-identity-history conn doomed gone)
         nil (ds/get-identity conn doomed gone)))
      (testing "- while the account's other persona is untouched"
        (are=
         [{:identity kept :name "stays" :text "put"}] (ds/list-identities conn spared)))
      (testing "- deleting what is not there is false, not an exception"
        (is (false? (ds/delete-persona conn :doomed)))))))

(deftest identities
  (testing-with-conn "add and retrieve identities"
    (let [dan (persona! :dan "d@et.n")
          dan2 (persona! :dan2 "d2@et.n")
          id11 (ds/add-identity conn dan "name11" "text11" "human")
          id12 (ds/add-identity conn dan "name12" "text12" "human")
          id21 (ds/add-identity conn dan2 "name21" "text21" "human")
          id22 (ds/add-identity conn dan2 "name22" "text22" "human")]
      (sets-are=
       [{:identity id11
         :name     "name11"
         :text     "text11"}
        {:identity id12
         :name     "name12"
         :text     "text12"}]
       (ds/list-identities conn dan)
       [{:identity id21
         :name     "name21"
         :text     "text21"}
        {:identity id22
         :name     "name22"
         :text     "text22"}]
       (ds/list-identities conn dan2)))))

(deftest every-version-remembers-who-wrote-it
  (testing-with-conn "the marker is a name, and it goes in and comes back verbatim"
    (let [dan (persona! :dan "d@et.n")
          t1 (Instant/parse "2020-01-01T00:00:00Z")
          t2 (Instant/parse "2020-06-01T00:00:00Z")
          t3 (Instant/parse "2020-12-01T00:00:00Z")
          id (ds/add-identity conn dan "notes" "his own first line" "human" {:valid-from t1})]
      (ds/save-identity-version conn dan id "notes" "an agent's second" "daniel-machine" {:valid-from t2})
      (ds/update-identity conn dan id "notes" "another agent's third" "other-machine" {:valid-from t3})
      (testing "- the history says who wrote each version, oldest first"
        (are=
         [["human" "his own first line"]
          ["daniel-machine" "an agent's second"]
          ["other-machine" "another agent's third"]]
         (mapv (juxt :author :text) (ds/get-identity-history conn dan id))))
      (testing "- nothing here validates the marker: the app takes sides in
                et.pe.provenance, and one it has never seen falls to *them*"
        (let [odd (ds/add-identity conn dan "odd" "text" "something-else-entirely")]
          (are=
           ["something-else-entirely"]
           (mapv :author (ds/get-identity-history conn dan odd))))))))

(deftest two-versions-of-one-millisecond-still-have-a-latest
  (testing-with-conn "the newest read answers with the one written last, not either of them"
    ;; **The collision is constructed, not hoped for.** `valid_from` is epoch
    ;; milliseconds and `Instant/now` is what stamps a version in the app, so
    ;; the real case is an agent writing twice inside one millisecond — but a
    ;; test that just writes three versions in a row and trusts them to collide
    ;; is a test that passes whether or not the tie-break is there (it was:
    ;; removing `[:id :desc]` left it green three runs running). Handing in one
    ;; explicit instant for all three is the same condition, made to happen
    ;; every time.
    (let [dan (persona! :dan "d@et.n")
          t (Instant/parse "2026-08-11T12:00:00Z")
          id (ds/add-identity conn dan "notes" "v1" "human" {:valid-from t})]
      (ds/save-identity-version conn dan id "notes" "v2" "a-machine" {:valid-from t})
      (ds/save-identity-version conn dan id "notes" "v3" "human" {:valid-from t})
      (testing "- the history keeps the order they were written in"
        (are=
         ["v1" "v2" "v3"] (mapv :text (ds/get-identity-history conn dan id))
         ["human" "a-machine" "human"] (mapv :author (ds/get-identity-history conn dan id))))
      (testing "- and the newest read is the one written last, not whichever the
                database felt like handing back"
        (are=
         "v3" (:text (ds/get-identity conn dan id))
         "v3" (:text (ds/get-identity-at conn dan id t)))))))

;; ---------------------------------------------------------------------------
;; The same tie, asked of many identities at once
;;
;; `get-identity` and friends answer "the latest version of *this* identity" and
;; tie-break on the id. The three listing reads ask the same question of every
;; identity a persona holds, through `latest-versions-subquery`, and used to ask
;; it as "the greatest valid_from" — which two rows can both satisfy. The join
;; then matched both and the identity came back twice.
;;
;; Not a race. The API takes an explicit `valid_from`, so an importer stamping a
;; batch with one timestamp meets this every time, deterministically — which is
;; also how these tests construct it. `(:valid-from t)` everywhere below: a test
;; that hoped two writes landed in one millisecond would be a test that cannot
;; fail, which is exactly what happened to the first pin in the round before this
;; one.
;; ---------------------------------------------------------------------------

(deftest two-versions-of-one-millisecond-do-not-duplicate-the-identity
  (testing-with-conn "one identity, two versions of the same instant, one row back"
    (let [dan (persona! :dan "d@et.n")
          t (Instant/parse "2026-08-11T12:00:00Z")
          id (ds/add-identity conn dan "notes" "v1" "human" {:valid-from t})]
      (ds/save-identity-version conn dan id "notes" "v2" "daniel-machine" {:valid-from t})

      (testing "- list-identities answers once, with the version written last"
        (are=
         [{:identity id :name "notes" :text "v2"}] (ds/list-identities conn dan)))

      (testing "- and so does search"
        (are=
         [{:identity id :name "notes" :text "v2"}] (ds/search-identities conn dan "notes")
         [{:identity id :name "notes" :text "v2"}] (ds/search-identities conn dan "")))

      (testing "- and the recent listing, whose duplicate the SPA renders"
        (let [{:keys [items has-more]} (ds/list-recent-identities conn dan 5 0)]
          (are=
           [id]  (mapv :identity items)
           false has-more)))

      (testing "- a third version of the same instant does not change that"
        (ds/save-identity-version conn dan id "notes" "v3" "human" {:valid-from t})
        (are=
         [{:identity id :name "notes" :text "v3"}] (ds/list-identities conn dan)
         [id] (mapv :identity (:items (ds/list-recent-identities conn dan 5 0))))))))

(deftest a-row-written-later-with-an-earlier-stamp-is-not-the-latest
  (testing-with-conn "the rule is the greatest (valid_from, id), not the greatest id"
    ;; The reason the tie-break cannot simply be `max(id)`: `valid_from` is the
    ;; caller's to set, so a version written *later* can carry an *earlier*
    ;; timestamp — backfilling an import, correcting a date. It must not become
    ;; the latest by virtue of having been inserted last.
    (let [dan (persona! :dan "d@et.n")
          early (Instant/parse "2020-01-01T00:00:00Z")
          late (Instant/parse "2026-01-01T00:00:00Z")
          id (ds/add-identity conn dan "notes" "the current text" "human" {:valid-from late})]
      (ds/save-identity-version conn dan id "notes" "a backfilled older version" "human"
                                {:valid-from early})
      (are=
       [{:identity id :name "notes" :text "the current text"}] (ds/list-identities conn dan)
       "the current text" (:text (ds/get-identity conn dan id))
       [id] (mapv :identity (:items (ds/list-recent-identities conn dan 5 0)))))))

(deftest search-at-a-time-point-answers-the-version-written-last
  (testing-with-conn "?valid_at is get-identity-at asked of every identity, and reads the same way"
    (let [dan (persona! :dan "d@et.n")
          t (Instant/parse "2026-08-11T12:00:00Z")
          later (Instant/parse "2026-08-11T13:00:00Z")
          id (ds/add-identity conn dan "notes" "v1" "human" {:valid-from t})]
      (ds/save-identity-version conn dan id "notes" "v2" "daniel-machine" {:valid-from t})
      (are=
       [{:identity id :name "notes" :text "v2"}] (ds/search-identities conn dan "notes" {:at t})
       [{:identity id :name "notes" :text "v2"}] (ds/search-identities conn dan "notes" {:at later})))))

(deftest a-page-holds-that-many-distinct-identities
  (testing-with-conn "the duplicate used to eat a slot in the page the SPA asked for"
    (let [dan (persona! :dan "d@et.n")
          t (Instant/parse "2026-08-11T12:00:00Z")
          ;; three identities, each with a colliding second version — a batch
          ;; import stamped with one timestamp, which is the workload this is about
          ids (doall (for [n [1 2 3]]
                       (let [id (ds/add-identity conn dan (str "note-" n) "v1" "human"
                                                 {:valid-from t})]
                         (ds/save-identity-version conn dan id (str "note-" n) "v2"
                                                   "daniel-machine" {:valid-from t})
                         id)))]
      (testing "- a page of two holds two *distinct* identities, and says there is more"
        (let [{:keys [items has-more]} (ds/list-recent-identities conn dan 2 0)]
          (are=
           2 (count items)
           2 (count (distinct (map :identity items)))
           true has-more)))

      (testing "- and the whole persona is three, not six"
        (are=
         3 (count (ds/list-identities conn dan))
         3 (count (:items (ds/list-recent-identities conn dan 5 0)))
         (set ids) (set (map :identity (ds/list-identities conn dan)))))

      (testing "- and one identity per page covers all three across three pages,
                none twice. This one bites on the duplicate and *not* on the
                ordering tie-break beside it — SQLite is stable for this shape
                today, so `[:iv.id :desc]` can be removed and this stays green.
                Said out loud rather than left to be assumed: see
                list-recent-identities for why the line is there anyway."
        (let [page (fn [offset] (mapv :identity (:items (ds/list-recent-identities conn dan 1 offset))))
              [p0 p1 p2] [(page 0) (page 1) (page 2)]]
          (are=
           3 (count (distinct (concat p0 p1 p2)))
           (set ids) (set (concat p0 p1 p2))))))))

(deftest identity-time-travel
  (testing-with-conn "identities change over time but history is preserved"
    (let [dan (persona! :dan "d@et.n")
          t1 (Instant/parse "2020-01-01T00:00:00Z")
          t2 (Instant/parse "2020-06-01T00:00:00Z")
          query-time (Instant/parse "2020-03-01T00:00:00Z")
          evolving-id (ds/add-identity conn dan "original name" "original text" "human" {:valid-from t1})]
      (ds/update-identity conn dan evolving-id "updated name" "updated text" "human" {:valid-from t2})
      (testing "- current query returns updated text"
        (is (= "updated text"
               (:text (first (filter #(= evolving-id (:identity %))
                                     (ds/list-identities conn dan)))))))
      (testing "- time-travel query returns original text and name"
        (is (= {:identity evolving-id :name "original name" :text "original text"}
               (ds/get-identity-at conn dan evolving-id query-time)))))))

(deftest relations-time-travel
  (testing-with-conn "relations exist only during specific time periods"
    (let [dan (persona! :dan "d@et.n")
          t1 (Instant/parse "2020-01-01T00:00:00Z")
          t2 (Instant/parse "2020-06-01T00:00:00Z")
          t3 (Instant/parse "2020-12-01T00:00:00Z")
          source-id (ds/add-identity conn dan "source" "source text" "human" {:valid-from t1})
          target-id (ds/add-identity conn dan "target" "target text" "human" {:valid-from t1})
          relation-id (str (name source-id) "/" (name target-id))]
      ;; relations only change as part of saving an identity version
      (ds/save-identity-version conn dan source-id "source v2" "source text v2" "human"
                                {:valid-from t2 :relation-adds [target-id]})
      (ds/save-identity-version conn dan source-id "source v3" "source text v3" "human"
                                {:valid-from t3 :relation-removes [relation-id]})
      (testing "- querying before relation exists returns no relations"
        (is (= []
               (ds/list-relations conn dan source-id {:at (Instant/parse "2020-03-01T00:00:00Z")}))))
      (testing "- querying during relation period returns the relation"
        (is (= [{:id relation-id
                 :target target-id
                 :target-name "target"
                 :description nil}]
               (ds/list-relations conn dan source-id {:at (Instant/parse "2020-09-01T00:00:00Z")}))))
      (testing "- querying after relation deleted returns no relations"
        (is (= []
               (ds/list-relations conn dan source-id {:at (Instant/parse "2021-01-01T00:00:00Z")})))))))

(deftest relations-delete-and-re-add
  (testing-with-conn "relations can be deleted and re-added at different times"
    (let [dan (persona! :dan "d@et.n")
          t1 (Instant/parse "2020-01-01T00:00:00Z")
          t2 (Instant/parse "2020-03-01T00:00:00Z")
          t3 (Instant/parse "2020-06-01T00:00:00Z")
          t4 (Instant/parse "2020-09-01T00:00:00Z")
          t5 (Instant/parse "2020-12-01T00:00:00Z")
          source-id (ds/add-identity conn dan "source" "v1" "human" {:valid-from t1})
          target-id (ds/add-identity conn dan "target" "target" "human" {:valid-from t1})
          relation-id (str (name source-id) "/" (name target-id))
          expected [{:id relation-id :target target-id :target-name "target" :description nil}]]
      (ds/save-identity-version conn dan source-id "source" "v2" "human" {:valid-from t2 :relation-adds [target-id]})
      (ds/save-identity-version conn dan source-id "source" "v3" "human" {:valid-from t3 :relation-removes [relation-id]})
      (ds/save-identity-version conn dan source-id "source" "v4" "human" {:valid-from t4 :relation-adds [target-id]})
      (ds/save-identity-version conn dan source-id "source" "v5" "human" {:valid-from t5 :relation-removes [relation-id]})
      (testing "- v1: no relation"
        (is (= [] (ds/list-relations conn dan source-id {:at (Instant/parse "2020-02-01T00:00:00Z")}))))
      (testing "- v2: relation exists"
        (is (= expected
               (ds/list-relations conn dan source-id {:at (Instant/parse "2020-04-01T00:00:00Z")}))))
      (testing "- v3: no relation"
        (is (= [] (ds/list-relations conn dan source-id {:at (Instant/parse "2020-07-01T00:00:00Z")}))))
      (testing "- v4: relation exists again"
        (is (= expected
               (ds/list-relations conn dan source-id {:at (Instant/parse "2020-10-01T00:00:00Z")}))))
      (testing "- v5: no relation"
        (is (= [] (ds/list-relations conn dan source-id {:at (Instant/parse "2021-01-01T00:00:00Z")})))))))

(deftest relations-carried-forward-on-plain-edit
  (testing-with-conn "editing an identity's text keeps its existing relations"
    (let [dan (persona! :dan "d@et.n")
          t1 (Instant/parse "2020-01-01T00:00:00Z")
          t2 (Instant/parse "2020-06-01T00:00:00Z")
          t3 (Instant/parse "2020-12-01T00:00:00Z")
          source-id (ds/add-identity conn dan "source" "v1" "human" {:valid-from t1})
          target-id (ds/add-identity conn dan "target" "target" "human" {:valid-from t1})
          relation-id (str (name source-id) "/" (name target-id))]
      (ds/save-identity-version conn dan source-id "source" "v2" "human" {:valid-from t2 :relation-adds [target-id]})
      ;; a plain edit (no relation changes) must not drop the relation
      (ds/update-identity conn dan source-id "source" "v3" "human" {:valid-from t3})
      (is (= [{:id relation-id :target target-id :target-name "target" :description nil}]
             (ds/list-relations conn dan source-id))))))

(deftest relation-description-round-trips
  (testing-with-conn "a relation carries an optional description"
    (let [dan (persona! :dan "d@et.n")
          t1 (Instant/parse "2020-01-01T00:00:00Z")
          t2 (Instant/parse "2020-06-01T00:00:00Z")
          source-id (ds/add-identity conn dan "source" "v1" "human" {:valid-from t1})
          target-id (ds/add-identity conn dan "target" "target" "human" {:valid-from t1})
          relation-id (str (name source-id) "/" (name target-id))]
      (ds/save-identity-version conn dan source-id "source" "v2" "human"
                                {:valid-from t2
                                 :relation-adds [{:target target-id :description "why they relate"}]})
      (is (= [{:id relation-id :target target-id :target-name "target" :description "why they relate"}]
             (ds/list-relations conn dan source-id))))))

(deftest save-identity-version-shares-relation-timeline
  (testing-with-conn "relations committed via save-identity-version share the version's valid-from"
    (let [dan (persona! :dan "d@et.n")
          t1 (Instant/parse "2020-01-01T00:00:00Z")
          t2 (Instant/parse "2020-06-01T00:00:00Z")
          t3 (Instant/parse "2020-12-01T00:00:00Z")
          source-id (ds/add-identity conn dan "source" "v1" "human" {:valid-from t1})
          target-id (ds/add-identity conn dan "target" "target" "human" {:valid-from t1})
          relation-id (str (name source-id) "/" (name target-id))
          expected [{:id relation-id :target target-id :target-name "target" :description nil}]]
      ;; add the relation as part of saving a new version, all tagged at t2
      (ds/save-identity-version conn dan source-id "source" "v2" "human"
                                {:valid-from t2 :relation-adds [target-id]})
      (testing "- relation is visible at the exact version timestamp (regression: was tagged later than the version)"
        (is (= expected (ds/list-relations conn dan source-id {:at t2}))))
      (testing "- and it is visible as the current relation"
        (is (= expected (ds/list-relations conn dan source-id))))
      (testing "- but not before that version existed"
        (is (= [] (ds/list-relations conn dan source-id {:at (Instant/parse "2020-03-01T00:00:00Z")}))))
      ;; remove it as part of saving a further version at t3
      (ds/save-identity-version conn dan source-id "source" "v3" "human"
                                {:valid-from t3 :relation-removes [relation-id]})
      (testing "- removal takes effect exactly at the new version's timestamp"
        (is (= [] (ds/list-relations conn dan source-id {:at t3}))))
      (testing "- while the earlier version still shows the relation"
        (is (= expected (ds/list-relations conn dan source-id {:at t2})))))))

(deftest relations-keep-the-order-they-were-added-in
  (testing-with-conn "with nothing said about the order, relations stay as they came"
    (let [dan (persona! :dan "d@et.n")
          t1 (Instant/parse "2020-01-01T00:00:00Z")
          source-id (ds/add-identity conn dan "source" "v1" "human" {:valid-from t1})
          a (ds/add-identity conn dan "a" "a" "human" {:valid-from t1})
          b (ds/add-identity conn dan "b" "b" "human" {:valid-from t1})
          c (ds/add-identity conn dan "c" "c" "human" {:valid-from t1})]
      (ds/save-identity-version conn dan source-id "source" "v2" "human"
                                {:valid-from (Instant/parse "2020-02-01T00:00:00Z")
                                 :relation-adds [b c a]})
      (testing "- the adds' own order is the stored one"
        (is (= [b c a] (mapv :target (ds/list-relations conn dan source-id)))))
      (testing "- and a plain edit says nothing about it, so it carries forward"
        (ds/update-identity conn dan source-id "source" "v3" "human"
                            {:valid-from (Instant/parse "2020-03-01T00:00:00Z")})
        (is (= [b c a] (mapv :target (ds/list-relations conn dan source-id))))))))

(deftest relation-order-can-be-changed
  (testing-with-conn "a version may rank its relations, and the ranking time-travels with it"
    (let [dan (persona! :dan "d@et.n")
          t1 (Instant/parse "2020-01-01T00:00:00Z")
          t2 (Instant/parse "2020-02-01T00:00:00Z")
          t3 (Instant/parse "2020-03-01T00:00:00Z")
          source-id (ds/add-identity conn dan "source" "v1" "human" {:valid-from t1})
          a (ds/add-identity conn dan "a" "a" "human" {:valid-from t1})
          b (ds/add-identity conn dan "b" "b" "human" {:valid-from t1})
          c (ds/add-identity conn dan "c" "c" "human" {:valid-from t1})]
      (ds/save-identity-version conn dan source-id "source" "v2" "human"
                                {:valid-from t2 :relation-adds [a b c]})
      (ds/save-identity-version conn dan source-id "source" "v3" "human"
                                {:valid-from t3 :relation-order [c a b]})
      (testing "- the new version is in the requested order"
        (is (= [c a b] (mapv :target (ds/list-relations conn dan source-id)))))
      (testing "- the version before it keeps the order it was saved with"
        (is (= [a b c] (mapv :target (ds/list-relations conn dan source-id {:at t2})))))
      (testing "- and the ranking carries forward over a plain edit"
        (ds/update-identity conn dan source-id "source" "v4" "human"
                            {:valid-from (Instant/parse "2020-04-01T00:00:00Z")})
        (is (= [c a b] (mapv :target (ds/list-relations conn dan source-id))))))))

(deftest relation-order-is-applied-after-the-adds-and-removes
  (testing-with-conn "one version may add, remove and rank in a single call"
    (let [dan (persona! :dan "d@et.n")
          t1 (Instant/parse "2020-01-01T00:00:00Z")
          t2 (Instant/parse "2020-02-01T00:00:00Z")
          t3 (Instant/parse "2020-03-01T00:00:00Z")
          source-id (ds/add-identity conn dan "source" "v1" "human" {:valid-from t1})
          a (ds/add-identity conn dan "a" "a" "human" {:valid-from t1})
          b (ds/add-identity conn dan "b" "b" "human" {:valid-from t1})
          c (ds/add-identity conn dan "c" "c" "human" {:valid-from t1})]
      (ds/save-identity-version conn dan source-id "source" "v2" "human"
                                {:valid-from t2 :relation-adds [a b]})
      ;; c arrives, b goes, and the newcomer is ranked first — all in one version,
      ;; which is what the edit view's Save does with a screenful of staged changes
      (ds/save-identity-version conn dan source-id "source" "v3" "human"
                                {:valid-from t3
                                 :relation-adds [c]
                                 :relation-removes [(str (name source-id) "/" (name b))]
                                 :relation-order [c a]})
      (is (= [c a] (mapv :target (ds/list-relations conn dan source-id)))))))

(deftest a-partial-relation-order-leaves-the-rest-where-it-was
  (testing-with-conn "the ranking names what it ranks; everything else keeps its place after it"
    (let [dan (persona! :dan "d@et.n")
          t1 (Instant/parse "2020-01-01T00:00:00Z")
          t2 (Instant/parse "2020-02-01T00:00:00Z")
          t3 (Instant/parse "2020-03-01T00:00:00Z")
          source-id (ds/add-identity conn dan "source" "v1" "human" {:valid-from t1})
          a (ds/add-identity conn dan "a" "a" "human" {:valid-from t1})
          b (ds/add-identity conn dan "b" "b" "human" {:valid-from t1})
          c (ds/add-identity conn dan "c" "c" "human" {:valid-from t1})
          d (ds/add-identity conn dan "d" "d" "human" {:valid-from t1})]
      (ds/save-identity-version conn dan source-id "source" "v2" "human"
                                {:valid-from t2 :relation-adds [a b c d]})
      ;; only the last two are spoken for; a and b are not demoted below anything
      ;; they were above, they simply follow the ones that were named
      (ds/save-identity-version conn dan source-id "source" "v3" "human"
                                {:valid-from t3 :relation-order [d c]})
      (is (= [d c a b] (mapv :target (ds/list-relations conn dan source-id)))))))

(deftest a-relation-order-naming-nothing-known-is-harmless
  (testing-with-conn "an order that names a target that is not there leaves the set alone"
    (let [dan (persona! :dan "d@et.n")
          t1 (Instant/parse "2020-01-01T00:00:00Z")
          t2 (Instant/parse "2020-02-01T00:00:00Z")
          source-id (ds/add-identity conn dan "source" "v1" "human" {:valid-from t1})
          a (ds/add-identity conn dan "a" "a" "human" {:valid-from t1})
          b (ds/add-identity conn dan "b" "b" "human" {:valid-from t1})
          gone (ds/add-identity conn dan "gone" "gone" "human" {:valid-from t1})]
      (ds/save-identity-version conn dan source-id "source" "v2" "human"
                                {:valid-from t2 :relation-adds [a b]})
      ;; the client may still be holding a relation the set no longer has — a
      ;; removal staged beside the drag, say. Naming it must not disturb the rest.
      (ds/save-identity-version conn dan source-id "source" "v3" "human"
                                {:valid-from (Instant/parse "2020-03-01T00:00:00Z")
                                 :relation-order [gone a b]})
      (is (= [a b] (mapv :target (ds/list-relations conn dan source-id)))))))

(deftest search-identities-time-travel
  (testing-with-conn "search identities with cutoff date returns versions at that time"
    (let [dan (persona! :dan "d@et.n")
          t1 (Instant/parse "2020-01-01T00:00:00Z")
          t2 (Instant/parse "2020-06-01T00:00:00Z")
          query-before (Instant/parse "2020-03-01T00:00:00Z")
          query-after (Instant/parse "2020-09-01T00:00:00Z")
          id1 (ds/add-identity conn dan "Alice" "original alice" "human" {:valid-from t1})
          id2 (ds/add-identity conn dan "Bob" "original bob" "human" {:valid-from t1})
          a 1]
      (ds/update-identity conn dan id1 "Alice Updated" "updated alice" "human" {:valid-from t2})
      (testing "- search without cutoff returns current versions"
        (let [results (ds/search-identities conn dan "Alice")]
          (is (= 1 (count results)))
          (is (= "Alice Updated" (:name (first results))))))
      (testing "- search with cutoff before update returns original version"
        (let [results (ds/search-identities conn dan "Alice" {:at query-before})]
          (is (= 1 (count results)))
          (is (= "Alice" (:name (first results))))))
      (testing "- search with cutoff after update returns updated version"
        (let [results (ds/search-identities conn dan "Alice" {:at query-after})]
          (is (= 1 (count results)))
          (is (= "Alice Updated" (:name (first results)))))))))

;; ---------------------------------------------------------------------------
;; Machine users: accounts rows under a human account, with one grant row per
;; persona they may write. They hold no password and never log in.
;; ---------------------------------------------------------------------------

(deftest machine-users-sit-under-an-account
  (testing-with-conn "an account may hold several, each with its own grants"
    (let [mine (ds/add-account conn "d@et.n" "hash")
          theirs (ds/add-account conn "e@et.n" "hash")]
      (ds/add-persona conn mine :face-a "A")
      (ds/add-persona conn mine :face-b "B")
      (ds/add-persona conn mine :face-c "C")
      (ds/add-persona conn theirs :not-mine "Theirs")

      (let [one (ds/add-machine-user conn mine "writer-ac" {:can-create-personas? false})
            two (ds/add-machine-user conn mine "writer-bc" {:can-create-personas? true})]
        (testing "- minting one hands back its account id"
          (is (integer? one))
          (is (integer? two))
          (is (not= one two)))

        (testing "- the name is taken now, globally rather than per account"
          (are=
           false (ds/add-machine-user conn mine "writer-ac" {})
           false (ds/add-machine-user conn theirs "writer-ac" {})))

        (testing "- it is an account row, but never one that can log in"
          (are=
           nil (ds/get-account-by-email conn nil)
           nil (ds/get-account-password-hash conn one))
          (is (nil? (:email (ds/get-machine-user conn "writer-ac")))))

        ;; the owner's own example: one writes A and C, the other B and C
        (ds/grant-persona conn one :face-a)
        (ds/grant-persona conn one :face-c)
        (ds/grant-persona conn two :face-b)
        (ds/grant-persona conn two :face-c)

        (testing "- the grants are exactly what was granted"
          (are=
           [:face-a :face-c] (ds/granted-personas conn one)
           [:face-b :face-c] (ds/granted-personas conn two)))

        (testing "- granting twice is the same grant, not an error"
          (ds/grant-persona conn one :face-a)
          (are= [:face-a :face-c] (ds/granted-personas conn one)))

        (testing "- and the question the guard actually asks"
          (are=
           true  (ds/machine-may-write? conn one :face-a)
           false (ds/machine-may-write? conn one :face-b)
           true  (ds/machine-may-write? conn one :face-c)
           false (ds/machine-may-write? conn one :not-mine)
           false (ds/machine-may-write? conn 9999 :face-a)))

        (testing "- a machine user knows its parent and its permission"
          (are=
           {:id one :name "writer-ac" :for-account-id mine :can-create-personas? false}
           (dissoc (ds/get-machine-user conn "writer-ac") :email :token-hash)
           {:id two :name "writer-bc" :for-account-id mine :can-create-personas? true}
           (dissoc (ds/get-machine-user conn "writer-bc") :email :token-hash)
           nil (ds/get-machine-user conn "no-such-machine")))

        (testing "- the account's roster, in name order"
          (are=
           [{:id one :name "writer-ac" :can-create-personas? false :personas [:face-a :face-c]}
            {:id two :name "writer-bc" :can-create-personas? true  :personas [:face-b :face-c]}]
           (ds/list-machine-users conn mine)
           [] (ds/list-machine-users conn theirs)))

        (testing "- revoking takes one grant and leaves the rest"
          (ds/revoke-persona conn one :face-a)
          (are=
           [:face-c] (ds/granted-personas conn one)
           [:face-b :face-c] (ds/granted-personas conn two)))

        (testing "- can-create-personas can be flipped"
          (ds/update-machine-user conn one {:can-create-personas? true})
          (is (true? (:can-create-personas? (ds/get-machine-user conn "writer-ac")))))

        (testing "- and removing one takes its grants with it, nobody else's"
          (is (true? (ds/delete-machine-user conn one)))
          (are=
           nil (ds/get-machine-user conn "writer-ac")
           [{:id two :name "writer-bc" :can-create-personas? true :personas [:face-b :face-c]}]
           (ds/list-machine-users conn mine)
           false (ds/machine-may-write? conn one :face-c))
          (is (false? (ds/delete-machine-user conn one))))))))

(deftest a-machine-user-is-not-in-the-human-listings
  (testing-with-conn "nothing that enumerates accounts or personas shows one"
    (let [mine (ds/add-account conn "d@et.n" "hash")]
      (ds/add-persona conn mine :face-a "A")
      (ds/add-machine-user conn mine "a-machine" {})
      (testing "- the admin account listing is humans only"
        (are=
         [{:id mine :email "d@et.n" :personas [{:id :face-a :name "A" :sort-order 0 :private? false}]}]
         (ds/list-accounts conn)))
      (testing "- and a machine user has no persona of its own to leak"
        (are=
         [{:id :face-a :name "A" :private? false}] (ds/list-personas conn)
         [] (ds/list-personas-for-account conn (:id (ds/get-machine-user conn "a-machine"))))))))

(deftest deleting-a-persona-revokes-every-grant-on-it
  (testing-with-conn "a grant must not outlive the persona it names"
    (let [mine (ds/add-account conn "d@et.n" "hash")]
      (ds/add-persona conn mine :doomed "Doomed")
      (ds/add-persona conn mine :spared "Spared")
      (let [one (ds/add-machine-user conn mine "writer-one" {})
            two (ds/add-machine-user conn mine "writer-two" {})]
        (doseq [m [one two]]
          (ds/grant-persona conn m :doomed)
          (ds/grant-persona conn m :spared))

        (ds/delete-persona conn :doomed)

        (testing "- every machine user's grant on it is gone"
          (are=
           [:spared] (ds/granted-personas conn one)
           [:spared] (ds/granted-personas conn two)
           false (ds/machine-may-write? conn one :doomed)))
        (testing "- and a persona re-created under the same id inherits nothing"
          (ds/add-persona conn mine :doomed "Doomed Again")
          (are= false (ds/machine-may-write? conn one :doomed)))))))
