(ns et.pe.server.handlers
  (:require [et.pe.ds :as ds]
            [et.pe.urbit :as urbit]
            [et.pe.middleware.rate-limit :as rate-limit]
            [clojure.string :as str]
            [clojure.walk]
            [buddy.hashers :as hashers]
            [buddy.sign.jwt :as jwt]
            [buddy.core.codecs :as codecs]
            [buddy.core.hash :as hash]
            [buddy.core.nonce :as nonce]
            [taoensso.telemere :as tel])
  (:import [java.time Instant ZonedDateTime]))

(defonce ds-conn (atom nil))
(defonce config (atom nil))

(defn set-conn! [conn]
  (reset! ds-conn conn))

(defn set-config! [cfg]
  (reset! config cfg))

(defn ensure-conn []
  @ds-conn)

(defn- str->keyword [s]
  (if (string? s) (keyword s) s))

(defn- serialize-response [data]
  (clojure.walk/postwalk
   (fn [x]
     (cond
       (instance? Instant x) (.toString x)
       (instance? ZonedDateTime x) (.toString (.toInstant x))
       (keyword? x) (name x)
       :else x))
   data))

(defn- allow-skip-logins?
  [prod-mode?]
  (and (true? (get-in @config [:devel :dangerously-skip-logins?]))
       (not prod-mode?)))

(defn- jwt-secret []
  (or (System/getenv "ADMIN_PASSWORD") "dev-secret"))

;; The token names the *account*, not a persona: the password lives on the
;; account and a persona is only one of the faces it wears, so one token has to
;; cover every persona the account holds.
(defn- create-token [account-id]
  (jwt/sign {:account account-id} (jwt-secret)))

;; The admin exemption (server/owns-persona?) keys on this :admin claim, never on
;; a persona id or an account id, so it cannot be minted by anyone who can create
;; a row: only the ADMIN_PASSWORD login below calls this. Folding it back into
;; create-token would re-open the admin escalation.
(defn- create-admin-token []
  (jwt/sign {:admin true} (jwt-secret)))

(defn- verify-token [token]
  (try
    (jwt/unsign token (jwt-secret))
    (catch Exception _ nil)))

(defn verify-token-check [token]
  (verify-token token))

;; ---------------------------------------------------------------------------
;; The machine-user token
;;
;; Two kinds of credential now arrive in the same `Authorization: Bearer`
;; header. A human's is a signed JWT; a machine user's is an opaque random
;; string, because a machine user has no password and no session — the token is
;; its whole identity.
;;
;; Deliberately *not* bcrypt. Bcrypt is slow on purpose, to guard low-entropy
;; human passwords against offline guessing; this is 256 bits from a secure
;; random source, which needs no stretching, and a machine user hits the API in
;; a loop. Paying ~100ms of key derivation per request to protect a string that
;; cannot be guessed would be a cost with no benefit. A single SHA-256 is the
;; right shape: it is only there so that a database dump does not hand out live
;; credentials.
;; ---------------------------------------------------------------------------

(def ^:private machine-token-prefix
  "Marks a machine token on sight, so the verifier can route it to the grant
   lookup rather than discovering what it is by trying to unsign it and reading
   the exception. It also makes a leaked token greppable in a log or a repo."
  "pmu_")

(defn- token-hash
  "SHA-256, hex. The token is hashed on presentation and the row looked up by
   the hash, so the token itself is never stored and never has to be."
  [token]
  (codecs/bytes->hex (hash/sha256 token)))

(defn machine-token? [token]
  (and (string? token) (str/starts-with? token machine-token-prefix)))

(defn mint-machine-token!
  "Issue a fresh token for the machine user named `nm` and store only its hash,
   answering the token itself. This is also the rotation: there is one column to
   hold the hash, so writing a new one is what makes the previous token stop
   verifying. The caller must show the result to the user immediately — it
   cannot be recovered afterwards, only replaced."
  [nm]
  (when-let [machine (ds/get-machine-user (ensure-conn) nm)]
    (let [token (str machine-token-prefix
                     (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder))
                                      (nonce/random-bytes 32)))]
      (ds/set-machine-token-hash! (ensure-conn) (:id machine) (token-hash token))
      token)))

(defn machine-user-for-token
  "The machine user a presented token belongs to, or nil. Only tokens wearing
   the prefix are looked up at all, and a machine user that has never been
   issued one has a NULL hash that nothing can match."
  [token]
  (when (machine-token? token)
    (ds/get-machine-user-by-token-hash (ensure-conn) (token-hash token))))

(defn- dangerously-skip-logins? []
  (true? (get-in @config [:devel :dangerously-skip-logins?])))

(defn account-of-persona
  "The id of the account holding `persona-id`, or nil when no persona has that
   id. This is what wrap-auth's ownership check became since accounts sit above
   personas: a lookup rather than a string comparison. wrap-auth runs above the
   routes and holds no connection of its own, so it asks here."
  [persona-id]
  (:account-id (ds/get-persona-by-id (ensure-conn) (str->keyword persona-id))))

