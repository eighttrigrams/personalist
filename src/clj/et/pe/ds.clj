(ns et.pe.ds
  (:require [xtdb.api :as xt]
            [xtdb.node :as xtn]
            [clojure.string :as str]
            [et.pe.urbit :as urbit]
            [taoensso.telemere :as tel]))

(defn log-xtdb-status
  "Logs XTDB node status including compaction information."
  [{:keys [conn]}]
  (try
    (let [status (xt/status conn)
          relevant-keys [:latest-completed-tx :latest-submitted-tx]
          filtered-status (select-keys status relevant-keys)]
      (tel/log! :info ["XTDB status:" filtered-status]))
    (catch Exception e
      (tel/log! :error ["Failed to get XTDB status:" (.getMessage e)]))))

(defn init-conn
  "Throws if invalid type passed. Must be one of :in-memory, :s3, :on-disk"
  [{:keys [type path s3-bucket s3-prefix]
    :or {path "data/xtdb"}}]
  (tel/log! :info ["Initializing XTDB connection with type:" type])
  {:conn
   (case type
     :in-memory
     (do
       (tel/log! :info "Using in-memory XTDB")
       (xtn/start-node))
     :s3
     (let [node-details {:log [:local {:path "/app/data/xtdb/log"}]
                         :storage [:remote {:object-store [:s3 {:bucket s3-bucket
                                                                :prefix s3-prefix}]}]
                         :disk-cache {:path "/tmp/xtdb/cache"}}]
       (tel/log! :info ["Using S3 XTDB2 - " node-details])
       (xtn/start-node node-details))
     :on-disk
     (do
       (tel/log! :info ["Using local XTDB - path:" path])
       (tel/log! :info "Log: local, Storage: local")
       (xtn/start-node {:log [:local {:path (str path "/log")}]
                        :storage [:local {:path (str path "/storage")}]})))})

(defn close-conn
  [{:keys [conn]}]
  (.close conn))

(defn- convert-persona [{id :xt/id email :persona/email persona-name :persona/name :as persona}]
  (when-not (nil? persona)
    {:id id :email email :name (or persona-name (clojure.core/name id))}))

(defn get-persona-by-id-or-email
  [conn id email]
  (map convert-persona (xt/q (:conn conn)
                             ['(fn [id email]
                                 (-> (from :personas [xt/id persona/email persona/name])
                                     (where (or (= xt/id id)
                                                (= persona/email email)))))
                              id email])))

(defn get-persona-by-id
  [conn id]
  (convert-persona (first (xt/q (:conn conn)
                                ['(fn [id]
                                    (-> (from :personas [xt/id persona/email persona/name])
                                        (where (= xt/id id))))
                                 id]))))

(defn get-persona-by-email
  [conn email]
  (convert-persona
   (first (xt/q (:conn conn)
                ['(fn [email]
                    (-> (from :personas [xt/id persona/email persona/name])
                        (where (= persona/email email))))
                 email]))))

(defn add-persona
  [conn id email password-hash persona-name]
  (if (seq (get-persona-by-id-or-email conn id email))
    false
    (xt/execute-tx (:conn conn) [[:put-docs :personas (cond-> {:xt/id         id
                                                               :persona/email email
                                                               :persona/name  (or persona-name (clojure.core/name id))}
                                                        password-hash (assoc :persona/password-hash password-hash))]])))

