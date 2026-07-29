# 베팅 접수의 상태와 일관성 경계

접수 한 건은 PostgreSQL, odds-feed Redis, 위험 관리 서비스, 지갑 서비스와 Kafka를
지난다. 이 저장소가 원자적으로 묶을 수 있는 범위는 PostgreSQL 트랜잭션뿐이다.
원격 호출 사이에는 진행 지점을 저장하고, 상대 서비스는 bet ID가 같은 요청을
중복으로 처리하지 않는다는 계약을 둔다.

```text
HTTP + Idempotency-Key
  │
  ├─ 기존 placement_request ── 저장된 BET/REJECTION 재생
  └─ 슬립 정책 + odds Redis 검증
       ├─ 확정 거절 ── REJECTION placement_request
       └─ PENDING bet + BET placement_request
            ├─ 위험 한도 예약
            ├─ 지갑 차감
            ├─ 위험 한도 확정
            └─ ACCEPTED + outbox ── Kafka bet.placed.v1

                 실패 방향
            위험 한도 해제 또는 지갑 환불
                 └─ REJECTED

Kafka bet.settled.v1 / bet.voided.v1 ── ACCEPTED bet의 종료 전이
```

## 시작점과 비동기 실행

`BettingServiceApplication`이 설정 속성 탐색과 스케줄링을 켠다.
`config/BettingInfrastructureConfig`의 UTC `Clock`을 접수, 아웃박스 발행, 복구 작업이
공유한다. 스케줄러는 미발행 아웃박스를 비우고 오래된 PENDING을 재개한다. 정산은
Kafka 리스너 컨테이너가 별도로 받는다. HTTP 요청 스레드가 끝나도 두 스케줄러와
리스너는 저장된 작업을 계속 진행한다.

## PostgreSQL이 소유하는 자료

| 자료 | 역할 | 함께 저장되는 경계 |
|---|---|---|
| `bet`와 `bet_leg` | 슬립 원본, 접수 단계, 외부 호출의 증거, 보상, 정산 결과 | 베팅 집계별 짧은 트랜잭션 |
| `placement_request` | 멱등 키의 소유자, 사용자와 요청 지문, BET/REJECTION 결과 | PENDING 생성 또는 사전 거절 |
| `outbox_event` | 수락된 베팅의 고정된 Avro 페이로드와 발행 상태 | ACCEPTED 전이와 같은 트랜잭션 |

`placement_request.idempotency_key`가 현재 요청 결과를 찾는 기준 자료다.
`BET`이면 bet ID를, `REJECTION`이면 오류 코드와 상세 내용을 가리킨다. V1부터 있던
`bet.idempotency_key` 유일성 제약도 남아 있으며, 서비스는 부분 마이그레이션
자료를 위해 bet 직접 조회를 보조 경로로 유지한다. Redis `IdempotencyCache`는 수락된
bet ID를 24시간 남기는 운영 기록이며, 처리 중 조회하거나 잠금으로 쓰지 않는다.

새 요청은 요청 지문을 저장해 같은 키의 본문 변경을 막는다. V5 이전 bet을 V6
`placement_request`로 옮긴 행은 지문이 `null`이거나 `legacy-` 값일 수 있다.
이 자료는 요청 사용자만 비교하므로 같은 사용자가 본문을 바꿔도 409로 구분하지
못한다.

`bet`은 `@Version`으로 단계 경쟁을 감지한다. `placement_request`의 유일성 제약과
함께 같은 본문의 동시 요청을 한 결과로 모으지만, 원격 API 호출 자체를 잠그지는
않는다. 중복 호출은 위험 관리·지갑 서비스가 bet ID를 기준으로 흡수해야 한다.

## 접수 상태 머신

`BetStatus`가 `PENDING`인 동안 접수 단계는 다음 순서로만 진행한다.

```text
CREATED → RISK_RESERVED → WALLET_CONFIRMED → RISK_COMMITTED
                                                   └─ BetStatus.ACCEPTED
```

`ACCEPTED`는 접수 단계가 아니라 베팅 집계의 상태다. 수락 뒤에도 접수 단계는
`RISK_COMMITTED`로 남는다.

`BetPlacementService`가 다음 외부 행동을 선택하고
`src/main/java/com/sportsbook/betting/placement/BetStore.java`의
`@Transactional` 메서드가 각 진행 지점만 저장한다. HTTP 응답을 기다리는 동안 DB
트랜잭션이나 행 잠금을 잡지 않는다. 위험 한도 예약 만료 시각, 예약 확정 관찰 여부,
지갑 작업 묶음 ID가 “어디까지 성공했는가”의 증거다.

위험 관리·지갑 서비스의 시간 초과, 연결 실패와 해석 불가능한 응답은 현재 bet을
`PENDING`으로 돌려준다. 일정 시간 지난 PENDING은 복구 작업이 같은 상태 머신을
다시 호출한다. `created_at` 기준 최대 100개를 조회할 뿐 행을 점유하지 않아 여러
인스턴스가 같은 bet을 선택할 수 있다.

확정된 실패 뒤에는 정방향 접수 단계로 돌아가지 않는다.

```text
보상 동작: RISK_RELEASE | WALLET_REFUND
보상 상태: REQUIRED → IN_PROGRESS → COMPLETED
                                         └─ BetStatus.REJECTED
```

`REJECTED`도 보상 상태에 포함되지 않는다. 필요한 외부 보상을 완료한 뒤 별도 베팅
집계 전이로 기록한다.

외부 보상 전에 보상 동작과 의도를 저장한다. 한도 해제와 환불 응답이 유실돼도 복구
작업은 같은 보상만 반복한다. 환불은 `refund:{betId}`라는 별도 지갑 멱등 키를
사용한다.

