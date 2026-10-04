module "platform" {
  source = "../../modules/platform"

  environment            = "prod"
  production             = true
  vpc_cidr               = "10.30.0.0/16"
  image_tag              = var.image_tag
  task_cpu               = 1024
  task_memory            = 2048
  min_tasks              = 3
  max_tasks              = 30
  throttling_rate_limit  = 5000
  throttling_burst_limit = 10000
  callback_urls          = ["https://tickets.example.com/callback"]
  log_retention_days     = 90
  alarm_emails           = var.alarm_emails
}
