#!/bin/sh
# Local stand-in for EventBridge Scheduler: publishes a tick to the reservation-expiration queue every
# SCHEDULE_INTERVAL_SECONDS, exactly as the "rate(1 minute)" schedule does in AWS (see deployment/terraform).
set -eu

QUEUE_URL="${RESERVATION_EXPIRATION_QUEUE_URL:-http://elasticmq:9324/000000000000/ticketing-reservation-expiration}"
INTERVAL="${SCHEDULE_INTERVAL_SECONDS:-60}"

echo "Publishing a reservation-expiration tick every ${INTERVAL}s to ${QUEUE_URL}"
while true; do
  sleep "$INTERVAL"
  aws sqs send-message --endpoint-url "${SQS_ENDPOINT:-http://elasticmq:9324}" --queue-url "$QUEUE_URL" \
    --message-body "{\"source\":\"local-scheduler\",\"time\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}" >/dev/null \
    && echo "Tick sent at $(date -u +%H:%M:%S)" \
    || echo "Tick failed, retrying on next interval"
done
