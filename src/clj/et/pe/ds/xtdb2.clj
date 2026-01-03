(ns et.pe.ds.xtdb2
  (:require [xtdb.api :as xt]
            [xtdb.node :as xtn]
            [clojure.string :as str])
  (:import [java.util UUID]))

(defn init-conn
  [{:keys [type path] :or {path "data/xtdb"}}]
  (if (= type :xtdb2-in-memory)
    {:conn (xtn/start-node)}
    {:conn (xtn/start-node {:log [:local {:path (str path "/log")}]
                            :storage [:local {:path (str path "/storage")}]})}))

(defn close-conn
  [{:keys [conn]}]
  (.close conn))

(defn- convert-persona [{id :xt/id email :persona/email display-name :persona/display-name :as persona}]
  (when-not (nil? persona)
    {:id id :email email :display-name (or display-name (clojure.core/name id))}))

(defn get-persona-by-id-or-email
  [conn id email]
  (map convert-persona (xt/q (:conn conn)
                             ['(fn [id email]
                                 (-> (from :personas [xt/id persona/email persona/display-name])
                                     (where (or (= xt/id id)
                                                (= persona/email email)))))
                              id email])))

(defn get-persona-by-id
  [conn id]
  (convert-persona (first (xt/q (:conn conn)
                                ['(fn [id]
                                    (-> (from :personas [xt/id persona/email persona/display-name])
                                        (where (= xt/id id))))
                                 id]))))

(defn get-persona-by-email
  [conn email]
  (convert-persona
   (first (xt/q (:conn conn)
                ['(fn [email]
                    (-> (from :personas [xt/id persona/email persona/display-name])
                        (where (= persona/email email))))
                 email]))))

(defn add-persona
  [conn id email password-hash display-name]
  (if (seq (get-persona-by-id-or-email conn id email))
    false
    (xt/execute-tx (:conn conn) [[:put-docs :personas (cond-> {:xt/id                id
                                                               :persona/email        email
                                                               :persona/display-name (or display-name (clojure.core/name id))}
                                                        password-hash (assoc :persona/password-hash password-hash))]])))

(defn update-persona
  [conn id {:keys [email display-name]}]
  (let [current (get-persona-by-id conn id)]
    (if-not current
      nil
      (let [new-email (or email (:email current))
            existing (when email (get-persona-by-email conn email))]
        (if (and existing (not= (:id existing) id))
          {:error :email-exists}
          (do
            (xt/execute-tx (:conn conn) [[:put-docs :personas {:xt/id                id
                                                               :persona/email        new-email
                                                               :persona/display-name (or display-name (:display-name current))
                                                               :persona/password-hash (:persona/password-hash current)}]])
            {:success true}))))))

(defn get-persona-password-hash
  [conn id]
  (let [result (first (xt/q (:conn conn)
                            ['(fn [id]
                                (-> (from :personas [xt/id persona/password-hash])
                                    (where (= xt/id id))))
                             id]))]
    (:persona/password-hash result)))

(defn list-personas
  [conn]
  (map convert-persona (xt/q (:conn conn) '(from :personas [xt/id persona/email persona/display-name]))))

(defn- make-identity-id [persona-id id]
  (keyword (str (name persona-id) "/" (name id))))

(defn- extract-identity-id [composite-id]
  (let [s (name composite-id)
        idx (.lastIndexOf s "/")]
    (keyword (subs s (inc idx)))))

(defn list-identities
  [conn {persona-id :id :as _mind}]
  (map
   (fn [{id :xt/id nm :identity/name text :identity/text}] {:identity (extract-identity-id id) :name nm :text text})
   (xt/q (:conn conn)
         ['(fn [persona-id]
             (-> (from :identities [identity/mind-id identity/name identity/text xt/id])
                 (where (= identity/mind-id persona-id))
                 (return identity/name identity/text xt/id)))
          persona-id])))

(defn add-identity
  [conn {persona-id :id :as _mind} nm text & [{:keys [valid-from id]}]]
  (let [id (or id (keyword (str (UUID/randomUUID))))]
    (xt/execute-tx (:conn conn)
                   [[:put-docs (cond-> {:into :identities}
                                 valid-from (assoc :valid-from valid-from))
                     {:xt/id            (make-identity-id persona-id id)
                      :identity/mind-id persona-id
                      :identity/name    nm
                      :identity/text    text}]])
    id))

(defn update-identity
  [conn {persona-id :id :as _mind} id nm text & [{:keys [valid-from]}]]
  (xt/execute-tx (:conn conn)
                 [[:put-docs (cond-> {:into :identities}
                               valid-from (assoc :valid-from valid-from))
                   {:xt/id            (make-identity-id persona-id id)
                    :identity/mind-id persona-id
                    :identity/name    nm
                    :identity/text    text}]]))

(defn get-identity-at
  [conn {persona-id :id :as _mind} id time-point]
  (let [composite-id (make-identity-id persona-id id)
        result (first (xt/q (:conn conn)
                            ['(fn [composite-id time-point]
                                (-> (from :identities {:bind [identity/mind-id identity/name identity/text xt/id]
                                                       :for-valid-time (at time-point)})
                                    (where (= xt/id composite-id))
                                    (return identity/name identity/text xt/id)))
                             composite-id time-point]))]
    (when result
      {:identity id :name (:identity/name result) :text (:identity/text result)})))

(defn get-identity-history
  [conn {persona-id :id :as _mind} id]
  (let [composite-id (make-identity-id persona-id id)
        results (xt/q (:conn conn)
                      ['(fn [composite-id]
                          (-> (from :identities {:bind [identity/mind-id identity/name identity/text xt/id xt/valid-from]
                                                 :for-valid-time :all-time})
                              (where (= xt/id composite-id))
                              (order-by xt/valid-from)
                              (return identity/name identity/text xt/id xt/valid-from)))
                       composite-id])]
    (mapv (fn [{:keys [identity/name identity/text xt/valid-from]}]
            {:identity id :name name :text text :valid-from valid-from})
          results)))

