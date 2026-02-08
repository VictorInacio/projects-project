(ns projects.handlers
  "HTTP request handlers for the Projects API.

   Error handling strategy:
   - 400 Bad Request: malformed JSON, validation failures
   - 404 Not Found: resource doesn't exist
   - 409 Conflict: ID collision (unlikely with UUIDs)
   - 422 Unprocessable Entity: business rule violations"
  (:require [projects.db :as db]
            [projects.schema :as schema]
            [clojure.tools.logging :as log])
  (:import [java.util UUID]))

;; -----------------------------------------------------------------------------
;; Response helpers
;; -----------------------------------------------------------------------------

(defn- json-response
  "Build a Ring response with JSON content type."
  [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body body})

(defn- error-response
  "Build a standardized error response."
  ([status error message]
   (error-response status error message nil))
  ([status error message details]
   (json-response status
                  (cond-> {:error error :message message}
                    details (assoc :details details)))))

(defn- validation-error-details
  "Convert Malli error map to error details list."
  [errors]
  (reduce-kv
   (fn [acc field msgs]
     (into acc (map (fn [msg] {:field (name field) :message msg}) msgs)))
   []
   errors))

;; -----------------------------------------------------------------------------
;; Handlers
;; -----------------------------------------------------------------------------

(defn list-projects
  "GET /v1/projects - List all projects with pagination.

   Query params:
   - limit: max items per page (1-100, default 20)
   - offset: skip N items (default 0)
   - sort: 'created_at', '-created_at', 'name', '-name' (default: -created_at)"
  [{:keys [ds api-config]} request]
  (let [params (get-in request [:parameters :query])
        {:keys [default-page-size]} api-config
        limit (or (:limit params) default-page-size)
        offset (or (:offset params) 0)
        sort (or (:sort params) "-created_at")
        {:keys [data total]} (db/list-projects ds {:limit limit
                                                   :offset offset
                                                   :sort sort})]
    (json-response 200
                   {:data data
                    :pagination {:total total
                                 :limit limit
                                 :offset offset}})))

(defn get-project
  "GET /v1/projects/{id} - Fetch a single project by ID."
  [{:keys [ds]} request]
  (let [id (get-in request [:parameters :path :id])
        project (db/find-project-by-id ds id)]
    (if project
      (json-response 200 project)
      (error-response 404 "not_found"
                      (str "Project with id '" id "' not found")))))

(defn create-project
  "POST /v1/projects - Create a new project.

   Request body:
   - name (required): 1-200 characters
   - status (optional): 'active' (default), 'on-hold', or 'archived'"
  [{:keys [ds]} request]
  (let [body (get-in request [:parameters :body])
        ;; Validate with Malli schema
        validation (schema/validate schema/ProjectCreate body)]
    (if-let [errors (:error validation)]
      ;; Validation failed
      (error-response 400 "validation_error"
                      "Invalid request body"
                      (validation-error-details errors))
      ;; Valid - create project
      (let [data (:ok validation)
            now (str (java.time.Instant/now))
            project {:id (str (UUID/randomUUID))
                     :name (:name data)
                     :status (or (:status data) "active")
                     :created_at now
                     :updated_at now}]
        ;; Check for ID collision (extremely unlikely with UUIDs)
        (if (db/project-exists? ds (:id project))
          (error-response 409 "conflict"
                          "A project with this ID already exists")
          (let [created (db/create-project! ds project)]
            (log/info "Created project" (:id created))
            (json-response 201 created)))))))

;; -----------------------------------------------------------------------------
;; Health check
;; -----------------------------------------------------------------------------

(defn health
  "GET /health - Basic health check endpoint."
  [_ _]
  (json-response 200 {:status "ok"}))