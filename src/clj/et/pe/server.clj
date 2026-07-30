(ns et.pe.server
  (:require [ring.adapter.jetty9 :as jetty]
            [et.pe.ds :as ds]
            [et.pe.logging :as logging]
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

(defn prod-mode? []
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
      false)))

(def ^:private describe-namespaces
  "Namespaces whose public vars back HTTP routes. The /api/describe endpoint
  walks these to enumerate the API surface from var metadata, so the docstring
  on each handler *is* the API documentation."
  '[et.pe.server
    et.pe.server.handlers])

(def ^:private route-doc-re
  "Route handlers document themselves as `METHOD /path — explanation`. Matching
  on that keeps non-route helpers (build-handler etc.) out of /api/describe, so
  the listing only ever advertises things you can actually call."
  #"(?s)^(GET|POST|PUT|DELETE|PATCH)\s+(\S+)\s")

(defn describe-handler
  "GET /api/describe — enumerate the API surface: every route handler with its
  method, path and docstring. Read-only and unauthenticated; lets an agent
  discover the endpoints before calling them.

  Every GET under /api is public here by design — personalist serves what a
  visitor of personalist.org sees. Only writes are guarded (see wrap-auth)."
  [_req]
  {:status 200
   :body (->> describe-namespaces
              (mapcat (fn [ns-sym] (when-let [n (find-ns ns-sym)] (ns-publics n))))
              (keep (fn [[sym v]]
                      (let [doc (:doc (meta v))]
                        (when-let [[_ method path] (some->> doc (re-find route-doc-re))]
                          {:name (str sym)
                           :ns (str (ns-name (.ns ^clojure.lang.Var v)))
                           :method method
                           :path path
                           :arglists (pr-str (:arglists (meta v)))
                           :doc doc}))))
              (sort-by (juxt :path :method))
              vec)})

(defroutes api-routes
  (context "/api" []
    (GET "/describe" [] describe-handler)
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
    (GET "/personas/:name/identities/:id" [_name _id] handlers/get-identity-handler)))

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
  (let [config-file (io/file "config.edn")]
    (if-not (.exists config-file)
      (throw (ex-info "Config file required" {:file (.getName config-file)}))
      (do
        (tel/log! :info (str "Loading configuration from " (.getName config-file)))
        (edn/read-string (slurp config-file))))))

(defn- should-pre-seed? [cfg]
  (true? (get-in cfg [:devel :pre-seed?])))

(defn- shadow-mode? [config]
  (true? (get-in config [:devel :shadow?])))

(defn app [config]
  (fn [req]
    (if (shadow-mode? config)
      (base-app req)
      ((handlers/wrap-rate-limit base-app) req))))

(defn- run-server [port config]
  (let [host (or (System/getenv "HOST") "127.0.0.1")]
    (tel/log! :info ["Binding to" host ":" port])
    (jetty/run-jetty (app config) {:port port :host host :join? false})))

(defn- ensure-app-options [config]
  (when (and (get-in config [:devel :pre-seed?])
             (prod-mode?))
    (throw (ex-info "Cannot use :devel :pre-seed? in prod mode" {})))
  (when (and (get-in config [:devel :dangerously-skip-logins?])
             (prod-mode?))
    (throw (ex-info "Cannot use :devel :dangerously-skip-logins? in production mode" {}))))

(defn- ensure-valid-options [config]
  (when-not (get-in config [:server :port]) (throw (ex-info ":server :port must be configured" {})))
  (ensure-app-options config))

(defn- run-seed-script []
  (let [seed-script (io/file "scripts/seed-db.sh")]
    (when (.exists seed-script)
      (tel/log! :info "Running seed script...")
      (let [process (.exec (Runtime/getRuntime) "bash scripts/seed-db.sh")
            exit-code (.waitFor process)]
        (if (zero? exit-code)
          (tel/log! :info "Seed script completed successfully")
          (tel/log! :error ["Seed script failed with exit code:" exit-code]))))))

(defn- db-empty? [conn]
  (empty? (ds/list-personas conn)))

(defn- pre-seed [conn]
  (future
    (Thread/sleep 2000)
    (if (db-empty? conn)
      (do
        (tel/log! :info "Pre-seed enabled and database empty, seeding...")
        (run-seed-script))
      (tel/log! :info "Pre-seed enabled but database has data, skipping seed"))))

(defn build-handler
  "Initialise personalist (logging, db conn, handlers state) and return a ring
   handler. Does not start jetty or nrepl. The caller owns the server lifecycle.
   Intended for use by composing apps (e.g. plurama)."
  [config]
  (logging/init! (:logging config))
  (ensure-app-options config)
  (let [conn (ds/init-conn (:type (:db config)) (:db config))]
    (handlers/set-config! config)
    (handlers/set-conn! conn)
    (when (should-pre-seed? config) (pre-seed conn))
    (app config)))

(defn -main
  [& _args]
  (let [config (load-config)
        _ (logging/init! (:logging config))
        _ (tel/log! :info ["Starting system in" (if (prod-mode?) "production" "development") "mode"])
        _ (ensure-valid-options config)
        conn (ds/init-conn (:type (:db config)) (:db config))]
    (handlers/set-config! config)
    (handlers/set-conn! conn)
    (when (should-pre-seed? config) (pre-seed conn))
    (let [port (get-in config [:server :port])]
      (tel/log! :info ["Starting server on port" port])
      (run-server port config)
      (when-not (prod-mode?)
        (let [nrepl-port (Integer/parseInt (or (System/getenv "NREPL_PORT") "7888"))]
          (nrepl/start-server :port nrepl-port)
          (spit ".nrepl-port" nrepl-port)
          (tel/log! :info ["nREPL server started on port" nrepl-port])))
      @(promise))))
