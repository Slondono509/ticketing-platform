variable "name" {
  type = string
}

variable "region" {
  type = string
}

variable "kms_key_arn" {
  type = string
}

variable "alarm_emails" {
  type    = list(string)
  default = []
}

variable "dlq_names" {
  type = map(string)
}

variable "order_commands_queue_name" {
  type = string
}

variable "max_order_queue_age_seconds" {
  type    = number
  default = 120
}

variable "alb_arn_suffix" {
  type = string
}

variable "table_names" {
  type = list(string)
}
