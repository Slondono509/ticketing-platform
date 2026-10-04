# Public entry point: API Gateway HTTP API -> VPC link -> internal ALB.
#  * Authentication with Cognito JWTs. The customer identity sent to the application (X-Customer-Id) is taken
#    from the validated token's "sub" claim, overwriting whatever the client sent: it cannot be spoofed.
#  * Back-office routes additionally require the "ticketing/admin" OAuth scope.
#  * Stage-level throttling protects the backend from abusive clients and retry storms.

data "aws_region" "current" {}

# ---------------------------------------------------------------- Identity
resource "aws_cognito_user_pool" "this" {
  name                     = var.name
  auto_verified_attributes = ["email"]
  username_attributes      = ["email"]
  mfa_configuration        = "OPTIONAL"
  deletion_protection      = var.deletion_protection ? "ACTIVE" : "INACTIVE"

  password_policy {
    minimum_length    = 12
    require_lowercase = true
    require_uppercase = true
    require_numbers   = true
    require_symbols   = true
  }

  software_token_mfa_configuration {
    enabled = true
  }
}

resource "aws_cognito_resource_server" "ticketing" {
  user_pool_id = aws_cognito_user_pool.this.id
  identifier   = "ticketing"
  name         = "Ticketing API"

  scope {
    scope_name        = "admin"
    scope_description = "Back-office operations"
  }
}

resource "aws_cognito_user_pool_domain" "this" {
  domain       = var.name
  user_pool_id = aws_cognito_user_pool.this.id
}

# Customers: authorization code + PKCE from the web/mobile apps.
resource "aws_cognito_user_pool_client" "customers" {
  name                                 = "${var.name}-customers"
  user_pool_id                         = aws_cognito_user_pool.this.id
  generate_secret                      = false
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["code"]
  allowed_oauth_scopes                 = ["openid", "email"]
  supported_identity_providers         = ["COGNITO"]
  callback_urls                        = var.callback_urls
  access_token_validity                = 15
  token_validity_units {
    access_token = "minutes"
  }
}

# Back-office: machine-to-machine client credentials with the admin scope.
resource "aws_cognito_user_pool_client" "backoffice" {
  name                                 = "${var.name}-backoffice"
  user_pool_id                         = aws_cognito_user_pool.this.id
  generate_secret                      = true
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["client_credentials"]
  allowed_oauth_scopes                 = ["${aws_cognito_resource_server.ticketing.identifier}/admin"]
  supported_identity_providers         = ["COGNITO"]
}

# ---------------------------------------------------------------- HTTP API
resource "aws_apigatewayv2_api" "this" {
  name          = var.name
  protocol_type = "HTTP"
}

resource "aws_apigatewayv2_vpc_link" "this" {
  name               = var.name
  subnet_ids         = var.private_subnet_ids
  security_group_ids = [var.vpc_link_security_group_id]
}

resource "aws_apigatewayv2_authorizer" "jwt" {
  api_id           = aws_apigatewayv2_api.this.id
  name             = "cognito"
  authorizer_type  = "JWT"
  identity_sources = ["$request.header.Authorization"]

  jwt_configuration {
    issuer   = "https://cognito-idp.${data.aws_region.current.region}.amazonaws.com/${aws_cognito_user_pool.this.id}"
    audience = [aws_cognito_user_pool_client.customers.id, aws_cognito_user_pool_client.backoffice.id]
  }
}

# Public catalog: the identity header is stripped.
resource "aws_apigatewayv2_integration" "public" {
  api_id             = aws_apigatewayv2_api.this.id
  integration_type   = "HTTP_PROXY"
  integration_method = "ANY"
  integration_uri    = var.alb_listener_arn
  connection_type    = "VPC_LINK"
  connection_id      = aws_apigatewayv2_vpc_link.this.id

  request_parameters = {
    "remove:header.X-Customer-Id" = "''"
  }
}

# Authenticated customers: the identity header comes from the token.
resource "aws_apigatewayv2_integration" "customer" {
  api_id             = aws_apigatewayv2_api.this.id
  integration_type   = "HTTP_PROXY"
  integration_method = "ANY"
  integration_uri    = var.alb_listener_arn
  connection_type    = "VPC_LINK"
  connection_id      = aws_apigatewayv2_vpc_link.this.id

  request_parameters = {
    "overwrite:header.X-Customer-Id" = "$context.authorizer.claims.sub"
  }
}

locals {
  public_routes = [
    "GET /api/v1/events",
    "GET /api/v1/events/{eventId}",
    "GET /api/v1/events/{eventId}/availability",
    "GET /api/v1/events/{eventId}/availability/stream",
  ]
  customer_routes = [
    "POST /api/v1/orders",
    "GET /api/v1/orders/{orderId}",
    "POST /api/v1/orders/{orderId}/confirm",
  ]
  admin_routes = [
    "POST /api/v1/events",
    "POST /api/v1/events/{eventId}/complimentary-tickets",
  ]
}

resource "aws_apigatewayv2_route" "public" {
  for_each  = toset(local.public_routes)
  api_id    = aws_apigatewayv2_api.this.id
  route_key = each.value
  target    = "integrations/${aws_apigatewayv2_integration.public.id}"
}

resource "aws_apigatewayv2_route" "customer" {
  for_each           = toset(local.customer_routes)
  api_id             = aws_apigatewayv2_api.this.id
  route_key          = each.value
  target             = "integrations/${aws_apigatewayv2_integration.customer.id}"
  authorization_type = "JWT"
  authorizer_id      = aws_apigatewayv2_authorizer.jwt.id
}

resource "aws_apigatewayv2_route" "admin" {
  for_each             = toset(local.admin_routes)
  api_id               = aws_apigatewayv2_api.this.id
  route_key            = each.value
  target               = "integrations/${aws_apigatewayv2_integration.public.id}"
  authorization_type   = "JWT"
  authorizer_id        = aws_apigatewayv2_authorizer.jwt.id
  authorization_scopes = ["${aws_cognito_resource_server.ticketing.identifier}/admin"]
}

resource "aws_cloudwatch_log_group" "access" {
  name              = "/apigateway/${var.name}"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.kms_key_arn
}

resource "aws_apigatewayv2_stage" "default" {
  api_id      = aws_apigatewayv2_api.this.id
  name        = "$default"
  auto_deploy = true

  default_route_settings {
    throttling_burst_limit = var.throttling_burst_limit
    throttling_rate_limit  = var.throttling_rate_limit
  }

  access_log_settings {
    destination_arn = aws_cloudwatch_log_group.access.arn
    format = jsonencode({
      requestId      = "$context.requestId"
      ip             = "$context.identity.sourceIp"
      routeKey       = "$context.routeKey"
      status         = "$context.status"
      latencyMs      = "$context.responseLatency"
      integrationErr = "$context.integrationErrorMessage"
      principal      = "$context.authorizer.claims.sub"
    })
  }
}
