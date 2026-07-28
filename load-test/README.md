# 베팅 접수 부하 테스트

동기 베팅 접수 경로의 처리량과 멱등성을 k6로 확인합니다. Docker Compose는 betting-service, PostgreSQL, Redis, Kafka를 실행하고 risk와 wallet은 항상 승인하는 WireMock으로 대체합니다. 따라서 하위 서비스가 포함된 전체 시스템 용량이 아니라 접수 오케스트레이션을 측정합니다.

## 시나리오

| 파일 | 확인 항목 |
| --- | --- |
| `scenarios/placement_load.js` | 지속 부하의 처리량, p95, p99, 오류율 |
| `scenarios/idempotency.js` | 같은 키의 순차 재요청이 하나의 betId로 수렴하는지 |
| `scenarios/concurrency.js` | 같은 사용자·본문·키의 동시 요청 100건 |

동시성 시나리오는 `201` 또는 `202`만 허용합니다. `verify-concurrency.sh`는 `placement_request`, 베팅, `ACCEPTED` 상태, 아웃박스와 지갑 차감이 각각 하나인지 확인합니다. 다른 시나리오의 데이터가 섞이지 않도록 새 볼륨에서 실행해야 합니다.

## 실행

```sh
cd ..
./mvnw -DskipTests package
cd load-test

docker compose up -d
curl --retry 30 --retry-delay 3 --retry-all-errors -fs \
  http://localhost:58082/actuator/health/readiness
./seed.sh

k6 run -e BASE_URL=http://localhost:58082 -e RATE=150 -e DURATION=30s \
  --summary-trend-stats="avg,med,p(95),p(99),max" \
  scenarios/placement_load.js
k6 run -e BASE_URL=http://localhost:58082 scenarios/idempotency.js

docker compose down -v
docker compose up -d
./seed.sh
k6 run -e BASE_URL=http://localhost:58082 -e VUS=100 \
  -e IDEMPOTENCY_KEY=race-bench-v2 scenarios/concurrency.js
./verify-concurrency.sh race-bench-v2
docker compose down -v
```

호스트 포트는 betting `58082`, PostgreSQL `55432`, Redis `56379`, Kafka `59092`, WireMock `58080`입니다.

처리량 결과를 남길 때에는 CPU·메모리 제한, Java 버전, k6 rate와 duration, warm-up, 데이터 초기화 방법을 함께 기록합니다. 동시성 개수 검증이 실패한 실행은 지연 수치가 좋아도 채택하지 않습니다.

현재 채택 상태와 결과 추가 조건은 [성능 검증 상태](results/BEST.md)에 정리했습니다.
