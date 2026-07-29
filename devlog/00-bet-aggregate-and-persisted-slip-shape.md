# 슬립을 하나의 베팅 집계로 저장하기

베팅 한 건은 상태 한 칸과 금액만으로 설명되지 않는다. 사용자가 본 선택지와 배당,
Single·Multiple·System 구조, 접수 지분과 최대 지급액이 함께 고정돼야 이후 정산과
감사가 같은 입력을 본다. `src/main/java/com/sportsbook/betting/domain/Bet.java`를
베팅 집계의 루트로 두고 `BetLeg`가 입력 순서를 가진 자식이 되도록 설계한 이유다.

`Bet.pending()`은 ID, 사용자, 표시용 참조 번호, 슬립 종류, 지분, 최대 지급액,
멱등 키와 레그를 한 번에 받는다. 생성 시 상태는 `PENDING`이며 통화 일치와 슬립
모양을 베팅 집계가 다시 확인한다. Single은 레그 하나, Multiple은 둘 이상,
System의 N은 레그 수와 같아야 한다. 정책 검증을 우회해도 저장할 수 없는 최소
불변식이다.

JPA에서는 공용 프로토콜의 값 객체 `BetSlipType`을 그대로 저장하지 않는다. `SlipKind`
문자열과 System일 때만 채우는 `system_min_wins`, `system_total_selections`로 평탄화하고
읽을 때 다시 `BetSlipType.System`을 만든다. V1 마이그레이션도 같은 규칙을 CHECK로
고정했다.

```sql
CONSTRAINT bet_system_params CHECK (
    (slip_type =  'SYSTEM' AND system_min_wins IS NOT NULL AND system_total_selections IS NOT NULL)
 OR (slip_type <> 'SYSTEM' AND system_min_wins IS     NULL AND system_total_selections IS     NULL)
)
```

`Money`도 서비스 밖의 값 객체에 JPA 애너테이션을 붙이지 않고 `EmbeddedMoney`로
변환한다. 공용 도메인 프로토콜과 이 서비스의 테이블 설계를 분리하면서 금액과 통화를
두 임베디드 열 묶음으로 저장한다. DB는 지분이 양수인지, 최대 지급액이 음수가 아닌지,
두 통화가 같은지도 확인한다.

## 레그 순서는 데이터다

`BetLeg`는 `leg_index`를 가지며 베팅 집계의 컬렉션은 그 값으로 정렬된다.
`UNIQUE (bet_id, leg_index)`가 중복 위치를 막는다. 선택지 집합이 같아도 제출 순서가
요청 지문과 System 조합의 입력에 쓰이므로, 단순한 무순서 관계로 취급하지 않았다.
각 레그에는 경기·마켓·선택지 ID와 제출 당시 소수점 네 자리 배당을 남긴다.

## 식별자마다 목적이 다르다

`UuidV7.generate()`는 타임스탬프 비트를 앞에 둔 UUID를 만들어 사용자별
`bet_id DESC` 키셋 조회에 쓴다. 같은 밀리초 안에서는 무작위 비트 순서라 완전한
생성 순서를 보장하지는 않는다.

`BetReferenceGenerator`는 UTC 날짜와 36진수 난수로
`B-YYYY-MM-DD-XXXXXXXX` 형태를 만든다. 사용자에게 보여 주기 쉬운 식별자일 뿐
충돌을 수학적으로 없애지 않는다. `uk_bet_reference`가 최종 보장이다.
`idempotency_key`도 V1부터 고유했으며, 뒤의 멱등 보강에서 별도 요청 판정 테이블의
소유권으로 확장됐다.

처음 잡은 베팅 집계의 모양은 뒤에 접수 단계, 보상 상태, 정산 결과가 추가돼도
선택지와 제출 가격이라는 원본을 바꾸지 않았다. 접수 조정 흐름은 여러 번
진화했지만 “사용자가 무엇을 제출했는가”는 이 베팅 집계에서 계속 읽는다.
