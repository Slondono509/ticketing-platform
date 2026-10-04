output "order_commands_queue_url" {
  value = aws_sqs_queue.this["order-commands"].url
}

output "order_commands_queue_arn" {
  value = aws_sqs_queue.this["order-commands"].arn
}

output "order_commands_queue_name" {
  value = aws_sqs_queue.this["order-commands"].name
}

output "reservation_expiration_queue_url" {
  value = aws_sqs_queue.this["reservation-expiration"].url
}

output "reservation_expiration_queue_arn" {
  value = aws_sqs_queue.this["reservation-expiration"].arn
}

output "reservation_expiration_dlq_arn" {
  value = aws_sqs_queue.dlq["reservation-expiration"].arn
}

output "dlq_names" {
  value = { for k, q in aws_sqs_queue.dlq : k => q.name }
}
