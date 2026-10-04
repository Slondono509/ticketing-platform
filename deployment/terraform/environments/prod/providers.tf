terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.0, < 7.0"
    }
    random = {
      source  = "hashicorp/random"
      version = ">= 3.6"
    }
  }
}

provider "aws" {
  region = var.region

  # Tags on every resource: cost allocation and ownership (governance).
  default_tags {
    tags = {
      Project     = "ticketing-platform"
      Environment = "prod"
      ManagedBy   = "terraform"
      CostCenter  = var.cost_center
    }
  }
}
