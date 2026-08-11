(ns et.pe.ds.migrations-test
  "Migrations are the one thing in this app that cannot be redone later: they run
   once against real data and then the old shape is gone. So they are tested
   against a database built the way production's was — earlier migrations
   applied, prod-shaped rows inserted, and only then the migration under test."
  (:require [clojure.test :refer [deftest testing is]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [ragtime.core :as ragtime]
            [ragtime.next-jdbc :as ragtime-jdbc]))

(def ^:private all-migrations
  (delay (ragtime-jdbc/load-resources "migrations/net/et/pe")))

(defn- run-migrations!
  "Apply the named migrations, in the order given. Naming them one by one rather
   than migrating everything is the whole point: the rows have to be inserted
   between two of them."
  [store ids]
  (let [by-id (into {} (map (juxt :id identity)) @all-migrations)]
    (doseq [id ids]
      (ragtime/migrate store (or (get by-id id)
                                 (throw (ex-info "No such migration"
                                                 {:id id :known (mapv :id @all-migrations)})))))))

(defn- rollback! [store id]
  (let [by-id (into {} (map (juxt :id identity)) @all-migrations)]
    (ragtime/rollback store (get by-id id))))

(defn- fresh-db
  "A connection to a private in-memory database with no migrations applied. Each
   caller passes its own `nm` — `file::memory:?cache=shared` is one database for
   the whole JVM, so two tests sharing a name would share a schema."
  [nm]
  (jdbc/get-connection
   (jdbc/get-datasource {:dbtype "sqlite"
                         :dbname (str "file:" nm "?mode=memory&cache=shared")})))

(defn- q [conn sql]
  (jdbc/execute! conn [sql] {:builder-fn rs/as-unqualified-lower-maps}))

;; The four rows of production as of 2026-08-10, one email each, no nulls, no
;; duplicates — which is what makes the split an exact 1-to-1 join on email.
(def ^:private prod-personas
  [{:id "dilmul-socfus" :email "shopping@eighttrigrams.net"          :name "Testuser"         :hash "bcrypt+sha512$aaa"}
   {:id "namlys-lasduc" :email "dan@eighttrigrams.net"               :name "Eighttrigrams"    :hash "bcrypt+sha512$bbb"}
   {:id "nolmev-tintep" :email "tracker.plurama@eighttrigrams.net"   :name "Tracker Ontology" :hash "bcrypt+sha512$ccc"}
   {:id "socrup-mosbur" :email "training@eighttrigrams.net"          :name "Training"         :hash "bcrypt+sha512$ddd"}])

(defn- seed-old-shape!
  "Insert personas in the pre-003 shape (email and password on the persona row)
   plus a couple of identity versions each, so the migration has something to
   carry across and something it must leave alone."
  [conn]
  (doseq [{:keys [id email name hash]} prod-personas]
    (jdbc/execute! conn ["INSERT INTO personas (id, email, name, password_hash) VALUES (?,?,?,?)"
                         id email name hash]))
  (doseq [{:keys [id]} prod-personas
          n [1 2]]
    (jdbc/execute! conn [(str "INSERT INTO identities (composite_id, persona_id, identity_id, name, text,"
                              " valid_from, relations) VALUES (?,?,?,?,?,?,?)")
                         (str id "/ident-" n) id (str "ident-" n)
                         (str "Identity " n) (str "text " n) (* 1000 n) "[]"])))

(deftest accounts-above-personas
  (with-open [conn (fresh-db "mig003-up")]
    (let [store (ragtime-jdbc/sql-database (jdbc/with-options conn {}))]
      (run-migrations! store ["001-initial-schema" "002-relations-in-identity"])
      (seed-old-shape! conn)
      (let [identities-before (q conn "SELECT id, composite_id, persona_id, identity_id, name, text, valid_from, relations FROM identities ORDER BY id")]

        (run-migrations! store ["003-accounts-above-personas"])

        (testing "every persona's email became an account, with its password hash verbatim"
          (let [accounts (q conn "SELECT id, email, password_hash FROM accounts ORDER BY email")]
            (is (= 4 (count accounts)))
            (is (= (sort (map :email prod-personas)) (map :email accounts)))
            (is (= (->> prod-personas (sort-by :email) (map :hash))
                   (map :password_hash accounts)))
            (testing "- and the account id is a real autoincrement key, not the email"
              (is (every? integer? (map :id accounts)))
              (is (= 4 (count (distinct (map :id accounts))))))))

        (testing "every persona survives under its own account, keeping id and display name"
          (let [personas (q conn (str "SELECT p.id, p.name, p.sort_order, a.email"
                                      " FROM personas p JOIN accounts a ON a.id = p.account_id"
                                      " ORDER BY p.id"))]
            (is (= 4 (count personas)))
            (is (= (map (juxt :id :name :email) (sort-by :id prod-personas))
                   (map (juxt :id :name :email) personas))
                "each persona is paired with the account minted from its own email")
            (testing "- the migrated persona is the first of its account"
              (is (every? #(= 0 (:sort_order %)) personas)))))

        (testing "personas no longer carry the credential half"
          (let [cols (set (map :name (q conn "PRAGMA table_info(personas)")))]
            (is (= #{"id" "account_id" "name" "sort_order"} cols))))

        (testing "not one identity row moved — the urbit name survives the split unchanged"
          (is (= identities-before
                 (q conn "SELECT id, composite_id, persona_id, identity_id, name, text, valid_from, relations FROM identities ORDER BY id"))))))))

(deftest rollback-restores-the-single-table-shape
  (with-open [conn (fresh-db "mig003-down")]
    (let [store (ragtime-jdbc/sql-database (jdbc/with-options conn {}))]
      (run-migrations! store ["001-initial-schema" "002-relations-in-identity"])
      (seed-old-shape! conn)
      (run-migrations! store ["003-accounts-above-personas"])

      (rollback! store "003-accounts-above-personas")

      (testing "while every account still holds exactly one persona, :down is honest"
        (let [cols (set (map :name (q conn "PRAGMA table_info(personas)")))]
          (is (= #{"id" "email" "name" "password_hash"} cols)))
        (is (= (map (juxt :id :email :name :hash) prod-personas)
               (map (juxt :id :email :name :password_hash)
                    (q conn "SELECT id, email, name, password_hash FROM personas ORDER BY id")))))

      (testing "the accounts table is gone"
        (is (empty? (q conn "SELECT name FROM sqlite_master WHERE type='table' AND name='accounts'")))))))

(deftest rollback-refuses-rather-than-picking-a-survivor
  (with-open [conn (fresh-db "mig003-down-conflict")]
    (let [store (ragtime-jdbc/sql-database (jdbc/with-options conn {}))]
      (run-migrations! store ["001-initial-schema" "002-relations-in-identity"])
      (seed-old-shape! conn)
      (run-migrations! store ["003-accounts-above-personas"])
      ;; the very thing 003 exists to allow: a second persona under one account
      (jdbc/execute! conn [(str "INSERT INTO personas (id, account_id, name, sort_order)"
                                " SELECT 'second-one', account_id, 'Second', 1 FROM personas WHERE id = 'namlys-lasduc'")])

      (testing "a rollback that would have to drop one of two personas fails loudly"
        (is (thrown? Exception (rollback! store "003-accounts-above-personas"))
            "email UNIQUE cannot hold two personas of one account; refusing beats silently losing one")))))

;; ---------------------------------------------------------------------------
;; 004 — machine users hang off the same accounts table, tracker's shape
;; (is_machine_user + for_account_id) but explicitly *without* its
;; one-machine-per-human unique index: the whole point here is that an account
;; may hold several.
;; ---------------------------------------------------------------------------

(defn- through-003!
  "A database in the shape 003 left it: the four prod accounts and their
   personas, plus the identity rows 003 must never have touched."
  [conn store]
  (run-migrations! store ["001-initial-schema" "002-relations-in-identity"])
  (seed-old-shape! conn)
  (run-migrations! store ["003-accounts-above-personas"]))

(deftest machine-users-on-accounts
  (with-open [conn (fresh-db "mig004-up")]
    (let [store (ragtime-jdbc/sql-database (jdbc/with-options conn {}))]
      (through-003! conn store)
      (let [accounts-before (q conn "SELECT id, email, password_hash FROM accounts ORDER BY id")
            personas-before (q conn "SELECT id, account_id, name, sort_order FROM personas ORDER BY id")
            identities-before (q conn "SELECT * FROM identities ORDER BY id")]

        (run-migrations! store ["004-machine-users"])

        (testing "every existing account came through untouched, and is a human"
          (is (= accounts-before
                 (q conn "SELECT id, email, password_hash FROM accounts ORDER BY id")))
          (is (= [0 0 0 0] (map :is_machine_user (q conn "SELECT is_machine_user FROM accounts ORDER BY id")))
              "the flag defaults to 0, so nobody was turned into a machine by the migration")
          (is (every? nil? (map :for_account_id (q conn "SELECT for_account_id FROM accounts")))
              "and no human points at a parent")
          (is (= [0 0 0 0] (map :can_create_personas (q conn "SELECT can_create_personas FROM accounts ORDER BY id")))))

        (testing "personas and identities are none of this migration's business"
          (is (= personas-before (q conn "SELECT id, account_id, name, sort_order FROM personas ORDER BY id")))
          (is (= identities-before (q conn "SELECT * FROM identities ORDER BY id"))))

        (testing "the accounts table gained exactly the machine-user columns"
          (is (= #{"id" "email" "password_hash" "name" "for_account_id"
                   "is_machine_user" "can_create_personas" "token_hash"}
                 (set (map :name (q conn "PRAGMA table_info(accounts)"))))))

        (testing "email lost NOT NULL, because a machine user has none"
          (jdbc/execute! conn [(str "INSERT INTO accounts (name, for_account_id, is_machine_user)"
                                    " VALUES ('a-machine', 1, 1)")])
          (is (= 1 (count (q conn "SELECT id FROM accounts WHERE name = 'a-machine'")))))

        (testing "two humans still cannot share an email"
          (is (thrown? Exception
                       (jdbc/execute! conn ["INSERT INTO accounts (email) VALUES ('dan@eighttrigrams.net')"]))))

        (testing "two machine users cannot share a name — globally, not per account"
          (is (thrown? Exception
                       (jdbc/execute! conn [(str "INSERT INTO accounts (name, for_account_id, is_machine_user)"
                                                 " VALUES ('a-machine', 2, 1)")]))
              "even under a different parent account"))

        (testing "but one account may hold several machine users — the whole point,
                  and the one thing tracker's shape must not be copied on"
          (jdbc/execute! conn [(str "INSERT INTO accounts (name, for_account_id, is_machine_user)"
                                    " VALUES ('second-machine', 1, 1)")])
          (is (= 2 (count (q conn "SELECT id FROM accounts WHERE for_account_id = 1 AND is_machine_user = 1")))))

        (testing "the two partial indexes do not collide across kinds"
          ;; every machine user has a NULL email and every human a NULL name;
          ;; a plain UNIQUE would be fine with that too, but only because SQLite
          ;; lets NULLs repeat — the point of WHERE is that the *other* kind is
          ;; not in the index at all
          (jdbc/execute! conn [(str "INSERT INTO accounts (name, for_account_id, is_machine_user)"
                                    " VALUES ('third-machine', 1, 1)")])
          (jdbc/execute! conn ["INSERT INTO accounts (email) VALUES ('another-human@et.n')"])
          (is (= 3 (count (q conn "SELECT id FROM accounts WHERE is_machine_user = 1"))))
          (is (= 5 (count (q conn "SELECT id FROM accounts WHERE is_machine_user = 0")))))

        (testing "grants are one row per persona a machine user may write"
          (let [m (:id (first (q conn "SELECT id FROM accounts WHERE name = 'a-machine'")))]
            (jdbc/execute! conn ["INSERT INTO machine_persona_grants (machine_account_id, persona_id) VALUES (?, ?)"
                                 m "dilmul-socfus"])
            (is (= 1 (count (q conn "SELECT * FROM machine_persona_grants"))))
            (testing "- and the same grant cannot be written twice"
              (is (thrown? Exception
                           (jdbc/execute! conn [(str "INSERT INTO machine_persona_grants"
                                                     " (machine_account_id, persona_id) VALUES (?, ?)")
                                                m "dilmul-socfus"]))))))))))

(deftest rollback-004-restores-the-account-shape
  (with-open [conn (fresh-db "mig004-down")]
    (let [store (ragtime-jdbc/sql-database (jdbc/with-options conn {}))]
      (through-003! conn store)
      (let [accounts-before (q conn "SELECT id, email, password_hash FROM accounts ORDER BY id")]
        (run-migrations! store ["004-machine-users"])
        (rollback! store "004-machine-users")

        (testing "the human accounts are back in their 003 shape, unharmed"
          (is (= #{"id" "email" "password_hash"}
                 (set (map :name (q conn "PRAGMA table_info(accounts)")))))
          (is (= accounts-before (q conn "SELECT id, email, password_hash FROM accounts ORDER BY id")))
          (is (= "NOT NULL"
                 (if (= 1 (:notnull (first (filter #(= "email" (:name %))
                                                   (q conn "PRAGMA table_info(accounts)")))))
                   "NOT NULL" "nullable"))
              "email is NOT NULL again"))

        (testing "the grants table is gone"
          (is (empty? (q conn "SELECT name FROM sqlite_master WHERE type='table' AND name='machine_persona_grants'"))))))))

(deftest rollback-004-refuses-while-machine-users-exist
  (with-open [conn (fresh-db "mig004-down-conflict")]
    (let [store (ragtime-jdbc/sql-database (jdbc/with-options conn {}))]
      (through-003! conn store)
      (run-migrations! store ["004-machine-users"])
      (jdbc/execute! conn [(str "INSERT INTO accounts (name, for_account_id, is_machine_user)"
                                " VALUES ('a-machine', 1, 1)")])

      (testing "a machine user has no email, and the old shape demands one — so the
                rollback hits NOT NULL rather than inventing one or dropping the row"
        (is (thrown? Exception (rollback! store "004-machine-users")))))))
