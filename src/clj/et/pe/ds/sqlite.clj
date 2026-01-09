(ns et.pe.ds.sqlite
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [clojure.string :as str]
            [et.pe.urbit :as urbit]
            [taoensso.telemere :as tel])
  (:import [java.time Instant]))

(defn- create-tables! [ds]
  (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS personas (
                       id TEXT PRIMARY KEY,
                       email TEXT UNIQUE NOT NULL,
                       name TEXT NOT NULL,
                       password_hash TEXT
                     )"])
  (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS identity_versions (
                       id INTEGER PRIMARY KEY AUTOINCREMENT,
                       composite_id TEXT NOT NULL,
                       persona_id TEXT NOT NULL,
                       identity_id TEXT NOT NULL,
                       name TEXT NOT NULL,
                       text TEXT,
                       valid_from INTEGER NOT NULL
                     )"])
  (jdbc/execute! ds ["CREATE INDEX IF NOT EXISTS idx_identity_versions_composite ON identity_versions(composite_id, valid_from DESC)"])
  (jdbc/execute! ds ["CREATE INDEX IF NOT EXISTS idx_identity_versions_persona ON identity_versions(persona_id, valid_from DESC)"])
  (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS relations (
                       relation_id TEXT PRIMARY KEY,
                       persona_id TEXT NOT NULL,
                       source_composite TEXT NOT NULL,
                       target_composite TEXT NOT NULL,
                       source_id TEXT NOT NULL,
                       target_id TEXT NOT NULL,
                       valid_from INTEGER NOT NULL,
                       valid_to INTEGER,
                       FOREIGN KEY (persona_id) REFERENCES personas(id)
                     )"])
  (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS relation_events (
                       id INTEGER PRIMARY KEY AUTOINCREMENT,
                       relation_id TEXT NOT NULL,
                       persona_id TEXT NOT NULL,
                       source_id TEXT NOT NULL,
                       target_id TEXT NOT NULL,
                       event_type TEXT NOT NULL,
                       valid_from INTEGER NOT NULL
                     )"])
  (jdbc/execute! ds ["CREATE INDEX IF NOT EXISTS idx_relation_events_lookup ON relation_events(persona_id, source_id, valid_from)"]))

(defn init-conn
  [type opts]
  (tel/log! :info ["Initializing SQLite connection with type:" type])
  (when (and (= type :sqlite-on-disk) (not (:path opts)))
    (throw (ex-info "Missing required :path in :db config for :sqlite-on-disk" {:type type :opts opts})))
  (let [db-spec (case type
                  :sqlite-in-memory {:dbtype "sqlite" :dbname "file::memory:?cache=shared"}
                  :sqlite-on-disk {:dbtype "sqlite" :dbname (:path opts)})
        ds (jdbc/get-datasource db-spec)
        persistent-conn (when (= type :sqlite-in-memory) (jdbc/get-connection ds))]
    (create-tables! ds)
    {:conn ds
     :persistent-conn persistent-conn
     :type type}))

(defn close-conn
  [{:keys [persistent-conn]}]
  (when persistent-conn
    (.close persistent-conn)))

(defn- instant->epoch [inst]
  (cond
    (nil? inst) (System/currentTimeMillis)
    (instance? Instant inst) (.toEpochMilli inst)
    (instance? java.time.ZonedDateTime inst) (.toEpochMilli (.toInstant inst))
    (number? inst) inst
    :else (throw (ex-info "Cannot convert to epoch" {:value inst :type (type inst)}))))

(defn- epoch->instant [epoch]
  (when epoch
    (Instant/ofEpochMilli epoch)))

(defn- kw->str [kw]
  (if (keyword? kw) (name kw) (str kw)))

(defn- str->kw [s]
  (when s (keyword s)))

(defn get-persona-by-id
  [conn id]
  (let [result (jdbc/execute-one! (:conn conn)
                                  (sql/format {:select [:id :email :name]
                                               :from [:personas]
                                               :where [:= :id (kw->str id)]})
                                  {:builder-fn rs/as-unqualified-lower-maps})]
    (when result
      {:id (str->kw (:id result))
       :email (:email result)
       :name (:name result)})))

(defn get-persona-by-email
  [conn email]
  (let [result (jdbc/execute-one! (:conn conn)
                                  (sql/format {:select [:id :email :name]
                                               :from [:personas]
                                               :where [:= :email email]})
                                  {:builder-fn rs/as-unqualified-lower-maps})]
    (when result
      {:id (str->kw (:id result))
       :email (:email result)
       :name (:name result)})))

(defn get-persona-by-id-or-email
  [conn id email]
  (let [results (jdbc/execute! (:conn conn)
                               (sql/format {:select [:id :email :name]
                                            :from [:personas]
                                            :where [:or
                                                    [:= :id (kw->str id)]
                                                    [:= :email email]]})
                               {:builder-fn rs/as-unqualified-lower-maps})]
    (map (fn [r]
           {:id (str->kw (:id r))
            :email (:email r)
            :name (:name r)})
         results)))

(defn add-persona
  [conn id email password-hash persona-name]
  (if (seq (get-persona-by-id-or-email conn id email))
    false
    (do
      (jdbc/execute! (:conn conn)
                     (sql/format {:insert-into :personas
                                  :values [{:id (kw->str id)
                                            :email email
                                            :name (or persona-name (kw->str id))
                                            :password_hash password-hash}]}))
      true)))

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
            (jdbc/execute! (:conn conn)
                           (sql/format {:update :personas
                                        :set {:email new-email
                                              :name (or name (:name current))}
                                        :where [:= :id (kw->str id)]}))
            {:success true}))))))

