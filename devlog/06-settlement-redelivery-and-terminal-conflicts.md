# 정산 재전달과 상충하는 종료 판정

접수 수락은 이 서비스가 결정하지만 실제 승패와 무효 처리는 정산 서비스에서
Kafka로 돌아온다.
`src/main/java/com/sportsbook/betting/settlement/SettlementResultListener.java`는
`bet.settled.v1`과 `bet.voided.v1`의 Avro 원시 바이트를 공용 프로토콜 클래스로
해석한 뒤 정산에는 bet ID·결과·지급액·시각을, 무효화에는 bet ID·사유·시각을
`BetSettlementService`에 넘긴다. 전송 스키마가 베팅 집계 안으로 퍼지지 않는
경계지만 사건의 모든 사실을 검증하는 경계는 아니다.

`applySettled()`와 `applyVoided()`는 각각 한 짧은 트랜잭션에서 bet을 읽고
종료 상태를 적용한다. 이미 같은 목표 상태라면 재전달로 보고 아무 일도 하지 않는다.

```java
if (bet.status() == BetStatus.SETTLED) {
  log.debug("BetSettled replay for {} — already SETTLED, skip", betId);
  return;
}
bet.settle(result, payout, settledAt);
```

SETTLED 뒤 VOIDED, VOIDED 뒤 SETTLED는 같은 사건의 재전달이 아니다. 베팅 집계는
오직 `ACCEPTED`에서 첫 종료 전이만 허용하므로 상충하는 사건은 예외가 된다. 알 수
없는 bet, PENDING이나 REJECTED bet의 첫 정산도 조용히 버리지 않는다.

## 같은 종료 종류의 페이로드는 비교하지 않는다

현재 아무 일도 하지 않는 조건은 `betId + 목표 상태`뿐이다. 이미 SETTLED라면 새
사건의 결과, 지급액, 정산 시각이 달라도 비교하지 않고 끝낸다. VOIDED도 사유와
시각이 다른 재전달을 충돌로 찾지 못한다.

따라서 발행자가 같은 bet ID와 종료 종류의 페이로드를 바꾸지 않는 계약이 필요하다.
서비스 안에서 강제하려면 원본 사건 ID나 페이로드 지문을 저장하고 재전달 때
비교해야 한다. “정산이 멱등이다”라는 표현은 이 제한을 포함해야 한다.

`BetSettled`의 사용자 ID, 경기 ID와 원 지분, `BetVoided`의 사용자 ID, 경기 ID와
환불액은 리스너가 읽어 기존 bet과 대조하지 않는다. 지급액 통화는 베팅 집계가
지분 통화와 비교하지만 나머지 일관성은 발행자 계약에 맡긴다.

SETTLED와 VOIDED는 서로 다른 토픽에서 들어와 둘을 합친 소비 순서가 없다. 상충하는
두 레코드가 경쟁하면 먼저 종료 전이를 커밋한 종류가 남고, 뒤 레코드는
`ACCEPTED`가 아닌 bet을 만나 재시도와 DLT 경로로 간다. Kafka 도착 순서를 업무
우선순위로 사용할 수 없다.

## 레코드 재시도 뒤 DLT로 간다

`src/main/java/com/sportsbook/betting/config/KafkaConfig.java`는 자동 커밋을 끄고
레코드 단위 확인을 쓴다. 리스너 예외는 1초 간격으로 세 번 재시도한 뒤 기본 복구
처리기가 원 토픽 이름에 `.DLT`를 붙인 곳으로 보낸다. DLT 토픽은 이
애플리케이션이 만들지 않고 orchestration이 준비한다.

원시 바이트가 공용 프로토콜과 맞지 않거나 종료 전이가 상충하면 같은 정책을 지난다.
DLT가 없거나 DLT 발행도 실패했을 때 자동 복구하는 코드는 이 저장소에 없다. 운영자는
DLT 페이로드와 원인, 수정 뒤 재주입 순서를 따로 가져야 한다.

발행자의 멱등 설정이나 소비자의 레코드 확인도 도메인 페이로드 충돌을 해결하지
않는다. 브로커 재전달은 같은 목표 상태에서 아무 일도 하지 않는 방식으로 흡수하고,
의미가 다른 종료 명령은 DLT로 드러내는 것이 현재 경계다.

정산 마이그레이션은 실제 지급액·결과 또는 무효 사유와 해결 시각을 bet에 남긴다.
정산 서비스는 지갑 HTTP 호출로 환불을 마친 뒤 `BetVoided`를 아웃박스에 넣는다.
그 사건은 베팅 서비스와 게이트웨이가 소비하고 지갑 서비스는 소비하지 않는다. 따라서
이 리스너는 입금을 호출하지 않으며, 베팅 서비스가 소유한 집계의 종료 사실만
기록한다.
