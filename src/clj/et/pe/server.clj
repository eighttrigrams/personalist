(ns et.pe.server
  (:require [ring.adapter.jetty9 :as jetty]
            [et.pe.ds :as ds]
            [clojure.walk]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            et.pe.ds.xtdb2
            [compojure.core :refer [defroutes GET POST PUT DELETE context]]
            [compojure.route :as route]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.cors :refer [wrap-cors]]
            [et.pe.middleware.rate-limit :refer [wrap-rate-limit]]
            [nrepl.server :as nrepl]
            [buddy.hashers :as hashers]
            [buddy.sign.jwt :as jwt])
  (:import [java.time Instant ZonedDateTime])
  (:gen-class))

(defonce ds-conn (atom nil))
(defonce config (atom nil))

(defn- load-config []
  (let [config-file (io/file "config.edn")]
    (if (.exists config-file)
      (do
        (prn "Loading configuration from config.edn")
        (edn/read-string (slurp config-file)))
      (do
        (prn "config.edn not found, using default in-memory database with pre-seed")
        {:db {:type :xtdb2-in-memory} :pre-seed? true}))))

(defn- should-pre-seed? [cfg]
  (true? (:pre-seed? cfg)))

(defn- run-seed-script []
  (let [seed-script (io/file "scripts/seed-db.sh")]
    (when (.exists seed-script)
      (prn "Running seed script...")
      (let [process (.exec (Runtime/getRuntime) "bash scripts/seed-db.sh")
            exit-code (.waitFor process)]
        (if (zero? exit-code)
          (prn "Seed script completed successfully")
          (prn "Seed script failed with exit code:" exit-code))))))

