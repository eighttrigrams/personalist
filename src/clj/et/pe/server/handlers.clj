(ns et.pe.server.handlers
  (:require [et.pe.ds :as ds]
            [et.pe.urbit :as urbit]
            [et.pe.middleware.rate-limit :as rate-limit]
            [clojure.string :as str]
            [clojure.walk]
            [buddy.hashers :as hashers]
            [buddy.sign.jwt :as jwt]
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

(defn- dangerously-skip-logins? []
  (true? (get-in @config [:devel :dangerously-skip-logins?])))

(defn account-of-persona
  "The id of the account holding `persona-id`, or nil when no persona has that
   id. This is what wrap-auth's ownership check became since accounts sit above
   personas: a lookup rather than a string comparison. wrap-auth runs above the
   routes and holds no connection of its own, so it asks here."
  [persona-id]
  (:account-id (ds/get-persona-by-id (ensure-conn) (str->keyword persona-id))))

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
  "401 without a verifying token, 403 with one that is not admin's — the same
   distinction wrap-auth draws for writes."
  [req]
  (if (claims req)
    {:status 403 :body {:success false :error "Admin only"}}
    {:status 401 :body {:success false :error "Authentication required"}}))

(defn- unauthenticated []
  {:status 401 :body {:success false :error "Authentication required"}})

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
  "GET /api/me — the account behind the request: {:email :personas [{:id :name
   :sort-order}]}, its personas in the order the account holds them. Together
   with GET /api/accounts this is one of the two guarded reads in the app: an
   anonymous visitor learns that personas exist and nothing about who holds
   them, so the one read that pairs an email with a persona list has to prove
   it is that account's own. 401 without a verifying token, or when the account
   the token names is gone. An admin token answers {:admin true} — admin has no
   account of its own. In dev with :dangerously-skip-logins? nothing mints a
   token, so ?persona=<id> names the persona whose account to answer for (and
   ?persona=admin the admin screen); that branch exists only in that mode."
  [prod-mode?]
  (fn [req]
    (let [dev? (allow-skip-logins? prod-mode?)
          admin? (if dev?
                   (= "admin" (dev-persona-param req))
                   (true? (:admin (claims req))))]
      (if admin?
        {:status 200 :body {:admin true}}
        (if-let [account (acting-account-row req prod-mode?)]
          {:status 200
           :body (serialize-response
                  {:email (:email account)
                   :personas (ds/list-personas-for-account (ensure-conn) (:id account))})}
          (unauthenticated))))))

(defn add-persona-handler
  "POST /api/personas — mint a persona under the requesting account. Takes
   {:name :id?}; :id defaults to a generated urbit-style two-word name and is
   the public address, so it must be free across *all* accounts, not merely
   this one. The new persona lands last in its account's order. Answers 201
   {:success true :id ...}. The account is the token's own and the body cannot
   name another, so there is no way to mint a persona into somebody else's
   login. 401 without a token; 400 when the id is taken or reserved."
  [prod-mode?]
  (fn [req]
    (let [{:keys [id name]} (:body req)]
      (if-let [account (acting-account-row req prod-mode?)]
        (let [id-kw (or (when (seq id) (str->keyword id)) (generate-persona-id))]
          (cond
            (nil? id-kw)
            {:status 500 :body {:success false :error "Could not generate unique ID"}}

            (contains? reserved-persona-ids id-kw)
            {:status 400 :body {:success false :error "Reserved persona id"}}

            (not (ds/add-persona (ensure-conn) (:id account) id-kw name))
            {:status 400 :body {:success false :error "Persona already exists"}}

            :else
            {:status 201 :body {:success true :id (clojure.core/name id-kw)}}))
        (unauthenticated)))))

(defn delete-persona-handler
  "DELETE /api/personas/:name — destroy a persona and every version of every
   identity under it. Permanent, and the first endpoint in an app that has never
   deleted anything: there is no history left afterwards and no undo.

   The body must carry {:confirm \"<persona-id>\"} equal to the URI's id — the
   hand-typed confirmation is enforced here and not only in the browser's
   dialog, because the browser is not the authority on anything. Answers
   {:success true}. 400 on a missing or mismatched :confirm, 404 when there is
   no such persona, 409 on an account's last persona, since an account with no
   persona is a login that leads nowhere. In prod mode the token's account must
   hold the persona, or be admin's — 401 without a token, 403 with another
   account's (see wrap-auth)."
  [req]
  (let [persona-id (str->keyword (get-in req [:params :name]))
        confirm (get-in req [:body :confirm])
        persona (ds/get-persona-by-id (ensure-conn) persona-id)]
    (cond
      (nil? persona)
      {:status 404 :body {:success false :error "Persona not found"}}

      (not= confirm (clojure.core/name persona-id))
      {:status 400 :body {:success false :error "Confirmation does not match the persona id"}}

      (< (count (ds/list-personas-for-account (ensure-conn) (:account-id persona))) 2)
      {:status 409 :body {:success false :error "An account must keep at least one persona"}}

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
  "POST /api/accounts — create an account and its first persona in one call.
   Takes {:email :password :name :id?}; the password is stored as a bcrypt hash
   and may be omitted, which leaves the account with no way to log in, and :id
   defaults to a generated urbit-style two-word name. Answers 201
   {:success true :id <persona-id>}. Admin only: 401 without a token, 403 with
   an ordinary account's — except in dev with :dangerously-skip-logins?, where
   nothing is guarded at all and the seed script uses this. 400 when the email
   or the persona id is already taken,
   or the id is the reserved `admin`. The persona id is checked before the
   account is minted, so a refusal never leaves an email spent on nothing."
  [prod-mode?]
  (fn [req]
    (let [{:keys [id email password name]} (:body req)]
      (if-not (admin-request? req prod-mode?)
        (refuse-non-admin req)
        (let [id-kw (or (when (seq id) (str->keyword id)) (generate-persona-id))]
          (cond
            (nil? id-kw)
            {:status 500 :body {:success false :error "Could not generate unique ID"}}

            (contains? reserved-persona-ids id-kw)
            {:status 400 :body {:success false :error "Reserved persona id"}}

            (not (seq email))
            {:status 400 :body {:success false :error "Email is required"}}

            (ds/get-persona-by-id (ensure-conn) id-kw)
            {:status 400 :body {:success false :error "Persona already exists"}}

            :else
            (let [password-hash (when (seq password) (hashers/derive password))
                  account (ds/add-account (ensure-conn) email password-hash)]
              (if account
                (do (ds/add-persona (ensure-conn) account id-kw name)
                    {:status 201 :body {:success true :id (clojure.core/name id-kw)}})
                {:status 400 :body {:success false :error "Email already exists"}}))))))))

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
