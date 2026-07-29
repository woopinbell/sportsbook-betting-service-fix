# 접수 명령과 커서 조회를 묶은 HTTP API

도메인 로직이 완성돼도 클라이언트는 `BetSlipType`이나 `Odds`를 직접 만들 수 없다.
`src/main/java/com/sportsbook/betting/api/BetController.java`의 첫 역할은
`/internal/v1/bets` JSON을 도메인 명령으로 바꾸고 결과를 조회 가능한 주소와 함께
돌려주는 일이었다.

`PlaceBetRequest`는 사용자 ID, 슬립 종류, 선택지 목록과 `Money`를 받는다. 각 선택지의
소수 문자열은 컨트롤러에서 `Odds.ofDecimal()`을 통과하고, SINGLE·MULTIPLE·SYSTEM
문자열은 `sealed` 타입인 `BetSlipType`으로 바뀐다. SYSTEM이면 K와 N이 모두 있어야
한다.

```java
case "SYSTEM" ->
    new BetSlipType.System(
        required(slipType.minWins(), "minWins"),
        required(slipType.totalSelections(), "totalSelections"));
```

`Idempotency-Key`는 필수 헤더이며 `IdempotencyKey.of()`가 길이와 형식을 확인한다.
HTTP DTO가 서비스 메서드 곳곳으로 퍼지지 않고 `PlaceBetCommand` 하나가 접수 경계를
지난다.

최초 API 구현 커밋 `d57d69f`는 동기 수락을 전제로 항상 201을 반환하고
`Location: /internal/v1/bets/{betId}`를 만들었다. 이 주소는 서비스 내부 호출에는
맞지만 외부 클라이언트가 게이트웨이를 통해 따라가기에는 틀린 경로였다. 의존 서비스
결과를 확정할 수 없어 PENDING을 반환하게 된 뒤에는 201과 202의 구분도 필요해졌다.

## UUIDv7 하나를 정렬 키와 커서로 썼다

사용자 내역은 `(user_id, bet_id DESC)` 인덱스와 UUIDv7 bet ID를 이용한 키셋
페이지네이션이다. 요청 제한값이 없거나 0 이하면 20, 최대 100이며 `limit + 1`개를
읽어 개수 쿼리 없이 다음 페이지가 있는지 정한다.

```java
List<Bet> rows =
    cursor == null
        ? bets.findByUserIdOrderByBetIdDesc(actorId, probe)
        : bets.findByUserIdAndBetIdLessThanOrderByBetIdDesc(actorId, cursor, probe);
```

다음 커서는 반환한 페이지의 마지막 bet ID다. 중간 행이 삭제돼도 “이 ID보다 작은
값”으로 계속 읽을 수 있다. UUIDv7 타임스탬프 접두사 덕분에 대체로 최신 생성 순서와
맞지만 같은 밀리초의 무작위 비트 사이에는 엄격한 생성 순서를 보장하지 않는다.

저장소 쿼리는 선택지 행을 함께 가져온다. 읽기 트랜잭션이 끝난 뒤 컨트롤러가
`BetResponse`를 만들 때 지연 로딩 컬렉션을 다시 읽는 오류를 피한 선택이다.

## 도메인 오류를 한 응답 형식으로 바꿨다

`BetExceptionHandler`는 `BetPlacementException`이 가진 공용 `ErrorCode`를 RFC 7807
`application/problem+json`으로 바꾼다. 찾을 수 없는 bet은 `BET_NOT_FOUND`, 바인딩과
요청 검증 오류는 `VALIDATION_FAILED`, 예상하지 못한 예외는 `INTERNAL_ERROR`다.
MDC에 추적 ID가 있으면 상관관계 ID로 함께 보낸다.

이 첫 API는 본문과 쿼리에 들어온 사용자 ID를 그대로 신뢰했고, bet ID 단건 조회도
소유자를 확인하지 않았다. 내부 경로라는 이름만으로는 사용자 경계가 되지 않았다.
정산 소비자를 붙인 뒤 이 문제가 별도 보안 수정으로 이어졌다.
