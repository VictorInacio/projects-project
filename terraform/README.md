# Terraform: Snowflake Projects Infrastructure

This module provisions Snowflake resources for the Projects service in production.

## Resources Created

| Resource | Description |
|----------|-------------|
| `snowflake_database` | Dedicated database for Projects service |
| `snowflake_schema` | Schema for organizing tables |
| `snowflake_table` | Projects table matching API data model |
| `snowflake_role` | Service role with appropriate grants |

## Usage

```hcl
module "projects" {
  source = "./terraform"

  database_name     = "PROJECTS_DB"
  schema_name       = "PROJECTS"
  service_role_name = "PROJECTS_SERVICE_ROLE"
  environment       = "prod"
}
```

## Variables

| Name | Description | Default |
|------|-------------|---------|
| `database_name` | Snowflake database name | `PROJECTS_DB` |
| `schema_name` | Schema name | `PROJECTS` |
| `service_role_name` | Service role for app access | `PROJECTS_SERVICE_ROLE` |
| `data_retention_days` | Time Travel retention (0-90) | `7` |
| `environment` | Environment (dev/staging/prod) | `dev` |

## Outputs

| Name | Description |
|------|-------------|
| `database_name` | Created database name |
| `schema_name` | Fully qualified schema name |
| `table_name` | Fully qualified table name |
| `service_role` | Service role name |

## Validation

```bash
cd terraform
terraform init
terraform validate
terraform plan  # Review before applying
```

## Notes

- **Do not apply** without proper Snowflake credentials configured
- Table schema matches SQLite model used in local development
- Service role has SELECT/INSERT/UPDATE (no DELETE by design)
