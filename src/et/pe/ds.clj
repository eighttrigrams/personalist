(ns et.pe.ds 
  (:require [xtdb.api :as xt]
            [xtdb.node :as xtn]))

(defn init-conn 
  "@param opts 
     -> :type :xtdb2-in-memory - creates an in memory xtdb2 node; needs ports 3000 and 5432" 
  [{:keys [type] :as _opts}]
  (when (= :xtdb2-in-memory type)
    {:type :xtdb2-in-memory 
     :conn (xtn/start-node)}))

(defn close-conn 
  [{:keys [conn]}]
  (.close conn))

(defonce node nil #_(init-conn {:type :xtdb2-in-memory}))

(defn- health-check []
  (= {:latest-completed-tx nil, :latest-submitted-tx nil}
     (with-open [node (init-conn {:type :xtdb2-in-memory})]
       (xt/status node))))

(defn- convert-person [{name :xt/id email :person/email :as person}]
  (when-not (nil? person)
    {:name name :email email}))

(defn get-person-by-name-or-email 
  "@returns a person if either the given name or the email match" 
  [conn name email]
  (map convert-person (xt/q (get-closable conn) 
                     '(-> (from :persons [xt/id person/email])
                          (where (or (= xt/id $name)
                                     (= person/email $email))))
                     {:args {:name  name
                             :email email}})))

(defn get-person-by-name 
  [conn name]
  (convert-person (first (xt/q (get-closable conn) 
                        '(-> (from :persons [xt/id person/email])
                             (where (= xt/id $name)))
                        {:args {:name name}}))))

(defn get-person-by-email 
  [conn email]
  (convert-person
   (first (xt/q (get-closable conn) 
                '(-> (from :persons [xt/id person/email])
                     (where (= person/email $email)))
                {:args {:email email}}))))

(defn add-person 
  "@returns true if person added, false otherwise"
  [conn name email]
  (if (seq (get-person-by-name-or-email conn name email))
    false
    (xt/execute-tx (get-closable conn) [[:put-docs :persons {:xt/id        name        , 
                                                         :person/email email}]])))

(defn list-persons [conn]
  (map convert-person (xt/q (get-closable conn) '(from :persons [xt/id person/email]))))

(defn list-identities [conn {person-id :xt/id :as _mind}]
  (map
   (fn [{id :xt/id text :identity/text}] {:identity id :text text})
   (xt/q (get-closable conn) 
         '(-> (from :identities [identity/mind-id identity/text xt/id])
              (where (= identity/mind-id $person-id))
              (return identity/text xt/id))
         {:args {:person-id person-id}})))

(defn add-identity
  "@param mind - person the identity belongs to"
  [conn {person-id :name :as _mind} id text]
  (xt/execute-tx (get-closable conn)
                 [[:put-docs :identities {:xt/id            id 
                                          :identity/mind-id person-id
                                          :identity/text    text}]]))

(comment
  (health-check)

  (with-open [conn (init-conn {:type :xtdb2-in-memory})]
    (add-person conn :dan "dan@g.c")
    (add-person conn :dan2 "dan2@g.c")
    (add-identity conn {:xt/id :dan} :id1 "text")
    (add-identity conn {:xt/id :dan2} :id2 "text2")
    (list-identities conn {:xt/id :dan}))
  :.)