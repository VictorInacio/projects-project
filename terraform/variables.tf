# -----------------------------------------------------------------------------
# Input Variables
# -----------------------------------------------------------------------------

variable "database_name" {
  description = "Name of the Snowflake database"
  type        = string
  default     = "PROJECTS_DB"

  validation {
    condition     = can(regex("^[A-Z][A-Z0-9_]*$", var.database_name))
    error_message = "Database name must be uppercase alphanumeric with underscores."
  }
}

variable "schema_name" {
  description = "Name of the schema within the database"
  type        = string
  default     = "PROJECTS"

  validation {
    condition     = can(regex("^[A-Z][A-Z0-9_]*$", var.schema_name))
    error_message = "Schema name must be uppercase alphanumeric with underscores."
  }
}

variable "service_role_name" {
  description = "Name of the Snowflake role for the Projects service"
  type        = string
  default     = "PROJECTS_SERVICE_ROLE"
}

variable "data_retention_days" {
  description = "Time Travel data retention period in days (0-90)"
  type        = number
  default     = 7

  validation {
    condition     = var.data_retention_days >= 0 && var.data_retention_days <= 90
    error_message = "Data retention must be between 0 and 90 days."
  }
}

variable "environment" {
  description = "Environment name (e.g., dev, staging, prod)"
  type        = string
  default     = "dev"

  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "Environment must be dev, staging, or prod."
  }
}
