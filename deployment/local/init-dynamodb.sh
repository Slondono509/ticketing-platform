#!/bin/sh
# Creates the DynamoDB tables in DynamoDB Local with the same keys and indexes Terraform defines in AWS.
set -eu

ENDPOINT="${DYNAMODB_ENDPOINT:-http://dynamodb-local:8000}"

create_table() {
  name="$1"; shift
  if aws dynamodb describe-table --endpoint-url "$ENDPOINT" --table-name "$name" >/dev/null 2>&1; then
    echo "Table $name already exists"
  else
    aws dynamodb create-table --endpoint-url "$ENDPOINT" --table-name "$name" --billing-mode PAY_PER_REQUEST "$@" >/dev/null
    echo "Table $name created"
  fi
}

create_table ticketing-events \
  --attribute-definitions AttributeName=id,AttributeType=S AttributeName=entityType,AttributeType=S AttributeName=startsAt,AttributeType=N \
  --key-schema AttributeName=id,KeyType=HASH \
  --global-secondary-indexes '[{"IndexName":"upcoming-events-index","KeySchema":[{"AttributeName":"entityType","KeyType":"HASH"},{"AttributeName":"startsAt","KeyType":"RANGE"}],"Projection":{"ProjectionType":"ALL"}}]'

create_table ticketing-orders \
  --attribute-definitions AttributeName=id,AttributeType=S AttributeName=expirationShard,AttributeType=S AttributeName=expiresAt,AttributeType=N \
  --key-schema AttributeName=id,KeyType=HASH \
  --global-secondary-indexes '[{"IndexName":"reservation-expiration-index","KeySchema":[{"AttributeName":"expirationShard","KeyType":"HASH"},{"AttributeName":"expiresAt","KeyType":"RANGE"}],"Projection":{"ProjectionType":"ALL"}}]'

create_table ticketing-order-audit \
  --attribute-definitions AttributeName=orderId,AttributeType=S AttributeName=sequence,AttributeType=N \
  --key-schema AttributeName=orderId,KeyType=HASH AttributeName=sequence,KeyType=RANGE

echo "DynamoDB tables ready"
