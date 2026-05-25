# betting-service

`betting-service`는 베팅 슬립을 검증하고 접수 결과를 결정하며, 수락한 베팅의 상태를 관리합니다.

## 현재 구현 범위

- Java 17과 Spring Boot 기반 프로젝트 구성
- 단식, 복식, 시스템 베팅 집계와 상태 전이
- PostgreSQL 저장 모델과 Flyway 스키마
- 슬립 구조, 금액, 마켓 상태와 배당 변동 검증
- K-of-N 조합과 최대 지급액 계산
- risk와 wallet HTTP 호출 및 장애 변환
- 베팅 수락 이벤트를 저장하는 transactional outbox
- 검증, 한도 확인, 금액 차감, 수락을 잇는 접수 흐름
- 저장된 outbox 이벤트의 Kafka 전송
- 오래 남은 PENDING 베팅의 멱등 복구
- 베팅 접수와 조회를 위한 내부 REST API
- 정산 완료와 취소 이벤트의 멱등 반영
- gateway가 검증한 X-User-Id를 기준으로 요청 제한

## 빌드

```sh
(cd ../sportsbook-shared-protocol && ./mvnw install)
./mvnw verify
```