(defn update-persona
  [conn id {:keys [email name]}]
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
                                                               :persona/name (or name (:name current))
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
  (map convert-persona (xt/q (:conn conn) '(from :personas [xt/id persona/email persona/name]))))

(defn- make-identity-id [persona-id id]
  (keyword (str (name persona-id) "/" (name id))))

(defn- extract-identity-id [composite-id]
  (let [s (name composite-id)
        idx (.lastIndexOf s "/")]
    (keyword (subs s (inc idx)))))

(defn- get-identity-by-composite-id
  [conn composite-id]
  (first (xt/q (:conn conn)
               ['(fn [composite-id]
                   (-> (from :identities [xt/id])
                       (where (= xt/id composite-id))))
                composite-id])))

(defn list-identities
  [conn {persona-id :id :as _persona}]
  (map
   (fn [{id :xt/id nm :identity/name}] {:identity (extract-identity-id id) :name nm})
   (xt/q (:conn conn)
         ['(fn [persona-id]
             (-> (from :identities [persona/id identity/name xt/id])
                 (where (= persona/id persona-id))
                 (return identity/name xt/id)))
          persona-id])))

(defn get-identity
  [conn {persona-id :id :as _persona} identity-id]
  (let [composite-id (make-identity-id persona-id identity-id)
        result (first (xt/q (:conn conn)
                            ['(fn [composite-id]
                                (-> (from :identities [xt/id identity/name identity/text])
                                    (where (= xt/id composite-id))
                                    (return identity/name identity/text xt/id)))
                             composite-id]))]
    (when result
      {:identity (extract-identity-id (:xt/id result))
       :name (:identity/name result)
       :text (:identity/text result)})))

(defn- to-millis [zdt]
  (cond
    (instance? java.time.ZonedDateTime zdt)
    (.toEpochMilli (.toInstant zdt))
    (instance? java.time.Instant zdt)
    (.toEpochMilli zdt)
    :else
    (do
      (tel/log! :error ["to-millis received unexpected type:" (type zdt) "value:" zdt])
      (throw (ex-info "Unexpected type for to-millis" {:type (type zdt) :value zdt})))))

(defn list-recent-identities
  [conn {persona-id :id :as _persona} limit offset]
  (let [fetch-limit (inc limit)
        results (xt/q (:conn conn)
                      ['(fn [persona-id fetch-limit offset]
                          (-> (from :identities [persona/id identity/name xt/id xt/valid-from])
                              (where (= persona/id persona-id))
                              (order-by {:val xt/valid-from, :dir :desc})
                              (offset offset)
                              (limit fetch-limit)
                              (return identity/name xt/id xt/valid-from)))
                       persona-id fetch-limit offset])
        has-more (> (count results) limit)
        page (take limit results)]
    {:items (mapv (fn [{id :xt/id nm :identity/name valid-from :xt/valid-from}]
                    {:identity (extract-identity-id id) :name nm :modified-at valid-from})
                  page)
     :has-more has-more}))

(defn add-identity
  [conn {persona-id :id :as _persona} nm text & [{:keys [valid-from id]}]]
  (let [id (or id (keyword (urbit/generate-name)))
        composite-id (make-identity-id persona-id id)]
    (if (get-identity-by-composite-id conn composite-id)
      false
      (do
        (xt/execute-tx (:conn conn)
                       [[:put-docs (cond-> {:into :identities}
                                     valid-from (assoc :valid-from valid-from))
                         {:xt/id            composite-id
                          :persona/id persona-id
                          :identity/name    nm
                          :identity/text    text}]])
        id))))

(defn update-identity
  [conn {persona-id :id :as _persona} id nm text & [{:keys [valid-from]}]]
  (xt/execute-tx (:conn conn)
                 [[:put-docs (cond-> {:into :identities}
                               valid-from (assoc :valid-from valid-from))
                   {:xt/id            (make-identity-id persona-id id)
                    :persona/id persona-id
                    :identity/name    nm
                    :identity/text    text}]]))

(defn get-identity-at
  [conn {persona-id :id :as _persona} id time-point]
  (let [composite-id (make-identity-id persona-id id)
        result (first (xt/q (:conn conn)
                            ['(fn [composite-id time-point]
                                (-> (from :identities {:bind [persona/id identity/name identity/text xt/id]
                                                       :for-valid-time (at time-point)})
                                    (where (= xt/id composite-id))
                                    (return identity/name identity/text xt/id)))
                             composite-id time-point]))]
    (when result
      {:identity id :name (:identity/name result) :text (:identity/text result)})))

