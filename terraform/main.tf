# -----------------------------------------------------------------------------
# Snowflake Projects Infrastructure
# -----------------------------------------------------------------------------
# This module provisions Snowflake resources for the Projects service:
# - Database and schema
# - Projects table matching the application data model
# - Appropriate roles and grants
#
# Note: This is code-only; do not apply without proper Snowflake credentials.
# -----------------------------------------------------------------------------

terraform {
  required_version = ">= 1.0.0"

  required_providers {
    snowflake = {
      source  = "Snowflake-Labs/snowflake"
      version = "~> 0.87.0"
    }
  }
}

# -----------------------------------------------------------------------------
# Database
# -----------------------------------------------------------------------------

resource "snowflake_database" "projects" {
  name                        = var.database_name
  comment                     = "Database for Projects service"
  data_retention_time_in_days = var.data_retention_days
}

# -----------------------------------------------------------------------------
# Schema
# -----------------------------------------------------------------------------

resource "snowflake_schema" "projects" {
  database            = snowflake_database.projects.name
  name                = var.schema_name
  comment             = "Schema for Projects service tables"
  data_retention_days = var.data_retention_days
}

# -----------------------------------------------------------------------------
# Projects Table
# -----------------------------------------------------------------------------

resource "snowflake_table" "projects" {
  database = snowflake_database.projects.name
  schema   = snowflake_schema.projects.name
  name     = "PROJECTS"
  comment  = "Core projects table"

  column {
    name     = "ID"
    type     = "VARCHAR(36)"
    nullable = false
    comment  = "UUID primary key"
  }

  column {
    name     = "NAME"
    type     = "VARCHAR(200)"
    nullable = false
    comment  = "Human-readable project name"
  }

  column {
    name     = "STATUS"
    type     = "VARCHAR(20)"
    nullable = false
    comment  = "Project status: active, on-hold, archived"
  }

  column {
    name     = "CREATED_AT"
    type     = "TIMESTAMP_NTZ"
    nullable = false
    comment  = "Creation timestamp (UTC)"
  }

  column {
    name     = "UPDATED_AT"
    type     = "TIMESTAMP_NTZ"
    nullable = false
    comment  = "Last update timestamp (UTC)"
  }

  # Primary key constraint
  primary_key {
    name = "PK_PROJECTS"
    keys = ["ID"]
  }
}

# -----------------------------------------------------------------------------
# Service Role (for application access)
# -----------------------------------------------------------------------------

resource "snowflake_role" "projects_service" {
  name    = var.service_role_name
  comment = "Role for Projects API service"
}

# Grant usage on database and schema
resource "snowflake_database_grant" "projects_usage" {
  database_name = snowflake_database.projects.name
  privilege     = "USAGE"
  roles         = [snowflake_role.projects_service.name]
}

resource "snowflake_schema_grant" "projects_usage" {
  database_name = snowflake_database.projects.name
  schema_name   = snowflake_schema.projects.name
  privilege     = "USAGE"
  roles         = [snowflake_role.projects_service.name]
}

# Grant table permissions
resource "snowflake_table_grant" "projects_crud" {
  database_name = snowflake_database.projects.name
  schema_name   = snowflake_schema.projects.name
  table_name    = snowflake_table.projects.name

  privilege = "SELECT"
  roles     = [snowflake_role.projects_service.name]
}

resource "snowflake_table_grant" "projects_insert" {
  database_name = snowflake_database.projects.name
  schema_name   = snowflake_schema.projects.name
  table_name    = snowflake_table.projects.name

  privilege = "INSERT"
  roles     = [snowflake_role.projects_service.name]
}

resource "snowflake_table_grant" "projects_update" {
  database_name = snowflake_database.projects.name
  schema_name   = snowflake_schema.projects.name
  table_name    = snowflake_table.projects.name

  privilege = "UPDATE"
  roles     = [snowflake_role.projects_service.name]
}
