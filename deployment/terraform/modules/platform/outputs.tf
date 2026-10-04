output "api_endpoint" {
  value = module.edge.api_endpoint
}

output "ecr_repository_url" {
  value = module.compute.ecr_repository_url
}

output "user_pool_id" {
  value = module.edge.user_pool_id
}

output "customer_client_id" {
  value = module.edge.customer_client_id
}

output "backoffice_client_id" {
  value = module.edge.backoffice_client_id
}

output "admin_api_key_secret_arn" {
  value = module.security.admin_api_key_secret_arn
}

output "ecs_cluster_name" {
  value = module.compute.cluster_name
}

output "ecs_service_name" {
  value = module.compute.service_name
}
