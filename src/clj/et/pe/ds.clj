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

(defn get-persona-by-id
  [conn id]
  (xtdb2/get-persona-by-id conn id))

(defn get-persona-by-email
  [conn email]
  (xtdb2/get-persona-by-email conn email))

(defn add-persona
  "@returns true if persona added, false otherwise"
  [conn id email password-hash persona-name]
  (xtdb2/add-persona conn id email password-hash persona-name))

(defn update-persona
  "@returns {:success true} or {:error :email-exists} or nil if not found
   @param updates - map with optional keys :email and :name"
  [conn id updates]
  (xtdb2/update-persona conn id updates))

(defn get-persona-password-hash
  [conn id]
  (xtdb2/get-persona-password-hash conn id))

(defn list-personas [conn]
  (xtdb2/list-personas conn))

(defn list-identities [conn persona]
  (xtdb2/list-identities conn persona))

(defn list-recent-identities [conn persona limit]
  (xtdb2/list-recent-identities conn persona limit))

(defn add-identity
  [conn persona name text & [opts]]
  (xtdb2/add-identity conn persona name text opts))

(defn update-identity
  [conn persona id name text & [opts]]
  (xtdb2/update-identity conn persona id name text opts))

(defn get-identity-at
  [conn persona id at]
  (xtdb2/get-identity-at conn persona id at))

(defn get-identity-history
  [conn persona id]
  (xtdb2/get-identity-history conn persona id))

(defn add-relation
  [conn persona source-id target-id & [opts]]
  (xtdb2/add-relation conn persona source-id target-id opts))

(defn list-relations
  [conn persona source-id & [opts]]
  (xtdb2/list-relations conn persona source-id opts))

(defn delete-relation
  [conn persona relation-id]
  (xtdb2/delete-relation conn persona relation-id))

(defn search-identities
  [conn persona query & [opts]]
  (xtdb2/search-identities conn persona query opts))
