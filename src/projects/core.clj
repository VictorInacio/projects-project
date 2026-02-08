(ns projects.core
  "Application entry point."
  (:require [projects.system :as system]
            [projects.config :as config]
            [clojure.tools.logging :as log])
  (:gen-class))

(defonce ^:private system-instance (atom nil))

(defn start!
  "Start the application system."
  []
  (when-not @system-instance
    (let [cfg (config/load-config)
          sys (system/start cfg)]
      (reset! system-instance sys)
      (log/info "Projects API started on port" (get-in cfg [:server :port])))))

(defn stop!
  "Stop the application system."
  []
  (when-let [sys @system-instance]
    (system/stop sys)
    (reset! system-instance nil)
    (log/info "Projects API stopped")))

(defn -main
  "Main entry point. Starts server and blocks."
  [& _args]
  (start!)
  ;; Add shutdown hook for graceful termination
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. ^Runnable stop!))
  ;; Block main thread (server runs in background)
  @(promise))