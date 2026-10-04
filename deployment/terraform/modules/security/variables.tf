variable "name" {
  type = string
}

variable "kms_deletion_window_days" {
  type    = number
  default = 30
}

variable "secret_recovery_window_days" {
  type    = number
  default = 30
}
