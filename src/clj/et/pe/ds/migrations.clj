(ns et.pe.ds.migrations
  (:require [ragtime.next-jdbc :as ragtime-jdbc]
            [ragtime.repl :as repl]
            [next.jdbc :as jdbc]
            [taoensso.telemere :as tel]))

(defn- wrap-connectable [conn-or-ds]
  (if (instance? java.sql.Connection conn-or-ds)
    (jdbc/with-options conn-or-ds {})
    conn-or-ds))

(defn- reporter
  "Who says \"Applying 001-initial-schema\". Ragtime's own reporter `println`s it,
   which makes it the one line in here that no log level can reach — and the
   suite builds a fresh in-memory database per test, so a run printed the whole
   migration list a hundred times over and buried what the tests had to say.

   Routing it through telemere changes nothing about what is recorded and puts
   every line this namespace produces behind one knob: `:test` in deps.edn turns
   the level down, production keeps the record."
  [_ op id]
  (case op
    :up   (tel/log! :info ["Applying" id])
    :down (tel/log! :info ["Rolling back" id])))

(defn- migration-config [connectable]
  {:datastore (ragtime-jdbc/sql-database (wrap-connectable connectable))
   :migrations (ragtime-jdbc/load-resources "migrations/net/et/pe")
   :reporter reporter})

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
