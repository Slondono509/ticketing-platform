# Composition of the whole platform for one environment. Each environment (environments/dev, environments/prod)
# calls this module with its own sizing and protections, and keeps its own state file: environments are isolated
# at state level and, ideally, at account level (one AWS account per environment via AWS Organizations).

data "aws_region" "current" {}

locals {
  name = "ticketing-${var.environment}"
}

module "security" {
  source                      = "../security"
  name                        = local.name
  kms_deletion_window_days    = var.production ? 30 : 7
  secret_recovery_window_days = var.production ? 30 : 0
}

module "network" {
  source             = "../network"
  name               = local.name
  cidr_block         = var.vpc_cidr
  az_count           = var.az_count
  single_nat_gateway = !var.production
  log_retention_days = var.log_retention_days
  kms_key_arn        = module.security.kms_key_arn
}

module "data" {
  source              = "../data"
  name                = local.name
  kms_key_arn         = module.security.kms_key_arn
  deletion_protection = var.production
}

module "messaging" {
  source      = "../messaging"
  name        = local.name
  kms_key_arn = module.security.kms_key_arn
}

module "scheduler" {
  source                = "../scheduler"
  name                  = local.name
  target_queue_arn      = module.messaging.reservation_expiration_queue_arn
  dead_letter_queue_arn = module.messaging.reservation_expiration_dlq_arn
  kms_key_arn           = module.security.kms_key_arn
}

module "compute" {
  source                           = "../compute"
  name                             = local.name
  vpc_id                           = module.network.vpc_id
  vpc_cidr                         = module.network.vpc_cidr
  private_subnet_ids               = module.network.private_subnet_ids
  vpc_link_security_group_id       = module.network.vpc_link_security_group_id
  kms_key_arn                      = module.security.kms_key_arn
  admin_api_key_secret_arn         = module.security.admin_api_key_secret_arn
  image_tag                        = var.image_tag
  task_cpu                         = var.task_cpu
  task_memory                      = var.task_memory
  min_tasks                        = var.min_tasks
  max_tasks                        = var.max_tasks
  deletion_protection              = var.production
  log_retention_days               = var.log_retention_days
  events_table_name                = module.data.events_table_name
  orders_table_name                = module.data.orders_table_name
  audit_table_name                 = module.data.audit_table_name
  events_table_arn                 = module.data.events_table_arn
  orders_table_arn                 = module.data.orders_table_arn
  audit_table_arn                  = module.data.audit_table_arn
  order_commands_queue_url         = module.messaging.order_commands_queue_url
  order_commands_queue_arn         = module.messaging.order_commands_queue_arn
  order_commands_queue_name        = module.messaging.order_commands_queue_name
  reservation_expiration_queue_url = module.messaging.reservation_expiration_queue_url
  reservation_expiration_queue_arn = module.messaging.reservation_expiration_queue_arn
}

module "edge" {
  source                     = "../edge"
  name                       = local.name
  private_subnet_ids         = module.network.private_subnet_ids
  vpc_link_security_group_id = module.network.vpc_link_security_group_id
  alb_listener_arn           = module.compute.alb_listener_arn
  kms_key_arn                = module.security.kms_key_arn
  callback_urls              = var.callback_urls
  throttling_rate_limit      = var.throttling_rate_limit
  throttling_burst_limit     = var.throttling_burst_limit
  deletion_protection        = var.production
  log_retention_days         = var.log_retention_days
}

module "observability" {
  source                    = "../observability"
  name                      = local.name
  region                    = data.aws_region.current.region
  kms_key_arn               = module.security.kms_key_arn
  alarm_emails              = var.alarm_emails
  dlq_names                 = module.messaging.dlq_names
  order_commands_queue_name = module.messaging.order_commands_queue_name
  alb_arn_suffix            = module.compute.alb_arn_suffix
  table_names               = [module.data.events_table_name, module.data.orders_table_name, module.data.audit_table_name]
}
