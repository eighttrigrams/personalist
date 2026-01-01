(ns et.pe.ds.dispatch)

(def db-hierarchy (atom (make-hierarchy)))

(defmulti init-conn
  (fn [{:keys [type]}]
    type)
  :hierarchy db-hierarchy)

(defmulti close-conn
  (fn [{:keys [type]}]
    type)
  :hierarchy db-hierarchy)

(defmulti get-persona-by-name-or-email
  (fn [{:keys [type]} _name _email]
    type)
  :hierarchy db-hierarchy)

(defmulti get-persona-by-name
  (fn [{:keys [type]} _name]
    type)
  :hierarchy db-hierarchy)

(defmulti get-persona-by-email
  (fn [{:keys [type]} _email]
    type)
  :hierarchy db-hierarchy)

(defmulti add-persona
  (fn [{:keys [type]} _name _email]
    type)
  :hierarchy db-hierarchy)

(defmulti list-personas
  (fn [{:keys [type]}]
    type)
  :hierarchy db-hierarchy)

(defmulti list-identities
  (fn [{:keys [type]} _mind]
    type)
  :hierarchy db-hierarchy)

(defmulti add-identity
  (fn [{:keys [type]} _mind _id _name _text & [_opts]]
    type)
  :hierarchy db-hierarchy)

(defmulti update-identity
  (fn [{:keys [type]} _mind _id _name _text & [_opts]]
    type)
  :hierarchy db-hierarchy)

(defmulti get-identity-at
  (fn [{:keys [type]} _mind _id _at]
    type)
  :hierarchy db-hierarchy)

(defmulti get-identity-history
  (fn [{:keys [type]} _mind _id]
    type)
  :hierarchy db-hierarchy)

(defmulti add-relation
  (fn [{:keys [type]} _mind _source-id _target-id & [_opts]]
    type)
  :hierarchy db-hierarchy)

(defmulti list-relations
  (fn [{:keys [type]} _mind _target-id & [_opts]]
    type)
  :hierarchy db-hierarchy)

(defmulti delete-relation
  (fn [{:keys [type]} _mind _relation-id]
    type)
  :hierarchy db-hierarchy)

(defmulti search-identities
  (fn [{:keys [type]} _mind _query]
    type)
  :hierarchy db-hierarchy)
