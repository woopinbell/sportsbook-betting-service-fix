# 내부 요청의 사용자도 다시 확인하기

첫 베팅 API는 게이트웨이 뒤의 내부 엔드포인트였지만 사용자 ID의 출처를 구분하지
않았다. POST 본문에 다른 사용자를 넣을 수 있었고, 목록 쿼리도 임의의 사용자 ID를
받았다. bet UUID를 알면 단건 조회에는 사용자 조건조차 없었다.

수정 뒤 `src/main/java/com/sportsbook/betting/api/BetController.java`는 게이트웨이가
검증해 전달한 `X-User-Id`를 요청 사용자로 삼는다. `@ModelAttribute` 메서드가 본문
바인딩 전에 헤더의 존재와 UUID 형식을 먼저 확인한다.

```java
@ModelAttribute
void requireActorBeforeBinding(
    @RequestHeader(value = ACTOR_HEADER, required = false) String actorHeader) {
  requireActor(actorHeader);
}
```

POST 본문과 목록 쿼리의 사용자 ID는 요청 사용자와 같아야 한다. 명령의 사용자도
본문 값을 다시 복사하지 않고 검증된 요청 사용자를 넣는다.

```java
UUID actor = requireActor(actorHeader);
requireSameUser(actor, request.userId());
Bet bet = placement.place(toCommand(actor, request, idempotencyKey));
```

`BetQueryService.byId(actorId, betId)`는 ID로 가져온 bet의 소유자가 요청 사용자와 같은
경우만 반환한다. 다른 사용자의 bet도 존재하지 않는 ID와 같은 404가 되어 존재
여부를 노출하지 않는다. 목록 쿼리도 전달받은 사용자 ID 대신 검증된 요청 사용자로
저장소를 조회한다.

멱등 키도 사용자 경계를 넘어 재사용되면 안 된다. 당시에는 기존 bet의 사용자와 새
명령의 사용자를 비교했고, 뒤의 `placement_request` 도입 뒤에는 사용자와 요청
지문을 함께 비교한다. 다른 사용자가 키만 알아도 기존 bet ID를 재생받을 수 없다.

## 헤더는 인증 결과이지 인증 수단이 아니다

서비스가 JWT나 헤더 서명을 검증하는 것은 아니다. 게이트웨이가 인증을 끝내고 외부에서
들어온 `X-User-Id`를 제거한 뒤 자신의 값을 넣는 배포 계약이다. 이 엔드포인트를
인터넷에 직접 노출하거나 클라이언트가 헤더를 덮을 수 있게 두면 UUID 형식 검증은 아무
보호가 되지 않는다.

요청 사용자가 일치하지 않으면 `ForbiddenException`을 거쳐 403 RFC 7807로 나간다.
헤더가 없거나 표준 형식의 UUID로 해석되지 않는 경우도 같은 경계에서 막힌다.

접수 도메인은 그대로 두고 HTTP에서 “누구의 명령인가”를 먼저 고정했다. 이후 복구
가능한 접수가 PENDING을 반환하면서 외부용 `Location`과 202 응답이 추가됐지만,
명령과 조회의 요청 사용자 원칙은 그대로 유지됐다.
