# -----------------------------------------------------------------------------
# Outputs
# -----------------------------------------------------------------------------

output "database_name" {
  description = "Name of the created Snowflake database"
  value       = snowflake_database.projects.name
}

output "schema_name" {
  description = "Fully qualified schema name"
  value       = "${snowflake_database.projects.name}.${snowflake_schema.projects.name}"
}

output "table_name" {
  description = "Fully qualified projects table name"
  value       = "${snowflake_database.projects.name}.${snowflake_schema.projects.name}.${snowflake_table.projects.name}"
}

output "service_role" {
  description = "Name of the service role for application access"
  value       = snowflake_role.projects_service.name
}
