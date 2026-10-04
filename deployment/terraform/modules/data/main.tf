# DynamoDB tables. On-demand capacity absorbs the spikes of an on-sale without capacity planning (and costs
# nothing when idle); PITR and deletion protection guard against operator mistakes.

locals {
  common = {
    billing_mode                = "PAY_PER_REQUEST"
    deletion_protection_enabled = var.deletion_protection
  }
}

resource "aws_dynamodb_table" "events" {
  name                        = "${var.name}-events"
  billing_mode                = local.common.billing_mode
  deletion_protection_enabled = local.common.deletion_protection_enabled
  hash_key                    = "id"

  attribute {
    name = "id"
    type = "S"
  }
  attribute {
    name = "entityType"
    type = "S"
  }
  attribute {
    name = "startsAt"
    type = "N"
  }

  # Lists upcoming events sorted by date without a Scan.
  global_secondary_index {
    name            = "upcoming-events-index"
    projection_type = "ALL"

    key_schema {
      attribute_name = "entityType"
      key_type       = "HASH"
    }
    key_schema {
      attribute_name = "startsAt"
      key_type       = "RANGE"
    }
  }

  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled     = true
    kms_key_arn = var.kms_key_arn
  }
}

resource "aws_dynamodb_table" "orders" {
  name                        = "${var.name}-orders"
  billing_mode                = local.common.billing_mode
  deletion_protection_enabled = local.common.deletion_protection_enabled
  hash_key                    = "id"

  attribute {
    name = "id"
    type = "S"
  }
  attribute {
    name = "expirationShard"
    type = "S"
  }
  attribute {
    name = "expiresAt"
    type = "N"
  }

  # Sparse index: only RESERVED orders carry expirationShard/expiresAt.
  global_secondary_index {
    name            = "reservation-expiration-index"
    projection_type = "ALL"

    key_schema {
      attribute_name = "expirationShard"
      key_type       = "HASH"
    }
    key_schema {
      attribute_name = "expiresAt"
      key_type       = "RANGE"
    }
  }

  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled     = true
    kms_key_arn = var.kms_key_arn
  }
}

resource "aws_dynamodb_table" "order_audit" {
  name                        = "${var.name}-order-audit"
  billing_mode                = local.common.billing_mode
  deletion_protection_enabled = local.common.deletion_protection_enabled
  hash_key                    = "orderId"
  range_key                   = "sequence"

  attribute {
    name = "orderId"
    type = "S"
  }
  attribute {
    name = "sequence"
    type = "N"
  }

  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled     = true
    kms_key_arn = var.kms_key_arn
  }
}
