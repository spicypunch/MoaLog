# Architecture

```text
composeApp (Android) -> app-shell (Jetpack Compose) -> shared (KMP)
iosApp (SwiftUI) ----------------------------------> SharedKit (shared KMP)
shared -> feature:* common state/domain/data -> core:model/database/network
server -> Spring MVC -> Service -> Spring Data JPA -> PostgreSQL
```

`shared`는 Koin 공통 그래프, 플랫폼별 Room 구성과 Swift가 사용할 타입 지정 접근점을 제공한다. `composeApp`은 Android 진입점이며 `app-shell`이 Android Compose 화면과 Navigation 3 탐색을 소유한다. `iosApp`은 SwiftUI 화면, `NavigationStack`과 플랫폼 생명주기를 소유한다.

기능 모델·저장소·상태 소유자는 `feature:*`의 `commonMain`에 두고 양쪽 UI가 같은 동작을 사용한다. Android Compose 화면은 `app-shell`의 Android 소스에 두며 SwiftUI 화면은 `iosApp`에 둔다. UI는 Repository나 데이터베이스를 직접 호출하지 않는다.

`core:network`는 Ktor Client와 직렬화, `core:database`는 Room KMP 로컬 저장 경계를 소유한다. 모바일과 서버는 HTTP/JSON 계약으로 연결한다. JPA Entity와 Room Entity는 API 경계를 넘어 공유하지 않는다.

서버는 기능 패키지에서 `web -> application -> domain` 방향을 유지하고 persistence 구현은 infrastructure 경계에 둔다. Kotlin Spring Boot 선택은 `docs/adr/0001-kotlin-spring-boot-backend.md`에 기록한다.