(defn- make-relation-id [persona-id source-id target-id]
  (keyword (str (name persona-id) "/rel-" (name source-id) "->" (name target-id))))

(defn add-relation
  [conn {persona-id :id :as _mind} source-id target-id & [{:keys [valid-from]}]]
  (let [relation-id (make-relation-id persona-id source-id target-id)
        source-composite (make-identity-id persona-id source-id)
        target-composite (make-identity-id persona-id target-id)]
    (xt/execute-tx (:conn conn)
                   [[:put-docs (cond-> {:into :relations}
                                 valid-from (assoc :valid-from valid-from))
                     {:xt/id             relation-id
                      :relation/source   source-composite
                      :relation/target   target-composite
                      :relation/mind-id  persona-id}]])))

(defn list-relations
  [conn {persona-id :id :as _mind} target-id & [{:keys [at]}]]
  (let [target-composite (make-identity-id persona-id target-id)
        results (if at
                  (xt/q (:conn conn)
                        ['(fn [target-composite time-point]
                            (-> (from :relations {:bind [xt/id relation/source relation/target relation/mind-id]
                                                  :for-valid-time (at time-point)})
                                (where (= relation/target target-composite))
                                (return xt/id relation/source relation/target)))
                         target-composite at])
                  (xt/q (:conn conn)
                        ['(fn [target-composite]
                            (-> (from :relations [xt/id relation/source relation/target relation/mind-id])
                                (where (= relation/target target-composite))
                                (return xt/id relation/source relation/target)))
                         target-composite]))]
    (mapv (fn [{:keys [xt/id relation/source]}]
            {:id (name id)
             :source (extract-identity-id source)})
          results)))

(defn delete-relation
  [conn {persona-id :id :as _mind} relation-id]
  (let [full-id (keyword (str (name persona-id) "/" relation-id))]
    (xt/execute-tx (:conn conn)
                   [[:delete-docs :relations full-id]])))

(defn search-identities
  [conn {persona-id :id :as _mind} query & [{:keys [at]}]]
  (let [results (if at
                  (xt/q (:conn conn)
                        ['(fn [persona-id time-point]
                            (-> (from :identities {:bind [identity/mind-id identity/name identity/text xt/id]
                                                   :for-valid-time (at time-point)})
                                (where (= identity/mind-id persona-id))
                                (return identity/name identity/text xt/id)))
                         persona-id at])
                  (xt/q (:conn conn)
                        ['(fn [persona-id]
                            (-> (from :identities [identity/mind-id identity/name identity/text xt/id])
                                (where (= identity/mind-id persona-id))
                                (return identity/name identity/text xt/id)))
                         persona-id]))
        query-lower (str/lower-case (or query ""))]
    (->> results
         (filter (fn [{:keys [identity/name]}]
                   (str/includes? (str/lower-case (or name "")) query-lower)))
         (mapv (fn [{id :xt/id nm :identity/name text :identity/text}]
                 {:identity (extract-identity-id id) :name nm :text text})))))
