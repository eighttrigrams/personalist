(ns et.pe.ds-test
  (:require [clojure.test :refer [deftest testing is]]
            [et.pe.ds :as ds]))

(deftest add-and-retrieve-persons
  (testing "add and retrieve a person"
    (is 
     (= {:xt/id        :dan
         :person/email "d@et.n"}
        (first (with-open [node (ds/start-in-memory-node)]
                 (ds/add-person node :dan "d@et.n")
                 (ds/list-persons node)))))))
