(ns projects.system
  "System lifecycle management using Integrant.

   Why Integrant:
   - Declarative component dependencies via config map
   - Clean start/stop lifecycle with proper ordering
   - REPL-friendly: easy to restart individual components
   - Simpler than Component, sufficient for this service size

   Component dependency graph:
   :db/pool <- :db/migrator <- :http/server"
  (:require [integrant.core :as ig]
            [ring.adapter.jetty :as jetty]
            [projects.db :as db]
            [projects.routes :as routes]
            [projects.config :as config]
            [clojure.tools.logging :as log]))

;; -----------------------------------------------------------------------------
;; System configuration
;; -----------------------------------------------------------------------------

(defn system-config
  "Build Integrant config from application config."
  [app-config]
  {;; Database connection pool
   :db/pool (:db app-config)

   ;; Run migrations on startup (depends on pool)
   :db/migrator {:pool (ig/ref :db/pool)}

   ;; HTTP server (depends on pool being ready and migrated)
   :http/server {:port (get-in app-config [:server :port])
                 :join? (get-in app-config [:server :join?])
                 :pool (ig/ref :db/pool)
                 :api-config (:api app-config)}})

;; -----------------------------------------------------------------------------
;; Component implementations
;; -----------------------------------------------------------------------------

;; Database connection pool
(defmethod ig/init-key :db/pool
  [_ config]
  (log/info "Starting database connection pool")
  (db/create-pool config))

(defmethod ig/halt-key! :db/pool
  [_ pool]
  (log/info "Stopping database connection pool")
  (db/close-pool pool))

;; Database migrator - runs migrations then returns pool reference
(defmethod ig/init-key :db/migrator
  [_ {:keys [pool]}]
  (log/info "Running database migrations")
  (db/migrate! pool)
  (db/seed! pool)
  pool)  ; Pass-through for dependency chain

(defmethod ig/halt-key! :db/migrator
  [_ _]
  nil)  ; Nothing to clean up

;; HTTP server
(defmethod ig/init-key :http/server
  [_ {:keys [port join? pool api-config]}]
  (log/info "Starting HTTP server on port" port)
  (let [components {:ds pool :api-config api-config}
        app (routes/create-app components)]
    (jetty/run-jetty app {:port port :join? join?})))

(defmethod ig/halt-key! :http/server
  [_ server]
  (log/info "Stopping HTTP server")
  (.stop server))

;; -----------------------------------------------------------------------------
;; System lifecycle
;; -----------------------------------------------------------------------------

(defn start
  "Start the system with given config."
  [config]
  (log/info "Starting Projects API system")
  (ig/init (system-config config)))

(defn stop
  "Stop the system gracefully."
  [system]
  (log/info "Stopping Projects API system")
  (ig/halt! system))