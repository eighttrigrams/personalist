(ns et.pe.server
  (:require [ring.adapter.jetty :as jetty]
            [et.pe.ds :as ds]
            [et.pe.resolver :as resolver]
            ;; [cheshire.core :as json]
            [clojure.data.json :as data.json]
            et.pe.ds.xtdb2
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
             (data.json/write-str result))}))

(defroutes app-routes
  (POST "/graphql" []
    (-> handler
        (wrap-json-response)
        (wrap-json-body {:keywords true}))))

(defn- run-jetty [port] (jetty/run-jetty #'app-routes {:port port}))

(defn -main
  [& _args]
  (prn "hi")
  (run-jetty 3017))

(comment
  (future (-main))
  (require '[buddy.sign.jwt :as jwt])
  (def signed (jwt/sign {:user :dan} "abc"))
  (jwt/unsign signed "abc"))
