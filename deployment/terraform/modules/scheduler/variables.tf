variable "name" {
  type = string
}

variable "schedule_expression" {
  type    = string
  default = "rate(1 minute)"
}

variable "enabled" {
  type    = bool
  default = true
}

variable "target_queue_arn" {
  type = string
}

variable "dead_letter_queue_arn" {
  type = string
}

variable "kms_key_arn" {
  type = string
}
