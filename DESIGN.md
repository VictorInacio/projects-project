# Projects API - Design Document

## 1. Problem Definition

### Acceptance Criteria

**Functional:**
- `GET /v1/projects` - List projects with pagination (limit/offset) and sorting
- `POST /v1/projects` - Create a project with validated name, optional status
- `GET /v1/projects/{id}` - Fetch a single project by UUID
- All responses return consistent JSON format
- Validation errors return 400 with detailed field errors
- Not found returns 404 with meaningful message
- ID collisions return 409

**Non-Functional:**
- SQLite file-based database for local development
- Connection pooling via HikariCP
- Clean startup/shutdown lifecycle via Integrant
- API versioning via URL path prefix
- OpenAPI 3.1.1 specification
- Malli schemas for request and response validation
- Malli schema coercion verified in integration tests

### Out of Scope
- Authentication/authorization
- Project updates (PUT/PATCH) and deletes
- Filtering by status (listed as optional extension)
- Milestones sub-resource
- Production deployment configuration
- CI/CD pipeline

### Ambiguities Resolved

| Question | Decision |
|----------|----------|
| Pagination style? | Limit/offset - simple, sufficient for expected data sizes |
| ID type? | UUID v4 string - no coordination, URL-safe, works with SQLite |
| Default page size? | 20 items (configurable via env) |
| Name validation? | 1-200 chars, trimmed, allows unicode |
| Status values? | `active` (default), `on-hold`, `archived` (terminal) |
| Error payloads? | RFC 7807-style: `error` code + `message` + optional `details` |
| 400 vs 422? | 400 for all input errors (simpler, widely understood) |

---

## 2. Data Model

### Projects Table

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | TEXT | PRIMARY KEY | UUID v4 (36 chars) |
| `name` | TEXT | NOT NULL, 1-200 chars | Human-readable name |
| `status` | TEXT | NOT NULL, CHECK | Enum: active, on-hold, archived |
| `created_at` | TEXT | NOT NULL | ISO-8601 timestamp |
| `updated_at` | TEXT | NOT NULL | ISO-8601 timestamp |

**Why `updated_at`?** Added beyond the minimal spec for audit trail and to
support future PATCH/PUT operations. Standard practice for mutable entities;
cheap to store and expensive to retrofit later.

### ID Strategy: UUID v4

**Rationale:**
- No coordination required (important for distributed systems)
- Works well with SQLite (stored as TEXT, simple string comparison)
- URL-safe without encoding
- Collision probability negligible (2^122 space)
- Portable across databases (SQLite dev -> Snowflake prod)

**Trade-offs:**
- Larger than integers (36 bytes vs 4-8 bytes) - acceptable for this scale
- Not sortable by creation order - use `created_at` index instead

### SQLite Choice

**Why SQLite over DuckDB:**
- Simpler JDBC setup, better Clojure library support
- Sufficient for CRUD operations (DuckDB optimized for analytics)
- WAL mode provides good concurrent read performance
- Widely understood, easier to debug

---

## 3. API Contract

### Endpoints

| Method | Path | Description | Status Codes |
|--------|------|-------------|--------------|
| GET | /health | Health check | 200 |
| GET | /v1/projects | List projects | 200 |
| POST | /v1/projects | Create project | 201, 400, 409 |
| GET | /v1/projects/{id} | Get project | 200, 404 |

### Status Codes

| Code | Meaning | When | Source |
|------|---------|------|--------|
| **200** | OK | Successful GET | `handlers.clj` |
| **201** | Created | Successful POST | `handlers.clj` |
| **400** | Bad Request | Missing/invalid fields, malformed JSON | Reitit coercion middleware + handler validation |
| **404** | Not Found | Unknown project ID or unknown endpoint | `handlers.clj` + default handler in `routes.clj` |
| **409** | Conflict | UUID collision on create (extremely rare) | `handlers.clj` |
| **500** | Internal Error | Unexpected exceptions | Exception middleware in `routes.clj` |

All error codes are covered by integration tests, including 409 which is tested by forcing a UUID collision via `with-redefs`.

### Error Model

All errors return a consistent JSON envelope:

```json
{
  "error": "validation_error",
  "message": "Human readable message",
  "details": [{"field": "name", "message": "specific error"}]
}
```

Error codes: `validation_error` (400), `not_found` (404), `conflict` (409), `internal_error` (500).

### Versioning Strategy: URL Path Prefix

**Choice:** `/v1/projects`

**Rationale:**
- Explicit and visible in logs, documentation, client code
- Easy to route different versions to different handlers
- HTTP-cacheable (unlike header-based versioning)
- Simple deprecation: add `/v2`, eventually remove `/v1`

**Trade-offs:**
- URLs change between versions (acceptable)
- Can't version individual resources differently (not needed here)

### OpenAPI Spec

See [openapi.yaml](./openapi.yaml) for the complete specification.

