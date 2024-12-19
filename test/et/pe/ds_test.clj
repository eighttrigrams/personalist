(ns et.pe.ds-test
  (:require [clojure.test :refer [deftest testing are test-vars use-fixtures]]
            [et.pe.ds :as ds]))

(def ^:dynamic *conn-type* nil)

(def ^:dynamic conn nil)

(defn xtdb2-in-memory [f]
  (binding [*conn-type* :xtdb2-in-memory]
    (f)))

(defn add_other_conn_types [f]
  (binding [*conn-type* :xtdb2-in-memory]
    (f)))

(defmacro with-conn [& body]
  (let [conn (gensym)
        closeable (gensym)]
    `(let [~conn (ds/init-conn {:type *conn-type*})]
       (with-open [~closeable (ds/get-closable ~conn)]
         (binding [conn ~conn]
           ~@body)))))

(defmacro are= [& body]
  `(are [expected actual] (= expected actual) ~@body))

(use-fixtures :once (juxt xtdb2-in-memory add_other_conn_types))

(deftest persons
  (testing "add persons"
    (with-conn
      (ds/add-person conn :dan "d@et.n")
      (testing "- can't add a person with the same name"
        (are=
          false (ds/add-person conn :dan "d2@et.n")
          1 (count (ds/list-persons conn))))
      (testing "- can't add a person with the same email"
        (are= 
          false (ds/add-person conn :dan2 "d@et.n")
          1 (count (ds/list-persons conn))))))
  (testing "retrieve persons"
    (with-conn
      (ds/add-person conn :dan "d@et.n")
      (ds/add-person conn :dan2 "d2@et.n")
      (are=
        (set [{:name  :dan
               :email "d@et.n"}
              {:name  :dan2
               :email "d2@et.n"}])
        (set (ds/list-persons conn))
        {:name  :dan
         :email "d@et.n"}
        (ds/get-person-by-name conn :dan)
        {:name  :dan2
         :email "d2@et.n"}
        (ds/get-person-by-email conn "d2@et.n")))))

(deftest identities
  (with-conn
    (ds/add-person conn :dan "d@et.n")
    (ds/add-person conn :dan2 "d2@et.n")
    (ds/add-identity conn {:name :dan} :id11 "text11")
    (ds/add-identity conn {:name :dan} :id12 "text12")
    (ds/add-identity conn {:name :dan2} :id21 "text21")
    (ds/add-identity conn {:name :dan2} :id22 "text22")
    (testing "add and retrieve identities"
      (are=
        (set [{:identity :id11 :text "text11"}
              {:identity :id12 :text "text12"}])
        (set (ds/list-identities conn {:xt/id :dan}))
        (set [{:identity :id21 :text "text21"}
              {:identity :id22 :text "text22"}])
        (set (ds/list-identities conn {:xt/id :dan2}))))))

(test-vars [#'persons #'identities])
