(ns projects.api-test
  "Integration tests for the Projects API endpoints.
   Uses an in-memory SQLite database for isolation.
   Response bodies are validated against Malli schemas to ensure
   the API contract is enforced end-to-end."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [malli.core :as m]
            [projects.db :as db]
            [projects.handlers :as h]
            [projects.routes :as routes]
            [projects.schema :as schema]
            [ring.mock.request :as mock]
            [clojure.data.json :as json]))

;; -----------------------------------------------------------------------------
;; Test fixtures
;; -----------------------------------------------------------------------------

(def ^:dynamic *ds* nil)
(def ^:dynamic *app* nil)

(defn with-test-db
  "Fixture that creates a fresh in-memory database for each test."
  [f]
  (let [ds (db/create-pool {:jdbcUrl "jdbc:sqlite::memory:"
                            :maximumPoolSize 1
                            :connectionInitSql "PRAGMA foreign_keys = ON;"})]
    (try
      (db/migrate! ds)
      (let [components {:ds ds :api-config {:default-page-size 20}}
            app (routes/create-app components)]
        (binding [*ds* ds *app* app]
          (f)))
      (finally
        (db/close-pool ds)))))

(use-fixtures :each with-test-db)

;; -----------------------------------------------------------------------------
;; Helper functions
;; -----------------------------------------------------------------------------

(defn- json-request
  "Create a JSON request with body as InputStream for wrap-json-body."
  [method uri body]
  (let [json-str (json/write-str body)
        bytes (.getBytes json-str "UTF-8")]
    (-> (mock/request method uri)
        (mock/content-type "application/json")
        (assoc :body (java.io.ByteArrayInputStream. bytes)))))

(defn- parse-body
  "Parse JSON response body. Handles string, stream, byte array, and map bodies."
  [response]
  (when-let [body (:body response)]
    (cond
      ;; Already a map (from Muuntaja in test mode)
      (map? body) body
      ;; Vector (for list responses)
      (vector? body) body
      ;; String (already JSON)
      (string? body) (json/read-str body :key-fn keyword)
      ;; Byte array
      (bytes? body) (json/read-str (String. ^bytes body "UTF-8") :key-fn keyword)
      ;; Input stream
      :else (json/read-str (slurp body) :key-fn keyword))))

(defn- api-call
  "Make an API call and return {:status :body}."
  ([method uri] (api-call method uri nil))
  ([method uri body]
   (let [request (if body
                   (json-request method uri body)
                   (mock/request method uri))
         response (*app* request)]
     {:status (:status response)
      :body (parse-body response)})))

(defn- conforms?
  "Assert that data validates against a Malli schema.
   Returns true/false; use inside (is ...) for clear test output."
  [malli-schema data]
  (m/validate malli-schema data))

;; -----------------------------------------------------------------------------
;; Health endpoint tests
;; -----------------------------------------------------------------------------

(deftest health-endpoint
  (testing "GET /health returns 200"
    (let [{:keys [status body]} (api-call :get "/health")]
      (is (= 200 status))
      (is (= "ok" (:status body))))))

;; -----------------------------------------------------------------------------
;; Create project tests
;; -----------------------------------------------------------------------------

(deftest create-project-happy-path
  (testing "POST /v1/projects creates a project with defaults"
    (let [{:keys [status body]} (api-call :post "/v1/projects"
                                          {:name "Test Project"})]
      (is (= 201 status))
      (is (conforms? schema/Project body) "response must match Project schema")
      (is (= "Test Project" (:name body)))
      (is (= "active" (:status body))))))

(deftest create-project-with-status
  (testing "POST /v1/projects accepts custom status"
    (let [{:keys [status body]} (api-call :post "/v1/projects"
                                          {:name "Archived" :status "archived"})]
      (is (= 201 status))
      (is (conforms? schema/Project body) "response must match Project schema")
      (is (= "archived" (:status body))))))

(deftest create-project-validation-errors
  (testing "rejects empty name"
    (let [{:keys [status body]} (api-call :post "/v1/projects" {:name ""})]
      (is (= 400 status))
      (is (conforms? schema/ErrorResponse body) "error must match ErrorResponse schema")
      (is (= "validation_error" (:error body)))))

  (testing "rejects missing name"
    (let [{:keys [status body]} (api-call :post "/v1/projects" {})]
      (is (= 400 status))
      (is (conforms? schema/ErrorResponse body) "error must match ErrorResponse schema")
      (is (= "validation_error" (:error body)))))

  (testing "rejects invalid status"
    (let [{:keys [status body]} (api-call :post "/v1/projects"
                                          {:name "Test" :status "invalid"})]
      (is (= 400 status))
      (is (conforms? schema/ErrorResponse body) "error must match ErrorResponse schema")))

  (testing "rejects name over 200 chars"
    (let [long-name (apply str (repeat 201 "a"))
          {:keys [status body]} (api-call :post "/v1/projects" {:name long-name})]
      (is (= 400 status))
      (is (conforms? schema/ErrorResponse body) "error must match ErrorResponse schema"))))

