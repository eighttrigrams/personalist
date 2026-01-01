(ns et.pe.ds.dispatch)

(defmulti init-conn
  (fn [{:keys [type]}]
    type))

(defmulti close-conn
  (fn [{:keys [type]}]
    type))

(defmulti get-persona-by-name-or-email
  (fn [{:keys [type]} _name _email]
    type))

(defmulti get-persona-by-name
  (fn [{:keys [type]} _name]
    type))

(defmulti get-persona-by-email
  (fn [{:keys [type]} _email]
    type))

(defmulti add-persona
  (fn [{:keys [type]} _name _email]
    type))

(defmulti list-personas
  (fn [{:keys [type]}]
    type))

(defmulti list-identities
  (fn [{:keys [type]} _mind] 
    type))

(defmulti add-identity
  (fn [{:keys [type]} _mind _id _name _text & [_opts]]
    type))

(defmulti update-identity
  (fn [{:keys [type]} _mind _id _name _text & [_opts]]
    type))

(defmulti get-identity-at
  (fn [{:keys [type]} _mind _id _at]
    type))

(defmulti get-identity-history
  (fn [{:keys [type]} _mind _id]
    type))

(defmulti add-relation
  (fn [{:keys [type]} _mind _source-id _target-id & [_opts]]
    type))

(defmulti list-relations
  (fn [{:keys [type]} _mind _target-id & [_opts]]
    type))

(defmulti delete-relation
  (fn [{:keys [type]} _mind _relation-id]
    type))

(defmulti search-identities
  (fn [{:keys [type]} _mind _query]
    type))
