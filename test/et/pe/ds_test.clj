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
        (set [{:name :dan
               :email "d@et.n"}
              {:name :dan2
               :email "d2@et.n"}])
        (set (ds/list-persons node))
        {:name :dan
         :email "d@et.n"}
        (ds/get-person-by-name node :dan)
        {:name :dan2
         :email "d2@et.n"}
        (ds/get-person-by-email node "d2@et.n")))))

(deftest identities
  (with-open [node (ds/start-in-memory-node)]
    (ds/add-person node :dan "d@et.n")
    (ds/add-person node :dan2 "d2@et.n")
    (ds/add-identity node {:name :dan} :id11 "text11")
    (ds/add-identity node {:name :dan} :id12 "text12")
    (ds/add-identity node {:name :dan2} :id21 "text21")
    (ds/add-identity node {:name :dan2} :id22 "text22")
    (testing "add and retrieve identities"
      (are [expected actual] (= expected actual)
        (set [{:identity :id11 :text "text11"}
              {:identity :id12 :text "text12"}])
        (set (ds/list-identities node {:xt/id :dan}))
        (set [{:identity :id21 :text "text21"}
              {:identity :id22 :text "text22"}])
        (set (ds/list-identities node {:xt/id :dan2}))))))
