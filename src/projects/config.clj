(ns projects.config
  "Application configuration management.
   Loads config from environment with sensible defaults for local dev.")

;; -----------------------------------------------------------------------------
;; Environment helpers
;; -----------------------------------------------------------------------------

(defn env
  "Get environment variable with optional default."
  ([k] (env k nil))
  ([k default] (or (System/getenv k) default)))

(defn env-int
  "Get environment variable as integer."
  ([k] (env-int k nil))
  ([k default]
   (if-let [v (env k)]
     (Integer/parseInt v)
     default)))

;; -----------------------------------------------------------------------------
;; Configuration
;; -----------------------------------------------------------------------------

(defn load-config
  "Load application configuration from environment.
   Returns a map suitable for Integrant system initialization."
  []
  {:server {:port (env-int "PORT" 3000)
            :join? false}  ; Don't block, allows REPL interaction
   :db {:jdbcUrl (str "jdbc:sqlite:" (env "DB_PATH" "projects.db"))
        :maximumPoolSize (env-int "DB_POOL_SIZE" 5)
        ;; SQLite needs these for safe concurrent access
        :connectionInitSql "PRAGMA foreign_keys = ON; PRAGMA journal_mode = WAL;"}
   :api {:version "v1"
         :default-page-size (env-int "DEFAULT_PAGE_SIZE" 20)
         :max-page-size (env-int "MAX_PAGE_SIZE" 100)}})