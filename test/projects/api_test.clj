(ns projects.api-test
  "Integration tests for the Projects API endpoints.
   Uses an in-memory SQLite database for isolation.
   Response bodies are validated against Malli schemas to ensure
   the API contract is enforced end-to-end.

   Test organization:
   - Happy-path tests: verify correct behavior for valid inputs
   - Validation & error tests: verify 400/404/409 responses
   - Edge-case tests: boundary conditions identified during design"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [malli.core :as m]
            [projects.db :as db]
            [projects.handlers :as h]
            [projects.routes :as routes]
            [projects.schema :as schema]
            [ring.mock.request :as mock]
            [clojure.data.json :as json]))

;; -----------------------------------------------------------------------------
;; Test fixtures — fresh in-memory SQLite per test
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
  "Create a JSON request with body as InputStream for Muuntaja decoding."
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
      (map? body) body
      (vector? body) body
      (string? body) (json/read-str body :key-fn keyword)
      (bytes? body) (json/read-str (String. ^bytes body "UTF-8") :key-fn keyword)
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

;; =============================================================================
;; HAPPY-PATH TESTS — verify correct behavior for valid inputs
;; =============================================================================

;; -----------------------------------------------------------------------------
;; Health endpoint
;; -----------------------------------------------------------------------------

(deftest health-endpoint
  (testing "GET /health returns 200 with ok status"
    (let [{:keys [status body]} (api-call :get "/health")]
      (is (= 200 status))
      (is (= "ok" (:status body))))))

;; -----------------------------------------------------------------------------
;; Create project — happy paths
;; -----------------------------------------------------------------------------

(deftest create-project-happy-path
  (testing "POST /v1/projects with name only → 201, defaults status to 'active'"
    (let [{:keys [status body]} (api-call :post "/v1/projects"
                                          {:name "Test Project"})]
      (is (= 201 status))
      (is (conforms? schema/Project body) "response must match Project schema")
      (is (= "Test Project" (:name body)))
      (is (= "active" (:status body)))
      (is (some? (:id body)) "must have a generated UUID")
      (is (some? (:created_at body)) "must have a server timestamp")
      (is (some? (:updated_at body)) "must have an updated_at timestamp"))))

(deftest create-project-with-status
  (testing "POST /v1/projects with explicit status → 201, uses provided status"
    (doseq [s ["active" "on-hold" "archived"]]
      (let [{:keys [status body]} (api-call :post "/v1/projects"
                                            {:name (str "Project " s)
                                             :status s})]
        (is (= 201 status) (str "status " s " should succeed"))
        (is (conforms? schema/Project body) "response must match Project schema")
        (is (= s (:status body)) (str "should store status '" s "'"))))))

;; -----------------------------------------------------------------------------
;; Get project — happy path
;; -----------------------------------------------------------------------------

(deftest get-project-happy-path
  (testing "GET /v1/projects/:id → 200, returns the created project"
    (let [{create-body :body} (api-call :post "/v1/projects"
                                        {:name "Fetch Me"})
          id (:id create-body)
          {:keys [status body]} (api-call :get (str "/v1/projects/" id))]
      (is (= 200 status))
      (is (conforms? schema/Project body) "response must match Project schema")
      (is (= id (:id body)))
      (is (= "Fetch Me" (:name body)))
      (is (= "active" (:status body))))))

;; -----------------------------------------------------------------------------
;; List projects — happy paths
;; -----------------------------------------------------------------------------

(deftest list-projects-empty
  (testing "GET /v1/projects → 200, empty list when no projects exist"
    (let [{:keys [status body]} (api-call :get "/v1/projects")]
      (is (= 200 status))
      (is (conforms? schema/PaginatedResponse body) "response must match PaginatedResponse schema")
      (is (= [] (:data body)))
      (is (= 0 (get-in body [:pagination :total]))))))

