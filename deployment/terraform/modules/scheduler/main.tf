# EventBridge Scheduler publishes a tick to the reservation-expiration queue every minute. The application only
# consumes the queue: the schedule lives in the infrastructure, each tick is processed by exactly one task
# (whichever receives the message), and failed runs are retried by SQS.

data "aws_caller_identity" "current" {}

resource "aws_scheduler_schedule_group" "this" {
  name = var.name
}

resource "aws_iam_role" "scheduler" {
  name = "${var.name}-scheduler"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "scheduler.amazonaws.com" }
      Action    = "sts:AssumeRole"
      Condition = { StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.current.account_id } }
    }]
  })
}

resource "aws_iam_role_policy" "scheduler" {
  name = "send-expiration-ticks"
  role = aws_iam_role.scheduler.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = "sqs:SendMessage"
        Resource = [var.target_queue_arn, var.dead_letter_queue_arn]
      },
      {
        Effect   = "Allow"
        Action   = ["kms:GenerateDataKey", "kms:Decrypt"]
        Resource = var.kms_key_arn
      }
    ]
  })
}

resource "aws_scheduler_schedule" "release_expired_reservations" {
  name                         = "release-expired-reservations"
  group_name                   = aws_scheduler_schedule_group.this.name
  description                  = "Triggers the release of reservations whose 10 minute hold expired"
  schedule_expression          = var.schedule_expression
  schedule_expression_timezone = "UTC"
  state                        = var.enabled ? "ENABLED" : "DISABLED"

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = var.target_queue_arn
    role_arn = aws_iam_role.scheduler.arn
    input = jsonencode({
      source = "eventbridge-scheduler"
      action = "RELEASE_EXPIRED_RESERVATIONS"
    })

    retry_policy {
      maximum_event_age_in_seconds = 60 # a tick older than the next one is useless
      maximum_retry_attempts       = 3
    }

    dead_letter_config {
      arn = var.dead_letter_queue_arn
    }
  }
}
