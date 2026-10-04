# Standard queues (not FIFO): correctness comes from DynamoDB conditional writes, not from message ordering, so
# we keep the near-unlimited throughput of standard queues. Every queue has a DLQ: a message that fails
# max_receive_count times is parked there for inspection and redrive instead of blocking the flow.

locals {
  queues = {
    order-commands = {
      visibility_timeout = 30
      max_receive_count  = 5
    }
    reservation-expiration = {
      visibility_timeout = 60
      max_receive_count  = 3
    }
  }
}

resource "aws_sqs_queue" "dlq" {
  for_each                          = local.queues
  name                              = "${var.name}-${each.key}-dlq"
  message_retention_seconds         = 1209600 # 14 days to investigate and redrive
  kms_master_key_id                 = var.kms_key_arn
  kms_data_key_reuse_period_seconds = 300
}

resource "aws_sqs_queue" "this" {
  for_each                          = local.queues
  name                              = "${var.name}-${each.key}"
  visibility_timeout_seconds        = each.value.visibility_timeout
  receive_wait_time_seconds         = 20 # long polling: fewer empty receives, lower cost
  message_retention_seconds         = 345600
  kms_master_key_id                 = var.kms_key_arn
  kms_data_key_reuse_period_seconds = 300

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq[each.key].arn
    maxReceiveCount     = each.value.max_receive_count
  })
}

resource "aws_sqs_queue_redrive_allow_policy" "dlq" {
  for_each  = local.queues
  queue_url = aws_sqs_queue.dlq[each.key].id
  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.this[each.key].arn]
  })
}

# Deny any access that is not over TLS.
resource "aws_sqs_queue_policy" "tls_only" {
  for_each  = merge({ for k, q in aws_sqs_queue.this : k => q }, { for k, q in aws_sqs_queue.dlq : "${k}-dlq" => q })
  queue_url = each.value.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Principal = "*"
      Action    = "sqs:*"
      Resource  = each.value.arn
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  })
}
