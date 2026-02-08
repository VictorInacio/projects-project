# Projects API - Design Document

## 1. Problem Definition

### Acceptance Criteria

**Functional:**
- `GET /v1/projects` — List projects with pagination (limit/offset) and sorting
- `POST /v1/projects` — Create a project with validated name, optional status
- `GET /v1/projects/{id}` — Fetch a single project by UUID
- All responses return consistent JSON format
- Validation errors return 400 with detailed field errors
- Not found returns 404 with meaningful message

**Non-Functional:**
- SQLite file-based database for local development
- Connection pooling via HikariCP
- Clean startup/shutdown lifecycle
- API versioning via URL path
- OpenAPI 3.1.1 specification
- Malli schemas for request/response validation

### Out of Scope
- Authentication/authorization
- Project updates (PUT/PATCH) and deletes
- Filtering by status (listed as optional extension)
- Milestones sub-resource
- Production deployment configuration
- CI/CD pipeline

### Open Questions Resolved
| Question | Decision |
|----------|----------|
| Pagination style? | Limit/offset — simple, sufficient for expected data sizes |
| ID type? | UUID v4 string — no coordination, URL-safe, works with SQLite |
| Default page size? | 20 items (configurable via env) |
| Name validation? | 1-200 chars, trimmed, allows unicode |
| Status values? | `active` (default), `on-hold`, `archived` (terminal) |

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
- Portable across databases (SQLite dev → Snowflake prod)

**Trade-offs:**
- Larger than integers (36 bytes vs 4-8 bytes) — acceptable for this scale
- Not sortable by creation order — use `created_at` index instead

### SQLite Choice

**Why SQLite over DuckDB:**
- Simpler JDBC setup, better Clojure library support
- Sufficient for CRUD operations (DuckDB optimized for analytics)
- WAL mode provides good concurrent read performance
- Widely understood, easier to debug

---

## 3. API Contract

### Endpoints Summary

| Method | Path | Description | Status Codes |
|--------|------|-------------|--------------|
| GET | /health | Health check | 200 |
| GET | /v1/projects | List projects | 200 |
| POST | /v1/projects | Create project | 201, 400, 409 |
| GET | /v1/projects/{id} | Get project | 200, 404 |

### Error Model

All errors return:
```json
{
  "error": "error_code",
  "message": "Human readable message",
  "details": [{"field": "name", "message": "specific error"}]
}
```

**Error Codes:**
- `validation_error` (400) — Invalid request body/params
- `not_found` (404) — Resource doesn't exist
- `conflict` (409) — ID collision (extremely rare)
- `internal_error` (500) — Unexpected server error

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

See [openapi.yaml](./openapi.yaml) for complete specification.

---

## 4. Validation

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

### Validation Enforcement

1. **Reitit coercion middleware** — Validates path/query params
2. **Malli schemas** — Validates request/response bodies
3. **Database constraints** — CHECK constraints as safety net

---

## 5. Implementation Notes

### Library Choices

| Library | Purpose | Rationale |
|---------|---------|-----------|
| Ring + Jetty | HTTP server | Standard, battle-tested |
| Reitit | Routing | Data-driven, Malli integration |
| Malli | Validation | Required by spec, modern, fast |
| next.jdbc | Database | Idiomatic, composable |
| HikariCP | Connection pool | Industry standard, fast |
| Integrant | Lifecycle | Simple, REPL-friendly |

### Project Layout

```
├── src/projects/
│   ├── config.clj      # Environment configuration
│   ├── core.clj        # Entry point
│   ├── db.clj          # Database layer
│   ├── handlers.clj    # HTTP handlers
│   ├── routes.clj      # Reitit routes
│   ├── schema.clj      # Malli schemas
│   └── system.clj      # Integrant lifecycle
├── test/projects/
│   ├── api_test.clj    # Integration tests
│   └── schema_test.clj # Unit tests
├── dev/user.clj        # REPL namespace
├── migrations/         # SQL migrations
├── terraform/          # Snowflake IaC
├── openapi.yaml        # API specification
└── DESIGN.md           # This document
```

