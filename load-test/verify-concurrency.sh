#!/usr/bin/env sh
set -eu

idempotency_key="${1:-race-bench-v2}"

sql() {
  docker compose exec -T postgres \
    psql -U betting -d betting -Atc "$1"
}

bet_count="$(sql "SELECT count(*) FROM bet WHERE idempotency_key = '${idempotency_key}'")"
accepted_count="$(sql "SELECT count(*) FROM bet WHERE idempotency_key = '${idempotency_key}' AND status = 'ACCEPTED'")"
request_count="$(sql "SELECT count(*) FROM placement_request WHERE idempotency_key = '${idempotency_key}' AND outcome = 'BET'")"
outbox_count="$(sql "SELECT count(*) FROM outbox_event")"
wallet_count="$(
  curl -fsS -X POST http://localhost:58080/__admin/requests/count \
    -H 'Content-Type: application/json' \
    -d '{"method":"POST","urlPath":"/internal/v1/wallet/transactions/debit"}' \
    | sed -E 's/[^0-9]*([0-9]+).*/\1/'
)"

test "${bet_count}" = "1"
test "${accepted_count}" = "1"
test "${request_count}" = "1"
test "${outbox_count}" = "1"
test "${wallet_count}" = "1"

echo "verified: request=1 bet=1 accepted=1 outbox=1 wallet_debit=1"