(defn machine-grants-persona?
  "Whether a machine user has been granted write on `persona-id`. The other half
   of wrap-auth's ownership question, for the other kind of token."
  [machine-account-id persona-id]
  (ds/machine-may-write? (ensure-conn) machine-account-id (str->keyword persona-id)))

(defn principal-for-token
  "Who a presented Bearer token is, or nil when it is neither a verifying JWT
   nor a live machine token. One place decides which of the two kinds arrived,
   on the prefix rather than by trying to unsign and catching the failure:

     {:kind :admin}
     {:kind :human   :account <account-id>}
     {:kind :machine :id <machine-account-id> :for-account-id ... :name ...}

   A machine token that has been rotated away, or belongs to a machine user that
   has been deleted, resolves to nil — the same nil a forged one gives, so both
   are refused as unauthenticated rather than as forbidden."
  [token]
  (if (machine-token? token)
    (some-> (machine-user-for-token token) (assoc :kind :machine))
    (when-let [c (verify-token token)]
      (if (true? (:admin c))
        {:kind :admin}
        {:kind :human :account (:account c)}))))

;; ---------------------------------------------------------------------------
;; The handlers that guard themselves
;;
;; wrap-auth guards writes and lets every GET through, because a GET here serves
;; what a visitor of personalist.org sees. /api/me and /api/accounts break that:
;; they answer *about an account*, which is the one thing this app's anonymity
;; protects. They verify the token here, in the handler, rather than widening
;; wrap-auth's rule for everything else.
;; ---------------------------------------------------------------------------

(defn- bearer-token
  "The Bearer token of a request, or nil. wrap-auth has its own copy for the
   requests it guards; the handlers that guard themselves need it too."
  [req]
  (when-let [auth-header (get-in req [:headers "authorization"])]
    (when (str/starts-with? auth-header "Bearer ")
      (subs auth-header 7))))

(defn- claims [req]
  (some-> (bearer-token req) verify-token))

(defn- dev-persona-param [req]
  (or (get-in req [:query-params "persona"])
      (get-in req [:params :persona])
      (get-in req [:params "persona"])))

(defn- acting-account
  "The id of the account a request acts as, or nil.

   In prod that is the :account claim of its Bearer token. In dev with
   :dangerously-skip-logins? nothing mints a token at all, so ?persona=<id>
   names the persona whose account to act as instead. That query parameter is
   this app's one dev-only auth path: it is read nowhere but here, and only
   while allow-skip-logins? is true — the same predicate every other handler
   uses, and one server/ensure-app-options refuses to let prod see."
  [req prod-mode?]
  (if (allow-skip-logins? prod-mode?)
    (some-> (dev-persona-param req) account-of-persona)
    (:account (claims req))))

(defn- acting-account-row
  "As `acting-account`, but nil unless the account is still there — a token
   outlives the row it names."
  [req prod-mode?]
  (some->> (acting-account req prod-mode?) (ds/get-account (ensure-conn))))

(defn- admin-request?
  "Whether a request may act as admin. In dev with :dangerously-skip-logins?
   there is no password anywhere, no token is minted, and every write is already
   open — so the admin reads are open there too rather than pretending to a
   protection the mode does not have. server/ensure-app-options refuses to start
   prod with that flag set."
  [req prod-mode?]
  (if (allow-skip-logins? prod-mode?)
    true
    (true? (:admin (claims req)))))

(defn- refuse-non-admin
  "401 without a credential at all, 403 with one that is simply not admin's —
   the same distinction wrap-auth draws for writes. A machine token counts as a
   credential even though it is not a JWT and `claims` cannot read it: it *is*
   authenticated, just not as admin, so it earns the 403."
  [req]
  (if (bearer-token req)
    {:status 403 :body {:success false :error "Admin only"}}
    {:status 401 :body {:success false :error "Authentication required"}}))

(defn- unauthenticated []
  {:status 401 :body {:success false :error "Authentication required"}})

;; ---------------------------------------------------------------------------
;; The gate on every machine-user management route
;;
;; wrap-auth waves any valid token through a URI that names no persona. That is
;; correct for POST /api/personas, whose handler mints under the caller's own
;; account. It is an escalation here: a machine user reaching these routes could
;; grant itself every persona in the account, mint itself a fresh token to
;; survive being rotated by its owner, or create a second machine user with
;; whatever grants it liked.
;;
;; So the check lives in the handler, and it is a *positive* one — the caller
;; must be a human account, not merely "not obviously a machine". A machine
;; token does not happen to unsign as a JWT, so acting-account would refuse it
;; anyway; that is a coincidence of representation and not something to build a
;; security property on.
;; ---------------------------------------------------------------------------

(defn- human-caller
  "The human account managing its machine users, or nil for anyone else — a
   machine token, an admin token (admin has no account, so there is nothing
   coherent for these routes to do on its behalf), or no token at all."
  [req prod-mode?]
  (when-not (machine-token? (bearer-token req))
    (acting-account-row req prod-mode?)))

