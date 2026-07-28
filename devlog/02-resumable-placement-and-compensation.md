# 재개 가능한 접수와 보상

## 네트워크 호출을 DB 트랜잭션에 넣지 않는다

risk 예약, wallet 차감과 risk 확정은 서로 다른 서비스에서 일어난다. 하나의 DB
트랜잭션을 연 채 이 요청들을 호출하면 원격 응답을 기다리는 동안 연결과 잠금을
점유하고, rollback으로 이미 끝난 외부 효과를 되돌릴 수도 없다. 반대로 단계를
메모리에만 두면 프로세스가 종료된 뒤 어디서 재개할지 알 수 없다.

`BetStore`는 짧은 `@Transactional` 메서드로 각 checkpoint만 저장하고
`BetPlacementService`가 트랜잭션 사이에서 외부 서비스를 호출한다. 현재 전이는
다음과 같다.

```text
CREATED → RISK_RESERVED → WALLET_CONFIRMED → RISK_COMMITTED → ACCEPTED
             │                   │
             └─ RISK_RELEASE     └─ WALLET_REFUND
                       └──────────────→ REJECTED
```

자기 클래스의 `@Transactional` 메서드를 직접 호출하면 Spring proxy를 우회할 수
있다. 저장 경계를 별도 `BetStore` bean으로 둔 이유다.

## timeout을 실패 여부로 해석하지 않는다

HTTP read timeout은 요청이 상대 서비스에 도착했지만 응답만 유실된 경우에도
발생한다. `DependencyUnavailableException`은 현재 단계를 거절로 바꾸지 않고
PENDING을 반환한다. 복구 작업은 저장된 phase에서 재개한다.

wallet debit은 조회 API로 먼저 확인하고 404일 때만 같은 betId로 재시도한다. risk
commit도 같은 betId의 예약 상태를 확인·확정한다.

```java
if (recovery) {
  operationId =
      walletClient.findDebit(bet.betId())
          .map(WalletOperationResponse::operationGroupId)
          .orElse(null);
}
if (operationId == null) {
  operationId = walletClient.debit(bet.betId(), bet.userId(), totalStake(bet));
}
store.confirmWallet(bet.betId(), operationId, clock.instant());
```

조회 실패를 404로 취급하면 응답을 잃은 차감을 다시 실행할 수 있다. 404와 연결
실패·timeout은 반드시 다른 결과여야 한다.

## 보상 방향은 되돌릴 수 없다

잔액 부족을 확인한 뒤에는 risk 예약 해제만 해야 한다. wallet 차감 뒤 risk 용량을
다시 얻지 못했다면 환불만 해야 한다. 이 결정을 메모리에만 두면 프로세스 종료 뒤
debit이나 accept로 되돌아갈 수 있다.

`compensationAction`과 `compensationState`를 bet에 저장해 forward phase와 독립된
분기를 만든다. `REQUIRED`를 저장한 뒤 `IN_PROGRESS`로 옮기고 외부 작업을 호출하며,
증거가 남은 뒤 `COMPLETED`와 최종 REJECTED를 기록한다.

```java
case REQUIRED -> store.beginCompensation(betId, clock.instant());
case IN_PROGRESS -> performCompensation(current);
case COMPLETED -> {
  return finishCompensatedRejection(current, surfaceRejection);
}
```

환불 키는 `refund:<betId>`처럼 debit과 다른 멱등 영역을 사용한다. 같은 betId만 쓰면
wallet이 원래 debit 요청과 refund 요청을 같은 연산으로 오인할 수 있다. 보상 의도를
저장하기 전에 DELETE나 refund를 보내는 것도 피해야 한다. 응답을 잃었을 때 forward
경로와 보상 경로 중 어느 쪽을 재생해야 하는지 알 수 없기 때문이다.

## 단계 수 상한은 교착 방지가 아니다

한 HTTP 호출에서 상태를 계속 전진시키되 `MAX_ADVANCE_STEPS = 8` 상한을 둔다.
낙관적 잠금 충돌이나 예상치 못한 전이로 루프가 끝나지 않는 일을 막고, 상한에 도달한
PENDING은 다음 reconciliation이 이어받는다. 상한을 높이는 것으로 외부 장애를
해결하려 해서는 안 된다.

`BetReconciliationJob`은 기본 30초보다 오래된 PENDING을 100개씩 읽고, 기본 10초
간격으로 재개한다. 여러 인스턴스가 같은 bet을 선택할 수 있으므로 checkpoint의
낙관적 잠금과 외부 API의 멱등성이 모두 필요하다. 조회 batch 자체를 작업 소유권
claim으로 오해하면 안 된다.

## 실패 지점 검증

`BetReconciliationIntegrationTest`는 실제 PostgreSQL·Redis와 WireMock을 사용해
다음 경계를 고정한다.

- 응답을 잃은 debit은 조회로 찾고 다시 차감하지 않는다.
- 잔액 부족 뒤 release가 실패하면 잔액이 회복돼도 debit으로 돌아가지 않는다.
- debit 뒤 만료된 risk 예약을 다시 얻지 못하면 한 번만 환불한다.
- refund 응답을 잃어도 다음 실행은 환불만 재생하며 ACCEPTED로 가지 않는다.

```sh
./mvnw -Dtest=BetReconciliationIntegrationTest test
```

테스트는 두 스케줄러 인스턴스가 같은 PENDING batch를 장시간 경쟁하는 운영 상황이나
보상 API의 멱등 보존 기간까지 증명하지 않는다. 이 계약이 깨지면 로컬 checkpoint만
으로 중복 외부 효과를 막을 수 없다.
