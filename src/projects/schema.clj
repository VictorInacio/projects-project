(ns projects.schema
  "Malli schemas for request/response validation.

   Design decisions:
   - ID: UUID string - no coordination needed, safe for distributed systems,
     works well with SQLite (stored as TEXT), URL-safe
   - Status: enum with 'active' default, 'archived' as terminal state
   - Name: 1-200 chars, trimmed, non-blank (allows unicode)"
  (:require [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]
            [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Primitive schemas
;; -----------------------------------------------------------------------------

(def NonBlankString
  "Non-empty string after trimming whitespace."
  [:string {:min 1}])

(def ProjectId
  "UUID v4 as string - URL-safe, no coordination needed."
  [:string {:min 36 :max 36}])

(def ProjectName
  "Human-readable project name: 1-200 chars, trimmed, non-blank.
   Allows unicode for international names."
  [:string {:min 1 :max 200}])

(def ProjectStatus
  "Project lifecycle status.
   - active: default, project is ongoing
   - on-hold: temporarily paused
   - archived: terminal state, project is complete/cancelled"
  [:enum "active" "on-hold" "archived"])

(def Timestamp
  "ISO-8601 timestamp string."
  :string)

;; -----------------------------------------------------------------------------
;; Domain schemas
;; -----------------------------------------------------------------------------

(def Project
  "Complete project entity as returned by API."
  [:map {:closed true}
   [:id ProjectId]
   [:name ProjectName]
   [:status ProjectStatus]
   [:created_at Timestamp]
   [:updated_at Timestamp]])

(def ProjectCreate
  "Schema for POST /projects request body.
   Name required; status optional (defaults to 'active')."
  [:map {:closed true}
   [:name ProjectName]
   [:status {:optional true} ProjectStatus]])

;; -----------------------------------------------------------------------------
;; Pagination schemas
;; -----------------------------------------------------------------------------

(def PaginationParams
  "Query params for list pagination (limit/offset style).
   Simple, well-understood, sufficient for modest data sizes."
  [:map
   [:limit {:optional true} [:int {:min 1 :max 100}]]
   [:offset {:optional true} [:int {:min 0}]]
   [:sort {:optional true} [:enum "created_at" "-created_at" "name" "-name"]]])

(def PaginatedResponse
  "Wrapper for paginated list responses."
  [:map
   [:data [:vector Project]]
   [:pagination [:map
                 [:total :int]
                 [:limit :int]
                 [:offset :int]]]])

;; -----------------------------------------------------------------------------
;; Error schemas
;; -----------------------------------------------------------------------------

(def ErrorDetail
  "Single validation error detail."
  [:map
   [:field {:optional true} :string]
   [:message :string]])

(def ErrorResponse
  "Standard error response format.
   Uses RFC 7807 'Problem Details' style."
  [:map
   [:error :string]           ; Error type/code
   [:message :string]         ; Human-readable message
   [:details {:optional true} [:vector ErrorDetail]]])

;; -----------------------------------------------------------------------------
;; Validation helpers
;; -----------------------------------------------------------------------------

(def string-transformer
  "Transformer that trims strings and coerces types."
  (mt/transformer
   mt/string-transformer
   mt/strip-extra-keys-transformer
   {:name :string-trimmer
    :decoders {:string str/trim}
    :encoders {:string str/trim}}))

(defn validate
  "Validate data against schema, returns {:ok data} or {:error details}."
  [schema data]
  (let [decoded (m/decode schema data string-transformer)]
    (if (m/validate schema decoded)
      {:ok decoded}
      {:error (me/humanize (m/explain schema decoded))})))

(defn coerce
  "Coerce and validate, throwing on failure. For internal use."
  [schema data]
  (let [result (validate schema data)]
    (if (:ok result)
      (:ok result)
      (throw (ex-info "Validation failed" {:errors (:error result)})))))