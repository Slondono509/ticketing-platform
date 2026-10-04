variable "region" {
  type    = string
  default = "us-east-1"
}

variable "image_tag" {
  description = "Image tag to deploy (git commit SHA), provided by the CI/CD pipeline"
  type        = string
}

variable "cost_center" {
  type    = string
  default = "ticketing"
}

variable "alarm_emails" {
  type    = list(string)
  default = []
}