(defn- refuse-non-human
  "401 with no credential at all, 403 with one that is simply not entitled —
   the same distinction wrap-auth draws for writes."
  [req]
  (if (bearer-token req)
    {:status 403 :body {:success false :error "Machine users are managed by their own account"}}
    (unauthenticated)))

(defn- owned-machine-user
  "The named machine user, but only when `account-id` is its parent. Nil
   otherwise, so one 404 covers both \"no such machine user\" and \"not yours\"
   and a caller cannot map another account's machine users by probing names.
   Tracker's own device (et.tr.server.user-handler/owned-machine-user)."
  [nm account-id]
  (let [m (ds/get-machine-user (ensure-conn) nm)]
    (when (and m (= account-id (:for-account-id m)))
      m)))

(defn- not-found []
  {:status 404 :body {:success false :error "Machine user not found"}})

(defn list-personas-handler
  "GET /api/personas — every persona as {:id :name}. This is the whole of what
   an anonymous reader learns: that these personas exist, never who holds them
   or which of them share a login. It used to hand out an :email per persona,
   which is exactly what the accounts split exists to stop. Public and
   unauthenticated, like nearly every read here. In dev with
   :dangerously-skip-logins? an extra :admin row is prepended so the persona
   switcher can offer it."
  [_req]
  (let [personas (ds/list-personas (ensure-conn))
        personas (if (dangerously-skip-logins?)
                   (cons {:id :admin :name "Admin"} personas)
                   personas)]
    {:status 200
     :body (serialize-response personas)}))

