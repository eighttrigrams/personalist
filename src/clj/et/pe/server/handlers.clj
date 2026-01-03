(ns et.pe.server.handlers
  (:require [et.pe.ds :as ds]
            [et.pe.urbit :as urbit]
            [clojure.walk]
            [buddy.hashers :as hashers]
            [buddy.sign.jwt :as jwt])
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
  (and (true? (:dangerously-skip-logins? @config))
       (not prod-mode?)))

(defn- jwt-secret []
  (or (System/getenv "ADMIN_PASSWORD") "dev-secret"))

(defn- create-token [persona-name]
  (jwt/sign {:persona (name persona-name)} (jwt-secret)))

(defn- verify-token [token]
  (try
    (jwt/unsign token (jwt-secret))
    (catch Exception _ nil)))

(defn verify-token-check [token]
  (verify-token token))

(defn list-personas-handler [_req]
  {:status 200
   :body (serialize-response (ds/list-personas (ensure-conn)))})

(defn add-persona-handler [req]
  (let [{:keys [id email password name]} (:body req)
        password-hash (when (seq password) (hashers/derive password))
        result (ds/add-persona (ensure-conn) (str->keyword id) email password-hash name)]
    (if result
      {:status 201 :body {:success true}}
      {:status 400 :body {:success false :error "Persona already exists"}})))

(defn update-persona-handler [req]
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

(defn list-identities-handler [req]
  (let [persona-name (str->keyword (get-in req [:params :name]))
        persona (ds/get-persona-by-id (ensure-conn) persona-name)]
    (if persona
      {:status 200 :body (serialize-response (ds/list-identities (ensure-conn) persona))}
      {:status 404 :body {:error "Persona not found"}})))

(defn list-recent-identities-handler [req]
  (let [persona-name (str->keyword (get-in req [:params :name]))
        limit (or (some-> (get-in req [:query-params "limit"]) Integer/parseInt) 5)
        persona (ds/get-persona-by-id (ensure-conn) persona-name)]
    (if persona
      {:status 200 :body (serialize-response (ds/list-recent-identities (ensure-conn) persona limit))}
      {:status 404 :body {:error "Persona not found"}})))

(defn add-identity-handler [req]
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

(defn update-identity-handler [req]
  (let [persona-name (str->keyword (get-in req [:params :name]))
        identity-id (str->keyword (get-in req [:params :id]))
        {:keys [name text valid_from]} (:body req)
        persona (ds/get-persona-by-id (ensure-conn) persona-name)
        opts (when valid_from {:valid-from (Instant/parse valid_from)})]
    (if persona
      (do
        (ds/update-identity (ensure-conn) persona identity-id name text opts)
        {:status 200 :body {:success true}})
      {:status 404 :body {:error "Persona not found"}})))

(defn get-identity-at-handler [req]
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

(defn get-identity-history-handler [req]
  (let [persona-name (str->keyword (get-in req [:params :name]))
        identity-id (str->keyword (get-in req [:params :id]))
        persona (ds/get-persona-by-id (ensure-conn) persona-name)]
    (if persona
      (let [history (ds/get-identity-history (ensure-conn) persona identity-id)]
        {:status 200 :body (serialize-response history)})
      {:status 404 :body {:error "Persona not found"}})))

(defn list-relations-handler [req]
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

(defn add-relation-handler [req]
  (let [persona-name (str->keyword (get-in req [:params :name]))
        identity-id (str->keyword (get-in req [:params :id]))
        {:keys [target_id valid_from]} (:body req)
        opts (when valid_from {:valid-from (Instant/parse valid_from)})
        persona (ds/get-persona-by-id (ensure-conn) persona-name)]
    (if persona
      (let [result (ds/add-relation (ensure-conn) persona identity-id (str->keyword target_id) opts)]
        (if (false? result)
          {:status 409 :body {:error "Relation already exists"}}
          {:status 201 :body {:success true}}))
      {:status 404 :body {:error "Persona not found"}})))

(defn delete-relation-handler [req]
  (let [persona-name (str->keyword (get-in req [:params :name]))
        relation-id (get-in req [:params :relation-id])
        persona (ds/get-persona-by-id (ensure-conn) persona-name)]
    (if persona
      (do
        (ds/delete-relation (ensure-conn) persona relation-id)
        {:status 200 :body {:success true}})
      {:status 404 :body {:error "Persona not found"}})))

(defn search-identities-handler [req]
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

(defn persona-login-handler [prod-mode?]
  (fn [req]
    (let [{:keys [id email password]} (:body req)]
      (if (allow-skip-logins? prod-mode?)
        {:status 200 :body {:success true :message "No password required"}}
        (if (= (str->keyword id) :admin)
          (let [admin-password (if prod-mode?
                                 (System/getenv "ADMIN_PASSWORD")
                                 "admin")]
            (if (= password admin-password)
              {:status 200 :body {:success true :token (create-token :admin)}}
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

(defn password-required-handler [prod-mode?]
  (fn [_req]
    {:status 200 :body {:required (not (allow-skip-logins? prod-mode?))}}))

(defn generate-id-handler [_req]
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
