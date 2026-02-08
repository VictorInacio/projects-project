(ns projects.routes
  "API routes with Reitit + Malli coercion.

   Versioning strategy: URL path prefix (/v1/...)
   Rationale:
   - Explicit and visible in logs, docs, client code
   - Easy to route different versions to different handlers
   - Cacheable (unlike header-based versioning)
   - Simple to deprecate: add /v2, eventually remove /v1"
  (:require [reitit.ring :as ring]
            [reitit.coercion.malli :as malli-coercion]
            [reitit.ring.coercion :as coercion]
            [reitit.ring.middleware.parameters :as parameters]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [muuntaja.core :as m]
            [projects.handlers :as h]
            [projects.schema :as schema]
            [clojure.tools.logging :as log]))

;; -----------------------------------------------------------------------------
;; Exception handling middleware
;; -----------------------------------------------------------------------------

(defn- make-error-handler
  "Generic error handler that logs and returns JSON error."
  [message status]
  (fn [exception _request]
    (log/error exception message)
    {:status status
     :headers {"Content-Type" "application/json"}
     :body {:error (if (= status 500) "internal_error" "request_error")
            :message message}}))

(def exception-middleware
  "Exception handling middleware configuration."
  (exception/create-exception-middleware
   (merge
    exception/default-handlers
    {;; Coercion/validation errors -> 400
     :reitit.coercion/request-coercion
     (fn [ex _]
       {:status 400
        :headers {"Content-Type" "application/json"}
        :body {:error "validation_error"
               :message "Invalid request parameters"
               :details (-> ex ex-data :errors)}})

     ;; Malformed JSON -> 400
     ::exception/default
     (make-error-handler "Internal server error" 500)

     ;; Unexpected errors -> 500
     java.lang.Exception
     (make-error-handler "Internal server error" 500)})))

;; -----------------------------------------------------------------------------
;; Route definitions
;; -----------------------------------------------------------------------------

(defn api-routes
  "Build API routes with component injection."
  [components]
  [["/health"
    {:get {:handler (partial h/health components)
           :summary "Health check"
           :responses {200 {:body [:map [:status :string]]}}}}]

   ;; API v1 - versioned routes
   ["/v1"
    ["/projects"
     {:tags ["projects"]}

     ;; GET & POST /v1/projects - List and create
     [""
      {:get {:handler (partial h/list-projects components)
             :summary "List all projects"
             :parameters {:query schema/PaginationParams}
             :responses {200 {:body schema/PaginatedResponse}}}
       :post {:handler (partial h/create-project components)
              :summary "Create a new project"
              :parameters {:body schema/ProjectCreate}
              :responses {201 {:body schema/Project}
                          400 {:body schema/ErrorResponse}
                          409 {:body schema/ErrorResponse}}}}]

     ;; GET /v1/projects/:id - Get single project
     ["/:id"
      {:get {:handler (partial h/get-project components)
             :summary "Get a project by ID"
             :parameters {:path [:map [:id schema/ProjectId]]}
             :responses {200 {:body schema/Project}
                         404 {:body schema/ErrorResponse}}}}]]]])

;; -----------------------------------------------------------------------------
;; Router creation
;; -----------------------------------------------------------------------------

(defn create-router
  "Create Reitit router with all middleware configured."
  [components]
  (ring/router
   (api-routes components)
   {:data {:coercion malli-coercion/coercion
           :muuntaja m/instance
           ;; Middleware order (inside-out): last wraps innermost
           ;; Request:  params -> negotiate -> resp-fmt -> exception -> req-fmt -> coerce -> handler
           ;; Response: handler -> coerce -> exception -> resp-fmt -> negotiate
           :middleware [parameters/parameters-middleware
                        muuntaja/format-negotiate-middleware
                        muuntaja/format-response-middleware
                        exception-middleware
                        muuntaja/format-request-middleware
                        coercion/coerce-request-middleware
                        coercion/coerce-response-middleware]}}))

(defn create-app
  "Create Ring handler."
  [components]
  (ring/ring-handler
   (create-router components)
   (ring/create-default-handler
    {:not-found (constantly {:status 404
                             :headers {"Content-Type" "application/json"}
                             :body "{\"error\":\"not_found\",\"message\":\"Endpoint not found\"}"})})))
