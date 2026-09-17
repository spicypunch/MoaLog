# ADR 0001: Kotlin Spring Boot 백엔드 채택

- 상태: 채택
- 결정일: 2026-09-08

## 배경

모아로그 서버 후보는 Kotlin 기반 Ktor Server와 Spring Boot였다. 두 선택 모두 제품 구현에는 충분하지만, 이 프로젝트는 부부 단위 권한, 인증, 관계형 데이터의 트랜잭션, 마이그레이션과 운영 관측성을 장기적으로 다뤄야 한다. 개발자의 국내 경력 활용성도 기술 선택의 명시적인 기준이다.

## 결정

서버는 Kotlin + Spring Boot MVC를 사용한다. 데이터 접근은 Spring Data JPA, 데이터베이스는 PostgreSQL, 스키마 변경은 Flyway로 관리한다. 모바일 앱의 Ktor Client는 서버 기술 선택과 무관하게 유지한다.

WebFlux, Ktor Server, Exposed는 현재 기준에 포함하지 않는다. 비동기 스트림이나 처리량 측정에서 구체적인 필요가 확인되면 새 ADR로 재검토한다.

## 결과

- Spring Security, Validation, Actuator, JPA 생태계의 표준 구성을 활용할 수 있다.
- 국내 Spring 기반 업무와 연결되는 서버 설계 경험을 남길 수 있다.
- Ktor Server보다 시작 시간과 메모리 사용량이 커질 수 있다.
- JPA 영속성 컨텍스트와 프록시 특성을 별도로 이해하고 테스트해야 한다.
- 기술 변경은 취향이 아니라 측정 결과와 제품 요구사항을 근거로 기록한다.
