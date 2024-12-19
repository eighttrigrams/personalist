(ns et.pe.ds 
  (:require [xtdb.api :as xt]
            [xtdb.node :as xtn]))

(defn get-connection 
  "@param opts 
     -> :type :xtdb2-in-memory - creates an in memory xtdb2 node; needs ports 3000 and 5432" 
  [{:keys [type] :as _opts}]
  (when (= :xtdb2-in-memory type)
    (xtn/start-node)))

(defonce node (get-connection {:type :xtdb2-in-memory}))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn close-node [] (.close node))

(defn- health-check []
  (= {:latest-completed-tx nil, :latest-submitted-tx nil}
     (with-open [node (get-connection {:type :xtdb2-in-memory})]
       (xt/status node))))

(defn- convert-person [{name :xt/id email :person/email :as person}]
  (when-not (nil? person)
    {:name name :email email}))

(defn get-person-by-name-or-email 
  "@returns a person if either the given name or the email match" 
  [node name email]
  (map convert-person (xt/q node 
                     '(-> (from :persons [xt/id person/email])
                          (where (or (= xt/id $name)
                                     (= person/email $email))))
                     {:args {:name  name
                             :email email}})))

(defn get-person-by-name 
  [node name]
  (convert-person (first (xt/q node 
                        '(-> (from :persons [xt/id person/email])
                             (where (= xt/id $name)))
                        {:args {:name name}}))))

(defn get-person-by-email 
  [node email]
  (convert-person
   (first (xt/q node 
                '(-> (from :persons [xt/id person/email])
                     (where (= person/email $email)))
                {:args {:email email}}))))

(defn add-person 
  "@returns true if person added, false otherwise"
  [node name email]
  (if (seq (get-person-by-name-or-email node name email))
    false
    (xt/execute-tx node [[:put-docs :persons {:xt/id        name        , 
                                              :person/email email}]])))

(defn list-persons [node]
  (map convert-person (xt/q node '(from :persons [xt/id person/email]))))

(defn list-identities [node {person-id :xt/id :as _mind}]
  (map
   (fn [{id :xt/id text :identity/text}] {:identity id :text text})
   (xt/q node 
         '(-> (from :identities [identity/mind-id identity/text xt/id])
              (where (= identity/mind-id $person-id))
              (return identity/text xt/id))
         {:args {:person-id person-id}})))

(defn add-identity
  "@param mind - person the identity belongs to"
  [node {person-id :name :as _mind} id text]
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