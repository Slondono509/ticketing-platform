variable "name" {
  type = string
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "vpc_link_security_group_id" {
  type = string
}

variable "alb_listener_arn" {
  type = string
}

variable "kms_key_arn" {
  type = string
}

variable "callback_urls" {
  description = "Allowed OAuth redirect URLs of the customer apps"
  type        = list(string)
}

variable "throttling_rate_limit" {
  description = "Steady-state requests per second for the whole API"
  type        = number
}

variable "throttling_burst_limit" {
  type = number
}

variable "deletion_protection" {
  type = bool
}

variable "log_retention_days" {
  type = number
}
