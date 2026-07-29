# 재개 가능한 접수와 보상

## 네트워크 호출을 DB 트랜잭션에 넣지 않는다

리스크 예약, 지갑 차감과 리스크 확정은 서로 다른 서비스에서 일어난다. 하나의 DB
트랜잭션을 연 채 이 요청들을 호출하면 원격 응답을 기다리는 동안 연결과 잠금을
점유하고, 롤백으로 이미 끝난 외부 효과를 되돌릴 수도 없다. 반대로 단계를
메모리에만 두면 프로세스가 종료된 뒤 어디서 재개할지 알 수 없다.

`src/main/java/com/sportsbook/betting/placement/BetStore.java`는 짧은
`@Transactional` 메서드로 각 진행 지점만 저장하고 `BetPlacementService`가
트랜잭션 사이에서 외부 서비스를 호출한다. 현재 전이는 다음과 같다.

```text
BetStatus.PENDING
  └─ placementPhase: CREATED → RISK_RESERVED → WALLET_CONFIRMED → RISK_COMMITTED
                                                                       └─ BetStatus.ACCEPTED

외부 효과를 되돌려야 하는 확정 실패
  ├─ compensationAction: RISK_RELEASE | WALLET_REFUND
  └─ compensationState:  REQUIRED → IN_PROGRESS → COMPLETED
                                                      └─ BetStatus.REJECTED
```

`ACCEPTED`는 접수 단계가 아니며 수락 뒤 `placementPhase`는 `RISK_COMMITTED`로
남는다. `REJECTED`도 보상 상태가 아니라 보상 완료 뒤 기록하는 베팅 집계
상태다.

자기 클래스의 `@Transactional` 메서드를 직접 호출하면 Spring 프록시를 우회할 수
있다. 저장 경계를 별도 `BetStore` 빈으로 둔 이유다.

## 제한 시간 초과를 실패 여부로 해석하지 않는다

HTTP 읽기 제한 시간 초과는 요청이 상대 서비스에 도착했지만 응답만 유실된 경우에도
발생한다. `DependencyUnavailableException`은 현재 단계를 거절로 바꾸지 않고
PENDING을 반환한다. 복구 작업은 저장된 단계에서 재개한다.

첫 리스크 검사는 이 작업에서 bet ID 기준의 원자적 예약 생명주기로 바뀌었다.

```text
POST   /internal/v1/risk/reservations
PUT    /internal/v1/risk/reservations/{betId}/commit
DELETE /internal/v1/risk/reservations/{betId}
```

예약의 `approved=false`는 확정된 `RiskLimitException`이고 `RESERVED`와
`COMMITTED`는 재생 가능한 정상 응답이다. 확정은 정확한 404만 만료·부재를 뜻하는
false로 돌려준다. 해제의 4xx는 이미 확정됐는지 단정할 수 없어 모두 의존 서비스
결과 불명으로 남긴다.

`WalletClient.findDebit()`은 조회 API로 먼저 확인하고 404일 때만 같은 bet ID로
차감을 재시도하게 한다. 조회 요청의 제한 시간 초과와 5xx는 “차감 내역 없음”으로
보지 않는다. 차감·조회·환불 응답 본문에는
`operationGroupId`가 반드시 있어야 로컬 진행 지점의 증거가 된다.

```java
if (recovery) {
  operationId =
      walletClient
          .findDebit(bet.betId())
          .map(WalletOperationResponse::operationGroupId)
          .orElse(null);
}
if (operationId == null) {
  operationId = walletClient.debit(bet.betId(), bet.userId(), totalStake(bet));
}
store.confirmWallet(bet.betId(), operationId, clock.instant());
```

조회 실패를 404로 취급하면 응답을 잃은 차감을 다시 실행할 수 있다. 404와 연결
실패·제한 시간 초과는 반드시 다른 결과여야 한다.

## 보상 방향은 되돌릴 수 없다

