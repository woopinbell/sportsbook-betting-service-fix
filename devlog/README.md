# 개발 기록

슬립 집계와 정책 검증을 만든 뒤 위험 관리·지갑 서비스와 Kafka를 연결했다. 첫 직선형
접수에서는 HTTP 응답 유실 뒤 다음 행동을 결정할 증거가 부족했고, 이후 작업에서
멱등 판정과 외부 단계, 보상 의도를 PostgreSQL에 남기는 상태 머신으로 보강했다.

1. [슬립을 하나의 베팅 집계로 저장하기](00-bet-aggregate-and-persisted-slip-shape.md)
2. [외부 효과 전에 슬립을 확정하는 검증](01-validation-live-odds-and-system-lines.md)
3. [위험 관리와 지갑 응답을 업무 결과와 불명 상태로 나누기](02-risk-wallet-http-error-boundaries.md)
4. [수락과 발행을 분리하는 아웃박스 행](03-transactional-outbox-and-at-least-once-publish.md)
5. [첫 접수와 아웃박스 발행에서 드러난 재시도 구간](04-first-placement-and-reconciliation-gap.md)
6. [접수 명령과 커서 조회를 묶은 HTTP API](05-request-mapping-and-uuid-cursor.md)
7. [정산 재전달과 상충하는 종료 판정](06-settlement-redelivery-and-terminal-conflicts.md)
8. [내부 요청의 사용자도 다시 확인하기](07-verified-actor-boundary.md)
9. [내구성 있는 멱등 판정](08-durable-idempotency-verdict.md)
10. [재개 가능한 접수와 보상](09-resumable-placement-and-compensation.md)

마지막 두 기록은 별도 커밋의 선후가 아니라 `acd2216`에서 함께 들어온 변경을 요청
판정과 외부 효과 복구라는 두 판단 단위로 나눈 것이다.

현재 저장 위치와 서비스 사이의 책임은
[베팅 접수의 상태와 일관성 경계](../architecture/betting-consistency-boundaries.md)에
연결해 두었다.
