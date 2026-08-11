(ns et.pe.ds.sqlite
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [et.pe.urbit :as urbit]
            [et.pe.ds.migrations :as migrations]
            [taoensso.telemere :as tel])
  (:import [java.time Instant]))

(defn init-conn
  [type opts]
  (tel/log! :info ["Initializing SQLite connection with type:" type])
  (when (and (= type :sqlite-on-disk) (not (:path opts)))
    (throw (ex-info "Missing required :path in :db config for :sqlite-on-disk" {:type type :opts opts})))
  (let [db-spec (case type
                  :sqlite-in-memory {:dbtype "sqlite" :dbname "file::memory:?cache=shared"}
                  :sqlite-on-disk {:dbtype "sqlite" :dbname (:path opts)})
        ds (jdbc/get-datasource db-spec)
        persistent-conn (when (= type :sqlite-in-memory) (jdbc/get-connection ds))
        conn-for-use (or persistent-conn ds)
        _ (migrations/migrate! conn-for-use)]
    {:conn conn-for-use
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

;; ---------------------------------------------------------------------------
;; Accounts and personas
;;
;; Since migration 003 these are two tables. An account is an email and a
;; password and nothing a visitor ever sees; a persona is the public address
;; (personalist.org/<persona-id>/...) plus its own display name. One account
;; holds many personas, and nothing anonymous ever reveals which ones belong
;; together — that privacy rule is why no read here returns an email unless the
;; caller has already proven it owns the account.
;; ---------------------------------------------------------------------------

(defn get-account
  [conn account-id]
  (let [result (jdbc/execute-one! (:conn conn)
                                  (sql/format {:select [:id :email]
                                               :from [:accounts]
                                               :where [:= :id account-id]})
                                  {:builder-fn rs/as-unqualified-lower-maps})]
    (when result
      {:id (:id result) :email (:email result)})))

(defn get-account-by-email
  [conn email]
  (let [result (jdbc/execute-one! (:conn conn)
                                  (sql/format {:select [:id :email]
                                               :from [:accounts]
                                               :where [:= :email email]})
                                  {:builder-fn rs/as-unqualified-lower-maps})]
    (when result
      {:id (:id result) :email (:email result)})))

(defn add-account
  "Mint an account, answering its id — or false when the email is already spent.
   Email is the only uniqueness left at this level; persona ids are checked
   separately, because they are global."
  [conn email password-hash]
  (if (get-account-by-email conn email)
    false
    (do
      (jdbc/execute! (:conn conn)
                     (sql/format {:insert-into :accounts
                                  :values [{:email email
                                            :password_hash password-hash}]}))
      ;; Read the key back off the UNIQUE email rather than through a driver's
      ;; generated-keys shape, which differs per JDBC driver.
      (:id (get-account-by-email conn email)))))

(defn get-account-password-hash
  [conn account-id]
  (let [result (jdbc/execute-one! (:conn conn)
                                  (sql/format {:select [:password_hash]
                                               :from [:accounts]
                                               :where [:= :id account-id]})
                                  {:builder-fn rs/as-unqualified-lower-maps})]
    (:password_hash result)))

(defn get-persona-by-id
  "A persona with the account that holds it. :account-id is what the ownership
   guard compares against a token's :account claim."
  [conn id]
  (let [result (jdbc/execute-one! (:conn conn)
                                  (sql/format {:select [:id :account_id :name :sort_order]
                                               :from [:personas]
                                               :where [:= :id (kw->str id)]})
                                  {:builder-fn rs/as-unqualified-lower-maps})]
    (when result
      {:id (str->kw (:id result))
       :name (:name result)
       :account-id (:account_id result)
       :sort-order (:sort_order result)})))

(defn- next-sort-order
  "Where a new persona lands within its account: after the last one."
  [conn account-id]
  (let [result (jdbc/execute-one! (:conn conn)
                                  (sql/format {:select [[[:max :sort_order] :max_order]]
                                               :from [:personas]
                                               :where [:= :account_id account-id]})
                                  {:builder-fn rs/as-unqualified-lower-maps})]
    (if-let [m (:max_order result)] (inc m) 0)))

(defn add-persona
  "Mint a persona under `account-id`. False when the id is taken — ids are unique
   across all accounts, not per account, because /:id is the public address and
   must mean exactly one thing."
  [conn account-id id persona-name]
  (if (get-persona-by-id conn id)
    false
    (do
      (jdbc/execute! (:conn conn)
                     (sql/format {:insert-into :personas
                                  :values [{:id (kw->str id)
                                            :account_id account-id
                                            :name (or persona-name (kw->str id))
                                            :sort_order (next-sort-order conn account-id)}]}))
      true)))

(defn update-persona
  "Change a persona's display name. Nil when there is no such persona. The email
   is not reachable from here any more — it belongs to the account."
  [conn id {:keys [name]}]
  (let [current (get-persona-by-id conn id)]
    (if-not current
      nil
      (do
        (jdbc/execute! (:conn conn)
                       (sql/format {:update :personas
                                    :set {:name (or name (:name current))}
                                    :where [:= :id (kw->str id)]}))
        {:success true}))))

(defn delete-persona
  "Hard-delete a persona and every version of every identity under it. There is
   no history left afterwards and no undo. False when there is no such persona.

   Identities go first: a half-done delete then leaves an empty persona, which
   the owner can simply retry, rather than identity rows no persona points at."
  [conn id]
  (if-not (get-persona-by-id conn id)
    false
    (do
      (jdbc/execute! (:conn conn)
                     (sql/format {:delete-from :identities
                                  :where [:= :persona_id (kw->str id)]}))
      (jdbc/execute! (:conn conn)
                     (sql/format {:delete-from :personas
                                  :where [:= :id (kw->str id)]}))
      true)))

(defn list-personas
  "Every persona, as an anonymous reader sees them: id and display name. No
   email, and no hint of which personas share an account."
  [conn]
  (let [results (jdbc/execute! (:conn conn)
                               (sql/format {:select [:id :name]
                                            :from [:personas]})
                               {:builder-fn rs/as-unqualified-lower-maps})]
    (map (fn [r]
           {:id (str->kw (:id r))
            :name (:name r)})
         results)))

(defn list-personas-for-account
  [conn account-id]
  (let [results (jdbc/execute! (:conn conn)
                               (sql/format {:select [:id :name :sort_order]
                                            :from [:personas]
                                            :where [:= :account_id account-id]
                                            :order-by [[:sort_order :asc]]})
                               {:builder-fn rs/as-unqualified-lower-maps})]
    (mapv (fn [r]
            {:id (str->kw (:id r))
             :name (:name r)
             :sort-order (:sort_order r)})
          results)))

(defn list-accounts
  "Every account with its personas — the admin Settings listing, and the only
   read in this namespace that pairs an email with anything public."
  [conn]
  (let [results (jdbc/execute! (:conn conn)
                               (sql/format {:select [:id :email]
                                            :from [:accounts]
                                            :order-by [[:id :asc]]})
                               {:builder-fn rs/as-unqualified-lower-maps})]
    (mapv (fn [r]
            {:id (:id r)
             :email (:email r)
             :personas (list-personas-for-account conn (:id r))})
          results)))

(defn- make-composite-id [persona-id identity-id]
  (str (kw->str persona-id) "/" (kw->str identity-id)))

(defn- latest-versions-subquery [persona-id]
  {:select [:composite_id [[:max :valid_from] :max_valid]]
   :from [:identities]
   :where [:= :persona_id (kw->str persona-id)]
   :group-by [:composite_id]})

(defn list-identities
  [conn {persona-id :id :as _persona}]
  (let [results (jdbc/execute! (:conn conn)
                               (sql/format {:select [:iv.identity_id :iv.name :iv.text]
                                            :from [[:identities :iv]]
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
                                               :from [:identities]
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
                                            :from [[:identities :iv]]
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
                       (sql/format {:insert-into :identities
                                    :values [{:composite_id composite-id
                                              :persona_id (kw->str persona-id)
                                              :identity_id (kw->str id)
                                              :name nm
                                              :text text
                                              :valid_from valid-from-epoch
                                              :relations "[]"}]}))
        id))))

(defn get-identity-at
  [conn {persona-id :id :as _persona} id time-point]
  (let [composite-id (make-composite-id persona-id id)
        time-epoch (instant->epoch time-point)
        result (jdbc/execute-one! (:conn conn)
                                  (sql/format {:select [:identity_id :name :text]
                                               :from [:identities]
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
                                            :from [:identities]
                                            :where [:= :composite_id composite-id]
                                            :order-by [[:valid_from :asc]]})
                               {:builder-fn rs/as-unqualified-lower-maps})]
    (mapv (fn [r]
            {:identity (str->kw (:identity_id r))
             :name (:name r)
             :text (:text r)
             :valid-from (epoch->instant (:valid_from r))})
          results)))

;; ---------------------------------------------------------------------------
;; Relations
;;
;; Relations are unidirectional and owned by the source identity, so each
;; identity *version* row carries its own relation set inline as a JSON blob:
;; an ordered list of {:target <identity-id> :description <string|nil>} maps.
;; Time-travel is then just "read that version's blob" — no event replay, no
;; separate table.
;; ---------------------------------------------------------------------------

(defn- parse-relations [json-str]
  (if (str/blank? json-str)
    []
    (vec (json/read-str json-str :key-fn keyword))))

(defn- relations->json [rels]
  (json/write-str rels))

(defn- relation-blob-at
  "The relation list of the identity version in effect at `time-epoch`
   (or the latest version when `time-epoch` is nil)."
  [conn persona-id id time-epoch]
  (let [row (jdbc/execute-one! (:conn conn)
                               (sql/format {:select [:relations]
                                            :from [:identities]
                                            :where (if time-epoch
                                                     [:and
                                                      [:= :composite_id (make-composite-id persona-id id)]
                                                      [:<= :valid_from time-epoch]]
                                                     [:= :composite_id (make-composite-id persona-id id)])
                                            :order-by [[:valid_from :desc] [:id :desc]]
                                            :limit 1})
                               {:builder-fn rs/as-unqualified-lower-maps})]
    (parse-relations (:relations row))))

(defn- relation-target
  "The target id of a relation id in the form \"source/target\"."
  [rel-id]
  (subs rel-id (inc (.indexOf rel-id "/"))))

(defn save-identity-version
  "Persist a new identity version, carrying its relation set forward from the
   current latest version and applying the requested changes. Because relations
   live on the version row, they automatically share the version's timeline.

   `relation-adds`    - seq of target ids (or {:target .. :description ..} maps).
   `relation-removes` - seq of relation ids in the form \"source/target\"."
  [conn {persona-id :id :as _persona} id nm text & [{:keys [valid-from relation-adds relation-removes]}]]
  (let [t (or valid-from (Instant/ofEpochMilli (System/currentTimeMillis)))
        current (relation-blob-at conn persona-id id nil)
        remove-targets (set (map relation-target relation-removes))
        kept (vec (remove #(contains? remove-targets (:target %)) current))
        existing (set (map :target kept))
        additions (for [a relation-adds
                        :let [tgt (kw->str (if (map? a) (:target a) a))]
                        :when (not (contains? existing tgt))]
                    {:target tgt :description (when (map? a) (:description a))})
        new-rels (into kept additions)]
    (jdbc/execute! (:conn conn)
                   (sql/format {:insert-into :identities
                                :values [{:composite_id (make-composite-id persona-id id)
                                          :persona_id (kw->str persona-id)
                                          :identity_id (kw->str id)
                                          :name nm
                                          :text text
                                          :valid_from (instant->epoch t)
                                          :relations (relations->json new-rels)}]}))
    t))

(defn update-identity
  "Save a new identity version, carrying the current relation set forward unchanged."
  [conn persona id nm text & [{:keys [valid-from]}]]
  (save-identity-version conn persona id nm text {:valid-from valid-from}))

(defn list-relations
  [conn {persona-id :id :as _persona} source-id & [{:keys [at]}]]
  (let [rels (relation-blob-at conn persona-id source-id (when at (instant->epoch at)))]
    (vec (for [{:keys [target description]} rels]
           {:id (str (kw->str source-id) "/" target)
            :target (str->kw target)
            :target-name (:name (get-identity conn {:id persona-id} (str->kw target)))
            :description description}))))

(defn search-identities
  [conn {persona-id :id :as _persona} query & [{:keys [at]}]]
  (let [query-lower (str/lower-case (or query ""))
        results (if at
                  (let [time-epoch (instant->epoch at)
                        all-composites (jdbc/execute! (:conn conn)
                                                      (sql/format {:select-distinct [:composite_id]
                                                                   :from [:identities]
                                                                   :where [:and
                                                                           [:= :persona_id (kw->str persona-id)]
                                                                           [:<= :valid_from time-epoch]]})
                                                      {:builder-fn rs/as-unqualified-lower-maps})]
                    (for [{:keys [composite_id]} all-composites
                          :let [version (jdbc/execute-one! (:conn conn)
                                                           (sql/format {:select [:identity_id :name :text]
                                                                        :from [:identities]
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
                                              :from [[:identities :iv]]
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
