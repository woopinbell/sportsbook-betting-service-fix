# 아웃박스와 정산 재전달

## DB 저장과 Kafka 발행 사이의 틈

bet을 ACCEPTED로 저장한 뒤 Kafka 발행이 실패하면 downstream은 수락을 모른다.
Kafka를 먼저 보내고 DB가 실패하면 존재하지 않는 bet 이벤트가 퍼진다.
`acceptAndEnqueue`는 상태 전이와 직렬화된 아웃박스 행을 한 트랜잭션으로 묶는다.

```java
if (bet.status() != BetStatus.PENDING) {
  return bet;
}
bet.accept(now);
outbox.save(event);
return bet;
```

두 저장이 같은 로컬 트랜잭션이라는 뜻이지 PostgreSQL과 Kafka가 원자적이라는 뜻은
아니다. publisher는 최대 100개의 미발행 행을 읽고 각 send의 broker ack를 최대 5초
기다린다. ack를 받으면 JPA dirty checking으로 `published_at`을 기록하고, 실패한 행은
비어 있어 다음 주기에 다시 시도된다.

```java
for (OutboxEvent event : batch) {
  if (sendBlocking(event)) {
    event.markPublished(clock.instant());
  }
}
```

ack 뒤 DB 트랜잭션이 끝나기 전에 프로세스가 종료되면 재발행될 수 있다. 따라서
eventId나 도메인 멱등 키를 소비자가 저장해야 한다. producer의
`enable.idempotence=true`는 한 producer session의 broker retry를 다루지만, 이
애플리케이션 재발행까지 한 번으로 만들지는 않는다. publisher가 published 행을
삭제하지 않는 것은 운영자가 재발행 대상을 찾는 데에는 유리하지만, 보존·정리 정책은
따로 필요하다.

현재 `publishPending()`은 `@Transactional` 상태에서 순차적으로 broker ack를
기다린다. 최대 batch가 느리면 DB 트랜잭션과 connection도 오래 유지된다. 처리량을
늘리려고 무작정 병렬 전송하면 `published_at` 갱신 순서와 같은 partition의 이벤트
순서를 함께 검토해야 한다.

## 정산의 “멱등” 범위를 좁힌다

`BetSettled`를 두 번 받았고 이미 SETTLED라면 두 번째는 no-op이다. `BetVoided`도 이미
VOIDED일 때만 같다. 하지만 SETTLED 뒤 VOIDED는 같은 사건의 재전달이 아니라 상충하는
종료 명령이다.

```java
if (bet.status() == BetStatus.SETTLED) {
  log.debug("BetSettled replay for {} — already SETTLED, skip", betId);
  return;
}
bet.settle(result, payout, settledAt);
log.info("Bet {} settled: result={} payout={}", betId, result, payout);
```

`Bet.settle()`과 `Bet.voidBet()`이 허용하지 않는 다른 종료 상태는 예외로 남는다. 알 수
없는 bet, PENDING/REJECTED bet의 첫 정산, 서로 다른 종료 종류도 listener의 retry와
DLT 정책이 처리한다. 모든 terminal 상태를 무조건 성공 처리하면 upstream 데이터
충돌이 조용히 사라진다.

같은 종류의 종료 상태에서는 저장된 내용과 새 payload를 비교하지 않는다. 이미
SETTLED면 result·payout·settledAt이 달라도 반환하고, 이미 VOIDED면 reason·voidedAt이
달라도 반환한다. 현재 멱등 키는 사실상 `betId + 종료 종류`이므로 발행자가 같은
betId의 payload를 바꾸지 않는 계약이 필요하다. 이 계약을 서비스 안에서 강제하려면
최초 종료 payload의 지문이나 eventId를 함께 저장해 재전달 때 비교해야 한다.

consumer는 record 단위로 offset을 처리하고 1초 간격으로 세 번 재시도한 뒤
`<topic>.DLT`로 보낸다. DLT topic은 서비스가 만들지 않고 orchestration이 준비한다.
DLT가 없거나 발행이 실패할 때의 운영 절차는 이 서비스에 자동화돼 있지 않다.

## 실제 검증 범위

`OutboxPublisherIntegrationTest`는 Kafka에서 user partition key와 Avro payload를 읽고
`published_at`이 생겼는지 확인한다. 빈 batch도 검사한다.
`SettlementConsumerIntegrationTest`는 정상 Avro 레코드가 ACCEPTED 베팅을 SETTLED와
VOIDED로 바꾸는 경로를 확인한다. 같은 종료 이벤트의 no-op과 알 수 없는 bet 예외는
`BetSettlementService`를 직접 호출해 검증한다.

```sh
./mvnw -Dtest=OutboxPublisherIntegrationTest,SettlementConsumerIntegrationTest test
```

현재 검사는 broker ack 뒤 DB 기록 전에 종료되는 창을 직접 주입하지 않는다. record
ack·실제 재전달, 잘못된 payload의 DLT 이동, DLT 뒤 같은 partition의 정상 record
처리와 같은 종료 종류의 payload 충돌도 추가해야 한다. 이 범위를 확인하지 않고
“모든 최종 정산 이벤트가 멱등적이다”라고 넓혀 말할 수 없다.