---

## 4. Validation

### Malli Schemas

All request and response validation is driven by [Malli](https://github.com/metosin/malli) schemas defined in `schema.clj`. Schema vars use **PascalCase** (`ProjectCreate`, `PaginationParams`) - this is the idiomatic Malli convention, mirroring how type/record names work in Clojure and visually distinguishing schema definitions from regular functions (which use `kebab-case`).

| Schema | Role | Used in |
|--------|------|---------|
| `Project` | Full entity (closed map) | Response coercion for GET endpoints |
| `ProjectCreate` | POST body (closed map) | Request coercion + handler validation |
| `PaginationParams` | Query params for listing | Request coercion on GET /v1/projects |
| `PaginatedResponse` | List response wrapper | Response coercion |
| `ErrorResponse` | Error envelope | Response coercion on 400/404/409 |
| `ProjectId` | UUID string (36 chars) | Path param coercion |
| `ProjectName` | 1-200 char string | Reused in `Project` and `ProjectCreate` |
| `ProjectStatus` | Enum: `active` / `on-hold` / `archived` | Reused in `Project` and `ProjectCreate` |

Schemas are composed bottom-up: primitive schemas (`ProjectName`, `ProjectStatus`) are combined into domain schemas (`Project`, `ProjectCreate`), which are referenced in route definitions for automatic Reitit coercion. Closed maps (`:closed true`) reject unknown keys, preventing typos from silently passing through.

A custom `string-transformer` chains Malli's built-in transformers with whitespace trimming, so `"  My Project  "` is coerced to `"My Project"` before validation.

### How Coercion Works

Schemas are enforced at two levels:

1. **Reitit middleware (runtime)** - `coerce-request-middleware` and `coerce-response-middleware` automatically validate every incoming request and outgoing response against the schemas declared in route definitions. Invalid requests are rejected with 400 before reaching the handler; invalid responses surface as 500 errors, catching contract drift early.

2. **Integration tests (build time)** - Every happy-path and error test asserts `(is (conforms? schema/Project body))` or `(is (conforms? schema/ErrorResponse body))`, validating that the actual API output matches the same Malli schemas used at runtime. This closes the loop: if a handler returns a field with the wrong type or omits a required key, the test suite catches it even without a running server.

```clojure
;; In api_test.clj - same schemas used in routes and tests
(is (conforms? schema/Project body)          "response must match Project schema")
(is (conforms? schema/PaginatedResponse body) "response must match PaginatedResponse schema")
(is (conforms? schema/ErrorResponse body)     "error must match ErrorResponse schema")
```

This means the Malli schemas are the **single source of truth** - shared across route definitions, OpenAPI documentation, and test assertions.

### Input Validation Rules

**Project Name:**
- Required
- 1-200 characters after trimming
- Allows unicode (international names)
- Whitespace-only rejected
- Extra whitespace trimmed

**Project Status:**
- Optional (defaults to "active")
- Must be one of: `active`, `on-hold`, `archived`

**Pagination Params:**
- `limit`: 1-100, defaults to 20
- `offset`: >= 0, defaults to 0
- `sort`: `created_at`, `-created_at`, `name`, `-name`

### Validation Enforcement Layers

1. **Reitit coercion middleware** - Validates path/query params and request/response bodies
2. **Malli schemas** - Single source of truth for all shapes
3. **Database constraints** - CHECK constraints as safety net

---

## 5. Implementation Notes

### Library Choices

| Library | Purpose | Rationale |
|---------|---------|-----------|
| Ring + Jetty | HTTP server | Standard, battle-tested, wide ecosystem |
| Reitit | Routing | Data-driven, first-class Malli integration |
| Malli | Validation | Required by spec, modern, composable, fast |
| Muuntaja | Content negotiation | Pairs with Reitit, handles JSON encoding |
| next.jdbc | Database | Idiomatic, composable, modern JDBC wrapper |
| HikariCP | Connection pool | Industry standard, fast, handles validation |
| Integrant | Lifecycle | Simple, REPL-friendly, declarative deps |
| Kaocha | Test runner | Good reporting, extensible, Clojure-native |

### Architecture

```
                 ┌───────────────────────────────────────┐
  HTTP request → │ Ring + Reitit                         │
                 │  ├─ content negotiation (Muuntaja)    │
                 │  ├─ coercion (Malli schemas)          │
                 │  └─ exception handling                │
                 └──────────────┬────────────────────────┘
                                │
                 ┌──────────────▼────────────────────────┐
                 │ Handlers (handlers.clj)               │
                 │  └─ business logic + validation       │
                 └──────────────┬────────────────────────┘
                                │
                 ┌──────────────▼────────────────────────┐
                 │ Database (db.clj)                     │
                 │  ├─ next.jdbc + HikariCP pool         │
                 │  └─ SQLite (WAL mode)                 │
                 └───────────────────────────────────────┘
```

### Stateful Component Management

**Integrant** manages component lifecycle with declarative dependency wiring:

```
:db/pool  →  :db/migrator  →  :http/server
 (HikariCP)    (SQL + seed)     (Jetty)
```

**Start order:** pool -> migrator -> server
**Stop order:** server -> migrator -> pool (reverse)

The REPL `(reset)` reloads code and restarts all components cleanly, providing fast development feedback without restarting the JVM.

### DB Connection Pooling

**HikariCP** with SQLite-specific settings:
- Pool size: 5 (small, since SQLite serializes writes)
- WAL mode enabled for concurrent reads
- Foreign keys enabled via init SQL

**Why pool even for SQLite:**
- Connection reuse avoids open/close overhead
- Consistent pattern with production databases (Snowflake)
- HikariCP handles connection health validation

### Testing Approach

1. **Unit tests** (`schema_test.clj`) - Malli validation logic in isolation
2. **Integration tests** (`api_test.clj`) - Full HTTP request cycle through Ring mock
3. **Schema coercion in tests** - Every response body validated against the same Malli schemas used at runtime
4. **In-memory SQLite** - Fresh database per test, fast, isolated, no cleanup
5. **Edge cases** - Unicode names, whitespace trimming, 409 conflict via `with-redefs`

### Security Considerations

- Input validation prevents injection (Malli + parameterized queries)
- No user-supplied data in SQL without parameterization
- Closed Malli maps reject unknown keys
- Status enum prevents invalid state transitions
- UUIDs not guessable (vs sequential integers)

---

## 6. Terraform Module (code-only)

### Resources Created

| Resource | Purpose |
|----------|---------|
| `snowflake_database` | Dedicated database for Projects service |
| `snowflake_schema` | Schema for organizing tables |
| `snowflake_table` | Projects table matching the SQLite model |
| `snowflake_role` | Service account role |
| `snowflake_*_grant` | SELECT/INSERT/UPDATE permissions (no DELETE) |

### Variables

- `database_name` - Snowflake database name (default: `PROJECTS_DB`)
- `schema_name` - Schema within database (default: `PROJECTS`)
- `service_role_name` - Role for API service (default: `PROJECTS_SERVICE_ROLE`)
- `data_retention_days` - Time Travel setting (default: 7, range: 0-90)
- `environment` - Environment tag: dev/staging/prod

### Review Surfaces

Before applying:
- Verify role permissions (SELECT/INSERT/UPDATE, no DELETE by design)
- Check Time Travel retention settings
- Ensure naming conventions match organizational standards

```bash
cd terraform && terraform init && terraform validate
```

---

## 7. AI Usage

This solution was developed with Claude Code assistance. Verification approach:

1. **Schema validation** - Tested all Malli schemas with edge cases in `schema_test.clj`
2. **API testing** - 16 integration tests covering happy paths, validation errors, 404, and 409 conflict
3. **Schema coercion in tests** - Every test asserts response bodies conform to Malli schemas, ensuring runtime and test-time contracts match
4. **Manual testing** - curl commands to verify actual HTTP behavior end-to-end
5. **Code review** - Read all generated code, understood each decision, fixed issues (e.g., Muuntaja encoding bug where manually set Content-Type headers prevented JSON serialization)
6. **Terraform validation** - `terraform validate` passes

### Key Verifications Made

- Confirmed Malli schema syntax for closed maps (`:closed true`)
- Verified Reitit coercion middleware ordering (negotiate -> format-response -> exception -> format-request -> coerce)
- Fixed Muuntaja integration: removed manual `Content-Type` headers from handlers that prevented JSON encoding
- Fixed coercion error serialization: converted raw Malli error objects to JSON-safe `{:field :message}` maps
- Tested SQLite WAL mode configuration for concurrent reads
- Validated HikariCP settings for SQLite (small pool, init SQL for pragmas)
- Reviewed Terraform Snowflake provider resources and grant permissions

---

## 8. Run & Test

### Prerequisites

- Java 17+ (tested with 21)
- Clojure CLI (deps.edn)

### Start the Server

```bash
# Run directly
clj -M:run

# Or development mode with REPL
clj -M:dev
# Then in REPL:
(start)   # boot the system
(reset)   # reload code and restart
(stop)    # shut down
```

Server starts at http://localhost:3000

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | 3000 | HTTP server port |
| `DB_PATH` | projects.db | SQLite database file |
| `DB_POOL_SIZE` | 5 | Connection pool size |
| `DEFAULT_PAGE_SIZE` | 20 | Default pagination limit |
| `MAX_PAGE_SIZE` | 100 | Maximum pagination limit |

### Run Tests

```bash
# Run all tests
clj -M:test

# With verbose output
clj -M:test --reporter documentation

# 16 tests, 77 assertions, 0 failures
```

### Terraform Validation

```bash
cd terraform
terraform init
terraform validate
```
