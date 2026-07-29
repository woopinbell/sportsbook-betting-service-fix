# 외부 효과 전에 슬립을 확정하는 검증

위험 한도 예약이나 지갑 차감 뒤에 잘못된 슬립을 발견하면 거절만으로 끝나지 않고
보상이 필요하다. 그래서 구조·지분·실시간 가격 검증은 외부 HTTP보다 먼저 끝낸다. 현재는
사전 거절도 멱등 결과로 저장하지만, 검증 규칙 자체는
`src/main/java/com/sportsbook/betting/validation`에 별도 정책으로 남아 있다.

`BetSlipValidator`는 비어 있는 슬립과 설정된 최대 선택지 수를 먼저 검사한다.
Multiple과 System에서는 같은 마켓과 같은 경기를 허용하지 않는다.

```java
if (!markets.add(leg.marketId())) {
  throw new ValidationFailedException(
      "L1 same market not allowed in a multi: market " + leg.marketId());
}
if (!events.add(leg.eventId())) {
  throw new ValidationFailedException(
      "L2 same event not allowed in a multi: event " + leg.eventId());
}
```

같은 마켓이면 경기도 같으므로 마켓 검사를 먼저 해 더 구체적인 원인을 남긴다.
모든 제출 배당의 곱은 `max-total-odds`를 넘지 않아야 하고, 지분은 통화별 최소·최대
목록 안에 있어야 한다. 지원하지 않는 통화도 검증 실패다.

베팅 집계가 확인하는 구조와 이 검증기가 확인하는 정책은 겹쳐 보이지만 역할이
다르다. 베팅 집계는 어떤 호출 경로에서도 깨져서는 안 되는 모양을 지키고, 검증기는
운영 설정으로 바뀔 수 있는 선택 수·상관 선택지·금액 한도를 적용한다.

## 사용자가 본 가격과 현재 가격을 비교한다

`OddsSlippageChecker`는 odds-feed가 소유한 Redis 키를 읽기만 한다.

```text
market:{eventId}:{marketId}
odds:{eventId}:{marketId}:{selectionId}
```

마켓 값이 정확히 `OPEN`이 아니면 닫힌 것으로 본다. 키가 없거나 모르는 상태 문자열이
와도 열렸다고 추측하지 않는다. 가격 키가 없을 때도 선택지가 더는 거래되지 않는
것으로 판단한다.

배당이 사용자가 본 값보다 좋아진 경우는 항상 통과시킨다. 나빠졌을 때만 설정된
배당 하락 허용치를 적용하며 나눗셈 반올림을 피하려고 교차 곱한다.

```java
BigDecimal currentScaled = current.multiply(HUNDRED);
BigDecimal floor = submitted.multiply(toleranceFactor);
if (currentScaled.compareTo(floor) < 0) {
  throw new OddsDriftException(
      "Odds drifted beyond tolerance: submitted "
          + submitted.toPlainString()
          + ", current "
          + current.toPlainString()
          + ", tolerance "
          + policy.slippageTolerancePercent().toPlainString()
          + "%");
}
```

허용치가 3%라면 `current * 100 >= submitted * 97`이 승인 조건이다. 제출 2.00에서
현재 1.94는 경계 안이고 그보다 낮으면 거절된다. odds-feed가 Kafka로 마지막 발행한
값이 아니라 Redis의 최신 조회값을 쓰므로 임계값 아래의 가격 변화도 접수 판단에
반영된다.

공용 프로토콜의 `OddsChanged` 설명에는 베팅 서비스가 변동을 소비한다고 적혀 있지만
이 저장소에는 해당 리스너가 없다. 게이트웨이가 이 사건으로 클라이언트에 변경을
알리는 경로와, 베팅 서비스가 Redis를 다시 읽어 접수를 판정하는 경로는 서로 다른
흐름이다.

## 계산값과 차감값을 같은 규칙에서 만든다

검증이 끝난 각 선택지의 제출 배당은 `SystemBetCalculator`에서 라인별 조합으로
펼쳐진다. `maxPayout()`은 모든 라인이 이겼을 때의 합, `totalStake()`는 실제 지갑
서비스에 요청할 금액이다. System 지분을 슬립 전체 지분으로 잘못 해석하면
C(N,K)배만큼 위험 한도와 지갑 금액이 어긋난다.

```java
public Money totalStake(BetSlipType type, Money unitStake, int legCount) {
  return unitStake.multiply(lineCount(type, legCount));
}
```

Single과 Multiple은 라인 하나, System은 `C(N,K)`개다. 선택지 최대 15라는 정책 아래
가장 많은 `C(15,7)=6435` 조합을 메모리에서 펼치는 비용을 받아들였다. 최대 지급액은
각 라인의 배당 곱을 더한 뒤 단위 지분을 곱하고 통화 최소 단위에 맞춰 내림한다.

위험 예약과 지갑 차감 HTTP 요청에는 이 전체 지분을 보낸다. 반면
`BetPlacedRequested.stake`에는 라인 하나의 단위 지분을 담는다. 정산 서비스가
System 라인별 지급액을 계산하려면 단위 지분이 필요하기 때문이다. 현재 위험 서비스의
사건 소비 경로는 `systemMinWins`와 `systemTotalSelections`로 전체 지분을 다시
계산하지 않는다. 예약 기록이 없는 사건을 처음 처리하면 레거시 한도 카운터가 실제
노출액보다 작아지고, 예약 기록이 있더라도 패턴 이력에는 단위 지분이 들어가 다음 전체
지분과의 중앙값 비교 기준이 달라진다.

검증은 가격 스냅샷을 고정하지 않는다. Redis를 읽은 뒤 위험 한도 예약과 지갑 차감이
끝날 때까지 배당이 바뀔 수 있다. 계약은 검증 순간의 허용 여부를 저장하는 것이지
가격 고정을 제공하지 않는다. 더 강한 보장이 필요하면 odds-feed와 원자적인 가격
예약 프로토콜을 별도로 마련해야 한다.

잘못된 구조, 마켓 종료, 허용치를 넘은 가격 하락은 재시도해도 최초 판정을 그대로
돌려줄 수 있는 확정 거절이다. 나중의 `placement_request`가 이 거절들을 영속화한다.
다만 Redis 접근 예외는 이 업무 예외로 번역되지 않는다. `place()`가 사전 거절을 저장하는
구간에도 들어가지 않아 bet과 요청 판정 행이 생기기 전에 호출이 실패한다.
