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
         [{:id :dan :name "dan"} {:id :dan2 :name "Second Face"} {:id :eve :name "eve"}]
         (ds/list-personas conn)))
      (testing "- an account's own personas, in the order it holds them"
        (are=
         [{:id :dan :name "dan" :sort-order 0} {:id :dan2 :name "Second Face" :sort-order 1}]
         (ds/list-personas-for-account conn mine)
         [{:id :eve :name "eve" :sort-order 0}]
         (ds/list-personas-for-account conn theirs)))
      (testing "- a persona knows which account holds it; that is the ownership check"
        (are=
         {:id :dan :name "dan" :account-id mine :sort-order 0} (ds/get-persona-by-id conn :dan)
         {:id :eve :name "eve" :account-id theirs :sort-order 0} (ds/get-persona-by-id conn :eve)
         nil (ds/get-persona-by-id conn :nobody)))
      (testing "- the display name is the persona's own, and the only thing editable"
        (ds/update-persona conn :dan {:name "Renamed"})
        (are=
         "Renamed" (:name (ds/get-persona-by-id conn :dan))
         nil       (ds/update-persona conn :nobody {:name "x"}))))))

(deftest listing-accounts-for-the-admin
  (testing-with-conn "the Settings listing: accounts, their emails, their personas"
    (let [a (ds/add-account conn "a@et.n" nil)
          b (ds/add-account conn "b@et.n" nil)]
      (ds/add-persona conn a :one "One")
      (ds/add-persona conn a :two "Two")
      (ds/add-persona conn b :three "Three")
      (are=
       [{:id a :email "a@et.n" :personas [{:id :one :name "One" :sort-order 0}
                                          {:id :two :name "Two" :sort-order 1}]}
        {:id b :email "b@et.n" :personas [{:id :three :name "Three" :sort-order 0}]}]
       (ds/list-accounts conn)))))

(deftest deleting-a-persona-takes-its-identities-with-it
  (testing-with-conn "removal is real destruction — no history, no undo"
    (let [acc (ds/add-account conn "d@et.n" nil)
          _ (ds/add-persona conn acc :doomed nil)
          _ (ds/add-persona conn acc :spared nil)
          doomed (ds/get-persona-by-id conn :doomed)
          spared (ds/get-persona-by-id conn :spared)
          gone (ds/add-identity conn doomed "goes" "away")
          kept (ds/add-identity conn spared "stays" "put")]
      ;; a second version, so it is the whole history that goes and not one row
      (ds/update-identity conn doomed gone "goes" "away, edited")
      (are=
       2 (count (ds/get-identity-history conn doomed gone))
       1 (count (ds/list-identities conn spared)))

      (is (true? (ds/delete-persona conn :doomed)))

      (testing "- the persona row is gone"
        (are=
         nil          (ds/get-persona-by-id conn :doomed)
         [{:id :spared :name "spared"}] (ds/list-personas conn)))
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
          id11 (ds/add-identity conn dan "name11" "text11")
          id12 (ds/add-identity conn dan "name12" "text12")
          id21 (ds/add-identity conn dan2 "name21" "text21")
          id22 (ds/add-identity conn dan2 "name22" "text22")]
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

(deftest identity-time-travel
  (testing-with-conn "identities change over time but history is preserved"
    (let [dan (persona! :dan "d@et.n")
          t1 (Instant/parse "2020-01-01T00:00:00Z")
          t2 (Instant/parse "2020-06-01T00:00:00Z")
          query-time (Instant/parse "2020-03-01T00:00:00Z")
          evolving-id (ds/add-identity conn dan "original name" "original text" {:valid-from t1})]
      (ds/update-identity conn dan evolving-id "updated name" "updated text" {:valid-from t2})
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
          source-id (ds/add-identity conn dan "source" "source text" {:valid-from t1})
          target-id (ds/add-identity conn dan "target" "target text" {:valid-from t1})
          relation-id (str (name source-id) "/" (name target-id))]
      ;; relations only change as part of saving an identity version
      (ds/save-identity-version conn dan source-id "source v2" "source text v2"
                                {:valid-from t2 :relation-adds [target-id]})
      (ds/save-identity-version conn dan source-id "source v3" "source text v3"
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
          source-id (ds/add-identity conn dan "source" "v1" {:valid-from t1})
          target-id (ds/add-identity conn dan "target" "target" {:valid-from t1})
          relation-id (str (name source-id) "/" (name target-id))
          expected [{:id relation-id :target target-id :target-name "target" :description nil}]]
      (ds/save-identity-version conn dan source-id "source" "v2" {:valid-from t2 :relation-adds [target-id]})
      (ds/save-identity-version conn dan source-id "source" "v3" {:valid-from t3 :relation-removes [relation-id]})
      (ds/save-identity-version conn dan source-id "source" "v4" {:valid-from t4 :relation-adds [target-id]})
      (ds/save-identity-version conn dan source-id "source" "v5" {:valid-from t5 :relation-removes [relation-id]})
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
          source-id (ds/add-identity conn dan "source" "v1" {:valid-from t1})
          target-id (ds/add-identity conn dan "target" "target" {:valid-from t1})
          relation-id (str (name source-id) "/" (name target-id))]
      (ds/save-identity-version conn dan source-id "source" "v2" {:valid-from t2 :relation-adds [target-id]})
      ;; a plain edit (no relation changes) must not drop the relation
      (ds/update-identity conn dan source-id "source" "v3" {:valid-from t3})
      (is (= [{:id relation-id :target target-id :target-name "target" :description nil}]
             (ds/list-relations conn dan source-id))))))