;; Ids the auth layer special-cases and so must never become a persona row.
;; "admin" logs in against ADMIN_PASSWORD rather than the database, so a row by
;; that id could be entered by an ordinary login and — before this guard —
;; minted a token the ownership check treated as admin's. The split closes that
;; structurally as well (a persona holds no password at all now), but the latch
;; stays: it costs one set membership and it is the thing that is easy to
;; re-open by accident.
(def ^:private reserved-persona-ids #{:admin})

(defn- generate-persona-id
  "An unused urbit-style two-word persona id, or nil when 100 draws all collide
   with an existing persona. Reserves nothing, so two callers can be handed the
   same id and whoever writes second is refused."
  []
  (let [existing-ids (set (map :id (ds/list-personas (ensure-conn))))]
    (loop [attempts 0]
      (let [candidate (keyword (urbit/generate-name))]
        (cond
          (not (contains? existing-ids candidate)) candidate
          (>= attempts 100) nil
          :else (recur (inc attempts)))))))

(defn me-handler
  "GET /api/me — who the caller is. Together with GET /api/accounts this is one
   of the two guarded reads in the app: an anonymous visitor learns that personas
   exist and nothing about who holds them, so the one read that pairs an email
   with a persona list has to prove it is that account's own. It answers three
   different shapes, for the three kinds of caller:

   - a **human**: {:email :personas [{:id :name :sort-order}]
     :machine-users [{:name :can-create :personas [<granted id>...]}]} — the
     account, its personas in the order it holds them, and its machine users
     with their grants. That last list is the checkbox grid of the profile page,
     as data. No token or token hash ever appears here; only minting and
     rotation ever return one.
   - a **machine user**: {:name :machine true :can-create :personas [<granted
     id>...]} — itself and nothing else. Never the account's roster, never the
     owner's email, never the personas it was not granted. A machine user has no
     browser session and no business seeing its siblings.
   - **admin**: {:admin true}. Admin has no account of its own.

   401 without a verifying token, when the account the token names is gone, or
   when a machine token has been rotated away. In dev with
   :dangerously-skip-logins? nothing mints a token, so ?persona=<id> names the
   persona whose account to answer for (and ?persona=admin the admin screen);
   that branch exists only in that mode."
  [prod-mode?]
  (fn [req]
    (let [dev? (allow-skip-logins? prod-mode?)
          token (bearer-token req)
          admin? (if dev?
                   (= "admin" (dev-persona-param req))
                   (true? (:admin (claims req))))]
      (cond
        admin?
        {:status 200 :body {:admin true}}

        ;; A machine token is answered about itself, before the human branch,
        ;; because acting-account would refuse it anyway and the 401 would be
        ;; true but useless — it *is* authenticated, just not as a person.
        (machine-token? token)
        (if-let [m (machine-user-for-token token)]
          {:status 200
           :body (serialize-response
                  {:name (:name m)
                   :machine true
                   :can-create (:can-create-personas? m)
                   :personas (ds/granted-personas (ensure-conn) (:id m))})}
          (unauthenticated))

        :else
        (if-let [account (acting-account-row req prod-mode?)]
          {:status 200
           :body (serialize-response
                  {:email (:email account)
                   :personas (ds/list-personas-for-account (ensure-conn) (:id account))
                   :machine-users (mapv (fn [m]
                                          {:name (:name m)
                                           :can-create (:can-create-personas? m)
                                           :personas (:personas m)})
                                        (ds/list-machine-users (ensure-conn) (:id account)))})}
          (unauthenticated))))))

(defn- creating-account
  "Which account a POST /api/personas mints under, and whether the caller may at
   all. Three callers now:

   - a **human** — its own account, as before
   - a **machine user with can_create_personas** — its *parent's* account. A
     machine user is a credential, not a place to hang content on, so what it
     creates belongs to the human it works for
   - a **machine user without it** — :forbidden

   nil means no usable credential at all."
  [req prod-mode?]
  (let [token (bearer-token req)]
    (if (machine-token? token)
      (when-let [m (machine-user-for-token token)]
        (if (:can-create-personas? m)
          {:account-id (:for-account-id m) :machine m}
          :forbidden))
      (when-let [account (acting-account-row req prod-mode?)]
        {:account-id (:id account)}))))

(defn add-persona-handler
  "POST /api/personas — mint a persona. Takes {:name :id?}; :id defaults to a
   generated urbit-style two-word name and is the public address, so it must be
   free across *all* accounts, not merely this one. The new persona lands last
   in its account's order. Answers 201 {:success true :id ...}.

   A human mints under its own account. A machine user with
   `can_create_personas` mints under its **parent's** account and is granted
   write on what it just made, so it may use it from that moment on; without
   that permission it gets a 403. Neither caller can name another account — the
   body has no field for one.

   401 without a credential; 400 when the id is taken or reserved.

   The self-grant is a second statement rather than part of one transaction:
   this codebase opens none anywhere, and the order is the safe one. A persona
   created but not yet granted is one the owner can grant by hand; a grant
   written first could name a persona that never appeared."
  [prod-mode?]
  (fn [req]
    (let [{:keys [id name]} (:body req)
          target (creating-account req prod-mode?)]
      (cond
        (= :forbidden target)
        {:status 403 :body {:success false :error "Not allowed to create personas"}}

        (nil? target)
        (unauthenticated)

        :else
        (let [id-kw (or (when (seq id) (str->keyword id)) (generate-persona-id))]
          (cond
            (nil? id-kw)
            {:status 500 :body {:success false :error "Could not generate unique ID"}}

            (contains? reserved-persona-ids id-kw)
            {:status 400 :body {:success false :error "Reserved persona id"}}

            (not (ds/add-persona (ensure-conn) (:account-id target) id-kw name))
            {:status 400 :body {:success false :error "Persona already exists"}}

            :else
            (do
              (when-let [machine (:machine target)]
                (ds/grant-persona (ensure-conn) (:id machine) id-kw))
              {:status 201 :body {:success true :id (clojure.core/name id-kw)}})))))))

(defn delete-persona-handler
  "DELETE /api/personas/:name — destroy a persona and every version of every
   identity under it. Permanent, and the first endpoint in an app that has never
   deleted anything: there is no history left afterwards and no undo.

   The body must carry {:confirm \"<persona-id>\"} equal to the URI's id — the
   hand-typed confirmation is enforced here and not only in the browser's
   dialog, because the browser is not the authority on anything. It is the whole
   guard, and it is enough: an account's **last** persona goes the same way as
   any other. Zero personas is a legitimate state, not a degenerate one — an
   account is an email and a password, and one holding none lands on its profile
   page, which is where it makes another.

   Answers {:success true}. 400 on a missing or mismatched :confirm, 404 when
   there is no such persona. In prod mode the token's account must hold the
   persona, or be admin's — 401 without a token, 403 with another account's
   (see wrap-auth).

   **A machine user is refused regardless of its grants**, including on a
   persona it created itself. A grant is permission to *write*, and this feature
   is about writing; destroying a public address and every identity under it
   stays with the human whose account it is. That is a judgement call rather
   than something the owner asked for, and it is cheap to reverse."
  [req]
  (let [persona-id (str->keyword (get-in req [:params :name]))
        confirm (get-in req [:body :confirm])
        persona (ds/get-persona-by-id (ensure-conn) persona-id)]
    (cond
      (machine-token? (bearer-token req))
      {:status 403 :body {:success false :error "Personas are removed by their own account"}}

      (nil? persona)
      {:status 404 :body {:success false :error "Persona not found"}}

      (not= confirm (clojure.core/name persona-id))
      {:status 400 :body {:success false :error "Confirmation does not match the persona id"}}

      :else
      (do (ds/delete-persona (ensure-conn) persona-id)
          {:status 200 :body {:success true}}))))

(defn update-persona-handler
  "PUT /api/personas/:name — change a persona's display :name, which since the
   accounts split is the only thing a persona owns that can be edited: the email
   and the password moved to the account. In prod mode the token's account must
   hold :name, or be admin's — 401 without a token, 403 with another account's.
   404 when the persona does not exist."
  [req]
  (let [persona-id (str->keyword (get-in req [:params :name]))
        {:keys [name]} (:body req)
        result (ds/update-persona (ensure-conn) persona-id (cond-> {} name (assoc :name name)))]
    (if (nil? result)
      {:status 404 :body {:success false :error "Persona not found"}}
      {:status 200 :body {:success true}})))

(defn add-account-handler
  "POST /api/accounts — create an account: an email and a password, and nothing
   else. Takes {:email :password}; the password is stored as a bcrypt hash and
   may be omitted, which leaves the account with no way to log in.

   It used to mint a first persona in the same call. It does not any more —
   zero personas is a legitimate state, and an account holding none lands on its
   profile page and makes its own. So a :name or an :id in the body is not a
   thing here, and the generated-id, reserved-id and persona-taken checks went
   with them; the email checks are what remain.

   Answers 201 {:success true :id <account-id>}. That id is the only way to give
   a new account its first persona from outside it: POST /api/personas honours
   an :account_id for an admin caller, which is what the seed script and the
   admin Settings form use.

   Admin only: 401 without a credential, 403 with an ordinary account's or a
   machine token — except in dev with :dangerously-skip-logins?, where nothing
   is guarded at all and the seed script uses this. 400 when the email is
   missing or already taken."
  [prod-mode?]
  (fn [req]
    (let [{:keys [email password]} (:body req)]
      (cond
        (not (admin-request? req prod-mode?))
        (refuse-non-admin req)

        (not (seq email))
        {:status 400 :body {:success false :error "Email is required"}}

        :else
        (let [password-hash (when (seq password) (hashers/derive password))]
          (if-let [account-id (ds/add-account (ensure-conn) email password-hash)]
            {:status 201 :body {:success true :id account-id}}
            {:status 400 :body {:success false :error "Email already exists"}}))))))

(defn add-machine-user-handler
  "POST /api/machine-users — create a machine user under the calling account and
   answer its first token. Takes {:name :can_create?}. The name is unique across
   all accounts, not per account: it is no longer a login identifier, but it is
   how these get referred to in secrets.yaml and in conversation.

   Answers 201 {:success true :name ... :token \"pmu_...\"}. **The token is in
   that body and nowhere else, ever** — only its SHA-256 is kept, so the caller
   has to show it to the user at once; losing it means rotating.

   Managed by the owning human account only: 401 without a credential, 403 for a
   machine token or an admin token. A machine user calling this could otherwise
   mint a fresh identity with whatever grants it liked. 400 when the name is
   blank or already taken."
  [prod-mode?]
  (fn [req]
    (if-let [account (human-caller req prod-mode?)]
      (let [{:keys [name can_create]} (:body req)]
        (cond
          (not (seq name))
          {:status 400 :body {:success false :error "Name is required"}}

          (not (ds/add-machine-user (ensure-conn) (:id account) name
                                    {:can-create-personas? (boolean can_create)}))
          {:status 400 :body {:success false :error "Machine user already exists"}}

          :else
          {:status 201 :body {:success true
                              :name name
                              :token (mint-machine-token! name)}}))
      (refuse-non-human req))))

(defn rotate-machine-token-handler
  "POST /api/machine-users/:name/token — issue a new token for a machine user
   and answer it, once. Whatever was using the previous token stops working the
   moment this returns: there is one column holding the hash, so writing a new
   one is the revocation.

   Answers 200 {:success true :token \"pmu_...\"}. Owning human account only —
   401 without a credential, 403 for a machine or admin token, 404 when the name
   is not one of this account's. **A machine user must never reach this route**:
   it is the one that would let it outlive being revoked by its owner.
   404 rather than 403 for another account's machine user, so probing names
   tells a caller nothing."
  [prod-mode?]
  (fn [req]
    (if-let [account (human-caller req prod-mode?)]
      (let [nm (get-in req [:params :name])]
        (if (owned-machine-user nm (:id account))
          {:status 200 :body {:success true :token (mint-machine-token! nm)}}
          (not-found)))
      (refuse-non-human req))))

(defn update-machine-user-handler
  "PUT /api/machine-users/:name — set which personas a machine user may write,
   and whether it may create them. Takes {:personas [<persona-id>...]
   :can_create?}; :personas is the grant list **in full** rather than a patch,
   because that is what the checkbox grid on the profile page holds. A key
   absent from the body is left alone.

   Every named persona must belong to this account. One that does not — another
   account's, or none at all — fails the whole request with a 400 rather than
   being dropped from the list, because a grant the caller believes it made and
   did not is worse than an error. Otherwise a human could hand its machine user
   write on a stranger's persona.

   Answers 200 {:success true}. Owning human account only: 401 without a
   credential, 403 for a machine token (which would otherwise grant itself the
   rest of the account) or an admin token, 404 for a name that is not this
   account's."
  [prod-mode?]
  (fn [req]
    (if-let [account (human-caller req prod-mode?)]
      (let [nm (get-in req [:params :name])
            {:keys [personas can_create] :as body} (:body req)]
        (if-let [machine (owned-machine-user nm (:id account))]
          (let [own (set (map :id (ds/list-personas-for-account (ensure-conn) (:id account))))
                asked (map str->keyword (or personas []))
                strangers (remove own asked)]
            (if (and (contains? body :personas) (seq strangers))
              {:status 400 :body {:success false
                                  :error "Not this account's personas"
                                  :personas (mapv clojure.core/name strangers)}}
              (do
                (when (contains? body :can_create)
                  (ds/update-machine-user (ensure-conn) (:id machine)
                                          {:can-create-personas? (boolean can_create)}))
                (when (contains? body :personas)
                  (let [wanted (set asked)
                        held (set (ds/granted-personas (ensure-conn) (:id machine)))]
                    (doseq [p (remove held wanted)] (ds/grant-persona (ensure-conn) (:id machine) p))
                    (doseq [p (remove wanted held)] (ds/revoke-persona (ensure-conn) (:id machine) p))))
                {:status 200 :body {:success true}})))
          (not-found)))
      (refuse-non-human req))))

(defn delete-machine-user-handler
  "DELETE /api/machine-users/:name — remove a machine user and every grant it
   held. Its token dies with the row, so whatever was using it stops at once. No
   hand-typed confirmation, unlike removing a persona: a machine user holds no
   content of its own, and re-creating one is a name and a click.

   Answers 200 {:success true}. Owning human account only: 401 without a
   credential, 403 for a machine token (a machine user deleting a *sibling*
   would be the escalation here) or an admin token, 404 for a name that is not
   this account's."
  [prod-mode?]
  (fn [req]
    (if-let [account (human-caller req prod-mode?)]
      (let [nm (get-in req [:params :name])]
        (if-let [machine (owned-machine-user nm (:id account))]
          (do (ds/delete-machine-user (ensure-conn) (:id machine))
              {:status 200 :body {:success true}})
          (not-found)))
      (refuse-non-human req))))

(defn list-accounts-handler
  "GET /api/accounts — every account with its :email and its :personas
   [{:id :name :sort-order}]: the admin Settings listing, and the only read in
   the app that ties emails to personas in bulk. Admin only, and guarded here
   rather than in wrap-auth for the same reason as /api/me — 401 without a
   token, 403 with an ordinary account's. In dev with :dangerously-skip-logins?
   it is open, like every other write in that mode."
  [prod-mode?]
  (fn [req]
    (if-not (admin-request? req prod-mode?)
      (refuse-non-admin req)
      {:status 200 :body (serialize-response (ds/list-accounts (ensure-conn)))})))

(defn list-identities-handler
  "GET /api/personas/:name/identities — the persona's identities at their latest
   version: [{:identity :name :text}]. Public, like every read here. 404 when
   the persona does not exist."
  [req]
  (let [persona-name (str->keyword (get-in req [:params :name]))
        persona (ds/get-persona-by-id (ensure-conn) persona-name)]
    (if persona
      {:status 200 :body (serialize-response (ds/list-identities (ensure-conn) persona))}
      {:status 404 :body {:error "Persona not found"}})))

(defn list-recent-identities-handler
  "GET /api/personas/:name/identities/recent — a page of the persona's identities,
   most recently versioned first: {:items [{:identity :name :modified-at}]
   :has-more bool}. Query params ?limit (default 5) and ?offset (default 0).
   Public, like every read here. 404 when the persona does not exist."
  [req]
  (let [persona-name (str->keyword (get-in req [:params :name]))
        limit (or (some-> (get-in req [:query-params "limit"]) Integer/parseInt) 5)
        offset (or (some-> (get-in req [:query-params "offset"]) Integer/parseInt) 0)
        persona (ds/get-persona-by-id (ensure-conn) persona-name)]
    (if persona
      {:status 200 :body (serialize-response (ds/list-recent-identities (ensure-conn) persona limit offset))}
      {:status 404 :body {:error "Persona not found"}})))

(defn get-identity-handler
  "GET /api/personas/:name/identities/:id — one identity at its latest version:
   {:identity :name :text}. Public, like every read here. 404 when either the
   persona or the identity does not exist."
  [req]
  (let [persona-name (str->keyword (get-in req [:params :name]))
        identity-id (str->keyword (get-in req [:params :id]))
        persona (ds/get-persona-by-id (ensure-conn) persona-name)]
    (if persona
      (if-let [identity (ds/get-identity (ensure-conn) persona identity-id)]
        {:status 200 :body (serialize-response identity)}
        {:status 404 :body {:error "Identity not found"}})
      {:status 404 :body {:error "Persona not found"}})))

(defn add-identity-handler
  "POST /api/personas/:name/identities — create an identity. Takes
   {:name :text :id? :valid_from?}; :id defaults to a generated urbit-style
   two-word name and :valid_from (ISO-8601) to now. Answers 201
   {:success true :id ...}. In prod mode the token must be :name's own or
   admin's — 401 without a token, 403 with another persona's. 409 when :id is
   already in use under this persona, 404 when the persona does not exist."
  [req]
  (let [persona-name (str->keyword (get-in req [:params :name]))
        {:keys [id name text valid_from]} (:body req)
        persona (ds/get-persona-by-id (ensure-conn) persona-name)
        opts (cond-> {}
               valid_from (assoc :valid-from (Instant/parse valid_from))
               id (assoc :id (keyword id)))]
    (if persona
      (let [generated-id (ds/add-identity (ensure-conn) persona name text (when (seq opts) opts))]
        (if (false? generated-id)
          {:status 409 :body {:error "Identity with this ID already exists"}}
          {:status 201 :body {:success true :id (clojure.core/name generated-id)}}))
      {:status 404 :body {:error "Persona not found"}})))

(defn update-identity-handler
  "PUT /api/personas/:name/identities/:id — append a new version of an identity.
   Takes {:name :text :valid_from? :relation_adds? :relation_removes?}, where
   :name and :text are the new version's values in full rather than a patch.
   :relation_adds are target identity ids, :relation_removes relation ids of the
   form \"source/target\"; relations neither added nor removed carry forward.
   Answers {:success true :valid-from <ISO-8601>}. There is no delete: an :id
   with no versions yet gets its first one here instead of a 404. In prod mode
   the token must be :name's own or admin's — 401 without a token, 403 with
   another persona's. 404 when the persona does not exist."
  [req]
  (let [persona-name (str->keyword (get-in req [:params :name]))
        identity-id (str->keyword (get-in req [:params :id]))
        {:keys [name text valid_from relation_adds relation_removes]} (:body req)
        persona (ds/get-persona-by-id (ensure-conn) persona-name)
        ;; One timestamp for the version and every relation change it commits, so
        ;; relations share the version's timeline.
        t (if valid_from (Instant/parse valid_from) (Instant/now))]
    (if persona
      (do
        (ds/save-identity-version (ensure-conn) persona identity-id name text
                                  {:valid-from t
                                   :relation-adds (or relation_adds [])
                                   :relation-removes (or relation_removes [])})
        {:status 200 :body {:success true :valid-from (str t)}})
      {:status 404 :body {:error "Persona not found"}})))

(defn get-identity-at-handler
  "GET /api/personas/:name/identities/:id/at — the version of an identity in
   effect at ?time (ISO-8601, required): {:identity :name :text}, or an empty
   body when the identity had no version yet at that instant. Public, like every
   read here. 404 when the persona does not exist; a missing or unparseable
   ?time comes back 500."
  [req]
  (let [persona-name (str->keyword (get-in req [:params :name]))
        identity-id (str->keyword (get-in req [:params :id]))
        time-str (or (get-in req [:params :time])
                     (get-in req [:params "time"])
                     (get-in req [:query-params "time"]))
        at (Instant/parse time-str)
        persona (ds/get-persona-by-id (ensure-conn) persona-name)]
    (if persona
      (let [result (ds/get-identity-at (ensure-conn) persona identity-id at)]
        {:status 200 :body (serialize-response result)})
      {:status 404 :body {:error "Persona not found"}})))

(defn get-identity-history-handler
  "GET /api/personas/:name/identities/:id/history — every version of an identity,
   oldest first: [{:identity :name :text :valid-from}]. This is what the version
   slider walks. Public, like every read here. 404 when the persona does not
   exist; an unknown identity is an empty list rather than a 404."
  [req]
  (let [persona-name (str->keyword (get-in req [:params :name]))
        identity-id (str->keyword (get-in req [:params :id]))
        persona (ds/get-persona-by-id (ensure-conn) persona-name)]
    (if persona
      (let [history (ds/get-identity-history (ensure-conn) persona identity-id)]
        {:status 200 :body (serialize-response history)})
      {:status 404 :body {:error "Persona not found"}})))

(defn list-relations-handler
  "GET /api/personas/:name/identities/:id/relations — the identity's outgoing
   relations as of ?time (ISO-8601, optional; its latest version when omitted):
   [{:id \"source/target\" :target :target-name :description}]. Relations live on
   the identity version, so they time-travel with it. Public, like every read
   here. 404 when the persona does not exist."
  [req]
  (let [persona-name (str->keyword (get-in req [:params :name]))
        identity-id (str->keyword (get-in req [:params :id]))
        time-str (or (get-in req [:params :time])
                     (get-in req [:params "time"])
                     (get-in req [:query-params "time"]))
        at (when time-str (Instant/parse time-str))
        persona (ds/get-persona-by-id (ensure-conn) persona-name)]
    (if persona
      (let [relations (ds/list-relations (ensure-conn) persona identity-id (when at {:at at}))]
        {:status 200 :body (serialize-response relations)})
      {:status 404 :body {:error "Persona not found"}})))

;; Relations are no longer mutated through dedicated endpoints — they are
;; committed as part of an identity version via update-identity-handler
;; (relation_adds / relation_removes) and read back via list-relations-handler.

(defn search-identities-handler
  "GET /api/personas/:name/identities/search — the persona's identities whose
   name contains ?q, case-insensitively; an absent or empty q matches all of
   them. With ?valid_at (ISO-8601) each identity is matched and returned as of
   that instant instead of at its latest version, which is what backs the
   read-only \"fixed\" exploring mode. Public, like every read here. 404 when the
   persona does not exist."
  [req]
  (let [persona-name (str->keyword (get-in req [:params :name]))
        query (or (get-in req [:params :q])
                  (get-in req [:params "q"])
                  (get-in req [:query-params "q"])
                  "")
        valid-at-str (or (get-in req [:params :valid_at])
                         (get-in req [:params "valid_at"])
                         (get-in req [:query-params "valid_at"]))
        at (when valid-at-str (Instant/parse valid-at-str))
        persona (ds/get-persona-by-id (ensure-conn) persona-name)]
    (if persona
      (let [results (ds/search-identities (ensure-conn) persona query (when at {:at at}))]
        {:status 200 :body (serialize-response results)})
      {:status 404 :body {:error "Persona not found"}})))

(defn persona-login-handler
  "POST /api/auth/login — exchange credentials for a JWT. Takes
   {:username ... :password ...}: one identifier field, and a human puts their
   **email** in it. Logging in by persona id is gone — a persona is a public
   address, not a login — and the field is `username` because five of the six
   plurama apps already take {username, password}. The username \"admin\" is
   checked against ADMIN_PASSWORD rather than the database.

   A machine user never reaches this route. It holds no password and
   authenticates by bearer token instead, so its name is answered with the same
   flat 401 as a name nobody has ever used — the login route must not confirm
   that a machine user exists.

   Answers {:success true :token ...}; the token carries {:account <account-id>}
   and is what every write wants back as `Authorization: Bearer`. Public,
   necessarily. 401 on any bad credential, without saying whether the account
   was unknown or the password wrong. In dev with :dangerously-skip-logins? it
   answers success and no token, since nothing is guarded there either."
  [prod-mode?]
  (fn [req]
    (let [{:keys [username password]} (:body req)]
      (if (allow-skip-logins? prod-mode?)
        {:status 200 :body {:success true :message "No password required"}}
        (if (= (str->keyword username) :admin)
          (let [admin-password (if prod-mode?
                                 (System/getenv "ADMIN_PASSWORD")
                                 "admin")]
            (if (and admin-password (= password admin-password))
              {:status 200 :body {:success true :token (create-admin-token)}}
              {:status 401 :body {:success false :error "Invalid credentials"}}))
          ;; get-account-by-email answers for humans only, so a machine user's
          ;; name finds nothing here and falls through to the same 401 as an
          ;; unknown one — no branch of its own, nothing to tell apart by timing.
          (let [account (when (seq username)
                          (ds/get-account-by-email (ensure-conn) username))
                stored-hash (when account
                              (ds/get-account-password-hash (ensure-conn) (:id account)))]
            (if (and stored-hash (string? password) (hashers/check password stored-hash))
              {:status 200 :body {:success true :token (create-token (:id account))}}
              {:status 401 :body {:success false :error "Invalid credentials"}})))))))

(defn password-required-handler
  "GET /api/auth/required — whether this instance asks for a password at all:
   {:required false} only in dev with :dangerously-skip-logins?, true otherwise.
   Public, necessarily — the login screen asks before anyone is authenticated."
  [prod-mode?]
  (fn [_req]
    {:status 200 :body {:required (not (allow-skip-logins? prod-mode?))}}))

(defn generate-id-handler
  "GET /api/generate-id — propose an unused urbit-style two-word persona id for
   the Settings and profile forms: {:id \"...\"}. Public, like nearly every read
   here. It reserves nothing, so two callers can be handed the same id and
   whoever writes second gets the 400. 500 if 100 draws all collide with an
   existing persona."
  [_req]
  (if-let [id (generate-persona-id)]
    {:status 200 :body {:id (name id)}}
    {:status 500 :body {:error "Could not generate unique ID"}}))

(defn- get-client-ip [request]
  (or (get-in request [:headers "fly-client-ip"])
      (get-in request [:headers "x-real-ip"])
      (:remote-addr request)
      "unknown"))

(defn- parse-int-from-env-var [env-var default]
  (if-let [s (System/getenv env-var)]
    (try (Integer/parseInt s)
         (catch Exception _e
           (tel/log! :warn ["Failed to parse int from" env-var "=" s "- using default" default])
           default))
    default))

(defn wrap-rate-limit [handler]
  (let [limiter (rate-limit/create-limiter)
        global-max (parse-int-from-env-var "GLOBAL_RATE_LIMIT" 180)
        ip-max (parse-int-from-env-var "PER_IP_RATE_LIMIT" 60)
        window-ms 60000]
    (fn [request]
      (let [now-ms (System/currentTimeMillis)
            client-ip (get-client-ip request)
            ip-ok? (rate-limit/check-ip-allowed limiter client-ip ip-max window-ms now-ms)
            global-ok? (when ip-ok?
                         (rate-limit/check-global-allowed limiter global-max window-ms now-ms))]
        (cond
          (not ip-ok?)
          (do
            (when (rate-limit/should-warn-ip? limiter client-ip now-ms)
              (tel/log! :warn ["Rate limit exceeded for IP:" client-ip]))
            {:status 429 :headers {"Content-Length" "0"} :body ""})

          (not global-ok?)
          (do
            (when (rate-limit/should-warn-global? limiter now-ms)
              (tel/log! :warn ["Global rate limit exceeded, triggered by IP:" client-ip]))
            {:status 429 :headers {"Content-Length" "0"} :body ""})

          :else
          (handler request))))))
