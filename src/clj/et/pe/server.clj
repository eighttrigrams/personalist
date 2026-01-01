(ns et.pe.server
  (:require [ring.adapter.jetty9 :as jetty]
            [et.pe.ds :as ds]
            [clojure.walk]
            [clojure.java.io]
            et.pe.ds.xtdb2
            [compojure.core :refer [defroutes GET POST PUT DELETE context]]
            [compojure.route :as route]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.cors :refer [wrap-cors]]
            [nrepl.server :as nrepl])
  (:import [java.time Instant ZonedDateTime]))

(defonce ds-conn (atom nil))

(defn ensure-conn []
  (when (nil? @ds-conn)
    (reset! ds-conn (ds/init-conn {:type :xtdb2-in-memory})))
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
  (let [{:keys [name email]} (:body req)
        result (ds/add-persona (ensure-conn) (str->keyword name) email)]
    (if result
      {:status 201 :body {:success true}}
      {:status 400 :body {:success false :error "Persona already exists"}})))

(defn list-identities-handler [req]
  (let [persona-name (str->keyword (get-in req [:params :name]))
        persona (ds/get-persona-by-name (ensure-conn) persona-name)]
    (if persona
      {:status 200 :body (serialize-response (ds/list-identities (ensure-conn) persona))}
      {:status 404 :body {:error "Persona not found"}})))

(defn add-identity-handler [req]
  (let [persona-name (str->keyword (get-in req [:params :name]))
        {:keys [id text valid_from]} (:body req)
        persona (ds/get-persona-by-name (ensure-conn) persona-name)
        opts (when valid_from {:valid-from (Instant/parse valid_from)})]
    (if persona
      (do
        (ds/add-identity (ensure-conn) persona (str->keyword id) text opts)
        {:status 201 :body {:success true}})
      {:status 404 :body {:error "Persona not found"}})))

(defn update-identity-handler [req]
  (let [persona-name (str->keyword (get-in req [:params :name]))
        identity-id (str->keyword (get-in req [:params :id]))
        {:keys [text valid_from]} (:body req)
        persona (ds/get-persona-by-name (ensure-conn) persona-name)
        opts (when valid_from {:valid-from (Instant/parse valid_from)})]
    (if persona
      (do
        (ds/update-identity (ensure-conn) persona identity-id text opts)
        {:status 200 :body {:success true}})
      {:status 404 :body {:error "Persona not found"}})))

(defn get-identity-at-handler [req]
  (let [persona-name (str->keyword (get-in req [:params :name]))
        identity-id (str->keyword (get-in req [:params :id]))
        time-str (or (get-in req [:params :time])
                     (get-in req [:params "time"])
                     (get-in req [:query-params "time"]))
        at (Instant/parse time-str)
        persona (ds/get-persona-by-name (ensure-conn) persona-name)]
    (if persona
      (let [result (ds/get-identity-at (ensure-conn) persona identity-id at)]
        {:status 200 :body (serialize-response result)})
      {:status 404 :body {:error "Persona not found"}})))

(defn get-identity-history-handler [req]
  (let [persona-name (str->keyword (get-in req [:params :name]))
        identity-id (str->keyword (get-in req [:params :id]))
        persona (ds/get-persona-by-name (ensure-conn) persona-name)]
    (if persona
      (let [history (ds/get-identity-history (ensure-conn) persona identity-id)]
        {:status 200 :body (serialize-response history)})
      {:status 404 :body {:error "Persona not found"}})))

(defn list-relations-handler [req]
  (let [persona-name (str->keyword (get-in req [:params :name]))
        identity-id (str->keyword (get-in req [:params :id]))
        persona (ds/get-persona-by-name (ensure-conn) persona-name)]
    (if persona
      (let [relations (ds/list-relations (ensure-conn) persona identity-id)]
        {:status 200 :body (serialize-response relations)})
      {:status 404 :body {:error "Persona not found"}})))

(defn add-relation-handler [req]
  (let [persona-name (str->keyword (get-in req [:params :name]))
        identity-id (str->keyword (get-in req [:params :id]))
        {:keys [source_id]} (:body req)
        persona (ds/get-persona-by-name (ensure-conn) persona-name)]
    (if persona
      (do
        (ds/add-relation (ensure-conn) persona (str->keyword source_id) identity-id)
        {:status 201 :body {:success true}})
      {:status 404 :body {:error "Persona not found"}})))

(defn delete-relation-handler [req]
  (let [persona-name (str->keyword (get-in req [:params :name]))
        relation-id (get-in req [:params :relation-id])
        persona (ds/get-persona-by-name (ensure-conn) persona-name)]
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
        persona (ds/get-persona-by-name (ensure-conn) persona-name)]
    (if persona
      (let [results (ds/search-identities (ensure-conn) persona query)]
        {:status 200 :body (serialize-response results)})
      {:status 404 :body {:error "Persona not found"}})))

(defroutes api-routes
  (context "/api" []
    (GET "/personas" [] list-personas-handler)
    (POST "/personas" [] add-persona-handler)
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

(def app
  (-> app-routes
      (wrap-params)
      (wrap-json-body {:keywords? true})
      (wrap-json-response)
      (wrap-cors :access-control-allow-origin [#".*"]
                 :access-control-allow-methods [:get :post :put :delete])))

(defn- run-server [port]
  (jetty/run-jetty #'app {:port port :join? false}))

(defn -main
  [& _args]
  (ensure-conn)
  (let [nrepl-port (Integer/parseInt (or (System/getenv "NREPL_PORT") "7888"))]
    (nrepl/start-server :port nrepl-port)
    (spit ".nrepl-port" nrepl-port)
    (prn "nREPL server started on port" nrepl-port))
  (let [port (Integer/parseInt (or (System/getenv "PORT") "3017"))]
    (prn "Starting server on port" port)
    (run-server port)
    @(promise)))

(comment
  (reset! ds-conn nil)
  (ensure-conn)
  (ds/add-persona @ds-conn :dan "d@et.n")
  (ds/list-personas @ds-conn))
