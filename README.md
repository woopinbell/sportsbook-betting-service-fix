# 베팅 서비스

`betting-service`는 베팅 접수와 상태 관리를 담당합니다. 사용자가 제출한 단식,
복식, 시스템 베팅을 검증하고 `risk-service`와 `wallet-service`를 차례로 호출해
수락을 진행합니다. 위험 관리·지갑 서비스의 결과를 확정할 수 없으면 거절로
추측하지 않고 저장한 `PENDING`을 복구 작업이 이어받습니다. 수락한 베팅의 정산
결과는 Kafka로 받아 반영합니다.

## 주요 처리 흐름

베팅 접수는 한 HTTP 요청 안에서 다음 순서로 처리합니다.

1. 슬립 구조와 베팅 금액을 검증합니다.
2. Redis에 저장된 현재 배당과 비교해 마켓 상태와 허용 오차를 확인합니다.
3. 베팅 객체를 만들 수 있는 요청은 `PENDING` 베팅과 `placement_request`를 함께
   저장합니다. 슬립 검증·마켓 종료·배당 변동의 사전 거절은 베팅 없이
   `placement_request`에만 저장합니다.
4. 위험 한도를 원자적으로 예약하고 `RISK_RESERVED` 단계를 저장합니다.
5. 지갑 차감을 확인하고 `WALLET_CONFIRMED` 단계를 저장합니다.
6. 위험 한도 예약을 확정하고 `RISK_COMMITTED` 단계로 전환합니다.
7. 베팅 수락과 수락 이벤트를 같은 트랜잭션에서 기록합니다.

외부 HTTP 호출 중에는 데이터베이스 트랜잭션을 유지하지 않습니다. 각 저장 작업을
짧은 트랜잭션으로 나눠 네트워크 지연이 커넥션 풀을 점유하지 않도록 했습니다.
연결·읽기 제한 시간은 `ClientConfig`의 `RestClient` 요청 팩토리가 적용하고,
Resilience4j 서킷 브레이커가 연속 장애와 열린 회로의 대체 처리를 맡습니다. 한도
초과나 잔액 부족 같은 정상적인 거절은 서킷 브레이커 장애로 집계하지 않습니다.
이 복구 규칙은 위험 관리·지갑 서비스 호출에 적용됩니다. 그보다 앞선 배당 검증에서 Redis
접근 자체가 실패하면 현재 코드는 베팅이나 `placement_request`를 만들지 않고 요청을
오류로 끝냅니다.

## 일관성

- PostgreSQL `placement_request` 기본 키가 현재 요청 결과를 찾는 주된
  `Idempotency-Key` 기준 자료입니다. V1의 `bet.idempotency_key` 유일 제약도 남아 있고
  서비스는 부분 마이그레이션 자료를 위해 bet 직접 조회를 보조 경로로 사용합니다.
  Redis 선점은 쓰지 않으므로 같은 본문의 동시 요청은 잘못된 `409`를 반환하지 않고
  하나의 betId로 수렴합니다. 각 응답은 요청이 저장된 단계를 읽은 시점에 따라
  `PENDING` 또는 `ACCEPTED`일 수 있습니다.
- 새 요청은 본문의 SHA-256 해시를 함께 저장하므로 같은 키에 다른 본문을 보내면
  `409 DUPLICATE_BET`을 반환합니다. V5 이전 행을 V6에서 옮길 때 요청 지문이 없거나
  `legacy-` 값인 자료는 사용자만 비교하고 본문 차이는 검사하지 않습니다.
- 베팅 객체를 만들기 전의 검증, 배당 변동, 마켓 종료로 인한 거절도 원래 오류 코드와
  상세 내용을 저장합니다. 이후 정책이나 실시간 상태가 바뀌어도 같은 요청은 최초
  RFC 7807 판정을 재현합니다.
