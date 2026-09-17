# Compose Screen Conventions

이 규칙은 Android Jetpack Compose 화면에 적용한다. iOS는 같은 상태와 액션 의미를 사용하는 별도 SwiftUI `View`와 `ObservableObject` 어댑터를 구현한다.

```kotlin
@Composable fun FooRoute(...)
@Composable fun FooScreen(state: FooUiState, onAction: (FooAction) -> Unit)
```

- Route는 상태 연결과 effect/내비게이션을 처리한다.
- Screen은 immutable state와 callback만 받아 렌더링한다.
- 비즈니스 상태를 `remember`에 저장하지 않는다.
- Screen에는 Android `Context`나 `NavController`를 전달하지 않는다.
- 입력 필드는 지속되는 라벨과 오류 semantics를 제공한다.
- 터치 영역은 최소 48dp를 기준으로 하며 긴 한국어, 큰 글자, 키보드와 Android 시스템 영역을 확인한다.
- 디자인은 `MoaLogTheme`의 Warm Ledger 토큰을 사용한다.
- 화면 작업을 시작할 때 `docs/product/moalog-stitch-design-review.md`의 ID를 참고하되, Stitch MCP에서 최신 화면과 HTML을 다시 조회해 실제 ID와 제목을 확인한다.
- 구현이 끝나면 대표 상태를 Android 에뮬레이터에서 캡처하고 해당 Stitch 화면과 나란히 비교해 레이아웃, 스크롤, 시스템 영역과 큰 글자 동작을 확인한다. iOS 구현은 별도로 시뮬레이터에서 같은 항목을 확인한다.
