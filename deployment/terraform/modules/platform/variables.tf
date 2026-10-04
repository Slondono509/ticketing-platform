variable "environment" {
  description = "Environment name (dev, prod)"
  type        = string
}

variable "production" {
  description = "Enables production protections: deletion protection, NAT per AZ, longer key/secret recovery windows"
  type        = bool
}

variable "vpc_cidr" {
  type = string
}

variable "az_count" {
  type    = number
  default = 2
}

variable "image_tag" {
  type = string
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

variable "throttling_rate_limit" {
  type = number
}

variable "throttling_burst_limit" {
  type = number
}

variable "callback_urls" {
  type = list(string)
}

variable "log_retention_days" {
  type = number
}

variable "alarm_emails" {
  type    = list(string)
  default = []
}
