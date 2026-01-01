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
  [conn mind id text & [opts]]
  (dispatch/add-identity conn mind id text opts))

(defn update-identity
  "@param opts - optional map with :valid-from for explicit valid-time"
  [conn mind id text & [opts]]
  (dispatch/update-identity conn mind id text opts))

(defn get-identity-at
  "@param at - java.time.Instant for time-travel query"
  [conn mind id at]
  (dispatch/get-identity-at conn mind id at))

(defn get-identity-history
  [conn mind id]
  (dispatch/get-identity-history conn mind id))
