output "kms_key_arn" {
  value = aws_kms_key.this.arn
}

output "admin_api_key_secret_arn" {
  value = aws_secretsmanager_secret.admin_api_key.arn
}
