(ns et.pe.ds.dispatch)

(defmulti init-conn
  (fn [{:keys [type]}]
    type))

(defmulti close-conn
  (fn [{:keys [type]}]
    type))

(defmulti get-person-by-name-or-email 
  (fn [{:keys [type]} _name _email]
    type))

(defmulti get-person-by-name 
  (fn [{:keys [type]} _name]
    type))

(defmulti get-person-by-email 
  (fn [{:keys [type]} _email]
    type))

(defmulti add-person
  (fn [{:keys [type]} _name _email] 
    type))

(defmulti list-persons
  (fn [{:keys [type]}] 
    type))

(defmulti list-identities
  (fn [{:keys [type]} _mind] 
    type))

(defmulti add-identity
  (fn [{:keys [type]} _mind _id _text] 
    type))
