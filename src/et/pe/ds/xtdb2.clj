(ns et.pe.ds.xtdb2
  (:require [xtdb.api :as xt]
            [xtdb.node :as xtn]
            [et.pe.ds.dispatch :as dispatch]))

(defmethod dispatch/init-conn :xtdb2-in-memory
  [_conn]
  {:type :xtdb2-in-memory 
   :conn (xtn/start-node)})

(defmethod dispatch/close-conn :xtdb2-in-memory
  [{:keys [conn]}]
  (.close conn))

#_{:clj-kondo/ignore [:unused-private-var]}
(defn- health-check []
  (= {:latest-completed-tx nil, :latest-submitted-tx nil}
     (with-open [node (dispatch/init-conn {:type :xtdb2-in-memory})]
       (xt/status node))))

(defn- convert-person [{name :xt/id email :person/email :as person}]
  (when-not (nil? person)
    {:name name :email email}))

(defmethod dispatch/get-person-by-name-or-email :xtdb2-in-memory
  [conn name email]
  (map convert-person (xt/q (:conn conn) 
                     '(-> (from :persons [xt/id person/email])
                          (where (or (= xt/id $name)
                                     (= person/email $email))))
                     {:args {:name  name
                             :email email}})))

(defmethod dispatch/get-person-by-name :xtdb2-in-memory
  [conn name]
  (convert-person (first (xt/q (:conn conn) 
                        '(-> (from :persons [xt/id person/email])
                             (where (= xt/id $name)))
                        {:args {:name name}}))))

(defmethod dispatch/get-person-by-email :xtdb2-in-memory
  [conn email]
  (convert-person
   (first (xt/q (:conn conn) 
                '(-> (from :persons [xt/id person/email])
                     (where (= person/email $email)))
                {:args {:email email}}))))

(defmethod dispatch/add-person :xtdb2-in-memory
  [conn name email]
  (if (seq (dispatch/get-person-by-name-or-email conn name email))
    false
    (xt/execute-tx (:conn conn) [[:put-docs :persons {:xt/id        name        , 
                                                      :person/email email}]])))

(defmethod dispatch/list-persons :xtdb2-in-memory
  [conn]
  (map convert-person (xt/q (:conn conn) '(from :persons [xt/id person/email]))))

(defmethod dispatch/list-identities :xtdb2-in-memory
  [conn {person-id :name :as _mind}]
  (map
   (fn [{id :xt/id text :identity/text}] {:identity id :text text})
   (xt/q (:conn conn) 
         '(-> (from :identities [identity/mind-id identity/text xt/id])
              (where (= identity/mind-id $person-id))
              (return identity/text xt/id))
         {:args {:person-id person-id}})))

(defmethod dispatch/add-identity :xtdb2-in-memory
  [conn {person-id :name :as _mind} id text]
  (xt/execute-tx (:conn conn)
                 [[:put-docs :identities {:xt/id            id 
                                          :identity/mind-id person-id
                                          :identity/text    text}]]))
