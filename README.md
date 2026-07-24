# 베팅 서비스

`betting-service`는 베팅 접수와 상태 관리를 담당합니다. 사용자가 제출한 단식,
복식, 시스템 베팅을 검증하고 `risk-service`와 `wallet-service`를 차례로 호출해
수락을 진행합니다. 확정할 수 없는 외부 응답은 거절로 추측하지 않고 `PENDING`으로
남겨 복구 작업이 이어받습니다. 수락한 베팅의 정산 결과는 Kafka로 받아 반영합니다.

## 주요 처리 흐름

베팅 접수는 한 HTTP 요청 안에서 다음 순서로 처리합니다.

1. 슬립 구조와 베팅 금액을 검증합니다.
2. Redis에 저장된 현재 배당과 비교해 마켓 상태와 허용 오차를 확인합니다.
3. 검증에서 거절됐거나 `PENDING` 상태인 베팅을 `placement_request`의 단일 멱등 키와 함께
   저장합니다.
4. risk 한도를 원자적으로 예약하고 `RISK_RESERVED` 단계를 저장합니다.
5. wallet 차감을 확인하고 `WALLET_CONFIRMED` 단계를 저장합니다.
6. risk 예약을 확정하고 `RISK_COMMITTED` 단계로 전환합니다.
7. 베팅 수락과 수락 이벤트를 같은 트랜잭션에서 기록합니다.

외부 HTTP 호출 중에는 데이터베이스 트랜잭션을 유지하지 않습니다. 각 저장 작업을
짧은 트랜잭션으로 나눠 네트워크 지연이 커넥션 풀을 점유하지 않도록 했습니다.
인프라 오류는 Resilience4j의 타임아웃과 서킷 브레이커로 처리하며, 한도 초과나 잔액
부족 같은 정상적인 거절은 장애로 집계하지 않습니다.

## 일관성

- PostgreSQL `placement_request` 기본 키가 `Idempotency-Key`의 유일한 소유권
  경계입니다. Redis 선점은 사용하지 않으므로 같은 본문의 동시 요청은 `409`가 아니라
  하나의 bet/`PENDING` 결과로 수렴합니다.
- 요청 본문의 SHA-256 해시를 함께 저장하므로 같은 키에 다른 본문을 보내면
  `409 DUPLICATE_BET`을 반환합니다.
- 베팅 객체를 만들기 전의 검증, 배당 변동, 마켓 종료로 인한 거절도 원래 오류 코드와
  상세 내용을 저장합니다. 이후 정책이나 실시간 상태가 바뀌어도 같은 요청은 최초
  RFC 7807 판정을 재현합니다.
- 같은 `betId`를 risk 예약과 wallet 차감의 멱등 키로 전달해 중복 효과를 막습니다.
- 베팅 수락과 `BetPlacedRequested` 아웃박스 저장을 같은 트랜잭션에서 처리합니다.
- 복구 작업은 저장된 접수 단계부터 재개합니다. 지갑 응답이 유실된 경우 먼저
  `GET /internal/v1/wallet/transactions/debit/{betId}`로 조회하고, 404일 때만 같은 키로
  차감을 재시도합니다.
- 잔액 부족 시에는 `RISK_RELEASE` 보상 작업을 먼저 저장하고 예약 해제가 성공한 뒤에만
  거절합니다. 지갑 차감 후 위험 한도 예약이 만료되고 재예약도 거절되면
  `WALLET_REFUND` 보상 의도를 먼저 저장하고 `refund:<betId>`로 환불한 뒤 거절합니다.
  두 분기 모두 `REQUIRED → IN_PROGRESS → COMPLETED`를 영속화하므로 응답 유실 뒤에는
  보상만 재시도하며 차감·위험 한도 확정·수락 경로로 돌아가지 않습니다.
- 확정 거절의 오류 코드와 상세 내용을 저장하므로 같은 요청은 원래 RFC 7807 결과를
  재현합니다.

## 정산

`settlement-service`가 발행한 `BetSettled`와 `BetVoided` 이벤트를 받아
`ACCEPTED` 베팅을 각각 `SETTLED`와 `VOIDED`로 전환합니다. 같은 이벤트가 다시
전달되어도 상태가 중복 변경되지 않도록 `betId` 기준으로 멱등 처리합니다. 승패 판정과
지급액 계산은 settlement의 책임이며, 이 서비스는 전달받은 결과만 저장합니다.

## 인터페이스

### HTTP

- `POST /internal/v1/bets`: 수락 완료는 `201`, 진행 결과가 모호하면 `202`와 공개
  gateway 조회 경로인 `Location: /api/v1/bets/{id}` 및 `PENDING` 본문을 반환합니다.
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

`shared-protocol` 0.3.0을 로컬 Maven 저장소에 먼저 설치해야 합니다. 통합 테스트는
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

## 성능 검증 상태

복구 가능한 접수 상태 머신을 도입한 뒤에는 처리량을 다시 측정하지 않았으므로 현재
코드의 RPS나 지연 수치를 제시하지 않습니다. 동시성 검증은 같은 사용자와 같은 키로
100건을 요청한 뒤 데이터베이스 행, `ACCEPTED` 상태, 아웃박스 및 WireMock 지갑
차감이 각각 하나인지 확인합니다.

날짜별 기존 결과는 안정화 전 구현의 과거 측정 자료일 뿐 현재 코드의 성능 근거가
아닙니다. 새 처리량은 이 정합성 검사를 함께 통과하고 소스·공통 계약·환경을 기록한
실행만 채택합니다. 자세한 방법은 [부하 테스트 문서](load-test/README.md)에 있습니다.

## 현재 제한

- SGP와 Bet Builder
- cash out
- in-play 베팅
- 서버 측 베팅 카트
- Asian handicap의 half-won, half-lost 결과
