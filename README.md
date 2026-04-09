# betting-service

`betting-service`는 베팅 슬립을 검증하고 접수 결과를 결정하며, 수락한 베팅의 상태를 관리합니다.

## 현재 구현 범위

- Java 17과 Spring Boot 기반 프로젝트 구성
- 단식, 복식, 시스템 베팅 집계와 상태 전이
- PostgreSQL 저장 모델과 Flyway 스키마

## 빌드

```sh
(cd ../sportsbook-shared-protocol && ./mvnw install)
./mvnw verify
```
