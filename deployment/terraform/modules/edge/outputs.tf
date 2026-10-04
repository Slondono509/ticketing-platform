output "api_endpoint" {
  value = aws_apigatewayv2_api.this.api_endpoint
}

output "api_id" {
  value = aws_apigatewayv2_api.this.id
}

output "user_pool_id" {
  value = aws_cognito_user_pool.this.id
}

output "customer_client_id" {
  value = aws_cognito_user_pool_client.customers.id
}

output "backoffice_client_id" {
  value = aws_cognito_user_pool_client.backoffice.id
}
