(ns et.pe.ds
  (:require [et.pe.ds.xtdb2 :as xtdb2]))

(defn init-conn
  "@param opts
     -> :type :xtdb2-in-memory - creates an in memory xtdb2 node; needs ports 3000 and 5432"
  [opts]
  (xtdb2/init-conn opts))

(defn close-conn
  [conn]
  (xtdb2/close-conn conn))

(defn get-persona-by-name
  [conn name]
  (xtdb2/get-persona-by-name conn name))

(defn get-persona-by-email
  [conn email]
  (xtdb2/get-persona-by-email conn email))

(defn add-persona
  "@returns true if persona added, false otherwise"
  [conn name email password-hash]
  (xtdb2/add-persona conn name email password-hash))

(defn update-persona
  "@returns {:success true} or {:error :email-exists}"
  [conn name new-email]
  (xtdb2/update-persona conn name new-email))

(defn get-persona-password-hash
  [conn name]
  (xtdb2/get-persona-password-hash conn name))

(defn list-personas [conn]
  (xtdb2/list-personas conn))

(defn list-identities [conn mind]
  (xtdb2/list-identities conn mind))

(defn add-identity
  "@param mind - persona the identity belongs to
   @param opts - optional map with :valid-from for explicit valid-time
   @returns the generated identity id"
  [conn mind name text & [opts]]
  (xtdb2/add-identity conn mind name text opts))

(defn update-identity
  "@param opts - optional map with :valid-from for explicit valid-time"
  [conn mind id name text & [opts]]
  (xtdb2/update-identity conn mind id name text opts))

(defn get-identity-at
  "@param at - java.time.Instant for time-travel query"
  [conn mind id at]
  (xtdb2/get-identity-at conn mind id at))

(defn get-identity-history
  [conn mind id]
  (xtdb2/get-identity-history conn mind id))

(defn add-relation
  [conn mind source-id target-id & [opts]]
  (xtdb2/add-relation conn mind source-id target-id opts))

(defn list-relations
  [conn mind target-id & [opts]]
  (xtdb2/list-relations conn mind target-id opts))

(defn delete-relation
  [conn mind relation-id]
  (xtdb2/delete-relation conn mind relation-id))

(defn search-identities
  [conn mind query & [opts]]
  (xtdb2/search-identities conn mind query opts))
