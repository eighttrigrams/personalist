(ns et.pe.ds 
  (:require [xtdb.api :as xt]
            [xtdb.node :as xtn]))

(defn- health-check []
  (= {:latest-completed-tx nil, :latest-submitted-tx nil}
     (with-open [node 
                 ;; needs ports 3000 and 5432
                 (xtn/start-node)]
       (xt/status node))))

(comment
  (health-check)

  (def n (xtn/start-node))
  
  (xt/execute-tx n [[:put-docs :foo {:xt/id "my-id", :c :d}]])
  (xt/q n '(from :foo [c]))

  (.close n)
  ;; (q node '(from :foo [{:a $a, :b $b}]) {:a a-value, :b b-value})
  :.
  )