(defn get-persona-password-hash
  [conn id]
  (let [result (jdbc/execute-one! (:conn conn)
                                  (sql/format {:select [:password_hash]
                                               :from [:personas]
                                               :where [:= :id (kw->str id)]})
                                  {:builder-fn rs/as-unqualified-lower-maps})]
    (:password_hash result)))

(defn list-personas
  [conn]
  (let [results (jdbc/execute! (:conn conn)
                               (sql/format {:select [:id :email :name]
                                            :from [:personas]})
                               {:builder-fn rs/as-unqualified-lower-maps})]
    (map (fn [r]
           {:id (str->kw (:id r))
            :email (:email r)
            :name (:name r)})
         results)))

(defn- make-composite-id [persona-id identity-id]
  (str (kw->str persona-id) "/" (kw->str identity-id)))

(defn- latest-versions-subquery [persona-id]
  {:select [:composite_id [[:max :valid_from] :max_valid]]
   :from [:identity_versions]
   :where [:= :persona_id (kw->str persona-id)]
   :group-by [:composite_id]})

(defn list-identities
  [conn {persona-id :id :as _persona}]
  (let [results (jdbc/execute! (:conn conn)
                               (sql/format {:select [:iv.identity_id :iv.name :iv.text]
                                            :from [[:identity_versions :iv]]
                                            :join [[(latest-versions-subquery persona-id) :latest]
                                                   [:and
                                                    [:= :iv.composite_id :latest.composite_id]
                                                    [:= :iv.valid_from :latest.max_valid]]]
                                            :where [:= :iv.persona_id (kw->str persona-id)]})
                               {:builder-fn rs/as-unqualified-lower-maps})]
    (map (fn [r]
           {:identity (str->kw (:identity_id r))
            :name (:name r)
            :text (:text r)})
         results)))

(defn get-identity
  [conn {persona-id :id :as _persona} identity-id]
  (let [composite-id (make-composite-id persona-id identity-id)
        result (jdbc/execute-one! (:conn conn)
                                  (sql/format {:select [:identity_id :name :text]
                                               :from [:identity_versions]
                                               :where [:= :composite_id composite-id]
                                               :order-by [[:valid_from :desc]]
                                               :limit 1})
                                  {:builder-fn rs/as-unqualified-lower-maps})]
    (when result
      {:identity (str->kw (:identity_id result))
       :name (:name result)
       :text (:text result)})))

(defn list-recent-identities
  [conn {persona-id :id :as _persona} limit offset]
  (let [fetch-limit (inc limit)
        results (jdbc/execute! (:conn conn)
                               (sql/format {:select [:iv.identity_id :iv.name :iv.valid_from]
                                            :from [[:identity_versions :iv]]
                                            :join [[(latest-versions-subquery persona-id) :latest]
                                                   [:and
                                                    [:= :iv.composite_id :latest.composite_id]
                                                    [:= :iv.valid_from :latest.max_valid]]]
                                            :where [:= :iv.persona_id (kw->str persona-id)]
                                            :order-by [[:iv.valid_from :desc]]
                                            :limit fetch-limit
                                            :offset offset})
                               {:builder-fn rs/as-unqualified-lower-maps})
        has-more (> (count results) limit)
        page (take limit results)]
    {:items (mapv (fn [r]
                    {:identity (str->kw (:identity_id r))
                     :name (:name r)
                     :modified-at (epoch->instant (:valid_from r))})
                  page)
     :has-more has-more}))

