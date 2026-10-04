variable "name" {
  description = "Prefix for every resource name"
  type        = string
}

variable "cidr_block" {
  description = "VPC CIDR"
  type        = string
}

variable "az_count" {
  description = "Number of availability zones"
  type        = number
  default     = 2
}

variable "single_nat_gateway" {
  description = "Use a single NAT gateway (cheaper, not AZ resilient)"
  type        = bool
}

variable "log_retention_days" {
  type = number
}

variable "kms_key_arn" {
  description = "Key used to encrypt the flow logs"
  type        = string
}
