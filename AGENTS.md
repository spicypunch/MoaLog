# MoaLog Agent Instructions

이 저장소는 모아로그의 KMP 공통 로직, Android Jetpack Compose 앱, iOS SwiftUI 앱과 Kotlin Spring Boot 서버를 함께 관리한다.

## 작업 전

- `docs/codex-preflight.md`를 먼저 읽는다.
- 화면 작업은 `docs/compose-screen-conventions.md`, 모듈 작업은 `docs/module-conventions.md`를 추가로 읽는다.
- 검증 명령은 `docs/validation.md`를 기준으로 한다.
- 기존 사용자 변경을 되돌리지 않고, 요청 범위 밖 제품 기능을 임의로 구현하지 않는다.

## 경계

- `shared`는 iOS용 `SharedKit` framework와 Android가 함께 쓰는 Koin 그래프를 제공한다.
- `composeApp`은 Android 앱, `app-shell`은 Android Compose UI와 내비게이션, `iosApp`은 SwiftUI UI와 내비게이션을 소유한다.
- `feature/*`의 모델·저장소·상태 소유자는 `commonMain`에 두고 Compose 화면을 넣지 않는다.
- 모바일 HTTP는 `core:network`의 Ktor Client를 사용한다.
- 모바일 로컬 DB는 `core:database`의 Room KMP를 사용한다.
- 서버는 `server`의 Spring MVC + Spring Data JPA를 사용한다. WebFlux와 Exposed를 섞지 않는다.
- 금액은 원 단위 `Long`, 회계 귀속월과 실제 발생일은 별도 필드로 모델링한다.

## 보안

- 키, 토큰, 운영 비밀번호, 서명 파일을 커밋하지 않는다.
- `local.properties.example`과 `.env.example`에는 예시 값만 둔다.
- 운영 통신은 HTTPS를 사용하고 인증 정보는 플랫폼 보안 저장소에 저장한다.
