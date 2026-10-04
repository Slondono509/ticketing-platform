#!/usr/bin/env bash
# Oversell test against the running stack (docker compose up).
# Creates an event with CAPACITY tickets and fires REQUESTS concurrent purchase requests of 1 ticket each, from
# different customers. Afterwards exactly CAPACITY orders must be RESERVED and the rest REJECTED (SOLD_OUT).
#
# Usage: ./scripts/concurrency-test.sh [CAPACITY] [REQUESTS] [PARALLELISM]
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api/v1}"
API_KEY="${ADMIN_API_KEY:-local-admin-key}"
CAPACITY="${1:-50}"
REQUESTS="${2:-300}"
PARALLELISM="${3:-100}"
WORK_DIR="$(mktemp -d)"
RUN_ID="$(date +%s)"

echo "Creating event with ${CAPACITY} tickets..."
EVENT_ID=$(curl -sf -X POST "${BASE_URL}/events" -H 'Content-Type: application/json' -H "X-Api-Key: ${API_KEY}" \
  -d "{\"name\":\"Concurrency test\",\"venue\":\"Lab\",\"startsAt\":\"2030-01-01T20:00:00Z\",\"totalCapacity\":${CAPACITY}}" \
  | sed -E 's/.*"id":"([^"]+)".*/\1/')
echo "Event ${EVENT_ID}"

echo "Firing ${REQUESTS} purchase requests with parallelism ${PARALLELISM}..."
seq 1 "${REQUESTS}" | xargs -P "${PARALLELISM}" -I{} sh -c \
  "curl -s -o '${WORK_DIR}/order-{}.json' -w '%{http_code}\n' -X POST '${BASE_URL}/orders' \
     -H 'Content-Type: application/json' -H 'X-Customer-Id: customer-${RUN_ID}-{}' -H 'Idempotency-Key: key-${RUN_ID}-{}' \
     -d '{\"eventId\":\"${EVENT_ID}\",\"quantity\":1}'" > "${WORK_DIR}/codes.txt"

echo "HTTP status codes:"
sort "${WORK_DIR}/codes.txt" | uniq -c

echo "Waiting for the consumers to drain the queue..."
sleep "${DRAIN_SECONDS:-10}"

reserved=0; rejected=0; other=0
for file in "${WORK_DIR}"/order-*.json; do
  order_id=$(sed -nE 's/.*"orderId":"([^"]+)".*/\1/p' "$file")
  [ -z "$order_id" ] && continue
  customer=$(basename "$file" .json | sed "s/order-/customer-${RUN_ID}-/")
  status=$(curl -s "${BASE_URL}/orders/${order_id}" -H "X-Customer-Id: ${customer}" | sed -nE 's/.*"status":"([A-Z_]+)".*/\1/p')
  case "$status" in
    RESERVED) reserved=$((reserved + 1)) ;;
    REJECTED) rejected=$((rejected + 1)) ;;
    *) other=$((other + 1)) ;;
  esac
done

echo "Orders RESERVED=${reserved} REJECTED=${rejected} OTHER=${other} (409 at the API are fail-fast sold-outs)"
echo "Availability:"
curl -s "${BASE_URL}/events/${EVENT_ID}/availability"; echo

rm -rf "${WORK_DIR}"
if [ "${reserved}" -eq "${CAPACITY}" ]; then
  echo "OK: no overselling, exactly ${CAPACITY} tickets reserved"
else
  echo "FAILED: expected ${CAPACITY} reserved orders, got ${reserved}"; exit 1
fi
