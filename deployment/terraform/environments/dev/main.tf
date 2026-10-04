module "platform" {
  source = "../../modules/platform"

  environment            = "dev"
  production             = false
  vpc_cidr               = "10.20.0.0/16"
  image_tag              = var.image_tag
  task_cpu               = 512
  task_memory            = 1024
  min_tasks              = 1
  max_tasks              = 4
  throttling_rate_limit  = 200
  throttling_burst_limit = 400
  callback_urls          = ["http://localhost:4200/callback"]
  log_retention_days     = 14
  alarm_emails           = var.alarm_emails
}
