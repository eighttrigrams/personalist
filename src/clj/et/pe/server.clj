(ns et.pe.server
  (:require [ring.adapter.jetty9 :as jetty]
            [et.pe.ds :as ds]
            [et.pe.logging :as logging]
            [clojure.java.io :as io]
            [aero.core :as aero]
            [clojure.string :as str]
            [compojure.core :refer [defroutes GET POST PUT DELETE context]]
            [compojure.route :as route]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.cors :refer [wrap-cors]]
            [ring.util.codec :as codec]
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

  Nearly every GET under /api is public by design — personalist serves what a
  visitor of personalist.org sees. Writes are guarded by wrap-auth; the two GETs
  that answer about an account rather than about the site, /api/me and
  /api/accounts, guard themselves."
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
    (POST "/personas" [] (handlers/add-persona-handler (prod-mode?)))
    (PUT "/personas/:name" [_name] handlers/update-persona-handler)
    (DELETE "/personas/:name" [_name] handlers/delete-persona-handler)
    (GET "/me" [] (handlers/me-handler (prod-mode?)))
    ;; These name no persona in their URI, so wrap-auth waves any valid token
    ;; through them — each handler gates itself on being a human account that
    ;; owns the target. See handlers/human-caller.
    (POST "/machine-users" [] (handlers/add-machine-user-handler (prod-mode?)))
    (POST "/machine-users/:name/token" [_name] (handlers/rotate-machine-token-handler (prod-mode?)))
    (PUT "/machine-users/:name" [_name] (handlers/update-machine-user-handler (prod-mode?)))
    (DELETE "/machine-users/:name" [_name] (handlers/delete-machine-user-handler (prod-mode?)))
    (GET "/accounts" [] (handlers/list-accounts-handler (prod-mode?)))
    (POST "/accounts" [] (handlers/add-account-handler (prod-mode?)))
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
   :body (slurp (clojure.java.io/resource "public/personalist/index.html"))})

(defroutes app-routes
  api-routes
  (GET "/" [] serve-index)
  (route/resources "/" {:root "public/personalist"})
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
  (= (:uri req) "/api/auth/login"))

(def ^:private persona-uri-re #"^/api/personas/([^/]+)")

(defn- persona-in-uri
  "The `:name` segment of a /api/personas/:name/... URI, or nil when the URI
   names no persona (/api/personas itself). Read off :uri rather than :params
   because wrap-auth sits above the routes, where compojure has not bound them
   yet, then URL-decoded with the same ring.util.codec/url-decode compojure
   applies to route params (compojure.core/decode-route-params), so the guard
   compares the exact string the handler will use as the persona id. Without the
   decode a persona whose id is another's percent-encoding slips past. Malformed
   escapes (%zz, a bare %) are left verbatim by url-decode on both sides, so they
   neither throw nor bypass."
  [req]
  (some-> (re-find persona-uri-re (or (:uri req) ""))
          second
          codec/url-decode))

(defn- owns-persona?
  "Whether `principal` may write under `persona`. Three kinds of credential
   reach this now, and each answers the question differently:

   - **admin** may write anywhere, because the Settings tab edits other
     accounts. The exemption keys on the un-mintable :admin claim
     (create-admin-token) and is answered without touching the database.
   - a **human** may write under any persona of its own account. That is a
     lookup, not a string comparison: the token carries an account id and the
     URI a persona id, so the guard asks which account holds that persona.
   - a **machine user** may write under exactly the personas it has been
     granted — *not* everything under its parent account. That distinction is
     the whole feature: one machine user for personas A and C, another for B
     and C, both hanging off the same human.

   A persona nobody holds is owned by nobody, and anything unrecognised is
   refused."
  [principal persona]
  (case (:kind principal)
    :admin true
    :human (boolean (when-let [account (handlers/account-of-persona persona)]
                      (= (:account principal) account)))
    :machine (handlers/machine-grants-persona? (:id principal) persona)
    false))

(defn wrap-auth
  "Guard writes. Engages only in prod mode, only for mutating requests under
   /api, and only outside /api/auth/login — a GET is public unless its own
   handler says otherwise, and login has to be reachable for anyone to obtain a
   token at all.

   A request must carry a verifying `Authorization: Bearer` token (401
   otherwise) that may write the persona it names, or be admin's (403
   otherwise). Two kinds of credential arrive in that header — a human's signed
   JWT and a machine user's opaque token — and owns-persona? answers for both.

   A request that names **no persona in its URI** gets past here on any valid
   token, and the handler decides. That is right for POST /api/personas, whose
   handler mints under the caller's own account. It is emphatically not enough
   for the /api/machine-users routes: a machine user reaching those could grant
   itself every persona in the account, so each of those handlers verifies for
   itself that the caller is a *human* owning the target. See
   handlers/owning-account.

   Two GETs are guarded, but by themselves rather than here: /api/me and
   /api/accounts answer *about an account*, which is the one thing this app's
   anonymity protects. Everything else under /api that a GET can reach is what a
   visitor of personalist.org sees anyway."
  [handler]
  (fn [req]
    (if (and (prod-mode?)
             (mutating-request? req)
             (str/starts-with? (or (:uri req) "") "/api")
             (not (public-endpoint? req)))
      (if-let [token (extract-token req)]
        (if-let [principal (handlers/principal-for-token token)]
          (let [persona (persona-in-uri req)]
            (if (or (nil? persona)
                    (owns-persona? principal persona))
              (handler req)
              {:status 403
               :headers {"Content-Type" "application/json"}
               :body "{\"error\":\"Not your persona\"}"}))
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
        (aero/read-config config-file)))))

(defn- should-pre-seed? [cfg]
  (true? (get-in cfg [:devel :pre-seed?])))

(defn- shadow-mode? [config]
  (true? (get-in config [:devel :shadow?])))

(defn app
  "The ring handler for `config`. Built once per call — wrap-rate-limit
   allocates the request log it counts against, so building it per request
   would hand every caller an empty one and never limit anything."
  [config]
  (if (shadow-mode? config)
    base-app
    (handlers/wrap-rate-limit base-app)))

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
