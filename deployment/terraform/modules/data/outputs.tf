output "events_table_name" {
  value = aws_dynamodb_table.events.name
}

output "orders_table_name" {
  value = aws_dynamodb_table.orders.name
}

output "audit_table_name" {
  value = aws_dynamodb_table.order_audit.name
}

output "table_arns" {
  value = [aws_dynamodb_table.events.arn, aws_dynamodb_table.orders.arn, aws_dynamodb_table.order_audit.arn]
}

output "orders_table_arn" {
  value = aws_dynamodb_table.orders.arn
}

output "events_table_arn" {
  value = aws_dynamodb_table.events.arn
}

output "audit_table_arn" {
  value = aws_dynamodb_table.order_audit.arn
}