(defn get-identity-history
  [conn {persona-id :id :as _persona} id]
  (let [composite-id (make-identity-id persona-id id)
        results (xt/q (:conn conn)
                      ['(fn [composite-id]
                          (-> (from :identities {:bind [persona/id identity/name identity/text xt/id xt/valid-from]
                                                 :for-valid-time :all-time})
                              (where (= xt/id composite-id))
                              (order-by xt/valid-from)
                              (return identity/name identity/text xt/id xt/valid-from)))
                       composite-id])]
    (mapv (fn [{:keys [identity/name identity/text xt/valid-from]}]
            {:identity id :name name :text text :valid-from valid-from})
          results)))

(defn- make-relation-id [persona-id source-id target-id]
  (keyword (str (name persona-id) "/" (name source-id) "/" (name target-id))))

(defn- get-relation-by-id
  [conn relation-id]
  (first (xt/q (:conn conn)
               ['(fn [relation-id]
                   (-> (from :relations [xt/id])
                       (where (= xt/id relation-id))))
                relation-id])))

(defn add-relation
  [conn {persona-id :id :as _persona} source-id target-id & [{:keys [valid-from]}]]
  (let [relation-id (make-relation-id persona-id source-id target-id)
        source-composite (make-identity-id persona-id source-id)
        target-composite (make-identity-id persona-id target-id)]
    (if (get-relation-by-id conn relation-id)
      false
      (do
        (xt/execute-tx (:conn conn)
                       [[:put-docs (cond-> {:into :relations}
                                     valid-from (assoc :valid-from valid-from))
                         {:xt/id             relation-id
                          :relation/source   source-composite
                          :relation/target   target-composite
                          :persona/id  persona-id}]])
        true))))

(defn list-relations
  [conn {persona-id :id :as _persona} source-id & [{:keys [at]}]]
  (let [source-composite (make-identity-id persona-id source-id)
        results (if at
                  (xt/q (:conn conn)
                        ['(fn [source-composite time-point]
                            (-> (from :relations {:bind [xt/id relation/source relation/target persona/id]
                                                  :for-valid-time (at time-point)})
                                (where (= relation/source source-composite))
                                (return xt/id relation/source relation/target)))
                         source-composite at])
                  (xt/q (:conn conn)
                        ['(fn [source-composite]
                            (-> (from :relations [xt/id relation/source relation/target persona/id])
                                (where (= relation/source source-composite))
                                (return xt/id relation/source relation/target)))
                         source-composite]))]
    (mapv (fn [{:keys [xt/id relation/target]}]
            {:id (name id)
             :target (extract-identity-id target)})
          results)))

(defn delete-relation
  [conn {persona-id :id :as _persona} relation-id & [{:keys [valid-from]}]]
  (let [full-id (keyword (str (name persona-id) "/" relation-id))]
    (xt/execute-tx (:conn conn)
                   [(if valid-from
                      [:delete-docs {:from :relations :valid-from valid-from} full-id]
                      [:delete-docs :relations full-id])])))

(defn search-identities
  [conn {persona-id :id :as _persona} query & [{:keys [at]}]]
  (let [results (if at
                  (xt/q (:conn conn)
                        ['(fn [persona-id time-point]
                            (-> (from :identities {:bind [persona/id identity/name identity/text xt/id]
                                                   :for-valid-time (at time-point)})
                                (where (= persona/id persona-id))
                                (return identity/name identity/text xt/id)))
                         persona-id at])
                  (xt/q (:conn conn)
                        ['(fn [persona-id]
                            (-> (from :identities [persona/id identity/name identity/text xt/id])
                                (where (= persona/id persona-id))
                                (return identity/name identity/text xt/id)))
                         persona-id]))
        query-lower (str/lower-case (or query ""))]
    (->> results
         (filter (fn [{:keys [identity/name]}]
                   (str/includes? (str/lower-case (or name "")) query-lower)))
         (mapv (fn [{id :xt/id nm :identity/name text :identity/text}]
                 {:identity (extract-identity-id id) :name nm :text text})))))