## 검증과 원격 경계

odds Redis의 `market:{event}:{market}`가 정확히 `OPEN`이어야 하며
`odds:{event}:{market}:{selection}`으로 제출 가격 하락을 확인한다. 베팅 서비스는 이
키들을 갱신하지 않는다. 검증 뒤 가격을 고정하지 않으므로 가격 예약과 같은 보장은
없다. Redis 키 부재는 업무 거절로 저장하지만 Redis 접근 예외는
`BetPlacementException`으로 번역하지 않는다. 이 경우 PENDING bet과
`placement_request`를 만들기 전 요청이 실패한다.

공용 `OddsChanged` 사건은 게이트웨이가 실시간 알림에 사용하지만 베팅 서비스는
구독하지 않는다. 화면에 변동을 밀어 주는 경로와 접수 시점에 Redis 최신값으로
슬리피지를 검증하는 경로를 함께 갱신되는 하나의 소비 흐름으로 보면 안 된다.

위험 관리 서비스는 bet ID 기준으로 한도 예약·확정·해제의 생명주기를 제공한다. 지갑
서비스는 bet ID를 사용한 차감과 조회, 별도 환불을 제공한다. 정확한 업무 거절만 확정
결과로 바꾸고 시간 초과와 예기치 않은 HTTP 응답은 불명 상태로 남긴다.

내부 HTTP API는 게이트웨이가 검증한 `X-User-Id`를 신뢰한다. 본문·쿼리의 사용자와
요청 사용자를 다시 비교하고 단건 조회도 소유자로 거른다. 이 서비스 자체가 토큰이나
헤더 서명을 검증하지는 않는다.

## Kafka의 두 방향

수락 방향에서는 `ACCEPTED`와 `BetPlacedRequested` 아웃박스 삽입이 한
트랜잭션이다. 발행기는 브로커 확인 뒤 `published_at`을 기록한다. 브로커 확인과 DB
커밋 사이에 종료되면 중복될 수 있어 최소 한 번 전달 방식이다. 파티션 키는 사용자
ID이며 발행을 마친 행은 자동 삭제하지 않는다. `created_at` 오름차순으로 조회하지만
한 행의 전송이 실패해도 다음 행을 계속 처리한다. 행을 점유하지도 않아 여러 발행기가
같은 묶음을 고를 수 있다. 사용자 ID 파티션 키는 도착한 레코드를 같은 파티션에 모을
뿐, 건너뛴 앞 행의 업무 순서까지 보장하지 않는다.

지갑 사건 계열은 이 서비스의 입력이 아니다. 차감 성공 여부는 동기 HTTP 응답과
작업 ID 조회로 확인하고, 잔액 부족은 HTTP 422 문제 코드로 판정하며, 환불 보상도
지갑 HTTP 명령으로 수행한다. 따라서 `WalletDebitFailed`가 나중에 도착해 보상 흐름을
시작하지 않는다.

정산 방향에서는 `SettlementResultListener`가 `BetSettled`와 `BetVoided`의 Avro
원시 바이트를 도메인 값으로 바꾼다. 같은 목표 종료 상태는 아무 일도 하지 않지만
페이로드가 달라졌는지는 비교하지 않는다. 반대 종료 상태, 알 수 없는 bet, ACCEPTED가
아닌 bet의 첫 정산은 재시도 뒤 DLT 대상이다. 리스너는 전달 자료의 사용자 ID, 경기
ID, 원 지분·환불액을 기존 bet과 대조하지 않는다. SETTLED와 VOIDED는 서로 다른
토픽이라 전체 소비 순서가 없고, 상충하면 먼저 커밋된 종료 종류가 남는다.

`BetVoided`는 정산 서비스가 지갑 환불을 먼저 끝낸 뒤 발행하는 사후 사실이다. 이
서비스는 환불을 다시 실행하지 않고 VOIDED 전이만 소유한다.

```text
bet.placed.v1:  DB 아웃박스 → Kafka, 소비자 멱등성 필요
bet.settled.v1: Kafka → SETTLED
bet.voided.v1:  Kafka → VOIDED
                         실패: 레코드 재시도 3회 → <topic>.DLT
```

스키마 레지스트리는 사용하지 않아 shared-protocol 버전을 배포 단위에서 맞춰야 한다.
DLT 토픽 생성과 재처리도 orchestration·운영 계층의 책임이다.

## 보장하지 않는 것

- PostgreSQL, 위험 관리 서비스, 지갑 서비스, Kafka를 한 원자적 트랜잭션으로 묶지
  않는다.
- `PENDING` 검색은 작업 소유권을 확보하지 않으며 엄격한 선입선출도 아니다.
- 아웃박스 조회도 행을 점유하지 않으며 사용자별 선입선출을 보장하지 않는다.
- UUIDv7 커서는 같은 밀리초 안의 정확한 생성 순서를 보장하지 않는다.
- 위험 관리·지갑 서비스가 bet ID 멱등 기록을 로컬 복구 상태보다 먼저 지우면
  안전한 복구가 깨진다.
- 같은 종류의 정산 재전달에서 페이로드 충돌을 검출하지 않는다.
- 정산 발행기가 bet ID와 검증하지 않는 사용자·경기·금액 필드의 일관성을 지켜야
  한다.
- SETTLED와 VOIDED 토픽 사이에는 종료 상태 결정 순서가 없다.
- 접수 요청과 발행을 마친 아웃박스 행의 보존·정리 정책은 구현돼 있지 않다.

이 한계 안에서 로컬 DB 트랜잭션은 사실을 저장하고, 원격 호출은 재생 가능한 ID를
사용하며, 모호한 결과는 거절로 단정하지 않는 방식으로 경계를 연결한다.