(deftest list-projects-with-data
  (testing "GET /v1/projects → 200, returns all created projects"
    (api-call :post "/v1/projects" {:name "Project A"})
    (api-call :post "/v1/projects" {:name "Project B"})

    (let [{:keys [status body]} (api-call :get "/v1/projects")]
      (is (= 200 status))
      (is (conforms? schema/PaginatedResponse body) "response must match PaginatedResponse schema")
      (is (= 2 (count (:data body))))
      (is (= 2 (get-in body [:pagination :total]))))))

(deftest list-projects-pagination
  (testing "GET /v1/projects with limit/offset → correct page slicing"
    (dotimes [i 5]
      (api-call :post "/v1/projects" {:name (str "Project " i)}))

    ;; First page: 2 items starting at 0
    (let [{body :body} (api-call :get "/v1/projects?limit=2&offset=0")]
      (is (conforms? schema/PaginatedResponse body) "response must match PaginatedResponse schema")
      (is (= 2 (count (:data body))))
      (is (= 5 (get-in body [:pagination :total])))
      (is (= 2 (get-in body [:pagination :limit])))
      (is (= 0 (get-in body [:pagination :offset]))))

    ;; Second page: 2 items starting at 2
    (let [{body :body} (api-call :get "/v1/projects?limit=2&offset=2")]
      (is (conforms? schema/PaginatedResponse body) "response must match PaginatedResponse schema")
      (is (= 2 (count (:data body))))
      (is (= 2 (get-in body [:pagination :offset]))))

    ;; Last page: 1 item at offset 4
    (let [{body :body} (api-call :get "/v1/projects?limit=2&offset=4")]
      (is (conforms? schema/PaginatedResponse body) "response must match PaginatedResponse schema")
      (is (= 1 (count (:data body)))))))

(deftest list-projects-sorting
  (testing "sort=name → ascending alphabetical order"
    (api-call :post "/v1/projects" {:name "Zebra"})
    (api-call :post "/v1/projects" {:name "Apple"})
    (api-call :post "/v1/projects" {:name "Mango"})

    (let [{body :body} (api-call :get "/v1/projects?sort=name")]
      (is (conforms? schema/PaginatedResponse body) "response must match PaginatedResponse schema")
      (is (= ["Apple" "Mango" "Zebra"] (mapv :name (:data body))))))

  (testing "sort=-name → descending alphabetical order"
    (let [{body :body} (api-call :get "/v1/projects?sort=-name")]
      (is (conforms? schema/PaginatedResponse body) "response must match PaginatedResponse schema")
      (is (= ["Zebra" "Mango" "Apple"] (mapv :name (:data body)))))))

;; =============================================================================
;; VALIDATION & ERROR TESTS — verify 400/404/409 responses
;; =============================================================================

;; -----------------------------------------------------------------------------
;; 400 Bad Request — invalid input
;; -----------------------------------------------------------------------------

(deftest create-project-validation-errors
  (testing "400 — empty name"
    (let [{:keys [status body]} (api-call :post "/v1/projects" {:name ""})]
      (is (= 400 status))
      (is (conforms? schema/ErrorResponse body) "error must match ErrorResponse schema")
      (is (= "validation_error" (:error body)))))

  (testing "400 — missing name (empty body)"
    (let [{:keys [status body]} (api-call :post "/v1/projects" {})]
      (is (= 400 status))
      (is (conforms? schema/ErrorResponse body) "error must match ErrorResponse schema")
      (is (= "validation_error" (:error body)))))

  (testing "400 — invalid status value"
    (let [{:keys [status body]} (api-call :post "/v1/projects"
                                          {:name "Test" :status "invalid"})]
      (is (= 400 status))
      (is (conforms? schema/ErrorResponse body) "error must match ErrorResponse schema")))

  (testing "400 — name exceeds 200 characters"
    (let [long-name (apply str (repeat 201 "a"))
          {:keys [status body]} (api-call :post "/v1/projects" {:name long-name})]
      (is (= 400 status))
      (is (conforms? schema/ErrorResponse body) "error must match ErrorResponse schema"))))

;; -----------------------------------------------------------------------------
;; 404 Not Found
;; -----------------------------------------------------------------------------

