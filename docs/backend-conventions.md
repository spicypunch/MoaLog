# Backend Conventions

## 패키지와 호출 방향

기능 패키지는 제품 경계별로 만들고 다음 호출 방향을 유지한다.

```text
web/controller -> application/service -> domain
                              |
                              v
                    infrastructure/persistence
```

- Controller는 HTTP 입력 검증, DTO 변환, 상태 코드만 담당한다.
- Application service는 유스케이스와 트랜잭션 경계를 담당한다.
- Domain은 Spring MVC, JPA, JSON 타입을 알지 않는다.
- JPA Entity와 Repository는 persistence 경계에 둔다.
- Controller가 JPA Repository를 직접 호출하지 않는다.
- API DTO, domain model, JPA Entity를 같은 타입으로 재사용하지 않는다.

공통 기술 코드는 `config`, `web`에 둔다. 제품 기능이 생기기 전에는 추상 계층이나 빈 패키지를 미리 만들지 않는다.

## HTTP와 오류

- API 요청 본문은 Bean Validation으로 검증한다.
- 오류 응답은 `application/problem+json`과 Spring `ProblemDetail`을 사용한다.
- 검증 오류의 `type`은 `urn:moalog:problem:validation`이며 `errors`에 필드와 메시지를 제공한다.
- 예상 가능한 제품 오류는 안정적인 문제 유형을 정의하고 내부 예외·SQL·비밀값을 응답에 노출하지 않는다.

## 보안

- 서버는 세션을 만들지 않는 HTTP API로 운영한다.
- 현재 `GET /api/system/health`, `GET /api/system/info`, Actuator health probe와 info만 공개한다.
- 공개 경로는 HTTP 메서드와 전체 경로를 명시한다. 와일드카드로 미래 엔드포인트까지 공개하지 않는다.
- 제품 인증 방식이 결정되기 전까지 나머지 요청은 모두 거부한다.
- CSRF는 쿠키 세션을 사용하지 않는 무상태 API 기준으로 비활성화했다. 향후 쿠키 인증을 채택하면 이 결정을 다시 검토한다.
- 가구 데이터는 인증된 사용자와 가구 구성원 관계를 서버에서 함께 확인한다.
- 가구 구성원 또는 초대 이력이 있는 사용자는 직접 hard delete하지 않는다. 향후 계정 삭제 유스케이스에서 소유권 이전 또는 가구 삭제와 이력 익명화를 하나의 트랜잭션으로 처리하기 전까지 DB 외래 키가 삭제를 차단한다.

## 데이터베이스

- 애플리케이션 객체의 기본 PostgreSQL 스키마는 `moalog`다.
- JPA `ddl-auto`는 `validate`를 유지하고 모든 변경은 Flyway migration으로 적용한다.
- migration은 이미 배포된 파일을 고치지 않고 새 버전을 추가한다.
- 원 단위 금액은 정수형으로 저장하고 소수 부동소수점 타입을 사용하지 않는다.
