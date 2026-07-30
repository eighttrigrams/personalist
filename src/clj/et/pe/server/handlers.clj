(ns et.pe.server.handlers
  (:require [et.pe.ds :as ds]
            [et.pe.urbit :as urbit]
            [et.pe.middleware.rate-limit :as rate-limit]
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

(defn- create-token [persona-name]
  (jwt/sign {:persona (name persona-name)} (jwt-secret)))

;; The admin exemption (server/owns-persona?) keys on this :admin claim, never on
;; the string "admin", so it cannot be minted by anyone who can create a persona
;; row: only the ADMIN_PASSWORD login below calls this. Folding it back into
;; create-token would re-open the admin-row escalation.
(defn- create-admin-token []
  (jwt/sign {:persona "admin" :admin true} (jwt-secret)))

(defn- verify-token [token]
  (try
    (jwt/unsign token (jwt-secret))
    (catch Exception _ nil)))

(defn verify-token-check [token]
  (verify-token token))

(defn- dangerously-skip-logins? []
  (true? (get-in @config [:devel :dangerously-skip-logins?])))

(defn list-personas-handler
  "GET /api/personas — every persona as {:id :email :name}, the :email included.
   Public and unauthenticated, like every read here. In dev with
   :dangerously-skip-logins? an extra :admin row is prepended so the persona
   switcher can offer it."
  [_req]
  (let [personas (ds/list-personas (ensure-conn))
        personas (if (dangerously-skip-logins?)
                   (cons {:id :admin :email nil :name "Admin"} personas)
                   personas)]
    {:status 200
     :body (serialize-response personas)}))

;; Ids the auth layer special-cases and so must never become a persona row.
;; "admin" logs in against ADMIN_PASSWORD rather than the persona table, so a row
;; by that id can be entered by email login and — before this guard — minted a
;; token the ownership check treated as admin's.
(def ^:private reserved-persona-ids #{:admin})

(defn add-persona-handler
  "POST /api/personas — mint a persona. Takes {:id :email :password :name}; the
   password is stored as a bcrypt hash and may be omitted, which leaves the
   persona with no way to log in. Answers 201 {:success true}. In prod mode any
   valid token will do, whosever it is — 401 without one. 400 when the id or the
   email is already taken. The id `admin` is reserved and refused with 400."
  [req]
  (let [{:keys [id email password name]} (:body req)
        id-kw (str->keyword id)]
    (if (contains? reserved-persona-ids id-kw)
      {:status 400 :body {:success false :error "Reserved persona id"}}
      (let [password-hash (when (seq password) (hashers/derive password))
            result (ds/add-persona (ensure-conn) id-kw email password-hash name)]
        (if result
          {:status 201 :body {:success true}}
          {:status 400 :body {:success false :error "Persona already exists"}})))))

(defn update-persona-handler
  "PUT /api/personas/:name — change a persona's :email and/or :name; a key absent
   from the body is left alone. In prod mode the token must be :name's own or
   admin's — 401 without a token, 403 with another persona's. 404 when the
   persona does not exist, 400 when the email belongs to someone else."
  [req]
  (let [persona-id (str->keyword (get-in req [:params :name]))
        {:keys [email name]} (:body req)
        updates (cond-> {}
                  email (assoc :email email)
                  name (assoc :name name))
        result (ds/update-persona (ensure-conn) persona-id updates)]
    (cond
      (nil? result) {:status 404 :body {:success false :error "Persona not found"}}
      (:error result) {:status 400 :body {:success false :error "Email already exists"}}
      :else {:status 200 :body {:success true}})))

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
   {:id <persona-id> :password ...} or {:email ... :password ...}, and :id
   \"admin\" is checked against ADMIN_PASSWORD rather than the persona table.
   Answers {:success true :token ...}; the token carries {:persona <id>} and is
   what every write wants back as `Authorization: Bearer`. Public, necessarily.
   401 on any bad credential, without saying whether the persona was unknown or
   the password wrong. In dev with :dangerously-skip-logins? it answers success
   and no token, since nothing is guarded there either."
  [prod-mode?]
  (fn [req]
    (let [{:keys [id email password]} (:body req)]
      (if (allow-skip-logins? prod-mode?)
        {:status 200 :body {:success true :message "No password required"}}
        (if (= (str->keyword id) :admin)
          (let [admin-password (if prod-mode?
                                 (System/getenv "ADMIN_PASSWORD")
                                 "admin")]
            (if (= password admin-password)
              {:status 200 :body {:success true :token (create-admin-token)}}
              {:status 401 :body {:success false :error "Invalid credentials"}}))
          (let [persona (cond
                          (seq id) (ds/get-persona-by-id (ensure-conn) (str->keyword id))
                          (seq email) (ds/get-persona-by-email (ensure-conn) email)
                          :else nil)]
            (if (nil? persona)
              {:status 401 :body {:success false :error "Invalid credentials"}}
              (let [persona-id (str->keyword (:id persona))
                    stored-hash (ds/get-persona-password-hash (ensure-conn) persona-id)]
                (if (and stored-hash (hashers/check password stored-hash))
                  {:status 200 :body {:success true :token (create-token persona-id)}}
                  {:status 401 :body {:success false :error "Invalid credentials"}})))))))))

(defn password-required-handler
  "GET /api/auth/required — whether this instance asks for a password at all:
   {:required false} only in dev with :dangerously-skip-logins?, true otherwise.
   Public, necessarily — the login screen asks before anyone is authenticated."
  [prod-mode?]
  (fn [_req]
    {:status 200 :body {:required (not (allow-skip-logins? prod-mode?))}}))

(defn generate-id-handler
  "GET /api/generate-id — propose an unused urbit-style two-word persona id for
   the Settings form: {:id \"...\"}. Public, like every read here. It reserves
   nothing, so two callers can be handed the same id and whoever POSTs second
   gets the 400. 500 if 100 draws all collide with an existing persona."
  [_req]
  (let [existing-ids (set (map :id (ds/list-personas (ensure-conn))))]
    (loop [attempts 0]
      (let [candidate (urbit/generate-name)]
        (cond
          (not (contains? existing-ids (keyword candidate)))
          {:status 200 :body {:id candidate}}

          (>= attempts 100)
          {:status 500 :body {:error "Could not generate unique ID"}}

          :else
          (recur (inc attempts)))))))

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
