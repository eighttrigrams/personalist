(ns et.pe.ds-test
  (:require [clojure.test :refer [deftest testing is are test-vars use-fixtures]]
            [et.pe.ds :as ds]
            et.pe.ds.xtdb2))

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

(deftest persons
  (testing-with-conn "add persons"
   (ds/add-person conn :dan "d@et.n")
   (testing "- can't add a person with the same name"
     (are=
      false (ds/add-person conn :dan "d2@et.n")
      1 (count (ds/list-persons conn))))
   (testing "- can't add a person with the same email"
     (are= 
      false (ds/add-person conn :dan2 "d@et.n")
      1 (count (ds/list-persons conn)))))
  (testing-with-conn "retrieve persons"
   (ds/add-person conn :dan "d@et.n")
   (ds/add-person conn :dan2 "d2@et.n")
   (sets-are=
    [{:name  :dan
      :email "d@et.n"}
     {:name  :dan2
      :email "d2@et.n"}]
    (ds/list-persons conn))
   (are=
    {:name  :dan
     :email "d@et.n"}
    (ds/get-person-by-name conn :dan)
    {:name  :dan2
     :email "d2@et.n"}
    (ds/get-person-by-email conn "d2@et.n"))))

(deftest identities
  (testing-with-conn "add and retrieve identities"
    (ds/add-person conn :dan "d@et.n")
    (ds/add-person conn :dan2 "d2@et.n")
    (let [dan (ds/get-person-by-name conn :dan)
          dan2 (ds/get-person-by-name conn :dan2)]
      (ds/add-identity conn dan :id11 "text11")
      (ds/add-identity conn dan :id12 "text12")
      (ds/add-identity conn dan2 :id21 "text21")
      (ds/add-identity conn dan2 :id22 "text22")
      (sets-are=
       [{:identity :id11
         :text     "text11"}
        {:identity :id12
         :text     "text12"}]
       (ds/list-identities conn dan)
       [{:identity :id21
         :text     "text21"}
        {:identity :id22
         :text     "text22"}]
       (ds/list-identities conn dan2)))))

(test-vars [#'persons #'identities])
