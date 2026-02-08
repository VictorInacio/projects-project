# Projects API

A Clojure REST API for managing projects, built with Reitit, Malli, and SQLite.

```
GET  /v1/projects      List projects (paginated, sortable)
POST /v1/projects      Create a project
GET  /v1/projects/{id} Fetch a single project
```

Browse the API interactively at [http://localhost:3000/swagger](http://localhost:3000/swagger) (Swagger UI).

See [`DESIGN.md`](./DESIGN.md) for architecture, design decisions, and rationale.
See [`openapi.yaml`](./openapi.yaml) for the full API contract (OpenAPI 3.1.1).

## Quick Start

**Prerequisites:** Java 17+ and [Clojure CLI](https://clojure.org/guides/install_clojure)

```bash
clj -M:run                          # start the server
clj -M:test                         # run tests (19 tests, 106 assertions)
```

The server starts at **http://localhost:3000** with a SQLite database and seed data created automatically.

For interactive development:

```bash
clj -M:dev
# user=> (start)    — boot the system
# user=> (reset)    — reload code and restart
# user=> (stop)     — shut down
```

## Project Layout

```
src/projects/
  core.clj          Entry point (-main, shutdown hook)
  config.clj        Environment configuration
  system.clj        Integrant component wiring
  db.clj            Connection pool, migrations, queries
  schema.clj        Malli schemas (request + response)
  handlers.clj      HTTP handlers
  routes.clj        Reitit router + middleware stack
test/projects/
  schema_test.clj   Unit tests — Malli validation rules
  api_test.clj      Integration tests — full HTTP cycle + schema coercion
dev/user.clj        REPL helpers (start/stop/reset)
migrations/
  001_create_projects.sql   Table + indexes + seed data
terraform/                  Snowflake IaC (code-only)
openapi.yaml                OpenAPI 3.1.1 specification
DESIGN.md                   Design decisions & rationale
```

## Example Requests

### Happy Paths

```bash
# Health check
curl "http://localhost:3000/health"
# => {"status":"ok"}

# Create a project (status defaults to "active")
curl -s -X POST "http://localhost:3000/v1/projects" \
  -H "Content-Type: application/json" \
  -d '{"name": "New Project"}' | jq

# Create with explicit status
curl -s -X POST "http://localhost:3000/v1/projects" \
  -H "Content-Type: application/json" \
  -d '{"name": "Deferred Work", "status": "on-hold"}' | jq

# Get a project by ID
curl -s "http://localhost:3000/v1/projects/550e8400-e29b-41d4-a716-446655440001" | jq

# List projects (default: 20 items, newest first)
curl -s "http://localhost:3000/v1/projects" | jq

# Paginate and sort
curl -s "http://localhost:3000/v1/projects?limit=2&offset=1&sort=-created_at" | jq
curl -s "http://localhost:3000/v1/projects?sort=name" | jq
```

### Error Responses

```bash
# 400 — Empty name
curl -s -X POST "http://localhost:3000/v1/projects" \
  -H "Content-Type: application/json" \
  -d '{"name": ""}' | jq
# => { "error": "validation_error", "message": "Invalid request parameters" }

# 400 — Missing name
curl -s -X POST "http://localhost:3000/v1/projects" \
  -H "Content-Type: application/json" \
  -d '{}' | jq

# 400 — Invalid status
curl -s -X POST "http://localhost:3000/v1/projects" \
  -H "Content-Type: application/json" \
  -d '{"name": "Test", "status": "invalid"}' | jq

# 404 — Project not found
curl -s "http://localhost:3000/v1/projects/00000000-0000-0000-0000-000000000000" | jq
# => { "error": "not_found", "message": "Project with id '00000000-...' not found" }

# 404 — Unknown endpoint
curl -s "http://localhost:3000/v1/unknown" | jq
# => { "error": "not_found", "message": "Endpoint not found" }
```

## Configuration

| Env Variable | Default | Description |
|--------------|---------|-------------|
| `PORT` | `3000` | HTTP port |
| `DB_PATH` | `projects.db` | SQLite file path |
| `DB_POOL_SIZE` | `5` | HikariCP pool size |
| `DEFAULT_PAGE_SIZE` | `20` | Default items per page |
| `MAX_PAGE_SIZE` | `100` | Maximum items per page |

## Terraform

```bash
cd terraform && terraform init && terraform validate
```

See [`terraform/README.md`](./terraform/README.md) for resources, variables, and outputs.