(deftest create-project-conflict
  (testing "409 when generated UUID collides with existing project"
    ;; Create a project and capture its ID
    (let [{:keys [body]} (api-call :post "/v1/projects" {:name "First"})
          existing-id (:id body)]
      ;; Force the next create to generate the same UUID
      (with-redefs [h/generate-id (constantly existing-id)]
        (let [{:keys [status body]} (api-call :post "/v1/projects"
                                              {:name "Collider"})]
          (is (= 409 status))
          (is (conforms? schema/ErrorResponse body) "error must match ErrorResponse schema")
          (is (= "conflict" (:error body))))))))

;; -----------------------------------------------------------------------------
;; Get project tests
;; -----------------------------------------------------------------------------

(deftest get-project-happy-path
  (testing "GET /v1/projects/:id returns created project"
    ;; First create a project
    (let [{create-body :body} (api-call :post "/v1/projects"
                                        {:name "Fetch Me"})
          id (:id create-body)
          ;; Then fetch it
          {:keys [status body]} (api-call :get (str "/v1/projects/" id))]
      (is (= 200 status))
      (is (conforms? schema/Project body) "response must match Project schema")
      (is (= id (:id body)))
      (is (= "Fetch Me" (:name body))))))

(deftest get-project-not-found
  (testing "GET /v1/projects/:id returns 404 for unknown ID"
    (let [{:keys [status body]} (api-call :get "/v1/projects/00000000-0000-0000-0000-000000000000")]
      (is (= 404 status))
      (is (conforms? schema/ErrorResponse body) "error must match ErrorResponse schema")
      (is (= "not_found" (:error body))))))

;; -----------------------------------------------------------------------------
;; List projects tests
;; -----------------------------------------------------------------------------

(deftest list-projects-empty
  (testing "GET /v1/projects returns empty list initially"
    (let [{:keys [status body]} (api-call :get "/v1/projects")]
      (is (= 200 status))
      (is (conforms? schema/PaginatedResponse body) "response must match PaginatedResponse schema")
      (is (= [] (:data body)))
      (is (= 0 (get-in body [:pagination :total]))))))

(deftest list-projects-with-data
  (testing "GET /v1/projects returns created projects"
    ;; Create two projects
    (api-call :post "/v1/projects" {:name "Project A"})
    (api-call :post "/v1/projects" {:name "Project B"})

    (let [{:keys [status body]} (api-call :get "/v1/projects")]
      (is (= 200 status))
      (is (conforms? schema/PaginatedResponse body) "response must match PaginatedResponse schema")
      (is (= 2 (count (:data body))))
      (is (= 2 (get-in body [:pagination :total]))))))

(deftest list-projects-pagination
  (testing "pagination with limit and offset"
    ;; Create 5 projects
    (dotimes [i 5]
      (api-call :post "/v1/projects" {:name (str "Project " i)}))

    ;; Get first page
    (let [{body :body} (api-call :get "/v1/projects?limit=2&offset=0")]
      (is (conforms? schema/PaginatedResponse body) "response must match PaginatedResponse schema")
      (is (= 2 (count (:data body))))
      (is (= 5 (get-in body [:pagination :total])))
      (is (= 2 (get-in body [:pagination :limit])))
      (is (= 0 (get-in body [:pagination :offset]))))

    ;; Get second page
    (let [{body :body} (api-call :get "/v1/projects?limit=2&offset=2")]
      (is (conforms? schema/PaginatedResponse body) "response must match PaginatedResponse schema")
      (is (= 2 (count (:data body))))
      (is (= 2 (get-in body [:pagination :offset]))))))

(deftest list-projects-sorting
  (testing "sorting by name ascending"
    (api-call :post "/v1/projects" {:name "Zebra"})
    (api-call :post "/v1/projects" {:name "Apple"})

    (let [{body :body} (api-call :get "/v1/projects?sort=name")]
      (is (conforms? schema/PaginatedResponse body) "response must match PaginatedResponse schema")
      (let [names (map :name (:data body))]
        (is (= ["Apple" "Zebra"] names))))))

;; -----------------------------------------------------------------------------
;; Edge case tests
;; -----------------------------------------------------------------------------

(deftest unicode-project-name
  (testing "accepts unicode characters in name"
    (let [{:keys [status body]} (api-call :post "/v1/projects"
                                          {:name "Proyecto España 日本語"})]
      (is (= 201 status))
      (is (conforms? schema/Project body) "response must match Project schema")
      (is (= "Proyecto España 日本語" (:name body))))))

(deftest whitespace-handling
  (testing "trims whitespace from name"
    (let [{:keys [status body]} (api-call :post "/v1/projects"
                                          {:name "  Trimmed Name  "})]
      (is (= 201 status))
      (is (conforms? schema/Project body) "response must match Project schema")
      (is (= "Trimmed Name" (:name body))))))

(deftest not-found-endpoint
  (testing "unknown endpoint returns 404"
    (let [{:keys [status body]} (api-call :get "/v1/unknown")]
      (is (= 404 status))
      (is (= "not_found" (:error body))))))