(ns et.pe.ds-test
  (:require [clojure.test :refer [deftest testing is are]]
            [et.pe.ds :as ds]))

(deftest add-and-retrieve-persons
  (testing "add and retrieve a person"
    (is 
     (= {:xt/id        :dan
         :person/email "d@et.n"}
        (first (with-open [node (ds/start-in-memory-node)]
                 (ds/add-person node :dan "d@et.n")
                 (ds/list-persons node))))))
  
  (testing "can't add a person with the same name or email"
    (with-open [node (ds/start-in-memory-node)]
      (ds/add-person node :dan "d@et.n")
      (are [expected actual] (= expected actual) 
        [false 1]
        [(ds/add-person node :dan "d2@et.n")
         (count (ds/list-persons node))]
        [false 1]
        [(ds/add-person node :dan2 "d@et.n")
         (count (ds/list-persons node))]))))
