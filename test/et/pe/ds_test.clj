(ns et.pe.ds-test
  (:require [clojure.test :refer [deftest testing are]]
            [et.pe.ds :as ds]))

(deftest persons
  (testing "add persons"
    (with-open [node (ds/get-connection {:type :xtdb2-in-memory})]
      (ds/add-person node :dan "d@et.n")
      (testing "- can't add a person with the same name"
        (are [expected actual] (= expected actual) ;; <- TODO factor that away
          false (ds/add-person node :dan "d2@et.n")
          1 (count (ds/list-persons node))))
      (testing "- can't add a person with the same email"
        (are [expected actual] (= expected actual) 
          false (ds/add-person node :dan2 "d@et.n")
          1 (count (ds/list-persons node))))))
  
  (with-open [node (ds/get-connection {:type :xtdb2-in-memory})]
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
  (with-open [node (ds/get-connection {:type :xtdb2-in-memory})]
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
