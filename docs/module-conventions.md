# Module Conventions

- `:shared`: 양 플랫폼이 사용하는 KMP 기능 조립, Koin 그래프, 플랫폼별 Room 구성, Swift용 의존성 접근점
- `:composeApp`: Android 애플리케이션 진입점과 플랫폼 설정
- `:app-shell`: Android 테마 적용, Jetpack Compose 화면, 루트 내비게이션, feature 조립
- `:iosApp`: SwiftUI 화면, `NavigationStack`, iOS 생명주기와 `SharedKit` 연결
- `:core:model`: 플랫폼 중립 공통 모델
- `:core:designsystem`: Android 색상, Pretendard 글꼴, 공통 Jetpack Compose 컴포넌트
- `:core:network`: Ktor Client, JSON, API 공통 오류
- `:core:database`: Room KMP와 로컬 저장 경계
- `:feature:*`: 플랫폼 중립 기능별 상태·도메인·데이터. UI 코드는 두지 않는다.
- `:feature:setup`: 최초 가계부 설정과 입력 검증, 설정 저장 repository
- `:feature:records`: 귀속월 기준 변동 지출 목록·필터와 신규/수정/삭제
- `:server`: Spring MVC 서버

새 모듈은 두 기능 이상이 공유하거나 독립적인 제품 경계가 있을 때만 만든다. 공유 가능한 모바일 로직은 `commonMain`에 두고 플랫폼 UI는 `app-shell/src/androidMain`과 `iosApp`에 둔다. 서버 코드는 모바일 모듈에 의존하지 않는다.
