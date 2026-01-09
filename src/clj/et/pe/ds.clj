(ns et.pe.ds
  (:require [et.pe.ds.xtdb2 :as xtdb2]
            [et.pe.ds.sqlite :as sqlite]))

(defn- db-category [type]
  (cond
    (#{:xtdb2-in-memory :xtdb2-on-disk :xtdb2-with-s3} type) :xtdb2
    (#{:sqlite-in-memory :sqlite-on-disk} type) :sqlite))

(defmulti init-conn (fn [type _opts] (db-category type)))
(defmulti close-conn (fn [conn] (db-category (:type conn))))
(defmulti log-xtdb-status (fn [conn] (db-category (:type conn))))
(defmulti get-persona-by-id (fn [conn _id] (db-category (:type conn))))
(defmulti get-persona-by-email (fn [conn _email] (db-category (:type conn))))
(defmulti get-persona-by-id-or-email (fn [conn _id _email] (db-category (:type conn))))
(defmulti add-persona (fn [conn _id _email _password-hash _persona-name] (db-category (:type conn))))
(defmulti update-persona (fn [conn _id _updates] (db-category (:type conn))))
(defmulti get-persona-password-hash (fn [conn _id] (db-category (:type conn))))
(defmulti list-personas (fn [conn] (db-category (:type conn))))
(defmulti list-identities (fn [conn _persona] (db-category (:type conn))))
(defmulti get-identity (fn [conn _persona _identity-id] (db-category (:type conn))))
(defmulti list-recent-identities (fn [conn _persona _limit _offset] (db-category (:type conn))))
(defmulti add-identity (fn [conn _persona _nm _text & _opts] (db-category (:type conn))))
(defmulti update-identity (fn [conn _persona _id _nm _text & _opts] (db-category (:type conn))))
(defmulti get-identity-at (fn [conn _persona _id _time-point] (db-category (:type conn))))
(defmulti get-identity-history (fn [conn _persona _id] (db-category (:type conn))))
(defmulti add-relation (fn [conn _persona _source-id _target-id & _opts] (db-category (:type conn))))
(defmulti list-relations (fn [conn _persona _source-id & _opts] (db-category (:type conn))))
(defmulti delete-relation (fn [conn _persona _relation-id & _opts] (db-category (:type conn))))
(defmulti search-identities (fn [conn _persona _query & _opts] (db-category (:type conn))))

(defmethod init-conn :xtdb2 [type opts] (xtdb2/init-conn type opts))
(defmethod init-conn :sqlite [type opts] (sqlite/init-conn type opts))

(defmethod close-conn :xtdb2 [conn] (xtdb2/close-conn conn))
(defmethod close-conn :sqlite [conn] (sqlite/close-conn conn))

(defmethod log-xtdb-status :xtdb2 [conn] (xtdb2/log-xtdb-status conn))
(defmethod log-xtdb-status :sqlite [_conn] nil)

(defmethod get-persona-by-id :xtdb2 [conn id] (xtdb2/get-persona-by-id conn id))
(defmethod get-persona-by-id :sqlite [conn id] (sqlite/get-persona-by-id conn id))

(defmethod get-persona-by-email :xtdb2 [conn email] (xtdb2/get-persona-by-email conn email))
(defmethod get-persona-by-email :sqlite [conn email] (sqlite/get-persona-by-email conn email))

(defmethod get-persona-by-id-or-email :xtdb2 [conn id email] (xtdb2/get-persona-by-id-or-email conn id email))
(defmethod get-persona-by-id-or-email :sqlite [conn id email] (sqlite/get-persona-by-id-or-email conn id email))

(defmethod add-persona :xtdb2 [conn id email password-hash persona-name] (xtdb2/add-persona conn id email password-hash persona-name))
(defmethod add-persona :sqlite [conn id email password-hash persona-name] (sqlite/add-persona conn id email password-hash persona-name))

(defmethod update-persona :xtdb2 [conn id updates] (xtdb2/update-persona conn id updates))
(defmethod update-persona :sqlite [conn id updates] (sqlite/update-persona conn id updates))

(defmethod get-persona-password-hash :xtdb2 [conn id] (xtdb2/get-persona-password-hash conn id))
(defmethod get-persona-password-hash :sqlite [conn id] (sqlite/get-persona-password-hash conn id))

(defmethod list-personas :xtdb2 [conn] (xtdb2/list-personas conn))
(defmethod list-personas :sqlite [conn] (sqlite/list-personas conn))

(defmethod list-identities :xtdb2 [conn persona] (xtdb2/list-identities conn persona))
(defmethod list-identities :sqlite [conn persona] (sqlite/list-identities conn persona))

(defmethod get-identity :xtdb2 [conn persona identity-id] (xtdb2/get-identity conn persona identity-id))
(defmethod get-identity :sqlite [conn persona identity-id] (sqlite/get-identity conn persona identity-id))

(defmethod list-recent-identities :xtdb2 [conn persona limit offset] (xtdb2/list-recent-identities conn persona limit offset))
(defmethod list-recent-identities :sqlite [conn persona limit offset] (sqlite/list-recent-identities conn persona limit offset))

(defmethod add-identity :xtdb2 [conn persona nm text & [opts]] (xtdb2/add-identity conn persona nm text opts))
(defmethod add-identity :sqlite [conn persona nm text & [opts]] (sqlite/add-identity conn persona nm text opts))

(defmethod update-identity :xtdb2 [conn persona id nm text & [opts]] (xtdb2/update-identity conn persona id nm text opts))
(defmethod update-identity :sqlite [conn persona id nm text & [opts]] (sqlite/update-identity conn persona id nm text opts))

(defmethod get-identity-at :xtdb2 [conn persona id time-point] (xtdb2/get-identity-at conn persona id time-point))
(defmethod get-identity-at :sqlite [conn persona id time-point] (sqlite/get-identity-at conn persona id time-point))

(defmethod get-identity-history :xtdb2 [conn persona id] (xtdb2/get-identity-history conn persona id))
(defmethod get-identity-history :sqlite [conn persona id] (sqlite/get-identity-history conn persona id))

(defmethod add-relation :xtdb2 [conn persona source-id target-id & [opts]] (xtdb2/add-relation conn persona source-id target-id opts))
(defmethod add-relation :sqlite [conn persona source-id target-id & [opts]] (sqlite/add-relation conn persona source-id target-id opts))

(defmethod list-relations :xtdb2 [conn persona source-id & [opts]] (xtdb2/list-relations conn persona source-id opts))
(defmethod list-relations :sqlite [conn persona source-id & [opts]] (sqlite/list-relations conn persona source-id opts))

(defmethod delete-relation :xtdb2 [conn persona relation-id & [opts]] (xtdb2/delete-relation conn persona relation-id opts))
(defmethod delete-relation :sqlite [conn persona relation-id & [opts]] (sqlite/delete-relation conn persona relation-id opts))

(defmethod search-identities :xtdb2 [conn persona query & [opts]] (xtdb2/search-identities conn persona query opts))
(defmethod search-identities :sqlite [conn persona query & [opts]] (sqlite/search-identities conn persona query opts))