(defn add-identity
  [conn {persona-id :id :as _persona} nm text & [{:keys [valid-from id]}]]
  (let [id (or id (keyword (urbit/generate-name)))
        composite-id (make-composite-id persona-id id)
        valid-from-epoch (instant->epoch valid-from)]
    (if (get-identity conn {:id persona-id} id)
      false
      (do
        (jdbc/execute! (:conn conn)
                       (sql/format {:insert-into :identity_versions
                                    :values [{:composite_id composite-id
                                              :persona_id (kw->str persona-id)
                                              :identity_id (kw->str id)
                                              :name nm
                                              :text text
                                              :valid_from valid-from-epoch}]}))
        id))))

(defn update-identity
  [conn {persona-id :id :as _persona} id nm text & [{:keys [valid-from]}]]
  (let [composite-id (make-composite-id persona-id id)
        valid-from-epoch (instant->epoch valid-from)]
    (jdbc/execute! (:conn conn)
                   (sql/format {:insert-into :identity_versions
                                :values [{:composite_id composite-id
                                          :persona_id (kw->str persona-id)
                                          :identity_id (kw->str id)
                                          :name nm
                                          :text text
                                          :valid_from valid-from-epoch}]}))))

(defn get-identity-at
  [conn {persona-id :id :as _persona} id time-point]
  (let [composite-id (make-composite-id persona-id id)
        time-epoch (instant->epoch time-point)
        result (jdbc/execute-one! (:conn conn)
                                  (sql/format {:select [:identity_id :name :text]
                                               :from [:identity_versions]
                                               :where [:and
                                                       [:= :composite_id composite-id]
                                                       [:<= :valid_from time-epoch]]
                                               :order-by [[:valid_from :desc]]
                                               :limit 1})
                                  {:builder-fn rs/as-unqualified-lower-maps})]
    (when result
      {:identity (str->kw (:identity_id result))
       :name (:name result)
       :text (:text result)})))

(defn get-identity-history
  [conn {persona-id :id :as _persona} id]
  (let [composite-id (make-composite-id persona-id id)
        results (jdbc/execute! (:conn conn)
                               (sql/format {:select [:identity_id :name :text :valid_from]
                                            :from [:identity_versions]
                                            :where [:= :composite_id composite-id]
                                            :order-by [[:valid_from :asc]]})
                               {:builder-fn rs/as-unqualified-lower-maps})]
    (mapv (fn [r]
            {:identity (str->kw (:identity_id r))
             :name (:name r)
             :text (:text r)
             :valid-from (epoch->instant (:valid_from r))})
          results)))

(defn- make-relation-id [persona-id source-id target-id]
  (str (kw->str persona-id) "/" (kw->str source-id) "/" (kw->str target-id)))

(defn add-relation
  [conn {persona-id :id :as _persona} source-id target-id & [{:keys [valid-from]}]]
  (let [relation-id (make-relation-id persona-id source-id target-id)
        source-composite (make-composite-id persona-id source-id)
        target-composite (make-composite-id persona-id target-id)
        valid-from-epoch (instant->epoch valid-from)
        existing (jdbc/execute-one! (:conn conn)
                                    (sql/format {:select [:relation_id]
                                                 :from [:relations]
                                                 :where [:and
                                                         [:= :relation_id relation-id]
                                                         [:is :valid_to nil]]})
                                    {:builder-fn rs/as-unqualified-lower-maps})]
    (if existing
      false
      (do
        (jdbc/execute! (:conn conn)
                       (sql/format {:insert-into :relations
                                    :values [{:relation_id relation-id
                                              :persona_id (kw->str persona-id)
                                              :source_composite source-composite
                                              :target_composite target-composite
                                              :source_id (kw->str source-id)
                                              :target_id (kw->str target-id)
                                              :valid_from valid-from-epoch
                                              :valid_to nil}]}))
        (jdbc/execute! (:conn conn)
                       (sql/format {:insert-into :relation_events
                                    :values [{:relation_id relation-id
                                              :persona_id (kw->str persona-id)
                                              :source_id (kw->str source-id)
                                              :target_id (kw->str target-id)
                                              :event_type "add"
                                              :valid_from valid-from-epoch}]}))
        true))))

