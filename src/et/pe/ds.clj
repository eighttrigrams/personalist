(ns et.pe.ds 
  (:require [xtdb.api :as xt]
            [xtdb.node :as xtn]))

(defn start-in-memory-node 
  "needs ports 3000 and 5432" 
  []
  (xtn/start-node))

(defonce node (xtn/start-node))

(defn- health-check []
  (= {:latest-completed-tx nil, :latest-submitted-tx nil}
     (with-open [node (start-in-memory-node)]
       (xt/status node))))

(defn add-person [node name email]
  (xt/execute-tx node [[:put-docs :persons {:xt/id        name        , 
                                            :person/email email}]]))

(defn list-persons [node]
  (xt/q node '(from :persons [xt/id person/email])))

(comment
  (health-check)
;;   (def n (xtn/start-node))
;;   (xt/execute-tx n [[:put-docs :foo {:xt/id "my-id", :c :d}]])
;;   (xt/q n '(from :foo [c]))
  (.close node)
  
  (add-person node :dan "a@b.c")
  (xt/q node '(from :persons [xt/id person/email]))
  :.)