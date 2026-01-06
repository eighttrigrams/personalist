(ns et.pe.server
  (:require [ring.adapter.jetty9 :as jetty]
            [et.pe.ds :as ds]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [compojure.core :refer [defroutes GET POST PUT DELETE context]]
            [compojure.route :as route]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.cors :refer [wrap-cors]]
            [et.pe.server.handlers :as handlers]
            [nrepl.server :as nrepl]
            [taoensso.telemere :as tel])
  (:gen-class))

(defonce ds-conn (atom nil))
(defonce config (atom nil))

(def prod-mode?*
  (delay
    (let [on-fly? (some? (System/getenv "FLY_APP_NAME"))
          dev-mode? (= "true" (System/getenv "DEV"))
          admin-pw (System/getenv "ADMIN_PASSWORD")]
      (cond
        (or on-fly? (not dev-mode?))
        (do (when-not admin-pw
              (throw (ex-info "ADMIN_PASSWORD required in production" {})))
            true)
        admin-pw
        true
        :else
        false))))

(defn prod-mode? [] @prod-mode?*)

(defn- load-config []
  (let [config-file (io/file (if (prod-mode?) "config.prod.edn" "config.edn"))]
    (if (.exists config-file)
      (do
        (tel/log! :info (str "Loading configuration from " (.getName config-file)))
        (edn/read-string (slurp config-file)))
      (if (prod-mode?)
        (throw (ex-info "Config file required in production mode" {:file (.getName config-file)}))
        (do
          (tel/log! :info "config file not found, using default in-memory database with pre-seed")
          {:db {:type :xtdb2-in-memory} :pre-seed? true})))))

(defn- should-pre-seed? [cfg]
  (true? (:pre-seed? cfg)))

(defn- shadow-mode? []
  (true? (:shadow? @config)))

(defn- run-seed-script []
  (let [seed-script (io/file "scripts/seed-db.sh")]
    (when (.exists seed-script)
      (tel/log! :info "Running seed script...")
      (let [process (.exec (Runtime/getRuntime) "bash scripts/seed-db.sh")
            exit-code (.waitFor process)]
        (if (zero? exit-code)
          (tel/log! :info "Seed script completed successfully")
          (tel/log! :error ["Seed script failed with exit code:" exit-code]))))))

(defn- enrich-db-config [db-config]
  (if (= (:type db-config) :xtdb2-s3)
    (merge db-config
           {:s3-endpoint (or (System/getenv "S3_ENDPOINT") "http://minio:9000")
            :s3-bucket (or (System/getenv "S3_BUCKET") "xtdb")
            :s3-prefix (or (System/getenv "S3_PREFIX") "personalist/")
            :access-key (System/getenv "S3_ACCESS_KEY")
            :secret-key (System/getenv "S3_SECRET_KEY")})
    db-config))

(defn ensure-conn []
  (when (nil? @ds-conn)
    (when (nil? @config)
      (reset! config (load-config)))
    (let [db-config (enrich-db-config (get @config :db {:type :xtdb2-in-memory}))]
      (reset! ds-conn (ds/init-conn db-config)))
    (handlers/set-conn! @ds-conn)
    (handlers/set-config! @config))
  @ds-conn)

(defn- db-empty? []
  (empty? (ds/list-personas (ensure-conn))))

(defroutes api-routes
  (context "/api" []
    (GET "/personas" [] handlers/list-personas-handler)
    (POST "/personas" [] handlers/add-persona-handler)
    (PUT "/personas/:name" [_name] handlers/update-persona-handler)
    (GET "/generate-id" [] handlers/generate-id-handler)
    (GET "/auth/required" [] (handlers/password-required-handler (prod-mode?)))
    (POST "/auth/login" [] (handlers/persona-login-handler (prod-mode?)))
    (GET "/personas/:name/identities" [_name] handlers/list-identities-handler)
    (GET "/personas/:name/identities/recent" [_name] handlers/list-recent-identities-handler)
    (GET "/personas/:name/identities/search" [_name] handlers/search-identities-handler)
    (POST "/personas/:name/identities" [_name] handlers/add-identity-handler)
    (PUT "/personas/:name/identities/:id" [_name _id] handlers/update-identity-handler)
    (GET "/personas/:name/identities/:id/at" [_name _id] handlers/get-identity-at-handler)
    (GET "/personas/:name/identities/:id/history" [_name _id] handlers/get-identity-history-handler)
    (GET "/personas/:name/identities/:id/relations" [_name _id] handlers/list-relations-handler)
    (POST "/personas/:name/identities/:id/relations" [_name _id] handlers/add-relation-handler)
    (DELETE "/personas/:name/relations/:source-id/:target-id" [name source-id target-id]
      (handlers/delete-relation-handler name source-id target-id))))

