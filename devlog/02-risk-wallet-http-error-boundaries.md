# 업무 거절과 통신 장애를 갈라놓은 HTTP 클라이언트

접수 경로에 리스크와 지갑 서비스를 처음 붙일 때 가장 먼저 정한 것은 API보다 오류의
뜻이었다. 리스크 한도 초과와 지갑 잔액 부족은 확정된 업무 거절이다. 연결 리셋,
제한 시간 초과, 5xx와 읽을 수 없는 본문은 상대가 처리했는지 알 수 없는 장애다.
둘을 같은 예외로 만들면 회로 차단기와 접수 상태가 잘못 반응한다.

`src/main/java/com/sportsbook/betting/client/ClientConfig.java`는 리스크와 지갑용
`RestClient`를 따로 만들고 기본 연결·읽기 제한 시간을 각각 200ms·500ms로 뒀다.
짧은 제한 시간은 접수 스레드를 빨리 돌려주지만 “상대가 실행하지 않았다”는 증거는
아니다. 아래의 첫 클라이언트 코드는 구현 커밋 `c47dedb`에서 확인할 수 있다.

## 리스크의 200 응답 안에 두 판정이 있었다

첫 `RiskClient`는 `/internal/v1/risk/check`에 사용자, bet ID, 총 지분과 선택지
목록을 보냈다. 리스크 서비스는 승인과 거절을 모두 200 본문으로 돌려줬으므로 HTTP 성공 여부가
업무 승인 여부가 아니었다.

```java
if (response == null || !response.approved()) {
  throw new RiskLimitException(response == null ? null : response.rejectionReason());
}
```

`approved=false`는 `RiskLimitException`, 전송 실패는
`DependencyUnavailableException`으로 나눴다. 회로 차단기 폴백도 이미 분류된
`BetPlacementException`은 그대로 던지고 열린 회로만 의존 서비스 장애로 바꿨다.
정상적인 한도 거절이 연속 장애로 집계되지 않게 한 것이다.

빈 본문까지 리스크 거절로 취급한 첫 구현에는 모호함이 남았다. 더 큰 문제는
`check`가 한도를 소비하는 예약이 아니라는 점이다. 한도 확인 뒤 지갑 차감까지 다른
요청이 끼어들 수 있었다. 이 API는 뒤의 복구 작업에서 bet ID 기반
예약·확정·해제 생명주기로 교체됐다.

## 지갑은 문제 코드를 읽어야 했다

지갑 차감은 bet ID를 `Idempotency-Key`로 보냈다. 같은 요청을 재전송해도 동일한
차감으로 수렴시키기 위해서다. 4xx 중에서는 RFC 7807 본문의
`WALLET_INSUFFICIENT_BALANCE`만 `InsufficientBalanceException`으로 바꿨다.

```java
WalletProblem problem = objectMapper.readValue(response.getBody(), WalletProblem.class);
if (INSUFFICIENT_BALANCE_CODE.equals(problem.code())) {
  return new InsufficientBalanceException(problem.detail());
}
return new DependencyUnavailableException(
    "unexpected wallet client error: code=" + problem.code());
```

다른 4xx와 해석할 수 없는 본문은 의존 서비스 장애다. 상태가 422라는 이유만으로
잔액 부족이라 단정하면 새 오류 코드나 잘못된 응답을 확정 거절로 저장할 수
있다.

환불은 차감과 다른 `refund:{betId}` 키를 쓴다. 같은 bet ID 문자열만 쓰면 지갑이
반대 방향의 입금을 원래 차감 재시도로 오인할 수 있다. 입금 출처는 잠긴
사용자 지분을 돌리는 `USER_LOCKED`다.

처음 클라이언트는 차감의 2xx 본문이 비거나 작업 ID가 없어도 null을 반환할 수
있었고, 적용 여부를 조회하는 API도 없었다. 동일 명령 재전송만으로 복구하려면
지갑이 멱등 키를 충분히 오래 보존해야 한다. 이후에는 작업 ID를 필수 증거로
만들고 정확한 404와 통신 장애를 나누는 조회가 추가됐다.

여기까지 만든 것은 분산 트랜잭션이 아니라 오류 번역 규칙과 재시도용 ID였다.
리스크 예약 상태, 지갑 적용 증거와 로컬 진행 지점 저장은 최초 접수의 복구 구간을
확인한 뒤 이어졌다.
