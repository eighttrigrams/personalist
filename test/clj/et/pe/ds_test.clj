(ns et.pe.ds-test
  (:require [clojure.test :refer [deftest testing is are use-fixtures]]
            [et.pe.ds :as ds]
            et.pe.ds.xtdb2)
  (:import [java.time Instant]))

(def ^:dynamic *conn-type* nil)

(def ^:dynamic conn nil)

(defn xtdb2-in-memory [f]
  (binding [*conn-type* :xtdb2-in-memory]
    (f)))

(defn other-db-adapter [f]
  (binding [*conn-type* 
            ;; TODO put in other adapter
            :xtdb2-in-memory]
    (f)))

(defmacro testing-with-conn [string & body]
  `(testing ~string (binding [conn (ds/init-conn {:type *conn-type*})]
              ~@body
              (ds/close-conn conn))))

(defmacro are= [& body]
  `(are [expected actual] (= expected actual) ~@body))

(defmacro sets-are= [& body]
  `(are [expected actual] (= (set expected) (set actual)) ~@body))

(use-fixtures :once (juxt xtdb2-in-memory other-db-adapter))

(deftest personas
  (testing-with-conn "add personas"
   (ds/add-persona conn :dan "d@et.n")
   (testing "- can't add a persona with the same name"
     (are=
      false (ds/add-persona conn :dan "d2@et.n")
      1 (count (ds/list-personas conn))))
   (testing "- can't add a persona with the same email"
     (are=
      false (ds/add-persona conn :dan2 "d@et.n")
      1 (count (ds/list-personas conn)))))
  (testing-with-conn "retrieve personas"
   (ds/add-persona conn :dan "d@et.n")
   (ds/add-persona conn :dan2 "d2@et.n")
   (sets-are=
    [{:name  :dan
      :email "d@et.n"}
     {:name  :dan2
      :email "d2@et.n"}]
    (ds/list-personas conn))
   (are=
    {:name  :dan
     :email "d@et.n"}
    (ds/get-persona-by-name conn :dan)
    {:name  :dan2
     :email "d2@et.n"}
    (ds/get-persona-by-email conn "d2@et.n"))))

(deftest identities
  (testing-with-conn "add and retrieve identities"
    (ds/add-persona conn :dan "d@et.n")
    (ds/add-persona conn :dan2 "d2@et.n")
    (let [dan (ds/get-persona-by-name conn :dan)
          dan2 (ds/get-persona-by-name conn :dan2)]
      (ds/add-identity conn dan :id11 "name11" "text11")
      (ds/add-identity conn dan :id12 "name12" "text12")
      (ds/add-identity conn dan2 :id21 "name21" "text21")
      (ds/add-identity conn dan2 :id22 "name22" "text22")
      (sets-are=
       [{:identity :id11
         :name     "name11"
         :text     "text11"}
        {:identity :id12
         :name     "name12"
         :text     "text12"}]
       (ds/list-identities conn dan)
       [{:identity :id21
         :name     "name21"
         :text     "text21"}
        {:identity :id22
         :name     "name22"
         :text     "text22"}]
       (ds/list-identities conn dan2)))))

(deftest identity-time-travel
  (testing-with-conn "identities change over time but history is preserved"
    (ds/add-persona conn :dan "d@et.n")
    (let [dan (ds/get-persona-by-name conn :dan)
          t1 (Instant/parse "2020-01-01T00:00:00Z")
          t2 (Instant/parse "2020-06-01T00:00:00Z")
          query-time (Instant/parse "2020-03-01T00:00:00Z")]
      (ds/add-identity conn dan :evolving-id "original name" "original text" {:valid-from t1})
      (ds/update-identity conn dan :evolving-id "updated name" "updated text" {:valid-from t2})
      (testing "- current query returns updated text"
        (is (= "updated text"
               (:text (first (filter #(= :evolving-id (:identity %))
                                     (ds/list-identities conn dan)))))))
      (testing "- time-travel query returns original text and name"
        (is (= {:identity :evolving-id :name "original name" :text "original text"}
               (ds/get-identity-at conn dan :evolving-id query-time)))))))