(deftest get-project-not-found
  (testing "404 — project with unknown UUID"
    (let [{:keys [status body]} (api-call :get "/v1/projects/00000000-0000-0000-0000-000000000000")]
      (is (= 404 status))
      (is (conforms? schema/ErrorResponse body) "error must match ErrorResponse schema")
      (is (= "not_found" (:error body)))
      (is (re-find #"00000000-0000-0000-0000-000000000000" (:message body))
          "error message should include the requested ID"))))

(deftest not-found-endpoint
  (testing "404 — unknown API endpoint"
    (let [{:keys [status body]} (api-call :get "/v1/unknown")]
      (is (= 404 status))
      (is (= "not_found" (:error body))))))

;; -----------------------------------------------------------------------------
;; 409 Conflict — UUID collision
;; -----------------------------------------------------------------------------

(deftest create-project-conflict
  (testing "409 — UUID collision returns conflict error"
    ;; Create a project and capture its ID
    (let [{:keys [body]} (api-call :post "/v1/projects" {:name "First"})
          existing-id (:id body)]
      ;; Force the next create to generate the same UUID via with-redefs
      (with-redefs [h/generate-id (constantly existing-id)]
        (let [{:keys [status body]} (api-call :post "/v1/projects"
                                              {:name "Collider"})]
          (is (= 409 status))
          (is (conforms? schema/ErrorResponse body) "error must match ErrorResponse schema")
          (is (= "conflict" (:error body))))))))

;; =============================================================================
;; EDGE-CASE TESTS — boundary conditions identified during design
;; =============================================================================

(deftest unicode-project-name
  (testing "accepts unicode characters in project name"
    (let [{:keys [status body]} (api-call :post "/v1/projects"
                                          {:name "Proyecto España 日本語"})]
      (is (= 201 status))
      (is (conforms? schema/Project body) "response must match Project schema")
      (is (= "Proyecto España 日本語" (:name body))))))

(deftest whitespace-handling
  (testing "trims leading/trailing whitespace from name"
    (let [{:keys [status body]} (api-call :post "/v1/projects"
                                          {:name "  Trimmed Name  "})]
      (is (= 201 status))
      (is (conforms? schema/Project body) "response must match Project schema")
      (is (= "Trimmed Name" (:name body)))))

  (testing "rejects whitespace-only name after trimming"
    (let [{:keys [status body]} (api-call :post "/v1/projects"
                                          {:name "   "})]
      (is (= 400 status))
      (is (conforms? schema/ErrorResponse body) "error must match ErrorResponse schema"))))

(deftest name-boundary-length
  (testing "accepts name at exactly 200 characters (boundary)"
    (let [max-name (apply str (repeat 200 "a"))
          {:keys [status body]} (api-call :post "/v1/projects" {:name max-name})]
      (is (= 201 status))
      (is (conforms? schema/Project body) "response must match Project schema")
      (is (= 200 (count (:name body)))))))

(deftest closed-map-rejects-unknown-keys
  (testing "extra keys in request body are silently stripped (closed Malli map)"
    (let [{:keys [status body]} (api-call :post "/v1/projects"
                                          {:name "Clean" :bogus "ignored" :extra 42})]
      (is (= 201 status))
      (is (conforms? schema/Project body) "response must match Project schema")
      (is (nil? (:bogus body)) "unknown key should not appear in response")
      (is (nil? (:extra body)) "unknown key should not appear in response"))))

(deftest create-and-fetch-roundtrip
  (testing "created project is retrievable and data matches"
    (let [{create-body :body} (api-call :post "/v1/projects"
                                        {:name "Roundtrip" :status "on-hold"})
          {:keys [status body]} (api-call :get (str "/v1/projects/" (:id create-body)))]
      (is (= 200 status))
      (is (= (:id create-body) (:id body)))
      (is (= "Roundtrip" (:name body)))
      (is (= "on-hold" (:status body)))
      (is (= (:created_at create-body) (:created_at body))))))
