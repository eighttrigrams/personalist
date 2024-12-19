(ns et.pe.ds 
  (:require [xtdb.api :as xt]
            [xtdb.node :as xtn]))

(defn start-in-memory-node 
  "needs ports 3000 and 5432" 
  []
  (xtn/start-node))

(defonce node (start-in-memory-node))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn close-node [] (.close node))

(defn- health-check []
  (= {:latest-completed-tx nil, :latest-submitted-tx nil}
     (with-open [node (start-in-memory-node)]
       (xt/status node))))

(defn get-person-by-name [node name]
  (xt/q node '(from :persons [{:xt/id ?name} xt/id]) {:name name}))

(defn get-person-by-email [node email]
  (xt/q node '(from :persons [{:person/email ?email} xt/id]) {:email email}))

(defn add-person 
  "@returns true if person added, false otherwise"
  [node name email]
  (if (or
       ;; TODO do that in a single query
       (seq (get-person-by-name node name))
       (seq (get-person-by-email node email)))
    false
    (xt/execute-tx node [[:put-docs :persons {:xt/id        name        , 
                                              :person/email email}]])))

(defn list-persons [node]
  (xt/q node '(from :persons [xt/id person/email])))

(comment
  (health-check)
  :.)