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
  "A *human* account. Machine users are accounts rows too, so every read that
   means \"a person's login\" says so: otherwise a machine user's account id,
   which is what its own grants are keyed on, would resolve here as if it were
   the human it belongs to."
  [conn account-id]
  (let [result (jdbc/execute-one! (:conn conn)
                                  (sql/format {:select [:id :email]
                                               :from [:accounts]
                                               :where [:and
                                                       [:= :is_machine_user 0]
                                                       [:= :id account-id]]})
                                  {:builder-fn rs/as-unqualified-lower-maps})]
    (when result
      {:id (:id result) :email (:email result)})))

(defn get-account-by-email
  [conn email]
  (let [result (jdbc/execute-one! (:conn conn)
                                  (sql/format {:select [:id :email]
                                               :from [:accounts]
                                               ;; is_machine_user 0 is not decoration: honeysql renders
                                               ;; [:= :email nil] as `email IS NULL`, which every machine
                                               ;; user matches. A nil email must find nobody.
                                               :where [:and
                                                       [:= :is_machine_user 0]
                                                       [:= :email email]]})
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
  "A machine user has none, forever — it authenticates by token and never
   reaches the login route. Restricted to humans so that stays true even if
   something one day writes a hash into the wrong row."
  [conn account-id]
  (let [result (jdbc/execute-one! (:conn conn)
                                  (sql/format {:select [:password_hash]
                                               :from [:accounts]
                                               :where [:and
                                                       [:= :is_machine_user 0]
                                                       [:= :id account-id]]})
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
   the owner can simply retry, rather than identity rows no persona points at.
   Any machine user's grant on it goes too — a grant that outlived its persona
   would silently become a grant on whatever is created under that id next."
  [conn id]
  (if-not (get-persona-by-id conn id)
    false
    (do
      (jdbc/execute! (:conn conn)
                     (sql/format {:delete-from :identities
                                  :where [:= :persona_id (kw->str id)]}))
      (jdbc/execute! (:conn conn)
                     (sql/format {:delete-from :machine_persona_grants
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
  "Every human account with its personas — the admin Settings listing, and the
   only read in this namespace that pairs an email with anything public. Machine
   users are accounts rows too but are not accounts in this sense: they have no
   email, no personas of their own, and no business in a roster of people."
  [conn]
  (let [results (jdbc/execute! (:conn conn)
                               (sql/format {:select [:id :email]
                                            :from [:accounts]
                                            :where [:= :is_machine_user 0]
                                            :order-by [[:id :asc]]})
                               {:builder-fn rs/as-unqualified-lower-maps})]
    (mapv (fn [r]
            {:id (:id r)
             :email (:email r)
             :personas (list-personas-for-account conn (:id r))})
          results)))

;; ---------------------------------------------------------------------------
;; Machine users
;;
;; A machine user is an `accounts` row flagged `is_machine_user`, pointing at
;; the human account whose personas it writes. It holds no password and never
;; reaches the login route; its whole credential is `token_hash`, and because
;; there is exactly one column to hold it, rotating a token overwrites the
;; previous one and that one stops verifying immediately.
;;
;; What it may write is not "everything under its parent" but exactly the rows
;; in `machine_persona_grants` — so an account can hold one machine user for
;; personas A and C and another for B and C.
;; ---------------------------------------------------------------------------

(defn- machine-row->map [r]
  (when r
    {:id (:id r)
     :name (:name r)
     :for-account-id (:for_account_id r)
     :can-create-personas? (= 1 (:can_create_personas r))
     :token-hash (:token_hash r)}))

(defn get-machine-user
  "A machine user by its (globally unique) name, or nil."
  [conn nm]
  (machine-row->map
   (jdbc/execute-one! (:conn conn)
                      (sql/format {:select [:id :name :for_account_id :can_create_personas :token_hash]
                                   :from [:accounts]
                                   :where [:and
                                           [:= :is_machine_user 1]
                                           [:= :name (kw->str nm)]]})
                      {:builder-fn rs/as-unqualified-lower-maps})))

(defn get-machine-user-by-id
  [conn account-id]
  (machine-row->map
   (jdbc/execute-one! (:conn conn)
                      (sql/format {:select [:id :name :for_account_id :can_create_personas :token_hash]
                                   :from [:accounts]
                                   :where [:and
                                           [:= :is_machine_user 1]
                                           [:= :id account-id]]})
                      {:builder-fn rs/as-unqualified-lower-maps})))

(defn add-machine-user
  "Mint a machine user named `nm` under `account-id`, answering its own account
   id — or false when the name is taken. Names are unique across all accounts,
   not per account: they are how these get referred to outside the app."
  [conn account-id nm {:keys [can-create-personas?]}]
  (if (get-machine-user conn nm)
    false
    (do
      (jdbc/execute! (:conn conn)
                     (sql/format {:insert-into :accounts
                                  :values [{:name (kw->str nm)
                                            :for_account_id account-id
                                            :is_machine_user 1
                                            :can_create_personas (if can-create-personas? 1 0)}]}))
      (:id (get-machine-user conn nm)))))

(defn update-machine-user
  [conn machine-account-id {:keys [can-create-personas?]}]
  (when (some? can-create-personas?)
    (jdbc/execute! (:conn conn)
                   (sql/format {:update :accounts
                                :set {:can_create_personas (if can-create-personas? 1 0)}
                                :where [:and
                                        [:= :is_machine_user 1]
                                        [:= :id machine-account-id]]})))
  {:success true})

(defn set-machine-token-hash!
  "Overwrite the machine user's one live token hash. This *is* the rotation:
   whatever verified against the old hash stops verifying here."
  [conn machine-account-id token-hash]
  (jdbc/execute! (:conn conn)
                 (sql/format {:update :accounts
                              :set {:token_hash token-hash}
                              :where [:and
                                      [:= :is_machine_user 1]
                                      [:= :id machine-account-id]]}))
  true)

(defn get-machine-user-by-token-hash
  "The machine user presenting a token, found by the hash of what it presented.
   A NULL token_hash must never match — a machine user that has never been given
   a token would otherwise be reachable by presenting nothing."
  [conn token-hash]
  (when (seq token-hash)
    (machine-row->map
     (jdbc/execute-one! (:conn conn)
                        (sql/format {:select [:id :name :for_account_id :can_create_personas :token_hash]
                                     :from [:accounts]
                                     :where [:and
                                             [:= :is_machine_user 1]
                                             [:= :token_hash token-hash]]})
                        {:builder-fn rs/as-unqualified-lower-maps}))))

(defn granted-personas
  "The persona ids this machine user may write, in id order."
  [conn machine-account-id]
  (mapv (fn [r] (str->kw (:persona_id r)))
        (jdbc/execute! (:conn conn)
                       (sql/format {:select [:persona_id]
                                    :from [:machine_persona_grants]
                                    :where [:= :machine_account_id machine-account-id]
                                    :order-by [[:persona_id :asc]]})
                       {:builder-fn rs/as-unqualified-lower-maps})))

(defn machine-may-write?
  "The question the auth guard asks. A lookup rather than a set membership on a
   list read elsewhere, so a grant revoked a moment ago is honoured at once."
  [conn machine-account-id persona-id]
  (some? (jdbc/execute-one! (:conn conn)
                            (sql/format {:select [:persona_id]
                                         :from [:machine_persona_grants]
                                         :where [:and
                                                 [:= :machine_account_id machine-account-id]
                                                 [:= :persona_id (kw->str persona-id)]]})
                            {:builder-fn rs/as-unqualified-lower-maps})))

(defn grant-persona
  "Let this machine user write `persona-id`. Granting twice is the same grant."
  [conn machine-account-id persona-id]
  (when-not (machine-may-write? conn machine-account-id persona-id)
    (jdbc/execute! (:conn conn)
                   (sql/format {:insert-into :machine_persona_grants
                                :values [{:machine_account_id machine-account-id
                                          :persona_id (kw->str persona-id)}]})))
  true)

(defn revoke-persona
  [conn machine-account-id persona-id]
  (jdbc/execute! (:conn conn)
                 (sql/format {:delete-from :machine_persona_grants
                              :where [:and
                                      [:= :machine_account_id machine-account-id]
                                      [:= :persona_id (kw->str persona-id)]]}))
  true)

(defn list-machine-users
  "An account's machine users with their grants, in name order — the roster the
   profile page draws its checkbox grid from. Never includes the token hash:
   nothing outside verification has any use for it."
  [conn account-id]
  (mapv (fn [r]
          {:id (:id r)
           :name (:name r)
           :can-create-personas? (= 1 (:can_create_personas r))
           :personas (granted-personas conn (:id r))})
        (jdbc/execute! (:conn conn)
                       (sql/format {:select [:id :name :can_create_personas]
                                    :from [:accounts]
                                    :where [:and
                                            [:= :is_machine_user 1]
                                            [:= :for_account_id account-id]]
                                    :order-by [[:name :asc]]})
                       {:builder-fn rs/as-unqualified-lower-maps})))

(defn delete-machine-user
  "Remove a machine user and every grant it held. Grants go first, so a
   half-done delete leaves a machine user that can write nothing rather than
   grant rows naming an account that no longer exists."
  [conn machine-account-id]
  (if-not (get-machine-user-by-id conn machine-account-id)
    false
    (do
      (jdbc/execute! (:conn conn)
                     (sql/format {:delete-from :machine_persona_grants
                                  :where [:= :machine_account_id machine-account-id]}))
      (jdbc/execute! (:conn conn)
                     (sql/format {:delete-from :accounts
                                  :where [:and
                                          [:= :is_machine_user 1]
                                          [:= :id machine-account-id]]}))
      true)))

(defn- make-composite-id [persona-id identity-id]
  (str (kw->str persona-id) "/" (kw->str identity-id)))

(defn- latest-versions-subquery
  "**Which row is each identity's latest version** — `{:composite_id :latest_id}`,
   exactly one row per identity of the persona. The three listing reads join on
   `latest_id` and inherit the rule from here, which is the point of it being one
   subquery rather than three joins that agree today.

   THE RULE, and it is stated here because this is where the next reader will
   look for it: **the latest version is the greatest `(valid_from, id)`.**

   - Not the greatest `valid_from` alone, which is what this asked for until an
     identity turned up with two versions of the same millisecond. `valid_from`
     is epoch milliseconds, so both rows were `max(valid_from)`, the join matched
     both, and the identity came back **twice** — in the list the SPA renders,
     and counted against the page limit, so a page of five showed four. Not a
     race, either: the API takes an explicit `valid_from`, so an importer
     stamping a batch with one timestamp met it every time.
   - Not the greatest `id` alone either. `valid_from` is the caller's to set, so
     a version written *later* can carry an *earlier* timestamp — a backfilled
     import, a corrected date — and must not become the latest by virtue of
     having been inserted last. Hence the two levels: the inner query finds each
     identity's greatest timestamp, and the outer takes the greatest id **among
     the rows holding it**, which is the one written last.

   It is the same rule `get-identity`, `get-identity-at` and
   `get-identity-history` read by (`[[:valid_from :desc] [:id :desc]]`), asked of
   many identities at once instead of one."
  [persona-id]
  {:select [:i.composite_id [[:max :i.id] :latest_id]]
   :from [[:identities :i]]
   :join [[{:select [:composite_id [[:max :valid_from] :max_valid]]
            :from [:identities]
            :where [:= :persona_id (kw->str persona-id)]
            :group-by [:composite_id]} :m]
          [:and
           [:= :m.composite_id :i.composite_id]
           [:= :i.valid_from :m.max_valid]]]
   :where [:= :i.persona_id (kw->str persona-id)]
   :group-by [:i.composite_id]})

(defn list-identities
  "Every identity of the persona at its latest version, one row each. The join is
   on `latest_id` — the row — rather than on a timestamp two rows can share; see
   latest-versions-subquery for the rule."
  [conn {persona-id :id :as _persona}]
  (let [results (jdbc/execute! (:conn conn)
                               (sql/format {:select [:iv.identity_id :iv.name :iv.text]
                                            :from [[:identities :iv]]
                                            :join [[(latest-versions-subquery persona-id) :latest]
                                                   [:= :iv.id :latest.latest_id]]
                                            :where [:= :iv.persona_id (kw->str persona-id)]})
                               {:builder-fn rs/as-unqualified-lower-maps})]
    (map (fn [r]
           {:identity (str->kw (:identity_id r))
            :name (:name r)
            :text (:text r)})
         results)))

(defn get-identity
  "The identity at its latest version.

   **`[:id :desc]` breaks a tie on `valid_from`**, which is epoch milliseconds:
   two versions saved in the same millisecond are both `max(valid_from)`, and
   without the id this returned whichever SQLite felt like — the *older* text,
   half the time, from a read whose whole job is to answer with the newest.
   `relation-blob-at` has always tie-broken this way and `get-identity-history`
   now does too.

   It bites hardest since the provenance rides along on this read
   (`handlers/get-identity-handler`): the ranges are computed over the whole
   history and so describe the newest version, while `:text` came from this
   query. Disagree, and one body carries a text and an attribution of a
   *different* text — every line tinted with its neighbour's colour, and nothing
   about the answer looking wrong."
  [conn {persona-id :id :as _persona} identity-id]
  (let [composite-id (make-composite-id persona-id identity-id)
        result (jdbc/execute-one! (:conn conn)
                                  (sql/format {:select [:identity_id :name :text]
                                               :from [:identities]
                                               :where [:= :composite_id composite-id]
                                               :order-by [[:valid_from :desc] [:id :desc]]
                                               :limit 1})
                                  {:builder-fn rs/as-unqualified-lower-maps})]
    (when result
      {:identity (str->kw (:identity_id result))
       :name (:name result)
       :text (:text result)})))

(defn list-recent-identities
  "A page of the persona's identities, most recently versioned first.

   **One row per identity, so a page of `limit` holds `limit` of them** and
   `has-more` counts identities rather than rows. It did not, while the join
   matched every row sharing the greatest timestamp: a duplicate ate a slot in
   the page the client asked for, and this is the listing the SPA renders.

   **The id also breaks the tie in the ordering**, which is a second use of the
   same rule for a different reason. Two *different* identities can share a
   `valid_from` — that is what a batch import is — and offset paging over an
   order that cannot tell them apart may hand the same one back on two pages and
   never hand back the other.

   That second tie-break is **not pinned by a test, and knowingly so**: SQLite
   returns these rows in a stable order for this query shape today, so removing
   `[:iv.id :desc]` leaves the suite green (checked, three runs). What it guards
   against is not a bug that reproduces now but a guarantee SQL does not make —
   the order of rows with equal sort keys is undefined, and the plan for this
   query can change with an index or with the size of the table. A test asserting
   it would be a test that cannot fail, which is worse than none; this comment is
   the record instead."
  [conn {persona-id :id :as _persona} limit offset]
  (let [fetch-limit (inc limit)
        results (jdbc/execute! (:conn conn)
                               (sql/format {:select [:iv.identity_id :iv.name :iv.valid_from]
                                            :from [[:identities :iv]]
                                            :join [[(latest-versions-subquery persona-id) :latest]
                                                   [:= :iv.id :latest.latest_id]]
                                            :where [:= :iv.persona_id (kw->str persona-id)]
                                            :order-by [[:iv.valid_from :desc] [:iv.id :desc]]
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

;; ---------------------------------------------------------------------------
;; Who wrote a version
;;
;; `author` is a **required positional argument** of the two functions that
;; write an identity version, and deliberately not an entry in their trailing
;; opts map. Migration 005 gives the column a default of 'human', which is what
;; lets one ALTER TABLE be both the constraint and the retrofit — but that
;; default must never become the way authorship gets set: a write path that
;; forgot to pass one would silently claim a person wrote it, which is a false
;; claim in the dangerous direction. As a positional argument, forgetting it is
;; an arity error where the call is written. As an opts key it would be a lie in
;; a row nobody reads again.
;;
;; The marker is the literal "human", or a machine user's own name. Nothing here
;; validates it: et.pe.provenance is where the app takes sides, and a marker it
;; has never seen falls to *them*, which is the safe direction.
;; ---------------------------------------------------------------------------

(defn add-identity
  [conn {persona-id :id :as _persona} nm text author & [{:keys [valid-from id]}]]
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
                                              :author author
                                              :relations "[]"}]}))
        id))))

(defn get-identity-at
  "The version in effect at `time-point`. Tie-broken on `id` for the reason
   `get-identity` gives: two versions of one millisecond are equally 'in effect'
   by timestamp alone, and the later-written one is the one in effect."
  [conn {persona-id :id :as _persona} id time-point]
  (let [composite-id (make-composite-id persona-id id)
        time-epoch (instant->epoch time-point)
        result (jdbc/execute-one! (:conn conn)
                                  (sql/format {:select [:identity_id :name :text]
                                               :from [:identities]
                                               :where [:and
                                                       [:= :composite_id composite-id]
                                                       [:<= :valid_from time-epoch]]
                                               :order-by [[:valid_from :desc] [:id :desc]]
                                               :limit 1})
                                  {:builder-fn rs/as-unqualified-lower-maps})]
    (when result
      {:identity (str->kw (:identity_id result))
       :name (:name result)
       :text (:text result)})))

(defn get-identity-history
  "Every version of an identity, **oldest first**, each with who wrote it.

   The order is not incidental. `et.uvt.caution/assess` replays a history
   forwards, and `valid_from :asc` is already that order — which is why
   `et.pe.provenance` hands this list over as it comes. Both sibling apps have
   to reverse theirs (cookbook's and rhizome's ladders arrive newest first), and
   copying their `(reverse …)` here would attribute every line to whoever wrote
   the version *after* it: not a crash and not a malformed answer, just a
   confident inversion of who wrote what. et.pe.provenance-test pins it.

   **The id breaks a tie on `valid_from`, and that is not decoration.** The
   column is epoch *milliseconds* and update-identity-handler stamps
   `Instant/now`, so two versions written in the same millisecond — an agent
   writing in a loop, which is precisely the writer this feature is about — sort
   arbitrarily without it, and a replay in the wrong order attributes each
   line to whoever wrote the version beside it. `relation-blob-at` already
   tie-breaks on `id` for the same reason in the other direction; the id is the
   insertion order, which is what \"which came first\" means when the clock
   cannot say.

   `:author` is on every entry, so the caller decides who may see it — the
   public history handler does not hand it out, because nothing about machine
   users is public."
  [conn {persona-id :id :as _persona} id]
  (let [composite-id (make-composite-id persona-id id)
        results (jdbc/execute! (:conn conn)
                               (sql/format {:select [:identity_id :name :text :valid_from :author]
                                            :from [:identities]
                                            :where [:= :composite_id composite-id]
                                            :order-by [[:valid_from :asc] [:id :asc]]})
                               {:builder-fn rs/as-unqualified-lower-maps})]
    (mapv (fn [r]
            {:identity (str->kw (:identity_id r))
             :name (:name r)
             :text (:text r)
             :author (:author r)
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
;;
;; **The order of that list is meant.** It is the persona's ranking of which
;; relations matter more, `list-relations` hands the blob out in it, and because
;; it lives on the version row it time-travels with everything else: an older
;; version keeps the order it was saved with.
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

(defn- order-relations
  "Rank a relation set by a requested order of target ids: the ones `order`
   names come first, in that order, and anything it does not name keeps its
   relative place after them (the sort is stable).

   An empty `order` is left alone rather than treated as \"order by nothing\".
   Saying nothing about the order is not the same as asking for the default one —
   every plain edit says nothing, and each of them would otherwise be a chance to
   silently undo a ranking."
  [rels order]
  (if (empty? order)
    rels
    (let [rank (into {} (map-indexed (fn [i t] [(kw->str t) i]) order))]
      (vec (sort-by #(get rank (:target %) Integer/MAX_VALUE) rels)))))

(defn save-identity-version
  "Persist a new identity version, carrying its relation set forward from the
   current latest version and applying the requested changes. Because relations
   live on the version row, they automatically share the version's timeline.

   `relation-adds`    - seq of target ids (or {:target .. :description ..} maps).
   `relation-removes` - seq of relation ids in the form \"source/target\".
   `relation-order`   - seq of target ids: the ranking to store the set in. See
                        order-relations for what a partial one means. Applied
                        after the adds, so one call can add a relation and say
                        where it goes.
   `author`           - who is writing this version: \"human\", or a machine
                        user's own name. Positional, and required, for the
                        reason given above the write section."
  [conn {persona-id :id :as _persona} id nm text author & [{:keys [valid-from relation-adds relation-removes relation-order]}]]
  (let [t (or valid-from (Instant/ofEpochMilli (System/currentTimeMillis)))
        current (relation-blob-at conn persona-id id nil)
        remove-targets (set (map relation-target relation-removes))
        kept (vec (remove #(contains? remove-targets (:target %)) current))
        existing (set (map :target kept))
        additions (for [a relation-adds
                        :let [tgt (kw->str (if (map? a) (:target a) a))]
                        :when (not (contains? existing tgt))]
                    {:target tgt :description (when (map? a) (:description a))})
        new-rels (order-relations (into kept additions) relation-order)]
    (jdbc/execute! (:conn conn)
                   (sql/format {:insert-into :identities
                                :values [{:composite_id (make-composite-id persona-id id)
                                          :persona_id (kw->str persona-id)
                                          :identity_id (kw->str id)
                                          :name nm
                                          :text text
                                          :valid_from (instant->epoch t)
                                          :author author
                                          :relations (relations->json new-rels)}]}))
    t))

(defn update-identity
  "Save a new identity version, carrying the current relation set forward unchanged."
  [conn persona id nm text author & [{:keys [valid-from]}]]
  (save-identity-version conn persona id nm text author {:valid-from valid-from}))

(defn list-relations
  [conn {persona-id :id :as _persona} source-id & [{:keys [at]}]]
  (let [rels (relation-blob-at conn persona-id source-id (when at (instant->epoch at)))]
    (vec (for [{:keys [target description]} rels]
           {:id (str (kw->str source-id) "/" target)
            :target (str->kw target)
            :target-name (:name (get-identity conn {:id persona-id} (str->kw target)))
            :description description}))))

(defn search-identities
  "The persona's identities whose name contains `query`, at their latest version —
   or, with `:at`, as of that instant.

   Both branches read the latest version the same way the rest of this namespace
   does: the `:at` branch is `get-identity-at` asked once per identity and so
   takes its `[[:valid_from :desc] [:id :desc]]`, and the plain branch inherits
   the rule from `latest-versions-subquery`. One identity comes back once from
   either."
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
                                                                        :order-by [[:valid_from :desc] [:id :desc]]
                                                                        :limit 1})
                                                           {:builder-fn rs/as-unqualified-lower-maps})]
                          :when version]
                      version))
                  (jdbc/execute! (:conn conn)
                                 (sql/format {:select [:iv.identity_id :iv.name :iv.text]
                                              :from [[:identities :iv]]
                                              :join [[(latest-versions-subquery persona-id) :latest]
                                                     [:= :iv.id :latest.latest_id]]
                                              :where [:= :iv.persona_id (kw->str persona-id)]})
                                 {:builder-fn rs/as-unqualified-lower-maps}))]
    (->> results
         (filter (fn [r]
                   (str/includes? (str/lower-case (or (:name r) "")) query-lower)))
         (mapv (fn [r]
                 {:identity (str->kw (:identity_id r))
                  :name (:name r)
                  :text (:text r)})))))
