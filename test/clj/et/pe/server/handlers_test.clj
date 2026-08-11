(ns et.pe.server.handlers-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
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

(defmacro ^:private are-nil [& forms]
  `(do ~@(map (fn [f] `(is (nil? ~f) (pr-str '~f))) forms)))

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
                               {:id "second-face" :name "Second" :sort-order 1}]
                    ;; an account with no machine users still says so, rather
                    ;; than omitting the key and making the client guess
                    :machine-users []}
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

          ;; This used to be a 409 — "an account with no persona is a login that
          ;; leads nowhere". It leads to the profile page. The rule is gone, and
          ;; the account's last persona goes like any other.
          (testing "and so does the account's last persona"
            (let [{:keys [status]} (handlers/delete-persona-handler
                                     {:params {:name "keeper"} :body {:confirm "keeper"}})]
              (is (= 200 status))
              (is (nil? (ds/get-persona-by-id conn :keeper)))))

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
      (testing "admin creates an account — an email and a password, no persona"
        (let [res ((handlers/add-account-handler true)
                   (as (create-admin-token) :body {:email "alice@et.n" :password "pw"}))]
          (is (= 201 (:status res)))
          (let [acc (ds/get-account-by-email conn "alice@et.n")]
            (is (some? acc))
            (is (= (:id acc) (:id (seen res))) "the response names the new account")
            (is (= [] (ds/list-personas-for-account conn (:id acc))))
            (testing "- and the password it can log in with"
              (is (= 200 (:status (login {:username "alice@et.n" :password "pw"}))))))))

      (testing "a taken email is refused"
        (is (= 400 (:status ((handlers/add-account-handler true)
                             (as (create-admin-token)
                                 :body {:email "alice@et.n" :password "pw"})))))
        (is (= 1 (count (ds/list-accounts conn)))))

      ;; What used to be here — "a taken persona id is refused, and leaves no
      ;; orphan account behind" — cannot happen any more: this call names no
      ;; persona. The id collision it guarded against now lives entirely in
      ;; POST /api/personas, where a-persona-is-minted-under-the-requesting-account
      ;; and the admin :account_id tests cover it.

      (testing "the listing pairs each account with its personas, and an account
                with none is listed all the same"
        ((handlers/add-account-handler true)
         (as (create-admin-token) :body {:email "bob@et.n" :password "pw"}))
        (let [alice (ds/get-account-by-email conn "alice@et.n")]
          (ds/add-persona conn (:id alice) :alice "Alice"))
        (let [res ((handlers/list-accounts-handler true) (as (create-admin-token)))]
          (is (= 200 (:status res)))
          (is (= [{:email "alice@et.n" :personas [{:id "alice" :name "Alice" :sort-order 0}]}
                  {:email "bob@et.n" :personas []}]
                 (mapv #(dissoc % :id) (seen res))))))

      (testing "a machine token is refused as forbidden, not as unauthenticated —
                it is a credential, just not an admin one"
        (let [acc (ds/get-account-by-email conn "alice@et.n")]
          (ds/add-machine-user conn (:id acc) "alice-machine" {})
          (let [mt (handlers/mint-machine-token! "alice-machine")]
            (is (= 403 (:status ((handlers/list-accounts-handler true) (as mt)))))
            (is (= 403 (:status ((handlers/add-account-handler true)
                                 (as mt :body {:email "x@et.n" :password "pw"}))))))))

      (testing "and neither endpoint answers to anyone else"
        (let [ordinary (create-token (:id (ds/get-account-by-email conn "alice@et.n")))]
          (is (= 403 (:status ((handlers/list-accounts-handler true) (as ordinary)))))
          (is (= 401 (:status ((handlers/list-accounts-handler true) (as nil)))))
          (is (= 403 (:status ((handlers/add-account-handler true)
                               (as ordinary :body {:email "eve@et.n" :password "pw"})))))
          (is (nil? (ds/get-account-by-email conn "eve@et.n"))))))))

;; F1's second latch: the confusing "admin" row cannot be created at all. It used
;; to be checked at POST /api/accounts as well, because that call minted a first
;; persona; it does not any more, so the latch lives where personas are actually
;; made — which is the only place it ever mattered.
(deftest reserved-persona-ids-are-refused
  (with-app
    (fn [conn]
      (let [acc (ds/add-account conn "r@example.com" (hashers/derive "pw"))
            token (create-token acc)]
        (testing "a human cannot mint the reserved id under its own account"
          (is (= 400 (:status ((handlers/add-persona-handler true)
                               (as token :body {:id "admin" :name "pwn"})))))
          (is (nil? (ds/get-persona-by-id conn :admin))))

        (testing "nor can admin mint it into somebody else's account"
          (is (= 400 (:status ((handlers/add-persona-handler true)
                               (as (create-admin-token)
                                   :body {:id "admin" :name "pwn" :account_id acc})))))
          (is (nil? (ds/get-persona-by-id conn :admin))))

        (testing "nor can a machine user that may create personas"
          (ds/add-machine-user conn acc "a-machine" {:can-create-personas? true})
          (let [mt (handlers/mint-machine-token! "a-machine")]
            (is (= 400 (:status ((handlers/add-persona-handler true)
                                 (as mt :body {:id "admin" :name "pwn"})))))
            (is (nil? (ds/get-persona-by-id conn :admin)))))

        (testing "an ordinary id still goes through"
          (is (= 201 (:status ((handlers/add-persona-handler true)
                               (as token :body {:id "regular" :name "Regular"})))))
          (is (some? (ds/get-persona-by-id conn :regular))))))))

;; ===========================================================================
;; THE ESCALATION TESTS
;;
;; wrap-auth waves any valid token through a URI that names no persona. That is
;; right for POST /api/personas, whose handler mints under the caller's own
;; account. It is emphatically wrong for the machine-user management routes: a
;; machine user that reached those could grant itself every persona in the
;; account, mint itself a fresh token to survive being rotated by its owner, or
;; create a second machine user with whatever grants it liked.
;;
;; So each of those handlers checks for itself that the caller is a *human*
;; account and that the machine user named is one of its own. These tests exist
;; to make that non-optional. A machine user escalating itself to full account
;; rights is the one bug in this feature that actually matters.
;; ===========================================================================

(defn- machine-fixture
  "A human account with two personas and a machine user granted one of them —
   the shape every escalation test starts from. Answers the pieces by name."
  [conn]
  (let [mine (ds/add-account conn "d@et.n" (hashers/derive "pw"))
        theirs (ds/add-account conn "e@et.n" (hashers/derive "pw"))]
    (ds/add-persona conn mine :face-a "A")
    (ds/add-persona conn mine :face-b "B")
    (ds/add-persona conn theirs :not-mine "Theirs")
    (ds/add-machine-user conn mine "daniel-machine" {})
    (let [m (ds/get-machine-user conn "daniel-machine")]
      (ds/grant-persona conn (:id m) :face-a)
      {:account mine
       :other-account theirs
       :machine m
       :machine-token (handlers/mint-machine-token! "daniel-machine")
       :human-token (create-token mine)
       :other-human-token (create-token theirs)})))

(defn- management-calls
  "Every machine-user management route, as a thunk taking a token. If a route is
   added later and not listed here, the escalation tests below stop covering it
   — which is why they are enumerated in one place."
  [f]
  {"POST /api/machine-users"
   (fn [t] ((handlers/add-machine-user-handler true)
            (as t :body {:name (str "minted-by-" (name f)) :can_create true})))

   "POST /api/machine-users/:name/token"
   (fn [t] ((handlers/rotate-machine-token-handler true)
            (as t :params {:name "daniel-machine"})))

   "PUT /api/machine-users/:name"
   (fn [t] ((handlers/update-machine-user-handler true)
            (as t :params {:name "daniel-machine"}
                :body {:personas ["face-a" "face-b"] :can_create true})))

   "DELETE /api/machine-users/:name"
   (fn [t] ((handlers/delete-machine-user-handler true)
            (as t :params {:name "daniel-machine"})))})

(deftest a-machine-token-is-refused-by-every-management-route
  (with-app
    (fn [conn]
      (let [{:keys [machine-token machine]} (machine-fixture conn)]
        (doseq [[route call] (management-calls :machine)]
          (testing route
            (is (= 403 (:status (call machine-token)))
                (str route " must refuse a machine token"))))

        (testing "and nothing it tried actually happened"
          (is (= [:face-a] (ds/granted-personas conn (:id machine)))
              "it did not grant itself the account's other persona")
          (is (false? (:can-create-personas? (ds/get-machine-user conn "daniel-machine")))
              "nor give itself permission to create personas")
          (is (= 1 (count (ds/list-machine-users conn (:for-account-id machine))))
              "nor mint a second machine user")
          (is (some? (ds/get-machine-user conn "daniel-machine"))
              "nor delete itself"))))))

(deftest a-machine-token-cannot-mint-itself-a-fresh-token
  (with-app
    (fn [conn]
      (let [{:keys [machine-token machine]} (machine-fixture conn)
            hash-before (:token-hash (ds/get-machine-user conn "daniel-machine"))]

        (testing "the rotation route is the one that would let it outlive being revoked"
          (let [res ((handlers/rotate-machine-token-handler true)
                     (as machine-token :params {:name "daniel-machine"}))]
            (is (= 403 (:status res)))
            (is (nil? (:token (seen res))) "and above all it hands back no token")))

        (testing "the stored hash is untouched, so the owner's rotation still governs"
          (is (= hash-before (:token-hash (ds/get-machine-user conn "daniel-machine")))))

        (testing "the machine user is still reachable only by the token it was given"
          (is (= (:id machine) (:id (handlers/machine-user-for-token machine-token)))))))))

(deftest one-account-s-human-cannot-manage-another-s-machine-user
  (with-app
    (fn [conn]
      (let [{:keys [other-human-token machine]} (machine-fixture conn)]
        (doseq [[route call] (management-calls :other-human)]
          (when-not (= route "POST /api/machine-users")   ; that one creates under its own account, legitimately
            (testing route
              (is (= 404 (:status (call other-human-token)))
                  (str route " must not confirm that another account's machine user exists")))))

        (testing "nothing of the other account's machine user moved"
          (is (= [:face-a] (ds/granted-personas conn (:id machine))))
          (is (some? (ds/get-machine-user conn "daniel-machine"))))))))

(deftest neither-anonymous-nor-admin-manages-machine-users
  (with-app
    (fn [conn]
      (machine-fixture conn)
      (doseq [[route call] (management-calls :anon)]
        (testing (str route " — no token at all")
          (is (= 401 (:status (call nil))))))
      ;; Admin is exempt from wrap-auth everywhere, but a machine user hangs off
      ;; an account and admin has none — there is no account for it to act as, so
      ;; there is nothing coherent for these routes to do on its behalf.
      (doseq [[route call] (management-calls :admin)]
        (testing (str route " — admin has no account to own one under")
          (is (= 403 (:status (call (create-admin-token))))))))))

(deftest a-human-cannot-grant-its-machine-user-somebody-else-s-persona
  (with-app
    (fn [conn]
      (let [{:keys [human-token machine]} (machine-fixture conn)]
        (testing "a persona of another account is refused, not silently dropped —
                  a grant the caller believes it made and did not is worse"
          (let [res ((handlers/update-machine-user-handler true)
                     (as human-token :params {:name "daniel-machine"}
                         :body {:personas ["face-a" "not-mine"]}))]
            (is (= 400 (:status res)))))

        (testing "and no part of that request took effect"
          (is (= [:face-a] (ds/granted-personas conn (:id machine)))))

        (testing "a persona that does not exist at all is refused the same way"
          (is (= 400 (:status ((handlers/update-machine-user-handler true)
                               (as human-token :params {:name "daniel-machine"}
                                   :body {:personas ["never-existed"]}))))))))))

;; ---------------------------------------------------------------------------
;; Creating personas, the third caller
;; ---------------------------------------------------------------------------

(deftest a-machine-user-with-can-create-mints-under-its-parent-and-self-grants
  (with-app
    (fn [conn]
      (let [{:keys [account machine machine-token]} (machine-fixture conn)]
        (ds/update-machine-user conn (:id machine) {:can-create-personas? true})

        (let [res ((handlers/add-persona-handler true)
                   (as machine-token :body {:id "made-by-machine" :name "Made by machine"}))]
          (is (= 201 (:status res)))

          (testing "the persona belongs to the parent account, not to the machine user —
                    a machine user is a credential, not a place to hang content on"
            (is (= account (:account-id (ds/get-persona-by-id conn :made-by-machine))))
            (is (= #{:face-a :face-b :made-by-machine}
                   (set (map :id (ds/list-personas-for-account conn account))))))

          (testing "and it granted itself write on what it just made — the owner's
                    'added to the list of personas from that moment on'"
            (is (= [:face-a :made-by-machine] (ds/granted-personas conn (:id machine)))))

          (testing "which the guard honours immediately, with the same token"
            (is (true? (handlers/machine-grants-persona? (:id machine) "made-by-machine")))))

        (testing "it still gains nothing on the personas it was not granted"
          (is (false? (handlers/machine-grants-persona? (:id machine) "face-b"))))))))

(deftest a-machine-user-without-can-create-is-refused
  (with-app
    (fn [conn]
      (let [{:keys [account machine machine-token]} (machine-fixture conn)]
        (is (false? (:can-create-personas? machine)) "the fixture's machine user has no such permission")

        (let [res ((handlers/add-persona-handler true)
                   (as machine-token :body {:id "should-not-exist" :name "Nope"}))]
          (is (= 403 (:status res))))

        (testing "and nothing was created under the parent account"
          (is (nil? (ds/get-persona-by-id conn :should-not-exist)))
          (is (= 2 (count (ds/list-personas-for-account conn account)))))))))

(deftest deleting-a-persona-stays-with-the-human-account
  (with-app
    (fn [conn]
      (let [{:keys [machine machine-token]} (machine-fixture conn)]
        (ds/update-machine-user conn (:id machine) {:can-create-personas? true})

        (testing "a machine user is refused even on a persona it holds a grant on"
          (is (true? (handlers/machine-grants-persona? (:id machine) "face-a")))
          (let [res (handlers/delete-persona-handler
                     (as machine-token :params {:name "face-a"} :body {:confirm "face-a"}))]
            (is (= 403 (:status res)))
            (is (some? (ds/get-persona-by-id conn :face-a)))))

        (testing "and even on one it created itself"
          ((handlers/add-persona-handler true)
           (as machine-token :body {:id "its-own" :name "Its own"}))
          (let [res (handlers/delete-persona-handler
                     (as machine-token :params {:name "its-own"} :body {:confirm "its-own"}))]
            (is (= 403 (:status res)))
            (is (some? (ds/get-persona-by-id conn :its-own)))))))))

(deftest a-human-still-creates-under-its-own-account
  (with-app
    (fn [conn]
      (let [{:keys [account human-token]} (machine-fixture conn)]
        (is (= 201 (:status ((handlers/add-persona-handler true)
                             (as human-token :body {:id "by-hand" :name "By hand"})))))
        (is (= account (:account-id (ds/get-persona-by-id conn :by-hand))))
        (testing "and grants nothing to any machine user by doing so"
          (is (= [:face-a] (ds/granted-personas conn (:id (ds/get-machine-user conn "daniel-machine"))))))))))

;; ---------------------------------------------------------------------------
;; /api/me answers two different questions, depending who asks
;; ---------------------------------------------------------------------------

(deftest me-tells-a-human-about-its-machine-users
  (with-app
    (fn [conn]
      (let [{:keys [account human-token machine]} (machine-fixture conn)]
        (ds/add-machine-user conn account "second-machine" {:can-create-personas? true})
        (ds/grant-persona conn (:id (ds/get-machine-user conn "second-machine")) :face-b)

        (let [body (seen ((handlers/me-handler true) (as human-token)))]
          (testing "the roster, with each one's grants — the checkbox grid, as data"
            (is (= [{:name "daniel-machine" :can-create false :personas ["face-a"]}
                    {:name "second-machine" :can-create true :personas ["face-b"]}]
                   (:machine-users body))))

          (testing "and the personas are still there for the grid's columns"
            (is (= ["face-a" "face-b"] (map :id (:personas body)))))

          (testing "no token ever comes back here, not even its hash"
            (is (not (str/includes? (pr-str body) "pmu_")))
            (is (not (str/includes? (pr-str body) (:token-hash (ds/get-machine-user conn "daniel-machine")))))
            (is (not (str/includes? (pr-str body) "token")))))

        (testing "another account sees none of them"
          (let [other (ds/add-account conn "z@et.n" nil)]
            (ds/add-persona conn other :zeta "Z")
            (is (= [] (:machine-users (seen ((handlers/me-handler true) (as (create-token other))))))))))))) 

(deftest me-tells-a-machine-user-about-itself
  (with-app
    (fn [conn]
      (let [{:keys [machine-token]} (machine-fixture conn)
            body (seen ((handlers/me-handler true) (as machine-token)))]

        (testing "its own name, what it may do, and what it may write"
          (is (= {:name "daniel-machine" :machine true :can-create false :personas ["face-a"]}
                 body)))

        (testing "and never the account's roster — a machine user is not shown
                  the other machine users, nor the personas it was not granted"
          (is (nil? (:machine-users body)))
          (is (not (str/includes? (pr-str body) "face-b")))
          (is (not (str/includes? (pr-str body) "@")) "nor its owner's email"))))))

(deftest me-refuses-a-rotated-away-machine-token
  (with-app
    (fn [conn]
      (let [{:keys [machine-token]} (machine-fixture conn)]
        (is (= 200 (:status ((handlers/me-handler true) (as machine-token)))))
        (handlers/mint-machine-token! "daniel-machine")
        (is (= 401 (:status ((handlers/me-handler true) (as machine-token)))))))))

;; ===========================================================================
;; Zero personas is a legitimate state
;;
;; An account is an email and a password; personas are things its owner makes,
;; or does not. The rule this replaces — "an account with no persona is a login
;; that leads nowhere" — was wrong: it leads to the profile page, which is
;; exactly where a new account should land.
;; ===========================================================================

(deftest removing-an-account-s-only-persona-is-allowed
  (with-app
    (fn [conn]
      (let [acc (ds/add-account conn "d@et.n" (hashers/derive "pw"))]
        (ds/add-persona conn acc :only-face "The only one")
        (let [persona (ds/get-persona-by-id conn :only-face)
              ident (ds/add-identity conn persona "goes" "away")]

          (testing "the last persona goes like any other — no 409, no special case"
            (let [res (handlers/delete-persona-handler
                       {:params {:name "only-face"} :body {:confirm "only-face"}})]
              (is (= 200 (:status res)))
              (is (true? (:success (seen res))))))

          (testing "and it took its identities with it, as any removal does"
            (is (nil? (ds/get-persona-by-id conn :only-face)))
            (is (= [] (ds/get-identity-history conn persona ident))))

          (testing "the account itself survives, holding nothing"
            (is (= {:id acc :email "d@et.n"} (ds/get-account conn acc)))
            (is (= [] (ds/list-personas-for-account conn acc))))

          (testing "and it can still log in, which is the whole point —
                    it lands on its profile page and makes a new one"
            (is (= 200 (:status (login {:username "d@et.n" :password "pw"})))))

          (testing "the hand-typed confirmation is still the guard, and still bites"
            (ds/add-persona conn acc :second-try "Again")
            (is (= 400 (:status (handlers/delete-persona-handler
                                 {:params {:name "second-try"} :body {:confirm "wrong"}}))))
            (is (some? (ds/get-persona-by-id conn :second-try)))))))))

(deftest creating-an-account-creates-only-the-account
  (with-app
    (fn [conn]
      (testing "an email and a password, and nothing else — no first persona"
        (let [res ((handlers/add-account-handler true)
                   (as (create-admin-token) :body {:email "alice@et.n" :password "pw"}))]
          (is (= 201 (:status res)))
          (testing "- the response carries the new account's id, which is what the
                    seed script and the admin form need to make a persona under it"
            (let [id (:id (seen res))
                  acc (ds/get-account-by-email conn "alice@et.n")]
              (is (integer? id))
              (is (= (:id acc) id))))
          (testing "- and the call made no persona at all"
            (is (= [] (ds/list-personas conn)))
            (is (= [] (ds/list-personas-for-account conn (:id (ds/get-account-by-email conn "alice@et.n"))))))))

      (testing "it can log in immediately, holding nothing"
        (is (= 200 (:status (login {:username "alice@et.n" :password "pw"})))))

      (testing "a :name or an :id in the body is not a thing any more"
        (let [res ((handlers/add-account-handler true)
                   (as (create-admin-token)
                       :body {:email "bob@et.n" :password "pw" :name "Bob" :id "bob"}))]
          (is (= 201 (:status res)))
          (is (nil? (ds/get-persona-by-id conn :bob))
              "an :id in the body mints no persona")
          (is (= [] (ds/list-personas conn)))))

      (testing "the email checks are what remain"
        (is (= 400 (:status ((handlers/add-account-handler true)
                             (as (create-admin-token) :body {:email "alice@et.n" :password "pw"})))))
        (is (= 400 (:status ((handlers/add-account-handler true)
                             (as (create-admin-token) :body {:password "pw"}))))))

      (testing "and it is still admin's alone"
        (let [ordinary (create-token (:id (ds/get-account-by-email conn "alice@et.n")))]
          (is (= 403 (:status ((handlers/add-account-handler true)
                               (as ordinary :body {:email "eve@et.n" :password "pw"})))))
          (is (= 401 (:status ((handlers/add-account-handler true)
                               (as nil :body {:email "eve@et.n" :password "pw"})))))
          (is (nil? (ds/get-account-by-email conn "eve@et.n"))))))))

;; ---------------------------------------------------------------------------
;; :account_id on POST /api/personas
;;
;; Once an account can exist with no personas, nobody can give it its first one
;; from outside — POST /api/personas mints "under the caller's own account", and
;; a fresh account has no caller yet. For its owner that is correct and is the
;; point. For the seed script, which builds alice and bob without ever being
;; either of them, it is a dead end.
;;
;; So admin may name the account. Admin already edits other accounts from
;; Settings; this is the same authority, not a special case invented for a
;; script. Everyone else naming somebody else's id must be REFUSED rather than
;; quietly obeyed or quietly ignored.
;; ---------------------------------------------------------------------------

(deftest naming-another-account-is-refused-for-everyone-but-admin
  (with-app
    (fn [conn]
      (let [mine (ds/add-account conn "d@et.n" nil)
            theirs (ds/add-account conn "e@et.n" nil)]
        (ds/add-persona conn mine :my-face "Mine")

        (testing "a human naming another account's id is refused, not obeyed"
          (let [res ((handlers/add-persona-handler true)
                     (as (create-token mine) :body {:id "stolen" :name "S" :account_id theirs}))]
            (is (= 403 (:status res)))
            (is (nil? (ds/get-persona-by-id conn :stolen)))
            (is (= [] (ds/list-personas-for-account conn theirs))
                "and above all it did not land in the account it named")))

        (testing "not even its own id — the field is admin's, full stop, so there is
                  no shape of it a human token gets to use"
          (let [res ((handlers/add-persona-handler true)
                     (as (create-token mine) :body {:id "redundant" :name "R" :account_id mine}))]
            (is (= 403 (:status res)))
            (is (nil? (ds/get-persona-by-id conn :redundant)))))

        (testing "a human without the field still mints under itself, as always"
          (is (= 201 (:status ((handlers/add-persona-handler true)
                               (as (create-token mine) :body {:id "ordinary" :name "O"})))))
          (is (= mine (:account-id (ds/get-persona-by-id conn :ordinary)))))

        (testing "a machine user is refused too, with and without can_create"
          (ds/add-machine-user conn mine "a-machine" {:can-create-personas? true})
          (let [mt (handlers/mint-machine-token! "a-machine")]
            (is (= 403 (:status ((handlers/add-persona-handler true)
                                 (as mt :body {:id "by-machine" :account_id theirs})))))
            (is (nil? (ds/get-persona-by-id conn :by-machine)))
            (testing "- while without the field it creates under its parent, as before"
              (is (= 201 (:status ((handlers/add-persona-handler true)
                                   (as mt :body {:id "under-parent" :name "P"})))))
              (is (= mine (:account-id (ds/get-persona-by-id conn :under-parent)))))))))))

(deftest admin-may-give-any-account-a-persona
  (with-app
    (fn [conn]
      (let [fresh (ds/add-account conn "fresh@et.n" nil)]
        (testing "the account starts with none, which is now a legitimate state"
          (is (= [] (ds/list-personas-for-account conn fresh))))

        (testing "admin names it and the persona lands there"
          (let [res ((handlers/add-persona-handler true)
                     (as (create-admin-token)
                         :body {:id "alice" :name "Alice" :account_id fresh}))]
            (is (= 201 (:status res)))
            (is (= "alice" (:id (seen res))))
            (is (= fresh (:account-id (ds/get-persona-by-id conn :alice))))
            (is (= [{:id :alice :name "Alice" :sort-order 0}]
                   (ds/list-personas-for-account conn fresh)))))

        (testing "a second one lands after it in that account's order"
          ((handlers/add-persona-handler true)
           (as (create-admin-token) :body {:id "alice2" :name "Alice again" :account_id fresh}))
          (is (= [0 1] (map :sort-order (ds/list-personas-for-account conn fresh)))))

        (testing "the id is still global — a taken one is refused whoever asks"
          (is (= 400 (:status ((handlers/add-persona-handler true)
                               (as (create-admin-token)
                                   :body {:id "alice" :name "X" :account_id fresh}))))))

        (testing "and the reserved id is refused for admin as well"
          (is (= 400 (:status ((handlers/add-persona-handler true)
                               (as (create-admin-token)
                                   :body {:id "admin" :name "pwn" :account_id fresh})))))
          (is (nil? (ds/get-persona-by-id conn :admin))))

        (testing "an :account_id naming no account is a 404, not a persona hanging off nothing"
          (is (= 404 (:status ((handlers/add-persona-handler true)
                               (as (create-admin-token)
                                   :body {:id "orphan" :name "O" :account_id 99999})))))
          (is (nil? (ds/get-persona-by-id conn :orphan))))

        (testing "and admin without the field has no account of its own to mint under"
          (is (= 400 (:status ((handlers/add-persona-handler true)
                               (as (create-admin-token) :body {:id "nowhere" :name "N"})))))
          (is (nil? (ds/get-persona-by-id conn :nowhere))))))))

(deftest me-answers-an-account-that-holds-nothing
  (with-app
    (fn [conn]
      (let [empty-account (ds/add-account conn "d@et.n" (hashers/derive "pw"))]
        (testing "an empty persona list is an answer, not an error — this is the
                  state a freshly created account is in, and /api/me is what the
                  profile page asks before rendering its empty state"
          (let [res ((handlers/me-handler true) (as (create-token empty-account)))]
            (is (= 200 (:status res)))
            (is (= {:email "d@et.n" :personas [] :machine-users []} (seen res)))))

        (testing "and it still holds machine users, which do not need a persona to exist"
          (ds/add-machine-user conn empty-account "a-machine" {})
          (let [body (seen ((handlers/me-handler true) (as (create-token empty-account))))]
            (is (= [] (:personas body)))
            (is (= [{:name "a-machine" :can-create false :personas []}] (:machine-users body)))))

        (testing "a machine user of an empty account is answered too, granted nothing"
          (let [mt (handlers/mint-machine-token! "a-machine")]
            (let [res ((handlers/me-handler true) (as mt))]
              (is (= 200 (:status res)))
              (is (= {:name "a-machine" :machine true :can-create false :personas []}
                     (seen res))))))))))
