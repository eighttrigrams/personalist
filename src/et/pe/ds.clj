(ns et.pe.ds 
  (:require [et.pe.ds.dispatch :as dispatch]
            et.pe.ds.xtdb2))

(defn init-conn 
  "@param opts 
     -> :type :xtdb2-in-memory - creates an in memory xtdb2 node; needs ports 3000 and 5432" 
  [opts]
  (dispatch/init-conn opts))

(defn close-conn 
  [conn] 
  (dispatch/close-conn conn))

(defn get-person-by-name 
  [conn name]
  (dispatch/get-person-by-name conn name))

(defn get-person-by-email 
  [conn email]
  (dispatch/get-person-by-email conn email))

(defn add-person 
  "@returns true if person added, false otherwise"
  [conn name email]
  (dispatch/add-person conn name email))

(defn list-persons [conn]
  (dispatch/list-persons conn))

(defn list-identities [conn mind]
  (dispatch/list-identities conn mind))

(defn add-identity
  "@param mind - person the identity belongs to"
  [conn mind id text]
  (dispatch/add-identity conn mind id text))

(comment 
  (with-open [conn (init-conn {:type :xtdb2-in-memory})]
    (add-person conn :dan "dan@g.c")
    (add-person conn :dan2 "dan2@g.c")
    (add-identity conn {:xt/id :dan} :id1 "text")
    (add-identity conn {:xt/id :dan2} :id2 "text2")
    (list-identities conn {:xt/id :dan}))
  :.)