### Stateful Component Management

**Integrant** manages component lifecycle:

```
:db/pool      → Create HikariCP datasource
    ↓
:db/migrator  → Run migrations, seed data
    ↓
:http/server  → Start Jetty with Ring app
```

**Start order:** pool → migrator → server
**Stop order:** server → migrator → pool (reverse)

### DB Connection Pooling

**HikariCP** with SQLite-specific settings:
- Pool size: 5 (small, since SQLite serializes writes)
- WAL mode enabled for concurrent reads
- Foreign keys enabled via init SQL

**Why pool even for SQLite:**
- Connection reuse avoids open/close overhead
- Consistent pattern with production databases
- HikariCP handles connection validation

### Testing Approach

1. **Unit tests** (`schema_test.clj`) — Malli validation logic
2. **Integration tests** (`api_test.clj`) — Full HTTP request cycle
3. **In-memory SQLite** — Fresh database per test, fast, isolated

### Security Considerations

- Input validation prevents injection (Malli + parameterized queries)
- No user-supplied data in SQL without parameterization
- Status enum prevents invalid state transitions
- UUIDs not guessable (vs sequential integers)

---

## 6. Terraform Module

### Resources Created

| Resource | Purpose |
|----------|---------|
| `snowflake_database` | Dedicated database |
| `snowflake_schema` | Schema for tables |
| `snowflake_table` | Projects table |
| `snowflake_role` | Service account role |
| `snowflake_*_grant` | Appropriate permissions |

### Variables

- `database_name` — Snowflake database name
- `schema_name` — Schema within database
- `service_role_name` — Role for API service
- `data_retention_days` — Time Travel setting

### Review Surfaces

Before applying:
- Verify role permissions (SELECT/INSERT/UPDATE, no DELETE)
- Check Time Travel retention settings
- Ensure naming conventions match standards

---

## 7. AI Usage

This solution was developed with Claude Code assistance. Verification approach:

1. **Schema validation** — Tested all Malli schemas with edge cases
2. **API testing** — Comprehensive test suite covering happy paths and errors
3. **Manual testing** — curl commands to verify behavior
4. **Code review** — Read all generated code, understood each decision
5. **Terraform validation** — `terraform validate` passes

### Key Verifications Made

- Confirmed Malli schema syntax for closed maps
- Verified Reitit coercion middleware integration
- Tested SQLite WAL mode configuration
- Validated HikariCP settings for SQLite
- Reviewed Terraform Snowflake provider resources

---

## 8. Run & Test

### Prerequisites

- Java 17+ (tested with 21)
- Clojure CLI (deps.edn)

### Start the Server

```bash
# Development mode with REPL
clj -M:dev
# Then in REPL:
(start)

# Or run directly
clj -M:run
```

Server starts at http://localhost:3000

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | 3000 | HTTP server port |
| `DB_PATH` | projects.db | SQLite database file |
| `DB_POOL_SIZE` | 5 | Connection pool size |
| `DEFAULT_PAGE_SIZE` | 20 | Default pagination limit |

### Run Tests

```bash
# Run all tests
clj -M:test

# Or with verbose output
clj -M:test --reporter documentation
```

### Example API Calls

```bash
# Health check
curl http://localhost:3000/health

# List projects
curl http://localhost:3000/v1/projects

# List with pagination
curl "http://localhost:3000/v1/projects?limit=2&offset=0&sort=-created_at"

# Get single project
curl http://localhost:3000/v1/projects/550e8400-e29b-41d4-a716-446655440001

# Create project
curl -X POST http://localhost:3000/v1/projects \
  -H "Content-Type: application/json" \
  -d '{"name": "New Project"}'

# Create with status
curl -X POST http://localhost:3000/v1/projects \
  -H "Content-Type: application/json" \
  -d '{"name": "Paused Project", "status": "on-hold"}'
```

### Terraform Validation

```bash
cd terraform
terraform init
terraform validate
```
