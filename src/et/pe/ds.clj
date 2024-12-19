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

(defn get-person-by-name-or-email 
  "@returns a person if either the given name or the email match" 
  [node name email]
  (xt/q node 
        '(from :persons 
               [(or {:xt/id ?name}
                    {:person/email ?email}) xt/id]) 
        #_{:name  name
         :email email}))

(defn add-person 
  "@returns true if person added, false otherwise"
  [node name email]
  (if (seq (get-person-by-name-or-email node name email))
    false
    (xt/execute-tx node [[:put-docs :persons {:xt/id        name        , 
                                              :person/email email}]])))

(defn list-persons [node]
  (xt/q node '(from :persons [xt/id person/email])))

(defn list-identities [node {person-id :xt/id :as _mind}]
  (xt/q node 
        '(-> (from :identities [identity/mind-id identity/text])
             (where (= identity/mind-id $person-id))
             (return identity/text))
        {:args {:person-id person-id}}))

(defn add-identity
  "@param mind - person the identity belongs to"
  [node {person-id :xt/id :as _mind} id text]
  (xt/execute-tx node [[:put-docs :identities {:xt/id            id 
                                               :identity/mind-id person-id
                                               :identity/text    text}]]))

(comment
  (health-check)

  (with-open [node (start-in-memory-node)]
    (add-person node :dan "dan@g.c")
    (add-person node :dan2 "dan2@g.c")
    (add-identity node {:xt/id :dan} :id1 "text")
    (add-identity node {:xt/id :dan2} :id2 "text2")
    (list-identities node {:xt/id :dan}))
  :.)