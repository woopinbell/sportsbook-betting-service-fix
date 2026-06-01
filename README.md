# betting-service

`betting-service`는 베팅 접수와 상태 관리를 담당합니다. 사용자가 제출한 단식,
복식, 시스템 베팅을 검증하고 `risk-service`와 `wallet-service`를 차례로 호출해
수락 여부를 즉시 결정합니다. 수락한 베팅의 정산 결과는 Kafka로 받아 최종 상태에
반영합니다.

## 주요 처리 흐름

베팅 접수는 한 HTTP 요청 안에서 다음 순서로 처리합니다.

1. 슬립 구조와 베팅 금액을 검증합니다.
2. Redis에 저장된 현재 배당과 비교해 마켓 상태와 허용 오차를 확인합니다.
3. 베팅을 `PENDING` 상태로 저장합니다.
4. `risk-service`에서 한도를 확인하고 `wallet-service`에서 금액을 차감합니다.
5. 베팅을 `ACCEPTED` 또는 `REJECTED`로 전환합니다.
6. 수락 이벤트를 같은 트랜잭션의 outbox에 기록합니다.

외부 HTTP 호출 중에는 데이터베이스 트랜잭션을 유지하지 않습니다. 각 저장 작업을
짧은 트랜잭션으로 나눠 네트워크 지연이 커넥션 풀을 점유하지 않도록 했습니다.
인프라 오류는 Resilience4j의 타임아웃과 서킷 브레이커로 처리하며, 한도 초과나 잔액
부족 같은 정상적인 거절은 장애로 집계하지 않습니다.

## 일관성

- `Idempotency-Key`를 Redis에서 먼저 확인하고 데이터베이스 고유 제약으로 다시
  보호합니다.
- 같은 `betId`를 risk와 wallet 호출의 멱등 키로 전달해 재시도에 따른 중복 차감을
  막습니다.
- 베팅 수락과 `BetPlacedRequested` outbox 저장을 같은 트랜잭션에서 처리합니다.
- 차감 뒤 수락 처리가 유실되어 `PENDING`에 남은 베팅은 reconciliation 작업이 다시
  확인합니다. 재차 차감에 성공하면 수락하고, 잔액 부족이면 거절하며, 하위 서비스가
  응답하지 않으면 다음 실행까지 보류합니다.

## 정산

`settlement-service`가 발행한 `BetSettled`와 `BetVoided` 이벤트를 받아
`ACCEPTED` 베팅을 각각 `SETTLED`와 `VOIDED`로 전환합니다. 같은 이벤트가 다시
전달되어도 상태가 중복 변경되지 않도록 `betId` 기준으로 멱등 처리합니다. 승패 판정과
지급액 계산은 settlement의 책임이며, 이 서비스는 전달받은 결과만 저장합니다.

## 인터페이스

### HTTP

- `POST /internal/v1/bets`: 베팅을 접수합니다.
- `GET /internal/v1/bets/{id}`: 베팅 한 건을 조회합니다.
- `GET /internal/v1/bets?userId=&cursor=&limit=`: 사용자의 베팅을 커서 방식으로
  조회합니다.

모든 요청은 gateway가 검증한 UUID 형식의 `X-User-Id`를 사용합니다. 본문이나
쿼리의 사용자와 헤더의 사용자가 다르면 `403 FORBIDDEN`을 반환하며, 다른 사용자의
베팅은 존재하지 않는 항목과 마찬가지로 `404 BET_NOT_FOUND`를 반환합니다.

### Kafka

- 발행: `BetPlacedRequested` (`bet.placed.v1`)
- 구독: `BetSettled` (`bet.settled.v1`), `BetVoided` (`bet.voided.v1`)

## 기술 구성

- Java 17, Spring Boot 3.2.11, Maven
- PostgreSQL 16, Flyway, Spring Data JPA
- Redis
- Kafka, Avro
- Resilience4j
- Micrometer, OpenTelemetry, Prometheus
- JUnit 5, Testcontainers, WireMock, Embedded Kafka

## 빌드와 검증

`shared-protocol` 0.2.0을 로컬 Maven 저장소에 먼저 설치해야 합니다. 통합 테스트는
Testcontainers를 사용하므로 Docker가 실행 중이어야 합니다.

```sh
cd ../sportsbook-shared-protocol
./mvnw install

cd ../sportsbook-betting-service
./mvnw compile
./mvnw verify
```

로컬 실행에는 PostgreSQL, Redis, Kafka가 필요합니다. 접속 정보는
`BETTING_DB_URL`, `BETTING_REDIS_HOST`, `BETTING_KAFKA_BOOTSTRAP`으로
설정할 수 있으며 기본 HTTP 포트는 `8082`입니다.

## 성능 확인

2026년 5월 29일 개발 환경에서 초당 150건을 목표로 접수 부하를 실행했습니다.
실측 처리량은 149.6 RPS, p95는 120.7ms, p99는 148.5ms였고 오류는 없었습니다.
동일한 키를 순차적으로 50회 보낸 요청은 하나의 `betId`로 수렴했습니다.

동일 키 100건을 동시에 보낸 시나리오는 응답이 모두 201 또는 409이고 5xx가 없다는
점까지만 확인했습니다. 단일 수락, 단일 데이터베이스 행, 단일 wallet 차감까지
검증하지는 않으므로 해당 정합성은 아직 측정 과제로 남아 있습니다. 자세한 실행 방법과
수치는 [부하 테스트 결과](load-test/results/BEST.md)에서 확인할 수 있습니다.

## 현재 제한

- SGP와 Bet Builder
- cash out
- in-play 베팅
- 서버 측 베팅 카트
- Asian handicap의 half-won, half-lost 결과

인프라 타임아웃과 5xx는 현재 오류 코드 목록에 별도의
`SERVICE_UNAVAILABLE` 항목이 없어 HTTP 500으로 변환합니다.
