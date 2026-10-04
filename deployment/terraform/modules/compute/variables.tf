variable "name" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "vpc_cidr" {
  type = string
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "vpc_link_security_group_id" {
  type = string
}

variable "kms_key_arn" {
  type = string
}

variable "admin_api_key_secret_arn" {
  type = string
}

variable "image_tag" {
  description = "Immutable image tag (git SHA) deployed by the pipeline"
  type        = string
}

variable "task_cpu" {
  type = number
}

variable "task_memory" {
  type = number
}

variable "min_tasks" {
  type = number
}

variable "max_tasks" {
  type = number
}

variable "backlog_scale_out_threshold" {
  description = "Visible messages in the order queue that trigger a scale out"
  type        = number
  default     = 500
}

variable "deletion_protection" {
  type = bool
}

variable "log_retention_days" {
  type = number
}

variable "events_table_name" {
  type = string
}

variable "orders_table_name" {
  type = string
}

variable "audit_table_name" {
  type = string
}

variable "events_table_arn" {
  type = string
}

variable "orders_table_arn" {
  type = string
}

variable "audit_table_arn" {
  type = string
}

variable "order_commands_queue_url" {
  type = string
}

variable "order_commands_queue_arn" {
  type = string
}

variable "order_commands_queue_name" {
  type = string
}

variable "reservation_expiration_queue_url" {
  type = string
}

variable "reservation_expiration_queue_arn" {
  type = string
}
