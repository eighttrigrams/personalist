(ns et.pe.resolver
  (:require [clojure.java.io :as io]
            [com.walmartlabs.lacinia.util :as util]
            [com.walmartlabs.lacinia.schema :as lacinia.schema]
            [com.walmartlabs.lacinia :as lacinia]
            [clojure.edn :as edn]
            [clojure.walk :as walk]
            [et.pe.ds :as ds]
            et.pe.ds.xtdb2)
  (:import (clojure.lang IPersistentMap)))

(defn resolver-map
  [ds-conn]
  {:Query/identitiesByPersonId 
   (fn [_context {:keys [personName]} _value]
     (ds/list-identities ds-conn (ds/get-person-by-name ds-conn (keyword personName))))})

(defn load-schema
  [ds-conn]
  (-> (io/resource "basic-schema.edn")
      slurp
      edn/read-string
      (util/inject-resolvers (resolver-map ds-conn))
      lacinia.schema/compile))

(defn simplify
  "Converts all ordered maps nested within the map into standard hash maps, and
   sequences into vectors, which makes for easier constants in the tests, and eliminates ordering problems."
  [m]
  (walk/postwalk
    (fn [node]
      (cond
        (instance? IPersistentMap node)
        (into {} node)

        (seq? node)
        (vec node)

        :else
        node))
    m))

(defn q
  [schema query-string]
  (lacinia/execute schema query-string nil nil))

(comment
  (def ds-conn (ds/init-conn {:type :xtdb2-in-memory}))
  (ds/add-person ds-conn :dan "d@et.n")
  (def person (ds/get-person-by-name ds-conn :dan))
  (ds/add-identity ds-conn person :id1 "Hallo, Welt!")
  (def schema (load-schema ds-conn))
  (q schema "{ identitiesByPersonId(personName: \"dan\") { identity text }}")
  (ds/close-conn ds-conn))
