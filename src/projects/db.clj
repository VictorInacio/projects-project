(ns projects.db
  "Database access layer using next.jdbc with HikariCP pooling.

   Connection pooling rationale:
   - HikariCP is the industry standard, battle-tested
   - For SQLite: small pool (5 connections) since SQLite serializes writes
   - WAL mode enabled for better concurrent read performance
   - Pool provides connection reuse, avoiding open/close overhead"
  (:require [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [next.jdbc.result-set :as rs]
            [clojure.tools.logging :as log])
  (:import [com.zaxxer.hikari HikariDataSource HikariConfig]))

;; -----------------------------------------------------------------------------
;; Connection pool management
;; -----------------------------------------------------------------------------

(defn create-pool
  "Create HikariCP connection pool from config map.
   Config keys: :jdbcUrl, :maximumPoolSize, :connectionInitSql"
  [{:keys [jdbcUrl maximumPoolSize connectionInitSql]}]
  (log/info "Creating connection pool for" jdbcUrl)
  (let [config (doto (HikariConfig.)
                 (.setJdbcUrl jdbcUrl)
                 (.setMaximumPoolSize (or maximumPoolSize 5))
                 (.setConnectionInitSql connectionInitSql)
                 ;; SQLite-specific: disable auto-commit for explicit txn control
                 (.setAutoCommit true))]
    (HikariDataSource. config)))

(defn close-pool
  "Gracefully close connection pool."
  [^HikariDataSource ds]
  (when ds
    (log/info "Closing connection pool")
    (.close ds)))

;; -----------------------------------------------------------------------------
;; Query options - use snake_case keys to match JSON API conventions
;; -----------------------------------------------------------------------------

(def ^:private query-opts
  {:builder-fn rs/as-unqualified-lower-maps})

;; -----------------------------------------------------------------------------
;; Migrations
;; -----------------------------------------------------------------------------

(def ^:private migrations
  "Ordered list of migration SQL statements.
   Using raw SQL for transparency and easy review."
  [;; v001: Create projects table
   "CREATE TABLE IF NOT EXISTS projects (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      status TEXT NOT NULL DEFAULT 'active',
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL,
      CHECK (status IN ('active', 'on-hold', 'archived')),
      CHECK (length(name) >= 1 AND length(name) <= 200)
    )"
   ;; Index for listing queries (sort by created_at)
   "CREATE INDEX IF NOT EXISTS idx_projects_created_at ON projects(created_at DESC)"
   ;; Index for filtering by status
   "CREATE INDEX IF NOT EXISTS idx_projects_status ON projects(status)"])

(defn migrate!
  "Run all migrations. Idempotent due to IF NOT EXISTS."
  [ds]
  (log/info "Running database migrations")
  (doseq [sql migrations]
    (jdbc/execute! ds [sql]))
  (log/info "Migrations complete"))

;; -----------------------------------------------------------------------------
;; Seed data
;; -----------------------------------------------------------------------------

(def ^:private seed-projects
  "Sample projects for development/demo."
  [{:id "550e8400-e29b-41d4-a716-446655440001"
    :name "Website Redesign"
    :status "active"}
   {:id "550e8400-e29b-41d4-a716-446655440002"
    :name "Mobile App v2"
    :status "active"}
   {:id "550e8400-e29b-41d4-a716-446655440003"
    :name "Legacy System Migration"
    :status "on-hold"}
   {:id "550e8400-e29b-41d4-a716-446655440004"
    :name "Q4 2024 Marketing Campaign"
    :status "archived"}
   {:id "550e8400-e29b-41d4-a716-446655440005"
    :name "Internal Tools Platform"
    :status "active"}])

(defn seed!
  "Insert seed data if table is empty."
  [ds]
  (let [count (-> (jdbc/execute-one! ds ["SELECT COUNT(*) as cnt FROM projects"])
                  :cnt)]
    (when (zero? count)
      (log/info "Seeding database with sample projects")
      (let [now (str (java.time.Instant/now))]
        (doseq [p seed-projects]
          (sql/insert! ds :projects
                       (assoc p :created_at now :updated_at now)))))))

;; -----------------------------------------------------------------------------
;; CRUD operations
;; -----------------------------------------------------------------------------

(defn find-project-by-id
  "Fetch a single project by ID. Returns nil if not found."
  [ds id]
  (jdbc/execute-one! ds
                     ["SELECT * FROM projects WHERE id = ?" id]
                     query-opts))

(defn list-projects
  "List projects with pagination and sorting.
   Returns {:data [...] :total n}."
  [ds {:keys [limit offset sort]
       :or {limit 20 offset 0 sort "-created_at"}}]
  (let [;; Parse sort param: '-' prefix means DESC
        [order-col order-dir] (if (.startsWith sort "-")
                                [(subs sort 1) "DESC"]
                                [sort "ASC"])
        ;; Whitelist columns to prevent SQL injection
        order-col (get {"created_at" "created_at"
                        "name" "name"}
                       order-col
                       "created_at")
        ;; Build query with safe interpolation
        query (format "SELECT * FROM projects ORDER BY %s %s LIMIT ? OFFSET ?"
                      order-col order-dir)
        data (jdbc/execute! ds [query limit offset] query-opts)
        total (-> (jdbc/execute-one! ds ["SELECT COUNT(*) as cnt FROM projects"])
                  :cnt)]
    {:data data :total total}))

(defn create-project!
  "Insert a new project. Returns the created project."
  [ds project]
  (sql/insert! ds :projects project query-opts)
  ;; SQLite doesn't return inserted row, so fetch it
  (find-project-by-id ds (:id project)))

(defn project-exists?
  "Check if a project with given ID exists."
  [ds id]
  (some? (jdbc/execute-one! ds
                            ["SELECT 1 FROM projects WHERE id = ?" id])))