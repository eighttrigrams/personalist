(ns et.pe.resolver
  (:require [clojure.java.io :as io]
            [com.walmartlabs.lacinia.util :as util]
            [com.walmartlabs.lacinia.schema :as lacinia.schema]
            [com.walmartlabs.lacinia :as lacinia]
            [clojure.edn :as edn]
            [clojure.walk :as walk])
  (:import (clojure.lang IPersistentMap)))

(defn resolver-map
  []
  {:Query/identitiesByPersonId 
   (fn [_context {:keys [personName]} _value]
     '({:identity :dan :text "yo"}))})

(defn load-schema
  []
  (-> (io/resource "basic-schema.edn")
      slurp
      edn/read-string
      (util/inject-resolvers (resolver-map))
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
  (simplify (lacinia/execute schema query-string nil nil)))

(comment
  (def schema (load-schema))
  (simplify (q schema "{ identitiesByPersonId(personName: \"dan\") { identity text }}")))