(defn ensure-conn []
  (when (nil? @ds-conn)
    (when (nil? @config)
      (reset! config (load-config)))
    (reset! ds-conn (ds/init-conn (get @config :db {:type :xtdb2-in-memory}))))
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

(defn list-personas-handler [_req]
  {:status 200
   :body (serialize-response (ds/list-personas (ensure-conn)))})

(defn add-persona-handler [req]
  (let [{:keys [id email password display_name]} (:body req)
        password-hash (when (seq password) (hashers/derive password))
        result (ds/add-persona (ensure-conn) (str->keyword id) email password-hash display_name)]
    (if result
      {:status 201 :body {:success true}}
      {:status 400 :body {:success false :error "Persona already exists"}})))

(defn update-persona-handler [req]
  (let [persona-name (str->keyword (get-in req [:params :name]))
        {:keys [email display_name]} (:body req)
        updates (cond-> {}
                  email (assoc :email email)
                  display_name (assoc :display-name display_name))
        result (ds/update-persona (ensure-conn) persona-name updates)]
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

(defn add-identity-handler [req]
  (let [persona-name (str->keyword (get-in req [:params :name]))
        {:keys [id name text valid_from]} (:body req)
        persona (ds/get-persona-by-id (ensure-conn) persona-name)
        opts (cond-> {}
               valid_from (assoc :valid-from (Instant/parse valid_from))
               id (assoc :id (keyword id)))]
    (if persona
      (let [generated-id (ds/add-identity (ensure-conn) persona name text (when (seq opts) opts))]
        {:status 201 :body {:success true :id (clojure.core/name generated-id)}})
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
        {:keys [source_id valid_from]} (:body req)
        opts (when valid_from {:valid-from (Instant/parse valid_from)})
        persona (ds/get-persona-by-id (ensure-conn) persona-name)]
    (if persona
      (do
        (ds/add-relation (ensure-conn) persona (str->keyword source_id) identity-id opts)
        {:status 201 :body {:success true}})
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

(defn- prod-mode?
  "Returns true when running in production mode.
   On Fly.io: always prod mode, requires ADMIN_PASSWORD.
   Locally: prod mode unless config has :shadow? true or in-memory db."
  []
  (let [on-fly? (some? (System/getenv "FLY_APP_NAME"))
        admin-pw (System/getenv "ADMIN_PASSWORD")
        cfg @config
        in-memory? (= :xtdb2-in-memory (get-in cfg [:db :type]))
        shadow? (true? (:shadow? cfg))]
    (cond
      on-fly? (do (when-not admin-pw
                    (throw (ex-info "ADMIN_PASSWORD required in production" {})))
                  true)
      :else (not (or in-memory? shadow?)))))

(defn- jwt-secret []
  (or (System/getenv "ADMIN_PASSWORD") "dev-secret"))

(defn- create-token [persona-name]
  (jwt/sign {:persona (name persona-name)} (jwt-secret)))

(defn- verify-token [token]
  (try
    (jwt/unsign token (jwt-secret))
    (catch Exception _ nil)))

(defn persona-login-handler [req]
  (let [{:keys [id email password]} (:body req)
        persona (cond
                  (seq id) (ds/get-persona-by-id (ensure-conn) (str->keyword id))
                  (and (seq email) (= email "admin@localhost")) {:id :admin :email "admin@localhost"}
                  (seq email) (ds/get-persona-by-email (ensure-conn) email)
                  :else nil)
        persona-id (str->keyword (:id persona))]
    (if (not (prod-mode?))
      {:status 200 :body {:success true :message "No password required"}}
      (if (nil? persona)
        {:status 401 :body {:success false :error "Invalid credentials"}}
        (if (= persona-id :admin)
          (let [admin-password (System/getenv "ADMIN_PASSWORD")]
            (if (= password admin-password)
              {:status 200 :body {:success true :token (create-token persona-id)}}
              {:status 401 :body {:success false :error "Invalid credentials"}}))
          (let [stored-hash (ds/get-persona-password-hash (ensure-conn) persona-id)]
            (if (and stored-hash (hashers/check password stored-hash))
              {:status 200 :body {:success true :token (create-token persona-id)}}
              {:status 401 :body {:success false :error "Invalid credentials"}})))))))

(defn password-required-handler [_req]
  {:status 200 :body {:required (prod-mode?)}})

(defroutes api-routes
  (context "/api" []
    (GET "/personas" [] list-personas-handler)
    (POST "/personas" [] add-persona-handler)
    (PUT "/personas/:name" [name] update-persona-handler)
    (GET "/auth/required" [] password-required-handler)
    (POST "/auth/login" [] persona-login-handler)
    (GET "/personas/:name/identities" [name] list-identities-handler)
    (GET "/personas/:name/identities/search" [name] search-identities-handler)
    (POST "/personas/:name/identities" [name] add-identity-handler)
    (PUT "/personas/:name/identities/:id" [name id] update-identity-handler)
    (GET "/personas/:name/identities/:id/at" [name id] get-identity-at-handler)
    (GET "/personas/:name/identities/:id/history" [name id] get-identity-history-handler)
    (GET "/personas/:name/identities/:id/relations" [name id] list-relations-handler)
    (POST "/personas/:name/identities/:id/relations" [name id] add-relation-handler)
    (DELETE "/personas/:name/relations/:relation-id" [name relation-id] delete-relation-handler)))

(defroutes app-routes
  api-routes
  (GET "/" [] {:status 200
               :headers {"Content-Type" "text/html"}
               :body (slurp (clojure.java.io/resource "public/index.html"))})
  (route/resources "/")
  (route/not-found {:status 404 :body {:error "Not found"}}))

(defn- extract-token [req]
  (when-let [auth-header (get-in req [:headers "authorization"])]
    (when (str/starts-with? auth-header "Bearer ")
      (subs auth-header 7))))

(defn- mutating-request? [req]
  (#{:post :put :delete} (:request-method req)))

(defn- public-endpoint? [req]
  (let [uri (:uri req)]
    (or (= uri "/api/auth/login")
        (= uri "/api/personas"))))

(defn wrap-auth [handler]
  (fn [req]
    (if (and (prod-mode?)
             (mutating-request? req)
             (str/starts-with? (or (:uri req) "") "/api")
             (not (public-endpoint? req)))
      (if-let [token (extract-token req)]
        (if (verify-token token)
          (handler req)
          {:status 401
           :headers {"Content-Type" "application/json"}
           :body "{\"error\":\"Invalid token\"}"})
        {:status 401
         :headers {"Content-Type" "application/json"}
         :body "{\"error\":\"Authentication required\"}"})
      (handler req))))

(def app
  (-> app-routes
      (wrap-params)
      (wrap-json-body {:keywords? true})
      (wrap-auth)
      (wrap-json-response)
      (wrap-cors :access-control-allow-origin [#".*"]
                 :access-control-allow-methods [:get :post :put :delete])
      (wrap-rate-limit)))

(defn- run-server [port]
  (let [host (or (System/getenv "HOST") "127.0.0.1")]
    (prn "Binding to" host ":" port)
    (jetty/run-jetty #'app {:port port :host host :join? false})))

(defn -main
  [& _args]
  (reset! config (load-config))
  (ensure-conn)
  (let [nrepl-port (Integer/parseInt (or (System/getenv "NREPL_PORT") "7888"))]
    (nrepl/start-server :port nrepl-port)
    (spit ".nrepl-port" nrepl-port)
    (prn "nREPL server started on port" nrepl-port))
  (let [port (Integer/parseInt (or (System/getenv "PORT") "3017"))]
    (prn "Starting server on port" port)
    (run-server port)
    (when (should-pre-seed? @config)
      (prn "Pre-seed enabled, will auto-seed...")
      (future
        (Thread/sleep 2000)
        (run-seed-script)))
    @(promise)))

(comment
  (reset! ds-conn nil)
  (ensure-conn)
  (ds/add-persona @ds-conn :dan "d@et.n" nil nil)
  (ds/list-personas @ds-conn))
