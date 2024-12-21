(ns et.pe.server
  (:require [ring.adapter.jetty :as jetty]
            [et.pe.ds :as ds]
            [et.pe.resolver :as resolver]
            ;; [clojure.data.json :as json]
            [cheshire.core :as json]
            [compojure.core :refer [defroutes POST]]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]))

(defn handler [request]
  (let [ds-conn (ds/init-conn {:type :xtdb2-in-memory})
        _ (ds/add-person ds-conn :dan "d@et.n")
        person (ds/get-person-by-name ds-conn :dan)
        _ (ds/add-identity ds-conn person :id1 "Hallo, Welt!")
        schema (resolver/load-schema ds-conn)]
    {:status 200
     :body (let [query (get-in request [:body "query"])
                 _ (prn "query!" query) 
                 result (resolver/q schema query)]
             (ds/close-conn ds-conn)
             (json/generate-string result))}))

(defroutes app-routes
  (POST "/graphql" []
    (-> handler
        (wrap-json-response)
        (wrap-json-body {:keywords true}))))

(defn- run-jetty [] (jetty/run-jetty #'app-routes {:port 3017}))

(defn -main
  [& _args]
  (run-jetty))

(comment
  (future (run-jetty))
  (require '[buddy.sign.jwt :as jwt])
  (def signed (jwt/sign {:user :dan} "abc"))
  (jwt/unsign signed "abc"))
