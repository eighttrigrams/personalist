(ns et.pe.ds-test
  (:require [clojure.test :refer [deftest testing is are use-fixtures]]
            [et.pe.ds :as ds])
  (:import [java.time Instant]))

(def ^:dynamic *conn-type* nil)

(def ^:dynamic conn nil)

(defn xtdb2-in-memory [f]
  (binding [*conn-type* :xtdb2-in-memory]
    (f)))

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

(use-fixtures :once (juxt xtdb2-in-memory sqlite-in-memory))

(deftest personas
  (testing-with-conn "add personas"
   (ds/add-persona conn :dan "d@et.n" nil nil)
   (testing "- can't add a persona with the same name"
     (are=
      false (ds/add-persona conn :dan "d2@et.n" nil nil)
      1 (count (ds/list-personas conn))))
   (testing "- can't add a persona with the same email"
     (are=
      false (ds/add-persona conn :dan2 "d@et.n" nil nil)
      1 (count (ds/list-personas conn)))))
  (testing-with-conn "retrieve personas"
   (ds/add-persona conn :dan "d@et.n" nil nil)
   (ds/add-persona conn :dan2 "d2@et.n" nil nil)
   (sets-are=
    [{:id  :dan
      :email "d@et.n"
      :name "dan"}
     {:id  :dan2
      :email "d2@et.n"
      :name "dan2"}]
    (ds/list-personas conn))
   (are=
    {:id  :dan
     :email "d@et.n"
     :name "dan"}
    (ds/get-persona-by-id conn :dan)
    {:id  :dan2
     :email "d2@et.n"
     :name "dan2"}
    (ds/get-persona-by-email conn "d2@et.n"))))

(deftest identities
  (testing-with-conn "add and retrieve identities"
    (ds/add-persona conn :dan "d@et.n" nil nil)
    (ds/add-persona conn :dan2 "d2@et.n" nil nil)
    (let [dan (ds/get-persona-by-id conn :dan)
          dan2 (ds/get-persona-by-id conn :dan2)
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
    (ds/add-persona conn :dan "d@et.n" nil nil)
    (let [dan (ds/get-persona-by-id conn :dan)
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
    (ds/add-persona conn :dan "d@et.n" nil nil)
    (let [dan (ds/get-persona-by-id conn :dan)
          t1 (Instant/parse "2020-01-01T00:00:00Z")
          t2 (Instant/parse "2020-06-01T00:00:00Z")
          t3 (Instant/parse "2020-12-01T00:00:00Z")
          source-id (ds/add-identity conn dan "source" "source text" {:valid-from t1})
          target-id (ds/add-identity conn dan "target" "target text" {:valid-from t1})
          relation-id (str (name source-id) "/" (name target-id))]
      (ds/update-identity conn dan source-id "source v2" "source text v2" {:valid-from t2})
      (ds/add-relation conn dan source-id target-id {:valid-from t2})
      (ds/update-identity conn dan source-id "source v3" "source text v3" {:valid-from t3})
      (ds/delete-relation conn dan relation-id {:valid-from t3})
      (testing "- querying before relation exists returns no relations"
        (is (= []
               (ds/list-relations conn dan source-id {:at (Instant/parse "2020-03-01T00:00:00Z")}))))
      (testing "- querying during relation period returns the relation"
        (is (= [{:id relation-id
                 :target target-id
                 :target-name "target"}]
               (ds/list-relations conn dan source-id {:at (Instant/parse "2020-09-01T00:00:00Z")}))))
      (testing "- querying after relation deleted returns no relations"
        (is (= []
               (ds/list-relations conn dan source-id {:at (Instant/parse "2021-01-01T00:00:00Z")})))))))

(deftest relations-delete-and-re-add
  (testing-with-conn "relations can be deleted and re-added at different times"
    (ds/add-persona conn :dan "d@et.n" nil nil)
    (let [dan (ds/get-persona-by-id conn :dan)
          t1 (Instant/parse "2020-01-01T00:00:00Z")
          t2 (Instant/parse "2020-03-01T00:00:00Z")
          t3 (Instant/parse "2020-06-01T00:00:00Z")
          t4 (Instant/parse "2020-09-01T00:00:00Z")
          t5 (Instant/parse "2020-12-01T00:00:00Z")
          source-id (ds/add-identity conn dan "source" "v1" {:valid-from t1})
          target-id (ds/add-identity conn dan "target" "target" {:valid-from t1})
          relation-id (str (name source-id) "/" (name target-id))]
      (ds/update-identity conn dan source-id "source" "v2" {:valid-from t2})
      (ds/add-relation conn dan source-id target-id {:valid-from t2})
      (ds/update-identity conn dan source-id "source" "v3" {:valid-from t3})
      (ds/delete-relation conn dan relation-id {:valid-from t3})
      (ds/update-identity conn dan source-id "source" "v4" {:valid-from t4})
      (ds/add-relation conn dan source-id target-id {:valid-from t4})
      (ds/update-identity conn dan source-id "source" "v5" {:valid-from t5})
      (ds/delete-relation conn dan relation-id {:valid-from t5})
      (testing "- v1: no relation"
        (is (= [] (ds/list-relations conn dan source-id {:at (Instant/parse "2020-02-01T00:00:00Z")}))))
      (testing "- v2: relation exists"
        (is (= [{:id relation-id :target target-id :target-name "target"}]
               (ds/list-relations conn dan source-id {:at (Instant/parse "2020-04-01T00:00:00Z")}))))
      (testing "- v3: no relation"
        (is (= [] (ds/list-relations conn dan source-id {:at (Instant/parse "2020-07-01T00:00:00Z")}))))
      (testing "- v4: relation exists again"
        (is (= [{:id relation-id :target target-id :target-name "target"}]
               (ds/list-relations conn dan source-id {:at (Instant/parse "2020-10-01T00:00:00Z")}))))
      (testing "- v5: no relation"
        (is (= [] (ds/list-relations conn dan source-id {:at (Instant/parse "2021-01-01T00:00:00Z")})))))))

(deftest search-identities-time-travel
  (testing-with-conn "search identities with cutoff date returns versions at that time"
    (ds/add-persona conn :dan "d@et.n" nil nil)
    (let [dan (ds/get-persona-by-id conn :dan)
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
