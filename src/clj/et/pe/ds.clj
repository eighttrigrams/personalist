(ns et.pe.ds 
  (:require [et.pe.ds.dispatch :as dispatch]))

(defn init-conn 
  "@param opts 
     -> :type :xtdb2-in-memory - creates an in memory xtdb2 node; needs ports 3000 and 5432" 
  [opts]
  (dispatch/init-conn opts))

(defn close-conn 
  [conn] 
  (dispatch/close-conn conn))

(defn get-persona-by-name
  [conn name]
  (dispatch/get-persona-by-name conn name))

(defn get-persona-by-email
  [conn email]
  (dispatch/get-persona-by-email conn email))

(defn add-persona
  "@returns true if persona added, false otherwise"
  [conn name email]
  (dispatch/add-persona conn name email))

(defn list-personas [conn]
  (dispatch/list-personas conn))

(defn list-identities [conn mind]
  (dispatch/list-identities conn mind))

(defn add-identity
  "@param mind - persona the identity belongs to
   @param opts - optional map with :valid-from for explicit valid-time"
  [conn mind id name text & [opts]]
  (dispatch/add-identity conn mind id name text opts))

(defn update-identity
  "@param opts - optional map with :valid-from for explicit valid-time"
  [conn mind id name text & [opts]]
  (dispatch/update-identity conn mind id name text opts))

(defn get-identity-at
  "@param at - java.time.Instant for time-travel query"
  [conn mind id at]
  (dispatch/get-identity-at conn mind id at))

(defn get-identity-history
  [conn mind id]
  (dispatch/get-identity-history conn mind id))

(defn add-relation
  [conn mind source-id target-id]
  (dispatch/add-relation conn mind source-id target-id))

(defn list-relations
  [conn mind target-id]
  (dispatch/list-relations conn mind target-id))

(defn delete-relation
  [conn mind relation-id]
  (dispatch/delete-relation conn mind relation-id))

(defn search-identities
  [conn mind query]
  (dispatch/search-identities conn mind query))