잔액 부족을 확인한 뒤에는 리스크 예약 해제만 해야 한다. 지갑 차감 뒤 리스크 용량을
다시 얻지 못했다면 환불만 해야 한다. 이 결정을 메모리에만 두면 프로세스 종료 뒤
차감이나 수락으로 되돌아갈 수 있다.

`compensationAction`과 `compensationState`를 bet에 저장해 정방향 단계와 독립된
분기를 만든다. `REQUIRED`를 저장한 뒤 `IN_PROGRESS`로 옮기고 외부 작업을 호출하며,
증거가 남은 뒤 `COMPLETED`로 만든 다음 `BetStatus.REJECTED`를 별도 전이로 기록한다.

```java
case REQUIRED -> store.beginCompensation(betId, clock.instant());
case IN_PROGRESS -> performCompensation(current);
case COMPLETED -> {
  return finishCompensatedRejection(current, surfaceRejection);
}
```

환불 키는 `refund:<betId>`처럼 차감과 다른 멱등 영역을 사용한다. 같은 betId만 쓰면
지갑이 원래 차감 요청과 환불 요청을 같은 연산으로 오인할 수 있다. 보상 의도를
저장하기 전에 DELETE나 환불을 보내는 것도 피해야 한다. 응답을 잃었을 때 정방향
경로와 보상 경로 중 어느 쪽을 재생해야 하는지 알 수 없기 때문이다.

## 한 번의 호출이 진행할 단계 수를 제한한다

한 HTTP 호출에서 진행 지점을 계속 전진시키되 `MAX_ADVANCE_STEPS = 8` 상한을 둔다.
각 반복은 한 단계 진행이나 낙관적 잠금 충돌 한 번을 소비한다. 예상하지 못한 전이는
즉시 예외로 끝나며 이 상한으로 복구하지 않는다. 여덟 번 안에 끝나지 않은 PENDING은
다음 복구 작업이 이어받는다.

`BetReconciliationJob`은 기본 30초보다 오래된 PENDING을 100개씩 읽고, 기본 10초
간격으로 재개한다. 여러 인스턴스가 같은 bet을 선택할 수 있으므로 진행 지점의
낙관적 잠금과 외부 API의 멱등성이 모두 필요하다. 조회 배치는 작업 소유권을
확보하지 않는다.

## HTTP는 복구 중이라는 사실을 숨기지 않는다

외부 응답이 모호해 `advance()`가 PENDING을 돌려주면 컨트롤러는 201 성공처럼
보이지 않고 202를 반환한다. `Location`도 서비스 내부 경로가 아니라 클라이언트가
게이트웨이를 통해 따라갈 공개 경로다.

```java
URI location = URI.create("/api/v1/bets/" + bet.betId());
if (bet.status() == BetStatus.PENDING) {
  return ResponseEntity.accepted().location(location).body(BetResponse.from(bet));
}
return ResponseEntity.created(location).body(BetResponse.from(bet));
```

202는 다른 멱등 키로 새 bet을 만들라는 뜻이 아니다. 같은 요청 결과가 PostgreSQL에
남아 있으며 이후 복구 작업이 진행한다는 응답이다. 클라이언트는 Location 조회나
같은 멱등 키 재요청으로 저장된 상태를 확인해야 한다.

## 실패 지점에서 남긴 증거

`BetReconciliationIntegrationTest`는 실제 PostgreSQL·Redis와 WireMock을 사용해
다음 경계를 고정한다.

- 응답을 잃은 차감은 조회로 찾고 다시 차감하지 않는다.
- 잔액 부족 뒤 예약 해제가 실패하면 잔액이 회복돼도 차감으로 돌아가지 않는다.
- 차감 뒤 만료된 리스크 예약을 다시 얻지 못하면 한 번만 환불한다.
- 환불 응답을 잃어도 다음 실행은 환불만 재생하며 ACCEPTED로 가지 않는다.

테스트는 두 스케줄러 인스턴스가 같은 PENDING 배치를 장시간 경쟁하는 운영 상황이나
보상 API의 멱등 보존 기간까지 증명하지 않는다. 이 계약이 깨지면 로컬
진행 지점만으로 중복 외부 효과를 막을 수 없다.
