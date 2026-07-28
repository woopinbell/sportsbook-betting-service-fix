# 베팅 접수의 일관성 경계

## 한 트랜잭션으로 묶을 수 없는 흐름

접수 한 건은 PostgreSQL, risk-service, wallet-service와 Kafka를 지난다. 이들을 하나의 데이터베이스 트랜잭션으로 묶지 않는다. 외부 HTTP 호출 동안 커넥션과 행 잠금을 잡지 않고, 각 로컬 상태 전이만 짧은 트랜잭션으로 저장한다.

```text
CREATED
  -> RISK_RESERVED
  -> WALLET_CONFIRMED
  -> RISK_COMMITTED
  -> ACCEPTED + outbox
```

각 단계는 `BetStore`의 별도 `@Transactional` 메서드다. `BetPlacementService`의 `private` 메서드에 `@Transactional`을 붙이면 자기 호출이 Spring 프록시를 거치지 않으므로 트랜잭션 경계가 생기지 않는다.

## 멱등 키의 소유자는 PostgreSQL이다

`placement_request.idempotency_key`가 요청 하나의 유일한 소유권 경계다. 요청 지문과 사용자도 함께 저장한다.

- 같은 사용자·같은 본문: 저장된 결과 재생
- 다른 사용자 또는 다른 본문: `DUPLICATE_BET`
- 사전 검증 거절: bet 없이 거절 판정만 저장
- 유효한 요청: PENDING bet와 요청 행을 함께 저장

`IdempotencyCache`는 수락한 betId를 Redis에 운영 확인용으로 기록하지만 요청 처리 중에는 이 값을 읽지 않는다. 요청 재생과 동시성 판정은 DB 유일 제약을 기준으로 하므로 Redis 기록이 없거나 서로 다른 인스턴스가 요청을 받아도 하나의 결과에 수렴한다.

## 외부 응답이 모호하면 PENDING이다

연결 단절이나 timeout은 상대 서비스가 작업을 수행하지 않았다는 뜻이 아니다. 이런 경우 거절로 확정하지 않고 마지막 저장 단계의 PENDING bet를 반환한다. 일정 시간 지난 PENDING은 `BetReconciliationJob`이 다시 읽어 이어 간다.

지갑 차감 단계의 복구는 먼저
`GET /internal/v1/wallet/transactions/debit/{betId}`로 결과를 조회한다. 결과가 없을
때만 같은 betId로 debit을 다시 요청한다. risk와 wallet 모두 betId를 멱등 키로
받아야 이 재시도가 안전하다.

## 보상도 먼저 의도를 저장한다

잔액 부족 전에 risk 예약이 있었다면 `RISK_RELEASE`, 차감 뒤 risk 확정이 불가능하면 `WALLET_REFUND`가 필요하다. 외부 보상을 먼저 호출하고 나중에 기록하면 성공 응답 유실 시 forward 경로를 다시 실행할 수 있다.

```text
NONE -> REQUIRED -> IN_PROGRESS -> COMPLETED -> REJECTED
```

action과 state를 먼저 저장한 뒤 외부 호출을 수행한다. 이후 복구는 해당 보상만 재실행하며 debit, risk commit, accept 경로로 돌아가지 않는다.

## 수락과 Kafka 사이

`ACCEPTED` 전이와 아웃박스 행 삽입은 같은 PostgreSQL 트랜잭션이다. publisher는 미발행 행을 읽어 Kafka broker ack를 기다린 뒤 `published_at`을 기록한다. 전송 성공 뒤 DB 기록 전에 중단되면 같은 이벤트가 다시 발행될 수 있으므로 전달 의미는 at-least-once다.

정산 소비자는 이미 같은 종류의 종료 상태이면 payload를 비교하지 않고 no-op으로
처리한다. 같은 `BetSettled`라도 결과나 지급액이 달라진 재전달을 충돌로 찾지는
못한다. SETTLED bet에 VOIDED가 오거나 그 반대인 경우는 중복이 아니라 상충하는
판정이므로 retry/DLT 대상이다.
