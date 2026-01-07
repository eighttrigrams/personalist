(ns et.pe.server
  (:require [ring.adapter.jetty9 :as jetty]
            [et.pe.ds :as ds]
            [et.pe.s3-check]
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

(defn prod-mode? [] (delay
                      (let [on-railway? (some? (System/getenv "RAILWAY_ENVIRONMENT"))
                            dev-mode? (= "true" (System/getenv "DEV"))
                            admin-pw (System/getenv "ADMIN_PASSWORD")]
                        (cond
                          (or on-railway? (not dev-mode?))
                          (do (when-not admin-pw
                                (throw (ex-info "ADMIN_PASSWORD required in production" {})))
                              true)
                          admin-pw
                          true
                          :else
                          false))))

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

(defn- load-config []
  (let [config-file (io/file (if (prod-mode?) "config.prod.edn" "config.edn"))]
    (if-not (.exists config-file)
      (throw (ex-info "Config file required" {:file (.getName config-file)}))
      (do
        (tel/log! :info (str "Loading configuration from " (.getName config-file)))
        (edn/read-string (slurp config-file))))))

(defn- should-pre-seed? [cfg]
  (true? (:pre-seed? cfg)))

(defn- shadow-mode? [config]
  (true? (:shadow? config)))

(defn- run-seed-script []
  (let [seed-script (io/file "scripts/seed-db.sh")]
    (when (.exists seed-script)
      (tel/log! :info "Running seed script...")
      (let [process (.exec (Runtime/getRuntime) "bash scripts/seed-db.sh")
            exit-code (.waitFor process)]
        (if (zero? exit-code)
          (tel/log! :info "Seed script completed successfully")
          (tel/log! :error ["Seed script failed with exit code:" exit-code]))))))

(defonce ds-conn (atom nil))

(defn ensure-conn [config]
  (when (nil? @ds-conn)
    (when (nil? config)
      (reset! config (load-config)))
    (let [db-config (get config :db)]
      (reset! ds-conn (ds/init-conn db-config)))
    (handlers/set-conn! @ds-conn)
    (handlers/set-config! config))
  @ds-conn)

(defn- db-empty? [config]
  (empty? (ds/list-personas (ensure-conn config))))

(defn app [config]
  (fn [req]
    (if (shadow-mode? config)
      (base-app req)
      ((handlers/wrap-rate-limit base-app) req))))

(defn- run-server [port config]
  (let [host (or (System/getenv "HOST") "127.0.0.1")]
    (tel/log! :info ["Binding to" host ":" port])
    (jetty/run-jetty (app config) {:port port :host host :join? false})))

(defn- ensure-valid-options [config]
  (when-not (:port config) (throw (ex-info ":port must be configured" {})))
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
      (Thread/sleep (* 10 60000))
      (recur))))

(defn- s3-needed? [config]
  (= :xtdb2-s3 (get-in config [:db :type])))

(defn- s3-ok? [config]
  ;; TODO check env vars present
  (let [db-config (get config :db)
        check-result (et.pe.s3-check/s3-health-check
                      (:s3-bucket db-config)
                      (:s3-prefix db-config))]
    (when-not (:success check-result)
      (tel/log! :error ["S3 smoke check failed:" (:message check-result)])
      (throw (ex-info "S3 smoke check failed - cannot start application"
                      {:reason (:message check-result)})))))

(defn- pre-seed [config]
  (future
    (Thread/sleep 2000)
    (if (db-empty? config)
      (do
        (tel/log! :info "Pre-seed enabled and database empty, seeding...")
        (run-seed-script))
      (tel/log! :info "Pre-seed enabled but database has data, skipping seed"))))

(defn -main
  [& _args]
  (tel/log! :info ["Starting system in" (if (prod-mode?) "production" "development") "mode"])
  (let [config (load-config)
        _ (ensure-valid-options config)
        _ (when (s3-needed? config) (s3-ok? config))]
    (ensure-conn config)
    (handlers/set-config! config)
    (when (should-pre-seed? config) (pre-seed config))
    ;; starting server
    (let [port (get-in config [:port])]
      (tel/log! :info ["Starting server on port" port])
      (run-server port config)
      (when-not (prod-mode?)
        (let [nrepl-port (Integer/parseInt (or (System/getenv "NREPL_PORT") "7888"))]
          (nrepl/start-server :port nrepl-port)
          (spit ".nrepl-port" nrepl-port)
          (tel/log! :info ["nREPL server started on port" nrepl-port])))
      (when (prod-mode?)
        (start-worker))
      @(promise))))

(comment
  (reset! ds-conn nil)
  #_(ensure-conn)
  (ds/add-persona @ds-conn :dan "d@et.n" nil nil)
  (ds/list-personas @ds-conn))
