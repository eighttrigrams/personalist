(ns et.pe.ds-test
  (:require [clojure.test :refer [deftest testing is are]]
            [et.pe.ds :as ds]))

(deftest add-and-retrieve-persons
  (with-open [node (ds/start-in-memory-node)]
    (ds/add-person node :dan "d@et.n")
    (ds/add-person node :dan2 "d2@et.n")
    (testing "add and retrieve persons"
      (are [expected actual] (= expected actual)
        (set [{:xt/id        :dan
               :person/email "d@et.n"}
              {:xt/id        :dan2
               :person/email "d2@et.n"}])
        (set (ds/list-persons node))
        {:xt/id        :dan
         :person/email "d@et.n"}
        (ds/get-person-by-name node :dan)
        {:xt/id        :dan2
         :person/email "d2@et.n"}
        (ds/get-person-by-email node "d2@et.n"))))
  
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
