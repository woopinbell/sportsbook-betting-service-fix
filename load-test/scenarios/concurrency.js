// Concurrency proof (over HTTP): N VUs fire the SAME Idempotency-Key at once.
// All VUs use the same actor, body, and key. After k6 completes,
// verify-concurrency.sh proves the single DB row / accept / outbox / debit effects.
//
// Run (after `docker compose up` + `./seed.sh`):
//   k6 run -e BASE_URL=http://localhost:58082 -e VUS=100 scenarios/concurrency.js

import http from 'k6/http';
import { check } from 'k6';
const BASE_URL = __ENV.BASE_URL || 'http://localhost:58082';
const VUS = parseInt(__ENV.VUS || '100', 10);
const SHARED_KEY = __ENV.IDEMPOTENCY_KEY || 'race-bench-v2';

const EVENT = '11111111-1111-7111-8111-111111111111';
const MARKET = '22222222-2222-7222-8222-222222222222';
const SELECTION = '33333333-3333-7333-8333-333333333333';
const USER = '44444444-4444-7444-8444-444444444444';

export const options = {
  scenarios: {
    same_key_race: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: 1,
      maxDuration: '15s',
    },
  },
  thresholds: {
    checks: ['rate==1.0'],
  },
};

export default function () {
  const body = JSON.stringify({
    userId: USER,
    slipType: { type: 'SINGLE' },
    selections: [
      { eventId: EVENT, marketId: MARKET, selectionId: SELECTION, odds: 2.0 },
    ],
    stake: { amount: 10000, currency: 'KRW' },
  });

  const res = http.post(`${BASE_URL}/internal/v1/bets`, body, {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': SHARED_KEY,
      'X-User-Id': USER,
    },
  });

  check(res, {
    'same payload converges to accepted or pending; never conflicts': r =>
      r.status === 201 || r.status === 202,
  });
}
