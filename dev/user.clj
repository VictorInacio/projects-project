(ns user
  "REPL development namespace.

   Usage:
     (start)  ; Start the system
     (stop)   ; Stop the system
     (reset)  ; Stop, reload code, start"
  (:require [integrant.repl :refer [clear halt go init prep reset reset-all]]
            [integrant.repl.state :refer [system]]
            [projects.config :as config]
            [projects.system :as sys]))

;; Configure Integrant REPL with our system config
(integrant.repl/set-prep!
 #(sys/system-config (config/load-config)))

;; Convenience aliases
(def start go)
(def stop halt)

;; Quick access to system components
(defn db [] (:db/pool system))
(defn server [] (:http/server system))

(comment
  ;; REPL workflow examples:
  (start)   ; Start everything
  (stop)    ; Stop everything
  (reset)   ; Reload and restart

  ;; Test database queries
  (require '[projects.db :as db])
  (db/list-projects (db) {})
  (db/find-project-by-id (db) "550e8400-e29b-41d4-a716-446655440001")

  ;; Test with curl:
  ;; curl http://localhost:3000/v1/projects
  ;; curl http://localhost:3000/v1/projects/550e8400-e29b-41d4-a716-446655440001
  ;; curl -X POST http://localhost:3000/v1/projects -H "Content-Type: application/json" -d '{"name":"Test"}'
  )