# Project Rules

- Android 화면은 `Route`와 stateless `Screen`, iOS 화면은 상태를 소유하는 `ObservableObject` 어댑터와 순수 SwiftUI `View`로 나눈다.
- 제품 화면을 새로 만들거나 수정하기 전에 Stitch 프로젝트 `7522919694799590979`에서 해당 화면의 최신 `(수정)`, `-A`, `-B`, `-S1`, `-S2` 디자인과 HTML을 다시 조회한다. 구현은 그 화면의 구조, 간격, 색상, 문구를 기준으로 하고 임의의 대체 UI를 만들지 않는다.
- 화면 구현 후 해당 플랫폼 실행 화면을 캡처해 기준 Stitch 화면과 비교한다. 비교하지 않은 플랫폼 화면은 Stitch 반영 완료로 표현하지 않는다.
- Android는 `MoaLogTheme`, iOS는 SwiftUI 네이티브 테마에서 Pretendard와 Warm Ledger 토큰을 사용한다.
- Compose와 SwiftUI 화면에서 API, DB, Repository를 직접 생성하거나 호출하지 않는다.
- 공통 상태 소유자는 Kotlin 타입과 `StateFlow`를 우선하며 플랫폼 객체에 의존하지 않는다.
- 일회성 effect와 지속되는 UI state를 구분한다.
- 사용자 문구는 한국어, 금액은 KRW 원 단위로 표시한다.
- API 입력은 서버에서 다시 검증하고 가구 단위 권한을 모든 데이터 접근에 적용한다.
- 서버 오류는 RFC 9457 계열 `application/problem+json` 형식으로 반환하고 내부 예외 정보를 노출하지 않는다.
- 운영 서버의 DB 접속 정보는 필수 환경변수로 주입하며 저장소 기본 비밀번호로 대체하지 않는다.
- DB 변경은 Flyway migration으로만 반영하고 JPA `ddl-auto`는 `validate`를 유지한다.
- 신규 라이브러리는 기존 모듈 경계로 해결할 수 없는 이유를 먼저 기록한다.
- 테스트나 컴파일을 실행하지 못한 항목은 성공으로 표현하지 않는다.
