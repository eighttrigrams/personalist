(ns et.pe.ds-test
  (:require [clojure.test :refer [deftest testing are]]
            [et.pe.ds :as ds]))

(deftest persons
  (testing "add persons - can't add a person with the same name or email"
    (with-open [node (ds/start-in-memory-node)]
      (ds/add-person node :dan "d@et.n")
      (are [expected actual] (= expected actual) 
        [false 1]
        [(ds/add-person node :dan "d2@et.n")
         (count (ds/list-persons node))]
        [false 1]
        [(ds/add-person node :dan2 "d@et.n")
         (count (ds/list-persons node))])))
  
  (with-open [node (ds/start-in-memory-node)] ;; TODO can't I make that automatically provided by a fixture?
    (ds/add-person node :dan "d@et.n")
    (ds/add-person node :dan2 "d2@et.n")
    (testing "retrieve persons"
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
        (ds/get-person-by-email node "d2@et.n")))))

(deftest identities
  (with-open [node (ds/start-in-memory-node)]
    (ds/add-person node :dan "d@et.n")
    (ds/add-person node :dan2 "d2@et.n")
    (ds/add-identity node {:xt/id :dan} :id11 "text11")
    (ds/add-identity node {:xt/id :dan} :id12 "text12")
    (ds/add-identity node {:xt/id :dan2} :id21 "text21")
    (ds/add-identity node {:xt/id :dan2} :id22 "text22")
    (testing "add and retrieve identities"
      (are [expected actual] (= expected actual)
        (set [{:identity/text "text11"}
              {:identity/text "text12"}])
        (set (ds/list-identities node {:xt/id :dan}))
        (set [{:identity/text "text21"}
              {:identity/text "text22"}])
        (set (ds/list-identities node {:xt/id :dan2}))))))