(defn list-relations
  [conn {persona-id :id :as _persona} source-id & [{:keys [at]}]]
  (if at
    (let [time-epoch (instant->epoch at)
          events (jdbc/execute! (:conn conn)
                                (sql/format {:select [:relation_id :source_id :target_id :event_type :valid_from]
                                             :from [:relation_events]
                                             :where [:and
                                                     [:= :persona_id (kw->str persona-id)]
                                                     [:= :source_id (kw->str source-id)]
                                                     [:<= :valid_from time-epoch]]
                                             :order-by [[:relation_id :asc] [:valid_from :asc]]})
                                {:builder-fn rs/as-unqualified-lower-maps})
          by-relation (group-by :relation_id events)
          active-relations (for [[rel-id rel-events] by-relation
                                 :let [last-event (last rel-events)]
                                 :when (= "add" (:event_type last-event))]
                             {:id (subs rel-id (inc (.indexOf rel-id "/")))
                              :target (str->kw (:target_id last-event))})]
      (vec active-relations))
    (let [results (jdbc/execute! (:conn conn)
                                 (sql/format {:select [:relation_id :target_id]
                                              :from [:relations]
                                              :where [:and
                                                      [:= :persona_id (kw->str persona-id)]
                                                      [:= :source_id (kw->str source-id)]
                                                      [:is :valid_to nil]]})
                                 {:builder-fn rs/as-unqualified-lower-maps})]
      (mapv (fn [r]
              {:id (subs (:relation_id r) (inc (.indexOf (:relation_id r) "/")))
               :target (str->kw (:target_id r))})
            results))))

(defn delete-relation
  [conn {persona-id :id :as _persona} relation-id & [{:keys [valid-from]}]]
  (let [full-id (str (kw->str persona-id) "/" relation-id)
        valid-from-epoch (instant->epoch valid-from)
        relation (jdbc/execute-one! (:conn conn)
                                    (sql/format {:select [:source_id :target_id]
                                                 :from [:relations]
                                                 :where [:= :relation_id full-id]})
                                    {:builder-fn rs/as-unqualified-lower-maps})]
    (when relation
      (jdbc/execute! (:conn conn)
                     (sql/format {:delete-from :relations
                                  :where [:= :relation_id full-id]}))
      (jdbc/execute! (:conn conn)
                     (sql/format {:insert-into :relation_events
                                  :values [{:relation_id full-id
                                            :persona_id (kw->str persona-id)
                                            :source_id (:source_id relation)
                                            :target_id (:target_id relation)
                                            :event_type "delete"
                                            :valid_from valid-from-epoch}]})))))

(defn search-identities
  [conn {persona-id :id :as _persona} query & [{:keys [at]}]]
  (let [query-lower (str/lower-case (or query ""))
        results (if at
                  (let [time-epoch (instant->epoch at)
                        all-composites (jdbc/execute! (:conn conn)
                                                      (sql/format {:select-distinct [:composite_id]
                                                                   :from [:identity_versions]
                                                                   :where [:and
                                                                           [:= :persona_id (kw->str persona-id)]
                                                                           [:<= :valid_from time-epoch]]})
                                                      {:builder-fn rs/as-unqualified-lower-maps})]
                    (for [{:keys [composite_id]} all-composites
                          :let [version (jdbc/execute-one! (:conn conn)
                                                           (sql/format {:select [:identity_id :name :text]
                                                                        :from [:identity_versions]
                                                                        :where [:and
                                                                                [:= :composite_id composite_id]
                                                                                [:<= :valid_from time-epoch]]
                                                                        :order-by [[:valid_from :desc]]
                                                                        :limit 1})
                                                           {:builder-fn rs/as-unqualified-lower-maps})]
                          :when version]
                      version))
                  (jdbc/execute! (:conn conn)
                                 (sql/format {:select [:iv.identity_id :iv.name :iv.text]
                                              :from [[:identity_versions :iv]]
                                              :join [[(latest-versions-subquery persona-id) :latest]
                                                     [:and
                                                      [:= :iv.composite_id :latest.composite_id]
                                                      [:= :iv.valid_from :latest.max_valid]]]
                                              :where [:= :iv.persona_id (kw->str persona-id)]})
                                 {:builder-fn rs/as-unqualified-lower-maps}))]
    (->> results
         (filter (fn [r]
                   (str/includes? (str/lower-case (or (:name r) "")) query-lower)))
         (mapv (fn [r]
                 {:identity (str->kw (:identity_id r))
                  :name (:name r)
                  :text (:text r)})))))