(defn- serve-index [_]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (slurp (clojure.java.io/resource "public/index.html"))})

(defroutes app-routes
  api-routes
  (GET "/" [] serve-index)
  (route/resources "/")
  (GET "/:persona-id" [_persona-id] serve-index)
  (GET "/:persona-id/:identity-id" [_persona-id _identity-id] serve-index)
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
        (if (handlers/verify-token-check token)
          (handler req)
          {:status 401
           :headers {"Content-Type" "application/json"}
           :body "{\"error\":\"Invalid token\"}"})
        {:status 401
         :headers {"Content-Type" "application/json"}
         :body "{\"error\":\"Authentication required\"}"})
      (handler req))))

(defn wrap-error-handling [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception e
        (tel/log! :error ["Request failed:" (.getMessage e)])
        {:status 500
         :headers {"Content-Type" "application/json"}
         :body "{\"error\":\"Internal server error\"}"}))))

(def base-app
  (-> app-routes
      (wrap-params)
      (wrap-json-body {:keywords? true})
      (wrap-auth)
      (wrap-json-response)
      (wrap-error-handling)
      (wrap-cors :access-control-allow-origin [#".*"]
                 :access-control-allow-methods [:get :post :put :delete])))

(def app
  (fn [req]
    (if (shadow-mode?)
      (base-app req)
      ((handlers/wrap-rate-limit base-app) req))))

(defn- run-server [port]
  (let [host (or (System/getenv "HOST") "127.0.0.1")]
    (tel/log! :info ["Binding to" host ":" port])
    (jetty/run-jetty #'app {:port port :host host :join? false})))

(defn- ensure-valid-options [config]
  (when (and (true? (:pre-seed? config))
             (prod-mode?))
    (throw (ex-info "Cannot use :pre-seed? in prod mode" {})))
  (when (and (true? (:dangerously-skip-logins? config))
             (prod-mode?))
    (throw (ex-info "Cannot use :dangerously-skip-logins? in production mode" {}))))

(defn- start-worker []
  (future
    (tel/log! :info "Worker starting...")
    (loop []
      (tel/log! :info "Hello from worker!")
      (Thread/sleep 30000)
      (recur))))

(defn -main
  [& _args]
  (reset! config (load-config))
  (handlers/set-config! @config)
  (tel/log! :info ["Starting system in" (if (prod-mode?) "production" "development") "mode"])
  (ensure-valid-options @config)
  (ensure-conn)
  (when-not (prod-mode?)
    (let [nrepl-port (Integer/parseInt (or (System/getenv "NREPL_PORT") "7888"))]
      (nrepl/start-server :port nrepl-port)
      (spit ".nrepl-port" nrepl-port)
      (tel/log! :info ["nREPL server started on port" nrepl-port])))
  (let [port (Integer/parseInt (or (System/getenv "PORT") "3017"))]
    (tel/log! :info ["Starting server on port" port])
    (run-server port)
    (when (prod-mode?)
      (start-worker))
    (when (should-pre-seed? @config)
      (future
        (Thread/sleep 2000)
        (if (db-empty?)
          (do
            (tel/log! :info "Pre-seed enabled and database empty, seeding...")
            (run-seed-script))
          (tel/log! :info "Pre-seed enabled but database has data, skipping seed"))))
    @(promise)))

(comment
  (reset! ds-conn nil)
  (ensure-conn)
  (ds/add-persona @ds-conn :dan "d@et.n" nil nil)
  (ds/list-personas @ds-conn))
