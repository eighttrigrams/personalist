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

(defn- convert-persona [{name :xt/id email :persona/email :as persona}]
  (when-not (nil? persona)
    {:name name :email email}))

(defmethod dispatch/get-persona-by-name-or-email :xtdb2-in-memory
  [conn name email]
  (map convert-persona (xt/q (:conn conn)
                             ['(fn [name email]
                                 (-> (from :personas [xt/id persona/email])
                                     (where (or (= xt/id name)
                                                (= persona/email email)))))
                              name email])))

(defmethod dispatch/get-persona-by-name :xtdb2-in-memory
  [conn name]
  (convert-persona (first (xt/q (:conn conn)
                                ['(fn [name]
                                    (-> (from :personas [xt/id persona/email])
                                        (where (= xt/id name))))
                                 name]))))

(defmethod dispatch/get-persona-by-email :xtdb2-in-memory
  [conn email]
  (convert-persona
   (first (xt/q (:conn conn)
                ['(fn [email]
                    (-> (from :personas [xt/id persona/email])
                        (where (= persona/email email))))
                 email]))))

(defmethod dispatch/add-persona :xtdb2-in-memory
  [conn name email]
  (if (seq (dispatch/get-persona-by-name-or-email conn name email))
    false
    (xt/execute-tx (:conn conn) [[:put-docs :personas {:xt/id         name
                                                       :persona/email email}]])))

(defmethod dispatch/list-personas :xtdb2-in-memory
  [conn]
  (map convert-persona (xt/q (:conn conn) '(from :personas [xt/id persona/email]))))

(defmethod dispatch/list-identities :xtdb2-in-memory
  [conn {persona-id :name :as _mind}]
  (map
   (fn [{id :xt/id text :identity/text}] {:identity id :text text})
   (xt/q (:conn conn)
         ['(fn [persona-id]
             (-> (from :identities [identity/mind-id identity/text xt/id])
                 (where (= identity/mind-id persona-id))
                 (return identity/text xt/id)))
          persona-id])))

(defmethod dispatch/add-identity :xtdb2-in-memory
  [conn {persona-id :name :as _mind} id text & [{:keys [valid-from]}]]
  (xt/execute-tx (:conn conn)
                 [[:put-docs (cond-> {:into :identities}
                               valid-from (assoc :valid-from valid-from))
                   {:xt/id            id
                    :identity/mind-id persona-id
                    :identity/text    text}]]))

(defmethod dispatch/update-identity :xtdb2-in-memory
  [conn {persona-id :name :as _mind} id text & [{:keys [valid-from]}]]
  (xt/execute-tx (:conn conn)
                 [[:put-docs (cond-> {:into :identities}
                               valid-from (assoc :valid-from valid-from))
                   {:xt/id            id
                    :identity/mind-id persona-id
                    :identity/text    text}]]))

(defmethod dispatch/get-identity-at :xtdb2-in-memory
  [conn {persona-id :name :as _mind} id time-point]
  (let [result (first (xt/q (:conn conn)
                            ['(fn [persona-id id time-point]
                                (-> (from :identities {:bind [identity/mind-id identity/text xt/id]
                                                       :for-valid-time (at time-point)})
                                    (where (= identity/mind-id persona-id)
                                           (= xt/id id))
                                    (return identity/text xt/id)))
                             persona-id id time-point]))]
    (when result
      {:identity (:xt/id result) :text (:identity/text result)})))

(defmethod dispatch/get-identity-history :xtdb2-in-memory
  [conn {persona-id :name :as _mind} id]
  (let [results (xt/q (:conn conn)
                      ['(fn [persona-id id]
                          (-> (from :identities {:bind [identity/mind-id identity/text xt/id xt/valid-from]
                                                 :for-valid-time :all-time})
                              (where (= identity/mind-id persona-id)
                                     (= xt/id id))
                              (order-by xt/valid-from)
                              (return identity/text xt/id xt/valid-from)))
                       persona-id id])]
    (mapv (fn [{:keys [xt/id identity/text xt/valid-from]}]
            {:identity id :text text :valid-from valid-from})
          results)))
