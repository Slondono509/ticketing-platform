output "api_endpoint" {
  value = module.platform.api_endpoint
}

output "ecr_repository_url" {
  value = module.platform.ecr_repository_url
}

output "user_pool_id" {
  value = module.platform.user_pool_id
}

output "customer_client_id" {
  value = module.platform.customer_client_id
}

output "backoffice_client_id" {
  value = module.platform.backoffice_client_id
}

output "admin_api_key_secret_arn" {
  value = module.platform.admin_api_key_secret_arn
}
