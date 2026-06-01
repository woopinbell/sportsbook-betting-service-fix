# 베팅 접수 부하 테스트

동기 베팅 접수 경로의 처리량과 멱등성을 k6로 확인합니다. Docker Compose는
`betting-service`, PostgreSQL, Redis, Kafka를 실행하고 risk와 wallet은 항상
승인하도록 설정한 WireMock으로 대체합니다. 따라서 결과는 하위 서비스의 처리량보다
베팅 접수 오케스트레이션 자체에 가깝습니다.

## 시나리오

| 파일 | 확인 항목 |
|---|---|
| `scenarios/placement_load.js` | 지속 부하에서 처리량, p95, p99, 오류율 |
| `scenarios/idempotency.js` | 같은 `Idempotency-Key`의 순차 재요청이 하나의 `betId`로 수렴하는지 여부 |
| `scenarios/concurrency.js` | 같은 키의 동시 요청이 201 또는 409로 끝나고 5xx를 만들지 않는지 여부 |

동시성 시나리오는 응답 상태만 확인합니다. 수락된 식별자 수, 데이터베이스 행 수,
wallet 차감 횟수까지 확인하지 않으므로 중복 수락과 중복 차감을 완전히 증명하지는
않습니다.

모든 요청은 본문의 `userId`와 같은 UUID를 `X-User-Id` 헤더에도 담습니다. 두 값이
다르거나 헤더가 올바른 UUID가 아니면 서비스는 `403 FORBIDDEN`을 반환합니다.

## 실행

```sh
cd ..
./mvnw -DskipTests package
cd load-test

docker compose up -d
curl --retry 30 --retry-delay 3 --retry-all-errors -fs \
  http://localhost:58082/actuator/health/readiness

./seed.sh

mkdir -p results/$(date +%F)
k6 run -e BASE_URL=http://localhost:58082 -e RATE=150 -e DURATION=30s \
  --summary-trend-stats="avg,med,p(95),p(99),max" \
  --summary-export=results/$(date +%F)/placement_load_150.json \
  scenarios/placement_load.js
k6 run -e BASE_URL=http://localhost:58082 scenarios/idempotency.js
k6 run -e BASE_URL=http://localhost:58082 -e VUS=100 scenarios/concurrency.js

docker compose down -v
```

호스트 포트는 betting `58082`, PostgreSQL `55432`, Redis `56379`, Kafka
`59092`, WireMock `58080`을 사용합니다.

측정 결과는 날짜별 JSON으로 저장하고, 비교하기 좋은 대표 결과는
[`results/BEST.md`](results/BEST.md)에 정리합니다.
