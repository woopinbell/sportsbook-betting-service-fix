# 수락과 발행을 분리하는 아웃박스 행

bet을 `ACCEPTED`로 저장한 뒤 Kafka 전송이 실패하면 후속 서비스는 수락을 모른다.
Kafka를 먼저 보내고 DB 커밋이 실패하면 존재하지 않는 bet 사건이 퍼진다. 접수
흐름을 만들기 전, 이 두 쓰기를 한 트랜잭션에 넣을 수 있도록 저장 형식부터 준비했다.

`V2__outbox.sql`은 사건 ID, 토픽, 파티션 키, 스키마 이름, 바이트 페이로드, 생성
시각과 `published_at`을 둔다. 미발행 행만 찾는 부분 인덱스도 이때 추가했다.
다음 커밋의 `OutboxEvent`는 페이로드 바이트 배열을 복사해 저장 뒤 외부 변경으로
내용이 달라지지 않게 했다.

## 전달할 페이로드는 수락 시점에 고정한다

`src/main/java/com/sportsbook/betting/outbox/BetEventFactory.java`는 수락 사건으로
내보낼 `BetPlacedRequested` Avro 레코드를 만든다. 이 시점에는 접수 서비스가 아직
없었으므로 팩터리를 호출하는 코드도 없었다. 팩터리가 받도록 설계한 값은 수락될
PENDING 베팅 집계이며, 슬립 종류와 System K/N, 선택지 순서, 제출 배당, 단위 지분과
멱등 키를 페이로드에 담는다.

```java
return OutboxEvent.pending(
    UuidV7.generate(), TOPIC, bet.userId().toString(), SCHEMA, payload, now);
```

한 슬립은 여러 경기를 포함할 수 있어 경기 ID를 파티션 키로 고를 수 없다. 위험 관리
서비스와 후속 서비스의 사용자별 자료를 같은 Kafka 파티션에 모으려고 사용자 ID를
쓴다. 이 선택만으로 업무 순서까지 보장되지는 않는다. Avro 원시 바이트를 저장하며
스키마 레지스트리는 없으므로 발행자와 소비자가 같은 shared-protocol 버전에 맞아야
한다.

여기까지의 두 커밋은 테이블과 사건 팩터리만 준비했다. 아직 bet 상태와 아웃박스
행을 실제로 함께 저장하지도, 미발행 행을 Kafka로 보내지도 않았다. 다음
[첫 접수 흐름](04-first-placement-and-reconciliation-gap.md)에서
`BetStore.acceptAndEnqueue()`가 이 행을 수락 트랜잭션에 붙이고, 그 뒤 발행기가
전달을 맡는다. 스키마를 먼저 만든 사실과 원자성 보장이 완성된 시점을 구분해야 한다.