- 같은 `betId`를 위험 한도 예약과 지갑 차감의 멱등 키로 전달해 중복 효과를 막습니다.
- 베팅 수락과 `BetPlacedRequested` 아웃박스 저장을 같은 트랜잭션에서 처리합니다.
  발행기는 오래된 행부터 읽지만 실패한 행 뒤의 전송을 멈추지 않으며 행을 점유하지도
  않습니다. 따라서 같은 사용자 파티션의 엄격한 업무 순서나 여러 인스턴스의 단일
  발행을 보장하지 않습니다.
- 복구 작업은 저장된 접수 단계부터 재개합니다. 지갑 응답이 유실된 경우 먼저
  `GET /internal/v1/wallet/transactions/debit/{betId}`로 조회하고, 404일 때만 같은 키로
  차감을 재시도합니다.
- 잔액 부족 시에는 `RISK_RELEASE` 보상 작업을 먼저 저장하고 예약 해제가 성공한 뒤에만
  거절합니다. 지갑 차감 후 위험 한도 예약이 만료되고 재예약도 거절되면
  `WALLET_REFUND` 보상 의도를 먼저 저장하고 `refund:<betId>`로 환불한 뒤 거절합니다.
  두 분기 모두 `REQUIRED → IN_PROGRESS → COMPLETED`를 영속화하므로 응답 유실 뒤에는
  보상만 재시도하며 차감·위험 한도 확정·수락 경로로 돌아가지 않습니다.

## 정산

`settlement-service`가 발행한 `BetSettled`와 `BetVoided` 이벤트를 받아
`ACCEPTED` 베팅을 각각 `SETTLED`와 `VOIDED`로 전환합니다. 이미 같은 종료 상태라면
페이로드를 다시 비교하지 않고 아무 일도 하지 않습니다. 따라서 같은 종류이지만 결과·지급액
또는 무효 사유가 달라진 이벤트는 이 서비스가 충돌로 탐지하지 않습니다. 반대 종류의
종료 이벤트, 알 수 없는 bet, `ACCEPTED` 이외 상태의 첫 종료 이벤트는 정상 재전달로
보지 않고 예외를 발생시켜 재시도와 DLT 경로로 보냅니다. 승패 판정과 지급액 계산은
정산 서비스의 책임이며, 이 서비스는 전달받은 결과만 저장합니다. 리스너는 사건의
`userId`, `eventId`, 원 지분 또는 환불액을 기존 bet과 대조하지 않습니다. SETTLED와
VOIDED가 서로 다른 토픽에서 상충하면 먼저 커밋된 종료 상태가 남고 반대 레코드는
DLT 경로로 갑니다. 두 토픽을 합친 전체 처리 순서는 없습니다.

## 설계와 문제 해결 기록

- [베팅 접수의 상태와 일관성 경계](architecture/betting-consistency-boundaries.md)
- [구현 순서로 읽는 개발 기록](devlog/README.md)

## 인터페이스

### HTTP

- `POST /internal/v1/bets`: 수락 완료는 `201`, 진행 결과가 모호하면 `202`와 공개
  게이트웨이 조회 경로인 `Location: /api/v1/bets/{id}` 및 `PENDING` 본문을 반환합니다.
- `GET /internal/v1/bets/{id}`: 베팅 한 건을 조회합니다.
- `GET /internal/v1/bets?userId=&cursor=&limit=`: 사용자의 베팅을 커서 방식으로
  조회합니다.

모든 요청은 게이트웨이가 검증한 UUID 형식의 `X-User-Id`를 사용합니다. 본문이나
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

접수 상태 머신의 처리량과 지연 수치는 아직 제시하지 않습니다. 동시성 검증은 한
사용자가 같은 키로 100건을 요청한 뒤 `placement_request`, 베팅, `ACCEPTED` 상태,
아웃박스와 WireMock 지갑 차감이 각각 하나인지 확인합니다. 측정 절차와 결과 채택
조건은 [부하 테스트 문서](load-test/README.md)에 있습니다.

## 현재 제한

- SGP와 Bet Builder
- cash out
- in-play 베팅
- 서버 측 베팅 카트
- Asian handicap의 half-won, half-lost 결과