(deftest relation-description-round-trips
  (testing-with-conn "a relation carries an optional description"
    (let [dan (persona! :dan "d@et.n")
          t1 (Instant/parse "2020-01-01T00:00:00Z")
          t2 (Instant/parse "2020-06-01T00:00:00Z")
          source-id (ds/add-identity conn dan "source" "v1" {:valid-from t1})
          target-id (ds/add-identity conn dan "target" "target" {:valid-from t1})
          relation-id (str (name source-id) "/" (name target-id))]
      (ds/save-identity-version conn dan source-id "source" "v2"
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
          source-id (ds/add-identity conn dan "source" "v1" {:valid-from t1})
          target-id (ds/add-identity conn dan "target" "target" {:valid-from t1})
          relation-id (str (name source-id) "/" (name target-id))
          expected [{:id relation-id :target target-id :target-name "target" :description nil}]]
      ;; add the relation as part of saving a new version, all tagged at t2
      (ds/save-identity-version conn dan source-id "source" "v2"
                                {:valid-from t2 :relation-adds [target-id]})
      (testing "- relation is visible at the exact version timestamp (regression: was tagged later than the version)"
        (is (= expected (ds/list-relations conn dan source-id {:at t2}))))
      (testing "- and it is visible as the current relation"
        (is (= expected (ds/list-relations conn dan source-id))))
      (testing "- but not before that version existed"
        (is (= [] (ds/list-relations conn dan source-id {:at (Instant/parse "2020-03-01T00:00:00Z")}))))
      ;; remove it as part of saving a further version at t3
      (ds/save-identity-version conn dan source-id "source" "v3"
                                {:valid-from t3 :relation-removes [relation-id]})
      (testing "- removal takes effect exactly at the new version's timestamp"
        (is (= [] (ds/list-relations conn dan source-id {:at t3}))))
      (testing "- while the earlier version still shows the relation"
        (is (= expected (ds/list-relations conn dan source-id {:at t2})))))))

(deftest search-identities-time-travel
  (testing-with-conn "search identities with cutoff date returns versions at that time"
    (let [dan (persona! :dan "d@et.n")
          t1 (Instant/parse "2020-01-01T00:00:00Z")
          t2 (Instant/parse "2020-06-01T00:00:00Z")
          query-before (Instant/parse "2020-03-01T00:00:00Z")
          query-after (Instant/parse "2020-09-01T00:00:00Z")
          id1 (ds/add-identity conn dan "Alice" "original alice" {:valid-from t1})
          id2 (ds/add-identity conn dan "Bob" "original bob" {:valid-from t1})
          a 1]
      (ds/update-identity conn dan id1 "Alice Updated" "updated alice" {:valid-from t2})
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
