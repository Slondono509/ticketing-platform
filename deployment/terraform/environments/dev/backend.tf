# Remote state per environment, encrypted and versioned. S3 native locking (Terraform >= 1.10) replaces the
# DynamoDB lock table. The bucket is created once per account by the platform team (bootstrap).
terraform {
  backend "s3" {
    bucket       = "ticketing-terraform-state-dev"
    key          = "ticketing-platform/dev/terraform.tfstate"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true
  }
}
