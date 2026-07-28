# 내구성 있는 멱등 판정

## 거절도 재생 가능한 결과다

같은 멱등 키의 첫 요청이 마켓 종료로 거절된 뒤 마켓이 다시 열렸다고 하자. 재요청 때
검증을 다시 수행하면 같은 키가 성공으로 바뀐다. 멱등성은 성공 객체만 재사용하는
것이 아니라 최초의 확정 판정을 재생해야 한다.

슬립 검증, 마켓 종료와 배당 변동은 Bet aggregate를 만들기 전에 실패할 수 있다.
거절된 Bet을 억지로 만드는 방법은 aggregate의 필수 불변식을 약하게 만든다. 성공
결과만 Redis에 보관하는 방법은 캐시 유실과 만료 뒤 판정이 바뀐다. 그래서
`placement_request`가 멱등 키의 소유권과 두 종류의 결과를 함께 맡는다.

- `BET` 결과는 생성된 bet의 ID를 가리킨다.
- `REJECTION` 결과는 사용자, 요청 fingerprint, 오류 코드와 상세를 보관한다.

`BetPlacementService.place()`는 저장된 결과를 입력 검증보다 먼저 찾는다. 확정 가능한
사전 거절만 별도 행으로 남기고, risk 장애나 wallet timeout처럼 결과를 모르는 실패는
PENDING bet의 복구 경로로 보낸다.

```java
Optional<PlacementRequest> existingRequest = store.findPlacementRequest(key);
if (existingRequest.isPresent()) {
  return replay(existingRequest.get(), command.userId(), fingerprint);
}
```

현재 영속 사전 거절은 `ValidationFailedException`, `MarketClosedException`,
`OddsDriftException`이다. 의존 서비스 장애와 risk·wallet의 업무 거절을 같은 분기로
넣으면, 외부 효과가 시작된 요청을 사전 판정처럼 재생하는 오류가 생긴다.

```java
private static boolean isDurablePreflightRejection(BetPlacementException rejection) {
  return rejection instanceof ValidationFailedException
      || rejection instanceof MarketClosedException
      || rejection instanceof OddsDriftException;
}
```

## 키만 비교하면 부족하다

같은 키에 다른 본문이나 사용자를 허용하면 이전 사용자의 betId가 노출되거나 전혀
다른 베팅이 기존 결과를 가져갈 수 있다. `RequestFingerprint`는 사용자, 슬립 종류,
단위 지분, 통화와 선택지 순서를 정규화해 SHA-256으로 만든다. 소수 배당은
`stripTrailingZeros().toPlainString()`으로 표현해 `2.0`과 `2.00`을 같은 값으로 본다.

```java
canonical
    .append('|')
    .append(selection.eventId())
    .append('|')
    .append(selection.marketId())
    .append('|')
    .append(selection.selectionId())
    .append('|')
    .append(selection.oddsAtSubmission().decimal()
        .stripTrailingZeros().toPlainString());
```

재생 시에는 저장된 사용자와 fingerprint를 모두 비교한다. 다른 사용자가 같은 키를
보내면 betId를 보여 주지 않고 409를 반환하며, 같은 사용자가 본문을 바꿔도 409다.

DB 유일 제약 위반을 곧바로 충돌로 반환하는 것도 흔한 실수다. 동시에 시작한 같은
요청일 수 있으므로 `saveAndFlush()` 충돌 뒤 `placement_request`를 다시 읽어 동일성을
검사하고 저장된 결과로 수렴한다. Redis의 `idempotency:betting:*` 값은 승인된 betId를
24시간 기록할 뿐 읽기나 소유권 판정에 쓰지 않는다. Redis 장애도 접수 결과를 바꾸지
않는다.

## 경쟁 조건을 결과 수로 검증한다

`BetPlacementIntegrationTest.samePayloadConcurrencyConverges`는 20개 호출자를
`CountDownLatch` 뒤에서 동시에 시작한다. 반환값만 비교하지 않고 다음 수를 함께
검사한다.

- 요청 소유권 행 1
- bet 1
- wallet debit 1
- accepted outbox 1

`validationRejectionIsDurable`, `oddsDriftRejectionSurvivesConditionChange`,
`marketClosedRejectionSurvivesConditionChange`는 최초 조건이 달라진 뒤에도 저장한
거절을 재생하는지 확인한다. 사용자 교차 사용과 본문 변경도 외부 호출 전에 막는다.

```sh
./mvnw -Dtest=BetPlacementIntegrationTest test
```

HTTP 응답만 비교하면 두 외부 효과가 생긴 뒤 우연히 같은 betId를 반환한 오류를 놓칠
수 있다. 반대로 행 개수만 보면 서로 다른 호출자가 이전 결과를 읽는 정보 노출을
놓친다.

## 남는 제한

fingerprint는 비밀 서명이 아니라 충돌 감지용 해시다. 원문을 인증하지 않으며
이론적인 해시 충돌도 배제하지 않는다. 선택지 순서는 fingerprint의 일부이므로 같은
선택지 집합을 다른 순서로 보내면 다른 본문으로 본다. `placement_request`의 보존
기간과 정리 작업도 현재 서비스에는 없다. 멱등 키를 얼마나 오래 재생할지는 데이터
보존 정책과 함께 정해야 한다.
