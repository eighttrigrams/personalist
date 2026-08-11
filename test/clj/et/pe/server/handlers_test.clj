(ns et.pe.server.handlers-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.walk :as walk]
            [buddy.hashers :as hashers]
            [et.pe.ds :as ds]
            [et.pe.server.handlers :as handlers]))

(defn- with-app
  "Run `f` against a fresh in-memory database wired into the handlers, with
   :dangerously-skip-logins? off so the guarded paths are actually exercised.
   Pass `devel` to run the same body as dev does."
  ([f] (with-app {:dangerously-skip-logins? false} f))
  ([devel f]
   (let [conn (ds/init-conn :sqlite-in-memory {})]
     (handlers/set-conn! conn)
     (handlers/set-config! {:devel devel})
     (try
       (f conn)
       (finally
         (handlers/set-conn! nil)
         (handlers/set-config! nil)
         (ds/close-conn conn))))))

(defn- login
  ([body] (login true body))
  ([prod-mode? body] ((handlers/persona-login-handler prod-mode?) {:body body})))

(def ^:private create-token #'handlers/create-token)
(def ^:private create-admin-token #'handlers/create-admin-token)

(defn- as
  "A request carrying `token` as its Bearer, plus whatever else."
  [token & {:as more}]
  (cond-> (or more {})
    token (assoc :headers {"authorization" (str "Bearer " token)})))

(defn- seen
  "A response body the way the browser gets it. serialize-response names every
   keyword it walks, map keys included, so a handler hands out string keys; the
   cljs side asks ajax for :keywords? true and reads them back as keywords. This
   does the same round trip, so the assertions below read as the client does."
  [res]
  (walk/keywordize-keys (:body res)))

(deftest login-takes-one-username-field-holding-the-email
  (with-app
    (fn [conn]
      (let [acc (ds/add-account conn "d@et.n" (hashers/derive "sekrit"))]
        (ds/add-persona conn acc :first-face "First")
        (ds/add-persona conn acc :second-face "Second")

        (testing "one field, and a human puts their email in it"
          (let [{:keys [status body]} (login {:username "d@et.n" :password "sekrit"})]
            (is (= 200 status))
            (is (true? (:success body)))
            (is (= {:account acc} (handlers/verify-token-check (:token body)))
                "the token names the account, not a persona")))

        (testing "logging in by persona id is gone — that identifier is not accepted at all"
          (doseq [id ["first-face" "second-face"]]
            (is (= 401 (:status (login {:username id :password "sekrit"}))) id)))

        (testing "and neither is the old wire shape, under either of its key names"
          (is (= 401 (:status (login {:email "d@et.n" :password "sekrit"}))))
          (is (= 401 (:status (login {:id "first-face" :password "sekrit"})))))

        (testing "every bad credential is one indistinguishable 401"
          (doseq [body [{:username "d@et.n" :password "wrong"}
                        {:username "nobody@et.n" :password "sekrit"}
                        {:username "first-face" :password "sekrit"}
                        {:username "" :password "sekrit"}
                        {:password "sekrit"}]]
            (let [res (login body)]
              (is (= 401 (:status res)) (pr-str body))
              (is (= "Invalid credentials" (get-in res [:body :error])) (pr-str body))
              (is (nil? (get-in res [:body :token])) (pr-str body)))))

        (testing "an account with no password at all cannot be entered"
          (let [no-pw (ds/add-account conn "e@et.n" nil)]
            (ds/add-persona conn no-pw :faceless nil)
            (is (= 401 (:status (login {:username "e@et.n" :password ""}))))))))))

(deftest a-machine-user-never-reaches-the-login-route
  (with-app
    (fn [conn]
      (let [acc (ds/add-account conn "d@et.n" (hashers/derive "sekrit"))]
        (ds/add-persona conn acc :face "Face")
        (ds/add-machine-user conn acc "daniel-machine" {})

        (testing "its name is answered with the same flat 401 an unknown name gets"
          (let [known (login {:username "daniel-machine" :password "anything"})
                unknown (login {:username "no-such-name-at-all" :password "anything"})]
            (is (= 401 (:status known)))
            (is (= (:body unknown) (:body known))
                "byte for byte, so the login route never confirms that a machine user exists")))

        (testing "not with an empty password either, though it has none stored"
          (is (= 401 (:status (login {:username "daniel-machine" :password ""})))))))))

(deftest the-admin-login-is-the-only-mint-of-the-admin-claim
  (with-app
    (fn [conn]
      ;; an ordinary account, to show its token carries nothing special
      (let [acc (ds/add-account conn "d@et.n" (hashers/derive "sekrit"))]
        (ds/add-persona conn acc :dan nil)
        ;; Driven with prod-mode? false, where the admin password is the literal
        ;; "admin"; with it true the handler reads ADMIN_PASSWORD, which a JVM
        ;; cannot set for itself. The login path taken is the same either way —
        ;; :dangerously-skip-logins? is off in this fixture.
        (testing "the ADMIN_PASSWORD login mints the un-mintable claim"
          (let [{:keys [status body]} (login false {:username "admin" :password "admin"})]
            (is (= 200 status))
            (is (true? (:admin (handlers/verify-token-check (:token body)))))))
        (testing "and an ordinary login never does"
          (let [{:keys [body]} (login {:username "d@et.n" :password "sekrit"})]
            (is (nil? (:admin (handlers/verify-token-check (:token body)))))))
        (testing "a wrong admin password is the same 401 as any other"
          (is (= 401 (:status (login false {:username "admin" :password "not-it"})))))))))

;; ---------------------------------------------------------------------------
;; The privacy rule, and everything that follows from it: an anonymous reader
;; learns that personas exist and nothing about who holds them.
;; ---------------------------------------------------------------------------

(deftest the-public-persona-list-carries-no-email
  (with-app
    (fn [conn]
      (let [acc (ds/add-account conn "d@et.n" nil)]
        (ds/add-persona conn acc :one "One")
        (ds/add-persona conn acc :two "Two")
        (let [res (handlers/list-personas-handler {})]
          (is (= 200 (:status res)))
          (testing "id and display name, and not one field more"
            (is (= [{:id "one" :name "One"} {:id "two" :name "Two"}]
                   (sort-by :id (seen res)))))
          (testing "nothing anywhere in the response says these two share a login"
            (is (not (re-find #"@" (pr-str (:body res)))))))))))

(deftest me-answers-for-the-account-behind-the-token
  (with-app
    (fn [conn]
      (let [mine (ds/add-account conn "d@et.n" nil)
            theirs (ds/add-account conn "e@et.n" nil)]
        (ds/add-persona conn mine :first-face "First")
        (ds/add-persona conn mine :second-face "Second")
        (ds/add-persona conn theirs :not-mine "Theirs")

        (testing "the account's own email and its personas, in the order it holds them"
          (let [res ((handlers/me-handler true) (as (create-token mine)))]
            (is (= 200 (:status res)))
            (is (= {:email "d@et.n"
                    :personas [{:id "first-face" :name "First" :sort-order 0}
                               {:id "second-face" :name "Second" :sort-order 1}]}
                   (seen res)))
            (is (not (re-find #"not-mine" (pr-str (:body res))))
                "and nobody else's")))

        (testing "this is the first guarded GET in the app: no token, no answer"
          (is (= 401 (:status ((handlers/me-handler true) (as nil)))))
          (is (= 401 (:status ((handlers/me-handler true) (as "garbage"))))))

        (testing "admin has no account of its own and says so"
          (let [res ((handlers/me-handler true) (as (create-admin-token)))]
            (is (= 200 (:status res)))
            (is (= {:admin true} (seen res)))))

        (testing "a token for an account that has since been deleted is a 401, not a 500"
          (is (= 401 (:status ((handlers/me-handler true) (as (create-token 99999)))))))))))

(deftest me-in-dev-mode-takes-the-persona-off-the-query-string
  (with-app {:dangerously-skip-logins? true}
    (fn [conn]
      (let [mine (ds/add-account conn "d@et.n" nil)]
        (ds/add-persona conn mine :first-face "First")
        (testing "nothing mints a token in that mode, so ?persona names the account"
          (let [res ((handlers/me-handler false) {:query-params {"persona" "first-face"}})]
            (is (= 200 (:status res)))
            (is (= "d@et.n" (:email (seen res))))))
        (testing "an unknown persona is still a 401"
          (is (= 401 (:status ((handlers/me-handler false)
                               {:query-params {"persona" "nobody"}})))))
        (testing "the branch is dev-only: with prod-mode? the same request is refused"
          (is (= 401 (:status ((handlers/me-handler true)
                               {:query-params {"persona" "first-face"}})))))))))

(deftest a-persona-is-minted-under-the-requesting-account
  (with-app
    (fn [conn]
      (let [mine (ds/add-account conn "d@et.n" nil)
            theirs (ds/add-account conn "e@et.n" nil)]
        (ds/add-persona conn mine :first-face "First")
        (ds/add-persona conn theirs :theirs "Theirs")

        (testing "a named id lands last in the account's own order"
          (let [res ((handlers/add-persona-handler true)
                     (as (create-token mine) :body {:id "second-face" :name "Second"}))]
            (is (= 201 (:status res)))
            (is (= "second-face" (:id (seen res))))
            (is (= [{:id :first-face :name "First" :sort-order 0}
                    {:id :second-face :name "Second" :sort-order 1}]
                   (ds/list-personas-for-account conn mine)))))

        (testing "without an :id one is generated"
          (let [res ((handlers/add-persona-handler true)
                     (as (create-token mine) :body {:name "Third"}))]
            (is (= 201 (:status res)))
            (is (re-matches #"[a-z]+-[a-z]+" (:id (seen res))))
            (is (= 3 (count (ds/list-personas-for-account conn mine))))))

        (testing "there is no way to mint into somebody else's account: the body names none"
          (let [before (count (ds/list-personas-for-account conn theirs))]
            ((handlers/add-persona-handler true)
             (as (create-token mine) :body {:id "sneaky" :name "S" :account theirs}))
            (is (= before (count (ds/list-personas-for-account conn theirs)))
                "an :account in the body is not a thing")))

        (testing "an id already taken — by anyone at all — is refused"
          (is (= 400 (:status ((handlers/add-persona-handler true)
                               (as (create-token mine) :body {:id "theirs" :name "X"}))))))

        (testing "and so is the reserved one"
          (is (= 400 (:status ((handlers/add-persona-handler true)
                               (as (create-token mine) :body {:id "admin" :name "X"}))))))

        (testing "no token, no persona"
          (is (= 401 (:status ((handlers/add-persona-handler true)
                               (as nil :body {:name "X"}))))))))))

(deftest deleting-a-persona-is-real-destruction
  (with-app
    (fn [conn]
      (let [mine (ds/add-account conn "d@et.n" nil)]
        (ds/add-persona conn mine :keeper "Keeper")
        (ds/add-persona conn mine :doomed "Doomed")
        (let [doomed (ds/get-persona-by-id conn :doomed)
              ident (ds/add-identity conn doomed "goes" "away")]
          (ds/update-identity conn doomed ident "goes" "away, edited")

          (testing "the typed confirmation is checked here, not only in the dialog"
            (is (= 400 (:status (handlers/delete-persona-handler
                                 {:params {:name "doomed"} :body {}}))))
            (is (= 400 (:status (handlers/delete-persona-handler
                                 {:params {:name "doomed"} :body {:confirm "keeper"}}))))
            (is (= 400 (:status (handlers/delete-persona-handler
                                 {:params {:name "doomed"} :body {:confirm "Doomed"}})))
                "it is the urbit id that must be typed, not the display name")
            (is (some? (ds/get-persona-by-id conn :doomed)) "and nothing was destroyed"))

          (testing "with the id typed correctly the persona and its whole history go"
            (let [res (handlers/delete-persona-handler
                       {:params {:name "doomed"} :body {:confirm "doomed"}})]
              (is (= 200 (:status res)))
              (is (true? (:success (seen res))))
              (is (nil? (ds/get-persona-by-id conn :doomed)))
              (is (= [] (ds/get-identity-history conn doomed ident)))))

          (testing "an account's last persona is refused: a login that leads nowhere"
            (let [{:keys [status]} (handlers/delete-persona-handler
                                     {:params {:name "keeper"} :body {:confirm "keeper"}})]
              (is (= 409 status))
              (is (some? (ds/get-persona-by-id conn :keeper)))))

          (testing "a persona that is not there is a 404"
            (is (= 404 (:status (handlers/delete-persona-handler
                                 {:params {:name "never-was"} :body {:confirm "never-was"}}))))))))))

(deftest a-persona-edit-reaches-the-display-name-and-nothing-else
  (with-app
    (fn [conn]
      (let [acc (ds/add-account conn "d@et.n" nil)]
        (ds/add-persona conn acc :dan "Dan")
        (is (= 200 (:status (handlers/update-persona-handler
                             {:params {:name "dan"} :body {:name "Renamed"}}))))
        (is (= "Renamed" (:name (ds/get-persona-by-id conn :dan))))
        (testing "an :email in the body is not a thing any more — it belongs to the account"
          (handlers/update-persona-handler {:params {:name "dan"} :body {:email "hijack@et.n"}})
          (is (= "d@et.n" (:email (ds/get-account conn acc))))
          (is (nil? (ds/get-account-by-email conn "hijack@et.n"))))
        (testing "an unknown persona is a 404"
          (is (= 404 (:status (handlers/update-persona-handler
                               {:params {:name "nobody"} :body {:name "X"}})))))))))

(deftest accounts-are-the-admin-s-business
  (with-app
    (fn [conn]
      (testing "admin creates an account and its first persona in one call"
        (let [res ((handlers/add-account-handler true)
                   (as (create-admin-token)
                       :body {:id "alice" :email "alice@et.n" :password "pw" :name "Alice"}))]
          (is (= 201 (:status res)))
          (is (= "alice" (:id (seen res))))
          (let [acc (ds/get-account-by-email conn "alice@et.n")]
            (is (some? acc))
            (is (= [{:id :alice :name "Alice" :sort-order 0}]
                   (ds/list-personas-for-account conn (:id acc))))
            (testing "- and the password it can log in with"
              (is (= 200 (:status (login {:username "alice@et.n" :password "pw"}))))))))

      (testing "a taken email is refused, and leaves no half-made persona"
        (is (= 400 (:status ((handlers/add-account-handler true)
                             (as (create-admin-token)
                                 :body {:id "bob" :email "alice@et.n" :password "pw" :name "Bob"})))))
        (is (nil? (ds/get-persona-by-id conn :bob))))

      (testing "a taken persona id is refused, and leaves no orphan account behind"
        (is (= 400 (:status ((handlers/add-account-handler true)
                             (as (create-admin-token)
                                 :body {:id "alice" :email "other@et.n" :password "pw" :name "X"})))))
        (is (nil? (ds/get-account-by-email conn "other@et.n"))))

      (testing "the listing pairs each account with its personas"
        ((handlers/add-account-handler true)
         (as (create-admin-token) :body {:id "bob" :email "bob@et.n" :password "pw" :name "Bob"}))
        (let [res ((handlers/list-accounts-handler true) (as (create-admin-token)))]
          (is (= 200 (:status res)))
          (is (= [{:email "alice@et.n" :personas [{:id "alice" :name "Alice" :sort-order 0}]}
                  {:email "bob@et.n" :personas [{:id "bob" :name "Bob" :sort-order 0}]}]
                 (mapv #(dissoc % :id) (seen res))))))

      (testing "and neither endpoint answers to anyone else"
        (let [ordinary (create-token (:id (ds/get-account-by-email conn "alice@et.n")))]
          (is (= 403 (:status ((handlers/list-accounts-handler true) (as ordinary)))))
          (is (= 401 (:status ((handlers/list-accounts-handler true) (as nil)))))
          (is (= 403 (:status ((handlers/add-account-handler true)
                               (as ordinary :body {:id "eve" :email "eve@et.n" :password "pw" :name "Eve"})))))
          (is (nil? (ds/get-persona-by-id conn :eve))))))))

;; F1's second latch, moved here from auth-test: the confusing "admin" row
;; cannot be created at all. Since the split it has to hold on the account side
;; too — a refusal that had already minted the account would leave an email
;; spent on nothing.
(deftest reserved-persona-ids-are-refused
  (with-app
    (fn [conn]
      (testing "POST /api/accounts with the reserved persona id is 400, not 201"
        (is (= 400 (:status ((handlers/add-account-handler true)
                             (as (create-admin-token)
                                 :body {:id "admin" :email "pwn@evil.example"
                                        :password "pwnedpw" :name "pwn"}))))))
      (testing "and neither row was written"
        (is (nil? (ds/get-persona-by-id conn :admin)))
        (is (nil? (ds/get-account-by-email conn "pwn@evil.example"))))
      (testing "an ordinary id still mints an account and its first persona"
        (is (= 201 (:status ((handlers/add-account-handler true)
                             (as (create-admin-token)
                                 :body {:id "regular" :email "r@example.com"
                                        :password "pw" :name "Regular"}))))))
      (testing "nor can the reserved id be minted as a further persona of an account"
        (let [acc (ds/get-account-by-email conn "r@example.com")]
          (is (= 400 (:status ((handlers/add-persona-handler true)
                               (as (create-token (:id acc)) :body {:id "admin" :name "pwn"})))))
          (is (nil? (ds/get-persona-by-id conn :admin))))))))
