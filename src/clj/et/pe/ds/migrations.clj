(ns et.pe.ds.migrations
  (:require [ragtime.next-jdbc :as ragtime-jdbc]
            [ragtime.repl :as repl]
            [next.jdbc :as jdbc]
            [taoensso.telemere :as tel]))

(defn- wrap-connectable [conn-or-ds]
  (if (instance? java.sql.Connection conn-or-ds)
    (jdbc/with-options conn-or-ds {})
    conn-or-ds))

(defn- migration-config [connectable]
  {:datastore (ragtime-jdbc/sql-database (wrap-connectable connectable))
   :migrations (ragtime-jdbc/load-resources "migrations")})

(defn migrate!
  [connectable]
  (tel/log! :info "Running database migrations...")
  (let [config (migration-config connectable)]
    (repl/migrate config)
    (tel/log! :info "Migrations completed")))

(defn rollback!
  [connectable]
  (tel/log! :info "Rolling back last migration...")
  (let [config (migration-config connectable)]
    (repl/rollback config)
    (tel/log! :info "Rollback completed")))
