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

(defn- make-identity-id [persona-id id]
  (keyword (str (name persona-id) "/" (name id))))

(defn- extract-identity-id [composite-id]
  (let [s (name composite-id)
        idx (.lastIndexOf s "/")]
    (keyword (subs s (inc idx)))))

(defmethod dispatch/list-identities :xtdb2-in-memory
  [conn {persona-id :name :as _mind}]
  (map
   (fn [{id :xt/id text :identity/text}] {:identity (extract-identity-id id) :text text})
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
                   {:xt/id            (make-identity-id persona-id id)
                    :identity/mind-id persona-id
                    :identity/text    text}]]))

(defmethod dispatch/update-identity :xtdb2-in-memory
  [conn {persona-id :name :as _mind} id text & [{:keys [valid-from]}]]
  (xt/execute-tx (:conn conn)
                 [[:put-docs (cond-> {:into :identities}
                               valid-from (assoc :valid-from valid-from))
                   {:xt/id            (make-identity-id persona-id id)
                    :identity/mind-id persona-id
                    :identity/text    text}]]))

(defmethod dispatch/get-identity-at :xtdb2-in-memory
  [conn {persona-id :name :as _mind} id time-point]
  (let [composite-id (make-identity-id persona-id id)
        result (first (xt/q (:conn conn)
                            ['(fn [composite-id time-point]
                                (-> (from :identities {:bind [identity/mind-id identity/text xt/id]
                                                       :for-valid-time (at time-point)})
                                    (where (= xt/id composite-id))
                                    (return identity/text xt/id)))
                             composite-id time-point]))]
    (when result
      {:identity id :text (:identity/text result)})))

(defmethod dispatch/get-identity-history :xtdb2-in-memory
  [conn {persona-id :name :as _mind} id]
  (let [composite-id (make-identity-id persona-id id)
        results (xt/q (:conn conn)
                      ['(fn [composite-id]
                          (-> (from :identities {:bind [identity/mind-id identity/text xt/id xt/valid-from]
                                                 :for-valid-time :all-time})
                              (where (= xt/id composite-id))
                              (order-by xt/valid-from)
                              (return identity/text xt/id xt/valid-from)))
                       composite-id])]
    (mapv (fn [{:keys [identity/text xt/valid-from]}]
            {:identity id :text text :valid-from valid-from})
          results)))
